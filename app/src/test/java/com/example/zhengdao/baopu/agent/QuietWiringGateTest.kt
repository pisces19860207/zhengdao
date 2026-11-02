// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 三百三十批接线 · RunEngine 侧的**源码门禁**：钉的是结构性事实，不是行为——
// RunEngine 在 JVM 里起不来（Android SQLite），引擎层的接线照本仓门禁范式用源码形状钉
// （同款范式见 CompressTriggerWiringGateTest／PlanContractWiringTest）。
//
// 钉五件事：
//   G1 两个弹卡口（askUser／askGroup）出卡前都过闸——静默中不弹；
//   G2 授权从用户原话开、到期有边沿账——`QuietMode` 从此生产有引用（本批的起因）；
//   G3 补问走**原始出口**（不再过闸）——再过一次闸，检查点那件会被重新压回去，"必现"落空；
//   G4 问人账只记真弹的（noteAsk 挪到原始出口）——被免打扰压下的卡没问过人；
//   G5 轮次入口就是检查点（她回话 ⇒ 积压按序现身）。
package com.example.zhengdao.baopu.agent

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietWiringGateTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun read(rel: String): String = repoFile(rel).readText()

    /** 去注释的源码（结构性断言用；免得门禁被改注释绕过）。 */
    private fun codeOnly(src: String): String =
        src.split("\n").joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    private val engine = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"

    private fun engineCode(): String = codeOnly(read(engine))

    /** 从 [start] 那行起、到 [end] 首次出现为止的源码切片（含端点前的内容）。 */
    private fun slice(src: String, start: String, end: String): String {
        val s = src.indexOf(start)
        require(s >= 0) { "找不到切片起点：$start" }
        val e = src.indexOf(end, s + start.length)
        require(e > s) { "找不到切片终点：$end" }
        return src.substring(s, e)
    }

    // ── G1：两个弹卡口出卡前都过闸 ──────────────────────────────────────

    @Test
    fun `G1_askUser与askGroup出卡前都过免打扰闸_压下就如实回没人答`() {
        val code = engineCode()
        val askUserBody = slice(code, "fun askUser(", "private fun askUserRaw(")
        val askGroupBody = slice(code, "fun askGroup(", "private fun askGroupRaw(")
        assertTrue(
            "askUser 出卡前必须先问闸（静默中不弹、记因免打扰延后）",
            askUserBody.contains("if (quietDefer(req)) return null"),
        )
        assertTrue(
            "askGroup 出卡前必须先问闸（整组一起判，不许拆一半）",
            askGroupBody.contains("if (quietDefer(req)) return null"),
        )
        assertTrue("压下之后放行前要先补问积压", askUserBody.contains("drainQuietQueue()"))
    }

    // ── G2：授权从用户原话开（QuietMode 生产引用的来源） ──────────────────

    @Test
    fun `G2_授权从用户原话开_到期有边沿账_静默状态可查`() {
        val note = slice(engineCode(), "private fun noteQuietGrant(", "fun stop(")
        assertTrue("授权必须从那句话开（认不出触发词＝没有授权）", note.contains("QuietMode.grantFromText("))
        assertTrue("开授权要落账（哪件事/凭什么话/多久/到几点）", note.contains("QuietMode.authRecord("))
        assertTrue("到期要有边沿账（跨过 4 小时那一刻说一声）", note.contains("QuietMode.justExpired("))
        assertTrue("静默状态要可查（一行读数）", note.contains("quietGate.stateLine("))
    }

    // ── G3：补问走原始出口，不再过闸 ────────────────────────────────────

    @Test
    fun `G3_补问走原始出口_不许再过一次闸`() {
        val drain = slice(engineCode(), "private fun drainQuietQueue(", "private fun quietShape(")
        assertTrue("单问题补问必须走 askUserRaw", drain.contains("askUserRaw(item.request)"))
        assertTrue("问题组补问必须走 askGroupRaw", drain.contains("askGroupRaw(item.request)"))
        assertTrue(
            "补问体里不许再问闸（过了闸，检查点那件会被重新压回去，必现落空）",
            !drain.contains("quietDefer("),
        )
    }

    // ── G4：问人账只记真弹的 ────────────────────────────────────────────

    @Test
    fun `G4_noteAsk挪到原始出口_被免打扰压下的卡不占问人账`() {
        val code = engineCode()
        val raw = slice(code, "private fun askUserRaw(", "fun answerChoice(")
        assertTrue("真弹那一刻才记账", raw.contains("PlanContractBoard.noteAsk()"))
        val gated = slice(code, "fun askUser(", "private fun askUserRaw(")
        assertTrue(
            "过闸那段不许记账（没问过人）",
            !gated.contains("PlanContractBoard.noteAsk()"),
        )
    }

    // ── G5：轮次入口就是检查点 ──────────────────────────────────────────

    @Test
    fun `G5_她回话时先开授权再按序补问`() {
        val entry = slice(engineCode(), "userText?.let { learnSignals += SkillRefine.learnMarkersIn(it) }", "val workspacePath")
        assertTrue("轮次入口要过一遍授权", entry.contains("noteQuietGrant(sessionId, it)"))
        assertTrue("轮次入口要当检查点补问", entry.contains("drainQuietQueue()"))
        assertTrue("先开授权再补问（她刚说『别弹了』⇒ 只有检查点/超上限那几件现身）",
            entry.indexOf("noteQuietGrant(") < entry.indexOf("drainQuietQueue()"))
    }

    // ── 确认卡路是有意豁免（必弹档），钉成文档 ────────────────────────────

    @Test
    fun `确认卡路的豁免是写下来的_不是漏接`() {
        val raw = read(engine)
        assertTrue(
            "确认卡＝CardClass.IRREVERSIBLE（必弹档，quietSuppresses=false），豁免必须留档，" +
                "免得下一个人把它当成漏接又给接上",
            raw.contains("免打扰**不闸这条路**"),
        )
    }
}
