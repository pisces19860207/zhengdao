// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui.taiji

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(
    models: List<OcModel>,
    override: OcModel?,
    currentModel: String?,
    isLoading: Boolean,
    onSelectDefault: () -> Unit,
    onSelect: (OcModel) -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("模型池", style = MaterialTheme.typography.titleMedium)
            Text(
                "当前：" + (override?.let { "${it.providerID}/${it.id}（本会话覆盖）" }
                    ?: currentModel ?: "服务端默认"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )

            if (isLoading) {
                Text(
                    "正在拉取模型目录…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else if (models.isEmpty()) {
                Column(Modifier.padding(vertical = 16.dp)) {
                    Text("暂无可用模型", style = MaterialTheme.typography.bodyMedium)
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
