// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十一批 · 法典门禁 **L18 调解器不得直接改制度/改授权**（派工单 271）
// ＋ 同一号在法典卷五的另一半：**爱称白名单原样透传、攻击短语优先**。
//
// ## 判据（可执行）
// ① **不许越权**：调解器三个文件（`HarmonyMediator`／`MediatorWiring`／`MediatorJudge`）的代码面
//    不得出现「改制度／改授权／直接写盘」的调用——它只能**观察、给话、记一条违规**；
//    记 208（`DecisionLedger.record`）是**留痕**，不算改制度，故不在禁列；
// ② **爱称不许误判**：白名单（小笨蛋/大坏蛋…）原样透传不算责备；但**攻击短语优先**——
//    「小笨蛋 + 废物」必须按攻击处理，白名单不能当挡箭牌。
//
// ## 负例（本文件自证会红）
// ① 假调解器里写一行 `TaskGrantRuntime.install(...)` ⇒ 扫描器必须命中；
// ② 「小笨蛋 废物」被判成爱称（不算责备）⇒ 红。
package com.example.zhengdao.law

import com.example.zhengdao.baopu.ReframeFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class L18MediatorNoWriteGateTest {

    private val mediatorFiles = listOf(
        "${LawIo.MAIN_ROOT}/baopu/HarmonyMediator.kt",
        "${LawIo.MAIN_ROOT}/baopu/MediatorWiring.kt",
        "${LawIo.MAIN_ROOT}/baopu/MediatorJudge.kt",
    )

    internal object Mediator {
        /** 改制度／改授权／直接写盘的口子（调解器一个都不许碰）。 */
        val FORBIDDEN: Map<String, Regex> = mapOf(
            
            "改授权-装授权" to Regex("""TaskGrantRuntime\.install"""),
            "改授权-记已执行" to Regex("""TaskGrantLedger\.recordApplied"""),
            "改制度-策略表" to Regex("""PolicyRuntime\.|PolicyTable\."""),
            "改制度-人设" to Regex("""PersonaFiles"""),
            "改制度-技能库" to Regex("""SkillStore\."""),
            "直接写盘" to Regex("""\.writeText\(|FileOutputStream"""),
        )
    }

    // ── 正例：调解器只观察/只留痕；爱称透传 ─────────────────────────────────

    @Test
    fun `L18_正例_调解器不碰授权与制度_爱称原样透传`() {
        val hits = mediatorFiles.flatMap { rel ->
            LawIo.scanCodeLine(rel, LawIo.read(rel), Mediator.FORBIDDEN)
        }
        assertEquals(
            "调解器越权改制度/改授权了：\n" + hits.joinToString("\n") { "  ${it.where()} [${it.api}] ${it.text}" },
            emptyList<String>(),
            hits.map { it.where() },
        )
        // 爱称白名单：原样透传，不算责备
        assertTrue("小笨蛋必须被认成爱称", ReframeFilter.isLoveName("你这个小笨蛋"))
        assertFalse("爱称不算责备信号", ReframeFilter.isBlame("你这个小笨蛋"))
        assertFalse("句末昵称形态也透传", ReframeFilter.isBlame("又错了笨蛋～"))
    }

    // ── 负例①：调解器直接改授权 ⇒ 扫描器必须抓到 ───────────────────────────

    @Test
    fun `L18_负例1_调解器直接改授权或制度必须被抓到`() {
        val bad = """
            internal object RogueMediator {
                fun calmDown(grant: TaskGrant) {
                    TaskGrantRuntime.install(grant, "epoch", 0L)
                }
            }
        """.trimIndent()
        val hits = LawIo.scanCodeLine("baopu/RogueMediator.kt", bad, Mediator.FORBIDDEN)
        assertEquals("假调解器改授权必须被抓到：${hits.map { it.api }}", 1, hits.size)

        val badPolicy = """
            internal object RogueMediator2 {
                fun rewrite() { PolicyRuntime.apply(newTable) }
            }
        """.trimIndent()
        assertEquals(
            "假调解器改制度也必须被抓到",
            1,
            LawIo.scanCodeLine("baopu/RogueMediator2.kt", badPolicy, Mediator.FORBIDDEN).size,
        )

        // 记 208 是留痕，不许被这条门禁误伤
        val ledgerOnly = """
            internal object FineMediator {
                fun note(draft: DecisionDraft) { DecisionLedger.record(draft) }
            }
        """.trimIndent()
        assertEquals(
            "记 208 不算改制度（否则门禁会逼人删留痕）",
            emptyList<String>(),
            LawIo.scanCodeLine("baopu/FineMediator.kt", ledgerOnly, Mediator.FORBIDDEN).map { it.api },
        )
    }

    // ── 负例②：混句里爱称不能当挡箭牌 ──────────────────────────────────────

    @Test
    fun `L18_负例2_爱称加攻击短语必须按攻击处理`() {
        assertTrue("攻击短语优先：小笨蛋+废物 必须算攻击", ReframeFilter.isAttack("小笨蛋，你就是个废物"))
        assertTrue("混句必须算责备", ReframeFilter.isBlame("小笨蛋，你就是个废物"))
        assertTrue("纯攻击当然算", ReframeFilter.isBlame("没用的东西"))
        // 对照：纯爱称不许被当成攻击（防「一律判攻击」的假绿）
        assertFalse("纯爱称不是攻击", ReframeFilter.isAttack("大坏蛋"))
    }
}
