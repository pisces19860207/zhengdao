// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 步骤卡收纳的单测（第十六批第一件 · 派工单 §4 判据 J1–J7）。
 *
 * 全部打在 [BaopuMessageList.kt] 的**纯函数**上（groupRounds / roundCollapsed /
 * stepsInWindow / roundSummary / formatDuration）——无 Compose、无 Android、无
 * SQLite，JVM 直测。数据形状与库里一致：message(id/role/created_at) ＋
 * part(kind/payload) 经 controller 读出后的 [BaopuUiMessage]/[BaopuUiPart]。
 */
class BaopuRoundGroupingTest {

    // ── 造数小工具 ────────────────────────────────────────────────────────

    private fun textPart(t: String) = BaopuUiPart(kind = "text", text = t)
    private fun toolPart(t: String = "read_file") = BaopuUiPart(kind = "tool", text = "结果：$t", tool = t)

    private fun msg(id: Long, role: String, at: Long, vararg parts: BaopuUiPart) =
        BaopuUiMessage(id = id, role = role, parts = parts.toList(), createdAt = at)

    /** J1 的 fixture：两条 user 消息（开轮，自身不计 part）；第二轮含 3 个 part（1 文本 + 2 工具卡）。 */
    private fun j1Messages(): List<BaopuUiMessage> = listOf(
        msg(1, "user", 1_000),
        msg(2, "assistant", 2_000, textPart("回答一")),
        msg(3, "user", 3_000),
        msg(4, "assistant", 4_000, textPart("中间说明"), toolPart("read_file")),
        msg(5, "assistant", 5_000, toolPart("list_dir")),
    )

    // ── J1 轮次分组 ───────────────────────────────────────────────────────

    @Test
    fun `J1_两条user消息切成2轮_第二轮含3个part`() {
        val rounds = groupRounds(j1Messages())
        assertEquals("两条 user 消息 ⇒ 2 轮", 2, rounds.size)
        assertEquals("第一轮从 user#1 开", 1L, rounds[0].userMessageId)
        assertEquals("第二轮从 user#3 开", 3L, rounds[1].userMessageId)
        assertEquals(
            "第二轮 3 个 part（1 文本 + 2 工具卡）",
            3,
            rounds[1].messages.sumOf { it.parts.size },
        )
        assertEquals("轮窗口左边界 = user 消息落库时刻", 3_000L, rounds[1].startMs)
        assertNull("最后一轮窗口开放（没有下一条 user 消息）", rounds[1].endMs)
        assertEquals("非最后一轮的窗口右边界 = 下一条 user 消息时刻", 3_000L, rounds[0].endMs)
        assertTrue("最后一轮才是当前轮", rounds[1].isCurrent)
        assertFalse("历史轮不是当前轮", rounds[0].isCurrent)
        assertEquals("空流 ⇒ 0 轮", 0, groupRounds(emptyList()).size)
    }

    // ── J2 当前轮不收起 ───────────────────────────────────────────────────

    @Test
    fun `J2_在跑时最后一轮不收起`() {
        val rounds = groupRounds(j1Messages())
        assertFalse(
            "running=true ⇒ 当前（最后一）轮必须展开",
            roundCollapsed(rounds.last(), running = true, pinnedUserIds = emptySet()),
        )
        // 收起的对称面：不在跑且没点开 ⇒ 收
        assertTrue(
            "收工后当前轮也收（一轮结束自动收成一行）",
            roundCollapsed(rounds.last(), running = false, pinnedUserIds = emptySet()),
        )
    }

    // ── J3 历史轮默认收起 ─────────────────────────────────────────────────

    @Test
    fun `J3_已完成的历史轮默认收起`() {
        val rounds = groupRounds(j1Messages())
        assertTrue(
            "历史轮（无卡/没点开）默认收起",
            roundCollapsed(rounds.first(), running = false, pinnedUserIds = emptySet()),
        )
        assertTrue(
            "在跑时历史轮照样收（只有当前轮保持展开）",
            roundCollapsed(rounds.first(), running = true, pinnedUserIds = emptySet()),
        )
        // 139：用户能展开的是轮尾过程条的**浮层明细**，不是正文——
        // 点开明细不会把历史轮的正文重新拉开（roundCollapsed 不再看用户展开态）。
        assertTrue(
            "点开过程明细不改变正文收起（明细走 Popup，不进布局流）",
            roundCollapsed(rounds.first(), running = false, pinnedUserIds = emptySet()),
        )
        assertTrue(
            "历史轮有工具 part ⇒ 仍挂过程条（16 批数据不丢）",
            roundHasProcess(rounds.last(), steps = emptyList(), awaitConfirmTool = null),
        )
    }

    // ── J4 失败计数 ───────────────────────────────────────────────────────

    @Test
    fun `J4_3行run_log两成一并失败_失败数1`() {
        val rows = listOf(
            BaopuStepRow("read_file", ok = true, latencyMs = 10, err = "", createdAt = 1_000),
            BaopuStepRow("list_dir", ok = true, latencyMs = 10, err = "", createdAt = 2_000),
            BaopuStepRow("write_file", ok = false, latencyMs = 10, err = "失败：磁盘满", createdAt = 3_000),
        )
        val s = roundSummary(rows)
        assertEquals("3 行都是干活步骤", 3, s.steps)
        assertEquals("失败数 = ok=0 的行数", 1, s.fails)
    }

    @Test
    fun `压缩留痕行不算干活步骤_失败也不进失败数`() {
        val rows = listOf(
            BaopuStepRow("read_file", ok = true, latencyMs = 10, err = "", createdAt = 1_000),
            BaopuStepRow("compression_failed", ok = false, latencyMs = 0, err = "code=500", createdAt = 1_500),
            BaopuStepRow("compression_skipped_degraded", ok = true, latencyMs = 0, err = "", createdAt = 1_600),
        )
        val s = roundSummary(rows)
        assertEquals("压缩内务不算步骤", 1, s.steps)
        assertEquals("压缩失败不算任务的失败", 0, s.fails)
    }

    // ── J5 用时口径 ───────────────────────────────────────────────────────

    @Test
    fun `J5_首末created_at差90秒_显示1m30s`() {
        val rows = listOf(
            BaopuStepRow("read_file", ok = true, latencyMs = 10, err = "", createdAt = 1_000_000),
            BaopuStepRow("list_dir", ok = true, latencyMs = 10, err = "", createdAt = 1_090_000),
        )
        assertEquals("用时 = 首末 created_at 之差", 90_000L, roundSummary(rows).durationMs)
        assertEquals("口径测试写死：90_000 → 1m30s", "1m30s", formatDuration(90_000))
        assertEquals("整分钟省秒：120_000 → 2m", "2m", formatDuration(120_000))
        assertEquals("一分钟内：42_000 → 42s", "42s", formatDuration(42_000))
    }

    // ── J6 没有数据不装 ───────────────────────────────────────────────────

    @Test
    fun `J6_无run_log行_用时为null_不显示0s`() {
        assertEquals("0 行（兜底也没有）⇒ 用时 null", null, roundSummary(emptyList()).durationMs)
        assertEquals("0 行但消息时间差可兜底 ⇒ 用兜底（派工单 §2.2）", 5_000L, roundSummary(emptyList(), fallbackMs = 5_000).durationMs)
        assertEquals("0 步", 0, roundSummary(emptyList()).steps)
        assertEquals("0 失败（「不知道」和「没失败」都是 0，不加符号）", 0, roundSummary(emptyList()).fails)
    }

    // ── J7 确认卡不被收 ───────────────────────────────────────────────────

    @Test
    fun `J7_带确认卡的轮强制展开`() {
        val rounds = groupRounds(j1Messages())
        val key = rounds.last().userMessageId
        assertFalse(
            "pinned 命中 ⇒ 不收（哪怕不在跑、用户也没点开）",
            roundCollapsed(rounds.last(), running = false, pinnedUserIds = setOf(key)),
        )
        // 钉住的是「要人动手」的卡所在的轮：确认卡/问人卡跟着最后一轮走（controller.actionCardsPresent）
        assertFalse(
            "在跑时被钉住的轮也不收（双保险，口径一致）",
            roundCollapsed(rounds.last(), running = true, pinnedUserIds = setOf(key)),
        )
    }

    // ── 窗口切片（汇总行的取数口径） ──────────────────────────────────────

    @Test
    fun `轮窗口左闭右开_各轮只取自己的行`() {
        val rows = listOf(
            BaopuStepRow("read_file", ok = true, latencyMs = 1, err = "", createdAt = 1_500),   // 轮1
            BaopuStepRow("list_dir", ok = true, latencyMs = 1, err = "", createdAt = 2_900),   // 轮1
            BaopuStepRow("search_in_files", ok = true, latencyMs = 1, err = "", createdAt = 3_500), // 轮2
        )
        val rounds = groupRounds(j1Messages())
        val r1 = stepsInWindow(rows, rounds[0].startMs, rounds[0].endMs)
        val r2 = stepsInWindow(rows, rounds[1].startMs, rounds[1].endMs)
        assertEquals("轮1 取到 2 行", 2, r1.size)
        assertEquals("轮2 取到 1 行（右开边界挡住下一轮）", 1, r2.size)
        assertEquals(3_500L, r2[0].createdAt)
    }
}
