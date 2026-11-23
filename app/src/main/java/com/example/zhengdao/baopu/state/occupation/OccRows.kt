// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百八十二批 · 表工厂：把「一行的十六格」压成可读的四到五行，四张分表共用同一套构造。
//
// 交接面 [handsOf] 是**算出来的**，不是每行手抄的：
// · 四面驱动恒定；
// · 军警消行恒带 DUTY_WINDOW_883（时段只喂 883 的条件档）；
// · 连体／背带式着装恒带 STRUCTURE_FLY_881（喂 881 的开合形态）；
// · 第二期某一槽从占位转成已填，才追加对应交接（C5 会双向核：少了红、多了也红）。
// 这样「忘了写联动」在构造阶段就发生不了，而「把职业接到清洁档上」仍然一测就红。
package com.example.zhengdao.baopu.state.occupation

/** 六维②–⑥五格（行业轴另存在行上，凑成六维）。 */
internal fun six(
    posture: Posture?, dirt: Dirt?, dress: Dress?, sched: Sched?, risk: Risk?,
): SixDims = SixDims(posture, dirt, dress, sched, risk)

/** 第一期②环境六格。 */
internal fun env(
    place: Place?, noise: Noise?, dust: Dust?, temp: Temp?, moist: Moist?, chem: Chem?,
): EnvProfile = EnvProfile(place, noise, dust, temp, moist, chem)

/** 第一期③着装与防护。 */
internal fun attire(items: List<Wear>, closure: Closure?): AttireProfile = AttireProfile(items, closure)

/** 第一期④劳动负荷四格。 */
internal fun burden(
    carry: Carry?, repeats: List<Joint>, standing: Stand?, shift: Shift?,
): BurdenProfile = BurdenProfile(carry, repeats, standing, shift)

/** 四面驱动＋机制＋个体差异档（派工单判据②③：方向与个体差异两个都得有）。 */
internal fun drives(
    build: Dir?, skin: Dir?, wear: Dir?, sweat: Dir?,
    mechs: Set<Mech>, spread: Spread? = Spread.LARGE,
): DriveSet = DriveSet(build, skin, wear, sweat, mechs, spread)

/** 军：岗位＋场景＋使命期＋值班形态＋时段＋装备＋环境附加＋负荷。 */
internal fun army(
    rank: ArmyRank?, scene: ServiceScene?, mission: DutyPhase?, cycle: DutyCycle?, gap: ReturnGap?,
    gear: List<Gear>?, extra: List<EnvExtra>?, load: List<BodyLoad>?,
): ServiceSlot = ServiceSlot(Arm.ARMY, rank, null, null, scene, mission, cycle, gap, gear, extra, load)

/** 警：岗位不设场景与使命期两格（那是军的字段）。 */
internal fun police(
    post: PolicePost?, cycle: DutyCycle?, gap: ReturnGap?,
    gear: List<Gear>?, extra: List<EnvExtra>?, load: List<BodyLoad>?,
): ServiceSlot = ServiceSlot(Arm.POLICE, null, post, null, null, null, cycle, gap, gear, extra, load)

/** 消：同上。 */
internal fun fire(
    post: FirePost?, cycle: DutyCycle?, gap: ReturnGap?,
    gear: List<Gear>?, extra: List<EnvExtra>?, load: List<BodyLoad>?,
): ServiceSlot = ServiceSlot(Arm.FIRE, null, null, post, null, null, cycle, gap, gear, extra, load)

/** 宗教职七格。 */
internal fun faithSlot(
    branch: FaithBranch?, celibate: Celibate?, diet: FaithDiet?, hair: HairRule?,
    dress: FaithDress?, residence: FaithResidence?, basis: Set<FaithBasis>?,
): FaithSlot = FaithSlot(branch, celibate, diet, hair, dress, residence, basis)

/** 按行内容算出交接面；四张分表都走这一个函数，避免手抄漏项。 */
internal fun handsOf(service: ServiceSlot?, att: AttireProfile?, p2: Phase2Slots?): List<Handoff> {
    val out = DRIVE_HANDS.toMutableList()
    if (service != null) out.add(Handoff.DUTY_WINDOW_883)
    if (att?.needsFlyHandoff() == true) out.add(Handoff.STRUCTURE_FLY_881)
    if (p2 != null && p2.habitat != null && p2.habitat.status == SlotStatus.SET) out.add(Handoff.HABITAT_883)
    if (p2 != null && p2.traces != null && p2.traces.status == SlotStatus.SET) out.add(Handoff.TRACE_BODY_805)
    if (p2 != null && p2.odor != null && p2.odor.status == SlotStatus.SET) out.add(Handoff.ODOR_SOURCE_827)
    return out
}

/**
 * 一行工种的完整构造。[hands] 不写在参数表里，由 [handsOf] 依内容算出——
 * 想注入违规交接的负例，用 `row.copy(hands = ...)`，不要改这个工厂。
 */
internal fun occRow(
    key: String,
    label: String,
    industry: Industry?,
    six: SixDims?,
    env: EnvProfile?,
    attire: AttireProfile?,
    burden: BurdenProfile?,
    drives: DriveSet?,
    service: ServiceSlot? = null,
    faith: FaithSlot? = null,
    phase2: Phase2Slots? = Phase2Slots.placeholders(),
): OccupationRow = OccupationRow(
    key = key, label = label, industry = industry, six = six, env = env,
    attire = attire, burden = burden, drives = drives, hands = handsOf(service, attire, phase2),
    service = service, faith = faith, phase2 = phase2,
)

/** 表内高频组合的短名，省掉每行重复写一长串枚举限定名。 */
internal val NO_PROTECTIVE: List<Wear> = emptyList()

/** 重复部位：无（久坐类岗位不该硬凑一个部位出来）。 */
internal val NO_REPEAT: List<Joint> = emptyList()

/** 常规工装组合：安全帽＋工装裤＋手套。 */
internal val SITE_HARD: List<Wear> = listOf(Wear.HELMET, Wear.WORK_PANTS, Wear.GLOVES)

/** 高可视组合：安全帽＋反光衣＋工装裤。 */
internal val SITE_VISOR: List<Wear> = listOf(Wear.HELMET, Wear.REFLECTIVE, Wear.WORK_PANTS)

/** 厨房组合：厨师服＋口罩（后厨的口罩是卫生操作要求，不是个人清洁评价）。 */
internal val KITCHEN_WEARS: List<Wear> = listOf(Wear.CHEF_JACKET, Wear.MASK)

/** 医疗组合：白大褂＋口罩。 */
internal val CLINIC_WEARS: List<Wear> = listOf(Wear.WHITE_COAT, Wear.MASK)

/**
 * 机制组合的短名。C3 只核**给了 ↑／↓ 的轴**：每根轴至少要有一个自己的机制在集合里
 * （体型要体力、皮肤要摩擦或暴露、磨损要摩擦、汗要发热或体力）；中性不需要因果。
 * 所以集合按岗位实配，不许图省事全填四个——全填等于这条闸没有牙。
 */
internal val MECH_LABOR: Set<Mech> = setOf(Mech.LOAD, Mech.FRICTION, Mech.EXPOSURE)

/** 高温岗（炉前／炒锅／火场）：发热是这一格的因，必须进集合。 */
internal val MECH_HOT: Set<Mech> = setOf(Mech.LOAD, Mech.FRICTION, Mech.EXPOSURE, Mech.HEAT)

/** 室内精细岗：摩擦＋暴露。 */
internal val MECH_FINE: Set<Mech> = setOf(Mech.FRICTION, Mech.EXPOSURE)

/** 驾驶／久坐岗：静态体力负荷＋摩擦。 */
internal val MECH_STATIC: Set<Mech> = setOf(Mech.LOAD, Mech.FRICTION)

/** 四面几乎全中性的岗：只留一个摩擦（衣物接触），不给多余因果。 */
internal val MECH_MINIMAL: Set<Mech> = setOf(Mech.FRICTION)
