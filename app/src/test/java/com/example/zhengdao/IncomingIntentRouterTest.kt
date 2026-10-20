// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第八十三批（§B 88）：外部 Intent 路由的 JVM 用例。
 *
 * 每一项都至少三例（分享 / ask / terminal / 单次消费 / 草稿合并），且全部是**纯函数**——
 * 不碰 Android 框架，也不依赖 Compose。真机行为（系统分享面板、深链唤起）不在本类证明范围。
 */
class IncomingIntentRouterTest {

    // ── ACTION_SEND：分享文本 ───────────────────────────────────────────────

    @Test
    fun `分享纯文本_预填抱朴且不截断`() {
        val route = routeIncomingIntent(
            IncomingIntent(
                action = "android.intent.action.SEND",
                type = "text/plain",
                text = "  这是一段分享的正文\n第二行  ",
            )
        )
        assertEquals(IncomingRoute.ShareToBaopu("这是一段分享的正文\n第二行", false), route)
    }

    @Test
    fun `分享纯文本_接受带charset参数的MIME`() {
        val route = routeIncomingIntent(
            IncomingIntent(
                action = "android.intent.action.SEND",
                type = "text/plain; charset=utf-8",
                text = "hello",
            )
        )
        assertEquals(IncomingRoute.ShareToBaopu("hello", false), route)
    }

    @Test
    fun `分享纯文本_EXTRA_TEXT为空时读ClipData的纯文本`() {
        val route = routeIncomingIntent(
            IncomingIntent(
                action = "android.intent.action.SEND",
                type = "text/plain",
                clipText = "来自剪贴板",
            )
        )
        assertEquals(IncomingRoute.ShareToBaopu("来自剪贴板", false), route)
    }

    @Test
    fun `分享非text-plain_直接拒绝`() {
        val route = routeIncomingIntent(
            IncomingIntent(
                action = "android.intent.action.SEND",
                type = "image/png",
                text = "假装是图片",
            )
        )
        assertEquals(IncomingRoute.Rejected(RejectReason.UNSUPPORTED_TYPE), route)
    }

    @Test
    fun `分享空正文_拒绝而不是塞一个空输入`() {
        listOf(null, "", "   \n\t ").forEach { body ->
            val route = routeIncomingIntent(
                IncomingIntent(
                    action = "android.intent.action.SEND",
                    type = "text/plain",
                    text = body,
                )
            )
            assertEquals(IncomingRoute.Rejected(RejectReason.EMPTY_TEXT), route)
        }
    }

    @Test
    fun `分享超长_截断并标记`() {
        val route = routeIncomingIntent(
            IncomingIntent(
                action = "android.intent.action.SEND",
                type = "text/plain",
                text = "字".repeat(MAX_BAOPU_CHARS + 50),
            )
        )
        assertTrue(route is IncomingRoute.ShareToBaopu)
        val share = route as IncomingRoute.ShareToBaopu
        assertEquals(MAX_BAOPU_CHARS, share.text.length)
        assertTrue(share.truncated)
    }

    @Test
    fun `分享截断点落在emoji中间_不留孤立代理`() {
        // 7999 个 ASCII 后接一个四字节 emoji：第 8000 个 UTF-16 单元正好是高位代理。
        val raw = "a".repeat(MAX_BAOPU_CHARS - 1) + "\uD83D\uDE00"
        val route = routeIncomingIntent(
            IncomingIntent(
                action = "android.intent.action.SEND",
                type = "text/plain",
                text = raw,
            )
        )
        val share = route as IncomingRoute.ShareToBaopu
        assertTrue(share.truncated)
        assertEquals(MAX_BAOPU_CHARS - 1, share.text.length)
        assertFalse("截断后不许留下孤立高位代理", share.text.last().isHighSurrogate())
    }

    @Test
    fun `分享正文的换行统一且控制字符被清掉`() {
        val route = routeIncomingIntent(
            IncomingIntent(
                action = "android.intent.action.SEND",
                type = "text/plain",
                text = "第一行\r\n第二行\r第三行\u0000\u001b\u007f",
            )
        )
        assertEquals(
            IncomingRoute.ShareToBaopu("第一行\n第二行\n第三行", false),
            route,
        )
    }

    @Test
    fun `SEND优先于data_带深链data的分享仍按分享处理`() {
        val route = routeIncomingIntent(
            IncomingIntent(
                action = "android.intent.action.SEND",
                scheme = "zhengdao",
                host = "terminal",
                rawQuery = "cmd=whoami",
                type = "text/plain",
                text = "分享正文",
            )
        )
        assertEquals(IncomingRoute.ShareToBaopu("分享正文", false), route)
    }

    @Test
    fun `VIEW即使带EXTRA_TEXT也只看data_不被当分享`() {
        // 优先级由 action 决定：VIEW 没有 data 就是畸形深链，不会退化成「收分享」。
        val route = routeIncomingIntent(
            IncomingIntent(
                action = "android.intent.action.VIEW",
                type = "text/plain",
                text = "这是一段正文",
            )
        )
        assertEquals(IncomingRoute.Rejected(RejectReason.MALFORMED_URI), route)
    }

    @Test
    fun `SEND带非纯文本类型_即使data是合法深链也按分享拒绝`() {
        val route = routeIncomingIntent(
            IncomingIntent(
                action = "android.intent.action.SEND",
                scheme = "zhengdao",
                host = "ask",
                rawQuery = "q=hi",
                type = "image/png",
                text = "看不出来是文件还是文本",
            )
        )
        assertEquals(IncomingRoute.Rejected(RejectReason.UNSUPPORTED_TYPE), route)
    }

    @Test
    fun `无关action即使带合法深链data也静默放过`() {
        val route = routeIncomingIntent(
            IncomingIntent(
                action = "android.intent.action.MAIN",
                scheme = "zhengdao",
                host = "ask",
                rawQuery = "q=hi",
            )
        )
        assertEquals(IncomingRoute.Ignore, route)
    }

    // ── zhengdao://ask ─────────────────────────────────────────────────────

    @Test
    fun `ask深链_百分号解码空格与中文`() {
        assertEquals(
            IncomingRoute.AskBaopu("你好 世界", false),
            routeIncomingIntent(view(scheme = "zhengdao", host = "ask", query = "q=%E4%BD%A0%E5%A5%BD%20%E4%B8%96%E7%95%8C")),
        )
    }

    @Test
    fun `ask深链_加号是字面加号不是空格`() {
        assertEquals(
            IncomingRoute.AskBaopu("1+1", false),
            routeIncomingIntent(view(scheme = "zhengdao", host = "ask", query = "q=1+1")),
        )
    }

    @Test
    fun `ask深链_host大小写不敏感_路径斜杠可省`() {
        assertEquals(
            IncomingRoute.AskBaopu("hi", false),
            routeIncomingIntent(view(scheme = "ZHENGDAO", host = "ASK", path = "/", query = "q=hi")),
        )
    }

    @Test
    fun `ask深链_缺q或空白q都拒绝`() {
        assertEquals(
            IncomingRoute.Rejected(RejectReason.EMPTY_TEXT),
            routeIncomingIntent(view(scheme = "zhengdao", host = "ask", query = null)),
        )
        assertEquals(
            IncomingRoute.Rejected(RejectReason.EMPTY_TEXT),
            routeIncomingIntent(view(scheme = "zhengdao", host = "ask", query = "q=%20%20")),
        )
    }

    @Test
    fun `ask深链_畸形百分号序列拒绝`() {
        assertEquals(
            IncomingRoute.Rejected(RejectReason.MALFORMED_URI),
            routeIncomingIntent(view(scheme = "zhengdao", host = "ask", query = "q=%ZZ")),
        )
    }

    @Test
    fun `ask深链_重复q参数拒绝而不是猜一个`() {
        assertEquals(
            IncomingRoute.Rejected(RejectReason.MALFORMED_URI),
            routeIncomingIntent(view(scheme = "zhengdao", host = "ask", query = "q=first&q=second")),
        )
    }

    @Test
    fun `ask深链_超长截断并标记`() {
        val route = routeIncomingIntent(
            view(scheme = "zhengdao", host = "ask", query = "q=" + "x".repeat(MAX_BAOPU_CHARS + 10))
        )
        val ask = route as IncomingRoute.AskBaopu
        assertEquals(MAX_BAOPU_CHARS, ask.text.length)
        assertTrue(ask.truncated)
    }

    @Test
    fun `ask深链_未知host拒绝`() {
        assertEquals(
            IncomingRoute.Rejected(RejectReason.UNKNOWN_HOST),
            routeIncomingIntent(view(scheme = "zhengdao", host = "evil", query = "q=hi")),
        )
    }

    @Test
    fun `ask深链_http与file都不接`() {
        listOf("http", "https", "file").forEach { scheme ->
            assertEquals(
                IncomingRoute.Rejected(RejectReason.UNSAFE_SCHEME),
                routeIncomingIntent(view(scheme = scheme, host = "ask", query = "q=hi")),
            )
        }
    }

    @Test
    fun `ask深链_带路径userinfo或端口都视为伪形`() {
        assertEquals(
            IncomingRoute.Rejected(RejectReason.MALFORMED_URI),
            routeIncomingIntent(view(scheme = "zhengdao", host = "ask", path = "/evil", query = "q=hi")),
        )
        assertEquals(
            IncomingRoute.Rejected(RejectReason.MALFORMED_URI),
            routeIncomingIntent(view(scheme = "zhengdao", host = "ask", query = "q=hi", userInfo = true)),
        )
        assertEquals(
            IncomingRoute.Rejected(RejectReason.MALFORMED_URI),
            routeIncomingIntent(view(scheme = "zhengdao", host = "ask", query = "q=hi", port = 8080)),
        )
    }

    @Test
    fun `ask深链_不透明URI拒绝`() {
        assertEquals(
            IncomingRoute.Rejected(RejectReason.MALFORMED_URI),
            routeIncomingIntent(
                IncomingIntent(
                    action = "android.intent.action.VIEW",
                    scheme = "zhengdao",
                    host = "ask",
                    rawQuery = "q=hi",
                    isHierarchical = false,
                )
            ),
        )
    }

    // ── zhengdao://terminal ────────────────────────────────────────────────

    @Test
    fun `terminal深链_解码后只预填不执行`() {
        assertEquals(
            IncomingRoute.PrefillTerminal("ls -la /sdcard"),
            routeIncomingIntent(view(scheme = "zhengdao", host = "terminal", query = "cmd=ls%20-la%20%2Fsdcard")),
        )
    }

    @Test
    fun `terminal深链_换行或回车一律拒绝`() {
        listOf("cmd=ls%0Awhoami", "cmd=ls%0Dwhoami", "cmd=ls%0D%0Awhoami").forEach { query ->
            assertEquals(
                IncomingRoute.Rejected(RejectReason.COMMAND_HAS_CONTROL),
                routeIncomingIntent(view(scheme = "zhengdao", host = "terminal", query = query)),
            )
        }
    }

    @Test
    fun `terminal深链_ESC与制表符等控制字符拒绝`() {
        listOf("cmd=ls%1B%5B31m", "cmd=echo%09x", "cmd=ls%00").forEach { query ->
            assertEquals(
                IncomingRoute.Rejected(RejectReason.COMMAND_HAS_CONTROL),
                routeIncomingIntent(view(scheme = "zhengdao", host = "terminal", query = query)),
            )
        }
    }

    @Test
    fun `terminal深链_超长命令拒绝而不是截断`() {
        val route = routeIncomingIntent(
            view(
                scheme = "zhengdao",
                host = "terminal",
                query = "cmd=" + "a".repeat(MAX_TERMINAL_COMMAND_CHARS + 1),
            )
        )
        assertEquals(IncomingRoute.Rejected(RejectReason.COMMAND_TOO_LONG), route)
    }

    @Test
    fun `terminal深链_空命令与未知host拒绝`() {
        assertEquals(
            IncomingRoute.Rejected(RejectReason.EMPTY_TEXT),
            routeIncomingIntent(view(scheme = "zhengdao", host = "terminal", query = "cmd=%20")),
        )
        assertEquals(
            IncomingRoute.Rejected(RejectReason.UNKNOWN_HOST),
            routeIncomingIntent(view(scheme = "zhengdao", host = "term", query = "cmd=ls")),
        )
    }

    @Test
    fun `terminal二次校验_与路由同一口径`() {
        assertTrue(isSafeTerminalPrefillCommand("ls -la"))
        assertFalse(isSafeTerminalPrefillCommand("ls\nwhoami"))
        assertFalse(isSafeTerminalPrefillCommand("ls\u001b[31m"))
        assertFalse(isSafeTerminalPrefillCommand("x".repeat(MAX_TERMINAL_COMMAND_CHARS + 1)))
        assertFalse(isSafeTerminalPrefillCommand("   "))
    }

    // ── 无关 action 与单次消费 ──────────────────────────────────────────────

    @Test
    fun `桌面启动等无关action_静默放过`() {
        listOf(null, "android.intent.action.MAIN", "com.example.zhengdao.action.BAOPU_BACKUP").forEach { action ->
            assertEquals(IncomingRoute.Ignore, routeIncomingIntent(IncomingIntent(action = action)))
        }
    }

    @Test
    fun `单次消费门_同一token只处理一次`() {
        val gate = IntentSingleShot()
        assertTrue(gate.shouldHandle("same"))
        assertFalse(gate.shouldHandle("same"))
        assertFalse(gate.shouldHandle("same"))
        assertTrue(gate.shouldHandle("other"))
    }

    @Test
    fun `单次消费门_空token不拦`() {
        val gate = IntentSingleShot()
        assertTrue(gate.shouldHandle(null))
        assertTrue(gate.shouldHandle(""))
        assertTrue(gate.shouldHandle("  "))
    }

    @Test
    fun `单次消费门_容量满后旧token被淘汰`() {
        val gate = IntentSingleShot(capacity = 3)
        assertTrue(gate.shouldHandle("a"))
        assertTrue(gate.shouldHandle("b"))
        assertTrue(gate.shouldHandle("c"))
        assertTrue(gate.shouldHandle("d")) // 淘汰 a
        assertTrue("被淘汰的旧 token 再次出现应重新处理", gate.shouldHandle("a"))
        assertFalse("窗口内的 token 仍要拦住", gate.shouldHandle("d"))
    }

    @Test
    fun `token由对象身份加内容组成_同文两次分享不算重复`() {
        val first = incomingIntentToken(1, "android.intent.action.SEND", null, "同一条")
        val sameObject = incomingIntentToken(1, "android.intent.action.SEND", null, "同一条")
        val newObject = incomingIntentToken(2, "android.intent.action.SEND", null, "同一条")
        assertEquals(first, sameObject)
        assertTrue(first != newObject)
    }

    @Test
    fun `token含data摘要_不同深链不同键且同一条稳定`() {
        val a = incomingIntentToken(1, "android.intent.action.VIEW", "zhengdao://ask?q=a", null)
        val b = incomingIntentToken(1, "android.intent.action.VIEW", "zhengdao://ask?q=b", null)
        assertEquals(
            a,
            incomingIntentToken(1, "android.intent.action.VIEW", "zhengdao://ask?q=a", null),
        )
        assertTrue("data 不同的两次投递不能共用一个键", a != b)
    }

    // ── 草稿合并 ───────────────────────────────────────────────────────────

    @Test
    fun `草稿为空_直接用送来的正文`() {
        assertEquals("分享正文", mergeIncomingDraft("", " 分享正文 "))
        assertEquals("分享正文", mergeIncomingDraft("   \n", "分享正文"))
    }

    @Test
    fun `已有草稿_保留草稿并空一行追加`() {
        assertEquals("我正在写\n\n分享正文", mergeIncomingDraft("我正在写\n", "分享正文"))
    }

    @Test
    fun `空正文不改变草稿`() {
        assertEquals("原草稿", mergeIncomingDraft("原草稿", "   "))
    }

    // ── helper ────────────────────────────────────────────────────────────

    private fun view(
        scheme: String?,
        host: String?,
        query: String?,
        path: String? = "",
        userInfo: Boolean = false,
        port: Int = -1,
    ) = IncomingIntent(
        action = "android.intent.action.VIEW",
        scheme = scheme,
        host = host,
        path = path,
        rawQuery = query,
        isHierarchical = true,
        hasUserInfo = userInfo,
        port = port,
    )
}
