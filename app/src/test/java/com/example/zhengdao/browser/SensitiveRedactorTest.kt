package com.example.zhengdao.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SensitiveRedactor 单测 —— §4.3/§4.4 强制脱敏红线的回归网。
 *
 * 立场（用户 2026-10-13 定调，②）：密码/卡号/手机号等隐私强制脱敏、agent/AI 不可绕。
 * 宁可误报（长数字串当卡号）不漏报——测试把"已知误报行为"也钉死，防回归成漏报。
 */
class SensitiveRedactorTest {

    private val redactor = SensitiveRedactor()

    @Test
    fun `手机号脱敏`() {
        assertEquals("致电 *** 联系", redactor.redactText("致电 13800138000 联系"))
    }

    @Test
    fun `银行卡号脱敏`() {
        assertEquals("卡号 ***", redactor.redactText("卡号 6222021234567890123"))
    }

    @Test
    fun `身份证脱敏_不留尾巴X`() {
        // 回归：ID_CARD 必须早于 BANK_CARD，否则输出"身份证 ***X"（17 位被卡号规则先吃）
        assertEquals("身份证 ***", redactor.redactText("身份证 11010119900307761X"))
    }

    @Test
    fun `密码键值脱敏_保留键名`() {
        assertEquals("password=***", redactor.redactText("password=SuperSecret123"))
    }

    @Test
    fun `普通文本不动`() {
        val text = "普通文本，没有隐私。版本 2.1.1 构建号 20261013。"
        assertEquals(text, redactor.redactText(text))
    }

    @Test
    fun `URL 不误伤`() {
        val url = "https://example.com/path?a=1&b=22"
        assertEquals(url, redactor.redactText(url))
    }

    @Test
    fun `十六位数字串按卡号脱敏_隐私优先的已知行为`() {
        // 订单号等 16-19 位纯数字会被当卡号脱敏——误报可接受（漏报不可接受）
        assertEquals("订单 *** 已发货", redactor.redactText("订单 2026101301234567 已发货"))
    }

    @Test
    fun `cookie 键值脱敏_非敏感键保留`() {
        assertEquals(
            "sessionid=***; theme=dark",
            redactor.redactKeyValues("sessionid=abc123def; theme=dark"),
        )
    }

    @Test
    fun `author 键被误报脱敏_隐私优先的已知行为`() {
        // auth 前缀通吃键名后缀的代价：author= 也打码。漏报不可接受，误报可接受。
        assertEquals("author=***", redactor.redactKeyValues("author=张三"))
    }

    @Test
    fun `关闭时原样返回`() {
        val off = SensitiveRedactor(enabled = false)
        assertEquals("13800138000", off.redactText("13800138000"))
    }

    @Test
    fun `DOM password 输入框值脱敏`() {
        val html = """<input type="password" value="hunter2">"""
        assertEquals("""<input type="password" value="***">""", redactor.redactDom(html))
    }

    @Test
    fun `空输入安全`() {
        assertEquals("", redactor.redactText(""))
        assertEquals("", redactor.redactDom(""))
        assertEquals("", redactor.redactKeyValues(""))
    }
}
