// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.zhengdao.BuildConfig
import com.example.zhengdao.settings.ApiKeyStore
import com.example.zhengdao.ui.SystemInfoProvider.dirSizeMb
import com.example.zhengdao.ui.AppState.rootfsInstalled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 设置偏好（工作区模式 / 已安装环境版本登记）。 */
object Settings {
    fun prefs(ctx: android.content.Context) =
        ctx.getSharedPreferences("zhengdao-settings", android.content.Context.MODE_PRIVATE)
}

/** 设置页（第二批）：存储占用 / 修复环境 / API Key / 工作区 / 检查更新 / Root / 关于。 */
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    var info by remember { mutableStateOf<SystemInfoProvider.Info?>(null) }
    var repairConfirm by remember { mutableStateOf(false) }
    var updateMsg by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var rootfsMb by remember { mutableStateOf(0L) }
    var homeMb by remember { mutableStateOf(0L) }
    var cacheMb by remember { mutableStateOf(0L) }
    var wsMode by remember {
        mutableStateOf(Settings.prefs(ctx).getString("workspace_mode", "default") ?: "default")
    }
    var wsCustom by remember {
        mutableStateOf(Settings.prefs(ctx).getString("workspace_custom", "") ?: "")
    }

    LaunchedEffect(Unit) {
        info = withContext(Dispatchers.IO) { SystemInfoProvider.collect(ctx) }
        val sizes = withContext(Dispatchers.IO) {
            Triple(
                dirSizeMb(File(ctx.filesDir, "rootfs")),
                dirSizeMb(File(ctx.filesDir, "home")),
                dirSizeMb(ctx.cacheDir),
            )
        }
        rootfsMb = sizes.first; homeMb = sizes.second; cacheMb = sizes.third
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        // ── 存储占用 ──
        SectionCard("存储占用") {
            InfoRow("rootfs（系统层）", "$rootfsMb MB")
            InfoRow("home（登录态与配置）", "$homeMb MB")
            InfoRow("cache（下载缓存）", "$cacheMb MB")
        }

        // ── 修复环境 ──
        SectionCard("修复环境") {
            Text(
                text = "重新解压系统层，保留登录态、API Key 与工作区。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { repairConfirm = true }) { Text("修复环境（30 秒）") }
        }

        // ── API Key 管理 ──
        SectionCard("API Key 管理") {
            ApiKeyStore.PROVIDERS.forEach { (id, envName) ->
                var value by remember(id) {
                    mutableStateOf(ApiKeyStore.get(ctx, id) ?: "")
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(id) },
                    supportingText = { Text(if (value.isBlank()) "启动时注入环境变量 $envName" else "已注入 $envName") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                TextButton(onClick = {
                    ApiKeyStore.save(ctx, id, value.trim())
                    Toast.makeText(ctx, "$id 密钥已保存（Keystore 加密）", Toast.LENGTH_SHORT).show()
                }) { Text("保存 $id") }
                HorizontalDivider()
            }
            var clearKeysConfirm by remember { mutableStateOf(false) }
            TextButton(onClick = { clearKeysConfirm = true }) {
                Text("清除全部密钥", color = MaterialTheme.colorScheme.error)
            }
            if (clearKeysConfirm) {
                AlertDialog(
                    onDismissRequest = { clearKeysConfirm = false },
                    title = { Text("清除全部密钥") },
                    text = { Text("将删除所有已保存的 API Key（不可恢复），确定？") },
                    confirmButton = {
                        TextButton(onClick = {
                            ApiKeyStore.clearAll(ctx)
                            clearKeysConfirm = false
                            Toast.makeText(ctx, "已清除全部密钥", Toast.LENGTH_SHORT).show()
                        }) { Text("清除") }
                    },
                    dismissButton = { TextButton(onClick = { clearKeysConfirm = false }) { Text("取消") } },
                )
            }
        }

        // ── 工作区路径 ──
        SectionCard("工作区路径") {
            Text(
                text = "guest 内路径：/workspace\n" + when (wsMode) {
                    "default" -> "手机侧：应用外部目录 files/workspace（当前设备实测可用的推荐方案）"
                    "custom" -> "手机侧：${wsCustom.ifBlank { "（未填写）" }}（仅 legacy 存储设备可挂载）"
                    else -> "仅使用 guest 私有 /root，不挂载任何共享目录"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            Row {
                FilterChip2("默认", wsMode == "default") { wsMode = "default" }
                Spacer(Modifier.width(6.dp))
                FilterChip2("自定义", wsMode == "custom") { wsMode = "custom" }
                Spacer(Modifier.width(6.dp))
                FilterChip2("仅私有", wsMode == "private") { wsMode = "private" }
            }
            if (wsMode == "custom") {
                OutlinedTextField(
                    value = wsCustom,
                    onValueChange = { wsCustom = it },
                    label = { Text("自定义绝对路径（如 /storage/emulated/0/Download/zhengdao）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            Row(modifier = Modifier.padding(top = 6.dp)) {
                TextButton(onClick = {
                    Settings.prefs(ctx).edit()
                        .putString("workspace_mode", wsMode)
                        .putString("workspace_custom", wsCustom).apply()
                    Toast.makeText(ctx, "已保存，下次启动会话生效", Toast.LENGTH_SHORT).show()
                }) { Text("保存工作区设置") }
                TextButton(onClick = {
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        })
                    } catch (t: Throwable) {
                        Toast.makeText(ctx, "无法打开文件选择器: ${t.message}", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("打开工作区") }
            }
        }

        // ── 检查环境更新 ──
        SectionCard("环境更新") {
            Text(
                text = "系统环境通过此按钮更新。不要在终端内执行 apt upgrade，可能导致环境损坏；语言级依赖优先用 pip / npm 管理。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                enabled = !checking,
                onClick = {
                    checking = true
                    Thread {
                        val result = try {
                            val c = URL("https://api.github.com/repos/pisces19860207/zhengdao/releases/latest")
                                .openConnection() as HttpURLConnection
                            c.connectTimeout = 15000; c.readTimeout = 15000
                            c.setRequestProperty("Accept", "application/vnd.github+json")
                            val body = c.inputStream.bufferedReader().readText()
                            val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                            val asset = Regex("\"name\"\\s*:\\s*\"(debian-[^\"]+\\.tar\\.zst)\"").find(body)?.groupValues?.get(1)
                            if (tag != null && asset != null) {
                                val installed = Settings.prefs(ctx).getString("installed_asset", "") ?: ""
                                if (asset != installed) "发现新版本 $tag（$asset），是否下载？安装后请在终端验证。"
                                else "已是最新版本（$tag）"
                            } else "仓库结构变化，无法解析版本"
                        } catch (t: Throwable) {
                            "检查失败（网络不可达）：${t.message}"
                        }
                        android.os.Handler(ctx.mainLooper).post {
                            updateMsg = result; checking = false
                            if (!result.startsWith("发现新版本")) {
                                Toast.makeText(ctx, result, Toast.LENGTH_LONG).show()
                            }
                        }
                    }.start()
                },
            ) { Text(if (checking) "检查中…" else "检查环境更新") }
        }

        // ── Root 增强模式 ──
        SectionCard("Root 增强模式") {
            val hasSu = remember {
                listOf("/system/bin/su", "/system/xbin/su", "/sbin/su").any { File(it).exists() }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (hasSu) "检测到 Root 设备" else "检测到 Root 后可用",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(checked = false, enabled = hasSu, onCheckedChange = {
                    Toast.makeText(ctx, "即将在 v1.x 版本推出（chroot 后端）", Toast.LENGTH_SHORT).show()
                })
            }
        }

        // ── 国产 ROM 保活指南（第五批前置入口）──
        SectionCard("国产 ROM 保活指南") {
            Text(
                text = "① 开发者选项 → 关闭「子进程限制」\n" +
                    "② 应用启动管理 → 允许「自启动 / 关联启动 / 后台活动」\n" +
                    "③ 多任务界面 → 锁定证道\n" +
                    "④ 电池优化 → 设置为「不允许」",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // ── 关于 ──
        SectionCard("关于") {
            InfoRow("版本", "${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE})")
            TextButton(onClick = {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/pisces19860207/zhengdao")))
            }) { Text("GitHub 仓库") }
            TextButton(onClick = {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/pisces19860207/zhengdao/issues")))
            }) { Text("问题反馈（Issues）") }
        }
    }

    // ── 修复环境二次确认（Compose 版）──
    if (repairConfirm) {
        AlertDialog(
            onDismissRequest = { repairConfirm = false },
            title = { Text("修复环境") },
            text = { Text("将重新解压 Debian 系统层（约 30 秒 + Agent 重装时间）。登录态、API Key 与工作区保留。需要本地已有安装包（cache 或 Download/证道）。确定？") },
            confirmButton = {
                TextButton(onClick = {
                    repairConfirm = false
                    val candidates = listOf(
                        File(ctx.cacheDir, "debian-13.7-base-arm64.tar.zst"),
                        File("/storage/emulated/0/Download/证道/debian-13.7-base-arm64.tar.zst"),
                    ).firstOrNull { it.isFile }
                    if (candidates == null) {
                        Toast.makeText(ctx, "未找到本地安装包：请先在终端重新下载一次", Toast.LENGTH_LONG).show()
                    } else {
                        Thread {
                            try {
                                val archive = File(ctx.cacheDir, candidates.name)
                                if (archive.absolutePath != candidates.absolutePath) candidates.copyTo(archive, true)
                                com.example.zhengdao.rootfs.RootfsInstaller.install(ctx, archive) { }
                                android.os.Handler(ctx.mainLooper).post {
                                    Toast.makeText(ctx, "修复完成：环境已重置，登录态保留", Toast.LENGTH_LONG).show()
                                }
                            } catch (t: Throwable) {
                                android.os.Handler(ctx.mainLooper).post {
                                    Toast.makeText(ctx, "修复失败: ${t.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }.start()
                    }
                }) { Text("修复") }
            },
            dismissButton = {
                TextButton(onClick = { repairConfirm = false }) { Text("取消") }
            },
        )
    }

    // ── 发现新版本弹窗（updateMsg 驱动）──
    updateMsg?.takeIf { it.startsWith("发现新版本") }?.let { msg ->
        AlertDialog(
            onDismissRequest = { updateMsg = null },
            title = { Text("发现环境更新") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = {
                    updateMsg = null
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/pisces19860207/zhengdao/releases"))
                    )
                }) { Text("去下载") }
            },
            dismissButton = { TextButton(onClick = { updateMsg = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(150.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

/** 轻量选择芯片（避免引入额外依赖）。 */
@Composable
fun FilterChip2(label: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Text(label, color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
