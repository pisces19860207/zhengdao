// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第九百三十五批 · 体相传闻唯一表（七类闭集）。
// 第九百四十三批 · 表搬 manifest：assets/policy/blacklist-manifest.json 的 935-shape-myth 类
// 成为唯一行数据；本文件**只留读端**——七类闭集、查询与匹配的口径一字不变。
//
// 口径：这些说法**只作个人偏好**，不作客观评价；叙述者复述即红，角色相信只 WARN 且须标偏见，
// 剧情把它「验证」成真即红（那等于变相背书，最容易被漏掉的一条）。
//
// 七类 A–G 是闭集：A 体型骨架／B 面部／C 毛发皮肤／D 姿态行为／E 命理伪分类／F 体液生理量／
// G 服装配饰。**凡未列的一条都不许新造**；新造一条要改 manifest，不许在别处另立第二张表。
//
// 与 934 的关系：934 的句式黑名单收「身份/长相/钱权 → 品性」，本表收「体相 → 性评价」。
// 943 起两类**同一处维护**（同一份 manifest、同一口径：句式模板、叙述者红/角色 WARN），
// 行不共用；行数与分类小计的锁跟着表走 manifest，测试从 manifest 实数复现。
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

/**
 * 传闻表的**读端**（943 起）：行住在 assets/policy/blacklist-manifest.json 的
 * 935-shape-myth 类，由装配侧 [install] 喂入后才可读。本类零 IO（源码门禁），
 * 也不内置任何一行——表不存在于代码里，「读不到就回退内置表」从数据上不可能。
 */
object ShapeRumorCatalog {

    @Volatile
    private var installed: List<Rumor>? = null

    /** 唯一表的读端：manifest 装配后才有数据；未装配即抛——绝不静默空表。 */
    val entries: List<Rumor>
        get() = installed
            ?: error("传闻表未装配：943 起唯一表在 assets/policy/blacklist-manifest.json（935-shape-myth），装配侧须先 install")

    /**
     * 装配入口（幂等）：喂 manifest 的 935 行。
     * 行结构（模板带 …、targets 非空）由 [Rumor] 的 init 把守；编号唯一与七类齐在这里把守；
     * 行数与 totals／subtotals 的一致性在 manifest 解析层（BlacklistManifest）把守。
     */
    fun install(rows: List<Rumor>) {
        require(rows.isNotEmpty()) { "传闻表不许空装（唯一表在 manifest 的 935-shape-myth 类）" }
        require(rows.size == rows.map { it.id }.toSet().size) { "传闻编号重复" }
        val missing = RumorClass.entries.filter { k -> rows.none { it.klass == k } }
        require(missing.isEmpty()) { "七类必须每类都有条目，缺：" + missing.joinToString { it.tag } }
        installed = ArrayList(rows)
    }

    /** 命理词：与结论同句即红（叙述者层面直接红）。单独出现不算——防误杀纯记录。 */
    val FORTUNE_TOKENS: List<String> = listOf(
        "血型", "星座", "生肖", "手相", "掌纹", "面相", "八字", "时辰", "第几胎", "骨相",
    )

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
