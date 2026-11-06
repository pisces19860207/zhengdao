// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百九十三批 · 判态英文词表的门禁（派工单 493 判据 ①–④）。
//
// 为什么语料要成对：只测「十条都判 TASK」是恒绿温床——万一 classify 被改成
// 无条件返回 TASK，这十条照样全绿。所以每条任务句都配一条「只把动作词换成中性词」
// 的同形对照句，必须落 CHAT；英文闲聊形态词另配「闲聊词＋任务动词」负例，
// 证明它们没有变成「命中即闲聊」的短路。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.askAllowedForTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TaskModeEnglishGateTest {

    /** 判据①：十条英文任务，**不含任何数字**（也不含会被文件名正则命中的点号）。 */
    private val englishTasks = listOf(
        "Create a folder for the notes",
        "Can you delete the old draft",
        "Write a short summary of the meeting",
        "I need you to run the unit tests",
        "Fix the broken link in the header",
        "Could you check the header alignment",
        "Translate this paragraph into plain English",
        "Kindly summarize the meeting notes",
        "Search for the missing receipt",
        "Open the settings panel and send the report",
    )

    /** 与上表逐条对应：只把动作词换成中性 "see"／删掉句首动词，其余尽量不动。 */
    private val taskControls = listOf(
        "A folder for the notes",
        "Can you see the old draft",
        "A short summary of the meeting",
        "I need you to see the unit tests",
        "The broken link in the header",
        "Could you see the header alignment",
        "This paragraph into plain English",
        "Kindly see the meeting notes",
        "For the missing receipt",
        "The settings panel and the report",
    )

    /** 判据②：十条英文闲聊，不含数字、文件名、任何动作词。 */
    private val englishChats = listOf(
        "thanks, that was sweet",
        "lol that is so funny",
        "good night, sleep well",
        "nvm, forget I said anything",
        "haha you always make me laugh",
        "that joke never gets old",
        "I am just happy to chat for a bit",
        "what a lovely afternoon",
        "you are such a dear friend",
        "oh stop it, you are too kind",
    )

    /** 派工单点名的四个英文闲聊形态词（只作回归对照，不参与判定）。 */
    private val enChatShapes = listOf("thanks", "lol", "good night", "nvm")

    // ── ① 英文任务 ─────────────────────────────────────────────────────────

    @Test
    fun `英文任务十条都不含数字_语料本身合格`() {
        assertEquals("语料条数", 10, englishTasks.size)
        englishTasks.forEach { s ->
            assertFalse("任务句里混进了数字（判据要求不含任何数字）：$s", s.any { it.isDigit() })
            assertFalse("任务句里混进了文件名点号：$s", s.contains('.'))
        }
    }

    @Test
    fun `判据1_英文任务十条全判TASK`() {
        englishTasks.forEach { s ->
            assertEquals("原文：$s", TaskMode.TASK, TaskModeBoundary.classify(s))
        }
    }

    @Test
    fun `负例_去掉动作词的同形对照十条全判CHAT`() {
        assertEquals("对照条数要与任务条数相同", englishTasks.size, taskControls.size)
        taskControls.forEach { s ->
            assertEquals("对照原文：$s", TaskMode.CHAT, TaskModeBoundary.classify(s))
        }
    }

    // ── ② 英文闲聊 ─────────────────────────────────────────────────────────

    @Test
    fun `判据2_英文闲聊十条全判CHAT`() {
        assertEquals("语料条数", 10, englishChats.size)
        englishChats.forEach { s ->
            assertEquals("原文：$s", TaskMode.CHAT, TaskModeBoundary.classify(s))
        }
    }

    @Test
    fun `英文闲聊形态词只作对照_命中也不短路成CHAT`() {
        val lex = TaskModeBoundary.emotionLexicon()
        enChatShapes.forEach { w ->
            assertTrue("闲聊形态词 $w 不在 emotionLexicon() 里", w in lex)
            assertEquals("$w 单独出现应落 CHAT", TaskMode.CHAT, TaskModeBoundary.classify(w))
        }
        listOf(
            "thanks, but fix the broken link",
            "lol, create a folder for me",
            "good night, but run the unit tests",
            "nvm, send the report",
        ).forEach { s ->
            assertEquals("命中闲聊词也判闲聊＝短路了：$s", TaskMode.TASK, TaskModeBoundary.classify(s))
        }
    }

    @Test
    fun `负例_英文自责客套不进任务态`() {
        // 500 之前这条靠的是「词表里干脆不收 was wrong」。**Q5 拍板（2026-11-06）**把口径改成
        // 按主语：was／were wrong 进了主张表（与 is／are wrong 同时态不分），但主语是"我"的自责
        // 先抹掉再判——这三条落 CHAT 的理由换了，断言一个字没动，中文侧「哎呀我错了」同口径。
        listOf("I was wrong, sorry", "Sorry, I was wrong", "my bad, I was wrong").forEach { s ->
            assertEquals("自责客套被判成任务态：$s", TaskMode.CHAT, TaskModeBoundary.classify(s))
        }
    }

    // ── ③ 中文既有语料零回归（走**生产** :policy 判据，不是测试侧 253 副本）───

    @Test
    fun `判据3_中文既有语料零回归`() {
        listOf(
            "小笨蛋～今天也要加油哦！",
            "你是不是傻哈哈哈，逗你的",
            "笑死我了，这家店太难吃了吧",
            "好烦啊，不想动，就想跟你贫两句",
            "你说得对！",
            "哎呀我错了，哈哈",
        ).forEach { s -> assertEquals("中文闲聊原文：$s", TaskMode.CHAT, TaskModeBoundary.classify(s)) }
        listOf(
            "帮我把第3行改成42",
            "这表就是错的，第3行应该是42",
            "报错了，log.txt 里有堆栈",
            "先这样，今天先收尾",
        ).forEach { s -> assertEquals("中文任务原文：$s", TaskMode.TASK, TaskModeBoundary.classify(s)) }
    }

    // ── ④ 弹卡闸联动 ──────────────────────────────────────────────────────

    @Test
    fun `判据4_英文任务可立单可弹卡_英文闲聊都不`() {
        englishTasks.forEach { s ->
            assertEquals("立单判据：$s", TaskMode.TASK, TaskModeBoundary.classify(s))
            assertTrue("弹卡闸该放行：$s", askAllowedForTurn(s))
        }
        englishChats.forEach { s ->
            assertEquals("不立单判据：$s", TaskMode.CHAT, TaskModeBoundary.classify(s))
            assertFalse("弹卡闸该拦住：$s", askAllowedForTurn(s))
        }
    }

    @Test
    fun `判据4_起单与弹卡共用同一判据_源码锚点还在`() {
        val rel = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"
        val engine = File(rel).let { if (it.isFile) it else File("app/$rel") }
        assertTrue("找不到引擎源码（${engine.absolutePath}）", engine.isFile)
        val src = engine.readText(Charsets.UTF_8)
        assertTrue("起单处没有判态闸门", src.contains("TaskModeBoundary.classify(it) == TaskMode.TASK"))
        assertTrue("弹卡闸没走同一个判定函数", src.contains("askAllowedForTurn(turnText)"))
    }

    // ── ⑤ 结构边界：中文四步一字未改 ────────────────────────────────────────

    @Test
    fun `边界_classify分支顺序未改且没有长度阈值`() {
        val src = PolicyPaths.read("MediatorJudge.kt")
        assertTrue("找不到 classify", src.contains("fun classify(text: String): TaskMode {"))
        val body = src.substringAfter("fun classify(text: String): TaskMode {")
            .substringBefore("\n    }")
        // 只钉四个 any 的相对顺序：`return TaskMode.CHAT` 在 `isEmpty()` 那行还有一次
        // 提前返回，用 indexOf 会抓错位置（实测 [56,90,155,344,417] vs [90,...,56]）。
        val order = listOf("INTENT.any", "WRAPUP.any", "CLAIM.any", "CLAIM_RE.any")
        val positions = order.map { needle ->
            val p = body.indexOf(needle)
            assertTrue("classify 里少了 $needle（分支被改）", p >= 0)
            p
        }
        assertEquals("classify 分支顺序被改动", positions.sorted(), positions)
        // 500 之前这里钉的是「classify 末尾必须裸返回 CHAT」——那是 493「本单不动结构」的围栏。
        // 改动授权来自 **2026-11-06 用户 Q3 拍板**（500 交付报告 §三）：500 正是被授权动结构的那一批，
        // 中文四步判不出来才进英文层，所以这一跳改成钉「末尾是英文层」，保守方向没丢
        // （英文层判不出来照样落 CHAT，见 TaskModeStructuralEnglishGateTest）。本文件其余十条行为判据一条没减。
        assertTrue("classify 末尾少了『进英文层』这一跳", body.trim().endsWith("return classifyEnglish(s)"))
        assertFalse("不许加长度阈值", body.contains(".length"))
    }

    // ── ⑥ WRAPUP / CLAIM 英文专项 ─────────────────────────────────────────

    @Test
    fun `英文收尾与英文事实主张专项`() {
        listOf("It is done", "Migration is finished", "The patch is shipped").forEach { s ->
            assertEquals("收尾原文：$s", TaskMode.TASK, TaskModeBoundary.classify(s))
        }
        listOf("the result is off", "in fact it failed", "the docs say otherwise").forEach { s ->
            assertEquals("主张原文：$s", TaskMode.TASK, TaskModeBoundary.classify(s))
        }
        assertEquals("空串仍判闲聊", TaskMode.CHAT, TaskModeBoundary.classify(""))
        assertEquals("纯空白仍判闲聊", TaskMode.CHAT, TaskModeBoundary.classify("   "))
    }
}
