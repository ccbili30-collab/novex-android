#!/usr/bin/env bash
set -euo pipefail

base="${1:?missing version base}"
stage="${2:?missing preview stage}"
number="${3:?missing run number}"
if ! [[ "$base" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && "$stage" =~ ^(beta|rc)$ && "$number" =~ ^[1-9][0-9]{0,3}$ ]]; then
  echo 'invalid preview version arguments' >&2
  exit 1
fi

# Both preview stages share the same Android version-code sequence.
while IFS= read -r tag || [[ -n "$tag" ]]; do
  if [[ "$tag" =~ ^v"$base"-(beta|rc)\.([1-9][0-9]*)$ ]]; then
    published="${BASH_REMATCH[2]}"
    if [[ "${#published}" -gt 4 ]]; then
      echo 'published preview number is outside the supported range' >&2
      exit 1
    fi
    if (( published >= number )); then number="$((published + 1))"; fi
  fi
done
if (( number >= 9999 )); then
  echo 'preview sequence is exhausted; advance the version base before publishing' >&2
  exit 1
fi
printf '%s-%s.%s\n' "$base" "$stage" "$number"
