// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十批 · 斥候的**账本**（唯一落盘口）。
//
// 落点：`<工作区>/记忆/斥候账.jsonl`（镜像）＋ `filesDir/抱朴记忆/斥候/斥候账.jsonl`（权威）。
// 为什么是两本：工作区那份**重装不丢**（派工单 287 追加），App 私有那份**存储权限被关时仍写得进**
// （理由与顺序见 `LedgerFanout`，这里不重复）。
//
// 房型照 208/219/239 那几本 JSONL：一行一条、追加式、`@Synchronized`、父目录不存在就建、
// 坏行跳过并计数（**不静默**）。
//
// 一行＝**一项能力当时的完整快照**（不是增量）：
//   `{"kind":"probe","cap":"storage","state":"denied","denials":0,"at":…,"intent":"","reason":"",
//     "note":"","mentioned":false,"device":"PGT-AN10/16"}`
// 读侧按能力取 `lastAtMs` 最大的那一行＝当前态。存绝对值的好处：读侧只读尾部也不会把驳回次数读丢
// （增量式账本一旦截断就会把"第 3 次"读成"第 1 次"，那正好是"反复弹"的来源）。
//
// 另有一行 `{"kind":"meta",…}` 记设备/版本指纹（判断"App 或系统版本变了要不要重探"）。
// 这本账**只记能力状态**：没有一处读用户文件、剪贴板内容、相册或联系人——
// 源码门禁 `ScoutReadOnlyGateTest` 从文本层钉住这一条，不靠自觉。
package com.example.zhengdao.baopu.scout

import java.io.File
import org.json.JSONObject

internal class ScoutLedger(dirs: List<File>) {

    private val fanout = LedgerFanout(dirs, ScoutLimits.LEDGER_FILE)
    private val sheetFanout = LedgerFanout(dirs, ScoutLimits.SHEET_FILE)

    /** 权威那份（App 私有）。 */
    fun file(): File = fanout.primary()

    /** 所有落点（报告里要写清"落了几本、在哪"）。 */
    fun paths(): List<String> = fanout.paths()

    fun sheetFile(): File = sheetFanout.primary()

    /** 读侧结果：当前快照 ＋ 设备指纹 ＋ 坏行数（坏行要能看见，不许静默丢账）。 */
    internal data class Loaded(
        val records: Map<Capability, ScoutRecord>,
        val device: ScoutDevice?,
        val badLines: Int,
    )

    /**
     * 读账并折成当前态（每项取 `lastAtMs` 最大的那一行）。
     *
     * 两本都读（镜像在前、权威在后），冲突取更新的那行——这样"存储权限被关 ⇒ 落到私有本"
     * 与"卸载重装 ⇒ 从工作区镜像找回"两条路径都不会把次数读回 0。
     */
    fun load(): Loaded {
        val records = LinkedHashMap<Capability, ScoutRecord>()
        var device: ScoutDevice? = null
        var deviceAt = Long.MIN_VALUE
        var bad = 0
        for (line in fanout.readTail(ScoutLimits.READ_LIMIT)) {
            if (line.isBlank()) continue
            val row = runCatching { JSONObject(line) }.getOrNull()
            if (row == null) {
                bad++
                continue
            }
            when (row.optString("kind")) {
                "meta" -> {
                    val d = parseDevice(row)
                    if (d == null) {
                        bad++
                    } else if (row.optLong("at", 0L) >= deviceAt) {
                        device = d
                        deviceAt = row.optLong("at", 0L)
                    }
                }

                else -> {
                    val rec = parseRecord(row)
                    if (rec == null) {
                        bad++
                    } else {
                        val old = records[rec.capability]
                        if (old == null || rec.lastAtMs >= old.lastAtMs) records[rec.capability] = rec
                    }
                }
            }
        }
        return Loaded(records, device, bad)
    }

    /** 追加一行（完整快照）。失败只记日志、返回 false——探测不许因为写账失败把 App 拖住。 */
    @Synchronized
    fun append(record: ScoutRecord): Boolean = fanout.appendLine(
        JSONObject().apply {
            put("kind", "probe")
            put("cap", record.capability.wire)
            put("state", record.state.wire)
            put("denials", record.denials)
            put("at", record.lastAtMs)
            put("intent", record.lastIntent)
            put("reason", record.reason)
            put("note", record.note)
            put("mentioned", record.revokedMentioned)
            put("device", record.device)
        }.toString(),
    )

    /** 记设备/版本指纹（每次探测写一行 `kind=meta`，读侧取最新一行）。 */
    @Synchronized
    fun appendDevice(device: ScoutDevice, atMs: Long): Boolean = fanout.appendLine(
        JSONObject().apply {
            put("kind", "meta")
            put("model", device.model)
            put("release", device.release)
            put("sdkInt", device.sdkInt)
            put("versionName", device.versionName)
            put("versionCode", device.versionCode)
            put("at", atMs)
        }.toString(),
    )

    /** 一页能力清单落盘（**派生视图**，不追加：每次覆盖就是最新一页）。 */
    fun saveSheet(sheet: ScoutSheet.Sheet): Boolean = sheetFanout.writeAllText(sheet.render())

    /** 供首条消息回灌读一页清单（本批只落 ＋ 提供读口，接线归后一批——见报告「未接线」）。 */
    fun readSheet(): String? = sheetFanout.readFirstText()

    private fun parseRecord(row: JSONObject): ScoutRecord? {
        val cap = Capability.ofWire(row.optString("cap")) ?: return null
        val state = ScoutState.ofWire(row.optString("state")) ?: return null
        val denials = row.optInt("denials", 0).coerceAtLeast(0)
        val reason = row.optString("reason")
        if (state == ScoutState.UNAVAILABLE && reason.isBlank()) return null
        return ScoutRecord(
            capability = cap,
            state = state,
            denials = denials,
            lastAtMs = row.optLong("at", 0L),
            lastIntent = row.optString("intent"),
            reason = reason,
            note = row.optString("note"),
            revokedMentioned = row.optBoolean("mentioned", false),
            device = row.optString("device"),
        )
    }

    private fun parseDevice(row: JSONObject): ScoutDevice? {
        val model = row.optString("model")
        val release = row.optString("release")
        val sdk = row.optInt("sdkInt", -1)
        if (model.isBlank() || release.isBlank() || sdk < 0) return null
        return ScoutDevice(
            model = model,
            release = release,
            sdkInt = sdk,
            versionName = row.optString("versionName"),
            versionCode = row.optLong("versionCode", 0L),
        )
    }
}
