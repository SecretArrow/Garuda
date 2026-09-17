# motion/ — Motion-specific source tree inside the Chromium checkout

This directory is copied into a Chromium checkout at `//motion` (see
`docs/BUILD_CHROMIUM.md` and `docs/UPSTREAM_STRATEGY.md`). Everything here is
Motion Browser project code; upstream Chromium remains untouched outside the
minimal patch series in `patches/`.

## Layout (spec §4)

| Subdir | Purpose | Status |
|--------|---------|--------|
| `java/src/com/motion/chromium/` | Java layer compiled into the browser (bridges, observers, services). | **present** — `MotionAgentBridge.java`, `MotionTabObserver.java`, `MotionAiSuggestService.java` |
| `BUILD.gn` | `motion_java` android_library target (wired into `chrome_java` by patch 0004). | **present** |
| `gn/args.gn` | Canonical GN args template consumed by `tools/gn_gen_android.sh`. | **present** |
| `security/` | Security design notes for the agent bridge permission model (spec §46). | **present** — `SECURITY_NOTES.md` |
| `ai/` | In-process AI glue (provider hand-off to host app, prompt assembly, sanitization policy). | planned — lands with first native rebase |
| `agent/` | Agent runtime bindings (runtime loop adapters on top of the bridge). | planned |
| `automation/` | Deterministic action primitives (type/click/scroll at embedder level). | planned |
| `browser/` | Native bridge (`browser/bridge/motion_agent_bridge.cc` — `content::WebContentsObserver` impl) + NTP host. | planned — first file added at rebase |
| `memory/` | Site/goal-scoped memory bindings (write-through to host app storage). | planned |
| `scheduler/` | Trigger/schedule integration (alarms aligned with browser lifecycle). | planned |
| `ui/` | Motion NTP page, agent status surface, resources. | planned |

## Integration points

The Motion layer observes and controls the browser through four sanctioned
surfaces. Keep this list the single source of truth; every patch touching an
integration point must update it.

1. **WebContents observation**
   - Java: `org.chromium.content_public.browser.WebContentsObserver`
     (`MotionTabObserver` — `onPageStarted` / `onPageFinished` / `onTitleChanged`).
   - Native: `content::WebContentsObserver` implemented by
     `motion/browser/bridge/motion_agent_bridge.cc` (added at rebase), which
     forwards to the Java bridge on the UI thread.
2. **Accessibility tree via `ax::mojom`**
   - Element discovery for the agent reads the rendered accessibility tree
     (`ui::AXNodeData`, `ax::mojom::*` roles/actions) — never raw DOM scraping.
   - Text derived here is the input to the sanitizer chain; only sanitized,
     length-capped excerpts leave the browser layer.
3. **Tabs via `TabModelSelector`**
   - Chrome-side tab model access (`org.chromium.chrome.browser.tabmodel.
     TabModelSelector`) is reached from the patched chrome side (patch 0004
     hook) or natively — **not** imported into `motion_java` (dep-direction
     rule below).
4. **Downloads via `DownloadManagerService`**
   - Same pattern as tabs: `org.chromium.chrome.browser.download.
     DownloadManagerService` accessed from the chrome side; `motion/` receives
     sanitized events (url, mime, size), never file contents.

## Maintainability rules

1. **Dep direction:** `motion_java` must never depend on `chrome_java`
   (patch 0004 wires `chrome_java → //motion:motion_java`). Chrome-only types
   are reached from patched chrome code or native code.
2. **`Motion*` naming** for every public symbol in this tree.
3. **`TODO(rebase)` markers** annotate every place known to drift between
   Chromium revisions (JNI annotation migration, observer signatures, flag map
   reshuffles). Resolving them is part of the rebase checklist
   (`docs/UPSTREAM_STRATEGY.md` §5).
4. **No raw page data leaves this tree.** Excerpts pass
   `MotionAiSuggestService.sanitizeExcerpt` (and the native sanitizer) first;
   no secrets, cookies, or credentials are ever logged or persisted here.
5. **Threading:** all `WebContents`/UI interaction on the Chromium UI thread
   (`ThreadUtils.assertOnUiThread()`); long work is handed to native sequences
   or the host app.
6. **Fail closed:** if any gating input is unavailable (flag lookup fails,
   native library not ready), the agent bridge is disabled, never half-enabled.
