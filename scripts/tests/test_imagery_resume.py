"""Focused offline checks for candidate-only scene recovery."""
import hashlib
import json
import os
import sys
import tempfile
import unittest
import subprocess
from datetime import date
from pathlib import Path
from unittest.mock import patch

try:
    from osgeo import gdal, osr
    import numpy as np
except ImportError:
    gdal = None
import shutil
from datetime import datetime, timezone
if gdal is not None:
    gdal.UseExceptions()
from scripts import weekly_imagery_sync as worker


@unittest.skipIf(gdal is None or not shutil.which("gdalinfo"), "offline raster recovery checks require GDAL and NumPy")
class ImageryResumeTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.aoi = self.root / 'aoi.json'
        self.aoi.write_text(json.dumps({'type': 'Polygon', 'coordinates': [[[0, 0], [.001, 0], [.001, .001], [0, .001], [0, 0]]]}))
        self.config = worker.SyncConfig(self.root, self.aoi, '', '', (), 30, 5, 14, 5, 60, '', 0, 4)
        self.candidate = worker.Candidate('S2-test', '2026-09-20T00:00:00Z', 0, True, {'visual': 'https://example.test/rgb.tif'}, '31NAA', (0, 0, .001, .001))
        self.window = worker.WeekWindow('2026-09-r4', date(2026, 9, 1), date(2026, 9, 20))
        self.plan = {'version': '2026-09-r4'}
        self.failed = self.root / '.failed-test'
        (self.failed / 'work' / 'scene-000').mkdir(parents=True)
        (self.failed / 'FAILED').write_text('not publishable')
        self.destination = self.root / '.staging-new' / 'work'
        self.destination.mkdir(parents=True)
        self.env = patch.dict(os.environ, {'QIQIHAR_IMAGERY_GDAL_PYTHON': sys.executable})
        self.env.start()
        self.addCleanup(self.env.stop)

    def raster(self, path=None, alpha=255, hidden_rgb=False, shifted=False):
        path = path or self.failed / 'work' / 'scene-000' / 'masked.tif'
        ds = gdal.GetDriverByName('GTiff').Create(str(path), 12, 12, 4, gdal.GDT_Byte, options=['TILED=YES', 'COMPRESS=DEFLATE'])
        ds.SetGeoTransform((10 if shifted else 0, 10, 0, 120, 0, -10))
        sr = osr.SpatialReference(); sr.ImportFromEPSG(3857)
        ds.SetProjection(sr.ExportToWkt())
        for band, value, color in zip(range(1, 5), [11 if alpha or hidden_rgb else 0, 22 if alpha else 0, 33 if alpha else 0, alpha], [gdal.GCI_RedBand, gdal.GCI_GreenBand, gdal.GCI_BlueBand, gdal.GCI_AlphaBand]):
            b = ds.GetRasterBand(band); b.WriteArray(np.full((12, 12), value, dtype=np.uint8)); b.SetColorInterpretation(color)
        ds = None
        return path

    def context(self):
        return worker._scene_resume_context(self.config, self.window, [self.candidate], self.plan)

    def completed(self):
        context = self.context()
        (self.failed / 'build-context.json').write_text(json.dumps(context))
        (self.failed / 'source-plan.json').write_text(json.dumps(self.plan))
        (self.failed / 'work' / 'cutline-union.geojson').write_bytes(self.aoi.read_bytes())
        raster = self.raster()
        worker._write_scene_completion(raster, context, 0)
        return context, raster

    def test_recovery_api_exists(self):
        self.assertTrue(callable(getattr(worker, '_restore_scenes', None)), 'strict scene recovery is not implemented')

    def test_legacy_without_identity_record_is_rejected_before_copy(self):
        self.raster()
        with self.assertRaisesRegex(worker.ReleaseValidationError, 'build context'):
            worker._restore_scenes(self.config, self.failed, self.destination, self.context())
        self.assertEqual([], list(self.destination.iterdir()))

    def test_valid_completed_scene_is_copied_and_quarantine_is_unchanged(self):
        context, raster = self.completed()
        before = raster.read_bytes()
        restored = worker._restore_scenes(self.config, self.failed, self.destination, context)
        self.assertEqual({0}, set(restored))
        self.assertEqual(before, restored[0].read_bytes())
        self.assertEqual(before, raster.read_bytes())
        self.assertNotEqual(raster.stat().st_ino, restored[0].stat().st_ino)
        self.assertTrue((self.failed / 'FAILED').exists())
        report = json.loads((self.destination.parent / 'resume-audit.json').read_text())
        self.assertEqual(hashlib.sha256(before).hexdigest(), report['scenes'][0]['sha256'])

    def test_context_product_date_or_cutline_drift_rejects_recovery(self):
        context, _ = self.completed()
        for key in ('sourcePlanSha256', 'cutlineSha256', 'workerSha256'):
            with self.subTest(key=key):
                changed = dict(context); changed[key] = '0' * 64
                with self.assertRaises(worker.ReleaseValidationError):
                    worker._restore_scenes(self.config, self.failed, self.destination, changed)
        changed = json.loads(json.dumps(context)); changed['scenes'][0]['observedAt'] = '2026-08-01T00:00:00Z'
        with self.assertRaises(worker.ReleaseValidationError):
            worker._restore_scenes(self.config, self.failed, self.destination, changed)

    def test_file_digest_drift_and_symlink_are_rejected(self):
        context, raster = self.completed()
        raster.write_bytes(raster.read_bytes() + b'changed')
        with self.assertRaisesRegex(worker.ReleaseValidationError, 'digest'):
            worker._restore_scenes(self.config, self.failed, self.destination, context)
        raster.unlink(); raster.symlink_to(self.aoi)
        with self.assertRaisesRegex(worker.ReleaseValidationError, 'symlink'):
            worker._restore_scenes(self.config, self.failed, self.destination, context)

    def test_all_pixels_are_checked_for_alpha_hidden_rgb_and_grid(self):
        for args in ({'alpha': 128}, {'alpha': 0, 'hidden_rgb': True}, {'shifted': True}, {'alpha': 0}):
            with self.subTest(args=args):
                raster = self.raster(**args)
                with self.assertRaises(worker.ReleaseValidationError):
                    worker._validate_resume_raster(raster, self.context()['scenes'][0]['grid'], 60)
        raster = self.raster()
        with raster.open('r+b') as stream:
            stream.truncate(raster.stat().st_size - 80)
        with self.assertRaises(worker.ReleaseValidationError):
            worker._validate_resume_raster(raster, self.context()['scenes'][0]['grid'], 60)

    def test_validator_preserves_bounded_failure_reason(self):
        raster = self.raster(alpha=128)
        with self.assertRaisesRegex(worker.ReleaseValidationError, 'non-binary alpha'):
            worker._validate_resume_raster(raster, self.context()['scenes'][0]['grid'], 60)

    def test_missing_completion_never_triggers_blind_full_rebuild(self):
        context, raster = self.completed()
        (raster.parent / 'complete.json').unlink()
        with self.assertRaisesRegex(worker.ReleaseValidationError, 'no verified scenes'):
            worker._restore_scenes(self.config, self.failed, self.destination, context)

    def test_python_gdal_must_match_recorded_cli_runtime(self):
        context, _ = self.completed()
        with patch.object(worker, '_validate_resume_raster', return_value={'gdalVersion': 'different GDAL'}):
            with self.assertRaisesRegex(worker.ReleaseValidationError, 'GDAL runtime mismatch'):
                worker._restore_scenes(self.config, self.failed, self.destination, context)

    def test_context_binds_gdal_runtime(self):
        self.assertEqual(subprocess.check_output(['gdalinfo', '--version'], text=True).strip(), self.context().get('gdalVersion'))

    def test_expected_grid_matches_gdal_warp_target_alignment(self):
        bounds = (125.927835, 52.175494, 127.634167, 53.211984)
        left, bottom, right, top = bounds
        self.aoi.write_text(json.dumps({'type': 'Polygon', 'coordinates': [[[left, bottom], [right, bottom], [right, top], [left, top], [left, bottom]]]}))
        self.candidate = worker.replace(self.candidate, bounds=bounds)
        source = self.root / 'source.tif'
        ds = gdal.GetDriverByName('GTiff').Create(str(source), 2, 2, 1, gdal.GDT_Byte)
        ds.SetGeoTransform((left, (right-left)/2, 0, top, 0, -(top-bottom)/2))
        sr = osr.SpatialReference(); sr.ImportFromEPSG(4326); ds.SetProjection(sr.ExportToWkt()); ds = None
        warped = gdal.Warp(str(self.root / 'grid.vrt'), str(source), format='VRT', dstSRS='EPSG:3857', outputBounds=bounds, outputBoundsSRS='EPSG:4326', xRes=10, yRes=10, targetAlignedPixels=True)
        expected = self.context()['scenes'][0]['grid']
        self.assertEqual(expected['size'], [warped.RasterXSize, warped.RasterYSize])
        self.assertEqual(expected['transform'], list(warped.GetGeoTransform()))

    def test_build_reuses_validated_scene_without_rebuilding_it(self):
        self.completed()
        def dissolve(config, destination):
            shutil.copyfile(config.aoi, destination)
            return destination
        with patch.object(worker, '_check_gdal'), patch.object(worker, '_dissolve_historical_cutline', side_effect=dissolve), patch.object(worker, '_build_scene') as build, patch.object(worker, '_log_alpha_coverage'), patch.object(worker, '_run'), patch.object(worker, '_build_web_tiles', side_effect=RuntimeError('stop after recovery')):
            with self.assertRaisesRegex(RuntimeError, 'stop after recovery'):
                worker.build_release(self.config, self.window, [self.candidate], datetime.now(timezone.utc), source_plan=self.plan, resume_failed=self.failed)
            build.assert_not_called()
        copies = list(self.root.glob('.failed-2026-09-r4-*/work/scene-000/masked.tif'))
        self.assertEqual(1, len(copies))
        self.assertTrue((copies[0].parent / 'complete.json').exists())

    def test_legacy_build_stops_before_any_remote_scene_read(self):
        self.raster()
        def dissolve(config, destination):
            shutil.copyfile(config.aoi, destination)
            return destination
        with patch.object(worker, '_check_gdal'), patch.object(worker, '_dissolve_historical_cutline', side_effect=dissolve), patch.object(worker, '_build_scene') as build:
            with self.assertRaisesRegex(worker.ReleaseValidationError, 'build context'):
                worker.build_release(self.config, self.window, [self.candidate], datetime.now(timezone.utc), source_plan=self.plan, resume_failed=self.failed)
            build.assert_not_called()

    def test_cli_recovery_requires_candidate_build_only(self):
        with patch.object(worker, 'search_candidates') as search:
            with self.assertRaisesRegex(ValueError, 'historical.*build-only'):
                worker.main(['--root', str(self.root), '--aoi', str(self.aoi), '--resume-failed', str(self.failed)])
            search.assert_not_called()
