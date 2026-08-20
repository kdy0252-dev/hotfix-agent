#!/bin/zsh

discover_grafana_datasources() {
  local -a curl_options=(
    --silent
    --show-error
    --fail-with-body
    --connect-timeout 10
    --max-time 30
  )

  if [[ "${GRAFANA_TLS_VERIFY}" == "false" ]]; then
    curl_options+=(--insecure)
  fi

  if ! GRAFANA_DATASOURCES="$(
    curl "${curl_options[@]}" \
      --header "Authorization: Bearer ${GRAFANA_TOKEN}" \
      --header "Accept: application/json" \
      "${GRAFANA_BASE_URL%/}/api/datasources"
  )"; then
    echo "Failed to query Grafana data sources." >&2
    return 1
  fi

  if ! GRAFANA_LOKI_DATASOURCE_UID="$(
    jq -er '[.[] | select(.type == "loki")][0].uid' <<< "${GRAFANA_DATASOURCES}"
  )" || ! GRAFANA_PROMETHEUS_DATASOURCE_UID="$(
    jq -er '[.[] | select(.type == "prometheus")][0].uid' <<< "${GRAFANA_DATASOURCES}"
  )" || ! GRAFANA_TEMPO_DATASOURCE_UID="$(
    jq -er '[.[] | select(.type == "tempo")][0].uid' <<< "${GRAFANA_DATASOURCES}"
  )"; then
    echo "Grafana must provide Loki, Prometheus, and Tempo data sources." >&2
    return 1
  fi

  export GRAFANA_LOKI_DATASOURCE_UID
  export GRAFANA_PROMETHEUS_DATASOURCE_UID
  export GRAFANA_TEMPO_DATASOURCE_UID
}
