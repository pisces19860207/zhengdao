// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第二十九批 §3 白名单——「新建 ui/baopu/BaopuSessionTree.kt（**只放纯函数**：
// 分组、相对时间；便于 JVM 单测）」＋ §2 A 段（抽屉顶部＝工作区树）的判据本体。
//
// 为什么单开一个文件：抽屉是 Compose，判据写在里面就测不到（JVM 起不了组合）。
// 本文件不 import 任何 Android/Compose 符号——所有"树该长什么样"的决定都在这里算完，
// BaopuDrawer.kt 只把算好的结构画出来。
//
// 口径（逐条对着派工单写，别在这儿发明新规矩）：
//   W1 文件夹行只显示**末级文件夹名**，取名字只走 java.io.File(path).name
//      ——不许自己 split/substring 截串，更不许把完整路径画上表；
//   W2 私有模式 ⇒ 「App 私有」，未授存储权限 ⇒ 「未授权」（未授权优先：hostDir
//      在无权限时本来就回落私有目录，若先判私有会把"没授权"这件事说成"选了私有"）；
//   W4 会话行右侧只有相对时间，五档：刚刚／N 分钟／N 小时／N 天／≥30 天（按月数）；
//   W5 按 session.workspace 分组；workspace 为空（NULL）的老会话归
//      「默认工作区 · 历史未记录」组并**排在最后**；当前工作区**一定出现**（哪怕没会话），
//      其余工作区只在有会话时出现。
//      ⚠️ 这条与第二十批/第二十五批会话库浮层的 groupByWorkspace（NULL 组排**最前**）
//      是两套口径：那边是"存量老会话别垫底看不见"，这边是"先看在哪个文件夹干活、
//      历史遗留沉底"。派工单 §4 W5 明写排最后，本文件按 W5；浮层那份文件本批禁改，
//      两处不一致已在交付报告里如实报给蓝总管。
package com.example.zhengdao.ui.baopu

import java.io.File

// ── 相对时间（W4）──────────────────────────────────────────────────────

/** 相对时间的五个档位；[MONTHS] 就是派工单说的「≥30 天」那一档。 */
internal enum class RelativeBucket { JUST_NOW, MINUTES, HOURS, DAYS, MONTHS }

/** 一档相对时间：档位＋数字（[RelativeBucket.JUST_NOW] 的 [count] 恒为 0）。 */
internal data class RelativeAge(val bucket: RelativeBucket, val count: Int)

/** 五个档位的本地化模板（`%1$d` 占位；从 strings.xml 取，格式不写死在代码里）。 */
internal data class RelativeTemplates(
    val justNow: String,
    val minutes: String,
    val hours: String,
    val days: String,
    val months: String,
)

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L

/** 「≥30 天」那一档的分界：30 天，按 30 天折算月数（不是日历月——抽屉只看"多久没碰"）。 */
private const val MONTH_EDGE_MS = 30L * DAY_MS

/**
 * 多久没碰（W4 判据本体）。
 *
 * 三条边界都是**左闭右开**：59 秒⇒刚刚，60 分钟⇒1 小时，24 小时⇒1 天，30 天⇒1 个月。
 * `updatedAt <= 0`（库里没写过）与 `updatedAt > now`（时钟往回跳过）一律按 0 差处理成「刚刚」——
 * 抽屉不许出现「-3 分钟」这种话。
 */
internal fun relativeAge(updatedAt: Long, now: Long): RelativeAge {
    if (updatedAt <= 0L) return RelativeAge(RelativeBucket.JUST_NOW, 0)
    val delta = (now - updatedAt).coerceAtLeast(0L)
    return when {
        delta < MINUTE_MS -> RelativeAge(RelativeBucket.JUST_NOW, 0)
        delta < HOUR_MS -> RelativeAge(RelativeBucket.MINUTES, (delta / MINUTE_MS).toInt())
        delta < DAY_MS -> RelativeAge(RelativeBucket.HOURS, (delta / HOUR_MS).toInt())
        delta < MONTH_EDGE_MS -> RelativeAge(RelativeBucket.DAYS, (delta / DAY_MS).toInt())
        else -> RelativeAge(RelativeBucket.MONTHS, (delta / MONTH_EDGE_MS).toInt())
    }
}

/** 档名 → 成品文字（模板来自 [RelativeTemplates]）。**刚刚**那一档不吃数字。 */
internal fun relativeTimeText(age: RelativeAge, t: RelativeTemplates): String = when (age.bucket) {
    RelativeBucket.JUST_NOW -> t.justNow
    RelativeBucket.MINUTES -> fillCount(t.minutes, age.count)
    RelativeBucket.HOURS -> fillCount(t.hours, age.count)
    RelativeBucket.DAYS -> fillCount(t.days, age.count)
    RelativeBucket.MONTHS -> fillCount(t.months, age.count)
}

/**
 * `%1$d` 手工替换，不用 `String.format`（同 `bridge/BrowserCommands.kt` 那条口径：
 * 跟着设备 locale 走，数字字形与分隔符会漂；抽屉这一列只要稳定）。
 */
private fun fillCount(template: String, count: Int): String =
    template.replace("%1\$d", count.toString())

/** 一步到位：时间戳 → 抽屉右侧那一列的文字。 */
internal fun relativeTimeText(updatedAt: Long, now: Long, t: RelativeTemplates): String =
    relativeTimeText(relativeAge(updatedAt, now), t)

// ── 文件夹行显示名与符号（W1／W2／A7）───────────────────────────────────

/**
 * 工作区这一行的状态（W2＋A7）。
 *
 * 图标只用文字符号（📁／🔒／⚠️）——派工单 A7 明写「不许引图标库、不许新自绘 glyph」，
 * 所以符号是**数据**而不是资源，由纯函数给出，抽屉照着画。
 */
internal enum class WorkspaceState { NORMAL, PRIVATE, UNAUTHORIZED, UNRECORDED }

/**
 * 状态判定（先未授权后私有）。
 *
 * 为什么这个顺序不能反：`Workspace.hostDir` 在无权限时**本来就回落成私有目录**，
 * 先判私有会把"没给存储权限"显示成"你选了仅私有"——那是两件事，说错了人就找不到授权入口了。
 */
internal fun workspaceStateOf(storageGranted: Boolean, privateMode: Boolean): WorkspaceState =
    if (!storageGranted) WorkspaceState.UNAUTHORIZED else if (privateMode) WorkspaceState.PRIVATE else WorkspaceState.NORMAL

/** 文件夹行最左边那个符号（只有这三种文字形状）。 */
internal fun folderIcon(state: WorkspaceState): String = when (state) {
    WorkspaceState.NORMAL -> "📁"
    WorkspaceState.UNRECORDED -> "📁"
    WorkspaceState.PRIVATE -> "🔒"
    WorkspaceState.UNAUTHORIZED -> "⚠️"
}

/**
 * 末级文件夹名（W1 唯一口径）：只允许 `File(path).name`，**不许** split("/")、
 * 不许 substringAfterLast、不许把完整路径画上表。
 *
 * 空串兜底：真给空串时退回原路径——宁可长也不能显示一个没字的行（那比长更糟）。
 */
internal fun folderLeafName(hostAbsolutePath: String): String =
    File(hostAbsolutePath).name.ifBlank { hostAbsolutePath }

/**
 * 文件夹行到底写什么（W1＋W2 合起来的那一支）。
 *
 * @param privateLabel 私有模式文案（「App 私有」）
 * @param unauthorizedLabel 未授权文案（「未授权」）
 */
internal fun workspaceFolderLabel(
    hostAbsolutePath: String,
    state: WorkspaceState,
    privateLabel: String,
    unauthorizedLabel: String,
): String = when (state) {
    WorkspaceState.UNAUTHORIZED -> unauthorizedLabel
    WorkspaceState.PRIVATE -> privateLabel
    WorkspaceState.NORMAL, WorkspaceState.UNRECORDED -> folderLeafName(hostAbsolutePath)
}

// ── 工作区树（W5）──────────────────────────────────────────────────────

/** 树里的一条会话（A2 段口径：**只有标题＋相对时间**，不许塞摘要、不许塞轮数）。 */
internal data class DrawerSessionRow(
    val id: Long,
    val title: String,
    val timeText: String,
    val isCurrent: Boolean,
)

/** 树里的一个文件夹节点。 */
internal data class DrawerWorkspaceNode(
    /** 归组键＝库里的 workspace 原值（去掉尾斜杠）；[unknown] 组为 null。 */
    val key: String?,
    /** 文件夹行的文字（末级名／App 私有／未授权／历史未记录组名）。 */
    val label: String,
    /** 点一下临时提示要的那句完整路径；未记录组用默认目录（不是"当时真跑过"的目录，故 [unknown] 为真时 UI 另标小字）。 */
    val fullPath: String,
    /** 是不是当前工作区（W3／W6 整行高亮就看它）。 */
    val isCurrent: Boolean,
    /** 这一行的状态（决定 🔒／⚠️／📁 与是否显示「未授权」）。 */
    val state: WorkspaceState,
    /** 是不是「历史未记录」组（永远排最后）。 */
    val unknown: Boolean,
    val rows: List<DrawerSessionRow>,
)

/** 归组键：只把尾斜杠当同一个目录，其余原样比较（不许在这里做名字截断——那是显示层的事）。 */
private fun groupKey(path: String): String = path.trimEnd('/')

/**
 * 会话库 → 工作区树（W5 判据本体）。
 *
 * 分组与排序：
 * 1. 有会话的组按「组内最新一条 updated_at」倒序；
 * 2. **当前工作区一定出现**：库里没它的会话时补一个空节点，排在有会话的组之后、
 *    未记录组之前（排最前会抢掉"最近在用"的位置，排最后又看不出自己在哪干活）；
 * 3. 未记录组（workspace 为 NULL／空白）只在有存量老会话时出现，**永远最后**；
 * 4. 组内行按 updated_at 倒序（SQL 已排，这里再钉一次，与第二十批同一防御口径）。
 *
 * @param entries [BaopuChatController.loadSessionLibrary] 的原始行（含 workspace 可空）
 * @param currentHostPath 当前工作区宿主目录绝对路径（[com.example.zhengdao.terminal.Workspace.hostDir]）
 * @param currentLabel 当前工作区文件夹行文字（[workspaceFolderLabel] 算出来的那个）
 * @param currentState 当前工作区状态（[workspaceStateOf] 算出来的那个；历史组一律 [WorkspaceState.NORMAL]）
 * @param unknownLabel 「默认工作区 · 历史未记录」
 * @param unknownPath 未记录组点开提示用的路径 = 默认目录绝对路径（只作提示，**不回填库**）
 * @param fallbackTitle 标题兜底的最后一环（库里没标题也没首句 ⇒ 「未命名会话」）
 */
internal fun buildWorkspaceTree(
    entries: List<BaopuSessionEntry>,
    currentHostPath: String,
    currentLabel: String,
    currentState: WorkspaceState,
    unknownLabel: String,
    unknownPath: String,
    currentSessionId: Long?,
    now: Long,
    templates: RelativeTemplates,
    fallbackTitle: String,
): List<DrawerWorkspaceNode> {
    val currentKey = groupKey(currentHostPath)
    // 「默认工作区」就是 `Download/证道` 这个**真文件夹**（2026-10-18 用户口径）。
    // 所以：跑在默认目录里的会话与 NULL（历史未记录）会话**归同一组**，
    // 不能再并排列出「证道」与「默认工作区」两行——那是同一个目录的两张脸。
    val defaultKey = groupKey(unknownPath)
    fun isDefaultWs(ws: String?): Boolean = ws.isNullOrBlank() || groupKey(ws) == defaultKey
    val known = entries.filter { !isDefaultWs(it.workspace) }
    val unknownEntries = entries.filter { isDefaultWs(it.workspace) }
    val grouped = known.groupBy { groupKey(it.workspace!!) }

    // 组间序锚＝组内最新一条 updated_at；没会话的当前工作区取 -1 ⇒ 排在有会话的组之后
    fun anchor(key: String): Long = grouped[key]?.maxOfOrNull { it.updatedAt } ?: -1L
    // 当前工作区即使是空的也一定出现（W5）——但它要是默认目录，就归默认组那一行，
    // 不许在这里再冒出一个「证道」组（同一个目录两张脸）。
    val keys = (grouped.keys + currentKey).toList()
        .filter { it != defaultKey }
        .sortedWith(compareByDescending { anchor(it) })

    fun rowsOf(list: List<BaopuSessionEntry>): List<DrawerSessionRow> = list
        .sortedByDescending { it.updatedAt }
        .map { rowOf(it, currentSessionId, now, templates, fallbackTitle) }

    val nodes = keys.map { key ->
        val current = key == currentKey
        DrawerWorkspaceNode(
            key = key,
            label = if (current) currentLabel else folderLeafName(key),
            fullPath = key,
            isCurrent = current,
            state = if (current) currentState else WorkspaceState.NORMAL,
            unknown = false,
            rows = rowsOf(grouped[key].orEmpty()),
        )
    }

    // 「默认工作区」**永远在**（2026-10-18 用户口径，照 DSH 左侧栏）：它是历史会话的收容所，
    // 空着也要显示一行，让人知道「老会话归这里」，而不是让它一会儿有一会儿没有。
    // 有 NULL 老会话就挂在这一行下面；一行都没有也照样列出来。
    // 抱朴此刻就在默认目录里干活 ⇒ 这一行就是**当前工作区**，要跟别的组一样被高亮成当前。
    val defaultIsCurrent = currentKey == defaultKey
    val defaultNode = DrawerWorkspaceNode(
        key = null,
        label = unknownLabel,
        fullPath = unknownPath,
        isCurrent = defaultIsCurrent,
        state = if (defaultIsCurrent) currentState else WorkspaceState.UNRECORDED,
        unknown = true,
        rows = rowsOf(unknownEntries),
    )
    return nodes + listOf(defaultNode)
}

private fun rowOf(
    e: BaopuSessionEntry,
    currentSessionId: Long?,
    now: Long,
    templates: RelativeTemplates,
    fallbackTitle: String,
): DrawerSessionRow = DrawerSessionRow(
    id = e.id,
    title = displayTitle(e.title, fallbackTitle),
    timeText = relativeTimeText(e.updatedAt, now, templates),
    isCurrent = currentSessionId != null && e.id == currentSessionId,
)

/**
 * 双高亮的强度判据（W6：当前工作区整行与当前会话行**不能同一个视觉强度**）。
 *
 * 判据本身是数据（谁是当前工作区／谁是当前会话），强度差由抽屉层用**两套不同语义**落地：
 * 工作区行 = 底色填充（secondaryContainer），会话行 = 文字加粗＋主色（不着底色）。
 * 这里把两套语义写成枚举，单测直接断言"两支不一样"，防止哪天有人图省事共用一个。
 */
internal enum class Highlight { NONE, WORKSPACE_CURRENT, SESSION_CURRENT }

/** 一个节点该用哪种高亮（会话行由 [DrawerSessionRow.isCurrent] 自己带）。 */
internal fun highlightOf(node: DrawerWorkspaceNode): Highlight =
    if (node.isCurrent) Highlight.WORKSPACE_CURRENT else Highlight.NONE

/** 一行会话该用哪种高亮。 */
internal fun highlightOf(row: DrawerSessionRow): Highlight =
    if (row.isCurrent) Highlight.SESSION_CURRENT else Highlight.NONE
