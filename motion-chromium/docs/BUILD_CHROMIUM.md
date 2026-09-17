# Building Motion Browser from Chromium source (spec §69)

**Read the status first:** this guide is real and reproducible, but it can only run
on a machine with Chromium-class resources. It **cannot** run on GitHub-hosted
runners (~14 GB disk, 6 h job cap). It is executed by
`.github/workflows/chromium-fork.yml` on a `[self-hosted, chromium]` runner, or by
you, interactively, on a qualifying Linux box. Numbers below are honest planning
estimates for recent revisions (base pin: `chromium/130.0.6723.58`) — **measure on
your machine, don't trust the table blindly**; every Chromium revision shifts these.

---

## 1. Hardware requirements

| Resource    | Minimum                     | Recommended                        | Notes |
|-------------|-----------------------------|------------------------------------|-------|
| CPU         | 8 cores                     | **16+ cores**                      | Build is embarrassingly parallel until link time. |
| RAM         | 32 GB                       | **64 GB**                          | Final link steps peak hard; with 32 GB use `symbol_level = 1` and cap `-j` (see §9). |
| Disk        | **≥ 250 GB free**, SSD      | 400+ GB NVMe                       | Free space *after* OS and tools; see §5 table. |
| OS          | Linux x86_64                | Ubuntu 22.04 / 24.04 LTS           | macOS/Windows are not covered here; Android cross-builds from Linux. |
| Network     | broadband                   | fast + stable                      | Initial fetch moves tens of GB. |
| Swap        | none needed at 64 GB        | 32 GB swap at 32 GB RAM            | Absorbs link-time spikes. |

## 2. Software requirements

| Tool                    | Version / source                             | Note |
|-------------------------|----------------------------------------------|------|
| depot_tools             | rolling (`git clone` from googlesource)      | Provides `fetch`, `gclient`, `gn`, `ninja`, `autoninja`. |
| JDK 17                  | Chromium bundles its own JDK 17 (`third_party/jdk`) | A system JDK 17 is fine to satisfy CI policy, but the build uses the bundled one by default. Do not force `JAVA_HOME`. |
| Android SDK (API 34)    | Chromium's **pinned** SDK, downloaded by `gclient runhooks` | M130-era revisions pin API 34 tooling. Never point GN at a system SDK. |
| Android NDK             | Chromium's pinned NDK, via runhooks          | Same rule. |
| GN / Ninja              | via depot_tools                              | Nothing to install manually. |
| Python 3 + git          | distro packages                              | depot_tools prerequisites. |
| adb                     | `chromium/src/third_party/android_sdk/public/platform-tools/adb` | For §8 verification. |

## 3. Install depot_tools

```bash
mkdir -p ~/tools && cd ~/tools
git clone https://chromium.googlesource.com/chromium/tools/depot_tools.git
export PATH="$HOME/tools/depot_tools:$PATH"       # put this line in ~/.bashrc
which fetch gclient gn ninja                      # sanity: all four must resolve
```

## 4. Fetch the Chromium checkout (with Android support)

Run this from `motion-chromium/` — `tools/fetch_chromium.sh` automates exactly
these steps and records the upstream commit into `VERSION_MOTION`:

```bash
cd motion-chromium
mkdir -p chromium && cd chromium

fetch --no-history android          # = chromium checkout + target_os += ['android']
                                    # fetch also runs the first `gclient sync` + hooks
cd src
gclient sync -D                     # prune/sync deps to match the checked-out DEPS
gclient runhooks                    # (re)run hooks: downloads pinned Android SDK/NDK, clang, PGO metadata
```

Notes:

- `--no-history` saves ~20+ GB and is what CI uses. For a **maintenance machine**
  (patch rebasing, §70) prefer a full-history checkout: `fetch android` without the
  flag — rebasing across revisions needs history.
- On Ubuntu, if hook scripts complain about missing packages, run
  `build/install-build-deps.sh` and `build/install-build-deps-android.sh` from `src/`
  (Ubuntu-only convenience; other distros: install equivalents manually).
- `fetch android` writes `target_os = ['android']` into the checkout's `.gclient`.

## 5. Disk & time expectations (per revision, approximate)

| Stage | Disk delta | Wall time (16 cores, NVMe, broadband) |
|-------|-----------|----------------------------------------|
| depot_tools clone | ~1 GB | < 5 min |
| `fetch --no-history android` (src + third_party) | 25–40 GB | 1–3 h (network-bound) |
| `gclient runhooks` (pinned SDK/NDK/toolchains) | +15–30 GB | 20–60 min |
| `gclient sync -D` | — | 10–30 min |
| `gn gen out/Motion` | < 1 GB | < 1 min |
| First `autoninja -C out/Motion chrome_public_apk` (arm64) | **40–80 GB** | **4–10 h** |
| Each additional ABI out dir (§7) | +30–60 GB | +3–8 h |
| Incremental rebuild after a small `motion/` change | — | 2–15 min |

**Headroom rule: keep ≥ 250 GB free before starting.** A working set of
checkout + hooks + one out dir realistically lands at 120–180 GB; two ABIs plus
picking caches can exceed 220 GB.

## 6. Configure GN — `out/Motion`

`tools/gn_gen_android.sh` copies `motion/gn/args.gn` (canonical template) into
`out/Motion/args.gn`, substituting `target_cpu`. Manual equivalent:

```bash
cd motion-chromium/chromium/src
mkdir -p out/Motion
cp ../../motion/gn/args.gn out/Motion/args.gn    # template (adjust target_cpu if needed)
gn gen out/Motion
gn args out/Motion --list --short | head -40     # sanity: confirm the args landed
```

The template contents (full `args.gn` sample):

```gn
target_os = "android"
target_cpu = "arm64"
is_official_build = false
is_debug = false
symbol_level = 1
v8_symbol_level = 0
is_component_build = false
chrome_public_manifest_package = "com.motion.browser.chromium"
enable_nacl = false
ffmpeg_branding = "Chrome"
proprietary_codecs = true
android_channel = "default"
```

Arg semantics that matter:

- `is_official_build = false` — no Google API keys, no Google branding/sign-in
  (expected and documented in §8). Keep `false` until you deliberately provide keys.
- `is_debug = false` + `symbol_level = 1` — release-speed build, minimal line-table
  symbols; keeps link RAM and out-dir size manageable.
- `chrome_public_manifest_package` — the sanctioned **applicationId override path**;
  patch 0002 only fills the same default when the arg is unset. Prefer this arg.
- `proprietary_codecs = true` / `ffmpeg_branding = "Chrome"` — match Chrome media
  support; review codec licensing before public distribution.

## 7. Build — per-ABI variants

Default target (arm64 production devices):

```bash
autoninja -C out/Motion chrome_public_apk
```

Per-ABI variants get their own out dir (GN args are frozen per dir; sharing one dir
across CPUs causes pointless rebuilds):

| target_cpu | Out dir | Device class |
|------------|-------------------|--------------------------------|
| `arm64`    | `out/Motion`      | Modern phones (primary) |
| `arm`      | `out/Motion-arm`  | Legacy 32-bit devices |
| `x86`      | `out/Motion-x86`  | 32-bit emulators |
| `x64`      | `out/Motion-x64`  | 64-bit emulators / Chromebooks |

```bash
tools/gn_gen_android.sh arm                 # writes out/Motion-arm, target_cpu = "arm"
autoninja -C out/Motion-arm chrome_public_apk
```

**`monochrome_public_apk` note:** Chromium can also build Monochrome — browser and
WebView provider in one APK (what Chrome itself ships). Motion Track B v1 ships
`chrome_public_apk` only: a standalone browser must **not** replace the platform
WebView provider, which other apps depend on (security/compat surface, spec §6).
Build `autoninja -C out/Motion monochrome_public_apk` only if you consciously take
on that testing burden later.

## 8. Expected outputs & install verification

```
chromium/src/out/Motion/apks/ChromePublic.apk        # produced by chrome_public_apk
    → renamed/copied by tools/build_android.sh to:
      out/Motion/apks/MotionBrowser-<cpu>-<rev>.apk  # e.g. MotionBrowser-arm64-130.0.6723.58-motion.5.apk
```

(`monochrome_public_apk` would emit `out/Motion/apks/MonochromePublic.apk` — not part
of v1, see §7.)

Verify on a device (Android 10+ / minSdk 29 target):

```bash
ADB=third_party/android_sdk/public/platform-tools/adb
$ADB install -r out/Motion/apks/MotionBrowser-arm64-130.0.6723.58-motion.5.apk
$ADB shell dumpsys package com.motion.browser.chromium | grep -E 'versionName|versionCode'
$ADB shell am start -n com.motion.browser.chromium/org.chromium.chrome.browser.ChromeTabbedActivity
```

Acceptance checks:

- App installs and launches (no `FATAL EXCEPTION` in `adb logcat -s AndroidRuntime`).
- `versionName` equals the Motion build version from `VERSION_MOTION`.
- Launcher name reads "Motion Browser" (patch 0001).
- No Google sign-in: expected, `is_official_build = false` (§6) — not a bug.

## 9. Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `gclient sync` fails with conflicting dirs / dirty git | partial previous sync | `git -C src rebase --abort` (if rebase in progress); `gclient sync -D --force`; worst case delete the named `src/third_party/<dir>` and re-sync. Never delete the whole checkout unless disk allows. |
| Hooks fail on CIPD downloads | network flake | `gclient runhooks --force`; retry — CIPD resumes. |
| **Disk full** mid-build | out dir growth (§5) | `df -h`; drop unused ABI out dirs (`rm -rf out/Motion-x86`); `du -sh out/* | sort -h` to find the hog; ninja resumes incrementally after freeing space. |
| **GN args drift** (build behaves differently than docs) | args.gn edited by hand or stale dir | `gn args out/Motion --list --short > /tmp/current.txt` and diff against `motion/gn/args.gn`; regenerate with `tools/gn_gen_android.sh`. Scripts own `args.gn` — never hand-edit. |
| Linker OOM / killed cc1plus | 32 GB RAM | cap jobs: `ninja -C out/Motion -j4 chrome_public_apk`; add swap; keep `symbol_level=1`, `v8_symbol_level=0`. |
| `chrome_public_manifest_package` unknown arg | old/new GN arg drift across revisions | check `gn args out/Motion --list | grep manifest`; if renamed upstream, port the arg in the rebase (§70) — do not drop the Motion package identity. |
| Wrong JDK / gradle errors during APK packaging | system `JAVA_HOME` forced | unset `JAVA_HOME`; Chromium uses its bundled JDK 17. |
| APK installs but is named "Chromium" | patch 0001 not applied | `tools/apply_patches.sh` output must show 5/5; `git -C src log --oneline -5` should list the Motion series. |

Only reproducible commands are documented here — everything above runs verbatim on
a qualifying machine with depot_tools on `PATH`.
