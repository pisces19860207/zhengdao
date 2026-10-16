// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.example.zhengdao.ui.util.pressClickable
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.res.stringResource
import com.example.zhengdao.BuildConfig
import com.example.zhengdao.ChevronRight
import com.example.zhengdao.R
import com.example.zhengdao.ui.SystemInfoProvider.dirSizeMb
import com.example.zhengdao.ui.AppState.rootfsInstalled
import com.example.zhengdao.ui.theme.AppearancePrefs
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import com.example.zhengdao.terminal.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 设置（入口页，2026-10-11 重排；2026-10-12 按走查报告 §2 再收敛）。
 *
 * 原来这里是 17 张卡、2400 行的单页 —— 用户（2026-10-11）看完说「设置页内容太多了，
 * 整体显得很乱」。重排只做两件事：**入口化**（顶层只留一句话状态 + 一次点击）
 * 和**搬运**（卡片内容一字未改地搬进 6 个二级页）。
 *
 * 2026-10-12 第二轮（走查报告 §2 / 用户反馈）：
 * - Hub 只做菜单：每行一个去向，状态一律短词（≤6 字）；
 * - 删「Root 增强模式」行（未实现项，说明并入「关于」页）；
 * - 删「权限」行——待处理时并进「终端与工作区」行显示，权限卡在二级页内照旧；
 * - 删 onOpenPlugins 死参数（插件入口 2026-10-08 已搬进太极页抽屉）。
 *
 * 二级页：存储与缓存 / 环境与更新 / 终端与资料 / 后台与保活 / 帮助 / 关于。
 */
@Composable
fun SettingsScreen(
    onOpenTerminal: (autocmd: String?, agentId: String?) -> Unit = { _, _ -> },
) {
    val ctx = LocalContext.current
    var storageTick by remember { mutableStateOf(0) }
    // 2026-10-12 无感化（走查报告 §5.5）：打开即显上次实测值，量完后就地覆盖——
    // 不再先显示「统计中…」再让三个数字一起蹦出来。
    val lastStorage = remember { LastValues.loadStorageMb(ctx) }
    var rootfsMb by remember { mutableStateOf(lastStorage?.first ?: -1L) }
    var homeMb by remember { mutableStateOf(lastStorage?.second ?: -1L) }
    var cacheMb by remember { mutableStateOf(lastStorage?.third ?: -1L) }

    // 局部函数没有提升：必须先声明再用（否则 remember { } 里的调用会推不出类型）。
    fun storageGrantedNow(): Boolean =
        com.example.zhengdao.terminal.ProotLauncher.storageGranted(ctx)

    fun manageGranted(): Boolean =
        runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)

    var storageOk by remember { mutableStateOf(storageGrantedNow()) }
    var manageOk by remember { mutableStateOf(manageGranted()) }
    var kbEnabled by remember {
        mutableStateOf(com.example.zhengdao.terminal.KnowledgeBase.isEnabled(ctx))
    }
    var kbFiles by remember { mutableStateOf<Int?>(null) }
    // 界面语言（2026-10-12）：读系统记的 per-app locale，切换后系统重建 Activity、当场生效。
    var langTag by remember { mutableStateOf(LanguagePrefs.current(ctx)) }
    var langDialog by remember { mutableStateOf(false) }
    // 外观（2026-10-12 深色主题）：订阅变更信号，切换即重组、不用重建页面。
    var appearDialog by remember { mutableStateOf(false) }
    val appearTick = AppearancePrefs.changeTick.value
    val appearPref = remember(appearTick) { AppearancePrefs.get(ctx) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                manageOk = manageGranted()
                storageOk = storageGrantedNow()
                kbEnabled = com.example.zhengdao.terminal.KnowledgeBase.isEnabled(ctx)
                storageTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(storageTick) {
        val sizes = withContext(Dispatchers.IO) {
            Triple(
                dirSizeMb(File(ctx.filesDir, "rootfs")),
                dirSizeMb(File(ctx.filesDir, "home")),
                dirSizeMb(ctx.cacheDir),
            )
        }
        rootfsMb = sizes.first
        homeMb = sizes.second
        cacheMb = sizes.third
        LastValues.saveStorageMb(ctx, sizes.first, sizes.second, sizes.third)
        kbFiles = withContext(Dispatchers.IO) {
            runCatching { com.example.zhengdao.terminal.KnowledgeBase.status(ctx).files }.getOrNull()
        }
    }

    /** 二级页统一入口。 */
    fun open(page: Class<*>) {
        ctx.startActivity(Intent(ctx, page))
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // 2026-10-12 重构（走查报告 §2）：Hub 只做菜单——每行一个去向、状态一律短词。
            // 同日接入 i18n（§5.7）：文案走 strings.xml，切英文时本页整页变英文。
            SectionCard(stringResource(R.string.settings_group_env)) {
                SettingRow(
                    label = stringResource(R.string.settings_env_update),
                    value = if (AppState.rootfsInstalled(ctx)) {
                        stringResource(R.string.settings_status_installed)
                    } else {
                        stringResource(R.string.settings_status_not_installed)
                    },
                ) { open(SettingsEnvActivity::class.java) }
                SettingsDivider()
                SettingRow(
                    label = stringResource(R.string.settings_storage),
                    value = if (rootfsMb < 0) stringResource(R.string.settings_status_measuring)
                    else "${rootfsMb + homeMb + cacheMb} MB",
                ) { open(SettingsStorageActivity::class.java) }
            }

            SectionCard(stringResource(R.string.settings_group_terminal)) {
                // 权限不再单占一行：待处理时由本行状态直接说出来，权限卡在二级页内。
                SettingRow(
                    label = stringResource(R.string.settings_terminal_workspace),
                    value = if (storageOk && manageOk) Workspace.hostDir(ctx).name
                    else stringResource(R.string.settings_status_perm_pending),
                    // P2-11：待处理状态是文字，走 zhengdaoErrorText()（error 色 3.55:1 不达 AA）
                    valueColor = if (storageOk && manageOk) Color.Unspecified else zhengdaoErrorText(),
                ) { open(SettingsDataActivity::class.java) }
                SettingsDivider()
                SettingRow(
                    label = stringResource(R.string.settings_knowledge_base),
                    value = when {
                        !kbEnabled -> stringResource(R.string.settings_status_disabled)
                        (kbFiles ?: 0) > 0 ->
                            stringResource(R.string.settings_status_file_count, kbFiles ?: 0)
                        else -> stringResource(R.string.settings_status_enabled)
                    },
                ) { open(SettingsDataActivity::class.java) }
            }

            SectionCard(stringResource(R.string.settings_group_background)) {
                SettingRow(
                    label = stringResource(R.string.settings_keepalive),
                    value = stringResource(R.string.settings_status_guide),
                ) { open(SettingsKeepaliveActivity::class.java) }
                SettingsDivider()
                // 浏览器桥（2026-10-15，设计文档 §4.5）：给终端里的 agent 用。
                // 放「后台」组 —— 它与保活同属"进程活着期间对外提供服务"的一类；
                // 默认关，入口只是让人知道有这条路。
                SettingRow(
                    label = stringResource(R.string.settings_bridge),
                    value = stringResource(R.string.settings_value_bridge),
                ) { open(SettingsBridgeActivity::class.java) }
            }

            SectionCard(stringResource(R.string.settings_group_interface)) {
                SettingRow(
                    label = stringResource(R.string.settings_appearance),
                    value = when (appearPref) {
                        AppearancePrefs.LIGHT -> stringResource(R.string.appearance_light)
                        AppearancePrefs.DARK -> stringResource(R.string.appearance_dark)
                        else -> stringResource(R.string.appearance_system)
                    },
                ) { appearDialog = true }
                SettingsDivider()
                SettingRow(
                    label = stringResource(R.string.settings_language),
                    value = when (langTag) {
                        LanguagePrefs.ZH -> stringResource(R.string.language_zh)
                        LanguagePrefs.EN -> stringResource(R.string.language_en)
                        else -> stringResource(R.string.language_system)
                    },
                ) { langDialog = true }
            }

            SectionCard(stringResource(R.string.settings_group_support)) {
                SettingRow(
                    label = stringResource(R.string.settings_help),
                    value = stringResource(R.string.settings_value_help),
                ) {
                    open(SettingsHelpActivity::class.java)
                }
                SettingsDivider()
                SettingRow(label = stringResource(R.string.settings_about), value = BuildConfig.VERSION_NAME) {
                    open(SettingsAboutActivity::class.java)
                }
            }

            Spacer(Modifier.height(8.dp))
        }

        // 外观选择（2026-10-12）：写偏好即改内存状态 ⇒ 整个界面当场重绘，不用重建页面。
        if (appearDialog) {
            val options = listOf(
                AppearancePrefs.SYSTEM to stringResource(R.string.appearance_system),
                AppearancePrefs.LIGHT to stringResource(R.string.appearance_light),
                AppearancePrefs.DARK to stringResource(R.string.appearance_dark),
            )
            AlertDialog(
                onDismissRequest = { appearDialog = false },
                shape = RoundedCornerShape(14.dp),
                title = { Text(stringResource(R.string.settings_appearance)) },
                text = {
                    Column {
                        options.forEach { (value, name) ->
                            SettingRow(
                                label = name,
                                value = if (value == appearPref) "✓" else "",
                            ) {
                                appearDialog = false
                                AppearancePrefs.set(ctx, value)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { appearDialog = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
        }

        // 界面语言选择（2026-10-12）：系统级 per-app locale，选完系统自动重建页面。
        if (langDialog) {
            val options = listOf(
                LanguagePrefs.SYSTEM to stringResource(R.string.language_system),
                LanguagePrefs.ZH to stringResource(R.string.language_zh),
                LanguagePrefs.EN to stringResource(R.string.language_en),
            )
            AlertDialog(
                onDismissRequest = { langDialog = false },
                shape = RoundedCornerShape(14.dp),
                title = { Text(stringResource(R.string.settings_language)) },
                text = {
                    Column {
                        options.forEach { (tag, name) ->
                            SettingRow(
                                label = name,
                                value = if (tag == langTag) "✓" else "",
                            ) {
                                langDialog = false
                                if (tag != langTag) {
                                    LanguagePrefs.set(ctx, tag)
                                    langTag = tag
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { langDialog = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
        }
    }
}

/**
 * 分组卡片（iOS 设置语言）：标题在卡片外做小号灰标签，卡片本身是白底 + 0.5dp 发丝描边 + 16dp 圆角。
 * 旧版把标题塞进卡片里、且是 16sp 半粗——十几张卡读下来每张都是"标题 + 一堆小字"，
 * 分组反而没有层级。标题移到卡外后，视线先落到组标签、再落到这一组的内容。
 */
@Composable
internal fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}

/** 「大字体」分界（B-补-22）：系统字号 ≥ 1.3× 时，设置页的「标签—数值」两列改上下堆叠。
 *
 *  为什么用字号阈值、而不是去量文字宽度：360dp 屏上卡片内可用宽度 ≈ 296dp（页面 16×2 ＋ 卡片 16×2）。
 *  1.0× 时中文标签 6 字 ≈ 84dp，数值列还剩 ~200dp — 两列并排省高度又好扫（旧版就是按这个定的，
 *  见 `InfoRow` 里 2026-10-11 那段注释）；但 1.3× 之后标签按固有宽度一行排完就要 110dp＋，
 *  1.5× / 2.0× 直接吃满整行，两列只能靠 `maxLines = 1` 硬截断标签、把状态文字变省略号 ——
 *  这正是 B-补-22 报的「大字体不换行」。系统字号档位是离散的（1.0 / 1.15 / 1.3 / 1.5 / 2.0），
 *  取第一档明显放大的 1.3× 做分界：它以下维持两列（零视觉变化），以上堆叠（谁也不截断）。 */
private const val LARGE_FONT_SCALE = 1.3f

/** 名称—数值行：数值右对齐成一列，扫一眼就能比大小（旧版固定 150dp 标签宽 + 左对齐，读着像表格）。
 *  [onClick] 非空时整行可点（用于「关于」页版本行连点进隐藏入口）。
 *  **大字体（≥ 1.3×）改上下堆叠**：标签一行、数值一行右对齐。 */
@Composable
internal fun InfoRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .then(if (onClick != null) Modifier.pressClickable(onClick = onClick) else Modifier)
        .padding(vertical = 5.dp)
    if (LocalDensity.current.fontScale >= LARGE_FONT_SCALE) {
        // 大字体：两行都占满整行宽度 ⇒ 标签与数值都按需要换行，没有任何 maxLines 截断。
        Column(modifier = rowModifier) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
            // 2026-10-11 修：**标签先按固有宽度排，数值吃剩余宽度并右对齐**。
            // 旧写法是 `label` 带 `weight(1f)`、`value` 不带权重 —— Row 里无权重子项先按固有宽度量，
            // 长数值（如「rootfs 768 MB · home 3530 MB · cache 6 MB」实测占 1060px）会把
            // 带权重标签分到的剩余宽度压到接近 0，中文标签于是**逐字换行成竖排**（用户 2026-10-11 报：
            // 「存储与缓存这几个字是竖着的」）。现在长数值自己换行，标签永不被挤。
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 设置页统一的可点行：主标签 + 右侧状态文字 + "›"，整行可点、行高一致。
 * 旧版三行权限各有各的写法（行尾有的挂按钮、有的挂 ✅ emoji、有的挂 ›），一页凑出三套交互视觉。
 * **大字体（≥ 1.3×）改上下堆叠**（同 `InfoRow`）：标签一行，状态文字 ＋ "›" 一行 —— 原来状态文字是
 * `maxLines = 1` + 省略号，大字体下用户读不到完整状态（如「未授予」被截成「未…」）。
 */
@Composable
internal fun SettingRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
    onClick: () -> Unit,
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .pressClickable(onClick = onClick)
        // 2026-10-08 走查：12dp → 14dp。bodyMedium 行高 24dp + 24dp = 48dp
        // 刚好不达标（差 4dp），这是设置页几乎所有行的交互热区。
        .padding(vertical = 14.dp)
    val valueText = if (valueColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant
    else valueColor
    if (LocalDensity.current.fontScale >= LARGE_FONT_SCALE) {
        Column(modifier = rowModifier) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 不给 maxLines：大字体下状态文字整句换行显示，不再被省略号吃掉。
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = valueText,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                ChevronRight(tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
            // 2026-10-11 修（同 `InfoRow`）：标签按固有宽度一行显示，数值吃剩余宽度右对齐。
            // 2026-10-12 走查（报告 §3.2）：主次对调——设置名是主角（16sp 主文字色），
            // 状态退后（13sp 次要色 + 省略号）；旧版两者同为 14sp 次要灰且状态加粗，眼睛先读到状态。
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = valueText,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            ChevronRight(tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 轻量选择芯片（避免引入额外依赖）：选中＝淡蓝底 + 主色描边。 */
@Composable
fun FilterChip2(label: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        border = BorderStroke(
            width = if (selected) 1.dp else 0.5.dp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
            contentColor = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        // 2026-10-08 走查：4dp → 10dp。M3 OutlinedButton 默认最小高 40dp，
        // 原来 24dp 行高 + 8dp = 32dp，被默认值兜到 40dp 仍不足 48dp；
        // 现在内容高 44dp，筛选 chip 这类"次要但要重复点"的控件按得准。
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
