package com.example.zhengdao.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P2-12（issue #17）：下拉刷新的守卫。
 *
 * 审计原文：「无下拉刷新（全仓 `PullToRefresh` / `SwipeRefresh` 0 命中）」。
 *
 * 落点选择：**怡然（HomeScreen 的 Agent 列表）**——全App唯一"内容会外部变化、
 * 用户会想主动拉一把"的列表（抱朴是对话流、设置是表单，都没有刷新语义）。
 * 已有 2 秒常驻轮询兜底（P1-4 已 IO 化），下拉刷新给的是"立刻"，不是"替代"。
 */
class PullToRefreshGuardTest {

    @Test
    fun `门禁_怡然列表有下拉刷新且刷新动作是立刻重灌`() {
        val src = File("src/main/java/com/example/zhengdao/ui/HomeScreen.kt").readText()
        assertTrue("必须用 material3 的 PullToRefreshBox（1.4 稳定 API）", src.contains("PullToRefreshBox("))
        assertTrue(
            "下拉刷新必须包住 LazyColumn（indicator 与手势才落在列表上）",
            src.indexOf("PullToRefreshBox(") < src.indexOf("LazyColumn("),
        )
        assertTrue(
            "刷新动作必须是 stateTick++（复用 ON_RESUME 同款的立刻重灌机制）",
            src.contains("onRefresh = {") && src.contains("stateTick++"),
        )
        assertTrue("转圈状态要接上（isRefreshing）", src.contains("isRefreshing = ptrRefreshing"))
    }
}
