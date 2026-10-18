// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 刀 4 §2.1 —— [PreviewSlots] 的判据（派工单 **J1** 全在这）。
 *
 * 这块是**纯逻辑**：不碰 socket、不碰盘，所以整份判据能在纯 JVM 里逐条钉死
 * （项目房型：审计类缺陷修成「纯函数 ＋ 表驱动单测」）。端口的真起真连归
 * [PreviewServerTest]（J3/J4/J5）。
 *
 * 槽是进程级单例，JUnit 同一 JVM 跑多个类会串味 ⇒ 每个用例开头 [resetForTests]。
 */
class PreviewSlotsTest {

    @Before
    fun reset() {
        PreviewSlots.resetForTests()
    }

    /** 喂一个假端口：J1 不需要真 socket，url 只验拼装口径。 */
    private fun bindFakeServer(port: Int = 7000) {
        PreviewSlots.bind("http://127.0.0.1:$port")
    }

    private fun placed(html: String): PreviewSlots.PutResult.Placed {
        val r = PreviewSlots.put(html)
        assertTrue("put 应成功，实际失败：${(r as? PreviewSlots.PutResult.Failed)?.reason}", r is PreviewSlots.PutResult.Placed)
        return r as PreviewSlots.PutResult.Placed
    }

    // ── J1：上限 8 与 LRU ──────────────────────────────────────────────────

    @Test
    fun `J1_放九个_第一个被淘汰_取不到_stats 报一次淘汰`() {
        bindFakeServer()
        val ids = (1..9).map { placed("<p>卡 $it</p>").id }
        val stats = PreviewSlots.stats()
        assertEquals("槽上限必须钉死 8", 8, stats.capacity)
        assertEquals("放了 9 个只能留 8 个", 8, stats.used)
        assertEquals("J1：累计淘汰必须恰为 1", 1, stats.evicted)
        assertNull("J1：第 1 个必须被淘汰（取不到＝url 变 404）", PreviewSlots.get(ids.first()))
        assertNotNull("第 9 个必须在", PreviewSlots.get(ids.last()))
    }

    @Test
    fun `J1_LRU 不是 FIFO_反复看的那张不被淘汰`() {
        bindFakeServer()
        val a = placed("<p>A</p>").id
        val b = placed("<p>B</p>").id
        // 反复访问 A（get 与 touch 都算访问：accessOrder=true 的 map 里读也是写）
        repeat(3) { assertNotNull(PreviewSlots.get(a)) }
        assertTrue(PreviewSlots.touch(a))
        (3..8).forEach { placed("<p>另 $it</p>") }
        // 槽满 8 个 ⇒ 再放一个，淘汰的必须是"最久没被看"的 B，而不是"最早放进"的 A
        val evicted = placed("<p>第 9 张</p>")
        assertEquals("LRU 淘汰必须挑最久未访问的那个（不是 FIFO 的队头）", b, evicted.evicted)
        assertNotNull("A 被反复看过，不许被淘汰", PreviewSlots.get(a))
        assertNull("B 是最久未访问的，必须被淘汰", PreviewSlots.get(b))
        assertEquals("被淘汰的 id 回执里要带出来", b, evicted.evicted)
    }

    @Test
    fun `J1_淘汰数逐次累计_第九次淘汰有读数`() {
        bindFakeServer()
        repeat(8) { placed("<p>$it</p>") }
        assertEquals(0, PreviewSlots.stats().evicted)
        repeat(3) { placed("<p>补 $it</p>") }
        assertEquals("再放 3 个应累计淘汰 3 次", 3, PreviewSlots.stats().evicted)
        assertEquals(8, PreviewSlots.stats().used)
    }

    // ── 契约细节 ────────────────────────────────────────────────────────────

    @Test
    fun `url 拼装口径_http 环回加 p 加 uuid_两次不同`() {
        bindFakeServer(7100)
        val one = placed("<p>一</p>")
        val two = placed("<p>二</p>")
        val uuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        assertTrue("uuid 必须是 randomUUID 形态（不可猜）：${one.id}", uuidPattern.matches(one.id))
        assertNotEqualsSame(one.id, two.id)
        assertEquals("http://127.0.0.1:7100/p/${one.id}", one.url)
        assertFalse("url 里不许漏进正文", one.url.contains("一"))
    }

    @Test
    fun `没绑定端口_put 明确失败并带上原因`() {
        // resetForTests 已把绑定清掉 ⇒ 服务没起来
        val r = PreviewSlots.put("<p>卡</p>")
        assertTrue("没起服务不许假装成功", r is PreviewSlots.PutResult.Failed)
        val reason = (r as PreviewSlots.PutResult.Failed).reason
        assertTrue("原因要说清该先调谁：$reason", reason.contains("PreviewServer.start"))
        assertEquals("失败时一个槽都不许占", 0, PreviewSlots.stats().used)
    }

    @Test
    fun `release 之后_put 回的是 release 给的原因`() {
        bindFakeServer()
        PreviewSlots.release("预览服务起不来：十个候选端口全部被占")
        val r = PreviewSlots.put("<p>卡</p>")
        assertTrue(r is PreviewSlots.PutResult.Failed)
        assertTrue((r as PreviewSlots.PutResult.Failed).reason.contains("全部被占"))
    }

    @Test
    fun `remove 与 clear 的读数口径`() {
        bindFakeServer()
        val a = placed("<p>A</p>").id
        val b = placed("<p>B</p>").id
        assertTrue(PreviewSlots.remove(a))
        assertFalse("删两遍第二遍必须报没删到", PreviewSlots.remove(a))
        assertEquals(1, PreviewSlots.stats().used)
        // 手动撤掉一份不算淘汰（evicted 只统计"满槽挤人"）
        assertEquals(0, PreviewSlots.stats().evicted)
        PreviewSlots.clear()
        assertEquals(0, PreviewSlots.stats().used)
        assertEquals("clear 只清槽位，不动历史累计淘汰数", 0, PreviewSlots.stats().evicted)
        assertFalse(PreviewSlots.touch(b))
    }

    @Test
    fun `assets 逐份复制_外部改不动已放进去的预览`() {
        bindFakeServer()
        val css = byteArrayOf('a'.code.toByte(), 'b'.code.toByte())
        val placed = PreviewSlots.put("<p>卡</p>", mapOf("app.css" to css))
        assertTrue(placed is PreviewSlots.PutResult.Placed)
        val id = (placed as PreviewSlots.PutResult.Placed).id
        css[0] = 'Z'.code.toByte()
        val served = PreviewSlots.get(id)?.assets?.get("app.css")
        assertNotNull("子资源必须按放入时的字节留着", served)
        assertEquals("外部数组改了不许影响槽内内容（防「预览到的卡被人回写」）", 2, served!!.size)
        assertEquals('a'.code.toByte(), served[0])
    }

    @Test
    fun `并发 put 不越上限`() {
        bindFakeServer()
        val n = 32
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(n)
        repeat(n) { i -> pool.execute { start.await(2, TimeUnit.SECONDS); placed("<p>$i</p>"); done.countDown() } }
        start.countDown()
        assertTrue("并发 put 10 秒收不完——死锁了？", done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
        val stats = PreviewSlots.stats()
        assertEquals("并发下也不许越过上限（8）", 8, stats.used)
        assertEquals("32 次 put 应淘汰 24 次", 24, stats.evicted)
    }

    private fun assertNotEqualsSame(a: String, b: String) {
        assertFalse("两次 put 的 uuid 不许相同", a == b)
    }
}
