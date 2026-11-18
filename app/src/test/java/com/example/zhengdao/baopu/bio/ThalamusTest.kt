// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百七十五批 · 丘脑单测：四路路由可达＋不丢弃＋新异同表＋确定性（判据③④）。
// "只中继不解读"钉法：断言只看 route/novelty/note 三样，任何输出里没有裁量字段。
package com.example.zhengdao.baopu.bio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThalamusTest {

    private fun relay(text: String, words: DangerWords = DangerWords.DEFAULT) =
        Thalamus.relay(BioEvent(text = text, source = "test"), words)

    // ── 四路都可达，优先级 危险词 > 工具样式 > 记忆标记 > 直答 ──

    @Test
    fun routesCoverAllFourDownstreams() {
        assertEquals(Thalamus.Route.GUARD, relay("帮我删除这个文件").route) // 危险词 → 门禁门口
        assertEquals(Thalamus.Route.TOOLS, relay("打开 /data/local/tmp 看看").route) // 路径样式 → 工具
        assertEquals(Thalamus.Route.MEMORY, relay("上次说的那个方案").route) // 记忆标记 → 记忆
        assertEquals(Thalamus.Route.DIRECT, relay("今天真开心").route) // 判不了 → 直答
    }

    @Test
    fun guardBeatsToolShapeAndNothingIsDropped() {
        // 同事件既有危险词又有工具样式：先送门禁门口（送，不拦）。
        assertEquals(Thalamus.Route.GUARD, relay("删除 /tmp/x").route)
        // 不丢弃事件：再怪的输入也有出路（穷尽 when，没有"扔了"分支）。
        assertEquals(Thalamus.Route.DIRECT, relay("").route)
        assertEquals(Thalamus.Route.DIRECT, relay("​零宽字符也能走").route)
    }

    // ── 新异读数与杏仁核同表：丘脑不另立口径（读只读函数，一套分档两处用） ──

    @Test
    fun noveltyMatchesAmygdalaRubric() {
        val words = DangerWords.DEFAULT
        for (text in listOf("这是什么？", "看看 ://demo", "今天真开心", "帮我删除文件")) {
            val r = relay(text, words)
            assertEquals(Amygdala.noveltyOf(text, words), r.novelty)
        }
        assertEquals(1, relay("帮我删除这个文件").novelty) // 危险词送门禁，但新异照样如实打
    }

    // ── 输出只有 {route, novelty, note}：note 只说送到哪，不带处置话 ──

    @Test
    fun noteSaysWhereNotWhatToDo() {
        val r = relay("帮我删除这个文件")
        assertTrue(r.note.contains("门禁"))
        assertTrue(r.note.contains("由下游定")) // 解读不是本件的活——话里明说
    }

    // ── 确定性（判据④）：同输入两次中继逐字一致 ──

    @Test
    fun sameInputSameRelayTwice() {
        val e = BioEvent(text = "打开 /data/local/tmp", source = "chat")
        assertEquals(Thalamus.relay(e), Thalamus.relay(e))
    }

    // ── 523 三件套①：有它＝同一输入出"路由＋新异"读数；没它＝事件没有这个读数面 ──

    @Test
    fun triple1SameInputYieldsRouteReadingOnlyWithOrgan() {
        val r = relay("删除 /tmp/x")
        assertEquals(Thalamus.Route.GUARD, r.route) // 装了它：事件有了去处（机械路由标签）
        assertTrue(r.novelty in 0..2) // 读数面三样齐（route/novelty/note）——没它就没有这个面
        assertTrue(r.note.contains("门禁"))
    }

    // ── 523 三件套③：词表缝失效（给空词表）⇒ 退回无词表的中继，不拦不丢 ──

    @Test
    fun triple3WordSeamFailureFallsBackToShapeOnly() {
        val blind = DangerWords(danger = emptySet(), negation = emptySet(), noveltyMarks = emptySet())
        // 词信号缺失 ⇒ 不再送门禁门口（与没有词信号的现状一致）；事件不丢——照走直答。
        assertEquals(Thalamus.Route.DIRECT, relay("帮我删除这个文件", blind).route)
        // 形状信号不依赖词表：路径样式照样中继（中继失败退直接分发的兜底口径）。
        assertEquals(Thalamus.Route.TOOLS, relay("打开 /tmp/x", blind).route)
    }
}
