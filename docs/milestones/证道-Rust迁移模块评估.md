# 证道 · Rust 迁移模块评估

> 评估日期：2026-10-07 · 前置：Rust SHA256 PoC 已打通全链路并实测
> （rust/sha256poc + dc268ea/fab4998，真机对拍 5/5）
> **评估问题已从"Rust 快不快"修正为："哪些模块适合数据常驻 + 整体下沉"。**

---

## 0. 收益模型（SHA256 PoC 实测得出，评估的唯一标尺）

| 事实 | 数据 | 出处 |
|---|---|---|
| JNI 边界搬大块数据 | 10MB 数组进 + hex 出：**27~33ms** | fab4998 真机计时 |
| 平台 MessageDigest（ARMv8 硬件 SHA） | 10MB：**5~6ms** | 同上 |
| 开 asm feature 不改变结论 | 27→33ms（波动内） | 同上 |
| Rust 纯逻辑层（PC cargo test） | 与平台摘要逐字节一致 | rust/sha256poc |

**推论**：
1. 单函数跨 JNI 边界调大块数据——**永远亏**（边界成本 > 计算收益）。
2. 划算的形态：**输入输出都在 native 侧、Java 只收小结果/进度事件**的流水线。
3. 网络下载类：瓶颈是带宽（实测 311MB ≈ 4 分钟 @1.3MB/s），下沉 CPU 不改善——除非把
   "下载→校验→解压→落盘"整条流水线一次下沉（数据全程常驻 native）。

---

## 1. 候选模块逐个评估

### 候选 1：环境安装流水线（下载 → SHA256 校验 → zstd 解压 → tar 落盘）⭐ 推荐

**现状**（RootfsDownloader.kt / RootfsInstaller.kt）：
- 下载：Java HttpURLConnection 流式 + Range 断点续传（311MB ≈ 4 分钟，网络主导）
- 校验：下载完再整读一遍归档算 SHA256（MessageDigest，311MB ≈ 155ms，小头）
- 解压：**commons-compress 的纯 Java zstd 实现**（ZstdCompressorInputStream——包里的
  zstd-jni 反而没用在解压上）+ TarArchiveInputStream 逐 entry：
  写文件 + Os.chmod（每文件 2 次 syscall，rootfs 数万文件）+ symlink + 硬链接复制语义
- 耗时分布（2026-10-07 实测重装）：全程约 4 分钟，其中网络下载约 3~3.5 分钟，
  解压+落盘约 0.5~1 分钟（纯 Java zstd 是主要 CPU 热点，native zstd 快 3~5 倍）

> ⚠️ **步骤 0 基线实测修正（2026-10-07，ExtractBaselineTest）**：真机解压
> 311MB 归档（20042 条目 → 969MB）实测 **2.07 秒**、峰值内存增量约 88MB——
> 远小于此前 0.5~1 分钟的估计。**修正：解压下沉的用户体感收益接近于零，
> 安装时长完全由网络下载主导。** P1 下沉的价值改判为「架构验证」：
> 验证 Rust 处理文件树 + native 解压 + 进度回调的模式，为 v2.0 rootfs
> 生命周期管理器铺路——非性能优化。（基线测试：rootfs/ExtractBaselineTest）

**数据常驻形态**：Rust 侧一条流水线——本地归档文件 → 流式 zstd 解压（zstd crate，
绑 C 实现）→ tar 遍历 → 写文件/chmod/symlink → 归档 SHA256 流式校验同步完成。
Java 只收 `onEntry` 进度回调。**归档字节全程不跨 JNI 边界。**

**收益**：安装尾部（解压+落盘）从 0.5~1 分钟压到 ~15-30s（native zstd + 批量 fs）；
省去归档二次整读。**上限受网络主导**——网络好时体感明显，网络差时无感。

**成本**：中。断点续传/双格式/硬链接二阶段/防路径穿越/原子替换等边界逻辑需在 Rust
重写（RootfsInstaller.kt 199 行的等价物）；下载环节**本次不下沉**（换网络栈风险 >
收益，见"不做"清单）。

**裁定（步骤 0 基线后修正）：P2，架构验证性质**——
1. 首做「归档 → 解压 → 落盘」下沉（输入本地文件、输出目录，天然数据常驻），
   作为 v2.0 rootfs 生命周期管理器的第一个实体模块——**价值在架构验证而非性能**
   （步骤 0 基线：纯 Java 解压仅 2.07s，性能收益接近零）。
2. 下载并入流水线仍列 Phase 2 观察——网络主导的前提下，下沉 CPU 不改善体感。
3. 若追求安装提速，正确的杠杆是**网络**（CDN/镜像/预载），不是解压引擎。

### 候选 2：设置页目录统计（dirSizeMb）✅ 顺手做

现状：Java walkFileTree 遍历 rootfs(1.6GB)+home(7GB)，每次进设置页全量重算。
数据常驻形态：Rust 一次遍历（native stat、无 JVM 路径对象分配）。
收益：设置页"存储占用"从秒级等待变即时。成本：一个纯函数（PoC 的 Sha256Stream 同款模式）。
**裁定：P2 搭车项**——做候选 1 时顺手移植，独立可回退。

### 候选 3：太极 REST/SSE 的 JSON 解析 ❌ 不下沉

现状：OkHttp 拉响应 + org.json 解析，REST 轮询为主数据源（E-008）。
判定：消息单条几 KB、轮询间隔秒级——跨 JNI 传 JSON 字符串再取回结构化结果的
边界成本**大于** org.json 解析本身（收益模型第 1 条直接适用）。
若未来消息量成为瓶颈，正确方向是 Kotlin 侧换 kotlinx.serialization（零边界成本），
**不是 Rust**。

### 候选 4：终端 pty 读写 ✅ 已是 native，不动

libtermux.so（termux/termux.c，CMake 构建）+ JNI.java——数据本就常驻 native。
v2.0 若统一到 Rust，属于"替换"而非"迁移"，另行立项。

### 候选 5：网络下载器本体（RootfsDownloader）⏸ 暂不下沉

看似候选 1 的一部分，但网络栈换语言 = TLS/代理兼容/分应用代理/断点续传/CDN 容错
全部重写重测。且实测瓶颈在带宽（1.3MB/s 是源站/网络给的，不是 Java 慢）。
**裁定：Phase 2 再议**——等候选 1 步骤 1 落地稳定、且确认下载 CPU 占比真的可见时再评估。

---

## 2. 裁定汇总

| 模块 | 判定 | 阶段 | 预期用户收益 |
|---|---|---|---|
| 安装解压+校验+落盘 | **下沉（架构验证）** | P2（基线实测仅 2.07s，性能收益≈0） | v2.0 rootfs 管理器的第一个实体模块 |
| dirSizeMb 目录统计 | **下沉** | P2 搭车 | 设置页即时报占用（基线同款模式） |
| 太极 JSON 解析 | **不下沉** | — | — |
| 终端 pty | 不动（已 native） | — | — |
| 网络下载器 | **暂不下沉** | Phase 2 评估 | — |

## 3. 已固化的 JNI 规范（PoC 产出，后续所有 Rust 模块沿用）

1. 错误不跨 FFI panic——native 层 catch 一切，失败返回 null/错误码
2. Kotlin 侧永远有平台回退路径（Rust 不可用 = 功能降级不中断）
3. proguard keep：`-keep class ...Rust* { native <methods>; }`（仿 Sha256Native）
4. .so 入库（llvm-strip 后），CI 不需要 Rust 工具链（与 libzstd-jni 同策略）
5. 交叉编译：`.cargo/config.toml` 直配 NDK clang 链接器，不用 cargo-ndk
   （windows-gnu 工具链下 dlltool 有已知问题，2026-10-07 实测）
6. Rust 侧纯逻辑层与 JNI 薄层分离：逻辑层 cargo test 可在 PC 跑

## 4. 工具链现状（2026-10-07 安装）

- rustup stable-x86_64-pc-windows-gnu（本机无 MSVC，GNU 工具链零外部依赖）
- rustup target aarch64-linux-android 已装
- 未装 cargo-ndk（见上）；链接器用 NDK 27.2 的 API 35 包装器（.so 在 API 36 正常加载）
- 注意：`dlltool` 需把 rustup 工具链的 self-contained 目录加进 PATH 才可用

---

*本评估基于 rust/sha256poc 真机 PoC 数据与 RootfsDownloader/RootfsInstaller/
OcRepository 源码走查（2026-10-07）。执行批次建议：下一次 token 批次做候选 1 步骤 1。*
