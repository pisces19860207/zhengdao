# extract crate —— Rust 解压流水线（v2.0 R2 原型）

> 架构依据：docs/milestones/证道-v2.0架构设计.md §2/§4（嵌入式 .so、数据常驻 native、
> JNI 边界只跨一次）。价值定位：验证「Rust 处理文件树 + native zstd + 进度回调」这套
> 模式（基线已证解压仅 2 秒，**不是性能任务**）。

## 结构

```
rust/extract/
├── Cargo.toml          # workspace 成员；cdylib；asm feature 仅 android 目标
├── src/lib.rs          # extract_pipeline：SHA 流式校验 → zstd/gzip 解压 → tar 落盘
├── src/jni_bridge.rs   # JNI 薄层（仅 android）：路径进 → JSON 报告出 + 限频进度回调
├── src/tests.rs        # PC 端 4 例：zstd 端到端 / gzip 兜底 / SHA 拒绝 / 路径穿越
```

Android 侧：`ExtractNative.kt`（桥接 + 回退判定）→ `RootfsInstaller.install` Rust 快路径
（失败自动落回 commons-compress Java 路径，规范 #2 回退纪律）。

## JNI 协议（一次跨边界）

- 入参：归档路径、目标目录、期望 SHA256（可 null）
- 出参：**永远返回 JSON**——成功 `{"ok":true,"entries":N,"bytes":N,"sha256":"..."}`；
  失败 `{"ok":false,"error":"..."}`（Android 的 stderr 不进 logcat，null 协议会让错误无迹可查——真机教训）
- 进度：每 200 条目回调 `ExtractNative.onProgress(entries, name)`（限频，避免边界风暴）

## 语义对齐（与 Kotlin 版 RootfsInstaller 逐条对拍）

- zstd 主壳 + gzip 兜底（魔数识别）
- 目录/符号链接/普通文件；硬链接复制落地，前向引用二阶段补齐
- FIFO/设备节点跳过（proot -b /dev 方案不需要）
- 路径穿越防护（目标上溯最近已存在祖先 canonicalize 后必须在目标目录内；
  Windows verbatim `\\?\` 前缀剥除后比较）
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

## 验证状态

| 层 | 结果 |
|---|---|
| PC `cargo test`（windows-gnu host） | 4/4 ✅ |
| 真机（Honor Magic 5 Pro / Android 16） | 真实 311MB 归档解压 ✅ + 与 Java 安装版关键文件对拍 ✅ + Rust 链路可用 ✅；SHA 拒绝用例待复验（首轮错误不可见已修，协议改 JSON 后待设备重连重跑） |
| 全量 JVM 单测 | 161/161 ✅（Java 路径未受集成影响） |

真机计时（BENCH_EXTRACT）待设备重连后补录。

## 已知边界（有意为之，非缺陷）

- symlink 目标不校验是否越界（与 Kotlin 版同语义，行为一致）——v2.0 加固候选项
- 进度回调经 `unsafe_clone` 的 JNIEnv，仅在回调周期内使用（jni crate 约定）
