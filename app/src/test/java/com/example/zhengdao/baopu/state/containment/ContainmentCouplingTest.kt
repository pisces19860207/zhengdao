// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百四十一批 · 判据④：姿态-空间耦合——问的是「这个空间里这个姿势够不够得着」，
// 不是「能不能挤进去」。所以同一空间、同一姿态，把目标挪远一档，结论必须从「进得去」
// 掉到「进不去」，而不是继续报「能进」。空间三档与开口位置的对偶也在这里钉住。
package com.example.zhengdao.baopu.state.containment

import com.example.zhengdao.baopu.state.geometry.Environment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainmentCouplingTest {

    private val roomy = SpaceProfile(Grade.LARGE, Grade.LARGE, Grade.LARGE, Opening.FRONT)
    private val audits = ContainmentFixtures.legalAudits()

    private fun judge(space: SpaceProfile, stance: Stance, facing: Facing, audit: StanceAudit): ContainmentVerdict =
        Containment.judge(space, stance, facing, audit)

    private fun auditOf(span: Double): StanceAudit =
        StanceLegality.audit(ContainmentFixtures.scene(Stance.STAND_FRONT, span = span))

    @Test
    fun `判据四_空间够但够不着_照样进不去`() {
        val verdict = judge(roomy, Stance.STAND_FRONT, Facing.TOWARD, auditOf(70.0))
        assertEquals("空间再宽裕，伸不到就是伸不到", ContainmentState.NOFIT, verdict.state)
        assertTrue("必须点名「够不着」", LimitReason.REACH_SHORT in verdict.reasons)
        assertTrue("文案里要看得见", "够不着" in verdict.text)
    }

    @Test
    fun `判据四_同一空间同一姿态_目标近则进得去远则进不去`() {
        val near = judge(roomy, Stance.STAND_FRONT, Facing.TOWARD, auditOf(30.0))
        val far = judge(roomy, Stance.STAND_FRONT, Facing.TOWARD, auditOf(70.0))
        assertEquals("够得着就是进得去", ContainmentState.FIT, near.state)
        assertEquals("够不着就是进不去", ContainmentState.NOFIT, far.state)
        assertTrue("两条结论必须真的不同，否则判的还是「能不能进」", near.text != far.text)
    }

    @Test
    fun `判据四_够得着但空间不够_结论由空间档决定`() {
        val narrow = roomy.copy(width = Grade.SMALL)
        val verdict = judge(narrow, Stance.STAND_FRONT, Facing.TOWARD, auditOf(30.0))
        assertEquals("够得着也架不住开口太窄", ContainmentState.MARGINAL, verdict.state)
        assertTrue("可及＝过，但空间不许说进得去", LimitReason.OPENING_NARROW in verdict.reasons)
    }

    @Test
    fun `判据四_开口位置换一下可用档就换一批`() {
        val front = Containment.survey(roomy, Facing.TOWARD, audits).usable
        val side = Containment.survey(roomy.copy(opening = Opening.SIDE), Facing.TOWARD, audits).usable
        val top = Containment.survey(roomy.copy(opening = Opening.TOP), Facing.TOWARD, audits).usable
        assertEquals("前开口正对：站前、跪、伏都能用", listOf(Stance.STAND_FRONT, Stance.KNEEL, Stance.PRONE), front)
        assertEquals("侧开口正对：只剩跪（侧身要正面偏转，朝向不合）", listOf(Stance.KNEEL), side)
        assertEquals("上开口正对：站前与跪够得着，伏躺不进去", listOf(Stance.STAND_FRONT, Stance.KNEEL), top)
    }

    @Test
    fun `判据四_朝向换一下可用档也换一批`() {
        val toward = Containment.survey(roomy, Facing.TOWARD, audits).usable
        val sideways = Containment.survey(roomy, Facing.SIDEWAYS, audits).usable
        assertEquals("正对：站前、跪、伏", listOf(Stance.STAND_FRONT, Stance.KNEEL, Stance.PRONE), toward)
        assertEquals("侧对：侧身与跪", listOf(Stance.SIDLE, Stance.KNEEL), sideways)
    }

    @Test
    fun `判据四_背对开口一律进不去`() {
        val survey = Containment.survey(roomy, Facing.AWAY, audits)
        for (verdict in survey.verdicts) {
            assertEquals("背对开口进不去：${verdict.stance.label}", ContainmentState.NOFIT, verdict.state)
            assertTrue("要写明朝向不合", LimitReason.FACING_CONFLICT in verdict.reasons)
        }
    }

    @Test
    fun `判据四_总表四档齐备_少一档台账就拒`() {
        val missing = audits - Stance.PRONE
        val error = runCatching { Containment.survey(roomy, Facing.TOWARD, missing) }.exceptionOrNull()
        assertTrue("四档站位每档都要过 655 四项：缺伏的台账就必须拒：$error", error is IllegalArgumentException)
    }

    @Test
    fun `判据四_总表按站位枚举次序排列_不随Map迭代顺序变`() {
        val reversed = audits.entries.reversed().associate { it.key to it.value }
        val survey = Containment.survey(roomy, Facing.TOWARD, reversed)
        assertEquals("次序只认站位枚举", Stance.entries.toList(), survey.verdicts.map { it.stance })
    }

    @Test
    fun `判据四_四档在低矮空间里的分化_跪与伏反而能进`() {
        val low = SpaceProfile(Grade.SMALL, Grade.MEDIUM, Grade.LARGE, Opening.FRONT)
        val survey = Containment.survey(low, Facing.TOWARD, audits)
        assertEquals("站前差一档高 ⇒ 勉强（需弯腰）", ContainmentState.MARGINAL, survey.verdictOf(Stance.STAND_FRONT).state)
        assertEquals("跪只要小档高 ⇒ 进得去", ContainmentState.FIT, survey.verdictOf(Stance.KNEEL).state)
        assertEquals("伏也只要小档高 ⇒ 进得去", ContainmentState.FIT, survey.verdictOf(Stance.PRONE).state)
        assertTrue(
            "低头弯腰不如直接跪：能用的档不止一档",
            survey.usable.containsAll(listOf(Stance.KNEEL, Stance.PRONE)),
        )
    }

    @Test
    fun `判据四_穿地的姿态在任何空间档下都不许进得去`() {
        val sunk = StanceLegality.audit(ContainmentFixtures.scene(Stance.KNEEL, env = Environment(10.0, 10.0)))
        for (height in Grade.entries) {
            val space = roomy.copy(height = height)
            val verdict = judge(space, Stance.KNEEL, Facing.TOWARD, sunk)
            assertEquals("穿地不是空间问题，换哪一档都不许进得去", ContainmentState.NOFIT, verdict.state)
        }
    }
}
