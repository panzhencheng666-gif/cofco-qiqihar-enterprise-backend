import hashlib
import json
import os
import sys
import tempfile
import unittest
from unittest.mock import patch
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPOSITORY_ROOT))

from scripts.weekly_imagery_sync import (  # noqa: E402
    Candidate,
    ReleaseValidationError,
    _legacy_webp_band_args,
    _mgrs_grid_code,
    _scene_worker_count,
    _vsicurl,
    _xyz_webp_relative,
    complete_week,
    parse_candidates,
    publish_release,
    rank_candidates,
    require_free_space,
    retain_releases,
    select_grid_candidates,
    validate_release,
)


class WeeklyImagerySyncTest(unittest.TestCase):
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
    def test_planetary_computer_assets_are_anonymously_signed(self, read_json):
        unsigned = (
            "https://sentinel2l2a01.blob.core.windows.net/sentinel2-l2/"
            "52/U/CV/product/visual.tif"
        )
        signed = unsigned + "?se=temporary&sig=read-only"
        read_json.return_value = {"href": signed}

        result = _vsicurl(unsigned, ("sentinel2l2a01.blob.core.windows.net",), 45)

        self.assertEqual("/vsicurl/" + signed, result)
        request, timeout = read_json.call_args.args
        self.assertEqual(45, timeout)
        self.assertTrue(
            request.full_url.startswith(
                "https://planetarycomputer.microsoft.com/api/sas/v1/sign?href="
            )
        )

    def test_complete_week_uses_previous_monday_to_sunday(self):
        window = complete_week(datetime(2026, 9, 22, 2, tzinfo=timezone.utc))

        self.assertEqual("2026-W38", window.identifier)
        self.assertEqual("2026-09-14", window.start.isoformat())
        self.assertEqual("2026-09-20", window.end.isoformat())

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

    def test_select_grid_candidates_keeps_best_ranked_scene_per_grid(self):
        ranked = [
            Candidate("new-a", "2026-09-20T02:00:00Z", 12.0, True, {}, "A"),
            Candidate("old-a", "2026-09-18T02:00:00Z", 3.0, True, {}, "A"),
            Candidate("grid-b", "2026-09-19T02:00:00Z", 5.0, True, {}, "B"),
            Candidate("unknown", "2026-09-17T02:00:00Z", 4.0, True, {}),
        ]

        selected = select_grid_candidates(ranked)

        self.assertEqual(
            ["new-a", "grid-b", "unknown"],
            [candidate.product_id for candidate in selected],
        )

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
