// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百零五批接线 · **源码门禁**：读侧行进模型上下文钉的是结构性事实，不是行为——
// RunEngine 在 JVM 单测里跑不起来（Android 框架），接线照本仓门禁范式用源码形状钉
// （同款范式见 LearnReadGateTest／QuietWiringGateTest）。
//
// 钉四件事：
//   G1 读不许改三态：LearnContextNote.kt 源码里不许出现任何写口——铁律②的源级形态；
//   G2 判卷三态零接触：不许引用 verify/ChancellorVerdict/DraftGate（PASS/FAIL/UNVERIFIABLE
//      的判卷面与这一行毫无关系）；
//   G3 RunEngine 接线钉死：轮开跑一次＋extraNotes 尾追加＋字段，缺一不可；
//   G4 地板复用 400 的尺：必须走 LearnReadJudge.recent（不许抄第二把尺、不许自带地板）。
package com.example.zhengdao.baopu

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnContextNoteGateTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun read(rel: String): String = repoFile(rel).readText()

    /** 去注释的源码（结构性断言用；免得门禁被改注释绕过）。 */
    private fun codeOnly(src: String): String =
        src.split("\n").joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    private val note = "src/main/java/com/example/zhengdao/baopu/LearnContextNote.kt"
    private val engine = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"

    // ── G1：读不许改三态（铁律②的源级形态） ────────────────────────────────

    @Test
    fun `G1_本文件源码不许出现任何写口`() {
        val code = codeOnly(read(note))
        val banned = listOf(
            ".append(", // 经验账写口（store.append）
            "refreshTable", // 经验表刷新（写侧 298 的活）
            "enqueueOnly", // 意见队列入队
            "recordDraftVeto", // 草稿查表记账
            "DecisionLedger.", // 208 台簿写口
            "SelfRulingLedger.note", // 自决单写口
            "onUserSignal", // 学习信号入口（写侧）
        )
        for (b in banned) {
            assertFalse("读侧不许写三态：发现写口「$b」", code.contains(b))
        }
        assertTrue("IO 壳只许读全本（LearnRead.rows）", code.contains("LearnRead.rows()"))
    }

    // ── G2：判卷三态（PASS/FAIL/UNVERIFIABLE）零接触 ───────────────────────

    @Test
    fun `G2_不许引用判卷三态的面`() {
        val code = codeOnly(read(note))
        val banned = listOf(
            "ChancellorVerdict",
            "DraftGate",
            "ClaimVerdict",
            "baopu.verify",
        )
        for (b in banned) {
            assertFalse("经验行只进 prompt 文本，不许碰判卷面：发现「$b」", code.contains(b))
        }
    }

    // ── G3：RunEngine 接线钉死 ──────────────────────────────────────────────

    @Test
    fun `G3_轮开跑一次_extraNotes尾追加_字段三处都要接线`() {
        val code = codeOnly(read(engine))
        assertTrue(
            "轮开跑必须读一次经验账（LearnContextNote.roundNote(resumed)）",
            code.contains("LearnContextNote.roundNote(resumed)"),
        )
        assertTrue(
            "extraNotes 必须尾追加该行（listOfNotNull(turnLearnNote)）",
            code.contains("listOfNotNull(turnLearnNote)"),
        )
        assertTrue(
            "本轮经验行必须是字段（每步同一份＝前缀判据）",
            code.contains("private var turnLearnNote: String?"),
        )
    }

    // ── G4：地板复用 400 的尺（不许抄第二把） ───────────────────────────────

    @Test
    fun `G4_地板必须走LearnReadJudge_recent_不许自带第二把尺`() {
        val code = codeOnly(read(note))
        assertTrue(
            "样本<5 不生效必须复用 400 的 recent（同一把尺，298 的教训：两把尺必红一条）",
            code.contains("LearnReadJudge.recent("),
        )
        assertFalse("不许重写窗口尺（windowSignals 归 LearnReadJudge）", code.contains("windowSignals("))
        assertFalse("不许自定义地板常量（MIN_SAMPLES 归 LearnReadJudge）", code.contains("const val MIN_SAMPLES"))
        assertTrue(
            "行模板「上次同类：」必须钉在本文件里（派工单 405 原文）",
            code.contains("上次同类："),
        )
    }
}
