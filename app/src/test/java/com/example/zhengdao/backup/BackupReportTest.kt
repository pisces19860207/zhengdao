// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百九十一批（设计修订）· 判据「冲突：目标已有数据 ⇒ 不覆盖、列清单」在**回执**这一侧的钉子。
//
// 为什么单测回执：模型是照着这段字跟用户说话的。回执里要是写"已自动跳过冲突"，
// 用户就永远不知道自己那几条没导进去；写清"一个都没改 + 列出是哪几条"才算数。
package com.example.zhengdao.backup

import com.example.zhengdao.baopu.agent.BackupTools
import com.example.zhengdao.baopu.agent.renderImportReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupReportTest {

    private fun run(
        applied: Int = 2,
        unchanged: Int = 1,
        conflicts: List<String> = listOf("memory/global/决策记录.jsonl"),
        written: List<String> = listOf("memory/global/人设.md"),
        settingsInPackage: Boolean = true,
        settingsApplied: Int = 0,
        reload: ReloadReport? = ReloadReport(3, true),
    ) = ImportRun(
        source = "/storage/emulated/0/Download/证道/导入/证道备份.zip",
        applied = applied,
        unchanged = unchanged,
        conflicts = conflicts,
        written = written,
        settingsInPackage = settingsInPackage,
        settingsApplied = settingsApplied,
        reload = reload,
    )

    @Test
    fun 冲突必须点名且明说一个都没改() {
        val text = renderImportReport(run())
        assertTrue(text, text.contains("冲突未覆盖 1 项"))
        assertTrue(text, text.contains("memory/global/决策记录.jsonl"))
        assertTrue("不许用「已自动跳过」这种糊话：$text", text.contains("一个都没改"))
        assertTrue("要告诉用户下一步怎么办：$text", text.contains("明确说"))
    }

    @Test
    fun 写进去的那几条也要点名() {
        val text = renderImportReport(run())
        assertTrue(text, text.contains("写入：memory/global/人设.md"))
    }

    @Test
    fun 冲突太多只列前若干条但仍报总数() {
        val many = (1..25).map { "memory/global/第$it.jsonl" }
        val text = renderImportReport(run(conflicts = many))
        assertTrue(text, text.contains("冲突未覆盖 25 项"))
        assertEquals("回执里最多列 CONFLICT_LINES 条", BackupTools.CONFLICT_LINES, countLines(text))
        assertTrue("剩下的要说还有几条：$text", text.contains("另有 5 项"))
    }

    private fun countLines(text: String): Int = text.lines().count { it.startsWith(" - memory/global/第") }

    @Test
    fun 设置没动要说清楚而不是假装还原了() {
        val text = renderImportReport(run())
        assertTrue(text, text.contains("包里有，但这次没动"))
        val applied = renderImportReport(run(settingsApplied = 7))
        assertTrue(applied, applied.contains("设置：已写回 7 项"))
    }

    @Test
    fun 热加载读数必须在回执里() {
        val text = renderImportReport(run())
        assertTrue(text, text.contains("记忆索引 3 条"))
        assertTrue("要能让用户/模型知道本会话已可搜：$text", text.contains("本会话立刻可搜到"))
        val noIndex = renderImportReport(run(reload = ReloadReport(0, false)))
        assertTrue(noIndex, noIndex.contains("没有检索索引"))
    }

    @Test
    fun 没有冲突就不该出现冲突那一段() {
        val text = renderImportReport(run(conflicts = emptyList()))
        assertTrue(text, !text.contains("冲突"))
    }
}
