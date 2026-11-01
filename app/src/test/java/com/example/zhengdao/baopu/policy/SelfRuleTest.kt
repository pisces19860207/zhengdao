// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百三十一批 · 左相打扰闸的判据测试。
//
// 五组判据（派工单 §①）：三类必须问**各一反例**（同一件事只改掉触发那一类的那个事实，
// 结局必须翻成自决）、恰好三类（加一类就红）、可回退的删除不许算必须问（负例）、
// 延后与跳不可用能力都自决、以及**穷举**——任何事实组合只有「问」或「判」两种结局，
// 没有第三种（「判不了」就是自决，这是本批要的敢判）。
package com.example.zhengdao.baopu.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfRuleTest {

    private fun req(
        irreversible: Boolean = false,
        rollback: Rollback = Rollback.None,
        authorization: Authorization = Authorization.IN_SCOPE,
        failure: FailureSignal? = null,
        deferrable: Boolean = false,
        capabilityAvailable: Boolean = true,
        destructive: Boolean = false,
        action: String = "删掉临时目录",
    ): SelfRequest = SelfRequest(
        actionId = "a-$action",
        action = action,
        authorization = authorization,
        irreversible = irreversible,
        rollback = rollback,
        failure = failure,
        deferrable = deferrable,
        capabilityAvailable = capabilityAvailable,
        destructive = destructive,
    )

    // ── 三类各一反例：只改掉触发那一类的那个事实，结局必须翻成自决 ──────────

    @Test
    fun `类一_不可逆且不可回退才问_有回退手段就不问`() {
        val ask = SelfRule.judge(req(irreversible = true, rollback = Rollback.None, destructive = true))
        assertEquals(AskClass.IRREVERSIBLE, ask.askClass)
        assertTrue(ask.mustAsk)
        assertNull("必须问就没有自决依据", ask.basis)
        assertTrue("判词要说出触发它的两个事实：${ask.why}", ask.why.contains("不可逆＝true"))

        val decide = SelfRule.judge(
            req(irreversible = true, rollback = Rollback.Ready("回收站 30 天"), destructive = true),
        )
        assertNull("同一个删除，只要有回退手段就不该问", decide.askClass)
        assertEquals(SelfBasis.REVERSIBLE_DESTRUCTIVE, decide.basis)
    }

    @Test
    fun `类二_超授权才问_授权内不问`() {
        val out = SelfRule.judge(req(authorization = Authorization.OUT_OF_SCOPE))
        assertEquals(AskClass.BEYOND_GRANT, out.askClass)
        assertEquals(AskClass.BEYOND_GRANT, SelfRule.judge(req(authorization = Authorization.NOT_GRANTED)).askClass)

        val inside = SelfRule.judge(req(authorization = Authorization.IN_SCOPE))
        assertNull("授权内不许拿「授权」当打扰的理由", inside.askClass)
        assertEquals(SelfBasis.UNCLASSIFIED, inside.basis)
    }

    @Test
    fun `类三_真失败才问_可重试或能换路都不问`() {
        val ask = SelfRule.judge(
            req(failure = FailureSignal(retryable = false, hasWorkaround = false, needsHuman = true)),
        )
        assertEquals(AskClass.REAL_FAILURE, ask.askClass)

        val retry = SelfRule.judge(
            req(failure = FailureSignal(retryable = true, hasWorkaround = false, needsHuman = true)),
        )
        assertNull("可重试的失败不许打扰人：${retry.why}", retry.askClass)

        val workaround = SelfRule.judge(
            req(failure = FailureSignal(retryable = false, hasWorkaround = true, needsHuman = true)),
        )
        assertNull("有替代路径的失败不许打扰人：${workaround.why}", workaround.askClass)

        val notHuman = SelfRule.judge(
            req(failure = FailureSignal(retryable = false, hasWorkaround = false, needsHuman = false)),
        )
        assertNull("不要人拍板的失败不算真失败", notHuman.askClass)
    }

    // ── 恰好三类 ＋ 负例 ────────────────────────────────────────────────

    @Test
    fun `必须问恰好三类_加第四类就会红`() {
        assertEquals("派工单写的是三类", 3, SelfRule.CLASS_COUNT)
        assertEquals(3, SelfRule.classes().size)
        assertEquals(
            listOf("irreversible", "beyond_grant", "real_failure"),
            SelfRule.classes().map { it.wire },
        )
    }

    @Test
    fun `负例_可回退的删除不许算必须问`() {
        val ruling = SelfRule.judge(
            req(irreversible = true, rollback = Rollback.Ready("移到 回收站/，30 天后自动清"), destructive = true),
        )
        assertFalse("可回退的删除必须自决，否则又回到「什么都要问」：${ruling.why}", ruling.mustAsk)
        assertTrue(ruling.mayDecide)
        assertEquals(SelfBasis.REVERSIBLE_DESTRUCTIVE, ruling.basis)
        assertTrue("依据要写出怎么退：${ruling.why}", ruling.why.contains("回收站"))
    }

    @Test
    fun `延后与跳不可用能力都自决_跳能力优先归因`() {
        val defer = SelfRule.judge(req(deferrable = true))
        assertEquals(SelfBasis.DEFER, defer.basis)
        assertTrue(defer.mayDecide)

        val skip = SelfRule.judge(req(capabilityAvailable = false, deferrable = true))
        assertEquals("跳不可用能力优先于延后（先能做的事先做）", SelfBasis.SKIP_UNAVAILABLE, skip.basis)
        assertTrue(skip.mayDecide)
    }

    @Test
    fun `又超授权又不可逆时记在不可逆那一类`() {
        val ruling = SelfRule.judge(
            req(irreversible = true, authorization = Authorization.OUT_OF_SCOPE),
        )
        assertEquals("顺序写死：不可逆是更硬的那条理由", AskClass.IRREVERSIBLE, ruling.askClass)
    }

    // ── 穷举：没有第三种结局 ───────────────────────────────────────────

    @Test
    fun `穷举所有事实组合_只有问或判两种结局`() {
        val rollbacks = listOf(Rollback.None, Rollback.Ready("备份"))
        val failures = listOf(
            null,
            FailureSignal(retryable = true, hasWorkaround = false, needsHuman = true),
            FailureSignal(retryable = false, hasWorkaround = true, needsHuman = true),
            FailureSignal(retryable = false, hasWorkaround = false, needsHuman = true),
            FailureSignal(retryable = false, hasWorkaround = false, needsHuman = false),
        )
        val cases = buildList {
            for (irreversible in listOf(false, true))
                for (rollback in rollbacks)
                    for (authorization in Authorization.entries)
                        for (failure in failures)
                            for (deferrable in listOf(false, true))
                                for (capability in listOf(false, true))
                                    for (destructive in listOf(false, true)) {
                                        add(
                                            req(
                                                irreversible = irreversible,
                                                rollback = rollback,
                                                authorization = authorization,
                                                failure = failure,
                                                deferrable = deferrable,
                                                capabilityAvailable = capability,
                                                destructive = destructive,
                                            ),
                                        )
                                    }
        }
        var asked = 0
        var decided = 0
        val hit = LinkedHashSet<AskClass>()
        cases.forEach { case ->
            val ruling = SelfRule.judge(case)
            assertTrue("判词不许为空：$case", ruling.why.isNotBlank())
            if (ruling.mustAsk) {
                asked++
                hit += ruling.askClass!!
                assertNull("必须问不许带自决依据", ruling.basis)
            } else {
                decided++
                assertNull(ruling.askClass)
                assertNotNull("自决必须说清依据", ruling.basis)
            }
            assertEquals("问与判互斥且穷尽", ruling.mustAsk, !ruling.mayDecide)
        }
        assertEquals("穷举数要对得上", 2 * 2 * 3 * 5 * 2 * 2 * 2, asked + decided)
        assertEquals("三类必须问都要被穷举命中过", AskClass.entries.toSet(), hit)
        assertTrue("自决也要真的发生过", decided > 0)
    }

    @Test
    fun `授权内且可回退且没有真失败_一律自决`() {
        // 「压窄」的可证伪说法：这三条都不成立时，任何其他事实组合都不许把它抬成必须问。
        var cases = 0
        for (irreversible in listOf(false, true))
            for (deferrable in listOf(false, true))
                for (capability in listOf(false, true))
                    for (destructive in listOf(false, true)) {
                        cases++
                        val ruling = SelfRule.judge(
                            req(
                                irreversible = irreversible,
                                rollback = Rollback.Ready("备份"),
                                authorization = Authorization.IN_SCOPE,
                                failure = FailureSignal(retryable = false, hasWorkaround = false, needsHuman = false),
                                deferrable = deferrable,
                                capabilityAvailable = capability,
                                destructive = destructive,
                            ),
                        )
                        assertTrue("这一组必须自决，实际问了：${ruling.why}", ruling.mayDecide)
                    }
        assertEquals(16, cases)
    }

    @Test
    fun `点不出名的动作不许判`() {
        assertThrows(IllegalArgumentException::class.java) {
            SelfRule.judge(SelfRequest("", "改文案", Authorization.IN_SCOPE))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SelfRule.judge(SelfRequest("a1", "  ", Authorization.IN_SCOPE))
        }
    }
}
