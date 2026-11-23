// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百八十二批 · 追加二：宗教类分教派（C7）与年代维只调可用集（C9）。
//
// C7 的分级要照派工单原文：只写「道士／和尚」不给教派 ⇒ **提示补**；婚否·饮食·发式·服饰·居住·依据缺 ⇒ **红**。
// 「依据」尤其要紧：宗教档每一项都得引教义或戒律作出处，空依据就等于把印象写进表里。
//
// C9 的口径：era 只允许走两条交接（881 衣物可用集、883 条件档的场所来源），
// 城乡两套场所菜单缺一套即红；方向写反（网购提前／早期形制滞留）也即红——那是「用年代推行为」的第一步。
package com.example.zhengdao.baopu.state.occupation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OccFaithEraTest {

    private val quanzhen = OccupationTable.byKey("daoQuanzhen")!!

    private val zhengyi = OccupationTable.byKey("daoZhengyi")!!

    private fun c7(rows: List<OccupationRow>) = OccupationGate.check(rows).filter { it.rule == "C7" }

    private fun c9(rows: List<EraAvailability>) = OccupationGate.check(eras = rows).filter { it.rule == "C9" }

    /** 把篡改过的那一格放回五档菜单的原位——C9 的「年代缺档」是按五档逐个数，只给一格就成了计数噪声。 */
    private fun splice(origin: EraAvailability, poisoned: EraAvailability): List<EraAvailability> =
        EraTable.ROWS.map { if (it.era == origin.era) poisoned else it }

    @Test
    fun `正例_九条教派各一行且七件齐`() {
        val rows = OccupationTable.withFaith()
        assertEquals(9, rows.size)
        FaithBranch.entries.forEach { branch ->
            assertEquals("${branch.label} 应该只有一行", 1, rows.count { it.faith?.branch == branch })
        }
        rows.forEach { row ->
            assertEquals("${row.key} 教派字段缺：" + row.faith!!.missingNames(), emptyList<String>(), row.faith!!.missingNames())
            assertTrue(row.faith!!.basis!!.isNotEmpty())
        }
        assertEquals(0, c7(rows).size)
    }

    @Test
    fun `全真与正一的差别落在档上`() {
        // 不婚 vs 可婚、住观 vs 居家、素食 vs 斋期外较宽、常年道装 vs 仅礼仪器务时着教服。
        assertEquals(Celibate.REQUIRED, quanzhen.faith!!.celibate)
        assertEquals(Celibate.PERMITTED, zhengyi.faith!!.celibate)
        assertEquals(FaithResidence.TEMPLE_RESIDENT, quanzhen.faith!!.residence)
        assertEquals(FaithResidence.HOME, zhengyi.faith!!.residence)
        assertEquals(FaithDiet.VEGETARIAN, quanzhen.faith!!.diet)
        assertEquals(FaithDiet.FAST_PERIOD, zhengyi.faith!!.diet)
        assertEquals(FaithDress.DAO_ROBE, quanzhen.faith!!.dress)
        assertEquals(FaithDress.RITUAL_ONLY, zhengyi.faith!!.dress)
        assertEquals(HairRule.GROWN_WITH_CAP, quanzhen.faith!!.hair)
        assertEquals(HairRule.MIGHT_GROW, zhengyi.faith!!.hair)
        assertTrue(zhengyi.faith!!.basis!!.contains(FaithBasis.HOUSEHOLDER_RULE))
        assertTrue(quanzhen.faith!!.basis!!.contains(FaithBasis.MONASTIC_PRECEPT))
    }

    @Test
    fun `神父独身与牧师可婚各引自己的教制`() {
        val priest = OccupationTable.byKey("catholicPriest")!!
        val pastor = OccupationTable.byKey("protestantPastor")!!
        assertEquals(Celibate.REQUIRED, priest.faith!!.celibate)
        assertEquals(Celibate.PERMITTED, pastor.faith!!.celibate)
        assertTrue(priest.faith!!.basis!!.contains(FaithBasis.CELIBACY_DISCIPLINE))
        assertTrue(pastor.faith!!.basis!!.contains(FaithBasis.MARRIAGE_ALLOWED))
        assertEquals(0, c7(listOf(priest, pastor)).size)
    }

    @Test
    fun `藏传分出家与在家两轨`() {
        val tibetan = OccupationTable.byKey("monkTibetan")!!
        assertEquals(Celibate.MONASTIC_ONLY, tibetan.faith!!.celibate)
        assertEquals(FaithResidence.MIXED, tibetan.faith!!.residence)
        assertEquals(
            setOf(FaithBasis.MONASTIC_PRECEPT, FaithBasis.HOUSEHOLDER_RULE),
            tibetan.faith!!.basis,
        )
    }

    @Test
    fun `没有笼统的道士或和尚条目`() {
        listOf("道士", "和尚", "僧人", "牧师", "神父").forEach { generic ->
            assertTrue("条目里不许只写到笼统名称", FaithBranch.entries.none { it.label == generic })
        }
        assertEquals(9, FaithBranch.entries.size)
    }

    @Test
    fun `负例_宗教行整槽抽掉必红`() {
        val findings = c7(listOf(quanzhen.copy(faith = null)))
        assertEquals(1, findings.size)
        assertEquals(OccSeverity.ERROR, findings.first().severity)
    }

    @Test
    fun `负例_只到教名没给教派是提示补不是红`() {
        val poisoned = quanzhen.copy(faith = quanzhen.faith!!.copy(branch = null))
        val findings = c7(listOf(poisoned))
        assertEquals(1, findings.size)
        assertEquals("派工单原文把这一条定成提示补", OccSeverity.WARN, findings.first().severity)
        assertTrue(findings.first().message.contains("请补教派"))
    }

    @Test
    fun `负例_依据清空必红`() {
        val findings = c7(listOf(quanzhen.copy(faith = quanzhen.faith!!.copy(basis = emptySet()))))
        assertEquals(1, findings.size)
        assertEquals(OccSeverity.ERROR, findings.first().severity)
        assertTrue(findings.first().message.contains("依据"))
    }

    @Test
    fun `负例_婚否或饮食缺一格必红`() {
        assertEquals(1, c7(listOf(quanzhen.copy(faith = quanzhen.faith!!.copy(celibate = null)))).size)
        assertEquals(1, c7(listOf(quanzhen.copy(faith = quanzhen.faith!!.copy(diet = null)))).size)
    }

    @Test
    fun `负例_别的行业填了教派槽必红`() {
        val findings = c7(listOf(OccupationTable.byKey("rebar")!!.copy(faith = quanzhen.faith)))
        assertEquals(1, findings.size)
        assertTrue(findings.first().message.contains("非宗教职"))
    }

    @Test
    fun `非宗教行一个都不填教派槽`() {
        assertEquals(61, OccupationTable.ROWS.count { it.faith == null })
        OccupationTable.ROWS.filter { it.faith == null }.forEach { assertTrue(it.key, it.industry != Industry.FAITH) }
    }

    @Test
    fun `正例_五档年代四类可用集全齐`() {
        assertEquals(5, EraTable.ROWS.size)
        Era.entries.forEach { era -> assertTrue(era.label, EraTable.ROWS.any { it.era == era }) }
        EraTable.ROWS.forEach { row ->
            listOf(row.forms, row.fabrics, row.supply, row.venueCity, row.venueRural).forEach {
                assertTrue("${row.era.label} 可用集有空", it != null && it.isNotEmpty())
            }
        }
        assertEquals(0, c9(EraTable.ROWS).size)
    }

    @Test
    fun `年代同时给城镇与乡村两套场所`() {
        EraTable.ROWS.forEach { row ->
            assertTrue("${row.era.label} 乡村场所缺", row.venueRural!!.isNotEmpty())
            assertTrue("${row.era.label} 城镇场所缺", row.venueCity!!.isNotEmpty())
        }
        val pre = EraTable.ROWS.first { it.era == Era.PRE_1949 }
        assertTrue(pre.venueCity!!.contains(WashVenue.PUBLIC_BATHHOUSE))
        assertEquals(listOf(WashVenue.YARD_WATER), pre.venueRural)
    }

    @Test
    fun `年代只走衣物与场所两条交接`() {
        EraTable.ROWS.forEach { row ->
            assertEquals(listOf(Handoff.ERA_GARMENT_881, Handoff.ERA_VENUE_883), row.hands)
        }
        val swapped = EraTable.ROWS.map { it.copy(hands = listOf(Handoff.BAN_HABIT_883)) }
        val findings = c9(swapped)
        assertTrue(findings.isNotEmpty())
    }

    @Test
    fun `负例_乡村场所菜单清空必红`() {
        val poisoned = EraTable.ROWS.map { it.copy(venueRural = emptyList()) }
        val findings = c9(poisoned)
        assertEquals(5, findings.count { it.message.contains("乡村场所可用集") })
    }

    @Test
    fun `负例_网购提前到五十年代必红`() {
        val mid = EraTable.ROWS.first { it.era == Era.MID_50_70 }
        val poisoned = mid.copy(supply = mid.supply!! + GarmentSupply.ONLINE)
        // 篡改的那一格要**放回五档菜单里**再判：只喂一格，C9 会连报四条「其余年代缺档」，那条真红就被淹了。
        val findings = c9(splice(mid, poisoned))
        assertEquals(1, findings.size)
        assertTrue(findings.first().message.contains("网购提前"))
    }

    @Test
    fun `负例_早期形制留到当代必红`() {
        val now = EraTable.ROWS.first { it.era == Era.NOW }
        val poisoned = now.copy(forms = now.forms!! + EraForm.LOOSE_PANTS)
        val findings = c9(splice(now, poisoned))
        assertEquals(1, findings.size)
        assertTrue(findings.first().message.contains("早期形制"))
    }

    @Test
    fun `年代不推行为_可用集里没有评价词`() {
        EraTable.ROWS.forEach { row ->
            val texts = listOf(row.era.label, row.era.span) +
                row.forms!!.map { it.label } + row.fabrics!!.map { it.label } +
                row.supply!!.map { it.label } + row.venueCity!!.map { it.label } + row.venueRural!!.map { it.label }
            OccWords.HYGIENE.forEach { word -> assertTrue("${row.era.label} 含 $word", texts.none { it.contains(word) }) }
            OccWords.STEREOTYPE.forEach { word -> assertTrue("${row.era.label} 含 $word", texts.none { it.contains(word) }) }
        }
    }
}
