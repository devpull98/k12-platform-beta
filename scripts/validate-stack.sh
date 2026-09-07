#!/usr/bin/env bash
# Verify project-context.yaml tồn tại + khai báo stack, và stack rules có mặt.
# Usage: ./scripts/validate-stack.sh
# Exit 0 = OK; Exit 1 = thiếu project-context.yaml / thiếu stack / thiếu rule.
# Escape: GOVERNANCE_SKIP=1 → bỏ qua (dùng cho repo dev kit, CI đặc thù).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "=== Stack Validation ==="

if [[ "${GOVERNANCE_SKIP:-}" == "1" ]]; then
  echo "SKIP: GOVERNANCE_SKIP=1"; exit 0
fi

# ── Gate onboarding: không có project-context.yaml → kit không resolve được {stack} ──
if [[ ! -f project-context.yaml ]]; then
  echo "FAIL: thiếu project-context.yaml ở root dự án."
  echo "  → Chạy skill onboarding, hoặc copy mẫu và set 'stack:'. Kit cần file này để resolve {stack} rules."
  echo "  → Escape tạm: GOVERNANCE_SKIP=1"
  exit 1
fi

STACK=$(grep -E '^stack:' project-context.yaml 2>/dev/null | sed 's/stack:[[:space:]]*//;s/[[:space:]]*#.*//' | tr -d '\r' | xargs | head -1)

if [[ -z "$STACK" ]]; then
  echo "FAIL: project-context.yaml chưa khai báo 'stack:' (giá trị rỗng)."
  echo "  → Set 'stack: <spring|laravel|golang|nodejs|...>' trong project-context.yaml."
  exit 1
fi

echo "Stack: $STACK"

MISSING=0
for rule in architecture test-patterns; do
  path="rules/$STACK/$rule.mdc"
  if [[ -f "$path" ]]; then
    echo "  OK: $path"
  else
    echo "  MISSING: $path"
    echo "    → Copy from rules/_template/$rule.mdc and customize for your stack."
    MISSING=$((MISSING + 1))
  fi
done

if [[ $MISSING -gt 0 ]]; then
  echo ""
  echo "FAIL: $MISSING stack rule file(s) missing. Dev must create before qc-automation/tdd can load conventions."
  exit 1
fi

echo "PASS: Stack rules present."
exit 0
