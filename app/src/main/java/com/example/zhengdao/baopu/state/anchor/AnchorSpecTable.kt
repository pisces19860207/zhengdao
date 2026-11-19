// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第七百四十七批 · 人物锚定：§二「七维度 × 字段全表」的逐行转写。
// 真源＝规格 md §二（142 行）＋§三 L1 标签表；本文件是它的机器副本，不是第二份口径：
// 验收方拿 specRow 回查 md 原文即可，一行一行对得上。
//
// 转写口径三条（对账口在交付报告 §二）：
// · 一规格行＝一 FieldSpec：行名进 label、取值格进 words、说明格进 note，不合并改写语义；
// · 同一落点在 §二④ 的多张表里重复出现时取**档并集、说明两表照抄**，共 12 行并表
//   （阴毛 4 ＋ 乳头乳晕 6 ＋ 按部位后列表 2）；§九「女表是否与男表共用同一套」未拍板 ⇒ ○ 待核；
// · §二④「按部位」行给的是**落点**（填什么）而非档 ⇒ mode=FREE，words 列落点、note 留原文；
//   「分级」行（细分表、乳头乳晕表）给的是**封闭档** ⇒ mode=CLOSED，表外词由校验直接拒。
//
// 判据⑥ 在本文件成立：id／label／group／words 四列没有任何性别或性取向标签——性别词只出现在
// specRow 与 note 里，那是「这行抄自规格哪张表」的文献出处，不参与取值、不进面板字段名。
package com.example.zhengdao.baopu.state.anchor

/** 一行规格：封闭档表（规格「分级」列）。 */
private fun grade(
    id: String,
    dim: DimKind,
    label: String,
    row: String,
    words: String,
    note: String = "",
    multi: Boolean = false,
    group: String = "",
) = FieldSpec(
    id = id, dim = dim, label = label, specRow = "§二·$row",
    words = words.split(W).filter { it.isNotBlank() }, mode = FillMode.CLOSED,
    multi = multi, note = note, group = group,
)

/** 一行规格：落点表（按部位「填什么」行、数值行、性癖加权项）。 */
private fun slot(
    id: String,
    dim: DimKind,
    label: String,
    row: String,
    words: String = "",
    note: String = "",
    multi: Boolean = false,
    unit: String = "",
    group: String = "",
) = FieldSpec(
    id = id, dim = dim, label = label, specRow = "§二·$row",
    words = words.split(W).filter { it.isNotBlank() }, mode = FillMode.FREE,
    multi = multi, unit = unit, note = note, group = group,
)

private const val W = "／"

// ── ① 五官（11 行）───────────────────────────────────────────────────
private val FACE = listOf(
    grade("face.shape", DimKind.FACE, "脸型", "①五官", "鹅蛋／方／长／圆／心形／菱形"),
    grade("face.eyes", DimKind.FACE, "眼", "①五官", "单／双／内双／杏眼／凤眼／下垂眼／圆眼／三白眼",
        note = "规格该行两组并列：眼皮（单／双／内双）＋眼型（五种）", multi = true),
    grade("face.eyeDistance", DimKind.FACE, "眼距", "①五官", "宽／中／窄"),
    grade("face.pupil", DimKind.FACE, "瞳色", "①五官", "黑／深棕／浅棕／灰／异色"),
    grade("face.nose", DimKind.FACE, "鼻", "①五官", "高挺／塌／鹰钩／小翘／鼻头肉"),
    grade("face.lips", DimKind.FACE, "唇", "①五官", "薄／厚／上薄下厚／唇色深／唇色浅／嘴角上扬／嘴角下垂",
        note = "规格原文「唇色深浅」按深浅两档展开", multi = true),
    grade("face.brows", DimKind.FACE, "眉", "①五官", "浓／淡／平／剑／弯／断",
        note = "「弯」是眉形档（判据⑥ 口径见交付报告 §五）", multi = true),
    grade("face.ears", DimKind.FACE, "耳", "①五官", "贴／招风／耳垂大／耳垂小", multi = true),
    grade("face.skin", DimKind.FACE, "皮肤", "①五官", "白／黄／麦色／深／细腻／粗糙／痣／疤／斑", multi = true),
    slot("face.teeth", DimKind.FACE, "牙", "①五官", "齐／不齐／虎牙／齿色",
        note = "齿色规格未给档 ⇒ ○ 待核", multi = true),
    grade("face.voice", DimKind.FACE, "声线", "①五官", "低／中／高／哑／清／气声", multi = true),
)

// ── ② 体型（9 行；两行数值走 660 口径，见 AnchorBodyBridge，判据⑦）───
private val BODY = listOf(
    slot("body.height", DimKind.BODY, "身高", "②体型", unit = "cm",
        note = "数值不自治：经 660 HumanBodySpec.heightCm 读数（见 AnchorBodyBridge）"),
    slot("body.weight", DimKind.BODY, "体重", "②体型", unit = "kg",
        note = "数值不自治：经 660 HumanBodySpec.weightKg 读数"),
    grade("body.frame", DimKind.BODY, "骨架", "②体型", "窄／中／宽"),
    grade("body.muscle", DimKind.BODY, "肌肉", "②体型", "无／薄／中／厚"),
    grade("body.fat", DimKind.BODY, "体脂", "②体型", "低／中／高", note = "规格说明：低＝见筋，高＝有肉"),
    grade("body.ratio", DimKind.BODY, "肩腰臀", "②体型", "肩宽／腰细／臀翘",
        note = "规格：三项比例；肩宽数值走 660 BodyFrame.Ratios.shoulder", multi = true),
    grade("body.legs", DimKind.BODY, "腿", "②体型", "长／短／直／O／X",
        note = "「直」是腿型档；660 无腿型口径 ⇒ 本行由本表自持", multi = true),
    grade("body.chest", DimKind.BODY, "胸", "②体型", "平／小／中／大／圆／垂／外扩", multi = true),
    slot("body.hands", DimKind.BODY, "手", "②体型", "大／小／指长／指短／骨节粗／骨节细／指甲",
        note = "指甲规格未给档 ⇒ ○ 待核", multi = true),
)

// ── ③ 体态（7 行）───────────────────────────────────────────────────
private val POSTURE = listOf(
    grade("posture.standing", DimKind.POSTURE, "站姿", "③体态", "直／松／含胸／歪", note = "「直」是站姿档"),
    grade("posture.walking", DimKind.POSTURE, "走路", "③体态", "大步／碎步／晃／拖"),
    grade("posture.sitting", DimKind.POSTURE, "坐姿", "③体态", "张腿／并腿／翘腿／盘腿"),
    grade("posture.habit", DimKind.POSTURE, "习惯动作", "③体态", "摸脖子／抖腿／转笔／咬唇／抠手／捋头发", multi = true),
    grade("posture.scent", DimKind.POSTURE, "气味", "③体态", "淡／浓／皂／汗／烟／香水／体味", multi = true),
    grade("posture.temp", DimKind.POSTURE, "体温", "③体态", "偏凉／正常／偏高"),
    grade("posture.skinFeel", DimKind.POSTURE, "皮肤质感", "③体态", "光滑／粗糙／多毛／有痣", multi = true),
)

// ── ④ 性器外观（规格 57 行 → 45 字段，12 行并表）─────────────────────
// 规格明写「不许把阴茎当一个整体写」⇒ 按部位拆，每个部位都是独立落点、可单独填。
private val GENITAL_PARTS_M = listOf(
    slot("g.root", DimKind.GENITAL, "阴茎根", "④·按部位（表一）", "耻骨区脂肪厚薄／根部直径／阴毛过渡带",
        note = "对应 02 条目：阴茎根、阴毛"),
    slot("g.shaft", DimKind.GENITAL, "柱身", "④·按部位（表一）", "皮肤色／青筋／弯曲",
        note = "青筋：不明显·细浮起·盘绕｜弯曲：直·左·右·上翘·下垂·扭转（「直」是柱身形态档）"),
    slot("g.sulcus", DimKind.GENITAL, "冠状沟", "④·按部位（表一）", "深浅／是否藏垢／有没有珍珠疹"),
    slot("g.corona", DimKind.GENITAL, "龟头冠", "④·按部位（表一）", "后缘隆起程度／圆润度",
        note = "后缘隆起：平·微隆·明显伞状"),
    slot("g.glansTip", DimKind.GENITAL, "龟头顶端", "④·按部位（表一）", "几何形态",
        note = "几何形态四档：钝圆·偏尖·扁平·蘑菇型"),
    grade("g.meatusForm", DimKind.GENITAL, "尿道口·形", "④·按部位（表一）", "纵缝／圆孔／偏大／位置偏移"),
    slot("g.frenulum", DimKind.GENITAL, "系带", "④·按部位（表一）", "长短／紧度／有无撕裂史"),
    grade("g.prepuce", DimKind.GENITAL, "包皮", "④·按部位（表一）", "未割半包／未割全包能翻／过长／已割",
        note = "另有落点「翻起时的松紧」，规格未给档 ⇒ ○ 待核"),
    slot("g.scrotum", DimKind.GENITAL, "阴囊", "④·按部位（表一）", "紧松／皮纹深浅／颜色／温度变化"),
    slot("g.testis", DimKind.GENITAL, "睾丸", "④·按部位（表一）", "大小／一高一低／精索条索感"),
    slot("g.overall", DimKind.GENITAL, "整体", "④·按部位（表一）", "疲软尺寸／勃起尺寸／直径／硬度分级／颜色",
        note = "规格硬上限：勃起尺寸 ≤16、直径 ≤5.5（超限由校验拒）"),
)

private val GENITAL_GRADES = listOf(
    grade("g.testisVolume", DimKind.GENITAL, "睾丸·体积", "④·细分", "小／中／大",
        note = "小＝偏软一把能攥住，大＝沉、有分量；规格以 Prader 睾丸计量的差异感为参照"),
    grade("g.testisPosition", DimKind.GENITAL, "睾丸·位置", "④·细分", "对称／一高一低／位置偏高",
        note = "一高一低以左低最常见；不对称是常态，不是异常"),
    grade("g.testisMobility", DimKind.GENITAL, "睾丸·可动性", "④·细分", "滑动好／固定", note = "精索长短决定"),
    grade("g.scrotumLaxity", DimKind.GENITAL, "阴囊·松弛度", "④·细分", "紧收／中等／明显下垂",
        note = "紧收＝冷·刚做完·紧张；下垂＝热·久站·年纪·久未释放；温度调节，不是病"),
    grade("g.scrotumHang", DimKind.GENITAL, "阴囊·下垂长度", "④·细分", "贴底／垂到会阴／垂过会阴",
        note = "年纪越大越垂（提睾肌松弛）"),
    grade("g.scrotumRugae", DimKind.GENITAL, "阴囊·皮纹", "④·细分", "细密／深皱／偏平滑", note = "皮薄，看得到底下血管"),
    grade("g.scrotumHair", DimKind.GENITAL, "阴囊·毛发", "④·细分", "浓密／稀疏／无毛／单侧无毛",
        note = "无毛是真实的个体差异，单侧不对称也有案例"),
    grade("g.scrotumTemp", DimKind.GENITAL, "阴囊·温度", "④·细分", "偏凉／体温", note = "常态比体温低一点，这是对的"),
    grade("g.pubicHairSpread", DimKind.GENITAL, "阴毛·分布", "④·细分＋阴毛表", "浓密／适中／稀疏／无／倒三角",
        note = "并表：两表各一套分布档，取并集（○ 待核：§九 未拍板两套是否共用）"),
    grade("g.pubicHairForm", DimKind.GENITAL, "阴毛·形态", "④·细分＋阴毛表", "卷曲／直／粗硬／柔软",
        note = "并表；跟发质不一定一致；「直」是毛流档（判据⑥ 口径）"),
    grade("g.pubicHairBorder", DimKind.GENITAL, "阴毛·边界", "④·细分＋阴毛表",
        "到肚脐／到腹股沟／只到根部／到肚脐以下／齐腹股沟／只到阴阜", note = "并表；剃过的人边界是硬的"),
    grade("g.pubicHairColor", DimKind.GENITAL, "阴毛·颜色", "④·细分＋阴毛表", "与发色一致／更深／带白",
        note = "并表（两表同档）；白毛通常先从阴毛开始"),
)

private val GENITAL_NIPPLE = listOf(
    grade("g.nippleSize", DimKind.GENITAL, "乳头·大小", "④·乳头乳晕两表", "小／中／大",
        note = "并表。先列表带参照物：米粒／黄豆／花生、显眼；跟胸肌厚薄不一定成正比"),
    grade("g.nippleForm", DimKind.GENITAL, "乳头·形态", "④·乳头乳晕两表", "平伏／微凸／明显挺立／内陷／突出／平",
        note = "并表；平伏的摸起来像找不到"),
    grade("g.nippleInversion", DimKind.GENITAL, "乳头·内陷分级", "④·乳头乳晕两表", "1级／2级／3级",
        note = "1 级一拉就出能维持／2 级拉得出松手回／3 级拉不出；后列表原文「分级同上」"),
    grade("g.areolaSize", DimKind.GENITAL, "乳晕·大小", "④·乳头乳晕两表", "小／中／大／特大",
        note = "并表，一律用绝对直径：先列表 小<2cm·中 2-3·大 3-4·特大 >4（少见）；" +
            "后列表 非孕 小<3·中 3-4·大 4-5·特大 >5，妊娠期可到 5-10cm"),
    grade("g.areolaColor", DimKind.GENITAL, "乳晕·颜色", "④·乳头乳晕两表", "粉／浅褐／深褐／近乎黑／玫瑰／褐／深",
        note = "并表；跟肤色走但比周围深。规格明写颜色不跟着兴奋变深（孕期与年龄除外）"),
    grade("g.areolaBorder", DimKind.GENITAL, "乳晕·边缘", "④·乳头乳晕两表", "清晰锐利／渐变模糊／清晰／模糊／蒙哥马利腺明显",
        note = "并表；一圈小疙瘩摸得到"),
    grade("g.areolaHair", DimKind.GENITAL, "乳晕·毛发", "④·乳头乳晕（表一）", "无／单根／几根／一圈",
        note = "长在边缘，不常有人注意到"),
    grade("g.nippleSensitivity", DimKind.GENITAL, "乳头·敏感度", "④·乳头乳晕两表", "无感／一般／敏感／一碰就硬／极高",
        note = "并表；后列表「极高＝不碰身体也会硬」"),
    grade("g.nippleReaction", DimKind.GENITAL, "乳头·反应", "④·乳头乳晕（表一）", "被吸会硬／被捏会疼／被舔会抖／被拧会叫",
        note = "反应具体到动作", multi = true),
    grade("g.lactation", DimKind.GENITAL, "哺乳相关", "④·乳头乳晕（表二）", "未哺乳／哺乳期／断奶后",
        note = "哺乳期＝涨、漏、有硬块；断奶后＝变软、颜色变浅"),
)

private val GENITAL_EXTRA = listOf(
    slot("g.crotch", DimKind.GENITAL, "裆部", "④·四条易漏", note = "隔着衣服的轮廓（穿衣服时的状态）"),
    slot("g.gap", DimKind.GENITAL, "空档", "④·四条易漏", note = "不穿内裤的日常摩擦与阈值"),
    slot("g.drip", DimKind.GENITAL, "滴白", "④·四条易漏", note = "无性刺激下的前列腺液自动渗出"),
    slot("g.realismBase", DimKind.GENITAL, "写实基准", "④·四条易漏", note = "全模块的禁止项（防漂锁）"),
)

private val GENITAL_PARTS_V = listOf(
    slot("g.mons", DimKind.GENITAL, "阴阜", "④·按部位（表二）", "脂肪厚度／阴毛分布",
        note = "阴毛分布走 g.pubicHairSpread，不另立字段"),
    slot("g.labiaMajor", DimKind.GENITAL, "大阴唇", "④·按部位（表二）", "饱满／薄／闭合程度／颜色"),
    slot("g.labiaMinor", DimKind.GENITAL, "小阴唇", "④·按部位（表二）", "小／大／薄／厚／对称性／是否外翻"),
    slot("g.clitoris", DimKind.GENITAL, "阴蒂", "④·按部位（表二）", "小藏／中／大露头／包皮松紧"),
    slot("g.meatusPlace", DimKind.GENITAL, "尿道口·位与径", "④·按部位（表二）", "位置／大小",
        note = "规格提醒：这一侧也有，别漏"),
    slot("g.introitus", DimKind.GENITAL, "阴道口", "④·按部位（表二）", "松紧／内壁粗糙／平滑／湿润度"),
    slot("g.perineum", DimKind.GENITAL, "会阴", "④·按部位（表二）", "长度／颜色／是否剃"),
    slot("g.breast", DimKind.GENITAL, "乳房", "④·按部位（表二）", "形状／下垂程度",
        note = "并表：先列表的乳头、乳晕两行并入 g.nippleSize／g.areolaSize 等，不重复立行"),
)

// ── ⑤ 性张力（7 行）─────────────────────────────────────────────────
private val TENSION = listOf(
    grade("t.voice", DimKind.TENSION, "声音", "⑤性张力", "低哑／清亮／气声／鼻音"),
    grade("t.gaze", DimKind.TENSION, "眼神", "⑤性张力", "躲／直／勾／凶／散", note = "「直」是眼神档"),
    grade("t.distance", DimKind.TENSION, "距离感", "⑤性张力", "贴身／正常／疏离"),
    grade("t.bodyOdor", DimKind.TENSION, "体味浓度", "⑤性张力", "无／淡／明显"),
    grade("t.sweat", DimKind.TENSION, "出汗", "⑤性张力", "不易／易／大汗"),
    grade("t.breath", DimKind.TENSION, "呼吸", "⑤性张力", "浅／深／屏"),
    grade("t.speechPace", DimKind.TENSION, "说话节奏", "⑤性张力", "快／慢／停顿多／不连贯"),
)

// ── ⑥ 性能力（12 行，两表各自独立成行）──────────────────────────────
private val ABILITY = listOf(
    grade("a.rigidity", DimKind.ABILITY, "硬度分级", "⑥·表一", "1级／2级／3级／4级",
        note = "规格类比：1 豆腐·2 剥皮香蕉·3 未剥香蕉·4 黄瓜"),
    slot("a.stamina", DimKind.ABILITY, "持久", "⑥·表一", unit = "min",
        note = "分钟数；档「一碰就射·可控·延迟」走 a.control，不重复立行"),
    grade("a.refractory", DimKind.ABILITY, "不应期", "⑥·表一", "短／中／长",
        note = "短＝几分钟，中＝半小时，长＝数小时"),
    grade("a.sensitivity", DimKind.ABILITY, "敏感度", "⑥·表一", "低／一般／高／极高", note = "极高＝一碰就颤"),
    grade("a.semenVolume", DimKind.ABILITY, "精液量", "⑥·表一", "少／中／多"),
    grade("a.control", DimKind.ABILITY, "射精控制", "⑥·表一", "早泄／可控／能忍／干性高潮"),
    grade("a.flowWhileSoft", DimKind.ABILITY, "软着流精", "⑥·表一", "有／无"),
    grade("a.multiples", DimKind.ABILITY, "多次能力", "⑥·表一", "能连／三次后空射／一次就废"),
    grade("a.orgasmEase", DimKind.ABILITY, "高潮难度", "⑥·表二", "易／中／难／必须用手"),
    grade("a.orgasmChain", DimKind.ABILITY, "连续能力", "⑥·表二", "单次／可连／连续到失控"),
    grade("a.squirting", DimKind.ABILITY, "潮吹", "⑥·表二", "会／不会"),
    grade("a.erogenous", DimKind.ABILITY, "敏感点", "⑥·表二", "阴蒂／内壁／G点／乳头", multi = true),
)

// ── ⑦ 性癖（39 项：可多选、每项权重 0–5，档在 AnchorFields.requireKinkWeight）──
private fun kink(group: String, prefix: String, names: List<String>, notes: Map<String, String> = emptyMap()) =
    names.mapIndexed { i, name ->
        slot("$prefix.${i + 1}", DimKind.KINK, name, "⑦·$group", group = group, note = notes[name] ?: "")
    }

private val KINK_PLAY = kink("玩法类", "k.play", listOf(
    "尿道 sounding", "龟头专攻", "阴蒂专攻", "马眼玩弄", "注射play", "灌肠", "哺乳", "挤奶",
    "足", "口", "手", "后穴", "前穴", "双插",
))

private val KINK_BODY = kink("身体改造类", "k.body", listOf("穿刺", "入珠", "纹身", "剃毛", "上环"),
    notes = mapOf("穿刺" to "PA／系带／乳环／Apadravya"))

private val KINK_POWER = kink("权力类", "k.power", listOf("束缚", "蒙眼", "堵嘴", "命令", "羞辱", "惩罚", "奖励", "认主"),
    notes = mapOf("羞辱" to "语言·行为"))

private val KINK_EXPOSE = kink("暴露类", "k.expose", listOf("露出", "被观看", "被拍摄", "当众", "只开裤缝", "衣物遮挡下"))

private val KINK_SENSE = kink("感官类", "k.sense", listOf("疼痛", "低温蜡", "冰", "电", "挠痒", "窒息"),
    notes = mapOf("疼痛" to "轻·中·重", "窒息" to "限虚构"))

/**
 * §二 全表 ＋ §三 L1 标签的装配出口。
 * internal：外部一律走 AnchorFields（它负责查重与拒表外 id），这张表只当数据源。
 */
internal object AnchorSpecTable {

    /** §三 L1 核心标签 15 个（顺序照规格表）。 */
    val L1_TAGS: List<String> = listOf(
        "薄肌", "肉壮", "软白", "精瘦", "露头", "包茎", "大龟头", "细长", "早泄", "久战",
        "软着流精", "一碰就湿", "爱玩马眼", "穿刺", "注射play",
    )

    /** L1 标签的一句话（规格 §三 原句，只作面板提示，不参与校验）。 */
    val L1_TAG_WORDS: Map<String, String> = mapOf(
        "薄肌" to "有肌肉但不厚，见筋",
        "肉壮" to "厚、沉、有分量",
        "软白" to "白、软、没力气",
        "精瘦" to "瘦到见骨，动作快",
        "露头" to "龟头常年露在外面",
        "包茎" to "全包，能翻，翻开会疼",
        "大龟头" to "冠大、伞状、系带紧",
        "细长" to "长而细，看着不起眼，进得深",
        "早泄" to "一碰就射",
        "久战" to "能忍，很难射",
        "软着流精" to "不硬也能渗",
        "一碰就湿" to "手指刚碰就出水",
        "爱玩马眼" to "对尿道有执念",
        "穿刺" to "身上有环",
        "注射play" to "喜欢被灌",
    )

    /** 规格 §二 的 142 行 → 130 个字段（12 行并表，见文件头口径）。 */
    val ROWS: List<FieldSpec> = FACE + BODY + POSTURE +
        (GENITAL_PARTS_M + GENITAL_GRADES + GENITAL_NIPPLE + GENITAL_EXTRA + GENITAL_PARTS_V) +
        TENSION + ABILITY + KINK_PLAY + KINK_BODY + KINK_POWER + KINK_EXPOSE + KINK_SENSE
}
