// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.rootfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64

/**
 * 环境索引签名（`RootfsIndexFetcher.verifySignature`）单测。
 *
 * 背景（2026-10-08）：`rootfs/agents.json` 自建链起就有 Ed25519 签名，但环境包这条链
 * ——`rootfs-index.json` → 完整包 / 差分包的 sha256——**一个签名都没有**。
 * sha256 与被校验的包在同一个 Release 里，**能改包的人也能顺手改哈希**。
 *
 * 为什么这里用 JDK 的 `Signature("Ed25519")` 造签名、而验签走 App 里那份从零实现：
 * 两边**必须是不同实现**，否则"用同一份代码自证"测不出任何东西。
 * （JVM 上 AndroidKeyStore 不可用，JDK 的 Ed25519 正好扮演"第三方参照实现"，
 *   与 App 侧 `ui/AgentManifest.kt` 里"不能用 java.security"的约束互不影响。）
 *
 * 覆盖方向：**正品 / 篡改 / 错钥 / 坏输入 / 空白容忍 / 内置公钥形状**，
 * 与 `AgentManifestVerifyTest` 同规格。
 */
class RootfsIndexSignatureTest {

    private val body: ByteArray = (
        """{"schema":1,"distro":"debian-13.7","version":"13.7","env":"0123456789abcdef",""" +
            """"url":"https://example.invalid/x.tar.zst","sha256":"aa","size":1}"""
        ).toByteArray()

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    /** 用参照实现对 [body] 签名，返回 base64。 */
    private fun signRef(secret: PrivateKey): String {
        val signer = Signature.getInstance("Ed25519").apply { initSign(secret) }
        signer.update(body)
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    /** JDK 的 X.509 编码外层带壳，**末 32 字节**才是 Ed25519 裸公钥（App 侧公钥常量就是那个形态）。 */
    private fun rawPub(kp: KeyPair): String {
        val x509 = kp.public.encoded
        return Base64.getEncoder().encodeToString(x509.copyOfRange(x509.size - 32, x509.size))
    }

    @Test
    fun `参照实现签的签名能通过`() {
        val kp = keyPair()
        assertTrue(RootfsIndexFetcher.verifySignature(body, signRef(kp.private), rawPub(kp)))
    }

    @Test
    fun `正文改一个字节即失败`() {
        val kp = keyPair()
        val sig = signRef(kp.private)
        val original = String(body)
        val tampered = original.replace("\"aa\"", "\"ab\"")
        assertTrue("篡改没生效，测试自身有问题", tampered != original)
        assertFalse(RootfsIndexFetcher.verifySignature(tampered.toByteArray(), sig, rawPub(kp)))
    }

    @Test
    fun `换一把公钥即失败`() {
        val signer = keyPair()
        val other = keyPair()
        assertFalse(
            "用别人的公钥验自己的签名必须失败",
            RootfsIndexFetcher.verifySignature(body, signRef(signer.private), rawPub(other)),
        )
    }

    @Test
    fun `签名不是合法 base64 时返回失败而不是抛异常`() {
        val kp = keyPair()
        assertFalse(RootfsIndexFetcher.verifySignature(body, "这不是 base64 !!!", rawPub(kp)))
    }

    @Test
    fun `公钥长度非法时返回失败而不是抛异常`() {
        val kp = keyPair()
        val short = Base64.getEncoder().encodeToString(ByteArray(16))
        assertFalse(RootfsIndexFetcher.verifySignature(body, signRef(kp.private), short))
    }

    @Test
    fun `签名前后有空白仍能验过`() {
        // CI 里 printf 会在 PEM 末尾留换行；base64 边车同样可能被加换行——都不能因此恒败。
        val kp = keyPair()
        assertTrue(
            RootfsIndexFetcher.verifySignature(body, "  " + signRef(kp.private) + "\n", rawPub(kp)),
        )
    }

    @Test
    fun `内置公钥是 32 字节 raw`() {
        val pub = Base64.getDecoder().decode(RootfsIndexFetcher.INDEX_SIGNING_PUBKEY_B64)
        assertEquals("内置索引公钥长度必须是 32 字节 raw", 32, pub.size)
    }

    @Test
    fun `签名边车地址就是索引地址加 sig 后缀`() {
        assertEquals(
            "https://x/rootfs-index.json.sig",
            RootfsIndexFetcher.signatureUrl("https://x/rootfs-index.json"),
        )
    }
}
