// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百八十二批 · 职业池的两条横轴：行业（`Industry`）与年代（`Era`）。
//
// 行业＝派工单 §一① 的 15 项，再加「宗教职」一项（追加二 §一要求宗教类必须进池且分教派），
// 所以这里 **16 项**，每项在 `OccupationTable` 里至少有一个工种示例（判据①，缺则红）。
//
// 年代＝派工单追加二 §二：`era` **只调「可用集」**——881 侧的衣物形制／材质／获取方式，
// 883 侧洗浴条件的客观场所来源。它**不推行为**、**不产出任何清洁档**：
// 「同一年代，有人宿舍有热水，有人没有」——个体差异永远大于年代差异（判据①：写「那个年代的人都…」即红）。
// 因此每个年代必须**同时给城镇与乡村两套场所菜单**（落实「可细分城乡」，缺任一套即红）。
package com.example.zhengdao.baopu.state.occupation

/** 行业轴：派工单 15 项＋宗教职，共 16 项。 */
enum class Industry(val key: String, val label: String) {
    AGRI("agri", "农渔"),
    MINE_BUILD("mineBuild", "采矿建筑"),
    MAKE("make", "制造装配"),
    TRANSPORT("transport", "运输物流"),
    UNIFORM("uniform", "军警消防"),
    MEDICAL("medical", "医疗"),
    EDU_RESEARCH("eduResearch", "教育科研"),
    IT_OFFICE("itOffice", "IT与办公"),
    BIZ_SERVICE("bizService", "商业服务"),
    FOOD_KITCHEN("foodKitchen", "餐饮后厨"),
    BEAUTY("beauty", "美发美容"),
    ART_MEDIA("artMedia", "艺术传媒"),
    SPORT_COACH("sportCoach", "体育教练"),
    FREELANCE("freelance", "自由与无业"),
    STUDENT("student", "学生"),
    FAITH("faith", "宗教职"),
    ;

    companion object {
        const val COUNT = 16
    }
}

/** 年代轴：派工单追加二 §二的五档。 */
enum class Era(val key: String, val label: String, val span: String) {
    PRE_1949("pre1949", "1949前", "1900–1949"),
    MID_50_70("mid5070", "1950–70", "1950–1979"),
    LATE_80_90("late8090", "1980–90", "1980–1999"),
    EARLY_2000("early2000", "2000后", "2000–2015"),
    NOW("now", "当代", "2016–今"),
    ;

    companion object {
        const val COUNT = 5
    }
}

/**
 * 内裤形制的**可用集**取值（派工单追加二 §二「早年无内裤／大裆裤／棉布自制 → 后有三角／平角」）。
 * 这里只登记「那个年代买得到、穿得到什么形制」；**有没有穿**由 881 的内衣层档持有，本包不产出。
 */
enum class EraForm(val label: String) {
    NONE_WORN("无（该年代普遍不穿）"),
    LOOSE_PANTS("大裆裤"),
    HOME_SEWN("棉布自制"),
    BRIEF("三角"),
    BOXER("平角"),
    SPORTS("运动贴身"),
    ;

    companion object {
        const val COUNT = 6
    }
}

/** 衣物材质的可用集。 */
enum class FabricClass(val label: String) {
    COARSE_COTTON("棉粗布"),
    COTTON_KNIT("棉针织"),
    DICRON("的确良"),
    SYNTHETIC("化纤"),
    FUNCTIONAL("功能面料"),
    ;

    companion object {
        const val COUNT = 5
    }
}

/** 成衣获取方式的可用集。 */
enum class GarmentSupply(val label: String) {
    HOME_SEWN("自制"),
    TAILOR("裁缝"),
    READY_MADE("成衣"),
    ONLINE("网购"),
    ;

    companion object {
        const val COUNT = 4
    }
}

/**
 * 洗浴**场所**的可用集——它是客观条件的一处来源，交给 883 合成条件档；
 * 本包只给「有没有这种场所可用」，**不给条件档本身、更不给意愿**。
 */
enum class WashVenue(val label: String) {
    PUBLIC_BATHHOUSE("公共澡堂"),
    WORKUNIT_BATH("单位浴池"),
    HOME_HEATER("家用热水器"),
    EN_SUITE_BATH("套内卫浴"),
    YARD_WATER("院内取水／公用水点"),
    ;

    companion object {
        const val COUNT = 5
    }
}

/**
 * 一个年代的可用集。
 *
 * 六个字段**刻意全部可空**：生产表 [EraTable.ROWS] 全填，但「漏一项」必须能被
 * [OccupationGate.check] 判红——这是判据「era 只调可用集」的负例抓手。
 * [venueCity] 与 [venueRural] 必须**两套都给**（城乡可分），只给一套即红。
 */
data class EraAvailability(
    val era: Era,
    val forms: List<EraForm>?,
    val fabrics: List<FabricClass>?,
    val supply: List<GarmentSupply>?,
    val venueCity: List<WashVenue>?,
    val venueRural: List<WashVenue>?,
    val hands: List<Handoff>?,
)

/** 年代可用集表：唯一真相，检查器与负例都从这里取，不许另抄一份。 */
object EraTable {

    val ROWS: List<EraAvailability> = listOf(
        era(
            Era.PRE_1949,
            listOf(EraForm.NONE_WORN, EraForm.LOOSE_PANTS, EraForm.HOME_SEWN),
            listOf(FabricClass.COARSE_COTTON),
            listOf(GarmentSupply.HOME_SEWN, GarmentSupply.TAILOR),
            listOf(WashVenue.PUBLIC_BATHHOUSE, WashVenue.YARD_WATER),
            listOf(WashVenue.YARD_WATER),
        ),
        era(
            Era.MID_50_70,
            listOf(EraForm.NONE_WORN, EraForm.LOOSE_PANTS, EraForm.HOME_SEWN),
            listOf(FabricClass.COARSE_COTTON, FabricClass.COTTON_KNIT, FabricClass.DICRON),
            listOf(GarmentSupply.HOME_SEWN, GarmentSupply.TAILOR, GarmentSupply.READY_MADE),
            listOf(WashVenue.PUBLIC_BATHHOUSE, WashVenue.WORKUNIT_BATH, WashVenue.YARD_WATER),
            listOf(WashVenue.YARD_WATER),
        ),
        era(
            Era.LATE_80_90,
            listOf(EraForm.HOME_SEWN, EraForm.BRIEF, EraForm.BOXER),
            listOf(FabricClass.COARSE_COTTON, FabricClass.COTTON_KNIT, FabricClass.DICRON, FabricClass.SYNTHETIC),
            listOf(GarmentSupply.HOME_SEWN, GarmentSupply.TAILOR, GarmentSupply.READY_MADE),
            listOf(WashVenue.PUBLIC_BATHHOUSE, WashVenue.WORKUNIT_BATH, WashVenue.HOME_HEATER),
            listOf(WashVenue.YARD_WATER, WashVenue.HOME_HEATER),
        ),
        era(
            Era.EARLY_2000,
            listOf(EraForm.BRIEF, EraForm.BOXER, EraForm.SPORTS),
            listOf(FabricClass.COTTON_KNIT, FabricClass.SYNTHETIC, FabricClass.FUNCTIONAL),
            listOf(GarmentSupply.TAILOR, GarmentSupply.READY_MADE, GarmentSupply.ONLINE),
            listOf(WashVenue.HOME_HEATER, WashVenue.EN_SUITE_BATH, WashVenue.PUBLIC_BATHHOUSE),
            listOf(WashVenue.YARD_WATER, WashVenue.HOME_HEATER, WashVenue.EN_SUITE_BATH),
        ),
        era(
            Era.NOW,
            listOf(EraForm.BRIEF, EraForm.BOXER, EraForm.SPORTS),
            listOf(FabricClass.COTTON_KNIT, FabricClass.SYNTHETIC, FabricClass.FUNCTIONAL),
            listOf(GarmentSupply.READY_MADE, GarmentSupply.ONLINE, GarmentSupply.TAILOR),
            listOf(WashVenue.HOME_HEATER, WashVenue.EN_SUITE_BATH, WashVenue.PUBLIC_BATHHOUSE),
            listOf(WashVenue.HOME_HEATER, WashVenue.EN_SUITE_BATH, WashVenue.YARD_WATER),
        ),
    )

    /** 年代可用集一律按「城乡两套」登记；这里只交接物侧与条件侧两个去向。 */
    private val HANDS: List<Handoff> = listOf(Handoff.ERA_GARMENT_881, Handoff.ERA_VENUE_883)

    private fun era(
        which: Era,
        forms: List<EraForm>,
        fabrics: List<FabricClass>,
        supply: List<GarmentSupply>,
        city: List<WashVenue>,
        rural: List<WashVenue>,
    ): EraAvailability = EraAvailability(
        era = which, forms = forms, fabrics = fabrics, supply = supply,
        venueCity = city, venueRural = rural, hands = HANDS,
    )

    init {
        require(ROWS.size == Era.COUNT) { "年代表必须五档齐全" }
    }
}
