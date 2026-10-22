// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第一百三十一批 · 模型选择 P0》②「多 Key 轮询（用户自管）」——
// 同一 provider 可填多把 Key；429/403/额度耗尽 ⇒ 切下一把；全部不可用 ⇒ 可读提示不静默重试；
// 冷却/顺序/禁用单把的策略要写清；不自动创建或购买 Key、不内置任何 Key。
//
// 这里测的是 [KeyPool] 这层**纯函数**（不碰 SharedPreferences ⇒ 无 Robolectric 也能全跑）。
// [ProviderPrefs] 那层薄壳（读 SP / 写 SP / 拿系统时间）在本仓**结构上不可 JVM 测**，
// 所以判据一律下沉到这里，薄壳只留"搬字节"的活；这条分工见 ProviderPrefs 的文件头。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyPoolTest {

    /** 任意基准时刻：所有判据都吃注入的 now，不读系统钟 ⇒ 结果与跑测时间无关。 */
    private val t0 = 1_700_000_000_000L

    private fun pool(vararg keys: String) = keys.map { KeyPool.Entry(it) }

    // ── 1. 这次失败是不是这把 Key 的锅 ──────────────────────────────────────

    @Test
    fun 状态码分类_四档各归各的() {
        assertEquals(KeyPool.Outcome.RATE_LIMITED, KeyPool.classify("HTTP 429 Too Many Requests"))
        assertEquals(KeyPool.Outcome.QUOTA_OR_FORBIDDEN, KeyPool.classify("HTTP 403 forbidden"))
        assertEquals(KeyPool.Outcome.KEY_REJECTED, KeyPool.classify("HTTP 401 Unauthorized"))
        assertEquals(KeyPool.Outcome.NOT_ABOUT_KEY, KeyPool.classify("java.net.SocketTimeoutException: timeout"))
        assertEquals(KeyPool.Outcome.NOT_ABOUT_KEY, KeyPool.classify("HTTP 503 upstream busy"))
    }

    @Test
    fun 文字形态的限流与额度也算() {
        // OpenRouter/DeepSeek 的 429 正文常见措辞；没有裸状态码时靠关键词兜住
        assertEquals(KeyPool.Outcome.RATE_LIMITED, KeyPool.classify("Rate limit reached for this key"))
        assertEquals(KeyPool.Outcome.QUOTA_OR_FORBIDDEN, KeyPool.classify("insufficient_quota"))
        assertEquals(KeyPool.Outcome.QUOTA_OR_FORBIDDEN, KeyPool.classify("Credits exhausted"))
        assertEquals(KeyPool.Outcome.KEY_REJECTED, KeyPool.classify("invalid api key provided"))
    }

    @Test
    fun 四零一优先_别的词不许把它降级成再等等() {
        // "401 invalid key rate limit" 同时命中三档关键词：401 必须先判（这把根本不认）
        assertEquals(KeyPool.Outcome.KEY_REJECTED, KeyPool.classify("401 invalid key, rate limit"))
    }

    @Test
    fun 与Key无关的失败_一个字都不写() {
        val e = KeyPool.Entry("sk-a", cooldownUntil = 0L, disabled = false)
        assertEquals(e, KeyPool.penalize(e, KeyPool.Outcome.NOT_ABOUT_KEY, t0))
        assertEquals(0L to false, KeyPool.penaltyOf(KeyPool.Outcome.NOT_ABOUT_KEY))
    }

    // ── 2. 处罚档位（派工单要"写清"的冷却/禁用策略） ──────────────────────────

    @Test
    fun 限流冷却六十秒_额度冷却五分钟_四零一直接禁用() {
        assertEquals(60_000L, KeyPool.COOLDOWN_RATE_MS)
        assertEquals(300_000L, KeyPool.COOLDOWN_QUOTA_MS)
        assertEquals(KeyPool.COOLDOWN_RATE_MS to false, KeyPool.penaltyOf(KeyPool.Outcome.RATE_LIMITED))
        assertEquals(KeyPool.COOLDOWN_QUOTA_MS to false, KeyPool.penaltyOf(KeyPool.Outcome.QUOTA_OR_FORBIDDEN))
        assertEquals(0L to true, KeyPool.penaltyOf(KeyPool.Outcome.KEY_REJECTED))

        val e = KeyPool.Entry("sk-a")
        assertEquals(t0 + 60_000L, KeyPool.penalize(e, KeyPool.Outcome.RATE_LIMITED, t0).cooldownUntil)
        assertEquals(t0 + 300_000L, KeyPool.penalize(e, KeyPool.Outcome.QUOTA_OR_FORBIDDEN, t0).cooldownUntil)
        val rejected = KeyPool.penalize(e, KeyPool.Outcome.KEY_REJECTED, t0)
        assertTrue("401 ⇒ 永久停手，等用户解禁", rejected.disabled)
        assertEquals(0L, rejected.cooldownUntil)
    }

    @Test
    fun 到点自动回池_不需要用户动手() {
        val cooling = KeyPool.Entry("sk-a", cooldownUntil = t0 + 60_000L)
        assertFalse(cooling.usableAt(t0))
        assertTrue("60 秒后自己回来", cooling.usableAt(t0 + 60_001L))
        assertTrue(cooling.waitMs(t0) > 0L)
        assertEquals(0L, cooling.waitMs(t0 + 60_001L))
    }

    @Test
    fun 禁用项不参与最快恢复() {
        val disabled = KeyPool.Entry("sk-a", cooldownUntil = t0 + 9_999L, disabled = true)
        assertFalse(disabled.usableAt(t0 + 9_999L))
        assertEquals("禁用是永久态：waitMs=0，别拿它算'再等 9 秒就好'", 0L, disabled.waitMs(t0))
    }

    @Test
    fun 整池处罚只动那一把_越界不抛() {
        val p = pool("sk-a", "sk-b")
        val after = KeyPool.penalizeAt(p, 1, KeyPool.Outcome.RATE_LIMITED, t0)
        assertEquals(p[0], after[0])
        assertEquals(t0 + 60_000L, after[1].cooldownUntil)
        assertEquals(p, KeyPool.penalizeAt(p, 7, KeyPool.Outcome.KEY_REJECTED, t0))
    }

    // ── 3. 顺序：轮询而不是"每次从第一把重试" ────────────────────────────────

    @Test
    fun 从游标起往后取_走到底回绕() {
        val p = pool("sk-a", "sk-b", "sk-c")
        assertEquals("sk-a", KeyPool.activeKey(p, cursor = 0, now = t0))
        assertEquals("sk-b", KeyPool.activeKey(p, cursor = 1, now = t0))
        assertEquals("游标回绕", "sk-a", KeyPool.activeKey(p, cursor = 3, now = t0))
        assertEquals("负游标也不许炸", "sk-c", KeyPool.activeKey(p, cursor = -1, now = t0))
    }

    @Test
    fun 坏游标不越界() {
        val p = pool("sk-a", "sk-b")
        assertEquals("sk-b", KeyPool.activeKey(p, cursor = 99, now = t0))
        assertNull(KeyPool.activeKey(emptyList(), 0, t0))
    }

    @Test
    fun 跳过冷却中与已禁用的() {
        val p = listOf(
            KeyPool.Entry("sk-a", cooldownUntil = t0 + 60_000L),
            KeyPool.Entry("sk-b", disabled = true),
            KeyPool.Entry("sk-c"),
        )
        assertEquals(2, KeyPool.activeIndex(p, cursor = 0, now = t0))
        assertEquals(listOf(2), KeyPool.usableIndices(p, t0))
    }

    @Test
    fun 全挂时返回负一_而不是硬用第一把() {
        val p = listOf(
            KeyPool.Entry("sk-a", cooldownUntil = t0 + 60_000L),
            KeyPool.Entry("sk-b", disabled = true),
        )
        assertEquals(-1, KeyPool.activeIndex(p, 0, t0))
        assertNull(KeyPool.activeKey(p, 0, t0))
    }

    // ── 4. 换一把（② 的核心动作） ───────────────────────────────────────────

    @Test
    fun 限流之后换下一把_并把这一把记进冷却() {
        val p = pool("sk-a", "sk-b")
        val (next, idx, key) = KeyPool.rotate(
            entries = p, index = 0, outcome = KeyPool.Outcome.RATE_LIMITED, now = t0,
        )
        assertEquals(1, idx)
        assertEquals("sk-b", key)
        assertEquals(t0 + 60_000L, next[0].cooldownUntil)
        assertFalse(next[1].disabled)
    }

    @Test
    fun 四零一之后那把不再被选中() {
        val p = pool("sk-a", "sk-b")
        val (next, idx, key) = KeyPool.rotate(
            entries = p, index = 0, outcome = KeyPool.Outcome.KEY_REJECTED, now = t0,
        )
        assertTrue(next[0].disabled)
        assertEquals("sk-b", key)
        // 再失败一次：sk-b 也没了 ⇒ 全挂（不许回退到刚被禁用的 sk-a 硬试）
        val (_, idx2, key2) = KeyPool.rotate(
            entries = next, index = idx, outcome = KeyPool.Outcome.KEY_REJECTED, now = t0,
        )
        assertEquals(-1, idx2)
        assertNull(key2)
    }

    @Test
    fun 只有一把时失败就是全挂() {
        val (_, idx, key) = KeyPool.rotate(pool("sk-a"), 0, KeyPool.Outcome.RATE_LIMITED, t0)
        assertEquals(-1, idx)
        assertNull(key)
        assertEquals(-1, KeyPool.nextIndex(pool("sk-a"), 0, t0))
    }

    @Test
    fun 三把轮着走一圈() {
        val p = pool("sk-a", "sk-b", "sk-c")
        var poolNow = p
        var idx = 0
        val order = mutableListOf<String>()
        repeat(3) {
            val (next, nextIdx, key) = KeyPool.rotate(poolNow, idx, KeyPool.Outcome.RATE_LIMITED, t0)
            poolNow = next
            if (key == null) return@repeat
            order += key
            idx = nextIdx
        }
        assertEquals("挨个轮询，不是每次都从第一把重试", listOf("sk-b", "sk-c"), order)
    }

    // ── 5. 编辑（用户自管；App 不自动创建/购买） ─────────────────────────────

    @Test
    fun 池子上限八把() {
        assertEquals(8, KeyPool.MAX_KEYS)
        var p = (1..8).map { KeyPool.Entry("sk-$it") }
        assertEquals(8, p.size)
        p = KeyPool.add(p, "sk-9")
        assertEquals("满了就原样返回，由调用方决定怎么说", 8, p.size)
    }

    @Test
    fun 空串与重复的Key加不进去() {
        val p = pool("sk-a")
        assertEquals(p, KeyPool.add(p, "   "))
        assertEquals(p, KeyPool.add(p, "sk-a"))
        assertEquals("去掉首尾空格后逐字比", p, KeyPool.add(p, " sk-a "))
        assertEquals(2, KeyPool.add(p, " sk-b ").size)
    }

    @Test
    fun 删除按原文精确匹配() {
        val p = pool("sk-a", "sk-b")
        assertEquals(listOf("sk-a"), KeyPool.remove(p, "sk-b").map { it.key })
        assertEquals(p, KeyPool.remove(p, "sk-zzz"))
    }

    @Test
    fun 禁用与解禁是用户的手_越界不改() {
        val p = pool("sk-a", "sk-b")
        assertTrue(KeyPool.setDisabled(p, 0, true)[0].disabled)
        assertFalse(KeyPool.setDisabled(p, 0, false)[0].disabled)
        assertEquals(p, KeyPool.setDisabled(p, 5, true))
    }

    @Test
    fun 三种状态的字() {
        assertEquals("可用", KeyPool.statusOf(KeyPool.Entry("sk-a"), t0))
        assertEquals("冷却中", KeyPool.statusOf(KeyPool.Entry("sk-a", cooldownUntil = t0 + 1L), t0))
        assertEquals("已禁用", KeyPool.statusOf(KeyPool.Entry("sk-a", disabled = true), t0))
    }

    // ── 6. 存储形态与老格迁移 ───────────────────────────────────────────────

    @Test
    fun 编解码往返() {
        val p = listOf(
            KeyPool.Entry("sk-a", cooldownUntil = t0, disabled = false),
            KeyPool.Entry("sk-b", cooldownUntil = t0 + 5L, disabled = true),
        )
        val text = KeyPool.encode(p)
        assertEquals(p, KeyPool.decode(text))
        assertEquals("空池写空数组：'删光了'与'从来没填过'在读取端才分得开", "[]", KeyPool.encode(emptyList()))
    }

    @Test
    fun 脏数据读成空池_不抛() {
        assertTrue(KeyPool.decode(null).isEmpty())
        assertTrue(KeyPool.decode("").isEmpty())
        assertTrue(KeyPool.decode("not json").isEmpty())
        assertTrue(KeyPool.decode("{}").isEmpty())
        assertEquals("缺 k 的那条跳过，好的那条留下",
            listOf("sk-a"), KeyPool.decode("""[{"u":1},{"k":"sk-a"}]""").map { it.key })
    }

    @Test
    fun 老格式单Key格迁成一把() {
        val migrated = KeyPool.migrate(stored = null, legacy = "sk-old")
        assertEquals(listOf("sk-old"), migrated.map { it.key })
        assertEquals("新格有货就以新格为准（不合并：合并会把已删的那把捞回来）",
            listOf("sk-new"),
            KeyPool.migrate(stored = KeyPool.encode(pool("sk-new")), legacy = "sk-old").map { it.key })
        assertTrue("两格都空 ⇒ 真没 Key", KeyPool.migrate(stored = null, legacy = "  ").isEmpty())
        // 诚实记一笔既有行为：新格写的是 "[]"（删光）时 migrate 仍会回落读老格。
        // 真机上不会同时看到这两格——[ProviderPrefs.saveKeyPool] 写新格的那一次就把老格清了，
        // 所以这条只是纯函数的兜底顺序，不是第二真相。
        assertEquals(listOf("sk-old"), KeyPool.migrate(stored = "[]", legacy = "sk-old").map { it.key })
    }

    // ── 7. 保密纪律（红线：Key 不进日志/界面原文） ───────────────────────────

    @Test
    fun 打码只留尾四位() {
        // 补充节 §三钉的形态：`••••a1b2`
        assertEquals("••••a1b2", KeyPool.mask("sk-or-v1-deadbeefcafe01234a1b2"))
        assertEquals(KeyPool.TAIL_DOTS.length + 4, KeyPool.mask("sk-abcdefghij1234").length)
        assertFalse("永不返回原文", KeyPool.mask("sk-or-v1-aaaaaaaa-bbbb-cccc-dddd-eeeeffff0011")
            .contains("sk-or-v1-aaaaaaaa"))
    }

    @Test
    fun 短Key连尾四位也不给() {
        // 长度 ≤8 时尾 4 位＝半个 Key ⇒ 整体压成点（多露一位是一位的风险）
        assertEquals("••••", KeyPool.mask("abcd1234"))
        assertEquals("••••", KeyPool.mask("sk-a"))
        assertEquals("••••", KeyPool.mask(""))
        assertEquals("••••", KeyPool.mask("sk-abcde"))
        assertEquals("••••wxyz", KeyPool.mask("sk-abcdefghijkl-wxyz"))
    }

    @Test
    fun 全池不可用的提示里没有任何Key原文() {
        val p = listOf(
            KeyPool.Entry("sk-secret-one-aaaaaaaa", cooldownUntil = t0 + 60_000L),
            KeyPool.Entry("sk-secret-two-bbbbbbbb", disabled = true),
        )
        val notice = KeyPool.allUnavailableNotice(p, t0)
        assertFalse(notice.contains("sk-secret-one-aaaaaaaa"))
        assertFalse(notice.contains("aaaaaaaa"))
        assertFalse(notice.contains("bbbbbbbb"))
        assertTrue(notice.contains("2 把"))
        assertTrue("冷却与禁用分开说", notice.contains("1 把在冷却") && notice.contains("1 把已被禁用"))
        assertTrue("要说最快多久回来", notice.contains("秒后恢复"))
        assertTrue("要给下一步：②「不静默重试」", notice.contains("再加一把 Key"))
    }

    @Test
    fun 全禁用时不谎报恢复时间() {
        val p = listOf(KeyPool.Entry("sk-a", disabled = true), KeyPool.Entry("sk-b", disabled = true))
        val notice = KeyPool.allUnavailableNotice(p, t0)
        assertFalse("都是禁用的 ⇒ 不该说'在冷却'", notice.contains("在冷却"))
        assertTrue(notice.contains("手动解禁"))
        assertEquals(0L, KeyPool.soonestRecoveryMs(p, t0))
    }

    @Test
    fun 最快恢复取最早那把() {
        val p = listOf(
            KeyPool.Entry("sk-a", cooldownUntil = t0 + 300_000L),
            KeyPool.Entry("sk-b", cooldownUntil = t0 + 30_000L),
        )
        assertEquals(30_000L, KeyPool.soonestRecoveryMs(p, t0))
    }
}
