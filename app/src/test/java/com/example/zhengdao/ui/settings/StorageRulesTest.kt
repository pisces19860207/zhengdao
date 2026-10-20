// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第七十七批 · 结构拆分第六阶段：Settings 存储/数据页纯逻辑抽取》§2
// （每个抽出的纯函数 ≥3 例：正常/边界/空值/口径）。
//
// 口径来源 = 抽取前 `ui/SettingsStorageActivity.kt` 的内联实现（等值搬移，
// 见交付报告「diff 等值证据」）：四条判据一条不改，负例口径见文件末注释。
package com.example.zhengdao.ui.settings

import com.example.zhengdao.terminal.StorageAudit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageRulesTest {

    private fun item(
        group: String,
        mb: Long = 0L,
        removable: Boolean = false,
        label: String = "x",
    ) = StorageAudit.Item(group = group, label = label, mb = mb, removable = removable)

    // ── groupLabel：分组名 → 白话展示名 ─────────────────────────────────

    @Test
    fun `groupLabel_三个登记分组各走各的白话`() {
        assertEquals("装 hermes 工具时下载的文件", StorageRules.groupLabel("hermes 工具"))
        assertEquals("hermes 自检记录", StorageRules.groupLabel("hermes 自检"))
        assertEquals("安装时下载的脚本", StorageRules.groupLabel("公共区安装脚本"))
    }

    @Test
    fun `groupLabel_未登记分组原样返回_不猜不吞`() {
        // 数据层新增分组时，展示层先原样显示（猜错比不翻译更糟）
        assertEquals("未来新组", StorageRules.groupLabel("未来新组"))
        assertEquals("", StorageRules.groupLabel(""))
    }

    // ── optionalToolsMb：可清理候选体积汇总 ─────────────────────────────

    @Test
    fun `optionalToolsMb_只算本组可清项`() {
        val items = listOf(
            item("hermes 工具", mb = 100L, removable = true),
            item("hermes 工具", mb = 50L, removable = true),
        )
        assertEquals(150L, StorageRules.optionalToolsMb(items))
    }

    @Test
    fun `optionalToolsMb_别组的可清项不算_本组的不可清项也不算`() {
        val items = listOf(
            item("hermes 工具", mb = 100L, removable = true),
            // 别组的可清项：口径是"hermes 工具"组，不是全场 removable
            item("hermes 自检", mb = 999L, removable = true),
            // 本组但标了保留（核心运行时/必需包/点名保留的扩展）
            item("hermes 工具", mb = 888L, removable = false),
        )
        assertEquals(100L, StorageRules.optionalToolsMb(items))
    }

    @Test
    fun `optionalToolsMb_空明细与无可清项都是0`() {
        assertEquals(0L, StorageRules.optionalToolsMb(emptyList()))
        assertEquals(
            0L,
            StorageRules.optionalToolsMb(listOf(item("hermes 工具", mb = 734L, removable = false))),
        )
    }

    // ── hasScriptCache：公共区安装脚本缓存在不在 ────────────────────────

    @Test
    fun `hasScriptCache_有那一组才真`() {
        assertTrue(
            StorageRules.hasScriptCache(
                listOf(item("hermes 工具", mb = 1L, removable = true), item("公共区安装脚本", mb = 2L)),
            ),
        )
    }

    @Test
    fun `hasScriptCache_没有或空表都假`() {
        assertFalse(StorageRules.hasScriptCache(listOf(item("hermes 工具", mb = 1L, removable = true))))
        assertFalse(StorageRules.hasScriptCache(emptyList()))
    }

    // ── agentCacheMb：Agent 包缓存合计 ─────────────────────────────────

    @Test
    fun `agentCacheMb_逐目录求和`() {
        assertEquals(
            36L,
            StorageRules.agentCacheMb(listOf("npm" to 20L, "uv" to 15L, "_cacache" to 1L)),
        )
    }

    @Test
    fun `agentCacheMb_空表为0`() {
        assertEquals(0L, StorageRules.agentCacheMb(emptyList()))
    }
}

// 负例口径（交付报告里逐条实跑）：
// ① optionalToolsMb 的过滤去掉 `it.group == "hermes 工具"` ⇒ 「别组的可清项不算」红；
// ② optionalToolsMb 的 `removable` 判成 `!removable` ⇒ 「本组的不可清项也不算」红；
// ③ hasScriptCache 的组名改一个字符 ⇒ 「有那一组才真」红；
// ④ groupLabel 的 else 改成 return "" ⇒ 「未登记分组原样返回」红。
