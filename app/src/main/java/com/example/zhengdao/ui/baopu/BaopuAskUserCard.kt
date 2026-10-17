// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第十批 §1 C 段（提问卡形状：问题一行、选项 2–4 且推荐第一并标
// 「推荐」、永远留一行空白输入）+ §4（渲染层只画 [AskUserCard]，判据全在纯函数）。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.AskUserCard
import com.example.zhengdao.baopu.AskUserOption

/**
 * `ask_user` 提问卡（刀 2 C 段）——**只渲染**，零判据。
 *
 * 房型（派工单 §1 C 段逐字）：
 * - 问题一行（[AskUserCard.question] 原样直显，不截断——问题长说明该问清）；
 * - 选项 [AskUserCard.options]（2–4 个；**推荐项排第一并带「推荐」角标**——
 *   排序与推荐位由 [AskUserCardRules.validate] 在数据层保证，这里只认
 *   `recommended` 标志上高亮，不自己重排）；
 * - **永远留一行空白输入**（[AskUserCard.freeTextPrompt] 是它的占位提示）——
 *   派工单原话「不许只有选项」；自由文本经 [onFreeText] 交回调用方解释，
 *   本层不猜用户想干什么。
 *
 * 与 DSH 提问卡的同与不同：同在一行问题 + 选项 + 自由行；不同在**没有**输入框
 * 键盘自动弹出等交互花活——这是资料库收料的一次性选择，不是聊天。
 */
@Composable
internal fun BaopuAskUserCard(
    card: AskUserCard,
    onPick: (AskUserOption) -> Unit,
    onFreeText: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            // 问题（一行）
            Text(
                text = card.question,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            // 选项（推荐第一是数据层的排序保证；这里按给定顺序画）
            card.options.forEach { opt ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { onPick(opt) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(opt.label)
                    }
                    if (opt.recommended) {
                        Text(
                            text = stringResource(R.string.baopu_ask_recommended),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
            // 空白输入行（永远在；提交后清空——下一次还是一张干净的卡）
            var freeText by remember(card) { mutableStateOf("") }
            OutlinedTextField(
                value = freeText,
                onValueChange = { freeText = it },
                enabled = enabled,
                placeholder = { Text(card.freeTextPrompt) },
                singleLine = false,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        onFreeText(freeText)
                        freeText = ""
                    },
                    enabled = enabled && freeText.isNotBlank(),
                ) {
                    Text(stringResource(R.string.baopu_ask_free_submit))
                }
            }
        }
    }
}
