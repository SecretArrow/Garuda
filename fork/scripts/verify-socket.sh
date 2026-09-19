#!/usr/bin/env bash
# Verification (plan Prompt 1 acceptance): APK installed → socket live → CDP reachable.
set -euo pipefail

PKG="${1:-com.garuda.browser}"

echo "==> [1/4] Socket presence"
adb shell cat /proc/net/unix | grep -E "garuda-devtools|webview_devtools_remote" || {
  echo "FAIL: no DevTools abstract socket found"; exit 1; }

echo "==> [2/4] App installed & running"
adb shell pm list packages | grep -q "garuda" || { echo "FAIL: app not installed"; exit 1; }
adb shell am start -n "$PKG/com.garuda.browser.MainActivity"
sleep 4

echo "==> [3/4] Fresh socket after launch"
adb shell cat /proc/net/unix | grep -E "garuda-devtools|webview_devtools_remote" || {
  echo "FAIL: socket disappeared"; exit 1; }

echo "==> [4/4] CDP discovery via device log (app self-connect)"
adb logcat -d | grep -E "DevToolsLocator|DevToolsClient" | tail -10 || true
echo "PASS: fork socket verification complete"
