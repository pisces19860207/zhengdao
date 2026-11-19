// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百五十批 · 位置→术语双向映射索引（deictic index）。
// 真源＝docs/facts/deictic-index.json（与 deictic-index.md 双真相同提交）。
// 13 区定义沿用 anatomy-zones.json，本索引不复制第二份区表。
// 正向：位置口语→术语（闭集词面匹配，多命中列候选＋消歧）；
// 反向：术语→上一环/下一环/相邻（顺序链取 731 更正版）；别名归规范名。
// 纯函数、零模型、零联网、零随机、零时钟；缺字段/空表/悬空引用 ⇒ 抛（fail-closed）。
package com.example.zhengdao.baopu.body

import java.io.File
import org.json.JSONObject

/** 一条位置描述：text 为人读口语，keys 为检索钥匙（闭集词面）。 */
data class DeicticDesc(val text: String, val keys: List<String>, val source: String, val asOf: String)

/** 别名分层：口语可解释，民间别名只进表、不进规范输出。 */
data class AliasLayer(val colloquial: List<String>, val folk: List<String>)

/** 一个 13 区术语条目。 */
data class DeicticZone(
    val zoneId: String,
    val canonical: String,
    val canonicalZh: String,
    val aliases: AliasLayer,
    val descriptions: List<DeicticDesc>,
    val source: String,
    val asOf: String,
)

/** 顺序链节点：zone 指向真实分区；anchor 是坡/过渡锚点，host 指所属分区。 */
data class ChainNodeData(val kind: String, val zoneId: String?, val host: String?, val zh: String)

/** 侧挂：frenulum 落在某环（at），prepuce 跨若干环（spans）。 */
data class SideAttach(val at: String?, val spans: List<String>, val surface: String?, val note: String)

data class ChainData(
    val core731: List<String>,
    val order: List<String>,
    val nodes: Map<String, ChainNodeData>,
    val side: Map<String, SideAttach>,
)

/** 消歧办法：一组易混区 → 区分动作。 */
data class Disambiguation(val zones: List<String>, val hint: String, val source: String, val asOf: String)

/** 正向命中：zoneId 与命中钥匙数。 */
data class ResolveHit(val zoneId: String, val score: Int)

/** 正向结果：候选按分数降序、表序升序；ambiguous=候选≥2。 */
data class ResolveResult(
    val input: String,
    val hits: List<ResolveHit>,
    val candidates: List<String>,
    val ambiguous: Boolean,
    val disambiguation: String?,
)

/** 反向一环：上一环/下一环来自链序，相邻含链邻居＋侧挂反引。 */
data class ChainLink(val term: String, val prev: String?, val next: String?, val adjacent: List<String>)

/**
 * 双向映射索引。构造时吃真源 json，纯查询。
 */
class DeicticIndex(
    val zones: List<DeicticZone>,
    val chain: ChainData,
    val disambiguations: List<Disambiguation>,
) {
    init {
        require(zones.isNotEmpty()) { "术语表为空，拒绝装配（fail-closed）" }
        require(zones.map { it.zoneId }.toSet().size == zones.size) { "zone_id 重复，拒绝装配" }
        require(chain.core731.isNotEmpty() && chain.order.isNotEmpty()) { "顺序链为空，拒绝装配" }
        for (t in chain.order) require(t in chain.nodes) { "链节点无定义：$t" }
        val zoneIds = zones.map { it.zoneId }.toSet()
        for ((k, n) in chain.nodes) {
            if (n.kind == "zone") require(n.zoneId in zoneIds) { "链节点 $k 指向未知分区：${n.zoneId}" }
            if (n.kind == "anchor") require(n.host in zoneIds) { "锚点 $k 的 host 未定义：${n.host}" }
        }
    }

    private val zoneOrder: Map<String, Int> = zones.mapIndexed { i, z -> z.zoneId to i }.toMap()

    /** 正向：位置口语→候选术语。民间别名可作检索钥匙，但输出只有规范名。 */
    fun resolve(input: String): ResolveResult {
        val hits = zones.map { z -> z.zoneId to score(z, input) }.filter { it.second > 0 }
        val ranked = hits.sortedWith(compareByDescending<Pair<String, Int>> { it.second }
            .thenBy { zoneOrder[it.first] ?: Int.MAX_VALUE })
        val candidates = ranked.map { it.first }
        val ambiguous = candidates.size >= 2
        return ResolveResult(
            input = input,
            hits = ranked.map { ResolveHit(it.first, it.second) },
            candidates = candidates,
            ambiguous = ambiguous,
            disambiguation = if (ambiguous) hintFor(candidates) else null,
        )
    }

    /**
     * 反向：术语→上一环/下一环/相邻。
     * 主链节点有 prev/next；侧挂术语（frenulum/prepuce）不在主链，prev/next 为 null，
     * 相邻取其 at/spans 及反向引用——两者都属判据③的"术语"。
     */
    fun chainOf(term: String): ChainLink? {
        val onChain = term in chain.nodes
        val isSide = term in chain.side
        if (!onChain && !isSide) return null
        val idx = chain.order.indexOf(term)
        val prev = if (onChain && idx > 0) chain.order[idx - 1] else null
        val next = if (onChain && idx in chain.order.indices && idx < chain.order.size - 1) {
            chain.order[idx + 1]
        } else null
        val adj = linkedSetOf<String>()
        listOf(prev, next).filterNotNull().forEach { adj += it }
        for ((k, s) in chain.side) {
            if (s.at == term || term in s.spans) adj += k
        }
        chain.side[term]?.let { s ->
            s.at?.let { adj += it }
            adj.addAll(s.spans)
        }
        return ChainLink(term, prev, next, adj.toList())
    }

    /** 别名归规范：规范名/中文名/口语/民间别名都回落到 canonical。 */
    fun canonicalOf(term: String): String? = zones.firstOrNull { z ->
        term == z.canonical || term == z.canonicalZh ||
            term in z.aliases.colloquial || term in z.aliases.folk
    }?.canonical

    /** 规范输出串：只含规范名，绝不含民间别名。 */
    fun canonicalOutput(z: DeicticZone): String = "${z.canonical}（${z.canonicalZh}）"

    private fun score(z: DeicticZone, input: String): Int {
        var n = 0
        for (d in z.descriptions) for (k in d.keys) if (k in input) n++
        for (k in z.aliases.colloquial) if (k in input) n++
        for (k in z.aliases.folk) if (k in input) n++
        return n
    }

    private fun hintFor(candidates: List<String>): String {
        val key = candidates.toSet()
        val exact = disambiguations.firstOrNull { it.zones.toSet() == key }
        if (exact != null) return exact.hint
        val covering = disambiguations.firstOrNull { key.all { z -> z in it.zones } }
        return covering?.hint ?: "同时命中 ${candidates.joinToString("、")}，请用区分动作再探（不要硬选一个）。"
    }

    companion object {
        const val REL_PATH = "docs/facts/deictic-index.json"

        fun load(repoRoot: File): DeicticIndex = parse(File(repoRoot, REL_PATH).readText())

        fun parse(json: String): DeicticIndex {
            val root = JSONObject(json)
            require(root.optString("schema") == "deictic-index/v1") { "schema 必须是 deictic-index/v1" }
            val zones = parseZones(root.getJSONArray("zones"))
            val chain = parseChain(root.getJSONObject("chain"))
            val dis = parseDis(root.getJSONArray("disambiguations"))
            require(root.has("tactile")) { "触觉索引缺失，拒绝装配" }
            return DeicticIndex(zones, chain, dis)
        }

        private fun parseZones(arr: org.json.JSONArray): List<DeicticZone> =
            (0 until arr.length()).map { parseZone(arr.getJSONObject(it)) }

        private fun parseZone(o: JSONObject): DeicticZone {
            val al = o.getJSONObject("aliases")
            val descs = o.getJSONArray("descriptions")
            return DeicticZone(
                zoneId = req(o, "zone_id"),
                canonical = req(o, "canonical"),
                canonicalZh = req(o, "canonical_zh"),
                aliases = AliasLayer(strList(al, "colloquial"), strList(al, "folk")),
                descriptions = (0 until descs.length()).map { d ->
                    val x = descs.getJSONObject(d)
                    DeicticDesc(req(x, "text"), strList(x, "keys"), req(x, "source"), req(x, "asOf"))
                },
                source = req(o, "source"),
                asOf = req(o, "asOf"),
            )
        }

        private fun parseChain(o: JSONObject): ChainData {
            val nodes = o.getJSONObject("nodes")
            val nodeMap = nodes.keys().asSequence().associate { k ->
                val n = nodes.getJSONObject(k)
                k to ChainNodeData(
                    kind = req(n, "kind"),
                    zoneId = n.optString("zone_id").ifBlank { null },
                    host = n.optString("host").ifBlank { null },
                    zh = req(n, "zh"),
                )
            }
            val side = o.getJSONObject("side")
            val sideMap = side.keys().asSequence().associate { k ->
                val s = side.getJSONObject(k)
                k to SideAttach(
                    at = s.optString("at").ifBlank { null },
                    spans = strList(s, "spans"),
                    surface = s.optString("surface").ifBlank { null },
                    note = req(s, "note"),
                )
            }
            return ChainData(strList(o, "core731"), strList(o, "order"), nodeMap, sideMap)
        }

        private fun parseDis(arr: org.json.JSONArray): List<Disambiguation> =
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Disambiguation(strList(o, "zones"), req(o, "hint"), req(o, "source"), req(o, "asOf"))
            }

        internal fun req(o: JSONObject, key: String): String {
            val v = o.optString(key)
            require(v.isNotBlank()) { "缺字段或为空：$key" }
            return v
        }

        internal fun strList(o: JSONObject, key: String): List<String> {
            val arr = o.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).map { arr.getString(it) }
        }
    }
}
