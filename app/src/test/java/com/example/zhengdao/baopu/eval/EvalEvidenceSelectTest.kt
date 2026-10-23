// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百六十八批：eval 证据按来源选（派工单 §2 消费点 B）的 JVM 钉子。
//
// 口径原文：「优先读 7 天内 prod-result，否则回退发版 eval，并显示来源/日期；
// 过期 ⇒ DEFER」——负例靶子：过期生产证据冒充可用、缺证据返非 null、
// 未来时间戳（时钟拨快）被当新鲜。
package com.example.zhengdao.baopu.eval

import com.example.zhengdao.baopu.EvolutionEval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalEvidenceSelectTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 2_000_000_000_000L

    private fun prod(ranAt: Long?) = EvolutionEval(
        score = 0.9,
        caseIds = listOf("a1"),
        source = EvalEvidenceSource.PERSONAL_PROD,
        ranAt = ranAt,
    )

    private val release = EvolutionEval(
        score = 1.0,
        caseIds = listOf("a1", "a2"),
        source = EvalEvidenceSource.RELEASE,
        ranAt = now - 30 * day,
    )

    @Test
    fun `保鲜期就是7天`() {
        assertEquals(7 * day, PROD_EVAL_MAX_AGE_MS)
    }

    @Test
    fun `新鲜的生产证据优先_哪怕发版分数更高`() {
        val fresh = prod(now - 60_000L)
        val got = selectEvalEvidence(fresh, release, now)
        assertSame("7 天内的个人生产证据优先（不许两套分数混用）", fresh, got)
        assertEquals(EvalEvidenceSource.PERSONAL_PROD, got!!.source)
    }

    @Test
    fun `生产证据过期_回退发版证据并留得出来源`() {
        val stale = prod(now - PROD_EVAL_MAX_AGE_MS - 1L)
        val got = selectEvalEvidence(stale, release, now)
        assertSame("过期不回退成『通过』，回退到发版证据", release, got)
        assertEquals(EvalEvidenceSource.RELEASE, got!!.source)
    }

    @Test
    fun `刚好7天整_还算新鲜`() {
        val edge = prod(now - PROD_EVAL_MAX_AGE_MS)
        assertSame(edge, selectEvalEvidence(edge, release, now))
    }

    @Test
    fun `没有生产证据_用发版证据`() {
        assertSame(release, selectEvalEvidence(null, release, now))
    }

    @Test
    fun `生产证据没有ranAt_按没有处理`() {
        // 判不了新鲜程度 ⇒ 不猜（负例：把 ranAt 缺失当永久新鲜）
        assertSame(release, selectEvalEvidence(prod(null), release, now))
    }

    @Test
    fun `时钟拨快的未来时间戳_不算新鲜`() {
        val future = prod(now + 10 * day)
        assertSame("未来时间戳按不新鲜处理（宁可回退）", release, selectEvalEvidence(future, release, now))
    }

    @Test
    fun `两份都没有_null_DEFER不是通过`() {
        assertNull(selectEvalEvidence(null, null, now))
        assertNull("生产证据过期＋没有发版证据 ⇒ 同样 null", selectEvalEvidence(prod(0L), null, now))
    }

    @Test
    fun `自定义保鲜期可注入`() {
        val p = prod(now - 2 * day)
        assertSame(p, selectEvalEvidence(p, release, now, maxAgeMs = 3 * day))
        assertSame("保鲜期缩短到 1 天，两天前的就不算新鲜", release, selectEvalEvidence(p, release, now, maxAgeMs = day))
    }

    @Test
    fun `默认来源是发版_旧读数不猜来源`() {
        // 99 批起构造的 EvolutionEval 没有显式 source ⇒ 恒按发版证据（构建期那份）
        val legacy = EvolutionEval(score = 0.5)
        assertEquals(EvalEvidenceSource.RELEASE, legacy.source)
        assertNull(legacy.ranAt)
        assertTrue("分数边界仍由构造守着", legacy.score in 0.0..1.0)
    }
}
