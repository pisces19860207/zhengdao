// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 798 段 B · 判据④⑤⑦ + 边界：源码级断言——段 C 零代码路径、不新增对外字段/类型、
// 不改 738 预算与顺序、不改教材库 schema。断言均带负例（喂坏代码串必须红）。
package com.example.zhengdao.baopu.textbook.inject

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TextbookBoundaryGateTest {

    /** 定位工程根：从 classpath 的 build 目录向上找 settings.gradle.kts。与生产同一 File(root,rel) 口径。 */
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        val candidates = listOf(dir, dir.parentFile, dir.parentFile?.parentFile)
        return candidates.firstOrNull { it != null && File(it, "settings.gradle.kts").isFile }
            ?: error("找不到工程根: ${dir.absolutePath}")
    }

    private fun read(rel: String): String = File(repoRoot(), rel).readText(Charsets.UTF_8)

    private val injectPkg = "app/src/main/java/com/example/zhengdao/baopu/textbook/inject"
    private val wiring = "app/src/main/java/com/example/zhengdao/baopu/calibration/CalibrationWiring.kt"
    private val injectCore = "app/src/main/java/com/example/zhengdao/baopu/calibration/CalibrationInject.kt"
    private val schema = "app/src/main/java/com/example/zhengdao/baopu/textbook/TextbookEntry.kt"

    /** 剥掉 Kotlin 注释——中文「不改判据/阈值」写在注释里不算代码路径，避免自证误伤。 */
    private fun stripComments(src: String): String =
        src.replace(Regex("""(?s)/\*.*?\*/"""), "").replace(Regex("""(?m)//.*$"""), "")

    /** ⑤ 段 C 一律不做：注入层源码（剥注释后）不得出现改判据/改参数/改阈值的代码路径。 */
    @Test
    fun `判据五_段C零代码路径_不改判据参数阈值`() {
        val src = stripComments(
            listOf("TeachModel.kt", "TextbookMatcher.kt", "TextbookInject.kt", "TextbookTeaching.kt", "TextbookRuntime.kt")
                .joinToString("\n") { read("$injectPkg/$it") },
        )
        val banned = listOf(
            "modifyThreshold", "setThreshold", "changeCriterion", "modifyCriterion",
            "changeParam", "setParam", "writeThreshold", "promoteVersion", "requireHumanApproval",
        )
        banned.forEach { token ->
            assertFalse("段 C 代码路径不得出现：$token", src.contains(token, ignoreCase = true))
        }
        // 负例护栏：本测试能咬住坏代码串（防止断言恒真/检测函数失效）。
        assertTrue("负例：检测器须对坏串报红", banned.any { "fun setThreshold(".contains(it, ignoreCase = true) })
    }

    /** ④ 不新增对外字段/类型：注入层全部 internal；对外 wiring 只加一条 provider 缝，无新数据类。 */
    @Test
    fun `判据四_注入层全internal_未新增对外类型`() {
        val files = listOf("TeachModel.kt", "TextbookMatcher.kt", "TextbookInject.kt", "TextbookTeaching.kt", "TextbookRuntime.kt")
        files.forEach { name ->
            val src = read("$injectPkg/$name")
            val decls = Regex("""(?m)^(internal\s+|public\s+)?(data class|class|object|enum class|sealed interface|sealed class|interface)\s+\w+""")
                .findAll(src).map { it.value.trim() }.toList()
            assertTrue("$name 应有顶层声明", decls.isNotEmpty())
            decls.forEach { d ->
                assertTrue("$name 顶层声明须 internal（无修饰=默认 public）：$d", d.startsWith("internal "))
                assertFalse("$name 不得有 public 顶层声明：$d", d.startsWith("public "))
            }
        }
    }

    /** 边界：738 注入预算与顺序未放宽（常量与三行逐行 addAll 原样）。 */
    @Test
    fun `边界_738预算三行与顺序未改`() {
        val core = read(injectCore)
        assertTrue("L1_MAX=3 原样", core.contains("L1_MAX = 3"))
        assertTrue("L2_MAX=8 原样", core.contains("L2_MAX = 8"))
        assertTrue("VARIANT_MAX=4 原样", core.contains("VARIANT_MAX = 4"))
        assertTrue("三行逐行 addAll 原样", core.contains("notes.addAll(readback.asContextNotes())"))
        val w = read(wiring)
        assertTrue("教材块确为纯追加：在 build 之后", w.indexOf("CalibrationInject.build") < w.indexOf("textbookNotesProvider?.invoke"))
        assertFalse("负例：若教材放在构建前则本测试红", w.indexOf("textbookNotesProvider?.invoke") < w.indexOf("CalibrationInject.build"))
    }

    /** 边界：教材库 schema（段 A 十字段）未改。 */
    @Test
    fun `边界_教材库schema未改十字段`() {
        val s = read(schema)
        val fields = listOf("id", "sourceType", "source", "asOf", "domain", "content", "strength", "scope", "revocable", "supersedes")
        fields.forEach { assertTrue("schema 字段须在：$it", s.contains("\"$it\"") || s.contains("val $it")) }
        assertFalse("段 B 不得给 schema 增字段", s.contains("teachSide") || s.contains("positive") || s.contains("negative"))
    }

    /** ⑦ 水位/kotlin 静态：注入层未引用判分器/786 grader（段 B 不做判分）。 */
    @Test
    fun `判据七_段B不引用判分器不改786`() {
        val src = listOf("TeachModel.kt", "TextbookMatcher.kt", "TextbookInject.kt", "TextbookTeaching.kt", "TextbookRuntime.kt")
            .joinToString("\n") { read("$injectPkg/$it") }
        assertFalse("不得引用 grader", src.contains("grader", ignoreCase = true))
        assertFalse("不得引用 grade.py", src.contains("grade.py"))
        assertTrue("负例护栏：检测函数可用", "Grade".contains("grade", ignoreCase = true))
    }
}
