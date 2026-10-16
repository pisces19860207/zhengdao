// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口：Jetpack Compose（Material 3）官方 API。
//
// P4-1（2026-10-14）：自 TaijiComponents.kt（拆分前 1929 行）按内聚性拆出——
// **只搬不改**，代码逐行原样（含已提交的 P2-10 48dp 触摸目标修复）。
package com.example.zhengdao.ui.taiji

import android.content.res.Resources
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.keepalive.CoroutineCrashHandler
import com.example.zhengdao.oc.OcSessionSummary
import com.example.zhengdao.ui.TrashGlyph
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import com.example.zhengdao.ui.util.pressClickable
import kotlinx.coroutines.launch

// ── 历史会话**左侧抽屉**（v1.1 第一阶段「会话完整化」+ 长按删除）─────────

/**
 * 历史会话抽屉内容 —— 宿主是 [androidx.compose.material3.ModalNavigationDrawer]
 * 的 `ModalDrawerSheet`（**左侧栏**）。
 *
 * ## 为什么是左侧栏而不是底部弹窗（2026-10-07 改）
 * 会话列表是**常驻导航**，与聊天主区平级；底部弹窗会遮住输入框与最新一条消息，
 * 也不符合国内用户「会话列表在左边」的肌肉记忆（微信 / Telegram / 各类 IM 皆然）。
 *
 * ## 分组与交互
 * - 按 **今天 / 昨天 / 更早** 分组（[groupSessionsByDay]）；每条显示标题 + 时间 + 条数。
 * - 点击任一条 → 恢复该会话（[onPick]），当前会话高亮。
 * - **点尾部垃圾桶 / 长按任一条 → 删除**（二次确认，见 [DeleteSessionDialog]）。
 * - 顶部「＋ 新会话」[onNew]、「⟳」[onRefresh]（每次打开抽屉都会重拉，见 TaijiScreen）。
 *
 * @param onDelete 真正执行删除，返回 true 表示服务端已确认；**列表刷新由调用方负责**
 *   （会话数据所有权在 TaijiScreen，本组件不持有）。
 */
@Composable
fun HistoryDrawer(
    sessions: List<OcSessionSummary>,
    loading: Boolean,
    currentId: String?,
    onPick: (String) -> Unit,
    onNew: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: suspend (OcSessionSummary) -> Boolean,
    /** 已启用插件数（抽屉底部入口行的右侧说明）。 */
    pluginCount: Int = 0,
    /** 打开插件面板。 */
    onPlugins: () -> Unit = {},
    /** 结束本次会话（关 OpenCode 实例 + 退出太极）。顶栏原「◼」的动作，见 [SessionBar] KDoc。 */
    onCloseSession: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<OcSessionSummary?>(null) }
    var confirmClose by remember { mutableStateOf(false) }
    // semantics{} 的 lambda 不是 @Composable，取文本先落局部变量
    val newSessionDesc = stringResource(R.string.taiji_new_session)
    // LazyColumn 的 content 是 LazyListScope 作用域、不是 @Composable，故 Resources 在这里取
    val resources = LocalContext.current.resources

    Column(modifier.fillMaxSize()) {
        // 头部：edge-to-edge 下抽屉顶到屏幕最上沿，必须自己避开状态栏
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.taiji_sessions),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRefresh) {
                // 2026-10-08：无障碍，同「☰」——「⟳」字形读屏会念成"逆时针箭头"。
                val refreshDesc = stringResource(R.string.taiji_refresh_sessions_desc)
                Text(
                    "⟳",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { contentDescription = refreshDesc },
                )
            }
        }

        // 新建会话：抽屉里最高频的动作，置顶且醒目
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                // 同上：12dp 圆角的「新会话」按钮，涟漪跟着圆角走
                .clip(RoundedCornerShape(12.dp))
                .pressClickable(onClick = onNew),
        ) {
            Text(
                stringResource(R.string.taiji_new_session_plus),
                style = MaterialTheme.typography.bodyMedium,
                // 2026-10-08 走查：vertical 10dp → 14dp。原来 10dp + bodyMedium ≈ 30dp 高，
                // 而它是**会话列表抽屉的正面入口**（打开抽屉第一眼就在这里），
                // 点不中会让用户以为"新会话按钮没出来"——再加垂直 14dp ≈ 44dp 高达标。
                // 未一步到 48dp：抽屉宽度有限，再厚会使文字与圆角挤得难看。
                //
                // B-补-20（2026-10-16）：14 → 16dp，整行 ≈ 48dp（M3/Google 无障碍最小触摸目标）。
                // 补一句此前那半句顾虑的实测口径：**高度**不占抽屉宽度（宽度是文字与圆角说了算），
                // 16dp 竖内边距只是把可点区域抬到达标，视觉上文字与圆角比例不变。
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    // 同上：纯文字 Surface，读屏需要明示这是按钮 + 它的含义
                    .semantics { contentDescription = newSessionDesc },
            )
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()

        when {
            // 首次拉取元数据 —— 不让用户对着空白发呆
            sessions.isEmpty() && loading -> Box(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }

            sessions.isEmpty() -> Text(
                stringResource(R.string.taiji_no_sessions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .navigationBarsPadding(),          // 避开底部导航条
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                groupSessionsByDay(sessions, resources).forEach { (label, items) ->
                    item(key = "header-$label") { DayHeader(label) }
                    items(items, key = { it.id }) { s ->
                        SessionRow(
                            summary = s,
                            current = s.id == currentId,
                            onClick = { onPick(s.id) },
                            // 可见路径与长按走同一个二次确认弹窗（DeleteSessionDialog）
                            onDelete = { pendingDelete = s },
                            onLongClick = { pendingDelete = s },
                        )
                    }
                }
            }
        }

        // ── 底部固定区（2026-10-08）：插件入口 + 结束会话 ──
        // 为什么放这里：
        //  ① 用户反馈「太极页找不到插件入口」——入口原只在「设置」页，而插件本来就是
        //     **太极的能力**（PluginManager 只读写太极实例的 opencode.json）。抽屉底部
        //     本来就摆着 OpenCode 版本条，两者同属"太极的环境"。
        //  ② 用户反馈「停止键应该和发送键在一起」——顶栏原来的 ◼ 不是"停止生成"，而是
        //     "结束本次会话（关掉 OpenCode 实例）并退出太极"。语义比"停止"重，外形却像
        //     一个停止键。移进抽屉并把后果写在确认框里，误会就没有来源了。
        HorizontalDivider()
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            DrawerActionRow(
                label = stringResource(R.string.taiji_plugins),
                value = if (pluginCount > 0) {
                    stringResource(R.string.taiji_plugins_enabled, pluginCount)
                } else {
                    stringResource(R.string.taiji_plugins_none)
                },
                onClick = onPlugins,
            )
            DrawerActionRow(
                // 2026-10-10 UIUX 审计 P0-2：原"结束本次会话"与底下"删除会话"字面几乎一样、
                // 后果完全不同。非技术用户极易看走眼。改名 + 把后果写在右侧副字段里：
                // 这里**只关 OpenCode 实例、释放内存**，聊天记录在服务端，下次进来还在。
                label = stringResource(R.string.taiji_close_session),
                value = stringResource(R.string.taiji_close_session_hint),
                onClick = { confirmClose = true },
            )
        }
    }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text(stringResource(R.string.taiji_close_session_title)) },
            text = {
                // 措辞重写：**只关实例、不删记录**。避免"释放内存"这种开发术语。
                Text(stringResource(R.string.taiji_close_session_msg))
            },
            confirmButton = {
                TextButton(onClick = { confirmClose = false; onCloseSession() }) {
                    Text(stringResource(R.string.common_close))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClose = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    pendingDelete?.let { target ->
        DeleteSessionDialog(
            summary = target,
            onConfirm = { onDelete(target) },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * 删除确认（长按触发）。
 *
 * **不可撤销**（服务端连消息一起删），所以必须二次确认；删除失败时在弹窗内直接给出
 * 原因并保持打开——不静默失败、不假装成功（项目原则）。
 */
@Composable
private fun DeleteSessionDialog(
    summary: OcSessionSummary,
    onConfirm: suspend () -> Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope { CoroutineCrashHandler }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // scope.launch 内不是 @Composable 上下文，失败文案先取出来
    val deleteFailedText = stringResource(R.string.taiji_delete_failed)
    val targetTitle = sessionTitle(summary, LocalContext.current.resources)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },   // 删除中不许点外面关掉
        // 2026-10-10 UIUX 审计 P0-2：标题整行标红 + 加"永久"，与上面的"关闭"形成视觉对立。
        // 颜色取 zhengdaoErrorText()（浅色 C0392B 5.4:1 满足 AA；P2-11：原 colorScheme.error
        // 只有 3.55:1），不需要等到用户点完确认才知道后果。
        title = {
            Text(
                stringResource(R.string.taiji_delete_session_title),
                color = zhengdaoErrorText(),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.taiji_delete_session_msg, targetTitle),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        // P2-11：错误正文同上——colorScheme.error 3.55:1 不达 AA。
                        color = zhengdaoErrorText(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val ok = onConfirm()
                        busy = false
                        if (ok) onDismiss()
                        else error = deleteFailedText
                    }
                },
            ) {
                Text(
                    if (busy) stringResource(R.string.taiji_deleting)
                    else stringResource(R.string.taiji_delete)
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun DayHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

/**
 * 单条会话。**点击恢复 / 点尾部垃圾桶删除 / 长按删除**。
 *
 * 删除有**两条通道**：尾部常驻的垃圾桶按钮（[onDelete]，可见、可发现）与整行长按
 * （[onLongClick]，辅助）。2026-10-09 补前者：此前只有长按、界面无提示，新用户不知道
 * 会话能删（审计 A1「删除功能不可发现」）。
 *
 * 点击与长按用 `combinedClickable` 而非两个独立手势检测：两者互斥、由同一手势管道判定，
 * 不会出现"长按也触发了点击"的竞态。尾部按钮是独立子节点，点击由它自己消费、不会冒泡到整行。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    summary: OcSessionSummary,
    current: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val ctx = LocalContext.current
    // 会话标题（可能回退成「会话 + id 尾缀」）与删除按钮的无障碍播报都随语言走
    val rowTitle = sessionTitle(summary, ctx.resources)
    val deleteDesc = stringResource(R.string.taiji_delete_session_desc, rowTitle)
    Surface(
        color = if (current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    rowTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        summary.updatedAt?.let { append(formatClock(it)) }
                        summary.messageCount?.let {
                            if (isNotEmpty()) append(" · ")
                            append(stringResource(R.string.taiji_msg_count, it))
                        }
                    }.ifEmpty { summary.id.take(12) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (current) {
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.taiji_current),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // A1（2026-10-09）：常驻删除入口。
            // 此前删除**只**能长按触发，界面没有任何提示 —— 新用户第一反应是"这是个死列表"，
            // 根本不知道还能删（用户反馈佐证）。这里补一个 trailing 垃圾桶按钮作为**可见路径**，
            // 长按保留作辅助（双通道）。图标用中性色 onSurfaceVariant，不喧宾夺主。
            // P2-10（issue #17）：40dp → 48dp。原注释「与顶栏＋同档」写于 ＋ 还是 40dp 的
            // 年代；＋ 自 2026-10-08 起就是 48dp（见 SessionBar），40dp 反而比它小一档。
            // 且 M3 的 minimumInteractiveComponentSize 按官方要求必须排在 size 修饰符
            // **之前**才生效——这里显式 size(40.dp) 恰好把 48dp 布局保留抵掉了，只能自己给足。
            // 字形 TrashGlyph 仍是固定 18dp，按钮放大不会把图标撑大。点击由子节点消费，
            // 不会误触整行的"恢复会话"。
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = deleteDesc },
            ) {
                TrashGlyph(tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * 会话标题：服务端 `title` 优先；为空回退「会话 + id 尾缀」。
 * **不调模型生成标题**（计划 P1-4 明确）。
 *
 * [res] 传 null 时回退中文（供 JVM 单测 / 无 Context 场景），传 `ctx.resources` 即随界面语言。
 */
private fun sessionTitle(s: OcSessionSummary, res: Resources? = null): String =
    s.title?.takeIf { it.isNotBlank() }
        ?: (res?.getString(R.string.taiji_session_fallback, s.id.takeLast(6))
            ?: "会话 ${s.id.takeLast(6)}")

/**
 * 按天分组：今天 / 昨天 / 更早。保持输入顺序（服务端已按时间倒序）。时间缺失归「更早」。
 *
 * `internal` 而非 `private`：供 JVM 单元测试（TaijiSessionGroupingTest）直接验证分组
 * —— 这是 v1.1 第一阶段唯一可脱离真机自动验证的核心逻辑。同模块可见，不扩大外部 API。
 */
internal fun groupSessionsByDay(
    list: List<OcSessionSummary>,
    res: Resources? = null,
): List<Pair<String, List<OcSessionSummary>>> {
    val out = LinkedHashMap<String, MutableList<OcSessionSummary>>()
    list.forEach { out.getOrPut(dayLabel(it.updatedAt, res)) { mutableListOf() }.add(it) }
    return out.map { it.key to it.value }
}

/**
 * 「今天 / 昨天 / 更早」判据。`internal` 供单测（见 [groupSessionsByDay]）。
 *
 * [res] 传 null 时回退中文（单测与无 Context 场景），传 `ctx.resources` 即随界面语言。
 */
internal fun dayLabel(ts: Long?, res: Resources? = null): String {
    val earlier = res?.getString(R.string.taiji_day_earlier) ?: "更早"
    ts ?: return earlier
    val startOfToday = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    return when {
        ts >= startOfToday -> res?.getString(R.string.taiji_day_today) ?: "今天"
        ts >= startOfToday - 86_400_000L -> res?.getString(R.string.taiji_day_yesterday) ?: "昨天"
        else -> earlier
    }
}

// B-补-24（2026-10-16）：会话行每次重组都调它 —— 旧写法每次都新建 SimpleDateFormat
private fun formatClock(ts: Long): String = com.example.zhengdao.util.TimeFormats.hourMinute(ts)

// ── 抽屉底部动作行 ─────────────────────────────────────────────────────

/**
 * 抽屉底部的动作行（标签 + 右侧说明 + `›`）。
 *
 * 形态与设置页的 `SettingRow` 一致，但**刻意不复用**：那一份的竖向内边距（12dp）是按
 * 设置页的长列表调的，放进 300dp 宽的抽屉会显得过松，且它在 `ui` 包、这里是 `ui.taiji`，
 * 复用会把抽屉的观感绑在另一个页面的样式上。
 */
@Composable
private fun DrawerActionRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // P2-10（issue #17）：竖内边距 8dp + bodyMedium 行高约 20dp ≈ 36dp，是全 App
            // 最矮的可点击行。补 defaultMinSize 到 M3 最小触摸目标 48dp（Row 吃
            // crossAxisMin，内容仍由 verticalAlignment 居中）。KDoc 里「形态与设置页
            // SettingRow 一致、刻意不复用」的判断不变——改的只是高度下限，不是样式来源。
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .pressClickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "›",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
