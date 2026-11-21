// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百七十四批（2026-11-21）· 治理中心「校准注入」只读块。
//
// 为什么单独一个文件：沿用 561「开发核对」的取舍——治理中心屏同时在飞多单，块本体放这里，
// 屏里只留一行为编排，合并时谁也不蹭谁的行。
//
// 口径（追加三定死的三条，这里一条都不越）：
//   · **只读**：本块没有任何写口，也不给开关；关掉注入的唯一路子是治理级应急关闭（一次性＋需确认＋留痕），
//     那条走 CalibrationGovernance.halt，不在这块里按；
//   · **不做用户开关**：设置页里没有"形神开关"，全新安装零设置即可读到；
//   · **脱敏**：这一行来自 CalibrationGovernance.snapshot()，只有模式、条数与话题类别，
//     没有身体数值，也没有原文。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.ui.SectionCard

/**
 * 「校准注入」只读一行：装配状态 ＋ 本场注入了什么（计数与类别）＋ 是否应急关闭中。
 *
 * @param summary 治理侧快照（[com.example.zhengdao.baopu.calibration.CalibrationGovernance.snapshot]）；
 *   null＝首读未回，先占位，不猜内容。
 */
@Composable
internal fun CalibrationSection(summary: String?) {
    SectionCard(title = stringResource(R.string.gc_calibration_title)) {
        Text(
            text = stringResource(R.string.gc_calibration_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Text(
            text = summary ?: stringResource(R.string.common_loading),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
