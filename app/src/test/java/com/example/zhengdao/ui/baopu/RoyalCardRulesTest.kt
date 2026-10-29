// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第二百七十七批 §判据②③ 与 §负例（卡面无默认／把"未核实"写成"真"）。
// 每条负例一个用例，名字里带"必须拒"，红了就知道踩的是哪一条。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoyalCardRulesTest {

    private fun line(
        kind: RoyalLineKind,
        text: String,
        truth: RoyalTruth = RoyalTruth.TRUE,
    ) = RoyalLine(kind, text, truth)

    /** 合规三行（改什么／为什么／影不影响你）。 */
    private fun threeLines() = listOf(
        line(RoyalLineKind.WHAT, "把 L7 的误报行降一档"),
        line(RoyalLineKind.WHY, "上周 12 次里 8 次是误报"),
        line(RoyalLineKind.IMPACT, "不影响你已有的活，下次少问你一次"),
    )

    /** 一组含默认项的选项。 */
    private fun options(
        first: String = "ack",
        second: String = "later",
    ) = listOf(
        RoyalOption(first, "照办", isDefault = true),
        RoyalOption(second, "稍后"),
    )

    private fun card(
        lines: List<RoyalLine> = threeLines(),
        options: List<RoyalOption> = options(),
    ) = RoyalCard(key = "t:1", lines = lines, options = options)

    // ── 判据：三行卡成不成立 ──────────────────────────────────────────────

    @Test
    fun `判据_三行齐全且有默认项就可渲染`() {
        val c = card()
        assertTrue("三行＋默认项应当可渲染，实际问题：${RoyalCardGate.check(c)}", RoyalCardGate.renderable(c))
        assertEquals(RoyalTruth.TRUE, c.mark)
    }

    @Test
    fun `判据_一行都没有也要拒`() {
        val c = card(lines = emptyList())
        assertTrue(RoyalCardGate.check(c).contains(RoyalReject.NO_LINE))
        assertFalse(RoyalCardGate.renderable(c))
    }

    // ── 负例：4 行 ────────────────────────────────────────────────────────

    @Test
    fun `负例_四行卡必须拒渲染`() {
        val four = threeLines() + line(RoyalLineKind.WHAT, "第四行：再补一句")
        val c = card(lines = four)
        assertTrue("四行必须拒：${RoyalCardGate.check(c)}", RoyalCardGate.check(c).contains(RoyalReject.TOO_MANY_LINES))
        assertFalse(RoyalCardGate.renderable(c))
    }

    @Test
    fun `负例_一行里塞换行也要拒_不许把四行伪装成一行`() {
        val sneaky = listOf(
            line(RoyalLineKind.WHAT, "改一档\n还改一档"),
            line(RoyalLineKind.WHY, "误报多"),
            line(RoyalLineKind.IMPACT, "不影响你"),
        )
        val c = card(lines = sneaky)
        assertTrue(
            "换行＝多行，必须拒：${RoyalCardGate.check(c)}",
            RoyalCardGate.check(c).contains(RoyalReject.LINE_BROKEN),
        )
        assertFalse(RoyalCardGate.renderable(c))
    }

    @Test
    fun `负例_单行太长也要拒_不许把整段塞进一行`() {
        val long = listOf(
            line(RoyalLineKind.WHAT, "改".repeat(RoyalCardGate.MAX_LINE_CHARS + 1)),
            line(RoyalLineKind.WHY, "误报多"),
            line(RoyalLineKind.IMPACT, "不影响你"),
        )
        val c = card(lines = long)
        assertTrue(RoyalCardGate.check(c).contains(RoyalReject.LINE_TOO_LONG))
    }

    @Test
    fun `负例_空白行必须拒`() {
        val blank = threeLines().toMutableList()
        blank[1] = line(RoyalLineKind.WHY, "   ")
        assertTrue(RoyalCardGate.check(card(lines = blank)).contains(RoyalReject.EMPTY_LINE))
    }

    // ── 负例：无默认项 ────────────────────────────────────────────────────

    @Test
    fun `负例_没有默认项必须拒`() {
        val noDefault = listOf(RoyalOption("a", "照办"), RoyalOption("b", "稍后"))
        val c = card(options = noDefault)
        assertTrue(
            "无默认项必须拒：${RoyalCardGate.check(c)}",
            RoyalCardGate.check(c).contains(RoyalReject.NO_DEFAULT),
        )
        assertFalse(RoyalCardGate.renderable(c))
    }

    @Test
    fun `负例_两个默认项等于没有默认_必须拒`() {
        val twoDefaults = listOf(
            RoyalOption("a", "照办", isDefault = true),
            RoyalOption("b", "稍后", isDefault = true),
        )
        assertTrue(RoyalCardGate.check(card(options = twoDefaults)).contains(RoyalReject.MULTI_DEFAULT))
    }

    @Test
    fun `负例_一个选项都没有必须拒`() {
        assertTrue(RoyalCardGate.check(card(options = emptyList())).contains(RoyalReject.NO_OPTION))
    }

    @Test
    fun `负例_选项id重复必须拒`() {
        val dup = listOf(RoyalOption("a", "照办", isDefault = true), RoyalOption("a", "稍后"))
        assertTrue(RoyalCardGate.check(card(options = dup)).contains(RoyalReject.DUPLICATE_OPTION))
    }

    // ── 真假标注：只能算出来，不许外部写成"真" ──────────────────────────

    @Test
    fun `判据_有一行未核实整卡就标未核实_不许写成真`() {
        val mixed = listOf(
            line(RoyalLineKind.WHAT, "把 L7 的误报行降一档"),
            line(RoyalLineKind.WHY, "上周 12 次里 8 次是误报", RoyalTruth.UNVERIFIED),
            line(RoyalLineKind.IMPACT, "不影响你已有的活"),
        )
        val c = card(lines = mixed)
        assertEquals("有一行未核实，整卡必须标未核实", RoyalTruth.UNVERIFIED, c.mark)
        assertEquals(RoyalTruth.UNVERIFIED, RoyalCardGate.mark(mixed))
        // 反过来：全真才许标真（负例的对偶）
        assertEquals(RoyalTruth.TRUE, RoyalCardGate.mark(threeLines()))
    }

    @Test
    fun `负例_有假主张的卡不得上御案`() {
        val withFalse = listOf(
            line(RoyalLineKind.WHAT, "把 L7 的误报行降一档"),
            line(RoyalLineKind.WHY, "上周 12 次里 8 次是误报", RoyalTruth.FALSE),
            line(RoyalLineKind.IMPACT, "不影响你已有的活"),
        )
        val c = card(lines = withFalse)
        assertTrue(
            "有假主张必须拒：${RoyalCardGate.check(c)}",
            RoyalCardGate.check(c).contains(RoyalReject.FALSE_CLAIM),
        )
        assertFalse(RoyalCardGate.renderable(c))
        assertEquals(RoyalTruth.FALSE, c.mark)
    }

    @Test
    fun `判据_一行都没有时标注最保守_是未核实不是真`() {
        assertEquals(RoyalTruth.UNVERIFIED, RoyalCardGate.mark(emptyList()))
    }

    // ── 一次说全 ──────────────────────────────────────────────────────────

    @Test
    fun `判据_多处不合规要一次说全_不让改一轮才发现下一个`() {
        val four = threeLines() + line(RoyalLineKind.WHY, "第四行")
        val c = card(lines = four, options = listOf(RoyalOption("a", "照办")))
        val problems = RoyalCardGate.check(c)
        assertTrue(problems.contains(RoyalReject.TOO_MANY_LINES) && problems.contains(RoyalReject.NO_DEFAULT))
        // 日志那行也要把两件事都写出来（验收靠 grep 这句）
        val line = RoyalCardGate.rejectLine(c)
        assertTrue(line.contains(RoyalReject.TOO_MANY_LINES.cn) && line.contains(RoyalReject.NO_DEFAULT.cn))
    }
}
