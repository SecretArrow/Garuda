# motion-chromium/ — Track B: real Chromium fork layer

> **STATUS: source integration layer — NOT BUILT.**
> This directory contains the *real* fork structure (patch series, `motion/` source
> tree, depot_tools/GN/Ninja scripts, honest docs). **No APK has ever been produced
> from this tree.** A genuine Chromium-for-Android build requires self-hosted
> Chromium-class infrastructure:
>
> | Resource      | Minimum       | Recommended     |
> |---------------|---------------|-----------------|
> | Disk (free)   | 250 GB SSD    | 400+ GB NVMe    |
> | CPU           | 8 cores       | 16+ cores       |
> | RAM           | 32 GB         | 64 GB           |
> | OS            | Linux x86_64  | Ubuntu 22.04/24.04 |
> | Wall clock    | 6–20+ hours per full build | — |
>
> GitHub-hosted runners (~14 GB disk, 6 h job cap) **cannot** run this. Builds happen
> only via `.github/workflows/chromium-fork.yml` on a `[self-hosted, chromium]`
> runner. Every document in this tree states its build requirements plainly
> (honesty is a hard requirement, spec §77). Nothing here is simulated or faked.

## What this track is

Motion Browser ships on two tracks (ARCHITECTURE.md §1):

- **Track A (ships today):** the `app/` Gradle APK, running on the Chromium engine
  embedded in Android (WebView = Blink + V8 + Chromium network stack). Buildable in
  GitHub Actions; produces signed per-ABI release APKs.
- **Track B (this tree):** a real upstream Chromium fork. Same Blink/V8 engine, but
  *our* browser surface: Motion agent bridge inside the browser process, Motion new
  tab page, fork branding, fork package identity (`com.motion.browser.chromium`).
  Track B is where the autonomous agent gets first-class, in-process browser
  capabilities that WebView cannot offer (privileged observation, native
  accessibility-tree access, embedder-level control).

Until fork infrastructure exists, **Track A is the shippable product** and Track B is
kept real-but-dormant: code, patches and scripts that a machine with the resources
above can execute as-is.

## Layout map

| Path | Purpose |
|------|---------|
| `docs/BUILD_CHROMIUM.md` | Full, reproducible build guide (hardware, depot_tools, fetch, GN args, autoninja, verify, troubleshooting). Spec §69. |
| `docs/UPSTREAM_STRATEGY.md` | Base-revision pinning, patch-series policy, `VERSION_MOTION` format, rebase flow, isolation rules. Spec §70. |
| `patches/0001…0005-*.patch` | Motion patch series (numbered, `Base-Revision` headers, git-am compatible). Authored against the 130.0.6723.58 file layout; **not verified against a real checkout** (stated in every header). |
| `motion/` | Motion-specific source tree copied into the Chromium checkout at `//motion`. Spec §4 layout. |
| `motion/java/src/com/motion/chromium/` | `MotionAgentBridge.java`, `MotionTabObserver.java`, `MotionAiSuggestService.java` |
| `motion/BUILD.gn` | `motion_java` android_library target (wired into `chrome_java` by patch 0004). |
| `motion/gn/args.gn` | Canonical GN args template used by `tools/gn_gen_android.sh`. |
| `motion/security/SECURITY_NOTES.md` | Agent bridge permission model: capability chain Agent → PermissionManager → BrowserToolAPI → Chromium. Spec §46. |
| `tools/fetch_chromium.sh` | `fetch --no-history android`, records upstream commit into `VERSION_MOTION`, `gclient sync -D`. |
| `tools/apply_patches.sh` | Applies `patches/*.patch` in order (git am → git apply --3way → fuzz fallback); aborts with a clear message on conflict. |
| `tools/gn_gen_android.sh` | `gn gen out/Motion` from the `motion/gn/args.gn` template (+ per-ABI variants). |
| `tools/build_android.sh` | `autoninja -C out/Motion chrome_public_apk`; renames artifacts to `MotionBrowser-<cpu>-<rev>.apk`. |
| `tools/package_apks.sh` | Optional zipalign + apksigner packaging (env: `MOTION_KEYSTORE_*`). |
| `VERSION_MOTION` | **Generated** by `fetch_chromium.sh` on the build machine (gitignored). Records pinned chromium version, upstream commit, patch list. |
| `chromium/` | **Created at build time** by `fetch_chromium.sh` (gitignored). ~60–90 GB after sync. |

## How it relates to the shippable APK (Track A)

- Track A is the product users install today. It uses `android.webkit.WebView` — the
  same Blink rendering engine, V8 and Chromium network stack that ship inside
  Android. Feature work lands there first.
- Track B is not a wrapper around Track A. It is a parallel, real fork: when it is
  built on suitable hardware, `chrome_public_apk` (branded "Motion Browser", package
  `com.motion.browser.chromium`) is a standalone Chromium-based browser whose agent
  runs *inside* the browser process through `motion/` + `patches/`.
- The `motion/` Java code is written against Chromium APIs (`content_public`,
  `base`, jni_zero) so it compiles inside a Chromium checkout, not inside the
  Gradle app. The two tracks share product behavior and security policy, not code.

## Safety & design principles (spec §6 — non-negotiable)

1. **Never weaken Chromium security.** No patch disables the sandbox, site
   isolation, Safe Browsing, certificate verification, or any hardening flag.
   `motion/gn/args.gn` changes build *identity*, not security posture.
2. **Capability chain, never unrestricted access** (spec §46): every agent
   capability flows Agent → PermissionManager → BrowserToolAPI → Chromium
   internals. The bridge fails closed (`isAgentEnabled() == false` until the
   feature flag is on *and* the user activated the agent).
3. **Page content is untrusted.** Only sanitized, length-capped excerpts leave the
   browser layer; no raw page dumps; no secrets/cookies in logs or persistence.
4. **Additive, isolated code.** Motion-specific code lives in `motion/`; upstream
   files are touched only by the minimal numbered patch series
   (see `docs/UPSTREAM_STRATEGY.md`).
5. **Honesty.** Nothing in this tree claims to be built, tested on-device, or
   verified against a real checkout until it actually is. Docs repeat the
   infrastructure requirements everywhere.

## Quick start (on a qualifying machine only)

```bash
cd motion-chromium
tools/fetch_chromium.sh     # ~1–3 h network-bound, ~60–90 GB
tools/apply_patches.sh      # aborts loudly on conflict
tools/gn_gen_android.sh     # TARGET_CPU=arm64 by default
tools/build_android.sh      # 4–10 h first build
tools/package_apks.sh       # optional signing
```

Full details: `docs/BUILD_CHROMIUM.md`. Rebase workflow: `docs/UPSTREAM_STRATEGY.md`.

## Licensing

Chromium upstream code (everything under the pinned base revision, plus everything
the patches touch) remains under the Chromium BSD-style license and its bundled
third-party licenses — no license files are removed or altered. Motion-specific code
(`motion/`, `patches/`, `tools/`, `docs/`) is separate Motion Browser project work.
