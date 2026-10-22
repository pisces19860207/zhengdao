// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百二十二批 · 政策表 v1：**只读、typed、版本化**的政策数据模型。
//
// 这张表只装「参数」，不装「权力」：
// - 工具组默认可见只许在 core / edit / compat 里选，且必须含 core 与 compat；
// - 风险分级只许往上抬，不许把 HIGH 降到 MEDIUM、把 MEDIUM 降到 LOW（[riskFloor]）；
// - L3 自动口、Guard / 权限 / 确认 / 越界拒绝**不在这张表里**（它们是代码，不是数据）。
//
// 类型与范围在构造器里就钉住（非法状态构造不出来），读表器 [PolicyTable] 再补一层
// 「JSON 里的类型是不是写对了」的检查；两层都过才允许进 [PolicyRuntime]。
package com.example.zhengdao.baopu.policy

import com.example.zhengdao.baopu.EvolutionRisk
import com.example.zhengdao.baopu.EvolutionRiskCategory
import com.example.zhengdao.baopu.agent.ToolGroup

/**
 * 一张政策表。
 *
 * [version] 是**表格式版本**（不是 App 版本）：读表器只认 [VERSION]；
 * 将来加字段/改语义必须先升版本、同时改读表器，不能靠「多一个键也没事」蒙混。
 * 因此 [PolicyTable] 对未知键一律拒绝——这不是配置，不走「宽松解析」。
 */
internal data class Policy(
    val version: Int,
    val toolGroups: ToolGroupPolicy,
    val approval: ApprovalPolicy,
    val eval: EvalPolicy,
) {
    /** 类别 → 生效风险等级（表值再抬也不许低于代码里的安全底线）。 */
    fun riskOf(category: EvolutionRiskCategory): EvolutionRisk =
        maxOf(approval.riskLevels[category] ?: category.riskClass, riskFloor(category))

    companion object {
        /** 表格式标识；写成别的串就不是本项目的政策表。 */
        const val SCHEMA = "zhengdao-policy"

        /** 本版只认这一个版本号。 */
        const val VERSION = 1

        /** 组闸默认 N（第九十七批原值）：被拒后攒几次申请再弹。 */
        const val GROUP_REASK_THRESHOLD = 3

        /** eval 过线默认值（第八十八批原值）。 */
        const val EVAL_FLOOR = 0.8

        /** 效果样本下限（第八十八批原值）。 */
        const val MIN_EFFECT_SAMPLES = 3

        /**
         * 表缺失/非法时的**内置默认**：与本批落盘的 `policy_v1.json` 逐字段一致。
         *
         * 为什么默认值必须在代码里也有：表被删、读坏、版本不认时只能 fail closed，
         * 而 fail closed 的落点就是这份默认；它不许比落盘表更宽。
         */
        val DEFAULT: Policy = Policy(
            version = VERSION,
            toolGroups = ToolGroupPolicy(
                defaultVisible = setOf(ToolGroup.CORE, ToolGroup.EDIT, ToolGroup.COMPAT),
            ),
            approval = ApprovalPolicy(
                groupReaskThreshold = GROUP_REASK_THRESHOLD,
                riskLevels = EvolutionRiskCategory.entries.associateWith { it.riskClass },
            ),
            eval = EvalPolicy(
                floor = EVAL_FLOOR,
                minEffectSamples = MIN_EFFECT_SAMPLES,
            ),
        )

        /**
         * 代码里的安全底线：表可以把风险往上抬，不能往下压。
         *
         * 人设核心/工具权限/系统层永远是 HIGH；结构类改动至少 MEDIUM。
         * 这条判据在 [ApprovalPolicy] 的构造器与 [EvolutionPolicy] 的判定里都会用，
         * 不依赖读表器有没有记住——**纵深防御，不是重复校验**。
         */
        fun riskFloor(category: EvolutionRiskCategory): EvolutionRisk = when (category) {
            EvolutionRiskCategory.PERSONA_CORE,
            EvolutionRiskCategory.TOOL_PERMISSION,
            EvolutionRiskCategory.ROOTFS_SYSTEM,
            -> EvolutionRisk.HIGH

            EvolutionRiskCategory.SKILL_BODY_STRUCTURE,
            EvolutionRiskCategory.CROSS_SKILL_IMPACT,
            -> EvolutionRisk.MEDIUM

            EvolutionRiskCategory.PROMPT_TWEAK,
            EvolutionRiskCategory.SKILL_BODY_MINOR,
            -> EvolutionRisk.LOW
        }
    }
}

/**
 * 工具组的默认可见政策。
 *
 * **锁定策略不在表里**：未默认可见的组仍由 `capabilities` 按会话申请、人点卡授权；
 * 表只决定「一上来就看得见谁」。允许范围是 [ALLOWED] 的子集，且必须含 [REQUIRED]：
 * - 缺 CORE ⇒ `capabilities` 自己也看不见，模型永远解不开别的组（自我锁死）；
 * - 缺 COMPAT ⇒ 免费通道的 tools 数组少两条占位，服务端直接 403。
 *
 * EDIT 可以在表里去掉（这是收紧，不是放宽），所以不放进 [REQUIRED]。
 */
internal data class ToolGroupPolicy(
    val defaultVisible: Set<ToolGroup>,
) {
    init {
        require(defaultVisible.isNotEmpty()) { "defaultVisible 不许为空" }
        val illegal = defaultVisible - ALLOWED
        require(illegal.isEmpty()) {
            "defaultVisible 含不许默认可见的组：${illegal.joinToString("、") { it.key }}" +
                "（只许 ${ALLOWED.joinToString("/") { it.key }}）"
        }
        val missing = REQUIRED.filterNot { it in defaultVisible }
        require(missing.isEmpty()) {
            "defaultVisible 必须含 ${missing.joinToString("、") { it.key }}" +
                "（core 缺了 capabilities 看不见；compat 缺了免费档 403）"
        }
    }

    companion object {
        /** 表能碰的默认可见组（也是代码允许默认可见的全集）。 */
        val ALLOWED: Set<ToolGroup> = setOf(ToolGroup.CORE, ToolGroup.EDIT, ToolGroup.COMPAT)

        /** 缺了就会把系统弄坏（或让免费档 403）的两组。 */
        val REQUIRED: Set<ToolGroup> = setOf(ToolGroup.CORE, ToolGroup.COMPAT)
    }
}

/**
 * 审批政策：组闸阈值 ＋ 风险分级映射。
 *
 * [riskLevels] 必须**七类齐全**：缺一类就等于「这类没政策」，读表器不允许；
 * 每一级再受 [Policy.riskFloor] 约束，只能更严。
 */
internal data class ApprovalPolicy(
    val groupReaskThreshold: Int,
    val riskLevels: Map<EvolutionRiskCategory, EvolutionRisk>,
) {
    init {
        require(groupReaskThreshold in 1..10) {
            "groupReaskThreshold 越界：$groupReaskThreshold（只许 1–10）"
        }
        require(riskLevels.keys == EvolutionRiskCategory.entries.toSet()) {
            "riskLevels 必须七类齐全，当前只有 ${riskLevels.keys.joinToString("、") { it.name }}"
        }
        riskLevels.forEach { (category, level) ->
            val floor = Policy.riskFloor(category)
            require(level >= floor) {
                "${category.name} 的风险等级 $level 低于代码底线 $floor——政策表只能收紧，不能放宽"
            }
        }
    }
}

/**
 * eval / 效果阈值。
 *
 * 范围比 `EvolutionPolicy` 的构造器更窄（那里允许 0–1、≥1 是给单测注入用的；
 * 政策表是**发版数据**，不能把过线值写成 0.1 来让门禁形同虚设）。
 */
internal data class EvalPolicy(
    val floor: Double,
    val minEffectSamples: Int,
) {
    init {
        require(floor in 0.5..1.0) { "eval floor 越界：$floor（只许 0.5–1.0）" }
        require(minEffectSamples in 3..100) {
            "minEffectSamples 越界：$minEffectSamples（只许 3–100）"
        }
    }
}
