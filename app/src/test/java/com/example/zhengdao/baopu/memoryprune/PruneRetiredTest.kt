// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.memoryprune

import com.example.zhengdao.baopu.MemoryEntry
import com.example.zhengdao.baopu.MemoryKind
import com.example.zhengdao.baopu.MemoryQuality
import com.example.zhengdao.baopu.MemoryScope
import com.example.zhengdao.baopu.MemoryStatus
import com.example.zhengdao.baopu.safety.DiaryEvent
import com.example.zhengdao.baopu.safety.DiaryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第七百五十八批 · 已退役读数投影的用例（757 判据②）：
 * 入口行 `已退役 N 条` 的 N 扫**当前库**（账 5 条但库 2 条必须显示 2——反例靶）；
 * 列表三列逐列对拍（标题＝条目原文、退役时间＝对应账行 atMs、原因按 FLAG_MERGED 二选一）；
 * 时间缺失 ⇒ null（界面渲染「时间未知」，不许拿当前时间补）。
 */
class PruneRetiredTest {

    private companion object {
        const val NOW = 1_770_000_000_000L
    }

    private fun entry(
        id: String,
        status: MemoryStatus = MemoryStatus.RETIRED,
        merged: Boolean = false,
    ) = MemoryEntry(
        id = id,
        scope = MemoryScope.PROJECT,
        kind = MemoryKind.FACT,
        title = "标题$id",
        body = "正文$id",
        sourceSession = "s1",
        status = status,
        createdAt = NOW - 40L * 86_400_000L,
        updatedAt = NOW - 40L * 86_400_000L,
        useCount = 0,
        asOf = NOW - 40L * 86_400_000L,
        qualityFlags = listOf("RETIRED") + if (merged) listOf(MemoryQuality.FLAG_MERGED) else emptyList(),
    )

    /** 账行（752 PruneLedger 行形态：直接退役行/合并行）。 */
    private fun retireRow(id: String, atMs: Long) = DiaryEvent(
        kind = DiaryKind.ACTION,
        atMs = atMs,
        why = "${PruneLedger.WHY}·退役·$id",
        what = "退役一条",
        result = "id=$id",
    )

    private fun mergedRow(survivorId: String, allIds: List<String>, atMs: Long) = DiaryEvent(
        kind = DiaryKind.ACTION,
        atMs = atMs,
        why = "${PruneLedger.WHY}·合并·$survivorId",
        what = "合并 ${allIds.size - 1} 条为 1 条",
        result = "指针全集：${allIds.joinToString(",")}（merged_from 全保留，原件只标退役未删）",
    )

    // ── 判据②：入口行计数（当前库，不是账行累计） ─────────────────────────────

    @Test
    fun 判据2_账5条但库2条_必须显示2() {
        val entries = listOf(entry("r1"), entry("r2"), entry("a1", status = MemoryStatus.CONFIRMED))
        val ledger = listOf(
            retireRow("r1", NOW), retireRow("r2", NOW),
            retireRow("r3", NOW), retireRow("r4", NOW), retireRow("r5", NOW), // 账 5 条退役历史
        )
        assertEquals("库 2 条", 2, retiredCountOf(entries))
        assertEquals("入口必须显示 2（防拿账行计数冒充当前条数）", "已退役 2 条", retiredEntryTextOf(retiredCountOf(entries)))
    }

    @Test
    fun 判据2_恢复一条显示少一条_全恢复入口消失() {
        val three = listOf(entry("r1"), entry("r2"), entry("r3"))
        assertEquals("已退役 3 条", retiredEntryTextOf(retiredCountOf(three)))
        val two = listOf(entry("r1"), entry("r2"), entry("r3", status = MemoryStatus.CONFIRMED))
        assertEquals("恢复 1 条 ⇒ 已退役 2 条", "已退役 2 条", retiredEntryTextOf(retiredCountOf(two)))
        assertNull("全恢复 ⇒ 入口行消失（投影返回 null）", retiredEntryTextOf(retiredCountOf(emptyList())))
        assertNull("负数不该出现", retiredEntryTextOf(-1))
    }

    @Test
    fun 判据2_负例_账里全是退役行_库里没有_入口消失() {
        val ledger = listOf(retireRow("r1", NOW), retireRow("r2", NOW), retireRow("r3", NOW))
        assertEquals(0, retiredCountOf(emptyList()))
        assertNull("库里没有 RETIRED ⇒ null，不拿账行冒充", retiredEntryTextOf(retiredCountOf(emptyList())))
        assertTrue("账行确实有 3 条（前提自证）", retireTimesOf(ledger).size == 3)
    }

    // ── 判据②：列表三列逐列对拍 ─────────────────────────────────────────────

    @Test
    fun 判据2_三列对拍_标题账面时刻与原因() {
        val entries = listOf(entry("r1"), entry("r2", merged = true), entry("live", status = MemoryStatus.CONFIRMED))
        val ledger = listOf(
            retireRow("r1", 1_000L),
            mergedRow("live", listOf("live", "r2"), 2_000L),
        )
        val rows = retiredRowsOf(entries, ledger)
        assertEquals("只投影 RETIRED 条目", listOf("r1", "r2"), rows.map { it.id })
        val r1 = rows.first { it.id == "r1" }
        assertEquals("列 1＝条目原文", "标题r1", r1.title)
        assertEquals("列 2＝账行 atMs", 1_000L, r1.retiredAtMs)
        assertFalse("列 3＝超期未引用（无 FLAG_MERGED）", r1.merged)
        val r2 = rows.first { it.id == "r2" }
        assertTrue("被合并吸收 ⇒ 原因＝冗余合并吸收", r2.merged)
        assertEquals("合并吸收的时间取合并行（指针全集含该 id）", 2_000L, r2.retiredAtMs)
        assertEquals("正文原样（详情展开用）", "正文r2", r2.body)
    }

    @Test
    fun 判据2_时间缺失_投影null_不拿当前时间补() {
        val rows = retiredRowsOf(listOf(entry("rx")), emptyList())
        assertNull("账里没有该 id 的任何行 ⇒ retiredAtMs 为 null", rows.single().retiredAtMs)
    }

    @Test
    fun 判据2_同一id多条账行_取最近一条() {
        val ledger = listOf(retireRow("r1", 1_000L), retireRow("r1", 9_000L), retireRow("r1", 5_000L))
        assertEquals("最近一次退役时刻", 9_000L, retireTimesOf(ledger)["r1"])
    }

    // ── 合并行指针全集解析（闭集口径；解析不出 ⇒ 空表） ─────────────────────────

    @Test
    fun 指针全集解析_标准形态与缺尾形态() {
        val standard = "指针全集：a,b（merged_from 全保留，原件只标退役未删）"
        assertEquals(listOf("a", "b"), mergedPointerIds(standard))
        val noTail = "指针全集：a,b"
        assertEquals(listOf("a", "b"), mergedPointerIds(noTail))
        assertEquals("没有 key ⇒ 空表", emptyList<String>(), mergedPointerIds("id=x；文件原地未动"))
        assertEquals("空段过滤", listOf("a"), mergedPointerIds("指针全集：a,"))
    }
}
