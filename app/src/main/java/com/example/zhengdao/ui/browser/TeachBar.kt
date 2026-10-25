// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十六批 · 玄览「教它一次」的 UI 半边：录制指示条＋确认卡。
// 判据与状态全在 baopu 侧（TeachSessionUiModel/TeachLearner），本文件只渲染与转发。
package com.example.zhengdao.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.TeachParamPick
import com.example.zhengdao.baopu.TeachParams
import com.example.zhengdao.baopu.TeachSkill
import com.example.zhengdao.baopu.TeachSkillDraft
import com.example.zhengdao.ui.theme.zhengdaoErrorText

/**
 * 示教指示条（底栏上方一行小胶囊）：告诉用户「正在记」，别让无感的录制变成隐私事故。
 * 只渲染；步数由 BrowserScreen 从控制器读。
 */
@Composable
fun TeachRecordingBadge(stepCount: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(
                Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.error, RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = pluralStringResource(R.plurals.teach_recording_badge, stepCount, stepCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/**
 * 确认卡（§1「确认才落盘」的 UI 半边）：技能名可改，步骤摘要可读，
 * 「保存技能」走 TeachReplayStore（184 写口），「放弃」一个字节不写。
 *
 * 第二百批：多一个「参数化…」——录完先把「北京」这种值勾出来变成 `${direction}`，
 * 勾完再存。判据全在 baopu 侧（TeachParams），这里只转手。
 */
@Composable
fun TeachConfirmSheet(
    draft: TeachSkillDraft,
    onSave: (name: String) -> Unit,
    onDiscard: () -> Unit,
    // Modifier 必须是第一个可选参数（lint ModifierParameter），所以它排在 onParameterize 前面
    modifier: Modifier = Modifier,
    onParameterize: (() -> Unit)? = null,
) {
    var name by remember(draft) { mutableStateOf(draft.skill.name) }
    AlertDialog(
        onDismissRequest = onDiscard,
        title = { Text(stringResource(R.string.teach_confirm_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.teach_confirm_name_label)) },
                )
                Spacer(Modifier.padding(top = 8.dp))
                Text(
                    text = draft.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }) {
                Text(stringResource(R.string.teach_confirm_save))
            }
        },
        dismissButton = {
            Row {
                if (onParameterize != null) {
                    TextButton(onClick = onParameterize) {
                        Text(stringResource(R.string.teach_params_action))
                    }
                }
                TextButton(onClick = onDiscard) {
                    Text(stringResource(R.string.teach_confirm_discard))
                }
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * 「步骤＋参数」视图（第二百批 §3）：三件事，别的都不做。
 *
 * 1. 勾选/改名/给默认值 —— 值从步骤里拔掉，换成 `${name}`；
 * 2. 调换步骤顺序（↑↓，不是拖拽）；
 * 3. 删参数 ⇒ 由参数表那边回退字面量（没默认值就不许删，界面上直说）。
 *
 * 做不了的明确不做：拖拽、条件分支、循环（那是编排层，不是这里）。
 */
@Composable
fun TeachParamsSheet(
    draft: TeachSkillDraft,
    onApply: (TeachSkill, List<String>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val candidates = remember(draft) { TeachParams.collect(draft.skill) }
    var working by remember(draft) { mutableStateOf(draft.skill) }
    var selected by remember(draft) {
        mutableStateOf(candidates.filter { it.selectable && it.defaultSelected }.map { it.example }.toSet())
    }
    val names = remember(draft) {
        mutableStateMapOf<String, String>().apply { candidates.forEach { put(it.example, it.name) } }
    }
    val defaults = remember(draft) { mutableStateMapOf<String, String>() }
    var error by remember(draft) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.teach_params_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.teach_params_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(top = 6.dp))

                candidates.forEach { c ->
                    val label = if (c.options.isNotEmpty()) {
                        c.example + "（" + c.options.take(4).joinToString("/") { it.label } + "）"
                    } else {
                        c.example
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(
                            checked = c.selectable && c.example in selected,
                            enabled = c.selectable,
                            onCheckedChange = { on ->
                                selected = if (on) selected + c.example else selected - c.example
                            },
                        )
                        Column(Modifier.padding(start = 4.dp)) {
                            Text(label, style = MaterialTheme.typography.bodySmall)
                            Text(
                                text = c.blockReason ?: (c.source.wire + " · " + c.slots.joinToString("、") { it.describe() }),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (c.selectable && c.example in selected) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = names[c.example].orEmpty(),
                                onValueChange = { names[c.example] = it },
                                singleLine = true,
                                label = { Text(stringResource(R.string.teach_params_name_label)) },
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(6.dp))
                            OutlinedTextField(
                                value = defaults[c.example].orEmpty(),
                                onValueChange = { defaults[c.example] = it },
                                singleLine = true,
                                label = { Text(stringResource(R.string.teach_params_default_label)) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                Spacer(Modifier.padding(top = 8.dp))
                Text(stringResource(R.string.teach_params_steps_title), style = MaterialTheme.typography.labelSmall)
                TeachParams.Editor.stepsView(working).forEachIndexed { i, line ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (i > 0) {
                            TextButton(onClick = {
                                TeachParams.Editor.apply(working, TeachParams.TeachEditOp.MoveStep(i + 1, i))
                                    .skill?.let { working = it }
                            }) { Text(stringResource(R.string.teach_params_move_up)) }
                        }
                        if (i < working.steps.size - 1) {
                            TextButton(onClick = {
                                TeachParams.Editor.apply(working, TeachParams.TeachEditOp.MoveStep(i + 1, i + 2))
                                    .skill?.let { working = it }
                            }) { Text(stringResource(R.string.teach_params_move_down)) }
                        }
                    }
                }

                error?.let {
                    Spacer(Modifier.padding(top = 6.dp))
                    // 文字色走 zhengdaoErrorText()（colorScheme.error 当正文只有 3.55:1，不达 AA）
                    Text(it, style = MaterialTheme.typography.labelSmall, color = zhengdaoErrorText())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val picks = candidates.filter { it.selectable && it.example in selected }.map { c ->
                    TeachParamPick(
                        name = names[c.example].orEmpty().trim(),
                        label = c.label,
                        example = c.example,
                        source = c.source,
                        shape = c.shape,
                        slots = c.slots,
                        default = defaults[c.example]?.takeIf { d -> d.isNotBlank() },
                        options = c.options,
                    )
                }
                val r = TeachParams.parameterize(working, picks)
                if (r.ok) onApply(r.skill!!, draft.warnings) else error = r.errors.joinToString("；")
            }) {
                Text(stringResource(R.string.teach_params_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.teach_params_cancel)) }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

