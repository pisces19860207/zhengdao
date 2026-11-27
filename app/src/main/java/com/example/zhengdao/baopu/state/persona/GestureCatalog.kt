// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第九百四十批 · 情绪动作个体化：唯一静态表。
// 本单不重做 930 的语言闸；只借用它的「位置判定」思路（见 GestureChecks.maskDialogue）。
// 本单的 30 条动作范式与 934 的 55 条句式模板、935 的体相传闻是三张独立行集，不共用行。
package com.example.zhengdao.baopu.state.persona

internal enum class GestureSourceKind(val label: String) {
    EXPRESSION_930("930 表达层"),
    ANCHOR_747("747 人物锚定"),
}

internal data class GestureSourceRef(val kind: GestureSourceKind, val ref: String) {
    val known: Boolean get() = kind in GestureCatalog.ALLOWED_SOURCES && ref in GestureCatalog.ALLOWED_SOURCES.getValue(kind)
}

internal enum class GesturePart(val label: String) {
    HAND("手"), HEAD("头"), FACE("面"), ARM("臂"), SHOULDER("肩"),
    LEG("腿"), FOOT("足"), TORSO("躯干"),
}

internal enum class GestureDirection(val label: String) {
    UP("上"), DOWN("下"), LEFT("左"), RIGHT("右"),
    FORWARD("前"), BACKWARD("后"), INWARD("向内"), OUTWARD("向外"),
}

internal enum class GestureAmplitude(val label: String) {
    MICRO("微"), SMALL("小"), MEDIUM("中"), LARGE("大"),
}

internal data class GestureBlacklistEntry(val id: String, val phrase: String, val group: String)

internal object GestureCatalog {
    const val MIN_PER_PERSON = 2
    const val MAX_PER_PERSON = 4
    const val FREQUENCY_WINDOW = 5
    const val MIN_BLACKLIST = 28

    /** 来源可指：只指到 930 表达层字段与 747 体态字段，表外一律不认。 */
    val ALLOWED_SOURCES: Map<GestureSourceKind, List<String>> = mapOf(
        GestureSourceKind.EXPRESSION_930 to listOf("surface.tags", "mood", "emotion"),
        GestureSourceKind.ANCHOR_747 to listOf("posture.habit"),
    )

    /** 动作范式黑名单：用户点名 27 条 + 歪嘴 + 2 条同类补充，共 30 条。 */
    val BLACKLIST: List<GestureBlacklistEntry> = listOf(
        entry("G01", "抿嘴", "口面"),
        entry("G02", "咬唇", "口面"),
        entry("G03", "握拳", "手"),
        entry("G04", "攥紧", "手"),
        entry("G05", "深吸一口气", "呼吸"),
        entry("G06", "瞳孔一缩", "眼"),
        entry("G07", "喉结滚动", "喉"),
        entry("G08", "皱眉", "面"),
        entry("G09", "挑眉", "面"),
        entry("G10", "垂眸", "眼"),
        entry("G11", "捏眉心", "手"),
        entry("G12", "歪头", "头"),
        entry("G13", "歪脖子", "头"),
        entry("G14", "斜嘴", "口面"),
        entry("G15", "嘴角勾起一抹弧度", "口面"),
        entry("G16", "斜睨", "眼"),
        entry("G17", "眯眼", "眼"),
        entry("G18", "嗤笑", "笑"),
        entry("G19", "单边冷笑", "笑"),
        entry("G20", "下颌微收", "头"),
        entry("G21", "抬下巴", "头"),
        entry("G22", "似笑非笑", "笑"),
        entry("G23", "邪魅一笑", "笑"),
        entry("G24", "眼神一暗", "眼"),
        entry("G25", "眸色渐深", "眼"),
        entry("G26", "扯了扯嘴角", "口面"),
        entry("G27", "垂眸一笑", "笑"),
        entry("G28", "歪嘴", "口面"),
        entry("G29", "唇角微勾", "口面"),
        entry("G30", "轻笑一声", "笑"),
    )

    /** 文学化形容不物理，不能替代部位/朝向/幅度/时长。 */
    val LITERARY: List<String> = listOf(
        "意味深长", "若有似无", "若有所思", "耐人寻味", "难以言喻", "说不清道不明",
        "莫名的", "情绪复杂", "意味不明", "无声的波澜", "藏着故事", "说不出所以然",
    )

    /** 类型标签不另立第二张 surface 表：前八项直接引用 930 的唯一表。 */
    val SYMBOL_LABELS: List<String> = PersonaLanguage.SURFACE_TAGS +
        listOf("心机", "城府", "老谋深算", "绿茶", "渣", "好人", "坏人")

    /** 「小动作 ⇒ 这人是什么类型」的推导线索，两个方向都判。 */
    val SYMBOL_CUES: List<String> = listOf(
        "说明", "证明", "意味着", "代表", "等于", "一看就是", "透着", "显出", "像个", "就是个",
    )

    init {
        require(ALLOWED_SOURCES.keys == GestureSourceKind.entries.toSet()) { "来源类别必须两类齐全" }
        require(BLACKLIST.size >= MIN_BLACKLIST) { "范式黑名单至少 $MIN_BLACKLIST 条，实得 ${BLACKLIST.size}" }
        require(BLACKLIST.map { it.id }.toSet().size == BLACKLIST.size) { "范式黑名单 id 不得重复" }
        require(BLACKLIST.map { it.phrase }.toSet().size == BLACKLIST.size) { "范式黑名单短语不得重复" }
        require(LITERARY.none { it in BLACKLIST.map { phrase -> phrase.phrase } }) { "文学化表与范式表不共用行" }
        require(SYMBOL_LABELS.toSet().size == SYMBOL_LABELS.size) { "符号化标签不得重复" }
    }

    private fun entry(id: String, phrase: String, group: String) = GestureBlacklistEntry(id, phrase, group)
}
