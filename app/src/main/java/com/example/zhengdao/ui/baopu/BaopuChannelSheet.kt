// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九批追加（2026-10-17 刀 1 补丁）§1.1 选型 (b)——抱朴页「换通道」小按钮
// 弹 bottom sheet：列出 Providers.all() 三项 + 自定义 baseUrl，选完即生效。
// 红线：res/** 零改动 ⇒ 本文件文案全部代码内中文（与引擎 notice 同口径；i18n 归设置体系）。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.zhengdao.baopu.LlmProtocol
import com.example.zhengdao.baopu.Provider
import com.example.zhengdao.baopu.ProviderPrefs
import com.example.zhengdao.baopu.Providers

/**
 * 换通道弹层（刀 1 补丁）。
 *
 * 结构：三个预设单选 + 自定义通道单选（baseUrl/model/key 三件套）。
 * 「启用此通道」保存即生效（调用方负责 switchChannel + 刷新页面）。
 * 免费通道不藏短处：说明文案直接写"当前不可用（2026-10-17 实测 403）"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BaopuChannelSheet(
    currentChannelId: String,
    storedKeyOf: (String) -> String,
    customBaseUrl: String?,
    customModel: String?,
    onDismiss: () -> Unit,
    onSave: (channelId: String, apiKey: String, baseUrl: String?, model: String?) -> Unit,
) {
    var selected by remember { mutableStateOf(currentChannelId) }
    var keyText by remember { mutableStateOf(storedKeyOf(currentChannelId)) }
    var baseUrlText by remember { mutableStateOf(customBaseUrl.orEmpty()) }
    var modelText by remember { mutableStateOf(customModel.orEmpty()) }

    val candidates = remember { Providers.all() }
    val customValid = selected != ProviderPrefs.CHANNEL_CUSTOM ||
        (baseUrlText.trim().startsWith("https://") && modelText.isNotBlank())

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                "抱朴通道",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                "免费通道当前不可用（2026-10-17 实测 403 地区限制）——要抱朴能干活，" +
                    "选一个预设填上你的 API Key，或用自定义通道。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            candidates.forEach { p ->
                ChannelRow(
                    provider = p,
                    selected = selected == p.id,
                    onSelect = {
                        selected = p.id
                        keyText = storedKeyOf(p.id)
                    },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ChannelRow(
                provider = CUSTOM_ROW,
                selected = selected == ProviderPrefs.CHANNEL_CUSTOM,
                onSelect = {
                    selected = ProviderPrefs.CHANNEL_CUSTOM
                    keyText = storedKeyOf(ProviderPrefs.CHANNEL_CUSTOM)
                },
            )
            if (selected == ProviderPrefs.CHANNEL_CUSTOM) {
                OutlinedTextField(
                    value = baseUrlText,
                    onValueChange = { baseUrlText = it },
                    label = { Text("API 地址（https:// 开头，如 https://api.deepseek.com）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = modelText,
                    onValueChange = { modelText = it },
                    label = { Text("模型名（如 deepseek-chat）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (selected != Providers.BUILTIN_FREE.id) {
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    label = { Text("API Key（只存本机，不进日志不进库）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(
                    onClick = {
                        val base = if (selected == ProviderPrefs.CHANNEL_CUSTOM) baseUrlText.trim() else null
                        val model = if (selected == ProviderPrefs.CHANNEL_CUSTOM) modelText.trim() else null
                        onSave(selected, keyText.trim(), base, model)
                    },
                    enabled = customValid,
                    modifier = Modifier.padding(start = 12.dp),
                ) { Text("启用此通道") }
            }
        }
    }
}

/** 单选行（预设与自定义共用；自定义通道用占位 Provider 撑 UI）。 */
private val CUSTOM_ROW = Provider(
    id = "__custom_row__",
    label = "自定义（OpenAI 兼容）",
    protocol = LlmProtocol.OPENAI_COMPAT,
    baseUrl = "https://example.invalid",
    model = "-",
)

@Composable
private fun ChannelRow(provider: Provider, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.padding(start = 4.dp)) {
            Text(provider.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (provider.id == Providers.BUILTIN_FREE.id) {
                    "${provider.model} · 当前不可用（403）"
                } else {
                    provider.model
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
