# Zhengdao (证道)

**English** ｜ [简体中文](README.md)

> This is a **condensed English overview**. [`README.md`](README.md) (Chinese) is the canonical
> and always-more-current document — where the two disagree, the Chinese one wins. Detailed
> engineering records (errata, feature ledger, milestones) are Chinese-only under [`docs/`](docs).

Zhengdao is a lightweight AI-agent runtime for Android: **no root, no commands, one tap** to
install and run your own CLI agents.

It gives an ordinary Android phone a real Debian 13.7 Linux environment (via PRoot) and runs
official CLI agents inside it — Claude Code, Hermes Agent, OpenCode and others.

## Requirements

- **Android 16 (API 36)** or newer
- **arm64-v8a** device
- Verified on Honor Magic 5 Pro (MagicOS 10 / Android 16). Other Android versions are **not**
  tested and may not work.

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

## Status (2026-10-08)

**v2.0 R1** — merged into `main`; the release tag is still pending. This round was about
foundations rather than features:

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
- **Reliability batch**: session wakelock renewal, terminal canvas insets, non-silent model-pool
  refresh with human-readable errors, the `zzclean` cache command, and three real-device UI fixes.
- **Tests**: 296 unit tests across 35 suites green (measured 2026-10-09 09:24: 0 failures, 0 errors,
  0 skipped); Macrobenchmark and Compose performance baselines in use.

## Network

An internet connection is required for the first environment install, for installing agents, and
for actually using them (agents call cloud APIs — GLM / DeepSeek / Claude …). Once the environment
is installed, the terminal itself works offline. If you use a proxy app, enable VPN mode and add
Zhengdao to its per-app proxy list.

## License

First-party code is released under **GPL-3.0**; see [`LICENSE`](LICENSE) and
[`PROVENANCE.md`](PROVENANCE.md) for third-party components and their licenses.
