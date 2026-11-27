package com.example.zhengdao.baopu.state.persona

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 940 源码闸：从磁盘读，带负例自检；不靠「文件里含某字面量」的恒真判据。 */
class GestureSourceAssertTest {
    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/persona/"
    private val ownFiles = listOf("GestureCatalog.kt", "GestureModel.kt", "GestureChecks.kt")
    private val importLine = Regex("(?m)^[ \\t]*import[ \\t]+\\S")
    private val publicDecl = Regex("(?m)^[ \\t]*(?:data[ \\t]+)?(?:class|object|interface|typealias)[ \\t]+")
    private val habitInSurface = Regex("data class SurfaceState\\([^)]*habitGestures")

    @Test
    fun `三件生产件都在同包且零import零公开顶层声明`() {
        ownFiles.forEach { name ->
            val text = code(name)
            assertTrue("$name 必须是 persona 同包", Regex("(?m)^package com\\.example\\.zhengdao\\.baopu\\.state\\.persona$").containsMatchIn(text))
            assertFalse("$name 不得 import（930 人格包零 import 闸同样适用）", importLine.containsMatchIn(text))
            assertTrue("$name 必须 internal", text.contains("internal "))
            assertFalse("$name 有公开顶层声明", publicDecl.containsMatchIn(text))
        }
        assertTrue("import 负例必须被看见", importLine.containsMatchIn("package a\nimport java.io.File"))
        assertTrue("公开声明负例必须被看见", publicDecl.containsMatchIn("package a\nobject Leak"))
        assertFalse("缩进成员不得误判", publicDecl.containsMatchIn("package a\ninternal object O {\n    val x = 1\n}"))
    }

    @Test
    fun `黑名单唯一表且与934的55条句式逐行不相交`() {
        val catalog = code("GestureCatalog.kt")
        val declaredTables = ownFiles.map { it to code(it) }.filter { (_, text) -> "val BLACKLIST" in text }
        assertEquals("BLACKLIST 只许在 GestureCatalog.kt 声明一次", listOf("GestureCatalog.kt"), declaredTables.map { it.first })
        val doc = File(repositoryRoot(), "docs/去滤镜.md").readText()
        val rows = doc.substringAfter("<!-- DEFILTER-BLACKLIST-BEGIN -->")
            .substringBefore("<!-- DEFILTER-BLACKLIST-END -->")
            .lines().filter { it.trim().startsWith("|") && "---" !in it && "编号" !in it }
            .map { it.trim().trim('|').split('|').map { cell -> cell.trim() } }
        assertEquals("934 文档必须真的读到 55 条，否则本闸是空的", 55, rows.size)
        val mine = GestureCatalog.BLACKLIST.map { it.phrase }.toSet()
        assertEquals(30, mine.size)
        rows.forEach { cells ->
            assertTrue("934 行格式坏了：$cells", cells[0].matches(Regex("[A-Z]+(?:-[A-Z]+)*[0-9]{2}")))
            assertFalse("不得与 934 共用行：${cells[3]}", cells[3] in mine)
        }
        val injected = "| JOB99 | 职业 | 医生 | 抿嘴 | 坏 |".trim().trim('|').split('|').map { it.trim() }
        assertTrue("负例自检：解析器必须认得出共用行", injected[3] in mine)
    }

    @Test
    fun `habitGestures只在persona包且不在SurfaceState的tags里`() {
        val model = code("PersonaModel.kt")
        assertFalse("SurfaceState 不得塞 habitGestures", habitInSurface.containsMatchIn(model))
        assertTrue(
            "负例自检：SurfaceState 里塞 habitGestures 必须被抓住",
            habitInSurface.containsMatchIn("data class SurfaceState(val tags: List<String>, val habitGestures: List<Int>)"),
        )
        assertFalse("负例自检：没有 habitGestures 的 SurfaceState 不得误判", habitInSurface.containsMatchIn("data class SurfaceState(val tags: List<String>)"))
        val surfaceBlock = model.substringAfter("data class SurfaceState").take(160)
        assertTrue("SurfaceState.tags 保持原样", "val tags" in surfaceBlock)
        val roots = File(repositoryRoot(), "app/src/main/java")
        val holders = roots.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { "val habitGestures" in it.readText() }.map { it.name }.toList()
        assertEquals("生产侧 habitGestures 只许在 GestureModel.kt 一处", listOf("GestureModel.kt"), holders)
    }

    @Test
    fun `位置判定只借用思路不重做930语言闸`() {
        val all = ownFiles.joinToString("\n") { code(it) }
        listOf("PersonaLanguage.clinical", "PersonaLanguage.stigma", "人格障碍", "依恋类型", "MBTI").forEach {
            assertFalse("940 不得重做/复制语言闸：$it", it in all)
        }
        val checks = code("GestureChecks.kt")
        assertTrue("必须有本地位置遮罩", "maskDialogue" in checks)
        assertTrue("必须认完整引号", "QUOTED" in checks)
        assertTrue("必须认直接引语", "DIRECT_SPEECH" in checks)
        assertTrue("负例自检：语言闸调用必须看得见", "PersonaLanguage.clinical(note)" in "PersonaLanguage.clinical(note)")
    }

    @Test
    fun `动作四要素字段与三张独立表都在册`() {
        val model = code("GestureModel.kt")
        listOf("val part:", "val direction:", "val amplitude:", "val durationMs:").forEach {
            assertTrue("四要素缺字段：$it", it in model)
        }
        val catalog = code("GestureCatalog.kt")
        listOf("val BLACKLIST", "val LITERARY", "val SYMBOL_LABELS", "val SYMBOL_CUES").forEach {
            assertTrue("表缺声明：$it", it in catalog)
        }
        assertFalse("负例自检：缺时长必须被看见", "val durationMs:" in "val amplitude: GestureAmplitude?")
        assertFalse("任何 IO 随机时钟入口都不许出现在新层", listOf("Random(", "currentTimeMillis", "readText(", "writeText(").any { it in allCode() })
    }

    private fun allCode(): String = ownFiles.joinToString("\n") { code(it) }

    private fun code(name: String): String = stripComments(SrcRoots.find(dir + name).readText())

    private fun stripComments(text: String): String {
        val withoutBlock = Regex("/\\*[\\s\\S]*?\\*/").replace(text, " ")
        return Regex("(?m)//.*$").replace(withoutBlock, " ")
    }

    private fun repositoryRoot(): File =
        generateSequence(SrcRoots.find(dir + "PersonaModel.kt").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
}
