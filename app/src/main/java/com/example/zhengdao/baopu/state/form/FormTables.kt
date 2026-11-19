// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百六十批 · 形轨.形体参数：两张口径表的读取（fail-closed）。
// 表里每条数值都必须带 caliber＋source＋asOf＋evidence，缺任一即拒收。
package com.example.zhengdao.baopu.state.form

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 一条带口径与来源的数值：点值时 low＝high＝default。 */
data class NumericSpec(
    val id: String,
    val low: Double,
    val high: Double,
    val default: Double,
    val caliber: String,
    val source: String,
    val asOf: String,
    val evidence: Evidence,
) {
    init {
        require(caliber.isNotBlank()) { "$id 缺 caliber，拒收" }
        require(source.isNotBlank()) { "$id 缺 source，拒收" }
        require(asOf.isNotBlank()) { "$id 缺 asOf，拒收" }
        require(low.isFinite() && high.isFinite() && default.isFinite()) { "$id 数值非有限" }
        require(low <= high) { "$id 区间倒置" }
        require(default in low..high) { "$id 默认值落在区间外" }
    }

    val isPoint: Boolean get() = low == high

    fun contains(v: Double) = v in low..high

    /** 把归一值 t∈[0,1] 映到区间内；t 越界即拒（不外推）。 */
    fun at(t: Double): Double {
        require(t in 0.0..1.0) { "$id 归一值 $t 越界" }
        return low + (high - low) * t
    }
}

/** 分段口径：length 为空表示由 lengthFrom 指定的残差/倒数推得。 */
data class SegmentSpec(
    val id: String,
    val joint: String,
    val extraJoint: String?,
    val split: NumericSpec?,
    val side: String,
    val shape: String,
    val lateral: String,
    val length: NumericSpec?,
    val lengthFrom: String?,
    val radius: NumericSpec?,
)

/** 躯干柱截面（station 归一到髋线→颈根）。 */
data class SectionSpec(
    val id: String,
    val station: NumericSpec,
    val width: NumericSpec,
    val thickness: NumericSpec,
)

/** 皮肤口径：弹性档/体毛档/色值位数＋阴毛分布梯度（梯度是方向性口径，不是数值区间）。 */
data class SkinRules(
    val elasticity: NumericSpec,
    val hair: NumericSpec,
    val hexDigits: NumericSpec,
    val hairGradient: Map<String, Double>,
    val gradientSource: String,
    val gradientAsOf: String,
) {
    init {
        require(hairGradient.isNotEmpty()) { "阴毛梯度为空" }
        require(gradientSource.isNotBlank()) { "阴毛梯度缺 source，拒收" }
        require(hairGradient.values.all { it in 0.0..1.0 }) { "阴毛梯度系数须在 0..1" }
    }

    fun factor(region: String) = hairGradient[region] ?: 0.0
}

/** 柔韧档口径：限位随档位变（不是常数表）；三态判定的边缘带与风险提示都从这里取。 */
data class FlexRules(
    val grade: NumericSpec,
    val ageBandDefault: Map<String, Int>,
    val ageBandCaliber: String,
    val limitScale: NumericSpec,
    val marginalBand: NumericSpec,
    val jointBaseDeg: Map<String, NumericSpec>,
    val riskNotes: Map<String, String>,
    val riskCaliber: String,
    val caliber: String,
    val source: String,
    val asOf: String,
) {
    init {
        require(ageBandDefault.isNotEmpty()) { "年龄段默认档为空" }
        require(jointBaseDeg.isNotEmpty()) { "关节基准活动度为空" }
        require(limitScale.low > 0.0) { "档位系数须为正" }
        require(marginalBand.default >= 1.0) { "边缘带系数须不小于 1" }
        require(ageBandCaliber.isNotBlank()) { "年龄段口径缺 caliber" }
        require(riskCaliber.isNotBlank()) { "风险提示口径缺 caliber" }
        require(caliber.isNotBlank()) { "柔韧口径缺 caliber" }
        require(source.isNotBlank()) { "柔韧口径缺 source，拒收" }
    }

    /** 档位系数：档下界取 limitScale.low，档上界取 high，中间线性。 */
    fun scale(g: Int): Double {
        require(g.toDouble() in grade.low..grade.high) { "柔韧档 $g 超域 ${grade.low}..${grade.high}" }
        return limitScale.at((g - grade.low) / (grade.high - grade.low))
    }

    fun defaultGrade(band: String?): Int {
        if (band == null) return grade.default.toInt()
        return ageBandDefault[band] ?: throw IllegalArgumentException("未知年龄段 $band")
    }
}

/** 比例表。 */
data class Proportions(
    val ratios: Map<String, NumericSpec>,
    val segments: List<SegmentSpec>,
    val sections: List<SectionSpec>,
    val constants: Map<String, NumericSpec>,
    val parts: Map<String, NumericSpec>,
    val skin: SkinRules,
    val flexibility: FlexRules,
) {
    init {
        require(segments.map { it.id }.distinct().size == segments.size) { "分段 id 重复" }
        require(sections.map { it.id }.distinct().size == sections.size) { "截面 id 重复" }
        require(segments.all { (it.length == null) != (it.lengthFrom == null) }) {
            "分段必须恰有一种长度来源（length 或 lengthFrom）"
        }
    }

    fun ratio(id: String) = ratios[id] ?: throw IllegalArgumentException("比例表缺 $id")
    fun constant(id: String) = constants[id] ?: throw IllegalArgumentException("常量表缺 $id")
    fun part(id: String) = parts[id] ?: throw IllegalArgumentException("部件表缺 $id")
    fun segment(id: String) = segments.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("分段表缺 $id")
    fun section(id: String) = sections.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("截面表缺 $id")
}

/** 一条硬误写不变式：参数表若违反它，报 vocab_error 并指名区与正确说法。 */
data class ZoneInvariant(
    val id: String,
    val zoneId: String,
    val field: String,
    val expected: String,
    val correction: String,
    val source: String,
    val asOf: String,
) {
    init {
        require(correction.isNotBlank()) { "$id 缺正确说法" }
        require(source.isNotBlank()) { "$id 缺 source，拒收" }
    }
}

/** 分区口径：尺寸相对柱身参考半径 R 与长度 L，R/L 一律经 FactSource，不在表里写死。 */
data class ZoneSpec(
    val zoneId: String,
    val proxy: String,
    val anchor: String,
    val side: String,
    val radiusFactor: NumericSpec,
    val axial: NumericSpec,
    val lateralFactor: Double,
    val gain: NumericSpec,
    val sourceRef: String,
    val lengthRef: String,
    val form: String?,
    val surface: String?,
    val colorDeepenStep: Int?,
    val layers: List<String>,
    val retractable: Boolean,
    val tempLinked: Boolean,
    val leftLower: Boolean,
) {
    init {
        require(sourceRef.isNotBlank()) { "$zoneId 尺寸参数缺 source_ref，拒收" }
        require(lengthRef.isNotBlank()) { "$zoneId 尺寸参数缺 length_ref，拒收" }
    }
}

/** 裆缝凸起高口径（供 666 的 HeightFieldSource）：只产 h，抹平交给 666。 */
data class BulgeRule(
    val diameterFactor: Double,
    val hardnessLow: Double,
    val hardnessHigh: Double,
    val caliber: String,
    val source: String,
    val asOf: String,
) {
    init {
        require(source.isNotBlank()) { "凸起口径缺 source，拒收" }
        require(caliber.isNotBlank()) { "凸起口径缺 caliber" }
        require(diameterFactor > 0.0) { "直径系数须为正" }
        require(hardnessLow <= hardnessHigh) { "硬度系数区间倒置" }
    }

    /** fill＝充血归一（0 档→0，满档→1）。 */
    fun coefficient(fill: Double): Double {
        require(fill in 0.0..1.0) { "充血归一值越界" }
        return hardnessLow + (hardnessHigh - hardnessLow) * fill
    }
}

/** 接触参数（供 661 腔隙碰撞；655 两体碰撞取 restitution 当前值）。 */
data class ContactRule(val restitution: NumericSpec, val stiffness: NumericSpec)

/** 紫红/暗紫的防漂锁：不是默认颜色，出现必须伴随温度下降。 */
data class PurpleRule(
    val requiresTempDrop: Boolean,
    val caliber: String,
    val source: String,
    val asOf: String,
) {
    init {
        require(caliber.isNotBlank()) { "紫红口径缺 caliber" }
        require(source.isNotBlank()) { "紫红口径缺 source，拒收" }
    }
}

/** 分区表。 */
data class ZoneTable(
    val zonesRef: String,
    val domains: Map<String, NumericSpec>,
    val zones: List<ZoneSpec>,
    val invariants: List<ZoneInvariant>,
    val enums: Map<String, List<String>>,
    val surfaceByTurgor: Map<Int, String>,
    val vesselDefault: String,
    val purple: PurpleRule,
    val bulge: BulgeRule,
    val rootY: NumericSpec,
    val contact: ContactRule,
) {
    init {
        require(zones.map { it.zoneId }.distinct().size == zones.size) { "分区 id 重复" }
        require(zones.isNotEmpty()) { "分区表为空" }
        require(invariants.all { inv -> zones.any { it.zoneId == inv.zoneId } }) { "不变式指向不存在的区" }
        require(zones.all { it.proxy in enums.getValue("proxy") }) { "代理体类型不在枚举内" }
        require(zones.all { it.side in enums.getValue("side") }) { "方位不在枚举内" }
    }

    fun domain(id: String) = domains[id] ?: throw IllegalArgumentException("分区域表缺 $id")
    fun zone(id: String) = zones.firstOrNull { it.zoneId == id }
        ?: throw IllegalArgumentException("分区表缺 $id")
}

/** 两张口径表的读取入口。表是 docs 下的口径文件，不是素材正文。 */
object FormTables {
    const val PROPORTIONS_REL_PATH = "docs/facts/body-proportions.json"
    const val ZONES_REL_PATH = "docs/facts/body-zones.json"
    const val SCHEMA_PROPORTIONS = "body-proportions/v1"
    const val SCHEMA_ZONES = "body-zones/v1"

    private val META_KEYS = setOf("caliber", "source", "asOf", "evidence")

    fun loadProportions(repoRoot: File) =
        parseProportions(File(repoRoot, PROPORTIONS_REL_PATH).readText())

    fun loadZones(repoRoot: File) = parseZones(File(repoRoot, ZONES_REL_PATH).readText())

    fun parseProportions(json: String): Proportions {
        val o = JSONObject(json)
        require(o.optString("schema") == SCHEMA_PROPORTIONS) { "比例表 schema 不符" }
        val inherit = o.getJSONObject("segment_defaults")
        return Proportions(
            ratios = specArray(o.getJSONArray("ratios"), null),
            segments = parseSegments(o.getJSONArray("segments"), inherit),
            sections = parseSections(o.getJSONArray("torso_sections"), inherit),
            constants = specMap(o.getJSONObject("constants"), null),
            parts = specMap(o.getJSONObject("parts"), null),
            skin = parseSkin(o.getJSONObject("skin")),
            flexibility = parseFlex(o.getJSONObject("flexibility")),
        )
    }

    fun parseZones(json: String): ZoneTable {
        val o = JSONObject(json)
        require(o.optString("schema") == SCHEMA_ZONES) { "分区表 schema 不符" }
        val inherit = o.getJSONObject("zone_defaults")
        val enums = parseEnums(o.getJSONObject("enums"))
        return ZoneTable(
            zonesRef = reqStr(o, "zones_ref", "分区表"),
            domains = specMap(o.getJSONObject("domains"), null),
            zones = parseZonesArray(o.getJSONArray("zones"), inherit),
            invariants = parseInvariants(o.getJSONArray("invariants")),
            enums = enums,
            surfaceByTurgor = parseSurface(o.getJSONObject("surface_by_turgor")),
            vesselDefault = reqStr(o.getJSONObject("vessel_rule"), "default", "vessel_rule"),
            purple = parsePurple(o.getJSONObject("purple_rule")),
            bulge = parseBulge(o.getJSONObject("bulge")),
            rootY = plain(o.getJSONObject("frame"), "root_y_below_hip_frac", "frame.root_y", o.getJSONObject("frame")),
            contact = parseContact(o.getJSONObject("contact")),
        )
    }

    private fun parseContact(o: JSONObject) = ContactRule(
        restitution = nestedSpec(o, "restitution", "contact.restitution"),
        stiffness = nestedSpec(o, "stiffness_grade", "contact.stiffness_grade"),
    )

    private fun parsePurple(o: JSONObject) = PurpleRule(
        requiresTempDrop = o.optBoolean("requires_temp_drop", false),
        caliber = reqStr(o, "caliber", "purple_rule"),
        source = reqStr(o, "source", "purple_rule"),
        asOf = reqStr(o, "asOf", "purple_rule"),
    )

    private fun nestedSpec(parent: JSONObject, key: String, id: String): NumericSpec {
        val o = parent.getJSONObject(key)
        val point = o.optDouble("value", Double.NaN)
        val low = o.optDouble("low", point)
        val high = o.optDouble("high", point)
        require(!low.isNaN() && !high.isNaN()) { "$id 缺 value 或 low/high，拒收" }
        return NumericSpec(
            id, low, high, o.optDouble("default", (low + high) / 2.0),
            reqStr(parent, "caliber", id), reqStr(parent, "source", id),
            reqStr(parent, "asOf", id), evidence(reqStr(parent, "evidence", id)),
        )
    }

    private fun parseBulge(o: JSONObject): BulgeRule = BulgeRule(
        diameterFactor = o.getDouble("diameter_factor"),
        hardnessLow = o.getDouble("hardness_low"),
        hardnessHigh = o.getDouble("hardness_high"),
        caliber = reqStr(o, "caliber", "bulge"),
        source = reqStr(o, "source", "bulge"),
        asOf = reqStr(o, "asOf", "bulge"),
    )

    /** 键序固定：org.json 的 keys() 不保证顺序，排序后同输入才逐字节一致。 */
    private fun sortedKeys(o: JSONObject): List<String> = o.keys().asSequence().sorted().toList()

    private fun arrOf(o: JSONObject): JSONArray {
        val a = JSONArray()
        for (k in sortedKeys(o)) a.put(o.getJSONObject(k).put("id", k))
        return a
    }

    private fun reqStr(o: JSONObject, k: String, ctx: String): String {
        val v = o.optString(k, "")
        require(v.isNotBlank()) { "$ctx 缺 $k，拒收" }
        return v
    }

    private fun evidence(s: String): Evidence = when (s) {
        "verified" -> Evidence.VERIFIED
        "consensus" -> Evidence.CONSENSUS
        "assumption" -> Evidence.ASSUMPTION
        else -> throw IllegalArgumentException("未知证据等级 $s")
    }

    private fun spec(o: JSONObject, id: String, inherit: JSONObject?): NumericSpec {
        val src = inherit ?: o
        val point = o.has("value")
        val low = o.optDouble(if (point) "value" else "low", Double.NaN)
        val high = o.optDouble(if (point) "value" else "high", Double.NaN)
        require(!low.isNaN() && !high.isNaN()) { "$id 缺 value 或 low/high，拒收" }
        val def = if (o.has("default")) o.getDouble("default") else (low + high) / 2.0
        return NumericSpec(
            id, low, high, def,
            reqStr(o, "caliber", id), reqStr(src, "source", id),
            reqStr(src, "asOf", id), evidence(reqStr(src, "evidence", id)),
        )
    }

    private fun plain(o: JSONObject, key: String, id: String, inherit: JSONObject): NumericSpec {
        val v = o.optDouble(key, Double.NaN)
        require(!v.isNaN()) { "$id 缺 $key，拒收" }
        return NumericSpec(
            id, v, v, v,
            reqStr(o, "caliber", id), reqStr(inherit, "source", id),
            reqStr(inherit, "asOf", id), evidence(reqStr(inherit, "evidence", id)),
        )
    }

    private fun specArray(a: JSONArray, inherit: JSONObject?): Map<String, NumericSpec> {
        val out = LinkedHashMap<String, NumericSpec>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val id = reqStr(o, "id", "表项")
            require(out.put(id, spec(o, id, inherit)) == null) { "表项 id 重复：$id" }
        }
        return out
    }

    private fun specMap(o: JSONObject, inherit: JSONObject?): Map<String, NumericSpec> =
        specArray(arrOf(o), inherit)

    private fun parseSegments(a: JSONArray, inherit: JSONObject): List<SegmentSpec> =
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val id = reqStr(o, "id", "分段")
            SegmentSpec(
                id = id,
                joint = reqStr(o, "joint", id),
                extraJoint = o.optString("extra_joint", "").ifBlank { null },
                split = if (o.has("split_frac")) plain(o, "split_frac", "$id.split", inherit) else null,
                side = reqStr(o, "side", id),
                shape = reqStr(o, "shape", id),
                lateral = reqStr(o, "lateral", id),
                length = if (o.has("length_frac")) plain(o, "length_frac", "$id.length", inherit) else null,
                lengthFrom = o.optString("length_from", "").ifBlank { null },
                radius = if (o.has("radius_frac")) plain(o, "radius_frac", "$id.radius", inherit) else null,
            )
        }

    private fun parseSections(a: JSONArray, inherit: JSONObject): List<SectionSpec> =
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val id = reqStr(o, "id", "截面")
            SectionSpec(
                id,
                plain(o, "station", "$id.station", inherit),
                plain(o, "width_frac", "$id.width", inherit),
                plain(o, "thickness_frac", "$id.thickness", inherit),
            )
        }

    private fun parseEnums(o: JSONObject): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        for (k in sortedKeys(o)) {
            val a = o.getJSONArray(k)
            out[k] = (0 until a.length()).map { a.getString(it) }
        }
        return out
    }

    private fun parseSurface(o: JSONObject): Map<Int, String> = mapOf(
        0 to reqStr(o, "grade0", "surface_by_turgor"),
        2 to reqStr(o, "grade2", "surface_by_turgor"),
        4 to reqStr(o, "grade4", "surface_by_turgor"),
    )

    private fun parseFlex(o: JSONObject): FlexRules {
        val base = o.getJSONObject("joint_base_deg")
        val bands = o.getJSONObject("age_band_default")
        val risks = o.getJSONObject("risk_notes")
        return FlexRules(
            grade = spec(o.getJSONObject("grade"), "flexibility.grade", o),
            ageBandDefault = intMap(bands),
            ageBandCaliber = reqStr(bands, "caliber", "flexibility.age_band_default"),
            limitScale = spec(o.getJSONObject("limit_scale"), "flexibility.limit_scale", o),
            marginalBand = spec(o.getJSONObject("marginal_band"), "flexibility.marginal_band", o),
            jointBaseDeg = dataKeys(base).associateWith { degSpec(base.getJSONObject(it), "flexibility.$it", base, o) },
            riskNotes = strMap(risks),
            riskCaliber = reqStr(risks, "caliber", "flexibility.risk_notes"),
            caliber = reqStr(o, "caliber", "flexibility"),
            source = reqStr(o, "source", "flexibility"),
            asOf = reqStr(o, "asOf", "flexibility"),
        )
    }

    /** 口径四件是元数据，不是数据项。 */
    private fun dataKeys(o: JSONObject): List<String> = sortedKeys(o).filter { it !in META_KEYS }

    private fun intMap(o: JSONObject): Map<String, Int> = dataKeys(o).associateWith { o.getInt(it) }

    private fun strMap(o: JSONObject): Map<String, String> = dataKeys(o).associateWith { o.getString(it) }

    private fun degSpec(o: JSONObject, id: String, base: JSONObject, inherit: JSONObject): NumericSpec {
        val low = o.getDouble("low")
        val high = o.getDouble("high")
        return NumericSpec(
            id, low, high, (low + high) / 2.0,
            reqStr(base, "caliber", id), reqStr(inherit, "source", id),
            reqStr(inherit, "asOf", id), evidence(reqStr(inherit, "evidence", id)),
        )
    }

    private fun parseSkin(o: JSONObject): SkinRules {
        val g = o.getJSONObject("hair_gradient")
        return SkinRules(
            elasticity = spec(o.getJSONObject("elasticity_grade"), "skin.elasticity_grade", null),
            hair = spec(o.getJSONObject("hair_grade"), "skin.hair_grade", null),
            hexDigits = spec(o.getJSONObject("color_hex_pattern"), "skin.color_hex_pattern", null),
            hairGradient = parseGradient(g),
            gradientSource = reqStr(g, "source", "hair_gradient"),
            gradientAsOf = reqStr(g, "asOf", "hair_gradient"),
        )
    }

    private fun parseGradient(g: JSONObject): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        for (k in sortedKeys(g)) {
            if (k.endsWith("_factor")) out[k.removeSuffix("_factor")] = g.getDouble(k)
        }
        require(out.isNotEmpty()) { "阴毛梯度缺 *_factor" }
        return out
    }

    private fun parseInvariants(a: JSONArray): List<ZoneInvariant> =
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val id = reqStr(o, "id", "不变式")
            ZoneInvariant(
                id, reqStr(o, "zone_id", id), reqStr(o, "field", id),
                reqStr(o, "expected", id), reqStr(o, "correction", id),
                reqStr(o, "source", id), reqStr(o, "asOf", id),
            )
        }

    private fun parseZonesArray(a: JSONArray, inherit: JSONObject): List<ZoneSpec> =
        (0 until a.length()).map { i -> parseZone(a.getJSONObject(i), inherit) }

    private fun parseZone(o: JSONObject, inherit: JSONObject): ZoneSpec {
        val id = reqStr(o, "zone_id", "分区")
        val axial = o.getJSONArray("axial")
        require(axial.length() == 2) { "$id axial 须两点" }
        val gain = o.getJSONArray("turgor_gain")
        require(gain.length() == 2) { "$id turgor_gain 须两点" }
        return ZoneSpec(
            zoneId = id,
            proxy = reqStr(o, "proxy", id),
            anchor = reqStr(o, "anchor", id),
            side = reqStr(o, "side", id),
            radiusFactor = plain(o, "radius_factor", "$id.radius_factor", inherit),
            axial = NumericSpec(
                "$id.axial", axial.getDouble(0), axial.getDouble(1),
                (axial.getDouble(0) + axial.getDouble(1)) / 2.0,
                reqStr(o, "caliber", id), reqStr(inherit, "source", id),
                reqStr(inherit, "asOf", id), evidence(reqStr(inherit, "evidence", id)),
            ),
            lateralFactor = o.optDouble("lateral_factor", 0.0),
            gain = NumericSpec(
                "$id.turgor_gain", gain.getDouble(0), gain.getDouble(1),
                (gain.getDouble(0) + gain.getDouble(1)) / 2.0,
                reqStr(o, "caliber", id), reqStr(inherit, "source", id),
                reqStr(inherit, "asOf", id), evidence(reqStr(inherit, "evidence", id)),
            ),
            sourceRef = reqStr(o, "source_ref", id),
            lengthRef = reqStr(o, "length_ref", id),
            form = o.optString("form", "").ifBlank { null },
            surface = o.optString("surface", "").ifBlank { null },
            colorDeepenStep = if (o.has("color_deepen_step")) o.getInt("color_deepen_step") else null,
            layers = strList(o.optJSONArray("layers")),
            retractable = o.optBoolean("retractable", false),
            tempLinked = o.optBoolean("temp_linked", false),
            leftLower = o.optBoolean("left_lower", false),
        )
    }

    private fun strList(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
}