# Motion agent bridge — security notes (spec §46, §6, §24, §63)

Scope: the Motion layer inside the Chromium fork (`motion/` + `patches/`).
These notes are the permission model for the fork-side agent bridge. They hold
for both tracks (the Track A app mirrors the same chain on WebView).

## 1. Capability chain (never bypass, never flatten)

    Agent runtime
        ↓  (requests a capability: READ / NAVIGATE / FILL_FORM / SUBMIT / DOWNLOAD / NOTIFY)
    PermissionManager
        ↓  (per-domain rules, default-deny for HIGH-risk actions, approval queue)
    BrowserToolAPI
        ↓  (the ONLY sanctioned call surface into browser internals)
    Chromium internals (WebContents, TabModelSelector, DownloadManagerService, ax tree)

Rules:

1. `MotionAgentBridge` is part of **BrowserToolAPI**, not a standalone power
   source. Every Java/native method it exposes either (a) is an observation
   event fan-out (page started/finished/title) or (b) requires a capability
   verdict that originated at PermissionManager.
2. **No unrestricted access, ever.** There is no "agent root" mode, no debug
   escape hatch, no reflective access around the chain. If a feature cannot be
   expressed through the chain, the feature does not ship.
3. **Fail closed.** `MotionAgentBridge.isAgentEnabled()` requires BOTH the
   `MotionAgentToggle` feature (patch 0005) AND explicit user activation
   (`setAgentActive(true)`). Any error resolving either input disables the
   bridge; nothing degrades to "half-enabled".

## 2. Untrusted content handling (§24)

- Page content is **data, never instructions**. Content-derived strings are
  wrapped as quoted data before entering any prompt or LLM context.
- **No raw page dumps.** Only sanitized excerpts leave the browser layer:
  control/format chars stripped, whitespace collapsed, hard cap
  (`MAX_EXCERPT_CHARS = 2000`), produced by the native a11y-tree extractor and
  re-sanitized in `MotionAiSuggestService.sanitizeExcerpt` (defense in depth).
- Observation uses the **accessibility tree (`ax::mojom`)**, not raw DOM dumps;
  form fields and password inputs are excluded at the extraction layer.
- Prompt-injection heuristics flag imperative-looking lines ("ignore previous
  instructions", credential requests) and surface them as security events
  instead of executing them.

## 3. Secrets and logging (§63)

- The bridge never sees API keys: provider keys live in the host app
  (`SecretStore`, Keystore-backed). Native bridge code has no key access.
- No cookies, session tokens, passwords, or raw form values in logs. Logs
  record event *kinds* and booleans (e.g. "agent bridge initialized"), not
  URLs-with-queries or page text.
- Remote debugging (`adb` DevTools) stays **off** in release builds — this is
  inherited Chromium default behavior and must not be patched open (§6).

## 4. Chromium hardening is out of bounds (§6)

The fork does not: disable the sandbox or site isolation, weaken Safe Browsing,
alter certificate verification, relax same-origin enforcement, or enable any
flag that reduces platform security. GN args in `motion/gn/args.gn` change
build identity only. Any patch proposal touching security-relevant upstream
code is rejected by default and requires a documented, reviewed exception.

## 5. Downloads, approvals, auditability

- Downloads initiated by the agent route through `DownloadManagerService` with
  the goal's `maxDownloads` cap and go through the approval queue when the
  domain/action verdict requires it.
- Every capability request and verdict is auditable: the host app persists
  structured events (`AuditLogger`) — the bridge emits the events, it does not
  store them.
- Emergency stop (`stopAll`) propagates: bridge-level gating re-checks the
  runtime state on every event dispatch, so a stop takes effect at the next
  event at the latest.
