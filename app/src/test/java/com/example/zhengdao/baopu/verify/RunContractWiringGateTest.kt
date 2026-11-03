// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百七十八批的接线门禁（房型照 AskGroupWiringTest／StepBudgetGateTest：引擎在 JVM
// 起不来，结构性事实用源码门禁钉）。钉的是"线真的接在这、真调用、调用顺序对"，
// 行为判据在 RunContractWiringTest（真文件真判据表）。
//
// 为什么逐条钉：378 派工单的原话是"必须是生产真跑，不是对象自测"。RunEngine 在 JVM
// 起不来 ⇒ "生产真跑"拆成两半：行为面（接线件直测，真文件真读数）＋结构面（本文件——
// 引擎侧的每个接线点按字面钉死，谁把线拔了谁红）。
package com.example.zhengdao.baopu.verify

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RunContractWiringGateTest {

    private fun code(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()
            .replace("\r\n", "\n")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun engine(): String =
        code("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")

    private fun wiring(): String =
        code("src/main/java/com/example/zhengdao/baopu/verify/RunContractWiring.kt")

    // ── ① 起单建契约：接线点在 runLoop 起单处，闲聊不立单 ────────────────────

    @Test
    fun `G1_起单接线_引擎起单处调startRun且闲聊不立单`() {
        val src = engine()
        assertTrue(
            "起单没建契约（RunContractWiring.startRun 不在引擎里）",
            src.contains("RunContractWiring.startRun(roundRunId"),
        )
        assertTrue(
            "起单没有判态——闲聊也会立单（TaskModeBoundary.classify 必须挡在 startRun 前）",
            src.contains("TaskModeBoundary.classify(it) == TaskMode.TASK"),
        )
    }

    // ── ① 前置读契约：装配层消费契约本体（目标/范围/预算进本轮上下文） ────────

    @Test
    fun `G2_前置读契约_契约行真拼进本轮上下文`() {
        assertTrue(
            "契约没进本轮上下文（前置读了没算数）",
            engine().contains("turnNotes = turnNotes + order.precheckLine()"),
        )
    }

    // ── ② 收工按契约判：三态真由 ContractSelector 算 ─────────────────────────

    @Test
    fun `G3_收工接线_引擎收尾调closeRun并落运行日志`() {
        val src = engine()
        assertTrue("收尾没调契约验收（closeRun 不在引擎文本收尾处）", src.contains("RunContractWiring.closeRun("))
        assertTrue("验收读数没进运行日志（reportLine 不在引擎里）", src.contains("RunContractWiring.reportLine(report)"))
    }

    @Test
    fun `G4_三态由判据表算_接线件真调ContractSelector_evaluate`() {
        val src = wiring()
        assertTrue(
            "PASS/FAIL/UNVERIFIABLE 不是 ContractSelector 算的（evaluate 不在接线件里）",
            src.contains("ContractSelector.evaluate(order.declared"),
        )
        assertTrue(
            "证据不是 Contracts 亲读的（readDoc 不在接线件里——调用方自己填数就是没验）",
            src.contains("Contracts.readDoc("),
        )
    }

    // ── ③ 弹卡判态：闸在 handleSerialCall 首位，只拦 ask_user ────────────────

    @Test
    fun `G5_弹卡闸_经出卡前第一道闸链_先于其余出卡闸`() {
        val src = engine()
        val gateChain = src.indexOf("preExecutionGate(call)?.let")
        assertTrue(
            "弹卡闸没进 handleSerialCall 闸链首位（preExecutionGate 出口不在）",
            gateChain >= 0,
        )
        val amendGate = src.indexOf("amendGate(call)?.let")
        assertTrue("变更卡闸不在引擎里（锚点丢失）", amendGate > 0)
        assertTrue(
            "第一道闸链必须先于其余出卡闸（handleSerialCall 闸链首位）",
            gateChain < amendGate,
        )
        // 闸链本体：军令状组合闸在前、闲聊弹卡闸在后（短路求并，谁命中谁收场）
        val chainBody = src.substring(src.indexOf("private fun preExecutionGate"))
            .take(400)
        assertTrue("闸链里没有军令状组合闸（347① 的线被拆了）", chainBody.contains("pledgeComboGate(call)"))
        assertTrue("闸链里没有闲聊弹卡闸（378③ 的线不在）", chainBody.contains("askModeGate(call)"))
    }

    @Test
    fun `G6_弹卡闸_只拦ask_user且判据走TaskMode判定函数`() {
        val src = engine()
        val gateBody = src.substring(src.indexOf("private fun askModeGate"))
            .take(600)
        assertTrue("闸没有限定 ask_user（会把同意卡也拦了）", gateBody.contains("call.name != \"ask_user\""))
        assertTrue("闸没走 TaskMode 判定函数", gateBody.contains("askAllowedForTurn(turnText)"))
        assertTrue(
            "判定函数没走 TaskModeBoundary.classify",
            engine().contains("TaskModeBoundary.classify(text) == TaskMode.TASK"),
        )
    }

    // ── 边界：本单不碰 MediatorWiring（379 的文件）────────────────────────────

    @Test
    fun `G7_边界_接线件不经MediatorWiring_弹卡判态直接调TaskMode判定函数`() {
        // 引擎里 258 批的 MediatorWiring 既有引用（promptNotes/PROMPT_CONTRACT）不是本单的线，
        // 不在此管；本门禁钉的是：**新接的判态线**（接线件＋弹卡闸）不经过 MediatorWiring——
        // 判定函数 TaskModeBoundary 是直接调的，379 接免打扰时不会被这根线绊住。
        assertTrue(
            "接线件不许引用 MediatorWiring（归 379）",
            !wiring().contains("MediatorWiring"),
        )
        val gate = engine().substring(engine().indexOf("private fun askModeGate")).take(600)
        assertTrue("弹卡闸不许经 MediatorWiring 判态", !gate.contains("MediatorWiring"))
    }

    // ── 判据④：接线件的调用点传真数据（契约字段全来自真源） ──────────────────

    @Test
    fun `G8_传真数据_契约构造口只认真源不认编造值`() {
        val src = wiring()
        assertTrue("预算不是走档位的正路构造口（BudgetPreset.budget）", src.contains("BudgetPreset.STANDARD.budget()"))
        assertTrue("授权范围不是产物目录真源（ZhengdaoDirs.PRODUCTS_DIR）", src.contains("ZhengdaoDirs.PRODUCTS_DIR"))
        assertTrue("目标不是用户原话（goal.text 没吃 userText）", src.contains("text = text.take(TaskGoal.MAX_TEXT_CHARS)"))
    }

    // ── 第四百零四批·判据③：契约起单即建、起单后立即可查 ────────────────────

    @Test
    fun `G9_契约起单即挂账_查询口在_跑的期间立即可查`() {
        val src = engine()
        assertTrue(
            "起单建的契约没挂进引擎状态（activeRunOrder）——起单与查询之间断了线",
            src.contains("activeRunOrder = order"),
        )
        assertTrue(
            "查询口不在（currentTaskContract）——「立即可查」没有落点",
            src.contains("fun currentTaskContract(): TaskContract?"),
        )
        assertTrue(
            "挂账字段要 @Volatile（runLoop 线程写、闸/工具线程读）",
            Regex("""@Volatile\s*\n\s*private var activeRunOrder""").containsMatchIn(src),
        )
    }

    @Test
    fun `G10_每个run先清_起单才挂_续跑轮不背上一张`() {
        val src = engine()
        val clearIdx = src.indexOf("activeRunOrder = null")
        val startIdx = src.indexOf("RunContractWiring.startRun(roundRunId")
        assertTrue("run 开始没清挂账（clear 不在）", clearIdx >= 0)
        assertTrue(
            "清要在起单之前（续跑/闲聊轮不许背着上一轮的契约）",
            clearIdx < startIdx,
        )
    }
}
