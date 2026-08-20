#!/usr/bin/env zsh

set -euo pipefail

if (( $# != 1 )); then
  print -u2 "Usage: $0 <output-env-file>"
  exit 2
fi

output_file="$1"
mkdir -p "${output_file:h}"

random_hex() {
  openssl rand -hex "$1"
}

umask 077
{
  print -r -- "POSTGRES_PASSWORD=$(random_hex 24)"
  print -r -- "CLICKHOUSE_PASSWORD=$(random_hex 24)"
  print -r -- "REDIS_AUTH=$(random_hex 24)"
  print -r -- "MINIO_ROOT_PASSWORD=$(random_hex 24)"
  print -r -- "SALT=$(random_hex 24)"
  print -r -- "ENCRYPTION_KEY=$(random_hex 32)"
  print -r -- "NEXTAUTH_SECRET=$(random_hex 32)"
  print -r -- "LANGFUSE_USER_PASSWORD=$(random_hex 24)"
  print -r -- "LANGFUSE_PUBLIC_KEY=lf_pk_$(random_hex 16)"
  print -r -- "LANGFUSE_SECRET_KEY=lf_sk_$(random_hex 16)"
} >| "$output_file"

print "Prepared ephemeral Langfuse credentials at ${output_file}"
