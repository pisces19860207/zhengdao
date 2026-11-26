package com.example.zhengdao.baopu.state.persona

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Persona930SourceAssertTest {
    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/persona/"
    private val personaPackage = "com.example.zhengdao.baopu.state.persona"
    private val newFiles = listOf(
        "PersonaValues.kt", "PersonaMoodState.kt", "PersonaLanguage.kt", "TraitChanges.kt", "PersonaEnhancement.kt",
    )

    @Test
    fun `全仓核心人格类型各声明一次且无平行personality包`() {
        val sources = productionSources()
        val personaSources = sources.filterValues { packageOf(it) == personaPackage }
        assertTrue("必须扫描到人格包生产源码", personaSources.isNotEmpty())
        listOf("CoreLayer", "TraitLayer", "SurfaceState", "PersonaValues", "PersonaMoodState").forEach { name ->
            val pattern = Regex("\\b(?:data\\s+)?class\\s+$name\\b")
            assertEquals(name, 1, personaSources.count { (_, code) -> pattern.containsMatchIn(code) })
        }
        listOf("PersonaValues", "PersonaMoodState").forEach { name ->
            val pattern = Regex("\\b(?:data\\s+)?class\\s+$name\\b")
            assertEquals("$name 全仓不得有第二份", 1, sources.count { (_, code) -> pattern.containsMatchIn(code) })
        }
        assertFalse(sources.keys.any { it.invariantSeparatorsPath.contains("/state/personality/") })
        assertFalse(sources.values.any { packageOf(it) == "com.example.zhengdao.baopu.state.personality" })
        val publicTopLevel = Regex(
            "(?m)^(?:public\\s+)?(?:(?:data|sealed|annotation|value|enum)\\s+)*(?:class|object|interface)\\b" +
                "|^(?:public\\s+)?(?:suspend\\s+)?fun\\b" +
                "|^(?:public\\s+)?(?:const\\s+)?(?:val|var)\\b",
        )
        assertTrue(publicTopLevel.containsMatchIn("fun leaked() = Unit"))
        assertTrue(publicTopLevel.containsMatchIn("public data class Leaked"))
        assertFalse(publicTopLevel.containsMatchIn("internal fun hidden() = Unit"))
        assertFalse(publicTopLevel.containsMatchIn("    fun member() = Unit"))
        newFiles.forEach { name ->
            val text = code(name)
            assertTrue("$name 必须 internal", text.contains("internal "))
            assertFalse("$name 存在公开顶层声明", publicTopLevel.containsMatchIn(text))
        }
    }

    @Test
    fun `states不引用稳定层trait变动不引用states`() {
        val mood = code("PersonaMoodState.kt")
        listOf("TraitLayer", "CoreLayer", "SurfaceState", "SurfaceDeriver", "derive", "infer", "predict").forEach {
            assertFalse("mood 不得含 $it", mood.contains(it))
        }
        val changes = code("TraitChanges.kt")
        listOf("PersonaMoodState", "MoodTier", "Emotion", "pressure").forEach {
            assertFalse("trait 变动不得含 $it", changes.contains(it))
        }
    }

    @Test
    fun `values不由职业关系身体锚点推导`() {
        val values = code("PersonaValues.kt")
        listOf("Occupation", "Relationship", "RelationKind", "BodyAxis", "Anchor", "personaRate", "state.relation", "intake.peer", "derive", "infer").forEach {
            assertFalse("values 不得含 $it", values.contains(it))
        }
    }

    @Test
    fun `SurfaceDeriver唯一且930五件无表达推导函数`() {
        val count = productionSources().values.count {
            Regex("\\bobject\\s+SurfaceDeriver\\b").containsMatchIn(it)
        }
        assertEquals(1, count)
        newFiles.forEach { name ->
            val text = code(name)
            assertFalse(Regex("\\bfun\\s+derive\\b").containsMatchIn(text))
            assertFalse(Regex("\\b(?:object|class)\\s+\\w*Deriver\\b").containsMatchIn(text))
        }
    }

    @Test
    fun `语言表静态且标签字段只落SurfaceState`() {
        val language = code("PersonaLanguage.kt")
        listOf("synonym", "vector", "embedding", "semantic", "同义", "向量").forEach {
            assertFalse("语言闸不得运行时联想 $it", language.contains(it))
        }
        val personaSources = productionSources().filterValues { packageOf(it) == personaPackage }
        assertTrue("必须扫描到人格包生产源码", personaSources.isNotEmpty())
        val tagFields = personaSources.count { (_, code) -> Regex("\\bval\\s+tags\\s*:").containsMatchIn(code) }
        assertEquals(1, tagFields)
        assertTrue(code("PersonaModel.kt").contains("data class SurfaceState"))
    }

    @Test
    fun `五个新文件零IO随机时钟且不碰747710921`() {
        val text = newFiles.joinToString("\n") { code(it) }
        listOf("Random", "currentTimeMillis", "nanoTime", "java.io", "readText", "writeText", "state.anchor", "state.relation", "intake.peer", "personaRate").forEach {
            assertFalse("新层不得含 $it", text.contains(it))
        }
    }

    private fun code(name: String): String = codeOf(SrcRoots.find(dir + name))

    private fun codeOf(file: File): String = stripComments(file.readText())

    private fun productionSources(): Map<File, String> = productionFiles().associateWith(::codeOf)

    private fun packageOf(code: String): String? =
        Regex("(?m)^[ \\t]*package[ \\t]+([A-Za-z0-9_.]+)[ \\t]*;?[ \\t]*$").find(code)?.groupValues?.get(1)

    private fun repositoryRoot(): File =
        generateSequence(SrcRoots.find(dir + "PersonaModel.kt").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }

    private fun productionFiles(): List<File> {
        val root = repositoryRoot()
        val modules = listOf("app", "core", "native-core", "policy", "macrobenchmark")
        return modules.flatMap { module ->
            val src = File(root, "$module/src")
            if (!src.isDirectory) emptyList() else src.walkTopDown()
                .filter { it.isFile && it.extension in setOf("kt", "java") }
                .filter { file ->
                    val parts = file.relativeTo(src).invariantSeparatorsPath.split('/')
                    parts.size >= 3 && parts[1] in setOf("java", "kotlin") && !isTestSourceSet(parts[0])
                }.toList()
        }.distinctBy { it.absolutePath }
    }

    private fun isTestSourceSet(name: String): Boolean =
        name in setOf("test", "androidTest", "testFixtures") ||
            name.removePrefix("test").firstOrNull()?.isUpperCase() == true ||
            name.removePrefix("androidTest").firstOrNull()?.isUpperCase() == true

    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
            when {
                two == "//" -> i = skipLine(src, i, out)
                two == "/*" -> i = skipBlock(src, i, out)
                src[i] == '\'' -> i = copyChar(src, i, out)
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

    private fun copyChar(src: String, start: Int, out: StringBuilder): Int {
        out.append(src[start])
        var i = start + 1
        while (i < src.length) {
            out.append(src[i])
            if (src[i] == '\\' && i + 1 < src.length) { out.append(src[i + 1]); i += 2; continue }
            if (src[i] == '\'') { i++; break }
            i++
        }
        return i
    }

    private fun copyString(src: String, start: Int, out: StringBuilder): Int {
        if (src.startsWith("\"\"\"", start)) {
            out.append("\"\"\"")
            var i = start + 3
            while (i < src.length) {
                if (src.startsWith("\"\"\"", i)) {
                    out.append("\"\"\"")
                    return i + 3
                }
                out.append(src[i]); i++
            }
            return i
        }
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
