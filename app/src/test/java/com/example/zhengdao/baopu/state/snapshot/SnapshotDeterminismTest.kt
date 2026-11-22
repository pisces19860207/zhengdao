// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百三十四批 · 判据②：同输入同输出（逐字节），无随机、无墙钟。
package com.example.zhengdao.baopu.state.snapshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotDeterminismTest {

    @Test
    fun `判据二_同输入十次导出逐字节一致`() {
        val first = SnapshotExample.json().toByteArray(Charsets.UTF_8)
        repeat(9) { i ->
            val again = SnapshotExample.json().toByteArray(Charsets.UTF_8)
            assertTrue("第 ${i + 2} 次与首次不同", first.contentEquals(again))
        }
    }

    @Test
    fun `判据二_同一输入对象装配两次产出相等`() {
        val input = SnapshotExample.input()
        val a = SnapshotAssembler.assemble(input)
        val b = SnapshotAssembler.assemble(input)
        assertEquals("装配结果须相等", a, b)
        assertEquals("JSON 须逐字节一致", SnapshotJson.render(a), SnapshotJson.render(b))
    }

    @Test
    fun `判据二_空输入也逐字节一致`() {
        val input = SnapshotInput(tick = 0)
        val a = SnapshotJson.render(SnapshotAssembler.assemble(input))
        repeat(4) { assertEquals("空输入第 $it 次不同", a, SnapshotJson.render(SnapshotAssembler.assemble(input))) }
    }

    @Test
    fun `判据二_随机与墙上时间不出现在导出里`() {
        val json = SnapshotExample.json()
        assertFalse("不许带墙钟", "currentTimeMillis" in json)
        assertFalse("不许带随机种子", "seed" in json.lowercase())
        assertTrue("版本固定", "\"version\": \"834-x1\"" in json)
        assertTrue("tick 固定", "\"tick\": 42" in json)
    }

    @Test
    fun `判据二_字符串转义不产生裸换行`() {
        val snap = Snapshot(
            version = SnapshotSpec.VERSION,
            tick = 1,
            sections = listOf(
                SnapshotSection(
                    SnapshotSpec.SEC_TIME,
                    listOf(
                        SnapshotField("time.tick", SnapshotSlot.Given("a\"b\\c\n", number = false)),
                    ),
                ),
            ),
        )
        val json = SnapshotJson.render(snap)
        assertTrue("引号须转义", "\\\"b" in json)
        assertTrue("反斜杠须转义", "\\\\c" in json)
        assertTrue("换行须转义", "\\n" in json)
        assertTrue("引号后仍是合法结构", json.startsWith("{\n  \"version\":"))
    }
}
