# Zhengdao (证道)

**English** ｜ [简体中文](README.md)

[![Latest release](https://img.shields.io/github/v/release/pisces19860207/zhengdao?label=release&color=blue&sort=semver)](https://github.com/pisces19860207/zhengdao/releases)
[![License](https://img.shields.io/badge/license-All%20rights%20reserved-lightgrey)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-16%2B%20%C2%B7%20arm64-brightgreen)](#requirements)

> This is the English version of [`README.md`](README.md). The two are kept in step; where they
> disagree, the Chinese one wins.

An AI-agent runtime for Android with a built-in assistant, **Baopu (抱朴)**, and a real Debian terminal.

One APK, no root, no commands. Baopu reads and writes files in its workspace, browses for you, handles images, and writes. **Xuanlan (玄览)** is its eyes: an in-app browser whose pages you can hand to Baopu. When you need a real shell, switch to **Xiaoyao (逍遥)** and its Debian terminal.

**Requires**: Android 16 (API 36) or newer, on an arm64 phone. It will not install otherwise.

**Download**: [GitHub Releases](https://github.com/pisces19860207/zhengdao/releases) — Current release: v2.1.2 (versionCode 37).

> v2.2.0 has been withdrawn; v2.2 is still being fixed and is **not available for download yet**. The Baopu capabilities below are ready in the repository and will ship with the next release.

**Not needed**: Termux, Shizuku, ADB, or a computer.

## Requirements

- **Android 16 (API 36)** or newer
- **arm64-v8a** device (only an arm64 build is published)
- Verified end to end on a Honor Magic 5 Pro (MagicOS 10.0.0.175 / Android 16, `PGT-AN10`); other devices and OS versions have not been individually tested

> `minSdk` has been 36 since 0.8.0, so the current build cannot be installed on Android 15 or older. The 0.7.x builds once supported Android 10, but they are no longer distributed or maintained.

## The four tabs

| Tab | What it is | What it does |
|---|---|---|
| Baopu | the built-in agent | Chat; read, write and search workspace files; view, edit and multi-attach images; read web pages; organise the library and build a local index; keep plans; grow skills. It writes novels and drafts, and it will write code — but code is only shown, never run |
| Xiaoyao | the terminal | A Debian 13 environment for when you need a shell |
| Xuanlan | the browser | Baopu's eyes: open a page in the app and hand it over. Baopu can also click and scroll it. Local pages and code are displayed, not executed |
| Yiran | install & health | Install third-party agents (Hermes / Claude Code / Codex / AGY CLI — none preinstalled), see environment status, and run health checks |

Baopu and Xuanlan are a pair: one acts, the other sees. Pages go from Xuanlan to Baopu, and results land back in the workspace. Baopu never touches a shell and never spawns a process; it works only inside its workspace. System-level work belongs to Xiaoyao.

## What it does

**Organise.** Scattered settings, reference images and notes? Describe the rules — how to classify, how to name — and let Baopu put them into your library. It proposes a list or a plan first and acts only after you say yes, one item at a time, and tells you which files it touched. The library can be indexed locally; answers come with sources and line numbers, and still work offline.

**See and edit.** Hand it pages and images: summarise, excerpt, resize, crop, convert. Pages open in Xuanlan; you can let Baopu click and turn pages, or point it at a specific page. The first time it needs the browser group, you get an approval card (this session / always). Images can be attached several at a time.

**Write.** Give it an outline and it writes chapter by chapter; keep going, or paste an old draft back and ask for changes. Output is plain files in the workspace — any file manager can open them. Same for code, except code is only displayed, never executed.

## It grows — but never behind your back

Baopu accumulates skills and memories, and every step needs your nod. Writes and deletes, opening a new tool group, changing a memory or a skill — each one raises a decision card; the decision center in the top bar collects them:

- **Skills** start as drafts that Baopu cannot see by default; after three uses a "promote" option appears, and it only becomes a real skill when you tap it. Writing one requires a confirmation card, and a backup is taken first, so you can restore the previous version.
- **Memories** are distilled into candidates at the end of a session; they are written only when you tap "Remember", and discarded when you don't.
- **Persona** has three layers: the core is read-only; the global and project layers evolve with sessions, but only as proposals you approve. The global layer follows the app; the project layer follows the workspace.
- **Global skills** cannot be changed by Baopu — only proposed. You make the change yourself in settings.

In short: it changes, but never without you.

## Why "heavy harness"

- **The runtime lives in the app**: the agent loop, tools, step budget, context compaction and crash-resume are all implemented here — not a wrapper around a desktop CLI.
- **Governance sits in the runtime**: per-group tool switches, confirmation before writes and deletes, workspace-only access, a step ceiling per task, a compaction threshold — enforced by code, not by prompt etiquette.
- **Evolution is a closed loop with gates**: skills go from draft to promotion, memories from candidate to disk, persona from proposal to effect — always backup first, confirm next, rollback possible.

Together that is what "heavy harness" means here: not many tools, but a boundary and an audit trail around every action.

## Install and use

1. Download the APK from [Releases](https://github.com/pisces19860207/zhengdao/releases) and install it. Every build is signed; the in-app About page marks whether it is an official build or a repackaged one.
2. On first launch, grant the permissions on the single page; install the Debian environment under Settings → Environment (about 200 MB, needs network); environment status and health checks live in Yiran.
3. Before using Baopu, set a model provider in Settings: an OpenAI-compatible or Anthropic-style endpoint, with your own URL and key.
4. Yiran offers install cards, not preinstalled agents: Hermes / Claude Code / Codex / AGY CLI are installed when you tap install, and not before.
5. Baopu reads and writes inside its workspace (default `Download/证道` on the phone, changeable in Settings); the default workspace lives on shared storage, so uninstalling Zhengdao keeps those files.
6. Updates: the app can check for app and component updates; environment updates are downloaded as diffs.

Release history is on the [Releases](https://github.com/pisces19860207/zhengdao/releases) page.

## Network and keeping it alive

What Zhengdao connects to, precisely:

- **Baopu**: sends your messages and the files you ask it to read to the model provider you configured (OpenAI-compatible / Anthropic).
- **Xuanlan**: fetches the pages you open; rendering uses the system WebView.
- **Environment settings**: downloads the Debian runtime; **Yiran**: downloads agent installers.

Beyond that, Zhengdao itself does not collect user data. Keys stay on the device. Baopu has no model of its own and does not work offline; Xiaoyao's terminal does, once the environment is installed.

If you use a proxy app (Clash, v2rayNG, etc.): switch it to VPN mode; if per-app proxying is on, include Zhengdao — that is the most common cause of connection failures. If it still fails, set the proxy address manually under Settings → Advanced.

Android may kill backgrounded apps. For long tasks, keep Zhengdao in the foreground, lock it in Recents, or disable battery optimisation. If it is killed anyway, the terminal session in Xiaoyao reattaches when you reopen the app; for Baopu, messages sent while a run is in progress are queued, and runs continue when you switch tabs or leave the app.

## Privacy

- Keys are stored on-device and never uploaded.
- The only thing Baopu sends out is your messages, plus the files you ask it to read, to the model provider you chose. It does not go through your files on its own — only what you point it at is sent.
- Xuanlan fetches the pages you open, and Environment settings / Yiran download components; nothing else.
- ⚠️ **The terminal is not a security sandbox**: processes inside Xiaoyao share Zhengdao's Android UID, kernel and network stack, and can read and write whatever the app can. Run only commands you trust.

## Known limits

- Android 16 (API 36) and arm64 only, and verified end to end on a single device; other devices and OS versions have not been individually tested.
- Baopu writes code but only displays it in Xuanlan — it never runs it. To run code on the phone, use Xiaoyao; the two sides do not cross.
- Baopu never touches a shell and never spawns a process; system-level work is out of its scope by design.
- Baopu needs a network; long tasks can be affected by background reclamation — see the previous section.

## License and trademarks

- First-party code is written from scratch. Since 2026-10-11 it is released as **all rights reserved**: you may read the source on GitHub, clone it locally, and install official builds on your own device; building from source, modifying, reverse-engineering or redistributing it is not permitted (except where mandatory law allows); commercial use requires prior written permission from the owner. Versions published before that date remain under the GPL-3.0 they shipped with.
- Bundled third-party components (Termux terminal components, proot, fonts, etc.) are distributed under their own licenses; the license texts ship inside the APK and can be read offline under Settings → About → Open source licenses.
- Claude Code, OpenCode, Hermes, AGY CLI and other names belong to their respective owners. This project has no affiliation with, authorisation from, or partnership with those vendors.
- The name "Zhengdao" and the author's signature are not licensed with the code. If you republish, use your own name — not "Zhengdao".
- Every build is signed; the in-app About page states whether it is an official build or a repackaged one.

Tutorials, videos and paid setup help are welcome; redistribution (republishing under another name, commercial use, offering it as a service, bundling it into hardware) requires prior written permission.

## Acknowledgements

This project was built by the author together with several AI collaborators. Thanks also to the open-source projects we ship or build on:

- [Termux](https://github.com/termux/termux-packages) — the Android-adapted proot distribution, and the terminal-emulator / terminal-view engine
- [proot-me/proot](https://github.com/proot-me/proot) — the foundation for running a full Linux environment without root
- [astral-sh/uv](https://github.com/astral-sh/uv) — the Python package manager used by the Hermes installer
- [Debian](https://www.debian.org/) — Debian 13 as the terminal environment base
