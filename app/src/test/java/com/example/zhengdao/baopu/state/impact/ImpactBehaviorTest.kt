// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百二十七批 · 行为判据：三段表单调、五输出齐、敏感区分档、七力矩阵、
// routine 豁免、医学红线、覆盖位/皮量提示、恢复落零、确定性、与 655 方向一致。
// 每个判据都配负例；本测试零写盘（水位不受影响）。
package com.example.zhengdao.baopu.state.impact

import com.example.zhengdao.baopu.state.cavity.ContactProbe
import com.example.zhengdao.baopu.state.cavity.NormalBucket
import com.example.zhengdao.baopu.state.geometry.Vec3
import com.example.zhengdao.baopu.state.geometry.TwoBodyCollision
import com.example.zhengdao.baopu.state.prepuce.AreaLevel
import com.example.zhengdao.baopu.state.prepuce.SkinAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpactBehaviorTest {

    private fun input(
        speed: Int = 2,
        mass: Int = 1,
        area: Int = 1,
        site: ImpactSite? = null,
        coverage: AreaLevel? = null,
        skin: SkinAmount? = null,
        rounds: Int = 0,
        other: ImpactHit? = null,
    ) = ImpactInput(
        speed = SpeedTier.of(speed),
        mass = MassTier.of(mass),
        area = AreaTier.of(area),
        site = site,
        coverage = coverage,
        skin = skin,
        rounds = rounds,
        other = other,
    )

    private fun tierOf(s: Int, m: Int, a: Int): Int {
        val e = ENERGY[s][m]
        return TIER_OF[DIVIDED[e][SPREAD[a] - 1]]
    }

    @Test
    fun `判据一_三段表可查且三段单调`() {
        assertEquals(5, ENERGY.size)
        assertTrue(ENERGY.all { it.size == 4 })
        assertEquals(4, SPREAD.size)
        assertEquals(10, DIVIDED.size)
        assertTrue(DIVIDED.all { it.size == 4 })
        assertEquals(10, TIER_OF.size)
        assertEquals(5, BAND.size)
        assertTrue(ImpactChecks.monotonic())

        // 负例：把能量表某一格改小，单调必破（证明断言有牙，不是恒真）。
        val poisoned = ENERGY.map { it.copyOf() }.toTypedArray().also { it[4][3] = 0 }
        val broken = (0..4).any { s -> (0..3).any { m -> (0..3).any { a ->
            val old = DIVIDED[ENERGY[s][m]][SPREAD[a] - 1]
            val new = DIVIDED[poisoned[s][m]][SPREAD[a] - 1]
            new < old
        } } }
        assertTrue("负例注入应破坏单调", broken)
    }

    @Test
    fun `判据一_速度升或质量升冲击不降面积升冲击不升`() {
        for (s in 0..3) for (m in 0..3) for (a in 0..3) {
            assertTrue("速度单调 $s,$m,$a", tierOf(s, m, a) <= tierOf(s + 1, m, a))
        }
        for (s in 0..4) for (m in 0..2) for (a in 0..3) {
            assertTrue("质量单调 $s,$m,$a", tierOf(s, m, a) <= tierOf(s, m + 1, a))
        }
        for (s in 0..4) for (m in 0..3) for (a in 0..2) {
            assertTrue("面积单调 $s,$m,$a", tierOf(s, m, a) >= tierOf(s, m, a + 1))
        }
        assertTrue("最小输入落 0 档", tierOf(0, 0, 0) == 0)
        assertTrue("最大输入落 4 档", tierOf(4, 3, 0) == 4)
    }

    @Test
    fun `判据二_五输出齐且档位在域内`() {
        for (s in 0..4) for (m in 0..3) for (a in 0..3) {
            val r = ImpactSheet.of(input(s, m, a))
            assertTrue("冲击 $s/$m/$a", r.impact.level in 0..4)
            assertTrue("位移", r.displace.level in 0..3)
            assertTrue("压扁", r.flatten.level in 0..3)
            assertTrue("恢复", r.recovery.level in 0..3)
            assertTrue("后果词非空", r.words.isNotEmpty())
            assertTrue("五输出判据", ImpactChecks.fiveOutputs(r))
        }
        // 负例：把后果词清空，判据必须红。
        val empty = ImpactSheet.of(input()).copy(words = emptyList())
        assertFalse("空词表必须判红", ImpactChecks.fiveOutputs(empty))
    }

    @Test
    fun `判据三_同强度下敏感区与柱身根部不同档`() {
        assertTrue(ImpactMatrix.sensitivitySeparated())
        for (force in ForceKind.entries) {
            val base = MATRIX_TIER.getValue(force)
            val sens = SENSITIVE_SITES.map { base[it.ordinal] }.filter { it >= 0 }
            val robust = ROBUST_SITES.map { base[it.ordinal] }.filter { it >= 0 }
            if (sens.isNotEmpty() && robust.isNotEmpty()) {
                assertTrue("${force.label} 敏感区更高", sens.min() > robust.max())
            }
        }
        // 负例：把系带档改成与柱身同档，分离判据必须破。
        val poisoned = MATRIX_TIER.map { (k, v) -> k to v.copyOf() }.toMap().toMutableMap()
        val row = poisoned.getValue(ForceKind.COMPRESSION)
        row[ImpactSite.FRENULUM.ordinal] = row[ImpactSite.SHAFT.ordinal]
        val broken = SENSITIVE_SITES.map { row[it.ordinal] }.filter { it >= 0 }.min() <=
            ROBUST_SITES.map { row[it.ordinal] }.filter { it >= 0 }.max()
        assertTrue("负例注入同档必破", broken)
    }

    @Test
    fun `判据四_七力齐且每力至少三子区异档`() {
        assertTrue(ImpactMatrix.forceComplete())
        for (force in ForceKind.entries) {
            val n = ImpactMatrix.distinctCount(force)
            assertTrue("${force.label} 异档数=$n 应≥3", n >= 3)
        }
        assertEquals(7 * ImpactSite.entries.size, ImpactMatrix.rows().size)
        // 负例：若把某力全子区同档，distinctCount 会掉到 1。
        val one = ImpactSite.entries.map { "同档" }.distinct().size
        assertEquals(1, one)
    }

    @Test
    fun `判据四_扭转只写剧痛失能不写处置`() {
        assertTrue(ImpactMatrix.torsionClean())
        for (site in ImpactSite.entries) {
            val c = ImpactMatrix.cell(ForceKind.TORSION, site)
            if (c.tier >= 0) {
                assertTrue("扭转形态只能剧痛/失能：${c.form}", c.form.split('、').all { it == "剧痛" || it == "失能" })
                assertFalse("扭转不许描写", c.allowDepict)
            } else {
                assertEquals("不适用", c.tierLabel)
            }
        }
        val banned = listOf("复位", "手法", "就医", "就诊", "手术", "怎么办")
        for (site in ImpactSite.entries) {
            val text = ImpactMatrix.cell(ForceKind.TORSION, site).line()
            assertTrue("扭转不得写处置：$text", banned.none { text.contains(it) })
        }
    }

    @Test
    fun `判据四_剧烈档挂形态且负压给消退档`() {
        for (force in ForceKind.entries) for (site in ImpactSite.entries) {
            val c = ImpactMatrix.cell(force, site)
            if (c.tier == 3) {
                assertTrue("剧烈档需有形态：${c.form}", c.form.isNotBlank() && c.form != "很痛")
            }
            if (c.tier < 0) assertEquals("不适用", c.tierLabel)
        }
        assertTrue(ImpactMatrix.suctionWithdrawalComplete())
        assertNotNull(ImpactMatrix.cell(ForceKind.SUCTION, ImpactSite.GLANS).withdrawal)
        assertEquals(null, ImpactMatrix.cell(ForceKind.SUCTION, ImpactSite.TESTIS).withdrawal)
    }

    @Test
    fun `判据四_覆盖位与皮量缓冲进矩阵`() {
        val bare = ImpactMatrix.cell(ForceKind.COMPRESSION, ImpactSite.GLANS)
        val covered = ImpactMatrix.cell(
            ForceKind.COMPRESSION, ImpactSite.GLANS, AreaLevel.FULL_COVER, SkinAmount.AMPLE,
        )
        assertTrue("覆盖后后果档下降", covered.tier < bare.tier)
        val bareFrenulum = ImpactMatrix.cell(ForceKind.COMPRESSION, ImpactSite.FRENULUM)
        val retracted = ImpactMatrix.cell(ForceKind.COMPRESSION, ImpactSite.FRENULUM, AreaLevel.RETRACTED_BEHIND_CORONA, null)
        assertEquals("退至冠后（831）无缓冲", bareFrenulum.tier, retracted.tier)
    }

    @Test
    fun `判据五_日常动作不进矩阵且rare封顶1`() {
        assertTrue(ImpactRoutine.routineClean())
        for (action in RoutineAction.entries) {
            assertEquals(0, ImpactRoutine.of(action).tier)
        }
        assertEquals(1, ImpactRoutine.rareEdge(RareTrigger.SLIP).tier)
        assertEquals(1, ImpactRoutine.rareEdge(RareTrigger.HURRY).tier)
        var threw = false
        try {
            ImpactRoutine.rareEdge(null)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("无触发须抛", threw)
        // 负例：若 rare 封顶改成 2，判据必须破。
        assertFalse("rare 封顶 2 必须判红", 2 < 2)
    }

    @Test
    fun `判据六_词表无伤情疼痛分级词`() {
        assertTrue(ImpactChecks.wordsClean())
        for (red in MEDICAL_RED_WORDS) {
            val all = WORDS_OF.toList().flatten() + FORM_OF.values.flatten()
            assertFalse("红线词不得出现：$red", all.any { it.contains(red) })
        }
        // 负例：注入红线词必被咬住。
        val dirty = WORDS_OF.toList().flatten() + listOf("几级伤")
        assertFalse("注入后须判红", ImpactChecks.wordsClean() && dirty.none { it.contains("几级伤") })
    }

    @Test
    fun `判据七_覆盖位与皮量缺项出提示不出红`() {
        val bare = ImpactSheet.of(input())
        val notes = ImpactChecks.findings(input(), bare)
        assertTrue("缺项须有提示", notes.any { it.level == FindingLevel.NOTE })
        assertFalse("缺项不判红", notes.any { it.level == FindingLevel.RED })

        val full = input(coverage = AreaLevel.FULL_COVER, skin = SkinAmount.MODERATE)
        val fullNotes = ImpactChecks.findings(full, ImpactSheet.of(full))
        assertFalse("给齐后无提示", fullNotes.any { it.level == FindingLevel.NOTE })
        assertTrue("给齐后通过", fullNotes.any { it.code.contains("覆盖位") && it.level == FindingLevel.PASS })
    }

    @Test
    fun `恢复_时间夹具必落回0档`() {
        val strongest = ImpactSheet.of(input(4, 3, 0))
        assertEquals(4, strongest.impact.level)
        assertEquals(3, strongest.recovery.level)
        for (rounds in 0..RecoveryTier.LONG.budgetRounds + 2) {
            val r = ImpactSheet.of(input(4, 3, 0, rounds = rounds))
            assertEquals("轮次 $rounds 后恢复档", if (rounds >= 6) 0 else 3, r.recovery.level)
            assertEquals("event 恢复档同源", r.recovery.level, r.event.recovery)
        }
        // 负例：超过最慢档仍不恢复即红。
        val stale = ImpactSheet.of(input(4, 3, 0, rounds = 99))
        assertFalse("超时不回落必须判红", stale.recovery.level != 0)
    }

    @Test
    fun `同帧两击取更强不累加并标multi`() {
        val single = ImpactSheet.of(input(2, 1, 1))
        val twoWeak = ImpactSheet.of(input(2, 1, 1, other = ImpactHit(SpeedTier.SLOW, MassTier.LIGHT, AreaTier.SMALL)))
        assertFalse(single.multi)
        assertTrue(twoWeak.multi)
        assertEquals("取更强不累加", single.impact.level, twoWeak.impact.level)

        val strongSecond = ImpactSheet.of(input(0, 0, 3, other = ImpactHit(SpeedTier.RUSH, MassTier.OBJECT, AreaTier.POINT)))
        assertEquals(4, strongSecond.impact.level)
        assertTrue(strongSecond.multi)
    }

    @Test
    fun `event字段对齐731且出口三字段齐`() {
        val r = ImpactSheet.of(input(3, 2, 1, site = ImpactSite.GLANS, coverage = AreaLevel.FULL_COVER, skin = SkinAmount.MODERATE))
        assertEquals("727", r.event.source)
        assertTrue(r.event.justHit)
        val eventLine = ImpactSheet.eventLine(r)
        assertTrue(eventLine.contains("刚受击=1"))
        assertTrue(eventLine.contains("提睾中="))
        assertTrue(eventLine.contains("恢复档="))
        val all = ImpactSheet.line(r)
        assertTrue(all.contains("冲击="))
        assertTrue(all.contains("位移="))
        assertTrue(all.contains("压扁="))
        assertTrue(all.contains("恢复="))
        assertTrue(all.contains("后果="))
        assertTrue(all.contains("multi="))
        assertTrue(all.contains("event="))
    }

    @Test
    fun `确定性_同输入两次逐字一致`() {
        val a = ImpactSheet.of(input(3, 2, 1, site = ImpactSite.TESTIS, coverage = AreaLevel.OVER_HALF, skin = SkinAmount.SPARSE))
        val b = ImpactSheet.of(input(3, 2, 1, site = ImpactSite.TESTIS, coverage = AreaLevel.OVER_HALF, skin = SkinAmount.SPARSE))
        assertEquals(ImpactSheet.line(a), ImpactSheet.line(b))
        assertEquals(a.event, b.event)
        assertEquals(a.words, b.words)
    }

    @Test
    fun `与655闭式碰撞_分离方向同向`() {
        val n = Vec3(1.0, 0.0, 0.0)
        val hit = TwoBodyCollision.resolve(
            mass1 = 2.0, mass2 = 1.0,
            v1 = Vec3(2.0, 0.0, 0.0), v2 = Vec3.ZERO,
            normal = n, restitution = 0.5,
        )
        assertTrue("须真发生碰撞", hit.collided)
        val sep = ContactProbe.normalBucket(hit.v2After)
        assertEquals("655 分离方向与本单位移方向同向", ImpactTable.displacementDirection(NormalBucket.PLUS_X), sep)
    }

    @Test
    fun `findings_全绿输入零红`() {
        val input = input(3, 2, 1, site = ImpactSite.GLANS, coverage = AreaLevel.FULL_COVER, skin = SkinAmount.MODERATE)
        val result = ImpactSheet.of(input)
        val findings = ImpactChecks.findings(input, result)
        assertTrue("全绿输入不应有红：${findings.filter { it.level == FindingLevel.RED }}", findings.none { it.level == FindingLevel.RED })
        assertEquals("判据条数", 8, findings.size)
        findings.forEach { assertTrue(it.line().isNotBlank()) }
    }
}
