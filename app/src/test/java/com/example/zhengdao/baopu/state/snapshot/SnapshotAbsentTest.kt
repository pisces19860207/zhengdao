// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百三十四批 · 判据③：缺失字段显式 absent(reason)，不许默认值填充。
package com.example.zhengdao.baopu.state.snapshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotAbsentTest {

    @Test
    fun `判据三_空输入除tick外全为absent且理由来自规格表`() {
        val snap = SnapshotAssembler.assemble(SnapshotInput(tick = 7))
        val all = snap.sections.flatMap { it.fields }
        assertEquals("字段数须等于规格表", SnapshotSpec.FIELDS.size, all.size)

        all.forEach { field ->
            if (field.id == "time.tick") {
                assertTrue("tick 必须有值", field.slot is SnapshotSlot.Given)
                return@forEach
            }
            val slot = field.slot as? SnapshotSlot.Absent
                ?: error("${field.id} 缺字段却被默认值填充：${field.slot}")
            assertEquals("${field.id} 的 absent 理由须逐字来自规格表", SnapshotSpec.of(field.id).missing, slot.reason)
            assertTrue("${field.id} 的理由不许空", slot.reason.isNotBlank())
        }
    }

    @Test
    fun `判据三_永远缺席的格在满输入下仍absent`() {
        val snap = SnapshotAssembler.assemble(SnapshotExample.input())
        val byId = snap.sections.flatMap { it.fields }.associateBy { it.id }
        val never = listOf(
            "body.zones.now",   // 731 生产类型未落地
            "space.motion.now", // motion 只有静态关键姿，无当前态类型
            "time.solarTerm",   // 821 未落地
            "light.sourceType", // 824 无生产包
            "light.colorTemp",
            "light.weather",
            "light.shadow.hardness",
            "light.material.response",
            "fluid.surfaceFlow", // 806 未装配
        )
        never.forEach { id ->
            val slot = byId.getValue(id).slot
            assertTrue("$id 不许被默认值填充：$slot", slot is SnapshotSlot.Absent)
            assertTrue("$id 的缺席理由须带单号", SnapshotSpec.of(id).missing.any { it.isDigit() })
        }
    }

    @Test
    fun `判据三_满输入下既有包字段确实有值`() {
        val snap = SnapshotAssembler.assemble(SnapshotExample.input())
        val byId = snap.sections.flatMap { it.fields }.associateBy { it.id }
        listOf(
            "time.tick", "time.dayOfYear", "environment.sun.intensity",
            "space.pose.valid", "space.capacity.verdict", "fluid.volume",
            "fluid.channels.sweat", "light.intensity", "light.direction",
        ).forEach { id ->
            assertTrue("$id 在示例里应当有值", byId.getValue(id).slot is SnapshotSlot.Given)
        }
    }

    @Test
    fun `判据三_absent格不输出value键`() {
        val json = SnapshotExample.json()
        val absentLine = json.lines().first { "\"absent\"" in it }
        assertFalse("缺席不许同时给值", "\"value\"" in absentLine)
        assertTrue("缺席必须给理由", "\"absent\": \"" in absentLine)
        assertTrue("缺席仍须溯源", "\"source\": \"" in absentLine)
    }
}
