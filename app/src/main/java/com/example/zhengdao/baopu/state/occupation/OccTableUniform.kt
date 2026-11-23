// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百八十二批 · ERRATA 补做段：军／警／消分细类，共 18 行（军 8、警 6、消 4）。
//
// 军的行**沿两条轴铺**：等级类别（义务兵／士官／军官）与场景类别（营区驻训／边防哨所／舰艇／机务／通信／医疗），
// 两个轴的取值都必须在表里出现，否则「分细类」又退成一个行业名（C6b 机检覆盖）。
//
// 三条纪律，逐行可查：
// ① 只给类别，不给番号／装备参数／行动细节——本表里没有任何单位序号与器材指标（C12 机检）；
// ② 不写性格——「铁血／服从／粗鲁」这一类词在本包一个都不许出现（C4 禁词表）；
// ③ dutyCycle 与 returnGap 只喂 883 的**条件**（能不能回到有热水、有独处空间之处），
//    不喂意愿、不喂清洁档：值勤、驻训、专案的人同样在意自身清洁，这与任何职业一样（补充更正：正负刻板都算红）。
package com.example.zhengdao.baopu.state.occupation

internal val UNIFORM_ROWS: List<OccupationRow> = listOf(
    occRow(
        key = "conscript", label = "义务兵", industry = Industry.UNIFORM,
        six = six(Posture.CARRY, Dirt.GENERAL, Dress.UNIFORM, Sched.ROTATING, Risk.TOOL),
        env = env(Place.MIXED, Noise.LOUD, Dust.NONE, Temp.AMBIENT, Moist.GENERAL, Chem.NONE),
        attire = attire(listOf(Wear.GLOVES), Closure.FLY_OPEN),
        burden = burden(Carry.MID, listOf(Joint.BACK, Joint.SHOULDER), Stand.HIGH, Shift.IRREGULAR),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.UP, MECH_LABOR),
        service = army(
            ArmyRank.CONSCRIPT, ServiceScene.CAMP_TRAINING, DutyPhase.TRAINING, DutyCycle.FIELD_TRAINING,
            ReturnGap.MULTI_DAY, listOf(Gear.CAMO, Gear.TRAINING_SUIT, Gear.HELMET, Gear.BOOTS, Gear.GLOVES),
            listOf(EnvExtra.DUST, EnvExtra.NOISE),
            listOf(BodyLoad.RUCK_MARCH, BodyLoad.CLIMB, BodyLoad.LONG_STAND, BodyLoad.SPRINT),
        ),
    ),
    occRow(
        key = "nco", label = "士官", industry = Industry.UNIFORM,
        six = six(Posture.CARRY, Dirt.GENERAL, Dress.UNIFORM, Sched.ROTATING, Risk.TOOL),
        env = env(Place.MIXED, Noise.LOUD, Dust.NONE, Temp.AMBIENT, Moist.GENERAL, Chem.NONE),
        attire = attire(listOf(Wear.GLOVES), Closure.FLY_OPEN),
        burden = burden(Carry.MID, listOf(Joint.BACK, Joint.SHOULDER), Stand.HIGH, Shift.THREE_SHIFT),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.UP, MECH_LABOR),
        service = army(
            ArmyRank.NCO, ServiceScene.CAMP_TRAINING, DutyPhase.DAILY, DutyCycle.ROTATING_24H,
            ReturnGap.NEXT_DAY, listOf(Gear.CAMO, Gear.TRAINING_SUIT, Gear.BOOTS, Gear.GLOVES),
            listOf(EnvExtra.NOISE, EnvExtra.DUST),
            listOf(BodyLoad.RUCK_MARCH, BodyLoad.LONG_STAND, BodyLoad.CLIMB),
        ),
    ),
    occRow(
        key = "armyOfficer", label = "军官", industry = Industry.UNIFORM,
        six = six(Posture.SIT, Dirt.CLEAN, Dress.UNIFORM, Sched.DAY, Risk.NONE),
        env = env(Place.INDOOR, Noise.MID, Dust.NONE, Temp.AMBIENT, Moist.DRY, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.FLY_OPEN),
        burden = burden(Carry.NONE, NO_REPEAT, Stand.MID, Shift.FIXED_DAY),
        drives = drives(Dir.DOWN, Dir.FLAT, Dir.UP, Dir.FLAT, MECH_STATIC),
        service = army(
            ArmyRank.OFFICER, ServiceScene.CAMP_TRAINING, DutyPhase.WATCH, DutyCycle.DAY_ONLY,
            ReturnGap.SAME_DAY, listOf(Gear.DRESS_SUIT, Gear.TRAINING_SUIT, Gear.BOOTS),
            listOf(EnvExtra.NOISE),
            listOf(BodyLoad.LONG_STAND),
        ),
    ),
    // 高寒走 envExtra.ALPINE_COLD：Temp 三档讲的是**作业温区**（常温／炉前／冷库），不拿冷库套边防。
    occRow(
        key = "borderPost", label = "边防哨所执勤", industry = Industry.UNIFORM,
        six = six(Posture.WALK, Dirt.DUST, Dress.UNIFORM, Sched.ROTATING, Risk.NONE),
        env = env(Place.OUTDOOR, Noise.MID, Dust.NONE, Temp.AMBIENT, Moist.DRY, Chem.NONE),
        attire = attire(listOf(Wear.GLOVES), Closure.FLY_OPEN),
        burden = burden(Carry.MID, listOf(Joint.BACK), Stand.HIGH, Shift.IRREGULAR),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.FLAT, MECH_LABOR),
        service = army(
            ArmyRank.NCO, ServiceScene.BORDER_POST, DutyPhase.WATCH, DutyCycle.R24_STATIONED,
            ReturnGap.MULTI_DAY, listOf(Gear.CAMO, Gear.TRAINING_SUIT, Gear.HELMET, Gear.GLOVES, Gear.BOOTS),
            listOf(EnvExtra.ALPINE_COLD, EnvExtra.DUST, EnvExtra.NOISE),
            listOf(BodyLoad.RUCK_MARCH, BodyLoad.LONG_STAND, BodyLoad.CLIMB),
        ),
    ),
    // 舰上的潮湿与机舱噪声走 envExtra；湿度四档里没有「海洋潮湿」这一档，硬套水产是错的（缺口见交付报告）。
    occRow(
        key = "vesselCrew", label = "舰艇值更", industry = Industry.UNIFORM,
        six = six(Posture.STAND, Dirt.OIL, Dress.UNIFORM, Sched.ROTATING, Risk.TOOL),
        env = env(Place.INDOOR, Noise.LOUD, Dust.NONE, Temp.AMBIENT, Moist.GENERAL, Chem.SOLVENT),
        attire = attire(listOf(Wear.GLOVES), Closure.FLY_OPEN),
        burden = burden(Carry.LIGHT, listOf(Joint.WRIST), Stand.HIGH, Shift.THREE_SHIFT),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.FLAT, MECH_LABOR),
        service = army(
            ArmyRank.NCO, ServiceScene.VESSEL, DutyPhase.DAILY, DutyCycle.ROTATING_24H,
            ReturnGap.MULTI_DAY, listOf(Gear.DRESS_SUIT, Gear.TRAINING_SUIT, Gear.BOOTS, Gear.GLOVES),
            listOf(EnvExtra.WATER_WET, EnvExtra.NOISE),
            listOf(BodyLoad.CLIMB, BodyLoad.LONG_STAND),
        ),
    ),
    occRow(
        key = "aircraftMaint", label = "机务维护", industry = Industry.UNIFORM,
        six = six(Posture.SQUAT, Dirt.OIL, Dress.UNIFORM, Sched.DAY, Risk.TOOL),
        env = env(Place.MIXED, Noise.LOUD, Dust.NONE, Temp.AMBIENT, Moist.DRY, Chem.SOLVENT),
        attire = attire(listOf(Wear.GLOVES, Wear.MASK), Closure.FLY_OPEN),
        burden = burden(Carry.MID, listOf(Joint.SHOULDER, Joint.WRIST), Stand.HIGH, Shift.LONG_DAY),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.UP, MECH_LABOR),
        service = army(
            ArmyRank.NCO, ServiceScene.AIRCRAFT_MAINT, DutyPhase.DAILY, DutyCycle.DAY_ONLY,
            ReturnGap.SAME_DAY, listOf(Gear.TRAINING_SUIT, Gear.HELMET, Gear.GLOVES, Gear.BOOTS),
            listOf(EnvExtra.NOISE, EnvExtra.DUST),
            listOf(BodyLoad.CLIMB, BodyLoad.LONG_STAND),
        ),
    ),
    occRow(
        key = "commsSoldier", label = "通信值机", industry = Industry.UNIFORM,
        six = six(Posture.SIT, Dirt.CLEAN, Dress.UNIFORM, Sched.ROTATING, Risk.NONE),
        env = env(Place.INDOOR, Noise.MID, Dust.NONE, Temp.AMBIENT, Moist.DRY, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.FLY_OPEN),
        burden = burden(Carry.LIGHT, listOf(Joint.WRIST), Stand.LOW, Shift.THREE_SHIFT),
        drives = drives(Dir.DOWN, Dir.FLAT, Dir.FLAT, Dir.FLAT, MECH_STATIC),
        service = army(
            ArmyRank.CONSCRIPT, ServiceScene.COMM, DutyPhase.DISPATCH, DutyCycle.ROSTER_SHIFT,
            ReturnGap.SAME_DAY, listOf(Gear.TRAINING_SUIT, Gear.BOOTS),
            listOf(EnvExtra.NOISE),
            listOf(BodyLoad.LONG_STAND, BodyLoad.SPRINT),
        ),
    ),
    occRow(
        key = "militaryMedic", label = "军卫勤", industry = Industry.UNIFORM,
        six = six(Posture.CARRY, Dirt.GENERAL, Dress.UNIFORM, Sched.ROTATING, Risk.PATHOGEN),
        env = env(Place.INDOOR, Noise.MID, Dust.NONE, Temp.AMBIENT, Moist.GENERAL, Chem.DISINFECTANT),
        attire = attire(CLINIC_WEARS, Closure.FLY_OPEN),
        burden = burden(Carry.HEAVY, listOf(Joint.BACK, Joint.SHOULDER), Stand.HIGH, Shift.IRREGULAR),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.UP, MECH_LABOR),
        service = army(
            ArmyRank.NCO, ServiceScene.SERVICE_MEDICAL, DutyPhase.DISPATCH, DutyCycle.R24_STATIONED,
            ReturnGap.NEXT_DAY, listOf(Gear.DRESS_SUIT, Gear.GLOVES, Gear.BOOTS),
            listOf(EnvExtra.NOISE, EnvExtra.DUST),
            listOf(BodyLoad.RUCK_MARCH, BodyLoad.LONG_STAND),
        ),
    ),
    // 背带式装备这一格走 881 的开合形态交接（连体／背带＝解手更麻烦），纯结构事实，不含评价。
    occRow(
        key = "patrolPolice", label = "巡警", industry = Industry.UNIFORM,
        six = six(Posture.WALK, Dirt.GENERAL, Dress.UNIFORM, Sched.ROTATING, Risk.PUBLIC_SAFETY),
        env = env(Place.OUTDOOR, Noise.LOUD, Dust.CEMENT, Temp.AMBIENT, Moist.GENERAL, Chem.NONE),
        attire = attire(listOf(Wear.REFLECTIVE), Closure.HARNESS_BACK),
        burden = burden(Carry.NONE, NO_REPEAT, Stand.HIGH, Shift.THREE_SHIFT),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.UP, MECH_LABOR),
        service = police(
            PolicePost.PATROL, DutyCycle.ROTATING_24H, ReturnGap.SAME_DAY,
            listOf(Gear.DRESS_SUIT, Gear.TACTICAL_VEST, Gear.BOOTS),
            listOf(EnvExtra.NOISE, EnvExtra.DUST), listOf(BodyLoad.LONG_STAND, BodyLoad.SPRINT),
        ),
    ),
    occRow(
        key = "trafficPolice", label = "交警", industry = Industry.UNIFORM,
        six = six(Posture.STAND, Dirt.DUST, Dress.UNIFORM, Sched.DAY, Risk.PUBLIC_SAFETY),
        env = env(Place.OUTDOOR, Noise.LOUD, Dust.CEMENT, Temp.AMBIENT, Moist.GENERAL, Chem.NONE),
        attire = attire(listOf(Wear.REFLECTIVE), Closure.FLY_OPEN),
        burden = burden(Carry.NONE, NO_REPEAT, Stand.VERY_HIGH, Shift.LONG_DAY),
        drives = drives(Dir.FLAT, Dir.UP, Dir.UP, Dir.UP, MECH_LABOR),
        service = police(
            PolicePost.TRAFFIC, DutyCycle.DAY_ONLY, ReturnGap.SAME_DAY,
            listOf(Gear.DRESS_SUIT, Gear.BOOTS),
            listOf(EnvExtra.NOISE, EnvExtra.DUST), listOf(BodyLoad.LONG_STAND),
        ),
    ),
    occRow(
        key = "swatOfficer", label = "特警", industry = Industry.UNIFORM,
        six = six(Posture.CARRY, Dirt.GENERAL, Dress.UNIFORM, Sched.ROTATING, Risk.PUBLIC_SAFETY),
        env = env(Place.MIXED, Noise.LOUD, Dust.NONE, Temp.AMBIENT, Moist.GENERAL, Chem.NONE),
        attire = attire(listOf(Wear.GLOVES), Closure.HARNESS_BACK),
        burden = burden(Carry.HEAVY, listOf(Joint.SHOULDER, Joint.BACK), Stand.HIGH, Shift.IRREGULAR),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.UP, MECH_LABOR),
        service = police(
            PolicePost.SWAT, DutyCycle.STANDBY, ReturnGap.IRREGULAR,
            listOf(Gear.CAMO, Gear.TACTICAL_VEST, Gear.HELMET, Gear.GLOVES, Gear.BOOTS),
            listOf(EnvExtra.NOISE, EnvExtra.DUST),
            listOf(BodyLoad.RUCK_MARCH, BodyLoad.CLIMB, BodyLoad.SPRINT, BodyLoad.LONG_STAND),
        ),
    ),
    occRow(
        key = "detective", label = "刑警", industry = Industry.UNIFORM,
        six = six(Posture.SIT, Dirt.GENERAL, Dress.CASUAL, Sched.ON_CALL, Risk.PUBLIC_SAFETY),
        env = env(Place.MIXED, Noise.MID, Dust.NONE, Temp.AMBIENT, Moist.DRY, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.HARNESS_BACK),
        burden = burden(Carry.LIGHT, NO_REPEAT, Stand.LOW, Shift.IRREGULAR),
        drives = drives(Dir.DOWN, Dir.FLAT, Dir.UP, Dir.FLAT, MECH_STATIC),
        service = police(
            PolicePost.DETECTIVE, DutyCycle.CASE_TASK, ReturnGap.IRREGULAR,
            listOf(Gear.DRESS_SUIT, Gear.BALLISTIC_VEST, Gear.BOOTS),
            listOf(EnvExtra.NOISE), listOf(BodyLoad.LONG_STAND, BodyLoad.SPRINT),
        ),
    ),
    occRow(
        key = "prisonGuard", label = "狱警", industry = Industry.UNIFORM,
        six = six(Posture.STAND, Dirt.CLEAN, Dress.UNIFORM, Sched.ROTATING, Risk.PUBLIC_SAFETY),
        env = env(Place.INDOOR, Noise.MID, Dust.NONE, Temp.AMBIENT, Moist.DRY, Chem.DISINFECTANT),
        attire = attire(listOf(Wear.GLOVES), Closure.HARNESS_BACK),
        burden = burden(Carry.LIGHT, listOf(Joint.BACK), Stand.HIGH, Shift.THREE_SHIFT),
        drives = drives(Dir.FLAT, Dir.FLAT, Dir.UP, Dir.FLAT, MECH_MINIMAL),
        service = police(
            PolicePost.PRISON, DutyCycle.R24_STATIONED, ReturnGap.NEXT_DAY,
            listOf(Gear.DRESS_SUIT, Gear.BALLISTIC_VEST, Gear.GLOVES, Gear.BOOTS),
            listOf(EnvExtra.NOISE), listOf(BodyLoad.LONG_STAND),
        ),
    ),
    occRow(
        key = "auxiliaryPolice", label = "辅警", industry = Industry.UNIFORM,
        six = six(Posture.WALK, Dirt.GENERAL, Dress.UNIFORM, Sched.ROTATING, Risk.PUBLIC_SAFETY),
        env = env(Place.MIXED, Noise.MID, Dust.CEMENT, Temp.AMBIENT, Moist.GENERAL, Chem.NONE),
        attire = attire(listOf(Wear.REFLECTIVE), Closure.FLY_OPEN),
        burden = burden(Carry.LIGHT, listOf(Joint.BACK), Stand.HIGH, Shift.THREE_SHIFT),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.FLAT, MECH_LABOR),
        service = police(
            PolicePost.AUXILIARY, DutyCycle.ROSTER_SHIFT, ReturnGap.SAME_DAY,
            listOf(Gear.DRESS_SUIT, Gear.BOOTS),
            listOf(EnvExtra.NOISE, EnvExtra.DUST), listOf(BodyLoad.LONG_STAND),
        ),
    ),
    occRow(
        key = "fireFighter", label = "灭火战斗员", industry = Industry.UNIFORM,
        six = six(Posture.CARRY, Dirt.HEAT, Dress.UNIFORM, Sched.ROTATING, Risk.HEAT),
        env = env(Place.MIXED, Noise.LOUD, Dust.NONE, Temp.AMBIENT, Moist.GENERAL, Chem.NONE),
        attire = attire(listOf(Wear.GLOVES), Closure.HARNESS_BACK),
        burden = burden(Carry.HEAVY, listOf(Joint.BACK, Joint.SHOULDER), Stand.HIGH, Shift.THREE_SHIFT),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.UP, MECH_HOT),
        service = fire(
            FirePost.FIGHTER, DutyCycle.R24_STATIONED, ReturnGap.NEXT_DAY,
            listOf(Gear.TRAINING_SUIT, Gear.HELMET, Gear.GLOVES, Gear.BOOTS),
            listOf(EnvExtra.SMOKED_HEAT, EnvExtra.NOISE, EnvExtra.WATER_WET),
            listOf(BodyLoad.SPRINT, BodyLoad.CLIMB, BodyLoad.LONG_STAND, BodyLoad.RUCK_MARCH),
        ),
    ),
    occRow(
        key = "fireDriver", label = "消防车驾驶员", industry = Industry.UNIFORM,
        six = six(Posture.DRIVE, Dirt.HEAT, Dress.UNIFORM, Sched.ROTATING, Risk.HEAT),
        env = env(Place.MIXED, Noise.LOUD, Dust.NONE, Temp.AMBIENT, Moist.GENERAL, Chem.NONE),
        attire = attire(listOf(Wear.GLOVES), Closure.HARNESS_BACK),
        burden = burden(Carry.MID, listOf(Joint.SHOULDER), Stand.MID, Shift.THREE_SHIFT),
        drives = drives(Dir.FLAT, Dir.UP, Dir.UP, Dir.UP, MECH_HOT),
        service = fire(
            FirePost.DRIVER, DutyCycle.ROSTER_SHIFT, ReturnGap.SAME_DAY,
            listOf(Gear.TRAINING_SUIT, Gear.HELMET, Gear.GLOVES, Gear.BOOTS),
            listOf(EnvExtra.SMOKED_HEAT, EnvExtra.NOISE),
            listOf(BodyLoad.LONG_STAND, BodyLoad.SPRINT),
        ),
    ),
    occRow(
        key = "fireComms", label = "消防通信员", industry = Industry.UNIFORM,
        six = six(Posture.SIT, Dirt.CLEAN, Dress.UNIFORM, Sched.ROTATING, Risk.NONE),
        env = env(Place.INDOOR, Noise.MID, Dust.NONE, Temp.AMBIENT, Moist.DRY, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.FLY_OPEN),
        burden = burden(Carry.NONE, listOf(Joint.WRIST), Stand.LOW, Shift.THREE_SHIFT),
        drives = drives(Dir.DOWN, Dir.FLAT, Dir.FLAT, Dir.FLAT, MECH_STATIC),
        service = fire(
            FirePost.COMMS, DutyCycle.DISPATCH, ReturnGap.SAME_DAY,
            listOf(Gear.DRESS_SUIT, Gear.BOOTS),
            listOf(EnvExtra.NOISE), listOf(BodyLoad.LONG_STAND),
        ),
    ),
    occRow(
        key = "firePrevention", label = "防火监督", industry = Industry.UNIFORM,
        six = six(Posture.WALK, Dirt.DUST, Dress.UNIFORM, Sched.DAY, Risk.NONE),
        env = env(Place.MIXED, Noise.MID, Dust.CEMENT, Temp.AMBIENT, Moist.DRY, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.FLY_OPEN),
        burden = burden(Carry.LIGHT, NO_REPEAT, Stand.MID, Shift.FIXED_DAY),
        drives = drives(Dir.FLAT, Dir.FLAT, Dir.UP, Dir.FLAT, MECH_MINIMAL),
        service = fire(
            FirePost.PREVENTION, DutyCycle.DAY_ONLY, ReturnGap.SAME_DAY,
            listOf(Gear.DRESS_SUIT, Gear.BOOTS),
            listOf(EnvExtra.DUST, EnvExtra.NOISE), listOf(BodyLoad.LONG_STAND),
        ),
    ),
)
