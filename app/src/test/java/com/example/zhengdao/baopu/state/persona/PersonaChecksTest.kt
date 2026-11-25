// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 第八百一十九批 · 总检判据：三层齐 / 十维齐 / 口头禅真出现 / 崩相必指定 /
// 底层带触发 / 禁心理诊断 / 归属硬规矩 / 台词互换。
package com.example.zhengdao.baopu.state.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaChecksTest {

    private fun codes(r: CheckReport): List<String> = r.items.map { it.code }

    @Test
    fun `判据_合法人物三层十维齐且无红`() {
        val r = PersonaChecks.check(PersonaFixture.persona())
        assertTrue(r.line(), r.passed)
        assertEquals(emptyList<String>(), r.errors.map { it.code })
        // 合法夹具：口头禅出现、崩相有、底层带触发 ⇒ 无对应提示。
        assertFalse("口头禅真出现", "CATCHPHRASE_NEVER" in codes(r))
        assertFalse("崩相已指定", "BREAKDOWN_NONE" in codes(r))
        assertFalse("底层带触发", "CORE_NO_TRIGGER" in codes(r))
    }

    @Test
    fun `判据_底层不足三条或超五条判红`() {
        val two = PersonaFixture.core().copy(drives = PersonaFixture.core().drives.take(2))
        val six = PersonaFixture.core().copy(
            drives = PersonaFixture.core().drives + listOf(
                CoreDrive(CoreNeed.FREEDOM, "被束缚", 4, "被限制时"),
                CoreDrive(CoreNeed.DOMINANCE, "被轻视", 5, "被挑战时"),
                CoreDrive(CoreNeed.SECURITY, "被抛下", 6, "独处时"),
            ),
        )
        val r2 = PersonaChecks.check(PersonaFixture.persona().copy(core = two))
        val r6 = PersonaChecks.check(PersonaFixture.persona().copy(core = six))
        assertTrue("CORE_COUNT" in codes(r2))
        assertTrue("CORE_COUNT" in codes(r6))
    }

    @Test
    fun `判据_底层只列需求不给触发则提示`() {
        val noTrigger = PersonaFixture.core().copy(
            drives = PersonaFixture.core().drives.map { it.copy(trigger = "") },
        )
        val r = PersonaChecks.check(PersonaFixture.persona().copy(core = noTrigger))
        assertTrue("不给触发不判红，只提示", r.passed)
        assertTrue("CORE_NO_TRIGGER" in r.warnings.map { it.code })
    }

    @Test
    fun `判据_底层排序并列或需求重复判红`() {
        val c = PersonaFixture.core()
        val dupRank = c.copy(drives = listOf(c.drives[0], c.drives[1].copy(rank = 1), c.drives[2]))
        val dupNeed = c.copy(drives = listOf(c.drives[0], c.drives[0].copy(rank = 2), c.drives[2]))
        assertTrue("CORE_RANK" in codes(PersonaChecks.check(PersonaFixture.persona().copy(core = dupRank))))
        assertTrue("CORE_NEED_DUP" in codes(PersonaChecks.check(PersonaFixture.persona().copy(core = dupNeed))))
    }

    @Test
    fun `判据_口头禅超六字判红`() {
        val long = PersonaFixture.voiceA().copy(
            dimensions = PersonaFixture.voiceA().dimensions.copy(catchphrase = "这可真是说实在的话呀"),
        )
        val r = PersonaChecks.check(PersonaFixture.persona().copy(voice = long))
        assertTrue("CATCHPHRASE_LONG" in codes(r))
    }

    @Test
    fun `判据_口头禅写了却永不出现只提示`() {
        val never = PersonaFixture.voiceA().copy(
            dimensions = PersonaFixture.voiceA().dimensions.copy(catchphrase = "绝不会出现"),
        )
        val r = PersonaChecks.check(PersonaFixture.persona().copy(voice = never))
        assertFalse("永不出现不判红，只提示", "CATCHPHRASE_LONG" in codes(r))
        assertTrue("CATCHPHRASE_NEVER" in r.warnings.map { it.code })
    }

    @Test
    fun `判据_崩相未指定即永不崩只提示`() {
        val noBreak = PersonaFixture.voiceA().copy(
            dimensions = PersonaFixture.voiceA().dimensions.copy(breakdown = null),
        )
        val r = PersonaChecks.check(PersonaFixture.persona().copy(voice = noBreak))
        assertTrue("BREAKDOWN_NONE" in r.warnings.map { it.code })
    }

    @Test
    fun `判据_禁心理诊断依恋类型与人格量表一律红`() {
        val terms = listOf("依恋类型", "回避依恋", "attachment", "人格量表", "色彩性格", "MBTI")
        assertEquals(6, terms.size)
        terms.forEach { term ->
            val poisoned = PersonaFixture.core().copy(
                drives = PersonaFixture.core().drives.map { it.copy(coreFear = "怕$term") },
            )
            val r = PersonaChecks.check(PersonaFixture.persona().copy(core = poisoned))
            assertTrue("命中'$term'须红：$term", "DIAGNOSIS" in codes(r))
        }
    }

    @Test
    fun `930_台词临床词放行台词污名词只WARN`() {
        val original = PersonaFixture.voiceA()
        val voice = original.copy(
            lines = listOf(
                VoiceLine(
                    "他说：「你是NPD。」你这个舔狗。",
                    LineFeatures(SentenceLength.LONG, DictionLevel.LITERARY, "叫全名", "说实在的"),
                ),
            ),
        )
        val report = PersonaChecks.check(PersonaFixture.persona().copy(voice = voice))
        assertFalse("DIAGNOSIS" in codes(report))
        assertTrue("STIGMA_DIALOGUE" in report.warnings.map { it.code })
    }

    @Test
    fun `930_surface标签合法且core标签越界红`() {
        val legal = PersonaFixture.persona().copy(
            surface = PersonaFixture.persona().surface.copy(tags = listOf("傲娇")),
        )
        assertTrue(PersonaChecks.check(legal).passed)
        val poisoned = PersonaFixture.core().copy(
            drives = PersonaFixture.core().drives.map { it.copy(trigger = "她很傲娇") },
        )
        assertTrue("ANIME_TAG_OFF_SURFACE" in codes(PersonaChecks.check(PersonaFixture.persona().copy(core = poisoned))))
    }

    @Test
    fun `判据_归属硬规矩稳定指三层临时指表层`() {
        assertNull(PersonaChecks.attribute("他就是这种人，向来如此", PersonaLayer.TRAIT))
        assertNull(PersonaChecks.attribute("他就是这种人，向来如此", PersonaLayer.CORE))
        assertEquals(
            "ATTR_STABLE_TO_SURFACE",
            PersonaChecks.attribute("他就是这种人", PersonaLayer.SURFACE)?.code,
        )
        assertNull(PersonaChecks.attribute("他今天这样", PersonaLayer.SURFACE))
        assertEquals(
            "ATTR_MOMENT_TO_CORE",
            PersonaChecks.attribute("他今天这样", PersonaLayer.TRAIT)?.code,
        )
        assertEquals(
            "ATTR_MOMENT_TO_CORE",
            PersonaChecks.attribute("他今天这样", PersonaLayer.CORE)?.code,
        )
    }

    @Test
    fun `判据_同场两人台词互换读不出差异判红`() {
        val r = PersonaChecks.swapCheck(PersonaFixture.voiceA(), PersonaFixture.voiceB())
        assertTrue(r.line(), r.passed)
        val bad = PersonaChecks.swapCheck(PersonaFixture.voiceA(), PersonaFixture.voiceA())
        assertFalse("一人两张嘴 ⇒ 红", bad.passed)
        assertTrue("SWAP_AB" in codes(bad))
        assertTrue("SWAP_BA" in codes(bad))
    }

    @Test
    fun `判据_只给性格开朗而无状态来源判红`() {
        // 表层五维齐，但每维 source 为空 ⇒ 不是状态推出来的 ⇒ 红。
        val noSource = SurfaceState(
            SurfaceAxis.entries.map { SurfaceLevel(it, 0, "") },
        )
        val r = PersonaChecks.check(PersonaFixture.persona().copy(surface = noSource))
        assertTrue("只给笼统性格、无来源 ⇒ 红", "SURFACE_NO_SOURCE" in codes(r))
        assertFalse(r.passed)
    }

    @Test
    fun `判据_十维齐由非空构造保证且有可核对快照`() {
        val v = PersonaFixture.voiceA().dimensions
        // 八维非空类型：句长/用词/脏话/称呼/语气词/停顿/反问/打断——构造期即保证非 null。
        val required = listOf<Any>(
            v.sentenceLength, v.diction, v.profanity, v.address,
            v.modalParticle, v.pauseEllipsis, v.rhetoric, v.interruption,
        )
        assertEquals(8, required.size)
        // 另两维：口头禅 ≤6 字、崩相已指定——合法夹具里也在场（各自判据另测）。
        assertTrue(v.catchphrase != null && v.catchphrase.length <= 6)
        assertTrue(v.hasBreakdown)
    }

    @Test
    fun `930_特质快照明确819七轴演进为十二轴`() {
        val report = PersonaChecks.check(PersonaFixture.persona())
        val text = report.items.first { it.code == "TRAIT_AXES" }.message
        assertTrue(text.contains("819 七轴 → 930 十二轴"))
        TraitLayer.BIG_FIVE_AXES.forEach { axis -> assertTrue(text.contains(axis)) }
        assertTrue(text.contains("1(DEFAULT)"))
    }

    @Test
    fun `判据_同输入同结论逐字一致`() {
        val a = PersonaChecks.check(PersonaFixture.persona())
        val b = PersonaChecks.check(PersonaFixture.persona())
        assertEquals(a, b)
        assertEquals(a.line(), b.line())
    }
}
