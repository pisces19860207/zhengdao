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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import com.example.zhengdao.R

/**
 * 抽屉里的一行：标题＋说明＋点击。说明写给"不常用的人"看，省得点进去才知道是什么。
 *
 * 第一百零五批（v1.4 §3.8）只改视觉，**条目一个字没增减**：
 * · 行高 ≥48dp（[BAO_ROW_MIN_HEIGHT_DP]）——条目是低频入口，但手指不会因为"低频"变细；
 * · 整行圆角高亮块（[BaoChipShape]）：点下去/置灰时是一块"可点的面"，不再是一行裸文字；
 * · 标题 16sp·500 走 onSurface，说明 11sp 走 onSurfaceVariant——两级对比靠字号与颜色，
 *   不靠加粗堆叠（旧版标题与说明同色同重，一屏十条读下来没有节奏）；
 * · 置灰（引擎在跑的新会话/换通道、N=0 的回收站）＝两级文字一起降到 38%，
 *   行**仍然占位**（判据 T1：N=0 只置灰不消失）。
 */
@Composable
private fun DrawerItem(
    title: String,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val ink1 = MaterialTheme.colorScheme.onSurface
    val ink2 = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = BAO_ROW_MIN_HEIGHT_DP.dp)
            .clip(BaoChipShape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = BAO_LIST_INSET_DP.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (enabled) ink1 else ink1.copy(alpha = 0.38f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) ink2 else ink2.copy(alpha = 0.38f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 分组标题（会话／工作区／资料与书／预览／设置）。右侧可带一个入口（工作区组用它放「＋」）。
 *
 * 第一百零五批：标题从**主色**改成**次文本色**（onSurfaceVariant）。
 * 七段并排时，五个蓝色组标签会跟主操作抢注意力——组标签是"目录"，不该比条目更亮；
 * 层级改由"上留白 18dp＋小字号"表达（v1.4 §1：分组靠间距不靠颜色）。
 */
@Composable
private fun DrawerGroup(title: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = BAO_LIST_INSET_DP.dp,
                end = BAO_LIST_INSET_DP.dp,
                top = 18.dp,
                bottom = 2.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** ⊕ 的**视觉**圆点边长（24dp——UI 第二批一个字没改）。 */
internal const val RUN_HERE_GLYPH_DP = 24

/** ⊕ 的**命中盒**边长（48dp＝派工单 §1 的目标；上一行那个圆仍然画在正中）。 */
internal const val RUN_HERE_TOUCH_DP = 48

/** 工作区行原来的行尾留白（16dp）；命中盒放大后要靠它做算术补偿。 */
internal const val OLD_WORKSPACE_ROW_END_DP = 16

/**
 * 命中盒从 24dp 扩到 48dp 以后，行尾 padding 该留多少，圆点才**还画在原来的位置上**。
 *
 * 盒子比圆每边多出 (48−24)/2 ＝ 12dp 的空，所以行尾要让出这 12dp：16 − 12 ＝ 4dp。
 * 不补偿 ⇒ 整颗 ⊕ 往左挪 12dp（那就是"改了视觉"，纪律不允许）。
 * 归门禁测（H2）：三个 dp 常量任一改动，这条算术就得跟着变。
 */
internal fun runHereRowEndPaddingDp(oldEndDp: Int, touchDp: Int, glyphDp: Int): Int =
    oldEndDp - (touchDp - glyphDp) / 2

/**
 * 工作区一行（文件夹行）。
 *
 * 显示什么由纯函数算好了交进来（W1：只有末级名，完整路径不进这一行）：
 * - 点一下 ⇒ [onTap]（那句完整路径的临时提示＋落点设置页「工作区」卡，都在抱朴页里做）；
 * - 长按 ⇒ [onHold]（**只**看路径，不跳页——派工单 §7「长按提示」那条口径）。
 *
 * 当前工作区整行**铺底色**（secondaryContainer）＝W6 里强度最高的那一档。
 * 不许做展开／收起动画（A6）：这里就是一个静态行，组的内容永远全展开。
 *
 * ## UI 第二批（2026-10-19 §1）：⊕ 的命中盒从 24dp 扩到 48dp
 *
 * 为什么这一处**必须**动布局，而抽屉「＋」/顶栏跑马灯/「朗读」三处不用动：
 * Compose 自己会把太小的可点目标在**触摸判定**上扩到 `ViewConfiguration.minimumTouchTargetSize`
 * ＝ 48×48dp（`NodeCoordinator.hitTest` 的 `hitNear` 分支；见交付报告 §2 的源码引用）。
 * 但同一份源码的优先级条款写着：**别人的直接命中赢过我的最小触摸扩展命中**。
 * 这一行的 `Row` 自己 `combinedClickable`（点行＝切到这个工作区），所以 ⊕ 那圈 12dp 扩展
 * 被行抢走 ⇒ 实际能点的就是画出来的 24dp。⇒ 只能把命中盒真做进布局。
 *
 * 视觉一个字没换：圆还是 24dp、边框还是 1dp outline、字形还是「＋」，
 * 只是外面那一层可点的方盒子从 24dp 变成 48dp。
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
            // 当前工作区＝**整行铺 tint 底**（v1.4 §3.8 里强度最高的那一档）。
            // 105 批：底从 secondaryContainer（灰）换成 primaryContainer（主色浅底）——
            // 灰底在深色主题下几乎看不出"我在这个文件夹里"，而 tint 底与"当前会话只染字"
            // 正好构成两级强度（一个是块、一个是字）。
            .then(if (current) Modifier.clip(BaoChipShape).background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
            .combinedClickable(
                onClick = { onTap(node.fullPath) },
                onLongClick = { onHold(node.fullPath) },
            )
            // 行尾按算术让位（见 runHereRowEndPaddingDp），上下留白交给 48dp 命中盒自己带
            // （旧 9/9 是给 24dp 内容用的；命中盒 48dp 时圆点居中 ⇒ 上下各 12dp，比原来松 3dp）。
            .padding(
                start = BAO_LIST_INSET_DP.dp,
                end = runHereRowEndPaddingDp(OLD_WORKSPACE_ROW_END_DP, RUN_HERE_TOUCH_DP, RUN_HERE_GLYPH_DP).dp,
            ),
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
                MaterialTheme.colorScheme.onPrimaryContainer
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
                    .size(RUN_HERE_TOUCH_DP.dp)
                    .clickable { onRunHere(node) },
                contentAlignment = Alignment.Center,
            ) {
                // 画出来的还是原来那颗 24dp 的圆（边框、字形、颜色一个字没动），
                // 只是它现在住在一个 48dp 的可点盒子里。
                Box(
                    modifier = Modifier
                        .size(RUN_HERE_GLYPH_DP.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
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
    onLongPress: (DrawerSessionRow, String) -> Unit,
    /** 这一行所属工作区名（2026-10-19：确认框要写清，防"分不清删哪一行"）。 */
    workspaceLabel: String = "",
) {
    val current = highlightOf(row) == Highlight.SESSION_CURRENT
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onOpen(row.id) },
                onLongClick = { onLongPress(row, workspaceLabel) },
            )
            // 缩进＝文件夹行下面一层（BAO_SESSION_INDENT_DP）：从属关系靠缩进，不靠颜色。
            // 上下 7dp 让行高 ≈34dp：会话行是**扫读列表**，不是触控主目标（点开会话另有整行热区）；
            // 这里刻意不套 48dp——一个工作区挂十来条会话时，48dp 会把抽屉撑成三屏（v1.4 §3.8 的取舍）。
            .padding(start = BAO_SESSION_INDENT_DP.dp, end = BAO_LIST_INSET_DP.dp, top = 7.dp, bottom = 7.dp),
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
    /** 抽屉当前是不是打开着（E-163：打开时要重数回收站，不然 N 永远是旧的）。 */
    drawerOpen: Boolean,
    nodes: List<DrawerWorkspaceNode>,
    onNewSession: () -> Unit,
    onOpenSession: (Long) -> Unit,
    /** 长按会话行：删除确认（不再有设置页那套平铺列表）。 */
    onSessionLongPress: (DrawerSessionRow, String) -> Unit,
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
    /** 回收站里恢复/彻底删除会话后，通知页面重读会话库。 */
    onSessionLibraryChanged: () -> Unit = {},
    /** 回收站里「彻底删除」一个会话（两击确认后）：走控制器口子，面板不自己开库。 */
    onPurgeSession: (Long) -> Boolean,
    modifier: Modifier = Modifier,
) {
    // 根 Column 左右各 8dp：条目自己再内缩 16dp ⇒ 文字左缘仍是 24dp（老落点），
    // 但圆角高亮块左右各留 8dp 呼吸位，不再像旧版那样从边贴到边。
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
    ) {
        // 三十六批 A 段：回收站那一行要**现读盘**数个数，所以这里需要一个 Context
        // （取根仍走全仓同一口径 BaopuRuntime.workspaceRoot，不在抽屉里自己拼 Download/证道）。
        val ctx = LocalContext.current
        // 抽屉头部（v1.4 W8）：标题 22sp·600 + 一句"这里是低频入口"的副题。
        // 左右 8+16＝24 与旧版落点一致（根 Column 另有 8dp 边距），下面所有条目左缘与副题对齐。
        Text(
            text = stringResource(R.string.baopu_drawer_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(
                start = BAO_LIST_INSET_DP.dp,
                end = BAO_LIST_INSET_DP.dp,
                top = 20.dp,
                bottom = 2.dp,
            ),
        )
        Text(
            text = stringResource(R.string.baopu_drawer_subtitle),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = BAO_LIST_INSET_DP.dp, end = BAO_LIST_INSET_DP.dp),
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

        HorizontalDivider(
            Modifier.padding(vertical = 6.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

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
                SessionRow(
                    row = row,
                    onOpen = onOpenSession,
                    onLongPress = onSessionLongPress,
                    workspaceLabel = node.label,
                )
            }
        }

        // ── 产物（第三十八批 §9）──────────────────────────────────────────────
        // 派工单 §9.2：入口放在「回收站」**上面**一行。与回收站那一行的两点不同都是它写死的：
        //   1. **永远可点**——N=0 也得进得去。空态那两句（「还没有产物」＋「让它写点东西就有了」）
        //      是 §9.2 点名要的，照着回收站那样置灰就永远看不见；
        //   2. N 数的是**台账**（库里的 artifact 表），不是数目录：这一行要回答的是
        //      「抱朴这一阵子写出来过什么」，回收站那一行回答的是「被扔出去过什么」。
        // E-163 那条教训照抄（判据 C3）：ModalNavigationDrawer 的 drawerContent 一直处于组合中，
        // 只 remember 一次 ⇒ N 永远停在刚进页面那一个值 ⇒ 每次拉开抽屉都重数一遍。
        // 取根走全仓同一处（BaopuRuntime.workspaceRoot），面板的调用在回收站那一段之后（见那里）。
        val artifactRoot = com.example.zhengdao.baopu.BaopuRuntime.workspaceRoot(ctx)
        val artifactDb = com.example.zhengdao.baopu.BaopuRuntime.db(ctx)
        var artifactTick by remember { mutableStateOf(0) }
        LaunchedEffect(drawerOpen) { if (drawerOpen) artifactTick++ }
        val artifactCount = remember(artifactTick, artifactRoot) { artifactShelfCount(artifactDb, artifactRoot) }
        var artifactShelfOpen by remember { mutableStateOf(false) }
        DrawerItem(
            title = stringResource(R.string.baopu_artifact_shelf_title, artifactCount),
            description = stringResource(R.string.baopu_artifact_shelf_desc),
            onClick = { artifactShelfOpen = true },
        )

        // ── 回收站（第三十六批 A 段）────────────────────────────────────────
        // 常驻一行：N=0 时**只把 enabled 关掉，不许整条消失**（判据 T1）。
        // 为什么放在工作区树后面：回收站是**这个工作区里**的一个目录（Download/证道/回收站），
        // 不是全局设置——它跟着「你在哪个文件夹干活」这件事，不跟着「设置」。
        // N 现读盘：目录列表一次而已，且只在抽屉真的重组时读（remember 的键是 tick 与根）。
        // 面板自己管状态（[TrashDialog]）。E-163 之后多了一个形参 `drawerOpen`——抽屉内容常驻组合，
        // 没有「打开」这个信号，N 就永远是刚进页面那一次的值。
        val trashRoot = com.example.zhengdao.baopu.BaopuRuntime.workspaceRoot(ctx)
        var trashTick by remember { mutableStateOf(0) }
        // E-163（真机 2026-10-19 抓到的）：ModalNavigationDrawer 的 drawerContent 一直处于组合中，
        // remember 不会因为「把抽屉拉开」而重算 ⇒ 抱朴刚删完文件，抽屉里仍显示旧 N；
        // 更糟的是旧 N=0 时整行 enabled=false，连面板都打不开（只能重启 App）。所以每次打开都重数一次。
        LaunchedEffect(drawerOpen) { if (drawerOpen) trashTick++ }
        var trashOpen by remember { mutableStateOf(false) }
        // 2026-10-19 用户「移入回收站后以为没移进去」：会话是软删（47 批），
        // 不数它的话删完会话抽屉里的 N 一点不动 ⇒ 看上去像没生效。文件 + 会话一起数。
        val trashDb = com.example.zhengdao.baopu.BaopuRuntime.db(ctx)
        val trashCount = remember(trashTick, trashRoot) {
            val files = trashRows(BaopuTrashBin.dirOf(trashRoot)).size
            val sessions = runCatching {
                trashDb.read { d ->
                    d.rawQuery(
                        com.example.zhengdao.baopu.BaopuSql.countDeletedSessions().sql,
                        emptyArray(),
                    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                }
            }.getOrDefault(0)
            files + sessions
        }
        DrawerItem(
            title = stringResource(R.string.trash_drawer_entry, trashCount),
            description = stringResource(R.string.trash_drawer_desc),
            enabled = trashCount > 0,
            onClick = { trashOpen = true },
        )
        if (trashOpen) {
            TrashDialog(
                workspaceRoot = trashRoot,
                onDismiss = {
                    trashOpen = false
                    trashTick++ // 关掉面板就重数一次：恢复／清空都改了 N
                },
                // 第一百二十一批：47 批起就声明了 onSessionLibraryChanged（页面也一直传着），
                // 却没接到本面板 ⇒ 恢复/彻底删除后抽屉里那份会话库不重读。这条线今天接上。
                onSessionsChanged = onSessionLibraryChanged,
                // 彻底删除走控制器口子（controller.purgeSession），SQL 形状只许一处定。
                onPurgeSession = onPurgeSession,
            )
        }

        // 产物区面板（38 批 §9.2）。调用位置**故意**在回收站那一段之后：
        // onChanged 要把两笔账一起重数（搬一条进回收站 ⇒ 产物 N 减一、回收站 N 加一），
        // 而 `trashTick` 是 36 批那一段里声明的局部量，本批不改它一个字——
        // Kotlin 不许可先读后声明，所以只能把调用放后面。Dialog 是独立窗口，
        // 声明顺序不改变「产物」那一行排在回收站上面这件事（上面那段才是行的顺序）。
        if (artifactShelfOpen) {
            ArtifactShelfDialog(
                workspaceRoot = artifactRoot,
                onChanged = {
                    artifactTick++
                    trashTick++
                },
                onDismiss = {
                    artifactShelfOpen = false
                    artifactTick++ // 关掉面板再数一次：面板里可能已经搬走了几条
                },
            )
        }

        HorizontalDivider(
            Modifier.padding(vertical = 6.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

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

        HorizontalDivider(
            Modifier.padding(vertical = 6.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

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

        HorizontalDivider(
            Modifier.padding(vertical = 6.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

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
