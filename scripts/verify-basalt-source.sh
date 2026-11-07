#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASALT_DIR="$ROOT/third_party/basalt"
EXPECTED_BASALT="0f3b2b52c807f70ff4e2973ce253c73329eea7bc"
EXPECTED_VCPKG="1e199d32ad53aab1defda61ce41c380302e3f95c"

test -f "$ROOT/.gitmodules"
git -C "$BASALT_DIR" rev-parse --is-inside-work-tree >/dev/null
test "$(git -C "$BASALT_DIR" rev-parse HEAD)" = "$EXPECTED_BASALT"

if [[ -n "$(git -C "$BASALT_DIR" status --porcelain --untracked-files=all)" ]]; then
  echo "Basalt submodule worktree is modified; upstream source must remain pristine." >&2
  exit 1
fi

VCPKG_ENTRY="$(git -C "$BASALT_DIR" ls-tree "$EXPECTED_BASALT" thirdparty/vcpkg)"
grep -q "$EXPECTED_VCPKG" <<<"$VCPKG_ENTRY"
test -f "$BASALT_DIR/LICENSE"

echo "Basalt source pin verified: $EXPECTED_BASALT"
echo "Basalt nested vcpkg pin verified: $EXPECTED_VCPKG"
echo "Basalt upstream worktree is clean."
