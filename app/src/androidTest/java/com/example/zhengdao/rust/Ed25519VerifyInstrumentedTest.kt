// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 真机验收：Rust 核心的 Ed25519 验签（RFC 8032 §7.1 官方向量）。
// 不联网、不依赖 App 状态——只证明"arm64 上这份 .so 的验签结论与 RFC 一致"。
// 与 Kotlin 版的对拍由 AgentManifest.verify 在运行时自证（见 ERRATA E-051）。
package com.example.zhengdao.rust

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class Ed25519VerifyInstrumentedTest {

    private fun h2b(s: String): ByteArray =
        s.replace(" ", "").replace("\n", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** RFC 8032 §7.1 TEST 1：空消息。 */
    @Test
    fun rfc8032_test1_空消息() {
        assumeTrue("Rust 核心不可用，跳过", CoreNative.isRustAvailable())
        val pub = h2b("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val sig = h2b(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
        )
        assertTrue("正品签名必须通过", CoreNative.verifyEd25519(pub, sig, ByteArray(0)) == true)
        assertFalse("正文多一个字节必须拒绝", CoreNative.verifyEd25519(pub, sig, byteArrayOf(0)) == true)
    }

    /** RFC 8032 §7.1 TEST 2：单字节 0x72。 */
    @Test
    fun rfc8032_test2_单字节() {
        assumeTrue("Rust 核心不可用，跳过", CoreNative.isRustAvailable())
        val pub = h2b("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
        val sig = h2b(
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
                "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"
        )
        val msg = byteArrayOf(0x72)
        assertTrue(CoreNative.verifyEd25519(pub, sig, msg) == true)
        // 签名翻一位 ⇒ 必须拒绝
        val bad = sig.copyOf()
        bad[40] = (bad[40].toInt() xor 0x01).toByte()
        assertFalse(CoreNative.verifyEd25519(pub, bad, msg) == true)
        // 错公钥 ⇒ 必须拒绝
        val wrongPub = pub.copyOf()
        wrongPub[0] = (wrongPub[0].toInt() xor 0x01).toByte()
        assertFalse(CoreNative.verifyEd25519(wrongPub, sig, msg) == true)
    }

    /** RFC 8032 §7.1 TEST 3：双字节 af82。 */
    @Test
    fun rfc8032_test3_双字节() {
        assumeTrue("Rust 核心不可用，跳过", CoreNative.isRustAvailable())
        val pub = h2b("fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025")
        val sig = h2b(
            "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac" +
                "18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"
        )
        assertTrue(CoreNative.verifyEd25519(pub, sig, h2b("af82")) == true)
        assertFalse(CoreNative.verifyEd25519(pub, sig, h2b("af83")) == true)
    }

    /** 长度非法不得崩溃（跨 FFI 不 panic 的契约）。 */
    @Test
    fun 长度非法_返回false不崩溃() {
        assumeTrue("Rust 核心不可用，跳过", CoreNative.isRustAvailable())
        assertFalse(CoreNative.verifyEd25519(ByteArray(31), ByteArray(64), byteArrayOf(1)) == true)
        assertFalse(CoreNative.verifyEd25519(ByteArray(32), ByteArray(63), byteArrayOf(1)) == true)
    }
}
