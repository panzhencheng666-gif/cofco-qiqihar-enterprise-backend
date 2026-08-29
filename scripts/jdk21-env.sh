#!/usr/bin/env bash

cofco_java_major() {
  "$1/bin/java" -version 2>&1 | awk -F '"' '
    /version/ {
      split($2, parts, ".")
      if (parts[1] == "1") print parts[2]
      else print parts[1]
      exit
    }
  '
}

cofco_jdk21_home=""
if [[ -x "${JAVA_HOME:-}/bin/java" ]] &&
  [[ "$(cofco_java_major "$JAVA_HOME")" == "21" ]]; then
  cofco_jdk21_home="$JAVA_HOME"
else
  for cofco_jdk_candidate in \
    "${COFCO_JDK21_HOME:-}" \
    /opt/homebrew/opt/openjdk@21 \
    /usr/local/opt/openjdk@21; do
    if [[ -n "$cofco_jdk_candidate" ]] &&
      [[ -x "${cofco_jdk_candidate}/bin/java" ]] &&
      [[ "$(cofco_java_major "$cofco_jdk_candidate")" == "21" ]]; then
      cofco_jdk21_home="$cofco_jdk_candidate"
      break
    fi
  done
fi

if [[ -z "$cofco_jdk21_home" ]]; then
  echo "JDK 21 is required; set JAVA_HOME to a JDK 21 installation." >&2
  return 1 2>/dev/null || exit 1
fi

export JAVA_HOME="$cofco_jdk21_home"
export PATH="$JAVA_HOME/bin:$PATH"
unset cofco_jdk21_home cofco_jdk_candidate
