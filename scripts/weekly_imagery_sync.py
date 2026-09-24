#!/usr/bin/env python3
"""Build and atomically publish a monthly self-hosted Sentinel-2 tile release."""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence
from zoneinfo import ZoneInfo


DEFAULT_STAC_URL = "https://planetarycomputer.microsoft.com/api/stac/v1/search"
DEFAULT_COLLECTION = "sentinel-2-l2a"
DEFAULT_ALLOWED_HOSTS = (
    "stac.dataspace.copernicus.eu",
    "catalogue.dataspace.copernicus.eu",
    "download.dataspace.copernicus.eu",
    "eodata.dataspace.copernicus.eu",
    "earth-search.aws.element84.com",
    "sentinel-cogs.s3.us-west-2.amazonaws.com",
    "e84-earth-search-sentinel-data.s3.us-west-2.amazonaws.com",
    "e84-earth-search-sentinel-data.s3.amazonaws.com",
    "planetarycomputer.microsoft.com",
    "sentinel2l2a01.blob.core.windows.net",
)
EARTH_SEARCH_REGIONAL_HOST = "e84-earth-search-sentinel-data.s3.us-west-2.amazonaws.com"
EARTH_SEARCH_GLOBAL_HOST = "e84-earth-search-sentinel-data.s3.amazonaws.com"
PLANETARY_COMPUTER_HOST = "planetarycomputer.microsoft.com"
PLANETARY_SENTINEL_HOST = "sentinel2l2a01.blob.core.windows.net"
_PLANETARY_TOKEN_CACHE: dict[tuple[str, str], str] = {}
_PLANETARY_TOKEN_LOCK = threading.Lock()
REQUIRED_GDAL_COMMANDS = (
    "gdalbuildvrt",
    "gdalwarp",
    "gdal_translate",
    "gdal_calc.py",
    "gdal2tiles.py",
)


class ReleaseValidationError(RuntimeError):
    pass


@dataclass(frozen=True)
class WeekWindow:
    identifier: str
    start: date
    end: date


@dataclass(frozen=True)
class Candidate:
    product_id: str
    observed_at: str
    cloud_percent: float
    authorized: bool
    assets: Mapping[str, str]
    grid_code: str | None = None
    bounds: tuple[float, float, float, float] | None = None


@dataclass(frozen=True)
class SyncConfig:
    root: Path
    aoi: Path
    stac_url: str
    collection: str
    allowed_hosts: tuple[str, ...]
    maximum_cloud_percent: float
    minimum_zoom: int
    maximum_zoom: int
    request_timeout_seconds: int
    command_timeout_seconds: int
    bearer_token: str
    minimum_free_bytes: int
    retention_count: int
    backend_reader_uid: int | None = None


@dataclass(frozen=True)
class ReleaseMetadata:
    version: str
    provider: str
    acquisition_from: str
    acquisition_to: str
    synced_at: str
    spatial_resolution_meters: int
    cloud_coverage_percent: float
    status: str
    source_product_ids: tuple[str, ...]


def complete_week(now: datetime) -> WeekWindow:
    instant = now.astimezone(timezone.utc)
    current_monday = instant.date() - timedelta(days=instant.weekday())
    end = current_monday - timedelta(days=1)
    start = end - timedelta(days=6)
    iso_year, iso_week, _ = start.isocalendar()
    return WeekWindow(f"{iso_year}-W{iso_week:02d}", start, end)


def complete_month(now: datetime) -> WeekWindow:
    """Search the last 30 complete local days for each China-time publication."""
    local = now.astimezone(ZoneInfo("Asia/Shanghai"))
    end = local.date() - timedelta(days=1)
    return WeekWindow(local.strftime("%Y-%m"), end - timedelta(days=29), end)


def rank_candidates(
    candidates: Iterable[Candidate], maximum_cloud_percent: float
) -> list[Candidate]:
    eligible = [
        item
        for item in candidates
        if item.authorized and 0 <= item.cloud_percent <= maximum_cloud_percent
    ]
    return sorted(
        eligible,
        key=lambda item: (item.observed_at, -item.cloud_percent, item.product_id),
        reverse=True,
    )


def select_grid_candidates(ranked: Iterable[Candidate]) -> list[Candidate]:
    """Keep recent, broad-footprint and clear observations for each grid.

    A newer Sentinel scene can cover only a sliver of its MGRS grid. Retaining
    only that scene created transparent rectangles even when a complete scene
    from the same acquisition window was available.
    """
    ordered = list(ranked)
    grids: dict[str, list[Candidate]] = {}
    for candidate in ordered:
        grid_key = candidate.grid_code or f"product:{candidate.product_id}"
        grids.setdefault(grid_key, []).append(candidate)

    chosen_ids: set[str] = set()
    for scenes in grids.values():
        def footprint_area(scene: Candidate) -> float:
            if scene.bounds is None:
                return 0.0
            west, south, east, north = scene.bounds
            return max(0.0, east - west) * max(0.0, north - south)

        choices = [
            scenes[0],
            max(scenes, key=footprint_area),
            min(scenes, key=lambda scene: scene.cloud_percent),
        ]
        for scene in scenes:
            if len({item.product_id for item in choices}) >= 3:
                break
            choices.append(scene)
        chosen_ids.update(scene.product_id for scene in choices)
    return [candidate for candidate in ordered if candidate.product_id in chosen_ids]


def _mgrs_grid_code(properties: Mapping[str, Any], product_id: str) -> str | None:
    direct = properties.get("grid:code") or properties.get("s2:mgrs_tile")
    if isinstance(direct, str) and direct.strip():
        return direct.strip().upper()
    zone = properties.get("mgrs:utm_zone")
    latitude_band = properties.get("mgrs:latitude_band")
    grid_square = properties.get("mgrs:grid_square")
    if zone is not None and latitude_band and grid_square:
        try:
            return f"{int(zone):02d}{str(latitude_band).upper()}{str(grid_square).upper()}"
        except (TypeError, ValueError):
            pass
    match = re.search(r"(?:^|_)T?(\d{2}[A-Z]{3})(?:_|$)", product_id.upper())
    return match.group(1) if match else None


def require_free_space(root: Path, minimum_free_bytes: int) -> None:
    if minimum_free_bytes < 0:
        raise ValueError("minimum free bytes cannot be negative")
    root.mkdir(parents=True, exist_ok=True)
    free_bytes = shutil.disk_usage(root).free
    if free_bytes < minimum_free_bytes:
        raise RuntimeError(
            "insufficient free disk space for monthly imagery build: "
            f"available={free_bytes}, required={minimum_free_bytes}"
        )


def _scene_worker_count(candidate_count: int) -> int:
    configured = int(os.getenv("QIQIHAR_IMAGERY_SCENE_WORKERS", "1"))
    return min(candidate_count, 4, max(1, configured))


def _asset_href(assets: Mapping[str, Any], aliases: Sequence[str]) -> str | None:
    lowered = {str(key).lower(): value for key, value in assets.items()}
    for alias in aliases:
        value = lowered.get(alias.lower())
        if isinstance(value, Mapping):
            href = value.get("href")
            if isinstance(href, str) and href:
                return href
    return None


def _trusted_https(url: str, allowed_hosts: Sequence[str]) -> bool:
    parsed = urllib.parse.urlparse(url)
    host = (parsed.hostname or "").lower()
    return parsed.scheme == "https" and any(
        host == allowed or host.endswith("." + allowed) for allowed in allowed_hosts
    )


def parse_candidates(payload: Mapping[str, Any], allowed_hosts: Sequence[str]) -> list[Candidate]:
    parsed: list[Candidate] = []
    features = payload.get("features")
    if not isinstance(features, list):
        return parsed
    for feature in features:
        if not isinstance(feature, Mapping):
            continue
        properties = feature.get("properties")
        assets = feature.get("assets")
        product_id = feature.get("id")
        if not isinstance(properties, Mapping) or not isinstance(assets, Mapping):
            continue
        if not isinstance(product_id, str) or not product_id:
            continue
        observed_at = properties.get("datetime") or properties.get("start_datetime")
        if not isinstance(observed_at, str):
            continue
        try:
            cloud_percent = float(properties.get("eo:cloud_cover", 100.0))
        except (TypeError, ValueError):
            continue
        normalized = {
            "visual": _asset_href(assets, ("visual", "rendered_preview")),
            "red": _asset_href(assets, ("red", "b04", "B04_10m")),
            "green": _asset_href(assets, ("green", "b03", "B03_10m")),
            "blue": _asset_href(assets, ("blue", "b02", "B02_10m")),
            "scl": _asset_href(assets, ("scl", "SCL_20m", "scene-classification")),
        }
        has_rgb = normalized["visual"] is not None or all(
            normalized[channel] is not None for channel in ("red", "green", "blue")
        )
        required_urls = [value for value in normalized.values() if value is not None]
        authorized = (
            has_rgb
            and normalized["scl"] is not None
            and all(_trusted_https(url, allowed_hosts) for url in required_urls)
        )
        if not authorized:
            continue
        parsed.append(
            Candidate(
                product_id,
                observed_at,
                cloud_percent,
                True,
                {key: value for key, value in normalized.items() if value is not None},
                _mgrs_grid_code(properties, product_id),
                tuple(float(value) for value in feature["bbox"])
                if isinstance(feature.get("bbox"), list) and len(feature["bbox"]) == 4
                else None,
            )
        )
    return parsed


def _read_json_response(request: urllib.request.Request, timeout: int) -> Mapping[str, Any]:
    last_failure: urllib.error.URLError | None = None
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                if response.status < 200 or response.status >= 300:
                    raise RuntimeError(f"STAC returned HTTP {response.status}")
                content_type = response.headers.get_content_type()
                if content_type not in ("application/json", "application/geo+json"):
                    raise RuntimeError(f"STAC returned unsupported content type {content_type}")
                body = response.read(32 * 1024 * 1024 + 1)
                if len(body) > 32 * 1024 * 1024:
                    raise RuntimeError("STAC response exceeds 32 MiB")
                decoded = json.loads(body)
                if not isinstance(decoded, Mapping):
                    raise RuntimeError("STAC response is not an object")
                return decoded
        except urllib.error.URLError as failure:
            last_failure = failure
            if attempt < 3:
                time.sleep(2**attempt)
    raise RuntimeError("STAC request failed after retries") from last_failure


def _coordinates(value: Any) -> Iterable[tuple[float, float]]:
    if (
        isinstance(value, list)
        and len(value) >= 2
        and isinstance(value[0], (int, float))
        and isinstance(value[1], (int, float))
    ):
        yield float(value[0]), float(value[1])
        return
    if isinstance(value, list):
        for child in value:
            yield from _coordinates(child)


def aoi_bounds(path: Path) -> tuple[float, float, float, float]:
    payload = json.loads(path.read_text())
    geometries: list[Mapping[str, Any]] = []
    if payload.get("type") == "FeatureCollection":
        geometries.extend(
            feature["geometry"]
            for feature in payload.get("features", [])
            if isinstance(feature, Mapping) and isinstance(feature.get("geometry"), Mapping)
        )
    elif payload.get("type") == "Feature" and isinstance(payload.get("geometry"), Mapping):
        geometries.append(payload["geometry"])
    elif isinstance(payload, Mapping) and "coordinates" in payload:
        geometries.append(payload)
    points = [point for geometry in geometries for point in _coordinates(geometry.get("coordinates"))]
    if not points:
        raise ValueError("AOI contains no coordinates")
    longitudes = [point[0] for point in points]
    latitudes = [point[1] for point in points]
    bounds = min(longitudes), min(latitudes), max(longitudes), max(latitudes)
    if not (-180 <= bounds[0] < bounds[2] <= 180 and -90 <= bounds[1] < bounds[3] <= 90):
        raise ValueError("AOI bounds are invalid")
    return bounds


def _require_nonempty_tile_coverage(
    tiles: Path, bounds: tuple[float, float, float, float], zoom: int,
    minimum_ratio: float = 0.8,
) -> None:
    """Reject a sparse release before it can replace the published imagery."""
    west, south, east, north = bounds
    scale = 1 << zoom

    def tile_x(longitude: float) -> int:
        return min(scale - 1, max(0, math.floor((longitude + 180) / 360 * scale)))

    def tile_y(latitude: float) -> int:
        latitude = min(85.05112878, max(-85.05112878, latitude))
        radians = math.radians(latitude)
        mercator = math.log(math.tan(radians) + 1 / math.cos(radians))
        return min(scale - 1, max(0, math.floor((1 - mercator / math.pi) / 2 * scale)))

    x_range = range(tile_x(west), tile_x(east) + 1)
    y_range = range(tile_y(north), tile_y(south) + 1)
    expected = len(x_range) * len(y_range)
    present = sum(
        tile.is_file() and tile.stat().st_size > 300
        for x in x_range for y in y_range
        for tile in (tiles / str(zoom) / str(x) / f"{y}.webp",)
    )
    if present / expected < minimum_ratio:
        raise ReleaseValidationError(
            f"imagery tile coverage {present}/{expected} ({present / expected:.1%}) "
            f"at zoom {zoom} is below the {minimum_ratio:.0%} publication threshold"
        )


def search_candidates(config: SyncConfig, start: date, end: date) -> list[Candidate]:
    if not _trusted_https(config.stac_url, config.allowed_hosts):
        raise ValueError("STAC URL is not an allowed HTTPS endpoint")
    bbox = aoi_bounds(config.aoi)
    search_body = {
        "collections": [config.collection],
        "bbox": list(bbox),
        "datetime": f"{start.isoformat()}T00:00:00Z/{end.isoformat()}T23:59:59Z",
        "limit": 100,
        "sortby": [{"field": "properties.datetime", "direction": "desc"}],
    }
    headers = {"Accept": "application/geo+json", "Content-Type": "application/json"}
    if config.bearer_token:
        headers["Authorization"] = f"Bearer {config.bearer_token}"
    request = urllib.request.Request(
        config.stac_url, data=json.dumps(search_body).encode(), headers=headers, method="POST"
    )
    candidates: list[Candidate] = []
    seen_requests: set[tuple[str, bytes | None]] = set()
    for _ in range(20):
        key = (request.full_url, request.data)
        if key in seen_requests:
            raise RuntimeError("STAC pagination repeated a request")
        seen_requests.add(key)
        payload = _read_json_response(request, config.request_timeout_seconds)
        candidates.extend(parse_candidates(payload, config.allowed_hosts))
        links = payload.get("links", [])
        next_link = next(
            (link for link in links if isinstance(link, Mapping) and link.get("rel") == "next"),
            None,
        ) if isinstance(links, list) else None
        if next_link is None:
            return candidates
        href = next_link.get("href")
        configured = urllib.parse.urlparse(config.stac_url)
        next_endpoint = urllib.parse.urlparse(href) if isinstance(href, str) else None
        if (
            next_endpoint is None
            or next_endpoint.scheme != "https"
            or next_endpoint.netloc != configured.netloc
            or next_endpoint.path != configured.path
            or next_endpoint.fragment
        ):
            raise RuntimeError("STAC next page leaves the configured search endpoint")
        if next_link.get("method") != "POST":
            raise RuntimeError("STAC pagination requires an unsupported method")
        next_body = next_link.get("body")
        if not isinstance(next_body, Mapping):
            raise RuntimeError("STAC next page has no POST body")
        page_body = {**search_body, **next_body} if next_link.get("merge") else next_body
        request = urllib.request.Request(
            href, data=json.dumps(page_body).encode(), headers=headers, method="POST"
        )
    raise RuntimeError("STAC pagination exceeded 20 pages")


def _run(command: Sequence[str], timeout: int, cwd: Path | None = None) -> None:
    environment = os.environ.copy()
    if Path(command[0]).name.startswith("gdal"):
        environment.setdefault("GDAL_DISABLE_READDIR_ON_OPEN", "EMPTY_DIR")
        environment.setdefault("CPL_VSIL_CURL_ALLOWED_EXTENSIONS", ".tif,.TIF")
        environment.setdefault("GDAL_HTTP_MULTIRANGE", "YES")
        environment.setdefault("GDAL_HTTP_MERGE_CONSECUTIVE_RANGES", "YES")
        environment.setdefault("VSI_CACHE", "TRUE")
        environment.setdefault("VSI_CACHE_SIZE", "50000000")
        environment.setdefault("CPL_VSIL_CURL_CACHE_SIZE", "200000000")
    try:
        subprocess.run(
            list(command),
            cwd=cwd,
            env=environment,
            check=True,
            timeout=timeout,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
    except subprocess.TimeoutExpired as failure:
        raise RuntimeError(f"command timed out: {command[0]}") from failure
    except subprocess.CalledProcessError as failure:
        message = (failure.stderr or failure.stdout or "")[-4000:]
        message = re.sub(r"(?:/vsicurl/)?https://[^\s'\"<>]+", "[remote asset]", message)
        raise RuntimeError(f"command failed: {command[0]}: {message}") from failure


def _xyz_webp_relative(tms_png: Path) -> Path:
    if len(tms_png.parts) != 3 or tms_png.suffix.lower() != ".png":
        raise ValueError(f"invalid GDAL tile path: {tms_png}")
    try:
        zoom = int(tms_png.parts[0])
        x = int(tms_png.parts[1])
        tms_y = int(tms_png.stem)
    except ValueError as failure:
        raise ValueError(f"invalid GDAL tile path: {tms_png}") from failure
    if zoom < 0 or x < 0 or tms_y < 0:
        raise ValueError(f"invalid GDAL tile coordinates: {tms_png}")
    xyz_y = (1 << zoom) - 1 - tms_y
    if xyz_y < 0:
        raise ValueError(f"invalid GDAL tile coordinates: {tms_png}")
    return Path(str(zoom), str(x), f"{xyz_y}.webp")


def _legacy_webp_band_args(source: Path) -> list[str]:
    """Expand GDAL's grayscale+alpha PNG tiles to WebP-compatible RGBA."""
    header = source.read_bytes()[:26]
    if len(header) >= 26 and header[:8] == b"\x89PNG\r\n\x1a\n" and header[25] == 4:
        return ["-b", "1", "-b", "1", "-b", "1", "-b", "2"]
    return []


def _discard_tiny_webp_tiles(tiles: Path) -> int:
    """Remove GDAL's tiny opaque white no-data tiles before publication."""
    removed = 0
    for tile in tiles.rglob("*.webp"):
        if tile.stat().st_size > 300:
            continue
        data = tile.read_bytes()
        if len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WEBP":
            tile.unlink()
            removed += 1
    return removed


def _sample_alpha_coverage(raster: Path, size: int = 64) -> tuple[int, int]:
    """Sample band four without creating another large raster on the worker disk."""
    result = subprocess.run(
        ["gdal_translate", "-q", "-of", "XYZ", "-b", "4", "-outsize",
         str(size), str(size), str(raster), "/vsistdout/"],
        check=True,
        timeout=120,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    values = [line.rsplit(" ", 1)[-1] for line in result.stdout.splitlines()]
    if len(values) != size * size:
        raise ReleaseValidationError(f"alpha diagnostic returned {len(values)} samples for {raster.name}")
    return sum(float(value) > 0 for value in values), len(values)


def _log_alpha_coverage(stage: str, raster: Path, size: int = 64) -> None:
    valid, total = _sample_alpha_coverage(raster, size)
    print(f"imagery diagnostic {stage}: alpha {valid}/{total} ({valid / total:.1%})", file=sys.stderr, flush=True)


def _build_web_tiles(mosaic: Path, tiles: Path, config: SyncConfig) -> None:
    try:
        help_result = subprocess.run(
            ["gdal2tiles.py", "--help"],
            check=True,
            timeout=min(config.command_timeout_seconds, 120),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
    except (OSError, subprocess.SubprocessError) as failure:
        raise RuntimeError("unable to inspect gdal2tiles.py capabilities") from failure
    zoom = f"--zoom={config.minimum_zoom}-{config.maximum_zoom}"
    if "--xyz" in help_result.stdout and "--tiledriver" in help_result.stdout:
        _run(
            [
                "gdal2tiles.py",
                "--xyz",
                "--exclude",
                "--webviewer=none",
                "--tiledriver=WEBP",
                "--webp-quality=82",
                zoom,
                "--processes=2",
                str(mosaic),
                str(tiles),
            ],
            config.command_timeout_seconds,
        )
        return

    tms_tiles = tiles.parent / ".tiles-tms"
    shutil.rmtree(tms_tiles, ignore_errors=True)
    _run(
        [
            "gdal2tiles.py",
            "--exclude",
            "--webviewer=none",
            zoom,
            "--processes=2",
            str(mosaic),
            str(tms_tiles),
        ],
        config.command_timeout_seconds,
    )
    sources = sorted(tms_tiles.glob("*/*/*.png"))
    if not sources:
        raise ReleaseValidationError("legacy GDAL produced no PNG imagery tiles")

    def convert(source: Path) -> None:
        destination = tiles / _xyz_webp_relative(source.relative_to(tms_tiles))
        destination.parent.mkdir(parents=True, exist_ok=True)
        _run(
            [
                "gdal_translate",
                "-of",
                "WEBP",
                "-co",
                "QUALITY=82",
                *_legacy_webp_band_args(source),
                str(source),
                str(destination),
            ],
            config.command_timeout_seconds,
        )

    workers = min(4, max(1, os.cpu_count() or 1))
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        list(executor.map(convert, sources))
    shutil.rmtree(tms_tiles)


def _vsicurl(url: str, allowed_hosts: Sequence[str], timeout: int = 60) -> str:
    if not _trusted_https(url, allowed_hosts):
        raise ValueError("imagery asset is not on an allowed HTTPS host")
    parsed = urllib.parse.urlparse(url)
    if parsed.hostname == EARTH_SEARCH_REGIONAL_HOST:
        parsed = parsed._replace(netloc=EARTH_SEARCH_GLOBAL_HOST)
        url = urllib.parse.urlunparse(parsed)
    elif parsed.hostname == PLANETARY_SENTINEL_HOST:
        path_parts = parsed.path.lstrip("/").split("/", 1)
        if len(path_parts) != 2 or not path_parts[0]:
            raise RuntimeError("Planetary Computer asset URL has no container")
        account = parsed.hostname.split(".", 1)[0]
        container = path_parts[0]
        cache_key = (account, container)
        with _PLANETARY_TOKEN_LOCK:
            token = _PLANETARY_TOKEN_CACHE.get(cache_key)
            if token is None:
                token_url = (
                    f"https://{PLANETARY_COMPUTER_HOST}/api/sas/v1/token/"
                    f"{urllib.parse.quote(account, safe='')}/"
                    f"{urllib.parse.quote(container, safe='')}"
                )
                token_payload = _read_json_response(
                    urllib.request.Request(token_url), timeout
                )
                token_value = token_payload.get("token")
                if not isinstance(token_value, str) or not token_value:
                    raise RuntimeError("Planetary Computer token response has no token")
                token = token_value.lstrip("?")
                _PLANETARY_TOKEN_CACHE[cache_key] = token
        url = f"{url}{'&' if parsed.query else '?'}{token}"
    return "/vsicurl/" + url


def _invalidate_planetary_token(url: str) -> None:
    parsed = urllib.parse.urlparse(url)
    if parsed.hostname != PLANETARY_SENTINEL_HOST:
        return
    container = parsed.path.lstrip("/").split("/", 1)[0]
    if not container:
        return
    account = parsed.hostname.split(".", 1)[0]
    with _PLANETARY_TOKEN_LOCK:
        _PLANETARY_TOKEN_CACHE.pop((account, container), None)


def _run_remote_warp(
    config: SyncConfig,
    url: str,
    warp_common: Sequence[str],
    resampling: str,
    destination: Path,
) -> None:
    """Retry a transient remote GDAL read with a newly signed source URL."""
    for attempt in range(3):
        try:
            source = _vsicurl(url, config.allowed_hosts, config.request_timeout_seconds)
            _run(
                ["gdalwarp", *warp_common, "-r", resampling, source, str(destination)],
                config.command_timeout_seconds,
            )
            return
        except RuntimeError as failure:
            destination.unlink(missing_ok=True)
            if attempt == 2:
                raise RuntimeError(
                    f"remote imagery warp failed after 3 attempts: {destination.name}"
                ) from failure
            _invalidate_planetary_token(url)
            time.sleep(2**attempt)


def _check_gdal() -> None:
    missing = [command for command in REQUIRED_GDAL_COMMANDS if shutil.which(command) is None]
    if missing:
        raise RuntimeError("missing GDAL commands: " + ", ".join(missing))


def _scene_band_expressions() -> list[str]:
    # Sentinel SCL uses 255 for pixels outside a scene. An exclusion list
    # accidentally treated 255 as clear and published opaque white tiles.
    clear = "((D==2)|(D==4)|(D==5)|(D==6)|(D==7))"
    return [f"{band}*{clear}" for band in "ABC"] + [f"255*{clear}"]


def _build_scene(config: SyncConfig, candidate: Candidate, work: Path, index: int) -> Path:
    scene = work / f"scene-{index:03d}"
    scene.mkdir()
    rgb = scene / "rgb.tif"
    visual_url = candidate.assets.get("visual")
    if visual_url is not None:
        # Warp the cloud-optimized remote asset directly. Copying every complete
        # 100 km Sentinel scene first exhausts the small production volume before
        # the AOI crop is applied.
        rgb_source = None
    else:
        vrt = scene / "rgb.vrt"
        _run(
            [
                "gdalbuildvrt",
                "-separate",
                str(vrt),
                *[
                    _vsicurl(
                        candidate.assets[channel],
                        config.allowed_hosts,
                        config.request_timeout_seconds,
                    )
                    for channel in ("red", "green", "blue")
                ],
            ],
            config.command_timeout_seconds,
        )
        _run(
            [
                "gdal_translate",
                "-ot",
                "Byte",
                "-scale",
                "0",
                "3000",
                "0",
                "255",
                "-co",
                "TILED=YES",
                str(vrt),
                str(rgb),
            ],
            config.command_timeout_seconds,
        )
        rgb_source = str(rgb)
    warped_rgb = scene / "rgb-3857.tif"
    warped_scl = scene / "scl-3857.tif"
    aoi_west, aoi_south, aoi_east, aoi_north = aoi_bounds(config.aoi)
    if candidate.bounds is not None:
        scene_west, scene_south, scene_east, scene_north = candidate.bounds
        west = max(aoi_west, scene_west)
        south = max(aoi_south, scene_south)
        east = min(aoi_east, scene_east)
        north = min(aoi_north, scene_north)
        if west >= east or south >= north:
            raise RuntimeError(f"candidate does not intersect AOI: {candidate.product_id}")
    else:
        west, south, east, north = aoi_west, aoi_south, aoi_east, aoi_north
    warp_common = [
        "-t_srs",
        "EPSG:3857",
        "-tr",
        "10",
        "10",
        "-tap",
        "-te_srs",
        "EPSG:4326",
        "-te",
        str(west),
        str(south),
        str(east),
        str(north),
        "-cutline",
        str(config.aoi),
        "-multi",
        "-wo",
        "NUM_THREADS=ALL_CPUS",
        "-co",
        "TILED=YES",
        "-co",
        "COMPRESS=DEFLATE",
        "-co",
        "BIGTIFF=IF_SAFER",
    ]
    if visual_url is not None:
        _run_remote_warp(config, visual_url, warp_common, "cubic", warped_rgb)
    else:
        _run(
            ["gdalwarp", *warp_common, "-r", "cubic", rgb_source, str(warped_rgb)],
            config.command_timeout_seconds,
        )
    _run_remote_warp(config, candidate.assets["scl"], warp_common, "near", warped_scl)
    masked = scene / "masked.tif"
    _run(
        [
            "gdal_calc.py",
            "-A",
            str(warped_rgb),
            "--A_band=1",
            "-B",
            str(warped_rgb),
            "--B_band=2",
            "-C",
            str(warped_rgb),
            "--C_band=3",
            "-D",
            str(warped_scl),
            "--D_band=1",
            *[f"--calc={expression}" for expression in _scene_band_expressions()],
            "--type=Byte",
            "--NoDataValue=0",
            "--co=TILED=YES",
            "--co=COMPRESS=DEFLATE",
            "--co=BIGTIFF=IF_SAFER",
            f"--outfile={masked}",
        ],
        config.command_timeout_seconds,
    )
    warped_rgb.unlink()
    warped_scl.unlink()
    if rgb.exists():
        rgb.unlink()
    return masked


def build_release(
    config: SyncConfig, window: WeekWindow, candidates: Sequence[Candidate], now: datetime
) -> Path:
    _check_gdal()
    config.root.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=f".staging-{window.identifier}-", dir=config.root))
    try:
        work = staging / "work"
        work.mkdir()
        indexed_candidates = list(enumerate(reversed(candidates)))

        def build_scene(item: tuple[int, Candidate]) -> Path:
            index, candidate = item
            require_free_space(config.root, config.minimum_free_bytes)
            return _build_scene(config, candidate, work, index)

        worker_count = _scene_worker_count(len(indexed_candidates))
        if worker_count == 1:
            scenes = [build_scene(item) for item in indexed_candidates]
        else:
            with concurrent.futures.ThreadPoolExecutor(max_workers=worker_count) as executor:
                scenes = list(executor.map(build_scene, indexed_candidates))
        for (_, candidate), scene in zip(indexed_candidates, scenes):
            _log_alpha_coverage(f"scene grid={candidate.grid_code or 'unknown'}", scene)
        require_free_space(config.root, config.minimum_free_bytes)
        mosaic_vrt = work / "mosaic.vrt"
        _run(
            [
                "gdalbuildvrt",
                "-srcnodata",
                "0 0 0 0",
                "-vrtnodata",
                "0 0 0 0",
                str(mosaic_vrt),
                *[str(scene) for scene in scenes],
            ],
            config.command_timeout_seconds,
        )
        _log_alpha_coverage("mosaic-vrt", mosaic_vrt, 256)
        mosaic = work / "mosaic.tif"
        _run(
            [
                "gdal_translate",
                "-of",
                "GTiff",
                "-co",
                "TILED=YES",
                "-co",
                "COMPRESS=DEFLATE",
                "-co",
                "BIGTIFF=IF_SAFER",
                str(mosaic_vrt),
                str(mosaic),
            ],
            config.command_timeout_seconds,
        )
        _log_alpha_coverage("mosaic-tiff", mosaic, 256)
        require_free_space(config.root, config.minimum_free_bytes)
        tiles = staging / "tiles"
        _build_web_tiles(mosaic, tiles, config)
        tile_count = sum(1 for _ in tiles.rglob("*.webp"))
        removed = _discard_tiny_webp_tiles(tiles)
        print(
            f"imagery diagnostic webp tiles: generated={tile_count} "
            f"tiny_removed={removed} retained={tile_count - removed}",
            file=sys.stderr, flush=True,
        )
        if not any(tiles.rglob("*.webp")):
            raise ReleaseValidationError("GDAL produced no imagery tiles")
        _require_nonempty_tile_coverage(tiles, aoi_bounds(config.aoi), config.maximum_zoom)
        observed = sorted(candidate.observed_at for candidate in candidates)
        metadata = {
            "version": window.identifier,
            "provider": "Copernicus Sentinel-2 L2A",
            "attribution": "European Union, Copernicus Sentinel-2 imagery",
            "updateCadence": "MONTHLY",
            "acquisitionFrom": observed[0],
            "acquisitionTo": observed[-1],
            "syncedAt": now.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
            "spatialResolutionMeters": 10,
            "cloudCoveragePercent": round(
                sum(candidate.cloud_percent for candidate in candidates) / len(candidates), 2
            ),
            "status": "CURRENT",
            "sourceProductIds": [candidate.product_id for candidate in candidates],
            "truthStatement": "Latest available cloud-filtered observation; not live video.",
        }
        (staging / "metadata.json").write_text(
            json.dumps(metadata, ensure_ascii=False, sort_keys=True) + "\n"
        )
        shutil.rmtree(work)
        _write_manifest(staging)
        validate_release(staging)
        return staging
    except BaseException:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def _manifest_entries(path: Path) -> Iterable[Path]:
    for candidate in sorted(path.rglob("*")):
        if candidate.is_file() and candidate.name != "manifest.sha256":
            yield candidate.relative_to(path)


def _write_manifest(path: Path) -> None:
    lines = []
    for relative in _manifest_entries(path):
        digest = hashlib.sha256((path / relative).read_bytes()).hexdigest()
        lines.append(f"{digest}  {relative.as_posix()}\n")
    (path / "manifest.sha256").write_text("".join(lines))


def validate_release(path: Path) -> ReleaseMetadata:
    root = path.resolve()
    metadata_path = root / "metadata.json"
    manifest_path = root / "manifest.sha256"
    if not metadata_path.is_file() or not manifest_path.is_file():
        raise ReleaseValidationError("release metadata or manifest is missing")
    seen: set[str] = set()
    for line in manifest_path.read_text().splitlines():
        try:
            expected, relative_text = line.split("  ", 1)
        except ValueError as failure:
            raise ReleaseValidationError("invalid manifest line") from failure
        relative = Path(relative_text)
        target = (root / relative).resolve()
        if root not in target.parents or not target.is_file():
            raise ReleaseValidationError("manifest path escapes or is missing")
        actual = hashlib.sha256(target.read_bytes()).hexdigest()
        if expected != actual:
            raise ReleaseValidationError(f"checksum mismatch for {relative_text}")
        seen.add(relative.as_posix())
    if "metadata.json" not in seen or not any(name.endswith(".webp") for name in seen):
        raise ReleaseValidationError("release has no governed metadata or imagery tile")
    try:
        payload = json.loads(metadata_path.read_text())
        metadata = ReleaseMetadata(
            str(payload["version"]),
            str(payload["provider"]),
            str(payload["acquisitionFrom"]),
            str(payload["acquisitionTo"]),
            str(payload["syncedAt"]),
            int(payload["spatialResolutionMeters"]),
            float(payload["cloudCoveragePercent"]),
            str(payload["status"]),
            tuple(str(item) for item in payload["sourceProductIds"]),
        )
    except (KeyError, TypeError, ValueError, json.JSONDecodeError) as failure:
        raise ReleaseValidationError("release metadata is invalid") from failure
    if metadata.spatial_resolution_meters != 10 or metadata.status not in ("CURRENT", "STALE"):
        raise ReleaseValidationError("release metadata violates the imagery contract")
    if not metadata.source_product_ids:
        raise ReleaseValidationError("release metadata has no source products")
    return metadata


def publish_release(
    root: Path, staging: Path, backend_reader_uid: int | None = None
) -> Path:
    metadata = validate_release(staging)
    releases = root / "releases"
    releases.mkdir(parents=True, exist_ok=True)
    destination = releases / metadata.version
    if destination.exists():
        existing = validate_release(destination)
        if existing != metadata:
            raise ReleaseValidationError("release version already exists with different metadata")
        shutil.rmtree(staging)
    else:
        staging.rename(destination)
    if backend_reader_uid is not None:
        if backend_reader_uid <= 0:
            raise ValueError("imagery backend reader UID must be positive")
        _run(["setfacl", "-m", f"u:{backend_reader_uid}:rx", str(root)], 120)
        _run(["setfacl", "-m", f"u:{backend_reader_uid}:rx", str(releases)], 120)
        _run(["setfacl", "-R", "-m", f"u:{backend_reader_uid}:rX", str(destination)], 120)
    temporary_link = root / ".current.new"
    temporary_link.unlink(missing_ok=True)
    temporary_link.symlink_to(destination)
    temporary_link.replace(root / "current")
    return destination


def retain_releases(root: Path, keep: int = 4) -> None:
    if keep < 1:
        raise ValueError("at least one release must be retained")
    releases = root / "releases"
    if not releases.is_dir():
        return
    current = (root / "current").resolve() if (root / "current").exists() else None
    valid = []
    for candidate in releases.iterdir():
        if candidate.is_dir():
            try:
                valid.append((validate_release(candidate).version, candidate))
            except ReleaseValidationError:
                continue
    valid.sort(key=lambda item: item[0])
    protected = {path.resolve() for _, path in valid[-keep:]}
    if current is not None:
        protected.add(current)
    for _, path in valid:
        if path.resolve() not in protected:
            shutil.rmtree(path)


def _config(arguments: argparse.Namespace) -> SyncConfig:
    allowed_hosts = tuple(
        host.strip().lower()
        for host in os.getenv("QIQIHAR_IMAGERY_ALLOWED_HOSTS", ",".join(DEFAULT_ALLOWED_HOSTS)).split(",")
        if host.strip()
    )
    reader_uid_text = os.getenv("QIQIHAR_IMAGERY_BACKEND_READER_UID", "")
    return SyncConfig(
        root=arguments.root.resolve(),
        aoi=arguments.aoi.resolve(),
        stac_url=os.getenv("QIQIHAR_IMAGERY_STAC_URL", DEFAULT_STAC_URL),
        collection=os.getenv("QIQIHAR_IMAGERY_COLLECTION", DEFAULT_COLLECTION),
        allowed_hosts=allowed_hosts,
        maximum_cloud_percent=float(os.getenv("QIQIHAR_IMAGERY_MAX_CLOUD_PERCENT", "30")),
        minimum_zoom=int(os.getenv("QIQIHAR_IMAGERY_MIN_ZOOM", "5")),
        maximum_zoom=int(os.getenv("QIQIHAR_IMAGERY_MAX_ZOOM", "14")),
        request_timeout_seconds=int(os.getenv("QIQIHAR_IMAGERY_REQUEST_TIMEOUT_SECONDS", "60")),
        command_timeout_seconds=int(os.getenv("QIQIHAR_IMAGERY_COMMAND_TIMEOUT_SECONDS", "18000")),
        bearer_token=os.getenv("COPERNICUS_ACCESS_TOKEN", ""),
        minimum_free_bytes=int(
            os.getenv("QIQIHAR_IMAGERY_MINIMUM_FREE_BYTES", str(6 * 1024**3))
        ),
        retention_count=int(os.getenv("QIQIHAR_IMAGERY_RETENTION_COUNT", "2")),
        backend_reader_uid=int(reader_uid_text) if reader_uid_text else None,
    )


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument(
        "--aoi",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "ops/imagery/qiqihar-aoi.geojson",
    )
    parser.add_argument("--now", help="UTC ISO instant used for deterministic operation")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--force", action="store_true")
    arguments = parser.parse_args(argv)
    now = (
        datetime.fromisoformat(arguments.now.replace("Z", "+00:00"))
        if arguments.now
        else datetime.now(timezone.utc)
    )
    window = complete_month(now)
    if arguments.dry_run:
        print(
            json.dumps(
                {"version": window.identifier, "start": str(window.start), "end": str(window.end)},
                sort_keys=True,
            )
        )
        return 0
    config = _config(arguments)
    if not config.aoi.is_file():
        raise RuntimeError(f"AOI file is missing: {config.aoi}")
    current = config.root / "current"
    if current.exists() and current.resolve().name == window.identifier and not arguments.force:
        validate_release(current.resolve())
        print(f"monthly imagery already published: {window.identifier}")
        return 0
    candidates = rank_candidates(
        search_candidates(config, window.start, window.end), config.maximum_cloud_percent
    )
    if not candidates:
        expanded_start = window.start - timedelta(days=30)
        candidates = rank_candidates(
            search_candidates(config, expanded_start, window.end), config.maximum_cloud_percent
        )
    if not candidates:
        raise RuntimeError("no authorized cloud-qualified Sentinel-2 product is available")
    candidates = select_grid_candidates(candidates)
    require_free_space(config.root, config.minimum_free_bytes)
    staging = build_release(config, window, candidates, now)
    published = publish_release(config.root, staging, config.backend_reader_uid)
    retain_releases(config.root, keep=config.retention_count)
    print(f"monthly imagery published: {published.name}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ReleaseValidationError, RuntimeError, ValueError) as failure:
        print(f"monthly imagery sync failed: {failure}", file=sys.stderr)
        raise SystemExit(1)
