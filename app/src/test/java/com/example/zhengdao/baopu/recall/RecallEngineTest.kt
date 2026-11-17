// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.recall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 540 编排与提炼判据（纯函数直测，假端口计数）。
 *
 * 派工单【判据】逐条落在这里：检索次数 ≤3（计数器断言）／要点 ≤5 每条 ≤60 字／
 * 要点缺消息 id 就丢弃／找不到直说（负例：编造就红）。
 */
class RecallEngineTest {

    /** 计数假端口：每次检索记一笔，按脚本回原文。 */
    private class FakePort(private val script: (List<String>) -> List<RecallHit>) : RecallSearchPort {
        val calls = mutableListOf<List<String>>()
        override fun search(keywords: List<String>): List<RecallHit> {
            calls += keywords
            return script(keywords)
        }
    }

    private fun hit(id: Long, sid: Long = 7L, at: Long = 1_000L, text: String = "部署用 8080 端口") =
        RecallHit(sessionId = sid, messageId = id, atMs = at, text = text)

    // ── 检索次数 ≤3（计数器断言） ─────────────────────────────────────────

    @Test
    fun `计数_线索组超过三组只检索三次`() {
        val groups = listOf(listOf("一"), listOf("二"), listOf("三"), listOf("四"), listOf("五"))
        val port = FakePort { emptyList() }
        val outcome = RecallEngine.recall(groups, port)
        assertEquals("检索次数硬顶 3", 3, outcome.searchCalls)
        assertEquals("假端口真的只被调了 3 次", 3, port.calls.size)
        assertEquals("只取前三组，丢序固定", listOf(listOf("一"), listOf("二"), listOf("三")), port.calls)
    }

    @Test
    fun `计数_凑够五条要点就停_后面的组不花次数`() {
        val groups = listOf(listOf("一"), listOf("二"), listOf("三"))
        val port = FakePort { kw -> if (kw == listOf("一")) (1L..5L).map { hit(it) } else emptyList() }
        val outcome = RecallEngine.recall(groups, port)
        assertEquals(1, outcome.searchCalls)
        assertEquals(5, outcome.points.size)
    }

    @Test
    fun `计数_空组不算检索`() {
        val groups = listOf(emptyList(), listOf("有"))
        val port = FakePort { emptyList() }
        val outcome = RecallEngine.recall(groups, port)
        assertEquals(1, outcome.searchCalls)
    }

    @Test
    fun `分组_三路线索按优先级取前三`() {
        val groups = RecallEngine.clueGroups(listOf("部署"), listOf("端口"), listOf("配置"))
        assertEquals(listOf(listOf("部署"), listOf("端口"), listOf("配置")), groups)
        assertEquals("组数账等于进编排的组数", 3, groups.size)
    }

    @Test
    fun `分组_空路不占位`() {
        assertEquals(listOf(listOf("部署")), RecallEngine.clueGroups(emptyList(), listOf("部署"), emptyList()))
    }

    // ── 要点：≤5 条、每条 ≤60 字、必带消息 id ─────────────────────────────

    @Test
    fun `要点_最多五条`() {
        val port = FakePort { (1L..9L).map { hit(it, at = it * 10) } }
        val outcome = RecallEngine.recall(listOf(listOf("端口")), port)
        assertEquals(5, outcome.points.size)
        assertTrue(outcome.found)
    }

    @Test
    fun `要点_每条都带消息id与原文文本`() {
        val port = FakePort { listOf(hit(42L, sid = 7L, text = "部署用 8080 端口")) }
        val p = RecallEngine.recall(listOf(listOf("端口")), port).points.single()
        assertEquals(42L, p.messageId)
        assertEquals(7L, p.sessionId)
        assertEquals("部署用 8080 端口", p.text)
    }

    @Test
    fun `要点_缺消息id就丢弃_一个都不许进卡`() {
        val port = FakePort {
            listOf(
                hit(0L, text = "没有 id 的话一"),
                hit(0L, text = "没有 id 的话二"),
                hit(11L, text = "有 id 的这条"),
            )
        }
        val outcome = RecallEngine.recall(listOf(listOf("端口")), port)
        assertEquals("缺 id 的两条被丢弃，只剩有 id 那条", 1, outcome.points.size)
        assertEquals(11L, outcome.points.single().messageId)
    }

    @Test
    fun `要点_缺会话id或空正文也丢弃`() {
        val port = FakePort {
            listOf(hit(1L, sid = 0L), hit(2L, text = "   "), hit(3L, text = "正经话"))
        }
        val outcome = RecallEngine.recall(listOf(listOf("话")), port)
        assertEquals(1, outcome.points.size)
        assertEquals(3L, outcome.points.single().messageId)
    }

    @Test
    fun `要点_同一消息只出一条_重复命中去重`() {
        val port = FakePort { kw ->
            if (kw == listOf("一")) listOf(hit(9L, at = 5L)) else listOf(hit(9L, at = 9L), hit(8L))
        }
        val outcome = RecallEngine.recall(listOf(listOf("一"), listOf("二")), port)
        assertEquals("两个组都命中消息 9 ⇒ 只算一条", 2, outcome.points.size)
        assertEquals("去重保留先到的组（更贴本句）", 9L, outcome.points.first().messageId)
    }

    // ── 排序（确定性） ────────────────────────────────────────────────────

    @Test
    fun `排序_组序在前_组内新话在前`() {
        val port = FakePort { kw ->
            if (kw == listOf("一")) {
                listOf(hit(1L, at = 100L), hit(2L, at = 300L), hit(3L, at = 200L))
            } else {
                listOf(hit(4L, at = 999L))
            }
        }
        val points = RecallEngine.recall(listOf(listOf("一"), listOf("二")), port).points
        assertEquals("组1新→旧，然后组2", listOf(2L, 3L, 1L, 4L), points.map { it.messageId })
    }

    @Test
    fun `确定性_同输入同输出`() {
        val port = FakePort { listOf(hit(1L, at = 100L), hit(2L, at = 50L)) }
        val a = RecallEngine.recall(listOf(listOf("端口")), port)
        val b = RecallEngine.recall(listOf(listOf("端口")), port)
        assertEquals(a.points, b.points)
        assertEquals(a.searchCalls, b.searchCalls)
    }

    // ── 每条 ≤60 字 ───────────────────────────────────────────────────────

    @Test
    fun `正文_短句原样_折行折叠`() {
        assertEquals("一 二", RecallEngine.pointTextOf("一\n  二", listOf("二")))
    }

    @Test
    fun `正文_超长折窗_窗口套住命中词且不超上限`() {
        val long = "前".repeat(100) + "部署" + "后".repeat(100)
        val point = RecallEngine.pointTextOf(long, listOf("部署"))
        assertTrue("≤60 字", point.length <= 60)
        assertTrue("窗口套住命中词", point.contains("部署"))
        assertTrue("窗不起于行首 ⇒ 头上补省略号", point.startsWith("…"))
    }

    @Test
    fun `正文_命中词在行首_不补省略号`() {
        val long = "部署" + "后".repeat(100)
        val point = RecallEngine.pointTextOf(long, listOf("部署"))
        assertTrue(point.length <= 60)
        assertFalse(point.startsWith("…"))
    }

    @Test
    fun `要点_经引擎后每条长度都不超60`() {
        val port = FakePort { (1L..5L).map { hit(it, text = "长".repeat(120) + "端口" + "长".repeat(120)) } }
        val outcome = RecallEngine.recall(listOf(listOf("端口")), port)
        outcome.points.forEach { p -> assertTrue("要点 ≤60 字", p.text.length <= 60) }
    }

    // ── 找不到直说（负例：编造就红） ───────────────────────────────────────

    @Test
    fun `没找到_零命中时直说_零要点不编`() {
        val port = FakePort { emptyList() }
        val outcome = RecallEngine.recall(listOf(listOf("部署"), listOf("端口")), port)
        assertFalse(outcome.found)
        assertEquals("要点一条都没有", 0, outcome.points.size)
        assertEquals("口径就是固定那句，不许带任何编出来的内容", "没找到相关的旧消息", outcome.notFoundText)
        assertEquals("没找到也把次数账记真", 2, outcome.searchCalls)
    }

    @Test
    fun `没找到_命中全被丢id也算没找到`() {
        val port = FakePort { listOf(hit(0L)) }
        val outcome = RecallEngine.recall(listOf(listOf("部署")), port)
        assertFalse(outcome.found)
        assertEquals("没找到相关的旧消息", outcome.notFoundText)
    }

    @Test
    fun `找到了_notFoundText必为空`() {
        val port = FakePort { listOf(hit(1L)) }
        val outcome = RecallEngine.recall(listOf(listOf("部署")), port)
        assertTrue(outcome.found)
        assertNull(outcome.notFoundText)
    }

    @Test
    fun `没找到_引擎里没有任何编造路径`() {
        val port = FakePort { emptyList() }
        val outcome = RecallEngine.recall(listOf(listOf("部署")), port)
        val rendered = outcome.notFoundText.orEmpty()
        assertFalse("固定口径里不许混进任何命中原文片段", rendered.contains("部署"))
    }
}
