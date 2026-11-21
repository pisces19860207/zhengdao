// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百四十一批 · 形轨.空间容纳与受限姿态：三态与受限原因。
// 三态只有三个名字：进得去／勉强／进不去；受限原因是一张闭集表（十种），
// 不许临时拼新词——用户看到的是「哪一档不够」，不是一条算出来的数。
package com.example.zhengdao.baopu.state.containment

/**
 * 三态结论。
 *
 * - [FIT] 进得去：档位够、姿态过 655 四项、目标也够得着，三样同时成立才给。
 * - [MARGINAL] 勉强：差一档，或有一项没证明（可能靠调整姿态将就）。
 * - [NOFIT] 进不去：差两档以上，或撞上硬冲突（开口／朝向／合法性／够不着）。
 */
enum class ContainmentState(val label: String) {
    FIT("进得去"),
    MARGINAL("勉强"),
    NOFIT("进不去"),
}

/**
 * 受限原因闭集。
 *
 * [hard] 是这张表的判定语义：硬原因一出现，不管档位还剩多少余量都只能判「进不去」；
 * 软原因（差一档、未证明）只把结论压到「勉强」。
 */
enum class LimitReason(val label: String, val hard: Boolean) {
    /** 高度比这一档站位要的矮一档（或更多）。 */
    HEIGHT_SHORT("高度不够", false),

    /** 宽度比这一档站位要的窄一档：开口太窄。 */
    OPENING_NARROW("开口太窄", false),

    /** 进深比这一档站位要的浅一档。 */
    DEPTH_SHORT("进深不够", false),

    /** 高度差一档，但这一档站位弯得下腰：写成「需弯腰」比「高度不够」更贴近实情。 */
    NEEDS_BEND("需弯腰", false),

    /** 开口位置与这一档站位不相容（例如只有侧开口却要正对站着）。 */
    OPENING_POSITION_CONFLICT("开口位置不合", true),

    /** 朝向与这一档站位不相容（例如背对着开口还想伸手进去）。 */
    FACING_CONFLICT("朝向不合", true),

    /** 655 判姿态不合法：限位／自碰撞／穿地里至少一项不过。 */
    LEGALITY_FAILED("姿态不合法", true),

    /** 655 有一项没判出结论（未证明）：不敢说站得住。 */
    LEGALITY_UNPROVEN("姿态合法性未证明", false),

    /** 够不着：目标超出这一档的手臂可达范围。空间够也不等于干得了活。 */
    REACH_SHORT("够不着", true),

    /** 够不够得着没判出来。 */
    REACH_UNPROVEN("够不着与否未证明", false),
}
