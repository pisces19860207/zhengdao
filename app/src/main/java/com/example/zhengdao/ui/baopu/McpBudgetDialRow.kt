// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百四十单 · 设置 → MCP 服务 的那一行「外部工具占用上限」滑杆（纯 UI）。
//
// 接线纪律（本单不许碰预算算法）：
// 1. 读写都走 [BaopuRuntime.mcpBudgetStore] —— 与装配器 McpBudgetWiring 共用的**同一份**
//    进程级账本；装配器每步现读 budget.ratio()，所以这里 setRatio 完，下一轮装配就是新额度，
//    不要求重启 App、不重建引擎。
// 2. 盘上仍是 630 的 `baopu_mcp` / `assembly` 键里的 ratio 字段：本文件不新建
//    SharedPreferences、不新增第二份配置键。
// 3. 范围/步长/默认/换算全在 [McpBudgetDial] 一份判据里，这里只画控件。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.example.zhengdao.baopu.BaopuRuntime

/**
 * 一行：主标签 + 当前值 + 滑杆 + 副标题。
 *
 * 初值直接读账本的 ratio() 覆盖值；没配过 = null ⇒ 默认 8%，
 * 所以「拖动 → 落 prefs → 退出重进设置」显示的还是新值。
 */
@Composable
internal fun McpBudgetDialRow() {
    val context = LocalContext.current
    // 进程级单例：与 McpBudgetWiring 同一实例 ⇒ 改动即时生效。
    val budget = remember { BaopuRuntime.mcpBudgetStore(context) }
    var percent by remember { mutableIntStateOf(McpBudgetDial.percentOf(budget.ratio())) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = McpBudgetDial.LABEL,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = McpBudgetDial.valueText(percent),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = percent.toFloat(),
            onValueChange = { raw ->
                percent = McpBudgetDial.commitIfChanged(
                    current = percent,
                    next = McpBudgetDial.percentOfSlider(raw),
                    setRatio = { ratio -> budget.setRatio(ratio) },
                )
            },
            valueRange = McpBudgetDial.SLIDER_RANGE,
            steps = McpBudgetDial.SLIDER_STEPS,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = McpBudgetDial.SUBTITLE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
