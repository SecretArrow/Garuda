#!/usr/bin/env bash
#
# Motion Browser — Track B: apply the Motion patch series onto the Chromium
# checkout (docs/UPSTREAM_STRATEGY.md). Run AFTER tools/fetch_chromium.sh.
#
# Strategy per patch (in filename order):
#   1. git am --3way          (preserves the series as commits)
#   2. git apply --3way       (fallback)
#   3. patch -p1 --fuzz=3     (last resort for context drift between Chromium
#                              revisions; enabled by default, disable with
#                              MOTION_PATCH_ALLOW_FUZZ=0)
# On any remaining failure the script ABORTS with a clear message and recovery
# instructions — it never silently skips a patch. A final count check verifies
# that every patch in patches/ was applied.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOTION_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
PATCH_DIR="${MOTION_ROOT}/patches"
CHECKOUT_DIR="${MOTION_CHECKOUT_DIR:-${MOTION_ROOT}/chromium}"
SRC_DIR="${CHECKOUT_DIR}/src"

log() { printf '[motion-patches] %s\n' "$*"; }
die() { printf '\n[motion-patches] ERROR: %s\n' "$*" >&2; exit 1; }

[ -d "${SRC_DIR}" ] || die "no Chromium checkout at ${SRC_DIR}. Run tools/fetch_chromium.sh first."
[ -d "${PATCH_DIR}" ] || die "patch dir missing: ${PATCH_DIR}"

shopt -s nullglob
PATCHES=( "${PATCH_DIR}"/*.patch )
shopt -u nullglob
[ "${#PATCHES[@]}" -gt 0 ] || die "no *.patch files in ${PATCH_DIR}"

cd "${SRC_DIR}"

if [ -n "$(git status --porcelain)" ]; then
  log "WARNING: checkout has uncommitted changes; patches expect the pristine base revision."
  log "         Continue only if you know what you are doing."
fi

APPLIED=0
TOTAL="${#PATCHES[@]}"

for patch in "${PATCHES[@]}"; do
  name="$(basename "${patch}")"
  log "==> [$((APPLIED + 1))/${TOTAL}] ${name}"

  # 1) git am --3way — preferred: records the series as local commits.
  if git am --3way "${patch}" >/dev/null 2>&1; then
    APPLIED=$((APPLIED + 1))
    continue
  fi
  git am --abort >/dev/null 2>&1 || true

  # 2) git apply --3way — plain working-tree application with merge fallback.
  if git apply --3way "${patch}" 2>/dev/null; then
    APPLIED=$((APPLIED + 1))
    continue
  fi

  # 3) patch --fuzz=3 — tolerates context drift between Chromium revisions
  #    (expected during rebases; patch headers document this).
  if [ "${MOTION_PATCH_ALLOW_FUZZ:-1}" = "1" ] \
      && patch -p1 --fuzz=3 --no-backup-if-mismatch --forward < "${patch}" >/dev/null 2>&1; then
    log "    applied with fuzz (context drift) — rebase this hunk soon"
    APPLIED=$((APPLIED + 1))
    continue
  fi

  die "CONFLICT while applying ${name}.
  The series is designed to abort on conflict (docs/UPSTREAM_STRATEGY.md §5).
  Recovery:
    1. git -C '${SRC_DIR}' status            # see partial state
    2. git -C '${SRC_DIR}' am --abort 2>/dev/null || true
    3. Re-anchor the failing hunk at the current base revision, update the
       patch file (and its Base-Revision header), commit the healed series.
  Never revert upstream changes to make a Motion hunk apply (spec §6)."
done

# --- Verify count ------------------------------------------------------------
if [ "${APPLIED}" -ne "${TOTAL}" ]; then
  die "applied ${APPLIED}/${TOTAL} patches — series incomplete."
fi

log "applied ${APPLIED}/${TOTAL} patches successfully."
log "series summary:"
git log --oneline -"${TOTAL}" 2>/dev/null | sed 's/^/    /' || true
log "next: tools/gn_gen_android.sh (docs/BUILD_CHROMIUM.md §6)."
