#!/bin/sh
set -eu

script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
sql_file="$script_directory/../ops/reset_local_2026_production_market.sql"
mode=${1:-preview}

case "$mode" in
  preview)
    apply=false
    expected_digest=''
    ;;
  apply)
    if [ "$#" -ne 2 ] || [ -z "$2" ]; then
      echo "usage: $0 apply <preview-reset-digest>" >&2
      exit 64
    fi
    apply=true
    expected_digest=$2
    ;;
  *)
    echo "usage: $0 [preview|apply <preview-reset-digest>]" >&2
    exit 64
    ;;
esac

exec psql --host=127.0.0.1 \
  --port=5432 \
  --dbname=qiqihar_enterprise_dev \
  --set=ON_ERROR_STOP=1 \
  --set=apply="$apply" \
  --set=expected_digest="$expected_digest" \
  --file="$sql_file"
