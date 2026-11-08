// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十五批 · 使用事件账（`许愿/_使用.jsonl`）：**每次受理一行**，去重靠读回它。
//
// 为什么单独一本（不写进 `_流水线.jsonl`）：两本账问的不是一件事——
//   · 流水线账＝「这条许愿走到哪一步了」（阶段推进，一行一阶段）；
//   · 使用账＝「灰度期被真用了多少次、哪几次没算、为什么」（计量，一行一事件）。
// 混在一本里，转正读数就得从两种语义的行里挑，账越久越算不清；分开两本各自 append-only。
//
// 为什么去重靠盘上这本而不是进程内 Set：灰度跨重启（一次灰度可能跨好几天），
// 内存 Set 重启即空 ⇒ 同一个工具命中在重启后又被算一次 ⇒ 转正提前。盘上那行就是凭据。
package com.example.zhengdao.baopu.wish

import com.example.zhengdao.core.RunLog
import org.json.JSONObject
import java.io.File

/**
 * 使用账的一行（键名固定，加字段不算改形状）。
 *
 * @param key 幂等键（**落盘的凭据**）：默认 `来源|归属键`，内容进台账那条腿是 `来源|content:<摘要>`
 *   ——去重必须按它，不能按 [target]（同一篇稿两次记台账的文件名不同）。
 */
data class WishUsageEntry(
    val atMs: Long,
    val source: WishUseSource,
    val target: String,
    val wishId: String?,
    val manual: Boolean,
    val verdict: WishUseVerdict,
    val uses: Int,
    val note: String,
    val key: String = WishUsageRules.keyOf(source, target),
)

/** 使用账落盘口（append-only；坏行读时跳过——读历史不许被一行脏数据整本堵死）。 */
internal object WishUsageLedger {

    /** 与流水线账同一个目录（`<工作区>/许愿/`），不同文件。 */
    const val FILE_NAME = "_使用.jsonl"

    fun fileIn(workspaceRoot: File): File = File(File(workspaceRoot, WishLedger.DIR_NAME), FILE_NAME)

    /** 落一行（写失败只出声，绝不把调用方带死——账是留痕不是闸门）。 */
    fun append(workspaceRoot: File, entry: WishUsageEntry): Boolean = runCatching {
        val file = fileIn(workspaceRoot)
        file.parentFile?.mkdirs()
        val obj = JSONObject()
            .put("at", entry.atMs)
            .put("src", entry.source.name)
            .put("target", entry.target)
            .put("key", entry.key)
            .put("wish", entry.wishId ?: "")
            .put("manual", entry.manual)
            .put("verdict", entry.verdict.name)
            .put("uses", entry.uses)
            .put("note", entry.note)
        file.appendText(obj.toString() + "\n", Charsets.UTF_8)
        true
    }.getOrElse {
        RunLog.log("许愿使用账：没落上（${it.javaClass.simpleName}: ${it.message}）——本次计数照旧生效")
        false
    }

    /** 全本读（测试与审计用）。 */
    fun readAll(workspaceRoot: File): List<WishUsageEntry> {
        val file = fileIn(workspaceRoot)
        if (!file.isFile) return emptyList()
        return runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList()).mapNotNull(::parse)
    }

    /**
     * 已经**算过**的幂等键（只看 `COUNTED` 的行：被挡的那些不是凭据）。
     * 这是跨重启的去重凭据来源。
     */
    fun countedKeys(workspaceRoot: File): Set<String> =
        readAll(workspaceRoot)
            .filter { it.verdict == WishUseVerdict.COUNTED }
            .map { it.key }
            .toSet()

    private fun parse(raw: String): WishUsageEntry? {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val source = WishUseSource.entries.firstOrNull { it.name == o.optString("src") } ?: return null
        val verdict = WishUseVerdict.entries.firstOrNull { it.name == o.optString("verdict") } ?: return null
        val target = o.optString("target")
        if (target.isBlank()) return null
        return WishUsageEntry(
            atMs = o.optLong("at", 0L),
            source = source,
            target = target,
            wishId = o.optString("wish").ifBlank { null },
            manual = o.optBoolean("manual", false),
            verdict = verdict,
            uses = o.optInt("uses", 0),
            note = o.optString("note"),
            // 老行没有 key 字段 ⇒ 退回「来源|归属键」（与当时的拼法一致，不会串）
            key = o.optString("key").ifBlank { WishUsageRules.keyOf(source, target) },
        )
    }
}
