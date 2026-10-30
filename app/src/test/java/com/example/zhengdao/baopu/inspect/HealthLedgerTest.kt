// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十一批（第二刀）· 体检账的往返门禁：写进去的必须原样读得回来。
//
// 为什么这本账也要钉：下一趟判"连续两次零产出 ⇒ 该撤掉"**全靠它**——
// 读回来少一个部位、或把空串当成一个态，第二趟就会误判成"上次也这样"。
package com.example.zhengdao.baopu.inspect

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthLedgerTest {

    private val root = File("build/tmp/health291-ledger").apply {
        deleteRecursively()
        mkdirs()
    }

    private fun entry(at: Long, wiring: Int = 7) = HealthEntry(
        id = "health:$at",
        atMs = at,
        windowDays = 30,
        counts = mapOf(
            OrganState.ACTIVE.wire to 1,
            OrganState.NOT_WIRED.wire to wiring,
            HealthRun.COUNT_UNMEASURED to 6,
        ),
        organs = mapOf("金吾卫" to OrganState.NOT_WIRED.wire, "宰相" to ""),
        hits = listOf("blocker:wiring"),
        headline = "体检：$wiring 处没接线、6 处未取样",
        lines = listOf("第一行", "第二行"),
    )

    @Test
    fun `写进去的原样读回来`() {
        assertTrue(HealthLedger.write(root, entry(1_000L)))
        val back = HealthLedger.read(root)
        assertEquals(1, back.size)
        val e = back.first()
        assertEquals("health:1000", e.id)
        assertEquals(1_000L, e.atMs)
        assertEquals(30, e.windowDays)
        assertEquals(7, e.counts[OrganState.NOT_WIRED.wire])
        assertEquals(6, e.counts[HealthRun.COUNT_UNMEASURED])
        assertEquals(OrganState.NOT_WIRED.wire, e.organs["金吾卫"])
        assertEquals("未取样记空串（不是第五态）", "", e.organs["宰相"])
        assertEquals(listOf("blocker:wiring"), e.hits)
        assertEquals(listOf("第一行", "第二行"), e.lines)
    }

    @Test
    fun `读侧新到旧_最近一次取第一条`() {
        HealthLedger.write(root, entry(1_000L))
        HealthLedger.write(root, entry(2_000L))
        assertEquals(listOf(2_000L, 1_000L), HealthLedger.read(root).map { it.atMs })
        assertEquals(2_000L, HealthLedger.lastAt(root))
    }

    @Test
    fun `没账时是第一次_不是上一趟零产出`() {
        val empty = File("build/tmp/health291-ledger-empty").apply {
            deleteRecursively()
            mkdirs()
        }
        assertNull(HealthLedger.lastAt(empty))
        assertTrue("没账 ⇒ 空表（第一次），不许当成'上次也这样'", HealthLedger.previousStates(empty).isEmpty())
    }

    @Test
    fun `上一趟的态能读回来_连续两次判据靠它`() {
        HealthLedger.write(root, entry(1_000L))
        val prev = HealthLedger.previousStates(root)
        assertEquals(OrganState.NOT_WIRED.wire, prev["金吾卫"])
        assertEquals("", prev["宰相"])
    }

    @Test
    fun `坏行跳过_不冒充上一趟`() {
        val f = HealthLedger.fileIn(root)
        f.appendText("{不是 JSON\n", Charsets.UTF_8)
        HealthLedger.write(root, entry(3_000L))
        assertEquals(1, HealthLedger.read(root).size)
        assertEquals(3_000L, HealthLedger.lastAt(root))
    }

    @Test
    fun `空文件不算账`() {
        val dir = File("build/tmp/health291-ledger-blank").apply {
            deleteRecursively()
            mkdirs()
        }
        HealthLedger.fileIn(dir).writeText("", Charsets.UTF_8)
        assertFalse(HealthLedger.read(dir).isNotEmpty())
        assertNull(HealthLedger.lastAt(dir))
    }
}
