// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-24-第一百八十二批 §0.5（刷新是「发现变化」不是全量重扫；
// 删除靠上轮快照 diff，不靠监听）；JSON 走 org.json（仓内既有，零新增依赖）。
package com.example.zhengdao.baopu.knowledge

import com.example.zhengdao.baopu.library.LibraryPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 知识库**文件快照**（第一百八十二批）——纯数据 ＋ 纯 diff，零 IO 零 Android。
 *
 * 快照只存「相对路径＋大小＋修改时间」，**不存正文**：它是变化检测的账本，不是索引。
 * 有了它，删除不靠 `FileObserver`（它漏事件/后台被回收），而是每轮与上一轮比：
 * 上一轮有、这一轮没有 ⇒ 删除；大小或 mtime 变 ⇒ 改动；这一轮新增 ⇒ 新增。
 */
internal object KnowledgeSnapshot {

    const val FORMAT = "kb-snapshot-v1"

    /** 单份快照条目上限（与索引扫描同量级；触顶明示，不静默）。 */
    const val MAX_ENTRIES = 4000

    data class Entry(val relPath: String, val size: Long, val mtime: Long)

    data class Snapshot(
        val entries: List<Entry> = emptyList(),
        val truncated: Boolean = false,
    ) {
        val byPath: Map<String, Entry> get() = entries.associateBy { it.relPath }
    }

    data class Diff(
        val added: List<String> = emptyList(),
        val modified: List<String> = emptyList(),
        val removed: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean get() = added.isEmpty() && modified.isEmpty() && removed.isEmpty()
        val changed: List<String> get() = added + modified
    }

    // ── 采集 ────────────────────────────────────────────────────────────────

    /**
     * 采一次当前快照（只读元数据，不读正文）。
     *
     * 主根 `知识库/` 里排除 `索引/`（索引文件不该把自己索引进去）；
     * 外部根（投递/私有原始/外部库）统一加前缀 `外部/<名字>/`，与索引路径一一对应。
     * 隐藏目录（`.` 开头）整段跳过——那是缓存/回收站，不属于知识库。
     */
    fun capture(workspace: File, filesDir: File): Snapshot {
        val out = ArrayList<Entry>()
        var truncated = false
        fun addRoot(root: File, prefix: String, excludeIndex: Boolean) {
            val scan = LibraryPaths.scanTree(root)
            if (scan.truncated) truncated = true
            for (f in scan.files) {
                if (out.size >= MAX_ENTRIES) {
                    truncated = true
                    return
                }
                if (hasHiddenComponent(f.relPath)) continue
                if (excludeIndex && KnowledgeLayout.excludedFromIndex(f.relPath)) continue
                out += Entry(prefix + f.relPath, f.size, f.mtime)
            }
        }
        addRoot(KnowledgeLayout.knowledgeDir(workspace), "", excludeIndex = true)
        for (root in KnowledgeLayout.externalRoots(workspace, filesDir)) {
            addRoot(root.dir, "外部/${root.name}/", excludeIndex = false)
        }
        out.sortBy { it.relPath }
        return Snapshot(out, truncated)
    }

    private fun hasHiddenComponent(relPath: String): Boolean =
        relPath.replace('\\', '/').split('/').any { it.startsWith(".") }

    // ── diff（删除靠这里，不靠监听） ─────────────────────────────────────────

    fun diff(previous: Snapshot?, current: Snapshot): Diff {
        if (previous == null) {
            return Diff(added = current.entries.map { it.relPath })
        }
        val prev = previous.byPath
        val cur = current.byPath
        val added = ArrayList<String>()
        val modified = ArrayList<String>()
        val removed = ArrayList<String>()
        for (e in current.entries) {
            val p = prev[e.relPath]
            when {
                p == null -> added += e.relPath
                p.size != e.size || p.mtime != e.mtime -> modified += e.relPath
            }
        }
        for (p in previous.entries) {
            if (p.relPath !in cur) removed += p.relPath
        }
        return Diff(added.sorted(), modified.sorted(), removed.sorted())
    }

    // ── 编解码（损坏/版本不符 ⇒ null，整份重建，不猜半份） ───────────────────

    fun encode(snapshot: Snapshot): String {
        val arr = JSONArray()
        snapshot.entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("rel", e.relPath)
                    .put("size", e.size)
                    .put("mtime", e.mtime),
            )
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("truncated", snapshot.truncated)
            .put("entries", arr)
            .toString()
    }

    fun decode(text: String): Snapshot? = runCatching {
        if (text.isBlank()) return null
        val j = JSONObject(text)
        if (j.optString("format") != FORMAT) return null
        val arr = j.optJSONArray("entries") ?: return null
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val rel = o.optString("rel")
            if (rel.isBlank()) continue
            out += Entry(rel, o.optLong("size"), o.optLong("mtime"))
        }
        out.sortBy { it.relPath }
        Snapshot(out, j.optBoolean("truncated"))
    }.getOrNull()
}
