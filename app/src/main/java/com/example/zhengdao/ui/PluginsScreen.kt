// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 插件页（2026-10-07 新增）：把 OpenCode 插件从"藏在配置文件里"改为**显性可管**。
 *
 * 三块内容：
 *  1. **已启用** —— 从太极实例 `opencode.json` 的 `plugin` 数组实时读出，逐项可开关；
 *  2. **推荐** —— 轻量免模型的记忆方案，一键启用（不自动写入，避免再出现"装了不工作"）；
 *  3. **插件包缓存** —— Bun 从 npm 装下来的包，可看体积、可一键清理。
 *
 * ⚠️ 作用域（用户 2026-10-07 裁决 ①②）：插件是 OpenCode 的能力，本页**只管太极实例**。
 * 终端里那份自装的 npm 版 opencode 由用户自己负责，App 不为它读写插件配置。
 */
@Composable
fun PluginsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var tick by remember { mutableStateOf(0) }
    var specs by remember { mutableStateOf<List<String>>(emptyList()) }
    var cached by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
    var versions by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var envReady by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var cleanConfirm by remember { mutableStateOf(false) }
    // 「添加插件」输入框（2026-10-08）：手输 npm 包名，见下方那个 SectionCard
    var specInput by remember { mutableStateOf("") }
    var specError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tick) {
        val data = withContext(Dispatchers.IO) {
            val cfg = PluginManager.taijiConfig(ctx)
            val list = PluginManager.readSpecs(cfg)
            val cache = PluginManager.scanCaches(ctx)
            val vs = HashMap<String, String?>()
            list.forEach { vs[it] = PluginManager.cachedVersion(ctx, it) }
            PluginManager.RECOMMENDED.forEach { vs[it.spec] = PluginManager.cachedVersion(ctx, it.spec) }
            Triple(cfg.isFile, list, cache to vs)
        }
        envReady = data.first
        specs = data.second
        cached = data.third.first
        versions = data.third.second
    }

    /**
     * 统一入口：改开关 → 落盘 → 重扫。
     *
     * [onDone] 拿到本次**实际写盘的结果**（n > 0 才是真改了配置）——「添加插件」要靠它判断
     * 成功后才清空输入框（2026-10-08 修复：此前无条件清空，添加失败时用户刚粘贴的包名就丢了）。
     */
    fun toggle(spec: String, on: Boolean, onDone: (Int) -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch {
            val n = withContext(Dispatchers.IO) { PluginManager.setEnabled(ctx, spec, on) }
            busy = false
            msg = when {
                n > 0 && on -> "已启用：$spec（下次启动 Agent 时生效）"
                n > 0 -> "已停用：$spec"
                !envReady -> "太极的 OpenCode 尚未初始化，先到「太极」启动一次"
                else -> "状态无变化"
            }
            onDone(n)
            tick++
        }
    }

    // 开关所在行：名称 + 版本/大小副行 + 右侧 Switch
    @Composable
    fun PluginRow(spec: String, on: Boolean, subtitle: String) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = PluginManager.recommendOf(spec)?.title ?: spec,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (PluginManager.recommendOf(spec) != null) {
                    Text(
                        text = spec,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = on, enabled = !busy, onCheckedChange = { toggle(spec, it) })
        }
    }

    fun subtitleFor(spec: String, enabled: Boolean): String {
        val v = versions[spec]
        val legacy = PluginManager.isLegacy(spec)
        val verText = when {
            legacy -> "⚠️ 已废弃插件（曾装上但不工作），建议停用"
            v != null -> "已缓存 v$v"
            enabled -> "尚未下载（首次启动 Agent 时联网安装）"
            else -> ""
        }
        return verText
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // ── 已启用 ──
        SectionCard("已启用") {
            if (specs.isEmpty()) {
                Text(
                    text = if (envReady) "还没有启用任何插件。可到下面「推荐」里一键添加。"
                    else "太极的 OpenCode 尚未初始化——先到「太极」启动一次，插件配置会自动生成。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                specs.forEach { spec ->
                    PluginRow(spec, on = true, subtitle = subtitleFor(spec, true))
                }
            }
        }

        // ── 推荐 ──
        SectionCard("推荐插件") {
            PluginManager.RECOMMENDED.forEach { r ->
                val on = specs.any { PluginManager.samePackage(it, r.spec) }
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                r.spec,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        // 已启用时不再摆第二个开关——上面「已启用」区已经有开关了，
                        // 同一页出现两个控制同一件事的开关只会让人怀疑它们是否同步。
                        if (on) {
                            Text(
                                text = "已启用",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Switch(
                                checked = false,
                                enabled = !busy && envReady,
                                onCheckedChange = { toggle(r.spec, it) },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = r.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── 手动添加（2026-10-08）──
        // 用户问「有没有新增插件的入口」——此前只有「推荐」里那一项，想装别的只能去改
        // opencode.json，等于把用户赶回终端。OpenCode 没有插件市场，插件的标识就是
        // **npm 包名**，所以这里只做一件事：把包名写进 plugin 数组（启动 Agent 时由 Bun 装）。
        SectionCard("添加插件（npm 包名）") {
            OutlinedTextField(
                value = specInput,
                onValueChange = { specInput = it; specError = null },
                singleLine = true,
                label = { Text("例如 @scope/opencode-plugin-xxx") },
                modifier = Modifier.fillMaxWidth(),
                // 与全仓一致：**绝不用密码类型**（国产 ROM 会弹安全键盘，v3 §7 红线）
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                ),
                isError = specError != null,
            )
            specError?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                enabled = !busy && envReady,
                onClick = {
                    val spec = PluginManager.normalizeSpecInput(specInput)
                    if (spec == null) {
                        specError = "这不像一个 npm 包名：应形如 my-plugin 或 @scope/my-plugin，且不含空格"
                    } else {
                        // 只有**真写进配置**（n > 0）才清空输入框：失败时保留原文，用户能直接改错重试，
                        // 不用重新去 npm 页面复制一遍。失败提示必须带上包名，否则用户只看到一句泛泛的
                        // 「状态无变化」，不知道说的是哪一个（2026-10-08 修复）。
                        toggle(spec, true) { n ->
                            if (n > 0) {
                                specInput = ""
                            } else {
                                specError = if (envReady) "未写入配置：$spec（可能已在插件列表中）"
                                else "未添加：$spec —— 太极的 OpenCode 尚未初始化，先到「太极」启动一次"
                            }
                        }
                    }
                },
            ) { Text("添加并启用") }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "包名从插件的 npm / GitHub 页面复制。添加后由 opencode 内置的 Bun 在下次" +
                    "启动 Agent 时从 npm 下载（首次需要联网）。插件会执行第三方代码——只添加你信任的包。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── 缓存 ──
        val totalMb = cached.sumOf { it.second }
        SectionCard("插件包缓存（共 $totalMb MB）") {
            if (cached.isEmpty()) {
                Text(
                    text = "暂无缓存。插件首次启动时由 opencode 内置的 Bun 从 npm 下载到此，之后离线可用。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                cached.sortedByDescending { it.second }.forEach { (name, mb) ->
                    // 包名（含 scope）能很长，不能让它把体积数字挤到贴边——单行省略号
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "$mb MB",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { cleanConfirm = true }) { Text("清理插件包缓存") }
                Text(
                    text = "清理不会停用插件——下次启动 Agent 时会重新联网下载。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        // ── 说明 ──
        SectionCard("关于插件") {
            Text(
                text = "OpenCode 没有官方「插件市场」。插件的加载方式只有两种：在 opencode.json 里写包名" +
                    "（启动时由 Bun 自动从 npm 安装），或引用本地 .js 文件。本页管理的即前者。\n\n" +
                    "插件会扩展 Agent 的能力（如记忆、工具），但也会执行第三方代码——只启用你信任的插件。\n\n" +
                    "本页只作用于**太极**的 OpenCode 实例；终端里自装的 opencode 不在管理范围内。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        msg?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    if (cleanConfirm) {
        AlertDialog(
            onDismissRequest = { cleanConfirm = false },
            title = { Text("清理插件包缓存？") },
            text = { Text("将删除已下载的插件包（约 ${cached.sumOf { it.second }} MB）。插件本身保持启用，下次启动会重新下载。") },
            confirmButton = {
                TextButton(onClick = {
                    cleanConfirm = false
                    busy = true
                    scope.launch {
                        val freed = withContext(Dispatchers.IO) { PluginManager.clearCaches(ctx) }
                        busy = false
                        msg = "已清理，回收约 $freed MB"
                        tick++
                    }
                }) { Text("清理") }
            },
            dismissButton = { TextButton(onClick = { cleanConfirm = false }) { Text("取消") } },
        )
    }
}
