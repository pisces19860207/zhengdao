// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：用户 2026-10-17 口述「不常用的设置什么的归到抱朴的设置抽屉里……满屏幕都是按钮和按键，
// 手机本来屏幕就小」＋ 选定方案「全收进去，顶条只留 ☰／状态／🔊／＋」；
// 第二十九批改派（2026-10-18）：用户「左侧栏就应该显示默认工作区、工作区了吧？」
// 「我看这几个 harness 都是工作区旁边加一个文件夹符号，而不是显示完整的路径啊，
// 工作区下面一个文件夹符号加当前项目文件夹的名字」⇒ 抽屉顶部从「三组七项菜单」改成
// **按工作区归组的会话树**（DSH／workbuddy 那个形状）。
//
// 为什么要抽屉而不是再加一行按钮：
//   抱朴页顶原本挂着「章节抽取」「备份原件」「换通道」三个文字按钮，下面还有一行状态＋新会话，
//   手机窄屏上两行入口占掉首屏一大块，真正的对话内容被挤下去。抽屉把低频入口收成一层，
//   首屏只留每次都用的四件。
//
// 口径：
//   1. 抽屉只做**入口搬运＋工作区可见**，不减任何功能（原来能点的，抽屉里都还能点）；
//   2. ☰ 是唯一必须入口（窄屏）；宽屏把抽屉钉住常驻时不出 ☰；
//   3. 关闭路径三条都要有：点遮罩、点返回键、点抽屉里的项（项点了先关再跳）；
//   4. 树怎么分组、时间怎么写、文件夹行显示什么——**全在 BaopuSessionTree.kt 的纯函数里算**，
//      本文件只负责把算好的结构画出来（判据能 JVM 直测，画出来的东西归真机）。
//   5. 图标只有文字符号 📁／🔒／⚠️（A7：不许引图标库、不许新自绘 glyph）。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import com.example.zhengdao.R

/** 抽屉里的一行：标题＋说明＋点击。说明写给"不常用的人"看，省得点进去才知道是什么。 */
@Composable
private fun DrawerItem(
    title: String,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        )
        Text(
            text = description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 分组标题（会话／工作区／资料与书／设置）。右侧可带一个入口（工作区组用它放「＋ 选文件夹」）。 */
@Composable
private fun DrawerGroup(title: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/**
 * 工作区一行（文件夹行）。
 *
 * 显示什么由纯函数算好了交进来（W1：只有末级名，完整路径不进这一行）：
 * - 点一下 ⇒ [onTap]（那句完整路径的临时提示＋落点设置页「工作区」卡，都在抱朴页里做）；
 * - 长按 ⇒ [onHold]（**只**看路径，不跳页——派工单 §7「长按提示」那条口径）。
 *
 * 当前工作区整行**铺底色**（secondaryContainer）＝W6 里强度最高的那一档。
 * 不许做展开／收起动画（A6）：这里就是一个静态行，组的内容永远全展开。
 */
@Composable
private fun WorkspaceRow(
    node: DrawerWorkspaceNode,
    onTap: (String) -> Unit,
    onHold: (String) -> Unit,
    onRunHere: (DrawerWorkspaceNode) -> Unit,
) {
    val current = highlightOf(node) == Highlight.WORKSPACE_CURRENT
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (current) {
                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                onClick = { onTap(node.fullPath) },
                onLongClick = { onHold(node.fullPath) },
            )
            .padding(start = 20.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = folderIcon(node.state),
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = node.label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = if (current) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // 「圆里一个加号」＝**在这个工作区里干活**（在这里开新会话）。
        // 照 DSH 左侧栏：文件夹行右侧的 ⊕。只有文字符号，不自绘 glyph、不引图标库（A7）。
        // 默认工作区（Download/证道）**也给**这个＋：它是真文件夹，用户 2026-10-18 明说了。
        run {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { onRunHere(node) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "＋",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 会话一行：标题（超长省略，只一行）＋ 右侧相对时间。
 *
 * A2 段口径：**只有这两样**——不许把 preview 摘要塞进来，也不许塞轮数／徽标
 * （那些归历史会话浮层，那一页是平铺列表）。
 *
 * 当前会话行的高亮是**另一种强度**（W6）：文字走主色＋加粗，不铺底色——
 * 与工作区行的整行填充刻意不同，一眼分得清"你在哪个文件夹"和"你开着哪一条"。
 */
@Composable
private fun SessionRow(
    row: DrawerSessionRow,
    onOpen: (Long) -> Unit,
    /** 长按＝删除这个会话（2026-10-18：设置页那张「会话记录」并联列表删掉了，删除改在这里）。 */
    onLongPress: (DrawerSessionRow) -> Unit,
) {
    val current = highlightOf(row) == Highlight.SESSION_CURRENT
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onOpen(row.id) },
                onLongClick = { onLongPress(row) },
            )
            .padding(start = 44.dp, end = 16.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
            color = if (current) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = row.timeText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * 抱朴的设置抽屉（顶部＝工作区树）。
 *
 * @param busy 引擎在跑：新会话与换通道都置灰。这是引擎层约束，不是 UI 洁癖——
 *             [com.example.zhengdao.baopu.BaopuChatController] 构造期就持引擎，
 *             换 provider 必须重建控制器，跑到一半换会丢现场。
 *             会话行**不**跟着置灰：撞上单活跃门由 B 段的确认卡去问人（不许自动停）。
 * @param nodes [buildWorkspaceTree] 算好的工作区树（顺序、名字、高亮都在纯函数里定了）。
 * @param onWorkspaceTap 点文件夹行：交出这一行的完整路径（临时提示＋设置页「工作区」卡落点）。
 * @param onWorkspaceHold 长按文件夹行：只给那句临时提示。
 */
@Composable
internal fun BaopuDrawer(
    busy: Boolean,
    nodes: List<DrawerWorkspaceNode>,
    onNewSession: () -> Unit,
    onOpenSession: (Long) -> Unit,
    /** 长按会话行：删除确认（不再有设置页那套平铺列表）。 */
    onSessionLongPress: (DrawerSessionRow) -> Unit,
    onWorkspaceTap: (String) -> Unit,
    onWorkspaceHold: (String) -> Unit,
    /** 文件夹行右侧的「圆里加号」：在这个工作区里开新会话（不是当前工作区时由页面先问人）。 */
    onWorkspaceRunHere: (DrawerWorkspaceNode) -> Unit,
    /** 「工作区」小节标题右侧的 ＋：选一个文件夹加进工作区。 */
    onAddWorkspace: () -> Unit,
    onLibrary: () -> Unit,
    onExtract: () -> Unit,
    onBackup: () -> Unit,
    /** 预览这条回复（刀 4-B 的 `previewLastReply`；本页只负责转交，渲染在 BaopuScreen）。 */
    onPreviewLastReply: () -> Unit,
    /** 清空预览缓存（返回清掉的张数，由调用方说话）。 */
    onPreviewClear: () -> Unit,
    onChannel: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        Text(
            text = stringResource(R.string.baopu_drawer_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 4.dp),
        )
        Text(
            text = stringResource(R.string.baopu_drawer_subtitle),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp),
        )

        DrawerGroup(stringResource(R.string.baopu_drawer_group_session))
        DrawerItem(
            title = stringResource(R.string.baopu_new_session),
            // A2 段：文案要说实话——新会话落在**当前工作区**，记忆（那套跨会话的东西）不动。
            description = stringResource(R.string.baopu_drawer_new_session_desc),
            enabled = !busy,
            onClick = onNewSession,
        )
        // ⛔ 「历史会话」这一行**已删掉**（2026-10-18 用户口径：会话都归在各自工作区下面了，
        // 再留一个「全部会话（平铺看）」就是同一批东西两个入口，抽屉还比 DSH 多一截）。
        // 那一页没消失，只是挪成低频入口：抱朴设置 → 会话记录（删除仍在那儿做）。

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        // ── 工作区树（A 段本体）──
        DrawerGroup(
            title = stringResource(R.string.baopu_drawer_group_workspace),
            trailing = {
                // 标题右侧的 ＋：选一个文件夹加进工作区（落点是设置页那张卡用的同一个选择器）。
                Text(
                    text = "＋",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(28.dp)
                        .clickable { onAddWorkspace() }
                        .padding(4.dp),
                )
            },
        )
        nodes.forEach { node ->
            WorkspaceRow(
                node = node,
                onTap = onWorkspaceTap,
                onHold = onWorkspaceHold,
                onRunHere = onWorkspaceRunHere,
            )
            node.rows.forEach { row ->
                SessionRow(row = row, onOpen = onOpenSession, onLongPress = onSessionLongPress)
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        DrawerGroup(stringResource(R.string.baopu_drawer_group_library))
        DrawerItem(
            title = stringResource(R.string.baopu_drawer_library),
            description = stringResource(R.string.baopu_drawer_library_desc),
            onClick = onLibrary,
        )
        DrawerItem(
            title = stringResource(R.string.baopu_extract_title),
            description = stringResource(R.string.baopu_drawer_extract_desc),
            onClick = onExtract,
        )
        DrawerItem(
            title = stringResource(R.string.baopu_backup_title),
            description = stringResource(R.string.baopu_drawer_backup_desc),
            onClick = onBackup,
        )

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        // 预览两行（刀 4-B 的两个零件函数在这里终于有调用方；第二十九批追加）。
        // 排序刻意放在「设置」之前：它读的是**当前这条回复**，属于会话动作，不属于配置。
        DrawerGroup(stringResource(R.string.baopu_drawer_group_preview))
        DrawerItem(
            title = stringResource(R.string.baopu_drawer_preview_last),
            description = stringResource(R.string.baopu_drawer_preview_last_desc),
            enabled = !busy,
            onClick = onPreviewLastReply,
        )
        DrawerItem(
            title = stringResource(R.string.baopu_drawer_preview_clear),
            description = stringResource(R.string.baopu_drawer_preview_clear_desc),
            onClick = onPreviewClear,
        )

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        DrawerGroup(stringResource(R.string.baopu_drawer_group_settings))
        DrawerItem(
            title = stringResource(R.string.baopu_channel_button),
            description = stringResource(R.string.baopu_drawer_channel_desc),
            enabled = !busy,
            onClick = onChannel,
        )
        DrawerItem(
            title = stringResource(R.string.baopu_drawer_settings),
            description = stringResource(R.string.baopu_drawer_settings_desc),
            onClick = onSettings,
        )
        Spacer(Modifier.height(24.dp))
    }
}
