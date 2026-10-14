package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P2-16（issue #17）：保活锁「乐观默认」的回归。
 *
 * 改动前（台账原文）：`terminal/SessionService.kt:202`（现行 :253 / :277 两处）
 * `runCatching { wakeLock.isHeld }.getOrDefault(true)` —— isHeld 抛异常时**假装锁还在**：
 * - renewWakeLockIfLost 直接 return ⇒ 锁真丢了也不再补（保活静默失效）；
 * - applyIdlePolicy 喂给策略 held=true ⇒ 活动回升时策略判 NONE 不补取。
 *
 * 本测试钉：保守默认（读不到 ⇒ 当没持有）。补取幂等（锁不 setReferenceCounted），
 * 多补一次无害，漏补才致命。
 */
class SessionServiceWakeLockDefaultTest {

    @Test
    fun `门禁_renewWakeLockIfLost 必须保守默认`() {
        val src = File("src/main/java/com/example/zhengdao/terminal/SessionService.kt").readText()
        val fn = src.substringAfter("private fun renewWakeLockIfLost()")
            .substringBefore("\n    }\n")
        assertTrue(
            "renewWakeLockIfLost 的 isHeld 必须 getOrDefault(false)（P2-16：乐观默认会漏补）",
            fn.contains("runCatching { wakeLock.isHeld }.getOrDefault(false)"),
        )
        assertTrue(
            "且不再出现乐观默认 true（防后人改回去）",
            !fn.contains("getOrDefault(true)"),
        )
    }

    @Test
    fun `门禁_applyIdlePolicy 必须保守默认`() {
        val src = File("src/main/java/com/example/zhengdao/terminal/SessionService.kt").readText()
        val fn = src.substringAfter("private fun applyIdlePolicy()")
            .substringBefore("\n    }\n")
        assertTrue(
            "applyIdlePolicy 的 isHeld 必须 getOrDefault(false)",
            fn.contains("runCatching { wakeLock.isHeld }.getOrDefault(false)"),
        )
        assertTrue("且不再出现乐观默认 true", !fn.contains("getOrDefault(true)"))
    }

    @Test
    fun `策略语义_没持有且活跃时应补取`() {
        // 直接喂策略（不碰 Android 框架）：held=false + 活跃 ⇒ 必须 ACQUIRE。
        // 这条钉的是"保守默认喂进策略后行为正确"——读不到锁状态时补取会被触发。
        val policy = WakeLockIdlePolicy(idleTicksRequired = 2, activeCpuJiffies = 2, streakCpuBudget = 15)
        assertEquals(
            "首轮无基线按活动处理 + 没持有 ⇒ 补取",
            IdleDecision.ACQUIRE,
            policy.onTick(totalCpu = 100, held = false),
        )
    }
}
