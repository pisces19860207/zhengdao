// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百三十七批（2026-10-22，派工单 §0②）：**信息四档容器的唯一实现处**。
//
// 为什么要有这个文件（不是「又一层封装」）：
//   §6.1 实测过「为什么各卡分不清」——六种不同语义的卡片全用同一个 surfaceVariant 底色，
//   而纸深对纸只有 1.11:1、发丝线对纸 1.31:1，屏幕上等于没分。结论是**不许再靠底色深浅
//   分卡片**：四档必须各占一个不同的「边界位置」。
//
// 四档（界面上只允许这四种，**边界位置四选一、不许叠加**）：
//   | 档     | 底色         | 边界（形状通道，唯一）        | 判据       |
//   | 正文   | 无（落在纸上） | 没有边界                     | 她在说话   |
//   | 行动卡 | 白 CardSurface | 四边 2dp 黛青＋顶部浅青标题条 | 等你动手   |
//   | 信息卡 | 纸深 PaperDeep | 只左边 4dp 黛青竖条          | 知会/回执  |
//   | 记录条 | 同背景        | 只下边 1dp 发丝线            | 历史       |
//
// 三条硬规则（§6.1）：
//   1. 边界位置四选一，不许叠加——十个弱信号不如一个强信号；
//   2. 一屏最多 1 张行动卡（出现两张＝优先级没做，回炉合并或排队）；
//   3. 同屏信息卡 >3 张收起为「还有 N 条」（入口在 `BaopuMemoryCard.kt` 的 MoreItemsRow，
//      判据 CARD_VISIBLE_LIMIT 已钉）。
//
// 纪律：本文件**只有容器**，没有业务判据、没有状态；五档之外不许新造（`ContainerTierGuardTest` 钉着）。

package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.ui.theme.rCard
import com.example.zhengdao.ui.theme.rChip

/** 行动卡四边描边宽度（§6.1：四边 2dp 黛青实线，6.66:1）。 */
internal const val BAO_ACTION_STROKE_DP = 2

/** 行动卡内部留白（§6.1：内距 16）。 */
internal const val BAO_ACTION_INSET_DP = 16

/** 信息卡左侧竖条宽度（§6.1：左边 4dp 黛青，不可缩放）。 */
internal const val BAO_INFO_BAR_DP = 4

/** 信息卡内部留白（§6.1：内距 12）。 */
internal const val BAO_INFO_INSET_DP = 12

/** 记录条视觉行高（§6.1：单行 44dp）。 */
internal const val BAO_RECORD_HEIGHT_DP = 44

/** 记录条内部横向留白（§6.1：内距 8）。 */
internal const val BAO_RECORD_INSET_DP = 8

/** 发丝线宽度（纯装饰分隔，1dp）。 */
internal const val BAO_HAIRLINE_DP = 1

/** 圆角取全 App 八档令牌的 rCard 16（§4 显式收编 v1.5 的卡 20 → 16，不新增第九档）。 */
private val BaoTierShape = RoundedCornerShape(rCard)

// ① 正文档：**没有容器函数**——这是刻意的，不是漏写。
//
// 「没有边界」用零代码表达最准确：助手消息在 `BaopuMessageList.BaopuMessageRow`
// 里走 `Surface(color = Color.Transparent, shape = RectangleShape)`，只保留左上
// 「抱朴」小标，无底、无边、无圆角、无内距。给它写一个 `BaopuBodySlot()` 包一层，
// 反而会让「正文档」看起来像个容器——`ContainerTierGuardTest` 直接查那里的三个参数。
//
// 判据「她在说话」。

/**
 * ② 行动卡：白底 ＋ 四边 2dp 黛青 ＋ 顶部通栏浅青标题条 ＋ 右上琥珀角标。
 *
 * 判据「等你动手」。七张（提问／选择／确认／技能提案／决策／恢复确认）**外形完全一致**，
 * 只有标题条文字与按钮内容不同——这是「3 秒内能指出哪一张在等我动手」的唯一来源。
 *
 * @param title 标题条文字（「提问 · 需要你选」这类；走 strings.xml）
 * @param badge 右上角标（通常「待你操作」；null ⇒ 不显示）
 */
@Composable
internal fun BaopuActionContainer(
    title: String,
    modifier: Modifier = Modifier, // 首个可选参数必须是 modifier（lint ModifierParameter）
    badge: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(BaoTierShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(BAO_ACTION_STROKE_DP.dp, MaterialTheme.colorScheme.primary, BaoTierShape),
    ) {
        // 通栏标题条（浅青）：这是行动卡与信息卡「一眼可分」的第二通道（底色＋位置＋体量）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = BAO_ACTION_INSET_DP.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (badge != null) {
                Spacer(Modifier.weight(1f))
                // 琥珀字（对纸深底 4.66:1 达 AA）＋ 圆角 rChip 8，不描边（避免与四边 2dp 抢）
                Box(
                    Modifier
                        .clip(RoundedCornerShape(rChip))
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
        Column(Modifier.padding(BAO_ACTION_INSET_DP.dp), content = content)
    }
}

/**
 * ③ 信息卡：纸深一档底 ＋ **只左边 4dp 黛青竖条**（其余三边无框）。
 *
 * 判据「知会 / 回执，不当主角」。原稿用过 1dp 发丝线描四边，实测 1.31:1 看不见
 * （§2.1 第 3 条），改为形状通道——WCAG 1.4.1 不许只靠单一颜色通道传达类别。
 *
 * 竖条用 [IntrinsicSize.Min] 撑满内容高度：内容多长条就多长，不随内容断开。
 */
@Composable
internal fun BaopuInfoContainer(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(BaoTierShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .height(IntrinsicSize.Min),
    ) {
        Box(
            Modifier
                .width(BAO_INFO_BAR_DP.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )
        Column(Modifier.padding(BAO_INFO_INSET_DP.dp), content = content)
    }
}

/**
 * ④ 记录条：单行 44dp，**只下边 1dp 发丝线**，左右无框，点开进详情。
 *
 * 判据「历史，不占地方」。行首 `›` 只是视觉折叠符，**整行可点**（热区＝整行 44dp）；
 * 单行不折行，超宽省略号（§6.1 + 约束汇总 133 单口径）。
 *
 * @param text 摘要文案（「上一轮 · 5 步 · 42s · 失败 1」这类，由调用方拼好）
 * @param onClick 点开详情（收起／展开同一个入口）
 */
@Composable
internal fun BaopuRecordRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: String = "\u203a",
) {
    val hairline = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = BAO_RECORD_HEIGHT_DP.dp)
            .clickable(onClick = onClick)
            .drawBehind {
                // 只画下边一条：这是记录条唯一的边界位置（不描四边、不填底）
                val y = size.height - (BAO_HAIRLINE_DP.dp.toPx() / 2f)
                drawLine(
                    color = hairline,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = BAO_HAIRLINE_DP.dp.toPx(),
                )
            }
            .padding(horizontal = BAO_RECORD_INSET_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = leading,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
