// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · 查询面板（派工单 §3 的 C 档；2026-10-25 裁定：落在 `ui/baopu/LogisticsSheet.kt`，
// **本地查询、零网络、零模型**）。
//
// ## 为什么面板不借模型的口
// 她问的是「北京装没装」，要的是一次库读＋一次内存聚合：毫秒级、离线、每次同一个数。
// 走模型要多花一次额度、多等几秒，还可能把「表里没有」改写成「可能还没装」——
// 那句正是 §3.5 禁的推断词。所以这一页自己把 [LogisticsAsk] 调到底；模型只在**她主动聊天**时
// 通过 `logistics_query` 看到同一份数（两处同一判据，不存在两套答案）。
//
// ## 毫秒数为什么摆在脸上
// 「p95 < 200ms」是判据不是愿望。把这一次实际花掉的毫秒数写在答案下面，慢了她当场就能看见，
// 而不是等验收时靠手感。
//
// ## 查询词的去向（派工单 §7）
// 输入框里那句只活在这一轮组合里：不落库、不写日志、不进任何文件。
// 唯一被记住的是她勾过的状态（`status_user`）——那是她亲手改的数据，不是她问的话。
//
// ## 文案为什么有一半不在 strings 里
// 按钮、标题、字段名走 `strings_baopu_logistics.xml`（双语）；而**导入结果与三行答案**是
// 判据层生成的句子（缺哪列、几个数、几点导的），派工单 §3.5 逐字定了形状。
// 把那些句子搬进 strings 会变成「资源里一套措辞、代码里一套判据」，两处迟早漂移。
package com.example.zhengdao.ui.baopu

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.zhengdao.R
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.logistics.DialNumber
import com.example.zhengdao.baopu.logistics.LogisticsAsk
import com.example.zhengdao.baopu.logistics.LogisticsBasis
import com.example.zhengdao.baopu.logistics.LogisticsImport
import com.example.zhengdao.baopu.logistics.LogisticsLedger
import com.example.zhengdao.baopu.logistics.LogisticsPhone
import com.example.zhengdao.baopu.logistics.LogisticsQuery
import com.example.zhengdao.baopu.logistics.QueryOutcome
import com.example.zhengdao.baopu.logistics.LogisticsStatus
import com.example.zhengdao.baopu.logistics.StoredRow
import com.example.zhengdao.keepalive.CoroutineCrashHandler
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 页面上最多列这么多单（超过就用上面那句问——一屏摆 500 行勾选框不是「查询」，是把表摊在她脸上）。 */
private const val MAX_ROWS_SHOWN = 30

@Composable
internal fun LogisticsSheet(
    onDismiss: () -> Unit,
    /**
     * 第二百一十二批：一次查询的**结果条件**（判据仍是 195 的 `LogisticsQuery`，本面板不新增判断）。
     *
     * 返回 true ＝ 上游已经把这次结果铺成卡并浮起来了 ⇒ 面板自己收起（Dialog 罩着整屏，
     * 不关掉就看不到卡，更点不到输入框）。默认 `{ false }`：没有人接这口时面板照旧只显示三行答案。
     */
    onQueryOutcome: (QueryOutcome) -> Boolean = { false },
) {
    val ctx = LocalContext.current
    val store = remember { BaopuRuntime.logisticsStore(ctx) }
    val zone = remember { TimeZone.getDefault() }
    // 面板上的「重读/重记台账」是 scope.launch + Dispatchers.IO：作用域必须挂崩溃处理器（P4-4，
    // 与别的 UI 作用域同一行写法）——IO 上抛出来的异常没人接就是静默
    val scope = rememberCoroutineScope { CoroutineCrashHandler }

    // 每次进面板重数：抽屉/面板的内容常驻组合，只 remember 一次就会停在头一个值（E-163 那条教训）
    var tick by remember { mutableIntStateOf(0) }
    val days = remember(tick) { store.daySummaries().map { it.day } }
    var day by remember { mutableStateOf(LogisticsAsk.dayKeyOf(System.currentTimeMillis(), zone)) }
    var basis by remember { mutableStateOf(LogisticsBasis.ORDER) }
    var text by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf<LogisticsAsk.Reply?>(null) }
    var costMs by remember { mutableLongStateOf(-1L) }
    var note by remember { mutableStateOf<String?>(null) }
    var copiedShown by remember { mutableStateOf<String?>(null) }

    // 点号码＝打开系统拨号盘并把号码填好（ACTION_DIAL）。**不外呼**：直接外呼的那个 action
    // 与它的拨号权限是门禁禁词（整条链连注释都不出现，所以这里不点名）——误点一下就是真拨出去一通电话。
    // 复制走剪贴板；两个动作都只在这一格判过形的号码上接线（LogisticsPhone.forRow）。
    fun openDialer(n: DialNumber) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", n.dial, null))
        if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    fun copyNumber(n: DialNumber) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("phone", n.dial))
        copiedShown = n.shown
    }

    val rows = remember(tick, day) { store.rowsFor(day) }
    val at = remember(rows) { LogisticsAsk.exportedAtOf(rows) }
    val absent = remember(rows) { LogisticsAsk.absentOf(rows) }
    val sources = remember(rows) { LogisticsAsk.sourcesOf(rows) }
    val shown = rows.filter { it.effectiveStatus() == LogisticsStatus.PENDING || it.effectiveStatus() == LogisticsStatus.LOADED }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.baopu_logistics_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.baopu_logistics_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )

                // ── 数据时间 / 来源 / 缺列（§1：界面必须明示数据时间）───────────────────
                Text(
                    text = LogisticsAsk.dataTimeNote(rows, zone),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (sources.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.baopu_logistics_sources, sources.joinToString("、")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (absent.isNotEmpty()) {
                    // 列名直接取契约里那一份（LogisticsField.label）：再造一个英文名就变成两套名词
                    Text(
                        text = stringResource(R.string.baopu_logistics_absent, absent.joinToString("、") { it.label }),
                        style = MaterialTheme.typography.bodySmall,
                        color = zhengdaoErrorText(), // P2-11：error 色 3.55:1 不达正文 AA 4.5:1
                    )
                }

                Spacer(Modifier.height(10.dp))

                // ── 日期（库里有哪些天就摆哪些，最新的在前）───────────────────────────
                if (days.size > 1) {
                    Text(
                        text = stringResource(R.string.baopu_logistics_days),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        days.take(5).forEach { d ->
                            OutlinedButton(onClick = { day = d; reply = null }) {
                                Text(
                                    text = d,
                                    fontWeight = if (d == day) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }

                // ── 口径三档（§1.8 冻结：默认按配货单；切换后车数与脚注同时变）──────────
                Text(
                    text = stringResource(R.string.baopu_logistics_basis),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LogisticsBasis.entries.forEach { b ->
                        OutlinedButton(onClick = { basis = b; reply = null }) {
                            Text(
                                text = stringResource(
                                    if (b == LogisticsBasis.PLATE) R.string.baopu_logistics_basis_plate else
                                        if (b == LogisticsBasis.ORDER) R.string.baopu_logistics_basis_order else
                                            R.string.baopu_logistics_basis_trip,
                                ),
                                fontWeight = if (b == basis) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ── 一句话查（本地，零网络、零模型）───────────────────────────────────
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.baopu_logistics_ask_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            val t0 = System.nanoTime()
                            val now = System.currentTimeMillis()
                            // 面板与工具走同一条出话路径（LogisticsAsk）；号码是同一次查询顺手带出来的
                            // （命中行的手机号列），不在答案文本上再扫一遍——单号长得再像号码也不是号码。
                            reply = LogisticsAsk.reply(store, text, basis, day, now, zone)
                            // 第二百一十二批：同一句话再取一次条件结果，只为把「这一票货」铺成卡。
                            // 两次读都在这一次点击里、同一个线程上（中间插不进一次导入），所以读数同源；
                            // 代价与替代方案（195 侧出一个 askAndReply）写在 212 的交付报告里。
                            val outcome = LogisticsQuery.ask(store, day, text, basis)
                            val shownAsCard = onQueryOutcome(outcome)
                            costMs = (System.nanoTime() - t0) / 1_000_000
                            // 铺成卡了才收面板；没命中时面板留着，照旧显示 195 的三行答案（不弹空卡）
                            if (shownAsCard) onDismiss()
                        },
                        enabled = rows.isNotEmpty(),
                    ) { Text(stringResource(R.string.baopu_logistics_ask)) }
                    if (costMs >= 0L) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.baopu_logistics_cost_ms, costMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                reply?.let { r ->
                    Text(
                        text = r.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    // 答案里出现过的号码 → 「拨打」链：点＝拨号盘带号，长按＝复制。
                    // 只摆这次命中行里的号（LogisticsPhone.forRows 已按拨号串去重），没有就不摆——
                    // 「未填」不点得出空号，单号/车牌长成什么样都进不了这排链接。
                    if (r.phones.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            r.phones.take(MAX_ROWS_SHOWN).forEach { n ->
                                Text(
                                    text = stringResource(R.string.baopu_logistics_dial, n.shown),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.pointerInput(n) {
                                        detectTapGestures(
                                            onTap = { openDialer(n) },
                                            onLongPress = { copyNumber(n) },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── 状态勾选（人勾的盖过表里的；只有待发/已装两档可勾）─────────────────
                if (shown.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.baopu_logistics_status_title),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    shown.take(MAX_ROWS_SHOWN).forEach { r ->
                        StatusLine(
                            row = r,
                            unfilled = stringResource(R.string.baopu_logistics_unfilled),
                            onSet = { s ->
                                store.setStatusUser(day, r.waybill, s)
                                reply = null
                                tick++
                            },
                            onDial = ::openDialer,
                            onCopy = ::copyNumber,
                        )
                    }
                    if (shown.size > MAX_ROWS_SHOWN) {
                        Text(
                            text = stringResource(R.string.baopu_logistics_folded, shown.size - MAX_ROWS_SHOWN),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── 读 inbox 与台账 ───────────────────────────────────────────────────
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            note = null
                            copiedShown = null
                            scope.launch {
                                val workspace = BaopuRuntime.workspaceRoot(ctx)
                                val msg = withContext(Dispatchers.IO) {
                                    LogisticsImport.importInbox(store, workspace, System.currentTimeMillis(), zone)
                                }
                                note = msg
                                tick++
                            }
                        },
                    ) { Text(stringResource(R.string.baopu_logistics_import)) }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val workspace = BaopuRuntime.workspaceRoot(ctx)
                                val w = withContext(Dispatchers.IO) {
                                    LogisticsLedger.writeForDay(workspace, store, day, zone)
                                }
                                note = LogisticsLedger.report(w)
                            }
                        },
                        enabled = rows.isNotEmpty(),
                    ) { Text(stringResource(R.string.baopu_logistics_ledger)) }
                }
                // 复制回执在组合期解析文案（stringResource）：ctx.getString 不随 Configuration 更新，是 lint 的 Error。
                val copyNote = copiedShown?.let { stringResource(R.string.baopu_logistics_copied, it) }
                (copyNote ?: note)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.baopu_logistics_close)) }
                }
            }
        }
    }
}

/** 一行的勾选条：勾上＝已装，取消＝退回待发（写的是 `status_user`，不动库里的原始状态）。 */
@Composable
private fun StatusLine(
    row: StoredRow,
    unfilled: String,
    onSet: (LogisticsStatus?) -> Unit,
    onDial: (DialNumber) -> Unit,
    onCopy: (DialNumber) -> Unit,
) {
    val loaded = row.effectiveStatus() == LogisticsStatus.LOADED
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = loaded,
            onCheckedChange = { onSet(if (it) LogisticsStatus.LOADED else LogisticsStatus.PENDING) },
        )
        Column(modifier = Modifier.weight(1f)) {
            // 标题行两个号都要：§1.8 冻结之后「车」是配货单号，她拿这一行去核的是配货单，
            // 只写运单号的话「3 车」她找不到那 3 张单子在哪。
            Text(
                text = if (row.orderNo.isEmpty()) {
                    row.waybill
                } else {
                    stringResource(R.string.baopu_logistics_row_title, row.waybill, row.orderNo)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                // §1.8 点名的四列（单号、车牌、司机姓名、手机号）**原样显示**，不打码：
                // 这一行是拿去核对司机的，打了码就核不了。日志与审计那边照旧一个字都不写。
                text = stringResource(
                    R.string.baopu_logistics_row_meta,
                    row.direction.ifEmpty { row.unload }.ifEmpty { unfilled },
                    row.pieces?.toString() ?: unfilled,
                    row.plates().joinToString("/").ifEmpty { unfilled },
                    row.driver.ifEmpty { unfilled },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 号码单独一行、可点：点＝拨号盘带号（ACTION_DIAL），长按＝复制。
            // 形状判据在 LogisticsPhone（单号/车牌/空格缺失都长不成链接），「未填」是纯文本、点不动。
            // 表里填了但不成号码形状的（「微信同号」之类）**原样摆出来、只是不可点**——
            // 把它写成「未填」等于替她把格子改了，§1.8 的「原样显示」就破了。
            val nums = LogisticsPhone.forRow(row)
            val rawPhone = row.phone.trim()
            when {
                nums.isNotEmpty() -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    nums.forEach { n ->
                        Text(
                            text = n.shown,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.pointerInput(n) {
                                detectTapGestures(
                                    onTap = { onDial(n) },
                                    onLongPress = { onCopy(n) },
                                )
                            },
                        )
                    }
                }
                rawPhone.isEmpty() -> Text(
                    text = unfilled,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    text = rawPhone,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
