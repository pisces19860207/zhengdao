// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百四十七批 · 人物锚定：七维字段模型（派工单① 的机器侧）。
// 规格真源（仓外只读，一字不复制进仓）：
//   C:\Users\guoli\OneDrive\Desktop\男性\人物锚定\人物锚定与越界轨迹.md
//   44,995 B · 1,031 行 · sha256 前 16 位 4ab930c29f3d9c02（2026-11-19 逐字节核过）。
// 本文件只翻两张表：§二「七维度 × 字段全表」的行结构与 §三「三级粒度」的档位。
// 行数据本身在 AnchorSpecTable.kt，一条不落、一条不加。
//
// 三条边界（都是规格原文，不是我加的规矩）：
// · §一：锚身体与癖好，不锚性别、不锚性取向、不锚性格、不锚关系状态——
//   七维里没有任何一列是身份标签（判据⑥ 的门禁臂扫的就是这四列）。
// · §〇／§十：本体系答「这个人是什么样」，不算关系质量（那是 00-03），
//   也不管「这个人是谁」；④⑥ 两维是描述不是要求，允许合法留空（留空 ≠ 缺维不报）。
// · §三：七个维度全展开一百多项，面板分三级用；**存储恒为全量**，
//   切档只换视图（判据① 的「切换不丢数据」由这条结构保证，不靠测试补救）。
package com.example.zhengdao.baopu.state.anchor

/**
 * 七维（§二，锁死）：数量、名称、含义都不许改。
 * 加一维或减一维等同改规格，须人批；判据① 的门禁臂钉住「恰好这七个」。
 */
enum class DimKind(val cn: String) {
    FACE("五官"),
    BODY("体型"),
    POSTURE("体态"),
    GENITAL("性器外观"),
    TENSION("性张力"),
    ABILITY("性能力"),
    KINK("性癖"),
}

/**
 * 三级粒度（§三）。档位只决定**渲染出多少**，不决定**存下多少**：
 * L1＝核心标签 15 选 2–4，L2＝七维细项，L3＝自由补充那一段。
 */
enum class Granularity(val cn: String) {
    L1("核心标签"),
    L2("细项"),
    L3("自由补充"),
}

/**
 * 一行的取值方式：
 * CLOSED＝规格给了封闭词表，填的词必须在表里（表外即拒，判据①「不新增」）；
 * FREE＝规格给的是「填什么」的落点而不是档，允许自由文本，落点仍逐条列在 words 里。
 */
enum class FillMode { CLOSED, FREE }

/**
 * 一行字段＝规格表里的一行。列的对应关系：
 * label＝行名，words＝「取值／填什么」那一格拆开的词，note＝那一格的「说明」列，
 * specRow＝这行出自规格哪张表（对账锚点，验收方据此逐行回查）。
 */
data class FieldSpec(
    val id: String,
    val dim: DimKind,
    val label: String,
    val words: List<String> = emptyList(),
    val mode: FillMode = FillMode.CLOSED,
    val multi: Boolean = false,
    val unit: String = "",
    val group: String = "",
    val note: String = "",
    val specRow: String,
) {
    val closed: Boolean get() = mode == FillMode.CLOSED

    /** 数值行（带单位，如身高 cm）：值走 AnchorProfile.numbers，不塞进词表。 */
    val measured: Boolean get() = unit.isNotEmpty()

    /** 这行能不能收某个词：封闭行只收表内词，开放行收自由文本但仍不许空。 */
    fun accepts(word: String): Boolean = word.isNotBlank() && (!closed || word in words)
}

/** 规格外的字段／表外的取值／缺维——一律用它抛，报错点名到底缺哪一样。 */
class AnchorSpecException(message: String) : IllegalArgumentException(message)

/**
 * 七维字段表的唯一读入口（装配自 AnchorSpecTable.ROWS）。
 * 未知字段 id 一律拒（判据①「不新增」），并按维给出有序清单（「不删减」的对账口）。
 */
object AnchorFields {

    val ALL: List<FieldSpec> = AnchorSpecTable.ROWS

    private val byId: Map<String, FieldSpec> = ALL.associateBy { it.id }

    /** §三 L1 核心标签 15 个，顺序照规格表（可增可减是 §九 的未决项，现照抄 15）。 */
    val L1_TAGS: List<String> = AnchorSpecTable.L1_TAGS

    /** L1 标签的一句话（面板提示用）。 */
    val L1_TAG_WORDS: Map<String, String> = AnchorSpecTable.L1_TAG_WORDS

    /** 规格写的「15」在这里是常量而不是实测：改了标签数必须同时改规格口径，门禁会红。 */
    const val L1_TAG_COUNT = 15

    /** 性癖加权档（§二⑦「0-5，0＝无感，5＝一碰就上头」）。 */
    const val KINK_WEIGHT_MIN = 0
    const val KINK_WEIGHT_MAX = 5

    // 规格 §二 各表的行数（对账口）：142 行 − 12 行并表 ＝ 130 字段。
    private val EXPECTED_ROWS_BY_DIM: Map<DimKind, Int> = mapOf(
        DimKind.FACE to 11, DimKind.BODY to 9, DimKind.POSTURE to 7, DimKind.GENITAL to 45,
        DimKind.TENSION to 7, DimKind.ABILITY to 12, DimKind.KINK to 39,
    )

    init {
        require(byId.size == ALL.size) { "字段 id 重复：七维表不自洽，拒绝装配" }
        require(ALL.map { it.dim }.toSet() == DimKind.values().toSet()) { "七维必须每一维都有行" }
        require(L1_TAGS.size == L1_TAG_COUNT) { "L1 核心标签须恰好 $L1_TAG_COUNT 个" }
        val short = EXPECTED_ROWS_BY_DIM.filter { (dim, specRows) -> ofDim(dim).size != specRows }.keys
        require(short.isEmpty()) {
            "七维行数与规格 §二 不符，对不上的维：" +
                short.joinToString("、") { "${it.cn}（表内 ${ofDim(it).size}／规格 ${EXPECTED_ROWS_BY_DIM[it]}）" }
        }
        require(ALL.size == EXPECTED_ROWS_BY_DIM.values.sum()) { "七维总行数须恰好 ${EXPECTED_ROWS_BY_DIM.values.sum()}" }
    }

    /** 取一行的规格定义；表外 id 直接拒（判据①）。 */
    fun spec(id: String): FieldSpec =
        byId[id] ?: throw AnchorSpecException("字段不在七维表里，不许新增：$id")

    fun known(id: String): Boolean = id in byId

    /** 按维取行，顺序＝表内顺序（渲染与对账都用它，保证稳定）。 */
    fun ofDim(dim: DimKind): List<FieldSpec> = ALL.filter { it.dim == dim }

    /** 每维行数——判据①「不删减」就是拿这张计数表去对规格 §二 的行数。 */
    fun rowCountByDim(): Map<DimKind, Int> = DimKind.values().associateWith { ofDim(it).size }

    fun requireKinkWeight(weight: Int) {
        if (weight !in KINK_WEIGHT_MIN..KINK_WEIGHT_MAX) {
            throw AnchorSpecException("性癖加权只取 $KINK_WEIGHT_MIN–$KINK_WEIGHT_MAX：$weight")
        }
    }
}

/**
 * 规格真源的出处钉（判据⑥「只读、不复制进仓」的另一半）。
 * 仓里只留路径与指纹，正文一字不抄；验收方拿这两串回查原文，字节数与 sha 对上
 * ⇒ 本批 130 行抄的还是同一份规格；对不上就是规格漂了，本批结论作废重核。
 */
object AnchorSpecSource {
    const val MD_PATH = """C:\Users\guoli\OneDrive\Desktop\男性\人物锚定\人物锚定与越界轨迹.md"""
    const val MD_SHA256_16 = "4ab930c29f3d9c02"
    const val MD_BYTES = 44995
    const val MD_LINES = 1031

    /** 拓扑源：11 态 13 转移的真源，改动须人批＋升 TRACK_SPEC_MAJOR（670 的闸，本批不碰）。 */
    const val DOT_PATH = """C:\Users\guoli\OneDrive\Desktop\男性\人物锚定\人物锚定图.dot"""
    const val DOT_SHA256_16 = "dcfbb63a833dcb78"

    /** asOf＝逐字节核过指纹的日期（AGENTS 铁律：证据带 source＋asOf）。 */
    const val VERIFIED_ON = "2026-11-19"
}
