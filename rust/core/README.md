# zhengdao_core crate —— Rust 核心（v2.0 R1 收编）

> 架构依据：docs/milestones/证道-v2.0架构设计.md §2/§4（嵌入式 .so、数据常驻 native、
> JNI 边界只跨一次）。价值定位：验证「Rust 处理文件树 + native zstd + 进度回调」这套
> 模式（基线已证解压仅 2 秒，**不是性能任务**）。

本 crate 由原 `sha256poc` + `extract` 两个 PoC **收编合并**而来（R1：演化，不并存）。
合并动机是收益模型铁律：JNI 边界只该跨一次、`.so` 只该 load 一次。旧的两个独立
`.so`（`libsha256poc.so` / `libextract.so`）已删除，只保留单一 `libzhengdao_core.so`。

## 结构

```
rust/core/
├── Cargo.toml          # workspace 成员；cdylib；asm feature 仅 android 目标
├── src/lib.rs          # 模块注册：pub mod sha256; pub mod extract;
├── src/sha256.rs       # 纯逻辑：sha256_hex / Sha256Stream（唯一一份 sha2 依赖）
├── src/extract.rs      # extract_pipeline：SHA 流式校验 → zstd/gzip 解压 → tar 落盘
├── src/jni_bridge.rs   # JNI 薄层（仅 android）：两个符号，入参进 → JSON 报告出 + 限频进度回调
└── src/tests.rs        # PC 端用例：zstd 端到端 / gzip 兜底 / SHA 拒绝 / 路径穿越 / 空归档 / 软链边界
```

Android 侧：`CoreNative.kt`（一次 `System.loadLibrary("zhengdao_core")`，桥接 + 回退判定）
→ `RootfsInstaller.install` Rust 快路径（失败自动落回 commons-compress Java 路径，规范 #2 回退纪律）。

## JNI 符号与协议

符号名与 Kotlin external 函数名严格一一对应（都在 `com.example.zhengdao.rust.CoreNative`）：

| JNI 符号 | Kotlin |
|---|---|
| `Java_com_example_zhengdao_rust_CoreNative_nativeSha256Hex` | `CoreNative.nativeSha256Hex` |
| `Java_com_example_zhengdao_rust_CoreNative_nativeExtract` | `CoreNative.nativeExtract` |

- extract 入参：归档路径、目标目录、期望 SHA256（可 null）
- extract 出参：**永远返回 JSON**——成功 `{"ok":true,"entries":N,"bytes":N,"sha256":"..."}`；
  失败 `{"ok":false,"error":"..."}`（Android 的 stderr 不进 logcat，null 协议会让错误无迹可查——真机教训）
- 进度：每 200 条目回调 `CoreNative.onProgress(entries, name)`（限频，避免边界风暴）

## 语义对齐（与 Kotlin 版 RootfsInstaller 逐条对拍）

- zstd 主壳 + gzip 兜底（魔数识别）
- 目录/符号链接/普通文件；硬链接复制落地，前向引用二阶段补齐
- FIFO/设备节点跳过（proot -b /dev 方案不需要）
- 路径穿越防护（成员名：目标上溯最近已存在祖先 canonicalize 后必须在目标目录内；
  Windows verbatim `\\?\` 前缀剥除后比较）
- 软链 linkname 边界（加固-1 / E-085）：相对链接按**链接所在目录**、绝对链接按 **guest 根**
  解释，词法归一后必须仍在解压根内，越界的不建链接、改用空文件占位（与 Kotlin 版
  `PathGuard.linkStaysInside` 同判据，Rust/Kotlin 两侧用例逐条对拍）
- 目标目录 pipeline 开头 create_dir_all（穿越判定的 canonicalize 依赖它）

## 构建链（本机 windows-gnu 工具链的三个坑，全部已解）

1. **rustup 自带 dlltool 缺 as.exe**（raw-dylib 编译即 CreateProcess 失败）→
   装 [w64devkit](https://github.com/skeeto/w64devkit)（便携免安装），`.cargo/config.toml`
   用 `-C dlltool=` 指向其 dlltool；原 dlltool.exe 已改名 .broken 留证
2. **链接器**：host 用 rustup 自带 `x86_64-w64-mingw32-gcc.exe` 显式指定
   （w64devkit 的 gcc/ld 找不到 rust 的 libgcc_eh）
3. **sha2 asm feature 不支持 Windows host** → 移到
   `[target.'cfg(target_os = "android")'.dependencies]` 条件依赖

Android 交叉编译：`.cargo/config.toml` 直配 NDK 27.2 链接器 + `[env]` 段给
`CC_aarch64_linux_android`/`AR_aarch64_linux_android`（zstd-sys/sha2-asm 的 C 编译需要）。

## ⚠️ 16KB 页对齐（R1 补齐，此前是静默失效）

NDK r27 的 clang **默认按 4KB 页做段对齐**，产出的 `.so` 在 16KB 页设备上会被 loader
直接拒绝加载。证道自己的门禁（`docs/milestones/M1.1-开发任务书.md:36`）要求每个 LOAD 段
`p_align ≥ 0x4000`。

因为 Kotlin 侧永远有回退（规范 #2），加载失败**不崩溃、不报错**，只是 Rust 路径永远
不会被执行——这是一个静默降级，所以必须靠 readelf 验收而不是靠"跑起来没崩"。

r27 及以下必须**同时**给两个 flag（r28+ 才默认对齐）：

```toml
# rust/.cargo/config.toml
[target.aarch64-linux-android]
rustflags = [
  "-C", "link-arg=-Wl,-z,max-page-size=16384",
  "-C", "link-arg=-Wl,-z,common-page-size=16384",
]
```

验收（每个 LOAD 段都必须是 `0x4000`）：

```bash
llvm-readelf -l app/src/main/jniLibs/arm64-v8a/libzhengdao_core.so | grep LOAD
```

## 验证状态

| 层 | 结果 |
|---|---|
| PC `cargo test`（windows-gnu host） | **8/8 ✅**（sha256 4 例 + extract 4 例） |
| 真机（Honor Magic 5 Pro / Android 16） | **3/3 ✅**——Rust 3611ms vs Java 3668ms（同一 311MB 归档、同口径含收尾）；**16,010 个普通文件路径+大小全量一致**；SHA 不匹配拒绝并带回期望/实际值明细 |
| 全量 JVM 单测 | 161/161 ✅（Java 路径未受集成影响） |

真机教训（已固化进协议与测试）：
1. Android 的 stderr 不进 logcat → JNI 失败必须返回 JSON 错误对象，不能用 null
2. JNI 符号名 = Kotlin external 函数名（`nativeExtract` 不是 `extract`）——不匹配报
   UnsatisfiedLinkError，且 instrumented 测试的"空跑通过"（数据被 gradle 重装清掉）会
   掩盖它；对拍测试必须是**自足式**（不依赖已安装数据）
3. 树遍历必须 NOFOLLOW——rootfs 里有指向整个共享存储的 bind symlink，跟进去
   会数出几万个用户文件（首版对拍 55,021 vs 17,650 的假差异即此）

## 已知边界（有意为之，非缺陷）

- ~~symlink 目标不校验是否越界（与 Kotlin 版同语义，行为一致）——v2.0 加固候选项~~
  —— **已于 2026-10-10 修（加固-1 / E-085）**：linkname 落盘前先过 `link_stays_inside`，
  越界的不建链接、改空文件占位；Kotlin 版 `RootfsInstaller` 同一处也接了 `PathGuard`。
  （这条此前写作"有意为之"，其实只是两边都没做——它是一条写出环境目录的通道。）
- 进度回调经 `unsafe_clone` 的 JNIEnv，仅在回调周期内使用（jni crate 约定）
- ~~`extract_pipeline` 不校验最小条目数~~ —— **已于 2026-10-10 修（BUG-1 / E-083）**：
  可落盘 0 条目现在报 `EmptyArchive`，与 Kotlin 版 `extractArchiveJava` 的
  `extracted == 0` 兜底真正一致。此前这里写的"两条路径一致"是**错的**——Java 会拒、
  Rust 会 Ok，而 Ok 会被上层当成装成功（写 `.zhengdao-rootfs-ok` + 换树）。
