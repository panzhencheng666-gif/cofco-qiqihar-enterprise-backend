#!/usr/bin/env bash
set -euo pipefail

overview_port="${COFCO_ENTERPRISE_OVERVIEW_PORT:-63200}"
overview_base_url="${COFCO_ENTERPRISE_OVERVIEW_BASE_URL:-http://127.0.0.1:${overview_port}}"
python_bin="${PYTHON_BIN:-/usr/bin/python3}"

if [[ ! -x "$python_bin" ]]; then
  echo "[FAIL] region hierarchy verifier requires Python 3: $python_bin" >&2
  exit 2
fi

fetch() {
  curl -fsS --max-time 12 "${overview_base_url}$1"
}

payload_file="$(mktemp "${TMPDIR:-/tmp}/cofco-region-hierarchy.XXXXXX")"
trap 'rm -f "$payload_file"' EXIT

fetch "/api/v1/master-data/regions" >"$payload_file"
"$python_bin" - "$payload_file" <<'PY'
import json
import sys
from collections import Counter, defaultdict

try:
    with open(sys.argv[1], encoding="utf-8") as source:
        payload = json.load(source)
    regions = payload["data"]
except (KeyError, TypeError, json.JSONDecodeError) as exc:
    raise SystemExit(f"[FAIL] invalid master-data region response: {exc}")

by_code = {item.get("code"): item for item in regions}
children = defaultdict(list)
for item in regions:
    children[item.get("parentCode")].append(item.get("code"))

expected = {
    "230200": ("齐齐哈尔市", {"COUNTY": 16, "TOWNSHIP": 118, "VILLAGE": 1197}),
    "231100": ("黑河市", {"COUNTY": 6, "TOWNSHIP": 65, "VILLAGE": 564}),
    "150700": ("呼伦贝尔市", {"COUNTY": 4, "TOWNSHIP": 49, "VILLAGE": 571}),
}

failures = []
for root_code, (root_name, expected_counts) in expected.items():
    root = by_code.get(root_code)
    if not root or root.get("name") != root_name or root.get("level") != "PREFECTURE":
        failures.append(f"missing prefecture {root_name}({root_code})")
        continue
    seen = set()
    pending = [root_code]
    counts = Counter()
    while pending:
        parent = pending.pop()
        for code in children.get(parent, []):
            if code in seen:
                failures.append(f"cycle or duplicate under {root_code}: {code}")
                continue
            seen.add(code)
            item = by_code.get(code, {})
            parent_level = by_code.get(parent, {}).get("level")
            expected_parent_level = {
                "COUNTY": "PREFECTURE",
                "TOWNSHIP": "COUNTY",
                "VILLAGE": "TOWNSHIP",
            }.get(item.get("level"))
            if expected_parent_level and parent_level != expected_parent_level:
                failures.append(
                    f"invalid hierarchy transition {parent}({parent_level}) -> "
                    f"{code}({item.get('level')})"
                )
            counts[item.get("level")] += 1
            pending.append(code)
    for level, expected_count in expected_counts.items():
        actual = counts[level]
        if actual != expected_count:
            failures.append(
                f"{root_name} {level} coverage expected {expected_count}, got {actual}"
            )

if failures:
    raise SystemExit("[FAIL] incomplete formal region hierarchy: " + "; ".join(failures))

print("[OK] formal master-data hierarchy covers Qiqihar, Heihe and Hulunbuir through villages")
PY

verify_overview_children() {
  local parent_code="$1"
  local expected_spec="$2"
  local query="/api/v1/overview/regions?productCode=CORN"
  if [[ -n "$parent_code" ]]; then
    query="${query}&parentCode=${parent_code}"
  fi
  fetch "$query" >"$payload_file"
  "$python_bin" - "$payload_file" "$expected_spec" <<'PY'
import json
import sys

try:
    with open(sys.argv[1], encoding="utf-8") as source:
        payload = json.load(source)
    items = payload["data"]
except (KeyError, TypeError, json.JSONDecodeError) as exc:
    raise SystemExit(f"[FAIL] invalid overview region response: {exc}")

by_code = {item.get("code"): item for item in items}
failures = []
for raw in sys.argv[2].split(","):
    code, name, parent, level = raw.split("|", 3)
    item = by_code.get(code)
    if not item:
        failures.append(f"missing {name}({code})")
        continue
    if item.get("name") != name:
        failures.append(f"{code} name expected {name}, got {item.get('name')}")
    expected_parent = None if parent == "ROOT" else parent
    if item.get("parentCode") != expected_parent:
        failures.append(f"{code} parent expected {expected_parent}, got {item.get('parentCode')}")
    if item.get("level") != level:
        failures.append(f"{code} level expected {level}, got {item.get('level')}")
    boundary = item.get("boundaryGeoJson")
    if not isinstance(boundary, str) or not boundary.strip():
        failures.append(f"{code} has no renderable boundaryGeoJson")
    else:
        try:
            geometry = json.loads(boundary)
            if geometry.get("type") not in {"Polygon", "MultiPolygon"}:
                failures.append(f"{code} boundary type is not Polygon/MultiPolygon")
        except (TypeError, json.JSONDecodeError):
            failures.append(f"{code} boundaryGeoJson is invalid JSON")

if failures:
    raise SystemExit("[FAIL] incomplete overview region hierarchy: " + "; ".join(failures))
PY
}

verify_overview_children "" \
  "230200|齐齐哈尔市|ROOT|PREFECTURE,231100|黑河市|ROOT|PREFECTURE,150700|呼伦贝尔市|ROOT|PREFECTURE"
verify_overview_children "231100" "231102|爱辉区|231100|COUNTY"
verify_overview_children "231102" "231102101|西岗子镇|231102|TOWNSHIP"
verify_overview_children "231102101" "231102101001|西岗子村|231102101|VILLAGE"
verify_overview_children "150700" "150721|阿荣旗|150700|COUNTY"
verify_overview_children "150721" "150721100|那吉镇|150721|TOWNSHIP"
verify_overview_children "150721100" "150721100001|那吉村|150721100|VILLAGE"

echo "[OK] overview API renders representative Heihe and Hulunbuir paths through villages"
