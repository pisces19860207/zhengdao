// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百八十二批 · 分表一：采矿建筑（5）＋制造装配（4）。
// 第一期①点名的钢筋／架子／塔吊／水电、机加／焊工／装配／质检都在这张表上，另补采矿一行（行业名里有它）。
//
// 每行都必须把六维、环境、着装、负荷、四面驱动填齐——判据③不许出现「只有名字」的一行。
// 方向一律是**倾向**（[Spread.LARGE] 个体差异大），不是断定；本表一个字的清洁／卫生档都不产出（判据④）。
package com.example.zhengdao.baopu.state.occupation

internal val TRADE_ROWS: List<OccupationRow> = listOf(
    occRow(
        key = "rebar", label = "钢筋工", industry = Industry.MINE_BUILD,
        six = six(Posture.CARRY, Dirt.DUST, Dress.WORKWEAR, Sched.DAY, Risk.HEIGHT),
        env = env(Place.OUTDOOR, Noise.LOUD, Dust.CEMENT, Temp.AMBIENT, Moist.GENERAL, Chem.NONE),
        attire = attire(SITE_HARD, Closure.FLY_OPEN),
        burden = burden(Carry.HEAVY, listOf(Joint.WRIST, Joint.BACK, Joint.SHOULDER), Stand.HIGH, Shift.FIXED_DAY),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.UP, MECH_LABOR),
    ),
    occRow(
        key = "scaffolder", label = "架子工", industry = Industry.MINE_BUILD,
        six = six(Posture.CLIMB, Dirt.DUST, Dress.WORKWEAR, Sched.DAY, Risk.HEIGHT),
        env = env(Place.OUTDOOR, Noise.MID, Dust.CEMENT, Temp.AMBIENT, Moist.GENERAL, Chem.NONE),
        attire = attire(SITE_VISOR, Closure.FLY_OPEN),
        burden = burden(Carry.MID, listOf(Joint.SHOULDER, Joint.BACK), Stand.HIGH, Shift.FIXED_DAY),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.UP, MECH_LABOR),
    ),
    // 上塔用全身式安全带＝背带式：这一行的着装开合形态必须走 881 那条交接（工厂会自动带上）。
    occRow(
        key = "craneOperator", label = "塔吊司机", industry = Industry.MINE_BUILD,
        six = six(Posture.SIT, Dirt.GENERAL, Dress.WORKWEAR, Sched.LONG_DAY, Risk.HEIGHT),
        env = env(Place.MIXED, Noise.MID, Dust.CEMENT, Temp.AMBIENT, Moist.DRY, Chem.NONE),
        attire = attire(SITE_VISOR, Closure.HARNESS_BACK),
        burden = burden(Carry.NONE, listOf(Joint.WRIST), Stand.LOW, Shift.LONG_DAY),
        drives = drives(Dir.DOWN, Dir.FLAT, Dir.UP, Dir.FLAT, MECH_STATIC),
    ),
    occRow(
        key = "plumberElectric", label = "水电工", industry = Industry.MINE_BUILD,
        six = six(Posture.SQUAT, Dirt.GENERAL, Dress.WORKWEAR, Sched.ON_CALL, Risk.TOOL),
        env = env(Place.MIXED, Noise.MID, Dust.CEMENT, Temp.AMBIENT, Moist.GENERAL, Chem.NONE),
        attire = attire(SITE_HARD, Closure.FLY_OPEN),
        burden = burden(Carry.LIGHT, listOf(Joint.WRIST, Joint.SHOULDER), Stand.MID, Shift.IRREGULAR),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.UP, MECH_LABOR),
    ),
    occRow(
        key = "miner", label = "井下采掘", industry = Industry.MINE_BUILD,
        six = six(Posture.SQUAT, Dirt.DUST, Dress.WORKWEAR, Sched.ROTATING, Risk.TOOL),
        env = env(Place.INDOOR, Noise.LOUD, Dust.CEMENT, Temp.AMBIENT, Moist.GENERAL, Chem.NONE),
        attire = attire(listOf(Wear.HELMET, Wear.COVERALL, Wear.GLOVES, Wear.MASK), Closure.COVERALL_NO_FLY),
        burden = burden(Carry.HEAVY, listOf(Joint.BACK, Joint.SHOULDER), Stand.HIGH, Shift.THREE_SHIFT),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.UP, MECH_LABOR),
    ),
    // 机加工有条反向常识：旋转件旁不戴手套，所以这一行的防护件列表是空的，别照抄别的车间。
    occRow(
        key = "machinist", label = "机加工", industry = Industry.MAKE,
        six = six(Posture.STAND, Dirt.OIL, Dress.WORKWEAR, Sched.ROTATING, Risk.TOOL),
        env = env(Place.INDOOR, Noise.LOUD, Dust.METAL, Temp.AMBIENT, Moist.GENERAL, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.FLY_OPEN),
        burden = burden(Carry.LIGHT, listOf(Joint.WRIST), Stand.MID, Shift.THREE_SHIFT),
        drives = drives(Dir.FLAT, Dir.UP, Dir.UP, Dir.FLAT, MECH_FINE),
    ),
    occRow(
        key = "welder", label = "焊工", industry = Industry.MAKE,
        six = six(Posture.SQUAT, Dirt.DUST, Dress.WORKWEAR, Sched.DAY, Risk.HEAT),
        env = env(Place.MIXED, Noise.MID, Dust.METAL, Temp.AMBIENT, Moist.DRY, Chem.NONE),
        attire = attire(listOf(Wear.WORK_PANTS, Wear.GLOVES, Wear.MASK), Closure.FLY_OPEN),
        burden = burden(Carry.MID, listOf(Joint.WRIST, Joint.SHOULDER), Stand.HIGH, Shift.FIXED_DAY),
        drives = drives(Dir.UP, Dir.UP, Dir.UP, Dir.UP, MECH_HOT),
    ),
    occRow(
        key = "assembler", label = "装配工", industry = Industry.MAKE,
        six = six(Posture.STAND, Dirt.GENERAL, Dress.WORKWEAR, Sched.ROTATING, Risk.TOOL),
        env = env(Place.INDOOR, Noise.MID, Dust.NONE, Temp.AMBIENT, Moist.GENERAL, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.FLY_OPEN),
        burden = burden(Carry.LIGHT, listOf(Joint.WRIST, Joint.SHOULDER), Stand.HIGH, Shift.THREE_SHIFT),
        drives = drives(Dir.FLAT, Dir.UP, Dir.UP, Dir.FLAT, MECH_MINIMAL),
    ),
    // 质检的 [Dirt.CLEAN] 说的是**作业面无尘**，不是人的状态；这一格与清洁档没有任何连线（C5 会核）。
    occRow(
        key = "qualityInspector", label = "质检", industry = Industry.MAKE,
        six = six(Posture.STAND, Dirt.CLEAN, Dress.WORKWEAR, Sched.DAY, Risk.NONE),
        env = env(Place.INDOOR, Noise.QUIET, Dust.NONE, Temp.AMBIENT, Moist.DRY, Chem.NONE),
        attire = attire(NO_PROTECTIVE, Closure.FLY_OPEN),
        burden = burden(Carry.NONE, listOf(Joint.WRIST), Stand.MID, Shift.FIXED_DAY),
        drives = drives(Dir.FLAT, Dir.FLAT, Dir.FLAT, Dir.FLAT, MECH_MINIMAL),
    ),
)
