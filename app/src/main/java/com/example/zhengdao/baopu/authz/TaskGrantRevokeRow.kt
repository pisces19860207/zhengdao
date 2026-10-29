// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十六批 Q2 补（2026-10-29 用户裁定「P1 补，只补最简版」）：开工卡上加**一行**
// 「撤销本单」→ 确认 → 调撤销 API → 落账。她给的三条理由里这条是本文件的正主：
// 撤销是安全出口，越早能用越好；而且它是 L26 的三元组第一次由人亲手按一下——
// 三元组对不对，一按就知道。
//
// ## 三条形状纪律（都是她的原话换来的，一条不许漂）
// 1. **不新开页面、不做第二张卡**：这一段是计划卡里的**一行字**（与「展开／收起」同一房型：
//    小字＋主色＋整行可点），没有对话框、没有新 Activity。撤销列表／历史／批量**都不做**
//    （她：「那是后面的事，别扩」）。
// 2. **二段确认**：第一下只把「再点一次确认撤销」摆出来，**这一步一点都没动授权**；
//    第二下才真撤。安全出口不能一按就没——撤错了只能重新开单，那比多问一次代价大得多。
// 3. **交出去的是重算的三元组，不是板上那份引用**：[tripleOf] 从卡上这份批准（[GrantOffer.grant]）
//    按开单时同一个构造重算。拿引用比永远相等 ⇒ L26 就成了摆设；重算与绑定的不一致，
//    拦下的那句话（差在 actionId／argsHash／对象摘要哪一格）原样显示在卡上。
//
// ## 落账落在哪一条（为什么不新造 kind）
// 用 208 既有的 [DecisionKind.ROLLBACK_OF]（枚举原文：「回滚本身也要留一条」），
// 不新造 `tool_grant_revoke`：新造要动 `DecisionLedger.kt`（不在本批租约里），
// 而 L16 的 kind 闭集与 208 那张「点名的十类」`REQUIRED` 都不该为这一格开口子。
// `rollbackOf` 指的就是开单那条批准行的 id（`grant.approvalEventId`，[TaskGrantWire.openGrant]
// 从真记录里拿的那个）——**指不出目标就不落账**（`SkillPromote` 同一条纪律：
// 「指不出目标就等于没痕，那比不记更糟」）；但撤销本身照完成：撤已经发生了，
// 账本没装配不许把已经撤销的授权再翻回来（208「写失败不抛」的口径）。
package com.example.zhengdao.baopu.authz

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger

/** 这一行点在哪儿了（只有 [ARMED] 上的那一下才会真的动手）。 */
internal enum class RevokeStage {
    /** 还没点：卡上只有「撤销本单」。 */
    IDLE,

    /** 点了一下：换成「再点一次确认撤销」＋「取消」——**这一格不碰授权**。 */
    ARMED,

    /** 已经按到底（撤成或拦下都算）：留一句话，不再给第二次点击。 */
    DONE,
}

/**
 * 二段确认的唯一口径：`IDLE` 上的一下只把确认摆出来，其余格子不再往前推。
 *
 * 摆成纯函数是因为「第一下不许真的撤」这条只能由函数钉，不能靠眼睛看 Compose。
 */
internal fun nextRevokeStage(stage: RevokeStage): RevokeStage =
    if (stage == RevokeStage.IDLE) RevokeStage.ARMED else stage

/** 一次撤销的结果（卡面按这三个字段选话，不按文字猜）。 */
internal data class RevokeOutcome(
    /** 撤掉了没有（被 L26 拦下＝false）。 */
    val revoked: Boolean,
    /** 拦下的那一句话（相符时为空串——成功的话由卡面从资源里取，不把中文写死在判定层）。 */
    val note: String,
    /** 这一笔有没有落进 208（撤成了但落不上账要**说出来**，不许悄悄吞）。 */
    val accounted: Boolean,
)

/**
 * 「撤销本单」这一行的后端（唯一实现；卡面与以后的探针都走这里，免得出现第二个撤销口径）。
 *
 * 与 [TaskGrantWire.revoke] 的分工：那一边只管 L26 的比对与收口，这一边管
 * ①**从卡上那份批准重算三元组**、②撤成之后**补那条回滚账**。
 */
internal object TaskGrantRevokeFlow {

    /** 卡上这份批准 ⇒ 三元组（与开单时同一个构造，所以同一份批准永远同一个值）。 */
    fun tripleOf(approved: GrantOffer): ApprovalBinding = TaskGrantWire.bindingOf(approved.grant)

    /**
     * 按下去就发生的这一趟：重算 → 撤销 → 落账。
     *
     * 时钟与账本都由调用方给默认值（构造层的规矩：判定层不碰钟不碰盘，事实在这儿算清）。
     */
    fun revokeNow(
        approved: GrantOffer,
        nowMonoMs: Long = TaskGrantWire.monoMs(),
        wallMs: Long = System.currentTimeMillis(),
        log: (String) -> Unit = { },
    ): RevokeOutcome {
        val (revoked, note) = TaskGrantWire.revoke(tripleOf(approved), nowMonoMs, log)
        if (!revoked) return RevokeOutcome(revoked = false, note = note, accounted = false)
        val accounted = accountRevocation(approved.grant, wallMs, log)
        return RevokeOutcome(revoked = true, note = "", accounted = accounted)
    }

    /**
     * 撤成本身进 208（[DecisionKind.ROLLBACK_OF]，指向开单那条批准行）。
     *
     * @return false＝这一笔没落成（指不出目标，或账本没装配）；撤销本身不回退
     */
    private fun accountRevocation(grant: TaskGrant, wallMs: Long, log: (String) -> Unit): Boolean {
        val target = grant.approvalEventId
        if (target.isBlank()) {
            log("抱朴授权：撤销没有对应的批准行（单 ${grant.grantId.take(10)}）——不落 rollback_of")
            return false
        }
        val row = DecisionLedger.record(
            DecisionDraft(
                kind = DecisionKind.ROLLBACK_OF,
                actor = DecisionActor.USER,
                reason = "撤销本单",
                evidence = target,
                before = "授权生效中（这一格免问）",
                after = "已撤销（照旧逐次问）",
                atMs = wallMs,
                sessionId = grant.sessionId,
                // 撤销不可再撤销：回滚行天生 reversible=false（kind 自己也是 false，两重都摆上）。
                reversible = false,
                rollbackOf = target,
            ),
        )
        if (row == null) {
            log("抱朴授权：撤销了，但决策记录没写成（${DecisionLedger.lastError}）")
            return false
        }
        log("抱朴授权：撤销已落账（回滚行 ${row.id}，指向批准 ${target}）")
        return true
    }
}

/**
 * 开工卡上的那**一行**「撤销本单」（不是按钮组，也不是第二张卡）。
 *
 * 只在「这张卡就是那张开着的单」时由 [com.example.zhengdao.ui.baopu.BaopuPlanCard] 摆出来；
 * 撤完之后这一行的文字换成结论那句，不再给第二次点击。
 */
@Composable
internal fun BaopuGrantRevokeRow(
    approved: GrantOffer,
    modifier: Modifier = Modifier,
) {
    var stage by remember(approved.grant.grantId) { mutableStateOf(RevokeStage.IDLE) }
    var outcome by remember(approved.grant.grantId) { mutableStateOf<RevokeOutcome?>(null) }

    Column(modifier.fillMaxWidth()) {
        if (stage != RevokeStage.DONE) {
            Text(
                text = stringResource(
                    if (stage == RevokeStage.ARMED) {
                        R.string.baopu_authz_revoke_confirm
                    } else {
                        R.string.baopu_authz_revoke_this
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .padding(top = 8.dp)
                    .clickable {
                        if (stage == RevokeStage.ARMED) {
                            val r = TaskGrantRevokeFlow.revokeNow(approved)
                            outcome = r
                            stage = RevokeStage.DONE
                        } else {
                            stage = nextRevokeStage(stage)
                        }
                    },
            )
        }
        if (stage == RevokeStage.ARMED) {
            Text(
                text = stringResource(R.string.baopu_authz_revoke_cancel),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clickable { stage = RevokeStage.IDLE },
            )
        }
        Text(
            text = when (stage) {
                RevokeStage.DONE -> outcome?.let { revokeOutcomeText(it) }
                    ?: stringResource(R.string.baopu_authz_revoke_hint)
                else -> stringResource(R.string.baopu_authz_revoke_hint)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 撤完之后卡上的那一句（三种：撤成／撤成但没落上账／被 L26 拦下——拦下要带差在哪一格）。 */
@Composable
private fun revokeOutcomeText(outcome: RevokeOutcome): String = when {
    !outcome.revoked -> stringResource(R.string.baopu_authz_revoke_refused, outcome.note)
    outcome.accounted -> stringResource(R.string.baopu_authz_revoke_done)
    else -> stringResource(R.string.baopu_authz_revoke_no_ledger)
}
