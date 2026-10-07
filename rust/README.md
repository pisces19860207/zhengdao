# rust/ — Rust Core（v2.0）

证道的 Rust 核心：交叉编译成**嵌入式 `.so`**（App 进程内，不是独立进程），
Kotlin 侧经 JNI 薄层调用。架构依据：`docs/milestones/证道-v2.0架构设计.md`。

```
rust/
├── Cargo.toml          # workspace（resolver 2，release: opt-level=3 + lto）
├── .cargo/config.toml  # NDK 链接器 + 16KB 页对齐 flag + [env] CC/AR
└── core/               # 唯一成员：zhengdao_core → libzhengdao_core.so
```

## 演进史：两个 PoC → 一个 core（R1 收编）

| 阶段 | 产物 | 状态 |
|---|---|---|
| sha256 PoC | `rust/sha256poc` → `libsha256poc.so` | 已删除，逻辑并入 `core/src/sha256.rs` |
| extract PoC（R2） | `rust/extract` → `libextract.so` | 已删除，逻辑并入 `core/src/extract.rs` |
| **R1 收编** | `rust/core` → `libzhengdao_core.so` | **当前** |

**演化，不并存**（架构文档 §2.3）：两个模块共享同一份 sha2/zstd 静态链接、一次
`System.loadLibrary`、一份 `jni_bridge`。收益模型铁律是这么来的——见下节。

## 收益模型铁律（为什么不是"哪个函数慢就用 Rust 重写"）

sha256 PoC 的真机实测（Honor Magic 5 Pro / Android 16 / arm64-v8a，10MB × 10 轮均值）：

| 轮次 | 配置 | 平台 MessageDigest | Rust/JNI | 差值 |
|---|---|---|---|---|
| 第一轮 | sha2 **软件实现**（漏开 asm） | 5ms | 27ms | +22ms |
| 第二轮 | sha2 **asm feature**（ARMv8 硬件指令） | 6ms | 33ms | +27ms |

**结论（两轮对照后的修正归因）**：

1. 开不开 asm，Rust/JNI 都慢于平台 5 倍左右——**瓶颈不在哈希算法，在 JNI 边界**
   （10MB 数组拷贝进 native + hex 字符串封送回 JVM）。
2. 所以划算的形态是：**数据常驻 native，把解析/分块/校验等多步流水线整体下沉一次做完**，
   而不是单函数跨边界搬大块数据——后者永远亏。
3. 这也解释了解压为什么值得下沉：归档路径进、JSON 报告出，**边界只跨一次**；
   而基线已证解压本身只要 2.07 秒，所以它的价值定位是**架构验证**，不是性能。

## 6 条 JNI 规范（已固化）

1. 错误不跨 FFI panic（`catch_unwind` 兜底，失败返回错误对象）
2. Kotlin 侧永远有平台回退（`.so` 加载失败不影响主流程）
3. proguard keep：`-keep class com.example.zhengdao.rust.CoreNative { native <methods>; }`
4. `.so` 入库，CI 免装 Rust（与 libzstd-jni 预编译同策略）
5. `.cargo/config.toml` 直配 NDK clang，不用 cargo-ndk
6. 逻辑层（纯 Rust，PC 可 `cargo test`）与 JNI 薄层（仅 android）分离

## 本机工具链（2026-10-07 安装，GNU 工具链——本机无 MSVC）

```
rustup-init.exe -y --default-toolchain stable-x86_64-pc-windows-gnu
rustup target add aarch64-linux-android
```

链接**不用 cargo-ndk**（其 host 依赖 windows-sys 在 windows-gnu 下 dlltool 有已知问题），
直接在 `.cargo/config.toml` 指定 NDK 的 `aarch64-linux-android35-clang.cmd` 做链接器
（NDK 27.2 最高带 API 35 包装器，产出 `.so` 在 Android 16/API 36 正常加载）。

## 构建与验证

```powershell
cd rust
cargo test                                  # PC 层纯逻辑：8/8（sha256 4 + extract 4）
cargo build --release -p zhengdao_core --target aarch64-linux-android
```

再生成入库的 `.so`（**注意 16KB 对齐验收，见 core/README.md**）：

```powershell
$ndk = "$env:LOCALAPPDATA\Android\Sdk\ndk\27.2.12479018\toolchains\llvm\prebuilt\windows-x86_64\bin"
$so  = "rust\target\aarch64-linux-android\release\libzhengdao_core.so"
& "$ndk\llvm-strip.exe" --strip-unneeded $so
Copy-Item $so app\src\main\jniLibs\arm64-v8a\libzhengdao_core.so -Force
& "$ndk\llvm-readelf.exe" -l app\src\main\jniLibs\arm64-v8a\libzhengdao_core.so | Select-String LOAD
```

真机对拍：

```bash
./gradlew connectedDebugAndroidTest --tests "com.example.zhengdao.rust.*"
```

模块细节、JNI 协议、语义对齐、16KB 门禁、已知边界 → [`core/README.md`](core/README.md)。
