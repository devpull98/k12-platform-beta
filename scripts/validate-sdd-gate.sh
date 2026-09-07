#!/usr/bin/env bash
# SDD gate: code changes must have corresponding spec/BDD artifacts (unless fast/hotfix track).
# Usage: ./scripts/validate-sdd-gate.sh [base-ref]
#   base-ref defaults to origin/main, then main, then HEAD~1
# Env escape hatches: FAST_TRACK=1 | HOTFIX=1 | GOVERNANCE_SKIP=1
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ "${GOVERNANCE_SKIP:-}" == "1" ]]; then
  echo "SKIP: GOVERNANCE_SKIP=1"
  exit 0
fi

if [[ "${FAST_TRACK:-}" == "1" || "${HOTFIX:-}" == "1" ]]; then
  echo "SKIP: ${FAST_TRACK:+FAST_TRACK}${HOTFIX:+HOTFIX} track — minimal spec gate"
  exit 0
fi

BASE="${1:-}"
if [[ -z "$BASE" ]]; then
  if git rev-parse --verify origin/main >/dev/null 2>&1; then
    BASE="origin/main"
  elif git rev-parse --verify main >/dev/null 2>&1; then
    BASE="main"
  else
    BASE="HEAD~1"
  fi
fi

if ! git rev-parse --verify "$BASE" >/dev/null 2>&1; then
  echo "WARN: Cannot resolve base ref '$BASE' — skip SDD gate (no git history)"
  exit 0
fi

# ── Resolve spec paths from project-context.yaml ─────────────────────────────
SPEC_DIRS="docs/specs docs/work"
if [[ -f project-context.yaml ]]; then
  _bdd=$(grep -E '^\s*bdd_specs:' project-context.yaml 2>/dev/null \
    | sed 's/.*bdd_specs:[[:space:]]*//; s/[[:space:]]*#.*$//; s/[[:space:]]*$//' | tr -d '\r' || true)
  _tech=$(grep -E '^\s*tech_design:' project-context.yaml 2>/dev/null \
    | sed 's/.*tech_design:[[:space:]]*//; s/[[:space:]]*#.*$//; s/[[:space:]]*$//' | tr -d '\r' || true)
  [[ -n "$_bdd" ]]  && SPEC_DIRS="$SPEC_DIRS $_bdd"
  [[ -n "$_tech" ]] && SPEC_DIRS="$SPEC_DIRS $_tech"
fi

# ── Resolve code source dirs from project-context.yaml ───────────────────────
# project-context.yaml khai báo (tùy chọn):
#   source_dirs: [src, app, internal, lib, services]
# Nếu không khai báo → fallback theo stack.

resolve_source_dirs() {
  local stack="$1"
  local explicit
  explicit=$(grep -E '^\s*source_dirs:' project-context.yaml 2>/dev/null \
    | sed 's/.*source_dirs:[[:space:]]*//' | tr -d '\r[]' | tr ',' ' ' || true)
  if [[ -n "$explicit" ]]; then
    echo "$explicit"
    return
  fi
  case "$stack" in
    spring|java|kotlin) echo "src/main api" ;;
    laravel|php)        echo "app routes" ;;
    golang|go)          echo "internal cmd pkg" ;;
    nodejs|node)        echo "src lib services packages" ;;
    django|python)      echo "app apps src" ;;
    rails|ruby)         echo "app lib" ;;
    dotnet|csharp)      echo "src Services Controllers" ;;
    flutter|dart)       echo "lib" ;;
    *)                  echo "src app internal lib packages services modules api" ;;
  esac
}

# ── Resolve code file extensions from project-context.yaml ───────────────────
resolve_extensions() {
  local stack="$1"
  local explicit
  explicit=$(grep -E '^\s*code_extensions:' project-context.yaml 2>/dev/null \
    | sed 's/.*code_extensions:[[:space:]]*//' | tr -d '\r[]' | tr ',' ' ' || true)
  if [[ -n "$explicit" ]]; then
    echo "$explicit"
    return
  fi
  case "$stack" in
    spring|java)    echo "java kt" ;;
    laravel|php)    echo "php" ;;
    golang|go)      echo "go" ;;
    nodejs|node)    echo "js ts tsx mjs cjs" ;;
    django|python)  echo "py" ;;
    rails|ruby)     echo "rb" ;;
    dotnet|csharp)  echo "cs" ;;
    flutter|dart)   echo "dart" ;;
    *)              echo "java kt go js ts tsx php py rb cs" ;;
  esac
}

STACK=$(grep -E '^stack:' project-context.yaml 2>/dev/null \
  | sed 's/stack:[[:space:]]*//' | tr -d '\r' | head -1 || echo "")

SOURCE_DIRS=$(resolve_source_dirs "$STACK")
CODE_EXTS=$(resolve_extensions  "$STACK")

# ── Check changed files ───────────────────────────────────────────────────────
CHANGED=$(git diff --name-only "$BASE"...HEAD 2>/dev/null \
  || git diff --name-only "$BASE" HEAD 2>/dev/null || true)

if [[ -z "$CHANGED" ]]; then
  echo "PASS: No changes vs $BASE"
  exit 0
fi

# Track marker in commit messages
if git log "$BASE"...HEAD --format=%s 2>/dev/null \
    | grep -qiE '\[(fast-track|hotfix|skip-sdd|wip)\]|^(wip|WIP):'; then
  echo "SKIP: track marker or WIP commit found"
  exit 0
fi

code_changed=false
spec_changed=false

is_code_file() {
  local file="$1"
  # Skip framework/config/doc dirs
  case "$file" in
    docs/*|templates/*|rules/*|skills/*|workflows/*|agents/*|scripts/*|.claude/*) return 1 ;;
  esac
  # Skip test files and test directories (do not require specs for writing tests)
  case "$file" in
    */test/*|*/tests/*|*/spec/*|*/__tests__/*|tests/*|spec/*|__tests__/*) return 1 ;;
    *Test.*|*_test.*|*.test.*|*.spec.*|*Spec.*) return 1 ;;
  esac
  # Match source dir prefixes (dynamic)
  for dir in $SOURCE_DIRS; do
    [[ "$file" == "$dir"/* || "$file" == "$dir" ]] && return 0
  done
  # Match by extension (dynamic fallback for files outside declared source dirs)
  local ext="${file##*.}"
  for e in $CODE_EXTS; do
    [[ "$ext" == "$e" ]] && return 0
  done
  return 1
}

while IFS= read -r file; do
  [[ -z "$file" ]] && continue

  if is_code_file "$file"; then
    code_changed=true
  fi

  for dir in $SPEC_DIRS; do
    if [[ "$file" == "$dir"/* || "$file" == "$dir" ]]; then
      spec_changed=true
      break
    fi
  done
done <<< "$CHANGED"

echo "=== SDD Gate ==="
echo "Stack: ${STACK:-unknown} | Source dirs: $SOURCE_DIRS"
echo "Base: $BASE"
echo "Code changed: $code_changed | Spec changed: $spec_changed"

if [[ "$code_changed" == "true" && "$spec_changed" == "false" ]]; then
  echo ""
  echo "FAIL: Source code changed without spec/BDD/plan updates."
  echo "Required: update docs/specs/bdd/ or docs/specs/ or docs/work/ before merge."
  echo "Escape: set FAST_TRACK=1, HOTFIX=1, or commit with [fast-track] / [hotfix] in message."
  exit 1
fi

echo "PASS: SDD gate satisfied."
exit 0
