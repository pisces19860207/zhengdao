// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百四十一批 · 判据①②③：三态与 655 合法性一致、勉强必给受限原因、文案不许说反话。
// 判据① 的牙有两层：判定函数「非法 ⇒ 进不去」，以及结论对象构造时把「非法却写进得去」当场抛掉。
package com.example.zhengdao.baopu.state.containment

import com.example.zhengdao.baopu.state.geometry.Environment
import com.example.zhengdao.baopu.state.geometry.SegmentProxy
import com.example.zhengdao.baopu.state.geometry.Sphere
import com.example.zhengdao.baopu.state.geometry.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ContainmentJudgeTest {

    private val roomy = SpaceProfile(Grade.LARGE, Grade.LARGE, Grade.LARGE, Opening.FRONT)
    private val passing = ContainmentFixtures.legalAudits()

    private fun judge(
        space: SpaceProfile = roomy,
        stance: Stance = Stance.STAND_FRONT,
        facing: Facing = Facing.TOWARD,
        audit: StanceAudit = passing.getValue(stance),
    ): ContainmentVerdict = Containment.judge(space, stance, facing, audit)

    @Test
    fun `判据一_655判不合法时三态必为进不去_四项各一条`() {
        val probe = SegmentProxy("probe", Sphere(Vec3(14.0, 130.0, 0.0), 5.0))
        val cases = mapOf(
            "穿地" to ContainmentFixtures.scene(Stance.STAND_FRONT, env = Environment(10.0, 10.0)),
            "越限" to ContainmentFixtures.scene(Stance.STAND_FRONT, angles = listOf(3.5, 0.0)),
            "自碰撞" to ContainmentFixtures.scene(Stance.STAND_FRONT, body = ContainmentFixtures.standBody() + probe),
            "够不着" to ContainmentFixtures.scene(Stance.STAND_FRONT, span = 70.0),
        )
        for ((why, scene) in cases) {
            val audit = StanceLegality.audit(scene)
            val verdict = judge(audit = audit)
            assertEquals("$why 属 655 判非法 ⇒ 三态必为进不去", ContainmentState.NOFIT, verdict.state)
            assertTrue("$why 必须给受限原因", verdict.reasons.isNotEmpty())
        }
    }

    @Test
    fun `判据一_非法台账不许被包装成进得去_构造即抛`() {
        val broken = ContainmentFixtures.audit(Stance.STAND_FRONT, mapOf(AuditItem.GROUND to AuditState.FAIL))
        val error = runCatching {
            ContainmentVerdict(
                state = ContainmentState.FIT,
                reasons = emptyList(),
                space = roomy,
                stance = Stance.STAND_FRONT,
                facing = Facing.TOWARD,
                audit = broken,
                text = Phrasebook.FIT_TEXT,
            )
        }.exceptionOrNull()
        assertTrue("穿地却写「进得去」必须当场拒：$error", error is IllegalArgumentException)
    }

    @Test
    fun `判据一_未证明最多到勉强_不许说进得去`() {
        val unproven = ContainmentFixtures.audit(Stance.KNEEL, mapOf(AuditItem.REACH to AuditState.UNPROVEN))
        val verdict = judge(stance = Stance.KNEEL, audit = unproven)
        assertNotEquals("可达未证明 ⇒ 不许判进得去", ContainmentState.FIT, verdict.state)
        assertTrue("要如实写「够不着与否未证明」", LimitReason.REACH_UNPROVEN in verdict.reasons)
    }

    @Test
    fun `判据一_台账姿态与被判姿态不一致就拒`() {
        val error = runCatching {
            Containment.judge(roomy, Stance.PRONE, Facing.TOWARD, passing.getValue(Stance.KNEEL))
        }.exceptionOrNull()
        assertTrue("拿跪的台账去判伏必须拒：$error", error is IllegalArgumentException)
    }

    @Test
    fun `判据二_高度差一档且弯得下腰_记需弯腰`() {
        val low = roomy.copy(height = Grade.SMALL)
        val verdict = judge(space = low, stance = Stance.STAND_FRONT)
        assertEquals(ContainmentState.MARGINAL, verdict.state)
        assertTrue("勉强必须点名受限原因", verdict.reasons.isNotEmpty())
        assertTrue("站姿弯得下腰 ⇒ 记「需弯腰」而不是笼统的「高度不够」", LimitReason.NEEDS_BEND in verdict.reasons)
        assertTrue("文案里要看得见原因", "需弯腰" in verdict.text)
    }

    @Test
    fun `判据二_伏只要小档高_低矮空间仍进得去且不记需弯腰`() {
        val low = roomy.copy(height = Grade.SMALL)
        val verdict = judge(space = low, stance = Stance.PRONE)
        assertEquals("伏本来就只要小档高 ⇒ 低矮空间照样进得去", ContainmentState.FIT, verdict.state)
        assertTrue("进得去时不许冒出「需弯腰」", LimitReason.NEEDS_BEND !in verdict.reasons)
    }

    @Test
    fun `判据二_宽度差一档记开口太窄`() {
        val narrow = roomy.copy(width = Grade.SMALL)
        val verdict = judge(space = narrow, stance = Stance.STAND_FRONT)
        assertEquals(ContainmentState.MARGINAL, verdict.state)
        assertTrue("肩宽占宽度 ⇒ 窄一档记「开口太窄」", LimitReason.OPENING_NARROW in verdict.reasons)
        assertTrue("文案写原因", "开口太窄" in verdict.text)
    }

    @Test
    fun `判据二_侧身能把窄开口救回来_同空间换档位结论不同`() {
        val narrow = roomy.copy(width = Grade.SMALL)
        assertEquals(
            "站姿在窄开口只能勉强",
            ContainmentState.MARGINAL,
            judge(space = narrow, stance = Stance.STAND_FRONT).state,
        )
        assertEquals(
            "侧身肩宽折半 ⇒ 同一空间直接进得去",
            ContainmentState.FIT,
            judge(space = narrow, stance = Stance.SIDLE, facing = Facing.SIDEWAYS).state,
        )
    }

    @Test
    fun `判据二_差两档以上直接进不去_不再谈将就`() {
        val tiny = SpaceProfile(Grade.SMALL, Grade.SMALL, Grade.SMALL, Opening.FRONT)
        val verdict = judge(space = tiny, stance = Stance.PRONE)
        assertEquals("伏要深大档，只有小档 ⇒ 差两档", ContainmentState.NOFIT, verdict.state)
        assertTrue("进不去也要给原因", LimitReason.DEPTH_SHORT in verdict.reasons)
    }

    @Test
    fun `判据三_进得去时必须四项全过且不带原因`() {
        val verdict = judge()
        assertEquals(ContainmentState.FIT, verdict.state)
        assertTrue("进得去 ⇒ 四项必须全过", verdict.audit.allPass)
        assertTrue("进得去 ⇒ 不许带受限原因", verdict.reasons.isEmpty())
        assertEquals("进得去的文案就是文案表那一句", Phrasebook.FIT_TEXT, verdict.text)
    }

    @Test
    fun `判据三_穷举全部空间档与站位与朝向_禁词一条不沾`() {
        var checked = 0
        for (opening in Opening.entries) {
            for (height in Grade.entries) {
                for (width in Grade.entries) {
                    for (depth in Grade.entries) {
                        val space = SpaceProfile(height, width, depth, opening)
                        for (stance in Stance.entries) {
                            for (facing in Facing.entries) {
                                for (variant in AUDIT_VARIANTS) {
                                    val audit = ContainmentFixtures.audit(stance, variant)
                                    val verdict = Containment.judge(space, stance, facing, audit)
                                    assertTextDiscipline(verdict)
                                    checked++
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue("穷举不能空转：至少要扫到上千条结论，实得 $checked", checked >= 3000)
    }

    @Test
    fun `判据三_禁词闸有牙_注入顺利完成必被咬住`() {
        assertTrue("脏句子必须被抓到", forbiddenHits(ContainmentState.NOFIT, "进不去但本单顺利完成").isNotEmpty())
        assertTrue("脏句子必须被抓到", forbiddenHits(ContainmentState.MARGINAL, "勉强但毫无阻碍").isNotEmpty())
        assertTrue("进得去写「勉强」也算说反话", forbiddenHits(ContainmentState.FIT, "勉强能行").isNotEmpty())
        assertTrue("干净句子不许误伤", forbiddenHits(ContainmentState.NOFIT, "进不去：受限原因＝开口太窄").isEmpty())
    }

    @Test
    fun `判据三_手写文案一律拒_文案只许文案表出`() {
        val error = runCatching {
            ContainmentVerdict(
                state = ContainmentState.MARGINAL,
                reasons = listOf(LimitReason.NEEDS_BEND),
                space = roomy.copy(height = Grade.SMALL),
                stance = Stance.STAND_FRONT,
                facing = Facing.TOWARD,
                audit = passing.getValue(Stance.STAND_FRONT),
                text = "勉强：顺利完成",
            )
        }.exceptionOrNull()
        assertTrue("手写文案（还带禁词）必须当场拒：$error", error is IllegalArgumentException)
    }

    @Test
    fun `判据三_勉强与进不去都必须带原因_不许空口`() {
        val error = runCatching {
            ContainmentVerdict(
                state = ContainmentState.NOFIT,
                reasons = emptyList(),
                space = roomy.copy(opening = Opening.SIDE),
                stance = Stance.PRONE,
                facing = Facing.TOWARD,
                audit = passing.getValue(Stance.PRONE),
                text = Phrasebook.of(ContainmentState.NOFIT, emptyList(), passing.getValue(Stance.PRONE)),
            )
        }.exceptionOrNull()
        assertTrue("进不去必须写清为什么：$error", error is IllegalArgumentException)
    }

    @Test
    fun `判据三_同一输入重复判三次逐字一致`() {
        val first = judge(space = roomy.copy(width = Grade.SMALL)).text
        val second = judge(space = roomy.copy(width = Grade.SMALL)).text
        assertEquals(first, second)
    }

    private fun assertTextDiscipline(verdict: ContainmentVerdict) {
        val hits = forbiddenHits(verdict.state, verdict.text)
        if (hits.isNotEmpty()) fail("${verdict.state.label} 的文案踩了禁词 $hits：${verdict.text}")
        if (verdict.state == ContainmentState.FIT) {
            assertTrue("进得去必须四项全过：${verdict.text}", verdict.audit.allPass)
            assertTrue("进得去不带原因：${verdict.text}", verdict.reasons.isEmpty())
        } else {
            assertTrue("${verdict.state.label} 必须给受限原因：${verdict.text}", verdict.reasons.isNotEmpty())
        }
        if (verdict.state == ContainmentState.NOFIT) {
            assertTrue("进不去要直说：${verdict.text}", "进不去" in verdict.text)
        }
        if (verdict.state == ContainmentState.MARGINAL) {
            assertTrue("勉强要直说：${verdict.text}", "勉强" in verdict.text)
        }
    }

    /** 判据③的机检口径：状态 → 该状态下不许出现的词（词表在 [Phrasebook.FORBIDDEN]）。 */
    private fun forbiddenHits(state: ContainmentState, text: String): List<String> =
        Phrasebook.FORBIDDEN.getValue(state).filter { it in text }

    private companion object {
        val AUDIT_VARIANTS: List<Map<AuditItem, AuditState>> = listOf(
            emptyMap(),
            mapOf(AuditItem.LIMIT to AuditState.FAIL),
            mapOf(AuditItem.SELF_COLLISION to AuditState.FAIL),
            mapOf(AuditItem.REACH to AuditState.UNPROVEN),
        )
    }
}
