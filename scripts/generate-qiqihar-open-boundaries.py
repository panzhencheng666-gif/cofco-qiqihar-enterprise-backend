#!/usr/bin/env python3
"""Reproduce the pinned Qiqihar county boundary extract and Flyway seed data."""

from __future__ import annotations

import argparse
import hashlib
import json
import tempfile
import urllib.request
from pathlib import Path


SOURCE_URL = (
    "https://media.githubusercontent.com/media/wmgeolab/geoBoundaries/9469f09/"
    "releaseData/gbOpen/CHN/ADM3/geoBoundaries-CHN-ADM3.geojson"
)
SOURCE_SHA256 = "3cc71d6cd23e7dbb5646422b40dd92a7a74d8779f340eb9377158c961dec310e"
DERIVED_SHA256 = "b80bf91b0c11ecc1b34fa3bc7a75cab0aaf9d197d452772de17d557c0cc0a824"

# Administrative codes are the platform's existing GB/T 2260 county identities. The
# source layer has no administrative-code field, so every match is pinned to its stable
# geoBoundaries feature id and reviewed English feature name.
FEATURES = (
    ("230202", "龙沙区", "Longsha District", "62558664B10391714074732"),
    ("230203", "建华区", "Jianhua District", "62558664B9030957209472"),
    ("230204", "铁锋区", "Tiefeng District", "62558664B6602116905760"),
    ("230205", "昂昂溪区", "Ang'angxi District", "62558664B84379514598009"),
    ("230206", "富拉尔基区", "Fularji District", "62558664B79904984618599"),
    ("230207", "碾子山区", "Nianzishan District", "62558664B50001016646076"),
    ("230208", "梅里斯达斡尔族区", "Meilisi Daur District", "62558664B30371997648461"),
    ("230221", "龙江县", "Longjiang County", "62558664B14005894926037"),
    ("230223", "依安县", "Yi'an County", "62558664B53755541107470"),
    ("230224", "泰来县", "Tailai County", "62558664B3570583361241"),
    ("230225", "甘南县", "Gannan County", "62558664B75943982247196"),
    ("230227", "富裕县", "Fuyu County", "62558664B59743501002139"),
    ("230229", "克山县", "Keshan County", "62558664B39264515469286"),
    ("230230", "克东县", "Kedong County", "62558664B85762436503804"),
    ("230231", "拜泉县", "Baiquan County", "62558664B14556220126727"),
    ("230281", "讷河市", "Nehe City", "62558664B94970929138909"),
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download() -> Path:
    target = Path(tempfile.mkdtemp(prefix="qiqihar-open-boundaries-")) / "source.geojson"
    with urllib.request.urlopen(SOURCE_URL) as response, target.open("wb") as output:
        while chunk := response.read(1024 * 1024):
            output.write(chunk)
    return target


def load_extract(source: Path) -> dict:
    actual = sha256(source)
    if actual != SOURCE_SHA256:
        raise SystemExit(f"source SHA-256 mismatch: expected {SOURCE_SHA256}, got {actual}")

    with source.open(encoding="utf-8") as input_file:
        dataset = json.load(input_file)
    crs_name = dataset.get("crs", {}).get("properties", {}).get("name")
    if crs_name != "urn:ogc:def:crs:OGC:1.3:CRS84":
        raise SystemExit(f"unexpected source CRS: {crs_name!r}")

    by_id = {feature["properties"]["shapeID"]: feature for feature in dataset["features"]}
    if len(by_id) != len(dataset["features"]):
        raise SystemExit("source contains duplicate geoBoundaries feature ids")
    if len({row[0] for row in FEATURES}) != 16 or len({row[3] for row in FEATURES}) != 16:
        raise SystemExit("pinned Qiqihar mapping must contain 16 unique codes and feature ids")
    extracted = []
    for region_code, region_name, source_name, source_id in FEATURES:
        feature = by_id.get(source_id)
        if feature is None:
            raise SystemExit(f"missing source feature {source_id} for {region_code}")
        properties = feature["properties"]
        if properties.get("shapeName") != source_name or properties.get("shapeType") != "ADM3":
            raise SystemExit(f"source identity drift for {region_code}: {properties!r}")
        geometry = feature["geometry"]
        if geometry.get("type") == "Polygon":
            geometry = {"type": "MultiPolygon", "coordinates": [geometry["coordinates"]]}
        if geometry.get("type") != "MultiPolygon" or not geometry.get("coordinates"):
            raise SystemExit(f"unsupported geometry for {region_code}: {geometry.get('type')}")
        extracted.append(
            {
                "type": "Feature",
                "properties": {
                    "regionCode": region_code,
                    "regionName": region_name,
                    "sourceFeatureName": source_name,
                    "sourceFeatureId": source_id,
                },
                "geometry": geometry,
            }
        )
    return {
        "type": "FeatureCollection",
        "name": "Qiqihar open administrative boundaries (county level)",
        "crs": {"type": "name", "properties": {"name": "urn:ogc:def:crs:EPSG::4326"}},
        "features": extracted,
    }


def sql_literal(value: str) -> str:
    return value.replace("'", "''")


def write_outputs(extract: dict, geojson_output: Path, sql_output: Path) -> None:
    geojson_output.parent.mkdir(parents=True, exist_ok=True)
    geojson_bytes = (json.dumps(extract, ensure_ascii=False, separators=(",", ":")) + "\n").encode()
    geojson_output.write_bytes(geojson_bytes)
    derived_sha256 = hashlib.sha256(geojson_bytes).hexdigest()
    if derived_sha256 != DERIVED_SHA256:
        raise SystemExit(
            f"derived SHA-256 mismatch: expected {DERIVED_SHA256}, got {derived_sha256}"
        )

    rows = []
    for feature in extract["features"]:
        properties = feature["properties"]
        geometry = json.dumps(feature["geometry"], ensure_ascii=False, separators=(",", ":"))
        rows.append(
            "  ('{code}','{name}','{source_name}','{source_id}',"
            "ST_SetSRID(ST_GeomFromGeoJSON($geojson$ {geometry} $geojson$),4326)"
            "::geometry(MultiPolygon,4326))".format(
                code=properties["regionCode"],
                name=sql_literal(properties["regionName"]),
                source_name=sql_literal(properties["sourceFeatureName"]),
                source_id=properties["sourceFeatureId"],
                geometry=geometry,
            )
        )

    template = Path(__file__).with_name("qiqihar-open-boundaries-v160-template.sql")
    rendered = template.read_text(encoding="utf-8")
    rendered = rendered.replace("@@DERIVED_GEOJSON_SHA256@@", derived_sha256)
    rendered = rendered.replace("@@BOUNDARY_ROWS@@", ",\n".join(rows))
    sql_output.write_text(rendered, encoding="utf-8")
    print(f"source_sha256={SOURCE_SHA256}")
    print(f"derived_geojson_sha256={derived_sha256}")
    print(f"features={len(rows)}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, help="already-downloaded pinned source GeoJSON")
    parser.add_argument("--geojson-output", type=Path, required=True)
    parser.add_argument("--sql-output", type=Path, required=True)
    args = parser.parse_args()
    source = args.source or download()
    write_outputs(load_extract(source), args.geojson_output, args.sql_output)


if __name__ == "__main__":
    main()
