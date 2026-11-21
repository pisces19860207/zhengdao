// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百七十四批 · 校准装配①（BodyStateProvider 的真身）：660 设定 → 660 BodyState。
//
// 三条纪律：
//   ① 只吃"人读可改"的设定文件 `<工作区>/抱朴记忆/校准/身体.json` ＋ 660 口径表（docs/facts），
//      一个身材数字都不在本文件发明；
//   ② 设定缺项 / 缺 source+asOf / 口径表缺失 / 660 可行域拒收 ⇒ 一律 null，
//      由上层出「未设定」——**不许回落默认身材**（判据②）；
//   ③ 只做 JSON 结构解析（JSONObject），不写检索、不打分、不进任何闸（判据⑤）。
package com.example.zhengdao.baopu.calibration

import com.example.zhengdao.baopu.state.form.BodyModel
import com.example.zhengdao.baopu.state.form.BodyState
import com.example.zhengdao.baopu.state.form.HumanBodySpec
import com.example.zhengdao.baopu.state.form.Override
import com.example.zhengdao.baopu.state.form.SkinSpec
import com.example.zhengdao.baopu.state.form.ZoneColor
import java.io.File
import org.json.JSONObject

/** 一次读到的 660 设定：规格本体 + 这份设定是谁填的（敏感层条目要引它做来源）。 */
internal data class BodySetting(
    val spec: HumanBodySpec,
    val source: String,
    val asOf: String,
)

/** 660 身体设定的读入口。纯取数：不缓存、不渲染、不判定。 */
internal object CalibrationBodySource {

    /** 设定文件落点（157「根里没有裸文件」：放在抱朴记忆/校准 下面）。 */
    const val REL_SPEC = "抱朴记忆/校准/身体.json"

    /** 660 口径表根：BodyModel.load 的 repoRoot 判据是这张表在不在。 */
    private const val FACTS_PROBE = "docs/facts/anatomy-zones.json"

    const val MUSCLE_MIN = 1
    const val MUSCLE_MAX = 5

    private const val F_HEIGHT = "heightCm"
    private const val F_WEIGHT = "weightKg"
    private const val F_FAT = "bodyFatPct"
    private const val F_MUSCLE = "muscleGrade"
    private const val F_SHOULDER = "shoulderRatio"
    private const val F_SKIN = "skin"
    private const val F_ZONE_COLORS = "zoneColors"
    private const val F_HEX = "hex"
    private const val F_ELASTICITY = "elasticityGrade"
    private const val F_HAIR = "hairGrade"
    private const val F_SOURCE = "source"
    private const val F_AS_OF = "asOf"
    private const val F_VALUE = "value"

    /** 设定文件：不存在 ⇒ null。 */
    fun specFile(root: File?): File? = root?.let { File(it, REL_SPEC) }?.takeIf { it.isFile }

    /** 口径表根：缺 669 表 ⇒ null（半套口径表比没有更危险，宁可不装配）。 */
    fun factsRoot(root: File?): File? = root?.takeIf { File(it, FACTS_PROBE).isFile }

    /** 现读设定文件；读不动/解析不出 ⇒ null。 */
    fun readSetting(root: File?): BodySetting? {
        val file = specFile(root) ?: return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return parseSetting(text)
    }

    /**
     * 解析 660 输入。硬门槛：身高/体重/体脂三项为正有限数，且 source、asOf 都在——
     * 缺任一项即 null（不许拿"平均值"补一个身材出来）。肌肉档越界只丢这一项。
     */
    fun parseSetting(json: String): BodySetting? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val height = o.optDouble(F_HEIGHT)
        val weight = o.optDouble(F_WEIGHT)
        val fat = o.optDouble(F_FAT)
        val source = o.optString(F_SOURCE).trim()
        val asOf = o.optString(F_AS_OF).trim()
        if (!(height.isPositive() && weight.isPositive() && fat.isPositive())) return null
        if (source.isEmpty() || asOf.isEmpty()) return null
        val muscle = if (o.has(F_MUSCLE)) o.optInt(F_MUSCLE).takeIf { it in MUSCLE_MIN..MUSCLE_MAX } else null
        val spec = runCatching {
            HumanBodySpec(
                heightCm = height, weightKg = weight, bodyFatPct = fat, muscleGrade = muscle,
                shoulderRatio = ratioOverride(o), skin = skin(o),
            )
        }.getOrNull() ?: return null
        return BodySetting(spec, source, asOf)
    }

    /**
     * 皮肤：弹性档/体毛档原样透传；分区色值只吃设定文件里"作者填的那一份"。
     *
     * 660 的 SpecGuard 要求 669 词表里每一区都带 source+asOf 的色值，缺任何一区就整份拒收
     * （`IllegalArgumentException: 缺色值的区…`）。本函数**不给任何区补色**——缺色的条目直接丢掉，
     * 丢到不齐 ⇒ 660 拒收 ⇒ 上层出「未设定」（判据②：宁可没有，不许编一个身材出来）。
     */
    private fun skin(o: JSONObject): SkinSpec {
        val node = o.optJSONObject(F_SKIN) ?: return SkinSpec()
        val elastic = if (node.has(F_ELASTICITY)) node.optInt(F_ELASTICITY) else null
        val hair = if (node.has(F_HAIR)) node.optInt(F_HAIR) else null
        return SkinSpec(elasticityGrade = elastic, hairGrade = hair, zoneColors = zoneColors(node))
    }

    /** 单区色值：hex 与 source、asOf 三者齐了才收，缺任一项即丢这一区（不补默认色）。 */
    private fun zoneColors(node: JSONObject): Map<String, ZoneColor> {
        val table = node.optJSONObject(F_ZONE_COLORS) ?: return emptyMap()
        val out = LinkedHashMap<String, ZoneColor>()
        for (zone in table.keys()) {
            val cell = table.optJSONObject(zone) ?: continue
            val hex = cell.optString(F_HEX).trim()
            val src = cell.optString(F_SOURCE).trim()
            val stamp = cell.optString(F_AS_OF).trim()
            if (hex.isEmpty() || src.isEmpty() || stamp.isEmpty()) continue
            runCatching { out[zone] = ZoneColor(hex, src, stamp) }
        }
        return out
    }

    /** 肩峰比覆盖：必须自带 source+asOf（660 Override 的门槛），缺则不带覆盖。 */
    private fun ratioOverride(o: JSONObject): Override? {
        val node = o.optJSONObject(F_SHOULDER) ?: return null
        val value = node.optDouble(F_VALUE)
        val src = node.optString(F_SOURCE).trim()
        val asOf = node.optString(F_AS_OF).trim()
        if (!(value.isPositive() && src.isNotEmpty() && asOf.isNotEmpty())) return null
        return runCatching { Override(value, src, asOf) }.getOrNull()
    }

    /** 真装配：设定与口径表都在 ⇒ 660 BodyState；660 拒收（超可行域）⇒ null。 */
    fun loadState(root: File?): BodyState? {
        val setting = readSetting(root) ?: return null
        val facts = factsRoot(root) ?: return null
        return runCatching { BodyModel.load(setting.spec, facts) }.getOrNull()
    }

    /** 降级原因（只给治理只读看，不进注入文本）：说清缺哪一环，不编数。 */
    fun degradeReason(root: File?): String = when {
        specFile(root) == null -> "无 660 设定文件"
        readSetting(root) == null -> "设定缺项或缺来源"
        factsRoot(root) == null -> "缺 docs/facts 口径表"
        loadState(root) == null -> "660 可行域拒收"
        else -> ""
    }

    private fun Double.isPositive(): Boolean = isFinite() && this > 0.0
}
