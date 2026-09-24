#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
verifier="${backend_root}/scripts/verify-weekly-imagery-release.sh"
fixture="$(mktemp -d)"
trap 'rm -rf -- "$fixture"' EXIT

fail() {
  echo "weekly imagery release verification contract failed: $*" >&2
  exit 1
}

[[ -x "$verifier" ]] || fail "missing executable verifier"

release="${fixture}/releases/2026-09"
mkdir -p "${release}/tiles/5/26"
printf 'fixture-webp' > "${release}/tiles/5/26/11.webp"
cat > "${release}/metadata.json" <<'JSON'
{"version":"2026-09","provider":"Copernicus Sentinel-2 L2A","updateCadence":"MONTHLY","acquisitionFrom":"2026-09-18","acquisitionTo":"2026-09-20","syncedAt":"2026-09-22T00:00:00Z","spatialResolutionMeters":10,"cloudCoveragePercent":8.4,"status":"CURRENT","sourceProductIds":["S2B_20260920_QIQIHAR"],"truthStatement":"Latest available observation; not live video."}
JSON
python3 - "$release" <<'PY'
from hashlib import sha256
from pathlib import Path
import sys

release = Path(sys.argv[1])
entries = []
for path in sorted(release.rglob("*")):
    if path.is_file() and path.name != "manifest.sha256":
        relative = path.relative_to(release).as_posix()
        entries.append(f"{sha256(path.read_bytes()).hexdigest()}  {relative}\n")
(release / "manifest.sha256").write_text("".join(entries))
PY
ln -s "releases/2026-09" "${fixture}/current"

output="$({
  bash "$verifier" \
    --root "$fixture" \
    --now 2026-09-22T06:00:00Z \
    --max-age-hours 48 \
    --skip-systemd
} 2>&1)" || fail "$output"

[[ "$output" == *"WEEKLY_IMAGERY_RELEASE_OK version=2026-09"* ]] || \
  fail "success marker missing: $output"

printf 'tampered' >> "${release}/tiles/5/26/11.webp"
if bash "$verifier" --root "$fixture" --now 2026-09-22T06:00:00Z \
  --max-age-hours 48 --skip-systemd >/dev/null 2>&1; then
  fail "tampered release was accepted"
fi

echo "WEEKLY_IMAGERY_RELEASE_VERIFIER_CONTRACT_OK"
