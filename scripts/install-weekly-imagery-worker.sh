#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
environment_source=""

usage() {
  echo "usage: sudo $0 --env-file /secure/path/weekly-imagery.env" >&2
  exit 2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file)
      [[ $# -ge 2 ]] || usage
      environment_source="$2"
      shift 2
      ;;
    *) usage ;;
  esac
done

[[ "$(id -u)" -eq 0 ]] || { echo "installer must run as root" >&2; exit 1; }
[[ -n "$environment_source" && -f "$environment_source" && ! -L "$environment_source" ]] || {
  echo "a regular protected environment file is required" >&2
  exit 1
}

for command in python3.11 flock setfacl gdalbuildvrt gdalwarp gdal_translate gdal_calc.py gdal2tiles.py; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "required command is unavailable: $command" >&2
    exit 1
  }
done

if ! getent group cofco-imagery >/dev/null; then
  groupadd --system cofco-imagery
fi
if ! id cofco-imagery >/dev/null 2>&1; then
  useradd --system --gid cofco-imagery --home-dir /var/lib/cofco/imagery \
    --shell /usr/sbin/nologin cofco-imagery
fi

install -d -m 750 -o root -g cofco-imagery /etc/cofco
install -d -m 755 -o root -g root /usr/local/lib/cofco-imagery
setfacl -m u:cofco-imagery:--x /var/lib/cofco
install -d -m 750 -o cofco-imagery -g cofco-imagery /var/lib/cofco/imagery
runuser -u cofco-imagery -- test -x /var/lib/cofco
install -m 755 -o root -g root \
  "${backend_root}/scripts/weekly_imagery_sync.py" \
  /usr/local/lib/cofco-imagery/weekly_imagery_sync.py
install -m 755 -o root -g root \
  "${backend_root}/scripts/verify-weekly-imagery-release.sh" \
  /usr/local/bin/verify-weekly-imagery-release
install -m 644 -o root -g root \
  "${backend_root}/ops/imagery/qiqihar-aoi.geojson" \
  /usr/local/lib/cofco-imagery/qiqihar-aoi.geojson
install -m 600 -o root -g cofco-imagery \
  "$environment_source" /etc/cofco/weekly-imagery.env
install -m 644 -o root -g root \
  "${backend_root}/ops/systemd/cofco-weekly-imagery.service" \
  /etc/systemd/system/cofco-weekly-imagery.service
install -m 644 -o root -g root \
  "${backend_root}/ops/systemd/cofco-weekly-imagery.timer" \
  /etc/systemd/system/cofco-weekly-imagery.timer

set -a
# shellcheck disable=SC1091
source /etc/cofco/weekly-imagery.env
set +a
if [[ -n "${QIQIHAR_IMAGERY_BACKEND_READER_UID:-}" ]]; then
  [[ "$QIQIHAR_IMAGERY_BACKEND_READER_UID" =~ ^[1-9][0-9]*$ ]] || {
    echo "invalid imagery backend reader UID" >&2
    exit 1
  }
  setfacl -m "u:${QIQIHAR_IMAGERY_BACKEND_READER_UID}:--x" /var/lib/cofco
fi
runuser -u cofco-imagery -- \
  /usr/bin/python3.11 /usr/local/lib/cofco-imagery/weekly_imagery_sync.py \
  --root /var/lib/cofco/imagery --aoi /usr/local/lib/cofco-imagery/qiqihar-aoi.geojson \
  --dry-run

systemctl daemon-reload
systemctl enable --now cofco-weekly-imagery.timer
systemctl is-enabled cofco-weekly-imagery.timer
systemctl list-timers cofco-weekly-imagery.timer --no-pager

echo "WEEKLY_IMAGERY_WORKER_INSTALLED"
