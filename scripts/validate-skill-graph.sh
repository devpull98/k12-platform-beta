#!/usr/bin/env bash
# Skill graph integrity: mọi skill được reference (router.yaml + on_success/on_failure/on_skip)
# phải resolve tới skills/<name>/SKILL.md, và mỗi SKILL.md có name khớp folder.
# Bắt "dangling skill reference" (vd skill mới chưa load, hoặc typo) TRƯỚC khi thành bug runtime.
# Usage: ./scripts/validate-skill-graph.sh
# Exit 0 = OK; Exit 1 = có reference gãy hoặc name mismatch.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# ── Guard: đây là check tính toàn vẹn của KIT repo (skills/, router.yaml, workflows/,
# commands/), không phải của project đích. Project chỉ cài scripts/ + rules/ + project-context.yaml
# nên không có các thư mục đó → skip thay vì fail giả.
# Sửa bởi skill `onboarding` khi cài kit vào repo này; phần còn lại giữ nguyên bản gốc.
if [[ ! -f router.yaml || ! -d skills ]]; then
  echo "=== Skill Graph Integrity ==="
  echo "SKIP: không có router.yaml/skills/ ở root — check này chỉ áp dụng cho repo chứa kit."
  echo "      Kit đang chạy từ plugin cache (uniclass-workflow); graph của nó được validate ở repo kit."
  exit 0
fi

FAIL=0
echo "=== Skill Graph Integrity ==="

# ── Tập skill tồn tại (folder có SKILL.md) ───────────────────────────────────
exist=$(for d in skills/*/SKILL.md; do basename "$(dirname "$d")"; done | sort -u)
has() { grep -qxF "$1" <<<"$exist"; }

# ── 1. name frontmatter phải khớp tên folder ─────────────────────────────────
for f in skills/*/SKILL.md; do
  folder=$(basename "$(dirname "$f")")
  name=$(grep -m1 '^name:' "$f" | sed 's/name:[[:space:]]*//' | tr -d '\r')
  if [[ "$name" != "$folder" ]]; then
    echo "  MISMATCH: skills/$folder/SKILL.md có name: '$name' (phải là '$folder')"
    FAIL=1
  fi
done

# ── Hàm kiểm 1 reference ─────────────────────────────────────────────────────
check_ref() {
  local skill="$1" src="$2"
  [[ -z "$skill" ]] && return 0
  if ! has "$skill"; then
    echo "  DANGLING: '$skill' (referenced in $src) — không có skills/$skill/SKILL.md"
    FAIL=1
  fi
}

# ── 2. router.yaml: mọi `skill:` phải resolve ────────────────────────────────
while IFS= read -r skill; do
  check_ref "$skill" "router.yaml"
done < <(grep -hoE '^[[:space:]]+skill:[[:space:]]*\S+' router.yaml | sed 's/.*skill:[[:space:]]*//' | tr -d '\r' | sort -u)

# ── 3. on_success/on_failure/on_skip trong mọi SKILL.md phải resolve ──────────
for f in skills/*/SKILL.md; do
  folder=$(basename "$(dirname "$f")")
  refs=$(grep -hoE '^(on_success|on_failure|on_skip):[[:space:]]*\[.*\]' "$f" \
    | grep -oE '\[.*\]' | tr -d '[]' | tr ',' '\n' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | grep -v '^$' || true)
  while IFS= read -r skill; do
    [[ -z "$skill" ]] && continue
    check_ref "$skill" "skills/$folder/SKILL.md"
  done <<<"$refs"
done

# ── 4. Workflow drift guard: role-view workflow phải trỏ về canonical source ──
# canonical-flow.md là single source of truth; các workflow khác chỉ là role-view
# và PHẢI reference nó để tránh drift khi canonical đổi.
for f in workflows/*.md; do
  base=$(basename "$f")
  [[ "$base" == "canonical-flow.md" ]] && continue
  if ! grep -q 'canonical-flow.md' "$f"; then
    echo "  DRIFT-RISK: $f không tham chiếu canonical-flow.md (role-view phải trỏ về canonical source)"
    FAIL=1
  fi
done

# ── 5. Command path guard: commands/*.md phải trỏ file tồn tại, cấm .claude/skills ──
# Chặn tái diễn lỗi command trỏ path chết (vd .claude/skills/ không tồn tại trong plugin).
for f in commands/*.md; do
  [[ -e "$f" ]] || continue
  # 5a. cấm .claude/skills/ (layout project-local, không có trong plugin)
  if grep -q '\.claude/skills' "$f"; then
    echo "  BAD-PATH: $f trỏ '.claude/skills/' — plugin skills ở 'skills/' (root)"
    FAIL=1
  fi
  # 5b. mọi ref skills/<x>/SKILL.md và workflows/<x>.md phải tồn tại
  while IFS= read -r ref; do
    [[ -z "$ref" ]] && continue
    if [[ ! -e "$ref" ]]; then
      echo "  DANGLING: $f trỏ '$ref' — file không tồn tại"
      FAIL=1
    fi
  done < <(grep -oE '(skills/[A-Za-z0-9_-]+/SKILL\.md|workflows/[A-Za-z0-9_-]+\.md)' "$f" | sort -u)
done

echo ""
if [[ $FAIL -eq 0 ]]; then
  echo "PASS: Skill/workflow/command reference resolve, name khớp folder, workflow trỏ canonical."
  exit 0
else
  echo "FAIL: Có reference gãy hoặc name mismatch — xem trên."
  echo "  Lưu ý: skill MỚI thêm chỉ được Claude Code load ở SESSION MỚI."
  echo "  Nếu folder đã tồn tại mà agent không gọi được → khởi động lại session."
  exit 1
fi
