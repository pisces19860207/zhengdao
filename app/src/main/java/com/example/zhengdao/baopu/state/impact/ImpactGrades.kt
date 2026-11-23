// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百二十七批 · 撞击事件与软组织响应（准静态近似）：档位与输入形状。
// 三输入 → 五输出，全是离散档与可观察词：不出力值、不做伤情分级、不做行为指导。
// event 三字段对齐 731 §一（刚受击 / 提睾中 / 恢复档 0–3）。
package com.example.zhengdao.baopu.state.impact

import com.example.zhengdao.baopu.state.cavity.NormalBucket
import com.example.zhengdao.baopu.state.prepuce.AreaLevel
import com.example.zhengdao.baopu.state.prepuce.CoverState
import com.example.zhengdao.baopu.state.prepuce.SkinAmount

/** 输入来源编号（进源码而非只进注释：判据⑦要求「未引用 ⇒ 提示」可被机器查到）。 */
internal const val COVERAGE_SOURCE_IDS: String = "804/831"
internal const val SKIN_SOURCE_ID: String = "810"
internal const val SUBZONE_SOURCE_ID: String = "775"

/** 相对速度档 0–4：静止 / 慢 / 中 / 快 / 急。 */
internal enum class SpeedTier(val level: Int, val label: String) {
    STILL(0, "静止"), SLOW(1, "慢"), MEDIUM(2, "中"), FAST(3, "快"), RUSH(4, "急"),
    ;

    companion object {
        fun of(level: Int): SpeedTier = entries.first { it.level == level }
    }
}

/** 参与体质量档 0–3：轻 / 中 / 重 / 重物。只吃档，不建模对方实体。 */
internal enum class MassTier(val level: Int, val label: String) {
    LIGHT(0, "轻"), MEDIUM(1, "中"), HEAVY(2, "重"), OBJECT(3, "重物"),
    ;

    companion object {
        fun of(level: Int): MassTier = entries.first { it.level == level }
    }
}

/** 接触面积档 0–3：点 / 小面 / 大面 / 包覆。面积越大，同样能量摊得越开。 */
internal enum class AreaTier(val level: Int, val label: String) {
    POINT(0, "点"), SMALL(1, "小面"), LARGE(2, "大面"), WRAP(3, "包覆"),
    ;

    companion object {
        fun of(level: Int): AreaTier = entries.first { it.level == level }
    }
}

/** 冲击档 0–4。 */
internal enum class ImpactTier(val level: Int, val label: String) {
    NONE(0, "无"), SLIGHT(1, "轻"), CLEAR(2, "明显"), STRONG(3, "强"), SEVERE(4, "很强"),
    ;

    companion object {
        fun of(level: Int): ImpactTier = entries.first { it.level == level }
    }
}

/** 位移档 0–3：准静态受击后偏离原位的档。 */
internal enum class DisplaceTier(val level: Int, val label: String) {
    ZERO(0, "原位"), SLIGHT(1, "微移"), CLEAR(2, "移位"), FAR(3, "偏离明显"),
    ;

    companion object {
        fun of(level: Int): DisplaceTier = entries.first { it.level == level }
    }
}

/** 压扁档 0–3：软组织在接触面上的压扁程度。 */
internal enum class FlattenTier(val level: Int, val label: String) {
    ZERO(0, "不压"), SLIGHT(1, "微压"), CLEAR(2, "压扁"), DEEP(3, "压扁明显"),
    ;

    companion object {
        fun of(level: Int): FlattenTier = entries.first { it.level == level }
    }
}

/**
 * 恢复档 0–3。[budgetRounds] 是内部轮次刻度，只用于「时间夹具必落回 0 档」，不进输出文案。
 * 刻度沿用 726 / 728 的 1 / 3 / 6 预算档。
 */
internal enum class RecoveryTier(val level: Int, val label: String, val budgetRounds: Int) {
    NOW(0, "当轮消", 0), SOON(1, "稍后消", 1), LATER(2, "一段后消", 3), LONG(3, "许久才消", 6),
    ;

    companion object {
        fun of(level: Int): RecoveryTier = entries.first { it.level == level }
    }
}

/** 后果档 0–3：无 / 轻微 / 明显 / 剧烈（观察档，不是伤情分级）。 */
internal enum class ConsequenceTier(val level: Int, val label: String) {
    NONE(0, "无"), SLIGHT(1, "轻微"), CLEAR(2, "明显"), SEVERE(3, "剧烈"),
    ;

    companion object {
        fun of(level: Int): ConsequenceTier = entries.first { it.level == level }
    }
}

/**
 * 下体子区（13 项，逐字来自本单§追加·作用力矩阵的子区清单）。
 * 775 的细分（龟头四 / 柱身三 / 睾丸三）尚未落地：本单只用这 13 个区级名，
 * 不自造细分层名；细分层统一写作 [FINE_SUBZONE_PENDING]。
 */
internal enum class ImpactSite(val label: String, val zoneId: String) {
    GLANS("龟头", "glans"),
    CORONA("冠状沟", "sulcus"),
    FRENULUM("系带", "frenulum"),
    MEATUS("尿道口", "meatus"),
    PREPUCE_INNER("包皮内板", "prepuce.inner"),
    PREPUCE_OUTER("包皮外板", "prepuce.outer"),
    SHAFT("柱身", "shaft"),
    ROOT("根部", "radix"),
    SCROTUM("阴囊", "scrotum"),
    TESTIS("睾丸", "testis"),
    CORD("精索", "cord"),
    PUBIS("耻骨", "mons"),
    PERINEUM("会阴", "perineum.x1"),
    ;

    companion object {
        const val FINE_SUBZONE_PENDING: String = "子区细分 id 待 775"
    }
}

/** 撞型四类（§追加·下体子区级撞击）：挤压 / 钝击 / 摩擦牵扯 / 贯穿型。 */
internal enum class StrikeKind(
    val label: String,
    val typical: ImpactSite,
    val intensity: ImpactTier,
    val consequence: String,
    val note: String,
) {
    COMPRESSION("挤压", ImpactSite.SHAFT, ImpactTier.CLEAR, "闷胀、一沉", "手 / 体压；缓冲＝包皮、阴囊壁、脂肪垫"),
    BLUNT("钝击", ImpactSite.TESTIS, ImpactTier.STRONG, "一沉、短暂停顿", "碰撞 / 膝顶；缓冲少，睾丸最重"),
    FRICTION_DRAG("摩擦牵扯", ImpactSite.FRENULUM, ImpactTier.CLEAR, "发热、微破损", "衣料 / 拉扯；缓冲＝覆盖位与皮量"),
    PENETRATIVE("贯穿型", ImpactSite.MEATUS, ImpactTier.STRONG, "短暂停顿、压痕", "进入语境，接 742 / 779；只写现象"),
}

/** 七类作用力（§追加·作用力矩阵）：指有意图或非日常的施加。 */
internal enum class ForceKind(val label: String, val source: String, val buffer: String) {
    COMPRESSION("挤压", "手 / 体压 / 夹挤", "包皮、阴囊壁、脂肪垫"),
    BLUNT("钝击", "碰撞 / 膝顶 / 工具", "缓冲少"),
    FRICTION("摩擦", "衣料 / 皮肤 / 反复接触", "覆盖位"),
    TRACTION("牵扯", "拉拽包皮 / 阴囊 / 毛发", "皮量与松紧"),
    SHEAR("剪切", "相对错动", "皮肤滑动度"),
    TORSION("扭转", "睾丸 / 根部旋转", "医学禁区，只写现象"),
    SUCTION("负压吸吮", "进入语境，接 742 / 779", "内板、粘膜"),
}

/** 负压撤除后的消退时间档：分钟 / 小时 / 过夜。 */
internal enum class WithdrawalTier(val label: String) {
    MINUTES("分钟"), HOURS("小时"), OVERNIGHT("过夜"),
}

/** 日常动作：不进作用力矩阵，默认无后果（见 748 豁免清单）。 */
internal enum class RoutineAction(val label: String) {
    REACH("掏"), ZIP("拉链"), UNZIP("解裤"), SIT("坐下"), STAND("起身"), URINATE("排尿"),
}

/** 拉链夹到包皮的唯一边界情形，必须有触发。 */
internal enum class RareTrigger(val label: String) {
    HURRY("急"), SLIP("手滑"), CLOTH("衣物变形"),
}

/** 一次撞击的三输入。 */
internal data class ImpactHit(val speed: SpeedTier, val mass: MassTier, val area: AreaTier)

/**
 * 判定输入。覆盖位（804 / 831）与皮量松紧（810）为可缺省输入：缺了按无缓冲算并出「提示」。
 * [other] 非空表示同帧第二次撞击：取更强的一次并标 multi，绝不累加。
 * [cremasteric] 由 730 / 726 供给，727 只透传、不判定。
 */
internal data class ImpactInput(
    val speed: SpeedTier,
    val mass: MassTier,
    val area: AreaTier,
    val site: ImpactSite? = null,
    val coverage: AreaLevel? = null,
    val cover: CoverState? = null,
    val skin: SkinAmount? = null,
    val normal: NormalBucket = NormalBucket.PLUS_Y,
    val cremasteric: Boolean = false,
    val rounds: Int = 0,
    val other: ImpactHit? = null,
) {
    init {
        require(rounds >= 0) { "轮次不得为负" }
    }
}

/** 对齐 731 §一 event 字段：刚受击? / 提睾中? / 恢复档 0–3。 */
internal data class ImpactEvent(
    val justHit: Boolean,
    val cremasteric: Boolean,
    val recovery: Int,
    val source: String = "727",
)

/** 五输出＋event：缺一即红（由 [ImpactChecks] 与源码门禁双向钉住）。 */
internal data class ImpactResult(
    val impact: ImpactTier,
    val displace: DisplaceTier,
    val flatten: FlattenTier,
    val recovery: RecoveryTier,
    val words: List<String>,
    val event: ImpactEvent,
    val site: ImpactSite?,
    val multi: Boolean,
    val consequence: ConsequenceTier,
    val why: String,
)
