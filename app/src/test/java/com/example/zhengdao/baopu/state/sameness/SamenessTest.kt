// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
package com.example.zhengdao.baopu.state.sameness

import com.example.zhengdao.baopu.state.casting.CastingCard
import com.example.zhengdao.baopu.state.casting.CastingRefs
import com.example.zhengdao.baopu.state.casting.RefSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SamenessTest {

    private fun card(role: String, values: Map<String, List<String>> = emptyMap()): CastingCard =
        CastingCard(role, "P1", false, values)

    private fun fullCard(role: String): CastingCard {
        val values = LinkedHashMap<String, List<String>>()
        CastingRefs.CARD_FIELDS.filter { it.words().isNotEmpty() }.forEach { ref ->
            values[ref.id] = listOf(ref.words().first())
        }
        return card(role, values)
    }

    private fun altered(base: CastingCard, vararg fieldIds: String): CastingCard {
        val values = LinkedHashMap(base.values)
        fieldIds.forEach { id ->
            val words = CastingRefs.field(id).words()
            require(words.size >= 2) { "$id 至少需要两档才能造差异夹具" }
            values[id] = listOf(words[1])
        }
        return base.copy(values = values)
    }

    private data class Fixtures(
        val base: CastingCard,
        val obvious: CastingCard,
        val close: CastingCard,
        val nearly: CastingCard,
    )

    private fun fixtures(): Fixtures {
        val base = fullCard("BASE")
        return Fixtures(
            base = base,
            obvious = altered(
                base, "face.shape", "face.eyes", "face.nose", "face.lips", "body.frame", "body.muscle",
            ),
            close = altered(base, "face.eyes", "face.nose"),
            nearly = altered(base, "part.pec.volume"),
        )
    }

    @Test
    fun `三件齐_差异指数来源均可调用`() {
        val fixtures = fixtures()
        val items = diffItems(fixtures.obvious, fixtures.close)
        assertTrue(items.isNotEmpty())
        assertTrue(samenessIndex(fixtures.obvious, fixtures.close) in 0..100)
        assertTrue(items.all { sourceNote(it).fieldId == it.fieldId })
    }

    @Test
    fun `判据六_三对夹具指数单调且差异数单调`() {
        val fixtures = fixtures()
        val obviousIndex = samenessIndex(fixtures.obvious, fixtures.base)
        val closeIndex = samenessIndex(fixtures.close, fixtures.base)
        val nearlyIndex = samenessIndex(fixtures.nearly, fixtures.base)
        assertTrue("明显不同 $obviousIndex < 接近 $closeIndex", obviousIndex < closeIndex)
        assertTrue("接近 $closeIndex < 几乎相同 $nearlyIndex", closeIndex < nearlyIndex)
        assertEquals(12, obviousIndex)
        assertEquals(52, closeIndex)
        assertEquals(82, nearlyIndex)
        val obviousDifferences = diffItems(fixtures.obvious, fixtures.base).count { it.distinguishable }
        val closeDifferences = diffItems(fixtures.close, fixtures.base).count { it.distinguishable }
        val nearlyDifferences = diffItems(fixtures.nearly, fixtures.base).count { it.distinguishable }
        assertTrue(obviousDifferences > closeDifferences)
        assertTrue(closeDifferences > nearlyDifferences)
    }

    @Test
    fun `判据三_分段表每格零到百且沿两轴单调`() {
        val columns = listOf(
            SAMENESS_SCORE_TABLE.map { it.none },
            SAMENESS_SCORE_TABLE.map { it.low },
            SAMENESS_SCORE_TABLE.map { it.medium },
            SAMENESS_SCORE_TABLE.map { it.high },
        )
        columns.forEach { scores ->
            assertTrue(scores.all { it in 0..100 })
            scores.zipWithNext().forEach { (higher, lower) -> assertTrue(higher >= lower) }
        }
        SAMENESS_SCORE_TABLE.zipWithNext().forEach { (a, b) ->
            assertTrue(a.none >= b.none)
            assertTrue(a.low >= b.low)
            assertTrue(a.medium >= b.medium)
            assertTrue(a.high >= b.high)
        }
        SAMENESS_SCORE_TABLE.forEach { row ->
            assertTrue(row.none >= row.low)
            assertTrue(row.low >= row.medium)
            assertTrue(row.medium >= row.high)
        }
    }

    @Test
    fun `判据三_分段表逐格固定且边界可复算`() {
        assertEquals(
            listOf(
                listOf(100, 100, 100, 100),
                listOf(100, 82, 72, 62),
                listOf(100, 68, 52, 42),
                listOf(100, 56, 38, 28),
                listOf(100, 44, 28, 18),
                listOf(100, 32, 20, 12),
            ),
            SAMENESS_SCORE_TABLE.map { listOf(it.none, it.low, it.medium, it.high) },
        )
        val base = fullCard("BASE")
        val twoMedium = altered(base, "face.eyes", "face.nose")
        val fiveHigh = altered(base, "face.shape", "body.frame", "face.eyes", "face.nose", "face.lips")
        assertEquals(52, samenessIndex(twoMedium, base))
        assertEquals(12, samenessIndex(fiveHigh, base))
        assertEquals(100, samenessIndex(base, base.copy(roleId = "OTHER")))
    }

    @Test
    fun `判据五_双方缺省进absent且不当相同`() {
        val a = card("A")
        val b = card("B")
        val items = diffItems(a, b)
        assertTrue(items.isNotEmpty())
        assertTrue(items.all { it.kind == DiffKind.ABSENT })
        assertTrue(items.none { it.distinguishable })
        assertEquals(50, samenessIndex(a, b))
        assertFalse(samenessIndex(a, b) == 100)
    }

    @Test
    fun `判据五_单侧缺省不计可辨且指数封顶`() {
        val base = fullCard("BASE")
        val values = LinkedHashMap(base.values)
        values.remove("face.shape")
        val missing = base.copy(values = values)
        val absent = diffItems(base, missing).single { it.fieldId == "face.shape" }
        assertEquals(DiffKind.ABSENT, absent.kind)
        assertFalse(absent.distinguishable)
        assertEquals(60, samenessIndex(base, missing))
    }

    @Test
    fun `判据五_部分重叠不计可辨但仍降低指数`() {
        val base = fullCard("BASE")
        val words = CastingRefs.field("face.eyes").words()
        val values = LinkedHashMap(base.values)
        values["face.eyes"] = listOf(words[0], words[1])
        val partial = base.copy(values = values)
        val item = diffItems(base, partial).single { it.fieldId == "face.eyes" }
        assertEquals(DiffKind.PARTIAL, item.kind)
        assertFalse(item.distinguishable)
        assertEquals(72, samenessIndex(base, partial))
    }

    @Test
    fun `确定性_多值档位顺序与映射顺序无关`() {
        val base = fullCard("BASE")
        val words = CastingRefs.field("face.eyes").words()
        val first = LinkedHashMap(base.values)
        first["face.eyes"] = listOf(words[0], words[1])
        val second = LinkedHashMap(
            base.values.entries.reversed().map { it.key to it.value.reversed() }.toMap(),
        )
        second["face.eyes"] = listOf(words[1], words[0], words[1])
        val a = base.copy(values = first)
        val b = base.copy(values = second)
        assertEquals(100, samenessIndex(a, b))
        assertEquals(observe(a, b).line(), observe(b, a).line())
    }

    @Test
    fun `来源标注_817字段来源与档位可追`() {
        val base = fullCard("BASE")
        val changed = altered(base, "face.shape")
        val item = diffItems(base, changed).single { it.fieldId == "face.shape" }
        val note = sourceNote(item)
        val ref = CastingRefs.field("face.shape")
        assertEquals(RefSource.ANCHOR, note.source)
        assertEquals(ref.cn, note.fieldName)
        assertEquals(ref.words(), note.allowed)
        assertTrue(note.allowed.containsAll(item.left + item.right))
        assertTrue(note.line().contains("face.shape|脸型|ANCHOR"))
    }

    @Test
    fun `来源标注_缺省档位写absent`() {
        val item = diffItems(card("A"), card("B")).first()
        assertTrue(sourceNote(item).line().contains("absent|absent"))
    }

    @Test
    fun `判据二_观察结果只含读数与提示不含裁决`() {
        val fixtures = fixtures()
        val text = observe(fixtures.close, fixtures.base).line()
        assertTrue(text.contains("index="))
        assertTrue(text.contains("source|"))
        assertTrue(text.contains("847|"))
        listOf("合格", "不合格", "PASS", "FAIL", "verdict").forEach { token ->
            assertFalse("不许返回裁决词 $token", text.contains(token))
        }
        assertFalse(observe(fixtures.close, fixtures.base).unseen.holds)
    }

    @Test
    fun `847_明显不同满足未见双条件`() {
        val fixtures = fixtures()
        val condition = unseenCondition(fixtures.obvious, fixtures.base)
        assertTrue(condition.belowIndexThreshold)
        assertTrue(condition.hasEnoughDifferences)
        assertTrue(condition.holds)
        assertEquals(6, condition.distinguishableCount)
    }

    @Test
    fun `847_接近不因差异数足够而误报`() {
        val fixtures = fixtures()
        val condition = unseenCondition(fixtures.close, fixtures.base)
        assertFalse(condition.belowIndexThreshold)
        assertTrue(condition.hasEnoughDifferences)
        assertFalse(condition.holds)
    }

    @Test
    fun `847_几乎相同不满足双条件`() {
        val fixtures = fixtures()
        val condition = unseenCondition(fixtures.nearly, fixtures.base)
        assertFalse(condition.belowIndexThreshold)
        assertFalse(condition.hasEnoughDifferences)
        assertFalse(condition.holds)
    }

    @Test
    fun `847_指数阈值严格小于40`() {
        assertTrue(UnseenCondition(index = 39, distinguishableCount = 2).holds)
        assertFalse(UnseenCondition(index = 40, distinguishableCount = 2).holds)
    }

    @Test
    fun `847_差异阈值大于等于2`() {
        assertTrue(UnseenCondition(index = 39, distinguishableCount = 2).holds)
        assertFalse(UnseenCondition(index = 39, distinguishableCount = 1).holds)
    }

    @Test
    fun `847_缺省不凑可辨差异`() {
        val condition = unseenCondition(card("A"), card("B"))
        assertEquals(0, condition.distinguishableCount)
        assertFalse(condition.holds)
    }

    @Test
    fun `确定性_同一输入双跑逐字一致`() {
        val fixtures = fixtures()
        val first = observe(fixtures.obvious, fixtures.base)
        val second = observe(fixtures.obvious, fixtures.base)
        assertEquals(first.line(), second.line())
        assertEquals(first.index, second.index)
        assertEquals(first.items, second.items)
        assertEquals(first.notes, second.notes)
    }

    @Test
    fun `对称_指数与847条件不随左右交换改变`() {
        val fixtures = fixtures()
        assertEquals(
            samenessIndex(fixtures.obvious, fixtures.base),
            samenessIndex(fixtures.base, fixtures.obvious),
        )
        assertEquals(
            unseenCondition(fixtures.obvious, fixtures.base).holds,
            unseenCondition(fixtures.base, fixtures.obvious).holds,
        )
    }
}
