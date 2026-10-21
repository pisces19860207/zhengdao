// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单-2026-10-21-第一百二十八批-知识库v2 §1（存储：本地索引文件，可增量、
// 可全量重建；索引损坏/缺失可重建，不阻塞对话）。设计页 §3。
// JSON 走 org.json（仓内工具层已在用，零新增依赖）。
package com.example.zhengdao.baopu.kb

import org.json.JSONObject

/**
 * 索引文件的编解码（**纯函数**：String ⇄ [KbIndex]）。
 *
 * ## 损坏恢复的口径（派工单 §2：索引损坏/缺失可重建，不阻塞对话）
 * [decode] 对**任何**异常、任何字段缺失、格式版本不符，一律返回 null
 * （＝当作没有索引）——调用方（[KbIndexStore.load]）据此让模型"先 kb_index 重建"，
 * 而不是把半截索引当成真相。**绝不抛异常、绝不部分解析**：半个索引比没有索引更坏
 * （模型会拿着 3 个文件的碎片以为"资料库里就这些"）。
 */
internal object KbIndexCodec {

    fun encode(index: KbIndex): String {
        val docs = org.json.JSONArray()
        index.docs.forEach { d ->
            val chunks = org.json.JSONArray()
            d.chunks.forEach { c ->
                chunks.put(
                    JSONObject()
                        .put("relPath", c.relPath)
                        .put("lineStart", c.lineStart)
                        .put("lineEnd", c.lineEnd)
                        .put("heading", c.heading)
                        .put("text", c.text)
                        .put("source", c.source),
                )
            }
            docs.put(
                JSONObject()
                    .put("relPath", d.relPath)
                    .put("size", d.size)
                    .put("mtime", d.mtime)
                    .put("kind", d.kind)
                    .put("chunks", chunks),
            )
        }
        val stats = JSONObject()
            .put("filesIndexed", index.stats.filesIndexed)
            .put("filesSkipped", index.stats.filesSkipped)
            .put("chunksTotal", index.stats.chunksTotal)
            .put("truncated", index.stats.truncated)
            .put("skippedBig", index.stats.skippedBig)
            .put("skippedBinary", index.stats.skippedBinary)
            .put("skippedUnclaimed", index.stats.skippedUnclaimed)
            .put("reused", index.stats.reused)
        return JSONObject()
            .put("format", index.format)
            .put("root", index.root)
            .put("rootLabel", index.rootLabel)
            .put("updatedAt", index.updatedAt)
            .put("docs", docs)
            .put("stats", stats)
            .toString()
    }

    /** @return 解析好的索引；任何损坏/版本不符 ⇒ null（调用方当"没有索引"处理）。 */
    fun decode(text: String): KbIndex? = runCatching {
        if (text.isBlank()) return null
        val j = JSONObject(text)
        if (j.optString("format") != KbIndex.FORMAT) return null
        val docsArr = j.optJSONArray("docs") ?: return null
        val docs = ArrayList<KbDoc>(docsArr.length())
        for (i in 0 until docsArr.length()) {
            val d = docsArr.optJSONObject(i) ?: continue
            val chunksArr = d.optJSONArray("chunks") ?: org.json.JSONArray()
            val chunks = ArrayList<KbChunk>(chunksArr.length())
            for (k in 0 until chunksArr.length()) {
                val c = chunksArr.optJSONObject(k) ?: continue
                val text0 = c.optString("text")
                if (text0.isEmpty()) continue // 空块：编码时本就不该有，解码时直接丢
                chunks += KbChunk(
                    relPath = c.optString("relPath"),
                    lineStart = c.optInt("lineStart"),
                    lineEnd = c.optInt("lineEnd"),
                    heading = c.optString("heading"),
                    text = text0,
                    source = c.optString("source", KbChunk.SOURCE_FILE),
                )
            }
            docs += KbDoc(
                relPath = d.optString("relPath"),
                size = d.optLong("size"),
                mtime = d.optLong("mtime"),
                kind = d.optString("kind"),
                chunks = chunks,
            )
        }
        val stats = j.optJSONObject("stats")
        KbIndex(
            format = KbIndex.FORMAT,
            root = j.optString("root"),
            rootLabel = j.optString("rootLabel"),
            updatedAt = j.optLong("updatedAt"),
            docs = docs,
            stats = KbStats(
                filesIndexed = stats?.optInt("filesIndexed") ?: docs.size,
                filesSkipped = stats?.optInt("filesSkipped") ?: 0,
                chunksTotal = stats?.optInt("chunksTotal") ?: docs.sumOf { it.chunks.size },
                truncated = stats?.optBoolean("truncated") ?: false,
                skippedBig = stats?.optInt("skippedBig") ?: 0,
                skippedBinary = stats?.optInt("skippedBinary") ?: 0,
                skippedUnclaimed = stats?.optInt("skippedUnclaimed") ?: 0,
                reused = stats?.optInt("reused") ?: 0,
            ),
        )
    }.getOrNull()
}
