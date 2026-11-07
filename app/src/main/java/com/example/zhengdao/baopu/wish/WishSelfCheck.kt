// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十批 · 许愿流水线 §一⑤：自检——按②验收机械判定。
// 模型可参谋（给理由/风险），不许给自己的产出发通过（判据⑤）。
// 纯函数，无 IO；四类产物各有机械判据（派工单 §六/§七/§九）。
package com.example.zhengdao.baopu.wish

/** 自检结论（闭集；Pass 只从机械判定来，不从模型来）。 */
sealed interface SelfCheckVerdict {
    data class Pass(val detail: String) : SelfCheckVerdict
    data class Fail(val reason: String) : SelfCheckVerdict
    /** 模型试图给自己发票 ⇒ 拦并记账（判据⑤）。 */
    data class ModelPassBlocked(val modelClaim: String) : SelfCheckVerdict
}

/** 模型参谋意见（只供参考，不能当通过票）。 */
data class ModelAdvice(val text: String, val claimsPass: Boolean = false)

/** 技能试跑读数（从 RehearsalReport 提取的机械事实）。 */
data class SkillTrialReading(
    val allStepsOk: Boolean,
    val realUntouched: Boolean,
    val sampleMatch: Boolean,
)

/** 卡片校验读数（从 CardFormat/CardValidate 提取的机械事实）。 */
data class CardCheckReading(
    val formatRecognized: Boolean,
    val formValid: Boolean,
    val deterministic: Boolean,
)

/** 内容校验读数（从 OutputCitationGate/台账查重提取的机械事实）。 */
data class ContentCheckReading(
    val citationCount: Int,
    val wordCount: Int,
    val minWords: Int,
    val maxWords: Int,
    val duplicateCount: Int,
)

/** 小说校验读数（从 NovelProjectStore 提取的机械事实）。 */
data class NovelCheckReading(
    val hasOutline: Boolean,
    val hasChapters: Boolean,
    val hasBody: Boolean,
    val newCharacterNames: List<String>,
    val oldChapterChanged: Boolean,
    val chapterWordsInRange: Boolean,
    /** 项目目录是否符合既有 `novel_project` 规范（§九⑤）；由 IO 壳按既有常量判，本件不另立词表。 */
    val dirConventionsOk: Boolean,
)

/** 机械自检（唯一判据入口；模型参谋不能替代机械判定）。 */
internal object WishSelfCheck {

    /** 模型给自己发票 ⇒ 拦（判据⑤：不许给自己的产出发通过）。 */
    fun guardModelPass(advice: ModelAdvice?): SelfCheckVerdict? {
        if (advice == null || !advice.claimsPass) return null
        return SelfCheckVerdict.ModelPassBlocked(advice.text)
    }

    /** 技能：跑一次成功＋样例一致＋真文件不变（派工单 §七①）。 */
    fun checkSkill(r: SkillTrialReading): SelfCheckVerdict {
        if (!r.allStepsOk) return SelfCheckVerdict.Fail("试跑有步骤失败")
        if (!r.realUntouched) return SelfCheckVerdict.Fail("真文件被改动（hash 变了）")
        if (!r.sampleMatch) return SelfCheckVerdict.Fail("输入输出样例不一致")
        return SelfCheckVerdict.Pass("试跑全通＋样例一致＋真文件不变")
    }

    /** 卡片：认脸＋必填齐＋确定性（派工单 §七②）。 */
    fun checkCard(r: CardCheckReading): SelfCheckVerdict {
        if (!r.formatRecognized) return SelfCheckVerdict.Fail("CardFormat 认不出格式")
        if (!r.formValid) return SelfCheckVerdict.Fail("CardValidate.planOf 有必填/类型错")
        if (!r.deterministic) return SelfCheckVerdict.Fail("同 spec 两次 build 不一致")
        return SelfCheckVerdict.Pass("认脸通过＋必填齐＋两次 build 一致")
    }

    /** 内容：依据非空＋字数结构＋台账查重（派工单 §七③）。 */
    fun checkContent(r: ContentCheckReading): SelfCheckVerdict {
        if (r.citationCount <= 0) return SelfCheckVerdict.Fail("依据为空（465 闸）")
        if (r.wordCount < r.minWords || r.wordCount > r.maxWords) {
            return SelfCheckVerdict.Fail("字数 ${r.wordCount} 不在 ${r.minWords}–${r.maxWords} 区间")
        }
        if (r.duplicateCount > 0) return SelfCheckVerdict.Fail("台账查重命中 ${r.duplicateCount} 条")
        return SelfCheckVerdict.Pass("依据非空＋字数合规＋台账无重复")
    }

    /** 小说：三级齐＋人物名一致＋续写只追加＋目录合规（派工单 §九 ①②④⑤）。 */
    fun checkNovel(r: NovelCheckReading): SelfCheckVerdict {
        if (!r.hasOutline || !r.hasChapters || !r.hasBody) {
            return SelfCheckVerdict.Fail("三级结构不齐（大纲→章→正文）")
        }
        if (r.newCharacterNames.isNotEmpty()) {
            return SelfCheckVerdict.Fail("新造未登记人物名：${r.newCharacterNames.joinToString("、")}")
        }
        if (r.oldChapterChanged) return SelfCheckVerdict.Fail("旧章被改（续写只许追加）")
        if (!r.chapterWordsInRange) return SelfCheckVerdict.Fail("章节字数不在区间内")
        if (!r.dirConventionsOk) return SelfCheckVerdict.Fail("项目目录不合既有 novel_project 规范")
        return SelfCheckVerdict.Pass("三级齐＋人物名一致＋只追加＋字数合规＋目录合规")
    }

    /** 按产物类型路由到对应机械判定。 */
    fun check(
        product: WishProduct,
        skill: SkillTrialReading? = null,
        card: CardCheckReading? = null,
        content: ContentCheckReading? = null,
        novel: NovelCheckReading? = null,
    ): SelfCheckVerdict = when (product) {
        WishProduct.SKILL -> skill?.let { checkSkill(it) } ?: SelfCheckVerdict.Fail("缺技能试跑读数")
        WishProduct.CARD -> card?.let { checkCard(it) } ?: SelfCheckVerdict.Fail("缺卡片校验读数")
        WishProduct.CONTENT -> content?.let { checkContent(it) } ?: SelfCheckVerdict.Fail("缺内容校验读数")
        WishProduct.NOVEL -> novel?.let { checkNovel(it) } ?: SelfCheckVerdict.Fail("缺小说校验读数")
    }
}