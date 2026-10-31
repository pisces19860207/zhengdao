// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百三十三批（第一批）· 技能契约 ＋ 适用性过滤 ＋ 修订边界——**只做静态层**。
//
// 依据：`派工单-2026-10-26-第二百三十三批-技能契约化.md` §1（A 契约）§2（B 过滤）§3（C 修订）。
// 口径来自 Skill-V（arXiv 2610.11781）：技能存成**可证伪的契约**（语义意图 ⇄ 可观察判据）；
// 契约评估与任务结果不一致时**修订边界**（不是直接改脚本），修订必须 ①`protected` 一条没动
// ②历史回放不回归（④调 206 的既有口 `EvalCompareRules` 的结论，本批不新造对拍机）。
//
// 三条形状纪律（都钉在代码里，不靠调用方自觉）：
// 1. **契约住在 SKILL.md 头部的注释行里，一行一条**：
//    `<!-- contract appliesWhen url_domain=example.com -->`。
//    为什么不照派工单 §1 那样把 YAML 块原样贴进头部：`SkillStore.firstSentence`（索引行的「场景」）
//    取的是**第一条非注释行**，`SkillStore.contentWithoutHeader`（卡片上可编辑的正文）剥的是
//    **开头连续的整行注释**。YAML 块一进头部，索引场景就变成 "appliesWhen:"、卡片一改就把契约整段吃掉；
//    这两条纪律都在 `SkillStore.kt`，本批红线不许碰它。一行一条注释同时避开两处，
//    而且人手改坏一行，报错能指到**第几行**（整份拒收，不会静默吞掉，也不会静默收下）。
// 2. **类型是闭集**（`SkillContract.ConditionKind`／`SuccessKind`）：认不得的类型 ⇒ `Parsed.Invalid`
//    （拒收），不猜、不给默认值——「看着成功其实没有」是本批最反感的一种假话，也是 §4 负例②的靶子。
// 3. 本文件**零 IO、零时钟、零 Android**：parse／assess／rank／review 全是纯函数
//    （负例⑦「解析带副作用」在这里无路可走，`SkillContractPurityTest` 拿源码钉死）。
//    写盘仍然只有 `SkillStore.apply` 一处，改契约照样过确认卡——本批一个字节都不写。
package com.example.zhengdao.baopu

// ── A · 契约的形状、读法与写法（派工单 §1）────────────────────────────────────

/**
 * SKILL.md 头部契约行的**解析与渲染**（纯函数）。
 *
 * 三段（§1 原文）：`appliesWhen`（该不该用）／`success`（成没成，必须可观察）／
 * `protected`（修订时不许碰的语义约束）。
 */
internal object SkillContract {

    /** 契约行的固定前缀（`<!-- contract … -->`）。 */
    const val MARK = "contract"

    const val SECTION_APPLIES = "appliesWhen"
    const val SECTION_SUCCESS = "success"
    const val SECTION_PROTECTED = "protected"

    /**
     * 第三百批：现实指纹（**可选段**）。
     *
     * 为什么可选：[missingSections] 只数前三段——没有指纹的技能只是"不做漂移比对"，
     * 不是"契约不齐"。把指纹算进齐不齐，会让全仓既有技能一夜之间掉出自动选中（那是改坏，不是改对）。
     * 指纹自己的解析/渲染在 [DriftCheck]（同一行一条注释，同一个 `contract` 前缀）。
     */
    const val SECTION_FINGERPRINT = "fingerprint"

    /** 段名闭集（未知段 ⇒ 拒收）。 */
    val SECTIONS: List<String> =
        listOf(SECTION_APPLIES, SECTION_SUCCESS, SECTION_PROTECTED, SECTION_FINGERPRINT)

    /** HTML 注释的收尾串（值里出现它会拆掉整行，解析前先剥掉）。 */
    internal const val COMMENT_CLOSE = "-->"

    /** 「该不该用」的条件类型——闭集（§1 规则 2）。 */
    enum class ConditionKind(val wire: String) {
        /** 当前页面域名（相等或子域都算）。 */
        URL_DOMAIN("url_domain"),

        /** 页面上有某段文案／某个元素。 */
        PAGE_HAS("page_has"),

        /** 工作区里有某个文件（支持 `*`／`?` 通配）。 */
        WORKSPACE_HAS("workspace_has"),
        ;

        companion object {
            /** 线值 ⇒ 类型；**认不得就 null**（调用方必须判拒收，不许兜默认值）。 */
            fun fromWire(wire: String): ConditionKind? = entries.firstOrNull { it.wire == wire }
        }
    }

    /** 「成没成」的判据类型——闭集，两种都是**页面上量得出来的**（§1 原文「必须可观察」）。 */
    enum class SuccessKind(val wire: String) {
        /** 某个元素/文案出现了。 */
        ELEMENT_APPEARS("element_appears"),

        /** 某个区域有非空文本。 */
        TEXT_NONEMPTY("text_nonempty"),
        ;

        companion object {
            fun fromWire(wire: String): SuccessKind? = entries.firstOrNull { it.wire == wire }
        }
    }

    /**
     * 「模型觉得成了」那一类空话——写进 `success` 的**值**里也照样拒收。
     *
     * 闭集类型只挡住「kind 不认识」这一种；§4 负例④走的是另一条路：类型合法
     * （`element_appears`）但值填「完成」。判据只看这一条值是不是一个**看得见东西的名字**，
     * 所以这张小表钉在值上。（「审核通过」这类页面上真有四个字的是 `文案「通过」`，
     * 整表只掐**裸的一个结论词**，不误伤带引号的真文案。）
     */
    private val VAGUE_SUCCESS_VALUES: Set<String> = setOf(
        "完成", "成功了", "成功", "好了", "搞定", "没问题", "结束", "结束了", "执行完毕",
        "done", "ok", "okay", "fine", "success", "finished", "completed",
    )

    /** 一条适用条件。 */
    data class Condition(val kind: ConditionKind, val value: String)

    /** 一条成功判据。 */
    data class Success(val kind: SuccessKind, val value: String)

    /**
     * 一份读成的契约。
     *
     * @param protectedClauses 段名沿用派工单的 `protected`；Kotlin 里 `protected` 是关键字，
     *   字段名取 `protectedClauses`，**线值仍是 `protected`**（读写两头都以线值为准）。
     * @param fingerprints 第三百批的现实指纹（可空：一条都没有＝不做漂移比对，见 [SECTION_FINGERPRINT]）。
     */
    data class Contract(
        val appliesWhen: List<Condition>,
        val success: List<Success>,
        val protectedClauses: List<String>,
        val fingerprints: List<DriftFingerprint> = emptyList(),
    ) {
        /** 三段里哪几段一条都没有（齐 = 三段都有；§2 规则 4 的 fail-closed 用它）。指纹不在其中。 */
        fun missingSections(): List<String> = buildList {
            if (appliesWhen.isEmpty()) add(SECTION_APPLIES)
            if (success.isEmpty()) add(SECTION_SUCCESS)
            if (protectedClauses.isEmpty()) add(SECTION_PROTECTED)
        }
    }

    /** 解析结果（三态；调用方**必须**逐态处理，不许把 Invalid 当 Absent 放过）。 */
    sealed interface Parsed {
        /** 一条契约行都没有 ⇒ 技能页标 `contract_missing`，**不许被自动选中**（§1 规则 1）。 */
        data object Absent : Parsed

        /** 有契约行但读不成（未知类型／值空／判据不可观察）⇒ 拒收，逐条给原因。 */
        data class Invalid(val errors: List<String>) : Parsed

        /** 读成了；[missingSections] 非空 ⇒ 不齐，同样不参与自动选中（fail-closed）。 */
        data class Contracted(val contract: Contract, val missingSections: List<String>) : Parsed {
            val complete: Boolean get() = missingSections.isEmpty()
        }
    }

    /** 整行机器注释（`<!-- … -->`：草稿头、契约行都算；标题与人话正文都不算）。 */
    private fun isCommentLine(trimmed: String): Boolean =
        trimmed.startsWith("<!--") && trimmed.endsWith(COMMENT_CLOSE)

    /** 这一行是不是契约行（`<!-- contract … -->`；`<!-- status=… uses=… -->` 那一类头不算）。返回契约段内容。 */
    private fun contractLineOf(trimmed: String): String? {
        if (!isCommentLine(trimmed)) return null
        val inner = trimmed.removePrefix("<!--").removeSuffix(COMMENT_CLOSE).trim()
        if (inner == MARK) return ""
        if (!inner.startsWith("$MARK ")) return null
        return inner.removePrefix(MARK).trim()
    }

    /** 契约行（行号从 1 起，只为给人指到第几行）；顺序＝文件里的顺序。 */
    private fun contractLines(body: String): List<Pair<Int, String>> =
        body.lines().mapIndexed { i, line -> Pair(i + 1, line.trim()) }
            .mapNotNull { (no, t) -> contractLineOf(t)?.let { Pair(no, it) } }

    /** 剥掉首尾引号（`"x"`／`“x”`／`「x」`），只剥**成对**的那一层。 */
    private fun unquote(raw: String): String {
        val s = raw.trim().replace(COMMENT_CLOSE, "")
        val pairs = listOf(
            Pair('"', '"'), Pair('“', '”'), Pair('「', '」'),
            Pair('‘', '’'), Pair('\'', '\''),
        )
        val hit = pairs.firstOrNull { s.length >= 2 && s.startsWith(it.first) && s.endsWith(it.second) }
            ?: return s.trim()
        return s.substring(1, s.length - 1).trim()
    }

    /** 域名形状（小写、只留字母数字点连字符、至少一个点、点不带头尾）。第三百批起 [DriftCheck] 也用它判指纹里的域名。 */
    internal fun looksLikeDomain(v: String): Boolean {
        val s = v.trim().lowercase()
        if (s.isEmpty() || s.startsWith(".") || s.endsWith(".") || s.contains("..")) return false
        if (!s.contains('.')) return false
        return s.all { it.isLetterOrDigit() || it == '.' || it == '-' }
    }

    /**
     * 读契约（**纯函数**：只吃字符串，不碰文件、不取时间、不看全局）。
     *
     * 错误**逐行列全**（不在第一处就返回），一次就能看完所有要改的地方；
     * 但只要有一条 ⇒ 整份 [Parsed.Invalid]：没有「坏行作废、好行照用」的半份契约——
     * 半份契约会少数条件条数，§2 的 fail-closed 与排序就都判错了。
     * 「一条技能完全没写契约」是另一条路（[Parsed.Absent]，可降级手动点名，见 §4 负例⑧）。
     */
    fun parse(body: String): Parsed {
        val lines = contractLines(body)
        if (lines.isEmpty()) return Parsed.Absent
        val applies = mutableListOf<Condition>()
        val success = mutableListOf<Success>()
        val protectedClauses = mutableListOf<String>()
        val fingerprints = mutableListOf<DriftFingerprint>()
        val errors = mutableListOf<String>()

        for ((no, payload) in lines) {
            if (payload.isEmpty()) {
                errors += "第 $no 行：契约行是空的（写法：<!-- contract $SECTION_APPLIES ${ConditionKind.URL_DOMAIN.wire}=example.com -->）"
                continue
            }
            val sp = payload.indexOf(' ')
            val section = if (sp > 0) payload.substring(0, sp) else payload
            val rest = if (sp > 0) payload.substring(sp + 1).trim() else ""
            when (section) {
                SECTION_APPLIES -> applyAppliesLine(no, rest, applies, errors)

                SECTION_SUCCESS -> applySuccessLine(no, rest, success, errors)

                SECTION_PROTECTED -> applyProtectedLine(no, rest, protectedClauses, errors)

                SECTION_FINGERPRINT -> DriftCheck.parseLine(no, rest, fingerprints, errors)

                else -> errors += "第 $no 行：不认得契约段「$section」（只许 ${SECTIONS.joinToString("／")}）"
            }
        }
        if (errors.isNotEmpty()) return Parsed.Invalid(errors)
        val contract = Contract(applies.toList(), success.toList(), protectedClauses.toList(), fingerprints.toList())
        return Parsed.Contracted(contract, contract.missingSections())
    }

    /**
     * 解析一行 `protected <一句话>`（2026-10-31 抽出）。
     *
     * 为什么要拆：第三百批给 `parse` 加了 `fingerprint` 段（多一个 when 分支），复杂度到 16 越线；
     * 而 `protected` 这三行本来就是一个独立判据（空/重复/收下），搬出去就把 `parse` 拉回 when 分发本身。
     * 错误文案逐字保留。
     */
    private fun applyProtectedLine(
        no: Int,
        rest: String,
        out: MutableList<String>,
        errors: MutableList<String>,
    ) {
        val clause = unquote(rest)
        if (clause.isEmpty()) {
            errors += "第 $no 行：$SECTION_PROTECTED 这条是空的——受保护约束要写成人话一句（例：不点提交/保存/支付类按钮）"
        } else if (clause !in out) {
            out += clause
        }
    }

    /**
     * 解析一行 `applies <类型>=<值>`（2026-10-29 从 [parse] 里抽出来：
     * 它是**一个可以单独读、单独测的校验单元**，留在循环里只会把 parse 撑到 70+ 行）。
     * 错误文案逐字保留——单测对着这些句子断言。
     */
    private fun applyAppliesLine(
        no: Int,
        rest: String,
        applies: MutableList<Condition>,
        errors: MutableList<String>,
    ) {
        val eq = rest.indexOf('=')
        if (eq <= 0) {
            errors += "第 $no 行：$SECTION_APPLIES 少了「类型=值」（例：$SECTION_APPLIES ${ConditionKind.WORKSPACE_HAS.wire}=配货单-*.xlsx）"
            return
        }
        val k = rest.substring(0, eq).trim()
        val v = unquote(rest.substring(eq + 1))
        val kind = ConditionKind.fromWire(k)
        when {
            kind == null -> errors +=
                "第 $no 行：未知的适用条件类型「$k」（只许 ${ConditionKind.entries.joinToString("／") { it.wire }}）"
            v.isEmpty() -> errors += "第 $no 行：$k 的值是空的"
            kind == ConditionKind.URL_DOMAIN && !looksLikeDomain(v) ->
                errors += "第 $no 行：url_domain 的值「$v」不像域名（例：example.com）"
            else -> {
                val c = Condition(kind, if (kind == ConditionKind.URL_DOMAIN) v.lowercase() else v)
                if (c !in applies) applies += c
            }
        }
    }

    /**
     * 解析一行 `success <类型>=<值>`（同上，从 [parse] 抽出）。
     * 「不可观察判据」那道拦（`完成`/`成功` 之类）只在这里，别挪走——它是 §2 的可执行面。
     */
    private fun applySuccessLine(
        no: Int,
        rest: String,
        success: MutableList<Success>,
        errors: MutableList<String>,
    ) {
        val eq = rest.indexOf('=')
        if (eq <= 0) {
            errors += "第 $no 行：$SECTION_SUCCESS 少了「类型=值」（例：$SECTION_SUCCESS ${SuccessKind.ELEMENT_APPEARS.wire}=文案「装车状态」）"
            return
        }
        val k = rest.substring(0, eq).trim()
        val v = unquote(rest.substring(eq + 1))
        val kind = SuccessKind.fromWire(k)
        when {
            kind == null -> errors +=
                "第 $no 行：未知的成功判据类型「$k」（只许 ${SuccessKind.entries.joinToString("／") { it.wire }}）"
            v.isEmpty() -> errors += "第 $no 行：$k 的值是空的"
            v.lowercase() in VAGUE_SUCCESS_VALUES -> errors +=
                "第 $no 行：「$v」不是可观察判据——要写页面上看得见的东西（元素文案或区域名），不许写「完成」这类结论"
            else -> {
                val s = Success(kind, v)
                if (s !in success) success += s
            }
        }
    }

    /** 缺契约／读不成／不齐——给人看的一句话（三种要分开说，别混成一句「没有契约」）。 */
    fun describe(parsed: Parsed): String = when (parsed) {
        Parsed.Absent -> "contract_missing：这条技能一条契约都没有"
        is Parsed.Invalid -> "契约读不成：" + parsed.errors.joinToString("；")
        is Parsed.Contracted -> if (parsed.complete) {
            "契约齐：适用 ${parsed.contract.appliesWhen.size} 条／成功 ${parsed.contract.success.size} 条／受保护 ${parsed.contract.protectedClauses.size} 条"
        } else {
            "契约不全（缺 ${parsed.missingSections.joinToString("／")}）"
        }
    }

    /**
     * 契约的**验收清单**（派工单 §1 规则 3 点名的第二个口）。
     *
     * 与 [parse] 的分工：parse 管「怎么读」（三态、逐行的原因），validate 管「能不能用」
     * （一张扁平的问题清单，空＝能用）。调用方只想知道「有没有事」时用这一口，
     * 不必自己拆三态——而「缺契约」在这里**有字**（负例①的静默放过在这条路上没有出口）。
     */
    fun validate(body: String): List<String> = when (val p = parse(body)) {
        Parsed.Absent -> listOf("一条契约都没有（contract_missing）")
        is Parsed.Invalid -> p.errors
        is Parsed.Contracted -> p.missingSections.map { section -> "缺 $section 段" }
    }

    /**
     * 技能页上的一行短标签（详版在 [describe]，页面只放状态本身）。
     *
     * 单独有一个短版，是为了界面那句「契约：X」不必把整段诊断塞进 labelSmall 那一行；
     * 四种状态都**有字**——空白＝没账，这在本仓是最忌讳的一种显示。
     */
    fun shortLabel(parsed: Parsed): String = when (parsed) {
        Parsed.Absent -> "缺"
        is Parsed.Invalid -> "读不成"
        is Parsed.Contracted -> if (parsed.complete) "齐" else "缺 ${parsed.missingSections.joinToString("／")}"
    }

    /** 一份契约 ⇒ 头部行（渲染顺序固定：appliesWhen → success → protected → fingerprint；**与 [parse] 互为逆运算**）。 */
    fun render(contract: Contract): List<String> = buildList {
        contract.appliesWhen.forEach { add("<!-- $MARK $SECTION_APPLIES ${it.kind.wire}=${it.value} -->") }
        contract.success.forEach { add("<!-- $MARK $SECTION_SUCCESS ${it.kind.wire}=${it.value} -->") }
        contract.protectedClauses.forEach { add("<!-- $MARK $SECTION_PROTECTED $it -->") }
        contract.fingerprints.forEach { add(DriftCheck.renderLine(it)) }
    }

    /** 正文去掉所有契约行（其余行**原样**留着——修订门槛拿它比对「脚本有没有被动」）。 */
    fun strip(body: String): String =
        body.lines().filterNot { contractLineOf(it.trim()) != null }.joinToString("\n")

    /**
     * 把契约写回正文（**纯函数**，不落盘）。
     *
     * 位置钉在「第一条非注释、非空行之后」：`contentWithoutHeader` 只剥开头连续的注释行，
     * 契约跟在标题后面就不会被卡片编辑吃掉；`firstSentence`（索引场景）取的第一条非注释行
     * 仍是标题，索引不会被写成 "appliesWhen:"。
     */
    fun withContract(body: String, contract: Contract): String {
        val lines = strip(body).lines()
        val anchor = lines.indexOfFirst { it.isNotBlank() && !isCommentLine(it.trim()) }
        val rendered = render(contract)
        return if (anchor < 0) {
            (lines.filter { it.isNotBlank() } + rendered).joinToString("\n")
        } else {
            (lines.take(anchor + 1) + rendered + lines.drop(anchor + 1)).joinToString("\n")
        }
    }
}

// ── B · 适用性过滤（别用错技能，派工单 §2）────────────────────────────────────

/**
 * 一次评估要的**读数**——全部由调用方喂进来（harness 的当前页面/工作区快照），本批不自己去取。
 *
 * 每个读数都可为空；空读数在 [SkillApplicability] 里记 [SkillReading.NO_DATA]，**绝不当成满足**（fail-closed）。
 */
internal data class SkillContext(
    /** 当前页面地址（没有 ⇒ null）。 */
    val url: String? = null,
    /** 页面上看得见的元素文案（快照给的；一条一个元素）。 */
    val pageTexts: List<String> = emptyList(),
    /** 工作区里的文件（相对路径或文件名，`\\` 会归一成 `/`）。 */
    val workspaceFiles: List<String> = emptyList(),
    /** 命名区域的文本（`text_nonempty` 的原料：区域名 → 读到的文本）。 */
    val regions: Map<String, String> = emptyMap(),
)

/** 一条条件/判据的读数（三态：**没有读数**与**读数不符**是两回事，都要能对人明说）。 */
internal enum class SkillReading { YES, NO, NO_DATA }

/** 一条判完的账。 */
internal data class SkillCheck(
    val section: String,
    val kindWire: String,
    val value: String,
    val reading: SkillReading,
    val why: String,
)

/** 一条技能对当前上文的适配结论（§2 的四种情形）。 */
internal enum class SkillFit {
    /** 全部条件都满足 ⇒ 可选。 */
    MATCHED,

    /** 满足一部分 ⇒ 列出来给人看，标「前提不全」，不参与自动选中。 */
    PARTIAL,

    /** 一条都没满足 ⇒ 不选，并说明缺哪条。 */
    UNMATCHED,

    /** 缺契约／契约不全／契约读不成 ⇒ 按「未知」处理：只许人点名。 */
    UNKNOWN_CONTRACT,
}

/** 一条技能的候选形状（[body] 是 SKILL.md 正文原文）。 */
internal data class SkillCandidate(
    val name: String,
    val body: String,
    val uses: Int = 0,
    /** 第二百五十五批：验收结论（降级/过期/待退役 ⇒ false；由 [SkillAcceptance.verdict] 喂）。 */
    val acceptanceOk: Boolean = true,
    /** 验收未过的一句话原因（给人看；null ⇒ 用默认话）。 */
    val acceptanceNote: String? = null,
)

/** 一条技能的适配评估。 */
internal data class SkillFitResult(
    val name: String,
    val uses: Int,
    val fit: SkillFit,
    val checks: List<SkillCheck>,
    /** 给人的一句话：为什么不选／缺哪条／契约缺什么。 */
    val note: String,
    val contract: SkillContract.Contract?,
) {
    val matchedCount: Int get() = checks.count { it.reading == SkillReading.YES }

    /** **自动选中只认全满足**（§2 规则 1＋4：部分满足只是候选，缺契约一律不自动）。 */
    val autoSelectable: Boolean get() = fit == SkillFit.MATCHED
}

/** 一轮过滤的分拣结果（三段各自有序）。 */
internal data class SkillRanking(
    /** 满足全部前提 ⇒ 可自动选中（排序：满足条数 ↓ → uses ↓ → 名字 ↑）。 */
    val selectable: List<SkillFitResult> = emptyList(),
    /** 部分满足 ⇒ 候选，标「前提不全」，交人点。 */
    val weakCandidates: List<SkillFitResult> = emptyList(),
    /** 不满足／契约未知 ⇒ 不参与排序，只带原因。 */
    val excluded: List<SkillFitResult> = emptyList(),
)

internal object SkillApplicability {

    /** 域名读数：取 host（剥 scheme／userinfo／port／路径），认不得就 null。 */
    fun hostOf(url: String?): String? {
        val raw = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val afterScheme = raw.substringAfter("://", raw)
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringAfterLast('@')
        val noPort = host.substringBeforeLast(':')
        val cleaned = noPort.trim().lowercase().trimEnd('.')
        return cleaned.takeIf { it.length >= 2 && it.all { ch -> ch.isLetterOrDigit() || ch == '.' || ch == '-' } }
    }

    /** 「文案「查货」」／`"查货"`／「查货」 ⇒ 要匹配的文本。 */
    fun needleOf(value: String): String {
        for (pair in listOf(
            Pair('「', '」'), Pair('“', '”'), Pair('"', '"'), Pair('‘', '’'), Pair('\'', '\''),
        )) {
            val a = value.indexOf(pair.first)
            val b = value.lastIndexOf(pair.second)
            if (a in 0 until b) return value.substring(a + 1, b).trim()
        }
        return if (value.startsWith("文案") && value.length > 2) value.removePrefix("文案").trim() else value.trim()
    }

    private fun normPath(p: String): String = p.trim().replace('\\', '/')

    /** 通配匹配（`*`／`?`，大小写不敏感）；值里没通配符时按「等名或包含」。 */
    private fun globMatch(pattern: String, target: String): Boolean {
        val p = pattern.trim().lowercase()
        val t = target.lowercase()
        if (!p.contains('*') && !p.contains('?')) return t == p || t.contains(p)
        val regex = buildString {
            for (ch in p) when (ch) {
                '*' -> append(".*")
                '?' -> append(".")
                '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> append('\\').append(ch)
                else -> append(ch)
            }
        }
        return Regex(regex).matches(t)
    }

    /** 一条适用条件对当前读数的账。 */
    private fun checkCondition(c: SkillContract.Condition, ctx: SkillContext): SkillCheck = when (c.kind) {
        SkillContract.ConditionKind.URL_DOMAIN -> {
            val host = hostOf(ctx.url)
            when {
                host == null -> SkillCheck(
                    SkillContract.SECTION_APPLIES, c.kind.wire, c.value, SkillReading.NO_DATA,
                    "没有当前页面地址的读数，判不了域名",
                )
                host == c.value || host.endsWith(".${c.value}") ->
                    SkillCheck(SkillContract.SECTION_APPLIES, c.kind.wire, c.value, SkillReading.YES, "域名对得上：$host")
                else -> SkillCheck(
                    SkillContract.SECTION_APPLIES, c.kind.wire, c.value, SkillReading.NO,
                    "当前域名是 $host，不是这条技能要的 ${c.value}",
                )
            }
        }

        SkillContract.ConditionKind.PAGE_HAS -> {
            val needle = needleOf(c.value)
            when {
                ctx.pageTexts.isEmpty() -> SkillCheck(
                    SkillContract.SECTION_APPLIES, c.kind.wire, c.value, SkillReading.NO_DATA,
                    "没有页面文案的读数，判不了「$needle」在不在",
                )
                ctx.pageTexts.any { it.contains(needle, ignoreCase = true) } ->
                    SkillCheck(SkillContract.SECTION_APPLIES, c.kind.wire, c.value, SkillReading.YES, "页面上有「$needle」")
                else -> SkillCheck(
                    SkillContract.SECTION_APPLIES, c.kind.wire, c.value, SkillReading.NO,
                    "页面上找不到「$needle」",
                )
            }
        }

        SkillContract.ConditionKind.WORKSPACE_HAS -> {
            val files = ctx.workspaceFiles.map { normPath(it) }
            when {
                files.isEmpty() -> SkillCheck(
                    SkillContract.SECTION_APPLIES, c.kind.wire, c.value, SkillReading.NO_DATA,
                    "没有工作区文件清单的读数，判不了「${c.value}」在不在",
                )
                files.any { globMatch(c.value, it) || globMatch(c.value, it.substringAfterLast('/')) } ->
                    SkillCheck(SkillContract.SECTION_APPLIES, c.kind.wire, c.value, SkillReading.YES, "工作区里有「${c.value}」")
                else -> SkillCheck(
                    SkillContract.SECTION_APPLIES, c.kind.wire, c.value, SkillReading.NO,
                    "工作区里没有匹配「${c.value}」的文件",
                )
            }
        }
    }

    /**
     * 一条成功判据对当前读数的账（§1「必须可观察」的执行点）。
     *
     * 没有读数＝**判不了**，调用方按失败处理（负例④「模型觉得成了」在这里没有出口）。
     */
    private fun checkSuccess(s: SkillContract.Success, ctx: SkillContext): SkillCheck = when (s.kind) {
        SkillContract.SuccessKind.ELEMENT_APPEARS -> {
            val needle = needleOf(s.value)
            when {
                ctx.pageTexts.isEmpty() -> SkillCheck(
                    SkillContract.SECTION_SUCCESS, s.kind.wire, s.value, SkillReading.NO_DATA,
                    "跑完没有页面文案读数，判不了「$needle」有没有出现",
                )
                ctx.pageTexts.any { it.contains(needle, ignoreCase = true) } -> SkillCheck(
                    SkillContract.SECTION_SUCCESS, s.kind.wire, s.value, SkillReading.YES, "「$needle」出现了",
                )
                else -> SkillCheck(
                    SkillContract.SECTION_SUCCESS, s.kind.wire, s.value, SkillReading.NO, "跑完页面上没有「$needle」",
                )
            }
        }

        SkillContract.SuccessKind.TEXT_NONEMPTY -> {
            val region = needleOf(s.value)
            val hit = ctx.regions.entries.firstOrNull { it.key.trim().equals(region, ignoreCase = true) }
            when {
                hit == null -> SkillCheck(
                    SkillContract.SECTION_SUCCESS, s.kind.wire, s.value, SkillReading.NO_DATA,
                    "没有「$region」这个区域的读数",
                )
                hit.value.isNotBlank() -> SkillCheck(
                    SkillContract.SECTION_SUCCESS, s.kind.wire, s.value, SkillReading.YES,
                    "「$region」有文本（${hit.value.trim().length} 字）",
                )
                else -> SkillCheck(
                    SkillContract.SECTION_SUCCESS, s.kind.wire, s.value, SkillReading.NO, "「$region」是空的",
                )
            }
        }
    }

    /**
     * 评估一条技能对 [ctx] 的适配（§2 规则 1）。
     *
     * 缺契约／不全／读不成 ⇒ [SkillFit.UNKNOWN_CONTRACT]（§2 规则 4：按「未知」处理）；
     * 有完整契约 ⇒ 全满足 [SkillFit.MATCHED]，一条都不满足 [SkillFit.UNMATCHED]，中间 [SkillFit.PARTIAL]。
     */
    fun assess(
        name: String,
        body: String,
        uses: Int,
        ctx: SkillContext,
        acceptanceOk: Boolean = true,
        acceptanceNote: String? = null,
    ): SkillFitResult {
        // 第二百五十五批：验收不过（连续 2 败降级／指纹变／成功率 <60% 待退役）
        // ⇒ **不自动选中**。契约说「该用」也不自动——用坏了的东西不该自己凑上来。
        if (!acceptanceOk) {
            return SkillFitResult(
                name = name,
                uses = uses,
                fit = SkillFit.UNMATCHED,
                checks = emptyList(),
                note = acceptanceNote ?: "验收未过：不自动选中，可手动点名跑",
                contract = (SkillContract.parse(body) as? SkillContract.Parsed.Contracted)?.contract,
            )
        }
        val parsed = SkillContract.parse(body)
        if (parsed !is SkillContract.Parsed.Contracted || !parsed.complete) {
            return SkillFitResult(
                name = name,
                uses = uses,
                fit = SkillFit.UNKNOWN_CONTRACT,
                checks = emptyList(),
                note = SkillContract.describe(parsed),
                contract = (parsed as? SkillContract.Parsed.Contracted)?.contract,
            )
        }
        val checks = parsed.contract.appliesWhen.map { checkCondition(it, ctx) }
        val yes = checks.count { it.reading == SkillReading.YES }
        val missing = checks.filter { it.reading != SkillReading.YES }
        val fit = if (yes == checks.size) SkillFit.MATCHED else if (yes == 0) SkillFit.UNMATCHED else SkillFit.PARTIAL
        val note = when (fit) {
            SkillFit.MATCHED -> "前提都满足（${checks.size} 条）"
            SkillFit.PARTIAL -> "前提不全：${missing.joinToString("；") { it.why }}"
            SkillFit.UNMATCHED -> if (missing.all { it.reading == SkillReading.NO_DATA }) {
                "判不了前提：${missing.joinToString("；") { it.why }}"
            } else {
                "前提不符，不该用这条：${missing.joinToString("；") { it.why }}"
            }
            SkillFit.UNKNOWN_CONTRACT -> "没有可用契约"
        }
        return SkillFitResult(name, uses, fit, checks, note, parsed.contract)
    }

    /** 满足条数 ↓ → uses ↓ → 名字 ↑（§2 规则 3：不满足的不参与排序，压根不在这条链上）。 */
    private fun ranked(list: List<SkillFitResult>): List<SkillFitResult> =
        list.sortedWith(
            compareByDescending<SkillFitResult> { it.matchedCount }
                .thenByDescending { it.uses }
                .thenBy { it.name },
        )

    /** 一轮适用性过滤（§2 规则 1＋3 的分拣）。 */
    fun rank(candidates: List<SkillCandidate>, ctx: SkillContext): SkillRanking {
        val all = candidates.map { assess(it.name, it.body, it.uses, ctx, it.acceptanceOk, it.acceptanceNote) }
        return SkillRanking(
            selectable = ranked(all.filter { it.fit == SkillFit.MATCHED }),
            weakCandidates = ranked(all.filter { it.fit == SkillFit.PARTIAL }),
            excluded = all.filter { it.fit == SkillFit.UNMATCHED || it.fit == SkillFit.UNKNOWN_CONTRACT }
                .sortedBy { it.name },
        )
    }

    /**
     * 自动选中唯一入口（§2 规则 4 的闸）：只有全满足的第一条能被判「就用它」，
     * 其余一律 null——**缺契约的、前提不全的、前提不符的都轮不到自动选中**。
     */
    fun autoPick(ranking: SkillRanking): SkillFitResult? = ranking.selectable.firstOrNull()

    /**
     * 人手动点名能不能跑（**四种状态一律能**——契约挡的是「自动」，不给人加锁）。
     *
     * 这条就是 §4 负例⑧的降级路径：现有技能（种子、老手记）都没契约，
     * 不许因为「加了契约这件事」变得点名也点不动。
     */
    fun manualPickAllowed(fit: SkillFit): Boolean = when (fit) {
        SkillFit.MATCHED, SkillFit.PARTIAL, SkillFit.UNMATCHED, SkillFit.UNKNOWN_CONTRACT -> true
    }

    /** 回放前的提示语（§1 规则 1「回放前提示先补契约」）；契约齐 ⇒ null（不啰嗦）。 */
    fun preReplayNote(body: String): String? {
        val parsed = SkillContract.parse(body)
        val bad = when (parsed) {
            is SkillContract.Parsed.Contracted -> !parsed.complete
            else -> true
        }
        return if (bad) {
            "先补契约（${SkillContract.describe(parsed)}）——这条没验过适用条件与成功判据，这次照你的意思跑"
        } else {
            null
        }
    }

    /** 成功判据整体判一次（§4：满足 ⇒ 判成功；不满足 ⇒ 判失败，没有第三条）。 */
    fun verifySuccess(contract: SkillContract.Contract, ctx: SkillContext): Pair<Boolean, List<SkillCheck>> {
        val checks = contract.success.map { checkSuccess(it, ctx) }
        return (checks.isNotEmpty() && checks.all { it.reading == SkillReading.YES }) to checks
    }
}

// ── C · 修订边界（不一致才修订，且只许改边界，派工单 §3）────────────────────────

/**
 * 「改契约边界」的门槛（纯函数；本批**不落盘**——写盘仍只有 `SkillStore.apply`，照样过确认卡）。
 *
 * 四道门槛，任一不过 ⇒ 拒收并写明原因：
 * ① 正文（步骤/脚本）逐字不变——修订只许动头部契约行；
 * ② 新契约自身读得成、三段齐；
 * ③ `protected` 一条没动（重排算没动，少一条/改一个字算动）；
 * ④ **必须带 206 对拍的结论**且不回归（没有＝没有证据＝不许采纳；`protected` 是空的**老契约**
 * 补第一条契约也照这条办——加契约会让这条**头一回**有资格被自动选中，那同样是行为改变）。
 */
internal object SkillContractRevision {

    /** 一条修订的判决。 */
    data class Verdict(
        val accepted: Boolean,
        val reasons: List<String>,
        /** 206 对拍那一行原文（`结论｜原因`）；没跑 ⇒ null。 */
        val compareLine: String?,
        /** 新契约（只在 accepted 时给）。 */
        val contract: SkillContract.Contract?,
    )

    /** 正文差异行数（契约行之外的行；0 ＝脚本一个字没动）。 */
    fun proseDiffLines(currentBody: String, proposedBody: String): Int {
        val a = SkillContract.strip(currentBody).lines()
        val b = SkillContract.strip(proposedBody).lines()
        if (a == b) return 0
        var diff = 0
        for (i in 0 until maxOf(a.size, b.size)) if (a.getOrNull(i) != b.getOrNull(i)) diff++
        return diff
    }

    /** `protected` 有没有被动（一条都没动才算过；重排算没动）。 */
    fun protectedUntouched(old: List<String>, new: List<String>): Boolean =
        old.size == new.size && old.sorted() == new.sorted()

    /**
     * 「结果与判据不符 ⇒ 出改边界的提议」（§3 第一条；注意它**只出一句话**，不改脚本一个字）。
     *
     * [unmet] 取 [SkillApplicability.verifySuccess] 里没判 [SkillReading.YES] 的那些。
     */
    fun boundaryProposal(name: String, unmet: List<SkillCheck>): String =
        "「$name」判据没兑现：${unmet.joinToString("；") { "${it.kindWire}「${it.value}」——${it.why}" } }" +
            " ⇒ 改这条技能的**契约边界**（补/改 appliesWhen 或 success），不是直接改脚本步骤。"

    /**
     * 判一次修订。
     *
     * @param compare 调用方拿 206 的 `EvalCompareRules.compare(...)` 算出来的结论；
     *   **null ＝ 没跑历史回放**（按 §3「任一不满足 ⇒ 拒收」，这里没有「先采纳再说」这条路）。
     */
    fun review(
        name: String,
        currentBody: String,
        proposedBody: String,
        compare: EvalCompare?,
    ): Verdict {
        val reasons = mutableListOf<String>()
        val current = SkillContract.parse(currentBody)
        val proposed = SkillContract.parse(proposedBody)
        val oldContract = (current as? SkillContract.Parsed.Contracted)?.contract

        // ① 只许改边界
        val diff = proseDiffLines(currentBody, proposedBody)
        if (diff > 0) reasons += "修订只许改契约边界：正文（步骤/脚本）动了 $diff 行——想改做法走示教，不走契约"

        // ② 新契约自身要立得住
        val newContract = (proposed as? SkillContract.Parsed.Contracted)?.contract
        when (proposed) {
            is SkillContract.Parsed.Invalid -> reasons += "新契约读不成：${proposed.errors.joinToString("；")}"
            is SkillContract.Parsed.Contracted -> if (!proposed.complete) {
                reasons += "新契约还缺 ${proposed.missingSections.joinToString("／")}——补齐再谈修订"
            }
            SkillContract.Parsed.Absent -> reasons += "新正文把契约整段删了：这是撤契约，不是改边界"
        }
        if (current is SkillContract.Parsed.Invalid) {
            reasons += "原契约读不成（${current.errors.joinToString("；")}）：没有可比的基线，先把契约本身改对再谈修订"
        }

        // ③ protected 一条没动（原契约压根没有 protected 段 ⇒ 没有可被动的东西，跳过）
        if (oldContract != null && newContract != null &&
            !protectedUntouched(oldContract.protectedClauses, newContract.protectedClauses)
        ) {
            reasons += "protected 被动了：原 ${oldContract.protectedClauses.size} 条 → 新 ${newContract.protectedClauses.size} 条，受保护语义约束修订不许碰"
        }

        // ④ 206 对拍：没跑＝没证据；REJECT/结果列退化＝回归
        val line = compare?.let { "${it.verdict.name}｜${it.reason}" }
        if (compare == null) {
            reasons += "没跑 206 历史回放（对拍）：没有「不回归」的证据 ⇒ 不许采纳"
        } else {
            if (compare.skillName != name) reasons += "对拍的不是这条技能：结论挂在「${compare.skillName}」上"
            if (compare.verdict == EvalVerdict.REJECT) {
                reasons += "206 对拍判 REJECT：${compare.reason}（改坏了，不许采纳）"
            } else {
                val worse = compare.resultDiff.filter { it.contains("变差") || it.contains("产物少了") }
                if (worse.isNotEmpty()) reasons += "206 对拍的结果列有退化：${worse.joinToString("；")}"
            }
        }

        val ok = reasons.isEmpty()
        return Verdict(
            accepted = ok,
            reasons = reasons.toList(),
            compareLine = line,
            contract = if (ok) newContract else null,
        )
    }

    /** 决策中心上给人看的一行结论（**只出结论，不代替人判**）。 */
    fun brief(v: Verdict, name: String): String =
        if (v.accepted) "契约边界修订「$name」四道门槛都过（${v.compareLine ?: "无对拍行"}）——待人判决"
        else "契约边界修订「$name」拒收：" + v.reasons.joinToString("；")
}
