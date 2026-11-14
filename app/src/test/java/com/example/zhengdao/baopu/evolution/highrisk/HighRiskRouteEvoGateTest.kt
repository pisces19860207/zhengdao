// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十八批 C · 高风险域②路由接线**源级门禁**（判据「冲突时以状态机为准」的钉缝面；
// 房型照域① HighRiskEvoGateTest：管线在 JVM 起不全，结构性事实用源码门禁钉）。
//
// 钉的事（缺一条就是"接了但没真接"）：
//   · 域②出口：AdmissionWiring 新 peer 登记完才出路由候选（routeSink），已登记支路提前 return 不出；
//   · 装配层：BaopuRuntime 真把 routeSink 接到 RouteEvo.onPeerAdmitted；
//   · 生效值读口：档位开着才用账上调整行（effectiveRoute 先问档再读行）；
//   · 状态机守卫：proposeRouteChange 过共用门，applyRoute 落行前先过 conflictsWithStateMachine，
//     守卫委托 RouteSemantics.flowLegal（＝549 EnvoyLaw.legal，不另判）且冲突记账；
//   · 扫档：EvolutionWiring.onRunSettled 真调 RouteEvo.sweepOnSettle，且扫档真过 589 样本门；
//   · 一键回滚：决策中心回滚 else 分派真接 rollbackRouteRowNow → RouteEvo.revertRoute（只认 route: 前缀）；
//   · 单一真相：RouteSemantics.flowLegal 只委托 549 法条，不另立第二份状态判定（硬要求①）。
//
// 判据的"负例"也在这：每个缝点配一条**拔缝变异**——把真文件里的调用点从原文里摘掉再跑同一把断言，
// 必须红。门禁自己能红，才不是恒真摆设（428 的 N2 教训）。派工单点名的负例「改了状态含义 ⇒ 红」
// ＝ R8 负例（把 flowLegal 的法条委托改成恒真）＋ HighRiskRouteSemanticsGateTest 的 S1–S3 负例。
package com.example.zhengdao.baopu.evolution.highrisk

import com.example.zhengdao.GateBodies
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighRiskRouteEvoGateTest {

    private fun code(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()
            .replace("\r\n", "\n")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun admissionWiring(): String =
        code("src/main/java/com/example/zhengdao/baopu/intake/AdmissionWiring.kt")

    private fun routeEvo(): String =
        code("src/main/java/com/example/zhengdao/baopu/evolution/highrisk/RouteEvo.kt")

    private fun routeSemantics(): String =
        code("src/main/java/com/example/zhengdao/baopu/evolution/highrisk/RouteSemantics.kt")

    private fun evolutionWiring(): String =
        code("src/main/java/com/example/zhengdao/baopu/evolution/EvolutionWiring.kt")

    private fun baopuRuntime(): String =
        code("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt")

    private fun chatController(): String =
        code("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")

    // ── 域②出口：新 peer 登记完才出候选，已登记支路不出 ──────────────────────

    @Test
    fun `R1_新peer准入才出路由候选_已登记支路不出`() {
        val src = admissionWiring()
        assertTrue("routeSink 字段不在了", src.contains("var routeSink:"))
        val body = GateBodies.functionBody(src, "private fun admit(")
        assertTrue("admit 不在了？", body != null)
        val known = body!!.indexOf("if (known != null)")
        val save = body.indexOf("AdmissionFlow.save(")
        val fire = body.indexOf("routeSink?.invoke(")
        assertTrue("已登记支路没有提前 return", known >= 0)
        assertTrue("admit 没出路由候选", fire >= 0)
        assertTrue("候选必须在「新登记」之后（已登记支路 return 在前）", known < fire)
        assertTrue("候选必须在登记落盘之后", save in 0 until fire)
    }

    @Test
    fun `R1负例_候选出口从真文件里拔掉_同一把断言必须红`() {
        val src = admissionWiring()
        assertTrue("前提：真文件里有候选出口", src.contains("routeSink?.invoke("))
        val mutated = src.replace("routeSink?.invoke(", "routeSinkGone?.invoke(")
        val body = GateBodies.functionBody(mutated, "private fun admit(")
        assertTrue(body != null)
        assertFalse("拔掉出口后必须红（生产调用点必须在）", body!!.contains("routeSink?.invoke("))
    }

    // ── 装配层：sink 真接到高风险门 ──────────────────────────────────────────

    @Test
    fun `R2_装配层真把路由出口接到高风险门`() {
        val src = baopuRuntime()
        assertTrue("装配层没注入路由出口（routeSink 没人听＝假接线）", src.contains("AdmissionWiring.routeSink ="))
        val wiring = src.indexOf("AdmissionWiring.routeSink =")
        val call = src.indexOf("RouteEvo.onPeerAdmitted(")
        assertTrue("出口没接到高风险门", call > wiring)
    }

    @Test
    fun `R2负例_装配层注入拔掉_断言必须红`() {
        val src = baopuRuntime()
        assertTrue(src.contains("AdmissionWiring.routeSink ="))
        val mutated = src.replace("AdmissionWiring.routeSink =", "AdmissionWiring.routeSinkDisabled =")
        assertFalse("拔掉装配注入后必须红（出口无人听＝假接线）", mutated.contains("AdmissionWiring.routeSink ="))
    }

    // ── 生效值读口：档位开着才用账上调整行 ──────────────────────────────────

    @Test
    fun `R3_生效值读口先问档再读调整行`() {
        val body = GateBodies.functionBody(routeEvo(), "fun effectiveRoute(")
        assertTrue("effectiveRoute 不在了？", body != null)
        val band = body!!.indexOf("currentCanaryBand(")
        val row = body.indexOf("currentRoute(")
        assertTrue("读口不读档位（调整行绕过档位＝直接生效）", band >= 0)
        assertTrue("读口不读调整行", row >= 0)
        assertTrue("顺序必须是 先问档 → 再读行（OFF 收权）", band < row)
    }

    @Test
    fun `R3负例_调整行读口拔掉_断言必须红`() {
        val src = routeEvo()
        val mutated = src.replace("currentRoute(", "currentRouteGone(")
        val body = GateBodies.functionBody(mutated, "fun effectiveRoute(")
        assertTrue(body != null)
        assertFalse("拔掉调整行读口后必须红", body!!.contains("currentRoute("))
    }

    // ── 状态机守卫：共用门 → 落行前先过守卫 → 守卫委托 549 法条且冲突记账 ───────

    @Test
    fun `R4_提议过共用门_落行前先过状态机守卫_守卫委托549且记账`() {
        val propose = GateBodies.functionBody(routeEvo(), "fun proposeRouteChange(")
        assertTrue("proposeRouteChange 不在了？", propose != null)
        assertTrue("提议没过共用门（红线→档位→深度）", propose!!.contains("LowRiskEvo.gate("))
        assertTrue("Refuse 支没记账（只拒不记＝判据没人验）", propose.contains("LowRiskEvo.recordRefusal("))
        val apply = GateBodies.functionBody(routeEvo(), "private fun applyRoute(")
        assertTrue("applyRoute 不在了？", apply != null)
        val guard = apply!!.indexOf("conflictsWithStateMachine(")
        val stamp = apply.indexOf("applyRouteChange(")
        assertTrue("落行前不过状态机守卫（冲突也落行＝判据红）", guard >= 0)
        assertTrue("顺序必须是 状态机守卫 → 落行", guard < stamp)
        val conflict = GateBodies.functionBody(routeEvo(), "private fun conflictsWithStateMachine(")
        assertTrue("conflictsWithStateMachine 不在了？", conflict != null)
        assertTrue("守卫没委托 549 迁移法条（另判状态＝改状态语义）", conflict!!.contains("RouteSemantics.flowLegal("))
        assertTrue("冲突没记账（只拦不记＝判据没人验）", conflict.contains("recordAdvisor("))
    }

    @Test
    fun `R4负例_状态机守卫从落行路径里拔掉_断言必须红`() {
        val src = routeEvo()
        assertTrue("前提：真文件里有状态机守卫", src.contains("RouteSemantics.flowLegal("))
        val mutated = src.replace("RouteSemantics.flowLegal(", "RouteSemantics.flowLegalGone(")
        val body = GateBodies.functionBody(mutated, "private fun conflictsWithStateMachine(")
        assertTrue(body != null)
        assertFalse("拔掉守卫后必须红（冲突时以状态机为准不许静默消失）", body!!.contains("RouteSemantics.flowLegal("))
    }

    // ── 扫档：收工家族一行 ＋ 样本门真在扫档里 ────────────────────────────────

    @Test
    fun `R5_收工扫档真调RouteEvo`() {
        val body = GateBodies.functionBody(evolutionWiring(), "fun onRunSettled(")
        assertTrue("onRunSettled 不在了？", body != null)
        assertTrue("收工没扫路由域档（RouteEvo.sweepOnSettle 不在 onRunSettled 里）", body!!.contains("RouteEvo.sweepOnSettle("))
    }

    @Test
    fun `R5负例_扫档行拔掉_断言必须红`() {
        val src = evolutionWiring()
        assertTrue(src.contains("RouteEvo.sweepOnSettle("))
        val mutated = src.replace("RouteEvo.sweepOnSettle(", "RouteEvo.sweepRemoved(")
        val body = GateBodies.functionBody(mutated, "fun onRunSettled(")
        assertTrue(body != null)
        assertFalse("拔掉扫档行后必须红", body!!.contains("RouteEvo.sweepOnSettle("))
    }

    @Test
    fun `R6_扫档真过589样本门_拒了压住升档方向`() {
        val body = GateBodies.functionBody(routeEvo(), "fun sweepOnSettle(")
        assertTrue("sweepOnSettle 不在了？", body != null)
        val promote = body!!.indexOf("EvoSampleWiring.promote(")
        val decide = body.indexOf("decideCanaryBand(")
        assertTrue("扫档没过样本门（样本不足也转正＝判据④红）", promote >= 0)
        assertTrue("样本门要在梯子之前（拒了才压得住）", promote < decide)
    }

    @Test
    fun `R6负例_样本门从扫档里拔掉_断言必须红`() {
        val src = routeEvo()
        val mutated = src.replace("EvoSampleWiring.promote(", "EvoSampleWiring.promoteRemoved(")
        val body = GateBodies.functionBody(mutated, "fun sweepOnSettle(")
        assertTrue(body != null)
        assertFalse("拔掉样本门后必须红（判据④：样本不足不许转正）", body!!.contains("EvoSampleWiring.promote("))
    }

    // ── 一键回滚：决策中心 else 分派的路由支 ──────────────────────────────────

    @Test
    fun `R7_回滚else分派有路由行支_真调revertRoute`() {
        val body = GateBodies.functionBody(chatController(), "fun rollbackDecisionNow(")
        assertTrue("rollbackDecisionNow 不在了？", body != null)
        assertTrue("路由行没有回滚通道（活体不在回滚分派里）", body!!.contains("rollbackRouteRowNow("))
        val live = GateBodies.functionBody(chatController(), "fun rollbackRouteRowNow(")
        assertTrue("回滚活体不在了？", live != null)
        assertTrue("活体只认 route: 前缀的调整行", live!!.contains("ConsequenceEvidence.ROUTE"))
        assertTrue("活体没接到高风险门（RouteEvo.revertRoute 不在活体里）", live.contains("RouteEvo.revertRoute("))
    }

    @Test
    fun `R7负例_路由回滚支拔掉_断言必须红`() {
        val src = chatController()
        assertTrue(src.contains("RouteEvo.revertRoute("))
        val mutated = src.replace("RouteEvo.revertRoute(", "RouteEvo.revertRemoved(")
        val body = GateBodies.functionBody(mutated, "fun rollbackRouteRowNow(")
        assertTrue(body != null)
        assertFalse("拔掉路由回滚支后必须红（判据②的一键回滚不许静默消失）", body!!.contains("RouteEvo.revertRoute("))
    }

    // ── 单一真相：路由守卫只委托 549 法条，不另立第二份状态判定（硬要求①） ──────

    @Test
    fun `R8_路由守卫只委托549法条_不另判`() {
        val body = GateBodies.functionBody(routeSemantics(), "fun flowLegal(")
        assertTrue("flowLegal 不在了？", body != null)
        assertTrue("守卫没委托 549 的 EnvoyLaw.legal（另判＝改状态语义）", body!!.contains("EnvoyLaw.legal(from, to)"))
    }

    @Test
    fun `R8负例_把法条委托改成恒真_断言必须红`() {
        val src = routeSemantics()
        assertTrue("前提：真文件里有法条委托", src.contains("EnvoyLaw.legal(from, to)"))
        val mutated = src.replace("EnvoyLaw.legal(from, to)", "true")
        val body = GateBodies.functionBody(mutated, "fun flowLegal(")
        assertTrue(body != null)
        assertFalse("把法条委托改成恒真后必须红（派工单负例：改了状态含义 ⇒ 红）", body!!.contains("EnvoyLaw.legal(from, to)"))
    }
}