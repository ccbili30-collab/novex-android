#!/usr/bin/env bash
# Preview or trash only known, reproducible Novex local outputs.

set -euo pipefail

MODE="${1:---dry-run}"
if [ "$MODE" != "--dry-run" ] && [ "$MODE" != "--apply" ]; then
    echo "Usage: $0 [--dry-run|--apply]" >&2
    exit 2
fi

REPO_ROOT="$(git -C "$(dirname "$0")/.." rev-parse --show-toplevel)"
if [ ! -d "$REPO_ROOT/.git" ] && [ ! -f "$REPO_ROOT/.git" ]; then
    echo "Refusing to clean outside a Git worktree: $REPO_ROOT" >&2
    exit 1
fi

TARGETS=(
    "artifacts"
    "dist"
    "novex.apk"
    ".DS_Store"
    "src/android/.gradle"
    "src/android/.cxx"
    "src/android/app/build"
)

trash_path() {
    local absolute="$1"
    case "$(uname -s)" in
        Darwin)
            osascript - "$absolute" <<'APPLESCRIPT' >/dev/null
on run argv
    set targetItem to POSIX file (item 1 of argv) as alias
    tell application "Finder" to delete targetItem
end run
APPLESCRIPT
            ;;
        Linux)
            if command -v gio >/dev/null 2>&1; then
                gio trash "$absolute"
            else
                echo "No recoverable trash command is available; refusing to delete $absolute" >&2
                return 1
            fi
            ;;
        *)
            echo "Unsupported platform; refusing to delete $absolute" >&2
            return 1
            ;;
    esac
}

found=0
total_kib=0
for relative in "${TARGETS[@]}"; do
    absolute="$REPO_ROOT/$relative"
    [ -e "$absolute" ] || continue
    case "$absolute" in
        "$REPO_ROOT"/*) ;;
        *)
            echo "Refusing path outside repository: $absolute" >&2
            exit 1
            ;;
    esac
    if ! git -C "$REPO_ROOT" check-ignore -q -- "$relative"; then
        echo "Refusing non-ignored path: $relative" >&2
        exit 1
    fi
    kib="$(du -sk "$absolute" | awk '{print $1}')"
    total_kib=$((total_kib + kib))
    found=$((found + 1))
    printf '%s\t%s KiB\n' "$relative" "$kib"
    if [ "$MODE" = "--apply" ]; then
        trash_path "$absolute"
    fi
done

printf 'Targets: %d; estimated size: %d MiB; mode: %s\n' \
    "$found" "$((total_kib / 1024))" "$MODE"

if [ "$MODE" = "--dry-run" ]; then
    echo "Nothing changed. Run again with --apply to move these paths to the system trash."
else
    echo "Selected paths were moved to the system trash and remain recoverable until it is emptied."
fi
