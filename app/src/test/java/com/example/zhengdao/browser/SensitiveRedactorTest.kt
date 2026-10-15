// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SensitiveRedactor 单测 —— §4.3/§4.4 强制脱敏红线的回归网。
 *
 * 立场（用户 2026-10-13 定调，②）：密码/卡号/手机号等隐私强制脱敏、agent/AI 不可绕。
 * 宁可误报（长数字串当卡号）不漏报——测试把"已知误报行为"也钉死，防回归成漏报。
 *
 * 第㊵刀变更：`redactKeyValues` 删除（零生产调用点的死代码），其模式由两个真实
 * 出口承接—— [SensitiveRedactor.redactPairs]（cookie/localStorage 键值对）与
 * [SensitiveRedactor.redactUrl]（URL query 敏感键名，status/navigate 用）。
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
    fun `关闭时原样返回`() {
        val off = SensitiveRedactor(enabled = false)
        assertEquals("13800138000", off.redactText("13800138000"))
        assertEquals("sessionid=abc", off.redactPairs("sessionid=abc"))
        assertEquals("https://x.test/?token=abc", off.redactUrl("https://x.test/?token=abc"))
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
        assertEquals("", redactor.redactPairs(""))
        assertEquals("", redactor.redactUrl(""))
    }

    // ── redactPairs（第㊵刀：承接原 redactKeyValues 的三条，出口改为 execJS） ──

    @Test
    fun `键值对脱敏_非敏感键保留`() {
        assertEquals(
            "sessionid=***; theme=dark",
            redactor.redactPairs("sessionid=abc123def; theme=dark"),
        )
    }

    @Test
    fun `author 键被误报脱敏_隐私优先的已知行为`() {
        // auth 前缀通吃键名后缀的代价：author= 也打码。漏报不可接受，误报可接受。
        assertEquals("author=***", redactor.redactPairs("author=张三"))
    }

    @Test
    fun `键值对_cookie 分隔符与引号形态`() {
        // 冒号分隔 + 引号值：开引号被分隔符后的 ["']? 消费，尾引号残留——值打码即达标
        assertEquals("token=***'", redactor.redactPairs("token: 'abc123'"))
    }

    // ── redactUrl（第㊵刀 P2：status/navigate 的 url 出口） ──

    @Test
    fun `URL_敏感键名值打码_键名保留`() {
        assertEquals(
            "https://x.test/p?token=***&tel=***&next=/home",
            redactor.redactUrl("https://x.test/p?token=abcSECRET&tel=13800138000&next=/home"),
        )
    }

    @Test
    fun `URL_后缀键名也打码`() {
        // sessionid / csrf_token / api-key / user_token 都算（键名后允许 \w- 后缀）
        assertEquals(
            "https://x.test/?sessionid=***&csrf_token=***&api-key=***&user_token=***",
            redactor.redactUrl("https://x.test/?sessionid=s1&csrf_token=t2&api-key=k3&user_token=u4"),
        )
    }

    @Test
    fun `URL_query 外的号码类 PII 走 redactText 兜底`() {
        // 无 query 的 URL 整体过 redactText：路径里明文的手机号同样打码（兜底生效）
        assertEquals(
            "https://x.test/***",
            redactor.redactUrl("https://x.test/13800138000"),
        )
    }

    @Test
    fun `URL_普通 query 不误打`() {
        // 负例：q/page/lang 不在敏感键名单，值原样（redactText 也不命中）
        val url = "https://www.bing.com/search?q=%E7%99%BD&page=2&lang=zh-CN"
        assertEquals(url, redactor.redactUrl(url))
    }

    @Test
    fun `URL_大小写不敏感`() {
        assertEquals(
            "https://x.test/?Token=***",
            redactor.redactUrl("https://x.test/?Token=AbC123"),
        )
    }
}
