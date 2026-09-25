#!/usr/bin/env python3
"""Independent four-region tile and source-date gate before an r4 switch."""

from __future__ import annotations

import argparse
from collections import deque
import json
import math
from pathlib import Path


def tile_x(longitude: float, zoom: int) -> int:
    return min((1 << zoom) - 1, max(0, math.floor((longitude + 180) / 360 * (1 << zoom))))


def tile_y(latitude: float, zoom: int) -> int:
    latitude = min(85.05112878, max(-85.05112878, latitude))
    radians = math.radians(latitude)
    mercator = math.log(math.tan(radians) + 1 / math.cos(radians))
    return min((1 << zoom) - 1, max(0, math.floor((1 - mercator / math.pi) / 2 * (1 << zoom))))


def region_bounds(feature: dict) -> tuple[float, float, float, float]:
    points = feature["geometry"]["coordinates"][0]
    return (min(point[0] for point in points), min(point[1] for point in points),
            max(point[0] for point in points), max(point[1] for point in points))


def check_region(tiles: Path, feature: dict, zoom: int) -> dict:
    code = feature["properties"]["regionCode"]
    west, south, east, north = region_bounds(feature)
    x0, x1 = tile_x(west, zoom), tile_x(east, zoom)
    y0, y1 = tile_y(north, zoom), tile_y(south, zoom)
    expected = (x1 - x0 + 1) * (y1 - y0 + 1)
    missing = set()
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            tile = tiles / str(zoom) / str(x) / f"{y}.webp"
            if not tile.is_file() or tile.stat().st_size <= 300:
                missing.add((x, y))
    present = expected - len(missing)
    ratio = present / expected
    edge = deque((x, y) for x, y in missing
                 if x in (x0, x1) or y in (y0, y1))
    exterior = set(edge)
    while edge:
        x, y = edge.popleft()
        for neighbor in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            if neighbor in missing and neighbor not in exterior:
                exterior.add(neighbor)
                edge.append(neighbor)
    interior = missing - exterior
    return {"regionCode": code, "expected": expected, "present": present,
            "coverage": ratio, "missing": len(missing), "internalMissing": len(interior),
            "internalExamples": sorted(interior)[:8]}


def verify(release: Path, aoi: Path) -> dict:
    metadata = json.loads((release / "metadata.json").read_text())
    plan = json.loads((release / "source-plan.json").read_text())
    if metadata.get("version") != "2026-09-r4" or plan.get("version") != metadata["version"]:
        raise ValueError("candidate version mismatch")
    if metadata.get("spatialResolutionMeters") != 10 or plan.get("rgbResolutionMeters") != 10:
        raise ValueError("RGB resolution mismatch")
    if plan.get("sclResolutionMeters") != 20:
        raise ValueError("SCL resolution mismatch")
    features = plan.get("features", [])
    ids = [item["id"] for item in features]
    dates = [item["properties"]["datetime"] for item in features]
    if not features or len(set(ids)) != len(ids) or set(ids) != set(metadata.get("sourceProductIds", [])):
        raise ValueError("source products do not match the release")
    if metadata.get("acquisitionFrom") != min(dates) or metadata.get("acquisitionTo") != max(dates):
        raise ValueError("acquisition date range does not match products")
    if any(not "2026-08-01" <= day[:10] <= "2026-09-20" for day in dates):
        raise ValueError("source outside approved dates")
    if not any("2026-09-14" <= day[:10] <= "2026-09-20" for day in dates):
        raise ValueError("recent source absent")
    if not any("2026-08-01" <= day[:10] <= "2026-09-13" for day in dates):
        raise ValueError("historical fill absent")
    if "历史10米RGB" not in metadata.get("truthStatement", ""):
        raise ValueError("mixed-date disclosure absent")
    for item in features:
        for band, expected in (("visual", 10), ("red", 10), ("green", 10), ("blue", 10), ("scl", 20)):
            asset = item["assets"][band]
            if asset["gsd"] != expected:
                raise ValueError(f"{item['id']} {band} resolution mismatch")
            if (not asset["href"].startswith("https://sentinel-cogs.s3.us-west-2.amazonaws.com/")
                    or not asset["href"].endswith("/TCI.tif" if band == "visual" else ".tif")):
                raise ValueError(f"{item['id']} {band} source mismatch")
    aoi_features = json.loads(aoi.read_text())["features"]
    codes = {feature["properties"]["regionCode"] for feature in aoi_features}
    if codes != {"230200", "150700", "231100", "232700"} or codes != set(metadata.get("coverageRegionCodes", [])):
        raise ValueError("four-region metadata mismatch")
    regions = [check_region(release / "tiles", feature, 14) for feature in aoi_features]
    result = {"version": metadata["version"], "sourceCount": len(ids),
              "acquisitionFrom": min(dates), "acquisitionTo": max(dates),
              "regions": regions}
    if any(region["coverage"] < .99 or region["internalMissing"] for region in regions):
        raise ValueError("candidate failed independent coverage/internal-hole gate: " + json.dumps(result))
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--release", required=True, type=Path)
    parser.add_argument("--aoi", required=True, type=Path)
    arguments = parser.parse_args()
    print(json.dumps(verify(arguments.release, arguments.aoi), ensure_ascii=False, sort_keys=True))
