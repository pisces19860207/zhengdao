// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百六十批 · 接线门禁（房型照 RunContractWiringGateTest／PlanPledgeWiringTest E 组）：
// 引擎在 JVM 起不来，"改约真的透传到起单处"这件事只能按字面钉在源码上。
//
// 钉两头：
// - **头**：amend 落地 ⇒ 退旧上限 ＋ 调透传口（PlanController 的 amend 分支）；
// - **尾**：透传口 ⇒ 起单处重开一版并挂回 activeRunOrder（RunEngine 的 reissueOrderForAmend）。
//
// 再加一组**别碰坏邻居**：404 已合入的两处（起单即挂账 `activeRunOrder = order`、
// 超时话术 `changeTimeoutNoPlan`）一个字都不许被本批覆盖。
package com.example.zhengdao.baopu.agent

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AmendContractWiringGateTest {

    private fun code(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()
            .replace("\r\n", "\n")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun engine(): String = code("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")

    private fun controller(): String = code("src/main/java/com/example/zhengdao/baopu/agent/PlanController.kt")

    private fun wiring(): String = code("src/main/java/com/example/zhengdao/baopu/verify/RunContractWiring.kt")

    private fun planGuard(): String = code("src/main/java/com/example/zhengdao/baopu/agent/PlanGuard.kt")

    /** 某个函数体（从声明到下一个 `private fun` / 文件尾；够用就够，不求精确解析）。 */
    private fun bodyOf(src: String, decl: String): String {
        val at = src.indexOf(decl)
        if (at < 0) return ""
        val rest = src.substring(at + decl.length)
        val end = rest.indexOf("private fun ").takeIf { it > 0 } ?: rest.length
        return rest.substring(0, end)
    }

    // ── 头：amend 落地才退上限、才透传 ───────────────────────────────────────

    @Test
    fun `G1_改约落地分支_退旧上限且调透传口`() {
        val src = controller()
        assertTrue("amend 落地分支没退旧上限（retireContractForAmend 不在）", src.contains("retireContractForAmend() +"))
        assertTrue("amend 落地分支没调透传口（onAmend 不在）", src.contains("onAmend(amendRequirementOf(d.change))"))
        val retire = bodyOf(src, "private fun retireContractForAmend")
        assertTrue("退上限那个函数不见了", retire.isNotEmpty())
        assertTrue(
            "退上限没真清板子（PlanContractBoard.clear 不在 retireContractForAmend 里）",
            retire.contains("PlanContractBoard.clear()"),
        )
        assertTrue("没判有没有上限就清（白记一行日志）", retire.contains("PlanContractBoard.hasContract()"))
    }

    @Test
    fun `G2_透传口在构造上可注入_缺省空实现不动存量调用点`() {
        val src = controller()
        assertTrue("PlanController 没有 onAmend 注入点", src.contains("private val onAmend: (String) -> String"))
        assertTrue("缺省不是空实现 ⇒ 存量调用点行为会变", src.contains("onAmend: (String) -> String = { \"\" }"))
    }

    // ── 尾：透传口 ⇒ 起单处重开一版并挂回去 ─────────────────────────────────

    @Test
    fun `G3_引擎透传口_调amendRun并挂回activeRunOrder`() {
        val src = engine()
        assertTrue(
            "引擎没接透传口（onAmend 没喂 reissueOrderForAmend）",
            src.contains("onAmend = { requirement -> reissueOrderForAmend(requirement) }"),
        )
        val reissue = bodyOf(src, "private fun reissueOrderForAmend")
        assertTrue("透传函数不见了", reissue.isNotEmpty())
        assertTrue("透传函数没重开单（amendRun 不在）", reissue.contains("RunContractWiring.amendRun(current, requirement)"))
        assertTrue("重开的单没挂回引擎状态（404 的「可查」会断）", reissue.contains("activeRunOrder = next"))
        assertTrue("重开之后没留运行日志", reissue.contains("amendLine("))
        assertTrue("没把「旧批准作废」回灌给模型", reissue.contains("reissueAck("))
    }

    @Test
    fun `G4_改单只在起单处重开一版_不放宽任何一格`() {
        val src = wiring()
        val amend = bodyOf(src, "fun amendRun(")
        assertTrue("amendRun 不见了", amend.isNotEmpty())
        assertTrue("版本没 +1", amend.contains("revision = order.contract.revision + 1"))
        assertTrue("新单没自查形状", amend.contains("next.violations().isNotEmpty()"))
        assertTrue(
            "改单顺手写了授权/预算（判据③：不许开新后门）",
            !amend.contains("authority =") && !amend.contains("budget =") && !amend.contains("evidence ="),
        )
    }

    // ── 邻居：404 已合入的两处一个字不许覆盖 ─────────────────────────────────

    @Test
    fun `G5_404的起单即挂账没被覆盖`() {
        val src = engine()
        assertTrue("404 的起单建契约被拔了", src.contains("RunContractWiring.startRun(roundRunId"))
        assertTrue("404 的起单即挂账被拔了", src.contains("activeRunOrder = order"))
        assertTrue("404 的查询口被拔了", src.contains("fun currentTaskContract(): TaskContract?"))
    }

    @Test
    fun `G6_404的超时话术没被覆盖`() {
        val src = planGuard()
        assertTrue("404 的超时话术函数不见了", src.contains("fun changeTimeoutNoPlan("))
        assertTrue("超时话术被改了（超时≠同意也≠拒绝 那句）", src.contains("这不算用户同意，也不算拒绝"))
        assertTrue("超时话术里的「军令状也不会立」被抹了", src.contains("军令状也没有立"))
    }
}
