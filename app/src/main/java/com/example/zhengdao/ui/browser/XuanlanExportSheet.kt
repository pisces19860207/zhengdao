// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.browser.PageExportPolicy

/**
 * 「预览尺寸与导出」半屏（651）。
 *
 * 两段，与用户口径一一对应：
 * 1. **设备尺寸预设**：手机 / 平板 / 桌面 / 自定义。手机＝跟随屏幕（默认，零改动）；
 *    其余三档把页面按那个 CSS 尺寸排，再等比缩小显示（[PageExportPolicy.fitScale]）。
 *    这里只**发选择**，真正改视口的是 `BrowserScreen` 的页面区（判据唯一）。
 * 2. **导出**：PNG（可视区）/ PNG（整页）/ PDF 三枚键。**未加载完一律禁用并写明原因**
 *    （[PageExportPolicy.Refusal] → 一句人话）——这就是"拒绝导出并提示"那条判据的界面半，
 *    另一半在执行层（`BrowserScreen.submitExport` 里同一判据再判一次，防状态在点击瞬间变化）。
 *
 * 本文件不碰 WebView、不碰存储：只把用户的选择往回发（[onPreset] / [onCustomWidth] /
 * [onExport]），动作本体全在主屏（与底栏"只发回调"同一条纪律）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun XuanlanExportSheet(
    preset: PageExportPolicy.Preset,
    customWidthDp: Int,
    scalePercent: Int,
    refusal: PageExportPolicy.Refusal,
    busy: Boolean,
    onPreset: (PageExportPolicy.Preset) -> Unit,
    onCustomWidth: (Int) -> Unit,
    onExport: (ExportKind) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = xuanLanPalette()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ready = refusal == PageExportPolicy.Refusal.NONE && !busy
    var widthText by remember(customWidthDp) { mutableStateOf(customWidthDp.toString()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.stripBg,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.xuanlan_export_title),
                style = MaterialTheme.typography.titleMedium,
                color = palette.ink,
            )
            Spacer(Modifier.height(12.dp))

            // ── 四档预设 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PresetChip(R.string.xuanlan_preset_phone, preset == PageExportPolicy.Preset.PHONE, palette, Modifier.weight(1f)) {
                    onPreset(PageExportPolicy.Preset.PHONE)
                }
                PresetChip(R.string.xuanlan_preset_tablet, preset == PageExportPolicy.Preset.TABLET, palette, Modifier.weight(1f)) {
                    onPreset(PageExportPolicy.Preset.TABLET)
                }
                PresetChip(R.string.xuanlan_preset_desktop, preset == PageExportPolicy.Preset.DESKTOP, palette, Modifier.weight(1f)) {
                    onPreset(PageExportPolicy.Preset.DESKTOP)
                }
                PresetChip(R.string.xuanlan_preset_custom, preset == PageExportPolicy.Preset.CUSTOM, palette, Modifier.weight(1f)) {
                    onPreset(PageExportPolicy.Preset.CUSTOM)
                }
            }

            // ── 自定义宽度（只有选了自定义才出现，免得常态多一个输入框） ──
            if (preset == PageExportPolicy.Preset.CUSTOM) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(
                            R.string.xuanlan_preset_custom_hint,
                            PageExportPolicy.MIN_CUSTOM_WIDTH_DP,
                            PageExportPolicy.MAX_CUSTOM_WIDTH_DP,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkDisabled,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.clusterPrimaryBg)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        BasicTextField(
                            value = widthText,
                            onValueChange = { raw ->
                                val digits = raw.filter { it.isDigit() }.take(4)
                                widthText = digits
                                digits.toIntOrNull()?.let(onCustomWidth)
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.ink),
                            cursorBrush = SolidColor(palette.focusRing),
                            modifier = Modifier.width(72.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = if (preset == PageExportPolicy.Preset.PHONE) {
                    stringResource(R.string.xuanlan_preset_follow_screen)
                } else {
                    stringResource(R.string.xuanlan_preset_scale, scalePercent)
                },
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkDisabled,
            )

            Spacer(Modifier.height(16.dp))

            // ── 三种导出 ──
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ExportRow(stringResource(R.string.xuanlan_export_png_view), ready, palette) {
                    onExport(ExportKind.PNG_VIEWPORT)
                }
                ExportRow(stringResource(R.string.xuanlan_export_png_full), ready, palette) {
                    onExport(ExportKind.PNG_FULL)
                }
                ExportRow(stringResource(R.string.xuanlan_export_pdf), ready, palette) {
                    onExport(ExportKind.PDF)
                }
            }

            // ── 未加载完的那句话（禁用时唯一解释）──
            val reason = refusalText(refusal)
            if (reason != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkDisabled,
                )
            } else if (busy) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.xuanlan_export_busy),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkDisabled,
                )
            }
        }
    }
}

/** 三种导出（判据在 [PageExportPolicy]，这里只做枚举）。 */
internal enum class ExportKind { PNG_VIEWPORT, PNG_FULL, PDF }

/** 拒绝理由 → 一句人话；可以导出时返回 null（不多说一句废话）。 */
@Composable
internal fun refusalText(refusal: PageExportPolicy.Refusal): String? = when (refusal) {
    PageExportPolicy.Refusal.NONE -> null
    PageExportPolicy.Refusal.NO_TAB -> stringResource(R.string.xuanlan_refuse_no_tab)
    PageExportPolicy.Refusal.RESTORED -> stringResource(R.string.xuanlan_refuse_restored)
    PageExportPolicy.Refusal.RECYCLED -> stringResource(R.string.xuanlan_refuse_recycled)
    PageExportPolicy.Refusal.LOADING -> stringResource(R.string.xuanlan_refuse_loading)
    PageExportPolicy.Refusal.NOT_LOADED -> stringResource(R.string.xuanlan_refuse_not_loaded)
    PageExportPolicy.Refusal.NOT_RENDERED -> stringResource(R.string.xuanlan_refuse_not_rendered)
}

/** 预设片：选中＝黛青描边＋浅青底；未选＝发丝线描边。 */
@Composable
private fun PresetChip(
    labelRes: Int,
    selected: Boolean,
    palette: XuanLanPalette,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (selected) palette.homeFill else palette.clusterPrimaryBg)
            .border(if (selected) 2.dp else 1.dp, if (selected) palette.focusRing else palette.stripStroke, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelLarge,
            color = palette.ink,
        )
    }
}

/** 一枚导出键（整行宽、48dp 高、左对齐）；不可用时降到禁用墨并吞掉点击。 */
@Composable
private fun ExportRow(
    label: String,
    enabled: Boolean,
    palette: XuanLanPalette,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.clusterPrimaryBg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) palette.ink else palette.inkDisabled,
        )
    }
}
