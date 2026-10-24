// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

// 第一百八十八批 §1.7（配套）：filtered 列的判据一致性。
//
// 快照日志的 filtered 列由 [ToolRegistry.filteredReasons] 算，它的判据是
// [filteredReasonOf]——[isVisibleTool] 的**反面**（可见 ⇔ 原因为空）。
// 本文件钉住这个「互为反面」：谁改可见性公式只改一边，全表扫描当场红。
// 这正是 §4 打回第四条「另起第二条工具过滤链」的防线。
package com.example.zhengdao.baopu.agent

import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilteredReasonConsistencyTest {

    private fun registry(): Pair<ToolRegistry, java.io.File> {
        val root = createTempDirectory("zd-filtered-").toFile()
        val reg = ToolRegistry(InMemoryToolPrefs())
        BaopuToolCatalog.loadInto(reg) { root }
        return reg to root
    }

    @Test
    fun `全表扫描_可见性与原因互为反面_三种开关状态都成立`() {
        val (reg, root) = registry()
        try {
            val specs = reg.all()
            assertTrue("注册表不该是空的", specs.isNotEmpty())
            val settingsVariants = listOf(
                ToolUserSettings(),
                ToolUserSettings(disabledNames = setOf("read_file")),
                ToolUserSettings(disabledGroups = setOf(ToolGroup.BROWSER)),
                ToolUserSettings(approvedGroups = ToolGroup.values().toSet()),
            )
            val unlockVariants = listOf(
                emptySet<ToolGroup>(),
                setOf(ToolGroup.BROWSER),
                ToolGroup.values().toSet(),
            )
            for (s in settingsVariants) {
                for (u in unlockVariants) {
                    for (spec in specs) {
                        val visible = isVisibleTool(spec, s, u)
                        val reason = filteredReasonOf(spec, s, u)
                        assertEquals(
                            "${spec.name}（组 ${spec.group.key}）的可见性与原因必须互为反面" +
                                "（settings=$s unlocked=$u）",
                            visible,
                            reason == null,
                        )
                    }
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `原因优先级_用户关组优先于未解锁_不许指错路`() {
        // 用户明确关掉的组 ⇒ 说「组被用户关闭」，不指路成「解锁一下就能用」
        val spec = ToolSpec(
            name = "browser_click",
            description = "点一下",
            inputSchema = org.json.JSONObject(),
            group = ToolGroup.BROWSER,
            timeoutMs = ToolTimeout.BROWSER_MS,
            executor = { ToolOutcome.Ok("") },
        )
        val s = ToolUserSettings(disabledGroups = setOf(ToolGroup.BROWSER))
        assertEquals("组被用户关闭", filteredReasonOf(spec, s, emptySet()))
        // 单工具关掉优先于组关掉（先判的赢）
        val s2 = ToolUserSettings(
            disabledNames = setOf("browser_click"),
            disabledGroups = setOf(ToolGroup.BROWSER),
        )
        assertEquals("用户关闭", filteredReasonOf(spec, s2, emptySet()))
        // 没关没解锁 ⇒ 组未解锁
        assertEquals("组未解锁", filteredReasonOf(spec, ToolUserSettings(), emptySet()))
        // 解锁了 ⇒ 没有原因
        assertEquals(null, filteredReasonOf(spec, ToolUserSettings(), setOf(ToolGroup.BROWSER)))
    }

    @Test
    fun `filteredLine_全可见时none_关一个出一条_组关了一整组`() {
        val (reg, root) = registry()
        try {
            // 全部长期同意 ⇒ 全可见（默认可见 ∪ 已同意 ∪ 已解锁 三川并流）
            ToolGroup.values().forEach { reg.setGroupApproved(it, true) }
            assertEquals("none", reg.filteredLine())

            reg.setDisabled("read_file", true)
            val line = reg.filteredLine()
            assertTrue("关掉的工具要带原因：$line", line.contains("read_file=用户关闭"))

            // 组级：把整个 browser 组的长期同意撤掉 ⇒ 组内全部变「组未解锁」
            reg.setGroupApproved(ToolGroup.BROWSER, false)
            val line2 = reg.filteredLine()
            assertTrue("组关了要整组带原因：$line2", line2.contains("=组未解锁"))
            assertTrue("read_file 仍在（单工具关是另一条）", line2.contains("read_file=用户关闭"))
        } finally {
            root.deleteRecursively()
        }
    }
}
