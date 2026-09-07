#!/usr/bin/env bash
# Validate @trace.implements / @trace.verifies against BDD specs.
# Usage: ./scripts/validate-trace.sh [--uc UC-LMS-001] [root-dir]
# Exit 0 = no GAP blockers; Exit 1 = GAP found; Exit 2 = config error.
set -euo pipefail

ROOT="${1:-.}"
if [[ "${1:-}" == "--uc" ]]; then
  UC_FILTER="${2:-}"
  ROOT="${3:-.}"
else
  UC_FILTER=""
  [[ -d "${1:-}" ]] && ROOT="$1"
fi

# ── Resolve paths from project-context.yaml ──────────────────────────────────
BDD_DIR="docs/specs/bdd"
TRACE_DIR="docs/trace"
if [[ -f "$ROOT/project-context.yaml" ]]; then
  _bdd=$(grep -E '^\s*bdd_specs:' "$ROOT/project-context.yaml" 2>/dev/null \
    | sed 's/.*bdd_specs:[[:space:]]*//; s/[[:space:]]*#.*$//; s/[[:space:]]*$//' | tr -d '\r' || true)
  _trace=$(grep -E '^\s*trace_dir:' "$ROOT/project-context.yaml" 2>/dev/null \
    | sed 's/.*trace_dir:[[:space:]]*//; s/[[:space:]]*#.*$//; s/[[:space:]]*$//' | tr -d '\r' || true)
  [[ -n "$_bdd" ]]   && BDD_DIR="$_bdd"
  [[ -n "$_trace" ]] && TRACE_DIR="$_trace"
fi

BDD_PATH="$ROOT/$BDD_DIR"
if [[ ! -d "$BDD_PATH" ]]; then
  echo "WARN: BDD directory not found: $BDD_PATH (skip trace validation)"
  exit 0
fi

# ── Resolve code file extensions from project-context.yaml ───────────────────
# project-context.yaml khai báo:
#   code_extensions: [java, kt, go, ts, tsx, js, php, py, rb, cs, swift]
# Nếu không khai báo → fallback theo stack đã biết.
resolve_extensions() {
  local stack="$1"
  # Explicit override wins
  local ext_line
  ext_line=$(grep -E '^\s*code_extensions:' "$ROOT/project-context.yaml" 2>/dev/null \
    | sed 's/.*code_extensions:[[:space:]]*//' | tr -d '\r[]' || true)
  if [[ -n "$ext_line" ]]; then
    echo "$ext_line" | tr ',' ' ' | tr -s ' '
    return
  fi
  # Stack-based fallback
  case "$stack" in
    spring|java)    echo "java kt" ;;
    laravel|php)    echo "php" ;;
    golang|go)      echo "go" ;;
    nodejs|node)    echo "js ts tsx mjs cjs" ;;
    django|python)  echo "py" ;;
    rails|ruby)     echo "rb" ;;
    dotnet|csharp)  echo "cs" ;;
    flutter|dart)   echo "dart" ;;
    *)              echo "java kt go js ts tsx php py rb cs" ;;  # broad fallback
  esac
}

STACK=$(grep -E '^stack:' "$ROOT/project-context.yaml" 2>/dev/null \
  | sed 's/stack:[[:space:]]*//' | tr -d '\r' | head -1 || echo "")

if [[ -z "$STACK" ]]; then
  echo "WARN: 'stack' not set in project-context.yaml — using broad extension fallback."
  echo "      Add 'stack: <your-stack>' or 'code_extensions: [...]' to project-context.yaml."
  echo "      See rules/_template/ to scaffold rules for a new stack."
  echo ""
elif [[ ! -d "$ROOT/rules/$STACK" ]]; then
  echo "WARN: No rules found for stack '$STACK' (rules/$STACK/ does not exist)."
  echo "      Copy rules/_template/ to rules/$STACK/ and fill in your conventions."
  echo ""
fi

# Build --include flags for grep from extension list
build_include_flags() {
  local exts="$1"
  local flags=()
  for ext in $exts; do
    flags+=("--include=*.$ext")
  done
  echo "${flags[*]}"
}

# Build --include flags for test files
build_test_include_flags() {
  local exts="$1"
  local flags=()
  for ext in $exts; do
    # Common test file naming patterns across stacks
    flags+=("--include=*Test.$ext" "--include=*_test.$ext" "--include=*.test.$ext" "--include=*.spec.$ext")
  done
  echo "${flags[*]}"
}

CODE_EXTS=$(resolve_extensions "$STACK")
INCLUDE_ALL=$(build_include_flags "$CODE_EXTS")
INCLUDE_TEST=$(build_test_include_flags "$CODE_EXTS")

# Resolve exclude dirs from project-context.yaml (default + user-defined)
EXCLUDE_DIRS_DEFAULT="node_modules vendor .git target build dist __pycache__ .gradle"
EXCLUDE_EXTRA=$(grep -E '^\s*exclude_dirs:' "$ROOT/project-context.yaml" 2>/dev/null \
  | sed 's/.*exclude_dirs:[[:space:]]*//' | tr -d '\r[]' | tr ',' ' ' || true)
EXCLUDE_DIRS="$EXCLUDE_DIRS_DEFAULT $EXCLUDE_EXTRA"

build_exclude_flags() {
  local flags=""
  for d in $EXCLUDE_DIRS; do
    flags="$flags --exclude-dir=$d"
  done
  echo "$flags"
}
EXCLUDE_FLAGS=$(build_exclude_flags)

# ── Validation ────────────────────────────────────────────────────────────────
shopt -s globstar nullglob 2>/dev/null || true

GAP_COUNT=0
OK_COUNT=0
WARN_COUNT=0

echo "=== Trace Validation ==="
echo "Stack: ${STACK:-unknown} | Extensions: $CODE_EXTS"
echo "BDD: $BDD_PATH"
echo ""

validate_uc() {
  local feature_file="$1"
  local uc_id
  uc_id=$(grep -E '# @trace\.uc_id:' "$feature_file" 2>/dev/null | head -1 \
    | sed 's/.*uc_id:[[:space:]]*//' | tr -d '\r' || true)
  [[ -z "$uc_id" ]] && uc_id=$(basename "$feature_file" .feature)

  echo "## UC: $uc_id ($feature_file)"

  local sc_ids=()
  while IFS= read -r line; do
    sc_ids+=("$line")
  done < <(grep -oE 'SC[0-9]+' "$feature_file" 2>/dev/null | sort -u || true)

  if [[ ${#sc_ids[@]} -eq 0 ]]; then
    echo "  WARN: No SC-IDs found (add # SC1 comments before scenarios)"
    ((WARN_COUNT++)) || true
    echo ""
    return
  fi

  for sc in "${sc_ids[@]}"; do
    local tag="${uc_id}-${sc}"

    # shellcheck disable=SC2086
    impl_count=$(grep -r $INCLUDE_ALL $EXCLUDE_FLAGS \
      -l "@trace\.implements:.*${tag}" "$ROOT" 2>/dev/null | wc -l | tr -d ' ')

    # Test files: try narrow test-file pattern first, then broad
    # shellcheck disable=SC2086
    verify_count=$(grep -r $INCLUDE_TEST $EXCLUDE_FLAGS \
      -l "@trace\.verifies:.*${tag}" "$ROOT" 2>/dev/null | wc -l | tr -d ' ')

    if [[ "$verify_count" == "0" ]]; then
      # shellcheck disable=SC2086
      verify_count=$(grep -r $INCLUDE_ALL $EXCLUDE_FLAGS \
        -l "@trace\.verifies:.*${tag}" "$ROOT" 2>/dev/null | wc -l | tr -d ' ')
    fi

    local status="OK"
    if [[ "$impl_count" == "0" && "$verify_count" == "0" ]]; then
      status="GAP (no impl, no test)"; ((GAP_COUNT++)) || true
    elif [[ "$impl_count" == "0" ]]; then
      status="GAP (no impl)";          ((GAP_COUNT++)) || true
    elif [[ "$verify_count" == "0" ]]; then
      status="GAP (no test)";          ((GAP_COUNT++)) || true
    else
      ((OK_COUNT++)) || true
    fi
    echo "  $tag: impl=$impl_count test=$verify_count → $status"
  done
  echo ""
}

# ── 5. Validate Quality Signals in Trace TSVs ────────────────────────────────
validate_tsv_signals() {
  local tsv_path="$ROOT/$TRACE_DIR"
  if [[ ! -d "$tsv_path" ]]; then
    return 0
  fi

  local tsv_fail=0
  for tsv in "$tsv_path"/*.tsv; do
    [[ -f "$tsv" ]] || continue
    echo "## Checking TSV: $(basename "$tsv")"

    # Schema 10 cột (xem rules/_global/traceability.mdc):
    # uc_id scenario_id spec_file implements_tag verifies_tag dev_selftest qc_status last_updated owner note
    local line_num=1
    while IFS=$'\t' read -r uc_id scenario_id spec_file implements_tag verifies_tag dev_selftest qc_status last_updated owner note || [[ -n "$uc_id" ]]; do
      ((line_num++)) || true
      uc_id=$(echo "$uc_id" | tr -d '\r' | xargs 2>/dev/null || echo "")
      [[ "$uc_id" == "uc_id" || -z "$uc_id" ]] && continue    # skip header + dòng trống

      local row_id="${uc_id}-$(echo "$scenario_id" | tr -d '\r' | xargs)"
      implements_tag=$(echo "$implements_tag" | tr -d '\r' | xargs 2>/dev/null || echo "-")
      verifies_tag=$(echo "$verifies_tag" | tr -d '\r' | xargs 2>/dev/null || echo "-")
      dev_selftest=$(echo "$dev_selftest" | tr -d '\r' | xargs 2>/dev/null || echo "-")
      qc_status=$(echo "$qc_status" | tr -d '\r' | xargs 2>/dev/null || echo "-")

      if [[ -n "$implements_tag" && "$implements_tag" != "-" && "$dev_selftest" != "pass" ]]; then
        echo "  FAIL: Line $line_num ($row_id): có implements_tag '$implements_tag' nhưng dev_selftest='$dev_selftest' (expected 'pass')"
        tsv_fail=1
      fi

      if [[ -n "$verifies_tag" && "$verifies_tag" != "-" && "$qc_status" != "pass" ]]; then
        echo "  FAIL: Line $line_num ($row_id): có verifies_tag '$verifies_tag' nhưng qc_status='$qc_status' (expected 'pass')"
        tsv_fail=1
      fi
    done < "$tsv"
  done

  if [[ $tsv_fail -eq 1 ]]; then
    echo "FAIL: Quality signals (dev_selftest / qc_status) failed validation in TSV trace files."
    GAP_COUNT=$((GAP_COUNT + 1))
  else
    echo "  OK: TSV quality signals satisfied."
  fi
  echo ""
}

for f in "$BDD_PATH"/*.feature; do
  [[ -f "$f" ]] || continue
  base=$(basename "$f" .feature)
  if [[ -n "$UC_FILTER" && "$base" != "$UC_FILTER" && "$UC_FILTER" != *"$base"* ]]; then
    continue
  fi
  validate_uc "$f"
done

# Chạy kiểm định tín hiệu chất lượng trong file TSV
validate_tsv_signals

echo "=== Summary ==="
echo "OK: $OK_COUNT | GAP: $GAP_COUNT | WARN: $WARN_COUNT"

if [[ $GAP_COUNT -gt 0 ]]; then
  echo "FAIL: $GAP_COUNT scenario(s) missing implementation, test coverage or quality signals."
  exit 1
fi

echo "PASS: No GAP blockers."
exit 0
