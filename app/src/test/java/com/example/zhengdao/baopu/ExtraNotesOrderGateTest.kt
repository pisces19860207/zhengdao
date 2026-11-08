// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 五百三十二批 · **extraNotes 顺序门禁**（只钉顺序，不改行为；生产 diff = 0）。
//
// 背景：ContextAssembler.kt:387 是 `extraNotes.forEach { if (it.isNotBlank()) add(it) }`
// ⇒ extraNotes 的顺序就是列表顺序，位次会改变模型看到的位置（越靠后越"新"）。
// 既有门禁只断言"含某字符串/有没有某注"，**不断言排第几** ⇒ 位次可静默漂移。
//
// 当前实际顺序（2026-11-08 在 b860fd6d 上量出，注替身按此序喂）：
//   CURRENT 段行下标（current 本体占行 0）：
//     行1  视觉通道说明（vision.note；组装器内置在所有 extraNotes 之前）  ContextAssembler.kt:386
//     行2  图片读失败说明（turnNotes 首项，轮开跑赋值）                  RunEngine.kt:2174/2183
//     行3  知识库自动注入 kbNote（turnNotes 追加）                       RunEngine.kt:2200
//     行4  任务契约前置读行 precheck（turnNotes 追加）                   RunEngine.kt:2286
//     行5  影响提示 ImpactMailbox.drain()（引擎拼接第 1 路）             RunEngine.kt:3225
//     行6  转译段 MediatorWiring.promptNotes()（引擎拼接第 2 路）        RunEngine.kt:3226
//     行7  补问回灌 AnswerReflow.notes()（引擎拼接第 3 路）              RunEngine.kt:3227
//     行8  经验注 listOfNotNull(turnLearnNote)（引擎拼接第 4 路，恒最末）RunEngine.kt:3230
//
// 钉两层（RunEngine 在 JVM 单测里跑不起来，照本仓门禁范式用源码形状钉，同款见
// LearnContextNoteGateTest／ImpactGateTest）：
//   A 行为级：真跑 ContextAssembler.assemble，CURRENT 段**逐行下标等值**断言
//     （不是 contains——每个期望值钉死在具体行号上）；
//   B 源级：引擎侧五路拼接与 turnNotes 装配序用源码锚点下标钉死（锚点全仓唯一，防恒真）。
// 负例：把两个注调换 ⇒ 两层门都必须红（红例输出见交付报告）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.VisionRules.ImagePayload
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraNotesOrderGateTest {

    // ── 注替身：按生产链当前实际顺序排列；单行、互不相同（好归因） ──────────
    private val visionNote = "（当前通道不能看图，已省略 1 张图片）"
    private val noteImage = "（图片没读出来：本轮按纯文本处理，请让用户用文字说一遍图里内容）"
    private val noteKb = "【知识库自动注入】命中片段"
    private val noteContract = "【任务契约·前置读】预算单档"
    private val noteImpact = "【影响提示】刚改了一个文件"
    private val noteReframe = "【转译段】用户原话已按保事实改写"
    private val noteReflow = "【补问回灌】上一轮没答完的部分"
    private val noteLearn = "上次同类：删目录 ⇒ 学习判了不打扰"

    /** extraNotes 生产序（RunEngine.kt:3224-3230 + turnNotes 装配序 2174→2200→2286）。 */
    private val productionOrder = listOf(
        noteImage, noteKb, noteContract, noteImpact, noteReframe, noteReflow, noteLearn,
    )

    private val currentText = "本轮输入本体：用户原话"

    private fun assembleWith(
        extraNotes: List<String>,
        withVisionNote: Boolean,
    ) = ContextAssembler.assemble(
        persona = emptyList(),
        facts = HardFacts(workspacePath = "/ws", model = "m"),
        stickyMemories = emptyList(),
        newMemories = emptyList(),
        history = emptyList(),
        current = currentText,
        images = if (withVisionNote) {
            listOf(ImagePayload(dataUrl = "data:image/png;base64,QUJD", bytes = 64))
        } else {
            emptyList()
        },
        visionSupported = false,
        extraNotes = extraNotes,
    )

    private fun currentLines(prompt: com.example.zhengdao.baopu.AssembledPrompt): List<String> =
        prompt.segments.first { it.kind == SegmentKind.CURRENT }.text.lines()

    /**
     * **下标等值断言**（判据②；硬编码期望值，不是 contains）：
     * 七路注替身必须从 [firstNoteLine] 行起逐行原样占位，且注后不许再有行。
     */
    private fun pinNoteLines(lines: List<String>, firstNoteLine: Int) {
        val expected = productionOrder
        assertEquals(
            "行0 必须是本轮输入本体",
            currentText,
            lines[0],
        )
        expected.forEachIndexed { i, note ->
            assertEquals("行${firstNoteLine + i} 必须是「$note」（位次漂移）", note, lines[firstNoteLine + i])
        }
        assertEquals(
            "注之后不许再有行（多一行 = 有注被挤位/重复）",
            firstNoteLine + expected.size,
            lines.size,
        )
    }

    // ── 门禁 A：行为级——生产序喂注，逐行下标等值 ──────────────────────────

    @Test
    fun `门禁A_有视觉说明时_七路注依次占行2到行8_逐行下标等值`() {
        val lines = currentLines(assembleWith(productionOrder, withVisionNote = true))
        assertEquals("行1 必须是视觉通道说明（vision.note 恒在所有 extraNotes 之前）", visionNote, lines[1])
        pinNoteLines(lines, firstNoteLine = 2)
    }

    @Test
    fun `门禁A2_无视觉说明时_七路注整体前移到行1到行7_相对顺序不变`() {
        val lines = currentLines(assembleWith(productionOrder, withVisionNote = false))
        pinNoteLines(lines, firstNoteLine = 1)
    }

    // ── 门禁 A 负例：调换两个注 ⇒ 门必须红 ─────────────────────────────────

    @Test
    fun `门禁A负例_知识注入与经验注调换_下标等值断言必须红`() {
        val swapped = listOf(
            noteImage, noteLearn, noteContract, noteImpact, noteReframe, noteReflow, noteKb,
        )
        val lines = currentLines(assembleWith(swapped, withVisionNote = true))
        val red = try {
            pinNoteLines(lines, firstNoteLine = 2)
            throw IllegalStateException("负例没红——调换顺序后下标等值断言竟然放行了")
        } catch (e: AssertionError) {
            // 红例输出落 test-results 的 system-out（交付报告贴证据用）
            println("[532红例·行为级] ${e.message}")
            e
        }
        assertTrue(
            "红例必须咬在行3（知识注入的原位）上，实际消息：${red.message}",
            red.message.orEmpty().contains("行3"),
        )
        assertTrue(
            "红例消息必须点名期望的那路注，实际消息：${red.message}",
            red.message.orEmpty().contains(noteKb),
        )
    }

    // ── 门禁 B：源级——RunEngine 拼接序与 turnNotes 装配序下标钉死 ──────────

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    /** 去注释的源码（结构性断言用；免得门禁被改注释绕过——同款范式见 LearnContextNoteGateTest）。 */
    private fun codeOnly(src: String): String =
        src.split("\n").joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    private val engine = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"

    /** 引擎侧五路拼接锚点（生产序；各锚点在去注释源码里必须唯一，防 contains 恒真）。 */
    private val concatAnchors = listOf(
        "com.example.zhengdao.baopu.ImpactMailbox.drain()",
        "com.example.zhengdao.baopu.MediatorWiring.promptNotes()",
        "AnswerReflow.notes(currentRoundId)",
        "listOfNotNull(turnLearnNote)",
    )

    /**
     * 源级拼接门：按 [expectedOrder] 检查引擎侧拼接段，返回 null=过，否则=红例消息
     * （供负例复用同一把尺——门与负例不各写一套判据）。
     */
    private fun checkConcatOrder(code: String, expectedOrder: List<String>): String? {
        val start = code.indexOf("extraNotes = extraNotes +")
        if (start < 0) return "找不到引擎侧拼接入口「extraNotes = extraNotes +」"
        val tail = "listOfNotNull(turnLearnNote)"
        val end = code.indexOf(tail, start)
        if (end < 0) return "找不到拼接尾「$tail」"
        val slice = code.substring(start, end + tail.length)
        var pos = -1
        for (anchor in expectedOrder) {
            val i = slice.indexOf(anchor)
            if (i < 0) return "拼接段里找不到「$anchor」（被挪走或改名）"
            if (i <= pos) return "顺序漂移：「$anchor」的下标 $i 不在前一路（下标 $pos）之后"
            pos = i
        }
        return null
    }

    @Test
    fun `门禁B_引擎侧拼接序按下标递增钉死_锚点全仓唯一`() {
        val code = codeOnly(repoFile(engine).readText())
        // 锚点唯一性：出现第二次就是第二份拼接口（顺序门会被人从另一条道绕过去）
        for (anchor in concatAnchors + "extraNotes = extraNotes +") {
            assertEquals("拼接锚点「$anchor」必须全仓唯一（防第二份拼接口绕门）", 1, countOf(code, anchor))
        }
        assertEquals(
            "引擎侧五路拼接顺序漂移",
            null,
            checkConcatOrder(code, concatAnchors),
        )
    }

    @Test
    fun `门禁B2_turnNotes装配序_图片说明先于知识注入先于契约前置读`() {
        val code = codeOnly(repoFile(engine).readText())
        val imageNote = code.indexOf("turnNotes = listOf(")
        val kb = code.indexOf("turnNotes = turnNotes + kbNote")
        val precheck = code.indexOf("turnNotes = turnNotes + precheck")
        assertEquals("图片说明赋值点必须恰两处（读图全炸＋逐张跳过原因）", 2, countOf(code, "turnNotes = listOf("))
        assertEquals("知识注入追加点必须唯一", 1, countOf(code, "turnNotes = turnNotes + kbNote"))
        assertEquals("契约前置读追加点必须唯一", 1, countOf(code, "turnNotes = turnNotes + precheck"))
        assertTrue(
            "turnNotes 装配序漂移：图片说明(下标 $imageNote) → 知识注入($kb) → 契约前置读($precheck) 必须按下标递增",
            imageNote in 0 until kb && kb < precheck,
        )
    }

    // ── 门禁 B 负例：期望序调换 ⇒ 源级门必须红 ─────────────────────────────

    @Test
    fun `门禁B负例_影响提示与转译段期望序调换_源级门必须红`() {
        val code = codeOnly(repoFile(engine).readText())
        val swapped = listOf(
            concatAnchors[1], concatAnchors[0], concatAnchors[2], concatAnchors[3],
        )
        val red = checkConcatOrder(code, swapped)
        println("[532红例·源级] $red")
        assertTrue("负例没红——期望序调换后源级门竟然放行", red != null)
        assertTrue(
            "红例必须点名漂移的那一路，实际消息：$red",
            red.orEmpty().contains("顺序漂移"),
        )
    }

    private fun countOf(haystack: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(haystack).count()
}
