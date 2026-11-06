// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百批 · 判态结构性改造的**中文侧零回归**门禁（派工单 500 判据④，也是双跑的中文一轮）。
//
// 为什么中文要单独一份：本单动的是结构，而结构改造的风险全在「顺手把中文判定也改了」。
// 中文四步（INTENT／WRAPUP／CLAIM／CLAIM_RE）与它们的先后顺序必须与 253/258 逐字同结论，
// 所以这里钉三件事：
//   ① 中文任务／闲聊语料（与 constitution/L3 探针同源语料）双向全过；
//   ② 中文表里不许混进拉丁字母——英文的处置一律走英文层，不往中文表贴词；
//   ③ 中文优先：混合句里中文的执行意图／收尾先命中，英文白名单与词边界不参与。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskModeStructuralChineseGateTest {

    /** 与 constitution/L3NoEmptyApologyGateTest 同一批中文任务语料（法典 §3.9.0 的正例）。 */
    private val chineseTasks = listOf(
        "帮我把第3行改成42",
        "这表就是错的，第3行应该是42",
        "报错了，log.txt 里有堆栈",
        "先这样，今天先收尾",
        "打开设置页",
        "把这个删掉",
        "重新跑一下",
        "日志里说超时了",
    )

    /** 与 L3 探针同一批中文闲聊语料（法典 §3.9.0 的反例；态判错则禁令全废）。 */
    private val chineseChats = listOf(
        "小笨蛋～今天也要加油哦！",
        "你是不是傻哈哈哈，逗你的",
        "笑死我了，这家店太难吃了吧",
        "好烦啊，不想动，就想跟你贫两句",
        "你说得对！",
        "哎呀我错了，哈哈",
    )

    @Test
    fun `判据4_中文任务语料全部仍判TASK`() {
        chineseTasks.forEach { s ->
            assertEquals("中文任务被英文层拖偏：$s", TaskMode.TASK, TaskModeBoundary.classify(s))
        }
    }

    @Test
    fun `判据4_中文闲聊语料全部仍判CHAT`() {
        chineseChats.forEach { s ->
            assertEquals("中文闲聊被误伤成任务态：$s", TaskMode.CHAT, TaskModeBoundary.classify(s))
        }
    }

    @Test
    fun `判据4_中文自责客套仍不进事实主张`() {
        listOf("哎呀我错了，哈哈", "我的错，别生气", "对不起嘛，逗你玩呢").forEach { s ->
            assertEquals("闲聊里的道歉被判成任务态：$s", TaskMode.CHAT, TaskModeBoundary.classify(s))
        }
    }

    @Test
    fun `判据4_混合态逐句判定未变`() {
        val mixed = "哈哈哈你真逗。这表第3行就是错的"
        val modes = TaskModeBoundary.classifySentences(mixed)
        assertEquals("分句数变了（句界切法被改动）", 2, modes.size)
        assertEquals("第一句是纯闲聊", TaskMode.CHAT, modes[0].second)
        assertEquals("第二句是事实主张", TaskMode.TASK, modes[1].second)
        assertTrue("混合态判不出来", TaskModeBoundary.isMixed(mixed))
    }

    @Test
    fun `判据4_中文表里没混进拉丁字母`() {
        val src = PolicyPaths.read("MediatorJudge.kt")
        // 锚点必须吃到 `listOf(`：只切 `= ` 会把 listOf 自己算进块里，拉丁字母就来自括号前的函数名。
        listOf("INTENT", "WRAPUP").forEach { name ->
            val block = src.substringAfter("private val $name = listOf(").substringBefore(")")
            val latin = Regex("[A-Za-z]").findAll(block).map { it.value }.toList()
            assertTrue("中文表 $name 被贴进了英文词（应走英文层）：$latin", latin.isEmpty())
        }
        val claim = src.substringAfter("private val CLAIM = listOf(").substringBefore(")")
        assertTrue(
            "中文 CLAIM 表被贴进了英文词",
            Regex("[A-Za-z]").findAll(claim).map { it.value }.toList().isEmpty(),
        )
        // 闲聊表同理：500 不动 PURE_EMOTION（493 曾往里贴英文，那是数据层权宜）。
        val emo = src.substringAfter("private val PURE_EMOTION = listOf(").substringBefore(")")
        assertTrue("PURE_EMOTION 被贴进了英文词", Regex("[A-Za-z]").findAll(emo).map { it.value }.toList().isEmpty())
    }

    @Test
    fun `判据4_中文优先于英文层`() {
        // 中文①③先命中 ⇒ 英文白名单（sounds good／no worries）与词边界都不参与判定。
        listOf(
            "帮我看看，I am open to ideas",
            "先收尾，no worries",
            "打开它，good to go",
        ).forEach { s ->
            assertEquals("中文意图被英文白名单吞掉：$s", TaskMode.TASK, TaskModeBoundary.classify(s))
        }
    }

    @Test
    fun `判据4_数字与文件名主张仍先于英文层`() {
        // CLAIM_RE 的「出现数字即事实主张」是既有口径，本单没动它，顺序也没动。
        listOf("run 2 tests", "check the 3 files").forEach { s ->
            assertEquals("数字主张被英文层的请求形态要求挡掉了：$s", TaskMode.TASK, TaskModeBoundary.classify(s))
        }
        assertEquals("空串仍判闲聊", TaskMode.CHAT, TaskModeBoundary.classify(""))
        assertEquals("纯空白仍判闲聊", TaskMode.CHAT, TaskModeBoundary.classify("   "))
        assertFalse("空串被判成任务态", TaskModeBoundary.isMixed(""))
    }

    @Test
    fun `同源_探针中文语料与生产同结论`() {
        (chineseTasks + chineseChats).forEach { s ->
            assertEquals(
                "探针与生产判出两个结论（两表已分叉）：$s",
                com.example.zhengdao.baopu.TaskModeBoundary.classify(s),
                com.example.zhengdao.baopu.constitution.TaskModeBoundary.classify(s),
            )
        }
    }
}
