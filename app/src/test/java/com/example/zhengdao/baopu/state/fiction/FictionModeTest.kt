// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百五十九批 · 题材模式装配行为臂：
// 判据⑥ 默认关 ⇒ 零注入（行数 0、字节 0、token 增量 0）；
// 判据⑦ 关闭后与"没有本单"逐字节一致（拼接串恒为空串，不产生空行/空戳）；
// 即时生效（同一实例拨开关，下一句就变，无缓存）；
// 判据④ 开启时产出行全部带设定戳、可机检区分"设定/事实"。
package com.example.zhengdao.baopu.state.fiction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FictionModeTest {

    private val lock = LockCanon(
        DepthStage.IN_CAVITY, LockPoint.DUAL_PRESS, LockSource.BOTH,
        LockDuration.LONG, ReleaseCondition.SUBSIDED, FirmnessDuringLock.GLANS_BODY_HARDEST,
        FictionCanon.SOURCE_CANON, FictionCanon.CANON_VERSION, FictionCanon.AS_OF_CANON,
    )

    private val topo = DualSuiteTopology(
        listOf(Outlet("urination_ejaculation", "尿道口"), Outlet("vaginal", "阴道口")),
        listOf(
            SupplyClaim("male", "vessel", "前段"), SupplyClaim("female", "vessel", "后段"),
            SupplyClaim("male", "nerve", "上段"), SupplyClaim("female", "nerve", "下段"),
        ),
        HeightBand.UPPER, HeightBand.LOWER,
        FictionCanon.SOURCE_CANON, FictionCanon.CANON_VERSION, FictionCanon.AS_OF_CANON,
    )

    // ── 判据⑥：关 ⇒ 零注入 ─────────────────────────────────────────────────

    @Test
    fun `判据六_开关关_行数零_字节零`() {
        val lines = FictionMode.lines(lock, topo, switchOn = false)
        assertEquals("关着必须零注入", 0, lines.size)
        assertEquals("关着字节增量必须为 0", 0, FictionMode.injectedBytes(lines))
    }

    @Test
    fun `判据七_开关关_拼接结果与没有本单逐字节一致_空串`() {
        val injected = FictionMode.lines(lock, topo, switchOn = false).joinToString("\n")
        assertEquals("关闭后的注入文本必须与基线（一个字都没有）逐字节一致", "", injected)
    }

    // ── 即时生效 ────────────────────────────────────────────────────────────

    @Test
    fun `开关拨开下一句就有_拨回下一句又零`() {
        assertEquals(0, FictionMode.lines(lock, topo, false).size)
        val on = FictionMode.lines(lock, topo, true)
        assertTrue("开着必须出设定行", on.isNotEmpty())
        val offAgain = FictionMode.lines(lock, topo, false)
        assertEquals("拨回后与第一次关闭逐字节一致", "", offAgain.joinToString("\n"))
    }

    // ── 判据④：开 ⇒ 全行带戳、可机检 ───────────────────────────────────────

    @Test
    fun `判据四_开关开_锁结六行加拓扑四行_全部带设定戳`() {
        val lines = FictionMode.lines(lock, topo, true)
        assertEquals(10, lines.size)
        for (l in lines) {
            assertTrue("每一行都必须带设定戳：$l", FictionOutput.isCanonLine(l))
        }
        FictionOutput.assertAllMarked(lines)
    }

    @Test
    fun `判据四_戳里带全_canon_三件套与_reality`() {
        val line = FictionMode.lines(lock, null, true).first()
        assertTrue("戳须含 source=canon：$line", "source=canon" in line)
        assertTrue("戳须含 reality=fiction：$line", "reality=fiction" in line)
        assertTrue("戳须含版本号：$line", "canon v1" in line)
        assertTrue("戳须含时点：$line", "asOf=2026-11-21" in line)
    }

    @Test
    fun `判据四_缺戳行冒充设定_红`() {
        val bare = listOf("事实行：人类无阴茎骨", FictionOutput.canonLine("lockPoint", "冠状沟"))
        assertThrowsCompat(bare)
    }

    private fun assertThrowsCompat(lines: List<String>) {
        val e = runCatching { FictionOutput.assertAllMarked(lines) }.exceptionOrNull()
        assertTrue("缺戳必须被拒，实际通过", e is IllegalArgumentException)
    }

    @Test
    fun `开着但无内容可注入_也是零行`() {
        assertEquals(0, FictionMode.lines(null, null, true).size)
    }

    @Test
    fun `锁结行内容_只出机制与现象_不出步骤体位技巧`() {
        val lines = FictionMode.lines(lock, null, true)
        val joined = lines.joinToString("\n")
        for (banned in listOf("体位", "姿势", "步骤", "技巧", "先", "然后")) {
            assertTrue("输出不许含行为指导字样「$banned」：$joined", banned !in joined)
        }
    }

    @Test
    fun `输出行字段名_与派工单五字段对齐`() {
        val fields = FictionMode.lines(lock, topo, true).map { it.substringAfter("〕 ").substringBefore("=") }
        assertEquals(
            listOf(
                "depthStage", "lockPoint", "lockSource", "lockDuration", "releaseCondition", "firmnessDuringLock",
                "outlets", "supplyClaims", "heightBand.male", "heightBand.female",
            ),
            fields,
        )
    }
}
