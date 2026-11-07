// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十六批 · 判据①的测试：一句「我想要一个能把 X 变成 Y 的东西」能被认成许愿，
// 普通聊天不误触发（与 TopicSuggest 同一条纪律：刻意窄）；产物类型猜四格；确认回话可解析。
// 生产调用点在 BaopuChatController.send()（本仓库唯一喂入口），此处只测判定层。
package com.example.zhengdao.baopu.wish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WishTriggerTest {

    // ── 判据①：那句许愿话能进 ────────────────────────────────────────────

    @Test
    fun 判据1_许愿原话能被认出来() {
        assertTrue(WishTrigger.isWishRequest("我想要一个能把长文变成卡片的工具"))
        assertTrue(WishTrigger.isWishRequest("我想要个东西把 Markdown 变成微头条"))
        assertTrue(WishTrigger.isWishRequest("我想要一款能把杂记转成整理步骤的小工具"))
        assertTrue(WishTrigger.isWishRequest("许愿：把我的资料整理成卡"))
        assertTrue(WishTrigger.isWishRequest("帮我做一个能把大纲换成正文的书"))
    }

    @Test
    fun 普通聊天不误触发() {
        assertFalse("没有产物名词收尾", WishTrigger.isWishRequest("我想要把这段话润色一下"))
        assertFalse("没有改造意图", WishTrigger.isWishRequest("我想要一个苹果"))
        assertFalse(WishTrigger.isWishRequest("今天的天气怎么样"))
        assertFalse("题材流的地盘，不许抢", WishTrigger.isWishRequest("微头条发完了，下一篇写什么"))
        assertFalse("复盘的地盘", WishTrigger.isWishRequest("帮我复盘一下最近的改动"))
        assertFalse(WishTrigger.isWishRequest("把这三张图拼成一张"))
    }

    // ── 产物类型猜（四格） ────────────────────────────────────────────────

    @Test
    fun 产物类型照名词猜() {
        assertEquals(WishProduct.CARD, WishTrigger.productOf("我想要一个能把长文变成卡片的工具"))
        assertEquals(WishProduct.NOVEL, WishTrigger.productOf("我想要一个能把大纲变成正文的书"))
        assertEquals(WishProduct.CONTENT, WishTrigger.productOf("我想要一个能把想法变成微头条的流程"))
        assertEquals(WishProduct.SKILL, WishTrigger.productOf("我想要一个能把杂记变成整理步骤的东西"))
    }

    @Test
    fun 类型猜不到就落技能() {
        assertEquals(WishProduct.SKILL, WishTrigger.productOf("我想要一个能把 X 变成 Y 的东西"))
    }

    // ── 确认回话 ────────────────────────────────────────────────────────

    @Test
    fun 确认回话能解析判据编号() {
        val c = WishTrigger.confirmRequest("许愿确认 S1 S3")
        assertEquals(listOf("S1", "S3"), c?.ids)
        assertNull("没带 id 就接最近一条", c?.wishId)
    }

    @Test
    fun 确认回话可以带许愿ID() {
        val c = WishTrigger.confirmRequest("许愿确认 wish-1-1699000000 T2")
        assertEquals("wish-1-1699000000", c?.wishId)
        assertEquals(listOf("T2"), c?.ids)
    }

    @Test
    fun 判据编号里的S不会被当ID() {
        val c = WishTrigger.confirmRequest("许愿确认 S1 S3")
        assertNull("S1 是判据编号不是 wishId（wishId 必须带 wish- 头）", c?.wishId)
        assertEquals(listOf("S1", "S3"), c?.ids)
    }

    @Test
    fun 不像确认的话不解析() {
        assertNull(WishTrigger.confirmRequest("许愿确认一下明天天气"))
        assertNull(WishTrigger.confirmRequest("确认收货"))
        assertNull(WishTrigger.confirmRequest("许愿确认"))
    }

    // ── 生产调用点（按源码钉：控制器类不上 JVM，接没接看源码＋真机） ────────

    @Test
    fun 生产调用点钉在send壳层() {
        val rel = "src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt"
        val text = repoFile(rel).readText()
        assertTrue(
            "send() 里要有许愿拦截判定（唯一喂入口）",
            text.contains("WishFlow.intercepts(text)"),
        )
        assertTrue(
            "拦截后要真进许愿流（onUserSend）",
            text.contains("WishFlow.onUserSend(appCtx, text)"),
        )
    }

    private fun repoFile(rel: String): File = File(rel).let { if (it.isFile) it else File("app/$rel") }
}
