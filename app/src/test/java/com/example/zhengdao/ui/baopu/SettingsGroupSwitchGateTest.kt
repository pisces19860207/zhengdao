// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：2026-10-21 真机（97 批工具分层）两个缺陷：
// ① 组开关点完**同屏不刷新**（写盘成功、文案仍是「已锁」，退出重进才对）；
// ② 一次点击写**两条**一模一样的 `[group]` 审计（审计自己 emit ＋ 调用点又 RunLog.log）。
// 本文件把两条都钉成源码门禁，防回潮。
package com.example.zhengdao.ui.baopu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 97 批组开关：刷新依赖与审计单写。 */
class SettingsGroupSwitchGateTest {

    private fun source(): String {
        val candidates = listOf(
            File("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt"),
            File("app/src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt"),
        )
        return candidates.first { it.exists() }.readText()
    }

    @Test
    fun `G1_组状态必须订阅toolTick_否则开关点完不刷新`() {
        // 2026-10-21 真机缺陷：开关写盘成功，但同屏一直显示旧状态，退出重进才对。
        // 141 批换了形态（组头不再各自 remember，改成整卡一份快照），**判据的实质没变**：
        // 组状态必须挂在 toolTick 这一个刷新源上，且归一仍只有一处。
        val src = source()
        listOf("approvedGroups", "disabledGroupSet", "disabledNameSet").forEach { name ->
            assertTrue(
                "val $name 必须 remember(toolTick)（写盘成功但同屏不刷新就是漏了这一层）",
                src.contains("val $name = remember(toolTick)"),
            )
        }
        assertFalse(
            "不许退回无订阅的 remember（点了不刷新）",
            Regex("val (approvedGroups|disabledGroupSet|disabledNameSet) = remember \\{").containsMatchIn(src),
        )
        assertTrue("组状态归一必须在页面里出现（四态）", src.contains("baopuGroupState("))
    }

    @Test
    fun `G2_调用点不得再包一层RunLog_log_否则审计双写`() {
        val src = source()
        assertFalse(
            "ToolGroupAudit.manual 自带 emit→RunLog，调用点再 log 一次就是双写",
            src.contains("RunLog.log(ToolGroupAudit.manual"),
        )
        assertTrue(
            "调用点必须保留审计动作本身（141 批：组开关移进 toolRows.forEach，变量名跟着变）",
            src.contains("ToolGroupAudit.manual(row.group, want)"),
        )
    }

    @Test
    fun `G3_审计纯函数只产一行且emit一次`() {
        val emitted = mutableListOf<String>()
        val old = com.example.zhengdao.baopu.agent.ToolGroupAudit.sink
        try {
            com.example.zhengdao.baopu.agent.ToolGroupAudit.sink = { emitted += it }
            val line = com.example.zhengdao.baopu.agent.ToolGroupAudit.manual(
                com.example.zhengdao.baopu.agent.ToolGroup.BROWSER, true,
            )
            assertEquals("一次 manual 只能 emit 一行", 1, emitted.size)
            assertEquals("行内容与返回值一致", line, emitted.single())
            assertTrue("锚必须是 [group]", line.startsWith("[group]"))
            assertTrue("来源必须是设置页", line.contains("来源=设置页"))
        } finally {
            com.example.zhengdao.baopu.agent.ToolGroupAudit.sink = old
        }
    }
}
