package com.example.zhengdao.baopu.state.smell

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OdorSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/smell/"
    private val files = listOf(
        "OdorGrades.kt", "OdorWords.kt", "OdorRefs.kt", "OdorModel.kt",
        "OdorRules.kt", "OdorChecks.kt", "Odor.kt", "OdorRenderer.kt",
    ).map { dir + it }

    private fun code(exceptWords: Boolean = false): String = files
        .filterNot { exceptWords && it.endsWith("OdorWords.kt") }
        .joinToString("\n") { stripComments(SrcRoots.find(it).readText()) }

    @Test
    fun `边界_八个生产文件全部存在`() {
        files.forEach { assertTrue(it, SrcRoots.find(it).exists()) }
    }

    @Test
    fun `边界_零IO零随机零时钟`() {
        val text = code()
        listOf("Random", "currentTimeMillis", "nanoTime", "java.io", "readText", "getenv", "File(", "URL")
            .forEach { assertFalse("纯函数层不得出现 $it", text.contains(it)) }
    }

    @Test
    fun `边界_零装配零闸零落库`() {
        val text = code()
        listOf("RunEngine", "Wiring", "Assembler", "ZhengdaoApp", "RiskMatrix", "BaopuSql", "insertRunLog", "Context")
            .forEach { assertFalse("不得装配或落库 $it", text.contains(it)) }
    }

    @Test
    fun `边界_不改730657883882且不复制天气档`() {
        val text = code()
        listOf(
            "state.occupation", "state.thermo", "state.hygiene", "OccupationRow", "OccWords",
            "ThermalBand", "HumidityBand", "WeatherGrade",
        ).forEach { assertFalse("不得引用或复制 $it", text.contains(it)) }
        assertFalse(text.contains("Double"))
        assertFalse(text.contains("Float"))
    }

    @Test
    fun `引用_只准657780806与730883来源槽`() {
        val imports = code().lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import com.example.") }
            .toList()
        val allowed = listOf("state.environment.", "state.fluid.", "state.surfaceflow.")
        imports.forEach { line ->
            assertTrue("越界 import：$line", allowed.any { line.contains("baopu.$it") })
        }
        listOf("730", "657", "780", "806", "883").forEach { source ->
            assertTrue("缺来源 $source", code().contains(source))
        }
    }

    @Test
    fun `强度_883引用类内没有公式`() {
        val body = code().substringAfter("internal data class SmellIntensityRef").substringBefore("\n}")
        listOf("smellIntensity +", "smellIntensity -", "smellIntensity *", "smellIntensity /", "coerceIn", "when")
            .forEach { assertFalse("强度不得自算：$it", body.contains(it)) }
        assertTrue(body.contains("883"))
    }

    @Test
    fun `词表_唯一禁词住处且只有val`() {
        val words = stripComments(SrcRoots.find(dir + "OdorWords.kt").readText())
        listOf("fun ", "if ", "when ", "class ").forEach {
            assertFalse("词表不许藏逻辑 $it", words.contains(it))
        }
        val banned = OdorWords.DIAGNOSIS + OdorWords.VALUE_JUDGMENT + OdorWords.BEHAVIOR_GUIDANCE +
            OdorWords.OCCUPATION_INFERENCE + OdorWords.FRESH_SWEAT_SOUR + OdorWords.WASHED_STILL_ODOR +
            OdorWords.POINT_VALUE
        val elsewhere = code(exceptWords = true)
        banned.forEach { word -> assertFalse("禁词抄到别处：$word", elsewhere.contains(word)) }
    }

    @Test
    fun `字段_六项用户点名符号齐全`() {
        val text = code()
        listOf("odorPresent", "odorSource", "odorWord", "odorRegion", "odorTemporal", "odorSeasonal")
            .forEach { assertTrue("缺字段 $it", text.contains(it)) }
    }

    @Test
    fun `闸自检_注入禁词必被咬住`() {
        val poisoned = stripComments("val bad = \"狐臭 底层 pH 推荐香水\"")
        assertTrue(OdorWords.DIAGNOSIS.any { poisoned.contains(it) })
        assertTrue(OdorWords.VALUE_JUDGMENT.any { poisoned.contains(it) })
        assertTrue(OdorWords.POINT_VALUE.any { poisoned.contains(it) })
        assertTrue(OdorWords.BEHAVIOR_GUIDANCE.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_剥注释后注释里的禁词不算数`() {
        val only = stripComments("// 这里只提到狐臭与 pH 作说明\nval ok = 1")
        assertFalse(OdorWords.DIAGNOSIS.any { only.contains(it) })
        assertFalse(OdorWords.POINT_VALUE.any { only.contains(it) })
    }

    @Test
    fun `码册_不重复`() {
        val fields = OdorCodes::class.java.declaredFields.filter { it.type == String::class.java }
        fields.forEach { it.isAccessible = true }
        val codes = fields.map { it.get(null) as String }
        assertEquals(codes.size, codes.toSet().size)
    }

    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
            when {
                two == "//" -> i = skipLine(src, i, out)
                two == "/*" -> i = skipBlock(src, i, out)
                src[i] == '"' -> i = copyString(src, i, out)
                else -> { out.append(src[i]); i++ }
            }
        }
        return out.toString()
    }

    private fun skipLine(src: String, start: Int, out: StringBuilder): Int {
        var i = start + 2
        out.append("  ")
        while (i < src.length && src[i] != '\n') { out.append(' '); i++ }
        return i
    }

    private fun skipBlock(src: String, start: Int, out: StringBuilder): Int {
        var i = start + 2
        var depth = 1
        out.append("  ")
        while (i < src.length && depth > 0) {
            val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
            if (two == "/*") { depth++; out.append("  "); i += 2 }
            else if (two == "*/") { depth--; out.append("  "); i += 2 }
            else { if (src[i] != '\n') out.append(' ') else out.append('\n'); i++ }
        }
        return i
    }

    private fun copyString(src: String, start: Int, out: StringBuilder): Int {
        var i = start
        out.append(src[i]); i++
        while (i < src.length) {
            out.append(src[i])
            if (src[i] == '\\' && i + 1 < src.length) { out.append(src[i + 1]); i += 2; continue }
            if (src[i] == '"') { i++; break }
            i++
        }
        return i
    }
}
