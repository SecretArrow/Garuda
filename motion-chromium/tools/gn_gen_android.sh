#!/usr/bin/env bash
#
# Motion Browser — Track B: generate the GN out dir for the Android target.
# Uses the canonical args template motion/gn/args.gn (docs/BUILD_CHROMIUM.md §6).
# Run AFTER tools/fetch_chromium.sh (and tools/apply_patches.sh).
#
# Usage:
#   tools/gn_gen_android.sh [TARGET_CPU]
#   TARGET_CPU: arm64 (default) | arm | x86 | x64
# Env:
#   MOTION_OUT_DIR   force a specific out dir (default: out/Motion for arm64,
#                    out/Motion-<cpu> for other CPUs — one dir per ABI, see
#                    docs/BUILD_CHROMIUM.md §7)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOTION_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CHECKOUT_DIR="${MOTION_CHECKOUT_DIR:-${MOTION_ROOT}/chromium}"
SRC_DIR="${CHECKOUT_DIR}/src"
ARGS_TEMPLATE="${MOTION_ROOT}/motion/gn/args.gn"

log() { printf '[motion-gn] %s\n' "$*"; }
die() { printf '\n[motion-gn] ERROR: %s\n' "$*" >&2; exit 1; }

TARGET_CPU="${1:-${TARGET_CPU:-arm64}}"
case "${TARGET_CPU}" in
  arm64|arm|x86|x64) ;;
  *) die "unsupported TARGET_CPU '${TARGET_CPU}' (use: arm64 | arm | x86 | x64)" ;;
esac

if [ "${TARGET_CPU}" = "arm64" ] && [ -z "${MOTION_OUT_DIR:-}" ]; then
  OUT_DIR="out/Motion"
else
  OUT_DIR="${MOTION_OUT_DIR:-out/Motion-${TARGET_CPU}}"
fi

[ -d "${SRC_DIR}" ] || die "no Chromium checkout at ${SRC_DIR}. Run tools/fetch_chromium.sh first."
[ -f "${ARGS_TEMPLATE}" ] || die "args template missing: ${ARGS_TEMPLATE}"
command -v gn >/dev/null 2>&1 || die "gn not found on PATH (depot_tools, docs/BUILD_CHROMIUM.md §3)"

cd "${SRC_DIR}"

# --- Write args.gn from the Motion template -----------------------------------
mkdir -p "${OUT_DIR}"
ARGS_FILE="${OUT_DIR}/args.gn"
if [ -f "${ARGS_FILE}" ] && ! cmp -s "${ARGS_FILE}" <(sed "s/^target_cpu = .*/target_cpu = \"${TARGET_CPU}\"/" "${ARGS_TEMPLATE}"); then
  log "overwriting existing ${ARGS_FILE} (scripts own this file — see troubleshooting in docs/BUILD_CHROMIUM.md §9)"
fi
sed "s/^target_cpu = .*/target_cpu = \"${TARGET_CPU}\"/" "${ARGS_TEMPLATE}" > "${ARGS_FILE}"

log "GN args (${OUT_DIR}):"
sed 's/^/    /' "${ARGS_FILE}"

# --- Generate + sanity check ---------------------------------------------------
gn gen "${OUT_DIR}"

log "sanity: non-default args as GN resolved them:"
gn args "${OUT_DIR}" --list --short | sed 's/^/    /' || true

for key in target_os target_cpu chrome_public_manifest_package; do
  gn args "${OUT_DIR}" --list --short | grep -q "^${key}=" \
    || log "NOTE: '${key}' not visible in --list --short output — verify manually with: gn args ${OUT_DIR} --list | grep -A2 '^${key}'"
done

grep -q '^target_os = "android"' "${ARGS_FILE}" \
  || die "target_os must be \"android\" in ${ARGS_FILE}"

log "done: ${SRC_DIR}/${OUT_DIR} ready. Build with: tools/build_android.sh"
