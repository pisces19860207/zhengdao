// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 第九百三十批 · 873「交付自查三步」取证探针：把业务完整输出与夹具侧别打印出来，
// 断言一律用设计文档里的独立字面量（档位、码、级别中文标签），不拿被照常量的自证。
// 每条判据先钉码再钉级别：可拒绝侧改坏判据时必须由断言咬住，而不是靠 single() 崩。
package com.example.zhengdao.baopu.state.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Persona930OutputProbeTest {

    @Test
    fun `探针一_values与core冲突_触发侧与未触发侧完整输出`() {
        val core = PersonaFixture.core()
        println("FIXTURE=vc-on input=core[RECOGNITION:1,SECURITY:2,BELONGING:3]+values[安全0/自由3] " +
            "threshold=设计文档L76『前二且安全0自由3』 side=fail(落在冲突侧) distance=SECURITY rank=2≤2 且两轴取满档")
        val on = PersonaValueChecks.check(Persona930Fixture.conflictingValues(), core)
        println(full("values↔core 冲突判据", on))
        assertEquals("设计文档L76/L289：冲突必须给出这条码", listOf("VALUES_CORE_CONFLICT"), on.map { it.code })
        assertEquals("设计文档L289：冲突只给提示档，不判红", "提示", on.single().severity.label)
        assertTrue("消息缺档位读数：${on.single().message}", "安全0/自由3" in on.single().message)
        assertTrue("消息缺 rank 读数：${on.single().message}", "rank=2" in on.single().message)
        assertTrue("消息缺两轴名：${on.single().message}", "axes=SECURITY/FREEDOM" in on.single().message)

        println("FIXTURE=vc-off input=同上但安全档改1 threshold=同一门槛 side=pass(不触发) distance=安全档离0差1档")
        val offValues = Persona930Fixture.conflictingValues().let { v ->
            PersonaValues(v.weights.map { if (it.axis == ValueAxis.SECURITY) it.copy(tier = 1) else it })
        }
        val off = PersonaValueChecks.check(offValues, core)
        println(full("values↔core 未触发对照", off))
        assertEquals("设计文档L76：安全档不为0就不算冲突", 0, off.size)
    }

    @Test
    fun `探针二_污名词台词侧与叙述者侧完整输出`() {
        println("FIXTURE=stigma-dialogue input=「你这个舔狗」位置=DIALOGUE threshold=设计文档L203『台词⇒WARN』 " +
            "side=pass(台词内) distance=引号内第2词")
        val dialogue = PersonaLanguage.stigma("你这个舔狗", ClinicalPosition.DIALOGUE)
        println(full("污名词·台词侧", dialogue))
        assertEquals("设计文档L203：台词侧必须是台词码", listOf("STIGMA_DIALOGUE"), dialogue.map { it.code })
        assertEquals("设计文档L203：台词只提示人物语言偏激", "提示", dialogue.single().severity.label)

        println("FIXTURE=stigma-narrator input=他是个舔狗 threshold=设计文档L204『非台词⇒ERROR』 " +
            "side=fail(叙述者) distance=无引号无引导语")
        val narrator = PersonaLanguage.stigma("他是个舔狗")
        println(full("污名词·叙述者侧", narrator))
        assertEquals("设计文档L204：叙述者侧必须是叙述者码", listOf("STIGMA_NARRATOR"), narrator.map { it.code })
        assertEquals("设计文档L204：叙述者定型蔑称判红", "红", narrator.single().severity.label)
    }

    @Test
    fun `探针三_临床词同句与跨句医疗不确定完整输出`() {
        println("FIXTURE=clinical-same-sentence input=这位医生可能是NPD。 role=DOCTOR " +
            "threshold=设计文档L142『医生＋同句不确定词⇒WARN』 side=fail(同句命中) distance=不确定词与本句同区间")
        val same = PersonaLanguage.clinical("这位医生可能是NPD。", subjectRole = ClinicalSubjectRole.DOCTOR)
        println(full("临床词·同句医疗不确定", same))
        assertEquals("设计文档L142：医疗不确定必须给豁免码", listOf("CLINICAL_MEDICAL_UNCERTAIN"), same.map { it.code })
        assertEquals("设计文档L142：医疗不确定只提示", "提示", same.single().severity.label)

        println("FIXTURE=clinical-cross-sentence input=这位医生是NPD。他可能累了。 role=DOCTOR " +
            "threshold=同一门槛 side=fail(豁免失效) distance=不确定词被。切到下一句")
        val cross = PersonaLanguage.clinical("这位医生是NPD。他可能累了。", subjectRole = ClinicalSubjectRole.DOCTOR)
        println(full("临床词·跨句不豁免", cross))
        assertEquals("设计文档L142：跨句必须回落诊断码", listOf("DIAGNOSIS"), cross.map { it.code })
        assertEquals("设计文档L142：跨句不算同句不确定 ⇒ 红", "红", cross.single().severity.label)
    }

    @Test
    fun `探针四_二次元标签在表与越界完整输出`() {
        println("FIXTURE=tag-on-surface input=surfaceTags=[傲娇] threshold=设计文档L103『只收静态表内标签』 " +
            "side=pass distance=表内第1条")
        val onSurface = PersonaLanguage.surfaceTags(listOf("傲娇"))
        println(full("标签·在静态表内", onSurface))
        assertEquals("设计文档L103：表内标签不得报未知", 0, onSurface.size)

        println("FIXTURE=tag-unknown input=surfaceTags=[赛博朋克] threshold=同一门槛 side=fail distance=表外新造词")
        val unknown = PersonaLanguage.surfaceTags(listOf("赛博朋克"))
        println(full("标签·表外", unknown))
        assertEquals("设计文档L103：表外标签必须报未知", listOf("ANIME_TAG_UNKNOWN"), unknown.map { it.code })
        assertEquals("设计文档L42：surface 只收静态表", "红", unknown.single().severity.label)

        println("FIXTURE=tag-off-surface input=叙述文本『她很傲娇』 threshold=设计文档L42『标签只落surface』 " +
            "side=fail distance=出现在叙述句")
        val off = PersonaLanguage.offSurfaceTags(listOf("她很傲娇"))
        println(full("标签·越界到叙述", off))
        assertEquals("设计文档L42：越界标签必须报这条码", listOf("ANIME_TAG_OFF_SURFACE"), off.map { it.code })
        assertEquals("红", off.single().severity.label)
    }

    @Test
    fun `探针五_values八轴与档位上下界完整输出`() {
        val core = PersonaFixture.core()
        println("FIXTURE=axes-ok input=八轴各一条0..3 threshold=设计文档L40/L288 side=pass distance=距上界3还差0档")
        val ok = PersonaValueChecks.check(Persona930Fixture.values(), core)
        println(full("values·合法八轴", ok))
        assertEquals("设计文档L288：合法八轴⇒绿", emptyList<String>(), ok.map { it.code })

        println("FIXTURE=axes-dup input=FAMILY出现两次 threshold=同一门槛 side=fail distance=多1条")
        val dup = PersonaValueChecks.check(Persona930Fixture.duplicateFamilyValues(), core)
        println(full("values·重轴", dup))
        assertEquals("设计文档L288：重轴必须报这条码", listOf("VALUES_AXES"), dup.map { it.code })
        assertEquals("红", dup.single().severity.label)

        println("FIXTURE=tier-upper input=CAREER档位=4 threshold=设计文档L70『只取0..3』 side=fail distance=+1档")
        val high = overTier(core, 4)
        println(full("values·档位越上界", high))
        assertEquals("设计文档L70：越上界必须报这条码", listOf("VALUES_TIER"), high.map { it.code })
        assertEquals("红", high.single().severity.label)

        println("FIXTURE=tier-lower input=CAREER档位=-1 threshold=同一门槛 side=fail distance=-1档")
        val low = overTier(core, -1)
        println(full("values·档位越下界", low))
        assertEquals(listOf("VALUES_TIER"), low.map { it.code })
    }

    @Test
    fun `探针六_927三根四种证据组合完整输出`() {
        val before = PersonaFixture.trait()
        val after = before.copy(agreeableness = TraitLayer.explicit(3))
        println("FIXTURE=root-none before=agreeableness=1/DEFAULT after=3/EXPLICIT evidence=三根全空 " +
            "threshold=设计文档L116『三种引用全空⇒红』 side=fail distance=慢变根0条")
        val none = TraitChanges.review(before, after, TraitChangeEvidence())
        println(full("927·三根全空", none.items))
        assertEquals("设计文档L116：三根全空必须报这条码", listOf("TRAIT_CHANGE_NO_ROOT"), none.items.map { it.code })
        assertEquals("设计文档L116", "红", none.items.single().severity.label)
        assertTrue("变化轴缺读数：${none.items.single().message}", "变化轴=agreeableness" in none.items.single().message)

        println("FIXTURE=root-single-event evidence=eventRef=ev-1 只有事件根 threshold=设计文档L115 side=fail distance=慢变根0条")
        val single = TraitChanges.review(before, after, TraitChangeEvidence(eventRef = "ev-1"))
        println(full("927·只有事件根", single.items))
        assertEquals("设计文档L115：只有事件根必须报这条码", listOf("TRAIT_CHANGE_SINGLE_EVENT"), single.items.map { it.code })
        assertEquals("红", single.items.single().severity.label)

        println("FIXTURE=root-latent evidence=eventRef=ev-1+latentRef=lt-1 threshold=设计文档L115『带慢变根⇒通过』 " +
            "side=pass distance=慢变根1条")
        val latent = TraitChanges.review(before, after, TraitChangeEvidence(eventRef = "ev-1", latentRef = "lt-1"))
        println(full("927·带慢变根", latent.items))
        assertEquals(0, latent.items.size)

        println("FIXTURE=root-no-change before=after threshold=设计文档L117『无变化⇒WARN』 side=fail(判据另档) distance=变化轴0条")
        val unchanged = TraitChanges.review(before, before, TraitChangeEvidence(latentRef = "lt-1"))
        println(full("927·前后无变化", unchanged.items))
        assertEquals("设计文档L117：无变化必须报这条码", listOf("TRAIT_CHANGE_NONE"), unchanged.items.map { it.code })
        assertEquals("设计文档L117：无变化只提示不判红", "提示", unchanged.items.single().severity.label)
    }

    @Test
    fun `探针七_压力档位上下界完整输出`() {
        println("FIXTURE=pressure-max input=pressure=3 threshold=设计文档L89『0..3』 side=pass distance=正好卡在上界")
        val ok = PersonaStateChecks.check(PersonaMoodState(MoodTier.STEADY, Emotion.CALM, 3, null))
        println(full("state·压力上界", ok))
        assertEquals("设计文档L89：3档合法", 0, ok.size)

        println("FIXTURE=pressure-over input=pressure=4 threshold=同一门槛 side=fail distance=+1档")
        val over = PersonaStateChecks.check(PersonaMoodState(MoodTier.STEADY, Emotion.CALM, 4, null))
        println(full("state·压力越界", over))
        assertEquals("设计文档L89：越界必须报这条码", listOf("STATE_PRESSURE"), over.map { it.code })
        assertEquals("红", over.single().severity.label)
        assertTrue("消息缺实际读数：${over.single().message}", "：4" in over.single().message)
    }

    @Test
    fun `探针八_内部矛盾高低档边界与实读档位完整输出`() {
        val valid = PersonaFixture.trait().copy(
            agreeableness = TraitLayer.explicit(3),
            conscientiousness = TraitLayer.explicit(0),
        )
        val contradiction = Persona930Fixture.contradictions().single()
        println("FIXTURE=contradiction-ok high=agreeableness low=conscientiousness " +
            "threshold=设计文档L129『high>=2且low<=1且说明非空』 side=pass distance=高档距门槛2差1档/低档距门槛1差1档")
        println("READBACK 实际档位 agreeableness=${valid.bigFiveTier(TraitLayer.AGREEABLENESS)}" +
            "/${valid.agreeableness.source} conscientiousness=${valid.bigFiveTier(TraitLayer.CONSCIENTIOUSNESS)}" +
            "/${valid.conscientiousness.source}")
        val ok = TraitContradictionChecks.check(valid, listOf(contradiction))
        println(full("矛盾·合法夹具", ok))
        assertEquals("设计文档L291：主夹具必须有实读档位", "3", valid.bigFiveTier(TraitLayer.AGREEABLENESS).toString())
        assertEquals("0", valid.bigFiveTier(TraitLayer.CONSCIENTIOUSNESS).toString())
        assertEquals("EXPLICIT", valid.agreeableness.source)
        assertEquals("合法矛盾不该出现红码", emptyList<String>(), ok.map { it.code })

        println("FIXTURE=contradiction-low-high input=agreeableness=1(DEFAULT) threshold=同一门槛 " +
            "side=fail distance=高档比2还低1档")
        val invalid = TraitContradictionChecks.check(PersonaFixture.trait(), listOf(contradiction))
        println(full("矛盾·高档不足", invalid))
        assertEquals("设计文档L129：高档不足必须报这条码", listOf("TRAIT_CONTRADICTION_INVALID"), invalid.map { it.code })
        assertEquals("红", invalid.single().severity.label)
    }

    @Test
    fun `探针九_聚合总检完整输出`() {
        val enhancement = Persona930Fixture.enhancement()
        println("FIXTURE=enhancement-ok input=819人物+values(八轴0..3)+state(平稳/CALM/压力1/note=此刻有些紧)" +
            "+矛盾(高宜人性3/低尽责性0) threshold=设计文档L57与L291『合法增强夹具总检不得出红』 " +
            "side=pass distance=全部判据在可接受侧")
        val report = PersonaEnhancementChecks.check(enhancement)
        println(full("增强总检（④聚合）", report.items))
        println("READBACK line()=${report.line()}")
        println("READBACK surface.tags=${enhancement.base.surface.tags}")
        val red = report.items.count { it.severity.label == "红" }
        assertEquals("设计文档L288–L294：合法夹具零红", 0, red)
        assertEquals("设计文档L57：十二轴提示恰好一条", 1, report.items.count { it.severity.label == "提示" })
        val annotation = report.items.single { it.code == "TRAIT_AXES" }.message
        assertTrue("缺十二轴标注原文：$annotation", "819 七轴 → 930 十二轴（新增五因子）" in annotation)
        assertTrue("缺五因子读数：$annotation", "agreeableness=3(EXPLICIT)" in annotation)
        assertEquals("设计文档L42：标签只在 SurfaceState.tags", listOf("傲娇"), enhancement.base.surface.tags)
    }

    private fun overTier(core: CoreLayer, tier: Int): List<CheckItem> {
        val values = PersonaValues(
            ValueAxis.entries.map { axis ->
                ValueWeight(axis, if (axis == ValueAxis.CAREER) tier else 1)
            },
        )
        return PersonaValueChecks.check(values, core)
    }

    private fun full(title: String, items: List<CheckItem>): String {
        val body = if (items.isEmpty()) {
            "  (无红无提示)"
        } else {
            items.joinToString("\n") { "  级别=${it.severity.label} 码=${it.code} 文=${it.message}" }
        }
        val red = items.count { it.severity.label == "红" }
        val warn = items.count { it.severity.label == "提示" }
        return "<<BEGIN $title\n$body\n  合计 红=$red 提示=$warn 条数=${items.size}\nEND>> $title"
    }
}
