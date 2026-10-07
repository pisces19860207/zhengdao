// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的公开资料：SHA-256 标准（FIPS 180-4）、sha2/jni crate 官方文档。
//
//! SHA-256 纯逻辑层（原 `rust/sha256poc`，v2.0 R1 收编进 `rust/core`）。
//!
//! 只做两件事：一次性摘要 [`sha256_hex`]、大文件流式 [`Sha256Stream`]。
//! 本模块**不含任何 JNI 代码**——JNI 薄层统一挂在 `crate::jni_bridge`，
//! 对应 Kotlin `com.example.zhengdao.rust.CoreNative.nativeSha256Hex`。

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
