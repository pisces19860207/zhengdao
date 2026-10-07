# rust/sha256poc — Rust Core PoC

v2.0「Rust Core」的前置验证：用最小模块打通 **Rust 交叉编译 .so → System.loadLibrary → Kotlin JNI 调用** 全链路。

## 结构

```
sha256poc/
├── Cargo.toml          # sha2 + hex（全平台）；jni 0.21 仅 Android 目标
├── .cargo/config.toml  # aarch64-linux-android 的 NDK 链接器路径（本机绝对路径）
└── src/lib.rs          # 纯逻辑层（sha256_hex / Sha256Stream）+ JNI 薄层
```

- **纯逻辑层**：`cargo test` 在 PC 上即可验证（FIPS 180-4 向量 + 流式一致性）
- **JNI 薄层**：错误返回 null，绝不 panic 跨 FFI；Kotlin 侧另有 MessageDigest 兜底

## 本机工具链（2026-10-07 安装，GNU 工具链——本机无 MSVC）

```
rustup-init.exe -y --default-toolchain stable-x86_64-pc-windows-gnu
rustup target add aarch64-linux-android
```

- 链接：**不用 cargo-ndk**（其 host 依赖 windows-sys 在 windows-gnu 下 dlltool 有已知问题），
  直接在 `.cargo/config.toml` 指定 NDK 的 `aarch64-linux-android35-clang.cmd` 做链接器
  （NDK 27.2 最高带 API 35 包装器，产出 .so 在 Android 16/API 36 正常加载）

## 再生成 .so

```bash
cd rust/sha256poc
cargo build --target aarch64-linux-android --release
# strip 瘦身（480KB → 318KB）
"$LOCALAPPDATA/Android/Sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip" \
  target/aarch64-linux-android/release/libsha256poc.so
cp target/aarch64-linux-android/release/libsha256poc.so ../../app/src/main/jniLibs/arm64-v8a/
```

.so **已提交入库**（与 libzstd-jni 预编译同策略）——CI 不需要装 Rust。

## 真机验证

`app/src/androidTest/.../Sha256NativeInstrumentedTest.kt`：
NIST 向量 + 随机数据对拍 MessageDigest + 计时对比。

```bash
./gradlew connectedDebugAndroidTest --tests "com.example.zhengdao.rust.*"
```

## 实测数据（Honor Magic 5 Pro / Android 16 / arm64-v8a，10MB × 10 轮均值）

| 轮次 | 配置 | 平台 MessageDigest | Rust/JNI | 差值 |
|---|---|---|---|---|
| 第一轮 | sha2 **软件实现**（漏开 asm） | 5ms | 27ms | +22ms |
| 第二轮 | sha2 **asm feature**（ARMv8 硬件指令） | 6ms | 33ms | +27ms |

**结论（两轮对照后的修正归因）**：

1. 开不开 asm，Rust/JNI 都慢于平台 5 倍左右——**瓶颈不在哈希算法，在 JNI 边界**
   （10MB 数组拷贝进 native + hex 字符串封送回 JVM）。
2. 因此 v2.0 的 Rust 收益模型是：**数据常驻 native，把解析/分块/校验等多步流水线
   整体下沉一次做完**，而不是单函数跨边界调大块数据——后者永远亏。
3. 对拍正确性在两轮中均全部一致（Rust 与平台摘要逐字节相同）。
