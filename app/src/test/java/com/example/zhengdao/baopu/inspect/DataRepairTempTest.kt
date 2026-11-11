// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百零八批 · 类③ 缓存/临时残留的**四段用例**。
//
// 判据是**合取**：带前缀 ∧ 超 7 天，缺一个都不许动。三个对照件（超龄带前缀／新件／
// 超龄但无前缀）摆在一起，才能证明"只清该清的那一个"，而不是"清了一堆"。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.TempScratch
import com.example.zhengdao.baopu.repair.DataDoctor
import com.example.zhengdao.baopu.repair.RepairLedger
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataRepairTempTest {

    private val ws = File("build/tmp/repair408-temp").absoluteFile.apply {
        deleteRecursively()
        mkdirs()
    }

    private val now = 1_790_000_000_000L

    private val day = 86_400_000L

    /** 造问题：在 `临时/` 下放一个文件，并把 mtime 拨到 [ageDays] 天前。 */
    private fun scratch(name: String, ageDays: Long): File {
        val f = File(File(ws, TempScratch.DIR), name)
        f.parentFile?.mkdirs()
        f.writeText("x", Charsets.UTF_8)
        f.setLastModified(now - ageDays * day)
        return f
    }

    @Test
    fun `只清带前缀且超七天_计划外一个都不动`() {
        val stale = scratch("zd-旧.bin", 8)
        val fresh = scratch("zd-新.bin", 0)
        val userThing = scratch("用户的东西.txt", 8)

        // 体检发现：候选只有一个
        val plan = DataDoctor.diagnoseTemp(ws, setOf("zd-"), now)
        assertEquals(RepairVerdict.FIXABLE, plan.verdict)
        assertEquals("带前缀∧超龄，只有一个", listOf("临时/zd-旧.bin"), plan.targets)

        // 自修
        val out = DataDoctor.repairTemp(ws, setOf("zd-"), now)
        assertTrue("该修成：${out.note}", out.done)

        assertFalse("超龄且带前缀 ⇒ 搬走", stale.exists())
        assertTrue("没超龄 ⇒ 留着（可能正在用）", fresh.exists())
        assertTrue("没前缀 ⇒ 一个字节都不动（可能是用户自己的东西）", userThing.exists())
        val trash = File(ws, DataDoctor.TRASH_DIR)
        assertTrue(
            "要搬进回收站而不是真删（可恢复）",
            trash.listFiles()?.any { it.name.endsWith("zd-旧.bin") } == true,
        )

        // 再体检变好
        assertEquals(RepairVerdict.NOTHING, DataDoctor.diagnoseTemp(ws, setOf("zd-"), now).verdict)
    }

    @Test
    fun `没有前缀判据_需人处理_不许猜也不许动`() {
        val stale = scratch("zd-旧.bin", 8)

        val plan = DataDoctor.diagnoseTemp(ws, emptySet(), now)
        assertEquals("判不了 ≠ 不用修", RepairVerdict.NEED_HUMAN, plan.verdict)

        val out = DataDoctor.repairTemp(ws, emptySet(), now)
        assertFalse(out.done)
        assertTrue("判不了就不许动", stale.exists())
        assertFalse("需人处理不该留 intent 账", RepairLedger.fileIn(ws).exists())
    }

    @Test
    fun `mtime读不出不算超龄_不许当1970年的老文件`() {
        val plan = DataRepair.tempPlan(
            items = listOf(TempItem("临时/zd-x.bin", 0L)),
            prefixes = setOf("zd-"),
            nowMs = now,
            evidence = "diagnose:test:temp",
        )
        assertEquals("测不出来 ≠ 超龄（同「绝不拿 0 顶替」）", RepairVerdict.NOTHING, plan.verdict)
    }

    @Test
    fun `正好七天不算超_多一毫秒才算`() {
        val exactly = TempItem("临时/zd-x.bin", now - 7 * day)
        assertFalse("正好 7 天不算「超」", DataRepair.isStale(exactly, setOf("zd-"), now))
        assertTrue(
            "超一毫秒就算",
            DataRepair.isStale(exactly.copy(lastModifiedMs = now - 7 * day - 1), setOf("zd-"), now),
        )
    }

    @Test
    fun `前缀只看文件名_目录名带前缀不算`() {
        assertFalse(
            "`临时/zd-稿/说明.txt` 的文件名没有前缀",
            DataRepair.hasPrefix("临时/zd-稿/说明.txt", setOf("zd-")),
        )
        assertTrue(DataRepair.hasPrefix("临时/zd-稿.bin", setOf("zd-")))
    }

    /** cleanup-b571（2026-11-11）：本文件在 `build/tmp/` 下建的目录跑完必须删——
     *  它们是测试暂存区（564 的水位闸按这些目录计）；此前只建不删，水位只能一路涨。 */
    @After
    fun cleanTmpDirs571() {
        java.io.File("build/tmp/repair408-temp").deleteRecursively()
    }

}
