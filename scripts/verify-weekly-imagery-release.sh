#!/usr/bin/env bash
set -euo pipefail

release_root="/var/lib/cofco/imagery"
maximum_age_hours="840"
verification_now=""
verify_systemd=true
python_command="python3"
[[ -x /usr/bin/python3.11 ]] && python_command="/usr/bin/python3.11"

usage() {
  echo "usage: $0 [--root PATH] [--max-age-hours HOURS] [--now ISO-8601] [--skip-systemd]" >&2
  exit 2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --root)
      [[ $# -ge 2 ]] || usage
      release_root="$2"
      shift 2
      ;;
    --max-age-hours)
      [[ $# -ge 2 ]] || usage
      maximum_age_hours="$2"
      shift 2
      ;;
    --now)
      [[ $# -ge 2 ]] || usage
      verification_now="$2"
      shift 2
      ;;
    --skip-systemd)
      verify_systemd=false
      shift
      ;;
    *) usage ;;
  esac
done

fail() {
  echo "WEEKLY_IMAGERY_RELEASE_FAILED: $*" >&2
  exit 1
}

[[ -d "$release_root" ]] || fail "release root is unavailable"
[[ -L "${release_root}/current" ]] || fail "current is not an atomic release symlink"

if $verify_systemd; then
  systemctl is-enabled --quiet cofco-weekly-imagery.timer || fail "timer is not enabled"
  systemctl is-active --quiet cofco-weekly-imagery.timer || fail "timer is not active"
fi

"$python_command" - "$release_root" "$maximum_age_hours" "$verification_now" <<'PY'
from __future__ import annotations

from datetime import datetime, timezone
from hashlib import sha256
import json
from pathlib import Path
import re
import sys


def fail(message: str) -> None:
    raise SystemExit(f"WEEKLY_IMAGERY_RELEASE_FAILED: {message}")


root = Path(sys.argv[1]).resolve()
try:
    maximum_age_hours = float(sys.argv[2])
except ValueError:
    fail("maximum age must be numeric")
if maximum_age_hours <= 0:
    fail("maximum age must be positive")

current_link = Path(sys.argv[1]) / "current"
try:
    current = current_link.resolve(strict=True)
    releases = (root / "releases").resolve(strict=True)
except OSError:
    fail("current release target is unavailable")
if current.parent != releases or not re.fullmatch(
    r"\d{4}-(?:W\d{2}|\d{2})(?:-r(?:[2-9]|[1-9]\d))?", current.name
):
    fail("current release escapes the governed releases directory")

metadata_path = current / "metadata.json"
manifest_path = current / "manifest.sha256"
if not metadata_path.is_file() or not manifest_path.is_file():
    fail("metadata or manifest is missing")

seen: set[str] = set()
tile_count = 0
for line in manifest_path.read_text().splitlines():
    parts = line.split("  ", 1)
    if len(parts) != 2 or not re.fullmatch(r"[0-9a-f]{64}", parts[0]):
        fail("manifest contains an invalid entry")
    relative = Path(parts[1])
    target = (current / relative).resolve()
    if current not in target.parents or not target.is_file():
        fail("manifest path escapes the release or is missing")
    if sha256(target.read_bytes()).hexdigest() != parts[0]:
        fail(f"checksum mismatch for {relative.as_posix()}")
    name = relative.as_posix()
    if name in seen:
        fail("manifest contains a duplicate entry")
    seen.add(name)
    if name.endswith(".webp"):
        tile_count += 1

if "metadata.json" not in seen or tile_count == 0:
    fail("manifest has no governed metadata or representative tile")

try:
    metadata = json.loads(metadata_path.read_text())
    version = str(metadata["version"])
    synced_at = datetime.fromisoformat(str(metadata["syncedAt"]).replace("Z", "+00:00"))
    acquisition_from = str(metadata["acquisitionFrom"])
    acquisition_to = str(metadata["acquisitionTo"])
    resolution = int(metadata["spatialResolutionMeters"])
    status = str(metadata["status"])
    product_ids = metadata["sourceProductIds"]
except (KeyError, TypeError, ValueError, json.JSONDecodeError):
    fail("metadata contract is invalid")

if version != current.name or resolution != 10 or status not in {"CURRENT", "STALE"}:
    fail("metadata does not identify a governed 10 metre release")
if not isinstance(product_ids, list) or not product_ids:
    fail("metadata has no source products")
if synced_at.tzinfo is None:
    fail("synchronization time has no timezone")

now_text = sys.argv[3]
try:
    now = (
        datetime.fromisoformat(now_text.replace("Z", "+00:00"))
        if now_text
        else datetime.now(timezone.utc)
    )
except ValueError:
    fail("verification time is invalid")
age_hours = (now.astimezone(timezone.utc) - synced_at.astimezone(timezone.utc)).total_seconds() / 3600
if age_hours < -1 or age_hours > maximum_age_hours:
    fail("current release synchronization time is outside the allowed age")

print(
    "WEEKLY_IMAGERY_RELEASE_OK"
    f" version={version} acquisition={acquisition_from}..{acquisition_to}"
    f" syncedAt={metadata['syncedAt']} status={status} tiles={tile_count}"
)
PY
