#!/usr/bin/env zsh

set -euo pipefail

base_url="${1:-http://127.0.0.1:13000}"
environment_file="${2:-}"

if [[ -z "$environment_file" || ! -f "$environment_file" ]]; then
  print -u2 "Usage: $0 <base-url> <langfuse-env-file>"
  exit 2
fi

source "$environment_file"

for attempt in {1..60}; do
  status_code="$(curl --silent --output /dev/null --write-out '%{http_code}' \
    --connect-timeout 2 --max-time 5 \
    --user "${LANGFUSE_PUBLIC_KEY}:${LANGFUSE_SECRET_KEY}" \
    "${base_url}/api/public/projects" || true)"
  if [[ "$status_code" == "200" ]]; then
    break
  fi
  sleep 2
done

if [[ "$status_code" != "200" ]]; then
  print -u2 "Langfuse did not become ready within 120 seconds: ${base_url}"
  exit 1
fi

print "Langfuse API is available; warming the first score write..."
write_status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
  --connect-timeout 5 --max-time 600 \
  --user "${LANGFUSE_PUBLIC_KEY}:${LANGFUSE_SECRET_KEY}" \
  --header 'Content-Type: application/json' \
  --data '{"id":"langfuse-write-readiness","sessionId":"ai-test-readiness","name":"langfuse-write-readiness","value":1.0,"dataType":"NUMERIC","comment":"Local AI test stack readiness check"}' \
  "${base_url}/api/public/scores")"

if [[ "$write_status" != 2* ]]; then
  print -u2 "Langfuse score API readiness failed with HTTP ${write_status}"
  exit 1
fi

print "Langfuse score API is ready at ${base_url}"
