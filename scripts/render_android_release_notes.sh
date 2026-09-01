#!/usr/bin/env bash
# Render concise release notes for a tested Android candidate.

set -euo pipefail

if [ "$#" -ne 3 ]; then
    echo "Usage: $0 VERSION SOURCE_SHA preview|stable" >&2
    exit 2
fi

VERSION="$1"
SOURCE_SHA="$2"
CHANNEL="$3"

if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Invalid stable version: $VERSION" >&2
    exit 1
fi
if ! [[ "$SOURCE_SHA" =~ ^[0-9a-f]{40}$ ]]; then
    echo "Invalid source SHA: $SOURCE_SHA" >&2
    exit 1
fi
case "$CHANNEL" in
    preview|stable) ;;
    *) echo "Unknown release-note channel: $CHANNEL" >&2; exit 2 ;;
esac

git cat-file -e "$SOURCE_SHA^{commit}"
NOTES_PATH=".github/release-notes/v$VERSION.md"

if [ "$CHANNEL" = "preview" ]; then
    printf '这是 Novex %s 的预览候选版，用于在正式发布前验证新功能与修复。\n\n' "$VERSION"
fi

if git cat-file -e "$SOURCE_SHA:$NOTES_PATH" 2>/dev/null; then
    git show "$SOURCE_SHA:$NOTES_PATH"
    exit 0
fi

PREVIOUS_TAG=""
while IFS= read -r tag; do
    normalized="${tag#v}"
    if [[ "$normalized" != *-* ]]; then
        PREVIOUS_TAG="$tag"
        break
    fi
done < <(git tag --merged "$SOURCE_SHA" --list 'v[0-9]*' --sort=-v:refname)

printf '• 本版本包含自上一正式版以来通过预览验证的功能改进与问题修复。\n'
if [ -n "$PREVIOUS_TAG" ]; then
    git log --no-merges --format='• %s' "$PREVIOUS_TAG..$SOURCE_SHA" | head -20
else
    git log --no-merges --format='• %s' -20 "$SOURCE_SHA"
fi
