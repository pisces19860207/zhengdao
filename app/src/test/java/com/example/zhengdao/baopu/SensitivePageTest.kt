// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第一百七十七批施工图 §3（敏感页判据）。纯函数直测。
package com.example.zhengdao.baopu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SensitivePage] 的判据。
 *
 * 钉两件事：
 * 1. **该命中的必须命中**（登录/支付/验证码/银行/钱包，中英都要）；
 * 2. **不该误伤的不许误伤**（`author.com` 不等于 `auth`——整词比对的意义就在这条）。
 */
class SensitivePageTest {

    @Test
    fun 登录域名_命中() {
        assertTrue(SensitivePage.isSensitive("https://login.example.com/", null))
        assertTrue(SensitivePage.isSensitive("https://accounts.example.com/signin", null))
        assertTrue(SensitivePage.isSensitive("https://a.com/logon?next=1", null))
    }

    @Test
    fun 中文标题_命中() {
        assertTrue(SensitivePage.isSensitive("https://a.com/", "微信支付"))
        assertTrue(SensitivePage.isSensitive("https://a.com/", "账户登录"))
        assertTrue(SensitivePage.isSensitive("https://a.com/", "请输入验证码"))
        assertTrue(SensitivePage.isSensitive("https://a.com/", "招商银行"))
    }

    @Test
    fun 验证码与二次验证_命中() {
        assertTrue(SensitivePage.isSensitive("https://a.com/captcha", null))
        assertTrue(SensitivePage.isSensitive("https://a.com/?otp=1", null))
        assertTrue(SensitivePage.isSensitive("https://a.com/", "Two-factor 2FA"))
    }

    @Test
    fun author不是auth_不许误伤() {
        assertFalse("整词比对：author ≠ auth", SensitivePage.isSensitive("https://author.example.com/", "Author"))
        assertFalse(SensitivePage.isSensitive("https://a.com/", "原创作者"))
    }

    @Test
    fun 普通新闻页_不命中() {
        assertFalse(SensitivePage.isSensitive("https://news.example.com/today", "今日要闻"))
        assertFalse(SensitivePage.isSensitive("https://a.com/", "关于我们"))
    }

    @Test
    fun 空输入_不命中() {
        assertFalse(SensitivePage.isSensitive(null, null))
        assertFalse(SensitivePage.isSensitive("", ""))
        assertFalse(SensitivePage.isSensitive("   ", "  "))
    }

    @Test
    fun 只有标题命中也算() {
        assertTrue(SensitivePage.isSensitive(null, "登录 - 某站"))
    }

    @Test
    fun 理由文案带命中词_不含整条URL() {
        val reason = SensitivePage.reasonOf("https://login.example.com/a?token=secret", "登录")
        assertTrue(reason.contains("登录"))
        assertFalse("卡片理由里不许出现整条 URL（可能带 token）", reason.contains("token=secret"))
    }
}
