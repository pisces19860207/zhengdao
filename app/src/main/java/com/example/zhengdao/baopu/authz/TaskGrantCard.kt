// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百四十九批：开工卡（§10 交付项「开工卡截图」的那张卡）。
//
// 卡上只摆**四要素 + 仍然会问的那几类**，两件事分开批：
//   「批准计划，并授权这一单」／「只批准计划（每步照问）」。
// 为什么必须是两个按钮、而不是一个「批准」加一个复选框：
// 批准计划说的是「这活该做」，授权这一单说的是「这十几个写盘不用再问我」——
// 两件事的正确率不一样（Sonus 评审 §2 第一条）。合并成一个按钮，就等于拿前者替后者签字。
//
// 文案一律走 strings（`strings_baopu_authz.xml`，中英各一份）；本文件只摆数据与格式。
package com.example.zhengdao.baopu.authz

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.ui.baopu.BaopuActionContainer

/** 卡上「这些仍然逐次问你」的几类（闭集；人话在 strings 里）。 */
internal enum class StillAsk(val wire: String) {
    /** 删除／发送／提交／执行／改权限（§2 的排除项）。 */
    DESTRUCTIVE("destructive"),

    /** 受保护对象（原件区／密码框）。 */
    PROTECTED("protected"),

    /** R3 风险（花钱、外发、账号语义）。 */
    R3("r3"),
    ;

    companion object {
        fun ofWire(wire: String): StillAsk? = entries.firstOrNull { it.wire == wire }
    }
}

/** 卡面上的一个对象行（[isNew] ＝ 新建，否则是改正文）。 */
internal data class ObjectLine(val handle: String, val isNew: Boolean)

/**
 * 开工卡的**纯显示事实**（可单测：不依赖 Compose，也不依赖任何 UI 状态）。
 *
 * 为什么把「卡上摆什么」与「卡怎么画」分开：截图上要能逐项对上派工单 §2 的四要素，
 * 而 Compose 那一半只能靠眼睛看；这一半可以拿断言钉住（对象清单不是整个工作区、
 * 动作只有那两种、四个数就是那四个数、结束条件里必须有进程重启）。
 */
internal data class TaskGrantFacts(
    val grantId: String,
    val objects: List<ObjectLine>,
    val actions: List<GrantActionType>,
    val maxObjects: Int,
    val maxActions: Int,
    val maxBytesPerFile: Long,
    val maxBytesTotal: Long,
    val lifetimeMinutes: Int,
    /** 四种结束（§2 第四要素原样；顺序＝卡上的顺序）。 */
    val ends: List<GrantEndReason>,
    val stillAsk: List<StillAsk>,
) {
    companion object {
        fun of(grant: TaskGrant): TaskGrantFacts {
            val budget = grant.budget.clamped()
            val lifetime = (grant.expiresMonoMs - grant.issuedMonoMs).coerceAtLeast(0L)
            return TaskGrantFacts(
                grantId = grant.grantId,
                objects = grant.objects.sortedBy { it.objectId }.map {
                    ObjectLine(it.handle, it.state is ObjectState.Absent)
                },
                actions = grant.actions.filter { it.grantable },
                maxObjects = budget.maxObjects,
                maxActions = budget.maxActions,
                maxBytesPerFile = budget.maxBytesPerFile,
                maxBytesTotal = budget.maxBytesTotal,
                lifetimeMinutes = (lifetime / 60_000L).toInt(),
                ends = listOf(
                    GrantEndReason.TASK_ENDED,
                    GrantEndReason.USER_STOPPED,
                    GrantEndReason.EXPIRED,
                    GrantEndReason.PROCESS_RESTARTED,
                ),
                stillAsk = listOf(StillAsk.DESTRUCTIVE, StillAsk.PROTECTED, StillAsk.R3),
            )
        }
    }
}

/**
 * 开工卡。
 *
 * [onApproveWithGrant] 与 [onApprovePlanOnly] 是**两个独立决定**；
 * [onDecline] ＝ 计划也不批（这一单不做）。
 */
@Composable
internal fun BaopuTaskGrantCard(
    facts: TaskGrantFacts,
    onApproveWithGrant: () -> Unit,
    onApprovePlanOnly: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    BaopuActionContainer(
        title = stringResource(R.string.baopu_authz_title),
        modifier = modifier,
        headerTrailing = stringResource(R.string.baopu_authz_badge, facts.lifetimeMinutes),
    ) {
        Text(
            text = stringResource(R.string.baopu_authz_subtitle),
            style = MaterialTheme.typography.bodySmall,
        )

        GrantSectionTitle(stringResource(R.string.baopu_authz_objects_title))
        facts.objects.forEach { o ->
            Text(
                text = if (o.isNew) {
                    stringResource(R.string.baopu_authz_object_new, o.handle)
                } else {
                    stringResource(R.string.baopu_authz_object_edit, o.handle)
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        GrantSectionTitle(stringResource(R.string.baopu_authz_actions_title))
        facts.actions.forEach { a ->
            Text(
                text = stringResource(R.string.baopu_authz_action_line, a.cn),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        GrantSectionTitle(stringResource(R.string.baopu_authz_limits_title))
        Text(
            text = stringResource(
                R.string.baopu_authz_limit_line,
                facts.maxObjects,
                mbText(facts.maxBytesPerFile),
                mbText(facts.maxBytesTotal),
                facts.maxActions,
            ),
            style = MaterialTheme.typography.bodySmall,
        )

        GrantSectionTitle(stringResource(R.string.baopu_authz_ends_title))
        facts.ends.forEach { reason ->
            Text(
                text = when (reason) {
                    GrantEndReason.TASK_ENDED -> stringResource(R.string.baopu_authz_end_task)
                    GrantEndReason.USER_STOPPED -> stringResource(R.string.baopu_authz_end_stop)
                    GrantEndReason.EXPIRED -> stringResource(R.string.baopu_authz_end_expiry, facts.lifetimeMinutes)
                    GrantEndReason.PROCESS_RESTARTED -> stringResource(R.string.baopu_authz_end_restart)
                    else -> stringResource(R.string.baopu_authz_end_revoked)
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        GrantSectionTitle(stringResource(R.string.baopu_authz_still_ask_title))
        facts.stillAsk.forEach { ask ->
            Text(
                text = when (ask) {
                    StillAsk.DESTRUCTIVE -> stringResource(R.string.baopu_authz_still_ask_kinds)
                    StillAsk.PROTECTED -> stringResource(R.string.baopu_authz_still_ask_protected)
                    StillAsk.R3 -> stringResource(R.string.baopu_authz_still_ask_r3)
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = onApproveWithGrant, enabled = enabled) {
            Text(stringResource(R.string.baopu_authz_approve_grant))
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onApprovePlanOnly, enabled = enabled) {
            Text(stringResource(R.string.baopu_authz_approve_plan_only))
        }
        TextButton(onClick = onDecline, enabled = enabled) {
            Text(stringResource(R.string.baopu_authz_decline))
        }
    }
}

@Composable
private fun GrantSectionTitle(text: String) {
    Column {
        Spacer(Modifier.height(8.dp))
        Text(text = text, style = MaterialTheme.typography.titleSmall)
    }
}

/** 字节 ⇒ 卡面上的「2MB」（只用于显示，判定一律用字节）。 */
private fun mbText(bytes: Long): String = "${bytes / (1024L * 1024L)}MB"
