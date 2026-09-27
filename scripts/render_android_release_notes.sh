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
    # [T-release-notes-sigpipe] 用 git 自带的 -n 限制替代 `| head`：两个正式
    # 版之间非合并提交超过 20 条时 head 提前关闭管道，git log 收 SIGPIPE，
    # set -o pipefail 把它变成 141（发布步骤三连失败实证 2026-09-26）。
    git log -20 --no-merges --format='• %s' "$PREVIOUS_TAG..$SOURCE_SHA"
else
    git log --no-merges --format='• %s' -20 "$SOURCE_SHA"
fi
