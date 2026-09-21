# Motion Browser Fork Track — Brave Android dengan DevTools socket internal

> **Fase 0–1 dari Plan Pack.** Folder ini TIDAK di-build di CI (build Chromium
> butuh mesin khusus — lihat spesifikasi di bawah). CI membangun dan menguji
> agent layer di app WebView (`:app` + `:cdp-bridge`) dengan protokol CDP yang
> SAMA; ketika fork siap, `DevToolsLocator.FORK_SOCKET_NAME = "motion-devtools"`
> otomatis diprioritaskan dan seluruh agent layer langsung berpindah engine.

## 1. Spesifikasi mesin build

| Komponen | Minimum | Nyaman |
|---|---|---|
| OS | Linux x64 (Ubuntu 22.04/24.04, Debian 12) | idem |
| CPU | 8 core | 16 core |
| RAM | 32 GB + 8 GB swap | 64 GB |
| Disk | 250 GB SSD | 500 GB NVMe |
| Jaringan | ~100 GB unduhan pertama | — |

Build pertama 4–12 jam; iterasi patch 20–60 menit.

## 2. Setup (Fase 0)

```bash
bash fork/scripts/setup-fork.sh      # melakukan semua langkah di bawah
```

Langkah manual yang dijalankan script:
1. Install depot_tools + `export PATH=$PATH:~/depot_tools`
2. `mkdir ~/motion && cd ~/motion`
3. `fetch --nohooks android` (atau clone brave-core + `npm run init`)
4. `gclient sync -D --with_branch_heads` (hook Android)
5. `gn gen out/Motion Browser arm64` dengan `target_os="android"`, `target_cpu="arm64"`
6. Terapkan `fork/patches/motion-devtools-socket.patch` ke tree
7. `autoninja -C out/Motion Browser arm64` → `chrome_public_apk` (atau target brave)

## 3. Patch inti (Fase 1): DevTools pada abstract unix socket

`fork/patches/motion-devtools-socket.patch`:
- `content/browser/devtools/devtools_http_handler.cc` — menambah `kUseAbstractUnixSocket`
  + `net::UnixDomainServerSocket` pada nama `"@motion-devtools"` (NAMESPACE_ABSTRACT)
  sebagai listener alternatif selain TCP default.
- `content/browser/devtools/devtools_manager_delegate.cc` — memastikan handler
  selalu aktif pada build release (tanpa flag command line).
- `android_webview/glue/java/...` (WebView phase tidak perlu patch ini).

Aturan penting dari plan:
- JANGAN gunakan `remote-debugging-port` TCP.
- Nama socket `@motion-devtools` — `DevToolsLocator` di `:cdp-bridge` sudah
  memprioritaskannya.
- Aplikasi host connect ke socket yang SAMA proses → tidak butuh adb forward.

## 4. Verifikasi

```bash
adb shell cat /proc/net/unix | grep motion-devtools
# → 00000000: 00000002 00000000 00010000 0001 01 12345 @motion-devtools
```

Lalu dari aplikasi Motion Browser (atau `:cdp-bridge` test): `DevToolsClient.autoDiscover()`
harus melaporkan socket `motion-devtools` dan `listTargets()` mengembalikan tab.

## 5. Branding (Fase 0 item 3)

- `chrome/browser/ui/android/strings/...` → nama app "Motion Browser"
- Package id di `brave_public/BUILD.gn` / `chrome/android/BUILD.gn`
  (`android_prefix_template`), TANPA menyentuh update mechanism (omaha/update
  server tetap Brave upstream, tidak dikirim telemetry).

## 6. Integrasi agent layer

1. Tambahkan `:cdp-bridge` (AAR) ke build fork atau sebagai Gradle module terpisah
   yang di-bundle via `brave/android/brave_public_deps`.
2. `DevToolsLocator.candidateNames()` sudah mengutamakan `motion-devtools`.
3. Jalankan full unit + instrumented suite yang sama di CI fork (self-hosted
   runner dengan spesifikasi §1) — workflow `build.yml` bisa dipakai ulang.
