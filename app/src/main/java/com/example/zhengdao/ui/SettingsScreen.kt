// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import com.example.zhengdao.BuildConfig
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.example.zhengdao.ChevronRight
import com.example.zhengdao.R
import com.example.zhengdao.core.kb.KnowledgeBase
import com.example.zhengdao.ui.SystemInfoProvider.dirSizeMb
import com.example.zhengdao.ui.theme.AppearancePrefs
import com.example.zhengdao.ui.theme.rCard
import com.example.zhengdao.ui.theme.rDialog
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.example.zhengdao.ui.util.pressClickable

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
 * - 删 onOpenPlugins 死参数（插件入口 2026-10-08 已搬进抱朴页抽屉）。
 *
 * 2026-10-25 第二百一十批（入口与权限归位）：Hub 顶部新增「申请权限」集中卡
 * （四行：所有文件访问 / 通知 / 精确闹钟 / 电池优化豁免）——全 App **唯一**的权限
 * 申请入口。原「权限」行的口径（2026-10-12 删行、待处理并进「终端与工作区」行显示）
 * 随之退役：状态显示保留在各处，申请只在那张卡里。
 *
 * 二级页：存储与缓存 / 环境与更新 / 终端与资料 / 后台与保活 / 帮助 / 关于。
 */
@Composable
fun SettingsScreen(
    onOpenTerminal: (autocmd: String?, agentId: String?) -> Unit = { _, _ -> },
) {
    val ctx = LocalContext.current
    fun manageGranted(): Boolean =
        runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)

    var manageOk by remember { mutableStateOf(manageGranted()) }
    var kbEnabled by remember {
        mutableStateOf(com.example.zhengdao.core.kb.KnowledgeBase.isEnabled(ctx))
    }
    var kbFiles by remember { mutableStateOf<Int?>(null) }
    // 工作区可写？—— 只看共享存储里那个目录能不能写（不建目录、不改任何东西）
    val workspaceStatus = remember(manageOk) {
        val dir = File(android.os.Environment.getExternalStorageDirectory(), "Download/证道")
        when {
            !manageOk -> "需要「所有文件访问」"
            dir.isDirectory && dir.canWrite() -> "可写"
            dir.isDirectory -> "不可写"
            else -> "未创建"
        }
    }
    // 定时任务（第一百六十七批）：Hub 这一行只报「启用几个」，开关/编辑都在二级页里。
    // null = 还没读完（这一页别的数字也用同一套「先占位后覆盖」的写法）。
    var schedEnabled by remember { mutableStateOf<Int?>(null) }
    // 界面语言（2026-10-12）：读系统记的 per-app locale，切换后系统重建 Activity、当场生效。
    var langTag by remember { mutableStateOf(LanguagePrefs.current(ctx)) }
    var langDialog by remember { mutableStateOf(false) }
    // 外观（2026-10-12 深色主题）：订阅变更信号，切换即重组、不用重建页面。
    var appearDialog by remember { mutableStateOf(false) }
    val appearTick = AppearancePrefs.changeTick.intValue
    val appearPref = remember(appearTick) { AppearancePrefs.get(ctx) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                manageOk = manageGranted()
                kbEnabled = com.example.zhengdao.core.kb.KnowledgeBase.isEnabled(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
            // ── 「申请权限」集中卡（第二百一十批 · 入口与权限归位）─────────────
            // 用户 2026-10-25 原话：「定时任务的权限等等各种权限申请应该放到证道 APP 的
            // 设置里弄一个申请权限的卡」。放 Hub **第一张**＝显眼位置；四行（所有文件访问 /
            // 通知 / 精确闹钟 / 电池优化豁免）各自带真实状态与「不给会怎样」。
            // 口径：**发起申请只此一处**——别处只许显示权限状态、或留一行「去设置 → 申请权限」
            // 指路文案（源码门禁见 PermissionEntryGateTest，负例 5/7 盯的就是这两条）。
            // 2026-10-26 用户：这张卡改成和普通行一样——点一下展开四行，再点收回；
            // 离开设置页（返回/切 tab）后重进是收起的（remember 不跨页面存活）。
            var permExpanded by remember { mutableStateOf(false) }
            SectionCard(stringResource(R.string.settings_perm_card_title)) {
                SettingRow(
                    label = stringResource(R.string.settings_perm_row_label),
                    value = if (permExpanded) stringResource(R.string.settings_perm_collapse) else stringResource(R.string.settings_perm_expand),
                ) { permExpanded = !permExpanded }
                if (permExpanded) {
                    Spacer(Modifier.height(8.dp))
                    SettingsPermissionCard()
                }
            }

            // 2026-10-12 重构（走查报告 §2）：Hub 只做菜单——每行一个去向、状态一律短词。
            // 同日接入 i18n（§5.7）：文案走 strings.xml，切英文时本页整页变英文。
            // 第二百四十八批（第四刀）：**「环境」与「终端与资料」两节随终端一起退役**。
            // 它们原来指向 SettingsEnvActivity（装/更环境）、SettingsStorageActivity（rootfs 体积）、
            // SettingsDataActivity（终端数据/工作区），三页都搬去 zhengdao-term 了。
            // 主仓设置现在只剩：权限 / 后台保活 / 浏览器桥 / 定时任务 / 界面 / 关于。
            // （工作区与资料库的入口留给第七步「设置瘦身」重建一个不依赖 rootfs 的版本。）

            // 第二百五十批：只留「工作区」「资料库」两组（终端相关的三节已随终端拆走）。
            // 这两行**只报状态、不给跳转**：它们的二级页（SettingsDataActivity）跟着终端删了，
            // 重建页面属于"加功能"，不在本刀范围 —— 所以这里只回答两个问题：
            // 工作区可写吗？资料库可读吗？（也就是原来"缺 N 项"体检里跟抱朴有关的那两条。）
            SectionCard(stringResource(R.string.settings_group_workspace)) {
                SettingRow(
                    label = stringResource(R.string.settings_workspace),
                    value = workspaceStatus,
                ) {}
                SettingsDivider()
                SettingRow(
                    label = stringResource(R.string.settings_knowledge_base),
                    value = when {
                        !kbEnabled -> stringResource(R.string.settings_status_disabled)
                        (kbFiles ?: 0) > 0 ->
                            pluralStringResource(R.plurals.settings_status_file_count, kbFiles ?: 0, kbFiles ?: 0)
                        else -> stringResource(R.string.settings_status_enabled)
                    },
                ) {}
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
                    // 131 批 UI-D5：英文是整句（For agents inside the terminal），一行放不下
                    // ⇒ 给两行；中文短句仍是一行（默认值 1，别处不受影响）。
                    valueMaxLines = 2,
                ) { open(SettingsBridgeActivity::class.java) }
                SettingsDivider()
                // 定时任务（第一百六十七批）：也放「后台」组——它和保活、浏览器桥同属
                // "进程不在前台也要替用户做一件事"的一类。状态只报"启用几个"，
                // 不在这行摊开权限（权限三行在「终端与资料」页那张卡里，一处一套控件）。
                SettingRow(
                    label = stringResource(R.string.sched_page_title),
                    value = when (schedEnabled) {
                        null -> stringResource(R.string.settings_status_measuring)
                        0 -> stringResource(R.string.sched_hub_none)
                        else -> stringResource(R.string.sched_hub_enabled, schedEnabled ?: 0)
                    },
                ) { open(com.example.zhengdao.schedule.ScheduleTasksActivity::class.java) }
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
                shape = RoundedCornerShape(rDialog),
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
                shape = RoundedCornerShape(rDialog),
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
            shape = RoundedCornerShape(rCard),
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
    // 第一百三十一批 UI-D5：英文副标题被省略号吃掉（真机 `For agents inside the te…`）。
    // 默认仍是 1 行（中文版面一个像素都不动），只给需要长句的那一行传 2。
    // 不许只调 maxLines 不给高度：本行没有固定高（padding + 文字自撑），两行时自然长高。
    valueMaxLines: Int = 1,
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
                // 131 批 UI-D5：1 行时保留省略号（中文既有观感），>1 行时整句换行显示。
                maxLines = valueMaxLines,
                overflow = if (valueMaxLines > 1) TextOverflow.Clip else TextOverflow.Ellipsis,
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
