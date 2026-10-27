// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十八批 · 使用者耐心画像 `PatienceProfile` 的三道闸。
//
//   样本闸：不足 5 次观察，`learnedAdjustment` 必须返回 null（个人化完全不生效）；
//   漂移闸：30 天半衰期，置信度衰减到 0.30 以下回默认；
//   保守闸：每项阈值只在默认值 ±1 档内，越界夹回并可由 `outOfRangeFields()` 记 `familiarity_bad`。
//
// 另一条硬线：画像只存**统计量**，没有任何 String 字段（见 `HarmonyInvisibilityTest`）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatienceProfileTest {

    private val day = 86_400_000L

    // ── 1. 默认值与钳位 ────────────────────────────────────────────────

    @Test
    fun `默认_一两次不怪`() {
        val p = PatienceProfile()
        assertEquals(2, p.patienceBudget)
        assertEquals(3, p.angerAt)
        assertEquals(4, p.loopBudget)
        assertEquals(2, p.askTolerance)
        assertTrue(p.enabled)
        assertTrue(p.outOfRangeFields().isEmpty())
    }

    @Test
    fun `常量_阈值区间与置信度门槛写死`() {
        assertEquals(1, PatienceProfile.MIN_PATIENCE_BUDGET)
        assertEquals(3, PatienceProfile.MAX_PATIENCE_BUDGET)
        assertEquals(2, PatienceProfile.MIN_ANGER_AT)
        assertEquals(4, PatienceProfile.MAX_ANGER_AT)
        assertEquals(3, PatienceProfile.MIN_LOOP_BUDGET)
        assertEquals(5, PatienceProfile.MAX_LOOP_BUDGET)
        assertEquals(1, PatienceProfile.MIN_ASK_TOLERANCE)
        assertEquals(3, PatienceProfile.MAX_ASK_TOLERANCE)
        assertEquals(0.30, PatienceProfile.CONFIDENCE_MIN_PERSONAL, 1e-9)
        assertEquals(0.70, PatienceProfile.CONFIDENCE_FULL_PERSONAL, 1e-9)
        assertEquals(30, Familiarity.DEFAULT_HALF_LIFE_DAYS)
    }

    @Test
    fun `钳位_每项只许在默认值正负一档内`() {
        val wild = PatienceProfile(
            patienceBudget = 9,
            angerAt = 9,
            loopBudget = 9,
            askTolerance = 9,
        )
        val fixed = wild.clamped()
        assertEquals(3, fixed.patienceBudget)
        assertEquals(4, fixed.angerAt)
        assertEquals(5, fixed.loopBudget)
        assertEquals(3, fixed.askTolerance)
        assertEquals(
            listOf("patienceBudget", "angerAt", "loopBudget", "askTolerance"),
            wild.outOfRangeFields(),
        )
    }

    @Test
    fun `钳位_熔断额度必须比耐心额度大`() {
        val p = PatienceProfile(patienceBudget = 3, loopBudget = 3).clamped()
        assertTrue("熔断额度必须大于耐心额度", p.loopBudget > p.patienceBudget)
    }

    @Test
    fun `钳位_下界也夹回`() {
        val fixed = PatienceProfile(patienceBudget = 0, angerAt = 1, loopBudget = 0, askTolerance = 0).clamped()
        assertEquals(1, fixed.patienceBudget)
        assertEquals(2, fixed.angerAt)
        assertEquals(3, fixed.loopBudget)
        assertEquals(1, fixed.askTolerance)
    }

    // ── 2. 样本闸 ──────────────────────────────────────────────────────

    @Test
    fun `样本闸_不足五次不个性化`() {
        val p = PatienceProfile(observations = 4, patienceBudget = 3, angerAt = 4, updatedAt = 1_000L)
        assertNull(p.learnedAdjustment(now = 1_000L))
        assertEquals(p.clamped(), p.effective(now = 1_000L))
    }

    @Test
    fun `样本闸_满五次才允许个性化且留一次余量`() {
        val p = PatienceProfile(observations = 6, patienceBudget = 3, angerAt = 4, updatedAt = 1_000L)
        val learned = p.learnedAdjustment(now = 1_000L)
        assertNotNull("样本够了应进入个性化", learned)
        assertEquals(2, learned!!.patienceBudget)
        assertEquals(3, learned.angerAt)
        assertTrue(p.familiarity(now = 1_000L).confidence < PatienceProfile.CONFIDENCE_FULL_PERSONAL)
    }

    @Test
    fun `样本闸_超过二十次用个人化阈值`() {
        val p = PatienceProfile(observations = 25, patienceBudget = 3, angerAt = 4, updatedAt = 1_000L)
        val learned = p.learnedAdjustment(now = 1_000L)!!
        assertEquals(3, learned.patienceBudget)
        assertEquals(4, learned.angerAt)
    }

    // ── 3. 漂移闸 ──────────────────────────────────────────────────────

    @Test
    fun `漂移闸_三十天半衰期后置信度掉到样本闸以下`() {
        val now = 100L * day
        val p = PatienceProfile(observations = 6, patienceBudget = 3, angerAt = 4, updatedAt = now - 30 * day)
        assertTrue(
            "半衰后置信度应低于 0.30，实际 ${p.familiarity(now)}",
            p.familiarity(now).confidence < PatienceProfile.CONFIDENCE_MIN_PERSONAL,
        )
        assertNull("漂移后应回默认", p.learnedAdjustment(now))
        // 回的是「自己的手工值」，不是三个月前学出来的那一档
        assertEquals(p.clamped(), p.effective(now))
    }

    @Test
    fun `漂移闸_刚更新时置信度最高`() {
        val p = PatienceProfile(observations = 25, updatedAt = 5_000L)
        val fresh = p.familiarity(now = 5_000L).confidence
        val old = p.familiarity(now = 5_000L + 30 * day).confidence
        assertTrue("半衰期应让置信度减半：$fresh -> $old", old < fresh)
        assertTrue(old * 1.8 < fresh)
    }

    // ── 4. 开关与清空 ──────────────────────────────────────────────────

    @Test
    fun `关机_一律用默认且不写盘`() {
        val off = PatienceProfile(enabled = false, patienceBudget = 3, observations = 40, updatedAt = 1_000L)
        val effective = off.effective(now = 1_000L)
        assertEquals(PatienceProfile.DEFAULT_PATIENCE_BUDGET, effective.patienceBudget)
        assertFalse(effective.enabled)
        assertEquals(off, off.recordObservation(now = 2_000L))
        assertNull(off.learnedAdjustment(now = 1_000L))
    }

    @Test
    fun `清空_回默认但保留开关`() {
        val p = PatienceProfile(patienceBudget = 3, observations = 12, updatedAt = 9_000L, prefersEmoji = false)
        val cleared = p.cleared()
        assertEquals(PatienceProfile(), PatienceProfile(enabled = cleared.enabled))
        assertEquals(0, cleared.observations)
        assertEquals(0L, cleared.updatedAt)
        assertEquals(PatienceProfile.DEFAULT_PATIENCE_BUDGET, cleared.patienceBudget)
        assertFalse("开关必须保持原样", cleared.enabled.not())
    }

    @Test
    fun `观察_每次失败加一并刷新时间`() {
        val p = PatienceProfile(observations = 2, updatedAt = 10L)
        val next = p.recordObservation(now = 20L)
        assertEquals(3, next.observations)
        assertEquals(20L, next.updatedAt)
        assertEquals(p.copy(observations = 3, updatedAt = 20L), next)
    }

    // ── 5. 编解码 ──────────────────────────────────────────────────────

    @Test
    fun `编解码_往返一致`() {
        val p = PatienceProfile(
            patienceBudget = 3,
            angerAt = 4,
            loopBudget = 5,
            askTolerance = 3,
            prefersEmoji = false,
            prefersShort = false,
            observations = 7,
            updatedAt = 12_345L,
        )
        assertEquals(p, PatienceProfileCodec.decode(PatienceProfileCodec.encode(p)))
    }

    @Test
    fun `编解码_空与坏数据回默认`() {
        assertEquals(PatienceProfile(), PatienceProfileCodec.decode(null))
        assertEquals(PatienceProfile(), PatienceProfileCodec.decode(""))
        assertEquals(PatienceProfile(), PatienceProfileCodec.decode("p0\npb=3"))
        val good = PatienceProfileCodec.encode(PatienceProfile(observations = 5, patienceBudget = 3))
        assertEquals(PatienceProfile(), PatienceProfileCodec.decode(good.replace("pb=3", "pb=x")))
        assertEquals(PatienceProfile(), PatienceProfileCodec.decode(good.replace("p1", "p2")))
    }

    @Test
    fun `编解码_只含数字与布尔`() {
        val encoded = PatienceProfileCodec.encode(
            PatienceProfile(observations = 8, updatedAt = 99L),
        )
        val lines = encoded.lineSequence().drop(1).filter { it.isNotBlank() }.toList()
        assertTrue(lines.isNotEmpty())
        lines.forEach { line ->
            val (key, value) = line.split('=', limit = 2)
            assertTrue("键名只许短代码：$key", key.all { it in 'a'..'z' })
            assertTrue("值只许数字或 -：$value", value.all { it.isDigit() || it == '-' || it == '1' })
        }
    }
}
