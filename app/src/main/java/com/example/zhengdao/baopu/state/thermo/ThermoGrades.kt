// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百三十批 · 温度·湿度·提睾档位层：档位与依据的唯一真相表。
// 对外只有闭集档位（体积/垂度/皱缩/贴合/提睾/湿膜/色变 0-3，血管 0-2）；
// 内部一维推进只用整型刻度，绝不外泄；不改 657/686/726/658。
// ERRATA 2026-11-24：本单含一维确定性最小仿真，禁的是病理性温度与连续读数，不是仿真本身。
package com.example.zhengdao.baopu.state.thermo

internal const val THERMO_SOURCE = "派工单-第七百三十批（温湿提睾）＋ERRATA-2026-11-24＋工程假设"
internal const val THERMO_AS_OF = "2026-11-24"
internal const val THERMO_DISCLAIMER = "非医疗判断·仅档位"

/** 方向三值：升/降/平。七条机制只给方向，不给连续量。 */
internal enum class Direction(val label: String) {
    UP("升"),
    DOWN("降"),
    FLAT("平"),
    ;

    companion object {
        const val COUNT = 3
    }
}

/** 温度四档：消费 657 的体感档，本单不重算天气、不给温度数值。 */
internal enum class ThermalBand(val level: Int, val label: String) {
    COOL(0, "偏凉"),
    MILD(1, "适中"),
    WARM(2, "偏暖"),
    HOT(3, "偏热"),
    ;

    companion object {
        const val COUNT = 4
        fun of(level: Int): ThermalBand = entries.first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** 湿度四档：消费 657 的蒸发档（干→蒸发高、饱和→蒸发低）。 */
internal enum class HumidityBand(val level: Int, val label: String) {
    DRY(0, "干燥"),
    NORMAL(1, "常态"),
    HUMID(2, "潮湿"),
    SATURATED(3, "饱和"),
    ;

    companion object {
        const val COUNT = 4
        fun of(level: Int): HumidityBand = entries.first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** 风四档：消费 657 的风档（风越大湿膜消退越快）。 */
internal enum class WindBand(val level: Int, val label: String) {
    STILL(0, "无风"),
    BREEZE(1, "微风"),
    WINDY(2, "有风"),
    GUSTY(3, "大风"),
    ;

    companion object {
        const val COUNT = 4
        fun of(level: Int): WindBand = entries.first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** 接触介质：无 / 水 / 沙 / 湿衣。恰四值，不做其他液体。 */
internal enum class Medium(val label: String) {
    NONE("无"),
    WATER("水"),
    SAND("沙"),
    WET_CLOTH("湿衣"),
    ;

    companion object {
        const val COUNT = 4
    }
}

/** 体位五档：垂坠档必须相对体位给，卧姿不写明显下垂。 */
internal enum class Posture(val label: String) {
    STAND("站"),
    SIT("坐"),
    LIE("卧"),
    SIDE("侧卧"),
    INVERT("倒"),
    ;

    companion object {
        const val COUNT = 5
    }
}

/** 提睾触发（离散事件）：无 / 冷 / 触碰 / 惊吓。 */
internal enum class CremasterTrigger(val label: String) {
    NONE("无"),
    COLD("冷"),
    TOUCH("触碰"),
    STARTLE("惊吓"),
    ;

    companion object {
        const val COUNT = 4
    }
}

/** 输出维度：八维，全部闭集档位，不含任何测量量。 */
internal enum class OutDim(val label: String) {
    VOLUME("体积"),
    HANG("垂度"),
    RUGOSITY("皱缩"),
    CLING("贴合"),
    WET_FILM("湿膜"),
    COLOR_SHIFT("色变"),
    VESSEL_VISIBLE("血管可见"),
    CREMASTERIC("提睾"),
    ;

    companion object {
        const val COUNT = 8
    }
}

/** 一条方向效应：某输出维度相对中性基线的升/降/平。 */
internal data class Effect(val dim: OutDim, val dir: Direction)

/** 七条机制之一的静态身份：id + 中文名 + 当前输入下的方向表。 */
internal data class MechanismSpec(
    val id: String,
    val label: String,
    val direction: (ThermoInput) -> List<Effect>,
)

/** 体积档 0-3。 */
internal enum class VolumeGrade(val level: Int, val label: String) {
    SMALL(0, "偏小"),
    NORMAL(1, "常态"),
    LARGE(2, "偏大"),
    FULL(3, "胀满"),
    ;
    companion object {
        const val COUNT = 4
        fun of(level: Int): VolumeGrade = entries.first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** 垂度档 0-3：与 731 的 scrotalDrop / hang 同口径。 */
internal enum class HangGrade(val level: Int, val label: String) {
    CLOSE(0, "贴体"),
    MIDDLE(1, "中等"),
    DROP(2, "明显下垂"),
    FULL(3, "胀满"),
    ;
    companion object {
        const val COUNT = 4
        fun of(level: Int): HangGrade = entries.first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** 皱缩档 0-3：平滑展平 / 一般 / 明显皱褶 / 密集皱褶。 */
internal enum class RugosityGrade(val level: Int, val label: String) {
    SMOOTH(0, "平滑展平"),
    NORMAL(1, "一般"),
    WRINKLED(2, "明显皱褶"),
    DENSE(3, "密集皱褶"),
    ;
    companion object {
        const val COUNT = 4
        fun of(level: Int): RugosityGrade = entries.first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** 贴合档 0-3：与大腿/衣物的贴合程度。 */
internal enum class ClingGrade(val level: Int, val label: String) {
    NONE(0, "不贴"),
    SLIGHT(1, "微贴"),
    CLING(2, "贴合"),
    TIGHT(3, "紧贴"),
    ;
    companion object {
        const val COUNT = 4
        fun of(level: Int): ClingGrade = entries.first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** 提睾档 0-3：松垂 / 微提 / 明显上提 / 紧贴根部。 */
internal enum class CremastericGrade(val level: Int, val label: String) {
    LOOSE(0, "松垂"),
    LIFT(1, "微提"),
    UP(2, "明显上提"),
    ROOT(3, "紧贴根部"),
    ;
    companion object {
        const val COUNT = 4
        fun of(level: Int): CremastericGrade = entries.first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** 湿膜档 0-3：干 / 微湿 / 湿 / 湿透。对齐 731.surface.wetFilm。 */
internal enum class WetFilmGrade(val level: Int, val label: String) {
    DRY(0, "干"),
    TRACE(1, "微湿"),
    WET(2, "湿"),
    SOAKED(3, "湿透"),
    ;
    companion object {
        const val COUNT = 4
        fun of(level: Int): WetFilmGrade = entries.first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** 色变档 0-3：相对常态的偏离，不给绝对色号。对齐 731.surface.colorShift。 */
internal enum class ColorShiftGrade(val level: Int, val label: String) {
    NONE(0, "无"),
    SLIGHT(1, "轻微"),
    OBVIOUS(2, "明显"),
    STRONG(3, "显著"),
    ;
    companion object {
        const val COUNT = 4
        fun of(level: Int): ColorShiftGrade = entries.first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** 血管可见档 0-2：不见 / 隐约 / 可见。对齐 731.surface.vesselVisible。 */
internal enum class VesselVisibleGrade(val level: Int, val label: String) {
    NONE(0, "不见"),
    FAINT(1, "隐约"),
    VISIBLE(2, "可见"),
    ;
    companion object {
        const val COUNT = 3
        fun of(level: Int): VesselVisibleGrade = entries.first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/**
 * 731.surface 三字段的机读名与档域。字段名与派工单中文名逐字对齐，
 * 731 可直接引用 SurfaceReading 而不必另立字段名。
 */
internal object SurfaceCodes {
    const val WET_FILM = "wetFilm"
    const val COLOR_SHIFT = "colorShift"
    const val VESSEL_VISIBLE = "vesselVisible"
    const val WET_FILM_MAX = 3
    const val COLOR_SHIFT_MAX = 3
    const val VESSEL_VISIBLE_MAX = 2
    val FIELDS = listOf(WET_FILM, COLOR_SHIFT, VESSEL_VISIBLE)
}

/** 判据红码：每个红一个码，测试逐码断言消息前缀。 */
internal object ThermoCodes {
    const val MECHANISM_MISSING = "E-MECH-MISSING"
    const val RECOIL_STUCK = "E-RECOIL-STUCK"
    const val DIRECTION_WRONG = "E-DIRECTION-WRONG"
    const val CONTINUOUS_LEAK = "E-CONTINUOUS-LEAK"
    const val PATHOLOGICAL = "E-PATHOLOGICAL"
    const val SURFACE_MISMATCH = "E-SURFACE-MISMATCH"
    val ALL = listOf(
        MECHANISM_MISSING, RECOIL_STUCK, DIRECTION_WRONG,
        CONTINUOUS_LEAK, PATHOLOGICAL, SURFACE_MISMATCH,
    )
}

/** 病理性温度词：命中即红（不做发热/炎症/感染那一类）。拼接写，避免源码门禁自咬。 */
internal object PathologyGuard {
    private val TERMS = listOf(
        "发" + "热", "发" + "烧", "炎" + "症", "感" + "染",
        "病" + "变", "肿" + "瘤", "就" + "医", "诊" + "断",
    )
    fun hits(text: String): List<String> = TERMS.filter { it in text }
    fun requireClean(text: String) {
        val h = hits(text)
        require(h.isEmpty()) { THERMO_DISCLAIMER + "：病理词命中「" + h.joinToString("、") + "」" }
    }
}

/** 连续读数词：对外文本不得出现温度/长度体积/时间读数。拼接写。 */
internal object ContinuousGuard {
    private val TERMS = listOf(
        "摄" + "氏度", "°C", "℃", "c" + "m", "m" + "m", "m" + "L",
        "毫" + "米", "厘" + "米", "毫" + "升", "秒", "赫" + "兹", "H" + "z",
    )
    fun hits(text: String): List<String> {
        val found = TERMS.filter { it in text }.toMutableList()
        var i = 0
        while (i + 2 < text.length) {
            if (text[i].isDigit() && text[i + 1] == '.' && text[i + 2].isDigit()) {
                found.add("小数读数")
                break
            }
            i++
        }
        return found
    }
}
