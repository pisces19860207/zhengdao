// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.state.hygiene

import com.example.zhengdao.baopu.state.prepuce.AreaLevel
import com.example.zhengdao.baopu.state.prepuce.Circumcision
import com.example.zhengdao.baopu.state.prepuce.RetractState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HygieneDerivationTest {

    private val expectedBase = listOf(
        listOf(0, 0, 0, 0, 0, 0),
        listOf(0, 0, 1, 1, 1, 1),
        listOf(1, 1, 1, 2, 2, 2),
        listOf(1, 2, 2, 2, 3, 3),
    )
    private val expectedRetractShift = listOf(0, 1, 1)
    private val expectedWarmShift = listOf(0, 0, 1)
    private val expectedCircumcisionShift = listOf(0, -1)

    @Test
    fun `判据五_包皮垢全合法组合逐格可独立复算`() {
        var checked = 0
        for (frequency in WashFrequency.entries) {
            for (coverage in AreaLevel.entries) {
                for (retract in RetractState.entries) {
                    for (warm in WarmHumidTier.entries) {
                        for (circumcision in Circumcision.entries) {
                            if (circumcision == Circumcision.DONE && retract != RetractState.NONE) continue
                            val state = HygieneFixtures.state(cleaningFreq = frequency, retractability = retract)
                            val context = HygieneFixtures.context(coverage = coverage, warmHumid = warm, circumcision = circumcision)
                            val actual = SmegmaTable.predict(state, context)
                            val expected = (
                                expectedBase[frequency.level][coverage.order] +
                                    expectedRetractShift[retract.ordinal] +
                                    expectedWarmShift[warm.level] +
                                    expectedCircumcisionShift[circumcision.ordinal]
                                ).coerceIn(0, 3)
                            assertEquals("$frequency/$coverage/$retract/$warm/$circumcision", SmegmaTier.of(expected), actual.tier)
                            val baseId = "S-B${frequency.level}${coverage.order}"
                            val modId = "S-M${retract.ordinal}${warm.level}${circumcision.ordinal}"
                            assertEquals("$baseId+$modId", actual.rowId)
                            assertTrue(actual.basis.contains(baseId) && actual.basis.contains(modId))
                            checked++
                        }
                    }
                }
            }
        }
        assertEquals(288, checked)
    }

    @Test
    fun `口径_已环切却可回缩当场红`() {
        for (retract in listOf(RetractState.FULL, RetractState.PARTIAL)) {
            val state = HygieneFixtures.state(retractability = retract)
            val context = HygieneFixtures.context(circumcision = Circumcision.DONE)
            assertThrows(IllegalArgumentException::class.java) { SmegmaTable.predict(state, context) }
        }
    }

    @Test
    fun `判据三_观察包皮垢低于明显档气味一律absent`() {
        for (tier in listOf(SmegmaTier.NONE, SmegmaTier.TRACE)) {
            for (sweat in SweatAmount.entries) {
                for (frequency in WashFrequency.entries) {
                    for (breathability in ClothingBreathability.entries) {
                        for (warm in WarmHumidTier.entries) {
                            for (afterExercise in listOf(false, true)) {
                                val state = HygieneFixtures.state(smegmaTier = tier, cleaningFreq = frequency)
                                val context = HygieneFixtures.context(
                                    sweat = sweat,
                                    breathability = breathability,
                                    warmHumid = warm,
                                    afterExercise = afterExercise,
                                )
                                val slot = SmellTable.predict(state, context)
                                assertTrue("$tier/$sweat/$frequency/$breathability/$warm/$afterExercise", slot is Absent)
                                assertEquals("包皮垢未到明显档", (slot as Absent).reason)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `判据五_气味全合法组合逐格可独立复算`() {
        val sweatBase = listOf(0, 1, 2, 3)
        val cleaningShift = listOf(-1, 0, 1, 2)
        val breathShift = listOf(0, 1, 2)
        val warmShift = listOf(0, 0, 1)
        var checked = 0
        for (tier in listOf(SmegmaTier.OBVIOUS, SmegmaTier.BUILDUP)) {
            for (sweat in SweatAmount.entries) {
                for (frequency in WashFrequency.entries) {
                    for (breathability in ClothingBreathability.entries) {
                        for (warm in WarmHumidTier.entries) {
                            for (afterExercise in listOf(false, true)) {
                                val state = HygieneFixtures.state(smegmaTier = tier, cleaningFreq = frequency)
                                val context = HygieneFixtures.context(
                                    sweat = sweat,
                                    breathability = breathability,
                                    warmHumid = warm,
                                    afterExercise = afterExercise,
                                )
                                val slot = SmellTable.predict(state, context) as SmellSlot.Given
                                val exerciseShift = if (afterExercise) 1 else 0
                                val expected = (
                                    sweatBase[sweat.level] + cleaningShift[frequency.level] +
                                        breathShift[breathability.level] + warmShift[warm.level] + exerciseShift
                                    ).coerceIn(0, 3)
                                assertEquals(SmellIntensity.of(expected), slot.intensity)
                                val exerciseId = if (afterExercise) 1 else 0
                                assertEquals(
                                    "O-S${sweat.level}-C${frequency.level}-T${breathability.level}-" +
                                        "H${warm.level}-E$exerciseId",
                                    slot.rowId,
                                )
                                assertTrue(slot.basis.isNotBlank())
                                checked++
                            }
                        }
                    }
                }
            }
        }
        assertEquals(576, checked)
    }

    @Test
    fun `判据五_分段表上下边界钳在零三档`() {
        val lowState = HygieneFixtures.state(smegmaTier = SmegmaTier.OBVIOUS, cleaningFreq = WashFrequency.DAILY)
        val lowContext = HygieneFixtures.context(
            sweat = SweatAmount.NONE,
            breathability = ClothingBreathability.GOOD,
            warmHumid = WarmHumidTier.DRY,
            afterExercise = false,
        )
        assertEquals(SmellIntensity.NONE, (SmellTable.predict(lowState, lowContext) as SmellSlot.Given).intensity)

        val highState = HygieneFixtures.state(smegmaTier = SmegmaTier.BUILDUP, cleaningFreq = WashFrequency.OVER_WEEK)
        val highContext = HygieneFixtures.context(
            sweat = SweatAmount.COPIOUS,
            breathability = ClothingBreathability.POOR,
            warmHumid = WarmHumidTier.HOT_HUMID,
            afterExercise = true,
        )
        assertEquals(SmellIntensity.STRONG, (SmellTable.predict(highState, highContext) as SmellSlot.Given).intensity)
    }
}
