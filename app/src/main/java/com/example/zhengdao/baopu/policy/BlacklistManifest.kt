// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第九百四十三批 · 黑名单唯一 manifest（assets/policy/blacklist-manifest.json）。
// 934 与 935 的两份黑名单同一口径、同一处维护：行数据只住在这一份 JSON 里，
// 934 的门禁测试与 935 的 ShapeRumorCatalog 都只做**读端**，不再各养一张表。
//
// 分工照 327 批 AnchorAsset 房型：本文件**纯 JVM**（零 Android、零 IO，
// PolicySourceGateTest 逐文件机检）；读资产在测试夹具与装配层。
//
// 两层校验，任何一层不过 ⇒ [Snapshot] 拒收（fail closed，不是跳过继续跑）：
//   ① 结构：kinds 闭集恰为 934-de-filter／935-shape-myth 两类（第三张表进不来）、
//      行字段齐、模板必须带 … 占位、id 唯一；
//   ② 计数自洽：totals 与 subtotals 必须和实际行数逐项相符——改表忘了同步计数，这里先拒。
//
// 另有一条**语义铁律**钉在解析层：每行 expect 只许 "RED"（[EXPECT_RED]）。
// 黑名单只会红——manifest 永远发不出通行证，判定代码也永远读不到 expect 这个字段
// （ShapeJudge／ShapeRumorCatalog 源码由 BlacklistManifestGateTest 机检）。
package com.example.zhengdao.baopu.policy

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal object BlacklistManifest {

    /** APK 内路径（assets 根以下）。 */
    const val PATH = "policy/blacklist-manifest.json"

    /** 审计行里的来源名。 */
    const val SOURCE = "assets/$PATH"

    /** 934 类 id：去滤镜·七类句式黑名单（身份/长相/钱权 → 品性）。 */
    const val KIND_DE_FILTER = "934-de-filter"

    /** 935 类 id：形态不推性评价·七类体相传闻（体相 → 性评价）。 */
    const val KIND_SHAPE_MYTH = "935-shape-myth"

    /** kinds 闭集：只许这两类，顺序即声明序。第三张表不许并入（943 边界）。 */
    val KINDS: List<String> = listOf(KIND_DE_FILTER, KIND_SHAPE_MYTH)

    /** kind id → totals／subtotals 的小节键。 */
    private val SECTION_OF: Map<String, String> =
        mapOf(KIND_DE_FILTER to "934", KIND_SHAPE_MYTH to "935")

    /** 唯一合法 expect：黑名单只会红；manifest 改成别的值 ⇒ 解析拒收（发不了通行证）。 */
    const val EXPECT_RED = "RED"

    /** 934 的一行：编号／类别／子类／句式模板／方向。 */
    class DeFilterRow(
        val id: String,
        val category: String,
        val sub: String,
        val template: String,
        val direction: String,
    )

    /** 935 的一行：编号／类 tag（A–G）／类名／句式模板／断言目标名集。 */
    class RumorRow(
        val id: String,
        val klass: String,
        val classLabel: String,
        val template: String,
        val targets: List<String>,
    )

    /**
     * manifest 快照：读 manifest 的**唯一形态**。
     * 拒收不抛异常：读端拿不到合法表就该空手而归并大声说不，而不是拿半份表继续跑。
     */
    class Snapshot private constructor(
        val ok: Boolean,
        val refusal: String?,
        deFilter: List<DeFilterRow>,
        shapeMyth: List<RumorRow>,
        val totals: Map<String, Int>,
        val subtotals: Map<String, Map<String, Int>>,
    ) {
        /** 不可变快照：构造期转死，此后没有改它的路。 */
        val deFilter: List<DeFilterRow> = ArrayList(deFilter)

        /** 不可变快照。 */
        val shapeMyth: List<RumorRow> = ArrayList(shapeMyth)

        /** 934 行数（拒收态恒 0）。 */
        val deFilterCount: Int get() = deFilter.size

        /** 935 行数（拒收态恒 0）。 */
        val shapeMythCount: Int get() = shapeMyth.size

        /** 某类的总数（totals 小节，键 934／935）。 */
        fun total(kind: String): Int = totals.getValue(section(kind))

        /** 某类的小计表（subtotals 小节，键 934／935）。 */
        fun subtotal(kind: String): Map<String, Int> = subtotals.getValue(section(kind))

        /** kind id → totals／subtotals 小节键。 */
        private fun section(kind: String): String =
            SECTION_OF[kind] ?: throw IllegalArgumentException("未知 manifest 类：$kind")

        /** 一行审计读数。 */
        fun summary(): String =
            if (ok) "$SOURCE 载入：$KIND_DE_FILTER ${deFilterCount} 条／$KIND_SHAPE_MYTH ${shapeMythCount} 条，计数自洽"
            else "$SOURCE 拒收：$refusal"

        companion object {
            fun force(
                deFilter: List<DeFilterRow>,
                shapeMyth: List<RumorRow>,
                totals: Map<String, Int>,
                subtotals: Map<String, Map<String, Int>>,
            ): Snapshot = Snapshot(true, null, deFilter, shapeMyth, totals, subtotals)

            fun refuse(reason: String): Snapshot =
                Snapshot(false, reason, emptyList(), emptyList(), emptyMap(), emptyMap())
        }
    }

    /** 资产字节 → 快照。空文件直接拒。 */
    fun fromAsset(raw: ByteArray): Snapshot {
        if (raw.isEmpty()) return Snapshot.refuse("$SOURCE 为空")
        return validate(raw.toString(Charsets.UTF_8))
    }

    /** 文本 → 快照：解析或校验的任何失败都转拒收，错误原因随行。 */
    internal fun validate(text: String): Snapshot =
        try {
            force(parse(text))
        } catch (e: IllegalArgumentException) {
            Snapshot.refuse(e.message ?: "$SOURCE 解析失败")
        } catch (e: JSONException) {
            Snapshot.refuse("$SOURCE 不是合法 JSON：${e.message}")
        }

    /**
     * 文本 → 四件套（行×2＋计数×2）。任何结构问题抛 [IllegalArgumentException]：
     * kinds 闭集、字段齐、模板带 …、id 唯一、expect 全 RED、totals／subtotals 与实数逐项相符。
     */
    internal fun parse(text: String): Parsed {
        val root = JSONObject(text)
        val kinds = root.optJSONArray("kinds")
            ?: throw IllegalArgumentException("$SOURCE 缺 kinds 数组")
        if (kinds.length() != KINDS.size) {
            throw IllegalArgumentException(
                "$SOURCE kinds 必须 ${KINDS.size} 类，实际 ${kinds.length()} 类（第三张表不许并入）",
            )
        }
        val deFilter = ArrayList<DeFilterRow>()
        val shapeMyth = ArrayList<RumorRow>()
        for (i in 0 until kinds.length()) {
            val kind = kinds.optJSONObject(i)
                ?: throw IllegalArgumentException("$SOURCE kinds[$i] 不是对象")
            val id = required(kind, "id", "kinds[$i]")
            if (i >= KINDS.size || id != KINDS[i]) {
                throw IllegalArgumentException(
                    "$SOURCE kinds[$i] id 应为 ${KINDS.getOrNull(i)}，实际 $id（顺序与 id 都是闭集）",
                )
            }
            val rows = kind.optJSONArray("rows")
                ?: throw IllegalArgumentException("$SOURCE kinds[$i] 缺 rows 数组")
            when (id) {
                KIND_DE_FILTER -> parseDeFilter(rows, deFilter)
                KIND_SHAPE_MYTH -> parseShapeMyth(rows, shapeMyth)
            }
        }
        val totals = sectionCounts(root, "totals", deFilter.size to shapeMyth.size)
        val subtotals = mapOf(
            SECTION_OF.getValue(KIND_DE_FILTER) to
                rowSubtotals(root, deFilter, SECTION_OF.getValue(KIND_DE_FILTER)) { it.category },
            SECTION_OF.getValue(KIND_SHAPE_MYTH) to
                rowSubtotals(root, shapeMyth, SECTION_OF.getValue(KIND_SHAPE_MYTH)) { it.klass },
        )
        return Parsed(deFilter, shapeMyth, totals, subtotals)
    }

    /** 解析产物（四件套），供 [parse] 返回、[validate] 转 [Snapshot]。 */
    internal class Parsed(
        val deFilter: List<DeFilterRow>,
        val shapeMyth: List<RumorRow>,
        val totals: Map<String, Int>,
        val subtotals: Map<String, Map<String, Int>>,
    )

    private fun force(p: Parsed): Snapshot = Snapshot.force(p.deFilter, p.shapeMyth, p.totals, p.subtotals)

    // ── 934 行 ────────────────────────────────────────────────────────

    private fun parseDeFilter(rows: JSONArray, out: MutableList<DeFilterRow>) {
        val seen = HashSet<String>()
        for (i in 0 until rows.length()) {
            val o = rows.optJSONObject(i)
                ?: throw IllegalArgumentException("$SOURCE 934 行[$i] 不是对象")
            val row = DeFilterRow(
                id = required(o, "id", "934 行[$i]"),
                category = required(o, "category", "934 行[$i]"),
                sub = required(o, "sub", "934 行[$i]"),
                template = required(o, "template", "934 行[$i]"),
                direction = required(o, "direction", "934 行[$i]"),
            )
            commonRowChecks(row.id, row.template, rowExpect(o), "934 行[$i]")
            if (row.direction !in setOf("好", "坏")) {
                throw IllegalArgumentException("$SOURCE 934 行[$i] direction 只许 好／坏：${row.direction}")
            }
            if (!seen.add(row.id)) {
                throw IllegalArgumentException("$SOURCE 934 行 id 重复：${row.id}")
            }
            out.add(row)
        }
    }

    // ── 935 行 ────────────────────────────────────────────────────────

    private fun parseShapeMyth(rows: JSONArray, out: MutableList<RumorRow>) {
        val seen = HashSet<String>()
        for (i in 0 until rows.length()) {
            val o = rows.optJSONObject(i)
                ?: throw IllegalArgumentException("$SOURCE 935 行[$i] 不是对象")
            val targets = o.optJSONArray("targets")?.let { arr ->
                (0 until arr.length()).map { j -> arr.optString(j).trim() }
            } ?: throw IllegalArgumentException("$SOURCE 935 行[$i] 缺 targets")
            if (targets.isEmpty() || targets.any { it.isEmpty() }) {
                throw IllegalArgumentException("$SOURCE 935 行[$i] targets 必须非空且不许空值")
            }
            val row = RumorRow(
                id = required(o, "id", "935 行[$i]"),
                klass = required(o, "class", "935 行[$i]"),
                classLabel = required(o, "classLabel", "935 行[$i]"),
                template = required(o, "template", "935 行[$i]"),
                targets = targets,
            )
            commonRowChecks(row.id, row.template, rowExpect(o), "935 行[$i]")
            if (!seen.add(row.id)) {
                throw IllegalArgumentException("$SOURCE 935 行 id 重复：${row.id}")
            }
            out.add(row)
        }
    }

    // ── 公共行检查 ────────────────────────────────────────────────────

    /** 模板必须带 … 占位；expect 必须恰为 RED——manifest 永远发不出通行证。 */
    private fun commonRowChecks(id: String, template: String, expect: String, where: String) {
        if (!template.contains('…')) {
            throw IllegalArgumentException("$SOURCE $where $id 只收句式模板（必须带 … 占位），不收单词")
        }
        if (expect != EXPECT_RED) {
            throw IllegalArgumentException(
                "$SOURCE $where $id expect 只许 $EXPECT_RED，实际 $expect（黑名单只会红，发不了通行证）",
            )
        }
    }

    private fun rowExpect(o: JSONObject): String = o.optString("expect").trim()

    private fun required(o: JSONObject, key: String, where: String): String {
        val v = o.optString(key).trim()
        if (v.isEmpty()) throw IllegalArgumentException("$SOURCE $where 缺字段或空值：$key")
        return v
    }

    // ── 计数自洽 ──────────────────────────────────────────────────────

    /**
     * totals 小节：必须恰有 934／935 两键，且与实际行数逐项相等。
     * 改表忘了同步 totals ⇒ 这里先拒（改一条必红的第一道闸）。
     */
    private fun sectionCounts(root: JSONObject, key: String, actual: Pair<Int, Int>): Map<String, Int> {
        val obj = root.optJSONObject(key)
            ?: throw IllegalArgumentException("$SOURCE 缺 $key 小节")
        val out = LinkedHashMap<String, Int>()
        SECTION_OF.values.forEach { section ->
            if (!obj.has(section)) {
                throw IllegalArgumentException("$SOURCE $key 缺键 $section")
            }
            val n = obj.optInt(section, -1)
            if (n <= 0) throw IllegalArgumentException("$SOURCE $key.$section 必须是正整数，实际 $n")
            out[section] = n
        }
        if (obj.length() != SECTION_OF.size) {
            throw IllegalArgumentException(
                "$SOURCE $key 只许 ${SECTION_OF.values} 两键，多出来的键不许有",
            )
        }
        val expected = linkedMapOf(
            SECTION_OF.getValue(KIND_DE_FILTER) to actual.first,
            SECTION_OF.getValue(KIND_SHAPE_MYTH) to actual.second,
        )
        if (out != expected) {
            throw IllegalArgumentException(
                "$SOURCE $key 与实际行数不符：声明 $out，实际 $expected",
            )
        }
        return out
    }

    /**
     * subtotals 某类的小节：键集必须与行的分组实数逐项相符（键多、键少、值不对都拒）。
     * 删 subtotals 键 ⇒ 缺键拒；改小计数值 ⇒ 不符拒（F5 的闸）。
     */
    private fun <T> rowSubtotals(
        root: JSONObject,
        rows: List<T>,
        section: String,
        groupOf: (T) -> String,
    ): Map<String, Int> {
        val subtotalsRoot = root.optJSONObject("subtotals")
            ?: throw IllegalArgumentException("$SOURCE 缺 subtotals 小节")
        if (!subtotalsRoot.has(section)) {
            throw IllegalArgumentException("$SOURCE subtotals 缺键 $section")
        }
        val declaredObj = subtotalsRoot.optJSONObject(section)
            ?: throw IllegalArgumentException("$SOURCE subtotals.$section 不是对象")
        val actual = LinkedHashMap<String, Int>()
        rows.forEach { row ->
            val g = groupOf(row)
            actual[g] = (actual[g] ?: 0) + 1
        }
        val declared = LinkedHashMap<String, Int>()
        declaredObj.keys().forEach { k -> declared[k] = declaredObj.optInt(k, -1) }
        if (declared != actual) {
            throw IllegalArgumentException(
                "$SOURCE subtotals.$section 与实际分组不符：声明 $declared，实际 $actual",
            )
        }
        return declared
    }
}
