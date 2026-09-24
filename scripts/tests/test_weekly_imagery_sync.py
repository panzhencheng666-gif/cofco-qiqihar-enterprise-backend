import hashlib
import json
import os
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
                f"A*{clear}", f"B*{clear}", f"C*{clear}", f"255*{clear}",
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

    def test_discards_tiny_white_webp_tiles_before_publication(self):
        with tempfile.TemporaryDirectory() as temporary:
            tiles = Path(temporary)
            blank = tiles / "14/13949/5764.webp"
            valid = tiles / "14/13949/5765.webp"
            blank.parent.mkdir(parents=True)
            blank.write_bytes(b"RIFF" + b"\0" * 4 + b"WEBP" + b"\0" * 166)
            valid.write_bytes(b"RIFF" + b"\0" * 4 + b"WEBP" + b"X" * 388)

            self.assertEqual(1, worker._discard_tiny_webp_tiles(tiles))
            self.assertFalse(blank.exists())
            self.assertTrue(valid.exists())

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

    @patch("scripts.weekly_imagery_sync._log_alpha_coverage")
    @patch("scripts.weekly_imagery_sync.require_free_space")
    @patch("scripts.weekly_imagery_sync._require_nonempty_tile_coverage")
    @patch("scripts.weekly_imagery_sync._build_web_tiles")
    @patch("scripts.weekly_imagery_sync._run")
    @patch("scripts.weekly_imagery_sync._build_scene")
    @patch("scripts.weekly_imagery_sync._check_gdal")
    def test_build_release_manifest_excludes_removed_work_files(
        self, check_gdal, build_scene, run, build_tiles, coverage, free_space, log_alpha
    ):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config = SyncConfig(root, root / "aoi.geojson", "https://example.test", "sentinel-2-l2a", ("example.test",), 30.0, 5, 5, 5, 60, "", 0, 4)
            config.aoi.write_text(json.dumps({"type": "Polygon", "coordinates": [[[113, 47], [114, 47], [114, 48], [113, 48], [113, 47]]]}))
            candidate = Candidate("S2-test", "2026-09-20T02:00:00Z", 5.0, True, {})

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
            staging = build_release(
                config,
                WeekWindow("2026-09", datetime(2026, 8, 24).date(), datetime(2026, 9, 22).date()),
                [candidate],
                datetime(2026, 9, 21, tzinfo=timezone.utc),
            )

            self.assertFalse((staging / "work").exists())
            self.assertNotIn("work/", (staging / "manifest.sha256").read_text())
            self.assertEqual("MONTHLY", json.loads((staging / "metadata.json").read_text())["updateCadence"])
            coverage.assert_called_once()
            self.assertEqual(3, log_alpha.call_count)
            validate_release(staging)

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

    @patch("scripts.weekly_imagery_sync.aoi_bounds", return_value=(123, 47, 124, 48))
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
