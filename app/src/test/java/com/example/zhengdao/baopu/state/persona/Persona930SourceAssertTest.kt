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
    private val base819Files = listOf("PersonaModel.kt", "PersonaChecks.kt", "SurfaceDeriver.kt", "VoiceSwap.kt")
    private val zoneSurfaceState = "app/src/main/java/com/example/zhengdao/baopu/state/zone/ZoneState.kt"
    private val declarationKeywords = setOf("fun", "val", "var", "class", "object", "interface", "enum", "typealias")

    @Test
    fun `全仓核心人格类型各声明一次且无平行personality包`() {
        val sources = productionSources()
        assertTrue("生产源码扫描不得为空，实际 ${sources.size} 个文件", sources.size > 200)
        val persona = sources.filterValues { packageOf(it) == personaPackage }
        assertTrue("必须扫描到人格包生产源码", persona.isNotEmpty())
        listOf("CoreLayer", "TraitLayer", "SurfaceState", "PersonaValues", "PersonaMoodState").forEach { name ->
            assertEquals("$name 人格包内必须恰好一处", 1, occurrences(typePattern(name), persona.values))
        }
        listOf("CoreLayer", "TraitLayer", "PersonaValues", "PersonaMoodState").forEach { name ->
            assertEquals("$name 全仓不得有第二份", 1, occurrences(typePattern(name), sources.values))
        }
        assertEquals(
            "SurfaceState 全仓只许人格层一处加身体zone层既有一处",
            setOf(dir + "PersonaModel.kt", zoneSurfaceState),
            sources.filterValues { typePattern("SurfaceState").containsMatchIn(it) }.keys.toSet(),
        )
        assertFalse(sources.keys.any { it.contains("/state/personality/") })
        assertFalse(sources.values.any { packageOf(it) == "com.example.zhengdao.baopu.state.personality" })
    }

    @Test
    fun `人格包文件集合封闭且930新层只许internal`() {
        val persona = productionSources().filterValues { packageOf(it) == personaPackage }
        assertEquals(
            "人格包生产文件必须留在已知集合内，新增第二套模型文件要同时改本清单",
            (base819Files + newFiles).toSet(),
            persona.keys.map { it.substringAfterLast('/') }.toSet(),
        )
        val layers = internalLayerSources()
        assertEquals(newFiles.toSet(), layers.keys.map { it.substringAfterLast('/') }.toSet())
        layers.forEach { (path, text) ->
            assertTrue("$path 必须 internal", text.contains("internal "))
            assertEquals("$path 存在公开顶层声明", emptyList<String>(), publicTopLevelDeclarations(text))
        }
        listOf(
            "fun leaked() = Unit",
            "public data class Leaked(val a: Int)",
            "data class DefaultPublic(val a: Int)",
            "    data class Indented(val a: Int)",
            "infix fun spaced(a: Int, b: Int) = a",
            "suspend fun delayed() = Unit",
            "public abstract class Open",
            "sealed interface Node",
            "enum class Extra { A }",
            "typealias Alias = String",
            "const val LIMIT = 3",
            "val top = 3",
            "internal object O {\n    fun member() = Unit\n}\nval leaked = 1",
        ).forEach { assertTrue("公开顶层闸必须命中：$it", publicTopLevelDeclarations(it).isNotEmpty()) }
        listOf(
            "package com.example.p\n\nimport java.util.Date\n\ninternal data class A(\n    val x: Int,\n)",
            "internal object O {\n    fun member() = Unit\n    val tags: List<String> = emptyList()\n}",
            "private fun hidden() = Unit",
            "@JvmStatic\ninternal fun s() = Unit",
        ).forEach { assertEquals("internal 代码不得误判：$it", emptyList<String>(), publicTopLevelDeclarations(it)) }
        assertTrue("公开面夹具必须能被闸看见", publicTopLevelDeclarations(code("PersonaModel.kt")).isNotEmpty())
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
        listOf(
            "Occupation", "Relationship", "RelationKind", "BodyAxis", "Anchor", "personaRate",
            "state.relation", "intake.peer", "derive", "infer",
        ).forEach { assertFalse("values 不得含 $it", values.contains(it)) }
        internalLayerSources().forEach { (path, text) ->
            assertFalse("$path 不得出现推导动词", Regex("\\b(?:derive|infer|predict)[A-Za-z]*\\b").containsMatchIn(text))
        }
    }

    @Test
    fun `SurfaceDeriver唯一且930五件无表达推导函数`() {
        val sources = productionSources()
        assertEquals(
            "全仓 object SurfaceDeriver 必须恰好一处",
            1,
            occurrences(Regex("\\bobject\\s+SurfaceDeriver\\b"), sources.values),
        )
        val persona = sources.filterValues { packageOf(it) == personaPackage }
        val extraDerivers = persona
            .filterKeys { it != dir + "SurfaceDeriver.kt" }
            .filterValues { Regex("\\b(?:object|class|interface)\\s+\\w*Deriver\\b").containsMatchIn(it) }
            .keys.toList()
        assertEquals("人格包除 SurfaceDeriver.kt 外不得再声明 Deriver", emptyList<String>(), extraDerivers)
        internalLayerSources().forEach { (path, text) ->
            assertFalse("$path 不得出现推导函数", Regex("\\bfun\\s+derive\\b").containsMatchIn(text))
        }
    }

    @Test
    fun `语言表静态且标签字段只落SurfaceState`() {
        val language = code("PersonaLanguage.kt")
        listOf("synonym", "vector", "embedding", "semantic", "同义", "向量").forEach {
            assertFalse("语言闸不得运行时联想 $it", language.contains(it))
        }
        val persona = productionSources().filterValues { packageOf(it) == personaPackage }
        assertTrue("必须扫描到人格包生产源码", persona.isNotEmpty())
        assertEquals(
            "标签字段在人格包内必须恰好一处",
            1,
            occurrences(Regex("\\bval\\s+tags\\s*:"), persona.values),
        )
        assertTrue(code("PersonaModel.kt").contains("data class SurfaceState"))
    }

    @Test
    fun `五个新文件零IO随机时钟且不碰747710921`() {
        val layers = internalLayerSources()
        assertEquals(newFiles.toSet(), layers.keys.map { it.substringAfterLast('/') }.toSet())
        val text = layers.values.joinToString("\n")
        listOf(
            "Random", "currentTimeMillis", "nanoTime", "java.io", "readText", "writeText",
            "state.anchor", "state.relation", "intake.peer", "personaRate",
        ).forEach { assertFalse("新层不得含 $it", text.contains(it)) }
    }

    private fun code(name: String): String = codeOf(SrcRoots.find(dir + name))

    private fun codeOf(file: File): String = stripComments(file.readText())

    private fun productionSources(): Map<String, String> {
        val root = repositoryRoot()
        return productionFiles().associateBy(
            { it.relativeTo(root).invariantSeparatorsPath },
            { codeOf(it) },
        )
    }

    private fun internalLayerSources(): Map<String, String> = productionSources()
        .filterValues { packageOf(it) == personaPackage }
        .filterKeys { it.substringAfterLast('/') !in base819Files }

    private fun occurrences(pattern: Regex, texts: Iterable<String>): Int =
        texts.sumOf { pattern.findAll(it).count() }

    private fun typePattern(name: String): Regex = Regex("\\b(?:data\\s+)?class\\s+$name\\b")

    private fun packageOf(code: String): String? =
        Regex("(?m)^[ \\t]*package[ \\t]+([A-Za-z0-9_.]+)[ \\t]*;?[ \\t]*$").find(code)?.groupValues?.get(1)

    private fun publicTopLevelDeclarations(code: String): List<String> =
        topLevelOnly(code).lines().map { it.trim() }.filter { line ->
            val words = line.substringBefore('(').split(Regex("[\\s<>=:.,?]+")).filter { it.isNotEmpty() }
            line.isNotEmpty() && !line.startsWith("package ") && !line.startsWith("import ") &&
                words.any { it in declarationKeywords } && words.none { it == "internal" || it == "private" }
        }

    private fun topLevelOnly(code: String): String {
        val out = StringBuilder(code.length)
        var i = 0
        var depth = 0
        while (i < code.length) {
            val c = code[i]
            if (c == '"' || c == '\'') {
                val end = literalEnd(code, i)
                out.append(if (depth == 0) code.substring(i, end) else mask(code.substring(i, end)))
                i = end
            } else if (c in "([{") {
                out.append(if (depth == 0) c else ' ')
                depth++
                i++
            } else if (c in ")]}") {
                depth = if (depth > 0) depth - 1 else 0
                out.append(if (depth == 0) c else ' ')
                i++
            } else {
                out.append(if (depth == 0) c else if (c == '\n') '\n' else ' ')
                i++
            }
        }
        return out.toString()
    }

    private fun mask(text: String): String = text.map { if (it == '\n') '\n' else ' ' }.joinToString("")

    private fun literalEnd(src: String, start: Int): Int {
        val quote = src[start]
        if (quote == '"' && src.startsWith("\"\"\"", start)) {
            val close = src.indexOf("\"\"\"", start + 3)
            return if (close < 0) src.length else close + 3
        }
        var i = start + 1
        while (i < src.length) {
            if (src[i] == '\\') {
                i += 2
                continue
            }
            if (src[i] == quote || src[i] == '\n') return i + 1
            i++
        }
        return i
    }

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
