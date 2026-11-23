// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百七十五批 · 龟头体与柱身静态形态（子区层）：**子区 id 的唯一来源**。
//
// 判据⑤（不许两套子区）落在本文件：全仓子区 id 只在这里定义一次，别处只能引用 `SubzoneId` 的取值，
// 不许再抄一份字符串清单。804／831 已在 main 合入且**一字不动**——它们的 `SubRegion` 记的是
// 「覆盖位图的面」（背／腹／左右侧缘／颈缘，一张皮盖住哪些面），本表记的是「形态子区」
// （沿轴分段：冠部／体部／尖端）。两轴正交，靠 [SubzoneId.zoneId]（13 区宿主）对得上，
// 交叉组合归 867 的组合表裁决，本单元不预判。
//
// id 稳定性：**只许加，不许改名**。code 一旦出门就被 804／727／867 直接引用（见 [SubzoneRefs]），
// 改名等于把三单的引用打断。要加新子区，请同单改三处消费者并在报告里登记。
package com.example.zhengdao.baopu.state.subzone

/**
 * 13 区词表的**子集引用**：只列本单元用到的 8 个区，取值与 `docs/facts/anatomy-zones.json`
 * 的 `zone_id` 逐字一致（单测现读该文件逐个核，见 `SubzoneSourceAssertTest`）。
 *
 * 这里**不是第二份 13 区表**：只有 8 个字符串，没有区名、没有可观察特征。
 */
object ZoneRef {
    const val GLANS = "glans"
    const val CORONA = "corona"
    const val SULCUS = "sulcus"
    const val FRENULUM = "frenulum"
    const val SHAFT = "shaft"
    const val RADIX = "radix"
    const val MEATUS = "meatus"
    const val PREPUCE = "prepuce"
}

/**
 * 观察组：派工单 §① 括号里的七个分组，声明序即派工单书写序。
 *
 * 组与 [SubzoneId.zoneId] 不是一回事：龟头组里「冠部」的宿主是 13 区的 `corona`（龟头冠自成一区），
 * 「体部／尖端」的宿主才是 `glans`。组是观察口径，宿主是词表口径，两个都留给下游，免得让下游猜。
 */
enum class SubzoneGroup(val label: String) {
    GLANS("龟头"),
    SULCUS("冠状沟"),
    FRENULUM("系带"),
    SHAFT("柱身"),
    RADIX("根部"),
    MEATUS("尿道口"),
    PREPUCE("包皮"),
}

/**
 * 子区在表面上的方位。四档闭集，别处不许再立一套方位词。
 *
 * 「系带在腹侧」这条硬常识在这里是**结构属性**，不是一句注释：`FRENULUM.side == VENTRAL`。
 * 改这一格就是改口径，单测当场红（判据③）。
 */
enum class SubzoneSide(val label: String) {
    DORSAL("背侧"),
    VENTRAL("腹侧"),
    RING("环周"),
    AXIS("中轴"),
}

/**
 * 十四个子区。code 是机读 id（下游引用它），label 是入文用词。
 *
 * 分组与依据：
 * · 龟头三子区＝派工单 §① 的冠部／体部／尖端。这三段与 731 顺序链的 `corona`／`glansbody`／`apex`
 *   逐字同源（`docs/facts/deictic-index.json` 的 `order` 链），所以 code 直接取链上的名字，
 *   不再另起一套拼音——同一根轴上有两个名字，迟早就对不上。
 * · 冠状沟、系带、根部各一个子区：这三处在 13 区里本来就自成一区，子区只是给它们一个
 *   「子区层」的地址，好让下游用同一套寻址方式说话，不是把它们再切一刀。
 * · 柱身三段＝近端／中段／远端。近端的 code 取 `shaftProximal`（对应链上的颈部过渡 `neckTaper`，
 *   即收腰那一段），不改链名，也不把 `neckTaper` 搬成独立区（13 区不变）。
 * · 尿道口两子区＝口缘／裂隙方向：口缘记厚薄，裂隙方向记走向（偏腹侧纵裂是硬常识的落点）。
 * · 包皮三子区＝内板／外板／口缘：内板贴龟头（黏膜面）、外板朝外（皮肤面）、口缘是那圈边。
 */
enum class SubzoneId(
    val code: String,
    val label: String,
    val group: SubzoneGroup,
    val zoneId: String,
    val side: SubzoneSide,
) {
    APEX("apex", "龟头·尖端", SubzoneGroup.GLANS, ZoneRef.GLANS, SubzoneSide.AXIS),
    GLANS_BODY("glansbody", "龟头·体部", SubzoneGroup.GLANS, ZoneRef.GLANS, SubzoneSide.RING),
    GLANS_CROWN("corona", "龟头·冠部", SubzoneGroup.GLANS, ZoneRef.CORONA, SubzoneSide.RING),

    SULCUS("sulcus", "冠状沟", SubzoneGroup.SULCUS, ZoneRef.SULCUS, SubzoneSide.RING),

    FRENULUM("frenulum", "系带", SubzoneGroup.FRENULUM, ZoneRef.FRENULUM, SubzoneSide.VENTRAL),

    SHAFT_PROXIMAL("shaftProximal", "柱身·近端", SubzoneGroup.SHAFT, ZoneRef.SHAFT, SubzoneSide.RING),
    SHAFT_MID("shaftMid", "柱身·中段", SubzoneGroup.SHAFT, ZoneRef.SHAFT, SubzoneSide.RING),
    SHAFT_DISTAL("shaftDistal", "柱身·远端", SubzoneGroup.SHAFT, ZoneRef.SHAFT, SubzoneSide.RING),

    RADIX("radix", "根部", SubzoneGroup.RADIX, ZoneRef.RADIX, SubzoneSide.AXIS),

    MEATUS_RIM("meatusRim", "尿道口·口缘", SubzoneGroup.MEATUS, ZoneRef.MEATUS, SubzoneSide.VENTRAL),
    MEATUS_SLIT("meatusSlit", "尿道口·裂隙方向", SubzoneGroup.MEATUS, ZoneRef.MEATUS, SubzoneSide.VENTRAL),

    PREPUCE_INNER("prepuceInner", "包皮·内板", SubzoneGroup.PREPUCE, ZoneRef.PREPUCE, SubzoneSide.RING),
    PREPUCE_OUTER("prepuceOuter", "包皮·外板", SubzoneGroup.PREPUCE, ZoneRef.PREPUCE, SubzoneSide.RING),
    PREPUCE_ORIFICE("prepuceOrifice", "包皮·口缘", SubzoneGroup.PREPUCE, ZoneRef.PREPUCE, SubzoneSide.RING),
    ;

    companion object {
        /** 十四个，表驱动不手数（表坏了单测先红）。 */
        val COUNT: Int get() = entries.size

        /** 按 code 取；对不上即抛，不猜近义词（下游拼字符串时当场炸，别静默返回 null）。 */
        fun ofCode(code: String): SubzoneId =
            entries.firstOrNull { it.code == code } ?: throw IllegalArgumentException(codeMiss(code))

        fun ofGroup(group: SubzoneGroup): List<SubzoneId> = entries.filter { it.group == group }

        fun ofZone(zoneId: String): List<SubzoneId> = entries.filter { it.zoneId == zoneId }

        /** 某区有没有子区定义：空表＝「本单元没有它的子区」，不是「它没有子区」。 */
        fun hasZone(zoneId: String): Boolean = entries.any { it.zoneId == zoneId }

        private fun codeMiss(code: String): String =
            "子区 code 只收 $COUNT 个登记值的原样：收到「$code」，不猜近义词（id 改名即打断 804／727／867 引用）"
    }
}
