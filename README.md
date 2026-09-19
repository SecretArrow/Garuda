# Garuda — Autonomous Android Browser

Browser Android **autonomous**: task berbahasa natural dieksekusi browser —
klik, isi form, scraping, monitoring — dijalankan agent yang berjalan terus di
background, dengan **input browser yang trusted** (`isTrusted: true`) lewat
Chrome DevTools Protocol (CDP), bukan JS injection naik.

## Arsitektur (3 pilar)

| Pilar | Pilihan | Status repo ini |
|---|---|---|
| Engine | Fork **Brave Android** | `fork/` — kit lengkap (patch + script + panduan); dibangun di mesin build khusus |
| Kendali otomasi | **CDP internal** via abstract unix socket | `cdp-bridge/` — HTTP + WebSocket over LocalSocket, full coroutine, live di CI |
| Agent layer | **Kotlin module terpisah** | `app/agent/` — perception, action, orchestrator, provider gateway, service |

Prinsip: prototipe agent layer jalan di app WebView SEKARANG (socket
`@webview_devtools_remote_<pid>`), dan otomatis berpindah ke fork saat socket
`@garuda-devtools` tersedia — `DevToolsLocator` memprioritaskannya.

## Struktur

```
cdp-bridge/            CDP client: socket locator, HTTP/WSS over LocalSocket,
                       CdpConnection + CdpTabSession (Page/Runtime/DOMSnapshot/
                       Input/Network/Emulation) — unit tested
app/
  agent/perception/    PageState + marking e1..eN stabil + serializer hemat token
                       + SoM renderer (screenshot bernomor untuk model vision)
  agent/action/        ActionExecutor: navigate/click/type/scroll/extract/
                       select/checkbox/tabs/captcha/ask_human/finish + rate
                       limit per domain + verifikasi efek + audit
  agent/llm/           Multi-provider: OpenAI-compat (OpenAI, Groq, OpenRouter,
                       DeepSeek, Mistral, xAI, z.ai, Together, Fireworks, vLLM,
                       Ollama), Anthropic, Gemini — auto-detect protokol dari
                       base URL + key, fallback chain, EncryptedSharedPreferences
  agent/runtime/       Orchestrator (QUEUED→PLANNING→RUNNING→WAITING_HUMAN→
                       PAUSED→DONE/FAILED), step loop, budgets, stuck detection,
                       context compaction, checkpoint/resume, foreground service,
                       cron scheduler, boot receiver
  browser/             Tab host WebView (prototype engine)
  ui/                  Browser shell, Chat drawer (streaming + badge tool),
                       Dashboard (audit + token cost + scheduler), Settings
                       (provider manager + guardrails + battery onboarding)
fork/                  Kit build Brave fork (Fase 0–1)
.github/workflows/     CI: lint+unit → emulator (real CDP + agent E2E) →
                       release → publish per-ABI
```

## Acceptance yang terbukti di CI

- ✅ CDP self-connect ke DevTools socket proses sendiri
- ✅ `Input.dispatchTouchEvent` → event `isTrusted: true` di halaman nyata
  (`CdpTrustedInputTest`)
- ✅ Agent E2E: login form di halaman nyata diselesaikan loop
  perceive → LLM → execute → verify (`AgentLoginE2eTest`)
- ✅ Auto-detect provider (MockWebServer), WS frame codec, serializer, scheduler
  (unit tests)

## Mulai cepat

1. Install APK per-ABI dari Releases (`arm64-v8a` untuk HP umum)
2. Settings → Add provider → pilih preset (Groq dkk.) → tempel API key → Save
3. (Opsional) Detect → cek model list + latensi
4. Buka agent chat (ikon 🤖) → tulis tugas → Run
5. Lihat progres live di drawer / tab Tasks (audit + token)
6. Untuk HP Xiaomi/Oppo: Settings → Battery optimization → exempt Garuda

## Catatan

- API key disimpan terenkripsi (Android Keystore). Tidak ada key bawaan.
- Aksi berisiko (submit/pay/delete…) selalu butuh konfirmasi (bisa dimatikan).
- Fork Brave dibangun terpisah — lihat `fork/README.md`.
