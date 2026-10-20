// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [EdgeSwipeTracker] 的验收（第七十四批 · 结构拆分第三阶段）。
 *
 * 数值按真机口径给（dp 当像素用，密度换算在 Activity 侧）：识别带 56dp、横向阈值 40dp、
 * 纵向容差 40dp。钉的是**会改变用户体感**的五条判据：
 * 1. 边缘起手才算（终端中部的滑动要留给滚动/选字）；
 * 2. 横向走够阈值；
 * 3. 横向占优（纵向滑动一律放过——终端继续滚动）；
 * 4. 纵向没跑偏；
 * 5. 正在选词时不算（长按拖选择手柄与右滑返回同形，判错=整屏没了且无撤销，2026-10-10 B7）。
 *
 * 另钉两条状态机纪律：命中后同一次手势不重复触发；选词状态**惰性**求值（普通滚动零开销）。
 */
class EdgeSwipeTrackerTest {

    private val edge = 56f
    private val trigger = 40f
    private val slop = 40f

    private fun trackerAtEdge(downX: Float = 10f, downY: Float = 500f): EdgeSwipeTracker =
        EdgeSwipeTracker().also { it.onDown(downX, downY, edge) }

    // ── 命中 ──────────────────────────────────────────────────────────────

    @Test
    fun `边缘起手横向拽够就命中`() {
        val t = trackerAtEdge()
        assertTrue(t.isTracking)
        assertTrue("dx=80 > 40、dy=5 < 40 ⇒ 命中", t.onDrag(90f, 505f, trigger, slop) { false })
        assertFalse("命中后跟踪必须清掉", t.isTracking)
    }

    @Test
    fun `命中后同一次手势的 UP 兜底不再重复触发`() {
        val t = trackerAtEdge()
        assertTrue(t.onDrag(90f, 500f, trigger, slop) { false })
        assertFalse("MOVE 已判命中并关闭，UP 再判必须为 false（否则可能双关）", t.onDrag(95f, 500f, trigger, slop) { false })
    }

    @Test
    fun `命中时保留日志三字段`() {
        val t = trackerAtEdge()
        t.onDrag(90f, 505f, trigger, slop) { false }
        assertEquals(80f, t.lastDx, 0.001f)
        assertEquals(5f, t.lastDy, 0.001f)
        assertEquals("最后一个 startX 是本次手势的起手点", 10f, t.startXValue, 0.001f)
    }

    // ── 不命中 ────────────────────────────────────────────────────────────

    @Test
    fun `不是边缘起手就不跟踪`() {
        val t = EdgeSwipeTracker()
        assertFalse("x=100 在 56dp 识别带外", t.onDown(100f, 500f, edge))
        assertFalse(t.onDrag(300f, 500f, trigger, slop) { false })
    }

    @Test
    fun `横向没走够阈值不算`() {
        val t = trackerAtEdge()
        assertFalse("dx=30 < 40", t.onDrag(40f, 500f, trigger, slop) { false })
    }

    @Test
    fun `纵向占优一律放过`() {
        val t = trackerAtEdge()
        assertFalse("dx=100 > 40，但 dy=120 > dx ⇒ 这是纵向滑动，终端继续滚动", t.onDrag(110f, 620f, trigger, slop) { false })
    }

    @Test
    fun `纵向跑偏不算`() {
        val t = trackerAtEdge()
        assertFalse("dy=45 ≥ 40 容差", t.onDrag(110f, 545f, trigger, slop) { false })
    }

    @Test
    fun `正在选词时不算右滑返回`() {
        val t = trackerAtEdge()
        assertFalse("形状一样但用户在拖选择手柄（2026-10-10 B7）", t.onDrag(90f, 500f, trigger, slop) { true })
        assertTrue("没命中就不该清跟踪", t.isTracking)
    }

    // ── 状态机纪律 ────────────────────────────────────────────────────────

    @Test
    fun `不跟踪时选词回调根本不被求值`() {
        var asked = 0
        val t = EdgeSwipeTracker()
        t.onDown(100f, 0f, edge) // 识别带外
        t.onDrag(300f, 0f, trigger, slop) { asked += 1; false }
        assertEquals("普通滚动时不得去问 isSelectingText（热路径零开销）", 0, asked)
    }

    @Test
    fun `UP 与 CANCEL 都结束跟踪`() {
        val a = trackerAtEdge()
        a.onUp()
        assertFalse(a.isTracking)

        val b = trackerAtEdge()
        b.onCancel()
        assertFalse(b.isTracking)
    }

    // ── 源码门禁 ──────────────────────────────────────────────────────────

    @Test
    fun `Activity 分发事件只调 tracker 旧字段不得残留`() {
        val src = File("src/main/java/com/example/zhengdao/TerminalActivity.kt")
            .let { if (it.isFile) it else File("app/src/main/java/com/example/zhengdao/TerminalActivity.kt") }
            .readText()
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

        assertTrue("dispatchTouchEvent 必须走 edgeSwipe.onDrag", src.contains("edgeSwipe.onDrag("))
        assertTrue("DOWN 必须走 edgeSwipe.onDown", src.contains("edgeSwipe.onDown("))
        assertFalse("旧的 edgeTracking 字段不得残留（两份状态必然漂移）", src.contains("edgeTracking"))
        assertFalse("旧的手工 dx/dy 判定不得留在 Activity", src.contains("dx > trigger && dx > dy"))
    }
}
