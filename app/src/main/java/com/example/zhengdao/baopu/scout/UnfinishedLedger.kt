// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十批 · 起居注「未办成单」的落盘壳（数据层，不画界面）。
//
// 落点与 [ScoutLedger] 同一套：`<工作区>/记忆/未办成单.jsonl`（镜像）＋ App 私有（权威）。
// 一行＝一条完整快照（读侧取最后一行，截断只丢历史不丢当前值）。
//
// **只增不改**：改判（原来"被驳回"→后来"允许了"）不删旧行，只追加一条新快照，
// 旧行留在账上——派工单「改判不是撤销历史」。
package com.example.zhengdao.baopu.scout

import java.io.File
import org.json.JSONObject

internal class UnfinishedLedger(dirs: List<File>) {

    private val fanout = LedgerFanout(dirs, ScoutLimits.UNFINISHED_FILE)

    /** 权威那份（App 私有）。 */
    fun file(): File = fanout.primary()

    /** 读当前单（每件事一行，取最后一行快照）。 */
    fun load(): List<UnfinishedItem> {
        val byKey = LinkedHashMap<String, UnfinishedItem>()
        for (line in fanout.readTail(ScoutLimits.READ_LIMIT)) {
            if (line.isBlank()) continue
            val row = runCatching { JSONObject(line) }.getOrNull() ?: continue
            val item = parse(row) ?: continue
            byKey[UnfinishedRules.keyOf(item)] = item
        }
        return byKey.values.toList()
    }

    /**
     * 记一件事：同 key（同一件事）只更新那一行的事实并累计次数（[UnfinishedRules.merge]），
     * 然后**追加**一条新快照（不重写文件、不删旧行）。
     *
     * @return 追加成功（false 时调用方照常继续——记账失败不许挡住用户的事）
     */
    @Synchronized
    fun record(item: UnfinishedItem): Boolean {
        val merged = UnfinishedRules.merge(load(), item).firstOrNull {
            UnfinishedRules.keyOf(it) == UnfinishedRules.keyOf(item)
        } ?: item
        return fanout.appendLine(encode(merged).toString())
    }

    private fun encode(item: UnfinishedItem): JSONObject = JSONObject().apply {
        put("intent", item.intent)
        put("outcome", item.outcome.wire)
        put("reason", item.reason.wire)
        put("reasonDetail", item.reasonDetail)
        put("reasonRef", item.reasonRef)
        put("fixable", item.fixable)
        put("fixHint", item.fixHint)
        put("at", item.atMs)
        put("count", item.count)
    }

    private fun parse(row: JSONObject): UnfinishedItem? {
        val intent = row.optString("intent")
        if (intent.isBlank()) return null
        val outcome = UnfinishedOutcome.ofWire(row.optString("outcome")) ?: return null
        val reason = UnfinishedReason.ofWire(row.optString("reason")) ?: return null
        return runCatching {
            UnfinishedItem(
                intent = intent,
                outcome = outcome,
                reason = reason,
                reasonDetail = row.optString("reasonDetail"),
                reasonRef = row.optString("reasonRef"),
                fixable = row.optBoolean("fixable", false),
                fixHint = row.optString("fixHint"),
                atMs = row.optLong("at", 0L),
                count = row.optInt("count", 1).coerceAtLeast(1),
            )
        }.getOrNull()
    }
}
