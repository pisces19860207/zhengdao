// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十批 · 许愿流水线 §二③：全程落账。
// 许愿原文·规格·草稿版本·试跑读数·自检结论·灰度/转正/回滚，每一步一条 JSONL。
// 与 DecisionLedger 同口径（reason＋evidence 缺一不落），不另建表。
// 落点：<workspace>/许愿/_流水线.jsonl（跟工作区走，不跟 App 私有目录）。
package com.example.zhengdao.baopu.wish

import org.json.JSONObject
import java.io.File

/** 一条流水线账（JSONL 的一行；键名固定）。 */
data class WishLedgerEntry(
    val wishId: String,
    val stage: WishStage,
    val product: WishProduct,
    val detail: String,
    val evidence: String,
    val atMs: Long,
)

/** 流水线账本（IO 壳；纯判据在各模块，这里只落盘）。 */
internal object WishLedger {

    const val DIR_NAME = "许愿"
    const val FILE_NAME = "_流水线.jsonl"

    fun fileIn(workspaceRoot: File): File =
        File(File(workspaceRoot, DIR_NAME), FILE_NAME)

    /** 落一条账（reason＋evidence 缺一不落，同 DecisionLedger 口径）。 */
    fun record(workspaceRoot: File, entry: WishLedgerEntry): Boolean {
        if (entry.detail.isBlank() || entry.evidence.isBlank()) return false
        val file = fileIn(workspaceRoot)
        return runCatching {
            file.parentFile?.mkdirs()
            val obj = JSONObject()
                .put("wishId", entry.wishId)
                .put("stage", entry.stage.name)
                .put("product", entry.product.name)
                .put("detail", entry.detail)
                .put("evidence", entry.evidence)
                .put("atMs", entry.atMs)
            file.appendText(obj.toString() + "\n", Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    /** 读全部账（测试与审计用；坏行跳过不崩）。 */
    fun readAll(workspaceRoot: File): List<WishLedgerEntry> {
        val file = fileIn(workspaceRoot)
        if (!file.isFile) return emptyList()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
                runCatching {
                    WishLedgerEntry(
                        wishId = o.getString("wishId"),
                        stage = WishStage.valueOf(o.getString("stage")),
                        product = WishProduct.valueOf(o.getString("product")),
                        detail = o.getString("detail"),
                        evidence = o.getString("evidence"),
                        atMs = o.getLong("atMs"),
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    /** 按 wishId 筛账（一次许愿的全流程）。 */
    fun readByWish(workspaceRoot: File, wishId: String): List<WishLedgerEntry> =
        readAll(workspaceRoot).filter { it.wishId == wishId }
}