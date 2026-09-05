#!/usr/bin/env bash

set -euo pipefail

mode="${1:-auto}"
shift || true

case "$mode" in
  full | novex-ui | novex-domain | novex-core | novex-document | skip)
    printf '%s\n' "$mode"
    exit 0
    ;;
  auto)
    ;;
  *)
    echo "unknown fast-check mode: $mode" >&2
    exit 2
    ;;
esac

if [[ "$#" -eq 0 ]]; then
  printf '%s\n' "full"
  exit 0
fi

core_only=true
for path in "$@"; do
  case "$path" in
    AGENTS.md | CONTEXT.md | README.md | docs/* | .github/release-notes/* | \
    src/android/novex-core/*)
      ;;
    *)
      core_only=false
      break
      ;;
  esac
done

if $core_only; then
  for path in "$@"; do
    case "$path" in
      src/android/novex-core/*)
        printf '%s\n' "novex-core"
        exit 0
        ;;
    esac
  done
fi

document_only=true
for path in "$@"; do
  case "$path" in
    AGENTS.md | CONTEXT.md | README.md | docs/* | .github/release-notes/* | \
    src/android/novex-core/* | \
    src/android/app/src/main/java/com/openminis/app/data/attachments/* | \
    src/android/app/src/test/java/com/openminis/app/data/attachments/* | \
    src/android/app/src/test/resources/docx/*)
      ;;
    *)
      document_only=false
      break
      ;;
  esac
done

if $document_only; then
  for path in "$@"; do
    case "$path" in
      src/android/app/src/main/java/com/openminis/app/data/attachments/* | \
      src/android/app/src/test/java/com/openminis/app/data/attachments/* | \
      src/android/app/src/test/resources/docx/*)
        printf '%s\n' "novex-document"
        exit 0
        ;;
    esac
  done
fi

domain_only=true
for path in "$@"; do
  case "$path" in
    AGENTS.md | CONTEXT.md | README.md | docs/* | .github/release-notes/* | \
    src/android/app/src/main/java/com/openminis/app/novex/domain/* | \
    src/android/app/src/test/java/com/openminis/app/novex/domain/*)
      ;;
    *)
      domain_only=false
      break
      ;;
  esac
done

if $domain_only; then
  for path in "$@"; do
    case "$path" in
      src/android/app/src/main/java/com/openminis/app/novex/domain/* | \
      src/android/app/src/test/java/com/openminis/app/novex/domain/*)
        printf '%s\n' "novex-domain"
        exit 0
        ;;
    esac
  done
fi

for path in "$@"; do
  case "$path" in
    AGENTS.md | CONTEXT.md | README.md | docs/* | .github/release-notes/*)
      ;;
    src/android/app/src/main/java/com/openminis/app/ui/novex/* | \
    src/android/app/src/test/java/com/openminis/app/ui/novex/* | \
    src/android/app/src/main/java/com/openminis/app/ui/sessions/Novex* | \
    src/android/app/src/test/java/com/openminis/app/ui/sessions/Novex* | \
    src/android/app/src/main/java/com/openminis/app/ui/navigation/Novex* | \
    src/android/app/src/test/java/com/openminis/app/ui/navigation/Novex* | \
    src/android/app/src/main/java/com/openminis/app/ui/settings/World* | \
    src/android/app/src/test/java/com/openminis/app/ui/settings/World* | \
    src/android/app/src/main/java/com/openminis/app/ui/settings/Character* | \
    src/android/app/src/test/java/com/openminis/app/ui/settings/Character* | \
    src/android/app/src/main/java/com/openminis/app/ui/settings/Catalog* | \
    src/android/app/src/test/java/com/openminis/app/ui/settings/Catalog* | \
    src/android/app/src/main/java/com/openminis/app/ui/settings/NovexNativeCard* | \
    src/android/app/src/test/java/com/openminis/app/ui/settings/NovexNativeCard* | \
    src/android/app/src/main/res/drawable/novex_* | \
    src/android/app/src/main/res/drawable-nodpi/novex_*)
      ;;
    *)
      printf '%s\n' "full"
      exit 0
      ;;
  esac
done

for path in "$@"; do
  case "$path" in
    src/android/*)
      printf '%s\n' "novex-ui"
      exit 0
      ;;
  esac
done

printf '%s\n' "skip"
