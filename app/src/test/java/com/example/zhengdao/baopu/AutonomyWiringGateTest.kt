// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十六批 · **源码级门禁**（行为用例钉不住的三类事，只能对着文件钉）：
// 1. 三档那张表被悄悄加行／改档 ⇒ 红（[AutonomyChangeKind] 只有这一张表）；
// 2. C 档在执行壳里出现写口、或壳绕过既有写口自己 `writeText` ⇒ 红（判据层挡不住新增一条道）；
// 3. 同一口径被写成两套（206 退化词、192 的体积上限与禁用词、205 的档案表）⇒ 红。
//
// 另钉派工单的两条纪律：**不新增 `DecisionKind`**（§4）与**本批不碰 213 那两个文件、UI 一行没接**
// （红线：挂接点只写一页说明，由蓝总管后续接）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AutonomyWiringGateTest {

    private fun file(rel: String): File {
        val direct = File(rel)
        return if (direct.isFile) direct else File("app/$rel")
    }

    /** 目录也两种运行目录都认（同 [file]）。 */
    private fun dir(rel: String): File = File(rel).takeIf { it.isDirectory } ?: File("app/$rel")

    /** 去掉整行注释（本层的纪律写在注释里，门禁不能拿注释当证据）。 */
    private fun code(rel: String): String = file(rel).readText(Charsets.UTF_8).lines()
        .filterNot { l ->
            val t = l.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .joinToString("\n")

    private val baopuDir = "src/main/java/com/example/zhengdao/baopu"
    private val uiDir = "src/main/java/com/example/zhengdao/ui"
    private val autonomy = "$baopuDir/ReflectionAutonomy.kt"
    private val shell = "$baopuDir/AutonomySelfEdit.kt"
    private val closure = "$baopuDir/ReflectionCommitments.kt"
    private val delegation = "$baopuDir/ExecutionDelegation.kt"
    private val contract = "$baopuDir/SkillContract.kt"
    private val ledger = "$baopuDir/DecisionLedger.kt"
    private val runner = "$baopuDir/ReflectionRunner.kt"
    private val rules = "$baopuDir/ReflectionRules.kt"
    private val evalRunner = "$baopuDir/EvalRunner.kt"
    private val matrix = "$baopuDir/RiskMatrix.kt"
    private val chatController = "src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt"
    private val preset = "$baopuDir/BaopuPreset.kt"

    /** 本批新引入的类型名——"有没有接线"就看这些字面在不在别人的文件里。 */
    private val newSymbols = listOf(
        "AutonomyRules", "AutonomySelfEdit", "DelegationRules", "CommitmentStore", "ClosureRules",
    )

    // ── 1. 三档那张表 ────────────────────────────────────────────────────────

    @Test
    fun `三档表_16条改法与档位分布钉死`() {
        assertEquals(3, AutonomyTier.entries.size)
        assertEquals(16, AutonomyChangeKind.entries.size)
        assertEquals(5, AutonomyChangeKind.entries.count { it.tier == AutonomyTier.A_SELF_EDIT })
        assertEquals(5, AutonomyChangeKind.entries.count { it.tier == AutonomyTier.B_HUMAN_APPROVAL })
        assertEquals(6, AutonomyChangeKind.entries.count { it.tier == AutonomyTier.C_NEVER_AUTO })
        // 每条改法都要有 label（档案与卡面靠它对字），且 ofLabel 认不出时不许兜默认档
        assertTrue(AutonomyChangeKind.entries.all { it.label.isNotBlank() })
        assertEquals(AutonomyChangeKind.entries.toSet(), AutonomyChangeKind.entries.map { it.label }.map { AutonomyChangeKind.ofLabel(it) }.toSet())
        assertEquals(null, AutonomyChangeKind.ofLabel("随便写的改法"))
        // 表只在 ReflectionAutonomy.kt 里声明一次（别处再列一份＝两张表）
        val peers = dir(baopuDir).listFiles()?.filter { it.isFile && it.extension == "kt" }.orEmpty()
        val tierDeclares = peers.count { code("$baopuDir/${it.name}").contains("enum class AutonomyChangeKind") }
        assertEquals("三档表被抄成两张", 1, tierDeclares)
    }

    // ── 2. 执行壳：C 档没有写口，也不许自己开道 ─────────────────────────────

    @Test
    fun `执行壳_没有C档的字面_也没有裸写盘`() {
        val src = code(shell)
        val cNames = AutonomyChangeKind.entries
            .filter { it.tier == AutonomyTier.C_NEVER_AUTO }
            .map { it.name } + AutonomyTier.C_NEVER_AUTO.name
        val leaked = cNames.filter { src.contains(it) }
        assertTrue("壳里出现了 C 档字面（＝给安全边界留了第二条路）：$leaked", leaked.isEmpty())
        // 写盘只走既有唯一口：技能＝SkillStore、人设＝PersonaFiles／PersonaBackup
        for (banned in listOf("writeText", "appendText", ".delete()", "FileWriter", "outputStream")) {
            assertFalse("壳里自己开了写口：$banned", src.contains(banned))
        }
        assertTrue("壳没走 SkillStore 那条道", src.contains("SkillStore.apply"))
        assertTrue("壳没走人设那条道", src.contains("applyProposal"))
        // 三件事按顺序：写前备份 → 跑对拍 → 不过就回滚
        assertTrue(src.contains("SkillStore.restore"))
        assertTrue(src.contains("PersonaBackup.restore"))
        assertTrue(src.contains("AutonomyRules.decide"))
        // 第一行就查档：不是 AutoEdit 直接返回"没写"
        val run = src.substring(src.indexOf("fun run(proposal: AutonomyProposal)"))
        assertTrue("壳开头先写后判：${run.take(200)}", run.indexOf("AutonomyRules.decide") < run.indexOf("skillPath("))
    }

    // ── 3. 一套口径只写一处 ──────────────────────────────────────────────────

    @Test
    fun `对拍退化词_与233同一串_不另立同义词`() {
        assertEquals(listOf("变差", "产物少了"), AutonomyEvalGate.REGRESSION_WORDS)
        val c = code(contract)
        assertTrue("233 那边换了词，这里没跟着换", AutonomyEvalGate.REGRESSION_WORDS.all { c.contains(it) })
        // 206 结果列真实产出的那行（EvalRunner 的"产物少了"）必须被本层认出来
        assertTrue(code(evalRunner).contains("产物少了"))
        // 判据只有一处：ReflectionAutonomy.kt 不许自己再数一遍退化词
        assertFalse(code(autonomy).contains("产物少了"))
    }

    @Test
    fun `上限与禁用词_复用192_不各写一套`() {
        assertEquals(SkillWritePolicy.BODY_MAX_BYTES, AutonomyRules.BODY_MAX_BYTES)
        val src = code(autonomy)
        assertTrue("体积上限自己写了个数", src.contains("SkillWritePolicy.BODY_MAX_BYTES"))
        assertTrue("索引上限自己写了个数", src.contains("SkillRules.INDEX_CAP"))
        assertTrue("禁用词自己抄了一份词表", src.contains("SkillWritePolicy.forbiddenFindings"))
        assertTrue("追加判据自己写了一遍", src.contains("PersonaRules.appendOnly"))
        // 字节数与索引上限不许在判据文件里出现字面量（出现即"两套口径"）
        assertFalse(src.contains("2 * 1024"))
        assertFalse(src.contains("2048"))
        assertTrue("判据层复制了索引上限", Regex("""INDEX_CAP\s*=\s*\d""").find(src) == null)
    }

    @Test
    fun `两张进化档案不合并_205那张表列数没动`() {
        assertTrue(
            "205 的档案文件名被改：加列复用旧表会让它静默错位",
            code(rules).contains("FILE_NAME = \"进化档案.md\""),
        )
        assertTrue(code(closure).contains("FILE_NAME = \"进化档案-承诺.md\""))
        assertEquals(6, ReflectionArchive.header().lines()[1].count { it == '|' } - 1)
        assertEquals(7, CommitmentArchive.header().lines()[1].count { it == '|' } - 1)
        // 本层的判据不读 205 那张表（读错位比不读更坏）
        assertFalse(code(closure).contains("ReflectionArchive.parse"))
    }

    // ── 4. 派工单 §4：不新增 DecisionKind ────────────────────────────────────

    @Test
    fun `自治用既有auto_act记账_没有新造kind`() {
        assertTrue(DecisionKind.entries.any { it.wire == "auto_act" })
        assertTrue(DecisionKind.entries.any { it.wire == "rollback_of" })
        val invented = DecisionKind.entries.filter { it.wire.startsWith("autonomy") || it.wire.contains("self_edit") }
        assertTrue("派工单 §4 说不新增 kind，实际新增了：$invented", invented.isEmpty())
        // 壳里只用了这两个 kind
        val src = code(shell)
        assertTrue(src.contains("DecisionKind.AUTO_ACT"))
        assertTrue(src.contains("DecisionKind.ROLLBACK_OF"))
        Regex("""DecisionKind\.([A-Z_]+)""").findAll(src).map { it.groupValues[1] }.toSet()
            .let { used -> assertTrue("壳里还写了别的 kind：$used", used.all { it == "AUTO_ACT" || it == "ROLLBACK_OF" }) }
        // 记账判据没去改 208 那张表
        assertFalse(code(ledger).contains("AutonomyChangeKind"))
    }

    // ── 5. 红线：不碰 213 那两个文件，UI 一行没接 ────────────────────────────

    @Test
    fun `红线_213那两个文件与本批零关联`() {
        for (rel in listOf(chatController, preset)) {
            val src = code(rel)
            val hit = newSymbols.filter { src.contains(it) }
            assertTrue("$rel 引用了本批的层（213 的改动还没合）：$hit", hit.isEmpty())
        }
    }

    @Test
    fun `挂接点未接线_本批只交一页说明`() {
        // 诚实性门禁：报告里说"由蓝总管后续接"，源码就必须真没接——半接比不接更坏。
        val root = dir(uiDir)
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> val s = f.readText(Charsets.UTF_8); newSymbols.any { s.contains(it) } }
            .map { it.name }.toList()
        assertTrue("UI 已经偷偷接上了：$offenders", offenders.isEmpty())
        assertFalse("反思器已接线（报告说不接）", code(runner).contains("AutonomyRules.decide"))
        assertFalse("反思器偷偷记承诺账", code(runner).contains("CommitmentStore"))
    }

    // ── 6. 下放层不判档（219 那张表只有一张）──────────────────────────────────

    @Test
    fun `下放只吃矩阵结论_不当第二张表`() {
        val src = code(delegation)
        assertTrue("档位来源不是 219 的结论", src.contains("f.verdict"))
        for (banned in listOf("RiskMatrix.classify", "RiskTable", "classify(")) {
            assertFalse("下放层自己判档＝第二张表：$banned", src.contains(banned))
        }
        // 它只读档位与三条事实，不数关键词
        assertFalse(src.contains("Regex("))
        assertFalse(src.contains("contains(\"删"))
        // 表只有一张：矩阵文件里不该出现下放层的结论类型
        assertFalse(code(matrix).contains("DelegationVerdict"))
    }

    @Test
    fun `判据层不摸盘_纯函数`() {
        for (rel in listOf(autonomy, delegation)) {
            val src = code(rel)
            val name = file(rel).name
            for (banned in listOf("readText", "appendText", "writeText", "File(")) {
                assertFalse("$name 是判据层，不该摸盘：$banned", src.contains(banned))
            }
        }
        // 闭合层有一薄 IO 壳（照 205 房型），但判据对象本身不许摸盘
        val src = code(closure)
        val from = src.indexOf("internal object ClosureRules")
        val to = src.indexOf("internal class CommitmentStore")
        assertTrue("闭合层的房型变了（判据/IO 两段还在吗）", from in 0 until to)
        val judge = src.substring(from, to)
        val touched = listOf("readText", "appendText", "writeText", "isFile", "mkdirs").filter { judge.contains(it) }
        assertTrue("判据里摸盘：$touched", touched.isEmpty())
    }
}
