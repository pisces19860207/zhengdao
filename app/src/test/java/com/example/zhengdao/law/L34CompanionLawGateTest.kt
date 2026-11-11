// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百七十一批 · 法典门禁 **L34 相处模式（判档与达成）**（法典卷十二；550 只落了行为，
// 制度条目按租约归 571）。本门禁的两条腿：
//
//   ① **条目与代码常量同源**（派工单判据①）：《法典》md 的 L34 条目里每一条可执行主张
//      （三档闭集、两条模式行字面、≤3 行、一轮只判一次、显式最高、三态闭集、改判三列线键、
//      改判账文件名）都必须与生产常量**逐字相等**——条目不许只写在文档里。
//      md 的找法与 `tools/check-law-gates.py` 同一口径（仓内 → docs → 私有侧）；
//      **md 不可得 ≠ 通过**：GitHub CI 没有私有侧 ⇒ 码侧判据照跑，md 对拍那半段明说没跑
//      （check-law-gates.py 判据 7 的 ⚠ 同一条哲学——看得见的跳过，不是静默跳过）。
//   ② **正负成对**（271 形制）：负例不用 md——用**从代码常量合成的标准条目**做基线，
//      逐条造违规（加第四档／加第四态／UNVERIFIABLE 当 PASS／改判缺依据／尾段超行），
//      每条违规必须被对拍器点红——证明对拍器不是恒绿。
//
// 碰生产：全部判据落在 `baopu.` 生产件上（CompanionMode／CompanionJudge／ChancellorVerdict／
// CompanionRejudgeLedger／MediatorWiring 源码），造违规红在生产常量与源码形状上。
package com.example.zhengdao.law

import com.example.zhengdao.baopu.CompanionDecision
import com.example.zhengdao.baopu.CompanionJudge
import com.example.zhengdao.baopu.CompanionMode
import com.example.zhengdao.baopu.agent.CompanionRejudgeLedger
import com.example.zhengdao.baopu.agent.CompanionRejudgeRow
import com.example.zhengdao.baopu.verify.ChancellorVerdict
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class L34CompanionLawGateTest {

    // ── 对拍器（判据①的本体；正例喂真 md 条目，负例喂合成条目的违规变体）──────────

    /** 三态闭集的规范字面（从生产枚举现场拼，不给手写机会）。 */
    private val verdictList: String = ChancellorVerdict.entries.joinToString("／") { it.name }

    /** 档位闭集的规范字面（同上）。 */
    private val modeList: String = CompanionMode.entries.joinToString("/") { it.wire }

    /** 改判三列的规范字面（从账行线键现场拼：原判（from）／新判（to）／依据（basis)）。 */
    private val rejudgeCols: String =
        "原判（${CompanionRejudgeLedger.COL_FROM}）／新判（${CompanionRejudgeLedger.COL_TO}）" +
            "／依据（${CompanionRejudgeLedger.COL_BASIS}）"

    /**
     * 对一份 L34 条目文本逐条核主张；返回问题列表（空＝同源成立）。
     * 每一条都是"md 说的"必须等于"码里写的"——不等即红。
     */
    private fun crosscheck(entry: String): List<String> {
        val problems = mutableListOf<String>()
        // 三态闭集：md 引用的三态清单必须与生产枚举逐字相等（多一项/少一项/换分隔符都算漂移）
        val verdictInMd = Regex("(PASS[／/]FAIL[／/]UNVERIFIABLE(?:[／/][A-Z_]+)*)").find(entry)?.value
        when {
            verdictInMd == null -> problems += "条目没有引用三态清单（PASS／FAIL／UNVERIFIABLE）"
            verdictInMd != verdictList -> problems += "三态清单漂移：条目写「$verdictInMd」，生产闭集是「$verdictList」"
        }
        // 档位闭集：同上（work/study，"第三档"是落点不是第三个值）
        val modeInMd = Regex("闭集两值\\s*([a-z]+(?:/[a-z]+)*)").find(entry)?.groupValues?.get(1)
        when {
            modeInMd == null -> problems += "条目没有引用档位闭集（work/study）"
            modeInMd != modeList -> problems += "档位闭集漂移：条目写「$modeInMd」，生产闭集是「$modeList」"
        }
        // 改判三列的线键：md 点名的列名必须与账行线键逐字相等
        val cols = Regex("原判（(\\w+)）／新判（(\\w+)）／依据（(\\w+)）").find(entry)?.groupValues
        when {
            cols == null -> problems += "条目没有点名改判三列（原判（from）／新判（to）／依据（basis)）"
            cols[1] != CompanionRejudgeLedger.COL_FROM ||
                cols[2] != CompanionRejudgeLedger.COL_TO ||
                cols[3] != CompanionRejudgeLedger.COL_BASIS ->
                problems += "改判三列线键漂移：条目写（${cols[0]}），账行线键是（$rejudgeCols）"
        }
        // 行数上限：md 的「≤N 行」必须等于生产常量
        val maxLines = Regex("[≤<]\\s*(\\d+)\\s*行").find(entry)?.groupValues?.get(1)?.toInt()
        when {
            maxLines == null -> problems += "条目没有写尾部行数上限（≤N 行）"
            maxLines != CompanionJudge.MAX_SEGMENT_LINES ->
                problems += "行数上限漂移：条目写 ≤$maxLines 行，生产常量是 ${CompanionJudge.MAX_SEGMENT_LINES}"
        }
        // 两条模式行的字面：md 引用的文案必须与生产常量逐字相等
        if (!entry.contains(CompanionJudge.LINE_WORK)) {
            problems += "条目缺工作档模式行字面：「${CompanionJudge.LINE_WORK}」"
        }
        if (!entry.contains(CompanionJudge.LINE_STUDY)) {
            problems += "条目缺学习档模式行字面：「${CompanionJudge.LINE_STUDY}」"
        }
        // 制度主张（谁判/判几次/谁最高/默认/判不出/账在哪）必须在场
        listOf(
            "判档权归左相", "达成判定归右相", "一轮只判一次", "显式要求", "最高优先",
            "默认＝工作", "判不出", "不许当 PASS", CompanionRejudgeLedger.FILE_NAME, "自治理中心",
        ).forEach { claim ->
            if (!entry.contains(claim)) problems += "条目缺制度主张：「$claim」"
        }
        return problems
    }

    /** 《法典》md 的找法（镜像 tools/check-law-gates.py：仓内 → docs → 私有侧；找不到＝null）。 */
    private fun codexMd(): File? =
        listOf("..", "../docs", "../../_zd-private/docs")
            .map { File(it) }
            .filter { it.isDirectory }
            .flatMap { dir -> (dir.listFiles { f -> f.isFile && f.name.startsWith("抱朴-法典") && f.name.endsWith(".md") })?.toList() ?: emptyList() }
            .firstOrNull { it.isFile }

    /** 从 md 原文切出 L34 的条目文本（表行起，到下一个卷/附标题止）。 */
    private fun l34Slice(md: String): String? {
        val lines = md.split("\n")
        val start = lines.indexOfFirst { it.trimStart().startsWith("| **L34** |") }
        if (start < 0) return null
        val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## ") }
        return lines.drop(start).take(if (end < 0) lines.size - start else end + 1).joinToString("\n")
    }

    /**
     * 从生产常量**合成**一份合规条目（负例的基线——它本身就是"合格条目至少要说什么"的机读版）。
     * 每个负例在它上面改一处，对拍器必须点红那一处。
     */
    private fun syntheticEntry(): String = "（合成条目）三档定义：①工作 ②学习 ③默认＝工作——判不出就落默认。" +
        "枚举闭集两值 $modeList。" +
        "判档权归左相，一轮只判一次；判档算式显式要求最高优先。" +
        "达成判定归右相：三态 $verdictList——UNVERIFIABLE 不许当 PASS。" +
        "system 尾部模式段恒常追加：「${CompanionJudge.LINE_WORK}」「${CompanionJudge.LINE_STUDY}」，≤${CompanionJudge.MAX_SEGMENT_LINES} 行。" +
        "改判落一行账（${CompanionRejudgeLedger.FILE_NAME}，$rejudgeCols），进自治理中心。"

    // ── 正例 ─────────────────────────────────────────────────────────────────

    @Test
    fun `L34_正例_条目与代码常量逐字对拍`() {
        val md = codexMd()
        if (md == null) {
            // CI（无私有侧）：md 对拍没跑——不是通过，是没跑（与 check-law-gates.py 判据 7 的 ⚠ 同一口径）。
            // 码侧判据在下面几个正例里照跑，合成条目的正反例在这里也照跑一遍兜底。
            assertTrue("合成合规条目必须过对拍（对拍器不许连自己的基线都拒）", crosscheck(syntheticEntry()).isEmpty())
            println("⚠ L34 md 对拍没跑：《法典》md 没找到（CI 无私有侧属预期；本地出现 ⇒ 查 md 找法）")
            return
        }
        val slice = l34Slice(md.readText(Charsets.UTF_8))
        assertTrue("md 里必须有 | **L34** | 条文行（md↔门禁同笔提交）", slice != null)
        val problems = crosscheck(slice!!)
        assertTrue("L34 条目必须与代码常量逐字同源，实得问题：$problems", problems.isEmpty())
    }

    @Test
    fun `L34_正例_三态是既有闭集恰三项_无第四态`() {
        assertEquals(
            "三态必须恰是 302 的闭集三项（不许新造第四态）",
            listOf("PASS", "FAIL", "UNVERIFIABLE"),
            ChancellorVerdict.entries.map { it.name },
        )
        // 档位闭集：两值，"默认＝工作"是落点不是第三值
        assertEquals(2, CompanionMode.entries.size)
        assertEquals(CompanionMode.WORK, CompanionMode.DEFAULT)
        assertEquals("work", CompanionMode.WORK.wire)
        assertEquals("study", CompanionMode.STUDY.wire)
        assertNull("自造档不渲染也不静默当某一档", CompanionMode.ofWire("unknown"))
    }

    @Test
    fun `L34_正例_判档权归左相_一轮只判一次_源码门禁`() {
        // CompanionJudge.judge 全仓主源码只出现在 MediatorWiring.onUserTurn（与 550 判据③同源，
        // 法典条目"一轮只判一次"的码侧锚点）。
        val callers = (File("src/main/java").walkTopDown() + File("../policy/src/main/java").walkTopDown())
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filter { it.readText(Charsets.UTF_8).contains("CompanionJudge.judge(") }
            .map { it.name }
            .toList()
        assertEquals(
            "判档调用点必须唯一且在左相接线层：$callers",
            listOf("MediatorWiring.kt"),
            callers,
        )
    }

    @Test
    fun `L34_正例_显式要求最高优先_三级顺序钉死`() {
        // 判档算式源序：explicitOf ?: phraseOf ?: taskTypeOf（改顺序即红——这是"显式最高"的码侧形状）。
        val judge = File("../policy/src/main/java/com/example/zhengdao/baopu/CompanionMode.kt")
            .readText(Charsets.UTF_8)
        val iExplicit = judge.indexOf("explicitOf(userText)")
        val iPhrase = judge.indexOf("phraseOf(userText)")
        val iTask = judge.indexOf("taskTypeOf(userText)")
        assertTrue("三级顺序必须在 judge 的算式里", iExplicit in 0 until iPhrase && iPhrase in 0 until iTask)
    }

    @Test
    fun `L34_正例_尾部模式段_两档字面与行数上限同源`() {
        assertEquals(3, CompanionJudge.MAX_SEGMENT_LINES)
        assertEquals(1, CompanionJudge.lines(CompanionMode.WORK).size)
        assertEquals(3, CompanionJudge.lines(CompanionMode.STUDY).size)
        assertTrue(CompanionJudge.segment(CompanionMode.WORK).contains(CompanionJudge.LINE_WORK))
        assertTrue(CompanionJudge.segment(CompanionMode.STUDY).contains(CompanionJudge.LINE_STUDY))
    }

    @Test
    fun `L34_正例_改判一行三列_账行线键与文件名同源`() {
        // 直接构造一行（不改账本进程态、不碰盘）：形状判据看的是 toJson 的键集与线键。
        val row = CompanionRejudgeRow(
            id = "cr:1:1",
            atMs = 1_800_000_000_000L,
            sessionId = 1L,
            turn = 2,
            from = CompanionMode.STUDY.wire,
            fromCn = CompanionMode.STUDY.cn,
            to = CompanionMode.WORK.wire,
            toCn = CompanionMode.WORK.cn,
            basis = "显式要求简短（点名「别啰嗦」）",
        ).toJson()
        assertEquals(
            "改判行必须恰好是 v/id/at/session/turn ＋ 三列（原判/新判/依据）＋两列的人话名",
            setOf("v", "id", "at", "session", "turn", "from", "fromCn", "to", "toCn", "basis"),
            row.keys().asSequence().toSet(),
        )
        assertEquals("study", row.optString(CompanionRejudgeLedger.COL_FROM))
        assertEquals("work", row.optString(CompanionRejudgeLedger.COL_TO))
        assertTrue("依据列必须真有内容", row.optString(CompanionRejudgeLedger.COL_BASIS).contains("别啰嗦"))
        assertEquals("相处改判.jsonl", CompanionRejudgeLedger.FILE_NAME)
    }

    // ── 负例（对拍器自证会红：基线改一处，必须点红那一处）────────────────────────

    @Test
    fun `L34_负例_第四档必拒`() {
        val bad = syntheticEntry().replace(modeList, "$modeList/chat")
        assertTrue("加第四档必须被点红", crosscheck(bad).any { it.contains("档位闭集漂移") })
    }

    @Test
    fun `L34_负例_第四态必拒`() {
        val bad = syntheticEntry().replace(verdictList, "$verdictList/PENDING")
        assertTrue("加第四态必须被点红", crosscheck(bad).any { it.contains("三态清单漂移") })
    }

    @Test
    fun `L34_负例_UNVERIFIABLE当PASS必拒`() {
        val bad = syntheticEntry().replace("不许当 PASS", "视同 PASS")
        assertTrue("UNVERIFIABLE 当 PASS 的条目必须被点红", crosscheck(bad).any { it.contains("不许当 PASS") })
    }

    @Test
    fun `L34_负例_改判缺依据列必拒`() {
        val bad = syntheticEntry()
            .replace("原判（from）／新判（to）／依据（basis）", "原判（from）／新判（to）")
        assertTrue("改判三列缺依据必须被点红", crosscheck(bad).any { it.contains("改判三列") })
    }

    @Test
    fun `L34_负例_尾段超行必拒`() {
        val bad = syntheticEntry().replace("≤3 行", "≤4 行")
        assertTrue("尾段行数上限漂移必须被点红", crosscheck(bad).any { it.contains("行数上限漂移") })
    }

    @Test
    fun `L34_负例_缺一条制度主张必拒`() {
        val bad = syntheticEntry().replace("判档权归左相", "判档权在模型手里")
        assertTrue("制度主张缺失必须被点红", crosscheck(bad).any { it.contains("判档权归左相") })
    }

    @Test
    fun `L34_负例_MediatorWiring观察口不许改判档`() {
        // 观察口是 571 加的只读事实口：判档结果必须仍由 CompanionJudge.judge 的返回值直接决定——
        // onUserTurn 里 observe/observer 只出现在"判完之后"。钉法：观察口的 invoke 必须在
        // companion 赋值之后（源码顺序），且观察口名下不许出现 CompanionJudge.judge 的第二次调用。
        val wiring = File("../policy/src/main/java/com/example/zhengdao/baopu/MediatorWiring.kt")
            .readText(Charsets.UTF_8)
        val iAssign = wiring.indexOf("companion = decided")
        val iObserve = wiring.indexOf("turnObserver?.invoke(")
        assertTrue("观察口必须在判档赋值之后（拿到的是既成事实）", iAssign in 0 until iObserve)
        assertEquals("观察口同一段不许再判档（一轮只判一次）", 1, Regex("CompanionJudge\\.judge\\(").findAll(wiring).count())
    }
}
