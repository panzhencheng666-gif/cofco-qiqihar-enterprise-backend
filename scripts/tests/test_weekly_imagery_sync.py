import base64
import hashlib
import io
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import call, patch
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPOSITORY_ROOT))

from scripts.weekly_imagery_sync import (  # noqa: E402
    Candidate,
    ReleaseValidationError,
    SyncConfig,
    WeekWindow,
    build_release,
    _build_web_tiles,
    _legacy_webp_band_args,
    load_historical_source_plan,
    _mgrs_grid_code,
    _PLANETARY_TOKEN_CACHE,
    _read_json_response,
    _scene_worker_count,
    _vsicurl,
    _xyz_webp_relative,
    complete_week,
    complete_month,
    parse_candidates,
    publish_release,
    rank_candidates,
    search_candidates,
    require_free_space,
    retain_releases,
    select_grid_candidates,
    validate_release,
)
from scripts import weekly_imagery_sync as worker  # noqa: E402


class WeeklyImagerySyncTest(unittest.TestCase):
    def test_r4i_seed_cli_is_exclusive_and_reports_unpublishable_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            aoi = root / "aoi.geojson"
            aoi.write_text("{}")
            baseline = root / worker.R4I_BASE_STAGE_NAME
            base = root / "base.json"
            delta = root / "delta.json"
            args = ["--root", str(root), "--aoi", str(aoi), "--now", "2026-09-26T00:00:00Z",
                    "--revision", "4", "--build-only", "--historical-source-plan", str(base),
                    "--source-delta", str(delta), "--seed-r4i-repair", str(baseline)]
            seed = root / ".failed-2026-09-r4-repair-seed-test"
            with patch.object(worker, "seed_r4i_repair", return_value=seed) as create, \
                    patch.object(worker, "build_release") as build, patch("sys.stdout", new_callable=io.StringIO) as output:
                self.assertEqual(0, worker.main(args))
                create.assert_called_once_with(root.resolve(), baseline, base, delta, worker._config(
                    SimpleNamespace(root=root, aoi=aoi)
                ).minimum_free_bytes)
                build.assert_not_called()
                self.assertIn("unpublishable r4i repair seed retained", output.getvalue())
            with self.assertRaisesRegex(ValueError, "exclusive"):
                worker.main(args + ["--force"])
            with self.assertRaisesRegex(ValueError, "exclusive"):
                worker.main(args + ["--resume-failed", str(root / "failed")])
            render_args = ["--root", str(root), "--aoi", str(aoi),
                           "--now", "2026-09-26T00:00:00Z", "--revision", "4", "--build-only",
                           "--historical-source-plan", str(base), "--source-delta", str(delta),
                           "--render-r4i-repair-seed", str(seed), "--repair-group", "dax"]
            with patch.object(worker, "render_r4i_repair_group", return_value=seed / "repair-work/dax") as render, \
                    patch.object(worker, "build_release") as build:
                self.assertEqual(0, worker.main(render_args))
                render.assert_called_once()
                build.assert_not_called()
            with self.assertRaisesRegex(ValueError, "exclusive"):
                worker.main(render_args + ["--force"])

    def test_r4i_repair_seed_is_isolated_and_never_publishable(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            staging = root / worker.R4I_BASE_STAGE_NAME
            staging.mkdir()
            (staging / "manifest.sha256").write_text("pinned manifest\n")
            (staging / "tile.webp").write_bytes(b"original")
            (root / "plan.json").write_text("{}")
            digest = worker._file_sha256(staging / "manifest.sha256")
            with patch.object(worker, "R4I_MANIFEST_SHA256", digest), patch.object(
                worker, "validate_r4i_repair_baseline"
            ) as baseline_guard, patch.object(worker, "validate_r4j_source_delta", return_value={"delta": {}}), \
                    patch.object(worker, "_r4i_repair_plan", return_value={"status": "UNPUBLISHABLE_TILE_REPAIR_PLAN_ONLY"}):
                seed = worker.seed_r4i_repair(root, staging, root / "plan.json", root / "delta.json", 0)
            baseline_guard.assert_called_once_with(staging, digest, root / "plan.json", root / "delta.json")
            self.assertTrue(seed.name.startswith(".failed-2026-09-r4-repair-seed-"))
            self.assertTrue((seed / "FAILED").is_file())
            self.assertEqual("UNPUBLISHABLE_TILE_REPAIR_PLAN_ONLY", json.loads(
                (seed / "repair-plan.json").read_text())["status"])
            self.assertNotEqual((staging / "tile.webp").stat().st_ino, (seed / "tile.webp").stat().st_ino)
            (seed / "tile.webp").write_bytes(b"changed")
            self.assertEqual(b"original", (staging / "tile.webp").read_bytes())
            with self.assertRaisesRegex(ReleaseValidationError, "failed candidate"):
                validate_release(seed)

    def test_r4i_repair_plan_binds_only_six_holes_and_their_ancestors(self):
        product_uri = "S2B_MSIL2A_20260914T023529_N0512_R089_T51UXQ_20260914T044641.SAFE"
        prefix = f"https://sentinel2l2a01.blob.core.windows.net/sentinel2-l2/{product_uri}/"
        hulun = {
            "id": "S2B_51UXQ_20260914_0_L2A",
            "bbox": [124.357925, 48.629831, 125.904558, 49.641696],
            "properties": {"datetime": "2026-09-14T02:44:02.573000Z",
                           "source:provider": "planetary-computer", "s2:product_uri": product_uri},
            "assets": {"visual": {"gsd": 10, "href": prefix + "T51UXQ_20260914T023529_TCI_10m.tif"},
                       "scl": {"gsd": 20, "href": prefix + "T51UXQ_20260914T023529_SCL_20m.tif"}},
        }
        delta = {"candidateId": "S2C_51UYT_20260909_0_L2A",
                 "bbox": [125.8702158, 51.2789252, 127.5399797, 52.3140378],
                 "acquiredAt": "2026-09-09T02:35:31.025000Z"}
        plan = worker._r4i_repair_plan({"features": [hulun]}, delta)
        self.assertEqual("UNPUBLISHABLE_TILE_REPAIR_PLAN_ONLY", plan["status"])
        self.assertEqual(6, len(plan["tiles"]))
        self.assertEqual(5, sum(tile["productId"] == delta["candidateId"] for tile in plan["tiles"]))
        self.assertTrue(all(tile["visualResolutionMeters"] == 10 and
                            tile["sclResolutionMeters"] == 20 and
                            len(tile["ancestorPaths"]) == 9 for tile in plan["tiles"]))
        self.assertEqual("13/6934/2812.webp", plan["tiles"][-1]["ancestorPaths"][0])
        with self.assertRaisesRegex(ReleaseValidationError, "misses tile"):
            worker._r4i_repair_plan({"features": [hulun]}, {**delta, "bbox": [0, 0, 1, 1]})

    def test_r4i_render_stays_in_quarantine_and_refuses_reuse(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            aoi = root / "aoi.geojson"
            aoi.write_text("{}")
            base = root / "base.json"
            base.write_text("{}")
            delta = root / "delta.json"
            seed = root / ".failed-2026-09-r4-repair-seed-test"
            seed.mkdir()
            (seed / "FAILED").write_text("never publish")
            (seed / "manifest.sha256").write_text("pinned")
            (seed / "source-plan.json").write_text("{}")
            selected = [{"x": x, "y": y, "productId": "S2C_51UYT_20260909_0_L2A",
                         "observedAt": "2026-09-09T02:35:31.025000Z",
                         "bbox4326": [126.0, 51.4, 126.1, 51.5]}
                        for x, y in ((13928, 5453), (13928, 5454), (13929, 5454),
                                     (13930, 5454), (13931, 5454))]
            plan = {"tiles": selected}
            (seed / "repair-plan.json").write_text(json.dumps(plan))
            config = worker._config(SimpleNamespace(root=root, aoi=aoi))
            candidate = Candidate("S2C_51UYT_20260909_0_L2A", selected[0]["observedAt"],
                                  3.6, True, {})

            def generate(_masked, destination, _config):
                for tile in selected:
                    path = destination / "14" / str(tile["x"]) / f'{tile["y"]}.webp'
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(b"R" * 400)

            with patch.object(worker, "R4I_MANIFEST_SHA256", worker._file_sha256(seed / "manifest.sha256")), \
                    patch.object(worker, "validate_r4j_source_delta", return_value={"delta": {}}), \
                    patch.object(worker, "_r4i_repair_plan", return_value=plan), \
                    patch.object(worker, "r4j_repair_candidate", return_value=candidate), \
                    patch.object(worker, "require_free_space"), \
                    patch.object(worker, "_dissolve_historical_cutline", return_value=aoi), \
                    patch.object(worker, "_build_scene", return_value=root / "masked.tif") as build, \
                    patch.object(worker, "_build_web_tiles", side_effect=generate), \
                    patch.object(worker, "_discard_tiny_webp_tiles", return_value=0):
                work = worker.render_r4i_repair_group(config, seed, base, delta, "dax")
                self.assertEqual(seed / "repair-work/dax", work)
                self.assertEqual(5, len(json.loads((work / "tile-render-complete.json").read_text())["tiles"]))
                self.assertEqual((126.0, 51.4, 126.1, 51.5), build.call_args.kwargs["bounds_override"])
                with self.assertRaises(FileExistsError):
                    worker.render_r4i_repair_group(config, seed, base, delta, "dax")
            self.assertTrue((seed / "FAILED").is_file())
            self.assertFalse((seed / "tiles").exists())

    def test_repair_scene_warp_is_clipped_to_tile_window(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            aoi = root / "aoi.geojson"
            aoi.write_text(json.dumps({"type": "Polygon", "coordinates": [[
                [125, 51], [127, 51], [127, 52], [125, 52], [125, 51],
            ]]}))
            config = worker._config(SimpleNamespace(root=root, aoi=aoi))
            candidate = Candidate("test", "2026-09-09T02:35:31Z", 3, True,
                                  {"visual": "https://sentinel2l2a01.blob.core.windows.net/visual.tif",
                                   "scl": "https://sentinel2l2a01.blob.core.windows.net/scl.tif"},
                                  bounds=(125.5, 51.2, 126.5, 51.8))

            def warp(_config, _url, _options, _resampling, destination):
                destination.write_bytes(b"warped")

            def mask(_rgb, _scl, scene, _timeout):
                output = scene / "masked.tif"
                output.write_bytes(b"masked")
                return output

            with patch.object(worker, "_run_remote_warp", side_effect=warp) as remote, \
                    patch.object(worker, "_build_masked_scene", side_effect=mask):
                output = worker._build_scene(config, candidate, root, 0,
                                             bounds_override=(126.0, 51.4, 126.1, 51.5))
            self.assertTrue(output.is_file())
            options = remote.call_args_list[0].args[2]
            self.assertEqual(["126.0", "51.4", "126.1", "51.5"],
                             options[options.index("-te") + 1:options.index("-te") + 5])
            with self.assertRaisesRegex(ReleaseValidationError, "does not intersect"):
                worker._build_scene(config, candidate, root, 1,
                                    bounds_override=(0, 0, 1, 1))

    def test_r4i_repair_baseline_requires_exact_inventory_and_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            staging = root / worker.R4I_BASE_STAGE_NAME
            staging.mkdir()
            (staging / "tiles").mkdir()
            (staging / "tiles" / "tile.webp").write_bytes(b"webp fixture")
            products = [f"scene-{i}" for i in range(189)]
            source = {"features": [{"id": item} for item in products]}
            base = root / "base.json"
            base.write_text(json.dumps(source))
            (staging / "source-plan.json").write_text(json.dumps(source))
            (staging / "metadata.json").write_text(json.dumps({
                "version": "2026-09-r4", "provider": "Sentinel-2", "acquisitionFrom": "2026-09-09",
                "acquisitionTo": "2026-09-20", "syncedAt": "2026-09-26T00:00:00Z",
                "spatialResolutionMeters": 10, "cloudCoveragePercent": 1, "status": "CURRENT",
                "sourceProductIds": products,
            }))
            worker._write_manifest(staging)
            manifest_sha = worker._file_sha256(staging / "manifest.sha256")
            with patch.object(worker, "validate_r4j_source_delta") as delta_guard:
                worker.validate_r4i_repair_baseline(staging, manifest_sha, base, root / "delta.json")
                delta_guard.assert_called_once()
                (staging / "unlisted.txt").write_text("unexpected")
                with self.assertRaisesRegex(ReleaseValidationError, "inventory"):
                    worker.validate_r4i_repair_baseline(staging, manifest_sha, base, root / "delta.json")
                (staging / "unlisted.txt").unlink()
                (staging / "link").symlink_to(staging / "metadata.json")
                with self.assertRaisesRegex(ReleaseValidationError, "symlink"):
                    worker.validate_r4i_repair_baseline(staging, manifest_sha, base, root / "delta.json")
                with self.assertRaisesRegex(ReleaseValidationError, "manifest SHA mismatch"):
                    worker.validate_r4i_repair_baseline(staging, "0" * 64, base, root / "delta.json")

    def test_tiling_uses_writable_sibling_temp_without_global_environment_change(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with patch.dict(os.environ, {"TMPDIR": "/unwritable-default"}), patch.object(worker.subprocess, "run") as run:
                worker._run(["gdal2tiles.py", str(root / "mosaic.tif"), str(root / "tiles")], 5)
                selected = Path(run.call_args.kwargs["env"]["TMPDIR"])
                self.assertEqual(root.resolve() / ".gdal-tmp", selected)
                self.assertTrue(selected.is_dir())
                self.assertEqual("/unwritable-default", os.environ["TMPDIR"])

    def test_candidate_temp_preflight_fails_before_scene_build_and_preserves_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config = SyncConfig(root, root / "aoi", "", "", (), 30, 5, 5, 5, 60, "", 0, 4)
            with patch.object(worker, "_check_gdal"), patch.object(worker, "_dissolve_historical_cutline", side_effect=RuntimeError("reached scenes")) as dissolve, patch.object(worker.tempfile, "TemporaryFile", side_effect=PermissionError("temp unwritable")):
                with self.assertRaisesRegex(PermissionError, "temp unwritable"):
                    build_release(config, WeekWindow("test", datetime.now().date(), datetime.now().date()), [], datetime.now(timezone.utc), source_plan={})
                dissolve.assert_not_called()
            failed = list(root.glob(".failed-*"))
            self.assertEqual(1, len(failed))
            self.assertTrue((failed[0] / "FAILED").is_file())
            with self.assertRaisesRegex(ReleaseValidationError, "failed candidate"):
                validate_release(failed[0])

    def test_mixed_source_rejects_cross_product_asset_and_false_date(self):
        product = "S2B_MSIL2A_20260914T023529_N0512_R089_T51TXM_20260914T044641.SAFE"
        root = "https://storage.googleapis.com/gcp-public-data-sentinel-2/L2/tiles/51/T/XM/"
        feature = {
            "id": "S2B_51TXM_20260914_0_L2A",
            "properties": {"datetime": "2026-09-14T02:35:29Z", "s2:product_uri": product,
                           "source:provider": "google-public-sentinel-2-l2a"},
            "assets": {
                "visual": {"href": root + product + "/T51TXM_TCI_10m.jp2", "gsd": 10, "bytes": 135_000_000},
                "scl": {"href": root + product + "/T51TXM_SCL_20m.jp2", "gsd": 20, "bytes": 2_000_000},
            },
        }
        counts = {"planetary-computer": 0, "google-public-sentinel-2-l2a": 0}
        worker._validate_mixed_source_feature(feature, counts)
        self.assertEqual(1, counts["google-public-sentinel-2-l2a"])
        feature["assets"]["scl"]["href"] = feature["assets"]["scl"]["href"].replace(product, "different.SAFE")
        with self.assertRaisesRegex(ReleaseValidationError, "product mismatch"):
            worker._validate_mixed_source_feature(feature, counts)
        feature["assets"]["scl"]["href"] = root + product + "/T51TXM_SCL_20m.jp2"
        feature["properties"]["datetime"] = "2026-09-13T02:35:29Z"
        with self.assertRaisesRegex(ReleaseValidationError, "date mismatch"):
            worker._validate_mixed_source_feature(feature, counts)

    @patch("scripts.weekly_imagery_sync.urllib.request.urlopen")
    def test_google_download_rejects_short_response_and_removes_partial_file(self, urlopen):
        class ShortResponse(io.BytesIO):
            headers = {"Content-Length": "200000"}
        urlopen.side_effect = lambda *_args, **_kwargs: ShortResponse(b"x" * 100001)
        with tempfile.TemporaryDirectory() as temporary, patch("scripts.weekly_imagery_sync.time.sleep"):
            root = Path(temporary)
            config = SyncConfig(root, root / "aoi.geojson", "https://example.test", "sentinel-2-l2a",
                                ("storage.googleapis.com",), 30, 5, 14, 5, 60, "", 0, 2)
            destination = root / "visual.jp2"
            with self.assertRaisesRegex(RuntimeError, "after 3 attempts"):
                worker._download_google_asset(config,
                    "https://storage.googleapis.com/gcp-public-data-sentinel-2/L2/tiles/test.jp2", destination)
            self.assertFalse(destination.exists())
            self.assertFalse(destination.with_suffix(".jp2.part").exists())

    def test_historical_source_plan_keeps_recent_pixels_newer_and_rejects_false_resolution(self):
        def feature(identifier, observed):
            return {
                "id": identifier, "bbox": [123, 47, 124, 48],
                "properties": {"datetime": observed, "eo:cloud_cover": 5},
                "assets": {band: {"href": f"https://sentinel-cogs.s3.us-west-2.amazonaws.com/{identifier}/"
                                 f"{'TCI' if band == 'visual' else band}.tif", "gsd": gsd}
                           for band, gsd in (("visual", 10), ("red", 10), ("green", 10), ("blue", 10), ("scl", 20))},
            }

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            plan = root / "plan.json"
            payload = {
                "version": "2026-09-r4", "recentPeriod": ["2026-09-14", "2026-09-20"],
                "historicalFillPeriod": ["2026-08-01", "2026-09-13"],
                "rgbResolutionMeters": 10, "sclResolutionMeters": 20,
                "features": [feature("S2A_recent", "2026-09-18T00:00:00Z"),
                             feature("S2A_history", "2026-08-18T00:00:00Z")],
            }
            plan.write_text(json.dumps(payload))
            config = SyncConfig(root, root / "aoi.geojson", "https://example.test", "sentinel-2-l2a",
                                ("sentinel-cogs.s3.us-west-2.amazonaws.com",), 30, 5, 14, 5, 60, "", 0, 2)

            ranked, loaded = load_historical_source_plan(config, plan, "2026-09-r4")
            self.assertEqual(["S2A_recent", "S2A_history"], [scene.product_id for scene in ranked])
            self.assertTrue(all(scene.assets["visual"].endswith("/TCI.tif") for scene in ranked))
            self.assertEqual("2026-09-r4", loaded["version"])

            payload["features"][1]["assets"]["red"]["gsd"] = 30
            plan.write_text(json.dumps(payload))
            with self.assertRaisesRegex(ReleaseValidationError, "resolution"):
                load_historical_source_plan(config, plan, "2026-09-r4")

    @patch("scripts.weekly_imagery_sync.time.sleep")
    @patch("scripts.weekly_imagery_sync._invalidate_planetary_token")
    @patch("scripts.weekly_imagery_sync._vsicurl", side_effect=["/vsicurl/first", "/vsicurl/refreshed"])
    @patch("scripts.weekly_imagery_sync._run", side_effect=[RuntimeError("remote read failed"), None])
    def test_remote_warp_retries_with_refreshed_token_and_removes_partial_output(
        self, run, vsicurl, invalidate, sleep
    ):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "scl.tif"
            output.write_bytes(b"partial")
            config = SyncConfig(root, root / "aoi.geojson", "https://example.test", "sentinel-2-l2a", ("example.test",), 30.0, 5, 14, 5, 60, "", 0, 4)

            worker._run_remote_warp(config, "https://example.test/scl.tif", ["-t_srs", "EPSG:3857"], "near", output)

            self.assertEqual(2, run.call_count)
            self.assertIn("/vsicurl/first", run.call_args_list[0].args[0])
            self.assertIn("/vsicurl/refreshed", run.call_args_list[1].args[0])
            self.assertFalse(output.exists())
            invalidate.assert_called_once()
            sleep.assert_called_once()

    @patch("scripts.weekly_imagery_sync.subprocess.run")
    @patch("scripts.weekly_imagery_sync._vsicurl", return_value="/vsicurl/signed")
    def test_single_range_is_scoped_to_candidate_pc_child(self, vsicurl, run):
        root = Path("/tmp")
        config = SyncConfig(root, root / "aoi", "https://example.test", "sentinel-2-l2a", (), 30, 5, 14, 5, 60, "", 0, 4)
        pc = "https://sentinel2l2a01.blob.core.windows.net/test.tif"
        with patch.dict(os.environ, {"GDAL_HTTP_MULTIRANGE": "YES", "GDAL_HTTP_MERGE_CONSECUTIVE_RANGES": "YES"}):
            for candidate, url, expected in ((False, pc, "YES"), (True, pc, "NO"), (True, "https://example.test/test.tif", "YES")):
                with self.subTest(candidate=candidate, url=url):
                    worker._run_remote_warp(worker.replace(config, candidate_single_range=candidate), url, [], "near", root / "out.tif")
                    environment = run.call_args.kwargs["env"]
                    self.assertEqual(expected, environment["GDAL_HTTP_MULTIRANGE"])
                    self.assertEqual(expected, environment["GDAL_HTTP_MERGE_CONSECUTIVE_RANGES"])
            self.assertEqual("YES", os.environ["GDAL_HTTP_MULTIRANGE"])

    @patch("scripts.weekly_imagery_sync.time.sleep")
    @patch("scripts.weekly_imagery_sync._invalidate_planetary_token")
    @patch("scripts.weekly_imagery_sync._vsicurl", return_value="/vsicurl/signed")
    @patch("scripts.weekly_imagery_sync.subprocess.run")
    def test_remote_warp_retains_sanitized_underlying_error(self, run, vsicurl, invalidate, sleep):
        run.side_effect = subprocess.CalledProcessError(1, ["gdalwarp"], stderr="IReadBlock X12 Y14 https://example.test/a?sig=SECRET")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config = SyncConfig(root, root / "aoi", "https://example.test", "sentinel-2-l2a", (), 30, 5, 14, 5, 60, "", 0, 4)
            with self.assertRaises(RuntimeError) as failure:
                worker._run_remote_warp(config, "https://example.test/a", [], "near", root / "rgb.tif")
            self.assertIn("IReadBlock X12 Y14", str(failure.exception))
            self.assertIn("rgb.tif", str(failure.exception))
            self.assertNotIn("SECRET", str(failure.exception))
            self.assertEqual(3, run.call_count)

    @patch("scripts.weekly_imagery_sync.subprocess.run")
    def test_gdal_error_does_not_log_signed_asset_url(self, run):
        run.side_effect = subprocess.CalledProcessError(
            1, ["gdalwarp"], stderr="ERROR 4: /vsicurl/https://example.test/scl.tif?sig=SECRET not recognized"
        )

        with self.assertRaises(RuntimeError) as failure:
            worker._run(["gdalwarp", "asset", "out"], 5)

        self.assertIn("[remote asset]", str(failure.exception))
        self.assertNotIn("SECRET", str(failure.exception))

    def test_scene_rgb_is_zeroed_where_cloud_mask_is_transparent(self):
        clear = "((D==2)|(D==4)|(D==5)|(D==6)|(D==7))"
        self.assertEqual(
            [
                f"A*{clear}", f"A*{clear}", f"A*{clear}", f"255*{clear}",
            ],
            worker._scene_band_expressions(),
        )
        for scl in (0, 1, 3, 8, 9, 10, 11, 255):
            self.assertEqual(
                0,
                eval(worker._scene_band_expressions()[3], {"__builtins__": {}}, {"D": scl}),
            )
        for scl in (2, 4, 5, 6, 7):
            self.assertEqual(
                255,
                eval(worker._scene_band_expressions()[3], {"__builtins__": {}}, {"D": scl}),
            )

    def test_rejects_sparse_release_instead_of_publishing_blank_weekly_map(self):
        with tempfile.TemporaryDirectory() as temporary:
            tiles = Path(temporary)
            present = tiles / "5" / "26" / "11.webp"
            present.parent.mkdir(parents=True)
            present.write_bytes(b"R" * 400)
            bounds = (112.6, 32.1, 134.9, 48.0)

            with self.assertRaisesRegex(ReleaseValidationError, "coverage"):
                worker._require_nonempty_tile_coverage(tiles, bounds, 5)

            for x, y in ((26, 12), (27, 11), (27, 12)):
                tile = tiles / "5" / str(x) / f"{y}.webp"
                tile.parent.mkdir(parents=True, exist_ok=True)
                tile.write_bytes(b"R" * 178)

            with self.assertRaisesRegex(ReleaseValidationError, "coverage"):
                worker._require_nonempty_tile_coverage(tiles, bounds, 5)

            for x, y in ((26, 12), (27, 11), (27, 12)):
                (tiles / "5" / str(x) / f"{y}.webp").write_bytes(b"R" * 400)

            worker._require_nonempty_tile_coverage(tiles, bounds, 5)

    def test_valid_tiny_webp_counts_as_covered_tile(self):
        if shutil.which("gdalinfo") is None:
            self.skipTest("GDAL is required to decode a tiny WebP")
        # Real 256x256 WebP encoding of a synthetic pixel window, 194 bytes.
        tiny = base64.b64decode(
            "UklGRroAAABXRUJQVlA4IK4AAAAwEQCdASoAAQABPmEwlkikIyIhICgAgAwJ"
            "aW7hdrEe3AAAE9gHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77Z"
            "OQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77Z"
            "OQ99snCgAD+/2cK//+drYYgex//ELvYwoAAAAAAAAAAAAA="
        )
        with tempfile.TemporaryDirectory() as temporary:
            tiles = Path(temporary)
            for x, y in ((26, 11), (26, 12), (27, 11)):
                tile = tiles / "5" / str(x) / f"{y}.webp"
                tile.parent.mkdir(parents=True, exist_ok=True)
                tile.write_bytes(b"R" * 400)
            last = tiles / "5/27/12.webp"
            last.write_bytes(tiny)
            worker._require_nonempty_tile_coverage(tiles, (112.6, 32.1, 134.9, 48.0), 5)
            last.write_bytes(b"R" * 194)
            with self.assertRaisesRegex(ReleaseValidationError, "coverage"):
                worker._require_nonempty_tile_coverage(tiles, (112.6, 32.1, 134.9, 48.0), 5)

    def test_region_gate_rejects_a_visible_missing_tile(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            aoi = root / "aoi.geojson"
            aoi.write_text(json.dumps({
                "type": "Feature",
                "geometry": {"type": "Polygon", "coordinates": [[
                    [112.6, 32.1], [134.9, 32.1], [134.9, 48.0],
                    [112.6, 48.0], [112.6, 32.1],
                ]]},
            }))
            tiles = root / "tiles"
            for x, y in ((26, 11), (26, 12), (27, 11)):
                tile = tiles / "5" / str(x) / f"{y}.webp"
                tile.parent.mkdir(parents=True, exist_ok=True)
                tile.write_bytes(b"R" * 400)

            with self.assertRaisesRegex(ReleaseValidationError, "AOI region 1"):
                worker._require_region_tile_coverage(tiles, aoi, 5)

            missing = tiles / "5/27/12.webp"
            missing.write_bytes(b"R" * 400)
            worker._require_region_tile_coverage(tiles, aoi, 5)

    def test_disjoint_regions_do_not_count_intervening_empty_tiles(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            aoi = root / "aoi.geojson"
            aoi.write_text(json.dumps({
                "type": "FeatureCollection",
                "features": [{
                    "type": "Feature",
                    "geometry": {"type": "Polygon", "coordinates": [[
                        [west, 0.5], [east, 0.5], [east, 1.0],
                        [west, 1.0], [west, 0.5],
                    ]]},
                } for west, east in ((-168, -166), (166, 168))],
            }))
            tiles = root / "tiles"
            for x in (1, 30):
                tile = tiles / "5" / str(x) / "15.webp"
                tile.parent.mkdir(parents=True)
                tile.write_bytes(b"R" * 400)

            with self.assertRaisesRegex(ReleaseValidationError, "80%"):
                worker._require_nonempty_tile_coverage(tiles, worker.aoi_bounds(aoi), 5)
            worker._require_release_tile_coverage(tiles, aoi, 5)

            (tiles / "5/30/15.webp").unlink()
            with self.assertRaisesRegex(ReleaseValidationError, "AOI region 2"):
                worker._require_release_tile_coverage(tiles, aoi, 5)

    def test_discards_only_tiny_white_webp_tiles_with_empty_mosaic_alpha(self):
        with tempfile.TemporaryDirectory() as temporary:
            tiles = Path(temporary)
            blank = tiles / "14/13949/5764.webp"
            valid = tiles / "14/13869/5625.webp"
            white_with_imagery = tiles / "14/13949/5765.webp"
            blank.parent.mkdir(parents=True)
            valid.parent.mkdir(parents=True)
            blank.write_bytes(b"RIFF" + b"\0" * 4 + b"WEBP" + b"\0" * 166)
            valid.write_bytes(b"RIFF" + b"\0" * 4 + b"WEBP" + b"X" * 286)
            white_with_imagery.write_bytes(b"RIFF" + b"\0" * 4 + b"WEBP" + b"\0" * 166)
            mosaic = tiles / "mosaic.tif"
            extent = math.pi * 6378137.0
            blank_top = extent - 5764 * (2 * extent / (1 << 14))

            def inspect(raster, band, value, bounds=None):
                if raster == valid:
                    return False
                if raster == mosaic:
                    self.assertEqual(4, band)
                    self.assertIsNotNone(bounds)
                    return abs(bounds[1] - blank_top) < 1e-6
                return True

            with patch.object(worker, "_raster_band_is_constant", side_effect=inspect) as inspect_pixels:
                self.assertEqual(1, worker._discard_tiny_webp_tiles(tiles, mosaic))

            self.assertFalse(blank.exists())
            self.assertTrue(valid.exists())
            self.assertTrue(white_with_imagery.exists())
            self.assertEqual(2, sum(call.args[0] == mosaic for call in inspect_pixels.call_args_list))

    @patch("scripts.weekly_imagery_sync._run")
    @patch("scripts.weekly_imagery_sync.subprocess.run")
    def test_modern_tile_build_excludes_fully_transparent_tiles(self, subprocess_run, run):
        subprocess_run.return_value = SimpleNamespace(stdout="--xyz --tiledriver --exclude")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config = SyncConfig(
                root, root / "aoi.geojson", "https://example.test", "sentinel-2-l2a",
                ("example.test",), 30.0, 5, 14, 5, 60, "", 0, 4,
            )
            _build_web_tiles(root / "mosaic.tif", root / "tiles", config)

        self.assertIn("--exclude", run.call_args.args[0])

    @patch("scripts.weekly_imagery_sync._run")
    @patch("scripts.weekly_imagery_sync.subprocess.run")
    def test_legacy_tile_build_excludes_fully_transparent_tiles(self, subprocess_run, run):
        subprocess_run.return_value = SimpleNamespace(stdout="--exclude")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config = SyncConfig(
                root, root / "aoi.geojson", "https://example.test", "sentinel-2-l2a",
                ("example.test",), 30.0, 5, 14, 5, 60, "", 0, 4,
            )

            def run_side_effect(command, timeout):
                if command[0] == "gdal2tiles.py":
                    tile = root / ".tiles-tms" / "5" / "26" / "11.png"
                    tile.parent.mkdir(parents=True)
                    tile.write_bytes(b"\x89PNG\r\n\x1a\n" + b"\0" * 18)

            run.side_effect = run_side_effect
            _build_web_tiles(root / "mosaic.tif", root / "tiles", config)

        self.assertIn("--exclude", run.call_args_list[0].args[0])

    @patch("scripts.weekly_imagery_sync._gdal_version", return_value="GDAL test-runtime")
    @patch("scripts.weekly_imagery_sync._log_alpha_coverage")
    @patch("scripts.weekly_imagery_sync.require_free_space")
    @patch("scripts.weekly_imagery_sync._require_nonempty_tile_coverage")
    @patch("scripts.weekly_imagery_sync._build_web_tiles")
    @patch("scripts.weekly_imagery_sync._run")
    @patch("scripts.weekly_imagery_sync._build_scene")
    @patch("scripts.weekly_imagery_sync._check_gdal")
    @patch("scripts.weekly_imagery_sync._dissolve_historical_cutline")
    def test_build_release_manifest_excludes_removed_work_files(
        self, dissolve, check_gdal, build_scene, run, build_tiles, coverage, free_space, log_alpha, gdal_version
    ):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config = SyncConfig(root, root / "aoi.geojson", "https://example.test", "sentinel-2-l2a", ("example.test",), 30.0, 5, 5, 5, 60, "", 0, 4)
            config.aoi.write_text(json.dumps({"type": "Polygon", "coordinates": [[[113, 47], [114, 47], [114, 48], [113, 48], [113, 47]]]}))
            candidate = Candidate("S2-test", "2026-09-20T02:00:00Z", 5.0, True, {}, bounds=(113, 47, 114, 48))

            def scene_side_effect(config, candidate, work, index):
                scene = work / "scene.tif"
                scene.write_bytes(b"temporary")
                return scene

            def tiles_side_effect(mosaic, tiles, config):
                tile = tiles / "5" / "26" / "11.webp"
                tile.parent.mkdir(parents=True)
                tile.write_bytes(b"RIFF-weekly-imagery-WEBP")

            build_scene.side_effect = scene_side_effect
            build_tiles.side_effect = tiles_side_effect
            def dissolve_side_effect(config, destination):
                destination.write_bytes(config.aoi.read_bytes())
                return destination

            dissolve.side_effect = dissolve_side_effect
            staging = build_release(
                config,
                WeekWindow("2026-09", datetime(2026, 8, 24).date(), datetime(2026, 9, 22).date()),
                [candidate],
                datetime(2026, 9, 21, tzinfo=timezone.utc),
                source_plan={"version": "2026-09-r4", "recentPeriod": ["2026-09-14", "2026-09-20"]},
            )

            self.assertFalse((staging / "work").exists())
            self.assertNotIn("work/", (staging / "manifest.sha256").read_text())
            self.assertEqual("MONTHLY", json.loads((staging / "metadata.json").read_text())["updateCadence"])
            self.assertIn("历史10米RGB", json.loads((staging / "metadata.json").read_text())["truthStatement"])
            self.assertIn("source-plan.json", (staging / "manifest.sha256").read_text())
            self.assertEqual(2, coverage.call_count)
            self.assertEqual(1, log_alpha.call_count)
            self.assertEqual("scene grid=unknown", log_alpha.call_args.args[0])
            self.assertEqual("cutline-union.geojson", build_scene.call_args.args[0].aoi.name)
            self.assertTrue(build_scene.call_args.args[0].candidate_single_range)
            validate_release(staging)

            build_scene.side_effect = RuntimeError("IReadBlock failed")
            with self.assertRaisesRegex(RuntimeError, "scene-000 product=S2-test: IReadBlock failed"):
                build_release(config, WeekWindow("2026-09", datetime(2026, 8, 24).date(), datetime(2026, 9, 22).date()), [candidate], datetime(2026, 9, 21, tzinfo=timezone.utc), source_plan={"version": "2026-09-r4"})
            failed = list(root.glob(".failed-*"))
            self.assertEqual(1, len(failed))
            self.assertIn("not publishable", (failed[0] / "FAILED").read_text())

    @patch("scripts.weekly_imagery_sync.time.sleep")
    @patch("scripts.weekly_imagery_sync.urllib.request.urlopen")
    def test_json_request_retries_transient_network_failures(self, urlopen, sleep):
        urlopen.side_effect = urllib.error.URLError("temporary")

        with self.assertRaisesRegex(RuntimeError, "after retries"):
            _read_json_response(urllib.request.Request("https://example.test"), 5)

        self.assertEqual(4, urlopen.call_count)
        self.assertEqual(3, sleep.call_count)

    def test_scene_workers_are_bounded_for_production_capacity(self):
        with patch.dict(os.environ, {"QIQIHAR_IMAGERY_SCENE_WORKERS": "8"}):
            self.assertEqual(4, _scene_worker_count(19))

    def test_scene_workers_do_not_exceed_candidate_count(self):
        with patch.dict(os.environ, {"QIQIHAR_IMAGERY_SCENE_WORKERS": "4"}):
            self.assertEqual(2, _scene_worker_count(2))

    def test_grid_code_falls_back_to_earth_search_product_id(self):
        self.assertEqual(
            "51TVM",
            _mgrs_grid_code({}, "S2B_T51TVM_20260920T025542_L2A"),
        )

    def test_grid_code_uses_earth_search_mgrs_components(self):
        self.assertEqual(
            "51TVM",
            _mgrs_grid_code(
                {
                    "mgrs:utm_zone": 51,
                    "mgrs:latitude_band": "T",
                    "mgrs:grid_square": "VM",
                },
                "unstructured-product-id",
            ),
        )

    def test_legacy_tms_tile_is_mapped_to_xyz_webp(self):
        self.assertEqual(
            Path("14/1234/10705.webp"),
            _xyz_webp_relative(Path("14/1234/5678.png")),
        )

    def test_legacy_gray_alpha_png_is_expanded_to_rgba(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "tile.png"
            source.write_bytes(b"\x89PNG\r\n\x1a\n" + b"\0" * 17 + b"\x04")

            self.assertEqual(
                ["-b", "1", "-b", "1", "-b", "1", "-b", "2"],
                _legacy_webp_band_args(source),
            )

    def test_earth_search_assets_use_faster_public_global_bucket_endpoint(self):
        regional = (
            "https://e84-earth-search-sentinel-data.s3.us-west-2.amazonaws.com/"
            "sentinel-2-c1-l2a/52/U/CV/product/TCI.tif"
        )

        self.assertEqual(
            "/vsicurl/https://e84-earth-search-sentinel-data.s3.amazonaws.com/"
            "sentinel-2-c1-l2a/52/U/CV/product/TCI.tif",
            _vsicurl(
                regional,
                ("e84-earth-search-sentinel-data.s3.us-west-2.amazonaws.com",),
            ),
        )

    @patch("scripts.weekly_imagery_sync._read_json_response")
    def test_planetary_computer_assets_share_one_anonymous_container_token(self, read_json):
        unsigned = (
            "https://sentinel2l2a01.blob.core.windows.net/sentinel2-l2/"
            "52/U/CV/product/visual.tif"
        )
        sibling = unsigned.replace("visual.tif", "SCL.tif")
        token = "se=temporary&sig=read-only"
        read_json.return_value = {"token": token}

        with patch.dict(_PLANETARY_TOKEN_CACHE, {}, clear=True):
            result = _vsicurl(unsigned, ("sentinel2l2a01.blob.core.windows.net",), 45)
            sibling_result = _vsicurl(
                sibling, ("sentinel2l2a01.blob.core.windows.net",), 45
            )

        self.assertEqual("/vsicurl/" + unsigned + "?" + token, result)
        self.assertEqual("/vsicurl/" + sibling + "?" + token, sibling_result)
        self.assertEqual(1, read_json.call_count)
        request, timeout = read_json.call_args.args
        self.assertEqual(45, timeout)
        self.assertEqual(
            "https://planetarycomputer.microsoft.com/api/sas/v1/token/"
            "sentinel2l2a01/sentinel2-l2",
            request.full_url,
        )

    def test_complete_week_uses_previous_monday_to_sunday(self):
        window = complete_week(datetime(2026, 9, 22, 2, tzinfo=timezone.utc))

        self.assertEqual("2026-W38", window.identifier)
        self.assertEqual("2026-09-14", window.start.isoformat())
        self.assertEqual("2026-09-20", window.end.isoformat())

    def test_monthly_run_searches_latest_thirty_complete_days(self):
        window = complete_month(datetime(2026, 9, 23, 2, tzinfo=timezone.utc))

        self.assertEqual("2026-09", window.identifier)
        self.assertEqual("2026-08-24", window.start.isoformat())
        self.assertEqual("2026-09-22", window.end.isoformat())

    def test_monthly_timer_uses_china_calendar_month_at_utc_day_boundary(self):
        window = complete_month(datetime(2026, 9, 30, 19, 10, tzinfo=timezone.utc))
        self.assertEqual("2026-10", window.identifier)
        self.assertEqual("2026-09-30", window.end.isoformat())

    @patch("scripts.weekly_imagery_sync.aoi_feature_bounds", return_value=[(123, 47, 124, 48)])
    @patch("scripts.weekly_imagery_sync._read_json_response")
    def test_monthly_catalog_search_follows_post_pagination(self, read_json, bounds):
        def feature(identifier):
            return {
                "id": identifier,
                "properties": {"datetime": "2026-09-20T00:00:00Z", "eo:cloud_cover": 1},
                "assets": {
                    "visual": {"href": "https://example.test/visual.tif"},
                    "scl": {"href": "https://example.test/scl.tif"},
                },
            }

        read_json.side_effect = [
            {"features": [feature("first")], "links": [{
                "rel": "next", "href": "https://example.test/search?collections=collection", "method": "POST",
                "body": {"token": "page-2"}, "merge": True,
            }]},
            {"features": [feature("second")], "links": []},
        ]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config = SyncConfig(root, root / "aoi.geojson", "https://example.test/search", "collection", ("example.test",), 30, 5, 14, 5, 60, "", 0, 2)
            found = search_candidates(config, datetime(2026, 9, 1).date(), datetime(2026, 9, 22).date())

        self.assertEqual(["first", "second"], [item.product_id for item in found])
        self.assertEqual("page-2", json.loads(read_json.call_args.args[0].data)["token"])
        self.assertEqual("collection", json.loads(read_json.call_args.args[0].data)["collections"][0])

    @patch("scripts.weekly_imagery_sync._read_json_response")
    def test_four_region_catalog_search_queries_each_envelope_and_deduplicates(self, read_json):
        def feature(identifier):
            return {
                "id": identifier,
                "properties": {"datetime": "2026-09-20T00:00:00Z", "eo:cloud_cover": 1},
                "assets": {
                    "visual": {"href": "https://example.test/visual.tif"},
                    "scl": {"href": "https://example.test/scl.tif"},
                },
            }

        read_json.side_effect = [
            {"features": [feature("shared"), feature("west")], "links": []},
            {"features": [feature("shared"), feature("east")], "links": []},
        ]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            aoi = root / "aoi.geojson"
            aoi.write_text(json.dumps({"type": "FeatureCollection", "features": [
                {"type": "Feature", "geometry": {"type": "Polygon", "coordinates": [
                    [[120, 47], [121, 47], [121, 48], [120, 48], [120, 47]]]}},
                {"type": "Feature", "geometry": {"type": "Polygon", "coordinates": [
                    [[124, 49], [125, 49], [125, 50], [124, 50], [124, 49]]]}},
            ]}))
            config = SyncConfig(root, aoi, "https://example.test/search", "collection", ("example.test",), 30, 5, 14, 5, 60, "", 0, 2)
            found = search_candidates(config, datetime(2026, 9, 1).date(), datetime(2026, 9, 22).date())

        self.assertEqual({"shared", "west", "east"}, {item.product_id for item in found})
        self.assertEqual([(120, 47, 121, 48), (124, 49, 125, 50)], [
            tuple(json.loads(item.args[0].data)["bbox"]) for item in read_json.call_args_list
        ])

    def test_governed_four_region_aoi_contains_all_root_regions(self):
        aoi = json.loads((REPOSITORY_ROOT / "ops/imagery/four-region-aoi.geojson").read_text())
        self.assertEqual(
            {"230200", "150700", "231100", "232700"},
            {feature["properties"]["regionCode"] for feature in aoi["features"]},
        )
        self.assertEqual(4, len(worker.aoi_feature_bounds(REPOSITORY_ROOT / "ops/imagery/four-region-aoi.geojson")))

    def test_rank_prefers_authorized_clear_and_recent_products(self):
        candidates = [
            Candidate("old-clear", "2026-09-18T02:00:00Z", 3.0, True, {}),
            Candidate("new-cloudy", "2026-09-20T02:00:00Z", 85.0, True, {}),
            Candidate("new-clear", "2026-09-20T02:00:00Z", 12.0, True, {}),
            Candidate("unlicensed", "2026-09-21T02:00:00Z", 0.0, False, {}),
        ]

        ranked = rank_candidates(candidates, maximum_cloud_percent=30.0)

        self.assertEqual(["new-clear", "old-clear"], [item.product_id for item in ranked])

    def test_parse_candidates_requires_true_colour_and_scene_classification_assets(self):
        payload = {
            "features": [
                {
                    "id": "S2-good",
                    "properties": {
                        "datetime": "2026-09-20T02:00:00Z",
                        "eo:cloud_cover": 8.5,
                        "grid:code": "MGRS-52UEU",
                    },
                    "bbox": [123.0, 46.0, 124.0, 47.0],
                    "assets": {
                        "red": {"href": "https://example.test/red.tif"},
                        "green": {"href": "https://example.test/green.tif"},
                        "blue": {"href": "https://example.test/blue.tif"},
                        "scl": {"href": "https://example.test/scl.tif"},
                    },
                },
                {
                    "id": "S2-incomplete",
                    "properties": {
                        "datetime": "2026-09-19T02:00:00Z",
                        "eo:cloud_cover": 1.0,
                    },
                    "assets": {"visual": {"href": "https://example.test/visual.tif"}},
                },
            ]
        }

        parsed = parse_candidates(payload, ("example.test",))

        self.assertEqual(["S2-good"], [candidate.product_id for candidate in parsed])
        self.assertEqual("MGRS-52UEU", parsed[0].grid_code)
        self.assertEqual((123.0, 46.0, 124.0, 47.0), parsed[0].bounds)

    def test_select_grid_candidates_retains_newest_full_footprint_and_clear_alternate(self):
        ranked = [
            Candidate("new-partial", "2026-09-20T02:00:00Z", 12.0, True, {}, "A", (123, 47, 123.4, 48)),
            Candidate("full", "2026-09-18T02:00:00Z", 8.0, True, {}, "A", (123, 47, 124.5, 48)),
            Candidate("clear", "2026-09-17T02:00:00Z", 0.1, True, {}, "A", (123, 47, 124.4, 48)),
            Candidate("grid-b", "2026-09-19T02:00:00Z", 5.0, True, {}, "B", (124, 47, 125, 48)),
            Candidate("unknown", "2026-09-17T02:00:00Z", 4.0, True, {}),
        ]

        selected = select_grid_candidates(ranked)

        self.assertEqual(
            ["new-partial", "full", "clear", "grid-b", "unknown"],
            [candidate.product_id for candidate in selected],
        )

    def test_select_grid_candidates_caps_redundant_scene_count(self):
        ranked = [
            Candidate(f"scene-{day}", f"2026-09-{day:02d}T02:00:00Z", float(day), True, {}, "A", (123, 47, 124, 48))
            for day in range(20, 15, -1)
        ]
        self.assertEqual(3, len(select_grid_candidates(ranked)))

    @patch("scripts.weekly_imagery_sync.shutil.disk_usage")
    def test_require_free_space_rejects_release_before_gdal_when_disk_is_low(self, disk_usage):
        disk_usage.return_value = SimpleNamespace(total=100, used=95, free=5)

        with self.assertRaisesRegex(RuntimeError, "insufficient free disk space"):
            require_free_space(Path("/tmp/imagery"), minimum_free_bytes=6)

    def test_publish_is_atomic_and_validation_failure_keeps_current(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            releases = root / "releases"
            releases.mkdir()
            previous = self._release(releases / "2026-W37", "2026-W37")
            (root / "current").symlink_to(previous)
            invalid = self._release(root / ".staging-2026-W38", "2026-W38")
            (invalid / "manifest.sha256").write_text("0" * 64 + "  metadata.json\n")

            with self.assertRaises(ReleaseValidationError):
                publish_release(root, invalid)

            self.assertEqual("2026-W37", (root / "current").resolve().name)

    def test_publish_switches_current_and_retains_four_successful_releases(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            releases = root / "releases"
            releases.mkdir()
            for week in range(33, 38):
                self._release(releases / f"2026-W{week:02d}", f"2026-W{week:02d}")
            staging = self._release(root / ".staging-2026-W38", "2026-W38")

            published = publish_release(root, staging)
            retain_releases(root, keep=4)

            self.assertEqual("2026-W38", published.name)
            self.assertEqual("2026-W38", (root / "current").resolve().name)
            self.assertEqual(
                ["2026-W35", "2026-W36", "2026-W37", "2026-W38"],
                sorted(path.name for path in releases.iterdir()),
            )

    @patch("scripts.weekly_imagery_sync._run")
    def test_publish_grants_backend_read_acl_before_switching_current(self, run):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            staging = self._release(root / ".staging-2026-W38", "2026-W38")

            published = publish_release(root, staging, backend_reader_uid=10001)

            self.assertEqual("2026-W38", (root / "current").resolve().name)
            self.assertEqual(
                [
                    ["setfacl", "-m", "u:10001:rx", str(root)],
                    ["setfacl", "-m", "u:10001:rx", str(root / "releases")],
                    ["setfacl", "-R", "-m", "u:10001:rX", str(published)],
                ],
                [call.args[0] for call in run.call_args_list],
            )

    def _release(self, path: Path, version: str) -> Path:
        tile = path / "tiles" / "5" / "26"
        tile.mkdir(parents=True)
        (tile / "11.webp").write_bytes(b"RIFF-weekly-imagery-WEBP")
        metadata = {
            "version": version,
            "provider": "Copernicus Sentinel-2 L2A",
            "acquisitionFrom": "2026-09-18T02:00:00Z",
            "acquisitionTo": "2026-09-20T02:00:00Z",
            "syncedAt": "2026-09-21T03:10:00Z",
            "spatialResolutionMeters": 10,
            "cloudCoveragePercent": 8.5,
            "status": "CURRENT",
            "sourceProductIds": ["S2-test"],
        }
        metadata_bytes = (json.dumps(metadata, sort_keys=True) + "\n").encode()
        (path / "metadata.json").write_bytes(metadata_bytes)
        entries = []
        for relative in (Path("metadata.json"), Path("tiles/5/26/11.webp")):
            digest = hashlib.sha256((path / relative).read_bytes()).hexdigest()
            entries.append(f"{digest}  {relative.as_posix()}\n")
        (path / "manifest.sha256").write_text("".join(entries))
        validate_release(path)
        return path


if __name__ == "__main__":
    unittest.main()
