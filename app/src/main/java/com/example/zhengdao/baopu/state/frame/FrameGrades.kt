// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百六十五批 · 骨点锚：一处骨性标志怎么写。
//
// 为什么要有这一批：869 的外观卡有十处骨点引用位却全是 pending；866 说「骨性隆起会被脂盖住」，
// 但没有一张表回答「哪些点能看见、能摸到、从哪一面看」——下游只能各写各的。
// 本单只交付**骨点**：能看见或能摸到的表面骨性标志，每处三项齐。
//
// 五条边界（含 2026-11-24 四条补丁，机器可判）：
// ① 只收 10–14 个可观察点，不做骨骼网格、不做空间坐标；
// ② 补丁一：骨段与关节归 900——本包不建骨段表、不建关节档，同一样东西只许一份；
// ③ 补丁二：体脂/骨架枚举直接读 815 的真枚举，不自造；
// ④ 禁数值：visible 的 0–3 是档序，不是尺寸、比例或体脂率；
// ⑤ 零装配：不产工具、不进闸、不写库，不改 660/815/869；
// ⑥ 补丁五/六：每点带 zoneId/zoneName，zoneId 与 subzoneId 分字段；子区只认 775 的 14 code；
// ⑦ 补丁八：报告逐点列引用方并给 ≥3 条下游示例。
package com.example.zhengdao.baopu.state.frame

/** 本单唯一来源口径。对外每条结论都引用它。 */
const val FRAME_SOURCE: String =
    "派工单-2026-11-24-第八百六十五批（骨性标志·骨点锚）＋四条补丁＋本项目工程假设"

/** 口径成形日。 */
const val FRAME_AS_OF: String = "2026-11-24"

/**
 * 补丁五：不属于 13 区词表的骨点统一记 `none` + 人读名。
 * `none` 不是「没有区」，是「本单不把它划进 13 区 genital 词表」。
 */
const val FRAME_ZONE_NONE: String = "none"

/** 允许的人读名（补丁五点名三档），不自造「髋区」「骨区」。 */
const val ZONE_TRUNK: String = "躯干"
const val ZONE_LEG_FOOT: String = "腿/足"
const val ZONE_HEAD: String = "头"

/** 骨点分群。只分组，不推断空间位置。 */
enum class FrameRegion(val label: String) {
    UPPER("上身"),
    LOWER("下身"),
    HEAD_NECK("头颈"),
    ;

    companion object {
        const val COUNT = 3
    }
}

/**
 * 点位浅深：这个骨点离表面是浅、中、深。
 *
 * 这是**点位属性**，不是任何物理量；只用于从体脂档推可见度与可触及档。
 */
enum class FrameExposure(val label: String) {
    SUPERFICIAL("浅表"),
    MID("中等"),
    DEEP("深位"),
    ;

    companion object {
        const val COUNT = 3
    }
}

/**
 * 可见档 0–3。`level` 只是档序；不是百分比、不是清晰度测量值。
 *
 * 0＝不显（被覆盖，不等于结构不存在）；3＝分明（一眼就能指出）。
 */
enum class VisibleGrade(val level: Int, val label: String) {
    HIDDEN(0, "不显"),
    FAINT(1, "隐约"),
    CLEAR(2, "清楚"),
    PRONOUNCED(3, "分明"),
    ;

    companion object {
        const val COUNT = 4
        const val LAST = 3

        fun of(level: Int): VisibleGrade = entries[level.coerceIn(0, LAST)]
    }
}

/** 可触及档 0–3。摸得到不等于看得见，两本账分开记。 */
enum class PalpableGrade(val level: Int, val label: String) {
    HARD(0, "难及"),
    FAINT(1, "隐约"),
    CLEAR(2, "清楚"),
    EASY(3, "易及"),
    ;

    companion object {
        const val COUNT = 4
        const val LAST = 3

        fun of(level: Int): PalpableGrade = entries[level.coerceIn(0, LAST)]
    }
}

/** 锚示例的档数。这里是**相差几档**，不是长度。 */
enum class AnchorStep(val label: String) {
    ONE("一档"),
    TWO("两档"),
    ;

    companion object {
        const val COUNT = 2
    }
}

/** 三态判定。[severity] 越大越严；取最严时用 severity，不靠声明次序。 */
enum class FrameVerdict(val label: String, val severity: Int) {
    PASS("通过", 0),
    HINT("提示", 1),
    DENY("红", 2),
    ;

    companion object {
        fun worst(a: FrameVerdict, b: FrameVerdict): FrameVerdict =
            if (a.severity >= b.severity) a else b
    }
}
