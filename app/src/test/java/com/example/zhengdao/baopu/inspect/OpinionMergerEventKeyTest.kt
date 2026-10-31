// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.baopu.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 336 · 事件键口径测试：eventKeyOf 实现 sameEvent 的**第一判据**（证据首项），
 * 第二判据（source+gist）有意不进键（不同证据的同名意见是不同事实）。
 */
class OpinionMergerEventKeyTest {

    private fun op(evFirst: String): Opinion = OpinionAdapter.CLASSIFIER.adapt(
        Severity.MAJOR, "越界访问", ActionCode.REVIEW, LawRef.L9,
        listOf(evFirst, "tool:write_file"),
    )

    private fun opWith(source: Source, evFirst: String): Opinion = OpinionAdapter.valueOf(source.name).adapt(
        Severity.MAJOR, "越界访问", ActionCode.REVIEW, LawRef.L9, listOf(evFirst),
    )

    @Test
    fun `同一事件的重试提交_同键`() {
        // 282 D 组死循环形状：同一动作重试，每次证据完全相同（callId 不同但 callId 不进 Opinion）
        val a = op("risk:outside_workspace")
        val b = op("risk:outside_workspace")
        assertEquals(OpinionMerger.eventKeyOf(a), OpinionMerger.eventKeyOf(b))
    }

    @Test
    fun `证据首项不同_不同键_即使source与gist相同`() {
        // sameEvent 的第二判据（source+gist）不进键：不同事实不并（比 sameEvent 窄，是故意的）
        val a = opWith(Source.CLASSIFIER, "risk:outside_workspace")
        val b = opWith(Source.CLASSIFIER, "risk:password_field")
        assertNotEquals(OpinionMerger.eventKeyOf(a), OpinionMerger.eventKeyOf(b))
    }

    @Test
    fun `键以证据首项为根_证据其余项不参与`() {
        val a = op("risk:outside_workspace")
        val b = OpinionAdapter.CLASSIFIER.adapt(
            Severity.MAJOR, "越界访问", ActionCode.REVIEW, LawRef.L9,
            listOf("risk:outside_workspace", "tool:edit_file", "matrix:v9"),
        )
        assertEquals(OpinionMerger.eventKeyOf(a), OpinionMerger.eventKeyOf(b))
    }

    @Test
    fun `键形状带版本前缀`() {
        assertTrue(OpinionMerger.eventKeyOf(op("risk:outside_workspace")).startsWith("ev1:"))
    }
}
