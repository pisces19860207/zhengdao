// 真机 + 真签名验收：线上 `latest/rootfs-index.json` 的 `.sig` 必须过 App 的验签入口。
//
// 为什么必须有这一条：JVM 单测用的是**现造的密钥对**（`RootfsIndexSignatureTest`），只能证明
// 算法正确；它证明不了"线上那份索引真的带着签名、且签名与 APK 里固化的公钥是一对"——
// 而"签名文件不存在"恰好是本条链路上线前最真实的失败形态（`.sig` 曾长期 404，见 ERRATA E-052）。
// 另一条：这里跑的是 `fetch()` 全路径（取索引 → 取 .sig → 验签 → 解析），
// 未签名/验签失败一律返回 null，所以断言非空就等于"这条通道是通的"。
package com.example.zhengdao.rootfs

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.zhengdao.rust.CoreNative
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootfsIndexSignatureInstrumentedTest {

    @Test
    fun 线上索引_带签名_能取到且验签解析通过() {
        assumeTrue(CoreNative.isRustAvailable())
        val index = RootfsIndexFetcher.fetch()
        assertNotNull(
            "线上索引应当能取到、验签通过并解析（未签名 / .sig 取不到 / 验签失败都会是 null）",
            index,
        )
        // 索引必须带"包身份"信息，否则后面没法当校验值用
        assertTrue("索引里应当有 sha256", index!!.sha256.isNotBlank())
        assertTrue("索引里应当有 env 指纹", !index.env.isNullOrBlank())
    }

    @Test
    fun 线上索引_正文被翻一位_验签必须拒绝() {
        assumeTrue(CoreNative.isRustAvailable())
        val url = RootfsIndexFetcher.URL
        val bytes = RootfsDownloader.fetchBytes(url)
        assumeTrue("取不到线上索引（网络/源不可达），跳过", bytes != null)
        val sig = RootfsDownloader.fetchBytes(RootfsIndexFetcher.signatureUrl(url))
            ?.toString(Charsets.UTF_8)
        assumeTrue("取不到线上 .sig，跳过", !sig.isNullOrBlank())

        assertTrue("线上索引 + 线上 .sig 必须验签通过", RootfsIndexFetcher.verifySignature(bytes!!, sig!!))

        val tampered = bytes.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()
        assertFalse(
            "正文被改一位后验签必须拒绝",
            RootfsIndexFetcher.verifySignature(tampered, sig),
        )
    }

    /**
     * 直接问 Rust 核心“这份线上索引的签名对不对”，不经平台实现。
     *
     * 为什么要单独一条：`Ed25519Verify` 是"Rust 优先 + 平台对拍"，而**这台设备上抓不到
     * App 的 `Log.i`**（HKS 噪声把缓冲冲掉，E-050/E-051 两轮都复现过）⇒ 路径日志这条证据链
     * 在真机上不可靠。直接调 `CoreNative.verifyEd25519` 就把"Rust 真的验了线上这份签"变成断言。
     */
    @Test
    fun 线上索引_直接过Rust核心_返回true() {
        assumeTrue(CoreNative.isRustAvailable())
        val url = RootfsIndexFetcher.URL
        val bytes = RootfsDownloader.fetchBytes(url)
        assumeTrue("取不到线上索引（网络/源不可达），跳过", bytes != null)
        val sigText = RootfsDownloader.fetchBytes(RootfsIndexFetcher.signatureUrl(url))
            ?.toString(Charsets.UTF_8)
        assumeTrue("取不到线上 .sig，跳过", !sigText.isNullOrBlank())
        val pub = java.util.Base64.getDecoder()
            .decode(RootfsIndexFetcher.INDEX_SIGNING_PUBKEY_B64)
        val sig = java.util.Base64.getDecoder().decode(sigText!!.trim())

        assertTrue(
            "Rust 核心（arm64 .so）对线上索引 + 线上签名的结论必须是 true",
            CoreNative.verifyEd25519(pub, sig, bytes!!) == true,
        )
        // 反向：同一把公钥对翻过一位的正文必须为 false（证明它真的在算，不是恒真）
        val tampered = bytes.copyOf()
        tampered[10] = (tampered[10].toInt() xor 0x01).toByte()
        assertTrue(
            "Rust 核心对篡改正文必须返回 false",
            CoreNative.verifyEd25519(pub, sig, tampered) == false,
        )
    }
}
