#!/usr/bin/env bash
# Garuda fork build environment setup (plan Prompt 1 — Fase 0).
# Linux x64, 32GB+ RAM, 250GB+ SSD required. Run on the build machine ONLY.
set -euo pipefail

GARUDA_ROOT="${GARUDA_ROOT:-$HOME/garuda}"
DEPOT_TOOLS="${DEPOT_TOOLS:-$HOME/depot_tools}"
BRANCH="${BRANCH:-master}"

echo "==> [1/7] System packages"
sudo apt-get update -qq
sudo apt-get install -y git python3 python3-pip curl wget file openjdk-17-jdk \
  libncurses6 libncurses5 lib32z1 lib32stdc++6 lld patchelf ninja-build pkg-config

echo "==> [2/7] depot_tools"
if [ ! -d "$DEPOT_TOOLS" ]; then
  git clone https://chromium.googlesource.com/chromium/tools/depot_tools.git "$DEPOT_TOOLS"
fi
export PATH="$DEPOT_TOOLS:$PATH"

echo "==> [3/7] Brave source (brave-browser + chromium via npm init)"
mkdir -p "$GARUDA_ROOT" && cd "$GARUDA_ROOT"
if [ ! -d brave-browser ]; then
  git clone --branch "$BRANCH" https://github.com/brave/brave-browser.git
  cd brave-browser
  npm install
  npm run init           # fetches chromium + syncs (~100GB; takes hours)
else
  cd brave-browser && npm run init
fi

echo "==> [4/7] GN args (android arm64 release)"
mkdir -p out/GarudaArm64
cat > out/GarudaArm64/args.gn <<'EOF'
target_os = "android"
target_cpu = "arm64"
is_official_build = true
is_debug = false
chrome_public_apk_use_monochrome = false
android_channel = "default"
ffmpeg_branding = "Chrome"
proprietary_codecs = true
enable_nacl = false
EOF
gn gen out/GarudaArm64

echo "==> [5/7] Apply Garuda DevTools socket patch"
cd "$GARUDA_ROOT/brave-browser"
PATCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/patches"
git apply --check "$PATCH_DIR/garuda-devtools-socket.patch" 2>/dev/null \
  || git apply "$PATCH_DIR/garuda-devtools-socket.patch" \
  || echo "Patch already applied or needs rebase — check git status"

echo "==> [6/7] Build (autoninja; 4-12h first time)"
autoninja -C out/GarudaArm64 chrome_public_apk

echo "==> [7/7] Deploy"
adb install -r out/GarudaArm64/apks/ChromePublic.apk
adb shell cat /proc/net/unix | grep garuda-devtools && \
  echo "OK: @garuda-devtools socket is live" || \
  echo "WARN: socket not found — verify the patch and that the app is running"

echo "Done. APK: $GARUDA_ROOT/brave-browser/out/GarudaArm64/apks/ChromePublic.apk"
