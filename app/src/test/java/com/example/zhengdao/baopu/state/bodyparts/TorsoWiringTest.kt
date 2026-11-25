// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第八百七十批 · 躯干装配缝：并入 852 唯一写后口，先卡后躯干，一场一次。
package com.example.zhengdao.baopu.state.bodyparts

import com.example.zhengdao.CodeText
import com.example.zhengdao.GateBodies
import com.example.zhengdao.SrcRoots
import com.example.zhengdao.baopu.calibration.CalibrationWiring
import com.example.zhengdao.baopu.state.casting.CastingRuntime
import com.example.zhengdao.baopu.state.form.FormFixtures
import com.example.zhengdao.baopu.state.readback.BodyReadback
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TorsoWiringTest {

    private val f = TorsoFixtures

    @Before
    fun setUp() {
        CalibrationWiring.resetForTest()
        CastingRuntime.resetForTest()
        TorsoRuntime.resetForTest()
        CalibrationWiring.readbackProvider = {
            BodyReadback.render(FormFixtures.state(), FormFixtures.anatomy).lines
        }
    }

    @After
    fun tearDown() {
        TorsoRuntime.detach()
        CastingRuntime.detach()
        CalibrationWiring.resetForTest()
    }

    @Test
    fun `判据一_852写后口先卡后躯干_各出一条带场号回执`() {
        val traced = ArrayList<String>()
        var providerCalls = 0
        val jia = f.person("甲")
        val yi = f.person("乙", shape = f.shape(linea = LineaAlba.DEEP, trap = TrapSlope.STEEP))
        CastingRuntime.install(cards = { emptyList() }, trace = { traced += it })
        TorsoRuntime.install(persons = {
            providerCalls++
            listOf(jia, yi)
        }, trace = { traced += it })

        val report = CastingRuntime.onSceneWritten("S01", emptyMap(), emptySet(), cards = emptyList())

        assertEquals("852 的空卡口径不变", "无描写", report.continuity.code)
        assertEquals("一场只校验一次", 1, CastingRuntime.writtenSceneCount())
        assertEquals("人源一场只取一次", 1, providerCalls)
        assertEquals("先卡后躯干，各一条", listOf("[casting]", "[torso]"),
            traced.map { it.substringBefore(' ') })
        assertTrue("casting 回执带场号", traced[0].startsWith("[casting] 写后 场=S01 "))
        val expected = "[torso] 写后 场=S01 可见位=pending:687 " +
            "[通过] SHAPE-OK：同场 甲 与 乙：形状差 2 维（白线深浅=浅→深；斜方肌坡度=平缓→陡）" +
            "（派工单-2026-11-23-第八百四十八批（躯干形状库）＋本项目工程假设 2026-11-23）"
        assertEquals("torso 回执须逐字带场号、来源、日期与 848 原结论", expected, traced[1])
        assertEquals(1, TorsoRuntime.receiptLines().size)
        println(traced[1])
    }

    @Test
    fun `判据一_未绑人源_照实判可见者不足且仍出回执`() {
        CastingRuntime.install(cards = { emptyList() })
        TorsoRuntime.install()

        CastingRuntime.onSceneWritten("S01", emptyMap(), emptySet(), cards = emptyList())

        val line = TorsoRuntime.receiptLines().single()
        assertTrue(line.startsWith("[torso] 写后 场=S01 "))
        assertTrue(line.contains("[通过] SCENE-TOO-FEW："))
        assertTrue(line.contains("不足 2 人"))
    }

    @Test
    fun `判据二_挂缝不绑人源与没装本单逐字节一致`() {
        val baseline = CalibrationWiring.notes(TRIGGER)
        assertTrue("夹具轮本身必须有内容，否则空等无意义", baseline.isNotEmpty())

        CastingRuntime.install(cards = { emptyList() })
        TorsoRuntime.install()

        assertEquals(baseline, CalibrationWiring.notes(TRIGGER))
    }

    @Test
    fun `判据二_总开关关与detach都回到逐字节基线`() {
        CalibrationWiring.enabled = { false }
        val disabledBaseline = CalibrationWiring.notes(TRIGGER)
        CalibrationWiring.enabled = { true }
        val baseline = CalibrationWiring.notes(TRIGGER)
        CastingRuntime.install(cards = { emptyList() })
        TorsoRuntime.install(persons = { listOf(f.person("甲"), f.person("乙")) })

        CalibrationWiring.enabled = { false }
        assertEquals("装配后关开关也与未装配关开关逐字节一致", disabledBaseline,
            CalibrationWiring.notes(TRIGGER))
        CalibrationWiring.enabled = { true }
        TorsoRuntime.detach()
        CastingRuntime.detach()

        assertEquals("detach 后与没有本单逐字节一致", baseline, CalibrationWiring.notes(TRIGGER))
    }

    @Test
    fun `判据三_可见位false的同形者被排除_置true立即判红`() {
        val jia = f.person("甲")
        val yi = f.person("乙", shape = f.shape(linea = LineaAlba.DEEP, trap = TrapSlope.STEEP))
        val bing = f.person("丙", visible = false)
        CastingRuntime.install(cards = { emptyList() })
        TorsoRuntime.install(persons = { listOf(jia, yi, bing) })

        CastingRuntime.onSceneWritten("S01", emptyMap(), emptySet(), cards = emptyList())

        val hidden = TorsoRuntime.receiptLines().single()
        assertTrue("不可见的同形者不许造成矛盾", hidden.contains("[通过] SHAPE-OK："))
        CastingRuntime.onSceneWritten(
            "S02", emptyMap(), emptySet(), cards = emptyList(),
            persons = listOf(jia, yi, bing.copy(torsoVisible = true)),
        )
        val shown = TorsoRuntime.receiptLines().last()
        assertTrue("同一形状一旦调用方置为可见，848 必须照判红", shown.contains("[红] SHAPE-IDENTICAL："))
    }

    @Test
    fun `判据五_注入四次不涨场数_同场号重写也按每次写后各一次`() {
        CastingRuntime.install(cards = { emptyList() })
        TorsoRuntime.install(persons = { emptyList() })
        repeat(4) { CalibrationWiring.notes(TRIGGER) }
        assertEquals("注入不是场边界", 0, CastingRuntime.writtenSceneCount())
        assertTrue(TorsoRuntime.receiptLines().isEmpty())

        CastingRuntime.onSceneWritten("S01", emptyMap(), emptySet(), cards = emptyList())
        assertEquals("一场一次", 1, CastingRuntime.writtenSceneCount())
        CastingRuntime.onSceneWritten("S01", emptyMap(), emptySet(), cards = emptyList())

        assertEquals("同场号重写不去重，仍是两次写后", 2, CastingRuntime.writtenSceneCount())
        assertEquals("torso 回执与场数等条", 2,
            TorsoRuntime.receiptLines().count { it.startsWith("[torso] 写后 场=S01 ") })
    }

    @Test
    fun `判据六_TorsoChecks_scene全仓唯一调用点在852写后口`() {
        val castingFile = SrcRoots.find(
            "app/src/main/java/com/example/zhengdao/baopu/state/casting/CastingRuntime.kt",
        ).absoluteFile
        val repo = generateSequence(castingFile) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").isFile }
        val sources = repo.walkTopDown()
            .onEnter { !it.name.startsWith(".") && it.name != "build" }
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.replace('\\', '/').contains("/src/main/") }
            .toList()
        assertTrue("必须扫到生产源码", sources.isNotEmpty())
        assertTrue("必须包含 852 写后口", sources.contains(castingFile))
        val calls = sources
            .map { it.name to CodeText.codeOnly(it.readText(Charsets.UTF_8)).occurrences("TorsoChecks.scene(") }
            .filter { it.second > 0 }
            .toList()

        assertEquals("唯一调用点必须留在 852 写后口", listOf("CastingRuntime.kt" to 1), calls)
        val castingSource = castingFile.readText(Charsets.UTF_8)
        val hook = requireNotNull(GateBodies.functionBody(castingSource, "fun onSceneWritten(")) {
            "852 唯一写后口必须存在"
        }
        val hookCode = CodeText.codeOnly(hook)
        assertEquals("写后口内只许调用一次", 1, hookCode.occurrences("TorsoChecks.scene("))
        val cardAt = hookCode.indexOf("SceneCheck.check(")
        val torsoAt = hookCode.indexOf("TorsoChecks.scene(")
        assertTrue("852 原校验口必须在", cardAt >= 0)
        assertTrue("必须先卡后躯干", torsoAt > cardAt)
        assertEquals("不许另起 onSceneTorso", emptyList<Pair<String, Int>>(),
            sources
                .map { it.name to CodeText.codeOnly(it.readText(Charsets.UTF_8)).occurrences("fun onSceneTorso(") }
                .filter { it.second > 0 }
                .toList())
        val torso = CodeText.codeOnly(
            SrcRoots.find("app/src/main/java/com/example/zhengdao/baopu/state/bodyparts/TorsoRuntime.kt")
                .readText(Charsets.UTF_8),
        )
        assertFalse("装配层不得自造 persons 或改可见位", torso.contains("TorsoPerson("))
        assertFalse("装配层不得读取或反推 torsoVisible", torso.contains("torsoVisible"))
    }

    private fun String.occurrences(needle: String): Int = Regex(Regex.escape(needle)).findAll(this).count()

    private companion object {
        const val TRIGGER = "写一下龟头的形态"
    }
}
