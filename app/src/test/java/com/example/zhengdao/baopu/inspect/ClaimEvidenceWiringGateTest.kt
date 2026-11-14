// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百三十五批 · 源级门禁（判据⑤＋三处接线＋只读边界）。
//
// 扫的是**剥掉注释之后**的代码：KDoc 里解释口径的话不算证据，真调用才算。
// 每条断言都配一个坏样本（G0），不许「A 干净就 true」式的恒绿。
package com.example.zhengdao.baopu.inspect

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaimEvidenceWiringGateTest {

    private val wiringRel = "src/main/java/com/example/zhengdao/baopu/inspect/ClaimEvidenceWiring.kt"
    private val nocturnalRel = "src/main/java/com/example/zhengdao/baopu/inspect/NocturnalAuto.kt"
    private val controllerRel = "src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt"
    private val audit627Rel =
        "app/src/main/java/com/example/zhengdao/baopu/inspect/ClaimEvidenceAudit.kt"

    private fun read(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText().replace("\r\n", "\n")

    /** 与 627 的 G1 同款：剥掉注释，注释里解释口径的话不算证据，真代码才算。 */
    private fun code(rel: String): String = strip(read(rel))

    /** 生产调用点：剥注释后真正出现的 `ClaimEvidenceAudit.audit(` 次数。 */
    private fun callSites(src: String): Int =
        Regex("""ClaimEvidenceAudit\s*\.\s*audit\s*\(""").findAll(src).count()

    private val bannedWrites = listOf(
        "DecisionLedger.append(", "SupervisionDiary.append(", "PatrolLedger.append(",
        "MutualLedger.append(", "ProcessBuilder(", "Runtime.getRuntime",
    )

    /** 与 [code] 同一把剥刀，供 G0 的坏样本走同一条路径（不许自己验自己）。 */
    private fun strip(text: String): String = text
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    // ── G0：门禁自己得有牙（负例） ──────────────────────────────────────────

    @Test
    fun `G0_门禁自身有牙_坏样本会被逐条检出`() {
        val noCall = """
            object Fake { fun rows() = emptyList<Any>() }
            // ClaimEvidenceAudit.audit(  ← 注释里的假调用
        """.trimIndent()
        assertEquals("注释里的假调用不该算生产调用点", 0, callSites(strip(noCall)))

        val withCall = "fun go() = ClaimEvidenceAudit.audit(report, env, true)"
        assertEquals("真调用要数到 1", 1, callSites(strip(withCall)))

        val dirty = "fun bad() { SupervisionDiary.append(c, e); DecisionLedger.append(r, x) }"
        val hits = bannedWrites.filter { dirty.contains(it) }
        assertTrue("写被检查账要被咬：$hits", hits.contains("SupervisionDiary.append("))
        assertTrue("写起居注要被咬：$hits", hits.contains("DecisionLedger.append("))
    }

    // ── G1：判据⑤ 装配层必须真调 ClaimEvidenceAudit（生产调用点 ≥1） ───────

    @Test
    fun `G1_装配层生产调用点至少一处`() {
        val src = code(wiringRel)
        assertTrue("装配层要真读到源码", src.contains("internal fun claimEvidenceRowsOf"))
        val n = callSites(src)
        assertTrue("装配层必须调 ClaimEvidenceAudit.audit（生产调用点 ≥1），实得 $n", n >= 1)
        assertTrue("调用要落在落行判据里", src.contains("val out = ClaimEvidenceAudit.audit("))
    }

    @Test
    fun `G1b_读数接口三格齐全_金吾卫不自己连库`() {
        val src = code(wiringRel)
        assertTrue("账行查询", src.contains("fun ledgerLines(): List<String>"))
        assertTrue("commit 摘要", src.contains("fun commitShortIds(): List<String>"))
        assertTrue("测试 XML 四个数", src.contains("fun testXml(): TestXmlReading?"))
        assertFalse("装配层不许自己跑 git", src.contains("ProcessBuilder("))
        assertFalse("装配层不许自己连库执行 SQL", src.contains("execSQL(") || src.contains("rawQuery("))
    }

    // ── G2：① 暗卫排期真接上（432 调度 ＋ 479 两级刹车之后） ──────────────

    @Test
    fun `G2_暗卫排期真接上_在479两级刹车之后`() {
        val src = code(nocturnalRel)
        val call = src.indexOf("runClaimEvidenceAudit(")
        assertTrue("暗卫趟里要有对账入口的调用", call >= 0)
        assertTrue("左相准许位要显式传入", src.contains("AUDIT_LEFT_ALLOWS"))
        val brake = src.indexOf("MaintBrake.blocked(")
        val evo = src.indexOf("LowRiskEvo.gate(")
        assertTrue("479 急停闸要在前", brake >= 0)
        assertTrue("479 灰度闸要在前", evo >= 0)
        assertTrue("对账必须排在两级刹车之后（不新开调度）", call > brake && call > evo)
        assertTrue(
            "时刻必须由本趟 nowMs 注入（不另读时钟）",
            Regex("""runClaimEvidenceAudit\(ctx, root, nowMs,""").containsMatchIn(src),
        )
    }

    // ── G3：③ 建议页签第三类行真接上，typeLabel 字面值 ────────────────────

    @Test
    fun `G3_建议页签第三类行真接上`() {
        val src = code(controllerRel)
        assertTrue("页签要读最近一轮结果", src.contains("ClaimEvidenceRowStore.latest()"))
        assertTrue("行要带第三类类型签", src.contains("R.string.decision_type_audit"))
        assertTrue("页签函数注释要写三类型", read(controllerRel).contains("99 建议＋100 效果＋635 对账三类型"))
        assertEquals("类型签常量", "对账", ClaimEvidenceWiring.AUDIT_TYPE_LABEL)

        val zh = read("src/main/res/values/strings_baopu_audit.xml")
        val en = read("src/main/res/values-en/strings_baopu_audit.xml")
        assertTrue("中文类型签必须是「对账」", zh.contains(">对账</string>"))
        assertTrue("英文也要有对应键", en.contains("decision_type_audit"))
        assertTrue("两份键一一对应", zh.contains("decision_type_audit") && en.contains("decision_type_audit"))
    }

    // ── G4：只读边界（对被检查对象只读，自身产出另起一本账） ───────────────

    @Test
    fun `G4_装配层不写被检查对象_自身产出只落独立账本`() {
        val src = code(wiringRel)
        val hits = bannedWrites.filter { src.contains(it) }
        assertTrue("装配层不许出现写被检查账/起居注/跑进程的字面量：$hits", hits.isEmpty())
        assertTrue("自身产出的写口只落自己的账本", src.contains("auditLedgerFile(root).appendText("))
        assertTrue("读被检查账只用原文行", src.contains("f.readLines()"))
        assertFalse(
            "不许覆盖写任何文件（只许追加自己的账，测试 XML／git 状态一律不碰）",
            src.contains(".writeText(") || src.contains(".writeBytes(") || src.contains(".delete("),
        )
    }

    // ── G5：627 判据层零改动（被检查对象前后不变，git 级证据） ─────────────

    private fun repoRoot(): File {
        val here = File(".").canonicalFile
        return if (File(here, "app/src/main").isDirectory) here else here.parentFile ?: here
    }

    @Test
    fun `G5_627判据层工作树零改动`() {
        val p = ProcessBuilder("git", "diff", "--quiet", "HEAD", "--", audit627Rel)
            .directory(repoRoot()).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        val rc = p.waitFor()
        assertEquals("627 判据层不许有工作树改动（rc=1 就是被改了）：$out", 0, rc)
        assertTrue("627 判据层是本单的被检查对象，不许改", File(repoRoot(), audit627Rel).isFile)
    }
}
