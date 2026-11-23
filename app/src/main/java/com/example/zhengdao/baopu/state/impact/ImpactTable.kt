// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百二十七批 · 三段映射分段表（判据①）。
// 冲击档 = 速度档 × 质量档 ÷ 接触面积档 的**分段表**，不是连续公式：
//   段一 ENERGY[speed][mass] → 能量段 0..9；
//   段二 SPREAD[area] 为分母段 1..4，查 DIVIDED[energy][area] → 商段 0..9；
//   段三 BAND[tier] 给出商段到 0..4 的分段边界，TIER_OF[商段] 直接查表。
// 全表只有整数索引，无乘除表达式、无浮点、无距离/角度/牛顿等数值。
package com.example.zhengdao.baopu.state.impact

import com.example.zhengdao.baopu.state.cavity.NormalBucket

/** 段一：速度(0..4) × 质量(0..3) → 能量段 0..9。全表钉死，单调不降。 */
internal val ENERGY: Array<IntArray> = arrayOf(
    intArrayOf(0, 0, 1, 1), // 速度 0
    intArrayOf(0, 1, 2, 2), // 速度 1
    intArrayOf(1, 2, 3, 4), // 速度 2
    intArrayOf(2, 3, 5, 6), // 速度 3
    intArrayOf(3, 4, 7, 9), // 速度 4
)

/** 段二分母：接触面积 0..3 → 1/2/3/4（点→包覆），只作查表索引，不参与表达式。 */
internal val SPREAD: IntArray = intArrayOf(1, 2, 3, 4)

/**
 * 段二结果：商段 = 能量段「÷」面积段后的**查表值**（0..9）。
 * 行＝能量段 0..9，列＝面积段 0..3；商随面积增大而不增（判据①面积单调）。
 */
internal val DIVIDED: Array<IntArray> = arrayOf(
    intArrayOf(0, 0, 0, 0),
    intArrayOf(1, 0, 0, 0),
    intArrayOf(2, 1, 0, 0),
    intArrayOf(3, 1, 1, 0),
    intArrayOf(4, 2, 1, 1),
    intArrayOf(5, 2, 2, 1),
    intArrayOf(6, 3, 2, 2),
    intArrayOf(7, 4, 2, 2),
    intArrayOf(8, 4, 3, 2),
    intArrayOf(9, 5, 3, 3),
)

/** 段三：商段 0..9 → 冲击档 0..4，逐段可查、非降。 */
internal val TIER_OF: IntArray = intArrayOf(0, 0, 1, 1, 2, 2, 3, 3, 4, 4)

/** 段三的可读分段边界（给报告/判据引用，也是负例自检的靶）。 */
internal val BAND: Array<IntArray> = arrayOf(
    intArrayOf(0, 1), // 冲击档 0：商段 0..1
    intArrayOf(2, 3), // 冲击档 1：商段 2..3
    intArrayOf(4, 5), // 冲击档 2：商段 4..5
    intArrayOf(6, 7), // 冲击档 3：商段 6..7
    intArrayOf(8, 9), // 冲击档 4：商段 8..9
)

/** 位移档 0–3：准静态，冲击档查表。 */
internal val DISPLACE_OF: IntArray = intArrayOf(0, 0, 1, 2, 3)

/** 压扁档 0–3：由接触面积与冲击档共同定（面积越大越压得开，但同冲击不反超）。 */
internal val FLATTEN_OF: Array<IntArray> = arrayOf(
    intArrayOf(0, 1, 2, 3, 3),
    intArrayOf(0, 1, 2, 2, 3),
    intArrayOf(0, 0, 1, 2, 2),
    intArrayOf(0, 0, 1, 1, 2),
)

/** 恢复档 0–3：冲击档查表；时间夹具另按 budgetRounds 落回 0。 */
internal val RECOVERY_OF: IntArray = intArrayOf(0, 0, 1, 2, 3)

/** 可观察后果词：只出词，不出诊断；与 805 的 BodyPartGuard 口径一致。 */
internal val WORDS_OF: Array<List<String>> = arrayOf(
    listOf("未见变化"),
    listOf("闷响"),
    listOf("闷响", "一沉"),
    listOf("一沉", "位移", "晃了晃"),
    listOf("短暂停顿", "压痕", "一沉"),
)

/** 唯一查表口：三输入 → 冲击档。越界即抛，不静默夹取。 */
internal object ImpactTable {

    fun impact(speed: SpeedTier, mass: MassTier, area: AreaTier): ImpactTier {
        val energy = ENERGY[speed.level][mass.level]
        val dividend = DIVIDED[energy][SPREAD[area.level] - 1]
        return ImpactTier.of(TIER_OF[dividend])
    }

    fun displace(tier: ImpactTier): DisplaceTier = DisplaceTier.of(DISPLACE_OF[tier.level])

    fun flatten(tier: ImpactTier, area: AreaTier): FlattenTier =
        FlattenTier.of(FLATTEN_OF[area.level][tier.level])

    fun recovery(tier: ImpactTier): RecoveryTier = RecoveryTier.of(RECOVERY_OF[tier.level])

    fun words(tier: ImpactTier): List<String> = WORDS_OF[tier.level]

    /**
     * 时间夹具：轮次达到恢复档预算就落回 0 档；超过最慢档也必为 0。
     * 只推进档位，不做时间积分、不做连续仿真。
     */
    fun recoveryAt(tier: ImpactTier, rounds: Int): RecoveryTier {
        val base = RECOVERY_OF[tier.level]
        val spent = if (base == 0) 0 else RecoveryTier.of(base).budgetRounds
        return if (rounds >= spent) RecoveryTier.NOW else RecoveryTier.of(base)
    }

    /** 同帧两击取更强的一次（判据③负例：不累加成一次巨击）。 */
    fun stronger(first: ImpactTier, second: ImpactTier): ImpactTier =
        if (first.level >= second.level) first else second

    /**
     * 与 655 闭式碰撞的方向口径：被撞体分离方向沿碰撞法向（同向断言）。
     * 这里只取方向档，不算速度、不做积分；方向值直接来自 655 的法向桶。
     */
    fun displacementDirection(normal: NormalBucket): NormalBucket = normal
}
