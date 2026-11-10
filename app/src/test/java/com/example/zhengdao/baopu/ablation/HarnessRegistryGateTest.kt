// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百五十九批（2026-11-11）· 消融登记表门禁：判据①每条「过期判定」不许空；
// 判据②补信任类不得进待删（负例演练：把 208 账本行改「待删」⇒ 本类红）。
// 只读 docs/harness-audit-registry.md，不碰生产；按行为/结构断言，不钉字面行号（557 口径）。
package com.example.zhengdao.baopu.ablation

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class HarnessRegistryGateTest {

    private data class Row(val cells: List<String>) {
        val name: String get() = cells[0]
        val category: String get() = cells[1]
        val location: String get() = cells[2]
        val expiry: String get() = cells[4]
        val status: String get() = cells[5]
    }

    // ── 判据①：过期判定列不许空 ──────────────────────────────────────────
    @Test
    fun `每条登记都有非空过期判定`() {
        val rows = rows()
        assertTrue("登记表一条数据行都没有", rows.isNotEmpty())
        rows.forEach { row ->
            assertTrue(
                "「${row.name}」的过期判定是空的（判据①：不许空）",
                row.expiry.isNotBlank(),
            )
        }
    }

    // ── 判据②：补信任不得进待删 ──────────────────────────────────────────
    @Test
    fun `补信任类不得进待删或已删`() {
        rows().filter { it.category == "补信任" }.forEach { row ->
            if (row.status.contains("待删") || row.status.contains("已删")) {
                fail("补信任件「${row.name}」状态=${row.status}——制度件不许删（判据②）")
            }
        }
    }

    @Test
    fun `补信任五入口在册且分类正确`() {
        val rows = rows()
        TRUST_ENTRIES.forEach { file ->
            val row = rows.find { it.location.contains(file) }
            assertTrue("补信任入口 $file 不在登记表里", row != null)
            assertTrue("$file 应分类为补信任，实为「${row!!.category}」", row.category == "补信任")
        }
    }

    // ── 结构：三分类齐全；首轮两条补能力在册 ─────────────────────────────
    @Test
    fun `三分类齐全且首轮两条补能力在册`() {
        val rows = rows()
        CATEGORIES.forEach { cat ->
            assertTrue("分类「$cat」一行都没有", rows.any { it.category == cat })
        }
        rows.forEach { row ->
            assertTrue("「${row.name}」分类「${row.category}」不在三分类里", row.category in CATEGORIES)
        }
        CAPABILITY_KEYS.forEach { key ->
            val row = rows.find { it.location.contains(key) }
            assertTrue("首轮补能力件 $key 不在登记表里", row != null)
            assertTrue("$key 应分类为补能力，实为「${row!!.category}」", row.category == "补能力")
        }
    }

    // ── 工具 ──────────────────────────────────────────────────────────────
    private fun rows(): List<Row> {
        val f = File(repoRoot(), "docs/harness-audit-registry.md")
        assertTrue("登记表不存在：${f.absolutePath}", f.isFile)
        return f.readLines(Charsets.UTF_8)
            .filter { it.trimStart().startsWith("|") }
            .filterNot { it.contains("---") || it.contains("过期判定") && it.contains("分类") }
            .map { line ->
                val cells = line.split('|').drop(1).dropLast(1).map { it.trim() }
                assertTrue("表格行列数不足 7：$line", cells.size >= 7)
                Row(cells)
            }
    }

    private fun repoRoot(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "app/src/main/java").isDirectory) d = d.parentFile
        return d ?: File("").absoluteFile
    }

    private companion object {
        val CATEGORIES = listOf("补能力", "补信任", "组织性")

        /** 与 ScaffoldSwitchesTest §⑤ 同一份五入口清单（那边钉源码，这边钉登记）。 */
        val TRUST_ENTRIES = listOf(
            "DecisionLedger.kt",
            "TaskGrantWire.kt",
            "GuardPipeline.kt",
            "AskGovernance.kt",
            "ConsequenceLedger.kt",
        )
        val CAPABILITY_KEYS = listOf(
            "zhengdao-scaffold/history-prefilter",
            "zhengdao-scaffold/compression-prompt",
        )
    }
}