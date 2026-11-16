// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百五十四批 · 模型提交口（learn_draft_submit）的判据 ①②③④⑥⑦。
//
// 房型照 624 LearnBoardGateTest：源码判据「剥注释后的真源码」＋负例变异（变异不红＝判据空洞，
// 本文件自己红）；运行时判据走纯层实跑（TempSandbox，不碰 Android）。
//
// 四条出参判据（派工单逐字）：
//   ① 合规草案 ⇒ 接受且落草稿区（不转正）
//   ② 缺定位 ⇒ 拒收且原因含「缺定位」（负例：接受 ⇒ 红）
//   ③ 缺证据 ⇒ 拒收
//   ④ 含不可逆动作 ⇒ 拒收且原因为「不可逆」
// 两条源码判据：
//   ⑥ 两条路共用同一个校验函数；⑦ 模型提交不写盘
package com.example.zhengdao.baopu.learn

import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class LearnSubmitTest {

    private val session = "lz9k2a"

    // ── 夹具 ──────────────────────────────────────────────────────────────────

    private fun faces(
        name: String = "查询",
        tag: String = "BUTTON",
        xp: String = "/html/body/button[1]",
        testid: String = "",
        placeholder: String = "",
        inputType: String = "",
    ) = ObserveFaces(
        tag = tag, role = "button", name = name, aria = "", testid = testid, id = "",
        inputName = "", placeholder = placeholder, inputType = inputType, auto = "",
        formLabel = "", xp = xp, cls = emptyList(),
    )

    private val blankFaces = ObserveFaces(
        tag = "", role = "", name = "", aria = "", testid = "", id = "",
        inputName = "", placeholder = "", inputType = "", auto = "", formLabel = "",
        xp = "", cls = emptyList(),
    )

    private fun step(
        index: Int,
        name: String = "查询",
        kind: String = "click",
        locator: ObserveFaces = faces(name = name, xp = "button[$index]"),
        param: String? = null,
        evidence: List<String> = listOf("观察/会话-$session.jsonl#seq$index"),
    ) = DraftStep(
        index = index, intent = name, kind = kind, url = "https://example.test/list",
        locator = locator, param = param, optional = false, candidates = emptyList(),
        expect = listOf("urlChanged"), evidence = evidence,
    )

    /** 模型交上来的形状＝草稿区落盘的形状（zl-draft/1，没有第二套 JSON）。 */
    private fun payload(
        goal: String,
        steps: List<DraftStep>,
        params: List<DraftParam> = steps.filter { it.param != null }.map { DraftParam(it.param!!, "step${it.index}.input") },
        sessions: List<String> = listOf("观察/会话-$session.jsonl"),
    ): JSONObject = SkillDraft(
        id = "m1", goal = goal, targetSite = "example.test", params = params,
        skeleton = steps, variableSteps = steps.filter { it.param != null }.map { it.index },
        mergedFrom = 1, sourceSessions = sessions,
    ).toJson()

    private fun storeIn(tmp: File) = SkillDraftStore(File(tmp, SkillDraftStore.DIR_NAME))

    private fun submit(p: JSONObject, store: SkillDraftStore, id: String = "n1", last: String? = session) =
        LearnSubmit.submit(p, id, last, store)

    // ── 判据①：合规草案 ⇒ 接受且落草稿区（不转正）──────────────────────────────

    @Test
    fun `判据1_合规草案被接受_落草稿区_不转正`() {
        TempSandbox.use("zd654-accept") { tmp ->
            val store = storeIn(tmp)
            val out = submit(payload("查装车", listOf(step(1), step(2, kind = "type", param = "单号", name = "单号"))), store)
            assertTrue("合规草案必须被接受，实到 $out", out is LearnSubmit.Outcome.Accepted)
            val accepted = out as LearnSubmit.Outcome.Accepted
            assertEquals("首条观察：mergedFrom=1", 1, accepted.withN)
            assertFalse("不许标归并", accepted.merged)
            assertEquals("落进草稿区（一条）", 1, store.pending().size)
            assertEquals("草稿区里就是这条", "查装车", store.pending().single().goal)
            // 不转正：技能目录一个都不该有；沙盒根下只该出现草稿区
            assertFalse("转正才算落技能库——这里不许有", File(tmp, "技能").exists())
            assertEquals("模型提交只许碰草稿区（⑦的运行时常量）", listOf("草案"), rootNames(tmp))
        }
    }

    private fun rootNames(root: File): List<String> =
        (root.listFiles() ?: emptyArray()).map { it.name }.sorted()

    // ── 判据②：缺定位 ⇒ 拒收（负例：接受 ⇒ 红）────────────────────────────────

    @Test
    fun `判据2_缺定位的草案被拒_原因含缺定位_合规对照必被接受`() {
        TempSandbox.use("zd654-nolocator") { tmp ->
            val store = storeIn(tmp)
            val bad = payload("查装车", listOf(step(1, locator = blankFaces, name = "")))
            val out = submit(bad, store)
            assertTrue("缺定位必须拒，实到 $out", out is LearnSubmit.Outcome.Rejected)
            val reasons = (out as LearnSubmit.Outcome.Rejected).reasons
            assertTrue("原因要含「缺定位」：$reasons", reasons.any { it.contains(LearnSubmit.REASON_LOCATOR) })
            assertTrue("也要说清是定位全空：$reasons", reasons.any { it.contains(SkillDraftRules.BLANK_LOCATOR) })
            assertEquals("拒收的不入区", 0, store.pending().size)
            // 负例对照：同一条草案把定位填上 ⇒ 必须接受（证明上面的「拒」不是因为测试恒假）
            val good = submit(payload("查装车", listOf(step(1))), store, id = "n2")
            assertTrue("同一夹具填上定位就该过，实到 $good", good is LearnSubmit.Outcome.Accepted)
        }
    }

    // ── 判据③：缺证据 ⇒ 拒收 ────────────────────────────────────────────────────

    @Test
    fun `判据3_缺证据的草案被拒_原因含缺证据_合规对照必被接受`() {
        TempSandbox.use("zd654-noevidence") { tmp ->
            val store = storeIn(tmp)
            val out = submit(payload("查装车", listOf(step(1), step(2, evidence = emptyList()))), store)
            assertTrue("缺证据必须拒，实到 $out", out is LearnSubmit.Outcome.Rejected)
            val reasons = (out as LearnSubmit.Outcome.Rejected).reasons
            assertTrue("原因要含「缺证据」：$reasons", reasons.any { it.contains(LearnSubmit.REASON_EVIDENCE) })
            assertTrue("要指认第几步：$reasons", reasons.any { it.contains("第 2 步") })
            assertEquals(0, store.pending().size)
            val good = submit(payload("查装车", listOf(step(1), step(2))), store, id = "n2")
            assertTrue("证据齐了就该过，实到 $good", good is LearnSubmit.Outcome.Accepted)
        }
    }

    // ── 判据④：含不可逆动作 ⇒ 拒收（原因「不可逆」）────────────────────────────

    @Test
    fun `判据4_含不可逆动作的草案被拒_原因含不可逆`() {
        TempSandbox.use("zd654-irreversible") { tmp ->
            val store = storeIn(tmp)
            val out = submit(payload("查装车", listOf(step(1), step(2, name = "提交订单"))), store)
            assertTrue("含不可逆动作必须拒，实到 $out", out is LearnSubmit.Outcome.Rejected)
            val reasons = (out as LearnSubmit.Outcome.Rejected).reasons
            assertTrue("原因要含「不可逆」：$reasons", reasons.any { it.contains(LearnSubmit.REASON_IRREVERSIBLE) })
            assertTrue("要点名是哪一步：$reasons", reasons.any { it.contains("第 2 步") })
            assertEquals(0, store.pending().size)
            // 负例对照：同一形状只把「提交订单」换成「查询明细」⇒ 必须接受
            val good = submit(payload("查装车", listOf(step(1), step(2, name = "查询明细"))), store, id = "n2")
            assertTrue("换个普通的按钮名就该过，实到 $good", good is LearnSubmit.Outcome.Accepted)
        }
    }

    @Test
    fun `判据4_冲突候选里藏提交按钮_同样拒`() {
        TempSandbox.use("zd654-irrev-cand") { tmp ->
            val store = storeIn(tmp)
            val conflict = step(1).copy(candidates = listOf(faces(name = "查询", xp = "/a"), faces(name = "确认支付", xp = "/b")))
            val out = submit(payload("查装车", listOf(conflict)), store)
            assertTrue("候选里有点提交的那套 ⇒ 迟早会点，必须拒：$out", out is LearnSubmit.Outcome.Rejected)
            assertTrue((out as LearnSubmit.Outcome.Rejected).reasons.any { it.contains(LearnSubmit.REASON_IRREVERSIBLE) })
        }
    }

    // ── 轨迹引用（提交口的前置闸）──────────────────────────────────────────────

    @Test
    fun `轨迹引用对不上最近会话_拒收`() {
        TempSandbox.use("zd654-session") { tmp ->
            val store = storeIn(tmp)
            val out = submit(payload("查装车", listOf(step(1)), sessions = listOf("观察/会话-旧会话.jsonl")), store)
            assertTrue("指到不存在的会话＝假账，必须拒：$out", out is LearnSubmit.Outcome.Rejected)
            assertTrue((out as LearnSubmit.Outcome.Rejected).reasons.any { it.contains(LearnSubmit.REASON_SESSION) })
            assertEquals(0, store.pending().size)
        }
    }

    @Test
    fun `会话引用的四种写法都认同一次会话`() {
        listOf(session, "会话-$session", "$session.jsonl", "观察/会话-$session.jsonl#seq7").forEach { ref ->
            assertTrue("「$ref」应认成会话 $session", LearnSubmit.sameSession(ref, session))
        }
        assertFalse(LearnSubmit.sameSession("观察/会话-别的.jsonl", session))
    }

    @Test
    fun `没给轨迹引用_用最近一次会话兜底`() {
        TempSandbox.use("zd654-session-fallback") { tmp ->
            val store = storeIn(tmp)
            val p = payload("查装车", listOf(step(1)), sessions = emptyList())
            val out = submit(p, store)
            assertTrue("有最近会话就兜底，实到 $out", out is LearnSubmit.Outcome.Accepted)
            assertEquals("兜底后 sessions 记最近一次", listOf(session), store.pending().single().sourceSessions)
        }
    }

    @Test
    fun `同意图第二次提交_归并成一条并标N`() {
        TempSandbox.use("zd654-merge") { tmp ->
            val store = storeIn(tmp)
            submit(payload("查装车", listOf(step(1))), store, id = "n1")
            val second = submit(payload("查装车", listOf(step(1))), store, id = "n2")
            assertTrue(second is LearnSubmit.Outcome.Accepted)
            val ok = second as LearnSubmit.Outcome.Accepted
            assertTrue("第二次应标归并", ok.merged)
            assertEquals("N=2", 2, ok.withN)
            assertEquals("区里仍只有一条", 1, store.pending().size)
        }
    }

    // ── 判据⑥：两条路共用同一个校验函数（源码断言＋变异）──────────────────────

    private val learnDir = "app/src/main/java/com/example/zhengdao/baopu/learn"

    private fun repoDir(): File =
        listOf(File(learnDir), File("../$learnDir")).firstOrNull { it.isDirectory }
            ?: throw AssertionError("learn 包目录不存在")

    private fun stripComments(src: String): String = src.replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    /** learn 包全部源码（剥注释）：判据只读代码，注释里写「共用一门」不算数。 */
    private fun sources(): Map<String, String> {
        val files = repoDir().listFiles { f -> f.name.endsWith(".kt") } ?: emptyArray()
        assertTrue("门禁不许空转（learn 包至少 1 件）", files.isNotEmpty())
        return files.associate { it.name to stripComments(it.readText()) }
    }

    /**
     * 判据⑥的判别函数：草案结构校验的**定义只有一处**，且两条归纳路都真调它。
     *
     * - 定义处：`fun validate(draft: SkillDraft)` 全包只许 1 个（多一个＝第二道门）；
     * - 脚本侧：DraftInducer 调 `SkillDraftRules.validate`；
     * - 模型侧：LearnSubmit 调 `SkillDraftRules.validate`。
     */
    private fun singleGate(src: Map<String, String>): Boolean {
        val defs = src.values.sumOf { Regex("fun validate\\(draft: SkillDraft\\)").findAll(it).count() }
        val scriptPath = src["DraftInducer.kt"]?.contains("SkillDraftRules.validate") == true
        val modelPath = src["LearnSubmit.kt"]?.contains("SkillDraftRules.validate") == true
        return defs == 1 && scriptPath && modelPath
    }

    @Test
    fun `判据6_脚本路与模型路共用同一个校验函数_只此一处`() {
        val src = sources()
        assertTrue("判据⑥不成立（定义处%d个）".format(
            src.values.sumOf { Regex("fun validate\\(draft: SkillDraft\\)").findAll(it).count() },
        ), singleGate(src))
        // 变异一：包里多出一个同签名校验函数 ⇒ 必须被抓（第二道门）
        val duplicated = src + ("CopyGate.kt" to "internal object CopyGate { fun validate(draft: SkillDraft): DraftCheck = DraftCheck.Ok }")
        assertFalse("多一道门必须抓红", singleGate(duplicated))
        // 变异二：模型路绕过校验（直接落区）⇒ 必须被抓
        val bypass = src + ("LearnSubmit.kt" to src.getValue("LearnSubmit.kt").replace("SkillDraftRules.validate", "validateNothing"))
        assertFalse("模型路绕过校验必须抓红", singleGate(bypass))
    }

    // ── 判据⑦：模型提交不写盘（源码断言＋运行时常量＋变异）────────────────────

    /** 脚本/技能的真源目录字面量：提交口一个都不许出现（出现＝自己开了一条写口）。 */
    private val writeTokens = listOf(
        "writeText", "appendText", "mkdirs", "openOutputStream", ".delete()",
        "File(", "技能/", "脚本/", "shards", "scripts",
    )

    private fun noDirectWrite(src: String): Boolean = writeTokens.none { src.contains(it) }

    @Test
    fun `判据7_模型提交不写盘_工具与提交层都没有写口`() {
        val tool = stripComments(
            listOf(
                File("app/src/main/java/com/example/zhengdao/baopu/agent/tools/LearnTools.kt"),
                File("../app/src/main/java/com/example/zhengdao/baopu/agent/tools/LearnTools.kt"),
            ).first { it.exists() }.readText(),
        )
        val submit = sources().getValue("LearnSubmit.kt")
        assertTrue("工具层不许有写口：${writeTokens.filter { tool.contains(it) }}", noDirectWrite(tool))
        assertTrue("提交层不许有写口：${writeTokens.filter { submit.contains(it) }}", noDirectWrite(submit))
        // 变异：把一次直接写盘塞进提交层 ⇒ 必须被抓
        assertFalse("直接写盘必须抓红", noDirectWrite(submit + "\nFile(dir, \"x.json\").writeText(\"{}\")"))
    }

    @Test
    fun `判据7_接受一次提交_盘上只多出草稿区`() {
        TempSandbox.use("zd654-nowrite") { tmp ->
            val store = storeIn(tmp)
            val before = rootNames(tmp)
            assertTrue("先确认沙盒是空的（判据不许恒真）", before.isEmpty())
            submit(payload("查装车", listOf(step(1))), store)
            assertEquals("只许多出草稿区（没有 技能/、没有 脚本/）", listOf("草案"), rootNames(tmp))
            assertEquals("草稿区里一份草案", 1, File(tmp, SkillDraftStore.DIR_NAME).listFiles()?.size)
        }
    }

    // ── 结构不合法（拒收原因的第四类）─────────────────────────────────────────

    @Test
    fun `结构不合法_读了不算草案_拒收并说清`() {
        TempSandbox.use("zd654-shape") { tmp ->
            val store = storeIn(tmp)
            val p = JSONObject().put("goal", 42).put("skeleton", JSONArray())
            val out = submit(p, store)
            assertTrue("坏形状必须拒：$out", out is LearnSubmit.Outcome.Rejected)
            val reasons = (out as LearnSubmit.Outcome.Rejected).reasons
            assertTrue("原因要含「结构不合法」：$reasons", reasons.any { it.contains(LearnSubmit.REASON_SHAPE) })
        }
    }
}
