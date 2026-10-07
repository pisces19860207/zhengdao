// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的公开资料：SHA-256 标准（FIPS 180-4）、sha2/jni crate 官方文档。
//
//! 证道 Rust Core PoC（v2.0 前置）：用最小模块验证
//! 「Rust 交叉编译 .so → System.loadLibrary → Kotlin JNI 调用」全链路。
//!
//! 分两层：
//! - 纯逻辑层 [sha256_hex]：不碰 JNI，PC 上 `cargo test` 即可验证；
//! - JNI 薄层（android 目标专属）：Java_com_example_zhengdao_rust_Sha256Native_sha256Hex，
//!   任何错误返回 null（不在 JNI 里 panic——panic 跨 FFI 是未定义行为风险）。

use sha2::{Digest, Sha256};

/// SHA-256 摘要（hex 小写输出）。
pub fn sha256_hex(data: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(data);
    hex::encode(hasher.finalize())
}

/// 流式版本：对大文件分块喂入，避免整块驻留内存（v2.0 校验 rootfs 归档的真实用法）。
pub struct Sha256Stream {
    hasher: Sha256,
}

impl Sha256Stream {
    pub fn new() -> Self {
        Self { hasher: Sha256::new() }
    }

    pub fn update(&mut self, chunk: &[u8]) {
        self.hasher.update(chunk);
    }

    pub fn finalize_hex(self) -> String {
        hex::encode(self.hasher.finalize())
    }
}

impl Default for Sha256Stream {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(target_os = "android")]
mod jni_bridge {
    use jni::objects::{JClass, JByteArray};
    use jni::JNIEnv;

    /// Kotlin: Sha256Native.nativeSha256Hex(ByteArray) -> String?
    /// 错误时返回 null（调用方回退 MessageDigest），绝不 panic 跨 FFI。
    #[no_mangle]
    pub extern "system" fn Java_com_example_zhengdao_rust_Sha256Native_nativeSha256Hex(
        env: JNIEnv,
        _class: JClass,
        data: JByteArray,
    ) -> jni::sys::jstring {
        let bytes = match env.convert_byte_array(data) {
            Ok(b) => b,
            Err(_) => return std::ptr::null_mut(),
        };
        let hex = super::sha256_hex(&bytes);
        match env.new_string(hex) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// FIPS 180-4 官方向量（NIST CSRC 例证）。
    #[test]
    fn nist_空串() {
        assert_eq!(
            sha256_hex(b""),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
    }

    #[test]
    fn nist_abc() {
        assert_eq!(
            sha256_hex(b"abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }

    #[test]
    fn nist_两段报文() {
        assert_eq!(
            sha256_hex(b"abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
        );
    }

    /// 流式分块与一次性摘要一致——块大小刻意不齐，覆盖跨 update 边界的状态连续性。
    #[test]
    fn 流式与一次性一致_跨块边界() {
        let data: Vec<u8> = (0..100_000u32).map(|i| (i % 251) as u8).collect();
        let one_shot = sha256_hex(&data);

        let mut stream = Sha256Stream::new();
        for chunk in data.chunks(8191) {
            stream.update(chunk);
        }
        assert_eq!(one_shot, stream.finalize_hex());
    }
}
