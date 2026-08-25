#!/bin/zsh

set -euo pipefail

SCRIPT_DIRECTORY="${0:A:h}"
PROJECT_ROOT="${SCRIPT_DIRECTORY:h}"
ENV_FILE="${PROJECT_ROOT}/.env.local"
MODEL_CONFIG_FILE="${PROJECT_ROOT}/app/src/main/resources/models/litellm-models.yml"
source "${SCRIPT_DIRECTORY}/lib/grafana.zsh"

if [[ -e "${ENV_FILE}" ]]; then
  read -r "OVERWRITE_ENV?${ENV_FILE} already exists. Overwrite? [y/N]: "
  [[ "${OVERWRITE_ENV:l}" == "y" ]] || { echo "Canceled."; exit 0; }
fi

read -r -s "BITBUCKET_TOKEN?Bitbucket access token: "
echo
[[ -n "${BITBUCKET_TOKEN}" ]] || { echo "Bitbucket access token is required." >&2; exit 1; }

read -r "JENKINS_USER?Jenkins username [autocrypt]: "
JENKINS_USER="${JENKINS_USER:-autocrypt}"

read -r -s "JENKINS_TOKEN?Jenkins API token: "
echo
[[ -n "${JENKINS_TOKEN}" ]] || { echo "Jenkins API token is required." >&2; exit 1; }

read -r "GRAFANA_BASE_URL?Grafana URL [https://prod-grafana.autocrypt-fms.io]: "
GRAFANA_BASE_URL="${GRAFANA_BASE_URL:-https://prod-grafana.autocrypt-fms.io}"

read -r -s "GRAFANA_TOKEN?Grafana service account token: "
echo
[[ -n "${GRAFANA_TOKEN}" ]] || { echo "Grafana service account token is required." >&2; exit 1; }

read -r "GRAFANA_TLS_VERIFY?Grafana TLS verify [false]: "
GRAFANA_TLS_VERIFY="${GRAFANA_TLS_VERIFY:-false}"
[[ "${GRAFANA_TLS_VERIFY}" == "true" || "${GRAFANA_TLS_VERIFY}" == "false" ]] || {
  echo "Grafana TLS verify must be true or false." >&2
  exit 1
}

discover_grafana_datasources

echo "Verified Grafana data sources:"
jq '[.[] | select(.type == "loki" or .type == "prometheus" or .type == "tempo") | {
  name,
  uid,
  type,
  access
}]' <<< "${GRAFANA_DATASOURCES}"

read -r "OBSERVABILITY_SERVICE_DISPLAY_NAME?Observed service display name [TARGET SERVICE]: "
OBSERVABILITY_SERVICE_DISPLAY_NAME="${OBSERVABILITY_SERVICE_DISPLAY_NAME:-TARGET SERVICE}"
read -r "OBSERVABILITY_REGION?Observed region [eu]: "
OBSERVABILITY_REGION="${OBSERVABILITY_REGION:-eu}"
read -r "OBSERVABILITY_APPLICATION?Observed application [app]: "
OBSERVABILITY_APPLICATION="${OBSERVABILITY_APPLICATION:-app}"
read -r "OBSERVABILITY_NAMESPACE_TEMPLATE?Namespace template [fms-eu-%s]: "
OBSERVABILITY_NAMESPACE_TEMPLATE="${OBSERVABILITY_NAMESPACE_TEMPLATE:-fms-eu-%s}"
read -r "OBSERVABILITY_SERVICE_NAME_TEMPLATE?Service label template [fms-eu-%s-app]: "
OBSERVABILITY_SERVICE_NAME_TEMPLATE="${OBSERVABILITY_SERVICE_NAME_TEMPLATE:-fms-eu-%s-app}"

read -r "AGENT_MODE?Agent mode [REPORT_ONLY]: "
AGENT_MODE="${AGENT_MODE:-REPORT_ONLY}"
[[ "${AGENT_MODE}" == "REPORT_ONLY" || "${AGENT_MODE}" == "DRAFT_PR" ]] || {
  echo "Agent mode must be REPORT_ONLY or DRAFT_PR." >&2
  exit 1
}

read -r "AGENT_FMS_REPOSITORY_PATH?FMS repository path [${HOME}/workspace/fms]: "
AGENT_FMS_REPOSITORY_PATH="${AGENT_FMS_REPOSITORY_PATH:-${HOME}/workspace/fms}"
[[ -d "${AGENT_FMS_REPOSITORY_PATH}/.git" ]] || {
  echo "FMS repository not found at ${AGENT_FMS_REPOSITORY_PATH}." >&2
  exit 1
}

read -r "AGENT_ANALYSIS_TTL?Analysis result TTL [72h]: "
AGENT_ANALYSIS_TTL="${AGENT_ANALYSIS_TTL:-72h}"

read -r "AGENT_API_BIND_ADDRESS?API bind address [127.0.0.1]: "
AGENT_API_BIND_ADDRESS="${AGENT_API_BIND_ADDRESS:-127.0.0.1}"

read -r "LITELLM_BASE_URL?LiteLLM URL [https://aigw.autocrypt.co.kr]: "
LITELLM_BASE_URL="${LITELLM_BASE_URL:-https://aigw.autocrypt.co.kr}"

read -r -s "LITELLM_API_KEY?LiteLLM API key: "
echo
[[ -n "${LITELLM_API_KEY}" ]] || { echo "LiteLLM API key is required." >&2; exit 1; }

read -r "LITELLM_MODEL?LiteLLM model [chatgpt-5.6-luna]: "
LITELLM_MODEL="${LITELLM_MODEL:-chatgpt-5.6-luna}"

SUPPORTED_LITELLM_MODELS=("${(@f)$(sed -nE 's/^  - name: "([^"]+)"$/\1/p' "${MODEL_CONFIG_FILE}")}")
if (( ${SUPPORTED_LITELLM_MODELS[(Ie)${LITELLM_MODEL}]} == 0 )); then
  echo "Unsupported LiteLLM model: ${LITELLM_MODEL}" >&2
  echo "Configured models: ${(j:, :)SUPPORTED_LITELLM_MODELS}" >&2
  exit 1
fi

read -r "LITELLM_TRIAGE_MODEL?Triage model [${LITELLM_MODEL}]: "
LITELLM_TRIAGE_MODEL="${LITELLM_TRIAGE_MODEL:-${LITELLM_MODEL}}"
read -r "LITELLM_REASONING_MODEL?Reasoning model [${LITELLM_MODEL}]: "
LITELLM_REASONING_MODEL="${LITELLM_REASONING_MODEL:-${LITELLM_MODEL}}"
read -r "LITELLM_REVIEW_MODEL?Review model [${LITELLM_REASONING_MODEL}]: "
LITELLM_REVIEW_MODEL="${LITELLM_REVIEW_MODEL:-${LITELLM_REASONING_MODEL}}"

for ROLE_MODEL in "${LITELLM_TRIAGE_MODEL}" "${LITELLM_REASONING_MODEL}" "${LITELLM_REVIEW_MODEL}"; do
  if (( ${SUPPORTED_LITELLM_MODELS[(Ie)${ROLE_MODEL}]} == 0 )); then
    echo "Unsupported LiteLLM role model: ${ROLE_MODEL}" >&2
    exit 1
  fi
done

read -r -s "OPENAI_API_KEY?OpenAI API key: "
echo
[[ -n "${OPENAI_API_KEY}" ]] || { echo "OpenAI API key is required." >&2; exit 1; }

umask 077
TEMP_ENV_FILE="$(mktemp "${PROJECT_ROOT}/.env.local.tmp.XXXXXX")"
trap 'rm -f "${TEMP_ENV_FILE}"' EXIT

{
  print -r -- "SPRING_PROFILES_ACTIVE=local"
  print -r -- ""
  print -r -- "BITBUCKET_BASE_URL=https://api.bitbucket.org/2.0"
  print -r -- "BITBUCKET_GIT_BASE_URL=https://bitbucket.org"
  print -r -- "BITBUCKET_WORKSPACE=autocrypt"
  print -r -- "BITBUCKET_REPOSITORY=fms"
  print -r -- "BITBUCKET_TOKEN=${(q)BITBUCKET_TOKEN}"
  print -r -- ""
  print -r -- "JENKINS_BASE_URL=https://jenkins.autocrypt-fms.io"
  print -r -- "JENKINS_ROOT_JOB=FMS-EU"
  print -r -- "JENKINS_USER=${(q)JENKINS_USER}"
  print -r -- "JENKINS_TOKEN=${(q)JENKINS_TOKEN}"
  print -r -- "JENKINS_TLS_VERIFY=false"
  print -r -- ""
  print -r -- "GRAFANA_BASE_URL=${(q)GRAFANA_BASE_URL}"
  print -r -- "GRAFANA_TOKEN=${(q)GRAFANA_TOKEN}"
  print -r -- "GRAFANA_TLS_VERIFY=${(q)GRAFANA_TLS_VERIFY}"
  print -r -- "GRAFANA_LOKI_DATASOURCE_UID=${(q)GRAFANA_LOKI_DATASOURCE_UID}"
  print -r -- "GRAFANA_PROMETHEUS_DATASOURCE_UID=${(q)GRAFANA_PROMETHEUS_DATASOURCE_UID}"
  print -r -- "GRAFANA_TEMPO_DATASOURCE_UID=${(q)GRAFANA_TEMPO_DATASOURCE_UID}"
  print -r -- "OBSERVABILITY_SERVICE_DISPLAY_NAME=${(q)OBSERVABILITY_SERVICE_DISPLAY_NAME}"
  print -r -- "OBSERVABILITY_REGION=${(q)OBSERVABILITY_REGION}"
  print -r -- "OBSERVABILITY_APPLICATION=${(q)OBSERVABILITY_APPLICATION}"
  print -r -- "OBSERVABILITY_NAMESPACE_TEMPLATE=${(q)OBSERVABILITY_NAMESPACE_TEMPLATE}"
  print -r -- "OBSERVABILITY_SERVICE_NAME_TEMPLATE=${(q)OBSERVABILITY_SERVICE_NAME_TEMPLATE}"
  print -r -- ""
  print -r -- "AGENT_MODE=${(q)AGENT_MODE}"
  print -r -- "AGENT_FMS_REPOSITORY_PATH=${(q)AGENT_FMS_REPOSITORY_PATH}"
  print -r -- "AGENT_ANALYSIS_TTL=${(q)AGENT_ANALYSIS_TTL}"
  print -r -- "AGENT_API_BIND_ADDRESS=${(q)AGENT_API_BIND_ADDRESS}"
  print -r -- "AGENT_AI_TRIAGE_MAX_INPUT_TOKENS=8000"
  print -r -- "AGENT_AI_TRIAGE_MAX_OUTPUT_TOKENS=1500"
  print -r -- "AGENT_AI_REASONING_MAX_INPUT_TOKENS=16000"
  print -r -- "AGENT_AI_REASONING_MAX_OUTPUT_TOKENS=4000"
  print -r -- "AGENT_AI_REVIEW_MAX_INPUT_TOKENS=8000"
  print -r -- "AGENT_AI_REVIEW_MAX_OUTPUT_TOKENS=1500"
  print -r -- "AGENT_AI_PROVIDER_MAX_ATTEMPTS=1"
  print -r -- "AGENT_AI_DATA_BINDING_MAX_ATTEMPTS=1"
  print -r -- "AGENT_PARITY_MAX_WORKERS=2"
  print -r -- "AGENT_NEWMAN_WORKSPACE_ROOT=${(q)PROJECT_ROOT}/.agent/runtime"
  print -r -- ""
  print -r -- "LITELLM_BASE_URL=${(q)LITELLM_BASE_URL}"
  print -r -- "LITELLM_API_KEY=${(q)LITELLM_API_KEY}"
  print -r -- "LITELLM_MODEL=${(q)LITELLM_MODEL}"
  print -r -- "LITELLM_TRIAGE_MODEL=${(q)LITELLM_TRIAGE_MODEL}"
  print -r -- "LITELLM_REASONING_MODEL=${(q)LITELLM_REASONING_MODEL}"
  print -r -- "LITELLM_REVIEW_MODEL=${(q)LITELLM_REVIEW_MODEL}"
  print -r -- ""
  print -r -- "OPENAI_API_KEY=${(q)OPENAI_API_KEY}"
} > "${TEMP_ENV_FILE}"

chmod 600 "${TEMP_ENV_FILE}"
mv -f "${TEMP_ENV_FILE}" "${ENV_FILE}"
trap - EXIT

echo "Created ${ENV_FILE} with permission 600."
echo "Run ./scripts/run-local.zsh to start the application."
