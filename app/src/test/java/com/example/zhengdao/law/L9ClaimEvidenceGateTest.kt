// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十一批 · 法典门禁 **L9 话术不得无证据宣称完成**（派工单 271）
// ＋ 同一号在法典卷二的另一半：**裁量三级**（便宜行事／先斩后奏／题请）。
//
// ## 为什么要判两次
// 271 单把 L9 写成「话术里不得出现"我已完成"而无证据」，法典卷二 L9 写的是「裁量三级」。
// 两处是同一个号下的两件事（一次是**说完**，一次是**动手**），本文件两件都钉：
// ① 完成声明必须带证据（本轮有工具调用 / 有可点开的引用）；
// ② 先斩后奏**四条同时满足**才成立，缺一条就降回题请；追认窗口 24 小时，超时不算同意。
//
// ## 生产状态（诚实标注）
// 两件判据目前**都还没有落进生产码**（`app/src/main` 里没有对应的闸）⇒
// 本条门禁钉的是**判据形状**（policy-as-code），缺陷 #271-L9 在账；
// 接线后本文件的两条 `判据` 用例改为对生产对象断言，用例名不变。
//
// ## 负例（本文件自证会红）
// ① 无证据的「已完成」⇒ 必须被判拒（判据放宽一格就红）；
// ② 超 20 文件 / 含删除 / 含外发 / 不可逆 / 禁区，任一条成立却走了先斩后奏 ⇒ 必须判违规。
package com.example.zhengdao.law

import com.example.zhengdao.baopu.RiskClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class L9ClaimEvidenceGateTest {

    // ── 判据①：完成声明 ────────────────────────────────────────────────────

    internal object ClaimJudge {

        /** 「说完成了」的语义面（只收**结果性**说法；「打算完成」不算）。 */
        val CLAIM_WORDS: List<String> = listOf(
            "已完成", "已经完成", "做好了", "做完了", "搞定了", "已改好", "已修好",
            "已删除", "已发送", "已写入", "已保存", "已提交", "已部署",
        )

        /** 否定/未完成/假设的说法——这些不算「宣称完成」，不许误伤。 */
        private val NOT_A_CLAIM: List<String> = listOf(
            "没完成", "未完成", "还没", "没有完成", "如果", "要是", "打算", "准备",
            "无法完成", "完不成", "不能完成",
        )

        /**
         * 一句带完成语义的话，有没有证据托着。
         *
         * @param evidenceRefs 本轮可点开的引用（208 行号／日志行／文件路径＋行号）
         * @param toolCallsInRound 本轮真实发生的工具调用数（0 = 这一轮什么都没做）
         */
        fun isUnbackedClaim(text: String, evidenceRefs: List<String>, toolCallsInRound: Int): Boolean {
            if (NOT_A_CLAIM.any { text.contains(it) }) return false
            val claims = CLAIM_WORDS.any { text.contains(it) }
            if (!claims) return false
            return evidenceRefs.none { it.isNotBlank() } && toolCallsInRound <= 0
        }
    }

    @Test
    fun `L9_正例_有证据的完成声明通过_闲聊与否定不误伤`() {
        // 有工具调用托着
        assertFalse(
            ClaimJudge.isUnbackedClaim("已改好 3 个文件", emptyList(), toolCallsInRound = 3),
        )
        // 有可点开的引用
        assertFalse(
            ClaimJudge.isUnbackedClaim("已完成迁移", listOf("208#4821"), toolCallsInRound = 0),
        )
        // 闲聊里根本没有完成声明
        assertFalse(ClaimJudge.isUnbackedClaim("今天天气不错", emptyList(), toolCallsInRound = 0))
        // 否定与假设不算宣称
        assertFalse(ClaimJudge.isUnbackedClaim("还没完成，卡在权限上", emptyList(), toolCallsInRound = 0))
        assertFalse(ClaimJudge.isUnbackedClaim("如果完成了我会告诉你", emptyList(), toolCallsInRound = 0))
    }

    @Test
    fun `L9_负例_无证据的我已经完成必须被拒`() {
        assertTrue(
            "空口说「已完成」而本轮一个工具都没调 ⇒ 必须判拒",
            ClaimJudge.isUnbackedClaim("我已经完成了全部改动", emptyList(), toolCallsInRound = 0),
        )
        assertTrue(
            "引用是空串也等于没证据",
            ClaimJudge.isUnbackedClaim("已删除临时目录", listOf("  "), toolCallsInRound = 0),
        )
        // 同一句话带上真证据就不该再红（防「一律判拒」的假门禁）
        assertFalse(
            ClaimJudge.isUnbackedClaim("我已经完成了全部改动", listOf("run_log#77"), toolCallsInRound = 0),
        )
    }

    // ── 判据②：裁量三级（法典卷二 L9） ──────────────────────────────────────

    internal enum class Discretion { CHEAP, PREEMPT, PETITION }

    internal data class Delegation(
        val risk: RiskClass,
        val reversible: Boolean,
        val fileCount: Int,
        val hasDelete: Boolean,
        val hasOutbound: Boolean,
        val inForbiddenZone: Boolean,
    )

    internal object DiscretionJudge {

        /** 先斩后奏的上限（法典原文：≤20 文件）。 */
        const val MAX_FILES = 20

        /** 追认窗口（法典原文：24 小时；超时不算同意）。 */
        const val FOLLOW_UP_MS = 24L * 3600 * 1000

        fun tier(d: Delegation): Discretion {
            if (d.inForbiddenZone) return Discretion.PETITION
            val cheap = (d.risk == RiskClass.R0 || d.risk == RiskClass.R1) && d.reversible
            if (cheap && !d.hasDelete && !d.hasOutbound) return Discretion.CHEAP
            val preempt = d.risk == RiskClass.R2 &&
                d.reversible &&
                d.fileCount <= MAX_FILES &&
                !d.hasDelete &&
                !d.hasOutbound
            return if (preempt) Discretion.PREEMPT else Discretion.PETITION
        }

        /** 追认是否在窗口内（超时 = 没追认 = 不算同意）。 */
        fun followUpInTime(actAtMs: Long, reportedAtMs: Long): Boolean =
            reportedAtMs >= actAtMs && reportedAtMs - actAtMs <= FOLLOW_UP_MS
    }

    @Test
    fun `L9_正例2_R2可回滚且不删不发二十文件内_可先斩后奏`() {
        val ok = Delegation(
            risk = RiskClass.R2,
            reversible = true,
            fileCount = 20,
            hasDelete = false,
            hasOutbound = false,
            inForbiddenZone = false,
        )
        assertEquals(Discretion.PREEMPT, DiscretionJudge.tier(ok))
        // R1 可逆 ⇒ 便宜行事
        assertEquals(
            Discretion.CHEAP,
            DiscretionJudge.tier(ok.copy(risk = RiskClass.R1, fileCount = 200)),
        )
        // 24 小时内追认 ⇒ 成立
        assertTrue(DiscretionJudge.followUpInTime(1_000L, 1_000L + DiscretionJudge.FOLLOW_UP_MS))
    }

    @Test
    fun `L9_负例2_先斩后奏缺一条即违规_超时不算同意`() {
        val base = Delegation(
            risk = RiskClass.R2,
            reversible = true,
            fileCount = 3,
            hasDelete = false,
            hasOutbound = false,
            inForbiddenZone = false,
        )
        assertEquals("基线应先斩后奏（否则下面的对照没意义）", Discretion.PREEMPT, DiscretionJudge.tier(base))
        // 五条边界逐个破，全部必须降回题请
        assertEquals("超 20 文件", Discretion.PETITION, DiscretionJudge.tier(base.copy(fileCount = 21)))
        assertEquals("含删除", Discretion.PETITION, DiscretionJudge.tier(base.copy(hasDelete = true)))
        assertEquals("含外发", Discretion.PETITION, DiscretionJudge.tier(base.copy(hasOutbound = true)))
        assertEquals("不可回滚", Discretion.PETITION, DiscretionJudge.tier(base.copy(reversible = false)))
        assertEquals("落在禁区", Discretion.PETITION, DiscretionJudge.tier(base.copy(inForbiddenZone = true)))
        assertEquals("R3 永不下放", Discretion.PETITION, DiscretionJudge.tier(base.copy(risk = RiskClass.R3)))
        // 超时不算同意
        assertFalse(
            "超过 24 小时的追认不算追认",
            DiscretionJudge.followUpInTime(0L, DiscretionJudge.FOLLOW_UP_MS + 1),
        )
        assertFalse("负的时间差不成立", DiscretionJudge.followUpInTime(1_000L, 999L))
    }
}
