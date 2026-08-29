#!/usr/bin/env bash
set -euo pipefail

backend_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
expected_java_home="${COFCO_JDK21_HOME:-/opt/homebrew/opt/openjdk@21}"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/cofco-jdk21-test.XXXXXX")"
legacy_java_home="${test_root}/legacy-jdk"
mkdir -p "${legacy_java_home}/bin"
printf '%s\n' '#!/usr/bin/env bash' 'echo '\''openjdk version "1.8.0"'\'' >&2' \
  >"${legacy_java_home}/bin/java"
chmod +x "${legacy_java_home}/bin/java"
trap 'rm -rf -- "$test_root"' EXIT

resolved_java_home="$({
  export JAVA_HOME="$legacy_java_home"
  export COFCO_JDK21_HOME="$expected_java_home"
  source "${backend_root}/scripts/jdk21-env.sh"
  printf '%s' "$JAVA_HOME"
})"

[[ "$resolved_java_home" == "$expected_java_home" ]] || {
  echo "[FAIL] legacy JAVA_HOME was not replaced with the repository JDK 21" >&2
  exit 1
}

wrapper_output="$(JAVA_HOME="$legacy_java_home" COFCO_JDK21_HOME="$expected_java_home" \
  "${backend_root}/scripts/mvn-jdk21.sh" --version)"
grep -Eq 'Java version: 21([.]|,)' <<<"$wrapper_output" || {
  echo "[FAIL] Maven wrapper did not run with JDK 21" >&2
  exit 1
}

echo "[OK] repository commands consistently select JDK 21"
