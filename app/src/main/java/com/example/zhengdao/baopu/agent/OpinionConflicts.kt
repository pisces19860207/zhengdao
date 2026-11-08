// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百三十七批（冲突入御案 · 落盘件）：把 [OpinionMerger.conflictsOf] 算出的分歧
// 追加到 `<工作区>/产物/.conflicts.jsonl`，并读回来给御案页签。
//
// 为什么是**点号开头**的文件：`产物/` 是她唯一的对外输出口，冲突记录是给系统自己看的旁证，
// 不是她的产物——点号开头不进产物计数（沿用 `_待删除清单.md` 同一口径的"系统件"约定）。
//
// 三条纪律（与意见判定/御批/卡处置同一房型）：
//   ① append-only，绝不改老行；
//   ② **没有冲突就不建文件**（空列表 ⇒ 一个字节都不写）；
//   ③ 写失败不抛：账是留痕不是闸门，落不上只记一行日志。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.inspect.OpinionConflict
import com.example.zhengdao.baopu.inspect.OpinionConflictSide
import com.example.zhengdao.baopu.inspect.Severity
import com.example.zhengdao.baopu.inspect.Source
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal object OpinionConflicts {

    /** 文件名（点号开头：不计产物数）。 */
    const val FILE_NAME = ".conflicts.jsonl"

    /** 行格式版本（改形状才 +1；加字段不算）。 */
    const val VERSION = 1

    fun fileIn(productsDir: File): File = File(productsDir, FILE_NAME)

    /** 一行（`a`/`b` 各三格；`ev` 是两边的证据并集）。 */
    fun lineOf(conflict: OpinionConflict): String = JSONObject()
        .put("v", VERSION)
        .put("event", conflict.eventKey)
        .put("aGist", conflict.a.gist)
        .put("aSev", conflict.a.severity.name)
        .put("aSrc", conflict.a.source.name)
        .put("bGist", conflict.b.gist)
        .put("bSev", conflict.b.severity.name)
        .put("bSrc", conflict.b.source.name)
        .put("at", conflict.at)
        .put("ev", JSONArray(conflict.evidence))
        .toString()

    /** 坏行一律跳过（读历史不许被一行脏数据整本堵死）。 */
    fun parse(raw: String): OpinionConflict? {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val event = o.optString("event")
        val a = sideOf(o, "aGist", "aSev", "aSrc") ?: return null
        val b = sideOf(o, "bGist", "bSev", "bSrc") ?: return null
        if (event.isBlank()) return null
        val ev = (o.optJSONArray("ev") ?: JSONArray()).let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).ifEmpty { null } }
        }
        return OpinionConflict(event, a, b, o.optLong("at", 0L), ev)
    }

    /** 读全本（文件不存在 ⇒ 空列表；不做时间窗过滤）。 */
    fun readAll(productsDir: File): List<OpinionConflict> {
        val f = fileIn(productsDir)
        if (!f.isFile) return emptyList()
        return runCatching {
            f.readLines(Charsets.UTF_8).mapNotNull(::parse).distinctBy { it.id }
        }.getOrDefault(emptyList())
    }

    private fun sideOf(o: JSONObject, gistKey: String, sevKey: String, srcKey: String): OpinionConflictSide? {
        val gist = o.optString(gistKey)
        val severity = Severity.entries.firstOrNull { it.name == o.optString(sevKey) } ?: return null
        val source = Source.entries.firstOrNull { it.name == o.optString(srcKey) } ?: return null
        if (gist.isBlank()) return null
        return OpinionConflictSide(gist, severity, source)
    }
}
