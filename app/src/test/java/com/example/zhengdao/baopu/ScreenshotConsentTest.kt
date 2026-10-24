// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第一百七十七批施工图 §3（授权闸）＋用户 2026-10-24 口径：
// 「不逐张弹卡可以，但必须有一次会话授权/首次确认＋审计，不能后台偷截。」
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScreenshotConsent] 的判据：**卡长什么样 ＋ 点了之后怎么走**。
 *
 * 最关键的一条：**超时/没人回答 = 拒绝**。把它算成同意，就是后台偷截。
 */
class ScreenshotConsentTest {

    @Test
    fun 普通页_三个选项_推荐会话授权() {
        val req = ScreenshotConsent.request("example.com", "首页", sensitive = false)
        assertEquals(listOf(ScreenshotConsent.OPT_SESSION, ScreenshotConsent.OPT_ONCE, ScreenshotConsent.OPT_DENY),
            req.options.map { it.id })
        assertTrue("首次确认推荐「本次会话允许」", req.options.first { it.id == ScreenshotConsent.OPT_SESSION }.recommended)
    }

    @Test
    fun 敏感页_措辞点明原因_推荐不截() {
        val req = ScreenshotConsent.request("login.example.com", "登录", sensitive = true)
        assertTrue(req.question.contains("敏感页"))
        assertTrue("卡上要说清是哪个词触发的", req.question.contains("登录"))
        assertTrue("敏感页默认推荐「不截」", req.options.first { it.id == ScreenshotConsent.OPT_DENY }.recommended)
        assertFalse("敏感页不该推荐会话授权", req.options.first { it.id == ScreenshotConsent.OPT_SESSION }.recommended)
    }

    @Test
    fun 卡上必须写清范围_不承诺安全() {
        val req = ScreenshotConsent.request("example.com", "首页", sensitive = false)
        val all = req.question + req.options.joinToString(" ") { it.detail }
        assertTrue("只说当前页", all.contains("当前这一页") || all.contains("只截玄览当前这一页"))
        assertTrue("不上传≠不发", all.contains("不上传"))
        assertTrue("落点写明", all.contains("证道/截屏/"))
        assertTrue("不进相册", all.contains("不进相册"))
    }

    @Test
    fun 无地址无标题_卡照样出() {
        val req = ScreenshotConsent.request(null, null, sensitive = false)
        assertTrue(req.question.contains("截图"))
        assertEquals(3, req.options.size)
    }

    @Test
    fun 超时或认不出的答案_一律拒绝() {
        assertEquals(ScreenshotConsent.Decision.DENY, ScreenshotConsent.decide(null))
        assertEquals(ScreenshotConsent.Decision.DENY, ScreenshotConsent.decide(""))
        assertEquals(ScreenshotConsent.Decision.DENY, ScreenshotConsent.decide("whatever"))
    }

    @Test
    fun 两个同意分支分得开() {
        assertEquals(ScreenshotConsent.Decision.ALLOW_SESSION, ScreenshotConsent.decide(ScreenshotConsent.OPT_SESSION))
        assertEquals(ScreenshotConsent.Decision.ALLOW_ONCE, ScreenshotConsent.decide(ScreenshotConsent.OPT_ONCE))
    }

    @Test
    fun 拒绝话术_指向文字快照这条路() {
        val text = ScreenshotConsent.deniedText()
        assertTrue(text.contains("不截图"))
        assertTrue("要给模型下一条路", text.contains("browser_snapshot"))
    }

    @Test
    fun 审计行_带授权来源与文件() {
        val line = ScreenshotConsent.auditLine(
            source = "会话授权",
            url = "https://example.com/a",
            title = "首页",
            file = "截屏/bridge-1.png",
            bytes = 1234,
        )
        assertTrue(line.contains("授权=会话授权"))
        assertTrue(line.contains("url=https://example.com/a"))
        assertTrue(line.contains("file=截屏/bridge-1.png"))
        assertTrue(line.contains("bytes=1234"))
    }

    @Test
    fun 审计行_无文件也成立() {
        val line = ScreenshotConsent.auditLine("拒绝/无应答", "-", "", null, null)
        assertTrue(line.contains("授权=拒绝/无应答"))
        assertFalse(line.contains("file="))
    }
}
