package com.example.zhengdao.ui.baopu

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.zhengdao.R
import com.example.zhengdao.baopu.BaopuDb
import com.example.zhengdao.baopu.BaopuGuardCore
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.BaopuSql
import com.example.zhengdao.baopu.artifactFileName
import com.example.zhengdao.keepalive.CoroutineCrashHandler
import com.example.zhengdao.rootfs.PathGuard
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import com.example.zhengdao.util.TimeFormats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第三十八批 §9（用户 2026-10-19 追加：「左侧栏的产物区还是要有的，方便用户删除、
// 回看啊什么的」；入口放在抽屉「回收站」**上面**一行）。
//
// 这一页为什么必须存在：§8 把顶栏改成产物条之后，**本轮**写的东西一眼能看见，但上一轮、昨天、
// 上周写出来的仍然只有抱朴自己知道。要「回看／删除」就得先有一本账（§9.1 的 artifact 表），
// 再有一个读这本账的入口（就是这里）。两件都不重造：
//   - 收集＝`BaopuCostAndArtifacts.kt` 的 `selectTextArtifacts`（与 §8 顶栏产物条**同一份**：
//     白名单／512 KB 上限／NUL 判定只有一处，两入口不可能各说一套）；
//   - 查看＝`BaopuArtifactViewer.kt` 那个只读查看器（点文件名直接开；§7 那三条复制纪律
//     也只在那一处，本页一个复制按钮都不放，免得出现第二种「复制全文」）。
//
// 为什么不复用工具层那个删除工具（§9.4 红线）：它的入参来自模型、确认卡来自引擎，界面走那条路
// 等于给自己开一条绕过工具层权限模型的旁路。所以这一页**只有一个动作**：把台账里的一条
// 搬进回收站（同卷 rename，落名 `<时间戳>-<原名>`，与工具层同一个形状、同一个目录名口径）。
// 没有「彻底删除」这一档——那是 36 批回收站那一页的事，本批对它**一个字都没改**。
//
// 四条硬口径（都是判据，不是风格）：
//   1. **二次确认**（C5）：`confirmed=false` 那条路一个字节都不动盘。闸做在纯函数里，
//      界面第一击只能拿到 NeedsConfirm，第二击才带 true——与 36 批同一房型（能在 JVM 里断言，
//      而不是靠按钮的选中态）。
//   2. **只在「原位 → 回收站」这一条边上动盘**：越界、已经躺在回收站里、软链、不是普通文件
//      ⇒ 一律拒并说清为什么；rename 失败 ⇒ 原件与台账行都不动。
//   3. **台账行只有两种下场才清**：真搬成了（[ArtifactMoveOutcome.Moved]）或盘上本来就没有
//      （[ArtifactMoveOutcome.LedgerOnly]，判据 C6 那半条：文件被用户在文件管理器里删了，
//      这一击就只把账清掉，不对不存在的路径做 rename）。搬不成 ⇒ 行留着，一句人话说明白，
//      不假装清干净。
//   4. **N 现读库**（C3）：抽屉那一行每次拉开都重数一遍（E-163 的教训：抽屉内容一直处于组合中，
//      只 remember 一次就永远是刚进页面那个数）。
//
// 为什么 N 数的是库而不是目录：台账是「这一阵子写出来、并且被认定为产物的东西」；
// 目录里可能躺着用户自己扔进去的文件，两者混在一页就说不清「谁记的账」了。
// 正因如此，行上那个「已不在盘上」徽标不是装饰：账与盘是两件事，
// 这一页承认它们可能不一致，而不是替用户偷偷改哪一边。

/**
 * 台账一行 ＋ 一个盘上事实。
 *
 * 只带事实不带句子（34 批 U6 那条纪律沿用）：中文怎么写由界面查 `strings.xml`，
 * 中英才对称得了。
 *
 * @param relPath 相对工作区根的路径（库里就存相对路径，**绝不存绝对路径**——§9.4）
 * @param bytes 落库时记的字节数（台账口径，不是现读；现读大小与它不一致时用户能从上一次的记录推出来）
 * @param createdAt 第一次被记进台账的时刻（毫秒）
 * @param updatedAt 最后一次被写入的时刻（毫秒，列表按它倒序）
 * @param sessionTitle 来源会话标题（LEFT JOIN 来的，会话没了 ⇒ 空串，界面这一行就不显示）
 * @param onDisk 现在还在盘上是个文件吗（一次 `isFile`；徽标与「移不进去」都靠它）
 */
internal data class ArtifactShelfRow(
    val relPath: String,
    val bytes: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val sessionTitle: String,
    val onDisk: Boolean,
)

/**
 * 「移进回收站」这一击的下场。**只带事实不带句子**，且每条都要能说出来（不许静默）。
 */
internal sealed interface ArtifactMoveOutcome {

    /**
     * 还没二次确认 ⇒ **什么都没动**（C5 的闸就在这）。
     *
     * [onDisk] 决定确认文案问的是哪件事：还在盘上 ⇒ 问「把它移进回收站」；
     * 已经不在了 ⇒ 问「把台账这一行清掉」（C6 那半条：文件被用户在文件管理器里删了，
     * 这一页不能假装还有东西可搬，也不许对着一个不存在的路径做 rename）。
     */
    data class NeedsConfirm(val relPath: String, val bytes: Long, val onDisk: Boolean) : ArtifactMoveOutcome

    /** 真搬成了：[trashRelPath] 是它现在相对工作区根的位置（`回收站/<时间戳>-<原名>`）。 */
    data class Moved(val relPath: String, val trashRelPath: String, val bytes: Long) : ArtifactMoveOutcome

    /** 台账有这一行、盘上没这个文件 ⇒ **只清台账**，一个字节都不动盘（C6）。 */
    data class LedgerOnly(val relPath: String) : ArtifactMoveOutcome

    /** 这一步不该做（越界、已经在回收站里、软链、不是普通文件、路径写法不合法）。 */
    data class Rejected(val relPath: String, val why: String) : ArtifactMoveOutcome

    /** 盘上没做成（建目录被拒／rename 失败）——原件与台账行都原样未动。 */
    data class IoFailed(val relPath: String, val why: String) : ArtifactMoveOutcome
}

/**
 * 回收站里的落名（纯函数，判据 C5 的落点形状）。
 *
 * 形状 `<时间戳>-<原名>` 与工具层同一个口径（36 批那一页恢复时也是照这串名字认「哪一次删的」）。
 * **撞名就让路**（`-2`、`-3`……）：同一毫秒里移两个同名文件不许把前一个盖掉——
 * 回收站里那份是用户唯一还能捞回来的东西，覆盖它是这一页最不能出的事故。
 */
internal fun artifactTrashTargetOf(dir: File, name: String, nowMs: Long): File {
    val first = File(dir, "$nowMs-$name")
    if (!first.exists()) return first
    var n = 2
    while (File(dir, "$nowMs-$name-$n").exists()) n++
    return File(dir, "$nowMs-$name-$n")
}

/**
 * 把台账里的一条搬进回收站（判据 C2／C5／C6 全在这一函数里，JVM 用真临时目录直测）。
 *
 * 顺序就是纪律，一步都不能换：
 * 先把路径写法验掉 ⇒ `PathGuard.isInside` 验「确实在工作区里」（按路径组件比，不是字符串前缀）⇒
 * 验它不是回收站自己里面的（对回收站再套一层回收站是原地打转）⇒ 软链拒 ⇒
 * 目录拒（本区只搬文件）⇒
 * **没确认 ⇒ NeedsConfirm，这一条以上一个字节都没动过**（C5 的闸）⇒
 * 盘上本来就没有 ⇒ LedgerOnly（C6：只清台账，不对不存在的路径做 rename）⇒
 * 建目录 ⇒ 撞名让路 ⇒ 只 `renameTo`（同卷、原子、不复制、不覆盖）⇒
 * 失败就说失败，**不退回复制＋删除**
 * （那条路会留下「两处都有」的半截现场，本区的账只认「一次动作一个结果」）。
 */
internal fun moveArtifactToTrash(
    root: File,
    relPath: String,
    nowMs: Long,
    confirmed: Boolean,
): ArtifactMoveOutcome {
    val rel = relPath.trim()
    if (rel.isBlank() || rel.contains('\\') || rel.startsWith("/")) {
        return ArtifactMoveOutcome.Rejected(rel, "bad_path")
    }
    val segments = rel.split("/")
    if (segments.any { it.isBlank() || it == "." || it == ".." }) {
        return ArtifactMoveOutcome.Rejected(rel, "bad_path")
    }
    val target = File(root, rel)
    if (!PathGuard.isInside(root, target)) return ArtifactMoveOutcome.Rejected(rel, "out_of_workspace")
    val dir = BaopuTrashBin.dirOf(root)
    if (PathGuard.isInside(dir, target)) return ArtifactMoveOutcome.Rejected(rel, "already_in_trash")
    if (BaopuGuardCore.isSymlink(target)) return ArtifactMoveOutcome.Rejected(rel, "symlink")
    if (target.isDirectory) return ArtifactMoveOutcome.Rejected(rel, "not_a_file")
    val onDisk = target.isFile
    val sizeNow = if (onDisk) runCatching { target.length() }.getOrDefault(0L) else 0L
    if (!confirmed) {
        return ArtifactMoveOutcome.NeedsConfirm(rel, sizeNow, onDisk)
    }
    // C6：文件早被用户自己删掉了 ⇒ 这一击只清台账，盘上什么都不做
    if (!onDisk) return ArtifactMoveOutcome.LedgerOnly(rel)
    if (!dir.exists() && !dir.mkdirs()) return ArtifactMoveOutcome.IoFailed(rel, "mkdirs")
    val dest = artifactTrashTargetOf(dir, target.name, nowMs)
    val moved = runCatching { target.renameTo(dest) }
    if (moved.isFailure || !moved.getOrDefault(false) || !dest.isFile || target.exists()) {
        return ArtifactMoveOutcome.IoFailed(rel, moved.exceptionOrNull()?.javaClass?.simpleName ?: "rename")
    }
    return ArtifactMoveOutcome.Moved(rel, trashRelOf(root, dest), sizeNow)
}

/**
 * 读台账（**只读**，最多 [BaopuSql.ARTIFACT_LIST_MAX] 行，按 `updated_at` 倒序）。
 *
 * 按工作区隔离：查的就是 `workspace = root.absolutePath` 那一批（§9.1——换了文件夹不该看见
 * 别的地方的产物）。`onDisk` 每行一次 `isFile`（200 次 stat 以内，且只在 IO 线程上）。
 * ⚠️ 只能在 IO 线程调（[ArtifactShelfDialog] 用 `produceState` ＋ `Dispatchers.IO`）。
 */
internal fun artifactShelfRows(db: BaopuDb, root: File): List<ArtifactShelfRow> {
    val stmt = BaopuSql.listArtifacts(root.absolutePath)
    return db.read { d ->
        d.rawQuery(stmt.sql, stmt.args).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val rel = c.getString(0) ?: continue
                    if (rel.isBlank()) continue
                    add(
                        ArtifactShelfRow(
                            relPath = rel,
                            bytes = c.getLong(1),
                            createdAt = c.getLong(2),
                            updatedAt = c.getLong(3),
                            sessionTitle = c.getString(4).orEmpty(),
                            onDisk = File(root, rel).isFile,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * 抽屉那一行的 N（现读库，一条 `COUNT(*)`）。
 *
 * 与回收站那一行不同：那里数的是目录（盘上事实），这里数的是台账（「被认定为产物」的那批）。
 * ⚠️ 抽屉那一行按 36 批同一房型在主线程 `remember` 里调一次——一条 COUNT 与一次列目录同量级，
 * 要挪去 IO 线程得整条抽屉链一起改，那是另一刀的事（本批不动 36 批那一行）。
 */
internal fun artifactShelfCount(db: BaopuDb, root: File): Int = db.read { d ->
    val stmt = BaopuSql.countArtifacts(root.absolutePath)
    d.rawQuery(stmt.sql, stmt.args).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
}

/**
 * 台账行清掉（**只在 [ArtifactMoveOutcome.Moved]／[ArtifactMoveOutcome.LedgerOnly] 之后**
 * 由界面调；判据 C4「删完这条当场消失」与 C6「只把它从表里清掉」都走这一处）。
 *
 * 写走单写线程（`BaopuDb.write`），这里等它落定再返回——界面拿这个返回值决定
 * 是「刷新列表」还是「如实说那一行没清掉」，不假装成功。调用方必须在 IO 线程上等
 * （[ArtifactShelfDialog] 里就是 `withContext(Dispatchers.IO)`）。
 * 失败一个字节都不补救：文件已经在回收站里了，账没对上由用户自己决定怎么办。
 */
internal fun deleteArtifactRow(db: BaopuDb, root: File, relPath: String): Boolean = runCatching {
    val stmt = BaopuSql.deleteArtifact(root.absolutePath, relPath)
    db.write { d -> d.execSQL(stmt.sql, stmt.args) }.get()
    true
}.getOrDefault(false)

/**
 * 产物区面板（§9.2 那一页；房型照 36 批 [TrashDialog]，但**没有**「彻底删除」与「清空」）。
 *
 * 与回收站那一页的两点形状差异，都是判据要求的：
 * - 空态有两句人话（「还没有产物」＋「让它写点东西就有了」）⇒ 抽屉那一行**永远可点**，
 *   不像回收站那样 N=0 就置灰（置灰就看不见空态了）；
 * - 每行的删除是**两击**：第一击纯函数回 [ArtifactMoveOutcome.NeedsConfirm]（盘没动），
 *   这一行下面出现那句红字，第二击才带 true——确认位靠**比对相对路径**，不是写死的 true。
 *
 * 点文件名 ⇒ 打开 38 批 B 段那个只读查看器（同一份查看器、同一份复制纪律）；
 * 每动完一次盘就调 [onChanged]（抽屉靠它把「产物 N」与「回收站 N」两笔账一起重数）。
 */
@Composable
internal fun ArtifactShelfDialog(
    workspaceRoot: File,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val db = BaopuRuntime.db(ctx)
    var tick by remember { mutableStateOf(0) }
    // 读库与读盘都 off-main（UI 线程查库是「点一下就卡住」的老坑）
    val rows by produceState(initialValue = emptyList<ArtifactShelfRow>(), key1 = workspaceRoot, key2 = tick) {
        value = withContext(Dispatchers.IO) { artifactShelfRows(db, workspaceRoot) }
    }
    var confirmingRel by remember { mutableStateOf<String?>(null) }
    var viewingRel by remember { mutableStateOf<String?>(null) }
    // P4-4 纪律：UI 作用域必须装 CoroutineCrashHandler（门禁扫「没装 handler 的裸作用域」）
    val scope = rememberCoroutineScope { CoroutineCrashHandler }

    /** 每一种下场都要**说话**（不许静默）：句子全在 strings，这里只查键。 */
    fun say(o: ArtifactMoveOutcome) {
        val msg = when (o) {
            // 确认那句分两型：盘上还有东西 ⇒ 问「移进回收站」；东西早没了 ⇒ 问「清掉这一行」（C6）
            is ArtifactMoveOutcome.NeedsConfirm -> if (o.onDisk) {
                ctx.getString(R.string.baopu_artifact_shelf_confirm, o.relPath)
            } else {
                ctx.getString(R.string.baopu_artifact_shelf_confirm_gone, o.relPath)
            }
            is ArtifactMoveOutcome.Moved -> ctx.getString(R.string.baopu_artifact_shelf_moved, o.relPath, o.trashRelPath)
            is ArtifactMoveOutcome.LedgerOnly -> ctx.getString(R.string.baopu_artifact_shelf_ledger_cleared, o.relPath)
            is ArtifactMoveOutcome.Rejected -> ctx.getString(R.string.baopu_artifact_shelf_rejected, o.relPath, o.why)
            is ArtifactMoveOutcome.IoFailed -> ctx.getString(R.string.baopu_artifact_shelf_io_failed, o.relPath, o.why)
        }
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }

    /**
     * 一击「移进回收站」：先问纯函数要不要确认，**只有真搬成／盘上本来就没有**才去清台账行
     * （判据 C4／C5／C6 的顺序钉在这几行，界面上没有第三条路）。
     */
    fun moveAway(row: ArtifactShelfRow) {
        val o = moveArtifactToTrash(
            root = workspaceRoot,
            relPath = row.relPath,
            nowMs = System.currentTimeMillis(),
            confirmed = confirmingRel == row.relPath,
        )
        confirmingRel = if (o is ArtifactMoveOutcome.NeedsConfirm) row.relPath else null
        say(o)
        val shouldClear = o is ArtifactMoveOutcome.Moved || o is ArtifactMoveOutcome.LedgerOnly
        if (!shouldClear) return
        scope.launch {
            val cleared = withContext(Dispatchers.IO) { deleteArtifactRow(db, workspaceRoot, row.relPath) }
            if (!cleared) {
                Toast.makeText(
                    ctx,
                    ctx.getString(R.string.baopu_artifact_shelf_ledger_failed, row.relPath),
                    Toast.LENGTH_LONG,
                ).show()
            }
            tick++ // 重新查库（C4：删完这条当场消失）
            onChanged() // 抽屉里「产物 N」与「回收站 N」两笔账一起重数
        }
    }

    val viewing = viewingRel
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    text = stringResource(R.string.baopu_artifact_shelf_title, rows.size),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                if (rows.isEmpty()) {
                    // 空态两句（§9.2 点名要的那两句）——不许整页空白让用户以为坏了
                    Text(
                        text = stringResource(R.string.baopu_artifact_shelf_none),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.baopu_artifact_shelf_none_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(modifier = Modifier.fillMaxWidth().height(340.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        rows.forEach { row ->
                            val isConfirming = confirmingRel == row.relPath
                            val name = artifactFileName(row.relPath)
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(
                                    // 文件名点得 ⇒ 开只读查看器（§9.2「点文件名 ⇒ 38 批 B 段查看器」）
                                    text = name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.clickable { viewingRel = row.relPath },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.baopu_artifact_shelf_row_meta,
                                        humanBytes(row.bytes),
                                        TimeFormats.minuteStamp(row.updatedAt),
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
                                )
                                if (row.sessionTitle.isNotBlank()) {
                                    Text(
                                        text = stringResource(R.string.baopu_artifact_shelf_row_session, row.sessionTitle),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (!row.onDisk) {
                                    // 账还在、东西不在了 ⇒ 明说（P2-11：错误色走 zhengdaoErrorText）
                                    Text(
                                        text = stringResource(R.string.baopu_artifact_shelf_offdisk),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = zhengdaoErrorText(),
                                    )
                                }
                                Row {
                                    TextButton(onClick = { viewingRel = row.relPath }) {
                                        Text(stringResource(R.string.baopu_artifact_shelf_open))
                                    }
                                    TextButton(onClick = { moveAway(row) }) {
                                        Text(stringResource(R.string.baopu_artifact_shelf_delete))
                                    }
                                }
                                if (isConfirming) {
                                    // 第二击前的红字（C5 的可见形状；C6 那一型问的是「清掉这一行」）
                                    Text(
                                        text = stringResource(
                                            if (row.onDisk) R.string.baopu_artifact_shelf_confirm
                                            else R.string.baopu_artifact_shelf_confirm_gone,
                                            row.relPath,
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = zhengdaoErrorText(),
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.baopu_artifact_shelf_close)) }
                }
            }
        }
    }
    // 查看器是独立一层 Dialog（同一个查看器，本页不复制它的一份实现）
    if (viewing != null) {
        BaopuArtifactViewer(
            root = workspaceRoot,
            relPath = viewing,
            onDismiss = { viewingRel = null },
        )
    }
}
