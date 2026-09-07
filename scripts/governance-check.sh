#!/usr/bin/env bash
# Run all governance checks. Use in CI and pre-merge.
# Usage: ./scripts/governance-check.sh [git-base-ref]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

BASE="${1:-}"
FAIL=0

run() {
  local name="$1"
  shift
  echo ""
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  echo "▶ $name"
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  if "$@"; then
    echo "✓ $name passed"
  else
    echo "✗ $name failed"
    FAIL=1
  fi
}

run "Skill graph" bash "$ROOT/scripts/validate-skill-graph.sh"
run "Stack rules" bash "$ROOT/scripts/validate-stack.sh"
run "SDD gate" bash "$ROOT/scripts/validate-sdd-gate.sh" ${BASE:+"$BASE"}
run "Trace coverage" bash "$ROOT/scripts/validate-trace.sh" "$ROOT"
run "Context state / ship gate" bash "$ROOT/scripts/validate-context-state.sh"

echo ""
if [[ $FAIL -eq 0 ]]; then
  echo "✓ All governance checks passed"
  exit 0
else
  echo "✗ Governance checks failed — see above"
  exit 1
fi
