#!/usr/bin/env bash

set -euo pipefail

if [[ "$#" -ne 2 ]]; then
  echo "Usage: $0 LOCAL_HASH_MANIFEST REMOTE_HASH_MANIFEST" >&2
  exit 2
fi

local_manifest="$1"
remote_manifest="$2"

awk -F '\t' '
  NR == FNR { remote[$1] = $2; next }
  !($1 in remote) || remote[$1] != $2 { print $1 }
' "$remote_manifest" "$local_manifest"
