// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百六十六批 · 体表地形：一条地形怎么写。
//
// 为什么要有这一批：830 只说「挺胸时显不显」，848 只说「形状是哪一型」——
// **没人说哪凸、哪凹、哪里是沟、前后多厚、左右差多少**。前两单回答「像谁」，
// 这一单回答「摸上去是什么起伏」。三者互不替代：形状可以细而薄，厚度可以厚而平。
//
// 四条边界（与 805／848 同口径，机器可判）：
// ① 不读真人影像——本包零 IO（门禁见 TopoSourceAssertTest）；
// ② 不反推尺寸——没有长度／围度／角度字段，也不接受百分比；只有档序；
// ③ 不做诊断、不做评价——档名只描述起伏，不写「异常」「标准」这类词；
// ④ 不改 848／830——形状归 848、挺胸归 830，本包只记常态下的起伏与厚度。
package com.example.zhengdao.baopu.state.topography

/** 本单唯一来源口径。对外每条结论都引用它。 */
const val TOPO_SOURCE: String =
    "派工单-2026-11-23-第八百六十六批（体表地形）＋本项目工程假设"

/** 口径成形日。 */
const val TOPO_AS_OF: String = "2026-11-23"

/**
 * 三族。派工单「四件」里的前三件各成一族；第四件「不对称」不是一族——
 * 它是**左右两笔之间的差**，落在每条读数上，所以不另立一族（见 [Topography.gapOf]）。
 */
enum class TopoFamily(val label: String) {
    RELIEF("凸起"),
    HOLLOW("凹陷"),
    DEPTH("厚度"),
    ;

    companion object {
        const val COUNT = 3
    }
}

/**
 * 一维地形。三族各一套枚举，但取档、比对、出口都按这张统一面孔走。
 *
 * @param lateral 分左右两侧。false＝中轴维（腹直肌沟、胸厚、腹厚、臀厚），只记一笔
 */
interface TopoDim {
    val label: String
    val family: TopoFamily
    val lateral: Boolean
}

/**
 * 档位共契约。`level` 只表先后，**不是任何物理量**——没有长度、没有角度、没有比值。
 */
interface TopoGrade {
    val level: Int
    val label: String
}

// ── ① 凸起（0–3）─────────────────────────────────────────────────────

/**
 * 凸起维。派工单 §① 五项。
 *
 * @param bony 这一维是不是骨性隆起。骨性隆起在脂多时会被盖住（判据②的落点）；
 *   腹直肌沟是**沟**不是隆起（脂多反而更深），斜方肌坡是**坡**不是点状隆起，两者都不算 bony。
 */
enum class ReliefDim(
    override val label: String,
    override val lateral: Boolean,
    val bony: Boolean,
) : TopoDim {
    SCAPULA("肩胛骨", true, true),
    RIBS("肋骨轮廓", true, true),
    RECTUS_GROOVE("腹直肌沟", false, false),
    TRAP_SLOPE("斜方肌坡", true, false),
    ILIAC_CREST("髋骨外凸", true, true),
    ;

    override val family: TopoFamily get() = TopoFamily.RELIEF

    companion object {
        const val COUNT = 5
    }
}

/** 凸起档 0–3。零档是「不显」——不是「没有该结构」，是这一面看不出来。 */
enum class ReliefGrade(override val level: Int, override val label: String) : TopoGrade {
    NONE(0, "不显"),
    FAINT(1, "隐约"),
    CLEAR(2, "清楚"),
    PRONOUNCED(3, "分明"),
    ;

    companion object {
        const val COUNT = 4
        const val LAST = 3

        fun of(level: Int): ReliefGrade = entries[level.coerceIn(0, LAST)]
    }
}

// ── ② 凹陷（0–3）─────────────────────────────────────────────────────

/** 凹陷维。派工单 §② 五项，全部分左右。 */
enum class HollowDim(override val label: String) : TopoDim {
    SUPRACLAVICULAR("锁骨上窝"),
    AXILLA("腋窝"),
    INGUINAL("腹股沟"),
    NECK_SIDE("颈侧"),
    WAIST_SIDE("腰侧"),
    ;

    override val family: TopoFamily get() = TopoFamily.HOLLOW

    override val lateral: Boolean get() = true

    companion object {
        const val COUNT = 5
    }
}

/**
 * 凹陷档 0–3。零档「不凹」是**平**，不是「没有窝」：锁骨上窝人人都有，
 * 不凹说的是它被填平了看不见。这一档与「浅」必须分开——脂多的人写「浅」等于说还看得见一点。
 */
enum class HollowGrade(override val level: Int, override val label: String) : TopoGrade {
    NONE(0, "不凹"),
    SHALLOW(1, "浅"),
    MID(2, "中"),
    DEEP(3, "深"),
    ;

    companion object {
        const val COUNT = 4
        const val LAST = 3

        fun of(level: Int): HollowGrade = entries[level.coerceIn(0, LAST)]
    }
}

// ── ③ 厚度（薄／中／厚）─────────────────────────────────────────────────

/** 厚度维。派工单 §③ 五项。中轴三维记整体厚度，下肢两维分左右。 */
enum class DepthDim(override val label: String, override val lateral: Boolean) : TopoDim {
    CHEST("胸厚", false),
    ABDOMEN("腹厚", false),
    GLUTEAL("臀厚", false),
    THIGH_FRONT("大腿前凸", true),
    CALF_BACK("小腿肚后凸", true),
    ;

    override val family: TopoFamily get() = TopoFamily.DEPTH

    companion object {
        const val COUNT = 5
    }
}

/**
 * 厚度档。派工单给薄／中／厚三档，与凸起／凹陷的 0–3 **不共用**档位枚举：
 * 厚薄是「前后多深」，凸凹是「表面多起伏」，两件事共用一个枚举会把「厚而平」写成「凸」。
 */
enum class DepthGrade(override val level: Int, override val label: String) : TopoGrade {
    THIN(0, "薄"),
    MID(1, "中"),
    THICK(2, "厚"),
    ;

    companion object {
        const val COUNT = 3
        const val LAST = 2

        fun of(level: Int): DepthGrade = entries[level.coerceIn(0, LAST)]
    }
}

// ── ④ 常态不对称的原因 ────────────────────────────────────────────────

/**
 * 常态不对称的原因。派工单 §④ 点名的三种：惯用侧、旧伤、训练。
 *
 * 只在这三种里选，不许自造第四种（「天生」「角度问题」都不是事实层能证的东西）。
 * 差一档以内**不需要**原因——人本来就很少完全对称，处处要原因等于把常态当缺陷。
 */
enum class AsymCause(val label: String) {
    HANDEDNESS("惯用侧"),
    OLD_INJURY("旧伤"),
    TRAINING("训练"),
    ;

    companion object {
        const val COUNT = 3
    }
}
