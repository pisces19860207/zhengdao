// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百三十六批 · 「提示补段」的**行为级门禁**（纯 JVM，零模型）。
//
// 判据逐条（派工单 §判据）：
//   ① 三段都在（断言）：认错认到步骤／写作立场中立／不对人下判断，按锚点逐个真查；
//   ② 总增量 ≤300 字符：新块（纪律段末尾三段）按码点数，超一个字符都红；
//   ③ 每段 ≤4 行；
//   ④ 文案里出现「情绪/安慰/柔和语气」类指令即红（防与调解器双重定义——语气归 238 的
//      `MediatorWiring.PROMPT_CONTRACT`，A 内核只许写内容口径）；
//   ⑤ VERSION v16→v17 且 KDoc 记了理由（点名调解器）；
//   ⑥ 旧纪律一字未动（抽三条既有锚点断言仍在）。
//
// 为什么增量测在**新块**上而不是整段 persona 的长度差：长度差要把 git 基线搬进单测（脆），
// 而三段本来就是连续的一块（纪律段末尾）——块即增量，测它自己就是测增量。
package com.example.zhengdao.baopu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaThreeSectionsTest {

    private val ws = "/storage/emulated/0/Download/证道"

    /** 三段锚点（字面量即判据，改文案不许动锚——动了就是重写本批）。 */
    private val anchorAdmit = "**认错认到步骤**"
    private val anchorNeutral = "**写作立场中立**"
    private val anchorNoJudge = "**不对人下判断**"

    private fun persona(): String = BaopuPreset.personaText(ws)

    /** 纪律段：从「## 纪律」到「## 输出纪律（答案先行）」之前。 */
    private fun discipline(): String {
        val text = persona()
        val from = text.indexOf("## 纪律")
        assertTrue("找不到纪律段", from >= 0)
        val end = text.indexOf("## 输出纪律（答案先行）", from)
        assertTrue("纪律段收尾定位失败（输出纪律节不见了？）", end > from)
        return text.substring(from, end)
    }

    /** 新块 = 纪律段里从第一个锚点所在 bullet 行起，到下一节标题之前（三段连续追加在纪律段末尾）。 */
    private fun newBlock(): String {
        val d = discipline()
        // 锚点从行首的 "- " 一起找：只切锚点会把首个 bullet 的 "- " 切掉半截（行数就少一条）
        val at = d.indexOf("- $anchorAdmit")
        assertTrue("缺第①段（$anchorAdmit）——它必须还在纪律段里", at >= 0)
        // 纪律段在 persona 中部：段尾之后紧跟下一节的空行与标题（"\n# "）——必须切在标题前，
        // 否则会把「## 输出纪律」的首行算进块里，行数/增量都虚高。
        val nextHeading = d.indexOf("\n#", at)
        val end = if (nextHeading >= 0) d.substring(at, nextHeading) else d.substring(at)
        return end.trimEnd()
    }

    // ── 判据①：三段都在，且按 ①②③ 顺序 ────────────────────────────────────

    @Test
    fun `三段都在_且顺序是认错_中立_不判断`() {
        val block = newBlock()
        assertTrue("缺第②段（$anchorNeutral）", block.contains(anchorNeutral))
        assertTrue("缺第③段（$anchorNoJudge）", block.contains(anchorNoJudge))
        val i1 = block.indexOf(anchorAdmit)
        val i2 = block.indexOf(anchorNeutral)
        val i3 = block.indexOf(anchorNoJudge)
        assertTrue("三段顺序被打乱", i1 < i2 && i2 < i3)
        // 第①段必须给全三件事（派工单原话：哪一步错、影响什么、下一步怎么改）
        listOf("哪一步错", "影响什么", "下一步怎么改").forEach {
            assertTrue("认错口径缺这一件：$it", block.contains(it))
        }
        // 第②段的两半：最佳版本 ＋ 写动机不写说教
        assertTrue("立场中立缺「最佳版本」", block.contains("最佳版本"))
        assertTrue("立场中立缺「动机」", block.contains("动机"))
        assertTrue("立场中立缺「不写说教」", block.contains("不写说教"))
        // 第③段的两半：不知道就说不知道 ＋ 不替用户下心理结论
        assertTrue("缺「不知道就说不知道」", block.contains("不知道就说不知道"))
        assertTrue("缺「不替用户下心理结论」", block.contains("心理结论"))
    }

    // ── 判据③：每段 ≤4 行 ────────────────────────────────────────────────────

    @Test
    fun `每段不超过四行`() {
        val bullets = newBlock().split("\n").filter { it.startsWith("- **") }
        assertEquals("新块必须恰好三个 bullet（一段一条）", 3, bullets.size)
        // 逐段数行：bullet 行 ＋ 其后非 bullet 的续行
        val lines = newBlock().split("\n")
        val starts = lines.indices.filter { lines[it].startsWith("- **") }
        starts.forEachIndexed { idx, start ->
            val end = if (idx + 1 < starts.size) starts[idx + 1] else lines.size
            val count = end - start
            assertTrue("第 ${idx + 1} 段 $count 行 > 上限 4 行", count <= 4)
        }
    }

    // ── 判据②：总增量 ≤300 字符（码点，与全仓 charCount 口径一致）───────────

    @Test
    fun `总增量不超过300字符`() {
        val n = newBlock().codePointCount(0, newBlock().length)
        assertTrue("三段共 $n 字符 > 300（派工单硬上限）", n <= 300)
    }

    // ── 判据④：情绪/安慰/柔和语气类指令 = 红 ────────────────────────────────

    /** 禁词表：全是「怎么说话」的指令词——说话方式归调解器（238），A 内核只写内容。 */
    private val forbidden = listOf(
        "温和", "温柔", "柔和", "亲切", "体贴", "共情", "安慰", "安抚",
        "情绪", "语气", "口吻", "腔调", "道歉", "抱歉", "对不起",
    )

    private fun hits(text: String): List<String> = forbidden.filter { text.contains(it) }

    @Test
    fun `新段零语气指令_防与调解器双重定义`() {
        val found = hits(newBlock())
        assertTrue("新段出现情绪/安慰/柔和语气类指令（双重定义）：$found", found.isEmpty())
    }

    @Test
    fun `负例自证_禁词表真的会咬`() {
        // 拿一句"看着合理"的旧式写法喂给扫描器：必须被抓（否则上面那条绿是假绿）
        val bad = "- **认错**：要温和地道歉，安抚用户情绪。\n"
        assertEquals("负例该抓到 温和/道歉/安抚/情绪 四个", listOf("温和", "安抚", "情绪", "道歉").toSet(),
            hits(bad).toSet())
    }

    // ── 判据⑤：VERSION 16→17 并记理由 ────────────────────────────────────────

    @Test
    fun `版本升到17_KDoc记了理由并点名调解器`() {
        assertEquals("文案版本必须 v16→v17", 17, BaopuPreset.VERSION)
        val src = presetSource()
        assertTrue("KDoc 必须有 v17 行且带日期", src.contains("v17（2026-11-14"))
        assertTrue("v17 理由必须点名调解器（语气归它，本批只补内容口径）", src.contains("调解器"))
    }

    // ── 判据⑥：旧纪律一字未动 ────────────────────────────────────────────────

    @Test
    fun `旧纪律锚点仍在_新段是追加不是替换`() {
        val d = discipline()
        listOf(
            "**不要编造**", "**拒绝要说明白**", "**别报工具名**",
            "**你的记忆可以提案改，但 A 内核（本节）永远不改**",
        ).forEach {
            assertTrue("旧纪律被吃掉：$it", d.contains(it))
        }
        // 新块必须排在 A 内核那条之后（追加在段尾，纪律段自己的老顺序不动）
        assertTrue(
            "新段必须追加在段尾",
            d.indexOf("**你的记忆可以提案改") < d.indexOf(anchorAdmit),
        )
    }

    @Test
    fun `全persona仍不超token上限`() {
        val tokens = TokenEstimator.estimate(persona())
        assertTrue(
            "补了三段之后不许超人设上限（截断＝纪律看不全）tokens=$tokens",
            tokens <= PersonaRules.PERSONA_TOKEN_LIMIT,
        )
    }

    private fun presetSource(): String {
        val roots = listOf(
            File("src/main/java/com/example/zhengdao/baopu/BaopuPreset.kt"),
            File("app/src/main/java/com/example/zhengdao/baopu/BaopuPreset.kt"),
        )
        return roots.firstOrNull { it.isFile }
            ?.readText(Charsets.UTF_8)
            ?: error("找不到 BaopuPreset.kt（cwd=${File("").absolutePath}）")
    }
}
