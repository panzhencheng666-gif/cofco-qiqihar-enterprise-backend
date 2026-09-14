#!/usr/bin/env bash
# Dedicated local public-search service. It never stops other containers.
set -euo pipefail
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
export DOCKER_CONTEXT=colima
search_state="${HOME}/Library/Application Support/COFCO Qiqihar Enterprise/regional-search"
search_image="searxng/searxng@sha256:14066ded90f0a2b0fc067b531066a297feedac74a603ce51c18cd83ea96d4298"
if ! docker info >/dev/null 2>&1; then
  colima start
fi
if docker inspect cofco-regional-search >/dev/null 2>&1; then
  [[ "$(docker inspect --format '{{.State.Running}}' cofco-regional-search)" == true ]] || docker start cofco-regional-search
else
  [[ -f "${search_state}/config/settings.yml" ]] || { echo 'Search configuration missing' >&2; exit 1; }
  docker run -d --name cofco-regional-search --restart unless-stopped \
    --memory 768m --cpus 1 --pids-limit 128 -p 127.0.0.1:63310:8080 \
    -v "${search_state}/config:/etc/searxng" -v "${search_state}/data:/var/cache/searxng" "$search_image"
fi
