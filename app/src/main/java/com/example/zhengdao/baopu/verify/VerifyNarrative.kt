// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百一十七批 · 验收结论的**人话层**（三态 → 用户看得见的一句实话 ＋ 出路）。
//
// ## 为什么单独一层
// 三态判定早就有了，本层**一个判定都不改**：
//   · 每轮收工那张单子（378 接线 → 332 判据表）出 [ContractReport]，词表是
//     `PASS`／`FAIL`／`UNVERIFIABLE`。
//   · 这些是**内部词**：直接贴给用户，她看见的是「UNVERIFIABLE（判不了）」，
//     不是「这次判断不了：缺什么、你能怎么办」。派工单 §一.3 明令禁止（负例①）。
// 所以本层只做两件事：**把三态翻成人话**，**给每句人话配一个真实去处**。
// 判定、取数、账行一律不在这儿（那是 332/378 的地盘）。
//
// ## 只有一个产出口，右相（302）那份不在这儿
// 派工单 §一.1 说的「每轮结束」只有 378 那一处会到；302 那份 [ChancellorReport] 出在
// 转正／三司时刻，**没有"哪一轮"可锚**，本批不替它编一个上屏时机、也不留一个没人调的
// 翻译口（那＝死代码）。它仍是日志与 541 看板那份，交付报告里作为移交项写明。
//
// ## 文案走 strings，判据钉 key（前置②；541 在这上面栽过）
// 本层**不写一个中文字**：产出 [VerifyPhrase]（键名）＋参数。于是判据①钉的是
// 「三态各用哪个 key」，不是「那句中文长什么样」——改措辞只改 XML，门禁不红；
// key 接错一律红。键名 → `R.string.*` 的唯一映射在 `ui/baopu/BaopuVerifyLine.kt`，
// `VerifyVisibleGateTest` 两头都钉（键表齐全 ＋ 中英两份 XML 都在 ＋ 值里没有内部词）。
//
// ## 三条边界（派工单 §三）
// 1. **一轮最多一行**：[VerifyLines.absorb] 是"同一轮只有一行"的唯一状态机（判据②）；
// 2. **只陈述事实**：话术表里没有"我尽力了"这类评价位（右相不评价）；
// 3. **上屏前先消毒**：[VerifyNarrative.sanitize] 把带内部词的运行时输入（文件名/明细）
//    整条挡在屏外——判据①不只扫 XML，运行时拼出来的那半句同样不许露内部词。
package com.example.zhengdao.baopu.verify

import com.example.zhengdao.baopu.extractUrl
import com.example.zhengdao.baopu.hostOf
import com.example.zhengdao.baopu.todo.TodoRules

/**
 * 一句人话话术的**键名**（值＝`strings_baopu_verify.xml` 里的 `<string name>`）。
 *
 * 闭集：加一句＝加一个枚举项＋中英两条 `<string>`＋映射表一行，别处都不许动。
 */
internal enum class VerifyPhrase(val key: String) {
    // ── 三态结论（派工单 §一.2 那张表）────────────────────────────────────
    /**
     * `FAIL`／`UNVERIFIABLE` 各两句：点得出缺哪几样走 `*_DETAIL`（缺项名填进去），
     * 点不出来走 `*_BARE`。
     *
     * 为什么分两句而不是一句加占位符：占位符空着上屏就是「没验过：缺 。要我再做一次吗」
     * ——负例③说的「失败只给错误码」的反面，同样不是人话。
     */
    PASS("baopu_verify_pass"),
    FAIL_DETAIL("baopu_verify_fail_detail"),
    FAIL_BARE("baopu_verify_fail_bare"),
    UNVERIFIABLE_DETAIL("baopu_verify_unverifiable_detail"),
    UNVERIFIABLE_BARE("baopu_verify_unverifiable_bare"),

    // ── 失败像产品（派工单 §二 那张表）──────────────────────────────────
    PAGE_READ("baopu_verify_page_read"),
    NO_EVIDENCE("baopu_verify_no_evidence"),
    NEEDS_TAP("baopu_verify_needs_tap"),
    NEEDS_TAP_NOWHERE("baopu_verify_needs_tap_nowhere"),

    // ── 一句依据（结论后面那半句；点开是证据明细）──────────────────────────
    BASIS_FILES("baopu_verify_basis_files"),
    BASIS_GATE("baopu_verify_basis_gate"),
    BASIS_READ("baopu_verify_basis_read"),
    BASIS_TAP("baopu_verify_basis_tap"),
    BASIS_BARE("baopu_verify_basis_bare"),

    // ── 证据浮层 ────────────────────────────────────────────────────────
    EVIDENCE_TITLE("baopu_verify_evidence_title"),
    EVIDENCE_MORE("baopu_verify_evidence_more"),
    EVIDENCE_NONE("baopu_verify_evidence_none"),

    /** 那一行整行可点，读屏要播报动作（沿用过程条做法；**不新造**点击语义）。 */
    TOGGLE_OPEN("baopu_verify_toggle_open"),
    TOGGLE_CLOSE("baopu_verify_toggle_close"),

    // ── 出路按钮（每个都必须有真实去处，判据③）──────────────────────────
    ACT_RETRY("baopu_verify_act_retry"),
    ACT_SWAP("baopu_verify_act_swap"),
    ACT_XUANLAN("baopu_verify_act_xuanlan"),
    ACT_AGAIN("baopu_verify_act_again"),
    ACT_NOTE("baopu_verify_act_note"),
    ACT_GO_TAP("baopu_verify_act_go_tap"),

    // ── 换做法时预填进输入框的那句（不是给用户看的话，是替她起个头）──────────
    SWAP_PREFILL("baopu_verify_swap_prefill"),

    // ── 渲染层的两个连接符 ＋「先记下来」那条待办的措辞与回执（都不许在代码里写死）──
    JOIN_OR("baopu_verify_join_or"),
    LINE_SEP("baopu_verify_line_sep"),
    NOTE_ITEM("baopu_verify_note_item"),
    NOTE_SOURCE("baopu_verify_note_source"),

    /** 「先记下来」点下去要有回执：记上了说一句、没记上说一句（负例④那种"点了没反应"）。 */
    NOTE_DONE("baopu_verify_note_done"),
    NOTE_FAIL("baopu_verify_note_fail"),
    DETAIL_NONE("baopu_verify_detail_none"),

    // ── 判据人话名（332 那 14 条，一条不许漏：漏了就是把判据号贴给用户）──────
    ITEM_PATH_SET("baopu_verify_item_path_set"),
    ITEM_BYTE_IDENTICAL("baopu_verify_item_byte_identical"),
    ITEM_NO_ESCAPE("baopu_verify_item_no_escape"),
    ITEM_LEDGER_COMPLETE("baopu_verify_item_ledger_complete"),
    ITEM_EXISTS("baopu_verify_item_exists"),
    ITEM_STRUCTURE("baopu_verify_item_structure"),
    ITEM_USER_CHECKLIST("baopu_verify_item_user_checklist"),
    ITEM_COMPILE("baopu_verify_item_compile"),
    ITEM_TESTS_GREEN("baopu_verify_item_tests_green"),
    ITEM_DIFF_IN_SCOPE("baopu_verify_item_diff_in_scope"),
    ITEM_BUDGET("baopu_verify_item_budget"),
    ITEM_SHAPE("baopu_verify_item_shape"),
    ITEM_SAMPLE_5("baopu_verify_item_sample_5"),
    ITEM_REOPENABLE("baopu_verify_item_reopenable"),
    ;
}

/** 一行结论能给的出路；每个都必须接到一个真存在的动作（判据③）。 */
internal enum class VerifyAction(val label: VerifyPhrase) {
    /** 这一轮再跑一遍（引擎续跑口）。 */
    RETRY(VerifyPhrase.ACT_RETRY),

    /** 换个做法：把话预填进输入框，发不发由人定。 */
    SWAP(VerifyPhrase.ACT_SWAP),

    /** 跳到玄览那一页，人自己看一眼。 */
    XUANLAN(VerifyPhrase.ACT_XUANLAN),

    /** 再试一次（与 [RETRY] 同一个去处；§二 那张表把它叫「再试一次」）。 */
    AGAIN(VerifyPhrase.ACT_AGAIN),

    /** 就这样，先记下来 ⇒ 进未办成单（541 的账 ＋ 565 的看板）。 */
    NOTE(VerifyPhrase.ACT_NOTE),

    /** 去点：跳到屏幕上要操作的那一页。 */
    GO_TAP(VerifyPhrase.ACT_GO_TAP),
}

/**
 * 失败两类（§二 里**运行时看得见**的那两类）：命中哪一类，结论换成那一句，出路整套跟着换。
 *
 * 优先级写死：**需要人动手 ＞ 页面没读出来**。引擎正等人在屏幕上点一下时先说这件——
 * 那一轮就停在那儿，说别的没用。
 *
 * 第三类「验不了（缺证据）」**不在这里**：它不是从工具读数看出来的，而是这一轮的验收单
 * 本身点不出缺项、也拿不出可对的证据（[VerifyPhrase.NO_EVIDENCE] 在 [fromContract] 里选）。
 * 硬造一个"缺证据"的运行时探测器＝猜，派工单没给这个口径。
 */
internal enum class VerifyFailureKind { NEEDS_TAP, PAGE_READ }

/**
 * 本轮结束上屏的那一行（纯数据：话术键＋参数＋证据；渲染层只翻译，不判断）。
 *
 * [place] 是消毒过的位置串（域名/页面名），**空串＝没有位置**：那时结论走
 * [VerifyPhrase.NEEDS_TAP_NOWHERE]，绝不在屏上留一个「点一下：」后面的空白。
 * [url] 是同一页的**完整地址**（[place] 的来处），只给「去点／你点一下玄览」当跳转目标，
 * **永不进文案**；空串＝这一轮没读到页地址，那两个按钮回落到「打开玄览」这个真实去处。
 */
internal data class VerifyLine(
    val conclusion: VerifyPhrase,
    val items: List<VerifyPhrase>,
    val place: String,
    val url: String,
    val basis: VerifyPhrase,
    val numbers: List<Int>,
    val actions: List<VerifyAction>,
    /** 本轮可对的证据（文件路径/账行），**消毒过、未截断**：摆几行是渲染层的事
     * （[VerifyNarrative.MAX_EVIDENCE_ROWS]），截在这儿「还有 N 条」就没得算了。 */
    val evidence: List<String>,
    /** 三态原值（只给测试与运行日志用，**永不进文案**）。 */
    val verdict: String,
)

/** 收工单子（332/378）→ 人话的唯一翻译口（判定与取数都不在这儿）。 */
internal object VerifyNarrative {

    /** 证据浮层最多摆几行（沿用过程条口径：更多折成「还有 N 条」）。 */
    const val MAX_EVIDENCE_ROWS = 6

    /** 结论里最多点几样缺项（多了读不完；剩下的进证据浮层）。 */
    const val MAX_ITEM_NAMES = 3

    /** 填表器件名（224 批）：§二「需要你操作」唯一的真实出处。 */
    private const val FORM_FILL = "form_fill"

    /** 224 的回执里那一格（工具自己报「按钮交给人」；空格子串归一后才比）。 */
    private const val NEEDS_HUMAN_CLICK = "\"needsHumanClick\":true"

    /**
     * 上屏文案里**一律不许出现**的片段（大写后子串比对；中英两份 XML 由门禁按它逐条扫）。
     *
     * 派工单 §一.3 点名 `UNVERIFIABLE`／`sha256`／`GapItem`／`PASS`；其余是同一族的
     * 词表与 grep 键（332 的 reason 码、302 的降级行、Java 异常字样）。
     */
    val FORBIDDEN: Set<String> = setOf(
        "PASS", "FAIL", "UNVERIFIABLE", "SHA256", "SHA-256", "GAPITEM", "GAP_FEEDBACK",
        "NO_SAMPLES", "MISSING_EVIDENCE", "VALUE_MISMATCH", "TYPE_MISMATCH",
        "CHANCELLOR", "CONTRACTREPORT", "UNDEFINED", "NULL", "EXCEPTION", "STACKTRACE",
    )

    /**
     * 运行时输入（文件名/明细串）里**只按整词**挡的那几个短词。
     *
     * 为什么不与 [FORBIDDEN] 同尺度：这一层吃的是盘上路径与别的批次写的串——
     * `password.md`／`test_failure.log` 是好文件名，按子串扫会把它们从证据栏里悄悄吃掉
     * （用户点不开，还不知道为什么）。整词才是「内部词漏上屏」的形状：孤零零一个
     * `PASS`／`FAIL` 只会是三态词表自己跑出来了（派工单 §一.3 点名的正是这两个）。
     */
    val WHOLE_WORD_FORBIDDEN: Set<String> = setOf("PASS", "FAIL", "NULL", "UNDEFINED")

    /** 子串那一档＝严格表去掉整词档（两个档合起来覆盖 [FORBIDDEN] 全集）。 */
    private val SUBSTRING_FORBIDDEN: Set<String> = FORBIDDEN - WHOLE_WORD_FORBIDDEN

    /**
     * 收工那张单子 → 一行话。
     *
     * @param report null = 这一轮没有验收单（纯问答轮）⇒ **不出行**：
     *   「验过了：该写的都写了」对一轮没交付的问答是假话，宁可不说话。
     * @param files 本轮够格的文本产物（引擎那份 `roundArtifacts`，证据用；本层不读盘）
     * @param gateFindings 收尾闸未过项数（[ArtifactVerifier] 的既有读数，本层不重算）
     */
    fun fromContract(
        report: ContractReport?,
        files: List<String>,
        gateFindings: Int,
    ): VerifyLine? {
        if (report == null) return null
        val touched = files.mapNotNull(::sanitize)
        val missing = (report.missing + report.failed).distinct().take(MAX_ITEM_NAMES).map(::itemOf)
        val evidence = touched
        return when (report.status) {
            Status.PASS -> VerifyLine(
                conclusion = VerifyPhrase.PASS,
                items = emptyList(),
                place = "",
                url = "",
                basis = VerifyPhrase.BASIS_FILES,
                numbers = listOf(touched.size),
                actions = emptyList(),
                evidence = evidence,
                verdict = report.status.name,
            )

            Status.FAIL -> {
                val basis = basisOf(gateFindings, touched)
                VerifyLine(
                    conclusion = shortfall(VerifyPhrase.FAIL_DETAIL, VerifyPhrase.FAIL_BARE, missing, evidence),
                    items = missing,
                    place = "",
                    url = "",
                    basis = basis.first,
                    numbers = basis.second,
                    actions = listOf(VerifyAction.AGAIN, VerifyAction.NOTE),
                    evidence = evidence,
                    verdict = report.status.name,
                )
            }

            Status.UNVERIFIABLE -> {
                val basis = basisOf(gateFindings, touched)
                VerifyLine(
                    conclusion = shortfall(
                        VerifyPhrase.UNVERIFIABLE_DETAIL,
                        VerifyPhrase.UNVERIFIABLE_BARE,
                        missing,
                        evidence,
                    ),
                    items = missing,
                    place = "",
                    url = "",
                    basis = basis.first,
                    numbers = basis.second,
                    actions = listOf(VerifyAction.AGAIN, VerifyAction.NOTE),
                    evidence = evidence,
                    verdict = report.status.name,
                )
            }
        }
    }

    /**
     * 失败两类覆盖（§二）：命中就换成那一句，出路整套换掉；缺项与证据留着（点开还得看）。
     *
     * @param base [fromContract] 的读数，可为 null——纯问答轮没单子，页面照样能读失败，
     *   那时**仍然出行**：失败像产品不依赖那轮有没有交付。
     * @param location 那一页的主机名（[failurePlaceOf] 的读数；抠不到 ⇒ 空串，走
     *   [VerifyPhrase.NEEDS_TAP_NOWHERE] 那句不留空白的说法）。
     * @param url 同一页的完整地址（[failureUrlOf]），只给「去点／你点一下玄览」当跳转目标。
     */
    fun withFailureKind(
        base: VerifyLine?,
        kind: VerifyFailureKind?,
        location: String?,
        url: String?,
    ): VerifyLine? {
        if (kind == null) return base
        val place = location?.let { sanitize(it) }.orEmpty()
        val items = base?.items.orEmpty()
        val evidence = base?.evidence.orEmpty()
        val verdict = base?.verdict ?: kind.name
        return when (kind) {
            VerifyFailureKind.NEEDS_TAP -> VerifyLine(
                conclusion = if (place.isEmpty()) VerifyPhrase.NEEDS_TAP_NOWHERE else VerifyPhrase.NEEDS_TAP,
                items = items,
                place = place,
                url = url.orEmpty(),
                basis = VerifyPhrase.BASIS_TAP,
                numbers = emptyList(),
                actions = listOf(VerifyAction.GO_TAP),
                evidence = evidence,
                verdict = verdict,
            )

            VerifyFailureKind.PAGE_READ -> VerifyLine(
                conclusion = VerifyPhrase.PAGE_READ,
                items = items,
                place = place,
                url = url.orEmpty(),
                basis = if (place.isEmpty()) VerifyPhrase.BASIS_BARE else VerifyPhrase.BASIS_READ,
                numbers = emptyList(),
                actions = listOf(VerifyAction.RETRY, VerifyAction.SWAP, VerifyAction.XUANLAN),
                evidence = evidence,
                verdict = verdict,
            )
        }
    }

    /**
     * 缺项点不出来时走哪一句：连一条可对的文件都没有 ⇒ 用 §二 第二行那句「我没法确认」
     * （那两句出路本来就是一对：再试一次 / 就这样先记下来）；有文件可点开 ⇒ 说这一态。
     *
     * 为什么不硬凑一句「缺 XX」：占位符空着上屏是「没验过：缺 。要我再做一次吗」——
     * 负例③说的「失败只给错误码」的反面同样不是人话。
     */
    private fun shortfall(
        detail: VerifyPhrase,
        bare: VerifyPhrase,
        items: List<VerifyPhrase>,
        evidence: List<String>,
    ): VerifyPhrase = when {
        items.isNotEmpty() -> detail
        evidence.isEmpty() -> VerifyPhrase.NO_EVIDENCE
        else -> bare
    }

    /**
     * 结论后面那半句依据填哪句：收尾闸有过不去的项先说闸，其次说这一轮落了几个文件，
     * 两样都没有就明说「没有能对上的读数」。
     *
     * 为什么 [Status.UNVERIFIABLE] 也要看闸：判不了**不等于**没有坏消息——闸上真有过不去的项时
     * 说「本轮落了 0 个文件」是把唯一说得出的事实藏起来（派工单 §一.1「一句依据」要的是依据）。
     */
    private fun basisOf(gateFindings: Int, touched: List<String>): Pair<VerifyPhrase, List<Int>> = when {
        gateFindings > 0 -> VerifyPhrase.BASIS_GATE to listOf(gateFindings)
        touched.isNotEmpty() -> VerifyPhrase.BASIS_FILES to listOf(touched.size)
        else -> VerifyPhrase.BASIS_BARE to emptyList()
    }

    /** 332 那 14 条判据 → 人话名（闭集；`when` 不带 else，将来加判据会编译不过）。 */
    fun itemOf(id: CheckId): VerifyPhrase = when (id) {
        CheckId.PATH_SET -> VerifyPhrase.ITEM_PATH_SET
        CheckId.BYTE_IDENTICAL -> VerifyPhrase.ITEM_BYTE_IDENTICAL
        CheckId.NO_ESCAPE -> VerifyPhrase.ITEM_NO_ESCAPE
        CheckId.LEDGER_COMPLETE -> VerifyPhrase.ITEM_LEDGER_COMPLETE
        CheckId.EXISTS -> VerifyPhrase.ITEM_EXISTS
        CheckId.STRUCTURE -> VerifyPhrase.ITEM_STRUCTURE
        CheckId.USER_CHECKLIST -> VerifyPhrase.ITEM_USER_CHECKLIST
        CheckId.COMPILE -> VerifyPhrase.ITEM_COMPILE
        CheckId.TESTS_GREEN -> VerifyPhrase.ITEM_TESTS_GREEN
        CheckId.DIFF_IN_SCOPE -> VerifyPhrase.ITEM_DIFF_IN_SCOPE
        CheckId.BUDGET -> VerifyPhrase.ITEM_BUDGET
        CheckId.SHAPE -> VerifyPhrase.ITEM_SHAPE
        CheckId.SAMPLE_5 -> VerifyPhrase.ITEM_SAMPLE_5
        CheckId.REOPENABLE -> VerifyPhrase.ITEM_REOPENABLE
    }

    /**
     * 运行时输入消毒：带内部词的一律 null（调用方**丢弃**，不截断、不改写）。
     *
     * 不改写是刻意的：把 `UNVERIFIABLE` 抠成 `UNVERIFI` 那种"擦干净"看着过了闸，
     * 上屏的还是半截内部词。丢一行证据，用户顶多点不开；露一个内部词，这一层就白做。
     */
    fun sanitize(raw: String): String? {
        val one = raw.replace('\n', ' ').trim()
        if (one.isEmpty()) return null
        val upper = one.uppercase()
        if (SUBSTRING_FORBIDDEN.any { upper.contains(it) }) return null
        if (words(upper).any { it in WHOLE_WORD_FORBIDDEN }) return null
        return one
    }

    /**
     * 按「非字母数字」切词（中文本身算字母，所以 `验收PASS通过` 里那个 `PASS` 照样被抓）。
     *
     * 不用 `String.split { }`：stdlib 没有谓词重载（这一处 317 首跑就是这么红的）。
     */
    private fun words(upper: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        upper.forEach { ch ->
            if (ch.isLetterOrDigit()) {
                cur.append(ch)
            } else if (cur.isNotEmpty()) {
                out.add(cur.toString())
                cur.setLength(0)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    /**
     * 一次工具调用读数 → 命中 §二 哪一类失败（null = 与两类都无关，一个字都不改结论）。
     *
     * 「需要人动手」只认 224 批 `form_fill` 自己账上那一格：`needsHumanClick=true` 是工具
     * **报出来**的「按钮交给你了」（那个端口的结构里没有 click 通道，224 的负例 1 钉着）——
     * 不是从"看起来像在等人"里猜的。
     * 「页面没读出来」认玄览器件族（`browser_*`／`teach_*`）与填表器件的**失败回执**
     * （`ok=false`）：§二 第一行「截屏/读页面失败」说的就是这三条。
     */
    fun failureKindOf(tool: String, summary: String, ok: Boolean): VerifyFailureKind? {
        val onPage = tool.startsWith("browser_") || tool.startsWith("teach_") || tool == FORM_FILL
        if (!onPage) return null
        if (!ok) return VerifyFailureKind.PAGE_READ
        val compact = summary.filterNot { it == ' ' || it == '\t' }
        return if (tool == FORM_FILL && compact.contains(NEEDS_HUMAN_CLICK)) {
            VerifyFailureKind.NEEDS_TAP
        } else {
            null
        }
    }

    /**
     * 这一类失败发生在**哪一页**：从读数里抠 url、只取主机名。
     *
     * 抠不出来 ⇒ null（[VerifyPhrase.NEEDS_TAP_NOWHERE]／依据退回不带地址那句）：
     * `form_fill` 的回执里没有 url 这一格，页地址是从同一轮先前的 `browser_*` 读数里带来的。
     */
    fun failurePlaceOf(summary: String): String? = extractUrl(summary)?.let { hostOf(it) }

    /**
     * 那一页的完整地址（「去点」与「你点一下玄览」两个按钮的跳转目标）。
     *
     * 与 [failurePlaceOf] 同源：都从引擎那行 `ToolCallVisible.summary` 里抠 `"url"`，
     * 一个留全串给跳转、一个只留主机名给文案——同一份读数，两处不各读各的。
     */
    fun failureUrlOf(summary: String): String? = extractUrl(summary)

    /** 两条同时命中留哪一条（[VerifyFailureKind] 的声明顺序就是优先级）。 */
    fun strongest(a: VerifyFailureKind?, b: VerifyFailureKind?): VerifyFailureKind? = when {
        a == null -> b
        b == null -> a
        else -> if (a.ordinal <= b.ordinal) a else b
    }
}

/**
 * 一轮一行的台账（判据②的唯一状态机）。
 *
 * 键＝这一轮**用户那条消息的库 id**：库 id 不变，所以切走再回来、重进 App，
 * 同一轮的结论还是那一行，不会裂成两行。
 */
internal object VerifyLines {

    /** 一次会话最多留几行结论（更早的随消息流滚走；也防止无界增长）。 */
    const val MAX_ROUNDS_KEPT = 20

    /**
     * 收进台账：同一轮**只有一行**（后到的覆盖先到的）。
     *
     * 派工单负例②「一轮插三行」在这一处结构上就发生不了——不判"要不要新起一行"，
     * 按锚去重是唯一的写入口。
     */
    fun absorb(lines: List<VerifyRow>, anchor: Long, incoming: VerifyLine?): List<VerifyRow> {
        if (incoming == null || anchor <= 0L) return lines
        val kept = lines.filterNot { it.anchor == anchor }
        val next = kept + VerifyRow(anchor, incoming)
        return if (next.size <= MAX_ROUNDS_KEPT) next else next.takeLast(MAX_ROUNDS_KEPT)
    }

    /** 某一轮那一行（没有 ⇒ null，渲染层那一行就不画）。 */
    fun find(lines: List<VerifyRow>, anchor: Long): VerifyLine? =
        lines.lastOrNull { it.anchor == anchor }?.line

    /** 清空（换会话／新建会话时调，不许把上一会话的结论带过去）。 */
    fun clear(): List<VerifyRow> = emptyList()
}

/** 台账里的一条：结论 ＋ 它属于哪一轮。 */
internal data class VerifyRow(val anchor: Long, val line: VerifyLine)

/**
 * 「就这样，先记下来」那条待办的摘要长度闸（渲染层把整句人话喂进来，这里只管裁）。
 *
 * 长度上限**不是本层定的**：541 的 [TodoRules] 超 [TodoRules.MAX_SUMMARY_CHARS] 字整条拒收，
 * 所以这里复用同一把尺（[TodoRules.normalize]／[TodoRules.charCount]／[TodoRules.takeChars]），
 * 不自己抄一个 40——抄了就是两把尺，将来 541 改上限，这一处会悄悄地开始被拒收。
 */
internal fun verifyTodoSummary(text: String): String {
    val one = TodoRules.normalize(text)
    return if (TodoRules.charCount(one) <= TodoRules.MAX_SUMMARY_CHARS) {
        one
    } else {
        TodoRules.takeChars(one, TodoRules.MAX_SUMMARY_CHARS)
    }
}
