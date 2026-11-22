// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百八十八批 · 段 B 的源码门禁（判据⑤⑦＋落点约束的机器钉法）。
// 口径与 675/694 的六体读数同款：**剥掉注释后扫代码**——注释里说"不做什么"不算证据。
//
// 钉的五件事：
//   ① 主判定链零引用：brain 包外一个文件都不许提到它（判据⑤，grep 的机器版）。
//   ② 零依赖：包内不许出现 android / 网络 / 数据库 / 日志运行时 / 第三方 SNN 框架（判据⑦）。
//   ③ 零时钟：不许读 currentTimeMillis / nanoTime；随机只许在拓扑采样那一处。
//   ④ 只有一个写盘口：appendText 只许出现在 ShadowRecorder。
//   ⑤ 不新增对外类型：所有顶层声明都带 internal/private（判据④）。
package com.example.zhengdao.baopu.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BrainSourceAssertTest {

    private val pkgRel = "app/src/main/java/com/example/zhengdao/baopu/brain"

    private val files = listOf(
        "LifNeuron.kt", "ShadowNet.kt", "Encode.kt",
        "MapToTier.kt", "ShadowRecorder.kt", "BrainShadow.kt",
    )

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

    // ── ① 主判定链零引用（判据⑤）──

    @Test
    fun `brain包外没有任何文件引用它`() {
        val root = find("app/src/main/java/com/example/zhengdao")
        val names = listOf("baopu.brain", "BrainShadow", "ShadowNet", "ShadowRecorder", "MapToTier", "LifNeuron")
        val hits = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { !it.path.replace('\\', '/').contains("/baopu/brain/") }
            .filter { file -> names.any { it in file.readText() } }
            .map { it.name }
            .toList()
        assertTrue("主判定链不许引用影子（判据⑤），命中：$hits", hits.isEmpty())
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

    /** 剥注释（行注释＋可嵌套块注释整段换空格）；字符串原样保留。 */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
            when (two) {
                "//" -> {
                    out.append("  ")
                    i += 2
                    while (i < src.length && src[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                }
                "/*" -> {
                    out.append("  ")
                    i += 2
                    var depth = 1
                    while (i < src.length && depth > 0) {
                        val d = if (i + 1 < src.length) src.substring(i, i + 2) else ""
                        when (d) {
                            "/*" -> { depth++; out.append("  "); i += 2 }
                            "*/" -> { depth--; out.append("  "); i += 2 }
                            else -> { out.append(if (src[i] == '\n') '\n' else ' '); i++ }
                        }
                    }
                }
                else -> {
                    out.append(src[i])
                    i++
                }
            }
        }
        return out.toString()
    }
}
