// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 2026-10-25 用户口径（第二百零三批）：「假如那些卡有好几个问题，是不是都可以在卡内
// 回答啊？不用每一个问题分别弹一次吧？」「每个问题弹一次的话真的会打扰到」。
//
// 多问卡（输入栏上方）：N 个问题一次答完、一次提交。房型：
// - 每问按 [AskQuestion.kind] 画控件：SINGLE/MULTI 选项片（2–4 个）、TEXT 输入行、
//   NUMBER 数字行（非数字当场红字）、BOOL 是/否两片；
// - **就地校验**（AskGroupRules.validate）：必答未填 ⇒ 提交置灰并点名是哪项；
//   数字项非数字 ⇒ 当场红字——都不许「提交后才报」；
// - 可跳过：有 default 的项给「按默认」（整张卡回到默认值）；全部可免答时再给
//   「都不改」（原样回默认，一次点击直接交）；
// - **不抢输入框**：卡是普通组合项，不 autofocus、不遮拦——用户照旧可以直接
//   打字发送（164 判据），没答完的卡留在流里等。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.agent.AskGroupCheck
import com.example.zhengdao.baopu.agent.AskGroupRules
import com.example.zhengdao.baopu.agent.AskQuestion
import com.example.zhengdao.baopu.agent.AskQuestionKind
import com.example.zhengdao.baopu.agent.AskUserRequest
import com.example.zhengdao.ui.theme.zhengdaoErrorText

/**
 * 多问卡（输入栏上方；`ask_user` 的问题组形状，第二百零三批）。
 *
 * @param request 引擎给的整张卡（[AskUserRequest.questions] 本卡的问题；[AskUserRequest.note]
 *   拆卡续问提示，只有第一张非空）
 * @param onSubmit 提交整卡（answers 以问题 id 为键；数据层 [AskGroupRules.draftAnswers]
 *   已把空项退回默认值）
 */
@Composable
internal fun BaopuQuestionGroupCard(
    request: AskUserRequest,
    onSubmit: (Map<String, String>) -> Unit,
) {
    val questions = request.questions
    // 草稿按问题 id 记（键换卡即弃——remember(request) 钉住；MULTI 存逗号连接的 id 串）
    val draft = remember(request) { mutableStateMapOf<String, String>() }
    BaopuActionContainer(
        title = stringResource(R.string.baopu_tier_action_group),
        badge = stringResource(R.string.baopu_tier_action_badge),
    ) {
        if (request.note.isNotBlank()) {
            Text(
                text = request.note,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
        }
        questions.forEachIndexed { index, q ->
            QuestionItem(
                question = q,
                value = draft[q.id].orEmpty(),
                onChange = { draft[q.id] = it },
            )
            if (index < questions.lastIndex) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
        }
        val check = AskGroupRules.validate(questions, draft)
        if (check is AskGroupCheck.Invalid) {
            // 就地校验的红字：必答没填点名是哪项；数字非数字当场报（不提交后才说）。
            // 语义色 zhengdaoErrorText（P2-11 守卫）：error 原色 3.55:1 够不上 AA 正文。
            Text(
                text = check.problems.joinToString("；"),
                style = MaterialTheme.typography.bodySmall,
                color = zhengdaoErrorText(),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (questions.any { it.default.isNotBlank() }) {
                OutlinedButton(
                    onClick = {
                        draft.clear()
                        draft.putAll(AskGroupRules.defaultAnswers(questions))
                    },
                ) {
                    Text(stringResource(R.string.baopu_group_use_defaults))
                }
            }
            if (questions.none { it.required }) {
                TextButton(onClick = { onSubmit(AskGroupRules.defaultAnswers(questions)) }) {
                    Text(stringResource(R.string.baopu_group_change_nothing))
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { onSubmit(AskGroupRules.draftAnswers(questions, draft)) },
                enabled = check is AskGroupCheck.Ok,
            ) {
                Text(stringResource(R.string.baopu_group_submit))
            }
        }
    }
}

@Composable
private fun QuestionItem(
    question: AskQuestion,
    value: String,
    onChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = question.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (question.required) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.baopu_group_required_mark),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (question.kind == AskQuestionKind.MULTI) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.baopu_group_multi_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (question.kind) {
            AskQuestionKind.SINGLE -> OptionChips(question, value, onChange)
            AskQuestionKind.MULTI -> OptionChips(question, value, onChange)
            AskQuestionKind.TEXT -> OutlinedTextField(
                value = value,
                onValueChange = onChange,
                placeholder = { Text(stringResource(R.string.baopu_group_text_hint)) },
                singleLine = false,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )

            AskQuestionKind.NUMBER -> {
                val bad = value.isNotBlank() && value.trim().toDoubleOrNull() == null
                OutlinedTextField(
                    value = value,
                    onValueChange = onChange,
                    isError = bad,
                    // 数字项就地拒绝：非数字当场红字（AskGroupRules.validate 兜底点名）
                    supportingText = if (bad) {
                        { Text(stringResource(R.string.baopu_group_number_bad)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }

            AskQuestionKind.BOOL -> Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = value == "true",
                    onClick = { onChange(if (value == "true") "" else "true") },
                    label = { Text(stringResource(R.string.baopu_group_bool_yes)) },
                )
                FilterChip(
                    selected = value == "false",
                    onClick = { onChange(if (value == "false") "" else "false") },
                    label = { Text(stringResource(R.string.baopu_group_bool_no)) },
                )
            }
        }
        question.options.firstOrNull { it.id in AskGroupRules.splitMulti(value) && it.detail.isNotBlank() }
            ?.let { picked ->
                Text(
                    text = picked.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
    }
}

/** SINGLE/MULTI 共用的选项片：点选/切换，草稿值统一走 [onChange]（MULTI 存逗号连接）。 */
@Composable
private fun OptionChips(
    question: AskQuestion,
    value: String,
    onChange: (String) -> Unit,
) {
    val multi = question.kind == AskQuestionKind.MULTI
    val picked = AskGroupRules.splitMulti(value).toSet()
    Column(Modifier.padding(top = 6.dp)) {
        question.options.forEach { option ->
            FilterChip(
                selected = option.id in picked,
                onClick = {
                    onChange(
                        when {
                            !multi -> if (option.id in picked) "" else option.id
                            option.id in picked -> AskGroupRules.joinMulti(picked - option.id)
                            else -> AskGroupRules.joinMulti(picked + option.id)
                        },
                    )
                },
                label = { Text(option.label) },
            )
        }
    }
}
