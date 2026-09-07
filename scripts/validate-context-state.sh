#!/usr/bin/env bash
# Ship gate: đọc state block trong docs/work/<KEY>-<slug>/_context.md và enforce
# handoff dev→QC→ship. Biến "dev_selftest + qc_status + trace phải pass" từ lời hứa
# thành check chặn được.
#
# Usage:
#   ./scripts/validate-context-state.sh [path/to/_context.md]   # 1 package cụ thể
#   ./scripts/validate-context-state.sh                          # scan mọi docs/work/*/
#
# Ship-ready khi: dev_selftest=pass  &&  qc_status ∈ {pass,na}  &&  trace=pass
# Escape hatch: FAST_TRACK=1 | HOTFIX=1 → qc_status=na được chấp nhận, vẫn cần dev_selftest.
# GOVERNANCE_SKIP=1 → bỏ qua hoàn toàn.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ "${GOVERNANCE_SKIP:-}" == "1" ]]; then
  echo "SKIP: GOVERNANCE_SKIP=1"; exit 0
fi

# Đọc 1 field trong YAML state block (fenced ```yaml ... ```) của 1 file.
field() { grep -m1 "^[[:space:]]*$2:" "$1" 2>/dev/null | sed "s/^[[:space:]]*$2:[[:space:]]*//" | sed 's/[[:space:]]*#.*//' | tr -d '\r"' | xargs || true; }

check_one() {
  local f="$1" fail=0
  local phase track dev qc trace
  phase=$(field "$f" phase);       track=$(field "$f" track)
  dev=$(field "$f" dev_selftest);  qc=$(field "$f" qc_status); trace=$(field "$f" trace)

  echo "── $f"
  echo "   phase=$phase track=$track | dev_selftest=$dev qc_status=$qc trace=$trace"

  # Chỉ enforce khi package tuyên bố đang/đã tới ship.
  case "$phase" in
    ship|done)
      [[ "$dev" == "pass" ]] || { echo "   ✗ dev_selftest chưa pass"; fail=1; }
      if [[ "${FAST_TRACK:-}" == "1" || "${HOTFIX:-}" == "1" || "$track" == "fast" || "$track" == "hotfix" ]]; then
        [[ "$qc" == "pass" || "$qc" == "na" ]] || { echo "   ✗ qc_status phải pass hoặc na (fast/hotfix)"; fail=1; }
      else
        [[ "$qc" == "pass" ]] || { echo "   ✗ qc_status chưa pass (standard track)"; fail=1; }
      fi
      [[ "$trace" == "pass" ]] || { echo "   ✗ trace chưa pass"; fail=1; }
      [[ $fail -eq 0 ]] && echo "   ✓ ship-ready"
      ;;
    "")
      echo "   ⚠ không tìm thấy state block — _context.md cần cập nhật theo template mới"
      ;;
    *)
      echo "   … chưa tới ship (phase=$phase) — bỏ qua ship gate"
      ;;
  esac
  return $fail
}

echo "=== Context State / Ship Gate ==="
FAIL=0

if [[ $# -ge 1 ]]; then
  [[ -f "$1" ]] || { echo "FAIL: không thấy file $1"; exit 1; }
  check_one "$1" || FAIL=1
else
  shopt -s nullglob
  found=0
  for f in docs/work/*/_context.md; do
    found=1
    check_one "$f" || FAIL=1
  done
  [[ $found -eq 0 ]] && echo "(không có docs/work/*/_context.md — chưa có work package nào)"
fi

echo ""
if [[ $FAIL -eq 0 ]]; then
  echo "PASS: state OK."
  exit 0
else
  echo "FAIL: có package chưa ship-ready — xem trên. Escape: FAST_TRACK=1 / HOTFIX=1 / GOVERNANCE_SKIP=1"
  exit 1
fi
