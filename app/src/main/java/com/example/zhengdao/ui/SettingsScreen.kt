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
import com.example.zhengdao.rootfs.RootfsDownloader
import com.example.zhengdao.rootfs.RootfsInstaller
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
    var pendingUpdateUrl by remember { mutableStateOf<String?>(null) }
    var pendingUpdateSha by remember { mutableStateOf<String?>(null) }
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

        // ── API Key 管理（按 国内 / 国外 / 免费额度 分组展示）──
        SectionCard("API Key 管理") {
            ApiKeyStore.GROUPS.forEach { (groupLabel, ids) ->
                Text(
                    groupLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                ids.forEach { id ->
                    val envName = ApiKeyStore.PROVIDERS[id] ?: return@forEach
                    var value by remember(id) {
                        mutableStateOf(ApiKeyStore.get(ctx, id) ?: "")
                    }
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text(id) },
                        supportingText = {
                            Text(
                                when {
                                    value.isBlank() && id == "zhipu" -> "注入 ZHIPU_API_KEY（国内站 bigmodel.cn）"
                                    value.isBlank() -> "启动时注入环境变量 $envName"
                                    else -> "已注入 $envName"
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    TextButton(onClick = {
                        ApiKeyStore.save(ctx, id, value.trim())
                        Toast.makeText(ctx, "$id 密钥已保存（Keystore 加密）", Toast.LENGTH_SHORT).show()
                    }) { Text("保存 $id") }
                    HorizontalDivider()
                }
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

        // ── 检查环境更新（第三批：manifest 对比 + 应用内下载安装，不自动检查）──
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
                        // 待下载目标（发现新版本时由检查逻辑填入，弹窗确认后用）
                        var pendingUrl: String? = null
                        var pendingSha: String? = null
                        val result = try {
                            // 优先拉 manifest（版本号 + 直链 + SHA256 一条龙）
                            val manifestText = com.example.zhengdao.rootfs.RootfsDownloader
                                .fetchText(com.example.zhengdao.rootfs.RootfsCache.MANIFEST_URL)
                            if (manifestText != null) {
                                val ver = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(manifestText)?.groupValues?.get(1)
                                val url = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(manifestText)?.groupValues?.get(1)
                                val sha = Regex("\"sha256\"\\s*:\\s*\"([^\"]+)\"").find(manifestText)?.groupValues?.get(1)
                                val installed = com.example.zhengdao.rootfs.RootfsCache.currentVersion(ctx)
                                when {
                                    ver == null || url == null ->
                                        "manifest 格式异常，无法解析版本"
                                    installed != null && ver == installed ->
                                        "已是最新版本（$installed）"
                                    else -> {
                                        pendingUrl = url; pendingSha = sha
                                        "发现新版本 $ver（当前 $installed），是否下载安装？安装包将缓存到 Download/zhengdao/cache，旧包自动保留。"
                                    }
                                }
                            } else {
                                // manifest 不可达：降级走 Releases API（仅提示 + 跳转）
                                val c = URL("https://api.github.com/repos/pisces19860207/zhengdao/releases/latest")
                                    .openConnection() as HttpURLConnection
                                c.connectTimeout = 15000; c.readTimeout = 15000
                                c.setRequestProperty("Accept", "application/vnd.github+json")
                                val body = c.inputStream.bufferedReader().readText()
                                val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                                if (tag != null) "仓库最新发布：$tag（应用内直装通道未就绪，可点「去下载」手动获取安装包）"
                                else "仓库结构变化，无法解析版本"
                            }
                        } catch (t: Throwable) {
                            "检查失败（网络不可达）：${t.message}"
                        }
                        android.os.Handler(ctx.mainLooper).post {
                            updateMsg = result; checking = false
                            pendingUpdateUrl = pendingUrl
                            pendingUpdateSha = pendingSha
                            if (!result.startsWith("发现新版本")) {
                                Toast.makeText(ctx, result, Toast.LENGTH_LONG).show()
                            }
                        }
                    }.start()
                },
            ) { Text(if (checking) "检查中…" else "检查环境更新") }
            Spacer(Modifier.height(4.dp))
            Text(
                "Agent 清单：${AgentManifest.cachedVersionText(ctx)}（安装命令可免发版更新，进主页时自动刷新）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                Toast.makeText(ctx, "正在刷新 Agent 清单…", Toast.LENGTH_SHORT).show()
                AgentManifest.refresh(ctx, force = true) { applied ->
                    android.os.Handler(ctx.mainLooper).post {
                        Toast.makeText(
                            ctx,
                            if (applied) "Agent 清单已更新" else "刷新失败（沿用现有清单）",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }) { Text("立即刷新 Agent 清单") }
        }

        // ── 安装包缓存（第三批）──
        SectionCard("安装包缓存") {
            var cacheText by remember { mutableStateOf("统计中…") }
            var rollbacks by remember { mutableStateOf<List<File>>(emptyList()) }
            var rollbackConfirm by remember { mutableStateOf<File?>(null) }
            fun reloadCache() {
                Thread {
                    val archives = com.example.zhengdao.rootfs.RootfsCache.listArchives(ctx)
                    val dir = com.example.zhengdao.rootfs.RootfsCache.dir(ctx)
                    val current = com.example.zhengdao.rootfs.RootfsCache.currentVersion(ctx)
                    val lines = buildString {
                        appendLine("目录：${dir.path}")
                        appendLine("已缓存 ${archives.size} 个安装包（当前环境：${current ?: "未安装"}）")
                        archives.forEach {
                            appendLine("· ${it.name}（${it.length() / (1024 * 1024)} MB）")
                        }
                    }
                    val rb = com.example.zhengdao.rootfs.RootfsCache.rollbackCandidates(ctx)
                    android.os.Handler(ctx.mainLooper).post { cacheText = lines; rollbacks = rb }
                }.start()
            }
            LaunchedEffect(Unit) { reloadCache() }
            Text(cacheText, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = {
                Thread {
                    val n = com.example.zhengdao.rootfs.RootfsCache.cleanupNonCurrent(ctx)
                    android.os.Handler(ctx.mainLooper).post {
                        Toast.makeText(ctx, "已清理 $n 个旧版本文件", Toast.LENGTH_SHORT).show()
                        reloadCache()
                    }
                }.start()
            }) { Text("清理旧版本缓存（保留当前版本）") }
            if (rollbacks.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "可回退的历史版本（安装包保留最近 2 个）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                rollbacks.forEach { f ->
                    TextButton(onClick = { rollbackConfirm = f }) {
                        Text("回退到 ${com.example.zhengdao.rootfs.RootfsCache.versionOf(f.name) ?: f.name}")
                    }
                }
            }
            if (rollbackConfirm != null) {
                val target = rollbackConfirm!!
                AlertDialog(
                    onDismissRequest = { rollbackConfirm = null },
                    title = { Text("回退环境版本") },
                    text = { Text("将用 ${target.name} 重装系统层（约几分钟）。登录态、API Key 与工作区都会保留。") },
                    confirmButton = {
                        TextButton(onClick = {
                            rollbackConfirm = null
                            Thread {
                                try {
                                    RootfsInstaller.ensureFreeSpace(ctx, target.length())
                                    RootfsInstaller.install(ctx, target) { }
                                    com.example.zhengdao.rootfs.RootfsCache.pruneKeep(ctx)
                                    android.os.Handler(ctx.mainLooper).post {
                                        Toast.makeText(ctx, "回退完成，重进终端生效", Toast.LENGTH_LONG).show()
                                    }
                                } catch (t: Throwable) {
                                    android.os.Handler(ctx.mainLooper).post {
                                        Toast.makeText(ctx, "回退失败：${t.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }.start()
                        }) { Text("回退") }
                    },
                    dismissButton = { TextButton(onClick = { rollbackConfirm = null }) { Text("取消") } },
                )
            }
        }

        // ── 运行内存上限（用户第四批）：ulimit -v 防单个任务膨胀拖垮整机 ──
        SectionCard("运行内存上限") {
            Text(
                text = "限制 guest 内每个进程的虚拟内存。⚠️ 默认关闭：虚拟地址空间≠物理内存，真实负载下可能误伤 Agent（内存治理由 M2 软监控负责）。仅在某任务失控膨胀、拖垮整机时才建议临时开启。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            var memLimit by remember {
                mutableStateOf(Settings.prefs(ctx).getString("guest_mem_limit_mb", "0") ?: "0")
            }
            Row {
                FilterChip2("关闭", memLimit == "0") {
                    memLimit = "0"
                    Settings.prefs(ctx).edit().putString("guest_mem_limit_mb", "0").apply()
                    Toast.makeText(ctx, "已关闭，下次启动会话生效", Toast.LENGTH_SHORT).show()
                }
                Spacer(Modifier.width(6.dp))
                FilterChip2("3GB", memLimit == "3072") {
                    memLimit = "3072"
                    Settings.prefs(ctx).edit().putString("guest_mem_limit_mb", "3072").apply()
                    Toast.makeText(ctx, "已设为 3GB，下次启动会话生效", Toast.LENGTH_SHORT).show()
                }
                Spacer(Modifier.width(6.dp))
                FilterChip2("4GB", memLimit == "4096") {
                    memLimit = "4096"
                    Settings.prefs(ctx).edit().putString("guest_mem_limit_mb", "4096").apply()
                    Toast.makeText(ctx, "已设为 4GB，下次启动会话生效", Toast.LENGTH_SHORT).show()
                }
            }
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

        // ── 荣耀 / MagicOS 保活指南（Magic 5 Pro 实测）──
        SectionCard("荣耀 / MagicOS 保活指南") {
            Text(
                text = "① 应用启动管理：设置 → 应用和服务 → 应用启动管理 → 证道 → 关闭「自动管理」，" +
                    "手动开启「允许自启动 / 关联启动 / 后台活动」\n" +
                    "② 电池优化：设置 → 电池 → 更多电池设置 → 证道 → 设为「不允许优化」\n" +
                    "③ 多任务锁定：多任务界面找到证道卡片，下滑出现小锁图标，点击锁定\n" +
                    "④ 进程意外退出时：开发者选项 → 确认「不要保留活动」未勾选",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "荣耀对前台服务较为尊重：保持「会话运行中」通知可见 + 多任务上锁，" +
                    "即可长期后台存活；即便被清理，tmux 会话恢复机制会在重进时自动回到现场。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = {
                // 电池优化白名单（用户第四批）：直接拉起系统"忽略电池优化"请求对话框；
                // 厂商定制系统不支持该入口时退回通用设置列表，仍不支持则静默。
                try {
                    ctx.startActivity(
                        Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .apply { data = android.net.Uri.parse("package:${ctx.packageName}") }
                    )
                } catch (_: Throwable) {
                    try {
                        ctx.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Throwable) {
                        Toast.makeText(ctx, "请到系统设置的电池优化中手动设置", Toast.LENGTH_LONG).show()
                    }
                }
            }) { Text("一键跳转：把证道设为「不优化」") }
        }

        // ── 新手指南（第三批）──
        SectionCard("新手指南") {
            GuideLine("1", "主页点「安装运行环境」装好 Debian 环境；再给想用的 Agent 点「安装」。")
            GuideLine("2", "在本页「API Key 管理」按 国内 / 国外 / 免费额度 填好密钥，启动会话时自动注入。")
            GuideLine("3", "进底部「终端」，直接输入 agent 命令使用（claude / hermes / opencode / agy）。")
            Spacer(Modifier.height(8.dp))
            Text(
                "常见问题",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            FaqLine("环境打不开 / 下载失败？", "用上方「修复环境」重新解压系统层，登录态与密钥都会保留。")
            FaqLine("Agent 想更新？", "在终端里重跑一遍安装命令即可；系统层更新用「检查环境更新」。")
            FaqLine("我的文件在哪？", "见下方「工作区路径」：手机端在 Download/证道 或应用目录 files/workspace，guest 内是 /workspace。")
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

    // ── 发现新版本弹窗（updateMsg 驱动）：确认后在应用内下载到公共缓存并安装 ──
    updateMsg?.takeIf { it.startsWith("发现新版本") }?.let { msg ->
        AlertDialog(
            onDismissRequest = { updateMsg = null },
            title = { Text("发现环境更新") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = {
                    val url = pendingUpdateUrl
                    updateMsg = null
                    if (url == null) {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/pisces19860207/zhengdao/releases"))
                        )
                        return@TextButton
                    }
                    val expectedSha = pendingUpdateSha
                    Thread {
                        try {
                            android.os.Handler(ctx.mainLooper).post {
                                Toast.makeText(ctx, "开始下载新版本环境…", Toast.LENGTH_SHORT).show()
                            }
                            val archive = com.example.zhengdao.rootfs.RootfsCache.archiveFor(ctx, url)
                            RootfsDownloader.download(
                                urls = listOf(url),
                                dest = archive,
                                shaUrl = "$url.sha256",
                            ) { done, total ->
                                if (total > 0 && done * 100 / total % 20 == 0L) {
                                    android.os.Handler(ctx.mainLooper).post {
                                        Toast.makeText(
                                            ctx,
                                            "下载中 ${done * 100 / total}%",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                            if (!expectedSha.isNullOrBlank()) {
                                RootfsDownloader.verifySha256(archive, expectedSha)
                            }
                            RootfsInstaller.ensureFreeSpace(ctx, archive.length())
                            RootfsInstaller.install(ctx, archive) { }
                            com.example.zhengdao.rootfs.RootfsCache.pruneKeep(ctx)
                            android.os.Handler(ctx.mainLooper).post {
                                Toast.makeText(ctx, "环境更新完成，重进终端生效", Toast.LENGTH_LONG).show()
                            }
                        } catch (t: Throwable) {
                            android.os.Handler(ctx.mainLooper).post {
                                Toast.makeText(ctx, "更新失败：${t.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }.start()
                }) { Text("下载并安装") }
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

/** 新手指南：三步上手的单行（蓝色序号 + 说明）。 */
@Composable
private fun GuideLine(number: String, text: String) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 3.dp)) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(20.dp),
        )
        Text(text = text, style = MaterialTheme.typography.bodySmall)
    }
}

/** 常见问题单条：加粗问题 + 答案。 */
@Composable
private fun FaqLine(question: String, answer: String) {
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 3.dp)) {
        Text(
            text = question,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = answer,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
