# 勘误记录（ERRATA）

本文件记录项目历史中关于第三方组件、架构前提或实现方式的**已更正表述**。
保留历史 commit 原样，仅在此追加勘误，避免后来者被过时描述误导。

---

## E-001 · 2026-10-05 · `5a0fd2e` 中 Termux 终端组件的许可证表述有误

**受影响提交**
`5a0fd2e feat: 终端架构升级方案 A——聚合 Termux terminal-emulator+terminal-view（v0.119.0-beta.3，GPL-3.0 源码级聚合，用户钉版本）……`

**原表述（有误）**
> 聚合 Termux `terminal-emulator` + `terminal-view`（**GPL-3.0** 源码级聚合）

**更正为**
> 聚合 Termux `terminal-emulator` + `terminal-view`（**Apache-2.0** 源码级聚合）

**依据**
上游 `termux/termux-app` 仓库根 `LICENSE.md` 原文：

```
The `termux/termux-app` repository is released under GPLv3 only license.

### Exceptions

- [Terminal Emulator for Android](https://github.com/jackpal/Android-Terminal-Emulator)
  code is used which is released under [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0)
  license. Check `terminal-view` and `terminal-emulator` libraries.
- Check `termux-shared/LICENSE.md` for `termux-shared` library related exceptions.
```

即：**仓库整体为 GPLv3 only，但 `terminal-view` 与 `terminal-emulator` 两个库属于明确列出的例外，为 Apache-2.0**（源自 jackpal/Android-Terminal-Emulator）。
只有 `termux-app` **主应用本体**才是 GPL-3.0，而本项目**并未聚合主应用**。

**为什么必须更正**
- GPL-3.0 会触发**传染**：若误判，会让人以为整个 App 必须开放第一方源码。
- Apache-2.0 **不会**传染：第一方 Kotlin/C 代码可合法闭源，义务仅为保留第三方 LICENSE 与 NOTICE，并履行书面要约（见 `PROVENANCE.md`）。
- 这条直接决定"核心代码能否闭源"，属于不可出错项。

**不影响的部分**
- proot（Termux fork 发行版）**确为** GPL-2.0-or-later，且必须以**独立可执行文件**方式 `exec` 调用——该结论不变（见 `PROVENANCE.md` 红线一）。
- targetSdk 28 钉死、单会话模型等架构前提——均不受影响。

---

## E-002 · 2026-10-05 · proot 分发方式：jniLibs 方案已废弃

**曾有表述**
> proot 二进制改名 `libproot.so` 放入 `jniLibs/arm64-v8a/`，运行时从 `nativeLibraryDir` 调用。

**现行为（以代码实现为准）**
> proot 用 **Termux 官方发行二进制**（proot **5.1.107.96** aarch64 官方构建产物），
> 以 **assets 内置**（`app/src/main/assets/proot/`）分发，首启释放到 `files/termux-proot/`
> 并补执行位，随后 **execve 执行**；每个文件带 SHA256 固定清单校验。

**为什么可行**
targetSdk 28 下 App 的 `files/` 目录**允许 exec**——这正是 targetSdk 28 的意义所在。
**若将来 targetSdk 抬高，这条路径会失效**，故 targetSdk 28 是硬前提。

**历史方案为何废弃**
① 自编译上游 proot 缺少 Android 适配，在 App 域内加载 guest 时**静默退出 255**（本机实测 2026-10-04），
而带 Android 补丁的 Termux 官方发行二进制正常；
② `.so` 命名容易被后来者误当作动态库 `loadLibrary`，是 GPL 传染隐患。

**红线不变**
**无论叫什么名字、放在哪个目录，proot 都只能 `exec`，绝不能 `dlopen` / `loadLibrary`。**
详见 `PROVENANCE.md` 第 1 节红线一。

---

## E-003 · 2026-10-05 · 终端滚动手势曾经完全失效（已修）

**现象**：终端里单指上下滑动没有反应。

**根因**：tmux 采用「定位光标 + 重绘整屏」的方式输出，不产生行滚动，
导致本地回滚缓冲恒为空（实测 `getActiveTranscriptRows() == 0`）；
而上游 `TerminalView.onScroll` 只在「事件来自鼠标源」时才转发滚轮事件，
**手机上触摸永远不是鼠标源** → 滑动被彻底丢弃。

**修法（两处配合）**
1. `TerminalView.onScroll`：`isMouseTrackingActive()` 时，把触摸滑动按行高换算成滚轮事件转发给应用。
2. `ProotLauncher`：预置 `~/.tmux.conf` 写 `set -g mouse on`（tmux 不开 mouse 收不到滚轮）。

**验收**：上滑后内容由 `76-100` 变为 `18-43`，tmux 状态栏出现 `[58/78]` 位置指示。
