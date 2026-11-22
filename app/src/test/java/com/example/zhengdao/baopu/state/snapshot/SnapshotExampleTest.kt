// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百三十四批 · 只读示例：给定 tick 与场景输入 → 完整快照 JSON。
package com.example.zhengdao.baopu.state.snapshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotExampleTest {

    @Test
    fun `示例导出六段齐全且格数等于规格表`() {
        val snap = SnapshotAssembler.assemble(SnapshotExample.input())
        assertEquals("六段", 6, snap.sections.size)
        assertEquals("段次序", SnapshotSpec.SECTIONS, snap.sections.map { it.id })
        assertEquals("字段数", SnapshotSpec.FIELDS.size, snap.sections.sumOf { it.fields.size })
        snap.sections.forEach { section ->
            assertEquals(
                "${section.id} 段内次序",
                SnapshotSpec.fieldsOf(section.id).map { it.id },
                section.fields.map { it.id },
            )
        }
    }

    @Test
    fun `示例JSON含给定与缺席两类格`() {
        val json = SnapshotExample.json()
        assertTrue("应有 Given", "\"value\": \"" in json)
        assertTrue("应有 Absent", "\"absent\": \"" in json)
        assertTrue("每格须带溯源单号", "\"source\": \"657" in json)
        assertTrue("824 子段须留痕", "\"source\": \"824" in json)
    }

    @Test
    fun `示例只读_两次调用互不影响`() {
        val a = SnapshotExample.input()
        val b = SnapshotExample.input()
        assertEquals("输入相等", a, b)
        assertEquals("JSON 相等", SnapshotExample.json(), SnapshotExample.json())
        assertEquals("tick 固定", SnapshotExample.TICK, a.tick)
    }
}
