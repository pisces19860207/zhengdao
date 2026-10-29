// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十七批（派工单 §2 · M4 御案卡面）：**卡面判据层**——纯值进、纯值出。
// 第二百七十八批（派工单 §1）在此之上补三条：
//   ⑥ 现状行 ≤30 字（"≤30 字现状"）；
//   ⑦ 选项 ≤3 个且**各带后果**（"点了会怎样"必须写在按钮上，不让人猜）；
//   ⑧ **技术字段不许上卡面**（法条号 `L7`、机器键 `risk:`、枚举 `CRITICAL`、文件名
//      `.jsonl`、snake_case 标识符…一律拒渲染）——它们只许进「证据区」。
//
// 与本包其它 *Rules 文件同一纪律：不许 @Composable、不许拿 Context、不许碰 IO。
// 渲染在 `BaopuRoyalCard.kt`，拟稿在 `RoyalDrafts.kt`，核验在 `baopu/agent/DraftGate.kt`。
//
// 前五条（277）保留原样：
//   ① 一张卡 ≤3 行（改什么／为什么／影不影响你）；② 每行带真假标注；
//   ③ 必有默认项；④ 有"假"主张不得上御案；⑤ 行内换行按多行处理。
package com.example.zhengdao.ui.baopu

/**
 * 一条事实主张的核验结论（③b 核验闸的三个输出值）。
 *
 * [rank] 是"取最弱"用的：一张卡里有若干条主张时，卡面只能标一个结论，
 * 取 rank 最大（最保守）那一条——**宁可让用户看到"这条没核过"，也不许伪装成已核**。
 *
 * [cn] 只进日志与账本，不进卡面：卡面文案走 `R.string`（中英双份），判据层不碰资源。
 */
internal enum class RoyalTruth(val cn: String, val rank: Int) {
    TRUE("真", 0),
    UNVERIFIED("未核实", 1),
    FALSE("假", 2),
    ;

    companion object {
        /** 认不出就 null（自造值不渲染，也不静默当"真"——照 208/影子账的解析纪律）。 */
        fun ofWire(wire: String): RoyalTruth? =
            entries.firstOrNull { it.name == wire || it.cn == wire }
    }
}

/** 卡面三行的身份（"改什么／为什么／影不影响你"——顺序即展示顺序）。 */
internal enum class RoyalLineKind(val cn: String) {
    WHAT("改什么"),
    WHY("为什么"),
    IMPACT("影不影响你"),
    ;

    companion object {
        fun ofWire(wire: String): RoyalLineKind? =
            entries.firstOrNull { it.name == wire || it.cn == wire }
    }
}

/**
 * 卡面的一行。
 *
 * @param truth 这一行**事实主张**的核验结论（不是"这句话好听不好听"）
 */
internal data class RoyalLine(
    val kind: RoyalLineKind,
    val text: String,
    val truth: RoyalTruth,
)

/**
 * 卡上的一枚可选项。
 *
 * @param consequence 点了会怎样（必填：按钮上只写"确定/取消"等于让用户猜后果）
 * @param isDefault 默认项（卡上必有且仅有一个）：用户不细看时按它走的那条
 */
internal data class RoyalOption(
    val id: String,
    val label: String,
    val consequence: String,
    val isDefault: Boolean = false,
)

/**
 * 一张御案卡。
 *
 * 注意 [mark] 是**算出来的**属性，不是构造参数：若让它能被外部传进来，
 * "把未核实写成真"就只是一行参数的事，判据也就形同虚设。
 *
 * @param evidence **证据区**（账本行/日志行/事件 id）：技术字段只许活在这里，
 *   渲染时折在卡底小字里，不占卡面三行
 */
internal data class RoyalCard(
    val key: String,
    val lines: List<RoyalLine>,
    val options: List<RoyalOption>,
    val evidence: List<String> = emptyList(),
) {
    /** 卡面那个真假标注（取各行里最保守的一条；没有行 ⇒ 未核实）。 */
    val mark: RoyalTruth get() = RoyalCardGate.mark(lines)

    /** 卡面上会显示的全部文字（判"技术字段"时的扫描面；证据区**不算**）。 */
    val faceTexts: List<String>
        get() = lines.map { it.text } + options.flatMap { listOf(it.label, it.consequence) }
}

/** 拒渲染的机器原因（`cn` 进日志；卡面不显示——不合规的卡一个像素都不许画）。 */
internal enum class RoyalReject(val cn: String) {
    NO_LINE("一行都没有"),
    TOO_MANY_LINES("超过 3 行"),
    LINE_TOO_LONG("单行超长（上限 30 字）"),
    EMPTY_LINE("有空白行"),
    LINE_BROKEN("一行里塞了换行（把多行伪装成一行）"),
    NO_OPTION("一个可选项都没有"),
    TOO_MANY_OPTIONS("选项超过 3 个"),
    NO_DEFAULT("没有默认项"),
    MULTI_DEFAULT("默认项不唯一"),
    NO_CONSEQUENCE("有选项没写后果（点了会怎样）"),
    BLANK_OPTION_ID("有选项 id 为空"),
    DUPLICATE_OPTION("选项 id 重复"),
    FALSE_CLAIM("有事实主张被判为假：该条不得上御案"),
    TECHNICAL_LEAK("卡面出现技术字段（只许进证据区）"),
}

/**
 * 卡面判据（**纯函数**，无状态）。
 *
 * 为什么把"拒"做成返回值而不是抛异常：拒渲染是**正常结局**（拟稿不合规要打回重写），
 * 抛异常会把它变成"系统出错"，接线层就只能吞或者崩。
 */
internal object RoyalCardGate {

    /** 卡面行数上限（改什么/为什么/影不影响你——三行）。 */
    const val MAX_LINES = 3

    /** 卡面单行字数上限（278：现状行 ≤30 字，按 [visualLength] 的等效字宽算）。 */
    const val MAX_LINE_CHARS = 30

    /**
     * 等效字宽（中文一字算 1，其他按半字算，向上取整）。
     *
     * 为什么不用"字符数"：同一句话英文要长一倍（30 个中文≈60 个英文字符），
     * 按字符数卡会把英文卡全拒掉；按"等效字宽"卡，中英是同一把尺，
     * 也堵住"换个语言就能塞更多字"的口子。
     */
    fun visualLength(text: String): Int {
        var half = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            // 全角/CJK 记 2 个半角单位；ASCII 与其余记 1（阈值取 0x2E80：涵盖中日韩与全角标点）
            half += if (cp in 0x1100..0x115F || cp >= 0x2E80) 2 else 1
            i += Character.charCount(cp)
        }
        return (half + 1) / 2
    }

    /** 选项上限（批/驳/搁置——三条路足够；再多就是把决策成本倒给用户）。 */
    const val MAX_OPTIONS = 3

    /** 整卡的真假标注：取各行里**最保守**的那条（有未核实就标未核实，有假就标假）。 */
    fun mark(lines: List<RoyalLine>): RoyalTruth =
        lines.maxByOrNull { it.truth.rank }?.truth ?: RoyalTruth.UNVERIFIED

    /**
     * 这段文字像不像**技术字段**（机器键/法条号/枚举/文件名/标识符）。
     *
     * 为什么是启发式而不是白名单：卡面文案是中文人话，技术字段有一堆写法，
     * 白名单永远漏；宁可偶尔多拒一张（人能看到日志、改一版再上），
     * 也不许把机器键直接怼到用户脸上。
     */
    fun looksTechnical(text: String): Boolean = TECHNICAL_PATTERNS.any { it.containsMatchIn(text) }

    /**
     * 逐条查，返回全部问题（一次说清，不让拟稿方改一轮才发现下一个）。
     *
     * 拆成三块（行／选项／卡面文字）是**为了每条判据都能被单独读懂**，
     * 不是为了让复杂度数字好看：一个函数里堆 14 个 if，读的人根本记不住第 11 条在说什么。
     */
    fun check(card: RoyalCard): List<RoyalReject> =
        checkLines(card.lines) + checkOptions(card.options) + checkFaceText(card.faceTexts)

    private fun checkLines(lines: List<RoyalLine>): List<RoyalReject> {
        val out = mutableListOf<RoyalReject>()
        if (lines.isEmpty()) out += RoyalReject.NO_LINE
        if (lines.size > MAX_LINES) out += RoyalReject.TOO_MANY_LINES
        if (lines.any { it.text.isBlank() }) out += RoyalReject.EMPTY_LINE
        if (lines.any { it.text.contains('\n') || it.text.contains('\r') }) out += RoyalReject.LINE_BROKEN
        if (lines.any { visualLength(it.text.trim()) > MAX_LINE_CHARS }) out += RoyalReject.LINE_TOO_LONG
        if (lines.any { it.truth == RoyalTruth.FALSE }) out += RoyalReject.FALSE_CLAIM
        return out
    }

    private fun checkOptions(options: List<RoyalOption>): List<RoyalReject> {
        val out = mutableListOf<RoyalReject>()
        if (options.isEmpty()) out += RoyalReject.NO_OPTION
        if (options.size > MAX_OPTIONS) out += RoyalReject.TOO_MANY_OPTIONS
        val defaults = options.count { it.isDefault }
        if (options.isNotEmpty() && defaults == 0) out += RoyalReject.NO_DEFAULT
        if (defaults > 1) out += RoyalReject.MULTI_DEFAULT
        if (options.any { it.consequence.isBlank() }) out += RoyalReject.NO_CONSEQUENCE
        if (options.any { it.id.isBlank() }) out += RoyalReject.BLANK_OPTION_ID
        val ids = options.map { it.id }.filter { it.isNotBlank() }
        if (ids.size != ids.distinct().size) out += RoyalReject.DUPLICATE_OPTION
        return out
    }

    /** ⑧ 技术字段：扫卡面（三行＋选项标签＋选项后果），证据区不在此列。 */
    private fun checkFaceText(texts: List<String>): List<RoyalReject> =
        if (texts.any { looksTechnical(it) }) listOf(RoyalReject.TECHNICAL_LEAK) else emptyList()

    /** 这张卡能不能画（拒渲染判据的唯一入口；渲染层只认它）。 */
    fun renderable(card: RoyalCard): Boolean = check(card).isEmpty()

    /** 日志/账本那一行（格式也是判据，埋进调用点就抓不到）。 */
    internal fun rejectLine(card: RoyalCard): String =
        "抱朴御案：卡面不合规，拒渲染（${card.key}）：" +
            check(card).joinToString("、") { it.cn }

    /**
     * 技术字段的识别式（每条都有正例/负例测试，见 `RoyalCardRulesTest`）。
     *
     * 刻意不收两字母大写：`AI`／`OK` 是人话里正常出现的，
     * 为了抓一个枚举名把正常卡也拒了，是本末倒置。
     */
    private val TECHNICAL_PATTERNS = listOf(
        // 法条号：L7 / L21
        Regex("""\bL\d{1,2}\b"""),
        // 机器键前缀：risk: / tool: / matrix:v3 / gate:path_guard
        Regex("""\b(risk|tool|matrix|gate|op|sr|ev|law|src|sev|act)\s*:"""),
        // snake_case 标识符：read_file / path_outside_workspace
        Regex("""[a-z]{2,}_[a-z0-9_]+"""),
        // 枚举/常量：CRITICAL / PATH_OUTSIDE_WORKSPACE（四字符起，避开 AI/OK）
        Regex("""[A-Z][A-Z0-9_]{3,}"""),
        // 文件名：意见判定.jsonl / Opinion.kt
        Regex("""\.(jsonl|json|kt|md|xml|apk)\b"""),
        // JSON 片段
        Regex("""[{}"\[\]]"""),
        // key: value
        Regex(""":\s*[a-z]"""),
    )
}
