# Zhengdao (证道)

**English** ｜ [简体中文](README.md)

[![Latest release](https://img.shields.io/github/v/release/pisces19860207/zhengdao?label=release&color=blue&sort=semver)](https://github.com/pisces19860207/zhengdao/releases)
[![License](https://img.shields.io/badge/license-All%20rights%20reserved-lightgrey)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-16%2B%20%C2%B7%20arm64-brightgreen)](#requirements)

> This is the **English version of [`README.md`](README.md)**. The two are kept in step; where they
> disagree, the Chinese one wins. The detailed engineering records (errata, feature ledger,
> milestones, acceptance pages) are Chinese-only under [`docs/`](docs).

Zhengdao is a lightweight AI-agent runtime for Android: **no root, no commands, one tap** to
install and run your own CLI agents.

It gives an ordinary Android phone a real Debian 13.7 Linux environment (via PRoot) and runs
official CLI agents inside it — Claude Code, Hermes Agent, OpenCode and others.

> ⚠️ **Check this first: it requires Android 16 (API 36)+ and an arm64 phone — if your device does
> not match, do not download; it will not run.** (See [Requirements](#requirements).)

> **Agent support status (2026-10-11, verified on a real Honor Magic 5 Pro)**: **OpenCode is no longer built in** (the Baopu tab's bionic host build was killed by W^X once `targetSdk` went to 36; abandoned outright on 2026-10-15 and the tab's replacement is still being researched — see "Where Baopu's built-in OpenCode went" below); install the official CLI yourself with `npm install -g` inside the terminal; **Hermes Agent is supported** (one-tap install → launch → TUI,
> `hermes update` works; it ships its own pinned Python toolchain, so the install step needs a
> network, and a broken dependency environment can be repaired in one tap from the Yiran tab);
> **Claude Code** and **AGY CLI (Antigravity)** install freely in the terminal from their own
> official scripts.

> **Trademarks**: names such as Claude Code, OpenCode, Hermes and AGY CLI belong to their respective owners. This project has **no affiliation with, authorisation from, or partnership with** those vendors; they are mentioned only to state the fact that their official clients can be run.

> **Layout** (four tabs on `main` since 2026-10-14): **Baopu 抱朴** (agent chat, **being reworked**) /
> **Xiaoyao 逍遥** (terminal, *freedom*) / **Xuanlan 玄览** (browser, *expectation*) /
> **Yiran 怡然** (agent management, *happiness*); settings live behind the gear icon, and the app
> opens on Yiran.
> ⚠️ **v2.1.1 and earlier releases still have three tabs — Taiji / Terminal / Danfang**: the renaming
> and the Xuanlan (browser) batch sit on `main`, **not released yet** (no tag, no Release), and will
> go out with the next version. Historical entries further down that say "Taiji" or "Danfang" mean
> today's Baopu / Yiran.
> **File boundary**: agent output goes to one shared workspace (default `Download/证道` on the
> phone, changeable in settings) — visible in any file manager, and kept after Zhengdao is
> uninstalled.

## Contents

- [Requirements](#requirements)
- [Download and install](#download-and-install)
- [Network](#network)
- [Honor / MagicOS keep-alive guide](#honor--magicos-keep-alive-guide)
- [FAQ](#faq)
- [Privacy](#privacy)
- [Status](#status-2026-10-13)
- [Recent releases](#recent-releases)
- [Environment trust chain](#environment-trust-chain-established-in-v200-unchanged-since)
- [Documentation layout](#documentation-layout)
- [Independent development](#independent-development)
- [Acknowledgements](#acknowledgements)
- [License](#license)
- [Redistribution, repackaging and the name "Zhengdao"](#redistribution-repackaging-and-the-name-zhengdao)

## Requirements

- **Android 16 (API 36)** or newer
- An **arm64-v8a** device (the vast majority of modern Android phones and tablets)
- Verified on a **Honor Magic 5 Pro (MagicOS 10.0.0.175 / Android 16, model code `PGT-AN10`)**

> **⚠️ About other Android versions**
> This project has only been **tested on real Android 16 hardware**; other versions (Android 15 and
> below) are **neither adapted nor verified** and may have compatibility problems.
> If you try it on a non-Android-16 device, please assess the risk yourself and test it —
> **it is not guaranteed to run**. This is a spare-time personal project with genuinely limited
> capacity, so there is no support for adapting to every Android version — **thank you for
> understanding**.

> **🔒 About `targetSdk` (raised from 28 to 36 on 2026-10-15)**
> Zhengdao now ships with **`targetSdk 36`** (knife ㉕, `954bf87`, on the real-device evidence in
> [`docs/ERRATA.md`](docs/ERRATA.md) **E-134**), and `minSdk` is 36 as well — so the bar is Android 16.
> - **Why it can go up**: the Termux quartet moved into `jniLibs` (`nativeLibraryDir` is read-only and
>   executable) with an explicit `PROOT_LOADER`, and guest binaries are **mmap'ed into memory** by the
>   proot loader (Android never `execve`s anything inside the rootfs) — so "no exec from the data
>   directory" never reaches them. The old "must stay ≤ 28" absoluteness is disproved.
> - **The cost (reproduced on hardware)**: an app with `targetSdk ≥ 29` **cannot launch a binary from its
>   own data directory** — Baopu's built-in opencode (`ProcessBuilder(filesDir/oc/usr/bin/opencode)`,
>   bypassing the loader) fails the moment a 36 build lands, with **`error=13, Permission denied`**
>   ([`docs/ERRATA.md`](docs/ERRATA.md) **E-140**, which overturned the earlier "not reproduced" E-139).
>   That is exactly why the built-in bionic build was **abandoned outright**.
> - **Storage**: the main path is "all files access" (`MANAGE_EXTERNAL_STORAGE`) plus direct `/sdcard`;
>   none of it depends on `targetSdk`. The old trick of dropping the manifest `READ` hat for targetSdk 28
>   (`d414dca`) is history.
> - **Long-term risk**: if Android tightens the `untrusted_app` domain policy further or raises the
>   minimum installable `targetSdk`, Zhengdao will have to migrate again; this is re-checked on real
>   hardware every quarter — see [`docs/milestones/已知限制.md`](docs/milestones/已知限制.md).

## Download and install

1. **Download**: official APKs are on the [Releases page](https://github.com/pisces19860207/zhengdao/releases) (install directly); CI artefacts (APK / rootfs / PRoot) are on the [Actions page](https://github.com/pisces19860207/zhengdao/actions).
2. **Check the requirements first**: you need an **Android 16 (API 36) + arm64** phone — if your device does not match, do not download; it will not run (see [Requirements](#requirements)).
3. **Check the signature**: every package is signed, and the in-app "About" page shows **official release ✓ / ⚠️ unofficial (repackaged)**; an unofficial build warns you again at startup (`docs/ERRATA.md` E-065).
4. **First run**: grant the permissions item by item on the single first-run page, download the ~200 MB Debian rootfs, then install an agent in one tap from the Yiran tab (OpenCode is no longer bundled — install it in the terminal).
5. **Updates**: the app can check for app and component updates; environment updates download **differential patches only** (see "Environment trust chain" below).

> On Honor / MagicOS phones, set the background permissions from the keep-alive guide below first, or the system may reap the app.

## Network

Zhengdao is designed as "local environment + cloud agents", so its network needs come in stages:

| Stage | Network needed? | Notes |
|---|---|---|
| **First environment install** | **Required** | Downloads the ~200 MB Debian rootfs archive (dead weight such as the GPU rendering stack is stripped at build time — see [Status](#status-2026-10-11)) |
| **Installing an agent** | **Required** | Downloads Claude Code / Hermes and friends from their official sources (OpenCode is no longer bundled — install it in the terminal) |
| **Using an agent day to day** | **Required** | Agents call cloud APIs (GLM / DeepSeek / Claude …); no network means no agent |
| **Terminal only** | **Optional** | Once the environment is installed, bash / git / python / npm and friends work offline |

> Zhengdao is positioned as "a runtime for cloud agents" and does not do local model inference. An
> agent's intelligence comes from a cloud API, so without a network it cannot work — but the local
> Linux environment still can.

### Proxy note

If you use a proxy app (Clash, v2rayNG, …), make sure:

1. The proxy app is running in **VPN mode** (system-wide proxy).
2. If it offers per-app proxying, **Zhengdao is checked in it** — this is the most common cause of
   connection failures.
3. If it still cannot connect, you can enter the proxy address by hand under settings → "Advanced".

## Honor / MagicOS keep-alive guide

Measured on Honor MagicOS (including the Magic 5 Pro); these settings keep Zhengdao from being
reaped in the background:

1. **App launch management** — Settings → Apps and services → App launch management → find
   "Zhengdao": turn off "Manage automatically", then enable "Auto-launch", "Secondary launch" and
   "Run in background" by hand.
2. **Battery optimisation** — Settings → Battery → More battery settings → find "Zhengdao" → set it
   to "Don't optimise".
3. **Lock it in recents** — open recents (swipe up and hold), find the "Zhengdao" card, swipe down
   until the padlock icon appears, then tap it to lock.
4. **If the process still exits** — in Developer options, make sure "Don't keep activities" is not
   checked.

> Honor is fairly respectful of foreground services. As long as Zhengdao shows its "foreground
> service" notification and is locked in recents, it usually survives in the background. If the
> system does reap it, the built-in tmux session recovery re-attaches to the old scene
> automatically the next time you open the app.

## FAQ

**It will not open / says unsupported** — the bar is hard: **Android 16 (API 36) + arm64**. A build repackaged by someone else also shows a one-time warning at launch (E-065).

**The agent spins forever and cannot connect** — the usual cause is the proxy not covering Zhengdao: the proxy app must run in **VPN (system-wide) mode**, and if it has a **per-app proxy** list, Zhengdao has to be ticked in it (see "Network" above). Still stuck? Set the proxy by hand under Settings → Advanced.

**The app keeps getting killed mid-run** — work through the "Honor / MagicOS keep-alive guide" (auto-launch, secondary launch, background activity, battery optimisation, locking it in recents). Even if the system does reap it, reopening Zhengdao **re-attaches to the existing tmux session**, so the work is not lost.

**What is `targetSdk`, and why did it go to 36** — it is **36** now (knife ㉕, `954bf87`, on real-device evidence in `docs/ERRATA.md` E-134), and `minSdk` is 36 too, which is why the bar is Android 16. The cost is that the app **can no longer launch a binary from its own data directory** (E-140) — which is why Baopu's built-in OpenCode was dropped in favour of installing the CLI in the terminal.

**If I uninstall, do I lose my files? Must I re-download the environment** — agent output lives in the workspace (default `Download/证道`) and **survives uninstall**; the Debian environment inside the app's private storage goes away with the app and is downloaded again (~200 MB) after reinstalling. Environment **updates** download delta patches only, never the whole package.

**Can it run a local LLM** — No. Zhengdao is a runtime *for* cloud agents: the local side provides a real Linux environment and toolchain, the intelligence comes from cloud APIs (GLM / DeepSeek / Claude and others).

**Does it need root** — No; the whole Linux environment runs under PRoot. But it shares the app's **Android UID**, so run only commands you trust (see "Privacy").

**What should a bug report include** — version (Settings → About), device model and Android version, and the steps to reproduce; the log entry points are the "keep-alive record" and "run log" pages under Settings (their buttons land on the right page since v2.0.16).


## Privacy

Zhengdao does not upload user data. Network access is used only to:

- download the rootfs and agent installers;
- check for app and component updates.

Server access logs exist only for troubleshooting, are kept for 7 days, and are not used for
behavioural analysis.

⚠️ **The terminal is not a sandbox.** Guest processes (under PRoot) run with the **same Android UID**
as the app, on the same kernel and network stack, and can read and write any file the app can.
**Run only commands you trust** — the app's signing and verification chain protects against a
*tampered distribution*, not against *code you execute inside the terminal yourself*. See
[`docs/milestones/已知限制.md`](docs/milestones/已知限制.md) §10 (Chinese).

## Status (2026-10-13)

**Current release: v2.1.1** (`versionCode 36`, 2026-10-13). The feature surface was frozen at
v1.1 and closed out at v2.0.12 — from here the project is **maintenance-driven**: bug fixes and
keeping up with upstream, not further restructuring. Remaining items (including known
performance and UX entries) are tracked in [Issues](https://github.com/pisces19860207/zhengdao/issues).

> **Accumulated on `main`, not released yet** (a new batch since 2026-10-14): the **four-tab rename**
> (Baopu / Xiaoyao / Yiran / Xuanlan, both locales) and **Xuanlan (the browser tab)** — multiple
> tabs, an address bar with search, Safe Browsing blocking, terminal links opening inside the app,
> and page-summary export for agents. Per the 2026-10-14 decision to **not ship yet**, the version
> number stays put and there is **no tag and no Release**; all of it goes out with the next version.
> Below, "Current release" and the tab layout describe the shipped **v2.1.1**, while the feature
> list reflects `main`.

The v2.1.0 batch branch (`fix/batch-v2.1.0`) and the v2.1.1 fix branch (`fix/session-switch-e123`)
run **1189 unit tests across 170 suites, all green**
(2026-10-16 on `main` (`e4a33c3`): 0 failures, 0 errors, 9 skipped by environment; the later batches merged after
v2.1.1 are logged in `docs/FEATURE-LEDGER.md` §A from #76 on).

### Recent releases

<details>
<summary>Show the last 14 releases (v2.0.0 → v2.1.1)</summary>

| Version | Date | In one line |
|---|---|---|
| **v2.1.1** (current) | 2026-10-13 | Fixes **E-123: Taiji did not reload when you switched back to a historical session** (the switch pre-set "current session" to the target before asking whether the connection must be reopened, so the reuse rule read it as a tab switch and returned early — no message reload, no rebuilt polling or SSE, leaving an empty list until the app was killed and restarted; a latent regression introduced with the reuse rule in v2.0.12); two JVM regression tests pin it down ("clearing residue must never touch sessionId", "clearing residue must force a reopen"). |
| **v2.1.0** | 2026-10-13 | A **Codex CLI** card on the Agents page (npm global install, launched as `codex --no-daemon` so the top-level flag cannot be misordered, uninstall keeps `~/.codex`; install/launch/uninstall only — no preset config, no key input, bring your own setup inside the terminal); the keep-alive wake lock now follows **terminal activity** instead of holding forever (all of this app's processes are sampled every 30s and the lock is released only after 10 idle minutes, with the notification flipping between "session running" and "idle sleep" and a deliberately conservative rule: hold too long rather than misjudge a busy terminal as idle); Taiji **self-heals a 401** (the other half of E-093 — it retries once with the freshly re-parsed password and never touches the streaming channel) and the serve password is now resolved by collecting up to three recent candidates and probing `GET /api/session` for the one that actually authenticates. |
| v2.0.18 | 2026-10-13 | English UI plus a dark theme (743 strings localised end to end, Appearance can follow the system or pin light/dark, the environment check now streams results in parallel); cleartext traffic narrowed from a global switch to a `network_security_config` that allows loopback only; E-097 closed out — the copyright texts of all 348 Debian packages now ship with the image (a `zstd` archive inside the rootfs, also published as a standalone Release asset); a "bugs and dead code" sweep (install lock now covers session startup, backup rules exclude path by path, the serve-stop matcher was narrowed, a main-thread assertion, one shared path-containment rule, backspace still mirrors the line buffer under sticky Ctrl); two new gates — building a release locally without the signing key now fails, and CI verifies the licence texts byte-for-byte. |
| v2.0.17 | 2026-10-11 | Cache-cleanup policy closed out (E-115): Hermes' own browser tool and `cua-driver` are now **named-keep** items — the tools group has **zero clearable entries** and the cleanup entry is no longer rendered, following the rule set in v2.0.12 (never treat Agent-required packages as optional; that incident was `ffmpeg`). |
| v2.0.16 | 2026-10-11 | License boundary closed out: every third-party license text now ships inside the APK under `assets/licenses/`, readable offline under About → Open-source licenses; the two "view logs" buttons (keep-alive record / run logs) now open the right destination instead of falling back to Settings; fixed Settings row labels being squeezed vertical by long values (E-111, the trigger for this release). |
| v2.0.15 | 2026-10-11 | Three fixes from on-device feedback: "clean Agent caches" now runs off the main thread and **counts while deleting** (it used to delete synchronously, so a large cache froze the UI); switching workspaces now **asks first** (copy and switch / switch only), and when the current library is unmounted but another one has files the app offers **one-tap copy**; returning to Settings **re-organises the library automatically** (no detour through the terminal). Also: dragging while selecting text no longer scrolls the terminal, and the "column → valid cursor column" rule is now a single implementation (fixing the zero-width-character case that snapped the handle back a column, pinned by 8 unit tests). |
| v2.0.14 | 2026-10-11 | Three named UI fixes: first-run permissions went from a series of system dialogs to a single **permissions page** (everything on one screen, grant row by row, remembered as guided); the terminal's long-press "more" menu drops "reload font size" and selected text now goes to the **system default search** instead of a hard-coded Bing URL; Settings' "keep-alive record" card became a **single-row entry plus its own page** (content unchanged). |
| v2.0.13 | 2026-10-11 | Release builds moved to an **independent release signing key** (E-099; already-installed older builds must be uninstalled once); the terminal title bar was shortened with auto-sizing so it is no longer ellipsised; the v2.0.12 notes gained an "uninstall first" warning and 19 old official releases were cleaned up. |
| v2.0.12 | 2026-10-11 | The Taiji page's connection now stays alive while the app is open — switching tabs or coming back no longer re-handshakes (also fixes an occasional 401 right after an app update). The UI is unified on an iOS-style skin (tab pills removed, dark terminal chrome, dark key bar — **the 17 key ids and their order are untouched**). Cache cleanup no longer counts Hermes' **required** package `ffmpeg` as optional, the appearance card keeps only line spacing selectable, and the key bar's `⌫` is now **DEL**. Plus ten UI/UX fixes. |
| v2.0.11 | 2026-10-11 | Fixed the built-in opencode (bionic host build) failing to install (E-090); library scanning no longer truncates silently (E-089); requirements moved to the first screen. |
| v2.0.10 | 2026-10-10 | Terminal visual upgrade: **Catppuccin Mocha** as the factory palette, a GB2312-subset font (APK 13.53 → **7.82 MB**), and a "comfortable" line-spacing default. |
| v2.0.9 | 2026-10-10 | Install-path hardening (scripts in the public area are fingerprinted before they are ever executed); two real rootfs bugs plus five audit items; library summaries became local-only. |
| v2.0.0 | 2026-10-09 | Engine consolidation and the signed **environment trust chain** (below). |

Release notes for every version are on the [Releases page](https://github.com/pisces19860207/zhengdao/releases).

</details>

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

**Feature surface** (frozen since v1.1; v2.0.12 swapped in an **iOS-style UI skin** with no change
in features):

- **Baopu tab** (formerly "Taiji", *reality*): a native Compose client for OpenCode (the built-in host build was abandoned on 2026-10-15; this tab is being reworked) — Markdown
  rendering, collapsible thinking, tool cards, session history / new / resume, and a model-pool
  picker (free models labelled, switchable per session, kept across restarts)
- **Terminal**: a real Debian 13.7 environment (PRoot) with tmux session persistence; one-tap
  split-screen and screen-off; three orientation notes on first entry; the "More" menu has clear
  screen and reload font size
- **Yiran** (formerly "Danfang", *happiness*): one-tap agent install / launch / uninstall (double
  confirmation, real `du` sizes, user data preserved) plus environment self-check and self-healing
- **Xuanlan** (browser, *expectation*): an in-app multi-tab browser — type a URL or a search in the
  address bar, Safe Browsing blocks risky sites, and a link tapped in the terminal opens inside the
  app; "export for agent" drops a summary of the current page into files the guest can read
  (on `main`, not released yet)
- **Plugin management** (settings → Plugins): manages the plugins of the former **built-in** OpenCode that
  Baopu runs (enable/disable, cache cleanup)
- **Library** (settings → Library): puts material on your phone within reach of the terminal
  agents; "attach library hints when asking" is **off by default** — when enabled, the app matches
  substrings **on-device** and appends just "filename + line number" to your message, so the
  content never leaves the phone (E-080 / E-084)
- **OpenCode** (the built-in bionic build is abandoned — install the CLI in the terminal), **Hermes Agent supported** (the latter verified end-to-end on real hardware);
  Claude Code / AGY CLI use their own official scripts
- Storage policy, settled: MANAGE as the main path plus direct `/sdcard`; 4 notification channels;
  cache cleanup is **shown in tiers** — nothing under `~/.hermes/tools` is clearable: Hermes
  **required** packages (such as `ffmpeg`) and the **extensions it installs itself** (`chromium` /
  `agent-browser` for browsing, `cua-driver` for desktop automation — all kept on the user's explicit
  request, so since v2.0.17 the tools group has no clearable entry) are reported but never clearable; and in
  the public area "install scripts" can be cleared while the "workspace" is your data and is not

> **⚠️ Where Baopu's built-in OpenCode went (abandoned on 2026-10-15)**: the "Baopu" tab used to run the
> app's **built-in** host build (ready out of the box, running on the **host bionic**, not through PRoot;
> ~65 MB downloaded, ~289 MB on disk; its config lives in `files/oc/xdg/config/opencode/`).
> **That route is abandoned for good**: once `targetSdk` went to 36, launching a binary from the app's data
> directory is blocked by W^X (see [`docs/ERRATA.md`](docs/ERRATA.md) **E-140**). What Baopu becomes instead
> (the official CLI inside PRoot, or a different built-in chat surface) **still needs research before a decision** —
> option ② (moving oc into `jniLibs`) is ruled out on APK-size grounds (~289 MB, ledger `§B` item 64), and the
> oc binary route is **closed for good as of 2026-10-16**;
> until then the tab keeps only the minimal "install / update if missing" placeholder, and the plugin page
> still applies to the old built-in copy alone.
> **Since v1.2 the app does not pre-install an OpenCode in the terminal either**: if you want the official
> CLI there, `npm install -g` is your call.
> ⚠️ The old built-in copy and the terminal do not share a network stack, so **editing `/etc/hosts` or
> `/etc/resolv.conf` inside the rootfs does not affect Baopu**, and vice versa (pit #0 in the
> troubleshooting manual).

## Documentation layout

The documentation has three layers, each with a job. **Look at the right layer before touching
code**, so you do not follow an out-of-date description:

| Layer | Where | What it is for |
|---|---|---|
| **① Overall design** | [`docs/milestones/安卓AgentApp-设计方案-v3.md`](docs/milestones/安卓AgentApp-设计方案-v3.md) | Architecture, technology choices and architectural red lines (the single-session model, how PRoot is distributed). Every phase spec derives from it. ⚠️ Its old "`targetSdk 28` pinned" wording has been falsified (see **E-134**); the note above is the current wording. |
| **② Phase specs** | [`docs/milestones/`](docs/milestones/) (M1.1 – M5) | The **engineering constraints and acceptance criteria** of each milestone. The rule: **implementation follows the code; constraints and red lines follow this document.** |
| **③ Compliance floor** | [`PROVENANCE.md`](PROVENANCE.md) | Where third-party components come from, their licences, and the lines that must not be crossed (e.g. PRoot may only `exec`, never `loadLibrary`). |

> One line to remember: **the overall design sets the direction, the phase specs set the standards,
> PROVENANCE sets the floor.**

## Independent development

All first-party code in this project was written independently, from scratch, without reference to
the code of any third-party app of the same kind. The list of official references that may be
consulted, and what is forbidden, are in [PROVENANCE.md](PROVENANCE.md).

## Acknowledgements

This project was built by me together with a few AI partners, each with a role:

**DeepSeek** (the partner I talk to most) — day-to-day discussion, design collisions and debugging
strategy all start here. Many key judgements were ground out in back-and-forth with it, then handed
to an implementer to land.

**WorkBuddy** (the planner) — the entire M1-to-M5 milestone plan and the skeleton documents came
from it: what each phase does and what its acceptance criteria are, with the boundaries drawn
clearly enough that execution could not drift. Today several of its persona-carrying instances each
run a workstream over MCP, in parallel.

**Zcode** (Zhipu AI's coding assistant, powered by GLM-5.3-Flash) — the main developer throughout.
From loading PRoot on Android, SELinux blocks and error 255, to switching the Termux fork baseline,
the rootfs build pipeline and the UI, and on to the Hermes uv hard-link deadlock that stalled the
project for days (broken in the end by wrapping the pinned uv binary Hermes ships) — Zhengdao went
from one command to a complete app, written line by line by it.

**DSH** (the closer) — the integrator for parallel multi-branch development: merging branches,
fixing conflicts, getting builds through, standing up CI gates, writing the errata and the feature
ledger, cleaning worktrees, and finally delivering real-device acceptance. The v2.0 R1 internals
(Rust core consolidation, environment package slimming, index signing, the `.so` gate) came from it.

I do the requirements, the decisions, the falling into pits, and the relaying between them.

Special thanks to GLM-5.3-Flash for the free quota that let this project go from an idea to a real,
runnable GitHub repository across 500 million tokens of conversation.

And thanks to the open-source projects we stand on:

- [Termux](https://github.com/termux/termux-packages) — the Android PRoot distribution, plus the
  terminal-emulator / terminal-view engines
- [proot-me/proot](https://github.com/proot-me/proot) — the foundation for running a whole Linux
  environment without root
- [astral-sh/uv](https://github.com/astral-sh/uv) — a blazing-fast Python package manager
- [Nous Research](https://github.com/NousResearch) — Hermes Agent and its open official install script
- [OpenCode](https://github.com/sst/opencode) — the open-source coding agent
- [Debian](https://www.debian.org/) — 13.7 as the guest base

## License

First-party code has been **"All rights reserved"** since **2026-10-11** (see the repository-root
[`LICENSE`](LICENSE)): reading, cloning and evaluating it locally (including building and running it
for evaluation) is fine, but **redistribution and any commercial use — paid or free, offering it as a
service (SaaS / hosting), bundling / OEM / white-labelling, company-wide deployment — require prior
written permission from the project owner** (contact: putaopanpan@gmail.com).

**Releases up to and including v2.0.15 are unaffected**: they remain under the **GPL-3.0** they shipped
with, and GPL grants are irrevocable — anyone who already has such a copy keeps their GPL rights. That
GPL-3.0 text now lives at [`licenses/GPL-3.0.txt`](licenses/GPL-3.0.txt) (moved out of the root
`LICENSE`, byte-for-byte unchanged).

> **Why the change was possible**: the repository has **no external human contributors** — first-party
> copyright is entirely with the project owner. See section 7 of
> [`docs/闭源切换清单-2026-10-11.md`](docs/闭源切换清单-2026-10-11.md) for the evidence (an authorship
> census over all 710 commits). Future outside contributions must sign [`CLA.md`](CLA.md) first —
> see [`CONTRIBUTING.md`](CONTRIBUTING.md).

The bundled Termux terminal-emulator / terminal-view (v0.119.0-beta.3) is **Apache-2.0** (it was
previously mis-labelled GPL; see PROVENANCE.md for the erratum); the bundled PRoot component is
compiled from the upstream GPL project (proot-me/proot) and aggregated as a standalone executable —
its origin and licence obligations are in [THIRD-PARTY-LICENSES.md](THIRD-PARTY-LICENSES.md).

## Redistribution, repackaging and the name "Zhengdao"

First-party code is **"All rights reserved"** as of **2026-10-11** (older releases, ≤ v2.0.15, remain
GPL-3.0). The author's stance, written down so nobody has to ask:

- **Welcome**: writing tutorials, recording videos, charging for setting up environments or answering
  questions, reading the source, building it locally for evaluation, sending PRs or issues upstream.
- **Ask first (written permission required)**: redistribution — including publishing your own renamed
  build, any commercial use, offering this project or a derivative as a service, bundling it into
  hardware or another product. Licensing and business enquiries: putaopanpan@gmail.com.
- **Please don't**: take the installer, reskin it and claim it as yours; delete the author, licence
  and source links from the "About" page; publish your repackaged build under the name "Zhengdao"
  (or the author's name "小信多多") — the code licence covers **code only** and grants **no name or
  trademark rights**, so passing off the name or the attribution is a separate matter.
- **The app tells the truth**: every build is signed, and Zhengdao's "About" page shows
  "Official release ✓ / ⚠️ Unofficial (repackaged)", with a second warning at launch for unofficial
  builds (ERRATA E-065). This is not aimed at anyone — it just tells users who signed the build in
  their hands.
- **The signing key is not in the repository** (it lives in `~/.android/debug.keystore` on the
  author's machine, plus a CI secret). Want to publish your own? Use your own key — do not expect to
  be able to upgrade-install over the official build.

Contact the author: putaopanpan@gmail.com (or just open an
[Issue](https://github.com/pisces19860207/zhengdao/issues)).
