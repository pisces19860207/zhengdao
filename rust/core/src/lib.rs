// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
//! 证道 Rust Core（v2.0 R1，架构文档 §2.3「演化，不并存」）。
//!
//! 把原先两个各自成 crate 的模块收编为**单一 cdylib** `libzhengdao_core.so`：
//! 一次 `System.loadLibrary("zhengdao_core")`、共享 sha2/zstd 依赖、多模块注册，
//! 取代此前 `libsha256poc.so` + `libextract.so` 两个 .so 并存的形态。
//!
//! 分层（R2 已固化的纪律，收编后不变）：
//! - 纯逻辑层：[`sha256`]（原 sha256poc）、[`extract`]（原 extract）——不碰 JNI，
//!   PC 上 `cargo test` 即可验证；
//! - JNI 薄层：[`jni_bridge`]（仅 android 目标编译）——错误一律转成 JSON 或 null，
//!   **绝不 panic 跨 FFI**。
//!
//! Kotlin 对应物：`com.example.zhengdao.rust.CoreNative`（旧 `Sha256Native` /
//! `ExtractNative` 两个对象已随本次收编删除，回退纪律不变：native 不可用时
//! 一律走平台实现，调用方无感知）。

pub mod ed25519;
pub mod extract;
pub mod sha256;

#[cfg(target_os = "android")]
mod jni_bridge;

#[cfg(test)]
#[path = "tests.rs"]
mod tests;
