// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百八十八批 · 段 B 的源码门禁（判据⑤⑦＋落点约束的机器钉法）。
// 口径与 675/694 的六体读数同款：**剥掉注释后扫代码**——注释里说"不做什么"不算证据。
//
// 钉的五件事：
//   ① 主判定链唯一引用：brain 包外只许 TriggerWiring 的开关守卫提到它（829 接线升级）。
//   ② 零依赖：包内不许出现 android / 网络 / 数据库 / 日志运行时 / 第三方 SNN 框架（判据⑦）。
//   ③ 零时钟：不许读 currentTimeMillis / nanoTime；随机只许在拓扑采样那一处。
//   ④ 只有一个写盘口：appendText 只许出现在 ShadowRecorder。
//   ⑤ 不新增对外类型：所有顶层声明都带 internal/private（判据④）。
package com.example.zhengdao.baopu.brain

import com.example.zhengdao.GateBodies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BrainSourceAssertTest {

    private val pkgRel = "app/src/main/java/com/example/zhengdao/baopu/brain"
    private val brainRoot = find(pkgRel)

    private val files = brainRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .map { it.relativeTo(brainRoot).invariantSeparatorsPath }
        .sorted()
        .toList()

    /**
     * 路径解析：Gradle 单测的工作目录是模块目录 `app/`，仓根跑测试时是仓根。
     * 两个位置都试（只读，找不到就当场红并列出试过的路径）。
     */
    private fun find(rel: String): File {
        val candidates = listOf(File(rel), File("../$rel"), File("../../$rel"))
        return candidates.firstOrNull { it.exists() }
            ?: error("找不到 $rel（试过：${candidates.joinToString { it.path }}）")
    }

    private fun codeOf(name: String): String = stripComments(find("$pkgRel/$name").readText())

    private fun allCode(): String = files.joinToString("\n") { codeOf(it) }

    // ── ① 主判定链唯一引用（788 时为零引用（未接线）；829 接线后升级为唯一调用点断言） ──

    @Test
    fun `brain包外引用恰好一处且位于TriggerWiring开关守卫处`() {
        val sourceRoot = find("app/src")
        val productionRoots = sourceRoot.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith("test") && !it.name.startsWith("androidTest") }
            .flatMap { sourceSet -> listOf(File(sourceSet, "java"), File(sourceSet, "kotlin")) }
            .filter { it.exists() }
            .sortedBy { it.invariantSeparatorsPath }
            .toList()
        fun relativePath(file: File): String {
            val root = productionRoots.single { file.toPath().startsWith(it.toPath()) }
            return file.relativeTo(root).invariantSeparatorsPath
        }
        val symbols = listOf(
            "BrainShadow", "ShadowFeed", "ZoneShadow", "ShadowNet", "ShadowRound",
            "ShadowRecorder", "MapToTier", "LifNeuron", "LifParams", "Encode",
        )
        val allProductionFiles = productionRoots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension in setOf("kt", "java") }
                .toList()
        }
        val productionFiles = allProductionFiles.filter { file ->
            val relative = relativePath(file)
            relative != "com/example/zhengdao/baopu/brain" &&
                !relative.startsWith("com/example/zhengdao/baopu/brain/")
        }
        val hits = productionFiles
            .filter { file ->
                val code = productionCode(file)
                "baopu.brain" in code || symbols.any { symbol ->
                    Regex("\\b${Regex.escape(symbol)}\\b").containsMatchIn(code)
                }
            }
            .map { file -> relativePath(file) }
            .toList()
        assertEquals(
            "包外引用必须恰好一处，且只在 TriggerWiring：$hits",
            listOf("com/example/zhengdao/baopu/inspect/TriggerWiring.kt"),
            hits,
        )

        val src = find("app/src/main/java/com/example/zhengdao/baopu/inspect/TriggerWiring.kt")
            .readText().replace("\r\n", "\n")
        val code = executableCode(src)
        assertFalse(
            "brain import 不许别名",
            Regex("(?m)^\\s*import\\s+[^\\n]*baopu\\.brain[^\\n]*\\s+as\\s+(?:\\w+|`[^`]+`)").containsMatchIn(code),
        )
        val usageCode = code.lines()
            .filterNot { it.trimStart().startsWith("import ") }
            .joinToString("\n")
        val references = symbols.mapNotNull { symbol ->
            val count = Regex("\\b${Regex.escape(symbol)}\\b").findAll(usageCode).count()
            if (count == 0) null else "$symbol=$count"
        }
        assertEquals("唯一文件里也只许有一个 BrainShadow 生产引用", listOf("BrainShadow=1"), references)

        val call = Regex("BrainShadow\\.onRoundEnd\\s*\\(")
        assertEquals("BrainShadow.onRoundEnd 生产调用必须唯一", 1, call.findAll(code).count())
        val brainImports = allProductionFiles.filter { file ->
            productionCode(file).lines().any { line ->
                line.trimStart().startsWith("import ") && "baopu.brain" in line
            }
        }.map { file -> relativePath(file) }.sorted()
        assertEquals("brain import 只许出现在唯一接线文件", listOf("com/example/zhengdao/baopu/inspect/TriggerWiring.kt"), brainImports)
        val brainShadowCounts = allProductionFiles.mapNotNull { file ->
            val usage = productionCode(file).lines()
                .filterNot { it.trimStart().startsWith("import ") }
                .joinToString("\n")
            val count = Regex("\\bBrainShadow\\b").findAll(usage).count()
            if (count == 0) null else "${relativePath(file)}=$count"
        }.sorted()
        assertEquals(
            "BrainShadow 生产符号只许声明与唯一调用点",
            listOf(
                "com/example/zhengdao/baopu/brain/BrainShadow.kt=1",
                "com/example/zhengdao/baopu/inspect/TriggerWiring.kt=1",
            ),
            brainShadowCounts,
        )
        val settled = blockAt(src, Regex("fun\\s+onRunSettled\\s*\\("))
        val guardStatement = Regex("(?m)^        safe\\(\"脑影\"\\) \\{").find(settled)
        assertTrue("脑影守卫必须位于 onRunSettled 顶层", guardStatement != null)
        val beforeGuard = executableCode(settled.substring(0, guardStatement!!.range.first))
        assertEquals(
            "脑影守卫必须位于 onRunSettled 顶层",
            0,
            braceDepth(beforeGuard),
        )
        assertFalse(
            "脑影守卫必须位于 onRunSettled 顶层",
            Regex("\\b(?:return|throw)\\b").findAll(beforeGuard).any { braceDepth(beforeGuard, it.range.first) == 0 },
        )
        val previousLine = beforeGuard.trimEnd().substringAfterLast('\n').trim()
        assertFalse(
            "脑影守卫必须位于 onRunSettled 顶层",
            Regex("^(?:if|when|for|while|do|else|try|finally|run|synchronized|with)\\b").containsMatchIn(previousLine),
        )
        val guard = blockAt(src, Regex("safe\\(\"脑影\"\\)"))
        val guardCode = executableCode(guard)
        assertEquals("safe(\"脑影\") 内只许一次装配", 1, Regex("ShadowWiring\\.onRunSettled(?:\\s*<[^>]+>)?\\s*\\(").findAll(guardCode).count())
        val arguments = topLevelArguments(callArgsAt(guard, Regex("ShadowWiring\\.onRunSettled(?:\\s*<[^>]+>)?\\s*\\(")))
        val argumentNames = arguments.map { argument ->
            val name = Regex("^\\s*([A-Za-z_][A-Za-z0-9_]*|`[^`]+`)\\s*=").find(argument)
            assertTrue("脑影装配只许注入 enabledProvider 与 run", name != null)
            name!!.groupValues[1].removeSurrounding("`")
        }
        assertEquals("脑影装配只许注入 enabledProvider 与 run", listOf("enabledProvider", "run"), argumentNames)
        assertEquals("开关守卫内只许一次调用", 1, call.findAll(guardCode).count())
        val run = blockAt(guard, Regex("run\\s*=\\s*\\{"))
        val runCode = executableCode(run)
        assertEquals("调用必须位于 run lambda 内", 1, call.findAll(runCode).count())
        assertEquals(
            "写入回调参数必须依次为 root round feeds",
            "root,round,feeds",
            runCode.substringBefore("->").trim().replace(Regex("\\s+"), ""),
        )
        val brainArguments = topLevelArguments(callArgsAt(guard, call))
            .map { argument -> argument.replace(Regex("\\s+"), "") }
        assertEquals(
            "写入回调必须原样转发 root 并返回真实写入数",
            listOf("root", "round", "feeds", "enabled=true"),
            brainArguments,
        )
        assertEquals(
            "写入回调必须原样转发 root 并返回真实写入数",
            "BrainShadow.onRoundEnd(root,round,feeds,enabled=true)",
            runCode.substringAfter("->").trim().replace(Regex("\\s+"), ""),
        )
        assertShadowDoesNotCount(src, settled)
        assertEquals(
            "脑影守卫必须位于 onRunSettled 顶层",
            "safe(\"\"){BioReadoutWiring.onRunSettled(ctx,sessionId)}",
            previousLine.replace(Regex("\\s+"), ""),
        )
        assertFalse("脑影守卫内不许吞异常", Regex("\\b(?:runCatching\\s*\\{|try\\s*\\{|catch\\b)").containsMatchIn(guardCode))
        assertTrue(
            "脑影装配必须位于开关守卫顶层",
            Regex("^\\s*ShadowWiring\\.onRunSettled(?:\\s*<[^>]+>)?\\s*\\(")
                .containsMatchIn(guardCode),
        )
        assertFalse("本单不解析账根，生产不许注入 root provider", Regex("(?:\\broot\\b|`root`)\\s*=").containsMatchIn(guardCode))
        assertFalse("本单不解析账根路径", "MemoryLayout.globalMemoryDir" in guardCode)
        assertTrue(
            "写入回调必须显式命名，避免尾随闭包误绑定 log",
            Regex("run\\s*=\\s*\\{").containsMatchIn(guardCode),
        )
        val provider = blockAt(guard, Regex("enabledProvider\\s*=\\s*\\{"))
        val providerSource = ExecutableSource(provider)
        val providerCode = providerSource.scan()
        assertEquals(
            "开关 provider 必须直接返回默认关的偏好值，异常交给外层 safe",
            "ctx.getSharedPreferences(\"\",Context.MODE_PRIVATE).getBoolean(\"\",false)",
            providerCode.replace(Regex("\\s+"), ""),
        )
        val getBooleanCalls = Regex("\\bgetBoolean\\s*\\(").findAll(providerCode).toList()
        assertEquals("开关只许一个真实 getBoolean 调用", 1, getBooleanCalls.size)
        val falseCall = Regex("\\bgetBoolean\\s*\\(\\s*\"\",\\s*false\\s*\\)").find(providerCode)
        assertTrue("开关必须默认关", falseCall != null)
        val argumentIndex = Regex("\"\"").findAll(providerCode.substring(0, falseCall!!.range.first)).count()
        assertEquals("开关 key 必须为 brain_shadow", "brain_shadow", providerSource.stringValues.getOrNull(argumentIndex))
    }

    // ── ② 零依赖（判据⑦）──

    @Test
    fun `包内没有android网络数据库与框架`() {
        val code = allCode()
        val forbidden = listOf(
            "android.", "Context", "Provider", "Http", "OkHttp", "Retrofit", "java.net",
            "BaopuDb", "run_log", "RunLog", "execSQL", "JSONObject", "JSONArray",
            "torch", "Tensor", "onnx", "brian", "nd4j", "smile", "snn4j",
        )
        for (token in forbidden) {
            assertFalse("brain 包（剥注释后）不得出现 $token", token in code)
        }
    }

    @Test
    fun `import只有标准库`() {
        for (name in files) {
            val imports = codeOf(name).lines()
                .filter { it.trimStart().startsWith("import ") }
                .map { it.trim().removePrefix("import ").trim() }
            for (imp in imports) {
                assertTrue(
                    "$name 只许 import kotlin./java.，实际有 $imp",
                    imp.startsWith("kotlin.") || imp.startsWith("java."),
                )
            }
        }
    }

    @Test
    fun `构建脚本没有引入任何SNN框架`() {
        val gradle = find("app/build.gradle.kts").readText().lowercase()
        for (token in listOf("torch", "onnx", "djl", "nd4j", "brian", "snn")) {
            assertFalse("构建脚本不许出现 $token", token in gradle)
        }
    }

    // ── ③ 零时钟、随机只在拓扑一处 ──

    @Test
    fun `不读时钟`() {
        val code = allCode()
        for (token in listOf("currentTimeMillis", "nanoTime", "LocalDateTime", "SystemClock")) {
            assertFalse("不许读时钟（影子的时间是自己的 5 ms 步进）：$token", token in code)
        }
    }

    @Test
    fun `Random只许出现在拓扑采样那一处`() {
        for (name in files) {
            val uses = "Random(" in codeOf(name)
            if (name == "ShadowNet.kt") {
                assertTrue("拓扑采样必须用 seed 建 Random", uses)
            } else {
                assertFalse("$name 不许自己造随机源", uses)
            }
        }
    }

    // ── ④ 只有一个写盘口 ──

    @Test
    fun `写盘入口只有ShadowRecorder`() {
        for (name in files) {
            if (name == "ShadowRecorder.kt") continue
            for (token in listOf("appendText", "writeText", "writeBytes", "createNewFile")) {
                assertFalse("$name 不许写盘（$token），唯一写口是 ShadowRecorder", token in codeOf(name))
            }
        }
        assertTrue("ShadowRecorder 必须有写口", "appendText" in codeOf("ShadowRecorder.kt"))
    }

    // ── ⑤ 不新增对外类型（判据④）──

    @Test
    fun `所有顶层声明都是internal或private`() {
        val exported = Regex("^(?:class|data class|enum class|interface|object|fun|val|const val)\\s")
        for (name in files) {
            for ((index, line) in codeOf(name).lines().withIndex()) {
                if (line.startsWith(" ") || line.startsWith("}")) continue
                assertFalse(
                    "$name:${index + 1} 顶层声明必须带 internal/private：$line",
                    exported.containsMatchIn(line),
                )
            }
        }
    }

    // ── 行数与常量真源 ──

    @Test
    fun `源码不超行数预算`() {
        for (name in files) {
            val lines = find("$pkgRel/$name").readLines().size
            assertTrue("$name 共 $lines 行，超 240 行预算", lines <= 240)
        }
    }

    @Test
    fun `定稿的硬数字一字不差`() {
        assertEquals("步长 5 ms", 5.0, LifParams.DT_MS, 0.0)
        assertEquals("每轮上限 1200 步", 1200, LifParams.MAX_STEPS)
        assertEquals("每物类 64 个", 64, LifParams.N_TOTAL)
        assertEquals("48 兴奋", 48, LifParams.N_EXC)
        assertEquals("16 抑制", 16, LifParams.N_INH)
        assertEquals("独立 seed 0x5eed", 0x5eedL, LifParams.SEED)
        assertEquals("窗口 100 ms", 100.0, LifParams.WINDOW_MS, 0.0)
        assertEquals("参数来源标记", "engineering", LifParams.SOURCE)
    }

    private fun assertShadowDoesNotCount(src: String, settled: String) {
        val marker = "// 第八百二十九批："
        assertTrue("829 接线段必须有批次锚点", marker in src)
        assertTrue("注释必须说明影子是旁路，不参与产出计数", "影子是旁路，不参与产出计数" in src)
        val guard = blockAt(src, Regex("safe\\(\"脑影\"\\)"))
        val markerIndex = src.indexOf(marker)
        val guardIndex = src.indexOf(guard)
        assertTrue("批次锚点与守卫都必须位于源码中", markerIndex >= 0 && guardIndex >= 0)
        val shadowCode = executableCode(src.substring(minOf(markerIndex, guardIndex)))
        val ranWrite = Regex(
            "(?:\\+\\+\\s*ran\\b|--\\s*ran\\b|\\bran\\s*(?:\\+\\+|--|[+*/%\\-]?=|<<=|>>=|>>>=|&=|\\|=|\\^=)(?!=))",
        )
        assertFalse("829 接线区不许写 ran", ranWrite.containsMatchIn(shadowCode))
        val settledCode = executableCode(settled)
        assertEquals(
            "onRunSettled 的 ran 只许初始零、五处原增量与原样返回",
            7,
            Regex("\\bran\\b").findAll(settledCode).count(),
        )
        assertTrue(
            "onRunSettled 的 ran 只许初始零、五处原增量与原样返回",
            Regex("\\bvar\\s+ran\\s*=\\s*0\\b").containsMatchIn(settledCode),
        )
        assertEquals(
            "onRunSettled 的 ran 只许初始零、五处原增量与原样返回",
            List(5) { "ran++" },
            Regex("\\bran\\s*\\+\\+").findAll(settledCode)
                .map { it.value.replace(Regex("\\s+"), "") }
                .toList(),
        )
        assertEquals(
            "onRunSettled 的五处 ran 增量必须保持原条件与原语句",
            listOf(
                "if(safe(\"\"){NocturnalAuto.runDue(ctx,nowMs,AskSource.EVENT)}!=null)ran++",
                "if(safe(\"\"){PatrolAuto.runDue(ctx,nowMs,AskSource.EVENT)}!=null)ran++",
                "if(safe(\"\"){subconsciousOnRunSettled(ctx,nowMs)}!=null)ran++",
                "if(safe(\"\"){insulaOnRunSettled(ctx,nowMs)}!=null)ran++",
                "if(safe(\"\"){habitOnRunSettled(ctx,nowMs)}!=null)ran++",
            ),
            settledCode.lines()
                .filter { line -> Regex("\\bran\\s*\\+\\+").containsMatchIn(line) }
                .map { line -> line.trim().replace(Regex("\\s+"), "") }
                .toList(),
        )
        val returns = Regex("\\breturn\\b").findAll(settledCode).toList()
        assertEquals("829 接线段必须原样返回 ran", 1, returns.size)
        assertTrue(
            "829 接线段必须原样返回 ran",
            Regex("\\breturn\\s+ran\\s*$")
                .containsMatchIn(settledCode.substring(returns.single().range.first).trim()),
        )
    }

    /** 剥注释与普通字面量，但保留字符串模板里的可执行表达式。 */
    private fun executableCode(src: String): String = ExecutableSource(src).scan()

    private fun productionCode(file: File): String {
        val source = file.readText()
        val translated = if (file.extension == "java") {
            Regex("\\\\u+([0-9a-fA-F]{4})").replace(source) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
        } else {
            source
        }
        return executableCode(translated)
    }

    private class ExecutableSource(
        private val src: String,
        private val preserveLiterals: Boolean = false,
        private val maskLiterals: Boolean = false,
    ) {
        val stringValues = mutableListOf<String>()
        private val out = StringBuilder(src.length)
        private var index = 0

        fun scan(): String {
            code(0)
            return out.toString()
        }

        private fun code(templateDepth: Int) {
            var depth = templateDepth
            val stopAtZero = templateDepth > 0
            while (index < src.length) {
                val c = src[index]
                val next = src.getOrNull(index + 1) ?: ' '
                when {
                    c == '/' && next == '*' -> blockComment()
                    c == '/' && next == '/' -> lineComment()
                    src.startsWith("\"\"\"", index) -> {
                        val slot = stringValueSlot()
                        if (preserveLiterals || maskLiterals) out.append("\"\"\"") else out.append("\"\"")
                        index += 3
                        string(raw = true, slot = slot, contentStart = index)
                    }
                    c == '"' -> {
                        val slot = stringValueSlot()
                        if (preserveLiterals || maskLiterals) out.append('"') else out.append("\"\"")
                        index++
                        string(raw = false, slot = slot, contentStart = index)
                    }
                    c == '\'' -> {
                        if (preserveLiterals || maskLiterals) out.append('\'') else out.append("''")
                        index++
                        charLiteral()
                    }
                    c == '{' -> { depth++; out.append(c); index++ }
                    c == '}' && depth > 0 -> {
                        depth--
                        out.append(c)
                        index++
                        if (stopAtZero && depth == 0) return
                    }
                    else -> { out.append(c); index++ }
                }
            }
        }

        private fun stringValueSlot(): Int {
            stringValues.add("")
            return stringValues.lastIndex
        }

        private fun string(raw: Boolean, slot: Int, contentStart: Int) {
            while (index < src.length) {
                when {
                    raw && src.startsWith("\"\"\"", index) -> {
                        stringValues[slot] = src.substring(contentStart, index)
                        if (preserveLiterals || maskLiterals) out.append("\"\"\"")
                        index += 3
                        return
                    }
                    !raw && src[index] == '\\' -> {
                        if (preserveLiterals) out.append(src, index, minOf(index + 2, src.length))
                        if (maskLiterals) out.append("  ")
                        index += 2
                    }
                    !raw && src[index] == '"' -> {
                        stringValues[slot] = src.substring(contentStart, index)
                        if (preserveLiterals || maskLiterals) out.append('"')
                        index++
                        return
                    }
                    src.startsWith("\${", index) -> {
                        out.append("\${")
                        index += 2
                        code(1)
                    }
                    src[index] == '$' && src.getOrNull(index + 1) == '`' -> {
                        out.append('$')
                        index++
                        while (index < src.length) {
                            val c = src[index]
                            out.append(c)
                            index++
                            if (c == '`') break
                        }
                    }
                    src[index] == '$' && isTemplateIdentifierStart(src.getOrNull(index + 1)) -> {
                        out.append('$')
                        index++
                        out.append(src[index])
                        index++
                        while (index < src.length && isTemplateIdentifierPart(src[index])) {
                            out.append(src[index])
                            index++
                        }
                    }
                    src[index] == '\n' -> { out.append('\n'); index++; if (!raw) return }
                    else -> {
                        if (preserveLiterals) out.append(src[index])
                        if (maskLiterals) out.append(' ')
                        index++
                    }
                }
            }
        }

        private fun isTemplateIdentifierStart(c: Char?): Boolean = c != null && (c == '_' || c.isLetter())

        private fun isTemplateIdentifierPart(c: Char): Boolean = c == '_' || c.isLetterOrDigit()

        private fun charLiteral() {
            while (index < src.length) {
                when {
                    src[index] == '\\' -> {
                        if (preserveLiterals) out.append(src, index, minOf(index + 2, src.length))
                        if (maskLiterals) out.append("  ")
                        index += 2
                    }
                    src[index] == '\'' -> {
                        if (preserveLiterals || maskLiterals) out.append('\'')
                        index++
                        return
                    }
                    src[index] == '\n' -> { out.append('\n'); index++; return }
                    else -> {
                        if (preserveLiterals) out.append(src[index])
                        if (maskLiterals) out.append(' ')
                        index++
                    }
                }
            }
        }

        private fun blockComment() {
            var depth = 1
            index += 2
            if (preserveLiterals || maskLiterals) out.append("  ")
            while (index < src.length && depth > 0) {
                when {
                    src.startsWith("/*", index) -> {
                        depth++
                        if (preserveLiterals || maskLiterals) out.append("  ")
                        index += 2
                    }
                    src.startsWith("*/", index) -> {
                        depth--
                        if (preserveLiterals || maskLiterals) out.append("  ")
                        index += 2
                    }
                    else -> {
                        if (preserveLiterals || maskLiterals) out.append(if (src[index] == '\n') '\n' else ' ')
                        if (!preserveLiterals && !maskLiterals && src[index] == '\n') out.append('\n')
                        index++
                    }
                }
            }
        }

        private fun lineComment() {
            index += 2
            if (preserveLiterals || maskLiterals) out.append("  ")
            while (index < src.length && src[index] != '\n') {
                if (preserveLiterals || maskLiterals) out.append(' ')
                index++
            }
            if (index < src.length) { out.append('\n'); index++ }
        }
    }

    private fun braceDepth(code: String, before: Int = code.length): Int =
        code.substring(0, before).count { it == '{' } - code.substring(0, before).count { it == '}' }

    private fun callArgsAt(src: String, anchor: Regex): String {
        val visible = stripComments(src)
        val match = anchor.find(visible) ?: error("找不到调用锚点：${anchor.pattern}")
        val open = match.range.last
        require(visible[open] == '(') { "调用锚点必须以左括号收尾：${anchor.pattern}" }
        val end = GateBodies.balancedEnd(maskedSource(visible), open)
        return src.substring(open + 1, end - 1)
    }

    private fun topLevelArguments(args: String): List<String> {
        val code = maskedSource(args)
        val out = mutableListOf<String>()
        var depth = 0
        var start = 0
        for ((index, c) in code.withIndex()) {
            when (c) {
                '(', '{', '[' -> depth++
                ')', '}', ']' -> depth--
                ',' -> if (depth == 0) {
                    out += args.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        require(depth == 0) { "调用实参括号没配上" }
        out += args.substring(start).trim()
        return out.filter { it.isNotEmpty() }
    }

    /** 锚点在字符串感知的剥注释源码里定位，再按字符列精确切出块体。 */
    private fun blockAt(src: String, anchor: Regex): String {
        val visible = stripComments(src)
        val match = anchor.find(visible) ?: error("找不到源码锚点：${anchor.pattern}")
        val masked = maskedSource(visible)
        val anchorOpen = match.value.lastIndexOf('{')
        val open = if (anchorOpen >= 0) {
            match.range.first + anchorOpen
        } else {
            masked.indexOf('{', match.range.last + 1)
        }
        require(open >= 0) { "源码锚点后没有块体：${anchor.pattern}" }
        val end = GateBodies.balancedEnd(masked, open)
        return src.substring(open + 1, end - 1)
    }

    /** 剥注释（行注释＋可嵌套块注释）；字符串与模板原样保留。 */
    private fun stripComments(src: String): String = ExecutableSource(src, preserveLiterals = true).scan()

    /** 剥注释并屏蔽普通字面量，保留模板表达式与原始字符列。 */
    private fun maskedSource(src: String): String = ExecutableSource(src, maskLiterals = true).scan()
}
