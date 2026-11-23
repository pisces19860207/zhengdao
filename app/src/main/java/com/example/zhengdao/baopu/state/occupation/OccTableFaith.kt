// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百八十二批 · 宗教职分表：九条教派／圣职（道教全真·正一·散居，佛教汉传·藏传·南传，
// 天主教神父·新教牧师·伊斯兰阿訇）。派工单追加二 §一：教派差异是硬信息，只写「道士／和尚」等于没写。
//
// 婚否、饮食、发式一律带 [FaithBasis]（教制依据），空依据＝把印象当依据，C7 判红；
// 「清心寡欲」「淡泊自律」这类把宗教身份写成性格的说法在本包一个都不许出现（C4）。
//
// 香火烟尘、地理气候这两档第一期没有：粉尘四值只列水泥／木屑／棉尘／金属，温区三值只列常温／炉前／冷库，
// 拿「冷库」套高原、拿「棉尘」套香烟都是错的 ⇒ 宁可留中性，缺口进交付报告残余项，不硬凑。
package com.example.zhengdao.baopu.state.occupation

internal val FAITH_ROWS: List<OccupationRow> = listOf(
    occRow(
        key = "daoQuanzhen", label = "道教全真", industry = Industry.FAITH,
        six = six(Posture.STAND, Dirt.GENERAL, Dress.UNIFORM, Sched.DAY, Risk.NONE),
        env = env(Place.MIXED, Noise.QUIET, Dust.NONE, Temp.AMBIENT, Moist.DRY, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.FLY_OPEN),
        burden = burden(Carry.LIGHT, listOf(Joint.WRIST), Stand.MID, Shift.FIXED_DAY),
        drives = drives(Dir.FLAT, Dir.UP, Dir.UP, Dir.FLAT, MECH_FINE),
        faith = faithSlot(
            FaithBranch.DAO_QUANZHEN, Celibate.REQUIRED, FaithDiet.VEGETARIAN, HairRule.GROWN_WITH_CAP,
            FaithDress.DAO_ROBE, FaithResidence.TEMPLE_RESIDENT,
            setOf(FaithBasis.MONASTIC_PRECEPT, FaithBasis.DIETARY_LAW),
        ),
    ),
    // 全真住观不婚、正一火居可婚——这两行只差在教制上，不在人上；差异必须落在档上而不是形容词上。
    // 正一：可婚、火居、斋期外饮食较宽、平时可着俗装仅法事穿道袍——四处都与全真相反，且各有教制出处。
    occRow(
        key = "daoZhengyi", label = "道教正一", industry = Industry.FAITH,
        six = six(Posture.STAND, Dirt.GENERAL, Dress.UNIFORM, Sched.ON_CALL, Risk.NONE),
        env = env(Place.MIXED, Noise.MID, Dust.NONE, Temp.AMBIENT, Moist.DRY, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.FLY_OPEN),
        burden = burden(Carry.LIGHT, listOf(Joint.WRIST), Stand.MID, Shift.IRREGULAR),
        drives = drives(Dir.FLAT, Dir.UP, Dir.UP, Dir.FLAT, MECH_FINE),
        faith = faithSlot(
            FaithBranch.DAO_ZHENGYI, Celibate.PERMITTED, FaithDiet.FAST_PERIOD, HairRule.MIGHT_GROW,
            FaithDress.RITUAL_ONLY, FaithResidence.HOME,
            setOf(FaithBasis.HOUSEHOLDER_RULE, FaithBasis.MARRIAGE_ALLOWED, FaithBasis.DIETARY_LAW),
        ),
    ),
    occRow(
        key = "daoSanju", label = "道教散居道士", industry = Industry.FAITH,
        six = six(Posture.WALK, Dirt.GENERAL, Dress.CASUAL, Sched.SEASONAL, Risk.NONE),
        env = env(Place.MIXED, Noise.MID, Dust.NONE, Temp.AMBIENT, Moist.GENERAL, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.FLY_OPEN),
        burden = burden(Carry.LIGHT, NO_REPEAT, Stand.MID, Shift.SEASON),
        drives = drives(Dir.FLAT, Dir.FLAT, Dir.UP, Dir.FLAT, MECH_FINE),
        faith = faithSlot(
            FaithBranch.DAO_SANJU, Celibate.PERMITTED, FaithDiet.BROAD, HairRule.UNFIXED,
            FaithDress.RITUAL_ONLY, FaithResidence.HOME, setOf(FaithBasis.HOUSEHOLDER_RULE),
        ),
    ),
    occRow(
        key = "monkHan", label = "汉传僧", industry = Industry.FAITH,
        six = six(Posture.STAND, Dirt.GENERAL, Dress.UNIFORM, Sched.DAY, Risk.NONE),
        env = env(Place.MIXED, Noise.QUIET, Dust.NONE, Temp.AMBIENT, Moist.DRY, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.NONE_FIXED),
        burden = burden(Carry.LIGHT, NO_REPEAT, Stand.HIGH, Shift.FIXED_DAY),
        drives = drives(Dir.FLAT, Dir.FLAT, Dir.UP, Dir.FLAT, MECH_MINIMAL),
        faith = faithSlot(
            FaithBranch.BU_HAN, Celibate.REQUIRED, FaithDiet.VEGETARIAN, HairRule.TONSURE,
            FaithDress.MONASTIC_ROBE, FaithResidence.TEMPLE_RESIDENT,
            setOf(FaithBasis.MONASTIC_PRECEPT, FaithBasis.DIETARY_LAW),
        ),
    ),
    occRow(
        key = "monkTibetan", label = "藏传僧与瑜伽士", industry = Industry.FAITH,
        six = six(Posture.SIT, Dirt.GENERAL, Dress.UNIFORM, Sched.DAY, Risk.NONE),
        env = env(Place.MIXED, Noise.QUIET, Dust.NONE, Temp.AMBIENT, Moist.DRY, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.NONE_FIXED),
        burden = burden(Carry.NONE, NO_REPEAT, Stand.MID, Shift.FIXED_DAY),
        drives = drives(Dir.FLAT, Dir.FLAT, Dir.UP, Dir.FLAT, MECH_MINIMAL),
        faith = faithSlot(
            FaithBranch.BU_TIBET, Celibate.MONASTIC_ONLY, FaithDiet.BROAD, HairRule.MIGHT_GROW,
            FaithDress.MONASTIC_ROBE, FaithResidence.MIXED,
            setOf(FaithBasis.MONASTIC_PRECEPT, FaithBasis.HOUSEHOLDER_RULE),
        ),
    ),
    occRow(
        key = "monkTheravada", label = "南传僧", industry = Industry.FAITH,
        six = six(Posture.WALK, Dirt.GENERAL, Dress.UNIFORM, Sched.DAY, Risk.NONE),
        env = env(Place.OUTDOOR, Noise.QUIET, Dust.NONE, Temp.AMBIENT, Moist.GENERAL, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.NONE_FIXED),
        burden = burden(Carry.NONE, NO_REPEAT, Stand.MID, Shift.FIXED_DAY),
        drives = drives(Dir.FLAT, Dir.UP, Dir.UP, Dir.UP, MECH_LABOR),
        faith = faithSlot(
            FaithBranch.BU_THERAVADA, Celibate.REQUIRED, FaithDiet.DAY_FASTING, HairRule.TONSURE,
            FaithDress.MONASTIC_ROBE, FaithResidence.TEMPLE_RESIDENT,
            setOf(FaithBasis.ALMS_ROUND_RULE, FaithBasis.DIETARY_LAW),
        ),
    ),
    occRow(
        key = "catholicPriest", label = "天主教神父", industry = Industry.FAITH,
        six = six(Posture.SIT, Dirt.CLEAN, Dress.UNIFORM, Sched.LONG_DAY, Risk.NONE),
        env = env(Place.INDOOR, Noise.QUIET, Dust.NONE, Temp.AMBIENT, Moist.DRY, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.FLY_OPEN),
        burden = burden(Carry.LIGHT, NO_REPEAT, Stand.MID, Shift.IRREGULAR),
        drives = drives(Dir.DOWN, Dir.FLAT, Dir.UP, Dir.FLAT, MECH_STATIC),
        faith = faithSlot(
            FaithBranch.CATHOLIC_PRIEST, Celibate.REQUIRED, FaithDiet.BROAD, HairRule.UNFIXED,
            FaithDress.CLERICAL, FaithResidence.PARISH_HOUSE, setOf(FaithBasis.CELIBACY_DISCIPLINE),
        ),
    ),
    // 独身是拉丁礼的纪律、可婚是新教的圣职制——两条都得写依据；不写依据就成了「印象」。
    occRow(
        key = "protestantPastor", label = "新教牧师", industry = Industry.FAITH,
        six = six(Posture.SIT, Dirt.CLEAN, Dress.FORMAL, Sched.DAY, Risk.NONE),
        env = env(Place.INDOOR, Noise.QUIET, Dust.NONE, Temp.AMBIENT, Moist.DRY, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.FLY_OPEN),
        burden = burden(Carry.LIGHT, NO_REPEAT, Stand.MID, Shift.LONG_DAY),
        drives = drives(Dir.DOWN, Dir.FLAT, Dir.UP, Dir.FLAT, MECH_STATIC),
        faith = faithSlot(
            FaithBranch.PROTESTANT_PASTOR, Celibate.PERMITTED, FaithDiet.BROAD, HairRule.UNFIXED,
            FaithDress.RITUAL_ONLY, FaithResidence.HOME, setOf(FaithBasis.MARRIAGE_ALLOWED),
        ),
    ),
    occRow(
        key = "muslimAhong", label = "伊斯兰阿訇", industry = Industry.FAITH,
        six = six(Posture.SIT, Dirt.CLEAN, Dress.FORMAL, Sched.DAY, Risk.NONE),
        env = env(Place.INDOOR, Noise.QUIET, Dust.NONE, Temp.AMBIENT, Moist.DRY, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.FLY_OPEN),
        burden = burden(Carry.NONE, NO_REPEAT, Stand.MID, Shift.FIXED_DAY),
        drives = drives(Dir.DOWN, Dir.FLAT, Dir.UP, Dir.FLAT, MECH_STATIC),
        faith = faithSlot(
            FaithBranch.MUSLIM_AHONG, Celibate.PERMITTED, FaithDiet.HALAL, HairRule.UNFIXED,
            FaithDress.RITUAL_ONLY, FaithResidence.HOME,
            setOf(FaithBasis.MARRIAGE_ALLOWED, FaithBasis.DIETARY_LAW),
        ),
    ),
)
