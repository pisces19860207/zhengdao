// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十七批 §2/§2.5：**求助卡本体**。
//
// 派工单把话说明白了：③不是「我失败了」一段话，而是一屏**四个可点选项**，每张卡都带
// 四件（分类器／推演／审计／后果）。所以这张卡的形状是被判据钉死的：
// - 选项区是按钮，不是文本（负例⑥「写成一段长文」在这张卡上画不出来）；
// - 四件按 [CardContext.cardSections] 的分段渲染，**审计段只有聚合一句＋一句指路**，
//   明细在决策中心·审计栏（§2.6），这张卡上没有装明细的地方；
// - R3 或不可逆 ⇒ 红字＋「要你确认」（[CardContext.needsRedConfirm]）。
//
// 承载：引擎走既有阻塞口 `ask_user`（`subject = "help:<工具>"`），所以这张卡**不新造
// 弹卡机制**——`BaopuScreen` 按 subject 分流到这里，点一下仍走 `controller.answerChoice`。
// 四件不在请求体里，走 [ToolHelpPending] 单槽（[HelpCardSpec]）。
//
// 文案：本卡自身的三个词（卡名／要你确认／组内工具）在 `strings_baopu_help.xml`（＋values-en），
// 共享 `strings.xml` 零字节；卡上的句子（标题、选项、四件）是 [com.example.zhengdao.baopu.agent.ToolFallback]
// 造的**数据**，与 220 批的影响句同一口径（判据能单测，不藏在 UI 里）。
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
import com.example.zhengdao.baopu.agent.HelpCardSpec
import com.example.zhengdao.baopu.agent.HelpOption
import com.example.zhengdao.ui.theme.zhengdaoErrorText

/**
 * 求助卡（输入栏上方，位置照 137 批的行动卡）。
 *
 * @param card 引擎递来的纯数据（四件＋选项）
 * @param onPick 点某个选项（把 [HelpOption.id] 交回引擎分派）
 */
@Composable
internal fun BaopuHelpCard(
    card: HelpCardSpec,
    onPick: (HelpOption) -> Unit,
) {
    BaopuActionContainer(
        title = stringResource(R.string.baopu_help_title),
        badge = stringResource(R.string.baopu_help_badge),
    ) {
        Text(
            text = card.title,
            style = MaterialTheme.typography.bodyMedium,
        )
        // §2.5 最后一条：R3 与不可逆动作要红字＋「要你确认」
        if (card.context.needsRedConfirm) {
            Text(
                text = stringResource(R.string.baopu_help_confirm),
                style = MaterialTheme.typography.labelLarge,
                color = zhengdaoErrorText(), // 正文级红字走主题色（colorScheme.error 3.55:1 不达 AA）
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        card.context.cardSections().forEach { (head, lines) ->
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Text(
                    text = head,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                lines.forEach { line ->
                    Text(text = line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (card.attempts.isNotEmpty()) {
            Text(
                text = card.attempts.joinToString(" → "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        card.options.forEach { option ->
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // ①与②是「替用户办一件事」⇒ 实心；③与④是要用户出力 ⇒ 描边。
                    if (option.kind == com.example.zhengdao.baopu.agent.HelpOptionKind.GROUP ||
                        option.kind == com.example.zhengdao.baopu.agent.HelpOptionKind.DRAFT_SKILL
                    ) {
                        Button(onClick = { onPick(option) }, modifier = Modifier.fillMaxWidth()) {
                            Text(option.label)
                        }
                    } else {
                        OutlinedButton(onClick = { onPick(option) }, modifier = Modifier.fillMaxWidth()) {
                            Text(option.label)
                        }
                    }
                }
                Text(
                    text = option.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 6.dp),
                )
                if (option.tools.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.baopu_help_group_tools) +
                            option.tools.joinToString("、"),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                    )
                }
            }
        }
    }
}
