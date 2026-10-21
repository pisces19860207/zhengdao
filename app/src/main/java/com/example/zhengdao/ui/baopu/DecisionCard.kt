// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百零四批（2026-10-21，派工单 §1）：**决策卡家族**——授权卡/建议卡/提案卡/
// 效果卡不是四个新组件，而是同一个骨架的四个变体（设计稿 v1.4 §0 决策 #3）。
//
// 骨架（四变体共用，§2）：
//   标题行（徽标 21dp＋18sp 标题）→ 副标题（15sp 为什么）→ 发丝线 →
//   正文（默认 3 行＋展开；证据 chips）→ 按钮行（≥48dp）。
// 高风险变体加贴顶警示带（危险色＋明确动词）；一张卡最多一个强调色
// （判据在 `DecisionSpecRules`，JVM 直测）。
//
// 纪律：本文件**只渲染**，零判据——规格（[DecisionCardSpec]）由接线层
// （BaopuChatController）填好，按钮 id 原样回调，本层不猜用户想干什么。
// 视觉全部走既有 token（MaterialTheme＋圆角常量），不新增第二套视觉语言。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R

// 卡圆角 20 / 按钮圆角 14 / 按钮最低 48dp：**值定义在 BaopuSkin.kt**（第一百零五批收口），
// 本文件只引用——卡与外壳用同一份，改一处不会漏另一处。卡的排版与判据一行没动。

/**
 * 决策卡（四变体共用骨架）——**只渲染**，零判据。
 *
 * @param spec 接线层填好的规格（标题/副标题/徽标/警示带/正文/chips/按钮）
 * @param onAction 按钮 id 原样回调（本层不解释 id）
 * @param modifier 外层修饰（槽位容器给的）
 */
@Composable
internal fun DecisionCard(
    spec: DecisionCardSpec,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 展开态按卡键重置：换一张卡＝收回展开（同 BaopuConfirmCard 的 busy 房型）
    var expanded by remember(spec.key) { mutableStateOf(false) }
    val cardDesc = stringResource(R.string.decision_card_desc, spec.title)

    Surface(
        shape = BaoCardShape,
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = cardDesc },
    ) {
        Column(Modifier.padding(16.dp)) {
            // 高风险警示带（贴顶；设计稿 §2「高风险变体加贴顶警示带」）
            if (spec.warning != null) {
                Surface(
                    shape = BaoChipShape,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = spec.warning,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            // 标题行：徽标＋标题（先结论：标题＝做什么）
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (spec.badge != null) {
                    DecisionBadge(spec.badge, badgeColors(spec.badgeTone))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = spec.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            // 副标题（为什么）
            if (spec.subtitle.isNotBlank()) {
                Text(
                    text = spec.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // 发丝线（1dp；深色不靠投影，§1）
            if (spec.body.isNotBlank() || spec.chips.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            // 正文：默认 3 行＋展开（§2「卡内长文本默认 3 行＋展开」）
            if (spec.body.isNotBlank()) {
                Text(
                    text = spec.body,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (spec.body.length > BODY_COLLAPSE_THRESHOLD) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.heightIn(min = BAO_BUTTON_MIN_HEIGHT_DP.dp),
                    ) {
                        Text(
                            stringResource(
                                if (expanded) R.string.common_collapse_arrow else R.string.common_expand_arrow,
                            ),
                        )
                    }
                }
            }
            // 证据 chips（横滑不换行——不引实验性 FlowRow，也不让 chip 被截断）
            if (spec.chips.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    spec.chips.forEach { chip ->
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = chip,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
            // 按钮行：≥48dp，左否定/中缓冲/右确认（顺序由规格给，角色定样式）
            if (spec.buttons.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    spec.buttons.forEach { b ->
                        Column(Modifier.weight(1f)) {
                            DecisionActionButton(b, onAction)
                            if (!b.enabled && b.note != null) {
                                Text(
                                    text = b.note,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
            // 卡底一行小字（「只弹一次」等）
            if (spec.footer != null) {
                Text(
                    text = spec.footer,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** 正文超过这个长度才给「展开」（短文本不需要折叠控件）。 */
private const val BODY_COLLAPSE_THRESHOLD = 60

/**
 * 徽标底色/字色（按色调分流；全部取既有 colorScheme，不写死十六进制）。
 */
private data class BadgeColors(val container: Color, val content: Color)

@Composable
private fun badgeColors(tone: DecisionBadgeTone): BadgeColors {
    val scheme = MaterialTheme.colorScheme
    return when (tone) {
        DecisionBadgeTone.DANGER -> BadgeColors(scheme.errorContainer, scheme.onErrorContainer)
        DecisionBadgeTone.WARNING -> BadgeColors(scheme.tertiaryContainer, scheme.onTertiaryContainer)
        DecisionBadgeTone.NEUTRAL -> BadgeColors(scheme.secondaryContainer, scheme.onSecondaryContainer)
    }
}

/** 徽标小丸（12sp·600 不随字体缩放的设计意图用 labelSmall＋固定内距近似）。 */
@Composable
private fun DecisionBadge(text: String, colors: BadgeColors) {
    Surface(shape = CircleShape, color = colors.container) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.content,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** 一枚按钮（角色定样式：主/危险＝实心，缓冲＝描边，否定＝文字钮）。 */
@Composable
private fun DecisionActionButton(
    button: DecisionButton,
    onAction: (String) -> Unit,
) {
    val label = button.label
    val desc = stringResource(R.string.decision_button_desc, label)
    val modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = BAO_BUTTON_MIN_HEIGHT_DP.dp)
        .semantics { contentDescription = desc }
    when (button.role) {
        DecisionButtonRole.PRIMARY -> Button(
            onClick = { onAction(button.id) },
            enabled = button.enabled,
            shape = BaoButtonShape,
            modifier = modifier,
        ) { Text(label, maxLines = 2) }

        DecisionButtonRole.DANGER -> Button(
            onClick = { onAction(button.id) },
            enabled = button.enabled,
            shape = BaoButtonShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = modifier,
        ) { Text(label, maxLines = 2) }

        DecisionButtonRole.BUFFER -> OutlinedButton(
            onClick = { onAction(button.id) },
            enabled = button.enabled,
            shape = BaoButtonShape,
            modifier = modifier,
        ) { Text(label, maxLines = 2) }

        DecisionButtonRole.NEGATIVE -> TextButton(
            onClick = { onAction(button.id) },
            enabled = button.enabled,
            shape = BaoButtonShape,
            modifier = modifier,
        ) { Text(label, maxLines = 2) }
    }
}

// 徽标色调由规格（DecisionCardSpec.badgeTone）带——建议卡按风险定色调的纯判据是
// riskBadgeTone（DecisionCardRules.kt），本文件只认色调不认风险。