// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：262 批第二步的预约式队列与账本（VisualEvidenceQueue）。无 Android 依赖 ⇒ JVM 直测。
package com.example.zhengdao.baopu.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VisualEvidenceQueueTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun cleanQueue() {
        // 单例队列：把上一条用例残留的项全部取走（shoot 不真拍，append 不落盘）。
        VisualEvidenceQueue.drain(
            shoot = { item ->
                VisualEvidenceQueue.Done(item.reason, item.enqueuedAtMs, 0L, null, "test-cleanup")
            },
            append = { true },
        )
    }

    // ── Q1 入队：空理由不收；理由修剪＋截断 ────────────────────────────────

    @Test
    fun `Q1_入队_空理由不收_理由修剪截断`() {
        VisualEvidenceQueue.enqueue("   ")
        VisualEvidenceQueue.enqueue("")
        assertEquals("空白理由不许进队", 0, VisualEvidenceQueue.pending().size)

        VisualEvidenceQueue.enqueue("  需要截图核对价格  ".repeat(30))
        val pending = VisualEvidenceQueue.pending()
        assertEquals(1, pending.size)
        assertEquals("理由要修剪两端空白", "需要截图核对价格", pending[0].reason.take(8))
        assertTrue("理由超长要截断（上限 200）", pending[0].reason.length <= 200)
    }

    // ── Q2 队列上限：塞爆丢最旧 ───────────────────────────────────────────

    @Test
    fun `Q2_塞爆丢最旧`() {
        for (i in 1..VisualEvidenceQueue.MAX_QUEUE + 2) {
            VisualEvidenceQueue.enqueue("项-$i")
        }
        val pending = VisualEvidenceQueue.pending()
        assertEquals("上限就是 ${VisualEvidenceQueue.MAX_QUEUE}", VisualEvidenceQueue.MAX_QUEUE, pending.size)
        assertFalse("最旧的两条被丢", pending.any { it.reason == "项-1" })
        assertFalse(pending.any { it.reason == "项-2" })
        assertTrue("最新的一条必须还在", pending.any { it.reason == "项-${VisualEvidenceQueue.MAX_QUEUE + 2}" })
    }

    // ── Q3 drain：取走全部＋逐条拍＋逐条记账 ───────────────────────────────

    @Test
    fun `Q3_drain_取走全部_逐条拍逐条记`() {
        VisualEvidenceQueue.enqueue("第一条", nowMs = 100L)
        VisualEvidenceQueue.enqueue("第二条", nowMs = 200L)

        val shotItems = mutableListOf<String>()
        val appended = mutableListOf<VisualEvidenceQueue.Done>()
        VisualEvidenceQueue.drain(
            shoot = { item ->
                shotItems += item.reason
                VisualEvidenceQueue.Done(item.reason, item.enqueuedAtMs, 999L, "shots/a.png", null)
            },
            append = { appended += it; true },
        )

        assertEquals(listOf("第一条", "第二条"), shotItems)
        assertEquals("每拍一张记一条账", 2, appended.size)
        assertEquals("账里带补拍的相对路径", "shots/a.png", appended[0].shotRel)
        assertTrue("拍完队列要空", VisualEvidenceQueue.pending().isEmpty())
    }

    @Test
    fun `Q3b_空队列drain是no_op`() {
        var called = 0
        VisualEvidenceQueue.drain(
            shoot = { item ->
                called++
                VisualEvidenceQueue.Done(item.reason, item.enqueuedAtMs, 0L, null, "never")
            },
            append = { true },
        )
        assertEquals("没东西就不许惊动桥", 0, called)
    }

    // ── Q4 单条失败不拖垮其余（runCatching 逐条包） ─────────────────────────

    @Test
    fun `Q4_单条shoot抛异常_其余照常处理`() {
        VisualEvidenceQueue.enqueue("会炸的", nowMs = 1L)
        VisualEvidenceQueue.enqueue("正常的", nowMs = 2L)

        val appended = mutableListOf<String>()
        VisualEvidenceQueue.drain(
            shoot = { item ->
                if (item.reason == "会炸的") throw IllegalStateException("桥坏了")
                appended += item.reason
                VisualEvidenceQueue.Done(item.reason, item.enqueuedAtMs, 3L, null, null)
            },
            append = { done -> appended += "账:" + done.reason; true },
        )

        assertTrue("炸掉的那条不许留到下一轮", VisualEvidenceQueue.pending().isEmpty())
        assertEquals("正常的照拍照记", listOf("正常的", "账:正常的"), appended)
    }

    // ── Q5 账本：jsonl 追加/现读/坏行跳过 ──────────────────────────────────

    @Test
    fun `Q5_账本_追加现读_新在前_坏行跳过`() {
        val root = tmp.newFolder()

        VisualEvidenceQueue.appendDone(
            root,
            VisualEvidenceQueue.Done("旧项", 100L, 500L, "shots/old.png", null),
        )
        VisualEvidenceQueue.appendDone(
            root,
            VisualEvidenceQueue.Done("新项", 200L, 900L, null, "玄览还没打开"),
        )
        // 手写一行坏 JSON 进去：坏行要被跳过，不许把整本账读炸。
        VisualEvidenceQueue.fileIn(root).appendText("这不是json\n", Charsets.UTF_8)

        val entries = VisualEvidenceQueue.read(root)
        assertEquals("坏行跳过后剩两条", 2, entries.size)
        assertEquals("新→旧", "新项", entries[0].reason)
        assertNull("失败的账没有 shot 路径", entries[0].shotRel)
        assertEquals("失败的原因要如实落账", "玄览还没打开", entries[0].error)
        assertEquals("shots/old.png", entries[1].shotRel)
    }

    @Test
    fun `Q5b_没账本时读出空表_不报错`() {
        val entries = VisualEvidenceQueue.read(tmp.newFolder())
        assertTrue(entries.isEmpty())
    }
}
