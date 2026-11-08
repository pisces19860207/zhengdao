// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十三批 · 令牌剪贴板通道的门禁（派工单 §判据①–⑤）。
// ①走通道后全文 grep 无明文（回执对象＋全部日志）；②空剪贴板不写；③非令牌形拒；
// ④读后清空（UI 侧结构钉：清的动作只挂在 Saved 分支，真机复核在交付报告）；
// ⑤对话侧只提示不落库（源码反钉：提示函数体内没有任何写库调用）。
package com.example.zhengdao.baopu.mcp

import java.io.File
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpClipboardTokenTest {

    private val logs = mutableListOf<String>()
    private val token = "ghp_513clipTokenValue9x2"

    private fun config(url: String = "https://mcp.example.com/mcp") = McpServerConfig(
        id = "srv513",
        name = "假服务器",
        url = url,
        connectTimeoutMs = 5_000,
        callTimeoutMs = 5_000,
    )

    /** 502 同款接线：内存保险柜＋日志收集；importToken 全程零网络。 */
    private fun wire(): Pair<McpAuthFlow, McpTokenVault> {
        val vault = McpTokenVault(InMemoryMcpSecretStore())
        val flow = McpAuthFlow(
            vault = vault,
            oauth = McpOAuthClient(OkHttpClient(), log = { logs += it }),
            now = { 1_000_000L },
            log = { logs += it },
        )
        return flow to vault
    }

    private fun code(rel: String): String =
        (File(rel).takeIf { it.isFile } ?: File("app/$rel")).readText()

    @After
    fun tearDown() {
        McpClipboardToken.resetForTest()
    }

    // ── 判据①：落库＋任何输出无明文 ─────────────────────────────────────

    @Test
    fun `判据①_走通道落库_回执与日志全文无明文`() {
        val (flow, vault) = wire()
        val outcome = McpClipboardToken.importText(flow, config(), token)
        assertEquals(McpClipboardToken.Outcome.Saved, outcome)
        val stored = vault.load("srv513", "https://mcp.example.com/mcp")
        assertNotNull("真的进了保险柜", stored)
        assertEquals(token, stored!!.accessToken)
        assertEquals("面板那列应显示有令牌", McpAuthStatus.VALID, flow.status(config()))
        assertFalse("回执对象不带内容", outcome.toString().contains(token))
        assertFalse("日志全文 grep 无明文", logs.joinToString("\n").contains(token))
        assertTrue("落库事件账还在", logs.any { it.contains("已导入手动令牌") })
    }

    // ── 判据②：空剪贴板不写 ─────────────────────────────────────────────

    @Test
    fun `判据②_null与空串_拒收且不写库`() {
        val (flow, vault) = wire()
        for (clip in listOf(null, "")) {
            val outcome = McpClipboardToken.importText(flow, config(), clip)
            assertEquals(McpClipboardToken.Reason.EMPTY, (outcome as McpClipboardToken.Outcome.Rejected).reason)
        }
        assertNull(vault.load("srv513", "https://mcp.example.com/mcp"))
        assertFalse(logs.any { it.contains("已导入手动令牌") })
    }

    // ── 判据③：非令牌形拒（含空白即拒，不 trim） ─────────────────────────

    @Test
    fun `判据③_含空白即拒_首尾也不例外`() {
        val (flow, vault) = wire()
        // importToken 本口会 trim 放行首尾空白；本通道按判据③字面口径更严：一律拒。
        for (clip in listOf(" $token", "$token ", "a b", "a\nb", "a\tb", "　")) {
            assertFalse("looksLikeToken 应拒：[$clip]", McpClipboardToken.looksLikeToken(clip))
            val outcome = McpClipboardToken.importText(flow, config(), clip)
            assertEquals(McpClipboardToken.Reason.SHAPE, (outcome as McpClipboardToken.Outcome.Rejected).reason)
        }
        assertNull(vault.load("srv513", "https://mcp.example.com/mcp"))
        assertFalse(logs.any { it.contains("已导入手动令牌") })
    }

    @Test
    fun `判据③_超长拒_边界8192放行`() {
        val (flow, vault) = wire()
        val over = "a".repeat(McpAuthFlow.MAX_IMPORTED_TOKEN + 1)
        val outcome = McpClipboardToken.importText(flow, config(), over)
        assertEquals(McpClipboardToken.Reason.SHAPE, (outcome as McpClipboardToken.Outcome.Rejected).reason)
        assertNull(vault.load("srv513", "https://mcp.example.com/mcp"))
        val edge = "a".repeat(McpAuthFlow.MAX_IMPORTED_TOKEN)
        assertEquals(McpClipboardToken.Outcome.Saved, McpClipboardToken.importText(flow, config(), edge))
    }

    @Test
    fun `判据③_非ASCII与控制字符拒`() {
        val (flow, _) = wire()
        val bad = listOf(
            "令牌abc123456789012345",
            "abc" + 0x01.toChar() + "def1234567890123",
            "abc" + 0x7f.toChar() + "def1234567890123",
        )
        for (clip in bad) {
            val outcome = McpClipboardToken.importText(flow, config(), clip)
            assertEquals(McpClipboardToken.Reason.SHAPE, (outcome as McpClipboardToken.Outcome.Rejected).reason)
        }
    }

    @Test
    fun `判据③补_通道指不到不许可的地址_502的构造闸先拦`() {
        // 这条钉的是「通道只能指向合法登记的服务器」：不许可的 URL 在造配置时就抛（502 闸），
        // 所以本通道没有「指着一台坏地址写库」这条路。IMPORT 分支留给真机保险柜异常。
        for (bad in listOf("没有协议", "ftp://mcp.example.com/mcp")) {
            assertThrows(IllegalArgumentException::class.java) { config(bad) }
        }
    }

    // ── 判据④：读后清空（结构钉；真机复核在交付报告） ─────────────────────

    @Test
    fun `判据④_清空只挂在Saved分支_全文件仅一处`() {
        val src = code("src/main/java/com/example/zhengdao/ui/baopu/McpServerScreen.kt")
        assertEquals("清剪贴板全文件只许一处调用", 1, Regex("clearPrimaryClip\\(").findAll(src).count())
        val savedAt = src.indexOf("is McpClipboardToken.Outcome.Saved")
        val clearAt = src.indexOf("clearPrimaryClip(context)")
        assertTrue("两个锚都要在", savedAt >= 0 && clearAt >= 0)
        assertTrue("清的动作必须挂在 Saved 之后（拒收不清）", savedAt < clearAt)
        assertTrue("清完再读一次复核", src.contains("readPrimaryClip(context).isNullOrEmpty()"))
    }

    // ── 判据⑤：对话侧只提示，绝不自动落库 ────────────────────────────────

    @Test
    fun `判据⑤_提示函数体内没有任何写库调用`() {
        val src = code("src/main/java/com/example/zhengdao/baopu/mcp/McpClipboardToken.kt")
        val hintPart = src.substringAfter("fun containsTokenWord")
        assertFalse("提示侧不许碰落库口", hintPart.contains("importText("))
        assertFalse("提示侧不许碰保险柜", hintPart.contains("vault"))
        val ctrl = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")
        assertTrue("send 里接了提示", ctrl.contains("hintTokenInChat(text)"))
        val body = ctrl.substringAfter("private fun hintTokenInChat").substringBefore("\n    }")
        assertTrue(body.contains("shouldHintChatToken"))
        assertTrue(body.contains("showNotice"))
        assertFalse("对话侧不许写库", body.contains("importToken") || body.contains("Vault"))
    }

    // ── 提示一次：进程内恰好一次 ─────────────────────────────────────────

    @Test
    fun `提示一次_同一进程只提示一回`() {
        assertTrue(McpClipboardToken.shouldHintChatToken("带着 $token 的一句话"))
        assertFalse("第二次就不提示了", McpClipboardToken.shouldHintChatToken("又带着 $token 的一句话"))
        McpClipboardToken.resetForTest()
        assertTrue("进程重启（测试复位）后可再提示", McpClipboardToken.shouldHintChatToken("带着 $token 的一句话"))
    }

    @Test
    fun `普通聊天不烧掉提示机会`() {
        assertFalse(McpClipboardToken.shouldHintChatToken("今天天气怎么样"))
        assertFalse(McpClipboardToken.shouldHintChatToken("帮我把这个文件改一下"))
        assertTrue("前面的普通对话没把「一次」用掉", McpClipboardToken.shouldHintChatToken("令牌是 $token"))
    }

    @Test
    fun `词形判定_正负例`() {
        assertTrue(McpClipboardToken.containsTokenWord(token))
        assertTrue(McpClipboardToken.containsTokenWord("sk-proj-abcd1234efgh5678ijkl"))
        assertFalse(McpClipboardToken.containsTokenWord("这是一段普通的中文对话，没有令牌。"))
        assertFalse("网址段大多被挡", McpClipboardToken.containsTokenWord("https://example.com/some/long/path/withoutdigits"))
        assertFalse("纯字母长词不算", McpClipboardToken.containsTokenWord("aaaaaaaaaaaaaaaaaaaaaaaa"))
        assertFalse("纯数字长词不算", McpClipboardToken.containsTokenWord("123456789012345678901234"))
        assertFalse("太短不算", McpClipboardToken.containsTokenWord("abc123"))
    }
}