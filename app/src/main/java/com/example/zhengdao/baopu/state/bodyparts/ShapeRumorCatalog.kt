// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第九百三十五批 · 体相传闻唯一表（七类闭集）。
//
// 口径：这些说法**只作个人偏好**，不作客观评价；叙述者复述即红，角色相信只 WARN 且须标偏见，
// 剧情把它「验证」成真即红（那等于变相背书，最容易被漏掉的一条）。
//
// 七类 A–G 是闭集：A 体型骨架／B 面部／C 毛发皮肤／D 姿态行为／E 命理伪分类／F 体液生理量／
// G 服装配饰。**凡未列的一条都不许新造**；新造一条要被改本表，不许在别处另立第二张表。
//
// 与 934 的关系：934 的 55 条句式收的是「身份/长相/钱权 → 品性」，本表收的是「体相 → 性评价」。
// 两表不合并——934 的条数与七类小计是它自己的硬判据（55／七类），并进来会同时破两单的边界；
// 本表只与它**同一口径**（句式模板、方向、叙述者红/角色 WARN），不共用行。
package com.example.zhengdao.baopu.state.bodyparts

/**
 * 七类闭集。tag 就是派工单里的 A–G，顺序即声明序，不许换。
 */
enum class RumorClass(val tag: String, val label: String) {
    BODY("A", "体型与骨架"),
    FACE("B", "面部"),
    HAIR("C", "毛发与皮肤"),
    POSTURE("D", "姿态与行为"),
    FORTUNE("E", "命理与伪分类"),
    FLUID("F", "体液与生理量"),
    DRESS("G", "服装与配饰"),
    ;

    companion object {
        const val COUNT = 7
    }
}

/**
 * 传闻断言的目标。比 [Judged] 多一个 [SIZE]：好几条传闻直接断言「那处大小」，
 * 而尺寸是形态本身（920 的档），不是评价维——所以不能塞进 [Judged]。
 */
enum class RumorTarget(val label: String) {
    SIZE("下体尺寸"),
    TENSION("性张力"),
    ATTRACTION("性吸引力"),
    ABILITY("性能力"),
    EXPERIENCE("性经验"),
    FERTILITY("生育力"),
    MORAL("品性"),
    ;

    companion object {
        const val COUNT = 7
    }
}

/**
 * 一条传闻：编号／类／句式模板／它断言了什么。
 * 模板里的 `…` 是任意填充；其余片段按字面量钉住，不做语义理解、不做同义词扩展。
 */
data class Rumor(
    val id: String,
    val klass: RumorClass,
    val template: String,
    val targets: Set<RumorTarget>,
    val note: String = "",
) {
    init {
        require(id.isNotBlank()) { "传闻缺编号" }
        require(template.contains('…')) { "只收句式模板（必须带 … 占位），不收单词：$id" }
        require(targets.isNotEmpty()) { "传闻必须说清它断言了什么：$id" }
    }
}

/** 传闻表的唯一真相。条数与分类小计由测试从本表实数复现，不手写在断言之外。 */
object ShapeRumorCatalog {

    val entries: List<Rumor> = listOf(
        // A 体型与骨架：全部禁推尺寸/张力/能力（920 已禁身高体重，924 已禁体积↔质量互推）
        Rumor("A01", RumorClass.BODY, "个子矮…那处一定大", setOf(RumorTarget.SIZE)),
        Rumor("A02", RumorClass.BODY, "个子高…那处一定大", setOf(RumorTarget.SIZE)),
        Rumor("A03", RumorClass.BODY, "骨架宽…能力强", setOf(RumorTarget.ABILITY)),
        Rumor("A04", RumorClass.BODY, "肩宽…更猛", setOf(RumorTarget.ABILITY)),

        // B 面部：鼻子那条最多；面相学另归 E 类
        Rumor("B01", RumorClass.FACE, "鼻子大…下面一定大", setOf(RumorTarget.SIZE)),
        Rumor("B02", RumorClass.FACE, "鼻子小…不行", setOf(RumorTarget.ABILITY)),
        Rumor("B03", RumorClass.FACE, "嘴唇厚…欲望强", setOf(RumorTarget.TENSION)),
        Rumor("B04", RumorClass.FACE, "下巴宽…那方面强", setOf(RumorTarget.ABILITY)),

        // C 毛发与皮肤：脱发与雄激素有关，但禁推性能力——最容易越界的一条
        Rumor("C01", RumorClass.HAIR, "体毛多…雄性激素足…能力强", setOf(RumorTarget.ABILITY)),
        Rumor("C02", RumorClass.HAIR, "秃顶…那方面不行", setOf(RumorTarget.ABILITY)),
        Rumor("C03", RumorClass.HAIR, "秃顶…更猛", setOf(RumorTarget.ABILITY)),
        Rumor("C04", RumorClass.HAIR, "青筋明显…能力强", setOf(RumorTarget.ABILITY)),

        // D 姿态与行为：只作习惯与状态；说话方式属 930 的 expression
        Rumor("D01", RumorClass.POSTURE, "岔腿坐…那方面强", setOf(RumorTarget.ABILITY)),
        Rumor("D02", RumorClass.POSTURE, "走路带风…很猛", setOf(RumorTarget.ABILITY)),
        Rumor("D03", RumorClass.POSTURE, "说话慢…持久", setOf(RumorTarget.ABILITY)),

        // E 命理与伪分类：叙述者层面**直接红**，不走 WARN 提示分支
        Rumor("E01", RumorClass.FORTUNE, "血型…决定…那方面", setOf(RumorTarget.ABILITY)),
        Rumor("E02", RumorClass.FORTUNE, "星座…合不合", setOf(RumorTarget.TENSION)),
        Rumor("E03", RumorClass.FORTUNE, "手相…看得出…", setOf(RumorTarget.MORAL)),
        Rumor("E04", RumorClass.FORTUNE, "面相…天生淫", setOf(RumorTarget.EXPERIENCE)),
        Rumor("E05", RumorClass.FORTUNE, "出生时辰…命里…", setOf(RumorTarget.MORAL)),

        // F 体液与生理量：前四项禁作能力/经验/健康判断；后两项只按 810/868 实测档写
        Rumor("F01", RumorClass.FLUID, "精液少…不行", setOf(RumorTarget.FERTILITY)),
        Rumor("F02", RumorClass.FLUID, "前液多…经验丰富", setOf(RumorTarget.EXPERIENCE)),
        Rumor("F03", RumorClass.FLUID, "勃起角度…所以很强", setOf(RumorTarget.ABILITY)),
        Rumor("F04", RumorClass.FLUID, "汗多…虚", setOf(RumorTarget.ABILITY)),

        // G 服装与配饰：混得好≠做人好（934 已禁），这里再钉住「由物看能力」
        Rumor("G01", RumorClass.DRESS, "骑电动车…没本事", setOf(RumorTarget.MORAL)),
        Rumor("G02", RumorClass.DRESS, "手表贵…混得好…人就行", setOf(RumorTarget.MORAL)),
        Rumor("G03", RumorClass.DRESS, "鞋干净…讲究…能力强", setOf(RumorTarget.ABILITY)),
    )

    /** 命理词：与结论同句即红（叙述者层面直接红）。单独出现不算——防误杀纯记录。 */
    val FORTUNE_TOKENS: List<String> = listOf(
        "血型", "星座", "生肖", "手相", "掌纹", "面相", "八字", "时辰", "第几胎", "骨相",
    )

    init {
        require(entries.size == entries.map { it.id }.toSet().size) { "传闻编号重复" }
        val missing = RumorClass.entries.filter { k -> entries.none { it.klass == k } }
        require(missing.isEmpty()) { "七类必须每类都有条目，缺：" + missing.joinToString { it.tag } }
        require(entries.size == 27) { "表条数变了要同步报告，不许偷改" }
    }

    /** 按类取条目，顺序＝表内顺序。 */
    fun byClass(klass: RumorClass): List<Rumor> = entries.filter { it.klass == klass }

    /** 每类条数——判据①「七类齐」就是拿这张计数表去对。 */
    fun countByClass(): Map<RumorClass, Int> = RumorClass.entries.associateWith { byClass(it).size }

    /** 命中一条文本的全部传闻（`…` 任意填充，其余按字面量）。 */
    fun match(text: String): List<Rumor> = entries.filter { templateMatches(it.template, text) }

    /** 一条模板是否命中：`…` 拆开后逐段 Pattern.quote，段间用 .* 连接。 */
    fun templateMatches(template: String, text: String): Boolean {
        val pattern = template.split('…').joinToString(".*") { java.util.regex.Pattern.quote(it) }
        return Regex(pattern).containsMatchIn(text)
    }

    /** 命中文本里的命理词；无命中返回空。 */
    fun fortuneHits(text: String): List<String> = FORTUNE_TOKENS.filter { text.contains(it) }
}
