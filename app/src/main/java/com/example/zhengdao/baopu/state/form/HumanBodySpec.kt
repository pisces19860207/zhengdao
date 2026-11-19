// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百六十批 · 形轨.形体参数：人读可改的身体输入。
// 输入只放"她填什么"，一切可行域与默认值都在 docs/facts 两张口径表里，不在本文件写死。
package com.example.zhengdao.baopu.state.form

/** 读数出身：实测覆盖值 vs 比例推算值，必须分清（工单 §一⑤）。 */
enum class Origin(val label: String) {
    MEASURED("实测"),
    DERIVED("推算"),
}

/** 一个带来源的读数。缺来源即拒收。 */
data class Sourced(
    val value: Double,
    val unit: String,
    val origin: Origin,
    val source: String,
    val asOf: String,
) {
    init {
        require(value.isFinite()) { "读数非有限" }
        require(source.isNotBlank()) { "读数缺 source，拒收" }
        require(asOf.isNotBlank()) { "读数缺 asOf，拒收" }
    }
}

/** 用户覆盖值：必须写明谁量的、什么时候。 */
data class Override(
    val value: Double,
    val source: String,
    val asOf: String,
) {
    init {
        require(value.isFinite() && value > 0.0) { "覆盖值须为正有限数" }
        require(source.isNotBlank()) { "覆盖值缺 source，拒收" }
        require(asOf.isNotBlank()) { "覆盖值缺 asOf，拒收" }
    }
}

/** 分段覆盖：长度与半径各自可覆盖，未覆盖的按比例推。 */
data class SegmentOverride(
    val lengthCm: Override? = null,
    val radiusCm: Override? = null,
)

/** 躯干截面覆盖：宽与厚各自可覆盖。 */
data class SectionOverride(
    val widthCm: Override? = null,
    val thicknessCm: Override? = null,
)

/** 分区色值：hex 必须带 source，防编色。位数由口径表 skin.color_hex_pattern 校验。 */
data class ZoneColor(
    val hex: String,
    val source: String,
    val asOf: String,
) {
    init {
        require(source.isNotBlank()) { "色值缺 source，拒收（防编色）" }
        require(asOf.isNotBlank()) { "色值缺 asOf，拒收" }
        require(hex.startsWith("#")) { "色值须以 # 开头：$hex" }
        require(hex.drop(1).all { it in HEX_DIGITS }) { "色值含非十六进制字符：$hex" }
    }

    private companion object {
        const val HEX_DIGITS = "0123456789abcdefABCDEF"
    }
}

/** 部件：胸肌/腹肌/臀。null＝取口径表默认。 */
data class PartsSpec(
    val pecMass: Double? = null,
    val pecInsertion: Double? = null,
    val absBlocks: Int? = null,
    val absDepth: Double? = null,
    val gluteMass: Double? = null,
)

/** 皮肤：弹性档/体毛档/分区色值。色值缺 source 或缺区即拒。 */
data class SkinSpec(
    val elasticityGrade: Int? = null,
    val hairGrade: Int? = null,
    val zoneColors: Map<String, ZoneColor> = emptyMap(),
)

/**
 * 生殖器输入。
 * 注意：**没有身高入口**——尺寸与身高只允许弱相关，禁止"身高推尺寸"（事实底盘 md §四）。
 * 尺寸抽样一律经 FactSource；这里的覆盖值必须自带来源。
 */
data class GenitalInput(
    /** 硬度档，对齐 EHS：0＝无反应，1–4 为临床四级。 */
    val turgorGrade: Int = 0,
    /** 阴囊皮温（℃）；null＝取口径表参考值。 */
    val skinTempC: Double? = null,
    /** 弯曲归一值 0–1；null＝取表默认。 */
    val curvature: Double? = null,
    val bendDirection: String = "up",
    /** tapered | capsule | curved；null＝curved 当 curvature>0 否则 capsule。 */
    val shape: String? = null,
    /** 勃起长度覆盖（mm）。 */
    val lengthMm: Override? = null,
    /** 勃起周长覆盖（mm）；等效直径由它换算。 */
    val girthMm: Override? = null,
    /** 疲软长度／勃起长度；null＝取表区间中值。 */
    val flaccidRatio: Double? = null,
    val scrotumVolumeGrade: Int? = null,
    val scrotumSagGrade: Int? = null,
    /** left | right | symmetric。 */
    val hangSide: String? = null,
    val testisAsymmetryMm: Double? = null,
    /** low | medium | high；个体常量，不随充血变化。 */
    val vesselVisibility: String? = null,
    /** 勒压/回流受阻：只有此时才允许紫红，且必须伴温度下降。 */
    val constricted: Boolean = false,
    /** 每区半径覆盖（mm），键＝zone_id。 */
    val zoneRadiusMm: Map<String, Override> = emptyMap(),
)

/**
 * 人体模型输入（工单 §一①）。
 * 整体六项＋分段覆盖＋躯干截面覆盖＋部件＋皮肤＋生殖器。
 * 可行域不在这里写死：由 SpecGuard 拿口径表校验，超域即拒或留痕。
 */
data class HumanBodySpec(
    val heightCm: Double,
    val weightKg: Double,
    val bodyFatPct: Double,
    val muscleGrade: Int? = null,
    /** 柔韧档 1–5；null＝按年龄段取默认（工单追加·可达性三态与柔韧档①）。 */
    val flexibilityGrade: Int? = null,
    /** 年龄段口径键（youth/adult/middle/senior）；null＝取柔韧档区间默认。 */
    val ageBand: String? = null,
    /** 肩峰宽／身高覆盖（口径是 biacromial，不是衣服肩宽）。 */
    val shoulderRatio: Override? = null,
    /** 头身比覆盖（身高／头高）。 */
    val headBodyRatio: Override? = null,
    /** 臂展／身高覆盖。 */
    val armSpanRatio: Override? = null,
    val segments: Map<String, SegmentOverride> = emptyMap(),
    val sections: Map<String, SectionOverride> = emptyMap(),
    val parts: PartsSpec = PartsSpec(),
    val skin: SkinSpec = SkinSpec(),
    val genital: GenitalInput = GenitalInput(),
) {
    init {
        require(heightCm.isFinite() && heightCm > 0.0) { "身高须为正有限数" }
        require(weightKg.isFinite() && weightKg > 0.0) { "体重须为正有限数" }
        require(bodyFatPct.isFinite() && bodyFatPct >= 0.0) { "体脂率须为非负有限数" }
        require(segments.keys.all { it.isNotBlank() }) { "分段覆盖键为空" }
        require(sections.keys.all { it.isNotBlank() }) { "截面覆盖键为空" }
    }

    val heightM: Double get() = heightCm / CM_PER_M

    private companion object {
        const val CM_PER_M = 100.0
    }
}