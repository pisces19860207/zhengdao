// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百七十批 · 神轨参数域（可进化层）＋锚点配置项。
// 规格来源：素材库/01-沦陷链 v2.0（10 条）＋02-欲望图谱（3 条）；
// 行为基准：证道设计报告/附件-角色轨状态机/character_track.py（照翻，未重新设计）。
// 自进化边界（派工单追加）：数值参数可进 568-A 参数域灰度；状态集/转移拓扑/
// 无排斥三条件/min 双重约束/单向门方向锁死，改动须人批并升主版本。
package com.example.zhengdao.baopu.state.track

/** 规格主版本：拓扑或状态集改动必须升此值（门禁测试钉住现值，改拓扑不升版 ⇒ 红）。 */
const val TRACK_SPEC_MAJOR = 1

/** 最短停留下限：规格「每阶段至少停留 2–4 轮」取下限 2；参数不得低于它。 */
const val MIN_DWELL_FLOOR = 2

/** 显性度上限 Lv4（观测器，只做读数不做锁——锁会互锁死锁，见单向门裁定）。 */
const val SALIENCE_MAX = 4

/** 防守阶段数 S0–S6 共 7 态；进攻阶段数 A–F 共 6 态；接触层共 3 层。 */
const val DEFENDER_STAGES = 7
const val ATTACKER_STAGES = 6
const val CONTACT_LAYERS = 3

/**
 * 可进化数值参数（568-A 参数域）。默认值与 Python 参考实现逐一同源；
 * 映射阈值可进化，但「上限 = min(显性度映射, 阶段映射)」的组合规则锁死在代码里。
 * 非法参数 fail-closed：构造即拒，不带病运行。
 *
 * 御裁⑤（2026-11-17）B 层＝加载期单调校验：阶段↑ ⇒ 允许上限不许降。
 * 非单调表在加载时就被拒，错误信息点名「哪两阶段反了」（阶段名＋两个值）；
 * 引擎另外在 `tick()` 每步兜底钳层（A 层），不靠参数作者自觉。
 */
data class TrackParams(
    val minDwell: Int = MIN_DWELL_FLOOR,
    val window: Int = 3,
    val advanceNeed: Int = 2,
    val retreatNeed: Int = 1,
    val anchorFactor: Double = 0.5,
    val salienceStageMax: Map<Int, Int> = DEFAULT_SALIENCE_STAGE_MAX,
    val salienceAttackerMax: Map<Int, Int> = DEFAULT_SALIENCE_ATTACKER_MAX,
    val salienceLayerMax: Map<Int, Int> = DEFAULT_SALIENCE_LAYER_MAX,
    val attackerCapByStage: Map<Int, Int> = DEFAULT_ATTACKER_CAP_BY_STAGE,
    val layerCapByStage: Map<Int, Int> = DEFAULT_LAYER_CAP_BY_STAGE,
    /**
     * B 层开关：默认开（生产路径恒开，源码有反钉判据）。
     * **只**给判据用来绕过 B 直接喂 A（验证「每步兜底」真的在兜），不许业务代码传 false。
     */
    val monotoneCheck: Boolean = true,
) {
    init {
        require(minDwell >= MIN_DWELL_FLOOR) { "min_dwell 不得低于下限 $MIN_DWELL_FLOOR：$minDwell" }
        require(window >= 1 && advanceNeed >= 1 && retreatNeed >= 1) { "窗口与计数阈值须为正" }
        require(anchorFactor > 0.0 && anchorFactor <= 1.0) { "锚点加速系数须在 (0,1]：$anchorFactor" }
        require(salienceStageMax.keys == (1..SALIENCE_MAX).toSet()) { "显性度→阶段映射须覆盖 Lv1–4" }
        require(salienceAttackerMax.keys == (1..SALIENCE_MAX).toSet()) { "显性度→进攻映射须覆盖 Lv1–4" }
        require(salienceLayerMax.keys == (1..SALIENCE_MAX).toSet()) { "显性度→接触层映射须覆盖 Lv1–4" }
        require(attackerCapByStage.keys == (0 until DEFENDER_STAGES).toSet()) { "阶段→进攻映射须覆盖 S0–S6" }
        require(layerCapByStage.keys == (0 until DEFENDER_STAGES).toSet()) { "阶段→接触层映射须覆盖 S0–S6" }
        require(salienceStageMax.values.all { it in 0 until DEFENDER_STAGES }) { "阶段上限越界" }
        require(salienceAttackerMax.values.all { it in 0 until ATTACKER_STAGES }) { "进攻上限越界" }
        require(salienceLayerMax.values.all { it in 1..CONTACT_LAYERS }) { "接触层上限越界" }
        require(attackerCapByStage.values.all { it in 0 until ATTACKER_STAGES }) { "阶段→进攻上限越界" }
        require(layerCapByStage.values.all { it in 1..CONTACT_LAYERS }) { "阶段→接触层上限越界" }
        if (monotoneCheck) {
            requireStageMonotone("阶段→接触层", layerCapByStage)
            requireStageMonotone("阶段→进攻", attackerCapByStage)
        }
    }

    /** 御裁⑤-B：阶段映射须单调不减；违例即拒，错误信息点名反了的两阶段（阶段名＋两个值）。 */
    private fun requireStageMonotone(name: String, capByStage: Map<Int, Int>) {
        for (stage in 1 until DEFENDER_STAGES) {
            val prev = capByStage.getValue(stage - 1)
            val cur = capByStage.getValue(stage)
            if (cur < prev) {
                throw IllegalArgumentException(
                    "${name}表非单调：${STAGE_NAMES[stage - 1]}→${STAGE_NAMES[stage]} 上限反而降 $prev→$cur",
                )
            }
        }
    }

    companion object {
        /** Lv => 防守阶段上限（规格映射表硬化，与参考实现 SALIENCE_STAGE_MAX 同源）。 */
        val DEFAULT_SALIENCE_STAGE_MAX: Map<Int, Int> = mapOf(1 to 1, 2 to 3, 3 to 4, 4 to 6)

        /** Lv => 进攻阶段上限（A=0..F=5；参考实现 SALIENCE_ATTACKER_MAX）。 */
        val DEFAULT_SALIENCE_ATTACKER_MAX: Map<Int, Int> = mapOf(1 to 1, 2 to 2, 3 to 4, 4 to 5)

        /** Lv => 接触层上限（参考实现 SALIENCE_LAYER_MAX）。 */
        val DEFAULT_SALIENCE_LAYER_MAX: Map<Int, Int> = mapOf(1 to 1, 2 to 2, 3 to 3, 4 to 3)

        /** 补①：进攻链还受防守阶段约束（A-B≈S0-1, C-D≈S2-3, E≈S4, F≈S5-6）。 */
        val DEFAULT_ATTACKER_CAP_BY_STAGE: Map<Int, Int> = mapOf(0 to 1, 1 to 1, 2 to 2, 3 to 3, 4 to 4, 5 to 5, 6 to 5)

        /** 补②：接触层同样双重约束（Lv1 不得越层是硬规则，阶段锁防抢跑）。 */
        val DEFAULT_LAYER_CAP_BY_STAGE: Map<Int, Int> = mapOf(0 to 1, 1 to 1, 2 to 2, 3 to 2, 4 to 3, 5 to 3, 6 to 3)

        val DEFAULT = TrackParams()
    }
}

/**
 * 锚点名单配置项：具体名单待作者配（派工单边界「不硬编码」），默认空。
 * 每阶段 1–3 个（规格：每个角色配置 1–3 个锚点）；名字长度上限 8 字是
 * 一行摘要 token 预算的工程护栏。未配置时摘要回退显示阶段编号（与参考实现一致）。
 */
data class AnchorConfig(val namesByStage: Map<Int, List<String>> = emptyMap()) {
    init {
        require(namesByStage.keys.all { it in 0 until DEFENDER_STAGES }) { "锚点阶段须在 S0–S6" }
        require(namesByStage.values.all { it.size in 1..3 }) { "每阶段锚点须为 1–3 个" }
        require(namesByStage.values.all { names -> names.all { it.isNotEmpty() && it.length <= 8 } }) {
            "锚点名须非空且不超过 8 字"
        }
    }
}