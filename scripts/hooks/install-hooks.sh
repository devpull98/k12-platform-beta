#!/usr/bin/env bash
# Cài tất cả Git hooks từ scripts/hooks/ vào .git/hooks/
# Usage: bash scripts/hooks/install-hooks.sh [repo-root]
set -euo pipefail

REPO_ROOT="${1:-$(git rev-parse --show-toplevel 2>/dev/null || echo ".")}"
HOOKS_SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOOKS_DST="$REPO_ROOT/.git/hooks"

if [[ ! -d "$HOOKS_DST" ]]; then
  echo "ERROR: .git/hooks not found at $HOOKS_DST"
  echo "       Run from inside a git repository."
  exit 1
fi

INSTALLED=0
SKIPPED=0

for src in "$HOOKS_SRC"/*; do
  hook_name=$(basename "$src")
  # Skip install script itself and non-hook files
  [[ "$hook_name" == "install-hooks.sh" ]] && continue
  [[ "$hook_name" == *.sh ]] && continue
  [[ "$hook_name" == *.md ]] && continue

  dst="$HOOKS_DST/$hook_name"

  if [[ -f "$dst" && ! -L "$dst" ]]; then
    echo "SKIP: $hook_name already exists (not a symlink) — backup to $dst.bak"
    cp "$dst" "$dst.bak"
  fi

  ln -sf "$src" "$dst"
  chmod +x "$src"
  echo "INSTALL: $hook_name → $dst"
  ((INSTALLED++)) || true
done

echo ""
echo "Done: $INSTALLED hook(s) installed."
echo "To verify: ls -la $HOOKS_DST"
