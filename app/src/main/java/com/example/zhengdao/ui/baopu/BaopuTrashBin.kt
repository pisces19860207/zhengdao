// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第三十六批 §1 A 段——抽屉里加一个「回收站（N）」入口，点开能**恢复**、能**彻底删除**、
// 底部能**清空回收站**。
//
// 为什么这套放在 `ui/baopu/` 而不是复用工具层：
//   回收站这套目录本来就有实现（`baopu/agent/tools/DeletePathTool.kt`），但那是**抱朴自己**干活用的
//   工具：它的入参来自模型、它的确认卡来自引擎（B 段），UI 走那条路等于给自己开一条
//   「绕过工具层权限模型」的旁路。派工单原话：**不许调 `DeletePathTool`**。
//   所以这里把「读目录／搬回来／删掉」这三件小事用**纯函数＋文件操作**重写一遍，
//   判据能在 JVM 里用真临时目录直接断言（T1–T3）。
//
//   唯一两边共享的是**目录名口径**：`TRASH_DIR` 这里重写了一次字面量。这不是「双份真相」——
//   `BaopuTrashBinTest` 钉着「两边的常量必须同一个字」，谁哪天改了哪一边都会红（比引用它更硬，
//   而且不构成调用关系）。
//
// 三条硬口径（都是判据，不是风格）：
//   1. **只在 `回收站/` 里活动**，且只碰**文件**：子目录一律不列、不动、不删（T1）。
//      边界用 `PathGuard.isInside` 判（按路径组件比较，不是字符串前缀——同前缀兄弟目录那种洞
//      在加固-1 里已经堵过一次，这里不重新发明）。
//   2. **恢复绝不覆盖**：工作区根上已经有同名的东西 ⇒ 拒绝并指名占位者（T2）。
//      占位的那份可能是用户刚放进去的另一个文件，覆盖是这一条最不能出的事故。
//   3. **删除必须二次确认**：`confirmed=false` 这条路一个字节都不动盘（T3）。
//      闸做在纯函数里，界面第一击只能拿到 `NeedsConfirm`，第二击才带 true——
//      这样「没确认就不许删」能在 JVM 里断言，而不只是按钮的选中态。
//
// 恢复落点为什么是**工作区根**：进回收站时名字被改成 `<时间戳>-<原名>`（工具层的口径），
// 原目录信息在名字里已经丢了 ⇒ 不猜。派工单 §1 也是这条口径（「默认搬回工作区根」）。
// 名字**保留时间戳前缀**：那是唯一能对上是哪一次删的线索，而剥掉它得把工具层
// `-2`／`-3` 撞名后缀的规则整套抄过来——那才是把数据层的事搬到界面重算。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.core.PathGuard
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.zhengdao.R
import com.example.zhengdao.baopu.BaopuGuardCore
import com.example.zhengdao.core.RunLog
import com.example.zhengdao.core.TimeFormats
import com.example.zhengdao.keepalive.CoroutineCrashHandler
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 回收站一行 = **盘上事实**，没有一句人话。
 *
 * 怎么说由界面查 `strings.xml`（34 批 U6 那条纪律沿用：数据层/纯函数层只报「哪件事没做成、
 * 涉及哪个文件」，界面负责组织语言，中英才对称得了）。
 *
 * @param file 回收站里那个文件（绝对路径）
 * @param name 回收站里的文件名（带 `<时间戳>-` 前缀，就是用户在列表里看到的那串）
 * @param bytes 大小（取不到当 0——列表不能因为一个 length 读失败就整页空）
 * @param modifiedAt 最后修改时间（毫秒），进回收站那一刻≈删除时刻
 */
internal data class TrashRow(
    val file: File,
    val name: String,
    val bytes: Long,
    val modifiedAt: Long,
)

/** 回收站的落点（工作区根下**不带点的可见目录**，与工具层同一个字）。 */
internal object BaopuTrashBin {

    /** 目录名。⚠️ 与 `DeletePathTool.TRASH_DIR` 必须一模一样——漂移由测试钉红，不靠人记。 */
    const val TRASH_DIR = "回收站"

    /** 工作区根 ⇒ 回收站目录（不在别处拼这个路径）。 */
    fun dirOf(workspaceRoot: File): File = File(workspaceRoot, TRASH_DIR)
}

/**
 * 列回收站（判据 T1）。
 *
 * 只收**文件**：子目录不列（工具层清空时也只删文件、留子目录，两边同一口径）；
 * 软链不列（`BaopuGuardCore.isSymlink`，不自己再写一份判定）；
 * 目录不存在 ⇒ 空表（首次使用还没有回收站是常态，不是错误）；读取被拒 ⇒ 也当空表，**不抛**。
 *
 * 排序按名字**字典序**（列表要稳定，且 JVM 断言才有确定值可写）。
 */
internal fun trashRows(dir: File): List<TrashRow> = trashListing(dir, Int.MAX_VALUE).rows

/**
 * 回收站一屏的条数（第二百六十四批：回收站大时不再把所有条目的 length 一次读进内存）。
 *
 * 80 条的理由：真机上开面板那一帧的开销已由 LazyColumn 挡住（只组合看得见的几条），
 * 一页仍给到能连续滚一屏的量；「显示更多」一次加一页，顺序不变。
 */
internal const val TRASH_PAGE = 80

/**
 * 回收站的一页读数。
 *
 * @param rows 排序后**前 limit 条**（每条的 `length`/`mtime` 才是逐文件 stat 的开销）
 * @param total 符合口径的文件**总数**（只数名字，不取大小）
 *
 * 第二百六十四批（A-P2-2）：原来面板在组合期 `trashRows` 全量读——2,000 条就是 2,000 次
 * `length()`＋`lastModified()`，开面板那一下主线程直接卡住。现在排序/计数在 IO 线程，
 * 且只对**前 N 条**取大小；其余按「还有 N 条」＋「显示更多」展开，内容与顺序不变。
 */
internal data class TrashListing(
    val rows: List<TrashRow>,
    val total: Int,
    /**
     * 全量文件的总体积（只数文件、不含子目录）。
     *
     * 第二百六十四批补齐：清空的确认红字要「N 个文件、共 X MB」，而分页后 `rows` 只有前 N 条
     * ——**拿前 80 条的 bytes 去当 2,000 条的总体积，就是给用户一个假数字**。
     * `wantBytes=false` 时为 0（抽屉只数个数，不需要体积，别为它白 stat 一遍）。
     */
    val totalBytes: Long = 0L,
)

/**
 * 分页列回收站：先只按名字排序（不 stat 大小），再对前 `limit` 条取 `length`/`mtime`。
 *
 * 顺序口径与 [trashRows] 逐字一致（名字字典序）；`limit` 只影响「一次读多少」，不影响顺序。
 *
 * @param wantBytes true ⇒ **全量**再 stat 一遍求 [TrashListing.totalBytes]（2,000 条约 100ms，
 *   只在 IO 线程上调，见 `TrashDialog` 的快照）；false ⇒ 只 stat `rows` 那一页。
 */
internal fun trashListing(
    dir: File,
    limit: Int = TRASH_PAGE,
    wantBytes: Boolean = false,
): TrashListing {
    if (!dir.isDirectory) return TrashListing(emptyList(), 0)
    val kids = runCatching { dir.listFiles() }.getOrNull() ?: return TrashListing(emptyList(), 0)
    val files = kids
        .filter { it.isFile && !BaopuGuardCore.isSymlink(it) }
        .sortedBy { it.name }
    val totalBytes = if (wantBytes) {
        files.sumOf { runCatching { it.length() }.getOrDefault(0L) }
    } else {
        0L
    }
    val rows = files.take(limit.coerceAtLeast(0)).map { f ->
        TrashRow(
            file = f,
            name = f.name,
            bytes = runCatching { f.length() }.getOrDefault(0L),
            modifiedAt = f.lastModified(),
        )
    }
    return TrashListing(rows, files.size, totalBytes)
}

/**
 * 一步操作的下场。同样**只带事实不带句子**：
 * 撞名要说清被谁占着、没确认要说清数量与体积、盘上没做成要带上是哪个文件与原因——一条都不许静默。
 */
internal sealed interface TrashOutcome {

    /** 还没二次确认 ⇒ **什么都没动**（判据 T3 的闸就在这）。[count]/[bytes] 给确认文案用。 */
    data class NeedsConfirm(val target: String, val count: Int, val bytes: Long) : TrashOutcome

    /** 搬回工作区根成功。[target] 是它现在的绝对路径。 */
    data class Restored(val name: String, val target: String) : TrashOutcome

    /** 目标位已经被占（判据 T2：**拒绝并指名占位者**，一个字节都不覆盖）。 */
    data class Occupied(val target: String) : TrashOutcome

    /** 真删了（只有先经 [NeedsConfirm] 才可能走到这条）。[failed] 是没删掉的个数（清空时可能是部分）。 */
    data class Deleted(val target: String, val count: Int, val bytes: Long, val failed: Int = 0) : TrashOutcome

    /** 这一步不该做（越界、不是回收站里的文件、名字不合法）——同样一个字节都不动。 */
    data class Rejected(val target: String, val why: String) : TrashOutcome

    /** 盘上没做成（文件不见了／改名被拒／删除被拒）。 */
    data class IoFailed(val target: String, val why: String) : TrashOutcome
}

/**
 * 回收站里那一份相对工作区根的显示路径（报给用户看的永远是相对路径，不是一长串 /storage/…）。
 *
 * 口径与仓里其它地方一致：`relativeTo(root).path` 再把 `\` 换成 `/`（Windows 上直接拿
 * `absolutePath` 剥前缀会剩一个开头的 `\`，那是分隔符不是路径）。真算不出相对路径
 * （文件根本不在根下面，或名字里带 `..`）⇒ 退回只报文件名，不许把越界的串念给用户。
 */
internal fun trashRelOf(root: File, file: File): String {
    val rel = runCatching { file.relativeTo(root).path.replace('\\', '/') }.getOrNull()
    return if (rel != null && rel.isNotBlank() && !rel.contains("..")) rel else file.name
}

/**
 * 恢复落点（判据 T2 的前半）：**工作区根＋原名（带时间戳前缀）**。
 *
 * 名字不合法（空、带 `/`、`.`／`..`）⇒ null ⇒ 调用方拒绝。这一条同时兜住「名字里被人塞了路径」：
 * 目录列表里本来不该出现这种名字，但判定不靠「应该不会」。
 */
internal fun restoreTargetOf(root: File, row: TrashRow): File? {
    val name = row.name
    if (name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\')) {
        return null
    }
    val target = File(root, name)
    return if (PathGuard.isInside(root, target)) target else null
}

/**
 * 把回收站里的一份搬回工作区根（判据 T2）。
 *
 * 顺序就是纪律：先验「这一份确实在回收站里」⇒ 再验它还在盘上是个文件 ⇒ 再算落点 ⇒
 * **落点有东西就拒绝并指名** ⇒ 最后才 `renameTo`（只搬文件，不复制、不覆盖、不写内容）。
 */
internal fun restoreTrash(row: TrashRow, root: File): TrashOutcome {
    val dir = BaopuTrashBin.dirOf(root)
    val rel = trashRelOf(root, row.file)
    if (!PathGuard.isInside(dir, row.file)) {
        return TrashOutcome.Rejected(rel, "不在回收站里")
    }
    if (!row.file.isFile) return TrashOutcome.IoFailed(rel, "missing")
    if (BaopuGuardCore.isSymlink(row.file)) return TrashOutcome.Rejected(rel, "symlink")
    val target = restoreTargetOf(root, row) ?: return TrashOutcome.Rejected(row.name, "bad_name")
    if (PathGuard.isInside(dir, target)) {
        // 名字撞回回收站自己（例如回收站里有个文件就叫「回收站」）：不搬，免得把目录本身搬走。
        return TrashOutcome.Rejected(rel, "inside_trash")
    }
    if (target.exists()) return TrashOutcome.Occupied(target.absolutePath)
    val moved = runCatching { row.file.renameTo(target) }
    if (moved.isFailure || !moved.getOrDefault(false) || !target.isFile) {
        return TrashOutcome.IoFailed(rel, moved.exceptionOrNull()?.javaClass?.simpleName ?: "rename")
    }
    return TrashOutcome.Restored(row.name, target.absolutePath)
}

/**
 * 彻底删除**一个**文件（判据 T3）。
 *
 * `confirmed=false` ⇒ 只回报 [TrashOutcome.NeedsConfirm]，盘上那个文件必须还在——
 * 界面第一击走的就是这一条，文案里写清「删了不可恢复」。
 */
internal fun deleteTrash(row: TrashRow, root: File, confirmed: Boolean): TrashOutcome {
    val dir = BaopuTrashBin.dirOf(root)
    val rel = trashRelOf(root, row.file)
    if (!PathGuard.isInside(dir, row.file)) return TrashOutcome.Rejected(rel, "不在回收站里")
    if (!row.file.isFile) return TrashOutcome.IoFailed(rel, "missing")
    if (!confirmed) return TrashOutcome.NeedsConfirm(rel, count = 1, bytes = row.bytes)
    val gone = runCatching { row.file.delete() }.getOrDefault(false)
    return if (gone && !row.file.exists()) {
        TrashOutcome.Deleted(rel, count = 1, bytes = row.bytes)
    } else {
        TrashOutcome.IoFailed(rel, "delete")
    }
}

/**
 * 清空回收站（判据 T3 的第二处）。
 *
 * 同样先 `NeedsConfirm`；确认后**只删文件**——子目录一个不动（留在盘上让用户自己看）。
 * 部分失败不装作全清完：`Deleted.failed` 带着没删掉的个数，界面必须把它念出来。
 */
internal fun emptyTrash(dir: File, confirmed: Boolean): TrashOutcome {
    val rows = trashRows(dir)
    val total = rows.sumOf { it.bytes }
    if (!confirmed) return TrashOutcome.NeedsConfirm(dir.name, rows.size, total)
    var count = 0
    var bytes = 0L
    var failed = 0
    rows.forEach { row ->
        val gone = runCatching { row.file.delete() }.getOrDefault(false)
        if (gone && !row.file.exists()) {
            count++
            bytes += row.bytes
        } else {
            failed++
        }
    }
    return TrashOutcome.Deleted(dir.name, count, bytes, failed)
}

/**
 * 回收站动作的**唯一执行体**（第二百六十四批补齐）：读盘/删盘在 IO，账先落，再回界面。
 *
 * ## 为什么必须 `NonCancellable` 包住整个
 * 动作由面板按钮发起，`rememberCoroutineScope`（**带 handler 的那个写法**，见下方发起处）
 * 绑的是 `Dialog` 的组合——点一下外面、
 * 按返回键，`onDismiss` 就把这个 scope 取消了。而 `File.delete()` **不是挂起函数，不会被打断**：
 * 取消只会在 `withContext` 退出时抛 `CancellationException`，把后面的 `sayBatch` / `tick++`
 * 一起吞掉。真实后果是「文件删了、界面一句话不说、用户以为没删成，再点一次」。
 *
 * ⇒ 整段（含落账与回灌）装在 `NonCancellable` 里：取消只影响「谁在等」，不影响「这件事做完没有」。
 *
 * ## 为什么先落 RunLog 再回灌界面
 * 界面可能已经没了（面板被关掉），但账必须留下——这段代码的可核查性就落在这一行上。
 * 顺序不能反：先 `report` 后落账，`report` 抛异常（极端情况下组合已死）就把账一起丢了。
 *
 * @param mainDispatcher 回灌界面用的调度器；默认主线程。单测传 `Dispatchers.Unconfined`
 *   （JVM 里没有 `Dispatchers.Main`）。
 */
internal suspend fun <T> runTrashAction(
    label: String,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    block: () -> T,
    describe: (T) -> String,
    report: (T) -> Unit,
) = withContext(NonCancellable) {
    val outcome = withContext(Dispatchers.IO) { block() }
    // 先落账（面板此刻可能已经关了，这一行是唯一留下的证据）
    RunLog.log("回收站$label：${describe(outcome)}")
    withContext(mainDispatcher) { report(outcome) }
}

/**
 * 把 [TrashOutcome] 写成**一句人话**（进 RunLog）。
 *
 * 为什么不用 `javaClass.simpleName`：日志是给人查的，`Deleted` 不如「删了 2,000 个（1.9 MB）」；
 * 尤其清空可能是**部分失败**，`failed` 不念出来就等于把"没删干净"藏起来。
 */
internal fun outcomeLine(o: TrashOutcome): String = when (o) {
    is TrashOutcome.NeedsConfirm -> "待确认（${o.count} 个 / ${humanBytes(o.bytes)}）"
    is TrashOutcome.Restored -> "已恢复 ${o.name}"
    is TrashOutcome.Occupied -> "撞名未动（${o.target}）"
    is TrashOutcome.Deleted -> when {
        o.failed > 0 -> "删了 ${o.count} 个，${o.failed} 个没删掉"
        else -> "删了 ${o.count} 个（${humanBytes(o.bytes)}）"
    }
    is TrashOutcome.Rejected -> "拒绝（${o.target}：${o.why}）"
    is TrashOutcome.IoFailed -> "失败（${o.target}：${o.why}）"
}

/**
 * 体积说成人话（确认文案要「N 个文件、共 X MB」）。
 *
 * **整数运算**，不用浮点也不用 `Locale`——这样 JVM 里的期望值是写死的字符串，
 * 不会因为某台机器的区域设置把小数点变成逗号而红。十档以内都不该看到 0.0 MB 这种东西。
 */
internal fun humanBytes(bytes: Long): String {
    val kb = 1024L
    val mb = kb * 1024
    val gb = mb * 1024
    val b = if (bytes < 0) 0L else bytes
    return when {
        b < kb -> "$b B"
        b < mb -> oneDecimal(b, kb, "KB")
        b < gb -> oneDecimal(b, mb, "MB")
        else -> oneDecimal(b, gb, "GB")
    }
}

/** 一位小数（四舍五入到十分位），全程 Long。 */
private fun oneDecimal(value: Long, unit: Long, suffix: String): String {
    val tenths = (value * 10 + unit / 2) / unit
    val whole = tenths / 10
    val frac = tenths % 10
    return if (frac == 0L) "$whole $suffix" else "$whole.$frac $suffix"
}

/**
 * 回收站面板的一份快照（第二百六十四批）。
 *
 * 「还在读（null）」与「读到了、确实是空的（total=0）」是**两种不同的画面**（派工单负例 3）：
 * 前者画「读取中…」，后者才画「回收站里没有文件」。
 */
private data class TrashPanelData(
    val listing: TrashListing,
    val sessions: List<Triple<Long, String, Int>>,
)

/**
 * 回收站面板（三十六批 A 段的「点开」）。
 *
 * 为什么状态放在这里而不是由 `BaopuScreen` 传进来：**派工单不许动 `BaopuScreen.kt`**，
 * 抽屉的形参一个都不能加（加了就是逼着那个文件改调用点）。所以这一页自己现读盘、
 * 自己管「等第二击」，只把 [onDismiss] 交回给抽屉（抽屉借此刷新那个 N）。
 *
 * 三条判据在这一屏的形状：
 * - T2：恢复点了就走 [restoreTrash]，撞名 ⇒ [TrashOutcome.Occupied]，界面把占位者路径念出来，
 *   **一个覆盖的分支都没有**；
 * - T3：彻底删除与清空都是**两击**——第一击纯函数返回 [TrashOutcome.NeedsConfirm]（盘没动），
 *   这一行/那一按钮下面出现那句红字（写清数量与「恢复不了」），第二击才带 true。
 *   确认位靠的是**比对盘上路径**（`confirmingFile == row.file.absolutePath`），不是写死的 true；
 * - 每条下场的句子都查 `strings.xml`（中英对称归 T6）。
 *
 * 第一百二十一批（死代码转正）：会话的「彻底删除」不再在本面板里内联硬删，改走
 * [onPurgeSession] 口子（`BaopuChatController.purgeSession`）——硬删的 SQL 形状只许
 * `BaopuSql.deleteSessionStatements` 一处定，面板只管两击确认与下场说话。
 * 同批把 `onSessionsChanged` 这条线接上：47 批起抽屉就声明了
 * `onSessionLibraryChanged`（Screen 也一直传着），却没有传到本面板 ⇒ 恢复/彻底删除后
 * 抽屉里那份会话库不重读（2026-10-19 用户「列表像没变」）。
 */
@Composable
internal fun TrashDialog(
    workspaceRoot: File,
    onDismiss: () -> Unit,
    /** 会话被恢复/彻底删除后回调：抽屉里那份会话库要重读（2026-10-19 用户「显示慢」）。 */
    onSessionsChanged: () -> Unit = {},
    /** 彻底删除一个会话（第二击确认后）：走控制器口子，返回是否成功；面板不自己开库。 */
    onPurgeSession: (Long) -> Boolean,
) {
    val ctx = LocalContext.current
    val dir = BaopuTrashBin.dirOf(workspaceRoot)
    // 第二百六十四批补齐：动作的唯一发起口。**不要**换成 `GlobalScope`/自建 scope——
    // 取消语义已经由 [runTrashAction] 的 NonCancellable 兜住，这里只负责「发起」。
    val scope = rememberCoroutineScope { CoroutineCrashHandler }
    // 一条动作在飞时不接第二条：恢复没有确认位，连点两次会各删各的（第二次必然失败并念错话）。
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    // 一次展开多少条（第二百六十四批 A-P2-2：2,000 条不再一次全读进内存）。
    var page by remember { mutableIntStateOf(TRASH_PAGE) }
    // 「等第二击」记的是**哪一个文件**（绝对路径）；换一条就得重新确认，
    // 关掉面板也清掉（下次进来重新问一遍——判据 T3 不许记住上次的许可）。
    var confirmingFile by remember { mutableStateOf<String?>(null) }
    var confirmingEmpty by remember { mutableStateOf(false) }
    // 清空那一击拿到的真实数量与体积（分页之后 rows 只有前 N 条，不能拿它算这句话）
    var emptyNeeds by remember { mutableStateOf<TrashOutcome.NeedsConfirm?>(null) }
    // 第四十七批：会话回收站（软删）——与文件回收站同一个面板，数字分开数。
    val res = LocalResources.current
    val db = remember { com.example.zhengdao.baopu.BaopuRuntime.db(ctx) }
    var confirmingSession by remember { mutableStateOf<Long?>(null) }

    // 第二百六十四批（A-P2-2）：`remember(tick) { trashRows(dir) }` → 快照 + IO。
    // 文件列表（listFiles＋逐文件 length）与会话回收站的 SQLite 读都不再占主线程；
    // 首帧画「读取中…」，两样一起读到后整体替换（排序与空态口径没动）。
    // 重读（恢复/删除后 tick++、翻页）**保留旧内容**直到新快照到达——
    // 先清空再填就是派工单点名的「先空白再闪」，这里不做。
    var ready by remember { mutableStateOf<TrashPanelData?>(null) }
    LaunchedEffect(dir, tick, page) {
        ready = withContext(Dispatchers.IO) {
            // wantBytes = true：清空确认要「共 X MB」，而分页后 rows 只有前 N 条
            // ——只算那一页的体积就是给用户一个假数字（2,000 条约 100ms，在 IO 上）。
            val listing = trashListing(dir, page, wantBytes = true)
            val sessions = runCatching {
                db.read { d ->
                    d.rawQuery(
                        com.example.zhengdao.baopu.BaopuSql.listDeletedSessions().sql,
                        emptyArray(),
                    ).use { c ->
                        val out = ArrayList<Triple<Long, String, Int>>()
                        while (c.moveToNext()) {
                            out += Triple(c.getLong(0), c.getString(1), c.getInt(3))
                        }
                        out
                    }
                }
            }.getOrDefault(emptyList())
            TrashPanelData(listing, sessions)
        }
    }
    val loading = ready == null
    val rows = ready?.listing?.rows.orEmpty()
    val total = ready?.listing?.total ?: 0
    val more = (total - rows.size).coerceAtLeast(0)
    val deletedSessions = ready?.sessions.orEmpty()

    fun restoreSessionRow(id: Long, title: String) {
        // 第二百六十四批补齐：DB 写事务原来点一下就在主线程跑完（A-P2-2 的同族漏项）。
        if (busy) return
        busy = true
        scope.launch {
            try {
                runTrashAction(
                    label = "恢复会话",
                    block = {
                        runCatching {
                            db.write { d ->
                                val st = com.example.zhengdao.baopu.BaopuSql.restoreSession(
                                    id,
                                    System.currentTimeMillis(),
                                )
                                d.execSQL(st.sql, st.args)
                            }.get()
                        }.isSuccess
                    },
                    describe = { if (it) "成功" else "失败" },
                ) { ok ->
                    Toast.makeText(
                        ctx,
                        res.getString(
                            if (ok) R.string.trash_session_restored else R.string.trash_session_failed,
                            title,
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                    confirmingSession = null
                    tick++
                    onSessionsChanged()
                }
            } finally {
                busy = false
            }
        }
    }

    fun purgeSessionRow(id: Long, title: String) {
        // 两击判定留在主线程（纯 state 比对，不碰盘）；真删在 IO。
        if (confirmingSession != id) {
            confirmingSession = id
            return
        }
        if (busy) return
        busy = true
        scope.launch {
            try {
                // 第一百二十一批：第二击才真删，且走控制器口子（controller.purgeSession）——
                // 里面是同一个写事务跑完 deleteSessionStatements 六条（消息/产物/计划快照按外键序），
                // 失败即整体保留（单写者事务天然原子），这里只把成败念出来（不许静默）。
                runTrashAction(
                    label = "彻底删除会话",
                    block = { onPurgeSession(id) },
                    describe = { if (it) "成功" else "失败" },
                ) { ok ->
                    Toast.makeText(
                        ctx,
                        res.getString(
                            if (ok) R.string.trash_session_purged else R.string.trash_session_failed,
                            title,
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                    confirmingSession = null
                    tick++
                    onSessionsChanged() // 与恢复对称：删完也让抽屉里那份会话库重读一次
                }
            } finally {
                busy = false
            }
        }
    }

    /** 每一种下场都要**说话**（不许静默）：句子全在 strings，这里只查键。 */
    fun sayCommon(o: TrashOutcome) {
        val msg = when (o) {
            is TrashOutcome.Restored -> res.getString(R.string.trash_restored, o.target)
            is TrashOutcome.Occupied -> res.getString(R.string.trash_occupied, o.target)
            is TrashOutcome.Rejected -> res.getString(R.string.trash_rejected, o.target, o.why)
            is TrashOutcome.IoFailed -> res.getString(R.string.trash_io_failed, o.target, o.why)
            else -> null
        } ?: return
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }

    /** 单条动作（恢复／彻底删除）的下场。NeedsConfirm 与 Deleted 的措辞跟批量不是一回事。 */
    fun sayOne(o: TrashOutcome) {
        val msg = when (o) {
            is TrashOutcome.NeedsConfirm -> res.getString(R.string.trash_confirm_one, o.target, humanBytes(o.bytes))
            is TrashOutcome.Deleted -> res.getString(R.string.trash_deleted_one, o.target)
            else -> null
        }
        if (msg != null) Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() else sayCommon(o)
    }

    /** 清空整回收站的下场：文案必须带**数量与体积**（判据 T3 点名的那条）。 */
    fun sayBatch(o: TrashOutcome) {
        val msg = when (o) {
            is TrashOutcome.NeedsConfirm -> res.getQuantityString(R.plurals.trash_confirm_empty, o.count, o.count, humanBytes(o.bytes))
            is TrashOutcome.Deleted -> if (o.failed > 0) {
                res.getQuantityString(R.plurals.trash_emptied_partial, o.count, o.count, humanBytes(o.bytes), o.failed)
            } else {
                res.getQuantityString(R.plurals.trash_emptied, o.count, o.count, humanBytes(o.bytes))
            }
            else -> null
        }
        if (msg != null) Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() else sayCommon(o)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    // 读取中不报 0：0 是"读到了、确实没有"，两者不能混（派工单负例 3）
                    text = if (loading) {
                        stringResource(R.string.trash_dialog_title_loading)
                    } else {
                        stringResource(R.string.trash_dialog_title, total)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                if (!loading && total == 0) {
                    // 空表也要有一句人话（不许整页空白让用户以为坏了）
                    Text(
                        text = stringResource(R.string.trash_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                    // 第二百六十四批：这一列改 LazyColumn。旧版是 `Column(verticalScroll)`，
                    // 一页 20 条**全部**在开面板那一帧组合完（每条两行字＋两个 TextButton），
                    // 真机上那正是回收站面板唯一超过 100ms 的一帧（200ms）。
                    // 懒加载只组合看得见的几条 ⇒ 首帧开销与总条数、页大小都无关；
                    // 内容、顺序、按钮、确认红字一律没动（rows 仍是算好的那一页）。
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (loading) {
                            item {
                                // 首帧：一句"读取中"，不是空表（2,000 条时这一段原来要卡住整帧）
                                Text(
                                    text = stringResource(R.string.common_loading),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(rows) { row ->
                            val isConfirming = confirmingFile == row.file.absolutePath
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(
                                    text = row.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    // 每行两样：大小 ・ 时间（派工单 §1「文件名、大小、时间」）
                                    text = stringResource(
                                        R.string.trash_row_meta,
                                        humanBytes(row.bytes),
                                        TimeFormats.minuteStamp(row.modifiedAt),
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    // 第二百六十四批补齐：两条都搬 IO（原来点一下就在主线程
                                    // stat／rename／delete）。确认位**在主线程先算好**再进 IO——
                                    // Compose 的 state 不该在后台线程读。
                                    val confirmedDelete = confirmingFile == row.file.absolutePath
                                    TextButton(onClick = restore@{
                                        if (busy) return@restore
                                        busy = true
                                        scope.launch {
                                            try {
                                                runTrashAction(
                                                    label = "恢复文件",
                                                    block = { restoreTrash(row, workspaceRoot) },
                                                    describe = ::outcomeLine,
                                                ) { o ->
                                                    sayOne(o)
                                                    confirmingFile = null
                                                    tick++
                                                }
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    }) { Text(stringResource(R.string.trash_restore)) }
                                    TextButton(onClick = purge@{
                                        if (busy) return@purge
                                        busy = true
                                        scope.launch {
                                            try {
                                                runTrashAction(
                                                    label = "彻底删除文件",
                                                    block = {
                                                        deleteTrash(row, workspaceRoot, confirmedDelete)
                                                    },
                                                    describe = ::outcomeLine,
                                                ) { o ->
                                                    confirmingFile = if (o is TrashOutcome.NeedsConfirm) {
                                                        row.file.absolutePath
                                                    } else {
                                                        null
                                                    }
                                                    sayOne(o)
                                                    tick++
                                                }
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    }) { Text(stringResource(R.string.trash_delete)) }
                                }
                                if (isConfirming) {
                                    Text(
                                        text = stringResource(
                                            R.string.trash_confirm_one,
                                            row.name,
                                            humanBytes(row.bytes),
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = zhengdaoErrorText(),
                                    )
                                }
                            }
                        }
                        // 分页：条数多时只读前 N 条，剩下的按「还有 N 条」展开（顺序不变）
                        if (more > 0) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(R.string.trash_more_hint, more),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = { page += TRASH_PAGE }) {
                                        Text(stringResource(R.string.trash_show_more))
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                // 会话回收站：读到之前不画「0 条」——那是"读到了、确实没有"的句子
                if (!loading) {
                    Text(
                        text = stringResource(R.string.trash_sessions_title, deletedSessions.size),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (deletedSessions.isEmpty()) {
                        Text(
                            text = stringResource(R.string.trash_sessions_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                        deletedSessions.forEach { (id, title, count) ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(
                                    text = pluralStringResource(R.plurals.trash_session_row, count, title, count),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(onClick = { restoreSessionRow(id, title) }) {
                                        Text(stringResource(R.string.trash_session_restore))
                                    }
                                    TextButton(onClick = { purgeSessionRow(id, title) }) {
                                        Text(stringResource(R.string.trash_session_purge))
                                    }
                                }
                                if (confirmingSession == id) {
                                    Text(
                                        text = stringResource(R.string.trash_session_confirm, title),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = zhengdaoErrorText(),
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = empty@{
                        if (busy) return@empty
                        if (!confirmingEmpty) {
                            // 第一击：账目直接取面板快照（IO 里已经算过 total 与 totalBytes），
                            // 不再额外全量扫一遍。快照还没到就不做——确认卡上不写一个猜的数字。
                            val listing = ready?.listing ?: return@empty
                            val needs = TrashOutcome.NeedsConfirm(
                                target = dir.name,
                                count = listing.total,
                                bytes = listing.totalBytes,
                            )
                            confirmingEmpty = true
                            emptyNeeds = needs
                            sayBatch(needs)
                            return@empty
                        }
                        // 第二击：真删。**必须自己重新全量枚举**——分页快照只有前 N 条，
                        // 拿它去删就只删 80 条、还回一句「清掉 80 个」（数据事故级）。
                        busy = true
                        scope.launch {
                            try {
                                runTrashAction(
                                    label = "清空",
                                    block = { emptyTrash(dir, true) },
                                    describe = ::outcomeLine,
                                ) { o ->
                                    confirmingEmpty = o is TrashOutcome.NeedsConfirm
                                    emptyNeeds = o as? TrashOutcome.NeedsConfirm
                                    sayBatch(o)
                                    tick++
                                }
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text(stringResource(R.string.trash_empty)) }
                    if (confirmingEmpty) {
                        // 清空那一击前的红字：数量与体积都写进去（判据 T3 的文案要求）。
                        // 分页之后 rows 不是全量，这里用第一击回出来的真实读数。
                        val needs = emptyNeeds
                        if (needs != null) {
                            Text(
                                text = pluralStringResource(
                                    R.plurals.trash_confirm_empty,
                                    needs.count,
                                    needs.count,
                                    humanBytes(needs.bytes),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = zhengdaoErrorText(),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.trash_close)) }
                }
            }
        }
    }
}
