// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零三批（批量问卡）判据层：
// - 形状：questions 解析（kind 五档、options 2–4、id 唯一、default 对账）＋ 旧形状包 1 项；
// - 拆卡：一卡上限 5，6 ⇒ 4+2（派工单判据原话），任何规模都不许出现 6 项一张卡；
// - 就地校验：必答未填点名、NUMBER 非数字、答案不在选项里；
// - 执行体：问题组一条结果 `{"answers":{…}}`、形状不许混用、超 20 拒、没人答如实说；
// - 御批红线：授权/提案类就算被塞进 questions 也不走多问卡（负例 5 的第一道闸）。
package com.example.zhengdao.baopu.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskQuestionGroupTest {

    // ── 形状与解析 ──────────────────────────────────────────────────────────

    private fun q(
        id: String = "",
        title: String = "",
        kind: String = "",
        options: JSONArray? = null,
        required: Boolean = false,
        default: String = "",
    ): JSONObject {
        val o = JSONObject().put("title", title)
        if (kind.isNotEmpty()) o.put("kind", kind)
        if (id.isNotEmpty()) o.put("id", id)
        if (options != null) o.put("options", options)
        if (required) o.put("required", true)
        if (default.isNotEmpty()) o.put("default", default)
        return o
    }

    private fun opts(vararg pairs: Pair<String, String>): JSONArray = JSONArray().also { arr ->
        pairs.forEach { (id, label) -> arr.put(JSONObject().put("id", id).put("label", label)) }
    }

    private fun parse(vararg items: JSONObject) = AskUserTool.parseQuestions(JSONArray().also { arr ->
        items.forEach { arr.put(it) }
    })

    @Test
    fun `P1_三个疑问解析成三项_id缺省按序号补`() {
        val r = parse(
            q(title = "删哪些？", options = opts("keep" to "留着", "drop" to "删掉")),
            q(title = "什么时候删？", kind = "TEXT"),
            q(title = "删完要不要报告？", kind = "BOOL"),
        )
        assertTrue("合法批要过：$r", r is AskQuestionsParse.Ok)
        val list = (r as AskQuestionsParse.Ok).questions
        assertEquals(3, list.size)
        assertEquals("q1", list[0].id)
        assertEquals(AskQuestionKind.SINGLE, list[0].kind)
        assertEquals(AskQuestionKind.TEXT, list[1].kind)
        assertEquals(AskQuestionKind.BOOL, list[2].kind)
    }

    @Test
    fun `P2_kind认不出要报人话_空数组拒_title空拒`() {
        val bad = parse(q(title = "a", kind = "RADIO"))
        assertTrue("kind 不认识必须报：$bad", bad is AskQuestionsParse.Bad)
        assertTrue((bad as AskQuestionsParse.Bad).reason.contains("SINGLE"))
        val empty = AskUserTool.parseQuestions(JSONArray())
        assertTrue("空 questions 必须拒：$empty", empty is AskQuestionsParse.Bad)
        val noTitle = parse(q(kind = "TEXT"))
        assertTrue("没 title 必须拒：$noTitle", noTitle is AskQuestionsParse.Bad)
    }

    @Test
    fun `P2b_kind缺省按形状定_给options算单选_没给算文本`() {
        val single = parse(q(title = "a", options = opts("x" to "甲", "y" to "乙")))
        assertTrue(single is AskQuestionsParse.Ok)
        assertEquals(AskQuestionKind.SINGLE, (single as AskQuestionsParse.Ok).questions[0].kind)
        val text = parse(q(title = "a"))
        assertTrue(text is AskQuestionsParse.Ok)
        assertEquals(AskQuestionKind.TEXT, (text as AskQuestionsParse.Ok).questions[0].kind)
    }

    @Test
    fun `P3_单选多选要2到4个选项_TEXT_BOOL不用给`() {
        val few = parse(q(title = "a", options = opts("x" to "只有一档")))
        assertTrue(few is AskQuestionsParse.Bad)
        val five = parse(q(title = "a", options = opts("a" to "1", "b" to "2", "c" to "3", "d" to "4", "e" to "5")))
        assertTrue("五个选项用户不看了：$five", five is AskQuestionsParse.Bad)
        val text = parse(q(title = "a", kind = "TEXT"))
        assertTrue("TEXT 不要 options：$text", text is AskQuestionsParse.Ok)
    }

    @Test
    fun `N1a_重复id直接拒_答案串位从源头掐掉`() {
        val r = parse(
            q(id = "keep", title = "第一问", options = opts("a" to "甲", "b" to "乙")),
            q(id = "keep", title = "第二问", options = opts("a" to "甲", "b" to "乙")),
        )
        assertTrue("重复 id 必须拒：$r", r is AskQuestionsParse.Bad)
        assertTrue((r as AskQuestionsParse.Bad).reason.contains("重复"))
    }

    @Test
    fun `P4_default要对得上账_不然卡上摆一个填不出的答案`() {
        val okSingle = parse(q(title = "a", options = opts("a" to "甲", "b" to "乙"), default = "b"))
        assertTrue(okSingle is AskQuestionsParse.Ok)
        val unknown = parse(q(title = "a", options = opts("a" to "甲", "b" to "乙"), default = "zzz"))
        assertTrue("SINGLE 默认值不在选项里必须拒：$unknown", unknown is AskQuestionsParse.Bad)
        val badNum = parse(q(title = "a", kind = "NUMBER", default = "abc"))
        assertTrue("NUMBER 默认值非数字必须拒：$badNum", badNum is AskQuestionsParse.Bad)
        val badBool = parse(q(title = "a", kind = "BOOL", default = "yes"))
        assertTrue("BOOL 默认值必须 true/false：$badBool", badBool is AskQuestionsParse.Bad)
        val okMulti = parse(
            q(title = "a", kind = "MULTI", options = opts("a" to "甲", "b" to "乙", "c" to "丙"), default = "a,c"),
        )
        assertTrue("MULTI 默认值逗号连接：$okMulti", okMulti is AskQuestionsParse.Ok)
    }

    @Test
    fun `B1_旧单问形状内部包成1项_老调用零改动`() {
        // 旧调用原样构造（第五参数之后的字段都有默认值 ⇒ 编译都不用动）
        val legacy = AskUserRequest("回收站怎么清？", listOf(AskUserOption("keep", "留着"), AskUserOption("drop", "删掉")))
        val wrapped = legacy.allQuestions
        assertEquals("旧形状＝1 项", 1, wrapped.size)
        assertEquals("回收站怎么清？", wrapped[0].title)
        assertEquals(AskQuestionKind.SINGLE, wrapped[0].kind)
        assertEquals(2, wrapped[0].options.size)
        assertTrue("旧形状是必答题（用户必须点一个）", wrapped[0].required)
        // 组形状原样透传，不再包
        val group = AskUserRequest(question = "", options = emptyList(), questions = listOf(AskQuestion("x", "题")))
        assertEquals(group.questions, group.allQuestions)
    }

    // ── 拆卡（负例 2 的判据本体） ────────────────────────────────────────────

    private fun batch(n: Int): List<AskQuestion> = (1..n).map { AskQuestion("q$it", "第 $it 问") }

    @Test
    fun `P5_三项一张卡_五项也一张`() {
        assertEquals(1, AskGroupRules.split(batch(3)).size)
        assertEquals(1, AskGroupRules.split(batch(AskUserTool.MAX_PER_CARD)).size)
        assertEquals(3, AskGroupRules.split(batch(3))[0].size)
    }

    @Test
    fun `N2_六项拆4加2_第一张提示还有2项`() {
        val cards = AskGroupRules.split(batch(6))
        assertEquals("6 项拆两张", 2, cards.size)
        assertEquals("第一张 4 项（派工单判据原话）", 4, cards[0].size)
        assertEquals("第二张 2 项", 2, cards[1].size)
        assertEquals(
            "第一张卡要提示还有几项",
            "另有 2 项，答完这张接着来",
            AskGroupRules.moreNote(6, cards[0].size),
        )
        assertEquals("单卡不提示", "", AskGroupRules.moreNote(3, 3))
    }

    @Test
    fun `N2b_任何规模都不许超5项一张卡_末张不许剩孤项`() {
        for (n in 1..40) {
            val cards = AskGroupRules.split(batch(n))
            cards.forEachIndexed { i, card ->
                assertTrue(
                    "n=$n 第 $i 张有 ${card.size} 项，超一卡上限",
                    card.size <= AskUserTool.MAX_PER_CARD,
                )
                assertTrue("n=$n 第 $i 张只剩 ${card.size} 项（孤项卡）", card.size >= 2 || cards.size == 1)
            }
            assertEquals("拆卡不许丢问题：n=$n", n, cards.sumOf { it.size })
            assertEquals("拆卡不许重排去重：n=$n", batch(n).map { it.id }, cards.flatten().map { it.id })
        }
        assertEquals(3, AskGroupRules.split(batch(11)).size)
        assertEquals(listOf(5, 4, 2), AskGroupRules.split(batch(11)).map { it.size })
    }

    // ── 就地校验（负例 3 的判据本体） ────────────────────────────────────────

    private val three: List<AskQuestion> = listOf(
        AskQuestion("q1", "删哪些？", AskQuestionKind.SINGLE, opts("keep" to "留着", "drop" to "删掉").let { arr ->
            (0 until arr.length()).map { AskUserOption(arr.optJSONObject(it).optString("id"), arr.optJSONObject(it).optString("label")) }
        }, required = true),
        AskQuestion("q2", "删到哪一层？", AskQuestionKind.NUMBER),
        AskQuestion("q3", "要不要报告？", AskQuestionKind.BOOL, required = true),
    )

    @Test
    fun `P6_全填了校验过_答案一条不缺`() {
        val ok = AskGroupRules.validate(three, mapOf("q1" to "drop", "q2" to "2", "q3" to "true"))
        assertEquals(AskGroupCheck.Ok, ok)
        val answers = AskGroupRules.draftAnswers(three, mapOf("q1" to "drop", "q2" to "2", "q3" to "true"))
        assertEquals(
            "3 个答案齐全、id 对应（正例判据）",
            mapOf("q1" to "drop", "q2" to "2", "q3" to "true"),
            answers,
        )
    }

    @Test
    fun `N3_必答没填要点名_提交会被挡`() {
        val check = AskGroupRules.validate(three, mapOf("q1" to "", "q3" to "true"))
        assertTrue("必答空必须 Invalid：$check", check is AskGroupCheck.Invalid)
        val problems = (check as AskGroupCheck.Invalid).problems
        assertEquals("只缺 q1 一项", 1, problems.size)
        assertTrue("要指出是哪项（title 点名）：$problems", problems[0].contains("删哪些？"))
        assertTrue("文案要说清是必答", problems[0].contains("必答"))
    }

    @Test
    fun `P7_数字项非数字就地拒绝_BOOL胡填也拒`() {
        val nan = AskGroupRules.validate(three, mapOf("q1" to "keep", "q2" to "两三层", "q3" to "true"))
        assertTrue("非数字必须拒：$nan", nan is AskGroupCheck.Invalid)
        assertTrue((nan as AskGroupCheck.Invalid).problems[0].contains("数字"))
        val badBool = AskGroupRules.validate(three, mapOf("q1" to "keep", "q2" to "3", "q3" to "maybe"))
        assertTrue("BOOL 只认 true/false：$badBool", badBool is AskGroupCheck.Invalid)
        val notInOptions = AskGroupRules.validate(three, mapOf("q1" to "zzz", "q2" to "3", "q3" to "true"))
        assertTrue("答案不在选项里要拒（串位/幻觉的第二道闸）：$notInOptions", notInOptions is AskGroupCheck.Invalid)
    }

    @Test
    fun `P8_按默认与都不改_可选空项退回默认`() {
        val withDefaults = listOf(
            AskQuestion("a", "留几个？", AskQuestionKind.NUMBER, default = "3"),
            AskQuestion("b", "要不要备份？", AskQuestionKind.BOOL, required = true, default = "true"),
            AskQuestion("c", "备注？", AskQuestionKind.TEXT),
        )
        // 「按默认」：整卡回到默认值
        assertEquals(
            mapOf("a" to "3", "b" to "true", "c" to ""),
            AskGroupRules.defaultAnswers(withDefaults),
        )
        // 用户只改了 a ⇒ b 退回默认 true（必答有默认 ⇒ 空着也能交）
        val answers = AskGroupRules.draftAnswers(withDefaults, mapOf("a" to "5"))
        assertEquals("5", answers["a"])
        assertEquals("true", answers["b"])
        assertEquals("没默认的 BOOL 退 false", "false", AskGroupRules.defaultAnswers(listOf(AskQuestion("x", "要吗？", AskQuestionKind.BOOL)))["x"])
    }

    @Test
    fun `N1b_答案合并按id对号入座_串位进不来`() {
        val chunkAnswers = listOf(
            mapOf("q1" to "drop", "q2" to "2", "q3" to "true", "q4" to ""),
            mapOf("q5" to "b", "q6" to "false"),
        )
        val merged = AskGroupRules.mergeAll(chunkAnswers)
        assertEquals("6 个 id 一个不缺", 6, merged.size)
        assertEquals("q1 的答案还是 q1 的", "drop", merged["q1"])
        assertEquals("q5 的答案还是 q5 的（不许串到 q1）", "b", merged["q5"])
        assertEquals("q6 的答案还是 q6 的", "false", merged["q6"])
    }

    // ── 御批红线（负例 5 的第一道闸） ────────────────────────────────────────

    @Test
    fun `N5a_多问卡只认QUESTION_御批类塞了questions也不许走多问卡`() {
        val group = AskUserRequest(question = "", options = emptyList(), questions = batch(2))
        assertTrue("普通问题组走多问卡", AskGroupRules.isGroupCard(group))
        listOf(AskKind.GROUP_AUTHORIZATION, AskKind.PERSONA_PROPOSAL).forEach { kind ->
            val stuffed = group.copy(kind = kind)
            assertFalse(
                "御批类（$kind）就算被塞进 questions 也不是多问卡：批准只能点按钮",
                AskGroupRules.isGroupCard(stuffed),
            )
        }
        val legacy = AskUserRequest("一个问题", listOf(AskUserOption("a", "甲"), AskUserOption("b", "乙")))
        assertFalse("旧单问形状走原选项卡", AskGroupRules.isGroupCard(legacy))
    }

    @Test
    fun `N5b_御批批准只经answerChoice_不经answerGroup`() {
        // 引擎的两条回答案通道是分开的：answerGroup 只动 groupResult，碰不到 choiceLock
        // ⇒ 打字/多问卡提交都不可能把御批卡糊弄过去。这里钉住引擎源码的分流。
        val code = engineCode()
        assertTrue(
            "引擎里必须有独立的 groupLock/groupResult（不与 choiceLock 共锁）",
            code.contains("private val groupLock = Object()") && code.contains("choiceLock"),
        )
        val answerGroup = fnBody(code, "fun answerGroup(", "fun stop(")
        assertFalse("answerGroup 不许碰 choiceResult（御批通道）", answerGroup.contains("choiceResult"))
    }

    // ── 执行体（问题组形状） ────────────────────────────────────────────────

    @Test
    fun `P9_三问一次提交_一条结果答案齐全且id对应`() {
        var seen: AskUserRequest? = null
        AskUserTool.groupSink = { req ->
            seen = req
            mapOf("q1" to "drop", "q2" to "2", "q3" to "true")
        }
        try {
            val out = AskUserTool.spec().executor(
                JSONObject()
                    .put(
                        "questions",
                        JSONArray()
                            .put(q(id = "q1", title = "删哪些？", options = opts("keep" to "留着", "drop" to "删掉")))
                            .put(q(id = "q2", title = "删到哪一层？", kind = "NUMBER"))
                            .put(q(id = "q3", title = "要不要报告？", kind = "BOOL")),
                    ),
            )
            assertTrue("应当成功：$out", out is ToolOutcome.Ok)
            val parsed = JSONObject((out as ToolOutcome.Ok).text)
            val answers = parsed.getJSONObject("answers")
            assertEquals("3 个答案齐全（一卡 3 项一次答完）", 3, answers.length())
            assertEquals("id 对应不许串位", "drop", answers.optString("q1"))
            assertEquals("2", answers.optString("q2"))
            assertEquals("true", answers.optString("q3"))
            assertEquals("sink 收到的是整组 3 项", 3, seen!!.questions.size)
        } finally {
            AskUserTool.groupSink = null
        }
    }

    @Test
    fun `P10_两套形状不许混用_超过20项拒_没界面如实说`() {
        val mixed = AskUserTool.spec().executor(
            JSONObject()
                .put("question", "选哪个？")
                .put("options", opts("a" to "甲", "b" to "乙"))
                .put("questions", JSONArray().put(q(title = "混着来"))),
        )
        assertTrue("混用必须拒：$mixed", mixed is ToolOutcome.Failed)
        assertTrue((mixed as ToolOutcome.Failed).error.contains("两套形状"))

        val tooMany = AskUserTool.spec().executor(
            JSONObject().put("questions", JSONArray().apply { repeat(21) { put(q(title = "第 $it 问")) } }),
        )
        assertTrue("超上限（${AskUserTool.MAX_QUESTIONS}）要拒：$tooMany", tooMany is ToolOutcome.Failed)

        AskUserTool.groupSink = null
        val noUi = AskUserTool.spec().executor(
            JSONObject().put("questions", JSONArray().put(q(title = "问一句"))),
        )
            assertTrue("没界面必须如实说：$noUi", noUi is ToolOutcome.Failed)
            assertTrue(
                "got: ${(noUi as ToolOutcome.Failed).error}",
                noUi.error.contains("没有界面"),
            )
    }

    @Test
    fun `P11_没人答如实说_不许假装答过了`() {
        AskUserTool.groupSink = { null }
        try {
            val out = AskUserTool.spec().executor(
                JSONObject().put("questions", JSONArray().put(q(title = "问一句"))),
            )
            assertTrue(out is ToolOutcome.Failed)
            assertTrue(
                "got: ${(out as ToolOutcome.Failed).error}",
                out.error.contains("没有答"),
            )
        } finally {
            AskUserTool.groupSink = null
        }
    }

    // ── 源码读取（照 AskUserToolTest 的房型） ────────────────────────────────

    private fun engineCode(): String =
        repoRel("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt").readText()
            .replace("\r\n", "\n")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun fnBody(code: String, fromMarker: String, toMarker: String): String {
        val s = code.indexOf(fromMarker)
        assertTrue("找不到锚点：$fromMarker", s >= 0)
        val bodyStart = s + fromMarker.length
        val e = code.indexOf(toMarker, bodyStart)
        return code.substring(bodyStart, if (e >= 0) e else code.length)
    }

    private fun repoRel(rel: String): java.io.File =
        java.io.File(rel).let { if (it.isFile) it else java.io.File("app/$rel") }

    @Test
    fun `P12_超时上限与旧形状同口径_等用户不等IO`() {
        assertEquals(
            "问题组与单问同一超时（15 分钟 + 5 秒）：等的是用户",
            RunRules.CONFIRM_TIMEOUT_MS + 5_000L,
            AskUserTool.spec().timeoutMs,
        )
        assertTrue("readOnly 必须还是 true（绝不进并行段）", AskUserTool.spec().readOnly)
    }
}
