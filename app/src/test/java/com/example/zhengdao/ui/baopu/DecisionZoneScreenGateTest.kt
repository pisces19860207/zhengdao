// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十八批 · 判据④的**源级门禁**：屏幕文件内不许有业务判断——
// 所有判定与写账都必须在判据层（`DecisionZoneRules`）与控制器数据口那一侧。
//
// 扫法照本仓既有源级门禁（去注释后再扫，注释里出现名字不算违规；
// 但代码文本里出现下面任何一个业务口，就是屏幕层"自己判"了——判据④直接红）。
package com.example.zhengdao.ui.baopu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionZoneScreenGateTest {

    private fun text(rel: String): String {
        val candidates = listOf(
            "src/main/java/com/example/zhengdao/ui/baopu/$rel",
            "../app/src/main/java/com/example/zhengdao/ui/baopu/$rel",
            "app/src/main/java/com/example/zhengdao/ui/baopu/$rel",
        )
        val f = candidates.map(::File).firstOrNull(File::exists)
            ?: throw AssertionError("找不到 $rel（工作目录 ${File(".").absolutePath}）")
        return f.readText(Charsets.UTF_8)
    }

    /** 去行注释与块注释后的代码文本（结构性断言用；免得被注释绕过）。 */
    private fun codeOnly(src: String): String =
        src.split("\n").joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    private val screen by lazy { codeOnly(text("DecisionZoneScreen.kt")) }

    // ── 判据④：屏幕层零业务判断 ──────────────────────────────────────────────

    @Test
    fun `屏幕层不许碰账本与队列的写口和判定口`() {
        val banned = listOf(
            // 写账口（裁定行只能由控制器数据口落）
            "DecisionLedger", "RoyalDecisionLedger", "DecisionReview", "recordTo",
            // 判定口（三区取数与分发全在控制器与判据层）
            "DecisionKind", "DecisionSampling", "foldDecisionRecords", "suppressedIds",
            "OpinionWiring", "LearnWiring", "RoyalQueueRules", "RoyalCardGate",
            // 既有动作通道（屏幕只转发，不许直接调通道）
            "answerChoice", "decideSuggestion", "keepPostAdoption", "laterPostAdoption",
            "rollbackPostAdoption", "onDecisionRecordJudge", "DecisionRollbacks",
            // 集合级业务算子（排序/筛选/分组都不许在屏幕里）
            "sortedBy", "sortedWith", "filter {", "groupBy", "distinctBy", "maxBy",
        )
        banned.forEach { b ->
            assertFalse("屏幕文件里不许出现业务口「$b」", screen.contains(b))
        }
    }

    @Test
    fun `屏幕层的每个动作都走控制器数据口`() {
        listOf(
            "controller.decisionZoneSnapshot()",
            "controller.onZonePendingAction(",
            "controller.onZoneRoyalPick(",
            "controller.onDecisionRecordRollback(",
        ).forEach { call ->
            assertTrue("屏幕必须经数据口「$call」", screen.contains(call))
        }
    }

    // ── 判据①的入口位：抽屉那一行在「新会话」之上 ────────────────────────────

    @Test
    fun `抽屉入口存在且排在新会话之上`() {
        val drawer = text("BaopuDrawer.kt")
        assertTrue("抽屉要有决策中心回调", drawer.contains("onDecisionCenter: () -> Unit"))
        val entry = drawer.indexOf("baopu_drawer_decision_center")
        val newSession = drawer.indexOf("R.string.baopu_new_session")
        assertTrue("抽屉里要有决策中心那一行", entry >= 0)
        assertTrue("决策中心必须排在「新会话」之上（派工单 §一①）", entry < newSession)
    }

    @Test
    fun `主屏挂在抱朴页上且接了返回键`() {
        val page = text("BaopuScreen.kt")
        assertTrue(page.contains("DecisionZoneScreen("))
        assertTrue(page.contains("BackHandler(enabled = showDecisionZone)"))
    }

    // ── 判据②的接线面：裁定行确实由数据口落账 ────────────────────────────────

    @Test
    fun `控制器数据口把裁定写进208_且御案三裁走既有RoyalVerdict`() {
        val controller = codeOnly(text("BaopuChatController.kt"))
        assertTrue("裁定行必须经 rulingDraft 造稿", controller.contains("DecisionZoneRules.rulingDraft("))
        assertTrue("裁定稿必须真调 DecisionLedger.record 落账", controller.contains("DecisionLedger.record(draft)"))
        assertTrue("御案选项 id 交给既有 RoyalVerdict 解释", controller.contains("RoyalVerdict.ofOptionId(optionId)"))
        assertTrue("主屏快照必须复用页签屏同一份御案算法", controller.contains("royalRows(decisionStr)"))
    }

    @Test
    fun `决策中心仍只有一套页签屏_主屏不顶替它`() {
        // 208 批 W4 的口径：`fun DecisionCenterScreen(` 全仓只许一处（主屏是另一个入口、同一批数据口）。
        val dir = listOf(
            "src/main/java/com/example/zhengdao/ui/baopu",
            "../app/src/main/java/com/example/zhengdao/ui/baopu",
            "app/src/main/java/com/example/zhengdao/ui/baopu",
        ).map(::File).firstOrNull(File::exists)
            ?: throw AssertionError("找不到 ui/baopu（工作目录 ${File(".").absolutePath}）")
        val centers = dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .count { f -> f.readText(Charsets.UTF_8).contains("fun DecisionCenterScreen(") }
        assertEquals(1, centers)
    }
}
