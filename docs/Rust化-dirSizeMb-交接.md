# Rust 化第三轮（`dirSizeMb` 下沉）· 交接说明

> 给收尾的人（DSH）：这份文件回答四件事 —— **这条支线是什么**、**怎么合**、**合之前要注意什么**、
> **哪些还没做**。读完再动手即可，不需要再翻对话记录。
>
> ⚠️ 本文写于**合并之前**。合并进 `main` 后，"未推/未合并"之类的状态描述即成为历史记录、
> 不再是当前事实（这也符合本项目"文档只记当时事实"的惯例）。

---


> **⏳ 2026-10-09 收工复核（DSH 记，合并前请先读这段）**：`main` 已推进到 `4483beb`（v2.0.8 / vc24），
> 当天新增的 ERRATA 条目**已占满 `E-072` ~ `E-076`**（E-072 存储读取回归用例 / E-073 清理口径与 mtime / E-074 输入回归页 / E-075 存储明细与可选工具 / E-076 通知权限）
> ⇒ 本条支线文档里写的 **`E-072` 合并时必须再让一次号**（改成当日 `main` 最大号 +1，目前即 **E-077** 起），
> 按 §3.2 的规矩：**只改条头编号 + 更新让号说明，正文一字不动**。
> 其余交接信息不变：分支 `feat/rust-dirsize-and-keep-guard`、提交 `e0894ad`、工作树 `wt-rust-dirsize`、**未 push / 未建 PR**（用户要求）；
> 本支线代码（Rust / Kotlin / `.so` / CI）与 `main` 当天的改动（`EnvHealth.kt`、`StorageAudit.kt`、`ImeRegressionActivity.kt` 等）无交集，预期仍只有 `docs/ERRATA.md` 与 `docs/FEATURE-LEDGER.md` 两处文档冲突。

## 1. 交接三要素

| 项 | 值 |
|---|---|
| **支线** | `feat/rust-dirsize-and-keep-guard` |
| **代码提交** | `e0894ad`（功能 + 两道 R8 门禁；本文档是其后的一个纯文档提交） |
| **基线** | `cff6c32`（= 当时的 `main`，E-071「后台被杀留档」） |
| **工作树** | `C:\Users\guoli\AndroidStudioProjects\wt-rust-dirsize` |

**一句话**：把"目录大小统计（`dirSizeMb`）"从 Java 下沉到统一的 `libzhengdao_core.so`，
顺带补两道防 R8 闪退复发的门禁。**未 push、未建 PR**（用户要求）。

---

## 2. 这条支线改了什么（15 个文件）

### 2.1 功能本体（这才是交付物）

| 文件 | 说明 |
|---|---|
| `rust/core/src/dirsize.rs`（新） | 纯逻辑层 `dir_size_bytes(path)`：显式栈递归、**不跟随软链**、读不到的条目静默跳过 |
| `rust/core/src/jni_bridge.rs` | 新增 `Java_..._CoreNative_nativeDirSizeBytes`（失败用 **`-1`** 当哨兵，**不是** `0`） |
| `rust/core/src/lib.rs` | 挂 `pub mod dirsize` |
| `rust/core/Cargo.toml` + `rust/Cargo.lock` | `tar` 关掉默认 feature（拆掉 `rustix`，见 §4.1） |
| `app/.../jniLibs/arm64-v8a/libzhengdao_core.so` | **重建**：948,392 → 957,488 B |
| `app/.../rust/CoreNative.kt` | `dirSizeBytes(dir): Long?`，Rust 不可用或 JNI<0 ⇒ `null` |
| `app/.../ui/SystemInfoProvider.kt` | `dirSizeMb` 改成 Rust 优先、Java 回退（原逻辑抽成 `javaDirSizeBytes`，语义不变） |

### 2.2 两道 R8 门禁（互补，各守一半）

| 文件 | 读什么 | 抓什么 |
|---|---|---|
| `app/src/test/.../rust/R8KeepRuleGuardTest.kt`（新，3 例） | proguard **文本** | "人把 keep 规则删了" |
| `tools/check-r8-mapping.py`（新）+ `.github/workflows/{ci,build}.yml` 各加一步 | R8 **构建产物** | "R8 实际没照办 / 改了名" |

> ⚠️ **别以为后者取代了前者**。文本门禁在 JVM 单测阶段就跑（快，不需 release 构建）；
> 产物门禁必须等 `assembleRelease` 之后（慢）。删任一个都会瞎一只眼。详见 ERRATA **E-072** 第 4 点。

### 2.3 文档

| 文件 | 说明 |
|---|---|
| `docs/ERRATA.md` | 新增 **E-072**（含让号说明、三个坑、两道门禁的分工） |
| `docs/FEATURE-LEDGER.md` | §2 追加 1 行（只加自己那行，未重排整表） |

---

## 3. 合并操作（给 DSH）

```bash
# 0) 先看 main 又走了多远（本支线基于 cff6c32，期间 main 可能已再推进）
git -C C:/Users/guoli/AndroidStudioProjects/zhengdao fetch origin
git -C C:/Users/guoli/AndroidStudioProjects/zhengdao log --oneline -5

# 1) 集成在主工作树上做（项目铁律：main 只做集成）
#    先 rebase 或直接 merge --no-ff，二选一
git -C C:/Users/guoli/AndroidStudioProjects/zhengdao merge --no-ff feat/rust-dirsize-and-keep-guard
```

### 3.1 预期冲突（只有文档，代码零冲突）

| 文件 | 为什么 | 怎么解 |
|---|---|---|
| `docs/ERRATA.md` | 两边都在**文件末尾追加**条目 | **两条都留**：保留 main 已有的编号，把本条**继续往后让号**（见 §3.2） |
| `docs/FEATURE-LEDGER.md` | 两边都在 §2 表尾加行 | **两条都留**，别重排整表 |

**代码文件（Rust / Kotlin / `.so` / CI）预期零冲突** —— 本支线只动 §2 列的 15 个文件，
与 main 上其他 agent 的在途文件（收尾核对时看到 `EnvHealth.kt` 等）没有交集。
若真出现代码冲突，说明有人同时改了同一处，**先停下核对**，别硬解。

### 3.2 ERRATA 编号（**务必先看**）

本条**已经让号三次**，条头有完整说明：

| 阶段 | 编号 | 原因 |
|---|---|---|
| 初稿 | E-067 | — |
| 第一次让号 | → E-069 | 提交前发现 `origin/main` 已占 E-067 且到 E-068 |
| 第二次让号 | → E-070 | `main` 又推进 4 提交（v2.0.7），**E-069 又被占**（太极/终端分家） |
| 第三次让号 | → **E-072** | 收尾前再核对：`main` 已到 `cff6c32`，**E-070 / E-071 又都被占** |

> **合并时若 main 上又出现了新的 E-072（或更大的）占用 ⇒ 继续让号**（按《协作规约》§7：
> 谁落后谁让号）。改编号时**只改编号 + 更新条头让号块**，正文一字不动。

---

## 4. 合并前必须知道的三件事

### 4.1 `tar` 的默认 feature 被关掉了（别"好心"改回去）

`rust/core/Cargo.toml` 里 `tar = { version = "0.4", default-features = false }`。

**原因**：`tar` 的默认 feature `xattr` 会拉进 `rustix`，而 `rustix` 的 build script 要给子进程开
**stdin 管道**，本机（Windows，沙箱内外都一样）**拒绝**该操作 ⇒ 交叉编译直接 panic（`os error 231`）。
关掉后依赖树少 5 个包（`rustix`/`xattr`/`errno`/`linux-raw-sys`/`bitflags`）。

> 本项目只用 core tar API、从不碰 pax 扩展属性 ⇒ 关掉**没有功能损失**。
> ⚠️ 别被"主仓当时能编成功"骗到 —— 那是复用了旧缓存，**冷缓存必炸**。

### 4.2 两条路径必须给同一个数字

`dir_size_bytes` 与 Java `Files.walkFileTree` 在"**入口是普通文件**"时都给**文件大小**
（不是 0 —— `walkFileTree` 会把文件自己当 entry 计入）。两边各留一条**成对测试**锁死。
改任一边的语义时，**另一边必须同步改**，否则回退路径会静默漂移。

### 4.3 JNI 失败哨兵是 `-1`，不是 `0`

`0` 是"目录**真的空**"的合法结果，拿它当错误码会静默误判。`CoreNative.dirSizeBytes` 见到负值
才返回 `null`（⇒ 走 Java 回退）。

---

## 5. 验证状态（都已跑绿，可复跑）

| 项 | 命令 | 结果 |
|---|---|---|
| Rust 单测 | `cd rust && cargo test -p zhengdao_core` | **23 passed** |
| Kotlin 单测 | `./gradlew :app:testDebugUnitTest --tests "*DirSizeMb*" --tests "*R8KeepRuleGuard*" --tests "*SystemInfoProvider*"` | **18 全绿**（6+3+9） |
| `.so` 门禁 | `python tools/check-native-so.py` | 通过（6 JNI 符号、4 段 16KB 对齐） |
| R8 产物门禁 | `python tools/check-r8-mapping.py`（需先 `assembleRelease`） | 对**真实** `mapping.txt` 通过 |
| 门禁**自测**（会红吗） | 见 §5.1 | 6 个反例**均按预期变红** |

> **本机跑 Rust 单测的前提**（项目 `docs/协作规约.md` §9）：
> `PATH` 加 `C:\Users\guoli\w64devkit\w64devkit\bin`，`CC` 指向其中的 `gcc.exe`。
> 若遇到 rustc 内部 panic（rmeta encoder），多半是 `rust/target/debug/incremental` 缓存损坏——
> **不是代码错**，用 `CARGO_INCREMENTAL=0` + 换一个 `CARGO_TARGET_DIR` 即可绕开。

### 5.1 门禁自测（证明不是"永远为真的假绿"）

- `R8KeepRuleGuardTest`：故意删掉 proguard 里的 keep 行 ⇒ `--rerun-tasks` 下 `AssertionError`。
- `check-r8-mapping.py`：分别构造"类被改名 ×3 / `onProgress` 被改名 / `seeds.txt` 缺 native 方法 /
  无产物严格模式"⇒ **每个都返回 1 并打印 `::error`**；`--allow-missing` 按预期跳过（返回 0）。

---

## 6. 还没做的事（如实列出）

| 事项 | 状态 |
|---|---|
| **真机复验** | ❌ **未做**（本机没连真机）。建议随下一版验：进设置页看目录占用数字是否与旧版一致 |
| push / PR | ❌ 未做（用户明确要求"不要推"） |
| `dirsize.rs` 的两条 `#[cfg(unix)]` 软链用例 | ⚠️ 在 Windows 宿主**不跑**（Android/aarch64 上会跑）；已由 Kotlin 侧同名软链用例在 JVM 上补位 |

---

## 7. 为什么这件事值得做（背景，可略读）

出处：同名审计报告《证道-Rust化余地审计-2026-10-09》的候选 A。
⚠️ 该报告**没有进仓库**（是主工作树 `zhengdao/docs/` 下的**未跟踪文件**，
`证道-Rust化余地审计-2026-10-09.md` + `.html`）；本文档不依赖它，背景信息此处已够用。

`dirSizeMb` 原本每次进设置页/首页都用 Java `Files.walkFileTree` 全量重算
`rootfs`（约 1.6 GB）+ `home`（可达数 GB）。它契合"收益模型铁律"（输入一个路径、输出一个数，
**JNI 边界只跨一次**），所以下沉划算。

同轮审计的结论是：**其他候选不该做**（索引 JSON 解析、docx/摘要文本处理——小数据、边界跨多次，
按同一模型必亏）。所以这条支线**只做 `dirSizeMb` 一项**，别顺手扩范围。
