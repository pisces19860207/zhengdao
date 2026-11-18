// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十一批 · 仿生门（BioAnalogGate）——523「三问门禁」未合入期间的**本地落点**。
//
// 523 在另一会话做（分支 feat/bio-gate，截至本批开工仍在基线 db67e8f3e、未合入）。
// 按派工单 §三：本地先把接口落下来，521 的两件（纹状体固化／岛叶自调）全部从这扇门过；
// 523 合入后**回填替换实现**（回填登记见交付报告 §五——本文件只留接口与形状，
// 三问判据的正式口径以 523 为准，回填时判据实现整件换血、调用点一个字不改）。
//
// 三问（派工单原话，本地最小落地；不许因 523 未合入而跳过——判据由测试钉住）：
//   ① **可测差异**：任何要过门的行为必须自带"测试怎么观察它"的那一句话（[BioIntent.observable]）。
//      空话不许过门——没有可观察面就没有可测差异，门拒收（Admit 前置条件，判据①）。
//   ② **不越权**：器官 × 动作必须在**白名单闭集**里（[ThreeQuestionGate.WHITELIST]）。
//      纹状体只许建议（建议走既有许愿流水线，永不直写技能）；岛叶只许调四个参数＋一键回默认。
//      白名单外的动作一律拒——包括将来谁想加"改授权档"这类动作（闭集，加项＝改门禁代码，要过批）。
//   ③ **失败兜底**：门自己崩了/拒了 ⇒ 调用方按"没有这件器官"走（拒＝兜底侧，fail-closed）。
//      [GateVerdict] 不携带任何可执行物，Deny 也不抛——调用方拿 Deny 返回默认/跳过，
//      主链路行为与"器官不存在"一致（逐字节口径由 521 的兜底测试钉）。
//
// 纪律：本包零 Android、零 IO、零时钟；不放任何器官业务逻辑——门只问三件事，
// 判据本体在各自包里（habit／insula），门不知道也不想知道。
package com.example.zhengdao.baopu.biogate

/**
 * 一次要过门的行为。四个字段都是**声明**，不是凭证：
 * [organ] 器官名（闭集见 [ThreeQuestionGate.WHITELIST] 的键）；[action] 动作名（闭集见值）；
 * [subject] 动作对象（溯源用，进账行）；[observable] 一句话说明"测试从哪儿观察这次行为"
 * （三问①的可测差异面——空串在构造期就拒，不给"过了门再说"的机会）。
 */
data class BioIntent(
    val organ: String,
    val action: String,
    val subject: String,
    val observable: String,
) {
    init {
        require(observable.isNotBlank()) { "过门必须自带可观察面（三问①：可测差异）" }
        require(organ.isNotBlank() && action.isNotBlank()) { "过门必须声明器官与动作" }
    }
}

/** 过门结论（闭集两态；Admit 不带可执行物——门只放行，不给权力）。 */
sealed interface GateVerdict {

    /** 放行：三问都过。调用方按自己的判据继续，出了事责任在判据不在门。 */
    data object Admit : GateVerdict

    /** 拒收：[why] 一句人话。拒＝兜底侧——调用方按"没有这件器官"走。 */
    data class Deny(val why: String) : GateVerdict
}

/**
 * 仿生门接口（523 合入前的本地形状；`fun interface`——测试夹具可以直接 lambda 一把拒门）。
 *
 * 实现方只需要答一个问题：这扇门放不放。三问怎么问、问几道，是实现自己的事；
 * 调用方只认 [admit] 的结论，不猜实现内部——523 回填时整件换实现，调用点不动。
 */
fun interface BioAnalogGate {

    /** 问一次门。不许抛：门崩了等于全器官瘫——实现方自己 runCatching，落到 Deny。 */
    fun admit(intent: BioIntent): GateVerdict
}

/**
 * 三问门的默认实现（521 本地版；523 合入后由 523 的实现回填替换）。
 *
 * 白名单是**闭集映射**：键＝器官名，值＝该器官允许的动作集。两件器官的动作名：
 *   · 纹状体：SUGGEST_SOLIDIFY（出固化建议卡）、SUBMIT_WISH（人点采纳 ⇒ 递进既有许愿流水线）；
 *   · 岛叶：TUNE_CONCURRENCY／TUNE_BATCH_SIZE／TUNE_RETRY_BACKOFF／TUNE_DEFER_HEAVY（四参数）、
 *     RESET_TUNING（一键回默认）。
 * 白名单外（含未知器官、想加"改授权档"之类）一律 Deny——②不越权。
 * [BioIntent.observable] 空白在构造期已拒，这里再查一道双保险——①可测差异。
 * ③失败兜底是**结构性**的：本门无状态、无 IO、无时钟，唯一的输出是 Admit/Deny 两态；
 * Deny 即"按器官不存在走"，调用方兜底测试逐字节钉住。
 */
internal object ThreeQuestionGate : BioAnalogGate {

    /** 白名单闭集（器官 → 动作集）。改这里＝改门禁，要过批，不许调用方绕。 */
    val WHITELIST: Map<String, Set<String>> = mapOf(
        ORGAN_STRIATUM to setOf(ACTION_SUGGEST, ACTION_SUBMIT_WISH),
        ORGAN_INSULA to setOf(
            ACTION_TUNE_CONCURRENCY,
            ACTION_TUNE_BATCH_SIZE,
            ACTION_TUNE_RETRY_BACKOFF,
            ACTION_TUNE_DEFER_HEAVY,
            ACTION_RESET_TUNING,
        ),
    )

    override fun admit(intent: BioIntent): GateVerdict = runCatching { judge(intent) }
        .getOrElse { GateVerdict.Deny("门判据异常，按兜底走：${it.javaClass.simpleName}") }

    private fun judge(intent: BioIntent): GateVerdict {
        val allowed = WHITELIST[intent.organ]
            ?: return GateVerdict.Deny("未知器官「${intent.organ}」——白名单外，不越权")
        if (intent.action !in allowed) {
            return GateVerdict.Deny("动作「${intent.action}」不在「${intent.organ}」的白名单里——不越权")
        }
        if (intent.observable.isBlank()) {
            return GateVerdict.Deny("可观察面为空——无可测差异，不放行")
        }
        return GateVerdict.Admit
    }

    /** 器官名（与 bio 包登记表的叫法一字不差；门按这个名字认器官）。 */
    const val ORGAN_STRIATUM = "纹状体"
    const val ORGAN_INSULA = "岛叶"

    /** 纹状体的两个动作（出卡／人批采纳后递许愿——转正动作永远是人批，门只放行这两格）。 */
    const val ACTION_SUGGEST = "SUGGEST_SOLIDIFY"
    const val ACTION_SUBMIT_WISH = "SUBMIT_WISH"

    /** 岛叶的五个动作（四参数白名单＋一键回默认；授权档/用户设置/工具白名单不在集里，永远过不了门）。 */
    const val ACTION_TUNE_CONCURRENCY = "TUNE_CONCURRENCY"
    const val ACTION_TUNE_BATCH_SIZE = "TUNE_BATCH_SIZE"
    const val ACTION_TUNE_RETRY_BACKOFF = "TUNE_RETRY_BACKOFF"
    const val ACTION_TUNE_DEFER_HEAVY = "TUNE_DEFER_HEAVY"
    const val ACTION_RESET_TUNING = "RESET_TUNING"
}
