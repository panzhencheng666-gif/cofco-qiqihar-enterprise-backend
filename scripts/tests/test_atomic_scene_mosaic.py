"""Focused regression for atomic RGBA and source-index composition."""

import json
import tempfile
import unittest
from pathlib import Path

import numpy as np
from osgeo import gdal, osr

from scripts.atomic_scene_mosaic import compose


class AtomicSceneMosaicTest(unittest.TestCase):
    def test_same_source_channels_and_index_survive_overlap(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            srs = osr.SpatialReference()
            srs.ImportFromEPSG(3857)

            def raster(path, values):
                ds = gdal.GetDriverByName("GTiff").Create(str(path), 8, 8, 4, gdal.GDT_Byte)
                ds.SetGeoTransform((0, 10, 0, 80, 0, -10))
                ds.SetProjection(srs.ExportToWkt())
                for band, pixels in enumerate(values, 1):
                    ds.GetRasterBand(band).WriteArray(pixels)
                ds = None

            older = np.zeros((4, 8, 8), dtype=np.uint8)
            older[0] = 91
            older[1] = 20
            older[2] = 30
            older[3, :, :6] = 255
            newer = np.zeros((4, 8, 8), dtype=np.uint8)
            newer[0] = 0  # A real zero channel must not fall back to older red.
            newer[1] = 42
            newer[2] = 52
            newer[3, :, 3:] = 255
            raster(root / "older.tif", older)
            raster(root / "newer.tif", newer)
            gdal.BuildVRT(str(root / "grid.vrt"), [str(root / "older.tif"), str(root / "newer.tif")])
            table = {"scenesInPriorityOrder": [
                {"sceneIndex": 1, "productId": "older", "acquiredAt": "2026-09-09T00:00:00Z",
                 "maskedRaster": str(root / "older.tif")},
                {"sceneIndex": 2, "productId": "newer", "acquiredAt": "2026-09-17T00:00:00Z",
                 "maskedRaster": str(root / "newer.tif")}]}
            (root / "table.json").write_text(json.dumps(table))
            histogram = compose(root / "grid.vrt", root / "table.json",
                                root / "rgba.tif", root / "index.tif", block_size=3)
            rgba = gdal.Open(str(root / "rgba.tif")).ReadAsArray()
            index = gdal.Open(str(root / "index.tif")).ReadAsArray()
            self.assertEqual({"0": 0, "1": 24, "2": 40}, histogram)
            self.assertTrue(np.all(rgba[0, :, 3:] == 0))
            self.assertTrue(np.all(rgba[1, :, 3:] == 42))
            self.assertTrue(np.all(index[:, :3] == 1))
            self.assertTrue(np.all(index[:, 3:] == 2))


if __name__ == "__main__":
    unittest.main()
