// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百一十三批 · 右相回灌的**源级门禁**（判据⑥源级面＋「唯一格式」钉死）：
//   ① GapItem 六字段封死（唯一格式：多一个字段就是第二份格式）；
//   ② 回灌与格式两个源文件的**字面量**禁价值词（判据⑥；豁免 BANNED_VALUE_WORDS 声明行——
//      闸本身得能写出来，但闸被删/词被挪进别处照样红）；
//   ③ record 只收 GapItem（判据②：没有自由文本进板的口子）。
// 扫之前先证明门禁本身会咬人（合成违规行必须被抓出来），免得「全绿」只是没扫到。
package com.example.zhengdao.baopu.verify

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GapFeedbackGateTest {

    private val verifyDir = File("src/main/java/com/example/zhengdao/baopu/verify")

    /** 扫代码行：注释剔掉（注释里提禁词是说明，不是文案）。 */
    private fun code(f: File): String = f.readText(Charsets.UTF_8)
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .replace(Regex("(?m)//.*$"), " ")

    /** 带行豁免的字面量扫描：声明禁词表的那一行（BANNED_VALUE_WORDS）跳过。 */
    private fun bannedWordLines(src: String): List<String> = src.lineSequence()
        .filterNot { it.contains("BANNED_VALUE_WORDS") }
        .flatMap { line ->
            Regex("\"([^\"]*)\"").findAll(line)
                .map { it.groupValues[1] }
                .filter { text -> GapItem.BANNED_VALUE_WORDS.any { text.contains(it) } }
                .map { word -> "$word ← ${line.trim().take(60)}" }
        }
        .toList()

    @Test
    fun `唯一格式_GapItem六字段封死_多一个少一个都红`() {
        val region = code(File(verifyDir, "GapItem.kt"))
            .substringAfter("internal data class GapItem(")
            .substringBefore(") {")
        val fields = Regex("""val (\w+)""").findAll(region).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf("source", "kind", "target", "evidence", "minimalFix", "attempts"),
            fields,
        )
    }

    @Test
    fun `判据⑥_回灌与格式源文件的字面量禁价值词`() {
        val hits = listOf("GapFeedback.kt", "GapItem.kt").flatMap { name ->
            bannedWordLines(code(File(verifyDir, name))).map { "$name: $it" }
        }
        assertTrue("回灌文案出现价值判断词：\n${hits.joinToString("\n")}", hits.isEmpty())
    }

    @Test
    fun `判据⑥_禁词闸在位_删了即红`() {
        val src = code(File(verifyDir, "GapItem.kt"))
        assertTrue(
            "禁词表被删（BANNED_VALUE_WORDS 声明＋使用应各出现一次）",
            Regex("BANNED_VALUE_WORDS").findAll(src).count() >= 2,
        )
    }

    @Test
    fun `判据②_record只收GapItem_没有自由文本的口子`() {
        val src = code(File(verifyDir, "GapFeedback.kt"))
        assertTrue("record 签名被改松", src.contains("fun record(items: List<GapItem>)"))
        assertTrue("金吾卫与右相共用的入口消失", src.contains("fun chancellorGapsOf(report: ChancellorReport)"))
    }

    @Test
    fun `门禁自证_合成违规行会被抓出来`() {
        val violations = listOf(
            """val fix = "你应该先备份再重试"""",
            """val hint = "建议换个做法"""",
            """val line = "这样更好一些"""",
        )
        violations.forEach { line ->
            assertTrue("没抓住违规：$line", bannedWordLines(line).isNotEmpty())
        }
        // 豁免行（禁词表声明自己）不得误伤。
        assertTrue(bannedWordLines("""val BANNED_VALUE_WORDS = listOf("建议", "应该", "更好")""").isEmpty())
    }
}
