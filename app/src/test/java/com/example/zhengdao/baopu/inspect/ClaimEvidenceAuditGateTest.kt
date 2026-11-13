// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十七批 · 判据⑤⑥⑦的源级门禁。
//
// 扫的是**剥掉注释之后**的代码：KDoc 里解释口径的话不算证据，真调用才算。
// 三件事各带负例：门禁自身对坏样本要能咬（G0），不许恒绿；
// 只读性除了扫源码，还要真跑一轮对账后前后对比 git status（G2）。
package com.example.zhengdao.baopu.inspect

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaimEvidenceAuditGateTest {

    private val srcRel = "src/main/java/com/example/zhengdao/baopu/inspect/ClaimEvidenceAudit.kt"

    private fun code(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()
            .replace("\r\n", "\n")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    /** 一处出现就红的字面量：分三组，对应判据⑥的三条禁令。 */
    private val forbidden = listOf(
        // ① 不产风险判定结论
        "RiskDecision", "RiskVerdict(", "RiskMatrix.classify(",
        // ② 不下右相三态结论
        "ChancellorVerdict", "Status.PASS", "Status.FAIL", "UNVERIFIABLE", "verdict =", "verdict:",
        // ③ 只读、不改档、不碰盘、不跑进程
        "appendText(", "writeText(", "writeBytes(", "createNewFile(", "mkdirs(", "mkdir(",
        ".delete(", "deleteRecursively(", "ProcessBuilder(", "Runtime.getRuntime",
        "java.io.File", ".append(",
        "LedgerStore.append(", "PatrolLedger.append(", "MutualLedger.append(",
        "AskGovLedger.append(", "GapFeedback.record(", "DecisionLedger",
    )

    private fun forbiddenHits(src: String): List<String> = forbidden.filter { src.contains(it) }

    // ── G0：门禁自己得有牙（负例，防「A 干净就 true」恒绿） ───────────────────

    @Test
    fun `G0_门禁自身有牙_坏样本会被逐条检出`() {
        val bad = """
            fun bad() {
                RiskDecision.MUST_ASK
                ChancellorVerdict.PASS
                File("x").appendText("y")
                PatrolLedger.append(root, entry)
            }
        """.trimIndent()
        val hits = forbiddenHits(bad)
        assertTrue("风险判定字面量该被咬：$hits", hits.contains("RiskDecision"))
        assertTrue("三态字面量该被咬：$hits", hits.contains("ChancellorVerdict"))
        assertTrue("写盘字面量该被咬：$hits", hits.contains("appendText("))
        assertTrue("改档字面量该被咬：$hits", hits.contains("PatrolLedger.append("))
    }

    // ── G1：源码断言（判据⑥：不产 RiskDecision、不改档；判据⑤：只读） ────────

    @Test
    fun `G1_生产件零风险判定_零三态_零写口_零改档`() {
        val src = code(srcRel)
        assertTrue("生产件要真读到源码", src.contains("internal object ClaimEvidenceAudit"))
        val hits = forbiddenHits(src)
        assertTrue("机检件里出现了越界字面量：$hits", hits.isEmpty())
    }

    @Test
    fun `G1b_只复用现成词表_不引入IO与Android依赖`() {
        val imports = code(srcRel).lines().map { it.trim() }.filter { it.startsWith("import ") }
        assertTrue("要复用 345 的完成式词表：$imports", imports.any { it.contains("IntakeProbes") })
        val banned = listOf("java.io", "android.", "org.json", "baopu.agent", "baopu.ledger")
        banned.forEach { needle ->
            assertFalse("机检件不该依赖 $needle：$imports", imports.any { it.contains(needle) })
        }
    }

    @Test
    fun `G1c_缺口文案只读声称与要求_不带任何判定字段`() {
        val src = code(srcRel)
        assertTrue("缺口要能指第几件", src.contains("第 \$claimIndex 件"))
        assertTrue("缺口要能报证据过期", src.contains("证据过期"))
        assertFalse("机检不许自己写 verdict 字段", src.contains("val verdict"))
        assertFalse("机检不许自己写结论字段", src.contains("val conclusion"))
    }

    // ── G2：跑一轮完整对账，前后 git status 必须一致（判据⑤的行为级证据） ──────

    private fun repoRoot(): File {
        val here = File(".").canonicalFile
        return if (File(here, "app/src/main").isDirectory) here else here.parentFile ?: here
    }

    private fun gitStatus(): String {
        val p = ProcessBuilder("git", "status", "--porcelain")
            .directory(repoRoot())
            .redirectErrorStream(true)
            .start()
        val text = p.inputStream.bufferedReader().readText().replace("\r\n", "\n")
        val rc = p.waitFor()
        assertEquals("git status 必须真跑起来（退出码 0）", 0, rc)
        return text
    }

    /** 与行为测试同款的内存夹具；这里只为「跑一轮」用。 */
    private class EmptyEnv : ClaimEvidenceEnv {
        override val roundStartMs: Long = 1_000L
        override val hourOfDay: Int = 10
        override fun ledgerLines(): List<String> = emptyList()
        override fun commitShortIds(): List<String> = emptyList()
        override fun gitShowPaths(): Set<String> = emptySet()
        override fun mergedIntoMain(branch: String): Boolean = false
        override fun testXml(): TestXmlReading? = null
        override fun deviceEvidenceFiles(): List<String> = emptyList()
    }

    @Test
    fun `G2_跑完整对账前后git状态逐字一致`() {
        val before = gitStatus()
        val report = """
            - 已完成 甲模块
            - 已加文件 `tools/check-a.py`
            - 已合并 feat/xxx
            - 测试全绿
            - 真机验过 治理中心截图
        """.trimIndent()
        val out = ClaimEvidenceAudit.audit(report, EmptyEnv(), leftAllowsDisturb = false)
        assertEquals("五类声称都要抽到", 5, out.claims.size)
        assertEquals("五类证据全缺 ⇒ 五条缺口", 5, out.gaps.size)
        assertEquals("左相否决 ⇒ 只记账", LeftAdvice.LEDGER_ONLY, out.leftAdvice)
        assertEquals("跑一轮对账不许动 git 状态", before, gitStatus())
    }

    // ── G3：上报边界（机检只交清单，不替左右相拍板） ────────────────────────

    @Test
    fun `G3_产出只有缺口清单与建议_没有任何三态类型`() {
        val src = code(srcRel)
        assertTrue("交右相的字段要在", src.contains("chancellorInput"))
        assertTrue("左相建议的字段要在", src.contains("leftAdvice"))
        val bannedTypes = listOf("ChancellorVerdict", "ChancellorReport", "Status", "RiskVerdict")
        bannedTypes.forEach { t ->
            assertFalse("机检产出不该带 $t 类型", src.contains(": $t") || src.contains("<$t>"))
        }
    }
}
