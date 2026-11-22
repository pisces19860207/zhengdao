// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百九十五批 · 判据①–⑥：四要素缺一判未知、清单空降未知、带锚清单判矛盾、
// 房间数反推红/拒、同输入同结论、来源锚字面校验。
package com.example.zhengdao.baopu.state.capacity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapacityRuleTest {

    /** 四要素齐备的基线断言（两间房住三人，全部要素写明）。 */
    private fun complete(): CapacityClaim = CapacityClaim(
        quantity = "三人",
        allocation = "一人一间、余一人客厅沙发",
        scope = setOf(Place.ROOM, Place.LIVING_ROOM, Place.SOFA),
        period = "那一夜",
    )

    private fun anchored(token: String, quote: String = "原文写有「$token」"): SourceAnchor =
        SourceAnchor(quote, token)

    // —— 判据①：四要素各缺一次 ⇒ 四次 unknown，且 missing 指对 ——
    @Test
    fun `判据一_四要素各缺一次得四次未知且missing指对`() {
        val cases = mapOf(
            CapacityFactor.QUANTITY to complete().copy(quantity = null),
            CapacityFactor.ALLOCATION to complete().copy(allocation = null),
            CapacityFactor.SCOPE to complete().copy(scope = null),
            CapacityFactor.PERIOD to complete().copy(period = null),
        )
        assertEquals("四要素各缺一次＝四例", 4, cases.size)
        for ((factor, claim) in cases) {
            val result = CapacityRule.judge(claim)
            assertEquals("缺 ${factor.label} ⇒ 未知", CapacityVerdict.UNKNOWN, result.verdict)
            assertEquals("缺 ${factor.label} ⇒ missing 精确指对", listOf(factor), result.missing)
            assertTrue("未知态不许带排除清单", result.excludedAlternatives.isEmpty())
        }
    }

    @Test
    fun `判据一_空串与空集也算缺`() {
        val blank = CapacityRule.judge(complete().copy(quantity = "   "))
        assertEquals(CapacityVerdict.UNKNOWN, blank.verdict)
        assertEquals(listOf(CapacityFactor.QUANTITY), blank.missing)
        val emptyScope = CapacityRule.judge(complete().copy(scope = emptySet()))
        assertEquals(CapacityVerdict.UNKNOWN, emptyScope.verdict)
        assertEquals(listOf(CapacityFactor.SCOPE), emptyScope.missing)
    }

    @Test
    fun `判据一_缺多项时missing按固定次序全列`() {
        val result = CapacityRule.judge(
            CapacityClaim(quantity = null, allocation = null, scope = null, period = "那一夜")
        )
        assertEquals(
            listOf(CapacityFactor.QUANTITY, CapacityFactor.ALLOCATION, CapacityFactor.SCOPE),
            result.missing,
        )
    }

    // —— 判据②：四要素齐 + 清单空 ⇒ unknown（不许判 contradiction） ——
    @Test
    fun `判据二_四要素齐清单空判未知不许矛盾`() {
        val result = CapacityRule.judge(complete())
        assertEquals(CapacityVerdict.UNKNOWN, result.verdict)
        assertEquals("四要素齐 ⇒ missing 空", emptyList<CapacityFactor>(), result.missing)
        assertTrue("未知态排除清单为空", result.excludedAlternatives.isEmpty())
    }

    @Test
    fun `判据二_四要素齐但只有正面陈述也判支持`() {
        val claim = complete().copy(confirmations = listOf(anchored("满")))
        val result = CapacityRule.judge(claim)
        assertEquals(CapacityVerdict.SUPPORT, result.verdict)
    }

    // —— 判据③：四要素齐 + 清单非空且每项有锚 ⇒ contradiction ——
    @Test
    fun `判据三_清单非空且每项有锚判矛盾且清单逐字留存`() {
        val claim = complete().copy(
            alternatives = listOf(
                ExcludedAlternative("一人一间", anchored("满")),
                ExcludedAlternative("客厅沙发", anchored("满")),
                ExcludedAlternative("地板", anchored("满")),
            ),
        )
        val result = CapacityRule.judge(claim)
        assertEquals(CapacityVerdict.CONTRADICTION, result.verdict)
        assertEquals("清单逐字留存且有序", listOf("一人一间", "客厅沙发", "地板"), result.excludedAlternatives)
        assertTrue("矛盾态 missing 空", result.missing.isEmpty())
    }

    @Test
    fun `判据三_清单含无锚项整张作废降未知`() {
        val claim = complete().copy(
            alternatives = listOf(
                ExcludedAlternative("一人一间", anchored("满")),
                ExcludedAlternative("地板", SourceAnchor("原文没写这个字", "满")),
            ),
        )
        val result = CapacityRule.judge(claim)
        assertEquals(CapacityVerdict.UNKNOWN, result.verdict)
        assertTrue("作废后不许残留清单", result.excludedAlternatives.isEmpty())
    }

    // —— 判据④：以房间数反推容量 ⇒ 红/拒 ——
    @Test
    fun `判据四_房间数反推判红抛异常`() {
        val claim = complete().copy(
            quantity = "两间房最多两人",
            countingBasis = CountingBasis.ROOM_COUNT_DERIVED,
        )
        val error = runCatching { CapacityRule.judge(claim) }.exceptionOrNull()
        assertTrue("以房间数反推必须抛异常：$error", error is IllegalArgumentException)
        assertEquals(CapacityRule.ROOM_COUNT_REJECT, error?.message)
    }

    @Test
    fun `判据四_正规数量来源不被误拒`() {
        val result = CapacityRule.judge(complete())
        assertEquals(CountingBasis.EXPLICIT, complete().countingBasis)
        assertEquals(CapacityVerdict.UNKNOWN, result.verdict)
    }

    // —— 判据⑤：无 IO 无随机 + 确定性（同输入同结论） ——
    @Test
    fun `判据五_同输入同结论逐字一致`() {
        val claim = complete().copy(
            alternatives = listOf(ExcludedAlternative("一人一间", anchored("满"))),
        )
        val a = CapacityRule.judge(claim)
        val b = CapacityRule.judge(claim)
        assertEquals(a, b)
        assertEquals(a.line(), b.line())
    }

    // —— 判据⑥的口径自检：来源锚字面校验（每项要带来源锚） ——
    @Test
    fun `来源锚_原文含字才成立`() {
        assertTrue(anchored("满").valid)
        assertTrue(SourceAnchor("站内已满", "满").valid)
        assertTrue(!SourceAnchor("原文写有「无」", "满").valid)
        assertTrue(!SourceAnchor("", "满").valid)
        assertTrue(!SourceAnchor("站内已满", "  ").valid)
    }

    @Test
    fun `结论对象_矛盾态无清单构造即抛`() {
        val error = runCatching {
            CapacityResult(CapacityVerdict.CONTRADICTION, emptyList(), emptyList(), "x")
        }.exceptionOrNull()
        assertTrue("矛盾态无清单必须当场拒：$error", error is IllegalArgumentException)
    }
}
