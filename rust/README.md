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
