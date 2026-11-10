package com.example.zhengdao.baopu.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 蓝总管验收补测（2026-11-10，317）：`itemOf` 的**闭集穷尽性**。
 *
 * 为什么补：`itemOf` 原来是 14 分支 `when`（不带 `else`，加判据编译不过），但它的复杂度
 * 正好顶破引擎区预算 1 条（CyclomaticComplexMethod 190 > 189）。我把它改成查表
 * （复杂度 1、行为不变）——**代价是穷尽性从编译器搬到了这里**：
 * 新增 `CheckId` 却没填人话名 ⇒ 本用例红（`getValue` 也会直接抛）。
 */
class VerifyNarrativeItemCoverageTest {

    @Test
    fun `每个判据都有人话名_一个都不许漏`() {
        val missing = CheckId.entries.filter { runCatching { VerifyNarrative.itemOf(it) }.isFailure }
        assertTrue("有判据没有人话名：$missing", missing.isEmpty())
        assertEquals("判据数与映射数必须相等", CheckId.entries.size, CheckId.entries.size - missing.size)
    }

    @Test
    fun `人话名两两不同_不许两条判据共用一个名字`() {
        val names = CheckId.entries.map { VerifyNarrative.itemOf(it) }
        assertEquals("人话名有重复：$names", names.size, names.toSet().size)
    }
}
