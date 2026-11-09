// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百五十八批（2026-11-09）· 租约的**读数**（同一份数的唯一取数口）。
//
// 口径唯一真相在 `docs/metrics-registry.md` §8（本文件只按它取数，不另算）：
//   · `live` —— 当下活着的租约张数，取数走**内存权威** [LeaseStore]（467 的结构是
//     "内存是权威，盘只是证据"：账可被她删改，进程重启不复活也只有内存知道）；
//   · 其余六格 —— 事件计数，走账本 replay（W3：[LeaseLedger.replay]，467 租约账
//     `产物/.leases.jsonl`），按 `at` 切当日窗（[AskGovernance.dayOf] 同一把尺）。
//
// 三个读口读这一份，不许各造一遍（registry §8）：① `governance_state` 的 lease 段；
// ② 体检话行 [com.example.zhengdao.baopu.agent.AskGovernance.healthLine] 尾句；
// ③ 自治理中心读数区那一行（它经出口抄，不走本文件）。
//
// 两条纪律（照 527 cards 段的房型）：
//   1. 读不出 ⇒ null（调用方各自降级：出口整段 unknown、话行整句不出现），**不抛、不编 0**；
//   2. `hits` 与 `savedCards` 是同一行账（`kind=use`）的两个视角——命中即免卡（467 判据
//      原话），恒相等，是对账格不是两个口径。
package com.example.zhengdao.baopu.authz

import com.example.zhengdao.baopu.agent.AskGovernance
import org.json.JSONObject

internal object LeaseReadings {

    /** 一把读数（字段序＝出口 JSON 的键序，metrics-registry.md §8）。 */
    data class Counts(
        val live: Int,
        val grantedToday: Int,
        val hits: Int,
        val savedCards: Int,
        val revoked: Int,
        val downgraded: Int,
        val expired: Int,
    ) {
        /** 有没有可显示的租约（话行/读数行的显示条件：今天授过或手里还有活租约）。 */
        val visible: Boolean get() = live > 0 || grantedToday > 0
    }

    /**
     * 撤销行的 why 词表（写侧在 [LeaseStore.revokeOne]；四类恰好被
     * 撤销/降权/过期三格分完——registry §8 的分法，每行账只归一格）。
     */
    private const val WHY_USER = "user"
    private const val WHY_EXPIRED = "expired"
    private const val WHY_DOWNGRADE = "downgrade"

    /**
     * 读一把（**null＝租约账落点未装配**，或账读不出——调用方按"读不到"降级，不编零）。
     *
     * @param nowMs 切当日窗的"现在"（出口与体检都传同一时刻）
     */
    fun counts(nowMs: Long = System.currentTimeMillis()): Counts? {
        if (LeaseLedger.root() == null) return null
        val rows = runCatching { LeaseLedger.replay() }.getOrElse { return null }
        val live = runCatching { LeaseStore.liveCount(nowMs) }.getOrElse { return null }
        val today = AskGovernance.dayOf(nowMs)
        fun isToday(row: JSONObject): Boolean = AskGovernance.dayOf(row.optLong("at", 0L)) == today

        val grants = rows.count { it.optString("kind") == LeaseKinds.GRANT && isToday(it) }
        val uses = rows.count { it.optString("kind") == LeaseKinds.USE && isToday(it) }
        val revokes = rows.filter { it.optString("kind") == LeaseKinds.REVOKE && isToday(it) }
        val expired = revokes.count { it.optString("why") == WHY_EXPIRED }
        val downgraded = rows.count { it.optString("kind") == LeaseKinds.DOWNGRADE && isToday(it) } +
            revokes.count { it.optString("why") == WHY_DOWNGRADE }
        val revoked = revokes.count { it.optString("why") != WHY_EXPIRED && it.optString("why") != WHY_DOWNGRADE }
        return Counts(
            live = live,
            grantedToday = grants,
            hits = uses,
            savedCards = uses,
            revoked = revoked,
            downgraded = downgraded,
            expired = expired,
        )
    }
}
