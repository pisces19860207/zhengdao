// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第一百七十二批 L4 · 页签快照的**纯判据**（J-L4-1/2/3/8/9/10）。
 *
 * 图纸：`_zd-private/docs/设计-2026-10-24-第一百七十二批-L4页签快照.md` §2.1/§4/§5/§6。
 * 这里全部是 JVM 可跑的表驱动用例；落库与界面那部分在 `BrowserSnapshotGateTest`（源码门禁）。
 */
class BrowserSnapshotPolicyTest {

    private fun row(
        id: Int,
        url: String = "https://example.com/$id",
        lastUsedAt: Long = 1_000L * id,
        scrollY: Float = 0f,
    ) = BrowserSnapshotPolicy.Row(
        id = id,
        url = url,
        title = "t$id",
        scrollX = 0f,
        scrollY = scrollY,
        updatedAt = lastUsedAt,
        lastUsedAt = lastUsedAt,
    )

    // ── J-L4-1 剪枝 ────────────────────────────────────────────────────────

    @Test
    fun `J-L4-1a 空表不删任何东西`() {
        assertTrue(BrowserSnapshotPolicy.pruneIds(emptyList(), now = 1_000_000L).isEmpty())
    }

    @Test
    fun `J-L4-1b 未超量且都在保留期内_一条不删`() {
        val rows = (1..3).map { row(it, lastUsedAt = 999_000L) }
        assertTrue(BrowserSnapshotPolicy.pruneIds(rows, now = 1_000_000L).isEmpty())
    }

    @Test
    fun `J-L4-1c 超过 100 条_只留 LRU 最近的 100`() {
        val rows = (1..105).map { row(it, lastUsedAt = it.toLong()) }
        val gone = BrowserSnapshotPolicy.pruneIds(rows, now = 1_000_000L)
        assertEquals("应删 5 条", 5, gone.size)
        assertEquals("删的是最久没用的那 5 个", setOf(1, 2, 3, 4, 5), gone)
    }

    @Test
    fun `J-L4-1d 超过 7 天_过期全删（不管数量）`() {
        val now = 10L * BrowserSnapshotPolicy.TTL_MS
        val fresh = row(1, lastUsedAt = now - 1_000L)
        val expired = row(2, lastUsedAt = now - BrowserSnapshotPolicy.TTL_MS - 1)
        assertEquals(setOf(2), BrowserSnapshotPolicy.pruneIds(listOf(fresh, expired), now))
    }

    @Test
    fun `J-L4-1e 过期与超量同时发生_两半都生效`() {
        val now = 10L * BrowserSnapshotPolicy.TTL_MS
        val expired = row(1, lastUsedAt = now - BrowserSnapshotPolicy.TTL_MS - 1)
        val alive = (2..104).map { row(it, lastUsedAt = now - 1_000L) }
        val gone = BrowserSnapshotPolicy.pruneIds(listOf(expired) + alive, now, limit = 100)
        assertTrue("过期那条必删", 1 in gone)
        assertEquals("103 条活的留下 100 ⇒ 再删 3 条", 4, gone.size)
    }

    @Test
    fun `J-L4-1f LRU 时间相同_按 id 小的先留（确定性）`() {
        val rows = (1..4).map { row(it, lastUsedAt = 500L) }
        val gone = BrowserSnapshotPolicy.pruneIds(rows, now = 1_000L, limit = 2)
        assertEquals(setOf(3, 4), gone)
    }

    // ── J-L4-2 恢复顺序与活跃标签 ──────────────────────────────────────────

    @Test
    fun `J-L4-2a 恢复顺序按 id 升序_与入参顺序无关`() {
        val rows = listOf(row(3), row(1), row(2))
        assertEquals(listOf(1, 2, 3), BrowserSnapshotPolicy.restoreOrder(rows).map { it.id })
    }

    @Test
    fun `J-L4-2b 活跃标签取 last_used_at 最大`() {
        val rows = listOf(row(1, lastUsedAt = 10), row(2, lastUsedAt = 30), row(3, lastUsedAt = 20))
        assertEquals(2, BrowserSnapshotPolicy.activeRestoredId(rows))
    }

    @Test
    fun `J-L4-2c 时间并列时取 id 小的（确定性）`() {
        assertEquals(4, BrowserSnapshotPolicy.activeRestoredId(listOf(row(5, lastUsedAt = 9), row(4, lastUsedAt = 9))))
    }

    @Test
    fun `J-L4-2d 空表_没有活跃标签`() {
        assertNull(BrowserSnapshotPolicy.activeRestoredId(emptyList()))
    }

    // ── J-L4-3 滚动 clamp ─────────────────────────────────────────────────

    @Test
    fun `J-L4-3a 正常值原样返回`() {
        val (x, y) = BrowserSnapshotPolicy.clampScroll(300f, 50f, 2000f, 2000f, 800f, 400f)
        assertEquals(50f, x, 0.01f)
        assertEquals(300f, y, 0.01f)
    }

    @Test
    fun `J-L4-3b 超过内容高度_夹到最大值`() {
        val (_, y) = BrowserSnapshotPolicy.clampScroll(9999f, 0f, 2000f, 400f, 800f, 400f)
        assertEquals(1200f, y, 0.01f)
    }

    @Test
    fun `J-L4-3c 负数与 NaN 一律归零`() {
        assertEquals(0f, BrowserSnapshotPolicy.clampScroll(-5f, -1f, 2000f, 800f, 800f, 400f).second, 0.01f)
        assertEquals(
            0f,
            BrowserSnapshotPolicy.clampScroll(Float.NaN, Float.NaN, 2000f, 800f, 800f, 400f).let { it.second + it.first },
            0.01f,
        )
    }

    @Test
    fun `J-L4-3d 内容比视口短_两个方向都归零`() {
        val (x, y) = BrowserSnapshotPolicy.clampScroll(500f, 300f, 600f, 300f, 800f, 400f)
        assertEquals(0f, x, 0.01f)
        assertEquals(0f, y, 0.01f)
    }

    @Test
    fun `J-L4-3e 页面不可横滚但库里 x 非 0_不横滚`() {
        val (x, _) = BrowserSnapshotPolicy.clampScroll(100f, 300f, 2000f, 400f, 800f, 400f)
        assertEquals("不可横滚 ⇒ x 必须为 0（否则把人甩到右边）", 0f, x, 0.01f)
    }

    @Test
    fun `J-L4-3f 可横滚但 x 超界_夹到最大值`() {
        val (x, _) = BrowserSnapshotPolicy.clampScroll(0f, 9999f, 2000f, 3000f, 800f, 400f)
        assertEquals(2600f, x, 0.01f)
    }

    @Test
    fun `J-L4-3g 横向值为 0_即使可横滚也不动`() {
        val (x, _) = BrowserSnapshotPolicy.clampScroll(0f, 0f, 2000f, 3000f, 800f, 400f)
        assertEquals(0f, x, 0.01f)
    }

    // ── J-L4-8 落库前的 URL 脱敏 ──────────────────────────────────────────

    @Test
    fun `J-L4-8a 剥掉 token 参数_其余保留`() {
        assertEquals(
            "https://a.com/p?q=hello",
            BrowserSnapshotPolicy.sanitizeUrlForStore("https://a.com/p?q=hello&token=SECRET"),
        )
    }

    @Test
    fun `J-L4-8b sessionid csrf_token api-key 都剥掉`() {
        val out = BrowserSnapshotPolicy.sanitizeUrlForStore(
            "https://a.com/cb?sessionid=1&csrf_token=2&api-key=3&page=2",
        )
        assertEquals("https://a.com/cb?page=2", out)
    }

    @Test
    fun `J-L4-8c 整段 fragment 剥掉（access_token 常藏在里面）`() {
        assertEquals(
            "https://a.com/cb",
            BrowserSnapshotPolicy.sanitizeUrlForStore("https://a.com/cb?code=1#access_token=SECRET"),
        )
        assertEquals(
            "https://a.com/p",
            BrowserSnapshotPolicy.sanitizeUrlForStore("https://a.com/p#access_token=SECRET&x=1"),
        )
    }

    @Test
    fun `J-L4-8h 搜索类参数 keyword 不误伤`() {
        assertEquals(
            "https://a.com/s?keyword=%E5%A4%A9%E6%B0%94",
            BrowserSnapshotPolicy.sanitizeUrlForStore("https://a.com/s?keyword=%E5%A4%A9%E6%B0%94"),
        )
    }

    @Test
    fun `J-L4-8d 普通参数原样保留`() {
        assertEquals(
            "https://a.com/s?q=hello&page=3",
            BrowserSnapshotPolicy.sanitizeUrlForStore("https://a.com/s?q=hello&page=3"),
        )
    }

    @Test
    fun `J-L4-8e 只剥中间那条_前后参数都在`() {
        assertEquals(
            "https://a.com/x?a=1&b=2",
            BrowserSnapshotPolicy.sanitizeUrlForStore("https://a.com/x?a=1&token=zzz&b=2"),
        )
    }

    @Test
    fun `J-L4-8f 没有 query 的地址原样（仅去 fragment）`() {
        assertEquals("https://a.com/p", BrowserSnapshotPolicy.sanitizeUrlForStore("https://a.com/p#frag"))
    }

    @Test
    fun `J-L4-8g 非 http 与空串一律拒存`() {
        assertNull(BrowserSnapshotPolicy.sanitizeUrlForStore("local-preview://a.html"))
        assertNull(BrowserSnapshotPolicy.sanitizeUrlForStore("file:///sdcard/a.html"))
        assertNull(BrowserSnapshotPolicy.sanitizeUrlForStore("   "))
    }

    // ── J-L4-9 恢复后的 id 游标 ───────────────────────────────────────────

    @Test
    fun `J-L4-9a 恢复 7 8 9 之后_nextId 是 10`() {
        assertEquals(10, BrowserSnapshotPolicy.nextIdAfterRestore(listOf(7, 8, 9), currentNextId = 1))
    }

    @Test
    fun `J-L4-9b 空表恢复_nextId 不动`() {
        assertEquals(1, BrowserSnapshotPolicy.nextIdAfterRestore(emptyList(), currentNextId = 1))
        assertEquals(5, BrowserSnapshotPolicy.nextIdAfterRestore(emptyList(), currentNextId = 5))
    }

    @Test
    fun `J-L4-9c 已有更高游标时取两者较大者`() {
        assertEquals(101, BrowserSnapshotPolicy.nextIdAfterRestore(listOf(3), currentNextId = 101))
    }

    // ── J-L4-10 二次 clamp ────────────────────────────────────────────────

    @Test
    fun `J-L4-10a 刚完成_还不到 800ms_不补`() {
        assertFalse(
            BrowserSnapshotPolicy.shouldSecondClamp(
                userScrolled = false, finishedAt = 1_000L, now = 1_500L, alreadyRescheduled = false,
            ),
        )
    }

    @Test
    fun `J-L4-10b 到 800ms 且用户没滚_补一次`() {
        assertTrue(
            BrowserSnapshotPolicy.shouldSecondClamp(
                userScrolled = false, finishedAt = 1_000L, now = 1_900L, alreadyRescheduled = false,
            ),
        )
    }

    @Test
    fun `J-L4-10c 用户已经手动滚过_绝不补（不抢人的手）`() {
        assertFalse(
            BrowserSnapshotPolicy.shouldSecondClamp(
                userScrolled = true, finishedAt = 1_000L, now = 9_999L, alreadyRescheduled = false,
            ),
        )
    }

    @Test
    fun `J-L4-10d 已经补过一次_不再补`() {
        assertFalse(
            BrowserSnapshotPolicy.shouldSecondClamp(
                userScrolled = false, finishedAt = 1_000L, now = 9_999L, alreadyRescheduled = true,
            ),
        )
    }

    @Test
    fun `J-L4-10e 还没完成加载_不补`() {
        assertFalse(
            BrowserSnapshotPolicy.shouldSecondClamp(
                userScrolled = false, finishedAt = null, now = 9_999L, alreadyRescheduled = false,
            ),
        )
    }
}
