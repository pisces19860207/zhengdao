// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九百零三批 · 事故回流表门禁。
// 唯一账本在 docs/事故回流表.md；本文件只读它、不抄第二份账。
// 钉死三件事：七列结构、八类四档词表、首批五例已实测入账——删行、改类、去理由必红。
// 903 只入账不做自动化、不改任何 check 函数（自动生成归 904，红分支归 905）。
package com.example.zhengdao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IncidentLedgerGateTest {

    private data class Row(
        val id: String,
        val symptom: String,
        val type: String,
        val cause: String,
        val target: String,
        val status: String,
        val date: String,
    )

    private data class Pinned(val id: String, val symptomPart: String, val type: String)

    /** 七列固定：表头列名与顺序变了即红。 */
    private val header = listOf("#", "症状", "类型", "根因", "落点单", "状态", "日期")

    /** 类型固定 8 类：账本类型声明与每行取值都必须出自这里。 */
    private val fixedTypes = listOf(
        "几何不可能", "穿模", "机制错", "场景失真",
        "能量档错", "词表误写", "时序错", "其他",
    )

    /** 状态固定 4 档：行内取值必须以其中一档开头（「不修（理由：……）」按前缀认档）。 */
    private val fixedStatuses = listOf("待归类", "已派单", "已拦截", "不修")

    /** 首批五例（已实测、派前核查在案）：删行、改类都由这里咬住。 */
    private val initialFive = listOf(
        Pinned("001", "嘴", "几何不可能"),
        Pinned("002", "躯干", "穿模"),
        Pinned("003", "拉链", "机制错"),
        Pinned("004", "毛巾", "场景失真"),
        Pinned("005", "没轻没重", "能量档错"),
    )

    private val ledgerBegin = "<!-- INCIDENT-LEDGER-BEGIN -->"
    private val ledgerEnd = "<!-- INCIDENT-LEDGER-END -->"
    private val typesBegin = "<!-- INCIDENT-TYPES-BEGIN -->"
    private val typesEnd = "<!-- INCIDENT-TYPES-END -->"

    /** 变异入口：读仓库里的唯一账本；内存变异直接改字符串，不碰仓库文件。 */
    private fun docContent(): String {
        val candidates = listOf(File("../docs/事故回流表.md"), File("docs/事故回流表.md"))
        val file = candidates.firstOrNull { it.isFile }
            ?: error("事故回流表没找到：${candidates.joinToString { it.absolutePath }}")
        return file.readText()
    }

    private fun section(content: String, begin: String, end: String): List<String> {
        val lines = content.lines()
        val from = lines.indexOfFirst { it.contains(begin) }
        val to = lines.indexOfFirst { it.contains(end) }
        check(from >= 0 && to > from) { "账本缺标记：$begin / $end" }
        return lines.subList(from + 1, to)
    }

    /** 只留数据行：竖表行拆列，丢掉分隔行。 */
    private fun tableRows(block: List<String>): List<List<String>> =
        block.filter { it.trimStart().startsWith("|") }
            .map { it.trim().trim('|').split('|').map { cell -> cell.trim() } }
            .filter { cols -> cols[0].any { it != '-' && it != ':' && it != ' ' } }

    private fun ledger(content: String): List<Row> {
        val rows = tableRows(section(content, ledgerBegin, ledgerEnd))
        assertEquals("账本表头七列固定，不许增删列", header, rows.firstOrNull() ?: error("账本连表头都没有"))
        return rows.drop(1).map { c ->
            assertEquals("每行必须恰好七列：$c", header.size, c.size)
            Row(c[0], c[1], c[2], c[3], c[4], c[5], c[6])
        }
    }

    private fun types(content: String): List<String> =
        tableRows(section(content, typesBegin, typesEnd))
            .filter { it[0] != "类型" }
            .map { it[0] }

    /** 账内有没有哪一行没咬住首批五例（缺行、改类、症状关键段丢失都算）。 */
    private fun missingPinned(rows: List<Row>): List<String> {
        val byId = rows.associateBy { it.id }
        return initialFive.filter { pinned ->
            val row = byId[pinned.id]
            row == null || !row.symptom.contains(pinned.symptomPart) || row.type != pinned.type
        }.map { it.id }
    }

    @Test
    fun `账本结构七列八类四档全固定`() {
        val content = docContent()
        assertEquals("类型固定 8 类，不许增删", fixedTypes, types(content))
        val rows = ledger(content)
        assertTrue("账本不许是空的", rows.isNotEmpty())
        assertEquals(
            "编号必须从 001 起连续递增",
            (1..rows.size).map { it.toString().padStart(3, '0') },
            rows.map { it.id },
        )
        rows.forEach { row ->
            assertTrue("类型必须出自固定 8 类：${row.id} ${row.type}", row.type in fixedTypes)
            val status = fixedStatuses.firstOrNull { row.status.startsWith(it) }
            assertTrue("状态必须出自固定 4 档：${row.id} ${row.status}", status != null)
            assertTrue("症状必须非空：${row.id}", row.symptom.isNotBlank())
            assertTrue("根因必须非空：${row.id}", row.cause.isNotBlank())
            assertTrue(
                "日期必须是 yyyy-MM-dd：${row.id} ${row.date}",
                row.date.matches(Regex("""\d{4}-\d{2}-\d{2}""")),
            )
            when (status) {
                "待归类" -> assertEquals("「待归类」不许挂落点单：${row.id}", "待派", row.target)
                else -> assertTrue(
                    "落点单必须挂单：${row.id} ${row.target}",
                    row.target.isNotBlank() && row.target != "待派",
                )
            }
        }
        rows.forEach { row ->
            println(
                "INCIDENT-LEDGER-READING ${row.id} | ${row.symptom} | ${row.type} | " +
                    "${row.cause} | ${row.target} | ${row.status} | ${row.date}",
            )
        }
        println("INCIDENT-LEDGER-READING rows=${rows.size} types=${fixedTypes.size} statuses=${fixedStatuses.size}")
    }

    @Test
    fun `首批五例已实测入账_删行与改类必红`() {
        val content = docContent()
        assertEquals("首批五例必须全咬住（绿侧）", emptyList<String>(), missingPinned(ledger(content)))
        // 两枪内存变异，不碰仓库文件：删行与改类都必须红。
        val dropped = content.lines().filterNot { it.startsWith("| 001 |") }.joinToString("\n")
        assertEquals("删 001 行必须红", listOf("001"), missingPinned(ledger(dropped)))
        val line001 = content.lines().first { it.startsWith("| 001 |") }
        val retyped = content.replace(line001, line001.replace("几何不可能", "其他"))
        assertEquals("001 类型被改成「其他」必须红", listOf("001"), missingPinned(ledger(retyped)))
        println("INCIDENT-LEDGER-READING mutation drop-001=red retyped-001=red")
    }

    @Test
    fun `不修必须留理由_待归类不许挂单`() {
        val rows = ledger(docContent())
        rows.filter { it.status.startsWith("不修") }.forEach { row ->
            assertTrue(
                "「不修」必须留理由：${row.id} ${row.status}",
                row.status.matches(Regex("""不修（理由：.+）""")),
            )
        }
        rows.filter { it.status.startsWith("待归类") }.forEach { row ->
            assertEquals("「待归类」必须写待派：${row.id}", "待派", row.target)
        }
        println(
            "INCIDENT-LEDGER-READING nofix=${rows.count { it.status.startsWith("不修") }} " +
                "pending=${rows.count { it.status.startsWith("待归类") }}",
        )
    }
}
