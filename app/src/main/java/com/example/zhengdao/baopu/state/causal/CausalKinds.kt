// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百三十五批 · 因果链骨架：事件类型闭集、参与部位、受伤机制。
//
// 三样东西都不带「档」：事件类型是**类型**（链上的节点名），部位是**引用**（805 部位 / 806 体表分区），
// 机制是**引用**（748 的七类闭集）。判据⑥「不新造档」在类型上就能看出来——这里没有一处序数刻度。
//
// 关于 748 与 824/825 的落地状态（必须写清，别让后来人以为「仓里已经有了」）：
//   · 748（受伤事件分类）**仓内无生产类型**，只有派工单。本包按派工单 §一① ＋ 追加① 的
//     七类机制逐字镜像（[InjuryMechanism]），一个字不加、一个字不减；748 落地后删掉它改 import。
//   · 824（光照五维）**仓内无生产类型**。本包不镜像它的档，只在光影行显式写 [Pending824] 的
//     「未落地」——**没有值就不许填值**（照 817 对头发/眼镜的处理）。
//   · 825（跨场结转）**仓内无生产类型**，四档跨度在 [Span825] 里镜像，溯源写死。
package com.example.zhengdao.baopu.state.causal

import com.example.zhengdao.baopu.state.bodyparts.BodyPart
import com.example.zhengdao.baopu.state.surfaceflow.SweatZone

/** 824 未落地的显式占位：光影只给 657 的日照/影长几何，五维光源一律缺省。 */
internal const val Pending824 = "824 五维光源未落地 ⇒ 色温/硬软/距离/材质响应 absent"

/** 748 未落地的显式占位：13 区与远近（浅/深）没有生产类型，本包只用 805/806 的既有部位。 */
internal const val Pending748 = "748 十三区与远近未落地 ⇒ 部位只用 805 部位 / 806 体表分区"

/**
 * 参与部位：三型。
 *
 * [Zone] 走 806 的体表九区（汗/湿/温这类要「哪个区」的读数）；
 * [Part] 走 805 的八个部位（伤情/姿态这类要「哪块」的读数）；
 * [Unnamed] 是**显式**的「没点名」，不是缺省值——检查器见它判红（825 §二「缺项 ⇒ 红」）。
 */
internal sealed interface SiteRef {
    val text: String

    data class Zone(val zone: SweatZone) : SiteRef {
        override val text: String get() = "区·${zone.label}"
    }

    data class Part(val part: BodyPart) : SiteRef {
        override val text: String get() = "部位·${part.label}"
    }

    data object Unnamed : SiteRef {
        override val text: String get() = "未点名"
    }
}

/**
 * 748 的受伤机制七类（闭集，逐字镜像派工单 §一① 五类 ＋ 追加① 追加两类）。
 *
 * 为什么镜像而不是「先跳过」：链①的起点必须说明「这一下是什么机制」，否则
 * 「受伤」退化成一根没有内容的边，前后档位接不起来。镜像只抄**类名**，不抄任何后果表
 * （后果表是 748 §二 的事，748 没落地就不许在这里发明）。
 */
internal enum class InjuryMechanism(val label: String, val observable: String) {
    BLUNT("钝击", "闷响、随即一沉"),
    COMPRESS("挤压", "压扁、回弹慢"),
    TWIST_PULL("扭转·牵拉", "歪斜、拉扯感"),
    FRICTION("摩擦·擦伤", "表面发红、火辣"),
    THERMAL("热（烫/冻）", "先热后钝"),
    CHEMICAL("化学刺激", "刺、烧"),
    TEAR("撕裂·裂伤", "线状破口、边缘不齐"),
    ;

    companion object {
        /** 七类：748 落地后这个数必须仍等于它的闭集大小，否则就是有人偷偷加了一类。 */
        const val COUNT = 7

        fun of(label: String): InjuryMechanism = values().first { it.label == label }
    }
}

/**
 * 事件类型闭集：十一条，正好是三根链上的十一个节点（链②的「皮肤湿/温」是两条边，故多一个）。
 *
 * [writes] 是「这类事件**允许**写哪些维度」——写别的维度判红。这条约束不是洁癖：
 * 有了它，「环境变化」就不能偷偷直接把伤情改掉，链的形状被类型钉住。
 *
 * [parents] 是合法父类（空集＝根节点类型）。父类不在集内判红——这就是判据③「断链 ⇒ 红」的牙。
 * 用 getter 而不是构造参数：枚举初始化期互相引用会拿到 null，getter 是懒的。
 */
internal enum class CausalKind(val label: String, val writes: List<CausalDim>) {
    /** 链①①：受伤。写入部位伤情与疼痛两档（档值由调用方给，本包不发明 748 的后果表）。 */
    STRIKE("受伤", listOf(CausalDim.SITE_INJURY, CausalDim.SENSE_PAIN)),

    /** 链①②：步态改变。只写 725 的关节角档与重心档。 */
    GAIT("步态改变", listOf(CausalDim.POSE_JOINT, CausalDim.POSE_COM)),

    /** 链①③：后续受限。落回 728 的活动受限档（level 3）。 */
    RESTRICT("后续受限", listOf(CausalDim.SITE_INJURY)),

    /** 链②①：环境变化。只作驱动（657 一次求解的整体产出在事件外传参），自己不写档。 */
    AMBIENT("环境变化", emptyList()),

    /** 链②②：皮肤湿。写 806 的量档。 */
    WET("皮肤湿", listOf(CausalDim.SKIN_WET)),

    /** 链②③：皮肤温。写 658 的体温偏移档。 */
    TEMP("皮肤温", listOf(CausalDim.SKIN_TEMP)),

    /** 链②④：体感。写 657 的体感温度档。 */
    FEEL("体感", listOf(CausalDim.SENSE_FEEL)),

    /** 链②⑤：光影。写 657 的日照档（824 五维缺省）。 */
    LIGHT("光影", listOf(CausalDim.LIGHT)),

    /** 链③①：动作。只作驱动（动作种类取 725 的 `MotionAction`），自己不写档。 */
    ACT("动作", emptyList()),

    /** 链③②：疲劳累积。写 658 的疲劳档。 */
    FATIGUE("疲劳累积", listOf(CausalDim.SENSE_FATIGUE)),

    /** 链③③：恢复时限。把疲劳档按 734 的恢复预算落回到期时点（825 四档）。 */
    RECOVER("恢复时限", listOf(CausalDim.SENSE_FATIGUE)),
    ;

    /** 合法父类；空集＝根类型。 */
    val parents: Set<CausalKind>
        get() = when (this) {
            STRIKE, AMBIENT, ACT -> emptySet()
            GAIT -> setOf(STRIKE, GAIT)
            RESTRICT -> setOf(GAIT)
            WET -> setOf(AMBIENT, WET)
            TEMP -> setOf(AMBIENT, WET, TEMP)
            FEEL -> setOf(WET, TEMP, FEEL)
            LIGHT -> setOf(AMBIENT)
            FATIGUE -> setOf(ACT, FATIGUE)
            RECOVER -> setOf(FATIGUE, RECOVER)
        }

    val isRoot: Boolean get() = parents.isEmpty()

    fun mayWrite(dim: CausalDim): Boolean = dim in writes

    companion object {
        const val COUNT = 11
    }
}
