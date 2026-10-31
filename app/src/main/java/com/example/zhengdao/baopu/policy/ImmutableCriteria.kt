// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百一十批 · **不可进化清单**（写死，硬拦）——只有用户亲手改才行的那三条。
//
// 为什么这里要一张表加一道闸，而不是一段注释：122 批的 `Policy` 早把话说死了——
// 「L3 自动口、Guard / 权限 / 确认 / 越界拒绝**不在这张表里**（它们是代码，不是数据）」。
// 本批把那句话变成**会红的东西**：三条判据各钉住一处生产锚点，任何「判据草案」想动
// 这三处之一 ⇒ 直接拒（不是警告）。理由很实在：这三条一旦可以进化，下一次「优化」
// 就会把它们磨平成「可配置」，而磨平之后没有任何一道闸会记得自己曾经拦过什么。
//
// 房型照 122／298 批的 `policy` 包：只读、构造期钉死、零 Android / 零 IO / 零时钟
// （`PolicySourceGateTest` 逐文件机检这三条，本文件白拿那道门）。
// 这里也**不读 `PolicyRuntime`**：那条链被钉子测试钉死只许三处执行点读，
// 准入多一个读点就是多给一处权力（同一个测试的「没有第五处」）。
//
// 三条只有一条例外，且例外是**闭集白名单**不是开关：紧急卡的止损四件
// （停机／隔离／回滚／冻结写入）可以不经人批先执行（L30b 御批的超时旁路）。
// 想往白名单里再放一件 ⇒ 得改这张表＝用户立法，不走进化通道。
package com.example.zhengdao.baopu.policy

/**
 * 清单里的一条。每个字段都是**给人读**的：命中时直接把 [cn] 与 [reason] 打进拒绝理由，
 * 不产出任何数字（分数不可争辩，见 [CriteriaEvolution] 的形态校验）。
 *
 * @param anchors 这条判据在生产上钉住的符号（`路径#符号`，可多处）。
 *                候选判据的 target 落在其中任何一处 ⇒ 命中。
 */
internal data class ImmutableCriterion(
    val id: String,
    val cn: String,
    /** 法典依据号：这三条不是本批新造的权力，是既有条文的具体化。 */
    val lawRef: String,
    val anchors: List<String>,
    val reason: String,
)

/** 不可进化清单：三条，闭集。 */
internal object ImmutableCriteria {

    const val DELIVERABLE_ON_DISK = "deliverable-on-disk"
    const val DESTRUCTIVE_CONFIRM = "destructive-confirm"
    const val EMERGENCY_APPROVAL = "emergency-approval"

    /**
     * 紧急卡唯一例外：止损四件的机器名。
     *
     * 用字符串而不是 import `inspect` 那边的枚举，是为了不让 policy 包跟
     * agent／inspect 长出依赖；逐字对齐关系由 `ImmutableCriteriaGateTest` 机检
     * （对齐断了就红，不靠注释）。
     */
    val STOP_LOSS_WHITELIST: Set<String> = setOf(
        "stop_machine",
        "quarantine",
        "rollback",
        "freeze_writes",
    )

    /**
     * 「开洞」词表：草案意图里出现这些词，一律当作它想给某条硬闸装开关。
     *
     * 口径是**收紧**：宁可错拒一条只是措辞撞词的草案（改措辞即可重来），
     * 也不放过一条真把「必须人确认」进化成「可配置」的草案。
     */
    val RELAXATION_MARKERS: Set<String> = setOf(
        "可配置",
        "可开关",
        "开关",
        "免批",
        "跳过确认",
        "自动通过",
        "configurable",
        "toggle",
        "bypass",
        "auto-approve",
    )

    /** 三条的全部家当。顺序固定（门禁按它逐条出检查条目）。 */
    val ALL: List<ImmutableCriterion> = listOf(
        ImmutableCriterion(
            id = DELIVERABLE_ON_DISK,
            cn = "交付物必须真落盘才算完成（不许「声称完成」）",
            lawRef = "L19",
            anchors = listOf(
                "app/src/main/java/com/example/zhengdao/baopu/verify/ArtifactVerifier#check",
                "app/src/main/java/com/example/zhengdao/baopu/authz/TaskGrant#ObjectState",
            ),
            reason = "把「存在且非空」这条实物核验进化掉，等于让模型自述当证据；" +
                "这是法典 L19「宪不可自改」点名不许模型自动改的那一类。",
        ),
        ImmutableCriterion(
            id = DESTRUCTIVE_CONFIRM,
            cn = "破坏性动作必须人确认",
            lawRef = "L16",
            anchors = listOf(
                "app/src/main/java/com/example/zhengdao/baopu/authz/TaskGrantSection#StillAsk.DESTRUCTIVE",
                "app/src/main/java/com/example/zhengdao/baopu/authz/TaskGrantSection#stillAsk",
                "app/src/main/java/com/example/zhengdao/baopu/agent/ConfirmFlow#resumeWithApproval",
            ),
            reason = "「即便有授权仍要问」这张名单一旦可进化，删／移／覆盖就能被预先授权掉；" +
                "2026-10-24 全账户回收站事故之后，这一条是止损线，不是参数。",
        ),
        ImmutableCriterion(
            id = EMERGENCY_APPROVAL,
            cn = "紧急卡必须人批（止损白名单除外）",
            lawRef = "L21",
            anchors = listOf(
                "app/src/main/java/com/example/zhengdao/baopu/law/EmergencyGate#requiresApproval",
                "app/src/main/java/com/example/zhengdao/baopu/law/EmergencyGate#selfRulingRejection",
            ),
            reason = "紧急卡是唯一允许绕过日常节流的上奏口；把它进化成「可自决」＝把熔断器交回给" +
                "正在熔断的那一方。唯一例外是 L30b 的止损四件，白名单见 [STOP_LOSS_WHITELIST]。",
        ),
    )

    /**
     * 路径归一：反斜杠、首尾空白、大小写、`.kt` 后缀的有无，都不算「躲过锚点」。
     *
     * 锚点本体**不写** `.kt`——那串文件名是字符串常量，而法典接线闸按原始文本数
     * `EmergencyGate.` 这类调用点，带后缀会把清单自己算成一处生产接线。但候选判据
     * 把 target 写成 `ArtifactVerifier.kt#check` 时指的是同一处：后缀不许成为逃逸口。
     */
    private fun normalize(anchor: String): String {
        val flat = anchor.trim().replace('\\', '/').lowercase()
        val stem = flat.substringBefore('#').removeSuffix(".kt")
        val symbol = flat.substringAfter('#', "")
        return if (symbol.isEmpty()) stem else "$stem#$symbol"
    }

    /** 候选判据想动的那一处命中清单里的哪一条；没命中返回 null。 */
    fun match(target: String): ImmutableCriterion? {
        val needle = normalize(target)
        if (needle.isEmpty()) {
            return null
        }
        return ALL.firstOrNull { criterion -> criterion.anchors.any { normalize(it) == needle } }
    }

    /** 意图里有没有「开洞」词；命中就返回那个词（拒绝理由要能指到具体措辞）。 */
    fun relaxationMarker(intent: String): String? =
        RELAXATION_MARKERS.firstOrNull { intent.lowercase().contains(it.lowercase()) }

    /** 这份例外是否**完全**落在止损白名单里（多一件都不算例外）。 */
    fun exemptedOnlyByStopLoss(actions: Set<String>): Boolean =
        actions.isNotEmpty() && STOP_LOSS_WHITELIST.containsAll(actions)

    /** 清单自己有没有被改瘦（条数、id 集合、锚点总数三项一起看）。 */
    fun intactIds(): List<String> = ALL.map { it.id }
}
