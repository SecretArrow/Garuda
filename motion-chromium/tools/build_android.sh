#!/usr/bin/env bash
#
# Motion Browser — Track B: build the Motion Chromium APK for Android.
# Run AFTER tools/gn_gen_android.sh. Real build: 4–10 h first run on 16 cores,
# 40–80 GB in the out dir (docs/BUILD_CHROMIUM.md §5). GitHub-hosted runners
# cannot run this (spec §77).
#
# Usage:
#   tools/build_android.sh [TARGET_CPU]        # arm64 default
# Env:
#   MOTION_OUT_DIR        override out dir (must match gn_gen_android.sh)
#   MOTION_BUILD_MONOCHROME=1   ALSO build monochrome_public_apk (see docs
#                         BUILD_CHROMIUM.md §7 — NOT part of v1; do not ship
#                         a fork as the platform WebView provider casually)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOTION_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CHECKOUT_DIR="${MOTION_CHECKOUT_DIR:-${MOTION_ROOT}/chromium}"
SRC_DIR="${CHECKOUT_DIR}/src"

log() { printf '[motion-build] %s\n' "$*"; }
die() { printf '\n[motion-build] ERROR: %s\n' "$*" >&2; exit 1; }

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
[ -f "${SRC_DIR}/${OUT_DIR}/args.gn" ] \
  || die "no args.gn at ${OUT_DIR}. Run tools/gn_gen_android.sh first."

cd "${SRC_DIR}"

# Version info (from the checkout, never invented)
MAJOR=0; MINOR=0; BUILD=0; PATCH=0
eval "$(grep -E '^(MAJOR|MINOR|BUILD|PATCH)=' chrome/VERSION | tr -d '\r')"
REV="${MAJOR}.${MINOR}.${BUILD}.${PATCH}"
if [ -f "${MOTION_ROOT}/VERSION_MOTION" ] \
    && grep -q '^motion_patch_version' "${MOTION_ROOT}/VERSION_MOTION"; then
  MOTION_PV="$(sed -n 's/^motion_patch_version[[:space:]]*=[[:space:]]*//p' "${MOTION_ROOT}/VERSION_MOTION")"
  REV="${REV}-motion.${MOTION_PV}"
fi

# --- Build --------------------------------------------------------------------
log "autoninja -C ${OUT_DIR} chrome_public_apk  (first build: 4–10 h on 16 cores)"
autoninja -C "${OUT_DIR}" chrome_public_apk

if [ "${MOTION_BUILD_MONOCHROME:-0}" = "1" ]; then
  log "MOTION_BUILD_MONOCHROME=1 — also building monochrome_public_apk"
  autoninja -C "${OUT_DIR}" monochrome_public_apk
fi

# --- Collect artifacts ---------------------------------------------------------
# Upstream emits out/<dir>/apks/ChromePublic.apk; Motion copies it under the
# fork artifact name. Honest note: this is a rename of the freshly built APK,
# not a downloaded or prebuilt binary.
APKS_DIR="${OUT_DIR}/apks"
SRC_APK="${APKS_DIR}/ChromePublic.apk"
[ -f "${SRC_APK}" ] || die "expected build output missing: ${SRC_APK}"
[ -s "${SRC_APK}" ] || die "build output is empty: ${SRC_APK} (build did not actually produce an APK)"

OUT_NAME="MotionBrowser-${TARGET_CPU}-${REV}.apk"
cp -f "${SRC_APK}" "${APKS_DIR}/${OUT_NAME}"

log "artifacts:"
ls -lh "${APKS_DIR}"/*.apk | awk '{printf "    %10s  %s\n", $5, $9}'
log "sha256:"
sha256sum "${APKS_DIR}/${OUT_NAME}" | sed 's/^/    /'

# Optional mirror for CI upload steps that expect a different path.
if [ -n "${MOTION_ARTIFACT_MIRROR:-}" ]; then
  mkdir -p "${MOTION_ARTIFACT_MIRROR}"
  cp -f "${APKS_DIR}/${OUT_NAME}" "${MOTION_ARTIFACT_MIRROR}/${OUT_NAME}"
  log "mirrored to ${MOTION_ARTIFACT_MIRROR}"
fi

log "done. Verify install: docs/BUILD_CHROMIUM.md §8."
