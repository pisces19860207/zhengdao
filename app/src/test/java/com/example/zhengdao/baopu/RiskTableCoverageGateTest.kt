// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第二百九十五批 §3「JVM 门禁：GROUP_OF 键集必须等于风险表键集，不等即红」。
//
// 为什么门禁放 JVM 不放真机：两张表都是编译期常量，纯集合相等在 JVM 上就能判死；
// 真机那条路（RiskWiringInstrumentedTest ⑤）只负责「真机的 org.json 下逐个 classify 不炸」。
// 这条门禁堵的是「新增工具只进分组表、忘了配风险行」——没档的工具判档时落 fail-closed
// 默认档（R2＋必问），等于没判；以前要等真机跑红才发现，现在 CI 直接拦。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.ToolGroupTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第二百九十五批 JVM 门禁：**键集对键集**，不是数数量。
 *
 * 四个用例各管一件事：
 * - 正例：生产默认表此刻必须键集双向相等；
 * - 负例 A（判据①）：故意删一条风险行 ⇒ 红；
 * - 负例 B（负例「只比数量不比键集」）：**同数量**换键 ⇒ 仍红；
 * - 负例 C：把注册中的工具塞进豁免集（豁免＝跳过判档，等于没判）⇒ 红。
 *
 * 负例全部以「改表副本」跑，不动生产表。
 */
class RiskTableCoverageGateTest {

    /**
     * 门禁本体：与 `ToolGroupTable.checkCoverage()` 同款的双向差集口径——
     * 少一行（漏配档）与多一行（表里躺着没注册的名字）都算红；豁免集不得收编注册中的工具。
     * 返回空 = 全过；非空 = 每条一条人话。
     */
    private fun gate(table: RiskTable): List<String> {
        val missing = ToolGroupTable.GROUP_OF.keys - table.tools.keys
        val stale = table.tools.keys - ToolGroupTable.GROUP_OF.keys
        val exemptedRegistered = table.exempt intersect ToolGroupTable.GROUP_OF.keys
        return buildList {
            if (missing.isNotEmpty()) add("注册了但没有风险行（判档会落 fail-closed 默认档）：${missing.sorted()}")
            if (stale.isNotEmpty()) add("风险表里躺着没注册的名字：${stale.sorted()}")
            if (exemptedRegistered.isNotEmpty()) add("注册中的工具不许进豁免集：${exemptedRegistered.sorted()}")
        }
    }

    /** 正例：生产默认表此刻必须全过（75 键双向相等）。 */
    @Test
    fun 门禁_生产默认表键集双向相等() {
        assertEquals("生产表与分组表键集必须双向相等", emptyList<String>(), gate(RiskTable.DEFAULT))
        // 数量也对死：两张表谁都不能偷偷少一行
        assertEquals(
            "键集相等时数量也必须相等",
            ToolGroupTable.GROUP_OF.size,
            RiskTable.DEFAULT.tools.size,
        )
    }

    /** 负例 A（判据①）：故意删一条风险行 ⇒ 门禁红，且红必须点名缺的那行。 */
    @Test
    fun 门禁_删一行风险行_红() {
        val mutated = RiskTable.DEFAULT.copy(tools = RiskTable.DEFAULT.tools - "write_file")
        val report = gate(mutated)
        assertTrue("删了行门禁必须红：$report", report.isNotEmpty())
        assertTrue("红必须点名缺的那行：$report", report.any { it.contains("write_file") })
    }

    /**
     * 负例 B：同数量换键 ⇒ 仍红。`write_file` 的行换成假名 `gate_fake_tool`，
     * 数量一格不变——「只比数量」的门禁在这里糊过去，键集门禁必须抓住。
     */
    @Test
    fun 门禁_同数量换键_仍红() {
        val swapped = RiskTable.DEFAULT.tools - "write_file" + ("gate_fake_tool" to ToolRule(RiskClass.R0))
        assertEquals("夹具自检：换键后数量必须不变", RiskTable.DEFAULT.tools.size, swapped.size)
        val report = gate(RiskTable.DEFAULT.copy(tools = swapped))
        assertTrue("同数量换键门禁必须仍红：$report", report.isNotEmpty())
        assertTrue(
            "红必须两头都点名（缺 write_file＋多 gate_fake_tool）：$report",
            report.any { it.contains("write_file") } && report.any { it.contains("gate_fake_tool") },
        )
    }

    /** 负例 C：把注册中的工具塞进豁免集 ⇒ 红（豁免＝跳过判档，等于没判）。 */
    @Test
    fun 门禁_注册工具进豁免集_红() {
        val mutated = RiskTable.DEFAULT.copy(exempt = RiskTable.DEFAULT.exempt + "read_file")
        val report = gate(mutated)
        assertTrue("豁免集收编注册工具必须红：$report", report.isNotEmpty())
        assertTrue("红必须点名那个工具：$report", report.any { it.contains("read_file") })
    }
}
