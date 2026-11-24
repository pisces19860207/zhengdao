// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百一十七批 · 角色外观卡与连续性校验：引用层。
// 本单是 805/807/814/815/816 这批外观单的唯一消费方，卡字段的档必须引用既有档，
// 一个档名都不许在单内新造。做法：每个字段挂一个 RefSource，allowed 动态取自既有
// 枚举 values().label 或 AnchorFields.spec(id).words；仓里没有封闭档的（头发/纹身/
// 眼镜）标 NONE，allowed 恒空 ⇒ 卡未设定，走「可补卡」，绝不新造维度。
package com.example.zhengdao.baopu.state.casting

import com.example.zhengdao.baopu.state.anchor.AnchorFields
import com.example.zhengdao.baopu.state.bodyparts.AbsBlock
import com.example.zhengdao.baopu.state.bodyparts.BodyPart
import com.example.zhengdao.baopu.state.bodyparts.BodyGrade
import com.example.zhengdao.baopu.state.bodyparts.Girth
import com.example.zhengdao.baopu.state.bodyparts.LarynxGrade
import com.example.zhengdao.baopu.state.bodyparts.NippleDiameter
import com.example.zhengdao.baopu.state.bodyparts.PecSeparation
import com.example.zhengdao.baopu.state.bodyparts.PecVolume
import com.example.zhengdao.baopu.state.bodyparts.AreolaRatio
import com.example.zhengdao.baopu.state.bearing.Bearing6
import com.example.zhengdao.baopu.state.physiology.Fatigue
import com.example.zhengdao.baopu.state.physiology.SweatLevel
import com.example.zhengdao.baopu.state.prepuce.Circumcision
import com.example.zhengdao.baopu.state.prepuce.CoverState
import com.example.zhengdao.baopu.state.prepuce.ExposeType
import com.example.zhengdao.baopu.state.prepuce.FlaccidSize

/** 本单唯一来源口径（与邻单同格式）。 */
const val CASTING_SOURCE: String =
    "派工单-2026-11-23-第八百一十七批（角色外观卡与连续性校验）＋本项目既有档位表"
const val CASTING_AS_OF: String = "2026-11-23"

/** 档的来源。NONE＝仓内无既有封闭档，卡未设定，走可补卡——不是「新造一个空档」。 */
enum class RefSource {
    ANCHOR,       // 747 七维词表 AnchorFields.spec(id).words
    BODY_PART,    // 805 部位档枚举 values().label
    BODY_SITE,    // 805 BodyPart 八部位（标记位置档）
    PREPUCE,      // 804 下体档枚举 values().label
    PHYSIO,       // 658 状态痕迹枚举 values().label
    BEARING,      // 790 方位六档（标记方位）
    NONE,         // 816 头发/纹身/眼镜：仓内无封闭档
}

/**
 * 卡字段分组；粒度合并按组折叠。TRACE 不进卡字段，只作鲜活判据白名单。
 * 869 新增 TORSO/TOPO/BONE 三组标识（八六九批四组引用位）；下体形状（814）敏感，
 * 直接归入 GENITAL，摘要安全组天然排除，不新增敏感出口。
 */
enum class CardGroup(val cn: String) {
    FACE("脸"), BODY("体型"), PART("部位"), GENITAL("下体"),
    HAIR("头发"), MARK("标记"), TRACE("状态痕迹"),
    TORSO("躯干形状"), TOPO("体表地形"), BONE("骨点"),
}

/**
 * 一条卡字段定义。[allowed] 动态取自既有档，是本单「引用不新造」的唯一落点。
 * [id] 是卡字段 key；ANCHOR 类的 id 直接等于既有字段 id，其余用 part./prepuce. 前缀区分。
 */
data class FieldRef(
    val id: String,
    val group: CardGroup,
    val cn: String,
    val source: RefSource,
    private val allowed: () -> List<String>,
) {
    /** 这一字段允许的档（逐字取自既有档）；NONE 源恒空。 */
    fun words(): List<String> = allowed().distinct()

    /** 该档能否收这个值；NONE 源一律不收（无既有档可引用）。 */
    fun accepts(value: String): Boolean = value.isNotBlank() && value in words()

    /** 三连身份键：脸型＋发型＋体型；非三连字段返回 null。 */
    val trioKey: String? get() = when (id) {
        "face.shape" -> "脸型"
        "hair.style" -> "发型"
        "body.frame" -> "体型"
        else -> null
    }
}

/** 805 枚举 → 档名，逐字取 values().label，不抄字面。 */
private fun bodyWords(enums: Array<out BodyGrade>): () -> List<String> =
    { enums.map { it.label } }

/** 既有字段 id → 封闭词表，逐字取 AnchorFields。 */
private fun anchorWords(id: String): () -> List<String> =
    { AnchorFields.spec(id).words }

/**
 * 卡字段白名单（817 的 33 项 + 869 四组引用位 23 项 = 56 项，≤56 预算）。
 * 全部指向既有档；无既有档者 source=NONE、allowed 空。顺序即档位向量与摘要的稳定次序。
 */
object CastingRefs {

    private val fields: LinkedHashMap<String, FieldRef> = linkedMapOf()

    private fun add(ref: FieldRef) {
        require(ref.id !in fields) { "卡字段 id 重复：${ref.id}" }
        fields[ref.id] = ref
    }

    init {
        // ① 脸与皮肤——引用 747 FACE 词表
        add(FieldRef("face.shape", CardGroup.FACE, "脸型", RefSource.ANCHOR, anchorWords("face.shape")))
        add(FieldRef("face.eyes", CardGroup.FACE, "眼", RefSource.ANCHOR, anchorWords("face.eyes")))
        add(FieldRef("face.nose", CardGroup.FACE, "鼻", RefSource.ANCHOR, anchorWords("face.nose")))
        add(FieldRef("face.lips", CardGroup.FACE, "唇", RefSource.ANCHOR, anchorWords("face.lips")))
        add(FieldRef("face.brows", CardGroup.FACE, "眉", RefSource.ANCHOR, anchorWords("face.brows")))
        add(FieldRef("face.skin", CardGroup.FACE, "皮肤", RefSource.ANCHOR, anchorWords("face.skin")))
        add(FieldRef("face.pupil", CardGroup.FACE, "瞳色", RefSource.ANCHOR, anchorWords("face.pupil")))

        // ② 体型三轴＋档案——引用 747 BODY 词表
        add(FieldRef("body.frame", CardGroup.BODY, "骨架", RefSource.ANCHOR, anchorWords("body.frame")))
        add(FieldRef("body.muscle", CardGroup.BODY, "肌肉", RefSource.ANCHOR, anchorWords("body.muscle")))
        add(FieldRef("body.fat", CardGroup.BODY, "体脂", RefSource.ANCHOR, anchorWords("body.fat")))
        add(FieldRef("body.ratio", CardGroup.BODY, "肩腰臀", RefSource.ANCHOR, anchorWords("body.ratio")))
        add(FieldRef("body.legs", CardGroup.BODY, "腿", RefSource.ANCHOR, anchorWords("body.legs")))

        // ③ 部位——引用 805 部位档枚举（不复制档名）
        add(FieldRef("part.pec.volume", CardGroup.PART, "胸肌量", RefSource.BODY_PART, bodyWords(PecVolume.values())))
        add(FieldRef("part.pec.sep", CardGroup.PART, "胸肌分界", RefSource.BODY_PART, bodyWords(PecSeparation.values())))
        add(FieldRef("part.nipple.diam", CardGroup.PART, "乳头径", RefSource.BODY_PART, bodyWords(NippleDiameter.values())))
        add(FieldRef("part.areola.ratio", CardGroup.PART, "乳晕倍数", RefSource.BODY_PART, bodyWords(AreolaRatio.values())))
        add(FieldRef("part.larynx", CardGroup.PART, "喉结", RefSource.BODY_PART, bodyWords(LarynxGrade.values())))
        add(FieldRef("part.abs.block", CardGroup.PART, "腹肌块", RefSource.BODY_PART, bodyWords(AbsBlock.values())))
        add(FieldRef("part.biceps.girth", CardGroup.PART, "肱二围", RefSource.BODY_PART, bodyWords(Girth.values())))
        add(FieldRef("part.thigh.girth", CardGroup.PART, "大腿围", RefSource.BODY_PART, bodyWords(Girth.values())))
        add(FieldRef("part.calf.girth", CardGroup.PART, "小腿围", RefSource.BODY_PART, bodyWords(Girth.values())))

        // ④ 下体——引用 804 档枚举＋747 g.* 词表
        add(FieldRef("g.prepuce", CardGroup.GENITAL, "包皮", RefSource.ANCHOR, anchorWords("g.prepuce")))
        add(FieldRef("prepuce.cover", CardGroup.GENITAL, "覆盖", RefSource.PREPUCE, { CoverState.values().map { it.label } }))
        add(FieldRef("prepuce.circum", CardGroup.GENITAL, "环切", RefSource.PREPUCE, { Circumcision.values().map { it.label } }))
        add(FieldRef("prepuce.size", CardGroup.GENITAL, "疲软尺寸", RefSource.PREPUCE, { FlaccidSize.values().map { it.label } }))
        add(FieldRef("prepuce.expose", CardGroup.GENITAL, "露出型", RefSource.PREPUCE, { ExposeType.values().map { it.label } }))
        add(FieldRef("g.pubicHairSpread", CardGroup.GENITAL, "阴毛分布", RefSource.ANCHOR, anchorWords("g.pubicHairSpread")))

        // ⑤ 头发——816 无既有封闭档，标 NONE；卡未设定 ⇒ 可补卡（三连仍把它当确定维度）
        add(FieldRef("hair.style", CardGroup.HAIR, "发型", RefSource.NONE) { emptyList() })

        // ⑥ 标记——疤/痣/斑引用 face.skin 既有词的子集；位置引用 805 八部位＋790 方位六档
        add(FieldRef("mark.skin", CardGroup.MARK, "疤痣斑", RefSource.ANCHOR, anchorSubset("face.skin", "痣", "疤", "斑")))
        add(FieldRef("mark.site", CardGroup.MARK, "标记部位", RefSource.BODY_SITE, { BodyPart.values().map { it.name } }))
        add(FieldRef("mark.bearing", CardGroup.MARK, "标记方位", RefSource.BEARING, { Bearing6.values().map { it.name } }))
        add(FieldRef("mark.tattoo", CardGroup.MARK, "纹身", RefSource.NONE) { emptyList() })
        add(FieldRef("mark.glasses", CardGroup.MARK, "眼镜", RefSource.NONE) { emptyList() })

        // ⑦ 状态痕迹（五类）——只作鲜活判据白名单，不进外观卡字段
        add(FieldRef("trace.sweat", CardGroup.TRACE, "出汗", RefSource.PHYSIO, { SweatLevel.values().map { it.label } }))
        add(FieldRef("trace.fatigue", CardGroup.TRACE, "疲劳", RefSource.PHYSIO, { Fatigue.values().map { it.label } }))
        add(FieldRef("trace.breath", CardGroup.TRACE, "呼吸", RefSource.ANCHOR, anchorWords("t.breath")))
        add(FieldRef("trace.posture", CardGroup.TRACE, "姿态", RefSource.BODY_PART,
            { com.example.zhengdao.baopu.state.bodyparts.Posture.values().map { it.label } }))
        add(FieldRef("trace.skin", CardGroup.TRACE, "皮肤变化", RefSource.ANCHOR, anchorWords("posture.skinFeel")))

        // ⑧–⑪ 869 四组引用位：躯干形状(848)/体表地形(866)/骨点(865)/下体形状(814)。
        // 这四组在本批一律 pending：848 待合中、866 在飞、865/814 待派——仓内无「本卡可直接
        // 引用」的封闭档出口，故 source=NONE、allowed 恒空，卡未设定走可补卡。绝不填值、不自造档；
        // 等各自合入 main 且有稳定出口后，由后续接线批把 source 换成对应既有档（本单不做装配）。
        addPendingRefs()
    }

    /**
     * 869 四组引用位工厂：[id] 是本卡字段 key，[cn] 只是人话标签，source 恒 NONE、allowed 恒空。
     * 一组一组写全，便于门禁逐组核对「齐」与「NONE」，不靠数字约定。
     */
    private fun pending(id: String, group: CardGroup, cn: String): FieldRef =
        FieldRef(id, group, cn, RefSource.NONE) { emptyList() }

    private fun addPendingRefs() {
        // ⑧ 躯干形状（848，六维）：块数/白线/胸下缘/斜方坡度/背阔外扩/前锯肌
        add(pending("torso.blocks", CardGroup.TORSO, "腹肌块数"))
        add(pending("torso.linea", CardGroup.TORSO, "白线"))
        add(pending("torso.pecEdge", CardGroup.TORSO, "胸下缘"))
        add(pending("torso.trapSlope", CardGroup.TORSO, "斜方坡度"))
        add(pending("torso.latFlare", CardGroup.TORSO, "背阔外扩"))
        add(pending("torso.serratus", CardGroup.TORSO, "前锯肌"))

        // ⑨ 体表地形（866，四族）：凸起/凹陷/厚度/不对称
        add(pending("topo.relief", CardGroup.TOPO, "凸起"))
        add(pending("topo.hollow", CardGroup.TOPO, "凹陷"))
        add(pending("topo.depth", CardGroup.TOPO, "厚度"))
        add(pending("topo.asymmetry", CardGroup.TOPO, "不对称"))

        // ⑩ 骨点（865，十处骨性标志）：每处一个引用位，取值将是 visible/palpable（可见/可触）。
        // 十处取自通用骨性标志命名，仅作卡字段 key；865 未落地，故一律 NONE、allowed 空。
        listOf(
            "clavicle" to "锁骨", "acromion" to "肩峰", "scapula" to "肩胛骨",
            "sternum" to "胸骨", "ribs" to "肋骨", "pelvis" to "骨盆",
            "elbow" to "肘", "wrist" to "腕", "knee" to "膝", "ankle" to "踝",
        ).forEach { (key, cn) ->
            add(pending("bone.$key", CardGroup.BONE, cn))
        }

        // ⑪ 下体形状（814，三项）：柱身截面/龟头廓形/横纵截面。归 GENITAL 敏感组，摘要排除。
        add(pending("shape.shaftSection", CardGroup.GENITAL, "柱身截面"))
        add(pending("shape.glansContour", CardGroup.GENITAL, "龟头廓形"))
        add(pending("shape.crossSection", CardGroup.GENITAL, "横纵截面"))
    }

    /** 取既有封闭词表的子集：词的字面仍来自既有档，单内只给筛选键。 */
    private fun anchorSubset(id: String, vararg keep: String): () -> List<String> =
        { AnchorFields.spec(id).words.filter { it in keep } }

    /** 全部卡字段（顺序稳定）；白名单总数即粒度预算的天然上界。 */
    val ALL: List<FieldRef> = fields.values.toList()

    /** 外观卡可填字段（不含状态痕迹）。 */
    val CARD_FIELDS: List<FieldRef> = ALL.filter { it.group != CardGroup.TRACE }

    /** 状态痕迹字段（鲜活判据白名单）。 */
    val TRACE_FIELDS: List<FieldRef> = ALL.filter { it.group == CardGroup.TRACE }

    /** 三连字段，顺序脸型→发型→体型。 */
    val TRIO: List<FieldRef> = listOf(
        field("face.shape"), field("hair.style"), field("body.frame")
    )

    /** 869：卡上限 40 → 56。四组引用位共新增 23 项，33+23=56，正好用满，不越界。 */
    const val MAX_CARD_ITEMS = 56

    fun field(id: String): FieldRef = fields[id] ?: throw CastingSpecException("卡字段不在既有档白名单，不许新增：$id")

    fun known(id: String): Boolean = id in fields

    /** 卡字段预算自检：白名单自身不得超 56；超了本批就自己不合规。 */
    init {
        require(CARD_FIELDS.size <= MAX_CARD_ITEMS) {
            "卡字段白名单 ${CARD_FIELDS.size} 项超预算 $MAX_CARD_ITEMS"
        }
        require(TRIO.size == 3 && TRIO.all { it.trioKey != null }) { "三连必须恰好脸型＋发型＋体型" }
    }
}

/** 越界（新字段／新档／超预算）一律用它拒，点名到底哪一样越界。 */
class CastingSpecException(message: String) : IllegalArgumentException(message)
