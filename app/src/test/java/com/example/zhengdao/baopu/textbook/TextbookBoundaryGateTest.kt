// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 798 判据⑥：本段明确不接注入、不接判据——源码断言 textbook 包零外部调用，
// 且 textbook 自己不许 import 注入/判据/账本实现。合成负例自证闸不是空转。
package com.example.zhengdao.baopu.textbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TextbookBoundaryGateTest {
    private val symbols = listOf(
        "TextbookLibrary", "AppendOnlyStore", "TextbookValidator", "TextbookSchema",
        "TextbookEntry", "RefCatalog", "ReviewExpiry", "TEXTBOOK_FIELDS",
    )
    private val bannedImports = listOf(
        "com.example.zhengdao.baopu.inspect.",
        "com.example.zhengdao.baopu.kb.",
        "com.example.zhengdao.baopu.agent.",
        "com.example.zhengdao.baopu.kernel.",
        "com.example.zhengdao.baopu.eval.",
        "com.example.zhengdao.ui.",
    )

    @Test
    fun `生产源码中textbook包零外部调用`() {
        val files = productionSources()
        assertTrue("一个生产源文件都没扫到，门禁失效了", files.isNotEmpty())
        val hits = externalCalls(files)
        assertEquals(
            "段 A 明确零消费方，发现外部调用：\n" + hits.joinToString("\n"),
            emptyList<String>(),
            hits,
        )
    }

    @Test
    fun `textbook包自身不调用注入或判据包`() {
        val files = textbookSources()
        assertTrue("textbook 包一个源文件都没扫到", files.isNotEmpty())
        val hits = bannedImportCalls(files)
        assertEquals(
            "教材库不许自己接注入/判据：\n" + hits.joinToString("\n"),
            emptyList<String>(),
            hits,
        )
    }

    @Test
    fun `门禁自检_造违规必须被抓_KDoc提及不算`() {
        val fake = listOf(
            "Other.kt" to listOf(
                "import com.example.zhengdao.baopu.textbook.TextbookLibrary",
                "val x = TextbookLibrary.ingest(drop, lib)",
            ),
        )
        assertEquals("两行违规都要抓到", 2, externalCalls(fake).size)

        val commentOnly = listOf("Doc.kt" to listOf("// 段 B 才会接 TextbookLibrary", " * 引用 ReviewExpiry"))
        assertEquals("注释/KDoc 提及不算调用", emptyList<String>(), externalCalls(commentOnly))

        val fakeImport = listOf(
            "TextbookValidator.kt" to listOf("import com.example.zhengdao.baopu.inspect.WorldFacts"),
        )
        assertEquals("禁止 import 必须被抓", 1, bannedImportCalls(fakeImport).size)
    }

    private fun productionSources(): List<Pair<String, List<String>>> =
        File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { !it.path.replace('\\', '/').contains("/baopu/textbook/") }
            .sortedBy { it.path }
            .map { it.path to it.readLines(Charsets.UTF_8) }
            .toList()

    private fun textbookSources(): List<Pair<String, List<String>>> =
        File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.replace('\\', '/').contains("/baopu/textbook/") }
            .sortedBy { it.path }
            .map { it.path to it.readLines(Charsets.UTF_8) }
            .toList()

    private fun isComment(line: String): Boolean {
        val t = line.trim()
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("*/")
    }

    private fun externalCalls(files: List<Pair<String, List<String>>>): List<String> {
        val hits = mutableListOf<String>()
        files.forEach { (path, lines) ->
            lines.forEachIndexed { idx, line ->
                if (isComment(line)) return@forEachIndexed
                if (symbols.any { line.contains(it) }) hits += "$path:${idx + 1}: ${line.trim()}"
            }
        }
        return hits
    }

    private fun bannedImportCalls(files: List<Pair<String, List<String>>>): List<String> {
        val hits = mutableListOf<String>()
        files.forEach { (path, lines) ->
            lines.forEachIndexed { idx, line ->
                if (isComment(line)) return@forEachIndexed
                val t = line.trim()
                if (t.startsWith("import ") && bannedImports.any { t.contains(it) }) {
                    hits += "$path:${idx + 1}: $t"
                }
            }
        }
        return hits
    }
}
