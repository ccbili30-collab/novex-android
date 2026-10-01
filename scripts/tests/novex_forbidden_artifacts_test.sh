#!/usr/bin/env bash
# 门禁：git 树里不得出现已退役的沙箱/GPL 二进制（2026-10-01 污染事故的防再犯闸）。
set -euo pipefail
cd "$(dirname "$0")/../../../.." 2>/dev/null || cd "$(git rev-parse --show-toplevel)"
BAD=$(git ls-tree -r HEAD --name-only | grep -iE "alpine-minirootfs|proot-aarch64|libproot|libtalloc|libandroid-shmem|^deps/|^src/android/app/src/main/cpp/pty_bridge" || true)
if [ -n "$BAD" ]; then
  echo "❌ 退役二进制混入 git 树（GPL 回归风险）：" >&2
  echo "$BAD" >&2
  exit 1
fi
echo "✅ forbidden artifacts gate: clean"
