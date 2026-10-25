// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十八批（派工单 §1①③④⑤ 的界面）：**全手机搜索那张卡**——挂在设置页
// S2「搜索范围（只读）」那张卡后面（S2 的开关决定能搜哪儿，这张卡决定搜不搜、搜什么）。
//
// ## 四件界面事，逐条对着派工单
// 1. **分片扫描可见**（§1①③）：「已扫 N／约 M」那句里的 M 只在真扫全过一次之后才编，
//    没扫全过时只显示 N——拿本片数量冒充总数就是个假数（理由见 `PhoneScanRules.approxTotal`）。
// 2. **截断与不认领必须可见**（§1②③＋§2「静默截断」负例）：「不认领 N 个」「长文截断 N 篇」
//    「%d 片入库失败」三行都是计数，一行都不许悄悄省掉。
// 3. **片间可取消**（§1①）：「停止」按下置一个 `AtomicBoolean`，扫描器一片一查
//    （`PhoneSearch.scan` 的 `cancelled`）——不杀线程、也不「点了没反应」。
// 4. **回跳只给三个动作**（§1⑤）：动表由 `PhoneJump.plan` 定，界面按表渲染，**不在这里自己判优先级**；
//    「用别的 App 打开」实测做不了（唯一的 FileProvider 只放相机缓存目录，加宽它属于安全面改动），
//    理由写在 PhoneJump 文件头，界面上不留半个假按钮。
//
// ## 三条纪律（本文件的红线）
// - **所有盘 IO 与库 IO 都在 `Dispatchers.IO`**（`produceState` ＋ `withContext`，
//   房型照 196 批产物抽屉与 34 批技能详情）：UI 线程扫全盘＝一点就卡死。
//   回跳那条 `artifact` 台账查询也在同一块 IO 里一次查完（[searchOutcomeOf]），
//   **不在 `remember` 里读库**——一屏 40 条 = 主线程 40 次 rawQuery，那是新的坑不是房型。
// - **协程作用域必须装 `CoroutineCrashHandler`**（P4-4，`CoroutineCrashHandlerTest` 扫裸作用域）。
// - **文案全在 strings.xml**（中英两份），本文件一个中文字面量都不写进 `Text()`。
//
// ## 「复制进证道」为什么落在 `投递/`
// 那是仓库里**已有**的「用户把原件放进来」目录（`LibraryPaths.deliverDir`，157 起在工作区根），
// 现有导入流水线就读它；另起一个新目录＝动目录口径（真机根目录那几个数是验收口径的一部分）。
// 三条谨慎：同名**不覆盖**（覆盖了就是毁掉用户已有的文件）、目标必须过
// `LibraryPaths.destinationUnder`（`../`／绝对路径／盘符一律拒 ＋ `PathGuard.isInside` 双保险）、
// 先写 `.tmp` 再 rename（不产生半个文件）。
package com.example.zhengdao.ui.baopu

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.BaopuDb
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.BaopuWorkspace
import com.example.zhengdao.baopu.library.LibraryPaths
import com.example.zhengdao.baopu.search.HitRow
import com.example.zhengdao.baopu.search.IndexSnapshot
import com.example.zhengdao.baopu.search.JumpAction
import com.example.zhengdao.baopu.search.JumpTarget
import com.example.zhengdao.baopu.search.PhoneJump
import com.example.zhengdao.baopu.search.PhoneRank
import com.example.zhengdao.baopu.search.PhoneSearch
import com.example.zhengdao.baopu.search.QueryResult
import com.example.zhengdao.baopu.search.ScanReport
import com.example.zhengdao.baopu.search.SearchScope
import com.example.zhengdao.baopu.search.SearchScopeRules
import com.example.zhengdao.keepalive.CoroutineCrashHandler
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.ui.SectionCard
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一条命中连同它的动表（§1⑤：跳会话／预览／复制进证道）。 */
private data class SearchRow(val hit: HitRow, val actions: List<JumpAction>, val sessionId: Long)

/** 一次检索的界面读数（行 + 「只按前 N 个词查的」那句）。 */
private data class SearchOutcome(val rows: List<SearchRow>, val termCount: Int, val termsTruncated: Boolean)

/**
 * 全手机搜索卡（派工单 §1 六条的界面落点）。
 *
 * @param onOpenSession 回跳路径①：跳到产生这个文件的那个会话。由
 *        [BaopuScreen] 传 `controller.requestOpenSession`——它自己管「别的会话正在跑 ⇒ 只出确认卡」
 *        那扇门，本卡不另造停止逻辑（两处各造一套停法就是将来吵的来源）。
 */
@Composable
internal fun BaopuPhoneSearchCard(onOpenSession: (Long) -> Unit) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    // P4-4 纪律：UI 作用域必须装 CoroutineCrashHandler（同 BaopuArtifactViewer:142）。
    val scope = rememberCoroutineScope { CoroutineCrashHandler }
    var tick by remember { mutableIntStateOf(0) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<ScanReport?>(null) }
    var outcome by remember { mutableStateOf<SearchOutcome?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf<HitRow?>(null) }
    // 取消闸门用 AtomicBoolean 而不是 Compose State：扫描器在 IO 线程上读它，
    // 跨线程读 State 不是快照安全的（「点了停止没反应」就是这么来的）。
    val cancelFlag = remember { AtomicBoolean(false) }

    // 索引现状：开库读根清单与水位 ⇒ 必须 off-main（`PhoneSearch.snapshot` 自己绝不建库）。
    val snap by produceState<IndexSnapshot?>(initialValue = null, key1 = tick) {
        value = withContext(Dispatchers.IO) { PhoneSearch.snapshot(ctx) }
    }

    /** 查一次（读库＋查台账，全在 IO 线程上做完；[q] 为 null = 只查不重扫）。 */
    fun runQuery() {
        if (input.isBlank()) return
        scope.launch {
            outcome = withContext(Dispatchers.IO) { searchOutcomeOf(ctx, input) }
        }
    }

    /** 起一次扫描（[forceFull] = 用户点「重建索引」：忽略水位，也忽略「账上 size+mtime 没变」那条快路）。 */
    fun startScan(forceFull: Boolean) {
        if (busy) return
        busy = true
        note = null
        cancelFlag.set(false)
        // 「可达即停」：带着用户等的词去扫 ⇒ 够一屏就停，不必先扫完整根才能看到结果。
        // 重建索引时不带词（那一次的目标是建全量索引，不是找东西）。
        val terms = if (forceFull || input.isBlank()) emptyList() else PhoneRank.termsOf(input).tokens
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                // ⑥①：**读侧账本的第二个手动入口**（第一个是上面那个开关）。扫描前先把账本拨到与
                // 「开关＋授权」的现状一致——没有「App 启动时自动同步」，所以这一刻就是唯一能改账本的时刻。
                // 不在这里同步的话，「开关早就开着但一次没扫过」的设备会扫到 0 个根（少搜，不是多读）。
                val ledgerLine = syncLedgerLine(ctx)
                val rep = PhoneSearch.scan(
                    ctx,
                    terms = terms,
                    cancelled = { cancelFlag.get() },
                    forceFull = forceFull,
                )
                rep to ledgerLine
            }
            report = out.first
            // 账本这一趟真被拨动过才占这一行（没动就没必要每次扫描都刷一句）
            out.second?.let { note = it }
            if (!forceFull && input.isNotBlank()) {
                outcome = withContext(Dispatchers.IO) { searchOutcomeOf(ctx, input) }
            }
            busy = false
            tick++
        }
    }

    SectionCard(stringResource(R.string.baopu_phone_search_card)) {
        Text(
            text = stringResource(R.string.baopu_phone_search_desc),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        snap?.let { s -> RootsLine(s) }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text(stringResource(R.string.baopu_phone_search_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                // 没词就不查：空查询只会换来「没有结果」那句，白闪一下
                enabled = !busy && input.isNotBlank(),
                onClick = { runQuery() },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.baopu_phone_search_action_search)) }
            TextButton(
                enabled = !busy,
                onClick = { startScan(forceFull = false) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.baopu_phone_search_action_scan)) }
            TextButton(
                // 「停止」只在真在扫时可点
                enabled = busy,
                onClick = {
                    cancelFlag.set(true)
                    note = res.getString(R.string.baopu_phone_search_stop_tapped)
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.baopu_phone_search_action_stop)) }
            TextButton(
                enabled = !busy,
                onClick = { startScan(forceFull = true) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.baopu_phone_search_action_rebuild)) }
        }
        if (busy) {
            Text(
                text = stringResource(R.string.baopu_phone_search_scanning),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        report?.let { ScanLedger(it) }
        outcome?.let { o ->
            if (o.termsTruncated) {
                Text(
                    text = stringResource(R.string.baopu_phone_search_terms_cut, PhoneRank.MAX_TERMS),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (o.rows.isEmpty()) {
                Text(
                    text = stringResource(R.string.baopu_phone_search_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            o.rows.forEach { row ->
                HitRowLine(
                    row = row,
                    enabled = !busy,
                    onOpenSession = onOpenSession,
                    onPreview = { viewing = row.hit },
                    onCopyIntoVault = {
                        scope.launch {
                            busy = true
                            note = withContext(Dispatchers.IO) { copyIntoDeliver(ctx, row.hit) }
                            busy = false
                        }
                    },
                )
            }
        }
        note?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // 只读预览：复用 196 批那套通用查看器（对 root 没有要求，外部授予根一样能读）。
    viewing?.let { hit ->
        BaopuArtifactViewer(root = File(hit.absRoot), relPath = hit.relPath, onDismiss = { viewing = null })
    }
}

/** 索引现状两行：几个根在账 ＋ 每个根的水位（④「重启按 watermark 补扫」的那份账）。 */
@Composable
private fun RootsLine(s: IndexSnapshot) {
    Spacer(Modifier.height(6.dp))
    Text(
        text = if (s.dbExists) {
            LocalResources.current.getQuantityString(R.plurals.baopu_phone_search_roots, s.roots.size, s.roots.size)
        } else {
            stringResource(R.string.baopu_phone_search_no_db)
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    s.roots.forEach { root ->
        val mark = s.marks[root.rootId]
        // 「从没扫过」与「扫过但见过 0 个」是两句话（前者一个文件都还没读，后者读了目录发现是空的）
        // ⇒ 分开两条文案，各自只带自己要用的参数。
        val text = if (mark == null || mark.lastFullAt <= 0L) {
            stringResource(R.string.baopu_phone_search_root_never, root.kind.tag)
        } else {
            stringResource(R.string.baopu_phone_search_root_scanned, root.kind.tag, mark.filesSeen)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 扫描对账（派工单 §2：截断、跳过、失败都要有数，不许静默）。 */
@Composable
private fun ScanLedger(rep: ScanReport) {
    val res = LocalResources.current
    Spacer(Modifier.height(6.dp))
    val total = rep.approxTotal
    Text(
        text = if (total != null) {
            stringResource(R.string.baopu_phone_search_progress, rep.scanned, total)
        } else {
            stringResource(R.string.baopu_phone_search_progress_unknown, rep.scanned)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = res.getQuantityString(R.plurals.baopu_phone_search_unclaimed, rep.unclaimed, rep.unclaimed),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (rep.cappedDocs > 0) {
        Text(
            text = res.getQuantityString(R.plurals.baopu_phone_search_capped, rep.cappedDocs, rep.cappedDocs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (rep.failedShards > 0) {
        Text(
            text = res.getQuantityString(R.plurals.baopu_phone_search_failed, rep.failedShards, rep.failedShards),
            style = MaterialTheme.typography.labelSmall,
            color = zhengdaoErrorText(),
        )
    }
    if (rep.isPartial) {
        // 停因说人话（`DONE` 走不到这一行）：用户要知道「为什么只扫了一半」以及「下半次还在不在」
        Text(
            text = stringResource(
                when (rep.stopped) {
                    com.example.zhengdao.baopu.search.StopReason.CANCELLED -> R.string.baopu_phone_search_stop_cancelled
                    com.example.zhengdao.baopu.search.StopReason.HIT_BUDGET -> R.string.baopu_phone_search_stop_hits
                    com.example.zhengdao.baopu.search.StopReason.TIMEOUT -> R.string.baopu_phone_search_stop_timeout
                    com.example.zhengdao.baopu.search.StopReason.DONE -> R.string.baopu_phone_search_stop_done
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 一条命中：路径／分数 ＋ 按 [PhoneJump] 动表渲染的三个动作（顺序＝动表顺序）。 */
@Composable
private fun HitRowLine(
    row: SearchRow,
    enabled: Boolean,
    onOpenSession: (Long) -> Unit,
    onPreview: () -> Unit,
    onCopyIntoVault: () -> Unit,
) {
    Spacer(Modifier.height(6.dp))
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = row.hit.relPath,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.baopu_phone_search_score, row.hit.score, row.hit.absRoot),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            row.actions.forEach { a ->
                when (a) {
                    JumpAction.SESSION -> TextButton(
                        onClick = { onOpenSession(row.sessionId) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.baopu_phone_search_action_session)) }

                    // 路径②（`.归属.jsonl`）属于第一百九十九批：本批恒不产出这个动作，
                    // 真产出了也先只给一行说明，不做假按钮。
                    JumpAction.OWNERSHIP -> Text(
                        text = stringResource(R.string.baopu_phone_search_ownership_todo),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    JumpAction.PREVIEW -> TextButton(
                        onClick = onPreview,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.baopu_phone_search_action_preview)) }

                    JumpAction.COPY_INTO_VAULT -> TextButton(
                        enabled = enabled,
                        onClick = onCopyIntoVault,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.baopu_phone_search_action_copy)) }
                }
            }
        }
    }
}

/**
 * 扫描前把读侧账本拨到现状，并回「这一趟账本动了没有」那一句（动了才显示，没动不占屏）。
 *
 * ⑥① 的裁定写在这：**账本只有两个手动入口**——设置页那个开关（用户改意愿的时刻）和这里
 * （用户主动要结果的时刻）。两处都汇到同一个 [SearchScope.sync]，没有第三处、也没有
 * 「App 启动时自动同步」：加自动同步＝让「谁在什么时候能改读侧账本」变成运行时的随手一刻，
 * 而这张账本是读侧唯一的授权真相。调用点集合由 `PhoneSearchEntryGateTest` 扫源码钉住。
 *
 * ⚠️ 只能在 IO 线程上调（读库＋写库）。
 */
private fun syncLedgerLine(ctx: Context): String? {
    val st = SearchScope.status(ctx)
    // 与设置页同一句话的口径：先问「库之前在不在」，`sync` 一跑就会把库建出来，之后再问永远是「在」。
    val firstBuild = !PhoneSearch.dbExists(ctx)
    val rep = PhoneSearch.syncNow(ctx)
    val line = SearchScopeRules.syncLine(
        switchOn = st.switchOn,
        access = st.access,
        recorded = rep.recorded,
        revokedRoots = rep.revoked.size,
        orphanRoots = rep.orphanRoots.size,
        dbFirstBuild = firstBuild,
    )
    RunLog.log(line)
    return if (rep.isNoop) null else line
}

/**
 * 一次检索的界面结果（**整块在 IO 线程上做完**：查 `search.db` ＋ 逐条查主库 `artifact` 台账）。
 *
 * 台账查询只对「这个根就是当前工作区根」的命中发（[PhoneJump.workspaceMatches] 再核一遍写法）：
 * `artifact` 按 workspace 隔离，外部根的文件不可能有台账行 ⇒ 查了也是白查，还会把
 * 一个不相干目录里的同名文件当成产物。查不到 ⇒ 路径③（预览／复制进证道）。
 */
private fun searchOutcomeOf(ctx: Context, text: String): SearchOutcome {
    val q: QueryResult = PhoneSearch.query(ctx, text)
    val hostDir = BaopuWorkspace.hostDir(ctx)
    val artifactWorkspace = hostDir.absolutePath
    val db = BaopuRuntime.db(ctx)
    return SearchOutcome(
        rows = q.hits.map { hit -> searchRowOf(db, hit, artifactWorkspace) },
        termCount = q.termCount,
        termsTruncated = q.termsTruncated,
    )
}

/** 一条命中的动作表（判据全在 [PhoneJump.plan]，这里只把 `artifact.session_id` 那个数取回来）。 */
private fun searchRowOf(db: BaopuDb, hit: HitRow, artifactWorkspace: String): SearchRow {
    val ledgerHit = hit.rootIsWorkspace && PhoneJump.workspaceMatches(hit.absRoot, artifactWorkspace)
    val sid = if (ledgerHit) {
        runCatching {
            db.read { d ->
                d.rawQuery(PhoneJump.SELECT_ARTIFACT_SESSION, arrayOf(artifactWorkspace, hit.relPath))
                    .use { c -> if (c.moveToNext()) c.getLong(0) else 0L }
            }
        }.getOrDefault(0L)
    } else {
        0L
    }
    val plan = PhoneJump.plan(
        JumpTarget(
            rootId = hit.rootId,
            absRoot = hit.absRoot,
            relPath = hit.relPath,
            rootIsWorkspace = hit.rootIsWorkspace,
        ),
        PhoneJump.artifactSessionIdOf(sid),
    )
    return SearchRow(hit, plan.actions, plan.sessionId)
}

/**
 * 复制进证道（§1⑤ 那句「复制进证道」）：字节逐字拷进 `投递/`，**同名不覆盖**。
 *
 * ⚠️ 只能在 IO 线程上调。返回给用户看的那一句由 [Context.getResources] 现取
 * （协程里不能调 `stringResource`，38 批查看器同款坑）。
 */
private fun copyIntoDeliver(ctx: Context, hit: HitRow): String {
    val res = ctx.resources
    val src = File(hit.absRoot, hit.relPath)
    val name = src.name
    if (name.isBlank()) return res.getString(R.string.baopu_phone_search_copy_bad_name)
    val deliver = LibraryPaths.deliverDir(ctx)
    if (!deliver.isDirectory && !deliver.mkdirs()) return res.getString(R.string.baopu_phone_search_copy_failed)
    // 目标名过一道越界判据（`../`／绝对路径／盘符一律拒；判据与资料库导入同一把）
    val target = LibraryPaths.destinationUnder(deliver, name)
        ?: return res.getString(R.string.baopu_phone_search_copy_bad_name)
    if (target.exists()) return res.getString(R.string.baopu_phone_search_copy_exists, target.name)
    val tmp = File(target.parentFile, "$name.tmp")
    val ok = runCatching {
        tmp.outputStream().use { out -> src.inputStream().use { it.copyTo(out) } }
        tmp.length() == src.length() && tmp.renameTo(target)
    }.getOrDefault(false)
    if (!ok) runCatching { tmp.delete() }
    return if (ok) {
        res.getString(R.string.baopu_phone_search_copied, target.name)
    } else {
        res.getString(R.string.baopu_phone_search_copy_failed)
    }
}
