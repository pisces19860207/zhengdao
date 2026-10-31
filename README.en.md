# Zhengdao (证道)

**English** ｜ [简体中文](README.md)

[![Latest release](https://img.shields.io/github/v/release/pisces19860207/zhengdao?label=release&color=blue&sort=semver)](https://github.com/pisces19860207/zhengdao/releases)
[![License](https://img.shields.io/badge/license-All%20rights%20reserved-lightgrey)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-16%2B%20%C2%B7%20arm64-brightgreen)](#requirements)

> This is the English version of [`README.md`](README.md). The two are kept in step; where they
> disagree, the Chinese one wins.

An AI-agent runtime for Android with a built-in assistant, **Baopu (抱朴)**, and an in-app browser, **Xuanlan (玄览)**.

One APK, no root, no commands. Baopu reads and writes files, can search the whole phone, run scheduled tasks on time, and connect to external MCP tool servers; it also browses for you, handles images, and writes. Before each action it tells you whether it will ask first and how many files it will touch — and afterwards the Decision Center shows what it did and lets you undo it. **Xuanlan (玄览)** is its eyes: an in-app browser whose pages you can hand to Baopu.

> **The terminal is now separate**: the terminal/environment (PTY + rootfs + proot) lives in [zhengdao-term](https://github.com/pisces19860207/zhengdao-term). v2.2.1 still bundles it; the next release removes it from Zhengdao itself.

**Requires**: Android 16 (API 36) or newer, on an arm64 phone. It will not install otherwise.

**Download**: [GitHub Releases](https://github.com/pisces19860207/zhengdao/releases) — Current release: v2.2.2 (versionCode 40).

**Not needed**: Termux, Shizuku, ADB, or a computer.

**Baseline**: the feature set is baselined at v2.2.0; from v2.2.1 on it only grows — no rewrites. The Decision Center, scheduled tasks, whole-phone search, MCP tool servers, teach-and-replay, the risk matrix and impact hints all landed batch by batch. The package is still v2.2.2 (versionCode 40).

## Requirements

- **Android 16 (API 36)** or newer
- **arm64-v8a** device (only an arm64 build is published)
- Verified end to end on a Honor Magic 5 Pro (MagicOS 10.0.0.175 / Android 16, `PGT-AN10`); other devices and OS versions have not been individually tested

> `minSdk` has been 36 since 0.8.0, so the current build cannot be installed on Android 15 or older. The 0.7.x builds once supported Android 10, but they are no longer distributed or maintained.

## Baopu and Xuanlan

Zhengdao itself has two pages, each with one job:

| Tab | What it is | What it does |
|---|---|---|
| Baopu | the built-in agent | Chat; read and write workspace files, search the whole phone (read-only, cancellable), run scheduled tasks, connect MCP tool servers; view, edit and multi-attach images; read web pages; organise the library and build a local index; keep plans; grow skills and replay a walkthrough. It writes novels and drafts, and it will write code — but code is only shown, never run |
| Xuanlan | the browser | Baopu's eyes: open a page in the app and hand it over. Baopu can also click and scroll it. Local pages and code are displayed, not executed |

> Xiaoyao (the terminal) and Yiran (agent install & health) moved with the terminal to [zhengdao-term](https://github.com/pisces19860207/zhengdao-term); v2.2.1 still keeps both pages.

Baopu and Xuanlan are a pair: one acts, the other sees. Pages go from Xuanlan to Baopu, and results land back in the workspace. Baopu never runs a shell and never spawns a process. Files normally stay inside the workspace; if it genuinely needs to step outside, it asks once — and that grant lasts for this run only. A few system actions (clipboard, notifications, dialling, scheduling) go through authorised tools; everything else system-level belongs to the terminal app.

**The conversation is all you see**: the transcript holds only your messages and Baopu's replies. Every step and tool call collapses into a single "process bar" at the end of each turn — tap it to see the details. Plans sit ahead as a capsule; the process bar looks back, and neither takes over the transcript.

## What it does

**Organise.** Scattered settings, reference images and notes? Describe the rules — how to classify, how to name — and let Baopu put them into your library. It proposes a list or a plan first and acts only after you say yes, one item at a time, and tells you which files it touched. The library can be indexed locally; answers come with sources and line numbers, and still work offline.

**See and edit.** Hand it pages and images: summarise, excerpt, resize, crop, convert. Pages open in Xuanlan; you can let Baopu click and turn pages, or point it at a specific page. The first time it needs the browser group, you get an approval card (this session / always). Images can be attached several at a time; it can also generate an image from a sentence (Gemini, one confirmation card per image).

**Write.** Give it an outline and it writes chapter by chapter; keep going, or paste an old draft back and ask for changes. Output is plain files in the workspace — any file manager can open them. Same for code, except code is only displayed, never executed. Long answers collapse into a card you can fold away, so the transcript stays readable.

**Schedule.** Say "every morning at eight, turn yesterday's diary into one paragraph" and it creates a scheduled task that fires on its own. Without exact-alarm permission it says "roughly around then" instead of forcing it.

**Search the phone.** Ask it to find "that invoice from last month" and it scans phone storage in slices: read-only, nothing leaves the device, cancellable at any time — then it asks whether to bring the file into the workspace.

**Show it once.** Walk it through a task (where to tap, what to type) and it turns the session into a skill. Next time it replays the steps, checking each expectation first and stopping on a mismatch instead of tapping blind.

## It grows — but never behind your back

Baopu accumulates skills and memories, but it does not decide on its own without asking the right question. Writes, deletes, memory or skill changes raise a decision card by default; when cards pile up, the **Decision Center** in the top bar handles them:

- **Layered confirmation**: the same thing is asked once (same-key dedupe); one grant covers this run only; pure reads raise no card.
- **Skills** start as drafts Baopu cannot see. On first launch three starter skills are installed (also drafts; deleting them does not reinstall, and same names are not overwritten). Promotion has two paths: you tap it, or three uses promote it automatically — either way it lands in the Decision Center with one-tap rollback.
- **Auto-write** is allowed only when all five conditions hold (process-only, project skills only, small, draft first, audited and revertible); miss one and it goes back to a card.
- **Memories** are distilled into candidates at the end of a session; they are written only when you tap "Remember", and discarded when you don't. **Episodic memory** records what Baopu did as a diary; the files are the source of truth — recall them into the conversation, or delete them.
- **Persona** has three layers: the core is read-only; the global and project layers evolve with sessions, but only as proposals you approve. The global layer follows the app; the project layer follows the workspace.
- **The Decision Center** keeps every side effect in one ledger: grouped cards, real undo, a sampling queue — what a change touched, and whether it can be undone, at a glance.
- **Global skills** cannot be changed by Baopu — only proposed. You make the change yourself in settings.

In short: it changes, but never without you.

## How it guards itself

Models make things up; there is no point pretending otherwise. Zhengdao stops that at three places:

- **Before**: every action first gets a risk level and an impact hint — whether this asks you, how many files it touches, how many ledger entries it involves. Being foreseeable is itself a constraint.
- **During**: irreversible actions are never taken alone (originals folder, password fields and intranet URLs are blocked); a tool that stalls for 300 ms degrades; you can stop at any time and interject mid-run.
- **After**: every side effect lands in the Decision Center, where it can be inspected and undone; skill and memory changes keep a diff and a backup, so they can be rolled back.

In one line: **the model thinks, the boundary blocks, the ledger remembers.**

## Why "heavy harness"

- **The runtime lives in the app**: the agent loop, tools, step budget, context compaction and crash-resume are all implemented here — not a wrapper around a desktop CLI.
- **Governance sits in the runtime**: per-group tool switches (only common groups on by default), confirmation before writes and deletes, one ask per run when stepping outside the workspace, a step ceiling and a no-progress circuit breaker, a compaction threshold — enforced by code, not by prompt etiquette.
- **Evolution is a closed loop with gates**: skills go from draft to promotion, memories from candidate to disk, persona from proposal to effect — always backup first, confirm next, rollback possible.

Together that is what "heavy harness" means here: not many tools, but a boundary and an audit trail around every action.

## Install and use

1. Download the APK from [Releases](https://github.com/pisces19860207/zhengdao/releases) and install it. Every build is signed; the in-app About page marks whether it is an official build or a repackaged one.
2. On first launch, grant the permissions on the single page.
3. Before using Baopu, tap the company name in the top bar to pick a model: choose a company (OpenAI / Anthropic / DeepSeek / OpenRouter) → fetch its model list (OpenRouter is searchable) → enter a key (you can store several; they rotate). The free lane now runs on OpenRouter and still needs your own key; the sheet has a "get an OpenRouter key" link.
4. Baopu reads and writes inside its workspace (default `Download/证道` on the phone, changeable in Settings); the default workspace lives on shared storage, so uninstalling Zhengdao keeps those files.
5. Updates: the app can check for app and component updates.

Release history is on the [Releases](https://github.com/pisces19860207/zhengdao/releases) page.

## Network and keeping it alive

What Zhengdao connects to, precisely:

- **Baopu**: sends your messages and the files you ask it to read to the model provider you configured (OpenAI-compatible / Anthropic).
- **Xuanlan**: fetches the pages you open; rendering uses the system WebView.

Beyond that, Zhengdao itself does not collect user data. Keys stay on the device. Baopu has no model of its own and does not work offline.

If you use a proxy app (Clash, v2rayNG, etc.): switch it to VPN mode; if per-app proxying is on, include Zhengdao — that is the most common cause of connection failures. If it still fails, set the proxy address manually under Settings → Advanced.

Android may kill backgrounded apps. For long tasks, keep Zhengdao in the foreground, lock it in Recents, or disable battery optimisation. If it is killed anyway, for Baopu, messages sent while a run is in progress are queued, and runs continue when you switch tabs or leave the app.

## Privacy

- Keys are stored on-device and never uploaded.
- The only thing Baopu sends out is your messages, plus the files you ask it to read, to the model provider you chose. It does not go through your files on its own — only what you point it at is sent.
- Xuanlan fetches the pages you open; nothing else.

## Known limits

- Android 16 (API 36) and arm64 only, and verified end to end on a single device; other devices and OS versions have not been individually tested.
- Baopu writes code but only displays it in Xuanlan — it never runs it. To run code on the phone, use the separate terminal app (`zhengdao-term`); the two sides do not cross.
- Baopu never runs a shell and never spawns a process; clipboard, notifications, dialling and scheduling go through authorised tools, and the rest of the system is out of its scope by design.
- The orchestration mode (several steps per turn, segmented budget) is **off by default**: it is faster on complex work and easier to run off the rails, so it stays behind a usage cap and a stop button until explicitly opened.
- New capabilities from this round (Decision Center, scheduled tasks, teach-and-replay, risk matrix / impact hints, whole-phone search) all have unit gates, but **real-device feel is still being verified batch by batch** — confirmation frequency, whether auto-write feels right, whether schedules fire on time. Anything not yet verified is stated in the release notes.
- Baopu needs a network; long tasks can be affected by background reclamation — see the previous section.

## License and trademarks

- First-party code is original to this project and contains no copied third-party source. Bundled third-party components are outside this statement; see the next line and [THIRD-PARTY-LICENSES.md](THIRD-PARTY-LICENSES.md). Since 2026-10-11 it is released as **all rights reserved**: you may read the source on GitHub, clone it locally, and install official builds on your own device; building from source, modifying, reverse-engineering or redistributing it is not permitted (except where mandatory law allows); commercial use requires prior written permission from the owner. Versions published before that date remain under the GPL-3.0 they shipped with. Development and collaboration details are recorded in [docs/协作规约.md](docs/协作规约.md) (Chinese).
- Bundled third-party components (Termux terminal components, proot, fonts, etc.) are distributed under their own licenses; the license texts ship inside the APK and can be read offline under Settings → About → Open source licenses.
- Claude Code, OpenCode, Hermes, AGY CLI and other names belong to their respective owners. This project has no affiliation with, authorisation from, or partnership with those vendors.
- The name "Zhengdao" and the author's signature are not licensed with the code. If you republish, use your own name — not "Zhengdao".
- Every build is signed; the in-app About page states whether it is an official build or a repackaged one.

Tutorials, videos and paid setup help are welcome; redistribution (republishing under another name, commercial use, offering it as a service, bundling it into hardware) requires prior written permission.

## Acknowledgements

This project was built by the author together with several AI collaborators. Baopu and Xuanlan are built on Kotlin, Jetpack Compose, AndroidX and the system WebView.

Terminal-side components (Termux, PRoot, Debian, uv, etc.) are credited with the terminal in [zhengdao-term](https://github.com/pisces19860207/zhengdao-term).

**Four agent projects, four philosophies** (we are the fourth):

| Project | In one line |
|---|---|
| OpenHands | **Get it done** — give agents an environment to work in |
| Hermes | **Get better with use** — grow through experience and skills |
| OpenClaw | **Always reachable** — connect channels and apps |
| **Baopu (this project)** | **Act on evidence, act within limits** — every action is authorised, supervised, audited and independently accepted |

Positioning we use consistently: **Governance-first Agent Runtime**.

