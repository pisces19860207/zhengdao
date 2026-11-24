// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百二十七批 · 唯一禁词表：只登记词，不登记逻辑。
package com.example.zhengdao.baopu.state.smell

internal object OdorWords {

    val NEUTRAL: List<String> = listOf(
        "汗", "酸", "油", "烟", "消毒水", "皂香", "皮革", "金属", "潮闷",
    )

    val DIAGNOSIS: List<String> = listOf(
        "狐臭", "臭汗症", "感染味", "炎症味", "尿道炎", "性病", "病变", "患病", "带病", "病情", "有病", "炎症", "感染",
        "梅毒", "疱疹", "湿疣", "hiv", "龟头炎", "包皮炎", "睾丸炎", "前列腺炎", "阴道炎", "外阴炎", "皮炎",
        "癌症", "恶性肿瘤", "良性肿瘤", "肿瘤", "结核", "真菌感染", "细菌感染", "败血症", "白血病", "糖尿病",
        "高血压", "冠心病", "哮喘", "湿疹", "股癣", "脚气",
    )

    val DIAGNOSIS_ODOR_COLLISIONS: List<String> = listOf("脚气")

    val VALUE_JUDGMENT: List<String> = listOf(
        "不讲卫生", "没洗澡", "邋遢", "恶心", "底层", "下等人", "素质差", "没教养", "没有教养",
    )

    val BEHAVIOR_GUIDANCE: List<String> = listOf(
        "建议香水", "推荐香水", "香水掩盖", "去洗澡", "遮味", "去味", "除味", "治疗", "就医", "用药", "盖味",
    )

    val OCCUPATION_INFERENCE: List<String> = listOf(
        "职业决定体味", "因为他是工人所以有汗味", "当兵所以有汗味", "干这行就一定有体味",
        "因为他是医生所以没有体味",
    )

    val FRESH_SWEAT_SOUR: List<String> = listOf(
        "刚出汗就酸臭", "刚出汗就臭", "立刻酸臭", "马上酸臭",
    )

    val WASHED_STILL_ODOR: List<String> = listOf(
        "洗过仍有味", "洗完还有味", "清洗后仍有味", "洗了还有味",
    )

    val POINT_VALUE: List<String> = listOf(
        "μg/L", "mg/L", "ppm", "pH", "浓度", "成分", "毫升", "mL", "ml", "cm", "毫米",
    )
}
