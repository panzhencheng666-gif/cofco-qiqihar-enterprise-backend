#!/usr/bin/env python3
"""Compose aligned RGBA scene rasters atomically, preserving source identity.

This is a build-only primitive. It never changes an imagery release pointer.
Scenes are ordered from lowest to highest priority. Each source contributes all
four channels and one index in the same pixel operation.
"""

import argparse
import json
import math
import os
from pathlib import Path

import numpy as np
from osgeo import gdal, osr


def _grid(dataset):
    transform = dataset.GetGeoTransform()
    if (len(transform) != 6 or transform[1] <= 0 or transform[5] >= 0 or
            transform[2] != 0 or transform[4] != 0 or
            not all(math.isfinite(number) for number in transform)):
        raise ValueError("unsupported mosaic grid")
    srs = osr.SpatialReference()
    srs.ImportFromWkt(dataset.GetProjection())
    expected = osr.SpatialReference()
    expected.ImportFromEPSG(3857)
    if not srs.IsSame(expected):
        raise ValueError("mosaic grid is not EPSG:3857")
    return transform


def _offset(scene, target_transform):
    transform = _grid(scene)
    if abs(transform[1] - target_transform[1]) > 1e-8 or abs(transform[5] - target_transform[5]) > 1e-8:
        raise ValueError("scene resolution differs from mosaic grid")
    x = (transform[0] - target_transform[0]) / target_transform[1]
    y = (transform[3] - target_transform[3]) / target_transform[5]
    if abs(x - round(x)) > 1e-6 or abs(y - round(y)) > 1e-6:
        raise ValueError("scene is not aligned to mosaic grid")
    return int(round(x)), int(round(y))


def _create(path, reference, count, data_type):
    if path.exists():
        raise ValueError("output already exists: " + str(path))
    dataset = gdal.GetDriverByName("GTiff").Create(
        str(path), reference.RasterXSize, reference.RasterYSize, count, data_type,
        options=["TILED=YES", "COMPRESS=DEFLATE", "BIGTIFF=YES", "SPARSE_OK=YES"])
    if dataset is None:
        raise RuntimeError("cannot create output: " + str(path))
    dataset.SetGeoTransform(reference.GetGeoTransform())
    dataset.SetProjection(reference.GetProjection())
    if count == 4:
        for number, color in enumerate((gdal.GCI_RedBand, gdal.GCI_GreenBand,
                                        gdal.GCI_BlueBand, gdal.GCI_AlphaBand), 1):
            dataset.GetRasterBand(number).SetColorInterpretation(color)
    return dataset


def compose(grid_path, scene_table_path, rgba_path, index_path, block_size=512):
    """Build new, unpublished rasters; caller owns resume and release gates."""
    gdal.UseExceptions()
    reference = gdal.Open(str(grid_path), gdal.GA_ReadOnly)
    if reference is None:
        raise ValueError("missing grid VRT")
    target_transform = _grid(reference)
    entries = json.loads(scene_table_path.read_text())["scenesInPriorityOrder"]
    if not entries or len(entries) > 65535 or block_size <= 0:
        raise ValueError("invalid source count or block size")
    sources = []
    identities = set()
    for number, entry in enumerate(entries, 1):
        if (entry.get("sceneIndex") != number or not entry.get("productId") or
                not entry.get("acquiredAt") or entry["productId"] in identities):
            raise ValueError("invalid scene identity/order")
        identities.add(entry["productId"])
        scene = gdal.Open(str(Path(entry["maskedRaster"])), gdal.GA_ReadOnly)
        if scene is None or scene.RasterCount != 4:
            raise ValueError("missing four-band source raster")
        if any(scene.GetRasterBand(i).DataType != gdal.GDT_Byte for i in range(1, 5)):
            raise ValueError("source raster is not Byte RGBA")
        x, y = _offset(scene, target_transform)
        sources.append((number, scene, x, y))
    if rgba_path.parent != index_path.parent or rgba_path.parent.is_symlink():
        raise ValueError("outputs must share a real staging directory")
    rgba = _create(rgba_path, reference, 4, gdal.GDT_Byte)
    index = _create(index_path, reference, 1, gdal.GDT_UInt16)
    histogram = np.zeros(len(sources) + 1, dtype=np.uint64)
    try:
        width, height = reference.RasterXSize, reference.RasterYSize
        for top in range(0, height, block_size):
            h = min(block_size, height - top)
            for left in range(0, width, block_size):
                w = min(block_size, width - left)
                pixels = np.zeros((4, h, w), dtype=np.uint8)
                source_index = np.zeros((h, w), dtype=np.uint16)
                for number, scene, sx, sy in sources:
                    x0, x1 = max(left, sx), min(left + w, sx + scene.RasterXSize)
                    y0, y1 = max(top, sy), min(top + h, sy + scene.RasterYSize)
                    if x0 >= x1 or y0 >= y1:
                        continue
                    tile = scene.ReadAsArray(x0 - sx, y0 - sy, x1 - x0, y1 - y0)
                    if tile.shape != (4, y1 - y0, x1 - x0):
                        raise ValueError("source raster read shape differs")
                    if np.any((tile[3] != 0) & (tile[3] != 255)):
                        raise ValueError("source alpha is not binary")
                    valid = tile[3] == 255
                    dest = pixels[:, y0 - top:y1 - top, x0 - left:x1 - left]
                    dest[:, valid] = tile[:, valid]
                    source_index[y0 - top:y1 - top, x0 - left:x1 - left][valid] = number
                if not np.any(source_index):
                    histogram[0] += h * w
                    continue
                for band in range(4):
                    rgba.GetRasterBand(band + 1).WriteArray(pixels[band], left, top)
                index.GetRasterBand(1).WriteArray(source_index, left, top)
                histogram += np.bincount(source_index.ravel(), minlength=len(histogram)).astype(np.uint64)
        rgba.FlushCache()
        index.FlushCache()
    finally:
        rgba = None
        index = None
    return {str(number): int(count) for number, count in enumerate(histogram)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--grid-vrt", required=True, type=Path)
    parser.add_argument("--scene-table", required=True, type=Path)
    parser.add_argument("--rgba-out", required=True, type=Path)
    parser.add_argument("--index-out", required=True, type=Path)
    parser.add_argument("--block-size", type=int, default=512)
    args = parser.parse_args()
    result = compose(args.grid_vrt, args.scene_table, args.rgba_out,
                     args.index_out, args.block_size)
    print(json.dumps({"status": "UNPUBLISHED", "indexHistogram": result}, sort_keys=True), flush=True)


if __name__ == "__main__":
    main()
