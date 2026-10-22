// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 2026-10-19 用户口径：「加，位置在输入栏的上方弹出」。
//
// 这是 `ask_user` 的渲染色面：抱朴拿不准时弹一张卡，2–4 个选项、推荐项排第一并带一句理由。
// 三条界面纪律：
// 1. **就在输入栏上方**（不遮对话、不跳新页）：用户眼睛本来就在这儿；
// 2. **推荐项要能一眼看出来**（实心按钮），其余用描边按钮；
// 3. **每项都带理由**（`detail` 非空才显示）——只有按钮没有理由，等于让用户瞎点。
//
// 第一百三十七批（2026-10-22）：外框换成四档容器的**行动卡**；原先的
// `surfaceVariant` 底＋投影随之取消（§6.1：不许再靠底色深浅分卡片）。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.agent.AskUserOption
import com.example.zhengdao.baopu.agent.AskUserRequest

/**
 * 选项卡（输入栏上方）。
 *
 * @param request 引擎给的问题与选项（推荐项已排在第一）
 * @param onPick 点某个选项（把 id 交回引擎）
 */
@Composable
internal fun BaopuChoiceCard(
    request: AskUserRequest,
    onPick: (AskUserOption) -> Unit,
) {
    BaopuActionContainer(
        title = stringResource(R.string.baopu_tier_action_choice),
        badge = stringResource(R.string.baopu_tier_action_badge),
    ) {
        Text(
            text = request.question,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        request.options.forEach { option ->
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (option.recommended) {
                        Button(onClick = { onPick(option) }, modifier = Modifier.fillMaxWidth()) {
                            Text(option.label)
                        }
                    } else {
                        OutlinedButton(onClick = { onPick(option) }, modifier = Modifier.fillMaxWidth()) {
                            Text(option.label)
                        }
                    }
                }
                if (option.detail.isNotBlank()) {
                    Text(
                        text = option.detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 6.dp),
                    )
                } else {
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}
