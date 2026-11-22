// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百三十四批 · 判据①⑤：字段全部溯源、不造新档、不新增 public 面。
package com.example.zhengdao.baopu.state.snapshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SnapshotSpecTest {

    private val sources: List<Pair<String, String>> by lazy {
        val dir = File("src/main/java/com/example/zhengdao/baopu/state/snapshot")
        dir.listFiles { f -> f.extension == "kt" }
            .orEmpty()
            .sortedBy { it.name }
            .map { it.name to it.readText() }
    }

    /** 去掉行注释与块注释，避免注释里写的字面量把断言顶成假红/假绿。 */
    private fun String.codeOnly(): String = replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .lines().map { it.substringBefore("//") }.joinToString("\n")

    @Test
    fun `判据一_每格都有单号符号文件与缺失理由`() {
        assertTrue("规格表不许空", SnapshotSpec.FIELDS.isNotEmpty())
        SnapshotSpec.FIELDS.forEach { f ->
            assertTrue("${f.id} 缺单号", f.sourceOrder.isNotBlank())
            assertTrue("${f.id} 缺符号", f.sourceSymbol.isNotBlank())
            assertTrue("${f.id} 缺文件", f.sourceFile.isNotBlank())
            assertTrue("${f.id} 缺 absent 理由", f.missing.isNotBlank())
            assertTrue("${f.id} 段名不在六段内", f.section in SnapshotSpec.SECTIONS)
        }
    }

    @Test
    fun `判据一_六段齐全且字段id唯一`() {
        assertEquals("六段", 6, SnapshotSpec.SECTIONS.size)
        assertEquals("段名不许重复", SnapshotSpec.SECTIONS.toSet().size, SnapshotSpec.SECTIONS.size)
        assertEquals("字段 id 不许重复", SnapshotSpec.FIELDS.size, SnapshotSpec.FIELDS.map { it.id }.toSet().size)
        SnapshotSpec.SECTIONS.forEach { sec ->
            assertTrue("$sec 段必须有字段", SnapshotSpec.fieldsOf(sec).isNotEmpty())
        }
    }

    @Test
    fun `判据一_快照包不定义任何状态档`() {
        val enums = sources.flatMap { (name, raw) ->
            raw.codeOnly().lines().mapIndexed { i, line ->
                "$name:${i + 1}" to line.trimStart()
            }.filter { (_, line) -> line.startsWith("enum class") || line.startsWith("internal enum class") }
                .map { (at, _) -> at }
        }
        assertEquals("快照只装配既有档，不许新造枚举：${enums.joinToString()}", emptyList<String>(), enums)
    }

    @Test
    fun `判据五_顶层声明全为internal或private`() {
        val allowed = listOf("internal", "private", "protected")
        val leaks = sources.flatMap { (name, raw) ->
            raw.codeOnly().lines().mapIndexed { i, line ->
                "$name:${i + 1}" to line
            }.filter { (_, line) ->
                // 只看顶层（列 0）；对象内成员缩进，不进本闸——与 public-surface.py 同口径
                val first = line.substringBefore(' ')
                line.isNotEmpty() && !line.first().isWhitespace() &&
                    first in setOf("class", "object", "interface", "fun", "val", "var", "data", "sealed", "const")
            }.filter { (_, line) -> allowed.none { line.startsWith(it) } }
                .map { (at, _) -> at }
        }
        assertEquals("新增 public 面须先登记：${leaks.joinToString()}", emptyList<String>(), leaks)
    }

    @Test
    fun `判据二_生产源码无墙钟无随机`() {
        val banned = listOf(
            "System.currentTimeMillis", "Instant.now", "LocalDateTime.now",
            "java.util.Random", "kotlin.random", "Math.random",
        )
        val hits = sources.flatMap { (name, raw) ->
            val code = raw.codeOnly()
            banned.filter { it in code }.map { "$name:$it" }
        }
        assertEquals("快照不许读墙钟/随机：${hits.joinToString()}", emptyList<String>(), hits)
    }
}
