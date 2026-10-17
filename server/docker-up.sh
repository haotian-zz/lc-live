#!/usr/bin/env bash
# Build the image and start the compile server container (auto-restarts with the docker daemon).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
if docker compose version >/dev/null 2>&1; then
  docker compose up -d --build
else
  # Fallback for a docker CLI without the compose plugin.
  docker build -t lc-live:latest .
  docker rm -f lc-live >/dev/null 2>&1 || true
  docker run -d --name lc-live --restart unless-stopped -p 127.0.0.1:47321:47321 --memory 768m lc-live:latest
fi
for i in $(seq 1 60); do
  if curl -sf http://127.0.0.1:47321/health >/dev/null 2>&1; then echo "lc-live is up: $(curl -s http://127.0.0.1:47321/health)"; exit 0; fi
  sleep 0.5
done
echo "container started but /health not answering yet; see: docker logs lc-live" >&2; exit 1
