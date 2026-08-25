#!/bin/zsh

set -euo pipefail

if (( $# != 1 )); then
  print -u2 "Usage: $0 <langfuse-env-file>"
  exit 2
fi

SCRIPT_DIRECTORY="${0:A:h}"
PROJECT_ROOT="${SCRIPT_DIRECTORY:h}"
LOCAL_ENV_FILE="${PROJECT_ROOT}/.env.local"
LANGFUSE_ENV_FILE="$1"

[[ -f "${LOCAL_ENV_FILE}" ]] || {
  print -u2 "Missing ${LOCAL_ENV_FILE}. Run ./scripts/setup-env-local.zsh first."
  exit 1
}
[[ -f "${LANGFUSE_ENV_FILE}" ]] || {
  print -u2 "Missing ${LANGFUSE_ENV_FILE}. Run ./gradlew langfuseReady first."
  exit 1
}

set -a
source "${LOCAL_ENV_FILE}"
source "${LANGFUSE_ENV_FILE}"
set +a

export LANGFUSE_BASE_URL="${LANGFUSE_BASE_URL:-http://127.0.0.1:13000}"
export LANGFUSE_EVALUATION_MODEL="${LITELLM_EVALUATION_MODEL:-${LITELLM_MODEL}}"

"${SCRIPT_DIRECTORY}/ai-test/configure-runtime-evaluation.zsh"

LANGFUSE_AUTH="$(printf '%s' "${LANGFUSE_PUBLIC_KEY}:${LANGFUSE_SECRET_KEY}" | base64 | tr -d '\n')"
export OTEL_EXPORTER_OTLP_ENDPOINT="${LANGFUSE_BASE_URL%/}/api/public/otel"
export OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic ${LANGFUSE_AUTH},x-langfuse-ingestion-version=4"
export OTEL_SERVICE_NAME="my-agent"
export OTEL_TRACES_SAMPLER="always_on"

# This opt-in runtime exposes already-redacted AI request and response content only to local Langfuse.
export SPRING_AI_CHAT_OBSERVATIONS_LOG_PROMPT=true
export SPRING_AI_CHAT_OBSERVATIONS_LOG_COMPLETION=true
export SPRING_AI_CHAT_CLIENT_OBSERVATIONS_LOG_PROMPT=true
export SPRING_AI_CHAT_CLIENT_OBSERVATIONS_LOG_COMPLETION=true

print "Starting my-agent container with live Langfuse evaluation at ${LANGFUSE_BASE_URL}."
print "Every generation is evaluated; stop the retained stack with ./gradlew langfuseStop."
exec "${DOCKER_EXECUTABLE:-/usr/local/bin/docker}" compose \
  --project-name my-agent-ai-test \
  --env-file "${LOCAL_ENV_FILE}" \
  --env-file "${LANGFUSE_ENV_FILE}" \
  --file "${PROJECT_ROOT}/compose.yml" \
  --file "${PROJECT_ROOT}/infra/langfuse/compose.yml" \
  --file "${PROJECT_ROOT}/infra/langfuse/app.compose.yml" \
  up --build --detach --wait --wait-timeout 240 my-agent
