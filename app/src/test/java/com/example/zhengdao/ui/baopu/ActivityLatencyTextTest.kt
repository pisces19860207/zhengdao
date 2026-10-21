// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百三十一批 · UI-D7 门禁（纯函数级 ＋ 一条渲染结构事实）。
//
// 真机现象（qa-2026-10-21-all/03-活动面板展开.png）：展开活动流后每条只有
// 类型签＋工具名＋状态/原因，**没有耗时**。派工单 §C 要的是「每条都有耗时位，
// 取不到显示 —，不许编 0s」。这里钉四件事：
//   J8 有读数的步给出统一格式（亚秒 `0.3s`、整秒 `3s`、分钟 `1m20s`）；
//   J9 取不到（<=0）显示 `—`，**绝不出现 `0s`**；
//   J9-b 0ms 这条边界单独钉死（`formatDuration(0)` 会吐 `0s`，那正是假值来源）；
//   J10 渲染层恒画耗时位（不再有 `latencyMs > 0` 的开关），1.3×/深色下才有东西可截。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.text.Charsets

class ActivityLatencyTextTest {

    private val panel: String by lazy {
        File("src/main/java/com/example/zhengdao/ui/baopu/BaopuActivityPanel.kt")
            .readText(Charsets.UTF_8)
    }

    /** J9：取不到 ⇒ `—`，不给假读数。 */
    @Test
    fun `J9_取不到耗时显示破折号`() {
        assertEquals("0ms（run_log 缺省）必须显示 —", "—", activityLatencyText(0))
        assertEquals("负数同样按取不到处理", "—", activityLatencyText(-1))
        assertEquals("占位常量就是全角破折号", "—", ACTIVITY_LATENCY_UNKNOWN)
    }

    /** J9-b：绝不允许吐 `0s`（那是"这步瞬间完成"的假读数）。 */
    @Test
    fun `J9_零毫秒不许吐0s假值`() {
        listOf(0L, -5L).forEach {
            assertFalse("latencyMs=$it 不许渲染成 0s", activityLatencyText(it) == "0s")
        }
    }

    /** J8：亚秒保留一位小数，且不会因为 <100ms 退化成 0。 */
    @Test
    fun `J8_亚秒保留一位小数`() {
        assertEquals("300ms", "0.3s", activityLatencyText(300))
        assertEquals("50ms 向上取到 0.1s（不许 0.0s）", "0.1s", activityLatencyText(50))
        assertEquals("949ms", "0.9s", activityLatencyText(949))
        assertEquals("950ms 进位到 1.0s（不许 0.10s）", "1.0s", activityLatencyText(950))
    }

    /** J8：≥1s 复用既有 formatDuration，口径与轮摘要一致。 */
    @Test
    fun `J8_整秒与分钟沿用既有口径`() {
        assertEquals("1s", activityLatencyText(1_000))
        assertEquals("3s", activityLatencyText(3_400))
        assertEquals("1m20s", activityLatencyText(80_000))
        assertEquals("2m", activityLatencyText(120_000))
    }

    /** J10：渲染层恒画耗时位——`latencyMs > 0` 的开关必须消失。 */
    @Test
    fun `J10_渲染层恒画耗时位`() {
        assertTrue(
            "活动行必须直接用 activityLatencyText(item.latencyMs)",
            panel.contains("activityLatencyText(item.latencyMs)"),
        )
        assertFalse(
            "不许再用 latencyMs > 0 决定画不画——那正是真机上「没有耗时」的成因",
            panel.contains("if (item.latencyMs > 0)"),
        )
    }

    /** 数据来源不另起一套：ActivityItem 的耗时字段就是 run_log 的 latencyMs。 */
    @Test
    fun `J8_数据只取既有latencyMs`() {
        val item = activityItemOf(
            BaopuStepRow(tool = "read_file", ok = true, latencyMs = 1_500, err = "", createdAt = 1L),
            target = "",
        )
        assertEquals("耗时原样来自 run_log 行，不新造计时源", 1_500L, item.latencyMs)
        assertEquals("1.5 秒按整秒口径显示", "1s", activityLatencyText(item.latencyMs))
    }
}
