// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十六批 §3/§4 安全层单测——负例 3（拒点）、4（密码拒填）、6（域白名单）、
// 7（凭证扫描）的判据靶子。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachSafetyTest {

    // ── 负例 3 判据面：拒点词（派工单定稿词表，代码层面一律不点）────────────────

    @Test
    fun `负例3_提交二字无论出现在哪个语义面都拒点`() {
        listOf(
            TeachSafety.ClickSurface(text = "提交", accessibleName = null, ariaLabel = null, testid = null),
            TeachSafety.ClickSurface(text = null, accessibleName = "提交订单", ariaLabel = null, testid = null),
            TeachSafety.ClickSurface(text = null, accessibleName = null, ariaLabel = "确认提交", testid = null),
            TeachSafety.ClickSurface(text = null, accessibleName = null, ariaLabel = null, testid = "submit-btn"),
        ).forEach {
            val reason = TeachSafety.refuseClickReason(it)
            assertNotNull("必须拒点：$it", reason)
            assertTrue(reason!!.contains("拒点"))
        }
    }

    @Test
    fun `负例3_九个拒点词逐个都拦`() {
        val words = listOf("提交", "确认", "删除", "作废", "发货", "派车", "结算", "支付", "充值")
        words.forEach { w ->
            val r = TeachSafety.refuseClickReason(TeachSafety.ClickSurface(w, null, null, null))
            assertNotNull("「$w」必须拒点", r)
            assertTrue(r!!.contains(w))
        }
    }

    @Test
    fun `正常按钮放行_长文案里不含拒点词也不拦`() {
        assertNull(
            TeachSafety.refuseClickReason(TeachSafety.ClickSurface("查询运单", null, null, null)),
        )
        assertNull(
            TeachSafety.refuseClickReason(TeachSafety.ClickSurface("下一页", null, null, null)),
        )
        assertNull(
            TeachSafety.refuseClickReason(TeachSafety.ClickSurface("导出清单", null, null, null)),
        )
    }

    // ── 负例 4 判据面：密码/验证码拒填 ─────────────────────────────────────────

    @Test
    fun `负例4_type_password硬拒`() {
        val r = TeachSafety.refuseTypeReason("password", null, null, null, null)
        assertNotNull(r)
        assertTrue(r!!.contains("密码框"))
    }

    @Test
    fun `负例4_疑似凭证字段拒填_名字命中的都拦`() {
        listOf("password", "passwd", "pwd", "user_pwd", "captcha", "otp", "验证码", "密码框")
            .forEach { suspicious ->
                val r = TeachSafety.refuseTypeReason(null, suspicious, null, null, null)
                assertNotNull("字段名「$suspicious」应拒填", r)
            }
        // id / placeholder 面同样拦
        assertNotNull(TeachSafety.refuseTypeReason("text", null, "login-pwd", null, null))
        assertNotNull(TeachSafety.refuseTypeReason("text", null, null, "请输入密码", null))
        assertNotNull(TeachSafety.refuseTypeReason("text", null, null, null, "one-time-code"))
    }

    @Test
    fun `普通输入框放行`() {
        assertNull(TeachSafety.refuseTypeReason("search", "q", "search-input", "输入运单号", null))
        assertNull(TeachSafety.refuseTypeReason("text", "wb_no", null, null, null))
    }

    // ── 负例 6 判据面：域名白名单 ──────────────────────────────────────────────

    @Test
    fun `负例6_授权域内放行_域外与子域蹭边都拦`() {
        val domains = listOf("search.example.com")
        assertTrue(TeachSafety.domainAllowed("https://search.example.com/start", domains))
        assertTrue(TeachSafety.domainAllowed("https://api.search.example.com/list", domains)) // 子域放行
        // 域外一律停
        assertEquals(false, TeachSafety.domainAllowed("https://evil.example.net/done", domains))
        assertEquals(false, TeachSafety.domainAllowed("https://search.example.com.evil.io/x", domains)) // 后缀蹭边
        assertEquals(false, TeachSafety.domainAllowed("http://other.com/", domains))
        assertEquals(false, TeachSafety.domainAllowed("https://search.example.com/", emptyList())) // 白名单空＝不放行
        assertEquals(false, TeachSafety.domainAllowed("about:blank", domains))
    }

    @Test
    fun `host解析_带端口带query都取对`() {
        assertEquals("a.com", TeachSafety.hostOf("https://a.com:8443/x?u=1#f"))
        assertEquals("a.com", TeachSafety.hostOf("https://USER@a.com/p"))
        assertEquals("b.cn", TeachSafety.hostOf("http://b.cn"))
    }

    // ── 负例 7 判据面：技能里出现明文凭证 ⇒ 写盘前直接拒绝 ─────────────────────

    @Test
    fun `负例7_密码框带值_扫描必中`() {
        val json = """
            {"schema":"zl-teach/1","steps":[
              {"i":1,"action":"type","locators":[{"tier":4,"kind":"elemId","value":"pwd"}],
               "value":"hunter2secret","snapshot":{"tag":"INPUT","role":"textbox","name":"","text":"","inputType":"password"}}
            ]}
        """.trimIndent()
        val findings = TeachSafety.credentialFindings(json)
        assertTrue("必须扫出密码值：$findings", findings.isNotEmpty())
        assertTrue(findings.first().contains("密码框"))
        // 报告里只留脱敏形式
        assertTrue(!findings.first().contains("hunter2secret"))
    }

    @Test
    fun `负例7_凭证键名与令牌字面量都扫得出`() {
        val withKey = """{"steps":[{"password":"p@ss"}]}"""
        assertTrue(TeachSafety.credentialFindings(withKey).isNotEmpty())
        val withJwt = """
            {"steps":[{"i":1,"value":"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.sig"}]}
        """.trimIndent()
        assertTrue(TeachSafety.credentialFindings(withJwt).isNotEmpty())
    }

    @Test
    fun `负例7_正常运单号与URL不误伤`() {
        val json = """
            {"schema":"zl-teach/1","startUrl":"https://search.example.com/start",
             "steps":[{"i":1,"action":"type","value":"WB2026102400123456",
               "locators":[{"tier":5,"kind":"inputName","value":"q"}],
               "snapshot":{"tag":"INPUT","inputType":"search"}}]}
        """.trimIndent()
        assertEquals(emptyList<String>(), TeachSafety.credentialFindings(json))
    }

    @Test
    fun `脱敏mask只留首尾`() {
        assertEquals("**", TeachSafety.mask("ab"))
        assertEquals("ab*****yz", TeachSafety.mask("abcdefgyz")) // 9 字：头 2 + 5 个 * + 尾 2
    }
}
