// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 649 批一 · 内容创作资料包（隐藏能力·按需挂载）的**判定与读取层**。
//
// 三条口径（派工单原话级）：
//   1. 设置项「内容创作资料包」**默认关**；关着 ⇒ 没有 pack_search、**零磁盘读**；
//   2. 开着**且 L1 命中** ⇒ 才把这组工具挂上去（挂载是逐轮算的，不是会话内一挂到底）；
//   3. 每次注入**记账**（谁、多少片、多少字节）。
//
// 为什么判定与读取都在这里、Context 只进 [ContentPackGate]：本包其余部分是纯 JVM
// 可测的（与 `KbIndexStore`／`TodoStore` 同房型），"关着零 IO" 这条判据才测得出
// ——测试里换一个会计数的包根，读没读一眼可见。
package com.example.zhengdao.baopu.pack

import androidx.core.content.edit
import android.content.Context
import java.io.File

/**
 * 设置项「内容创作资料包」的开关（唯一读写口）。
 *
 * 默认**关**：读失败也按关（fail closed）——这是「隐藏能力」的底线，宁可看不见，
 * 不能在用户没开的时候把素材读进上下文。
 */
internal object ContentPackGate {
    const val PREFS_NAME = "baopu_content_pack"
    const val KEY_ON = "content_pack_on"

    /** 默认值。**不许改成 true**：派工单「设置项默认关」。 */
    const val DEFAULT_ON = false

    fun on(ctx: Context): Boolean = runCatching {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ON, DEFAULT_ON)
    }.getOrDefault(DEFAULT_ON)

    /** 拨动（设置页唯一写口；写失败按没写处理，下次读到的还是旧值）。 */
    fun set(ctx: Context, on: Boolean) {
        runCatching {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putBoolean(KEY_ON, on) }
        }
    }
}

/**
 * 挂载判定（纯函数，无 IO）。
 *
 * 两个条件同时成立才挂：开关开、**且**本轮输入命中 L1 主题标签。
 * 把判定抽成一行纯函数，是为了让「关着 ⇒ 不挂」与「开着但没命中 ⇒ 也不挂」
 * 两条都能被单测直接钉住，不用起引擎。
 */
internal object PackMount {
    fun shouldMount(switchOn: Boolean, l1Hit: Boolean): Boolean = switchOn && l1Hit
}

/** L1 主题标签（索引里的 `l1` 段）：命中它算「这一轮在谈这个主题」。 */
internal data class PackL1(val key: String, val aliases: List<String>)

/** 索引里一片的元数据（正文不在索引里——按需再读那一个文件）。 */
internal data class PackSliceMeta(
    val id: String,
    val title: String,
    val kind: String,
    val source: String,
    val coverage: String,
    val via: String,
    val from: String,
    val tags: List<String>,
    val path: String,
    val bytes: Int,
)

/** 一次检索的结果：命中片 + 实际注入的字节数（记账用）。 */
internal data class PackHits(
    val query: String,
    val slices: List<Pair<PackSliceMeta, String>>,
    val bytes: Int,
) {
    val ids: List<String> get() = slices.map { it.first.id }
}

/** 注入记账（生产实现落盘；测试给假件）。 */
internal fun interface PackLedger {
    fun record(query: String, ids: List<String>, bytes: Int)

    companion object {
        /** 无账可记（未装配时的兜底：不抛、不阻塞检索）。 */
        val NONE: PackLedger = PackLedger { _, _, _ -> }
    }
}

/**
 * 资料包读取层。
 *
 * **懒加载**：`index()` 第一次被调用才读 `index.json`；在此之前对象存在但一个字节
 * 都没读。`indexLoads` 是"到底读没读"的读数点（判据「关着零 IO」靠它）。
 *
 * @param root 包根（生产＝App 私有目录下 `packs/男性`；测试＝临时目录假件）
 */
internal class PackStore(val root: File) {

    companion object {
        /** 索引文件名。 */
        const val INDEX_NAME = "index.json"

        /** 单片数上限（派工单：≤3 片）。 */
        const val MAX_K = 3

        /** 单次注入字节上限（派工单：≤6KB）。 */
        const val MAX_BYTES = 6144

        /** 默认片数。 */
        const val DEFAULT_K = 3

        /** 剥 YAML 头（首行 `---` 到下一个 `---` 之间）。 */
        fun stripFrontMatter(text: String): String {
            if (!text.startsWith("---")) return text.trim()
            val end = text.indexOf("\n---", 3)
            if (end < 0) return text.trim()
            val after = text.indexOf('\n', end + 1)
            return if (after < 0) "" else text.substring(after + 1).trim()
        }
    }

    /** 索引真读了几次（测试用读数点；生产不看）。 */
    var indexLoads: Int = 0
        private set

    private var cached: List<PackSliceMeta>? = null
    private var cachedL1: List<PackL1>? = null
    private var broken = false

    /** 索引缺失/损坏 ⇒ null（静默：不阻塞对话，也不编造）。 */
    fun index(): List<PackSliceMeta>? {
        if (broken) return null
        cached?.let { return it }
        val f = File(root, INDEX_NAME)
        if (!f.isFile) {
            broken = true
            return null
        }
        indexLoads++
        return runCatching {
            val doc = org.json.JSONObject(f.readText(Charsets.UTF_8))
            val arr = doc.optJSONArray("slices") ?: return null
            val list = ArrayList<PackSliceMeta>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank()) continue
                list.add(
                    PackSliceMeta(
                        id = id,
                        title = o.optString("title"),
                        kind = o.optString("kind"),
                        source = o.optString("source"),
                        coverage = o.optString("coverage"),
                        via = o.optString("via"),
                        from = o.optString("from"),
                        tags = jsonList(o.optJSONArray("tags")),
                        path = o.optString("path"),
                        bytes = o.optInt("bytes"),
                    ),
                )
            }
            val l1 = ArrayList<PackL1>()
            val l1Arr = doc.optJSONArray("l1")
            if (l1Arr != null) {
                for (i in 0 until l1Arr.length()) {
                    val o = l1Arr.optJSONObject(i) ?: continue
                    l1.add(PackL1(o.optString("key"), jsonList(o.optJSONArray("aliases"))))
                }
            }
            cachedL1 = l1
            cached = list
            list
        }.getOrElse {
            broken = true
            null
        }
    }

    /** L1 主题表（索引缺失 ⇒ 空表 ⇒ 永远不命中 ⇒ 不挂载，fail closed）。 */
    fun l1Tags(): List<PackL1> {
        index()
        return cachedL1 ?: emptyList()
    }

    /** 本轮输入命中了哪些 L1 主题（空表 ＝ 没命中）。 */
    fun l1Hits(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val hit = ArrayList<String>()
        for (t in l1Tags()) {
            if (t.key in text || t.aliases.any { it.isNotBlank() && it in text }) hit.add(t.key)
        }
        return hit
    }

    /**
     * 检索：先在**元数据**（标题/标签/来源文件名）上打分，再只读命中片的正文。
     *
     * 为什么不在全量正文上打分：666 片全读＝几 MB 的磁盘 IO，跟"按需挂载"反着来。
     * 标签来自世界书的 key 表（作者手写的触发词），比正文匹配更准也更便宜。
     *
     * @param includeEdge 是否带上 `coverage=edge` 的片（默认**不带**：非代表性情形）
     */
    fun search(q: String, k: Int = DEFAULT_K, includeEdge: Boolean = false): PackHits {
        val query = q.trim()
        if (query.isEmpty()) return PackHits(query, emptyList(), 0)
        val all = index() ?: return PackHits(query, emptyList(), 0)

        val tokens = expandTokens(query)
        if (tokens.isEmpty()) return PackHits(query, emptyList(), 0)

        val scored = all.asSequence()
            .filter { includeEdge || it.coverage != "edge" }
            .map { s -> s to score(s, tokens) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<PackSliceMeta, Int>> { it.second }.thenBy { it.first.id })
            .take(k.coerceIn(1, MAX_K))
            .toList()

        val out = ArrayList<Pair<PackSliceMeta, String>>(scored.size)
        var used = 0
        for ((meta, _) in scored) {
            val body = readBody(meta.path) ?: continue
            val cost = body.toByteArray(Charsets.UTF_8).size
            if (out.isNotEmpty() && used + cost > MAX_BYTES) continue
            if (cost > MAX_BYTES) {
                // 单片就超预算：按字符截断（不静默丢，留一句"被截断"）
                val cut = body.take(MAX_BYTES / 3 - 40) + "\n…（超单次预算，已截断）"
                out.add(meta to cut)
                used += cut.toByteArray(Charsets.UTF_8).size
                continue
            }
            out.add(meta to body)
            used += cost
        }
        return PackHits(query, out, used)
    }

    /** 查询词 → 检索词表（含 L1 别名展开：谈到"勃起"也能命中"充血/硬度"那几片）。 */
    private fun expandTokens(query: String): List<String> {
        val raw = query.split(Regex("[\\s,，。、；;：:！!？?（）()\\[\\]「」\"']+"))
            .filter { it.length >= 1 }
        val set = LinkedHashSet<String>()
        set.addAll(raw)
        if (query.length in 2..12) set.add(query)
        for (t in l1Tags()) {
            val hit = t.key in query || t.aliases.any { it.isNotBlank() && it in query }
            if (hit) set.addAll(t.aliases.filter { it.isNotBlank() })
        }
        return set.filter { it.length >= 1 }.take(24)
    }

    private fun score(s: PackSliceMeta, tokens: List<String>): Int {
        var v = 0
        for (t in tokens) {
            if (s.title.contains(t)) v += 3
            if (s.tags.any { it.contains(t) }) v += 2
            if (s.from.contains(t)) v += 1
        }
        return v
    }

    /** 读正文（剥掉 YAML 头）。读不出 ⇒ null（跳过这一片，不抛）。 */
    private fun readBody(rel: String): String? = runCatching {
        val f = File(root, rel)
        if (!f.isFile) return null
        val text = f.readText(Charsets.UTF_8)
        stripFrontMatter(text)
    }.getOrNull()

    private fun jsonList(a: org.json.JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) {
            val s = a.optString(i)
            if (s.isNotBlank()) out.add(s)
        }
        return out
    }
}
