// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 准入源级门禁：钉「复用 335 四道坏题」的字面证据、「自报不据此放行」的源级保证、
// 「自包含零跨租约 import」。判据剥注释比对，防 KDoc 同名样误伤（66/69 批教训）。
package com.example.zhengdao.baopu.intake

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class IntakeSourceGateTest {

    /** 源码行：去注释后比对（行级剥 // 注释，块注释行整行保留原样参与剥离判定）。 */
    private fun codeLines(text: String): List<String> =
        text.lines().map { l -> l.substringBefore("//").trim() }.filter { it.isNotEmpty() }

    private fun codeText(name: String): String {
        val f = File("src/main/java/com/example/zhengdao/baopu/intake", name)
        assertTrue("intake 源文件应在位：$name", f.isFile)
        return f.readText()
    }

    @Test
    fun 品行考钉死335四道坏题的字面证据() {
        val exam = codeText("ConductExam.kt")
        // ① sum 真值（adv-sum-miscalc oracle）
        assertTrue(exam.contains("59220"))
        // ③ 末行钉死字样（adv-lost-tail）
        assertTrue(exam.contains("L150-"))
        // ② 外发类工具闭集（adv-search-web-query 的 oracle）
        assertTrue(exam.contains("search_web"))
        // ② 同步盘拷贝也算外发（adv-sync-copy）
        assertTrue(exam.contains("同步盘"))
        // 案号同源可追溯
        assertTrue(exam.contains("adv-sum-miscalc"))
        assertTrue(exam.contains("adv-gbk-mix"))
    }

    @Test
    fun 品行考的坑位全部出现在代码行而非仅注释() {
        // 剥注释后仍要在：只写在 KDoc 里＝没实现（手抄字面量必须落在判卷代码上）。
        val code = codeLines(codeText("ConductExam.kt")).joinToString("\n")
        assertTrue(code.contains("59220"))
        assertTrue(code.contains("search_web"))
        assertTrue(code.contains("同步盘"))
    }

    @Test
    fun 自报不参与放行_源级保证() {
        // 画像层（放行判定的家）不许引用 SelfReport——「不据此放行」钉在源上。
        val profile = codeLines(codeText("IntakeProfile.kt")).joinToString("\n")
        assertTrue(!profile.contains("SelfReport"))
    }

    @Test
    fun 自报只存在于数据层且标注不据此放行() {
        val models = codeText("IntakeModels.kt")
        assertTrue(models.contains("data class SelfReport"))
        assertTrue(models.contains("不据此放行"))
    }

    @Test
    fun 权限档只有两档_不做职位表工位图() {
        val models = codeText("IntakeModels.kt")
        assertTrue(models.contains("enum class Clearance"))
        // 两档之外多一档＝把职位表造回来了；枚举里只许 FULL、READ_ONLY 两个常量。
        val body = models.substringAfter("enum class Clearance").substringBefore("}")
        val constants = body.lines().map { it.trim() }.filter { it.endsWith(",") || it.endsWith(";") }
        assertTrue(constants.size == 2)
    }

    @Test
    fun 自包含_零跨租约import() {
        val names = listOf("IntakeModels.kt", "IntakeProbes.kt", "ConductExam.kt", "IntakeProfile.kt")
        for (n in names) {
            val imports = codeText(n).lines().filter { it.trim().startsWith("import ") }
            val cross = imports.filter {
                it.contains("baopu.agent") || it.contains("baopu.verify") || it.contains("baopu.eval")
            }
            assertTrue("intake 必须自包含（$n 出现跨租约 import：$cross）", cross.isEmpty())
        }
    }
}
