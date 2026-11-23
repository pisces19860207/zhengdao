// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百八十二批 · 宗教职：派工单追加二 §一要求「必须进池，且分教派」，因为教派差异是硬信息。
//
// 全真与正一的区别（可婚不可婚、住观还是火居）比「道士」这个名字信息量大得多；汉传／藏传／南传
// 与天主教／新教、伊斯兰同样各走各的教制。所以 [FaithBranch] 从九条教派起步，
// 「道士／和尚」这种只到名称的写法在结构上就落不了地（只填到 branch 为止，填不出五字段仍会被 C7 报）。
//
// 【口径】婚否／饮食／发式一律以**教义或戒律**为依据（[FaithBasis] 非空不可），
// 不引「普遍印象」；宗教身份也**不是性格或品德**——「清心寡欲」「虔诚朴素」这类描写在本包里是红（C4）。
package com.example.zhengdao.baopu.state.occupation

/** 九条教派／圣职细类。 */
enum class FaithBranch(val label: String, val tradition: String) {
    DAO_QUANZHEN("道教全真", "道教"),
    DAO_ZHENGYI("道教正一", "道教"),
    DAO_SANJU("道教散居道士", "道教"),
    BU_HAN("汉传僧", "佛教"),
    BU_TIBET("藏传（出家与在家瑜伽士）", "佛教"),
    BU_THERAVADA("南传僧（托钵）", "佛教"),
    CATHOLIC_PRIEST("天主教神父", "基督宗教"),
    PROTESTANT_PASTOR("新教牧师", "基督宗教"),
    MUSLIM_AHONG("伊斯兰阿訇", "伊斯兰"),
    ;

    companion object {
        const val COUNT = 9
    }
}

/** 婚否档：教制事实，不是对个人选择的评判。 */
enum class Celibate(val label: String) {
    REQUIRED("依教制须独身不婚"),
    PERMITTED("可婚"),
    MONASTIC_ONLY("出家者须独身，在家者不限"),
    ;

    companion object {
        const val COUNT = 3
    }
}

/** 饮食档：派工单点名的素／常／斋期，加上南传过午不食与伊斯兰清真两类。 */
enum class FaithDiet(val label: String) {
    VEGETARIAN("素食"),
    BROAD("常食（无素荤之禁）"),
    FAST_PERIOD("斋期持戒，斋期外较宽"),
    DAY_FASTING("过午不食"),
    HALAL("清真饮食"),
    ;

    companion object {
        const val COUNT = 5
    }
}

/** 发式档。 */
enum class HairRule(val label: String) {
    GROWN_WITH_CAP("蓄发戴巾"),
    TONSURE("剃发"),
    MIGHT_GROW("蓄发或不限"),
    UNFIXED("发式不限"),
    ;

    companion object {
        const val COUNT = 4
    }
}

/** 服饰档（类别，不含具体形制与纹样）。 */
enum class FaithDress(val label: String) {
    DAO_ROBE("道袍／法衣"),
    MONASTIC_ROBE("僧衣"),
    CLERICAL("圣职服饰"),
    RITUAL_ONLY("平时常服，礼仪器务时着教服"),
    ;

    companion object {
        const val COUNT = 4
    }
}

/** 居住档：住观住寺／堂区住所／居家／两处均可。 */
enum class FaithResidence(val label: String) {
    TEMPLE_RESIDENT("住观／住寺"),
    PARISH_HOUSE("堂区或教区住所"),
    HOME("居家"),
    MIXED("寺院与居家均可"),
    ;

    companion object {
        const val COUNT = 4
    }
}

/** 依据类别：这一条为什么这么写——必须引教制，不许引印象（判据③）。 */
enum class FaithBasis(val label: String) {
    MONASTIC_PRECEPT("出家戒律"),
    HOUSEHOLDER_RULE("火居／在家教制"),
    ALMS_ROUND_RULE("托钵律制"),
    CELIBACY_DISCIPLINE("圣职独身纪律"),
    MARRIAGE_ALLOWED("圣职可婚制"),
    DIETARY_LAW("饮食教规"),
    ;

    companion object {
        const val COUNT = 6
    }
}

/**
 * 宗教职槽。派工单点名的五个字段（celibate／diet／hairRule／dress／residence）
 * 加依据一项，缺一即红；basis 还必须是**非空集合**——没有依据的宗教档等于印象。
 */
data class FaithSlot(
    val branch: FaithBranch?,
    val celibate: Celibate?,
    val diet: FaithDiet?,
    val hair: HairRule?,
    val dress: FaithDress?,
    val residence: FaithResidence?,
    val basis: Set<FaithBasis>?,
) {
    fun missingNames(): List<String> {
        val slots: List<Pair<String, Any?>> = listOf(
            "教派" to branch, "婚否" to celibate, "饮食" to diet, "发式" to hair,
            "服饰" to dress, "居住" to residence,
        )
        val out = slots.filter { it.second == null }.map { it.first }.toMutableList()
        if (basis == null || basis.isEmpty()) out.add("依据（教制）")
        return out
    }
}
