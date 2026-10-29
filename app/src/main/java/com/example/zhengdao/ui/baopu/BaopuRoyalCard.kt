// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十七批（派工单 §2 · M4 御案卡面）：**御案卡**——行动卡家族第十三张，
// 壳照第二百三十七批求助卡（同一个 `BaopuActionContainer`，不新造卡型）。
//
// 卡面只有三行（改什么／为什么／影不影响你）＋一个真假标注＋一组按钮（含默认项）。
// **判据全在 [RoyalCardGate]**（纯函数、JVM 直测），本文件只渲染：
// 判据不过 ⇒ 一个像素都不画（`return`），并留一行日志说明为什么拒——
// "4 行也照画、回头再改"是派工单点名的负例，所以拒渲染写在组件入口，不在调用方。
//
// 纪律：文案全走 `R.string`（`strings_baopu_royal.xml` 中英双份），本文件不发明字符串。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.core.RunLog

/**
 * 一张御案卡。
 *
 * @param card 卡面数据（三行＋选项）；判据在渲染入口再查一遍——**别处的校验不算数**
 * @param onPick 选项 id 原样回调（本层不解释 id，与 `DecisionCard` 同一口径）
 */
@Composable
internal fun BaopuRoyalCard(
    card: RoyalCard,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!RoyalCardGate.renderable(card)) {
        // 拒渲染也要留痕（否则"为什么这张卡没出现"只能靠猜）；
        // 用 LaunchedEffect 而不是在组合期直接写日志：组合可以重入，日志不该跟着重入。
        LaunchedEffect(card.key) { RunLog.log(RoyalCardGate.rejectLine(card)) }
        return
    }
    val markText = stringResource(royalMarkRes(card.mark))
    val desc = stringResource(R.string.royal_card_desc, markText)

    BaopuActionContainer(
        title = stringResource(R.string.royal_card_title),
        modifier = modifier.semantics { contentDescription = desc },
        badge = markText,
    ) {
        card.lines.forEach { RoyalLineRow(it) }
        Spacer(Modifier.heightIn(min = 10.dp))
        RoyalOptionRow(card.options, onPick)
        RoyalEvidence(card.evidence)
    }
}

/** 一行（标签＋正文；三行标签对齐，正文才不参差）。 */
@Composable
private fun RoyalLineRow(line: RoyalLine) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = stringResource(royalLineRes(line.kind)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(ROYAL_LINE_LABEL_WIDTH_DP.dp),
        )
        Text(
            text = line.text,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** 按钮行：默认项实心（最容易被拇指够到的那一格），其余描边。 */
@Composable
private fun RoyalOptionRow(options: List<RoyalOption>, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { opt ->
            Column(Modifier.weight(1f)) {
                val minHeight = Modifier
                    .fillMaxWidth()
                    .heightIn(min = BAO_BUTTON_MIN_HEIGHT_DP.dp)
                if (opt.isDefault) {
                    Button(onClick = { onPick(opt.id) }, shape = BaoButtonShape, modifier = minHeight) {
                        Text(text = opt.label, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    OutlinedButton(onClick = { onPick(opt.id) }, shape = BaoButtonShape, modifier = minHeight) {
                        Text(text = opt.label)
                    }
                }
                // 每个选项都写清"点了会怎样"（278 判据②）；默认项前面标出来
                val hint = if (opt.isDefault) {
                    stringResource(R.string.royal_option_default) + " · " + opt.consequence
                } else {
                    opt.consequence
                }
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * 证据区（278）：技术字段**只许活在这里**——卡面三行一个字都不带。
 *
 * 默认折起来：想核的人点开看账本行，不想核的人不被这些字打扰。
 */
@Composable
private fun RoyalEvidence(evidence: List<String>) {
    if (evidence.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        TextButton(
            onClick = { open = !open },
            modifier = Modifier.heightIn(min = BAO_BUTTON_MIN_HEIGHT_DP.dp),
        ) {
            Text(
                text = stringResource(R.string.royal_evidence_title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (open) {
            evidence.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 行标签列宽（三行标签对齐，正文才不参差）。 */
private const val ROYAL_LINE_LABEL_WIDTH_DP = 62

/** 真假标注 → 文案（"未核实"不许被写成"真"：这层映射是唯一出口）。 */
private fun royalMarkRes(mark: RoyalTruth): Int = when (mark) {
    RoyalTruth.TRUE -> R.string.royal_mark_true
    RoyalTruth.UNVERIFIED -> R.string.royal_mark_unverified
    RoyalTruth.FALSE -> R.string.royal_mark_false
}

/** 行身份 → 标签文案。 */
private fun royalLineRes(kind: RoyalLineKind): Int = when (kind) {
    RoyalLineKind.WHAT -> R.string.royal_line_what
    RoyalLineKind.WHY -> R.string.royal_line_why
    RoyalLineKind.IMPACT -> R.string.royal_line_impact
}
