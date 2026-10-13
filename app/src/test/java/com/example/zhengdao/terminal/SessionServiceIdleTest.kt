// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SessionService 空闲检测策略（[WakeLockIdlePolicy]）与 CPU 读数解析
 * （[GuestCpuCounter.parseCpuJiffies] / [parseUid]）的 JVM 单测。
 *
 * 任务背景（用户 2026-10-13 拍板）：锁要跟「终端有无活动」走，不跟「会话是否存在」走；
 * 判据刻意偏保守——宁可多持锁，不可把跑着的任务误判成空闲。这里的每条用例都对应
 * 一条"保守偏向"或一个状态转换：
 *  - 连续 N 次无活动 → 释放（RELEASE）
 *  - 有活动 → 立即重新获取（ACQUIRE）
 *  - 活动打断空闲计数、读不到/无基线/倒退一律按活动、微量增长累计超预算也算活动
 */
class SessionServiceIdleTest {

    /** 用小参数驱动策略（语义与生产默认值完全一致，只是轮数少、跑得快）。 */
    private fun policy(
        ticks: Int = 5,
        activeJiffies: Long = 2L,
        streakBudget: Long = 15L,
    ) = WakeLockIdlePolicy(
        idleTicksRequired = ticks,
        activeCpuJiffies = activeJiffies,
        streakCpuBudget = streakBudget,
    )

    // ── 释放：连续 N 次无活动 ──────────────────────────────────────────────

    @Test
    fun `连续无活动达到阈值即释放`() {
        val p = policy(ticks = 5)
        var cpu = 1000L
        // 首轮：无基线 ⇒ 保守按"有活动"（NONE，因为锁已在手）
        assertEquals(IdleDecision.NONE, p.onTick(cpu, held = true)); cpu += 1
        // 之后每轮只涨 1 jiffy（< 单轮阈值 2）⇒ 计入空闲
        repeat(4) { assertEquals(IdleDecision.NONE, p.onTick(cpu, held = true)); cpu += 1 }
        // 第 5 次连续空闲 ⇒ 释放
        assertEquals(IdleDecision.RELEASE, p.onTick(cpu, held = true))
        assertTrue(p.isDeliberatelyIdle)
    }

    @Test
    fun `全零增长同样计入空闲并释放`() {
        val p = policy(ticks = 3)
        var cpu = 500L
        assertEquals(IdleDecision.NONE, p.onTick(cpu, held = true))
        repeat(2) { assertEquals(IdleDecision.NONE, p.onTick(cpu, held = true)) } // 增长 0
        assertEquals(IdleDecision.RELEASE, p.onTick(cpu, held = true))
    }

    @Test
    fun `释放后持续空闲保持不动作`() {
        val p = policy(ticks = 2)
        val cpu = 0L
        assertEquals(IdleDecision.NONE, p.onTick(cpu, held = true))  // 首轮：无基线 ⇒ 活动
        assertEquals(IdleDecision.NONE, p.onTick(cpu, held = true))  // 空闲 1 次（差 1 次）
        assertEquals(IdleDecision.RELEASE, p.onTick(cpu, held = true))
        // 已在"刻意空闲"态：继续无活动 ⇒ NONE（不该反复发 RELEASE/ACQUIRE）
        assertEquals(IdleDecision.NONE, p.onTick(cpu, held = false))
        assertEquals(IdleDecision.NONE, p.onTick(cpu, held = false))
        assertTrue(p.isDeliberatelyIdle)
    }

    @Test
    fun `计数达标而锁未持也当轮置空闲标记`() {
        // 锁被外部释放（isHeld 读到 false）且空闲计数已达标 ⇒ 不必再等一轮：
        // 当轮就置 isDeliberatelyIdle，让"补取门控"立即生效（否则 renew 会把外部
        // 已释放的锁又补回来，下一轮才收敛）。
        val p = policy(ticks = 2)
        val cpu = 0L
        assertEquals(IdleDecision.NONE, p.onTick(cpu, held = true))  // 首轮：无基线
        // 空闲 1 次时锁已经不在手（外部释放）⇒ NONE 但未达标
        assertEquals(IdleDecision.NONE, p.onTick(cpu, held = false))
        // 第 2 次空闲：计数达标 ⇒ 标记当轮置位（held=false 只是不发 RELEASE）
        assertEquals(IdleDecision.NONE, p.onTick(cpu, held = false))
        assertTrue(p.isDeliberatelyIdle)
        // 之后活动回升照常立即重取
        assertEquals(IdleDecision.ACQUIRE, p.onTick(cpu + 500, held = false))
        assertFalse(p.isDeliberatelyIdle)
    }

    // ── 恢复：有活动立即重取 ──────────────────────────────────────────────

    @Test
    fun `释放后有活动立即重新获取`() {
        val p = policy(ticks = 2)
        val cpu = 0L
        assertEquals(IdleDecision.NONE, p.onTick(cpu, held = true))  // 首轮：无基线
        assertEquals(IdleDecision.NONE, p.onTick(cpu, held = true))  // 空闲 1 次
        assertEquals(IdleDecision.RELEASE, p.onTick(cpu, held = true))
        // agent 醒了：一轮涨 500 jiffy ⇒ 立即 ACQUIRE，且退出空闲态
        assertEquals(IdleDecision.ACQUIRE, p.onTick(cpu + 500, held = false))
        assertFalse(p.isDeliberatelyIdle)
    }

    @Test
    fun `微量增长超过单轮阈值也算活动`() {
        val p = policy(ticks = 5)
        var cpu = 0L
        assertEquals(IdleDecision.NONE, p.onTick(cpu, held = true)); cpu += 1
        // +2 jiffy = 阈值本身 ⇒ 活动（>= 语义）
        assertEquals(IdleDecision.NONE, p.onTick(cpu + 2, held = true))
        assertFalse(p.isDeliberatelyIdle)
    }

    @Test
    fun `活动打断空闲计数须重新数满`() {
        val p = policy(ticks = 5)
        var cpu = 1000L
        assertEquals(IdleDecision.NONE, p.onTick(cpu, held = true)); cpu += 1
        repeat(3) { assertEquals(IdleDecision.NONE, p.onTick(cpu, held = true)); cpu += 1 } // 空闲 3 次
        // 中途一轮活动 ⇒ 计数清零
        cpu += 100
        assertEquals(IdleDecision.NONE, p.onTick(cpu, held = true))
        // 再数 4 次不够（1 首轮不算 + 阈值 5）⇒ 仍不释放
        repeat(4) { assertEquals(IdleDecision.NONE, p.onTick(cpu, held = true)); cpu += 1 }
        // 第 5 次连续空闲才释放
        assertEquals(IdleDecision.RELEASE, p.onTick(cpu, held = true))
    }

    // ── 保守偏向：读不到 / 无基线 / 倒退 ⇒ 一律当活动 ─────────────────────

    @Test
    fun `读不到CPU读数按活动处理`() {
        val p = policy(ticks = 2)
        assertEquals(IdleDecision.NONE, p.onTick(null, held = true))
        assertEquals(IdleDecision.ACQUIRE, p.onTick(null, held = false))
    }

    @Test
    fun `首轮无基线按活动处理`() {
        val p = policy(ticks = 2)
        // 即便读数看起来"很低"，首轮没有基线可比 ⇒ 不能据此放锁
        assertEquals(IdleDecision.ACQUIRE, p.onTick(3L, held = false))
    }

    @Test
    fun `CPU计数倒退按活动处理`() {
        val p = policy(ticks = 2)
        var cpu = 5000L
        assertEquals(IdleDecision.NONE, p.onTick(cpu, held = true))
        // 倒退（进程树重建 / pid 复用 / 读取口径变化）⇒ 保守按活动
        assertEquals(IdleDecision.ACQUIRE, p.onTick(cpu - 100, held = false))
    }

    // ── 保守偏向：trickle 型任务的累计兜底 ────────────────────────────────

    @Test
    fun `微量增长累计超预算视为活动永不释放`() {
        // 每轮只涨 1 jiffy（< 单轮阈值 2）。预算设为 2 ⇒ 累计到 3（第 3 个空闲轮）就触发
        // "视为活动"清零，空闲计数永远数不满 5 ⇒ 慢速下载这类任务不该被放睡着。
        // （生产参数 20 轮/预算 15 是同一语义：第 16 个空闲轮就打断，20 轮永远数不满。）
        val p = policy(ticks = 5, activeJiffies = 2, streakBudget = 2)
        var cpu = 0L
        assertEquals(IdleDecision.NONE, p.onTick(cpu, held = true)); cpu += 1
        var released = false
        repeat(19) {
            if (p.onTick(cpu, held = true) == IdleDecision.RELEASE) released = true
            cpu += 1
        }
        assertFalse("累计超预算的任务不得被判为空闲", released)
        assertFalse(p.isDeliberatelyIdle)
    }

    @Test
    fun `预算内的微量增长仍可释放`() {
        // 同样是每轮 +1 jiffy，但把预算调大到足够覆盖全部轮次 ⇒ 纯空转可释放
        val p = policy(ticks = 5, activeJiffies = 2, streakBudget = 100)
        var cpu = 0L
        assertEquals(IdleDecision.NONE, p.onTick(cpu, held = true)); cpu += 1
        repeat(4) { assertEquals(IdleDecision.NONE, p.onTick(cpu, held = true)); cpu += 1 }
        assertEquals(IdleDecision.RELEASE, p.onTick(cpu, held = true))
    }

    // ── reset：用户拨保活开关 / 会话消失后重新基线 ─────────────────────────

    @Test
    fun `reset清空空闲态与基线`() {
        val p = policy(ticks = 1)
        var cpu = 0L
        assertEquals(IdleDecision.NONE, p.onTick(cpu, held = true))
        assertEquals(IdleDecision.RELEASE, p.onTick(cpu, held = true))
        assertTrue(p.isDeliberatelyIdle)
        p.reset()
        assertFalse(p.isDeliberatelyIdle)
        // reset 后首轮又是"无基线" ⇒ 按活动处理
        assertEquals(IdleDecision.ACQUIRE, p.onTick(cpu, held = false))
    }

    @Test
    fun `reset后计数从头再数`() {
        val p = policy(ticks = 3)
        var cpu = 0L
        assertEquals(IdleDecision.NONE, p.onTick(cpu, held = true))
        repeat(1) { assertEquals(IdleDecision.NONE, p.onTick(cpu, held = true)) } // 空闲 1 次
        p.reset()
        // 重新数满 3 次（reset 后首轮是无基线活动，再 2 次空闲，第 3 次释放）
        assertEquals(IdleDecision.NONE, p.onTick(cpu, held = true))
        assertEquals(IdleDecision.NONE, p.onTick(cpu, held = true))
        assertEquals(IdleDecision.NONE, p.onTick(cpu, held = true))
        assertEquals(IdleDecision.RELEASE, p.onTick(cpu, held = true))
    }

    // ── /proc/<pid>/stat 解析 ─────────────────────────────────────────────

    @Test
    fun `stat解析出四个CPU字段`() {
        // 真实 stat 行结构：pid (comm) state ppid pgrp session tty_nr tpgid flags
        //                   minflt cminflt majflt cmajflt utime stime cutime cstime ...
        val line = "12345 (tmux: server) S 1 100 200 0 -1 4194560 10 20 30 40 50 60 70 80 90 0"
        val cpu = GuestCpuCounter.parseCpuJiffies(line)
        assertTrue(cpu != null)
        assertEquals(50L, cpu!![0]) // utime
        assertEquals(60L, cpu[1])   // stime
        assertEquals(70L, cpu[2])   // cutime
        assertEquals(80L, cpu[3])   // cstime
    }

    @Test
    fun `comm含右括号时按最后一个括号取界`() {
        // comm 自身可含 ')'（如 "(ba)sh"）——以最后一个 ')' 为界，不能截断在中间
        val line = "99 (ba)sh) R 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15"
        val cpu = GuestCpuCounter.parseCpuJiffies(line)
        assertTrue(cpu != null)
        assertEquals(11L, cpu!![0])
        assertEquals(12L, cpu[1])
        assertEquals(13L, cpu[2])
        assertEquals(14L, cpu[3])
    }

    @Test
    fun `坏输入返回null而不是抛异常`() {
        assertNull(GuestCpuCounter.parseCpuJiffies("12345"))            // 没有 comm 括号
        assertNull(GuestCpuCounter.parseCpuJiffies("1 (x) S 1 2"))      // 字段不够
        assertNull(GuestCpuCounter.parseCpuJiffies("1 (x) S a b c d e f g h i j k l m")) // 非数字
    }

    @Test
    fun `Uid行解析`() {
        assertEquals(10123, GuestCpuCounter.parseUid("Uid:\t10123\t10123\t10123\t10123"))
        assertEquals(0, GuestCpuCounter.parseUid("Uid: 0 0 0 0"))
        assertNull(GuestCpuCounter.parseUid("VmRSS:\t 123456 kB"))
        assertNull(GuestCpuCounter.parseUid("Uid:\tabc\t1"))
    }
}
