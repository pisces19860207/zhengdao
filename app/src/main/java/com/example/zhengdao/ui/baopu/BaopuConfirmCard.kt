// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单 §3.4（确认卡：一行标题「要动文件：<路径>」、一行摘要、两个按钮
// 「批准」「拒绝」、忙碌态「执行中…」；形态照 BaopuAskUserCard，不另起一套视觉；
// 无障碍 contentDescription 走 semantics，**不引 material-icons**——本项目没这个依赖）
// ＋ §3.1（界面文案全走 strings.xml，中英各一份；九句由 B 段随包落盘，别重写）。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.agent.ConfirmRequest
import org.json.JSONObject

/**
 * 文件改动确认卡（刀 3 A 段）——**只渲染**，零判据。
 *
 * 与 [BaopuAskUserCard] 的同与不同：同在一行标题 + 摘要 + 按钮；不同在没有
 * 自由输入行——这是「批不批」的二选一，不是聊天。
 *
 * 路径只从 `argsJson` 里取 `path` 做**标题展示**（不解析执行任何动作——
 * 执行只走引擎的 GuardPipeline.executeApproved；取不到就退化成工具名）。
 */
@Composable
internal fun BaopuConfirmCard(
    request: ConfirmRequest,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 点过就进忙碌态（执行中…）；卡被摘掉（控制器置空）时本组件随之消失
    var busy by remember(request.callId) { mutableStateOf(false) }
    val path = remember(request.argsJson) {
        runCatching { JSONObject(request.argsJson).optString("path").trim() }.getOrNull()?.ifBlank { null }
    }
    val approveDesc = stringResource(R.string.baopu_confirm_approve_desc)
    val rejectDesc = stringResource(R.string.baopu_confirm_reject_desc)

    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            // 标题：要动文件：<路径>（取不到 path ⇒ 换成「要执行：<工具名>」——
            // 浏览器/搬文件这类动作没有 path 参数，硬套「要动文件」会说假话）
            Text(
                text = if (path != null) {
                    stringResource(R.string.baopu_confirm_title, path)
                } else {
                    stringResource(R.string.baopu_confirm_title_action, request.tool)
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            // 摘要：一句话人话（引擎生成；重启后同函数重算）
            Text(
                text = request.summary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            // 提示：批准真的会改（写前自动备份）；拒绝就跳过
            Text(
                text = if (path != null) {
                    stringResource(R.string.baopu_confirm_hint)
                } else {
                    stringResource(R.string.baopu_confirm_hint_action)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        busy = true
                        onApprove()
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f).semantics { contentDescription = approveDesc },
                ) {
                    Text(
                        if (busy) {
                            stringResource(R.string.baopu_confirm_running)
                        } else {
                            stringResource(R.string.baopu_confirm_approve)
                        },
                    )
                }
                OutlinedButton(
                    onClick = {
                        busy = true
                        onReject()
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f).semantics { contentDescription = rejectDesc },
                ) {
                    Text(stringResource(R.string.baopu_confirm_reject))
                }
            }
        }
    }
}
