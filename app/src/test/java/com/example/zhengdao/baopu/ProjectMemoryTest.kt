// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 2026-10-19 最小版项目记忆：工作区根 `项目记忆.md`，每轮自动读、≤2k tokens、永不压缩。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectMemoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun missing_file_is_null_not_error() {
        assertNull(ProjectMemory.load(tmp.root))
    }

    @Test
    fun blank_file_is_null() {
        File(tmp.root, ProjectMemory.FILE_NAME).writeText("  \n\t\n")
        assertNull(ProjectMemory.load(tmp.root))
    }

    @Test
    fun reads_content_as_is() {
        val text = "# 项目记忆\n- 口径 A\n- 口径 B\n"
        File(tmp.root, ProjectMemory.FILE_NAME).writeText(text)
        assertEquals(text.trimEnd(), ProjectMemory.load(tmp.root))
    }

    @Test
    fun over_budget_is_trimmed_with_a_visible_note() {
        val huge = "长".repeat(8_000) // 8000 个汉字，远超 2k tokens
        File(tmp.root, ProjectMemory.FILE_NAME).writeText(huge)
        val got = ProjectMemory.load(tmp.root)!!
        assertTrue("要带截断说明", got.contains("已截断"))
        assertTrue("截断后仍应可读", got.length > 50)
        assertTrue("截断后 token 不超预算", TokenEstimator.estimate(got) <= ProjectMemory.MAX_TOKENS + 120)
    }

    @Test
    fun assembler_puts_project_memory_after_hard_facts_and_never_compresses() {
        val prompt = ContextAssembler.assemble(
            persona = emptyList(),
            facts = HardFacts(workspacePath = "/ws", model = "m"),
            stickyMemories = emptyList(),
            newMemories = emptyList(),
            history = emptyList(),
            current = "干活",
            projectMemory = "- 口径：先备份再改",
        )
        val kinds = prompt.segments.map { it.kind }
        val factsAt = kinds.indexOf(SegmentKind.HARD_FACTS)
        val memAt = kinds.indexOf(SegmentKind.PROJECT_MEMORY)
        assertTrue("要有项目记忆段：$kinds", memAt > factsAt)
        val text = prompt.segments.first { it.kind == SegmentKind.PROJECT_MEMORY }.text
        assertTrue(text.contains(ProjectMemory.FILE_NAME))
        assertTrue(text.contains("先备份再改"))
        assertFalse("项目记忆不许被压缩标记替代", kinds.contains(SegmentKind.COMPRESSION))
    }

    @Test
    fun no_project_memory_no_segment() {
        val prompt = ContextAssembler.assemble(
            persona = emptyList(),
            facts = HardFacts(workspacePath = "/ws", model = "m"),
            stickyMemories = emptyList(),
            newMemories = emptyList(),
            history = emptyList(),
            current = "干活",
        )
        assertFalse(prompt.segments.any { it.kind == SegmentKind.PROJECT_MEMORY })
    }
}
