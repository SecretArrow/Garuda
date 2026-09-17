#!/usr/bin/env bash
#
# Motion Browser — Track B: optional zipalign + apksigner packaging for the
# Motion Chromium APKs produced by tools/build_android.sh.
#
# Signing is OPTIONAL and OFF unless keystore env vars are provided:
#   MOTION_KEYSTORE_FILE          path to a keystore (.jks/.keystore)
#   MOTION_KEYSTORE_PASSWORD      keystore password
#   MOTION_KEY_ALIAS              key alias
#   MOTION_KEY_PASSWORD           key password (defaults to keystore password)
# Without them the script only zipaligns and prints a clear notice — it does
# NOT fake a signed artifact. Never put passwords in files or logs (spec §63).
#
# align/apksigner binaries come from Chromium's bundled Android build-tools
# (third_party/android_sdk/public) unless MOTION_BUILD_TOOLS overrides it.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOTION_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CHECKOUT_DIR="${MOTION_CHECKOUT_DIR:-${MOTION_ROOT}/chromium}"
SRC_DIR="${CHECKOUT_DIR}/src"

log() { printf '[motion-package] %s\n' "$*"; }
die() { printf '\n[motion-package] ERROR: %s\n' "$*" >&2; exit 1; }

TARGET_CPU="${1:-${TARGET_CPU:-arm64}}"
if [ "${TARGET_CPU}" = "arm64" ] && [ -z "${MOTION_OUT_DIR:-}" ]; then
  OUT_DIR="out/Motion"
else
  OUT_DIR="${MOTION_OUT_DIR:-out/Motion-${TARGET_CPU}}"
fi
APKS_DIR="${SRC_DIR}/${OUT_DIR}/apks"

[ -d "${APKS_DIR}" ] || die "no apks dir at ${APKS_DIR}. Run tools/build_android.sh first."
shopt -s nullglob
APKS=( "${APKS_DIR}"/MotionBrowser-*.apk )
shopt -u nullglob
[ "${#APKS[@]}" -gt 0 ] || die "no MotionBrowser-*.apk in ${APKS_DIR}."

# --- Locate build-tools binaries ----------------------------------------------
BUILD_TOOLS="${MOTION_BUILD_TOOLS:-}"
if [ -z "${BUILD_TOOLS}" ]; then
  SDK_DIR="${SRC_DIR}/third_party/android_sdk/public/build-tools"
  [ -d "${SDK_DIR}" ] || die "Chromium build-tools not found at ${SDK_DIR}.
  Provide MOTION_BUILD_TOOLS=/path/to/android/build-tools/<ver>."
  BUILD_TOOLS="$(ls -d "${SDK_DIR}"/* 2>/dev/null | sort -V | tail -1)"
fi
ZIPALIGN="${BUILD_TOOLS}/zipalign"
APKSIGNER="${BUILD_TOOLS}/apksigner"
[ -x "${ZIPALIGN}" ] || die "zipalign not found at ${ZIPALIGN}"
[ -x "${APKSIGNER}" ] || die "apksigner not found at ${APKSIGNER}"
log "using build-tools: ${BUILD_TOOLS}"

# --- Align (always), sign (only with keystore env) ----------------------------
for apk in "${APKS[@]}"; do
  base="$(basename "${apk}")"
  aligned="${APKS_DIR}/${base%.apk}-aligned.apk"

  log "zipalign: ${base}"
  "${ZIPALIGN}" -f -p 4 "${apk}" "${aligned}"

  if [ -n "${MOTION_KEYSTORE_FILE:-}" ] && [ -n "${MOTION_KEYSTORE_PASSWORD:-}" ] \
      && [ -n "${MOTION_KEY_ALIAS:-}" ]; then
    log "apksigner: signing ${base} (alias: ${MOTION_KEY_ALIAS})"
    "${APKSIGNER}" sign \
      --ks "${MOTION_KEYSTORE_FILE}" \
      --ks-key-alias "${MOTION_KEY_ALIAS}" \
      --ks-pass "pass:${MOTION_KEYSTORE_PASSWORD}" \
      --key-pass "pass:${MOTION_KEY_PASSWORD:-${MOTION_KEYSTORE_PASSWORD}}" \
      --out "${APKS_DIR}/${base%.apk}-signed.apk" \
      "${aligned}"
    "${APKSIGNER}" verify "${APKS_DIR}/${base%.apk}-signed.apk"
    log "signed + verified: ${base%.apk}-signed.apk"
  else
    log "NOTE: keystore env not set (MOTION_KEYSTORE_FILE / MOTION_KEYSTORE_PASSWORD / MOTION_KEY_ALIAS)."
    log "      ${aligned} is aligned but UNSIGNED — an unsigned release APK will not install."
    log "      For device smoke tests, sign it yourself or provide the keystore env vars."
  fi
  # Keep exactly one artifact per input: the signed one when signing ran,
  # otherwise the aligned (unsigned) one.
  if [ -f "${APKS_DIR}/${base%.apk}-signed.apk" ]; then
    rm -f "${aligned}"
  fi
done

log "artifacts:"
ls -lh "${APKS_DIR}"/*.apk | awk '{printf "    %10s  %s\n", $5, $9}'
log "done."
