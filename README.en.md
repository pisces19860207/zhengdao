# Zhengdao (证道)

**English** ｜ [简体中文](README.md)

> This is a **condensed English overview**. [`README.md`](README.md) (Chinese) is the canonical
> and always-more-current document — where the two disagree, the Chinese one wins. Detailed
> engineering records (errata, feature ledger, milestones) are Chinese-only under [`docs/`](docs).

Zhengdao is a lightweight AI-agent runtime for Android: **no root, no commands, one tap** to
install and run your own CLI agents.

It gives an ordinary Android phone a real Debian 13.7 Linux environment (via PRoot) and runs
official CLI agents inside it — Claude Code, Hermes Agent, OpenCode and others.

> ⚠️ **Check this first: requires Android 16 (API 36)+ and an arm64 phone — if it doesn't match,
> don't download; it won't run.** (See [Requirements](#requirements).)

## Requirements

- **Android 16 (API 36)** or newer
- **arm64-v8a** device
- Verified on Honor Magic 5 Pro (MagicOS 10 / Android 16, model code `PGT-AN10`). Other Android
  versions are **not** tested and may not work.

> **Why `targetSdk` is pinned to 28:** Android 10+ forbids executing files from an app's own
> writable data directory (W^X), and Zhengdao's entire Linux environment — thousands of
> executables — lives exactly there. Raising `targetSdk` would turn the environment into
> non-executable dead files. Termux (GitHub/F-Droid) and Taixu take the same approach.

## What you get

Three bottom tabs:

- **Taiji** — a built-in OpenCode chat client rendering Markdown, thinking blocks and tool cards,
  with session history and a model-pool picker. It runs on the **bionic host** (no PRoot overhead).
- **Terminal** — the real Debian 13.7 environment with tmux session persistence.
- **Danfang** — one-tap agent install / launch / uninstall with a built-in environment self-check.

Settings live behind the gear icon. Agent output goes to a shared workspace (default
`Download/证道`), visible in any file manager and kept after uninstalling Zhengdao.

## Status (2026-10-11)

**Current release: v2.0.12** (`versionCode 28`, 2026-10-11). The feature surface was frozen at
v1.1 and closed out at v2.0.12 — from here the project is **maintenance-driven**: bug fixes and
keeping up with upstream, not further restructuring. Remaining items (7 open, including known
performance and UX entries) are tracked in [Issues](https://github.com/pisces19860207/zhengdao/issues).

`main` currently runs **574 unit tests across 64 suites, all green** (2026-10-11: 0 failures,
0 errors, 1 skipped by environment).

### Recent releases

| Version | Date | In one line |
|---|---|---|
| **v2.0.12** (current) | 2026-10-11 | The Taiji page's connection now stays alive while the app is open — switching tabs or coming back no longer re-handshakes (also fixes an occasional 401 right after an app update). The UI is unified on an iOS-style skin (tab pills removed, dark terminal chrome, dark key bar — **the 17 key ids and their order are untouched**). Cache cleanup no longer counts Hermes' **required** package `ffmpeg` as optional, the appearance card keeps only line spacing selectable, and the key bar's `⌫` is now **DEL**. Plus ten UI/UX fixes. |
| v2.0.11 | 2026-10-11 | Fixed the built-in opencode (bionic host build) failing to install (E-090); library scanning no longer truncates silently (E-089); requirements moved to the first screen. |
| v2.0.10 | 2026-10-10 | Terminal visual upgrade: **Catppuccin Mocha** as the factory palette, a GB2312-subset font (APK 13.53 → **7.82 MB**), and a "comfortable" line-spacing default. |
| v2.0.9 | 2026-10-10 | Install-path hardening (scripts in the public area are fingerprinted before they are ever executed); two real rootfs bugs plus five audit items; library summaries became local-only. |
| v2.0.0 | 2026-10-09 | Engine consolidation and the signed **environment trust chain** (below). |

Release notes for every version are on the [Releases page](https://github.com/pisces19860207/zhengdao/releases).

### Environment trust chain (established in v2.0.0, unchanged since)

- **Rust core consolidation** (`zhengdao_core`): SHA-256, rootfs extraction (zstd + tar) and
  install-integrity digests now live behind a **single** `libzhengdao_core.so` (one JNI boundary,
  one library load), with automatic fallback to the pure-Java path when native code is unavailable.
- **Install integrity / environment fingerprint** (E-044/E-049): "repair environment" and
  fallback reinstalls no longer wipe the fingerprint, and a same-version package already on disk is
  reused instead of re-downloading ~192 MB.
- **Smaller environment package**: build leftovers removed, locales trimmed, and the GPU
  software-rendering stack dropped (`libllvm19`, −118 MB on disk) with zero loss for terminal use.
- **Index signing**: Ed25519; the app verifies the signature before parsing (the Rust core verifies
  it and the result is cross-checked against the platform implementation — a mismatch is a rejection).
  The CI "no key, no publish" gate is live: the repository secret is configured and the published
  `.sig` has been fetched back and verified (2026-10-08, see `docs/ERRATA.md` E-052).
- **`.so` gate in CI** (E-048): ELF parsing enforces 16 KB page alignment and the presence of every
  JNI entry symbol (expected names are read from `CoreNative.kt`, never hard-coded).
- **Local package checksums defer to the signed index** (E-053): the terminal's "SHA256 mismatch"
  install failure came from a stale, unmaintained local `.sha256` sidecar; the sidecar is now
  reconciled against the index and rewritten after a successful check.
- **Delta updates** (E-050): environment updates download and apply binary patches; patch extraction
  also runs in the Rust core and can skip named members; a checksum mismatch fails hard and leaves
  the installed environment untouched.
- **Taiji tab usability fixes** (2026-10-09): tool-status pill badges (`●/✓/✗`), a permanent delete
  entry on session rows, and non-silent session-refresh failures that keep the existing list.
- **Reliability batch**: session wakelock renewal, terminal canvas insets, non-silent model-pool
  refresh with human-readable errors, the `zzclean` cache command, and three real-device UI fixes.
- **Tests at v2.0.0**: 296 unit tests across 35 suites green (measured 2026-10-09 09:24: 0 failures,
  0 errors, 0 skipped); Macrobenchmark and Compose performance baselines in use.

## Network

An internet connection is required for the first environment install, for installing agents, and
for actually using them (agents call cloud APIs — GLM / DeepSeek / Claude …). Once the environment
is installed, the terminal itself works offline. If you use a proxy app, enable VPN mode and add
Zhengdao to its per-app proxy list.

## License

First-party code is released under **GPL-3.0**; see [`LICENSE`](LICENSE) and
[`PROVENANCE.md`](PROVENANCE.md) for third-party components and their licenses.
