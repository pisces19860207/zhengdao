// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui.taiji

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.zhengdao.oc.OcModel

/**
 * 模型池选择器（v1.0 任务一）：底部抽屉，按 providerID 分组、免费模型置顶。
 *
 * 空态如实呈现：模型目录来自 models.dev，设备网络不可达时服务端返回空数组
 * （本机实测）——显示原因与自救提示，不装作"没有模型"。
 *
 * [error] 是**拉取失败**的原因（来自 [com.example.zhengdao.oc.TaijiState.lastModelFetchError]）：
 * 空表有两种含义（目录真的为空 / 这次没拉到），只有它能区分，必传不许省略。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(
    models: List<OcModel>,
    override: OcModel?,
    currentModel: String?,
    isLoading: Boolean,
    error: String?,
    onSelectDefault: () -> Unit,
    onSelect: (OcModel) -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "模型池",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                // 常驻刷新入口（2026-10-08）：此前「重新加载」只写在**空列表**分支里，
                // 列表有内容时反而没有任何刷新入口——想换一批模型只能关掉抽屉、再点一次
                // 顶部胶囊（而那条路径本身要先联网拉完才弹，见 TaijiScreen 的注释）。
                // 列表在拉取中时同一个位置显示状态，避免"按了没反应"。
                if (isLoading) {
                    Text(
                        "拉取中…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "⟳ 刷新",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable(onClick = onReload)
                            // 2026-10-08 审查（MINOR-3）：触控区抬到 ≥48dp（Material 最小目标）。
                            // 原先只有 labelLarge 行高 + 6dp 内边距 ≈ 32–33dp——弱网下最需要点它的
                            // 时候最难点到。sizeIn 只约束**最小值**：文字视觉大小完全不变，多出来的
                            // 部分只是点击区（与同批走查把空态「重新加载」8dp→14dp 同一条标准）。
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                    )
                }
            }
            Text(
                "当前：" + (override?.let { "${it.providerID}/${it.id}（本会话覆盖）" }
                    ?: currentModel ?: "服务端默认"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )

            // 拉取失败必须看得见（项目原则）：原先失败也返回空表，用户只看到「⟳ 刷新」闪一下。
            // 列表非空时也要显示——那时空态分支不渲染，但用户刚点的刷新同样失败了。
            // 拉取中不显示：此刻顶行已写「拉取中…」，留着上一次的旧原因只会让人以为这次也失败了。
            if (!isLoading && error != null) {
                Text(
                    "刷新失败：$error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            // 只有**列表为空**时才用整块文案占位（2026-10-08 审查 MINOR-1）：
            // 原先 `if (isLoading)` 排在 `models.isEmpty()` 之前，弱网下刷新会把**已缓存的
            // 可用列表**整块换成「正在拉取模型目录…」——列表没了，用户反而更慌。刷新中的
            // 反馈由顶行的「拉取中…」承担，列表有内容就原地保留。
            if (isLoading && models.isEmpty()) {
                Text(
                    "正在拉取模型目录…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else if (models.isEmpty()) {
                Column(Modifier.padding(vertical = 16.dp)) {
                    // 只有"拉成功了、目录就是空的"才说「暂无可用模型」；拉取失败时上面那行
                    // 「刷新失败：…」已经把真实原因说清楚了，这里再断言一次"没有模型"
                    // 就又把两种空态混成一句了——正是本次要修的那件事。
                    if (error == null) {
                        Text("暂无可用模型", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "模型目录来自 models.dev，当前网络不可达（免费模型也依赖目录）。" +
                            "在代理 App 的「分应用代理」里勾选证道后重试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                    )
                    Text(
                        "重新加载",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable(onClick = onReload)
                            .padding(vertical = 8.dp),
                    )
                }
            } else {
                // 「默认」= 清除本会话的模型覆盖（本地 override 丢弃；服务端回到其默认）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onSelectDefault)
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("默认（清除本会话覆盖）", style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
                LazyColumn(Modifier.height(360.dp)) {
                    val groups = models.groupBy { it.providerID }
                        .toSortedMap(compareBy({ it != "opencode" }, { it }))
                    groups.forEach { (provider, list) ->
                        item(key = "hdr_$provider") {
                            Text(
                                provider,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                            )
                        }
                        val sorted = list.sortedWith(
                            compareByDescending<OcModel> { it.free }.thenBy { it.id }
                        )
                        items(sorted, key = { "${provider}_${it.id}" }) { m ->
                            val selected = override?.id == m.id && override.providerID == m.providerID
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(m) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        m.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold
                                        else MaterialTheme.typography.bodyMedium.fontWeight,
                                    )
                                    Text(
                                        m.id,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (m.free) {
                                    Text(
                                        "免费",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                if (selected) {
                                    Text(
                                        "  ✓",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
