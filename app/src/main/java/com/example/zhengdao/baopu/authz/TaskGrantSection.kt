// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百四十九批（2026-10-28 用户补一条）：「**不要新建第二张卡**。授权并入现有计划卡
// （ui/baopu/BaopuPlanCard.kt）——一张卡上半『做什么/碰哪些对象』，下半『授权：对象清单☑ /
// 动作类型☑ / 上限』，按钮叫『开工』；对外卡名『开工卡』，内部仍用 PlanContract + 授权快照。
// 理由：用户不该为同一件事被打断两次（看计划一次、授权又一次）。」
//
// 所以本文件**只产出「授权那一段」**（一堆勾选框＋一行上限＋一个「开工」），
// 由 [com.example.zhengdao.ui.baopu.BaopuPlanCard] 接在计划条目下面渲染：
//   · 没有自己的容器／标题条／关闭按钮——那是第二张卡，正是她要拆掉的东西；
//   · 段内不跳别处、不弹窗：勾完点「开工」，选择随 [GrantSelection] 交回调用方。
//
// 数据仍来自本批的授权快照（[TaskGrant]）；文案一律走 `strings_baopu_authz.xml`
// （`strings.xml` 正被 251 改着）。
package com.example.zhengdao.baopu.authz

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R

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

/**
 * 卡面上的一个对象行。
 *
 * [handle] 是账本/勘验认的那个句柄（`task:t1:a@0`），**不上卡**——那是机器话；
 * 卡上给人看的是 [objectId]（就是文件本身），勾选也按 [objectId] 交回去。
 */
internal data class ObjectLine(val handle: String, val objectId: String, val isNew: Boolean)

/**
 * 卡面上的**纯显示事实**（不依赖 Compose，也不依赖任何 UI 状态）。
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
                    ObjectLine(it.handle, it.objectId, it.state is ObjectState.Absent)
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
 * 用户在授权段里勾出来的那一份（点「开工」时交回调用方）。
 *
 * 空集合是**合法**的：一个都不勾＝这一单不授权免问，逐步照旧问。段上没有第三个按钮
 * 来表达这件事——少一个按钮就少一次误解（「只批准计划」与「授权这一单」本来是两件事，
 * 但它不该用两个按钮去问同一个人两遍）。
 */
internal data class GrantSelection(
    val objects: List<String>,
    val actions: List<GrantActionType>,
) {
    /** 一个都没勾（＝照旧逐次问）。 */
    val empty: Boolean get() = objects.isEmpty() || actions.isEmpty()

    /** 与卡上默认值完全相同（＝整单授权）。 */
    fun coversAll(facts: TaskGrantFacts): Boolean =
        objects.toSet() == facts.objects.map { it.handle }.toSet() &&
            actions.toSet() == facts.actions.toSet()
}

/** 卡上默认：全勾（默认值＝「我都看过了，这一单照批准的范围做」）。 */
internal fun defaultGrantSelection(facts: TaskGrantFacts): GrantSelection =
    GrantSelection(facts.objects.map { it.handle }, facts.actions)

/** 勾/取消一个对象（纯函数：返回新的一份，不改入参）。 */
internal fun toggleGrantObject(selection: GrantSelection, handle: String): GrantSelection =
    if (handle in selection.objects) {
        selection.copy(objects = selection.objects - handle)
    } else {
        selection.copy(objects = selection.objects + handle)
    }

/** 勾/取消一个动作类型（纯函数）。 */
internal fun toggleGrantAction(selection: GrantSelection, action: GrantActionType): GrantSelection =
    if (action in selection.actions) {
        selection.copy(actions = selection.actions - action)
    } else {
        selection.copy(actions = selection.actions + action)
    }

/**
 * 「授权」那一段：对象☑ → 动作类型☑ → 上限与失效条件 → 「这些仍然逐次问你」→「开工」。
 *
 * 注意它**没有**自己的卡容器：外框、标题、位置全归计划卡（她就是不要再被打断一次）。
 *
 * @param onStart 点「开工」时回调（带上这一份勾选）；接线由 B 段做，本批只把形状立起来
 */
@Composable
internal fun BaopuPlanGrantSection(
    facts: TaskGrantFacts,
    onStart: (GrantSelection) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var selection by remember(facts.grantId) { mutableStateOf(defaultGrantSelection(facts)) }

    Column(modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.baopu_authz_section_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = stringResource(R.string.baopu_authz_select_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )

        facts.objects.forEach { o ->
            GrantCheckRow(
                checked = o.handle in selection.objects,
                label = if (o.isNew) {
                    stringResource(R.string.baopu_authz_object_new, o.objectId)
                } else {
                    stringResource(R.string.baopu_authz_object_edit, o.objectId)
                },
                enabled = enabled,
                onToggle = { selection = toggleGrantObject(selection, o.handle) },
            )
        }

        Text(
            text = stringResource(R.string.baopu_authz_actions_title),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        facts.actions.forEach { a ->
            GrantCheckRow(
                checked = a in selection.actions,
                label = a.cn,
                enabled = enabled,
                onToggle = { selection = toggleGrantAction(selection, a) },
            )
        }

        Text(
            text = stringResource(
                R.string.baopu_authz_limit_line,
                facts.maxObjects,
                mbText(facts.maxBytesPerFile),
                mbText(facts.maxBytesTotal),
                facts.maxActions,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = stringResource(R.string.baopu_authz_ends_line, facts.lifetimeMinutes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        facts.stillAsk.forEach { ask ->
            Text(
                text = stringResource(
                    when (ask) {
                        StillAsk.DESTRUCTIVE -> R.string.baopu_authz_still_ask_kinds
                        StillAsk.PROTECTED -> R.string.baopu_authz_still_ask_protected
                        StillAsk.R3 -> R.string.baopu_authz_still_ask_r3
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        Spacer(Modifier.padding(top = 4.dp))
        Button(
            onClick = { onStart(selection) },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        ) {
            Text(stringResource(R.string.baopu_authz_start))
        }
    }
}

/** 一行勾选（整行可点，勾选框不单独接事件——避免一处两个点击语义）。 */
@Composable
private fun GrantCheckRow(
    checked: Boolean,
    label: String,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(enabled = enabled, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 字节 ⇒ 卡面上的「2MB」（只用于显示，判定一律用字节）。 */
private fun mbText(bytes: Long): String = "${bytes / (1024L * 1024L)}MB"
