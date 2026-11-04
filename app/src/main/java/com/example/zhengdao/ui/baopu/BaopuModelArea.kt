// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百三十四批（2026-11-04，派工单《顶栏对齐设计稿》）：顶栏第二件「模型区」的排版层。
//
// 这里只画，不判：`[徽标] 模型名` 叫什么、哪一张该开着、「仅此一家 / 未配置」该不该出话——
// 全在 `BaopuTopBarRules.kt`（本仓无 Robolectric，能算的必须能 JVM 直测）。
//
// 三条硬口径（派工单逐字）：
//   1. **两个独立命中区**（徽标区 / 模型名区），中间 ≥8dp、各区 ≥44dp；整块**不许**跳「换模型/设置」页；
//   2. **同一时刻只许开一个弹层**——`open` 是单值枚举（不是两个布尔），类型里没有「都开着」这一项；
//   3. 唯一允许跳设置的是**未配置 Key**：那时两颗都显示「未配置」，点它才把人带去设置。
//
// 配色：浅青底 `primaryContainer` ＋ 黛青描边 `primary`（青＝配置区，与跑马灯的朱＝提醒区对分），
// 圆角走 `rPill` 10 / `rBadge` 6 令牌，**零新增颜色 token、零硬编码色值**。
//
// 高：视觉 44dp（与跑马灯同高），命中层 48dp（外壳四档容器纪律里的「视觉高与热区分离」，与 131/138 同一手法）。
//
// 文案落点：a11y 描述走 res（第一百三十八批那条口径——读屏字不硬编码在 .kt 里，英文环境跟着走）。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.ui.theme.rBadge
import com.example.zhengdao.ui.theme.rPill

/**
 * 模型区那一整块：`[徽标] 模型名` ＋ 各自那张弹层。
 *
 * `open` 由调用方从 [BaopuTopBarRules.toggleSheet] 算出来（单值 ⇒ 两张弹层不可能同时展开）；
 * 本函数只按它决定画哪一张，不自己记状态（状态记在两处就会同时开——上一版截图正是那样）。
 */
@Composable
internal fun BaopuModelArea(
    letter: String,
    modelName: String,
    configured: Boolean,
    open: BaopuTopBarSheet,
    providerRows: List<BaopuTopBarRules.ProviderRow>,
    modelRows: List<BaopuTopBarRules.ModelRow>,
    onlyOneProvider: Boolean,
    onBadgeClick: () -> Unit,
    onModelClick: () -> Unit,
    onDismiss: () -> Unit,
    onPickProvider: (String) -> Unit,
    onPickModel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val unconfigured = stringResource(R.string.baopu_topbar_unconfigured)
    val offDesc = stringResource(R.string.baopu_topbar_unconfigured_desc)
    val shape = RoundedCornerShape(rPill)
    Box(
        modifier
            .heightIn(min = BAO_ROW_MIN_HEIGHT_DP.dp)
            .wrapContentSize(Alignment.Center),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(BAO_TOPBAR_PILL_HEIGHT_DP.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.primaryContainer, shape)
                .border(BAO_TOPBAR_PILL_STROKE_DP.dp, MaterialTheme.colorScheme.primary, shape)
                .padding(horizontal = 2.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.zoneHit(
                    desc = if (configured) stringResource(R.string.baopu_topbar_badge_desc) else offDesc,
                    onClick = onBadgeClick,
                ),
            ) {
                if (configured) LetterTile(letter) else ZoneText(unconfigured, emphasize = false)
                if (open == BaopuTopBarSheet.PROVIDER) {
                    ProviderSheet(providerRows, onlyOneProvider, onPickProvider, onDismiss)
                }
            }
            Spacer(Modifier.width(BAO_TOPBAR_ZONE_GAP_DP.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.zoneHit(
                    desc = if (configured) stringResource(R.string.baopu_topbar_model_desc) else offDesc,
                    onClick = onModelClick,
                ),
            ) {
                ZoneText(if (configured) modelName else unconfigured, emphasize = true)
                if (open == BaopuTopBarSheet.MODEL) ModelSheet(modelRows, onPickModel, onDismiss)
            }
        }
    }
}

/** 一颗命中区的外壳：44dp 高 × ≥44dp 宽（派工单硬规矩），点谁由参数给。 */
private fun Modifier.zoneHit(desc: String, onClick: () -> Unit): Modifier = this
    .height(BAO_TOPBAR_PILL_HEIGHT_DP.dp)
    .widthIn(min = BAO_TOPBAR_PILL_HEIGHT_DP.dp)
    .clip(RoundedCornerShape(rPill))
    .clickable(onClick = onClick)
    .semantics { contentDescription = desc }
    .padding(horizontal = 4.dp)

/** 模型区那一段的字（选中区加一档 Medium，未配置态不加——它不是"当前项"）。 */
@Composable
private fun ZoneText(text: String, emphasize: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (emphasize) FontWeight.Medium else FontWeight.Normal,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 自绘字母徽标（判据 2：D/O/G/Z/Q…，**不用官方 logo**）。
 *
 * 官方标识涉商标与许可（逐家确认麻烦、还要进 THIRD-PARTY 声明）；自绘这颗无版权风险、
 * 体积 0、风格统一，且**只用于识别**，不当按钮、不当状态。
 */
@Composable
private fun LetterTile(letter: String) {
    val shape = RoundedCornerShape(rBadge)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(BAO_LETTER_TILE_DP.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary, shape),
    ) {
        Text(
            text = letter,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1,
        )
    }
}

/** 弹层之一：供应商 / 网关（徽标区点开）。没 Key 的一家**不可点**，末尾摆「未配置」。 */
@Composable
private fun ProviderSheet(
    rows: List<BaopuTopBarRules.ProviderRow>,
    onlyOne: Boolean,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val unconfigured = stringResource(R.string.baopu_topbar_unconfigured)
    val onlyOneHint = stringResource(R.string.baopu_topbar_only_one)
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.sheetShell()) {
            if (onlyOne) {
                Text(
                    text = onlyOneHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                )
            } else {
                rows.forEach { row ->
                    SheetRow(row.letter, row.name, row.selected, row.configured, unconfigured) { onPick(row.id) }
                }
            }
        }
    }
}

/** 弹层之二：同一家下的模型（模型名区点开）。条目显示名与顶栏同一条规则，不露原始 ID——除非表里没有。 */
@Composable
private fun ModelSheet(
    rows: List<BaopuTopBarRules.ModelRow>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.sheetShell()) {
            rows.forEach { row ->
                SheetRow(letter = "", title = row.name, selected = row.selected, enabled = true, unconfigured = "") {
                    onPick(row.id)
                }
            }
        }
    }
}

/** 两张弹层共用的容器尺寸（与跑马灯详情同一口径：320×280 上限 ＋ 内部可滚，不推正文）。 */
@Composable
private fun Modifier.sheetShell(): Modifier = this
    .widthIn(max = 320.dp)
    .heightIn(max = 280.dp)
    .verticalScroll(rememberScrollState())
    .padding(horizontal = 8.dp, vertical = 4.dp)

/**
 * 弹层里的一行。选中态**只许**这一种样式（浅青底 `primaryContainer` ＋ 左侧 2dp `primary` 竖条，
 * 与 [com.example.zhengdao.ui.baopu.BaopuChannelSheet] / [com.example.zhengdao.ui.baopu.BaopuDrawer] 同一套）；
 * `enabled = false` ⇒ 这一行不可点（没 Key 的一家切过去只会让下一次请求打空）。
 */
@Composable
private fun SheetRow(
    letter: String,
    title: String,
    selected: Boolean,
    enabled: Boolean,
    unconfigured: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(BAO_CHIP_RADIUS_DP.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = BAO_ROW_MIN_HEIGHT_DP.dp)
            .clip(shape)
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        if (selected) {
            Box(
                Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp),
        ) {
            if (letter.isNotEmpty()) {
                LetterTile(letter)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!enabled && unconfigured.isNotEmpty()) {
                Text(
                    text = unconfigured,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
