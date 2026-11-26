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
    private val visibilityKeywords = setOf("internal", "private")
    private val modifierKeywords =
        "(?:public|private|internal|abstract|open|inner|final|data|sealed|enum|value|annotation)\\s+"
    private val typeKeywords = "(?:class|interface|object|typealias)"

    // Java 的 \b 与 \w 只认 ASCII，`data class 情绪模型` 这种名字用 \b 收尾根本不构成边界；
    // 一律改成显式的 Unicode 前后视，才能让 Kotlin 与镜像脚本对中文类型名给出同一个答案。
    private val nameStartGuard = "(?<![\\p{L}\\p{N}_])"
    private val nameEndGuard = "(?![\\p{L}\\p{N}_])"
    private val DERIVER_DECLARATION =
        Regex("$nameStartGuard(?:$modifierKeywords)*$typeKeywords\\s+`?([\\p{L}\\p{N}_]+)`?")
    private val verbAtWordStart = Regex("(?:^|[^A-Za-z])(?:derive|infer|predict)", RegexOption.IGNORE_CASE)
    private val verbInCompound = Regex("[a-z](?:Derive|Infer|Predict)(?!r)")
    private val verbInChinese = Regex("推导|推断|预判|反推")
    private val tagProperty = Regex(
        "(?:val|var)\\s+(?:[\\p{L}\\p{N}_]+\\s*\\.\\s*)?([\\p{L}\\p{N}_]*[Tt]ags[\\p{L}\\p{N}_]*)$nameEndGuard"
    )
    private val importLine = Regex("(?m)^[ \\t]*import[ \\t]+\\S")
    private val topLevelTypeName = Regex(
        "(?m)^[ \\t]*(?:@\\w+[ \\t]*)?(?:$modifierKeywords)*$typeKeywords[ \\t]+(?:`([^`\\n]+)`|([\\p{L}\\p{N}_]+))"
    )
    private val scannedModules = listOf("app", "core", "native-core", "policy", "macrobenchmark")

    /**
     * 人格层类型名在人格包之外允许的既有重名数，实测于 tip f0bc321ff。
     * 只钉"不得再多出第二份"，别人删掉一份不该让 930 变红。
     */
    private val nameCollisionBaseline = mapOf(
        "CheckItem" to 1, "CheckReport" to 1, "Persona" to 1, "PersonaLayer" to 1,
        "Severity" to 1, "SwapResult" to 1, "SurfaceState" to 1,
    )
    private val bannedIoTokens = listOf(
        "Random", "currentTimeMillis", "nanoTime", "java.io", "readText", "writeText", "randomUUID",
        "Math.random", "java.time", "Instant", "Clock", "Files.", "Paths.", "System.currentTimeMillis",
        "ThreadLocalRandom", "Random(", "readBytes", "File(", "InputStream", "SharedPreferences",
        "println", "state.anchor", "state.relation", "intake.peer", "personaRate",
    )

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
        assertEquals(
            "SurfaceState 出现次数必须与钉住的两个文件一一对应，钉住的文件里再加第二份也红",
            2,
            occurrences(typePattern("SurfaceState"), sources.values),
        )
        assertEquals(
            "扫描模块集合必须与 settings.gradle.kts 的 include 同步，新增模块要同时改本闸",
            settingsDeclaredModules().sorted(),
            scannedModules.sorted(),
        )
        assertEquals(
            "仓库里带 src/main 源码的顶层目录必须全部在册，漏扫一个模块等于给平行模型开门",
            moduleRootsWithSources(),
            scannedModules.sorted(),
        )
        listOf(
            "include(\":app\", \":core\")" to setOf("app", "core"),
            "include(\n    \":native-core\",\n)" to setOf("native-core"),
            "  include(\":features:chat\")" to setOf("features"),
            "include(\":policy\")" to setOf("policy"),
        ).forEach { (form, want) ->
            assertEquals("include 写法必须解析得出：$form", want, parseIncludes(form))
        }
        scannedModules.forEach { module ->
            assertTrue("$module 必须真的扫到生产源码，不许清单在而扫描空", sources.keys.any { it.startsWith("$module/src/") })
        }
        assertFalse("人格层不得寄生在任何 personality/personalities 目录", sources.keys.any { it.contains("/state/personalit") })
        assertFalse(sources.values.any { packageOf(it) == "com.example.zhengdao.baopu.state.personality" })
    }

    @Test
    fun `人格包九件零import`() {
        val scoped = personaScopeSources()
        assertTrue("必须扫描到人格包生产源码", scoped.isNotEmpty())
        assertEquals(
            "人格包九件一旦允许 import，就能拼出第二套人格模型；今天全是零 import，故钉死",
            emptyList<String>(),
            scoped.filterValues { importLine.containsMatchIn(it) }.keys.sorted(),
        )
        assertTrue("import 夹具必须被看见", importLine.containsMatchIn("package z\n\nimport com.other.Model"))
        assertFalse("行中的 import 字样不得误判成导入语句", importLine.containsMatchIn("package z\n\nval s = \"import x\""))
    }

    @Test
    fun `人格层类型名在包外不得多出第二份`() {
        val sources = productionSources()
        val scoped = personaScopeSources()
        val names = personaTypeNames(scoped)
        assertTrue(
            "类型名采集必须看得见 internal 前缀的声明（实测 55 个，旧的 34 是漏采），否则重名闸形同虚设：实际 ${names.size} 个",
            names.size >= 40,
        )
        listOf("Persona", "PersonaLayer", "CoreLayer", "TraitLayer", "SurfaceState", "PersonaValues",
            "PersonaMoodState", "SurfaceDeriver", "ValueAxis", "TraitChanges", "PersonaEnhancement",
            "PersonaLanguage").forEach { name ->
            assertTrue("$name 必须被类型名采集看见", name in names)
        }
        assertTrue("重名基线里的名字必须真的属于人格层", names.containsAll(nameCollisionBaseline.keys))
        names.forEach { name ->
            val outside = declarationsOutsideScope(name, scoped, sources)
            assertTrue(
                "$name 在人格包之外声明了 $outside 份，基线 ${nameCollisionBaseline[name] ?: 0}；" +
                    "再造一套同名模型要先过这一关，真要放宽必须显式改本闸基线",
                outside <= (nameCollisionBaseline[name] ?: 0),
            )
        }
        val psyche = "package com.example.zhengdao.baopu.state.psyche\n\ndata class Persona(val core: Int)\n"
        assertEquals(
            "换个包名再抄一份 Persona，必须被这条重名规则抓住",
            1,
            occurrences(typePattern("Persona"), listOf(psyche)),
        )
        assertEquals(
            "Unicode 前后视必须认得中文类型名，Java 的 \\b 在这里根本不构成边界",
            1,
            occurrences(typePattern("情绪模型"), listOf("internal data class 情绪模型(val a: Int)")),
        )
        assertEquals("反引号中文类型名必须被采集", listOf("情绪Deriver"), declaredNames("internal object `情绪Deriver` {"))
        assertEquals("普通类型名照旧被采集", listOf("SurfaceDeriver"), declaredNames("internal object SurfaceDeriver {"))
        assertEquals(
            "typealias 形式的第二套模型也必须被重名闸数到",
            1,
            occurrences(typePattern("CoreLayer"), listOf("package x\n\ntypealias CoreLayer = Other")),
        )
    }

    private fun declaredNames(decl: String): List<String> =
        topLevelTypeName.findAll(decl).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()

    private fun personaTypeNames(scoped: Map<String, String>): List<String> =
        scoped.values.flatMap { text -> declaredNames(topLevelOnly(text)) }.distinct().sorted()

    @Test
    fun `人格包文件集合封闭且930新层只许internal`() {
        val expected = (base819Files + newFiles).map { dir + it }.sorted()
        val scoped = personaScopeSources()
        assertEquals(
            "人格包按目录与按包名两个口径的并集必须封闭在已知九件内（躲进本目录换包名、躲在本包名换目录都红）",
            expected,
            scoped.keys.sorted(),
        )
        assertEquals(
            "按包名与按目录两个口径今天必须重合，否则说明有人把人格层文件搬出目录却留着包名",
            scoped.keys.sorted(),
            productionSources().filterValues { packageOf(it) == personaPackage }.keys.sorted(),
        )
        val layers = internalLayerSources()
        assertEquals(newFiles.map { dir + it }.sorted(), layers.keys.sorted())
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
        listOf(
            "class Twin internal constructor(private val a: Int)",
            "data class PublicTwin private constructor(val a: Int)",
            "@Deprecated(\"d\") object PublicTwin3",
            "@Deprecated(\"d\") fun leakedToo() = Unit",
            "interface TwinInterface",
            "object TwinObject",
        ).forEach { assertTrue("构造可见性与同行注解不得成为逃逸口：$it", publicTopLevelDeclarations(it).isNotEmpty()) }
        listOf(
            "internal val keep = 0; object PublicLeak { fun x() = 1 }",
            "private val seed = 0; data class Leak(val a: Int)",
            "internal val u = '('; class QuoteLeak",
            "import kotlin.text.Regex; class SmuggledThing",
            "internal val s = \"a(\"; class AfterParenLeak",
            "internal val a = 1; internal val b = 2; fun leakedToo() = Unit",
        ).forEach { assertTrue("同一行的分号夹带不得放过：$it", publicTopLevelDeclarations(it).isNotEmpty()) }
        listOf("internal val x = 1", "private fun h() = Unit", "internal val a = 1; internal val b = 2")
            .forEach { assertEquals("分号切分不得把 internal 误判成公开：$it", emptyList<String>(), publicTopLevelDeclarations(it)) }
        assertEquals(
            "类型闸必须认得 interface 形式的同名双胞胎",
            1,
            occurrences(typePattern("CoreLayer"), listOf("interface CoreLayer { val a = 1 }")),
        )
        assertEquals(
            "类型闸必须认得 object 形式的同名双胞胎",
            1,
            occurrences(typePattern("TraitLayer"), listOf("sealed object TraitLayer")),
        )
        assertEquals(
            "同一份文本里的两处声明必须计两次",
            2,
            occurrences(Regex("\\bval\\s+tags\\s*:"), listOf("val tags: List<String> = emptyList()\nval tags: Set<String> = emptySet()")),
        )
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
    fun `SurfaceDeriver唯一且人格包除它以外无推导动词`() {
        val sources = productionSources()
        assertEquals(
            "全仓 SurfaceDeriver 必须恰好一处（含 typealias 与反引号写法）",
            1,
            occurrences(typePattern("SurfaceDeriver"), sources.values),
        )
        val persona = personaScopeSources()
        assertEquals(
            "人格包除 SurfaceDeriver.kt 外不得再声明任何名字含 Deriver 的类型",
            emptyList<String>(),
            persona.filterKeys { it != dir + "SurfaceDeriver.kt" }
                .filterValues { deriverNames(it).isNotEmpty() }.keys.sorted(),
        )
        assertEquals(
            "SurfaceDeriver.kt 里只能有 SurfaceDeriver 这一个 Deriver 名",
            listOf("SurfaceDeriver"),
            deriverNames(persona.getValue(dir + "SurfaceDeriver.kt")),
        )
        listOf(
            "internal object MoodDeriverV2",
            "internal object SurfaceDeriver2",
            "internal object `SurfaceDeriver`",
            "internal object 情绪Deriver",
            "internal class TraitDeriver",
            "internal interface MoodDeriver",
            "internal typealias MoodDeriverV2 = SurfaceDeriver",
            "public data class ExpressionDeriverImpl(val a: Int)",
        ).forEach { assertTrue("Deriver 闸必须认得它：$it", deriverNames(it).isNotEmpty()) }
        listOf("data class TraitLayer(val a: Int)", "internal val deriverCount = 1")
            .forEach { assertEquals("不相干声明不得被 Deriver 闸误伤：$it", emptyList<String>(), deriverNames(it)) }
        assertEquals(
            "推导动词的禁令范围是整个人格包，不只是五件新层",
            emptyList<String>(),
            persona.filterKeys { it != dir + "SurfaceDeriver.kt" }
                .filterValues { hasDerivationVerb(it) }.keys.sorted(),
        )
        listOf(
            "internal fun _deriveFromJob() = 1",
            "internal fun deriveSurface2() = 1",
            "internal fun inferV2() = 1",
            "internal fun predictScore2() = 1",
            "internal val 价值推导 = 1",
            "internal fun doDerive() = 1",
            "internal val 反推说明 = \"\"",
        ).forEach { assertTrue("推导动词的各种形状都必须被看见：$it", hasDerivationVerb(it)) }
        listOf(
            "internal fun renderSurface() = 1",
            "internal object SurfaceDeriver",
            "internal val derivationNote = 1",
        ).forEach { assertFalse("名词与既有派生层不得误伤：$it", hasDerivationVerb(it)) }
    }

    @Test
    fun `语言表静态且标签字段只落SurfaceState`() {
        val language = code("PersonaLanguage.kt")
        listOf("synonym", "vector", "embedding", "semantic", "同义", "向量").forEach {
            assertFalse("语言闸不得运行时联想 $it", language.contains(it))
        }
        val persona = personaScopeSources()
        assertTrue("必须扫描到人格包生产源码", persona.isNotEmpty())
        assertEquals(
            "标签字段在人格包内必须恰好一处，且名字必须是 tags",
            listOf("tags"),
            persona.values.flatMap { text -> tagProperty.findAll(text).map { it.groupValues[1] } }.toList(),
        )
        listOf(
            "internal var tags: List<String> = emptyList()",
            "internal val tags = listOf(1)",
            "internal val SurfaceState.tags2: List<String> get() = emptyList()",
            "internal val labelTags: Set<String> = emptySet()",
        ).forEach { assertTrue("标签字段的各种写法都必须被看见：$it", tagProperty.containsMatchIn(it)) }
        assertTrue(code("PersonaModel.kt").contains("data class SurfaceState"))
    }

    @Test
    fun `五个新文件零IO随机时钟且不碰747710921`() {
        val layers = internalLayerSources()
        assertEquals(newFiles.toSet(), layers.keys.map { it.substringAfterLast('/') }.toSet())
        val text = layers.values.joinToString("\n")
        bannedIoTokens.forEach { assertFalse("新层不得含 $it", text.contains(it)) }
        listOf(
            "import java.util.UUID\nval id = UUID.randomUUID()",
            "val r = Math.random()",
            "val t = java.time.Clock.system()",
            "val l = java.nio.file.Files.readAllLines(p)",
            "val n = Instant.now()",
            "val f = File(\"x\").readBytes()",
            "val p = SharedPreferences.getDefault(ctx)",
        ).forEach { token ->
            assertTrue("IO 字面量的各种写法都必须被看见：$token", bannedIoTokens.any { token.contains(it) })
        }
        val persona = personaScopeSources()
        listOf("PersonaValueChecks", "PersonaStateChecks", "TraitContradictionChecks", "PersonaLanguage")
            .forEach { name ->
                assertTrue(
                    "$name 必须被人格包内另一件调用，不许写了没人用的孤儿聚合",
                    persona.values.count { Regex("(?<![\\p{L}\\p{N}_])$name(?![\\p{L}\\p{N}_])").containsMatchIn(it) } >= 2,
                )
            }
        assertTrue("930 聚合必须串起 819 总检", code("PersonaEnhancement.kt").contains("PersonaChecks.check("))
        assertTrue("819 总检必须真的走到 930 语言闸", code("PersonaChecks.kt").contains("PersonaLanguage."))
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

    private fun internalLayerSources(): Map<String, String> = personaScopeSources()
        .filterKeys { it.substringAfterLast('/') !in base819Files }

    /** 按目录 **或** 按包名，两个口径取并集：搬家与换包名两条逃避路一起堵。 */
    private fun personaScopeSources(): Map<String, String> =
        productionSources().filter { (path, text) -> path.startsWith(dir) || packageOf(text) == personaPackage }

    private fun occurrences(pattern: Regex, texts: Iterable<String>): Int =
        texts.sumOf { pattern.findAll(it).count() }

    private fun typePattern(name: String): Regex = Regex(declaredAs(name))

    /** 名字用反引号括起来时收尾不存在 \\w 边界，所以反引号写法必须单独成一支。 */
    private fun declaredAs(name: String): String =
        "$nameStartGuard(?:$modifierKeywords)*$typeKeywords\\s+(?:`$name`|$name$nameEndGuard)"

    private fun deriverNames(text: String): List<String> =
        DERIVER_DECLARATION.findAll(text).map { it.groupValues[1] }
            .filter { "deriver" in it.lowercase() }.toList()

    private fun hasDerivationVerb(text: String): Boolean =
        verbAtWordStart.containsMatchIn(text) || verbInCompound.containsMatchIn(text) ||
            verbInChinese.containsMatchIn(text)

    /** 人格层声明的每一个类型名，在全仓（含人格包之外）的声明次数减去包内次数。 */
    private fun declarationsOutsideScope(name: String, scoped: Map<String, String>, all: Map<String, String>): Int =
        occurrences(typePattern(name), all.values) - occurrences(typePattern(name), scoped.values)

    /** 一条 include 可以带多个参数、可以换行、可以是 `:features:chat` 这种子模块；解析口径必须与目录扫描一致。 */
    private fun settingsDeclaredModules(): Set<String> =
        parseIncludes(File(repositoryRoot(), "settings.gradle.kts").readText())

    private fun parseIncludes(text: String): Set<String> =
        Regex("include\\s*\\(([^)]*)\\)").findAll(text)
            .flatMap { Regex("\"([^\"]*)\"|'([^']*)'").findAll(it.groupValues[1]) }
            .map { (it.groupValues[1].ifEmpty { it.groupValues[2] }).trim(':').split(":").first() }
            .toSet()

    private fun moduleRootsWithSources(): List<String> =
        repositoryRoot().listFiles().orEmpty().filter { module ->
            module.isDirectory && (File(module, "src/main/java").isDirectory || File(module, "src/main/kotlin").isDirectory)
        }.map { it.name }.sorted()

    private fun packageOf(code: String): String? =
        Regex("(?m)^[ \\t]*package[ \\t]+([A-Za-z0-9_.]+)[ \\t]*;?[ \\t]*$").find(code)?.groupValues?.get(1)

    /** 一行可以用分号塞进多条顶层声明：`import x; class Y` 不能因为行首是 import 就整行放过。 */
    private fun publicTopLevelDeclarations(code: String): List<String> =
        topLevelOnly(code).lines().flatMap { raw -> raw.split(";").map { it.trim() } }.filter { line ->
            val words = declarationWords(line)
            line.isNotEmpty() && !line.startsWith("package ") && !line.startsWith("import ") &&
                words.indexOfFirst { it in declarationKeywords }.let { idx ->
                    idx >= 0 && words.take(idx).none { it in visibilityKeywords }
                }
        }

    /** 括号里的东西（注解实参、构造参数、调用实参）不属于声明头，但括号**后面**的关键字必须还在。 */
    private fun declarationWords(line: String): List<String> {
        var text = line
        while (true) {
            val stripped = text.replace(Regex("\\([^()]*\\)"), " ")
            if (stripped == text) break
            text = stripped
        }
        return text.split(Regex("[\\s<>=:.,?{}]+")).filter { it.isNotEmpty() }
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
        return scannedModules.flatMap { module ->
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
