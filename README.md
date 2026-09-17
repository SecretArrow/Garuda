# Motion Browser

**An autonomous AI browser for Android — built on real Chromium.**

Motion Browser lets you give a goal in natural language ("check my parcel status
every morning and notify me if it's delayed") and runs it with an on-device agent:
observe page → plan → execute → observe, with per-goal caps, approval prompts for
risky actions, and full audit logs. You bring your own AI provider key (OpenAI-
compatible, Anthropic, Gemini, OpenRouter, Ollama, custom).

Repository: `https://github.com/SecretArrow/MotionBrowser`

![build](https://github.com/SecretArrow/MotionBrowser/actions/workflows/build.yml/badge.svg)
![chromium-fork](https://github.com/SecretArrow/MotionBrowser/actions/workflows/chromium-fork.yml/badge.svg)

*(badge placeholders — they resolve once the workflows have run; the chromium-fork
badge only goes green on a self-hosted runner, see below.)*

---

## The two tracks (read this first — honest engineering)

A real Chromium-for-Android fork needs **~250 GB free disk, 16+ cores, 64 GB RAM
and 6–20+ hours per build**. GitHub-hosted runners (~14 GB disk, 6 h job cap)
**cannot** run it. We will not pretend otherwise, and nothing in this repo fakes a
Chromium build (spec §77). So the project ships in two parallel tracks:

| | **Track A — shippable APK** | **Track B — real Chromium fork layer** |
|---|---|---|
| Engine | Chromium embedded in Android (`WebView` = Blink + V8 + Chromium network stack) | Full upstream Chromium fork (`motion-chromium/`) |
| Where built | GitHub Actions (`build.yml`) — every push | Self-hosted runner only (`chromium-fork.yml`, `[self-hosted, chromium]`, `workflow_dispatch`) |
| Requirements | None (CI) | ≥250 GB free SSD, 16+ cores rec / 8 min, 64 GB RAM rec / 32 min, Linux x86_64, 6–20+ h per rev |
| Status | **Builds now** — signed per-ABI APKs on every run | **Source integration layer — not built yet.** Real patches, real fork source tree, real scripts, honest docs; executes as-is on qualifying hardware |
| Code | `app/` (Kotlin 2.0.21, Compose, AGP 8.5.2, minSdk 29) | `motion-chromium/` (patches, `motion/` tree, depot_tools/GN/Ninja scripts) |

Until fork infrastructure exists, **Track A is the product**. Track B is kept real
and maintained (pin `chromium/130.0.6723.58`, patch series, rebase flow) so the
fork can begin the moment a qualifying machine is available. Details:
[`motion-chromium/README.md`](motion-chromium/README.md),
[`motion-chromium/docs/BUILD_CHROMIUM.md`](motion-chromium/docs/BUILD_CHROMIUM.md),
[`motion-chromium/docs/UPSTREAM_STRATEGY.md`](motion-chromium/docs/UPSTREAM_STRATEGY.md).

## Repo layout

```
MotionBrowser/
├── app/                     # Track A: shippable APK (WebView-based, per-ABI split)
├── motion-chromium/         # Track B: real Chromium fork layer (NOT built — needs infra)
│   ├── README.md            #   status, layout map, safety principles
│   ├── docs/BUILD_CHROMIUM.md        # reproducible build guide (spec §69)
│   ├── docs/UPSTREAM_STRATEGY.md     # pinning, patch series, rebase flow (spec §70)
│   ├── patches/             #   numbered Motion patch series (0001–0005)
│   ├── motion/              #   Motion source tree for the Chromium checkout (spec §4)
│   │   ├── java/src/com/motion/chromium/   # MotionAgentBridge / MotionTabObserver /
│   │   │                                    # MotionAiSuggestService
│   │   ├── BUILD.gn, gn/args.gn, security/SECURITY_NOTES.md, README.md
│   └── tools/               #   fetch_chromium / apply_patches / gn_gen_android /
│                            #   build_android / package_apks
├── ARCHITECTURE.md          # contract for all development agents
└── .github/workflows/       # build.yml (CI) · chromium-fork.yml (self-hosted only)
```

## Quickstart (Track A — today)

1. Open the latest run of [`build.yml`](https://github.com/SecretArrow/MotionBrowser/actions/workflows/build.yml).
2. Download the release artifacts: signed **per-ABI** APKs
   (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`) + AAB.
3. Install the APK matching your device (most modern phones: `arm64-v8a`),
   Android 10+ (minSdk 29).
4. On launch: grant the notification permission if you want goal notifications,
   then add an AI provider (your key, stored in Keystore-backed storage — never
   logged, never exported).

CI stages: quality (lint + unit tests) → instrumented emulator tests → signed
release artifacts. A run is only trusted when all three are green.

## Track B quickstart (only on qualifying hardware)

```bash
cd motion-chromium
tools/fetch_chromium.sh     # ~1–3 h, ~60–90 GB (gitignored checkout)
tools/apply_patches.sh      # 5-patch Motion series; aborts loudly on conflict
tools/gn_gen_android.sh     # GN out/Motion from motion/gn/args.gn (arm64 default)
tools/build_android.sh      # 4–10 h; → out/Motion/apks/MotionBrowser-<cpu>-<rev>.apk
tools/package_apks.sh       # optional zipalign + apksigner (MOTION_KEYSTORE_* env)
```

Docs: [`motion-chromium/docs/BUILD_CHROMIUM.md`](motion-chromium/docs/BUILD_CHROMIUM.md).

## Contribution & agent workflow (spec §78)

- All development follows [`ARCHITECTURE.md`](ARCHITECTURE.md) — module ownership
  map, exact cross-module signatures, hard rules.
- **CI-only builds:** no local `gradlew` builds; everything runs in GitHub Actions.
- **No direct pushes:** the coordinator commits/pushes; parallel agents work in
  assigned scopes only and file out-of-scope needs in the shared worklog
  (`CROSS-SCOPE REQUESTS`).
- **No fake functionality:** every feature calls real logic; platform limits are
  stated in UI text and comments, never hidden.
- Security is non-negotiable: page content is untrusted data; agent capabilities
  flow the chain Agent → PermissionManager → BrowserToolAPI → Chromium (see
  `motion-chromium/motion/security/SECURITY_NOTES.md`); no weakening of Chromium
  hardening anywhere (spec §6).

## Licensing

- Chromium upstream code (the pinned base revision and everything the patch series
  touches) remains under the **Chromium BSD-style license** and its bundled
  third-party licenses; no license files are removed or altered.
- **Motion Browser code** (`app/`, `motion-chromium/motion/`, `patches/`, `tools/`,
  docs) is separate Motion Browser project work, licensed by the Motion Browser
  project.
