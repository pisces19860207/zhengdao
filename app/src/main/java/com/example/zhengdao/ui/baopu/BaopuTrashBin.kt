// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
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

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.zhengdao.R
import com.example.zhengdao.baopu.BaopuGuardCore
import com.example.zhengdao.rootfs.PathGuard
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import com.example.zhengdao.util.TimeFormats
import java.io.File

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
internal fun trashRows(dir: File): List<TrashRow> {
    if (!dir.isDirectory) return emptyList()
    val kids = runCatching { dir.listFiles() }.getOrNull() ?: return emptyList()
    return kids
        .filter { it.isFile && !BaopuGuardCore.isSymlink(it) }
        .map { f ->
            TrashRow(
                file = f,
                name = f.name,
                bytes = runCatching { f.length() }.getOrDefault(0L),
                modifiedAt = f.lastModified(),
            )
        }
        .sortedBy { it.name }
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
 */
@Composable
internal fun TrashDialog(
    workspaceRoot: File,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val dir = BaopuTrashBin.dirOf(workspaceRoot)
    var tick by remember { mutableStateOf(0) }
    val rows = remember(tick) { trashRows(dir) }
    // 「等第二击」记的是**哪一个文件**（绝对路径）；换一条就得重新确认，
    // 关掉面板也清掉（下次进来重新问一遍——判据 T3 不许记住上次的许可）。
    var confirmingFile by remember { mutableStateOf<String?>(null) }
    var confirmingEmpty by remember { mutableStateOf(false) }
    // 第四十七批：会话回收站（软删）——与文件回收站同一个面板，数字分开数。
    val res = LocalResources.current
    val db = remember { com.example.zhengdao.baopu.BaopuRuntime.db(ctx) }
    var confirmingSession by remember { mutableStateOf<Long?>(null) }
    val deletedSessions = remember(tick) {
        runCatching {
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
    }

    fun restoreSessionRow(id: Long, title: String) {
        val ok = runCatching {
            db.write { d ->
                val st = com.example.zhengdao.baopu.BaopuSql.restoreSession(id, System.currentTimeMillis())
                d.execSQL(st.sql, st.args)
            }.get()
        }.isSuccess
        Toast.makeText(
            ctx,
            res.getString(if (ok) R.string.trash_session_restored else R.string.trash_session_failed, title),
            Toast.LENGTH_LONG,
        ).show()
        confirmingSession = null
        tick++
    }

    fun purgeSessionRow(id: Long, title: String) {
        if (confirmingSession != id) {
            confirmingSession = id
            return
        }
        val ok = runCatching {
            db.write { d ->
                com.example.zhengdao.baopu.BaopuSql.deleteSessionStatements(id).forEach { st ->
                    d.execSQL(st.sql, st.args)
                }
            }.get()
        }.isSuccess
        Toast.makeText(
            ctx,
            res.getString(if (ok) R.string.trash_session_purged else R.string.trash_session_failed, title),
            Toast.LENGTH_LONG,
        ).show()
        confirmingSession = null
        tick++
    }

    /** 每一种下场都要**说话**（不许静默）：句子全在 strings，这里只查键。 */
    fun sayCommon(o: TrashOutcome) {
        val msg = when (o) {
            is TrashOutcome.Restored -> ctx.getString(R.string.trash_restored, o.target)
            is TrashOutcome.Occupied -> ctx.getString(R.string.trash_occupied, o.target)
            is TrashOutcome.Rejected -> ctx.getString(R.string.trash_rejected, o.target, o.why)
            is TrashOutcome.IoFailed -> ctx.getString(R.string.trash_io_failed, o.target, o.why)
            else -> null
        } ?: return
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }

    /** 单条动作（恢复／彻底删除）的下场。NeedsConfirm 与 Deleted 的措辞跟批量不是一回事。 */
    fun sayOne(o: TrashOutcome) {
        val msg = when (o) {
            is TrashOutcome.NeedsConfirm -> ctx.getString(R.string.trash_confirm_one, o.target, humanBytes(o.bytes))
            is TrashOutcome.Deleted -> ctx.getString(R.string.trash_deleted_one, o.target)
            else -> null
        }
        if (msg != null) Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() else sayCommon(o)
    }

    /** 清空整回收站的下场：文案必须带**数量与体积**（判据 T3 点名的那条）。 */
    fun sayBatch(o: TrashOutcome) {
        val msg = when (o) {
            is TrashOutcome.NeedsConfirm -> ctx.getString(R.string.trash_confirm_empty, o.count, humanBytes(o.bytes))
            is TrashOutcome.Deleted -> if (o.failed > 0) {
                ctx.getString(R.string.trash_emptied_partial, o.count, humanBytes(o.bytes), o.failed)
            } else {
                ctx.getString(R.string.trash_emptied, o.count, humanBytes(o.bytes))
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
                    text = stringResource(R.string.trash_dialog_title, rows.size),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                if (rows.isEmpty()) {
                    // 空表也要有一句人话（不许整页空白让用户以为坏了）
                    Text(
                        text = stringResource(R.string.trash_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        rows.forEach { row ->
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
                                    TextButton(onClick = {
                                        sayOne(restoreTrash(row, workspaceRoot))
                                        confirmingFile = null
                                        tick++
                                    }) { Text(stringResource(R.string.trash_restore)) }
                                    TextButton(onClick = {
                                        val o = deleteTrash(row, workspaceRoot, confirmingFile == row.file.absolutePath)
                                        confirmingFile = if (o is TrashOutcome.NeedsConfirm) {
                                            row.file.absolutePath
                                        } else {
                                            null
                                        }
                                        sayOne(o)
                                        tick++
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
                    }
                }
                Spacer(Modifier.height(10.dp))
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
                                text = stringResource(R.string.trash_session_row, title, count),
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
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        val o = emptyTrash(dir, confirmingEmpty)
                        confirmingEmpty = o is TrashOutcome.NeedsConfirm
                        sayBatch(o)
                        tick++
                    }) { Text(stringResource(R.string.trash_empty)) }
                    if (confirmingEmpty) {
                        // 清空那一击前的红字：数量与体积都写进去（判据 T3 的文案要求）
                        Text(
                            text = stringResource(
                                R.string.trash_confirm_empty,
                                rows.size,
                                humanBytes(rows.sumOf { it.bytes }),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = zhengdaoErrorText(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.trash_close)) }
                }
            }
        }
    }
}
