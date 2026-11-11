// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百九十七批（2026-11-12）：视觉描述的**判定层**（单 597 判据②③的闸）。
//
// 红线在这里说死：
// - `Provider.vision=false` ⇒ [Verdict.NoVision]「该模型看不了图」——**调用方必须一个
//   字节都不发**（负例：假件计数请求次数，必须为 0）；
// - 没批（人没点确认卡、租约也没命中）⇒ [Verdict.NotApproved]「只出本地特征」——
//   外发的唯一通道是 portrait_appearance（CONFIRM）：管线第③段末尾过 467 租约闸，
//   命中免卡、未命中弹卡，**没有第三条路**（本层再拦一道，防绕过管线的调用方）。
//
// 纯数据＋纯函数：零 IO、零 Android、零时钟；文案在本层（与 VisionConsent 同口径）。
package com.example.zhengdao.baopu.portrait

internal object PortraitDescribeGate {

    /** 视觉描述的长度上限（单 597 ②：≤120 字）。超了截断，不加"…"之类的自作主张。 */
    const val MAX_DESC_CHARS = 120

    /** 外发提示词（数据放判据层：改口径只改这里，测试逐字断言）。 */
    const val PROMPT: String =
        "看这张立绘，用不超过120个字客观描述人物外貌：发色、瞳色、服饰、体型。" +
            "只写看得见的，不评价、不编造、不写画风术语。"

    /** 无视觉能力时的回话（判据②负例的期望文案，逐字）。 */
    const val NO_VISION_NOTE: String = "该模型看不了图：未发起任何请求。"

    /** 没批时的回话（判据③负例的期望文案，逐字）。 */
    const val NOT_APPROVED_NOTE: String = "没批外发：只出本地特征。"

    /** 一次判定的结论。 */
    sealed interface Verdict {
        /** 能不能发起视觉请求（唯一的外发许可来源）。 */
        val allowed: Boolean

        /** 给模型的回话；[Allow] 为空串。 */
        val note: String

        /** 能力有＋已批 ⇒ 可以发起唯一一次视觉请求。 */
        data object Allow : Verdict {
            override val allowed: Boolean get() = true
            override val note: String get() = ""
        }

        /** 模型无视觉能力：**不许发请求**。 */
        data class NoVision(override val note: String = NO_VISION_NOTE) : Verdict {
            override val allowed: Boolean get() = false
        }

        /** 人没批（卡被拒/租约没命中）：**不许发请求**，只出本地特征。 */
        data class NotApproved(override val note: String = NOT_APPROVED_NOTE) : Verdict {
            override val allowed: Boolean get() = false
        }
    }

    /**
     * 唯一判定口（顺序即法条：先能力、后批准——能力都没有，"批准"无从谈起）。
     *
     * @param visionSupported 只读 [com.example.zhengdao.baopu.Provider.vision]（V5：不许自己猜）
     * @param approved 人批了没有：CONFIRM 管线放行（点卡或 467 租约命中）⇒ true
     */
    fun decide(visionSupported: Boolean, approved: Boolean): Verdict = when {
        !visionSupported -> Verdict.NoVision()
        !approved -> Verdict.NotApproved()
        else -> Verdict.Allow
    }

    /** ≤120 字硬闸：按 UTF-16 码元截断（中文一字一码元； surrogate 对截半的风险可忽略——描述是本模型产出的受限文本）。 */
    fun clamp(text: String): String {
        val t = text.trim()
        return if (t.length <= MAX_DESC_CHARS) t else t.take(MAX_DESC_CHARS)
    }
}
