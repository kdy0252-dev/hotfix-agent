#!/bin/zsh

set -euo pipefail

SCRIPT_DIRECTORY="${0:A:h}"
PROJECT_ROOT="${SCRIPT_DIRECTORY:h}"
ENV_FILE="${PROJECT_ROOT}/.env.local"
MODEL_CONFIG_FILE="${PROJECT_ROOT}/app/src/main/resources/models/litellm-models.yml"
source "${SCRIPT_DIRECTORY}/lib/grafana.zsh"

if [[ -f "${ENV_FILE}" ]]; then
  set -a
  source "${ENV_FILE}"
  set +a
  echo "Loaded ${ENV_FILE}."
fi

export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"

export BITBUCKET_BASE_URL="${BITBUCKET_BASE_URL:-https://api.bitbucket.org/2.0}"
export BITBUCKET_GIT_BASE_URL="${BITBUCKET_GIT_BASE_URL:-https://bitbucket.org}"
export BITBUCKET_WORKSPACE="${BITBUCKET_WORKSPACE:-autocrypt}"
export BITBUCKET_REPOSITORY="${BITBUCKET_REPOSITORY:-fms}"

export JENKINS_BASE_URL="${JENKINS_BASE_URL:-https://jenkins.autocrypt-fms.io}"
export JENKINS_ROOT_JOB="${JENKINS_ROOT_JOB:-FMS-EU}"
export JENKINS_TLS_VERIFY="${JENKINS_TLS_VERIFY:-false}"

export GRAFANA_BASE_URL="${GRAFANA_BASE_URL:-https://prod-grafana.autocrypt-fms.io}"
export GRAFANA_TLS_VERIFY="${GRAFANA_TLS_VERIFY:-false}"

export AGENT_MODE="${AGENT_MODE:-REPORT_ONLY}"
export AGENT_FMS_REPOSITORY_PATH="${AGENT_FMS_REPOSITORY_PATH:-${HOME}/workspace/fms}"
export AGENT_ANALYSIS_TTL="${AGENT_ANALYSIS_TTL:-24h}"
export AGENT_API_BIND_ADDRESS="${AGENT_API_BIND_ADDRESS:-127.0.0.1}"

if [[ -z "${BITBUCKET_TOKEN:-}" ]]; then
  read -r -s "BITBUCKET_TOKEN?Bitbucket access token: "
  echo
fi
[[ -n "${BITBUCKET_TOKEN}" ]] || { echo "Bitbucket access token is required." >&2; exit 1; }
export BITBUCKET_TOKEN

if [[ -z "${JENKINS_USER:-}" ]]; then
  read -r "JENKINS_USER?Jenkins username [autocrypt]: "
fi
JENKINS_USER="${JENKINS_USER:-autocrypt}"
export JENKINS_USER

if [[ -z "${JENKINS_TOKEN:-}" ]]; then
  read -r -s "JENKINS_TOKEN?Jenkins API token: "
  echo
fi
[[ -n "${JENKINS_TOKEN}" ]] || { echo "Jenkins API token is required." >&2; exit 1; }
export JENKINS_TOKEN

if [[ -z "${GRAFANA_TOKEN:-}" ]]; then
  read -r -s "GRAFANA_TOKEN?Grafana service account token: "
  echo
fi
[[ -n "${GRAFANA_TOKEN}" ]] || { echo "Grafana service account token is required." >&2; exit 1; }
export GRAFANA_TOKEN

if [[ -z "${GRAFANA_LOKI_DATASOURCE_UID:-}" \
  || -z "${GRAFANA_PROMETHEUS_DATASOURCE_UID:-}" \
  || -z "${GRAFANA_TEMPO_DATASOURCE_UID:-}" ]]; then
  echo "Discovering Grafana data source UIDs."
  discover_grafana_datasources
fi

if [[ -z "${LITELLM_BASE_URL:-}" ]]; then
  read -r "LITELLM_BASE_URL?LiteLLM URL [https://aigw.autocrypt.co.kr]: "
fi
LITELLM_BASE_URL="${LITELLM_BASE_URL:-https://aigw.autocrypt.co.kr}"
export LITELLM_BASE_URL

if [[ -z "${LITELLM_API_KEY:-}" ]]; then
  read -r -s "LITELLM_API_KEY?LiteLLM API key: "
  echo
fi
[[ -n "${LITELLM_API_KEY}" ]] || { echo "LiteLLM API key is required." >&2; exit 1; }
export LITELLM_API_KEY

if [[ -z "${LITELLM_MODEL:-}" ]]; then
  read -r "LITELLM_MODEL?LiteLLM model [chatgpt-5.6-luna]: "
fi
LITELLM_MODEL="${LITELLM_MODEL:-chatgpt-5.6-luna}"

SUPPORTED_LITELLM_MODELS=("${(@f)$(sed -nE 's/^  - name: "([^"]+)"$/\1/p' "${MODEL_CONFIG_FILE}")}")
if (( ${SUPPORTED_LITELLM_MODELS[(Ie)${LITELLM_MODEL}]} == 0 )); then
  echo "Unsupported LiteLLM model: ${LITELLM_MODEL}" >&2
  echo "Configured models: ${(j:, :)SUPPORTED_LITELLM_MODELS}" >&2
  exit 1
fi

export LITELLM_MODEL

export LITELLM_TRIAGE_MODEL="${LITELLM_TRIAGE_MODEL:-${LITELLM_MODEL}}"
export LITELLM_REASONING_MODEL="${LITELLM_REASONING_MODEL:-${LITELLM_MODEL}}"
export LITELLM_REVIEW_MODEL="${LITELLM_REVIEW_MODEL:-${LITELLM_REASONING_MODEL}}"

for ROLE_MODEL in "${LITELLM_TRIAGE_MODEL}" "${LITELLM_REASONING_MODEL}" "${LITELLM_REVIEW_MODEL}"; do
  if (( ${SUPPORTED_LITELLM_MODELS[(Ie)${ROLE_MODEL}]} == 0 )); then
    echo "Unsupported LiteLLM role model: ${ROLE_MODEL}" >&2
    exit 1
  fi
done

echo "Starting my-agent with triage=${LITELLM_TRIAGE_MODEL}, reasoning=${LITELLM_REASONING_MODEL}, review=${LITELLM_REVIEW_MODEL}."
cd "${PROJECT_ROOT}"
exec ./gradlew --no-daemon :app:bootRun
