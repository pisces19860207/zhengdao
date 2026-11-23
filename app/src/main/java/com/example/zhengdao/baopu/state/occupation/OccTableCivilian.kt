// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百八十二批 · 分表三：第一期没点名的九个行业，每个至少一行（判据①「每行业至少一个岗位示例」）。
// 农渔 2／教育科研 3／IT与办公 2／商业服务 3／美发美容 2／艺术传媒 2／体育教练 2／自由与无业 2／学生 2。
//
// 这一张表最容易踩的坑是「拿岗位名当评价」：写「保洁」「美甲」这类岗位时，
// 附着暴露档说的是他工作的**面**，噪声与化学档说的是他站的那间**房**，
// 一行里都不含、也不允许推出这个人自身的任何状态（判据④＋2026-11-24 补充更正：正负刻板都算红）。
package com.example.zhengdao.baopu.state.occupation

internal val CIVILIAN_ROWS: List<OccupationRow> = listOf(
    occRow(
        key = "fieldFarmer", label = "大田种植", industry = Industry.AGRI,
        six = six(Posture.SQUAT, Dirt.CHEMICAL, Dress.CASUAL, Sched.SEASONAL, Risk.TOOL),
        env = env(Place.OUTDOOR, Noise.QUIET, Dust.NONE, Temp.AMBIENT, Moist.GENERAL, Chem.NONE),
        attire = attire(listOf(Wear.GLOVES), Closure.FLY_OPEN),
        burden = burden(Carry.MID, listOf(Joint.BACK, Joint.WRIST), Stand.MID, Shift.SEASON),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.UP, MECH_LABOR),
    ),
    occRow(
        key = "fisher", label = "捕捞养殖", industry = Industry.AGRI,
        six = six(Posture.CARRY, Dirt.MUD, Dress.WORKWEAR, Sched.SEASONAL, Risk.NONE),
        env = env(Place.MIXED, Noise.MID, Dust.NONE, Temp.AMBIENT, Moist.AQUATIC, Chem.NONE),
        attire = attire(listOf(Wear.GLOVES, Wear.REFLECTIVE), Closure.FLY_OPEN),
        burden = burden(Carry.HEAVY, listOf(Joint.SHOULDER, Joint.BACK), Stand.HIGH, Shift.IRREGULAR),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.UP, MECH_LABOR),
    ),
    occRow(
        key = "schoolTeacher", label = "中小学教师", industry = Industry.EDU_RESEARCH,
        six = six(Posture.STAND, Dirt.CLEAN, Dress.CASUAL, Sched.LONG_DAY, Risk.NONE),
        env = env(Place.INDOOR, Noise.MID, Dust.NONE, Temp.AMBIENT, Moist.DRY, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.FLY_OPEN),
        burden = burden(Carry.NONE, listOf(Joint.WRIST), Stand.HIGH, Shift.LONG_DAY),
        drives = drives(Dir.DOWN, Dir.FLAT, Dir.FLAT, Dir.FLAT, MECH_STATIC),
    ),
    occRow(
        key = "preschoolTeacher", label = "幼儿教师", industry = Industry.EDU_RESEARCH,
        six = six(Posture.SQUAT, Dirt.CLEAN, Dress.CASUAL, Sched.DAY, Risk.NONE),
        env = env(Place.INDOOR, Noise.LOUD, Dust.NONE, Temp.AMBIENT, Moist.GENERAL, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.FLY_OPEN),
        burden = burden(Carry.LIGHT, listOf(Joint.BACK, Joint.WRIST), Stand.MID, Shift.FIXED_DAY),
        drives = drives(Dir.FLAT, Dir.FLAT, Dir.UP, Dir.FLAT, MECH_MINIMAL),
    ),
    occRow(
        key = "labResearcher", label = "科研实验员", industry = Industry.EDU_RESEARCH,
        six = six(Posture.SIT, Dirt.CLEAN, Dress.UNIFORM, Sched.LONG_DAY, Risk.PATHOGEN),
        env = env(Place.INDOOR, Noise.QUIET, Dust.NONE, Temp.AMBIENT, Moist.DRY, Chem.DISINFECTANT),
        attire = attire(CLINIC_WEARS, Closure.FLY_OPEN),
        burden = burden(Carry.NONE, listOf(Joint.WRIST), Stand.MID, Shift.LONG_DAY),
        drives = drives(Dir.DOWN, Dir.UP, Dir.FLAT, Dir.FLAT, MECH_STATIC),
    ),
    occRow(
        key = "softwareDeveloper", label = "软件开发", industry = Industry.IT_OFFICE,
        six = six(Posture.SIT, Dirt.CLEAN, Dress.CASUAL, Sched.DAY, Risk.NONE),
        env = env(Place.INDOOR, Noise.QUIET, Dust.NONE, Temp.AMBIENT, Moist.DRY, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.FLY_OPEN),
        burden = burden(Carry.NONE, listOf(Joint.WRIST), Stand.LOW, Shift.FIXED_DAY),
        drives = drives(Dir.DOWN, Dir.FLAT, Dir.DOWN, Dir.FLAT, MECH_STATIC),
    ),
    occRow(
        key = "idcOperator", label = "机房运维", industry = Industry.IT_OFFICE,
        six = six(Posture.STAND, Dirt.CLEAN, Dress.CASUAL, Sched.ON_CALL, Risk.TOOL),
        env = env(Place.INDOOR, Noise.LOUD, Dust.NONE, Temp.AMBIENT, Moist.DRY, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.FLY_OPEN),
        burden = burden(Carry.MID, listOf(Joint.BACK, Joint.SHOULDER), Stand.MID, Shift.IRREGULAR),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.FLAT, MECH_LABOR),
    ),
    occRow(
        key = "retailSales", label = "门店销售", industry = Industry.BIZ_SERVICE,
        six = six(Posture.STAND, Dirt.CLEAN, Dress.UNIFORM, Sched.ROTATING, Risk.NONE),
        env = env(Place.INDOOR, Noise.MID, Dust.NONE, Temp.AMBIENT, Moist.DRY, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.FLY_OPEN),
        burden = burden(Carry.LIGHT, NO_REPEAT, Stand.HIGH, Shift.THREE_SHIFT),
        drives = drives(Dir.FLAT, Dir.FLAT, Dir.UP, Dir.FLAT, MECH_MINIMAL),
    ),
    occRow(
        key = "gateGuard", label = "门卫值守", industry = Industry.BIZ_SERVICE,
        six = six(Posture.STAND, Dirt.GENERAL, Dress.UNIFORM, Sched.NIGHT, Risk.PUBLIC_SAFETY),
        env = env(Place.MIXED, Noise.MID, Dust.NONE, Temp.AMBIENT, Moist.GENERAL, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.FLY_OPEN),
        burden = burden(Carry.NONE, NO_REPEAT, Stand.HIGH, Shift.THREE_SHIFT),
        drives = drives(Dir.FLAT, Dir.FLAT, Dir.UP, Dir.FLAT, MECH_MINIMAL),
    ),
    // 保洁：这一行是判据④最容易读反的地方——消毒水与溶剂是他的**作业暴露**，
    // 与他自己怎么洗漱没有任何关系；C5 会核这一行的交接面里没有 883 禁区那三条。
    occRow(
        key = "cleaningStaff", label = "保洁", industry = Industry.BIZ_SERVICE,
        six = six(Posture.WALK, Dirt.CHEMICAL, Dress.WORKWEAR, Sched.ROTATING, Risk.NONE),
        env = env(Place.MIXED, Noise.MID, Dust.COTTON, Temp.AMBIENT, Moist.GENERAL, Chem.DISINFECTANT),
        attire = attire(listOf(Wear.GLOVES, Wear.MASK), Closure.FLY_OPEN),
        burden = burden(Carry.MID, listOf(Joint.SHOULDER, Joint.BACK), Stand.MID, Shift.FIXED_DAY),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.UP, MECH_LABOR),
    ),
    // 洗衣房的湿热与溶剂同样是作业面：派工单点名的「洗衣」湿度档在这一行落地。
    occRow(
        key = "laundryStaff", label = "洗衣房作业", industry = Industry.BIZ_SERVICE,
        six = six(Posture.STAND, Dirt.GENERAL, Dress.WORKWEAR, Sched.ROTATING, Risk.NONE),
        env = env(Place.INDOOR, Noise.MID, Dust.NONE, Temp.AMBIENT, Moist.LAUNDRY, Chem.SOLVENT),
        attire = attire(listOf(Wear.GLOVES), Closure.FLY_OPEN),
        burden = burden(Carry.MID, listOf(Joint.WRIST, Joint.BACK), Stand.HIGH, Shift.FIXED_DAY),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.UP, MECH_HOT),
    ),
    occRow(
        key = "barber", label = "理发师", industry = Industry.BEAUTY,
        six = six(Posture.STAND, Dirt.CLEAN, Dress.CASUAL, Sched.LONG_DAY, Risk.NONE),
        env = env(Place.INDOOR, Noise.MID, Dust.NONE, Temp.AMBIENT, Moist.DRY, Chem.SOLVENT),
        attire = attire(NO_PROTECTIVE, Closure.FLY_OPEN),
        burden = burden(Carry.NONE, listOf(Joint.WRIST, Joint.SHOULDER), Stand.HIGH, Shift.FIXED_DAY),
        drives = drives(Dir.FLAT, Dir.UP, Dir.UP, Dir.FLAT, MECH_FINE),
    ),
    occRow(
        key = "nailTechnician", label = "美甲师", industry = Industry.BEAUTY,
        six = six(Posture.SIT, Dirt.CLEAN, Dress.CASUAL, Sched.LONG_DAY, Risk.NONE),
        env = env(Place.INDOOR, Noise.QUIET, Dust.NONE, Temp.AMBIENT, Moist.DRY, Chem.SOLVENT),
        attire = attire(listOf(Wear.MASK), Closure.FLY_OPEN),
        burden = burden(Carry.NONE, listOf(Joint.WRIST), Stand.LOW, Shift.FIXED_DAY),
        drives = drives(Dir.DOWN, Dir.UP, Dir.FLAT, Dir.FLAT, MECH_STATIC),
    ),
    // 布景要开料，所以舞台技术的粉尘是木屑——派工单点名的四种粉尘各得有一行真落到位置上。
    occRow(
        key = "stageCrew", label = "舞台技术", industry = Industry.ART_MEDIA,
        six = six(Posture.CARRY, Dirt.GENERAL, Dress.WORKWEAR, Sched.NIGHT, Risk.HEIGHT),
        env = env(Place.INDOOR, Noise.LOUD, Dust.WOOD, Temp.AMBIENT, Moist.GENERAL, Chem.PAINT),
        attire = attire(listOf(Wear.HELMET, Wear.GLOVES), Closure.FLY_OPEN),
        burden = burden(Carry.HEAVY, listOf(Joint.SHOULDER, Joint.BACK), Stand.MID, Shift.IRREGULAR),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.UP, MECH_LABOR),
    ),
    occRow(
        key = "cameraOperator", label = "摄影摄像", industry = Industry.ART_MEDIA,
        six = six(Posture.STAND, Dirt.GENERAL, Dress.CASUAL, Sched.ON_CALL, Risk.NONE),
        env = env(Place.MIXED, Noise.MID, Dust.NONE, Temp.AMBIENT, Moist.GENERAL, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.FLY_OPEN),
        burden = burden(Carry.HEAVY, listOf(Joint.SHOULDER, Joint.WRIST), Stand.HIGH, Shift.LONG_DAY),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.FLAT, MECH_LABOR),
    ),
    occRow(
        key = "strengthCoach", label = "体能教练", industry = Industry.SPORT_COACH,
        six = six(Posture.WALK, Dirt.GENERAL, Dress.SPORTS, Sched.ROTATING, Risk.NONE),
        env = env(Place.MIXED, Noise.LOUD, Dust.NONE, Temp.AMBIENT, Moist.GENERAL, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.FLY_OPEN),
        burden = burden(Carry.MID, NO_REPEAT, Stand.MID, Shift.IRREGULAR),
        drives = drives(Dir.UP, Dir.UP, Dir.FLAT, Dir.UP, MECH_LABOR),
    ),
    occRow(
        key = "youthCoach", label = "青训教练", industry = Industry.SPORT_COACH,
        six = six(Posture.WALK, Dirt.GENERAL, Dress.SPORTS, Sched.DAY, Risk.NONE),
        env = env(Place.OUTDOOR, Noise.LOUD, Dust.NONE, Temp.AMBIENT, Moist.GENERAL, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.FLY_OPEN),
        burden = burden(Carry.LIGHT, NO_REPEAT, Stand.HIGH, Shift.SEASON),
        drives = drives(Dir.UP, Dir.UP, Dir.FLAT, Dir.UP, MECH_LABOR),
    ),
    occRow(
        key = "freelancer", label = "自由职业", industry = Industry.FREELANCE,
        six = six(Posture.SIT, Dirt.CLEAN, Dress.NONE_FIXED, Sched.ON_CALL, Risk.NONE),
        env = env(Place.MIXED, Noise.QUIET, Dust.NONE, Temp.AMBIENT, Moist.GENERAL, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.NONE_FIXED),
        burden = burden(Carry.NONE, listOf(Joint.WRIST), Stand.LOW, Shift.IRREGULAR),
        drives = drives(Dir.DOWN, Dir.FLAT, Dir.DOWN, Dir.FLAT, MECH_STATIC),
    ),
    occRow(
        key = "jobSeeker", label = "待业间隙", industry = Industry.FREELANCE,
        six = six(Posture.SIT, Dirt.CLEAN, Dress.NONE_FIXED, Sched.ON_CALL, Risk.NONE),
        env = env(Place.INDOOR, Noise.QUIET, Dust.NONE, Temp.AMBIENT, Moist.GENERAL, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.NONE_FIXED),
        burden = burden(Carry.NONE, NO_REPEAT, Stand.LOW, Shift.IRREGULAR),
        drives = drives(Dir.DOWN, Dir.FLAT, Dir.DOWN, Dir.DOWN, MECH_STATIC),
    ),
    occRow(
        key = "collegeStudent", label = "高校学生", industry = Industry.STUDENT,
        six = six(Posture.SIT, Dirt.CLEAN, Dress.NONE_FIXED, Sched.DAY, Risk.NONE),
        env = env(Place.INDOOR, Noise.QUIET, Dust.NONE, Temp.AMBIENT, Moist.GENERAL, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.NONE_FIXED),
        burden = burden(Carry.NONE, listOf(Joint.WRIST), Stand.LOW, Shift.FIXED_DAY),
        drives = drives(Dir.DOWN, Dir.FLAT, Dir.DOWN, Dir.FLAT, MECH_STATIC),
    ),
    occRow(
        key = "vocationalTrainee", label = "职校实训", industry = Industry.STUDENT,
        six = six(Posture.STAND, Dirt.GENERAL, Dress.WORKWEAR, Sched.DAY, Risk.TOOL),
        env = env(Place.INDOOR, Noise.LOUD, Dust.METAL, Temp.AMBIENT, Moist.DRY, Chem.NONE),
        attire = attire(listOf(Wear.GLOVES), Closure.FLY_OPEN),
        burden = burden(Carry.LIGHT, listOf(Joint.WRIST), Stand.MID, Shift.FIXED_DAY),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.FLAT, MECH_LABOR),
    ),
)
