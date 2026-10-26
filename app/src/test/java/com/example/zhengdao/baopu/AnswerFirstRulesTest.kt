// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百一十三批 · 答话纪律 v2「答案先行」的判据层。
//
// 四层各管一件事（口径来自派工单 §2 与用户 2026-10-25 的原话
// 「不用看模型那一堆废话，还要在模型那堆废话里找答案」）：
//   1) 提示层——十条纪律真的写进了 A 内核（缺一条就红，防「改了个注释」式交差）；
//   2) 对拍层——10 题改前/改后：改后首行**逐字等于**答案（assertEquals，不是 assertContains）；
//   3) 一致层——提示里点名的禁词，后处理必须逐条兜住（派工单负例「提示里写了但后处理没兜住」）；
//   4) 不误伤层——代码块／表格／列表／引用／数据围栏原样，数字·路径·引用只增不减。
//
// 推断词表**不另起一份**：与 212 批 `ResultCardBody.PREDICTION_WORDS` 比集合，
// 谁将来加词只能改那一处（212 文件头就是这么写的：调用方不许再抄）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.DataFence
import com.example.zhengdao.baopu.cards.BodyViolation
import com.example.zhengdao.baopu.cards.CardGroup
import com.example.zhengdao.baopu.cards.CardRow
import com.example.zhengdao.baopu.cards.ResultCardBody
import com.example.zhengdao.baopu.cards.ResultCardSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerFirstRulesTest {

    private val persona: String get() = BaopuPreset.personaText("工作区")

    private fun String.occurrences(needle: String): Int =
        Regex(Regex.escape(needle)).findAll(this).count()

    // ── 1. 提示层：十条纪律在 A 内核里 ─────────────────────────────────
    @Test
    fun `提示层_输出纪律一节与十条标题全在`() {
        val need = listOf(
            "## 输出纪律（答案先行）",
            "第一句就是答案", "不复述问题", "不自我说明", "免责与建议不堆叠",
            "不许推断词", "能一行答完就一行", "卡里有的正文不再念一遍", "不确定就一句反问",
            "成卡与展开的分工", "创作不算例外",
        )
        val missing = need.filter { !persona.contains(it) }
        assertEquals("A 内核缺这些条文：$missing", emptyList<String>(), missing)
        assertTrue(
            "没有「未确认／未填」这个出口，推断词禁令就是空话",
            persona.contains("未确认") && persona.contains("未填"),
        )
    }

    @Test
    fun `提示层_新增一节不许吃掉旧纪律`() {
        // 212／145／59 批钉过的口径不能因为追加一节而丢
        listOf("计划有变就在原表上改", "别报工具名", "preview_local_file", "工具是按需给你的").forEach {
            assertTrue("丢了既有口径：$it", persona.contains(it))
        }
        assertEquals(
            "「## 你会做什么」只能有一处（ProgressiveTools 用 substringAfter 定位）",
            1,
            persona.occurrences("## 你会做什么"),
        )
        // 追加的节不许排在「## 纪律」之前——145 批那条口径一直靠这个顺序读得通
        assertTrue(
            "输出纪律一节必须排在「## 纪律」之后",
            persona.indexOf("## 纪律") < persona.indexOf("## 输出纪律（答案先行）"),
        )
    }

    @Test
    fun `提示层_加长之后A内核仍不超人设token上限`() {
        val tokens = TokenEstimator.estimate(persona)
        assertTrue(
            "A 内核被 clip 截断＝模型看不全纪律（tokens=$tokens，上限 ${PersonaRules.PERSONA_TOKEN_LIMIT}）",
            tokens <= PersonaRules.PERSONA_TOKEN_LIMIT,
        )
    }

    // ── 2. 对拍层：10 题改前 → 改后首行逐字＝答案 ──────────────────────
    private data class Shot(val q: String, val before: String, val firstLine: String, val deleted: Int)

    private val shots = listOf(
        Shot(
            "这个包裹到哪了",
            "好的，我来帮你查一下。物流显示已签收，10 月 24 日 14:20 由门卫代收。",
            "物流显示已签收，10 月 24 日 14:20 由门卫代收。",
            11,
        ),
        Shot(
            "资料库里有多少本书",
            "根据你提供的信息，我帮你查了一下。共 12 本，都在 资料库/整理/ 下面。",
            "共 12 本，都在 资料库/整理/ 下面。",
            17,
        ),
        Shot(
            "这张卡的 world_book 字段叫什么",
            "以下是我的回答：顶层键是 `world_book`，条目键是 `entries`。",
            "顶层键是 `world_book`，条目键是 `entries`。",
            8,
        ),
        Shot(
            "图片转成 PNG 存哪了",
            "抱歉让你久等了。已经转好了，存在 图片/封面.png。",
            "已经转好了，存在 图片/封面.png。",
            8,
        ),
        Shot(
            "这个月写了多少字",
            "我帮你查一下。本月共 43,210 字。",
            "本月共 43,210 字。",
            7,
        ),
        Shot(
            "这条技能转正吗",
            "关于这个问题，它已经用了 4 次，够转正了。现在转正吗？",
            "它已经用了 4 次，够转正了。现在转正吗？",
            7,
        ),
        Shot(
            "把这段转成 GBK",
            "好的，我这就处理。已经存为 稿件/第三章.gbk.txt。",
            "已经存为 稿件/第三章.gbk.txt。",
            9,
        ),
        Shot(
            "定时任务设好了吗",
            "我先说明一下。设好了，明早 7:30 通知你。",
            "设好了，明早 7:30 通知你。",
            7,
        ),
        Shot(
            "这个页面的脚本能跑吗",
            "作为 AI，我不能替你执行。本地预览能看排版，页面里的脚本默认不跑。",
            "我不能替你执行。本地预览能看排版，页面里的脚本默认不跑。",
            6,
        ),
        Shot(
            "有几条要说明",
            "好的，嗯，收到。一共 3 条。",
            "一共 3 条。",
            8,
        ),
    )

    @Test
    fun `对拍层_十题改后首行逐字等于答案`() {
        val red = mutableListOf<String>()
        for (s in shots) {
            val after = AnswerFirstRules.tidy(s.before)
            val got = after.split("\n").first()
            if (got != s.firstLine) red += "${s.q}｜首行=「$got」期望「${s.firstLine}」"
            val cut = s.before.length - after.length
            if (cut != s.deleted) red += "${s.q}｜删了 $cut 字，期望 ${s.deleted} 字"
        }
        assertEquals("对拍红：\n" + red.joinToString("\n"), emptyList<String>(), red)
    }

    @Test
    fun `对拍层_改后首行不含开场白与推断词`() {
        val banned = listOf("好的", "我帮你", "根据你提供", "以下是", "我来看看", "先说明一下", "作为 AI")
        val red = mutableListOf<String>()
        for (s in shots) {
            val first = AnswerFirstRules.tidy(s.before).split("\n").first()
            banned.filter { first.startsWith(it) }.forEach { red += "${s.q}｜首行以铺垫「$it」开头" }
            ResultCardBody.PREDICTION_WORDS.filter { first.contains(it) }
                .forEach { red += "${s.q}｜首行有推断词「$it」" }
        }
        assertEquals("判据红：\n${red.joinToString("\n")}", emptyList<String>(), red)
    }

    @Test
    fun `对拍层_事实保全_数字路径引用一个不少`() {
        fun facts(text: String): Set<String> =
            Regex("""\d[\d.,]*""").findAll(text).map { it.value }.toSet() +
                Regex("""[^\s]*[/\\.][^\s]*""").findAll(text).map { it.value }.toSet() +
                Regex("""`[^`]+`""").findAll(text).map { it.value }.toSet()
        // 三条额外的「铺垫里就带事实」样本：内容闸必须把它们整句留下
        val extra = listOf(
            "数字写在铺垫同一句" to "好的，一共 3 个文件。已签收。",
            "路径写在铺垫同一句" to "我查了一下，在 稿件/第三章.txt。",
            "代码段紧跟铺垫" to "好的，看这里：\n`稿件/第三章.txt` 共 43,210 字。",
        )
        val red = mutableListOf<String>()
        for ((q, raw) in shots.map { it.q to it.before } + extra) {
            val lost = facts(raw) - facts(AnswerFirstRules.tidy(raw))
            if (lost.isNotEmpty()) red += "$q｜丢了事实 $lost"
        }
        assertEquals(
            "负例⑥红（后处理把数字/路径/引用删掉）：\n${red.joinToString("\n")}",
            emptyList<String>(),
            red,
        )
    }

    // ── 3. 一致层：提示点名的禁词，后处理逐条兜住 ───────────────────────
    @Test
    fun `一致层_提示禁词表与后处理词表同一批`() {
        val line = persona.substringAfter("## 输出纪律（答案先行）")
            .lines().first { it.contains("首句不许出现这些铺垫") }
        val banned = Regex("""「([^」]+)」""").findAll(line).map { it.groupValues[1] }.toList()
        assertTrue("提示里至少要点名 10 条开场白，实际 ${banned.size} 条", banned.size >= 10)
        val miss = banned.filter { !AnswerFirstRules.isPreamble("$it，") }
        assertEquals("提示写了但后处理没兜住：$miss", emptyList<String>(), miss)
    }

    @Test
    fun `一致层_推断词与二百一十二批同一份词表`() {
        val line = persona.substringAfter("## 输出纪律（答案先行）")
            .lines().first { it.contains("不许推断词") }
            .trimStart('-', ' ')
        val words = line.substringAfter("：").substringBefore("。").split("／")
        assertTrue("提示里至少点名 6 个推断词，实际 ${words.size}", words.size >= 6)
        val outside = words.filter { it !in ResultCardBody.PREDICTION_WORDS }
        assertEquals(
            "另起了一套推断词表（212 已有 PREDICTION_WORDS）：$outside",
            emptyList<String>(),
            outside,
        )
    }

    // ── 4. 不误伤层：结构与整条护栏 ────────────────────────────────────
    @Test
    fun `不误伤层_代码块首行不是开场白`() {
        val raw = "好的，给你一段代码。\n```kotlin\nval total = items.sumOf { it.count }\n```\n上面这行就是求和。"
        val after = AnswerFirstRules.tidy(raw)
        assertTrue(
            "代码围栏被切到了：" + after.lines().joinToString("|") { it.take(6) },
            after.contains("```kotlin\nval total = items.sumOf { it.count }\n```"),
        )
        assertEquals("删掉的只该是「好的，」三个字", 3, raw.length - after.length)
        assertFalse(AnswerFirstRules.isPreamble("```kotlin"))
        assertFalse(AnswerFirstRules.isPreamble("val total = items.sumOf { it.count }"))
    }

    @Test
    fun `不误伤层_表格整体不切`() {
        val table = "| 单号 | 状态 |\n|---|---|\n| SF123 | 已签收 |"
        assertEquals("铺垫之外的字节一个没动", table, AnswerFirstRules.tidy("我帮你查一下。\n$table"))
        assertEquals(table, AnswerFirstRules.tidy(table))
        assertFalse(AnswerFirstRules.isPreamble("| 单号 | 状态 |"))
    }

    @Test
    fun `不误伤层_列表与标题与引用块原样`() {
        listOf(
            "- 已签收（10/24）",
            "* 待取件",
            "1. 先看 资料库/原始/",
            "# 第一章",
            "> 原文：她转身走了，没有回头。",
            "| 合计 | 3 |",
        ).forEach {
            assertFalse("结构行被当铺垫：$it", AnswerFirstRules.isPreamble(it))
        }
        val raw = "以下是我的回答：\n- 已签收（10/24）\n- 待取件"
        assertEquals("- 已签收（10/24）\n- 待取件", AnswerFirstRules.tidy(raw))
    }

    @Test
    fun `不误伤层_数据围栏整条原样返回`() {
        val fence = "<tool_result name=\"search_in_files\" ts=\"17\">\n好的，3 个命中\n</tool_result>"
        assertEquals("回灌内容不许动", fence, AnswerFirstRules.tidy(fence))
        // 铺垫紧贴围栏：这一条**只有**围栏早退能救（开头那句自己就过了三道闸）
        val glued = "收到。<tool_result name=\"x\" ts=\"1\">\n3 个命中\n</tool_result>"
        assertEquals("围栏早退是承重的：拆掉它整条回灌会被改写", glued, AnswerFirstRules.tidy(glued))
    }

    @Test
    fun `围栏标记与 DataFence 同源`() {
        // 两边各存一份 "<tool_result" 就是漂移隐患：谁改了哪一侧，这条立刻红
        assertEquals(DataFence.FENCE_OPEN, AnswerFirstRules.FENCE_MARK)
        val feed = DataFence.wrap("search_in_files", 17L, "好的，收到。3 个命中")
        assertEquals("真产物的围栏必须原样退回", feed, AnswerFirstRules.tidy(feed))
    }

    @Test
    fun `不误伤层_幂等与空输入`() {
        shots.forEach { s ->
            val once = AnswerFirstRules.tidy(s.before)
            assertEquals("tidy 跑两遍结果必须一致：" + s.q, once, AnswerFirstRules.tidy(once))
        }
        listOf("", "   ", "\n\n").forEach { assertEquals("空白原样", it, AnswerFirstRules.tidy(it)) }
    }

    // ── 5. 负例（派工单 §3 六条 ＋ 用户点名三条） ───────────────────────
    @Test
    fun `负例一_首段铺垫必须被删掉`() {
        listOf(
            "我帮你查一下。已签收。",
            "让你久等了。已签收。",
            "抱歉。已签收。",
        ).forEach {
            val after = AnswerFirstRules.tidy(it)
            assertTrue("铺垫没删掉：$it → $after", after.startsWith("已签收。"))
        }
        // 反面前缀式规则的坑：「好的」开头但整句是真答案，不许削
        assertFalse("「好的建议是先补结尾」不是铺垫", AnswerFirstRules.isPreamble("好的建议是先补结尾。"))
    }

    @Test
    fun `负例二_复述问题开头判红`() {
        assertTrue(AnswerFirstRules.opensWithPreamble("关于这个问题，共 12 本。"))
        assertEquals("已签收。", AnswerFirstRules.tidy("你问的是。已签收。"))
        // 词表覆盖不到的复述（后半句是内容）⇒ 删不起，只能原样；这条由提示层管
        val keep = "你问的是物流吧，已签收。"
        assertFalse(AnswerFirstRules.isPreamble("你问的是物流吧，"))
        assertEquals(keep, AnswerFirstRules.tidy(keep))
    }

    @Test
    fun `负例三_推断词没标未确认判红`() {
        listOf("大概 30 本", "可能是明天", "应该在 资料库/ 里").forEach {
            assertTrue("这句确实含推断词：$it", ResultCardBody.PREDICTION_WORDS.any { w -> it.contains(w) })
        }
        // 写了「未确认」就不是糊弄——出口口径与 212 的「未填」一致
        val honest = "未确认，得看一眼页面。"
        assertFalse("「未确认」那句不许被算作推断词", ResultCardBody.PREDICTION_WORDS.any { honest.contains(it) })
        // 推断词是 227 批 AnswerGate 的活，本层不许动它（含数字的一句原样退回）
        val guess = "大概 30 本，未确认。"
        assertEquals(guess, AnswerFirstRules.tidy(guess))
    }

    @Test
    fun `负例四_正文复述卡里的数字判红`() {
        val card = ResultCardSpec(
            templateId = "test.answer.first",
            title = "资料库",
            groups = listOf(CardGroup("g", "统计", listOf(CardRow("books", "书本", "12 本")))),
            actions = emptyList(),
        )
        assertTrue(
            "正文复述卡里的值必须红",
            ResultCardBody.violations("一共 12 本，见卡", card).contains(BodyViolation.REPEATS_CARD),
        )
        val anchor = ResultCardBody.violations("查到 1 条，见卡", card)
        assertEquals("锚点那一行不该报任何违规：$anchor", emptyList<BodyViolation>(), anchor)
    }

    @Test
    fun `负例五_不确定就一句反问_不许猜`() {
        val ask = "查哪个方向？"
        assertEquals("反问句一个字都不许删", ask, AnswerFirstRules.tidy(ask))
        assertFalse(AnswerFirstRules.isPreamble(ask))
        // 「先猜一个答案充数」不在后处理的职权里（它只删不含事实的半句）——这条由提示层钉住
        assertTrue(persona.contains("更不许先猜一个答案充数"))
    }

    @Test
    fun `负例六_只删铺垫句_不改事实不改数字`() {
        val raw = "好的，我看了下：共 43,210 字，出处《第三章》，路径 稿件/第三章.txt。"
        val after = AnswerFirstRules.tidy(raw)
        listOf("43,210", "《第三章》", "稿件/第三章.txt").forEach {
            assertTrue("少了「$it」＝改了事实：$after", after.contains(it))
        }
        assertTrue("删掉的只该是开头铺垫", raw.length > after.length)
        assertEquals("铺垫之后的字节必须完全一致", raw.substring(8), after)
    }

    @Test
    fun `负例七_连排灌水只删前五句_字数闸是硬顶`() {
        val raw = "好的，".repeat(6) + "一共 3 条。"
        val after = AnswerFirstRules.tidy(raw)
        assertEquals(
            "开头连排 6 句只删前 5 句（MAX_PREAMBLE_UNITS=${AnswerFirstRules.MAX_PREAMBLE_UNITS}），其余原样",
            "好的，一共 3 条。",
            after,
        )
        assertEquals(15, raw.length - after.length)
        // 构造样本钉字数硬顶（真实回答不长这样）：某个铺垫句结束在下标 >100 处 ⇒ 不再往后删
        val pad = "好的，我帮你查一下" + " ".repeat(100) + "已签收。"
        assertEquals(
            "第二句拖过 MAX_PREAMBLE_CHARS ⇒ 只删第一句「好的，」",
            "我帮你查一下" + " ".repeat(100) + "已签收。",
            AnswerFirstRules.tidy(pad),
        )
    }

    @Test
    fun `负例八_答案就是两个字时不许删空`() {
        listOf("好的，我帮你查一下。", "收到。", "明白了。").forEach {
            assertEquals("整条都是铺垫时不许返回空串：$it", it, AnswerFirstRules.tidy(it))
        }
    }

    // ── 6. 词表边界：这些词**故意**不收 ────────────────────────────────
    @Test
    fun `词表边界_是否类答案不当铺垫`() {
        listOf("可以。", "没问题。", "当然。", "是的。", "对。", "行。").forEach {
            assertFalse("$it 常常就是答案本身", AnswerFirstRules.isPreamble(it))
        }
    }

    @Test
    fun `词表边界_带任务的复述不删`() {
        val raw = "我帮你查一下物流到哪了。已签收。"
        assertEquals("这句含「物流到哪了」＝复述加过程，删不起，只能原样", raw, AnswerFirstRules.tidy(raw))
        assertTrue(persona.contains("不复述问题"))
    }

    @Test
    fun `上限口径_开头最多看五句且删字不超一百`() {
        assertEquals(5, AnswerFirstRules.MAX_PREAMBLE_UNITS)
        assertEquals(100, AnswerFirstRules.MAX_PREAMBLE_CHARS)
    }
}
