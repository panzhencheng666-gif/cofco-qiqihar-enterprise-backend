"""Offline tests for the single pinned r4g migration, not general legacy recovery."""
import json
import unittest
from unittest.mock import patch
from scripts.tests import test_imagery_resume as fixtures
worker, gdal = fixtures.worker, fixtures.gdal


@unittest.skipIf(gdal is None, 'real GDAL required')
class LegacyMigrationTest(unittest.TestCase):
    setUp = fixtures.ImageryResumeTest.setUp
    raster = fixtures.ImageryResumeTest.raster
    context = fixtures.ImageryResumeTest.context

    def fixture(self):
        self.assertTrue(callable(getattr(worker, '_migrate_r4g_scenes', None)), 'migration entry missing')
        r = self.raster()
        self.ctx = self.context()
        (self.failed / 'work' / 'cutline-union.geojson').write_bytes(self.aoi.read_bytes())
        self.plan_path = self.root / 'plan.json'; self.plan_path.write_text(json.dumps(self.plan))
        self.old_worker = self.root / 'old.py'; self.old_worker.write_text('# original fixture')
        self.report_path = self.root / 'audit.json'
        row = {'scene': self.ctx['scenes'][0], 'bytes': r.stat().st_size,
               'mtimeNs': r.stat().st_mtime_ns, 'mtimeWithinOriginalRun': True,
               'sha256': worker._file_sha256(r), 'completeRecordExists': False,
               'fullRasterPass': True, 'unchangedAfterRead': True,
               'rasterCheck': worker._validate_resume_raster(r, self.ctx['scenes'][0]['grid'], 60)}
        self.report = {'schema': 1, 'originalWorkerSha256': worker._file_sha256(self.old_worker),
                       'checkerWorkerSha256': 'fixture-checker', 'planFileSha256': worker._file_sha256(self.plan_path),
                       'aoiSha256': self.ctx['aoiSha256'], 'cutlineSha256': self.ctx['cutlineSha256'],
                       'orderAgreesWithOriginalWorker': True, 'nativeRecordsAbsent': True,
                       'all18Readable': True, 'migrationAuthorized': False, 'scenes': [row]}
        self.report_path.write_text(json.dumps(self.report))
        self.pins = {'failedName': self.failed.name, 'reportSha256': worker._file_sha256(self.report_path),
                     'originalWorkerSha256': self.report['originalWorkerSha256'],
                     'checkerWorkerSha256': 'fixture-checker', 'planFileSha256': self.report['planFileSha256'],
                     'aoiSha256': self.ctx['aoiSha256'], 'cutlineSha256': self.ctx['cutlineSha256'],
                     'indices': [0], 'version': self.ctx['version'],
                     'processingSha256': worker._json_sha256(self.ctx['processing'])}
        return r

    def migrate(self):
        with patch.object(worker, '_R4G_MIGRATION', self.pins), patch.object(worker, '_r4g_unit_evidence', return_value={'unit': 'offline-fixture'}):
            return worker._migrate_r4g_scenes(self.config, self.failed, self.destination, self.ctx,
                                            self.report_path, self.old_worker, self.plan_path)

    def test_copy_preserves_quarantine_and_records_distinct_origin(self):
        r = self.fixture(); before = r.read_bytes()
        copied = self.migrate()
        self.assertEqual({0}, set(copied))
        self.assertEqual(before, copied[0].read_bytes())
        self.assertNotEqual(r.stat().st_ino, copied[0].stat().st_ino)
        self.assertTrue((self.failed / 'FAILED').exists())
        self.assertFalse((r.parent / 'complete.json').exists())
        self.assertFalse((self.failed / 'build-context.json').exists())
        receipt = json.loads((copied[0].parent / 'complete.json').read_text())
        self.assertEqual('independent-legacy-audit-copy', receipt['origin']['kind'])
        self.assertEqual(self.pins['reportSha256'], receipt['origin']['reportSha256'])
        self.assertTrue((self.destination.parent / 'legacy-migration.json').exists())

    def test_changed_report_is_rejected(self):
        self.fixture(); self.report_path.write_text(self.report_path.read_text() + ' ')
        with self.assertRaisesRegex(worker.ReleaseValidationError, 'report digest'): self.migrate()
        self.assertEqual([], list(self.destination.iterdir()))

    def test_source_digest_or_symlink_is_rejected(self):
        r = self.fixture(); r.write_bytes(r.read_bytes() + b'changed')
        with self.assertRaises(worker.ReleaseValidationError): self.migrate()
        r.unlink(); r.symlink_to(self.aoi)
        with self.assertRaises(worker.ReleaseValidationError): self.migrate()

    def test_identity_and_old_worker_drift_are_rejected(self):
        self.fixture(); self.ctx['scenes'][0]['observedAt'] = '2026-08-01'
        with self.assertRaises(worker.ReleaseValidationError): self.migrate()
        self.ctx = self.context(); self.old_worker.write_text('changed')
        with self.assertRaises(worker.ReleaseValidationError): self.migrate()

    def test_truncated_source_even_with_matching_digest_is_rejected(self):
        r = self.fixture()
        with r.open('r+b') as f: f.truncate(r.stat().st_size - 80)
        row = self.report['scenes'][0]; row.update(sha256=worker._file_sha256(r), bytes=r.stat().st_size, mtimeNs=r.stat().st_mtime_ns)
        self.report_path.write_text(json.dumps(self.report)); self.pins['reportSha256'] = worker._file_sha256(self.report_path)
        with self.assertRaises(worker.ReleaseValidationError): self.migrate()

    def test_digest_change_during_copy_is_rejected(self):
        r = self.fixture(); original = worker.shutil.copyfile
        def corrupt(source, dest):
            result = original(source, dest)
            with r.open('ab') as f: f.write(b'changed during copy')
            return result
        with patch.object(worker.shutil, 'copyfile', side_effect=corrupt):
            with self.assertRaises(worker.ReleaseValidationError): self.migrate()
        self.assertFalse((self.destination / 'scene-000' / 'complete.json').exists())

    def test_cli_cannot_migrate_without_build_only_and_plan(self):
        with self.assertRaises(ValueError): worker.main(['--root', str(self.root), '--migrate-r4g-audit', str(self.root / 'audit.json')])

    def test_processing_contract_drift_is_rejected(self):
        self.fixture(); self.ctx['processing']['rgbResampling'] = 'near'
        with self.assertRaises(worker.ReleaseValidationError): self.migrate()

    def test_native_resume_preserves_migration_origin(self):
        self.fixture(); copied = self.migrate()
        stage = self.destination.parent
        (stage / 'FAILED').write_text('later candidate failed')
        (stage / 'build-context.json').write_text(json.dumps(self.ctx))
        (stage / 'source-plan.json').write_text(json.dumps(self.plan))
        (self.destination / 'cutline-union.geojson').write_bytes(self.aoi.read_bytes())
        failed = self.root / '.failed-after-migration'; stage.rename(failed)
        target = self.root / '.new-resume' / 'work'; target.mkdir(parents=True)
        resumed = worker._restore_scenes(self.config, failed, target, self.ctx)
        record = json.loads((resumed[0].parent / 'complete.json').read_text())
        self.assertEqual('independent-legacy-audit-copy', record['origin']['kind'])

    def test_unit_evidence_rejects_running_or_wrong_producer(self):
        output = 'ActiveState=active\nResult=success\nExecMainStatus=0\nExecStart=unrelated\n'
        with patch.object(worker.subprocess, 'check_output', return_value=output):
            with self.assertRaises(worker.ReleaseValidationError): worker._r4g_unit_evidence()

    def test_build_migration_skips_verified_scene_and_retains_on_later_failure(self):
        from datetime import datetime, timezone
        self.fixture(); real_migrate = worker._migrate_r4g_scenes
        def migrate(config, failed, work, context, report, original, plan):
            return real_migrate(config, failed, work, context, report, self.old_worker, self.plan_path)
        def dissolve(config, destination):
            worker.shutil.copyfile(config.aoi, destination); return destination
        with patch.object(worker, '_R4G_MIGRATION', self.pins), patch.object(worker, '_r4g_unit_evidence', return_value={'unit':'offline'}), patch.object(worker, '_migrate_r4g_scenes', side_effect=migrate), patch.object(worker, '_dissolve_historical_cutline', side_effect=dissolve), patch.object(worker, '_build_scene') as build, patch.object(worker, '_run'), patch.object(worker, '_build_web_tiles', side_effect=RuntimeError('stop after migration')):
            with self.assertRaisesRegex(RuntimeError, 'stop after migration'):
                worker.build_release(self.config, self.window, [self.candidate], datetime.now(timezone.utc), source_plan=self.plan, migrate_r4g_audit=self.report_path)
            build.assert_not_called()
        self.assertEqual(1, len(list(self.root.glob('.failed-2026-09-r4-*/legacy-migration.json'))))
