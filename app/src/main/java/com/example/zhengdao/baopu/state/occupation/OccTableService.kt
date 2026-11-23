// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百八十二批 · 分表二：运输物流（4）＋餐饮后厨（4）＋医疗（4）。
// 第一期①点名的长途／城配／叉车、炒锅／切配／洗碗／前厅、医生／护士／护工／检验全在这张表上。
//
// 两处口径特别记下，免得后人「顺手补一格」补出问题：
// · 驾驶类岗位的危险暴露只能落在 [Risk.TOOL]／[Risk.NONE]——本包的六维⑥没有「道路交通事故」这一档，
//   强行归到治安或器械都是错的，宁可留中性，缺口写进交付报告的残余项。
// · 护士这一行**不在名字里带性别**：同岗性别少数属第二期⑧的事实槽，不属工种名。
package com.example.zhengdao.baopu.state.occupation

internal val SERVICE_ROWS: List<OccupationRow> = listOf(
    occRow(
        key = "longHaulDriver", label = "长途货运", industry = Industry.TRANSPORT,
        six = six(Posture.DRIVE, Dirt.GENERAL, Dress.CASUAL, Sched.LONG_DAY, Risk.NONE),
        env = env(Place.MIXED, Noise.MID, Dust.NONE, Temp.AMBIENT, Moist.DRY, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.FLY_OPEN),
        burden = burden(Carry.LIGHT, listOf(Joint.BACK), Stand.LOW, Shift.IRREGULAR),
        drives = drives(Dir.DOWN, Dir.FLAT, Dir.UP, Dir.FLAT, MECH_STATIC),
    ),
    occRow(
        key = "cityDelivery", label = "城配司机", industry = Industry.TRANSPORT,
        six = six(Posture.WALK, Dirt.GENERAL, Dress.CASUAL, Sched.DAY, Risk.NONE),
        env = env(Place.MIXED, Noise.LOUD, Dust.NONE, Temp.AMBIENT, Moist.GENERAL, Chem.NONE),
        attire = attire(listOf(Wear.REFLECTIVE), Closure.FLY_OPEN),
        burden = burden(Carry.MID, listOf(Joint.SHOULDER, Joint.BACK), Stand.MID, Shift.LONG_DAY),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.UP, MECH_LABOR),
    ),
    occRow(
        key = "forkliftOperator", label = "叉车司机", industry = Industry.TRANSPORT,
        six = six(Posture.DRIVE, Dirt.GENERAL, Dress.WORKWEAR, Sched.ROTATING, Risk.TOOL),
        env = env(Place.INDOOR, Noise.LOUD, Dust.NONE, Temp.AMBIENT, Moist.DRY, Chem.NONE),
        attire = attire(listOf(Wear.REFLECTIVE), Closure.FLY_OPEN),
        burden = burden(Carry.NONE, listOf(Joint.WRIST), Stand.LOW, Shift.THREE_SHIFT),
        drives = drives(Dir.DOWN, Dir.FLAT, Dir.UP, Dir.FLAT, MECH_STATIC),
    ),
    // 冷库这一行的方向给得很直：低温环境出汗↓，这是物理机制，与任何人自身的习惯无关。
    occRow(
        key = "coldChainHandler", label = "冷链装卸", industry = Industry.TRANSPORT,
        six = six(Posture.CARRY, Dirt.GENERAL, Dress.WORKWEAR, Sched.ROTATING, Risk.NONE),
        env = env(Place.MIXED, Noise.MID, Dust.NONE, Temp.COLD_STORE, Moist.GENERAL, Chem.NONE),
        attire = attire(listOf(Wear.GLOVES), Closure.FLY_OPEN),
        burden = burden(Carry.HEAVY, listOf(Joint.BACK, Joint.SHOULDER), Stand.HIGH, Shift.THREE_SHIFT),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.DOWN, MECH_LABOR),
    ),
    occRow(
        key = "courierSorter", label = "快递分拣", industry = Industry.TRANSPORT,
        six = six(Posture.WALK, Dirt.GENERAL, Dress.WORKWEAR, Sched.NIGHT, Risk.NONE),
        env = env(Place.INDOOR, Noise.LOUD, Dust.NONE, Temp.AMBIENT, Moist.GENERAL, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.FLY_OPEN),
        burden = burden(Carry.MID, listOf(Joint.WRIST, Joint.SHOULDER), Stand.HIGH, Shift.THREE_SHIFT),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.UP, MECH_LABOR),
    ),
    occRow(
        key = "wokCook", label = "炒锅", industry = Industry.FOOD_KITCHEN,
        six = six(Posture.STAND, Dirt.HEAT, Dress.UNIFORM, Sched.LONG_DAY, Risk.HEAT),
        env = env(Place.INDOOR, Noise.LOUD, Dust.NONE, Temp.FURNACE_FRONT, Moist.GENERAL, Chem.NONE),
        attire = attire(KITCHEN_WEARS, Closure.FLY_OPEN),
        burden = burden(Carry.MID, listOf(Joint.WRIST, Joint.SHOULDER), Stand.HIGH, Shift.LONG_DAY),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.UP, MECH_HOT),
    ),
    occRow(
        key = "prepCook", label = "切配", industry = Industry.FOOD_KITCHEN,
        six = six(Posture.STAND, Dirt.GENERAL, Dress.UNIFORM, Sched.DAY, Risk.TOOL),
        env = env(Place.INDOOR, Noise.MID, Dust.NONE, Temp.AMBIENT, Moist.GENERAL, Chem.NONE),
        attire = attire(KITCHEN_WEARS, Closure.FLY_OPEN),
        burden = burden(Carry.LIGHT, listOf(Joint.WRIST), Stand.HIGH, Shift.LONG_DAY),
        drives = drives(Dir.FLAT, Dir.UP, Dir.UP, Dir.UP, MECH_HOT),
    ),
    // 洗碗这一行的消毒水与湿热是**作业面**的暴露；它不推出任何关于这个人自身的结论（判据④）。
    occRow(
        key = "dishwasher", label = "洗碗", industry = Industry.FOOD_KITCHEN,
        six = six(Posture.STAND, Dirt.MUD, Dress.UNIFORM, Sched.LONG_DAY, Risk.TOOL),
        env = env(Place.INDOOR, Noise.MID, Dust.NONE, Temp.FURNACE_FRONT, Moist.GENERAL, Chem.DISINFECTANT),
        attire = attire(listOf(Wear.CHEF_JACKET, Wear.MASK, Wear.GLOVES), Closure.FLY_OPEN),
        burden = burden(Carry.LIGHT, listOf(Joint.WRIST), Stand.HIGH, Shift.LONG_DAY),
        drives = drives(Dir.FLAT, Dir.UP, Dir.UP, Dir.UP, MECH_HOT),
    ),
    occRow(
        key = "frontHall", label = "前厅服务", industry = Industry.FOOD_KITCHEN,
        six = six(Posture.WALK, Dirt.CLEAN, Dress.UNIFORM, Sched.ROTATING, Risk.NONE),
        env = env(Place.INDOOR, Noise.MID, Dust.NONE, Temp.AMBIENT, Moist.DRY, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.FLY_OPEN),
        burden = burden(Carry.LIGHT, NO_REPEAT, Stand.HIGH, Shift.THREE_SHIFT),
        drives = drives(Dir.FLAT, Dir.FLAT, Dir.UP, Dir.FLAT, MECH_MINIMAL),
    ),
    occRow(
        key = "physician", label = "医生", industry = Industry.MEDICAL,
        six = six(Posture.STAND, Dirt.CLEAN, Dress.UNIFORM, Sched.ON_CALL, Risk.PATHOGEN),
        env = env(Place.INDOOR, Noise.QUIET, Dust.NONE, Temp.AMBIENT, Moist.DRY, Chem.DISINFECTANT),
        attire = attire(CLINIC_WEARS, Closure.FLY_OPEN),
        burden = burden(Carry.LIGHT, NO_REPEAT, Stand.VERY_HIGH, Shift.IRREGULAR),
        drives = drives(Dir.FLAT, Dir.UP, Dir.DOWN, Dir.FLAT, MECH_FINE),
    ),
    occRow(
        key = "nurse", label = "护士", industry = Industry.MEDICAL,
        six = six(Posture.CARRY, Dirt.GENERAL, Dress.UNIFORM, Sched.ROTATING, Risk.PATHOGEN),
        env = env(Place.INDOOR, Noise.MID, Dust.NONE, Temp.AMBIENT, Moist.DRY, Chem.DISINFECTANT),
        attire = attire(CLINIC_WEARS, Closure.FLY_OPEN),
        burden = burden(Carry.HEAVY, listOf(Joint.BACK, Joint.SHOULDER), Stand.HIGH, Shift.THREE_SHIFT),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.UP, MECH_LABOR),
    ),
    occRow(
        key = "orderly", label = "护工", industry = Industry.MEDICAL,
        six = six(Posture.CARRY, Dirt.GENERAL, Dress.WORKWEAR, Sched.LONG_DAY, Risk.PATHOGEN),
        env = env(Place.INDOOR, Noise.MID, Dust.NONE, Temp.AMBIENT, Moist.GENERAL, Chem.DISINFECTANT),
        attire = attire(listOf(Wear.GLOVES, Wear.MASK), Closure.FLY_OPEN),
        burden = burden(Carry.HEAVY, listOf(Joint.BACK, Joint.SHOULDER), Stand.HIGH, Shift.IRREGULAR),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.UP, MECH_LABOR),
    ),
    occRow(
        key = "labTechnician", label = "检验", industry = Industry.MEDICAL,
        six = six(Posture.SIT, Dirt.CLEAN, Dress.UNIFORM, Sched.ROTATING, Risk.PATHOGEN),
        env = env(Place.INDOOR, Noise.QUIET, Dust.NONE, Temp.AMBIENT, Moist.DRY, Chem.DISINFECTANT),
        attire = attire(CLINIC_WEARS, Closure.FLY_OPEN),
        burden = burden(Carry.NONE, listOf(Joint.WRIST), Stand.LOW, Shift.FIXED_DAY),
        drives = drives(Dir.DOWN, Dir.UP, Dir.FLAT, Dir.FLAT, MECH_STATIC),
    ),
)
