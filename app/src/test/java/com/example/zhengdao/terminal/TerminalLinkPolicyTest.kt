// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端链接闸门（第二十一批 A 段）——**判据本体**，纯 JVM，不碰 Android API。
 *
 * ## 钉的是什么
 *
 * 旧版 `needsConfirm(url)` 函数体是 `return false`、`classify(url)` 的 `when`
 * 从不产出 `CONFIRM`。界面与注释上写着「首次域名二次确认」，代码里这条判据是空的
 * （`docs/接线审计-2026-10-18.md` P1「假安全」）。本文件把「真空的判据」钉住：
 *
 * - **J1 同源**：`needsConfirm` 与 `classify` 必须给同一个答案——旧版之所以能一边
 *   写着要确认、一边永不确认，就是因为两处各写一套。现在两个入口都走 `verdictFor`，
 *   本测试用「遍历样本 × 比对两个函数」的方式锁死，谁再拆成两套就红。
 * - **J2 信任簿与红线**：信任簿内的 host 直接开；危险 scheme **永远** `REJECT`
 *   ——哪怕那个 host 恰好在信任簿里（不能因为"信任过这个域名"就放行 file://）。
 *
 * ## 为什么值得单独钉
 *
 * 这条判据的全部价值是「防显示 A 跳 B 的钓鱼」：终端输出不可信，任何程序都能打印
 * 一个看着像 `github.com` 实际指向 `evil.example` 的链接。判据一旦退回恒 false，
 * 编译期毫无反应、界面看不出区别，只在真被钓鱼时才有代价。
 */
class TerminalLinkPolicyTest {

    private val empty: Set<String> = emptySet()

    // ── J1：needsConfirm 与 classify 同源 ────────────────────────────────────

    @Test
    fun `J1_陌生域名必须要求确认`() {
        assertEquals(
            "信任簿外的 https 链接必须是 CONFIRM（旧版这里恒 OPEN_IN_APP）",
            TerminalLinkPolicy.Verdict.CONFIRM,
            TerminalLinkPolicy.classify("https://example.com/a", empty),
        )
        assertEquals(
            TerminalLinkPolicy.Verdict.CONFIRM,
            TerminalLinkPolicy.classify("http://example.com", empty),
        )
        assertTrue(TerminalLinkPolicy.needsConfirm("https://example.com/a", empty))
    }

    @Test
    fun `J1_两个入口对同一输入必须同源`() {
        // 样本覆盖四条判据分支；对每一份都要求 needsConfirm == (classify == CONFIRM)。
        val samples = listOf(
            "https://example.com/x",
            "http://example.com",
            "https://trusted.example/x",
            "mailto:a@b.com",
            "tel:10086",
            "intent://x#Intent;scheme=http;end",
            "file:///etc/passwd",
            "content://prov/id",
            "android-app://com.x",
            "weird-scheme://x",
            "https://",
            "not a url",
        )
        val trusted = setOf("trusted.example")
        samples.forEach { url ->
            val verdict = TerminalLinkPolicy.classify(url, trusted)
            assertEquals(
                "同源判据被拆成两套：$url",
                verdict == TerminalLinkPolicy.Verdict.CONFIRM,
                TerminalLinkPolicy.needsConfirm(url, trusted),
            )
        }
    }

    // ── J2：信任簿内直接开；危险 scheme 永远拒 ───────────────────────────────

    @Test
    fun `J2_信任簿内直接开`() {
        val trusted = setOf("github.com")
        assertEquals(
            TerminalLinkPolicy.Verdict.OPEN_IN_APP,
            TerminalLinkPolicy.classify("https://github.com/a/b", trusted),
        )
        assertFalse(TerminalLinkPolicy.needsConfirm("https://github.com/a/b", trusted))
    }

    @Test
    fun `J2_信任簿大小写不敏感`() {
        val trusted = setOf("GitHub.COM")
        assertEquals(
            TerminalLinkPolicy.Verdict.OPEN_IN_APP,
            TerminalLinkPolicy.classify("https://github.com/x", trusted),
        )
        // 反向：簿里小写、链接大写
        assertEquals(
            TerminalLinkPolicy.Verdict.OPEN_IN_APP,
            TerminalLinkPolicy.classify("https://GITHUB.com/x", setOf("github.com")),
        )
    }

    @Test
    fun `J2_危险scheme即使host在信任簿里也拒`() {
        // 信任的是域名，不是「这个域名下的一切」——file://evil.com 仍然不许
        val trusted = setOf("evil.com", "localhost")
        listOf(
            "file://evil.com/x",
            "intent://evil.com/x#Intent;end",
            "content://evil.com/x",
            "android-app://evil.com",
        ).forEach { url ->
            assertEquals(
                "危险 scheme 被信任簿吃成放行：$url",
                TerminalLinkPolicy.Verdict.REJECT,
                TerminalLinkPolicy.classify(url, trusted),
            )
        }
    }

    @Test
    fun `J2_系统scheme交系统_不受信任簿影响`() {
        assertEquals(
            TerminalLinkPolicy.Verdict.OPEN_SYSTEM,
            TerminalLinkPolicy.classify("mailto:someone@example.com", empty),
        )
        assertEquals(
            TerminalLinkPolicy.Verdict.OPEN_SYSTEM,
            TerminalLinkPolicy.classify("tel:10086", setOf("10086")),
        )
    }

    @Test
    fun `J2_http没有host一律拒`() {
        // https:// 后什么都没有 ⇒ 没有 host ⇒ 不可信（不能因为 scheme 对就放行）
        listOf("https://", "http://", "https:///path").forEach { url ->
            assertEquals(
                "无 host 的 http(s) 必须拒：$url",
                TerminalLinkPolicy.Verdict.REJECT,
                TerminalLinkPolicy.classify(url, empty),
            )
        }
    }

    // ── 解析边界（hostOf / schemeOf 是判据的地基）────────────────────────────

    @Test
    fun `host解析_去端口去userinfo`() {
        assertEquals("example.com", TerminalLinkPolicy.hostOf("https://example.com/a?b#c"))
        assertEquals("example.com", TerminalLinkPolicy.hostOf("https://example.com:8443/a"))
        assertEquals("example.com", TerminalLinkPolicy.hostOf("https://user:pw@example.com:8443/a"))
        assertEquals("example.com", TerminalLinkPolicy.hostOf("https://a@b@example.com/x"))
        assertEquals("example.com", TerminalLinkPolicy.hostOf("https://example.com"))
    }

    @Test
    fun `host解析_IPv6字面量`() {
        assertEquals("::1", TerminalLinkPolicy.hostOf("http://[::1]:8080/x"))
        assertEquals("2001:db8::1", TerminalLinkPolicy.hostOf("http://[2001:db8::1]/x"))
        assertNull(TerminalLinkPolicy.hostOf("http://[::1/x"))
    }

    @Test
    fun `host解析_无authorities返回null`() {
        assertNull(TerminalLinkPolicy.hostOf("mailto:a@b.com"))
        assertNull(TerminalLinkPolicy.hostOf("no-scheme-here"))
        assertNull(TerminalLinkPolicy.hostOf("https:///only/path"))
    }

    @Test
    fun `scheme解析_RFC3986形状`() {
        assertEquals("https", TerminalLinkPolicy.schemeOf("HTTPS://EXAMPLE.COM"))
        assertEquals("mailto", TerminalLinkPolicy.schemeOf("mailto:a@b.com"))
        assertEquals("a+b-c.d", TerminalLinkPolicy.schemeOf("a+b-c.d://x"))
        assertNull("数字开头不是合法 scheme", TerminalLinkPolicy.schemeOf("1http://x"))
        assertNull("没有冒号不是链接", TerminalLinkPolicy.schemeOf("plain text"))
        assertNull("冒号在开头", TerminalLinkPolicy.schemeOf(":x"))
        assertNull("scheme 含非法字符", TerminalLinkPolicy.schemeOf("ht tp://x"))
    }

    @Test
    fun `信任簿上限是个正数且被实现引用`() {
        // 上限只约束「调用方记多少」，judge 侧不参与；这里只钉它没被改成 0/负数
        assertTrue("MAX_TRUSTED 必须为正", TerminalLinkPolicy.MAX_TRUSTED > 0)
    }
}
