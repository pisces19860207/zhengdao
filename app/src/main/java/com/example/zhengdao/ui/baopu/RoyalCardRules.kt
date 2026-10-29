// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十七批（派工单 §2 · M4 御案卡面）：**卡面判据层**——纯值进、纯值出。
// 与本包其它 *Rules 文件同一纪律：不许出现 @Composable、不许拿 Context、不许碰 IO，
// 全部可在 JVM 直测。渲染在 `BaopuRoyalCard.kt`，落账在 `baopu/agent/SelfRulingLedger.kt`。
//
// 三条硬判据（派工单原文，逐条一条函数）：
//   ① 一张卡 ≤3 行（改什么／为什么／影不影响你）；
//   ② 每行带**真假标注**（真／假／未核实），整卡标注由各行**算出来**，不许外部传；
//   ③ **必有默认项**——没有默认项的卡等于把选择成本原样丢回给用户。
//
// 判据之外多守两条（路线图 §9.2b「上御案前逐条核对」）：
//   ④ 有"假"的事实主张 ⇒ 该条不得上御案（拒渲染，打回拟稿重写）；
//   ⑤ 一行里塞换行符 ⇒ 按"多行"处理（否则 4 行塞进 1 行即可绕过 ①——判据不许能被绕过）。
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
 * @param isDefault 默认项（卡上必有且仅有一个）：用户不细看时按它走的那条
 */
internal data class RoyalOption(
    val id: String,
    val label: String,
    val isDefault: Boolean = false,
)

/**
 * 一张御案卡。
 *
 * 注意 [mark] 是**算出来的**属性，不是构造参数：若让它能被外部传进来，
 * "把未核实写成真"就只是一行参数的事，判据也就形同虚设。
 */
internal data class RoyalCard(
    val key: String,
    val lines: List<RoyalLine>,
    val options: List<RoyalOption>,
) {
    /** 卡面那个真假标注（取各行里最保守的一条；没有行 ⇒ 未核实）。 */
    val mark: RoyalTruth get() = RoyalCardGate.mark(lines)
}

/** 拒渲染的机器原因（`cn` 进日志；卡面不显示——不合规的卡一个像素都不许画）。 */
internal enum class RoyalReject(val cn: String) {
    NO_LINE("一行都没有"),
    TOO_MANY_LINES("超过 3 行"),
    LINE_TOO_LONG("单行太长（把整段塞进一行）"),
    EMPTY_LINE("有空白行"),
    LINE_BROKEN("一行里塞了换行（把多行伪装成一行）"),
    NO_OPTION("一个可选项都没有"),
    NO_DEFAULT("没有默认项"),
    MULTI_DEFAULT("默认项不唯一"),
    BLANK_OPTION_ID("有选项 id 为空"),
    DUPLICATE_OPTION("选项 id 重复"),
    FALSE_CLAIM("有事实主张被判为假：该条不得上御案"),
}

/**
 * 卡面判据（**纯函数**，无状态）。
 *
 * 为什么把"拒"做成返回值而不是抛异常：拒渲染是**正常结局**（拟稿不合规要打回重写），
 * 抛异常会把它变成"系统出错"，接线层就只能吞或者崩。
 */
internal object RoyalCardGate {

    /** 一行上限（改什么/为什么/影不影响你——三行）。 */
    const val MAX_LINES = 3

    /** 单行字数上限（超过就不是"一行话"了；派工单要求卡面能一眼扫完）。 */
    const val MAX_LINE_CHARS = 40

    /** 整卡的真假标注：取各行里**最保守**的那条（有未核实就标未核实，有假就标假）。 */
    fun mark(lines: List<RoyalLine>): RoyalTruth =
        lines.maxByOrNull { it.truth.rank }?.truth ?: RoyalTruth.UNVERIFIED

    /** 逐条查，返回全部问题（一次说清，不让用户/拟稿方改一轮才发现下一个）。 */
    fun check(card: RoyalCard): List<RoyalReject> {
        val out = mutableListOf<RoyalReject>()
        val lines = card.lines
        if (lines.isEmpty()) out += RoyalReject.NO_LINE
        if (lines.size > MAX_LINES) out += RoyalReject.TOO_MANY_LINES
        if (lines.any { it.text.isBlank() }) out += RoyalReject.EMPTY_LINE
        if (lines.any { it.text.contains('\n') || it.text.contains('\r') }) out += RoyalReject.LINE_BROKEN
        if (lines.any { it.text.trim().codePointCount(0, it.text.trim().length) > MAX_LINE_CHARS }) {
            out += RoyalReject.LINE_TOO_LONG
        }
        if (lines.any { it.truth == RoyalTruth.FALSE }) out += RoyalReject.FALSE_CLAIM
        val options = card.options
        if (options.isEmpty()) out += RoyalReject.NO_OPTION
        val defaults = options.count { it.isDefault }
        if (options.isNotEmpty() && defaults == 0) out += RoyalReject.NO_DEFAULT
        if (defaults > 1) out += RoyalReject.MULTI_DEFAULT
        if (options.any { it.id.isBlank() }) out += RoyalReject.BLANK_OPTION_ID
        val ids = options.map { it.id }.filter { it.isNotBlank() }
        if (ids.size != ids.distinct().size) out += RoyalReject.DUPLICATE_OPTION
        return out
    }

    /** 这张卡能不能画（拒渲染判据的唯一入口；渲染层只认它）。 */
    fun renderable(card: RoyalCard): Boolean = check(card).isEmpty()

    /** 日志/账本那一行（格式也是判据，埋进调用点就抓不到）。 */
    internal fun rejectLine(card: RoyalCard): String =
        "抱朴御案：卡面不合规，拒渲染（${card.key}）：" +
            check(card).joinToString("、") { it.cn }
}
