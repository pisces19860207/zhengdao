// 独立开发声明：本文件为本项目原创。
//
// 第二百零二批 · 口味档案与范例库的**纯判据**（解析 / 证据校验 / 禁区优先 / 范例挑选 / 注入拼装）。
// 图纸：`_zd-private/docs/派工单-2026-10-25-第二百零二批-题材偏好与范例库.md`。
//
// 口径（用户 2026-10-25）：「不搞黑箱画像；每条偏好都能落到证据、你说不对就能删」。
// 所以这里的判据只有两件事：
//   ① 档案是一段人可读的 markdown，**没有「证据：」的条目一律丢弃**（不静默生效）；
//   ② 禁区（[TasteSection.FORBIDDEN]）优先级最高：命中直接不出候选，且注入时排在最前。
//
// 零 Android / 零 IO / 零时钟：读盘写盘在 `ExampleBank`（Context 只在那层薄壳里），
// 脱敏用一个 `(String) -> String` 参数传进来（不 import 浏览器包，保持可测）。
package com.example.zhengdao.baopu

/** 档案的三个段（标题即文件里的二级标题；顺序固定＝派工单原文顺序）。 */
internal enum class TasteSection(val heading: String) {
    THEME("题材偏"),
    STYLE("写法偏"),

    /** 禁区：优先级最高，命中直接不出候选。 */
    FORBIDDEN("禁区"),
}

/** 一条偏好：正文 ＋ 证据（证据必须非空，否则不进 [TasteArchive.entries]）。 */
internal data class TasteEntry(
    val section: TasteSection,
    val text: String,
    val evidence: String,
) {
    /** 稳定标识＝段＋正文（去掉这一条就靠它，跨进程、跨重排都不变）。 */
    val id: String get() = "${section.name}|$text"
}

/** 被丢弃的行：原因可读，设置页要原样列出来（不然用户以为「写了就生效」）。 */
internal data class TasteRejected(val line: String, val reason: String)

/** 一份档案（entries 已过滤；rejected 留着给设置页解释为什么不生效）。 */
internal data class TasteArchive(
    val entries: List<TasteEntry> = emptyList(),
    val rejected: List<TasteRejected> = emptyList(),
    val version: String? = null,
) {
    fun bySection(section: TasteSection): List<TasteEntry> = entries.filter { it.section == section }

    val isEmpty: Boolean get() = entries.isEmpty()

    /** 设置页表头用：`题材 2 · 写法 1 · 禁区 1`。 */
    fun summary(): String = TasteSection.entries.joinToString(" · ") {
        "${it.heading} ${bySection(it).size}"
    }
}

/** 范例库的正/负两类（对应 `产物/范例/正`、`产物/范例/负`）。 */
internal enum class SampleKind(val dirName: String, val label: String) {
    GOOD("正", "正样本"),
    BAD("负", "负样本"),
}

/**
 * 一篇范例。
 *
 * @param name 文件名（卡面与「移出」都按它，用户认这个）
 * @param title 正文首行的标题（没有就用文件名）
 * @param reason 负样本的一句原因（`<!-- 原因：… -->` 或 `> 原因：…` 里取；正样本为 null）
 * @param body 正文（注入前还会被截断与脱敏）
 */
internal data class TasteSample(
    val kind: SampleKind,
    val name: String,
    val title: String,
    val reason: String?,
    val body: String,
)

/** 一次注入的全部材料（block 是给模型的正文，goods/bad 是给卡面与设置页的显示名）。 */
internal data class TasteInjection(
    val block: String,
    val goods: List<String>,
    val bad: String?,
    /** 有话要说时给用户的一句话（如「正样本只有 1 篇，效果有限」）；空表示没话说。 */
    val notes: List<String> = emptyList(),
)

/** 禁区剔除的一条账（交付报告与卡面都用它，写清「因禁区 X 被剔除」）。 */
internal data class TasteDrop(val seedId: String, val title: String, val entry: TasteEntry) {
    val reason: String get() = "因禁区「${entry.text}」被剔除（证据：${entry.evidence}）"
}

/** 排序后的候选批（dropped 是禁区剔除的，给报告；batch 是给卡面的）。 */
internal data class TasteRanked(val batch: TopicSuggestBatch, val dropped: List<TasteDrop>)

/**
 * 第二百零二批纯判据本体。
 *
 * 纪律：这里**不许**出现时间、文件、Context；所有输出可复现（同一份档案同一批候选 ⇒ 同一结果）。
 */
internal object TasteProfile {

    /** 档案文件名（放在 `<工作区>/产物/` 下，与台账同屋）。 */
    const val ARCHIVE_NAME = "口味档案.md"

    /** 范例库目录名与已移出清单名（移出不删文件，只在这里记一行）。 */
    const val EXAMPLES_DIR = "范例"
    const val MUTED_FILE = "已移出.txt"

    /** 注入时每篇正文截断到多少字（整篇塞进去会把上下文吃光）。 */
    const val BODY_LIMIT = 1200

    /** 正样本注入上限（派工单：2–3 篇）；负样本固定 1 篇。 */
    const val MAX_GOOD = 3
    const val MAX_BAD = 1

    // ── 解析 ──────────────────────────────────────────────────────────────

    private val HEADING = Regex("^#+\\s*(.+)$")
    private val EVIDENCE_MARK = Regex("证据\\s*[：:]")
    private val VERSION = Regex("口味档案\\s*(v\\d+)")

    /** 丢弃原因（设置页原样显示）。 */
    const val WHY_NO_SECTION = "不在「题材偏／写法偏／禁区」任何一段里"
    const val WHY_NO_EVIDENCE = "没有证据行（每条都要写「证据：」）"
    const val WHY_EMPTY_TEXT = "这条只有证据没有内容"

    /**
     * 解析档案。**没有证据的条目一律进 [TasteArchive.rejected]，不进 entries**——
     * 这是派工单负例 1 的靶心：宁可少一条，也不让无凭据的规则影响出稿。
     */
    fun parse(md: String?): TasteArchive {
        val entries = mutableListOf<TasteEntry>()
        val rejected = mutableListOf<TasteRejected>()
        var version: String? = null
        var section: TasteSection? = null
        if (md.isNullOrBlank()) return TasteArchive()

        md.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("<!--")) {
                VERSION.find(line)?.let { version = it.groupValues[1] }
                return@forEach
            }
            if (line.startsWith("#")) {
                val head = HEADING.find(line)?.groupValues?.get(1)?.trim()
                section = TasteSection.entries.firstOrNull { head != null && head.startsWith(it.heading) }
                return@forEach
            }
            if (!line.startsWith("-")) return@forEach

            val body = line.removePrefix("-").trim()
            if (body.isEmpty()) return@forEach
            val sec = section ?: run {
                rejected += TasteRejected(line, WHY_NO_SECTION)
                return@forEach
            }
            val cut = splitEvidence(body)
            if (cut == null) {
                rejected += TasteRejected(line, WHY_NO_EVIDENCE)
                return@forEach
            }
            val (text, evidence) = cut
            when {
                evidence.isBlank() -> rejected += TasteRejected(line, WHY_NO_EVIDENCE)
                text.isBlank() -> rejected += TasteRejected(line, WHY_EMPTY_TEXT)
                else -> entries += TasteEntry(sec, text, evidence)
            }
        }
        return TasteArchive(entries, rejected, version)
    }

    /** `正文 — 证据：xxx` 切成两半；没有「证据：」⇒ null。容忍全角/半角冒号与 1–2 个破折号。 */
    private fun splitEvidence(body: String): Pair<String, String>? {
        val m = EVIDENCE_MARK.find(body) ?: return null
        val text = body.substring(0, m.range.first).trim().trimEnd('—', '–', '-', '：', ':', ' ', '\t')
        val evidence = body.substring(m.range.last + 1).trim()
        return text to evidence
    }

    /** 写回文件（人可读；版本注释带时间戳，由调用方给——本文件不碰时钟）。 */
    fun render(archive: TasteArchive, stamp: String): String = buildString {
        appendLine("# 口味档案")
        appendLine()
        appendLine("<!-- 口味档案 ${archive.version ?: "v1"} · $stamp · 每条必须带「证据：」；删掉的条目下一轮就不再注入 -->")
        appendLine()
        TasteSection.entries.forEach { sec ->
            appendLine("## " + sec.heading + if (sec == TasteSection.FORBIDDEN) "（优先级最高，命中直接不出候选）" else "")
            val rows = archive.bySection(sec)
            if (rows.isEmpty()) {
                // 空段用注释占位：`- （暂无）` 会被解析成「没证据的条目」⇒ 设置页误报
                appendLine("<!-- 暂无 -->")
            } else {
                rows.forEach { appendLine("- ${it.text} — 证据：${it.evidence}") }
            }
            appendLine()
        }
    }

    /** 删一条（按 [TasteEntry.id]；找不到就原样返回，设置页据此报「已经不在里面了」）。 */
    fun remove(archive: TasteArchive, id: String): TasteArchive =
        archive.copy(entries = archive.entries.filterNot { it.id == id })

    // ── 禁区与偏好命中 ────────────────────────────────────────────────────

    /** 去掉「不写／别写／禁止…」前缀后的核心词（命中判定与解释都用它）。 */
    fun term(text: String): String {
        var t = text.trim()
        val prefixes = listOf(
            "不要再写", "不要写", "不许再写", "不许写", "禁止再写", "禁止写",
            "别再写", "别写", "不写", "不碰", "不要", "不许", "禁止", "避免",
        )
        prefixes.forEach { p -> if (t.startsWith(p)) t = t.removePrefix(p).trim() }
        t = t.trimStart('：', ':', '，', ',', '、', ' ')
        val tails = listOf("这类", "这种", "这样的", "类", "题材", "方向")
        tails.forEach { s -> if (t.length > s.length && t.endsWith(s)) t = t.removeSuffix(s).trim() }
        return t.trim('。', '，', '！', '？', '；', ' ')
    }

    private fun haystacks(seed: TopicSeed): List<String> = listOf(seed.title, seed.scene, seed.track, seed.hook)

    /**
     * 禁区命中（优先级最高）。1 字词只认完全相等，2 字以上才做包含——
     * 「不写丧」这种单字禁区若按包含判，会把「丧气」误伤，也会把无关的「丧」字全打死。
     */
    fun forbiddenHit(archive: TasteArchive, seed: TopicSeed): TasteEntry? =
        archive.bySection(TasteSection.FORBIDDEN).firstOrNull { e ->
            val t = term(e.text)
            when {
                t.isEmpty() -> false
                t.length == 1 -> haystacks(seed).any { it == t }
                else -> haystacks(seed).any { it.contains(t) }
            }
        }

    /**
     * 题材偏命中：条目里的「类/题材/赛道」类词（如「民生类更吃香」⇒ 民生）出现在种子身上，
     * 或种子的场景词出现在条目里。**只做字面判据**——做不到语义理解，这是刻意的：
     * 判据要能跟用户解释「为什么这条排前面」。
     */
    fun themeHits(archive: TasteArchive, seed: TopicSeed): List<TasteEntry> =
        archive.bySection(TasteSection.THEME).filter { e ->
            val tag = categoryTag(e.text)
            when {
                tag != null && tag.length >= 2 -> haystacks(seed).any { it.contains(tag) }
                seed.scene.length >= 2 && e.text.contains(seed.scene) -> true
                else -> false
            }
        }

    /** 「民生类更吃香」⇒「民生」；「同志类克制」⇒「同志」；没有「XX类/题材/方向」⇒ null。 */
    fun categoryTag(text: String): String? {
        val m = Regex("(.+?)(?:类|题材|赛道|方向)").find(text) ?: return null
        val tag = m.groupValues[1].trim().trimStart('不', '别', '少', '多', '·', '、', '，')
        return tag.takeIf { it.length >= 2 }
    }

    // ── 候选批排序（禁区剔除 + 题材偏加权） ────────────────────────────────

    /**
     * 给一批候选套口味档案：禁区命中的**剔除**，题材偏命中的**提前**。
     *
     * 稳定排序（分相同保持池子原序）⇒ 同一份档案同一批候选永远同一顺序，判据可测。
     * @param affinity 种子 id → 0..1 的范例相似度（没有范例就传空表）
     */
    fun rankCandidates(
        batch: TopicSuggestBatch,
        archive: TasteArchive,
        affinity: Map<String, Double> = emptyMap(),
    ): TasteRanked {
        val dropped = mutableListOf<TasteDrop>()
        val scored = mutableListOf<Pair<TopicCandidate, Double>>()
        batch.candidates.forEach { c ->
            val hit = forbiddenHit(archive, c.seed)
            if (hit != null) {
                dropped += TasteDrop(c.seed.id, c.seed.title, hit)
            } else {
                val theme = themeHits(archive, c.seed).size * 2.0
                val sim = (affinity[c.seed.id] ?: 0.0) * 1.5
                scored += c to (theme + sim)
            }
        }
        val kept = scored.sortedByDescending { it.second }.map { it.first }
        return TasteRanked(
            batch.copy(candidates = kept, exhausted = batch.exhausted || kept.size < TopicSuggest.CANDIDATES),
            dropped,
        )
    }

    // ── 范例挑选 ──────────────────────────────────────────────────────────

    /** 截断（超长正文按字切，末尾加省略号；不切则在中间截断正文语义）。 */
    fun clip(text: String, limit: Int = BODY_LIMIT): String {
        val t = text.trim()
        return if (t.length <= limit) t else t.take(limit).trimEnd() + "…"
    }

    /**
     * 客户名脱敏（派工单 §2「范例含隐私（客户名/手机号）⇒ 注入前先脱敏」）。
     *
     * `SensitiveRedactor` 只管号码/证件/密码，**不管姓名**；范例里最常见的隐私恰恰是
     * 「客户王女士」「顾客李先生」这类称呼。这里只认**带身份前缀**或**姓＋称谓**两种形状，
     * 宁可少遮也不乱遮：`客户王女士` ⇒ `客户***`，`王女士` ⇒ `***`，`老板娘` 不被误伤。
     *
     * 纯文本变换、不 import 浏览器包（与 [injection] 的 `redact` 参数同一层）。
     */
    fun redactNames(text: String): String {
        if (text.isEmpty()) return text
        var out = CUSTOMER_HONORIFIC.replace(text) { m -> m.groupValues[1] + MASK }
        out = CUSTOMER_BARE.replace(out) { m -> m.groupValues[1] + MASK }
        out = NAME_HONORIFIC.replace(out, MASK)
        return out
    }

    /** 脱敏替换符（与 `SensitiveRedactor.MASK` 同值；这里写死避免跨包依赖）。 */
    const val MASK = "***"

    /** 带身份前缀＋称谓的客户名：`客户王女士` / `顾客李先生` / `联系人张总`。 */
    private val CUSTOMER_HONORIFIC = Regex(
        """(客户|顾客|联系人|委托人)[：:\s]*[\p{IsHan}]{1,3}(?:先生|女士|小姐|老师|经理|老板|总)""",
    )

    /** 带身份前缀、没有称谓的客户名：只在标点／空白／行尾前收（避免把后文的动词一起吃掉）。 */
    private val CUSTOMER_BARE = Regex(
        """(客户|顾客|联系人|委托人)[：:\s]*[\p{IsHan}]{2,3}(?=[\s，。！？；：、,.!?;]|$)""",
    )

    /** 姓＋称谓（`王女士` / `李先生` / `张老师`）；只要求左侧不贴汉字，右侧可接正文（`李女士来取货`）。 */
    private val NAME_HONORIFIC = Regex(
        """(?<![\p{IsHan}])[\p{IsHan}]{1,2}(?:先生|女士|小姐|老师|经理|总)""",
    )

    /** 一篇范例与当前题材的相似度（0..1，Jaccard；与 [MemoryRules.tokensOf] 同一套分词）。 */
    fun similarity(query: Set<String>, sample: TasteSample): Double {
        if (query.isEmpty()) return 0.0
        val st = MemoryRules.tokensOf(sample.title + "\n" + sample.body)
        if (st.isEmpty()) return 0.0
        val inter = query.intersect(st).size
        val union = query.union(st).size
        return if (union == 0) 0.0 else inter.toDouble() / union.toDouble()
    }

    /** 当前题材的查询词（题目＋场景＋角度＋写法档位）。 */
    fun queryTokens(seed: TopicSeed, style: TopicStyle?): Set<String> {
        val sb = StringBuilder(seed.title).append('\n').append(seed.scene).append('\n')
            .append(seed.track).append('\n').append(seed.hook)
        if (style != null) sb.append('\n').append(style.voice).append('\n').append(style.structure)
        return MemoryRules.tokensOf(sb.toString())
    }

    /**
     * 挑正 2–3 ＋ 负 1：正样本按相似度降序取前 [MAX_GOOD]；负样本取**最像的那一篇**
     * （同样的题材最容易踩同样的坑）。空库 ⇒ 空结果，不编造。
     */
    fun pick(seed: TopicSeed, style: TopicStyle?, samples: List<TasteSample>): Pair<List<TasteSample>, List<TasteSample>> {
        val q = queryTokens(seed, style)
        val goods = samples.filter { it.kind == SampleKind.GOOD }
            .sortedByDescending { similarity(q, it) }
            .take(MAX_GOOD)
        val bad = samples.filter { it.kind == SampleKind.BAD }
            .sortedByDescending { similarity(q, it) }
            .take(MAX_BAD)
        return goods to bad
    }

    /** 种子 → 正样本相似度（给 [rankCandidates] 的 affinity；同一份材料的第二种用法）。 */
    fun affinityOf(seed: TopicSeed, style: TopicStyle?, samples: List<TasteSample>): Double {
        val q = queryTokens(seed, style)
        return samples.filter { it.kind == SampleKind.GOOD }.maxOfOrNull { similarity(q, it) } ?: 0.0
    }

    // ── 注入拼装 ──────────────────────────────────────────────────────────

    /**
     * 拼注入块。**顺序即优先级**：禁区 ⊆ 最前（负例 5：禁区不许被其它规则盖住）；
     * 正样本在前、负样本在后且带一句原因（派工单 §2）。
     *
     * @param redact 脱敏函数（生产传 `SensitiveRedactor` 的 `redactText`；测试传恒等）
     */
    fun injection(
        archive: TasteArchive,
        seed: TopicSeed,
        style: TopicStyle?,
        samples: List<TasteSample>,
        redact: (String) -> String = { it },
    ): TasteInjection? {
        val (goods, bad) = pick(seed, style, samples)
        val hasRules = !archive.isEmpty
        if (!hasRules && goods.isEmpty() && bad.isEmpty()) return null

        val notes = mutableListOf<String>()
        if (goods.isEmpty()) notes += "范例库里还没有正样本，这次只按偏好规则写"
        if (goods.size == 1) notes += "正样本只有 1 篇，参考面偏窄"

        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("【口味档案 · 用户自己的记录，每条后面是证据，不许改写也不许忽略】")
        val forbid = archive.bySection(TasteSection.FORBIDDEN)
        if (forbid.isNotEmpty()) {
            sb.appendLine("禁区（最高优先级：命中就不写，照这个写会被退回）：")
            forbid.forEach { sb.appendLine("- ${it.text}") }
        }
        val theme = archive.bySection(TasteSection.THEME)
        if (theme.isNotEmpty()) {
            sb.appendLine("题材偏好（同等条件下优先这些）：")
            theme.forEach { sb.appendLine("- ${it.text}") }
        }
        val styleRows = archive.bySection(TasteSection.STYLE)
        if (styleRows.isNotEmpty()) {
            sb.appendLine("写法偏好（必须遵守，比长度/结构更优先）：")
            styleRows.forEach { sb.appendLine("- ${it.text}") }
        }

        if (goods.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("【范例 · 用户认可的写法，照这个手感写（不是抄内容）】")
            goods.forEachIndexed { i, g ->
                sb.appendLine("参考 ${i + 1}《${g.name}》：")
                sb.appendLine(redact(clip(g.body)))
            }
        }
        bad.firstOrNull()?.let { b ->
            sb.appendLine()
            sb.appendLine("【范例 · 用户否掉的样子，别学】")
            val why = b.reason?.takeIf { it.isNotBlank() } ?: "用户没有写原因"
            sb.appendLine("避开《${b.name}》（原因：${redact(why)}）：")
            sb.appendLine(redact(clip(b.body)))
        }
        sb.appendLine()
        sb.appendLine("以上偏好与范例来自用户自己的档案，不是模型猜测；与本次题目冲突时，以禁区为最高优先级。")

        return TasteInjection(
            block = sb.toString().trimEnd(),
            goods = goods.map { it.name },
            bad = bad.firstOrNull()?.name,
            notes = notes,
        )
    }

    /** 写作指令＝190 的原文 ＋ 口味/范例段（190 的指令一字不改，只在后面接）。 */
    fun writeInstruction(seed: TopicSeed, style: TopicStyle, injection: TasteInjection?): String {
        val base = TopicSuggest.writeInstruction(seed.title, style)
        return if (injection == null || injection.block.isBlank()) base else base + "\n" + injection.block
    }

    /** 卡面那一行：「本次参考：《a》《b》；避开：《c》」（没有就不显示）。 */
    fun cardNote(injection: TasteInjection?): String? {
        if (injection == null) return null
        val refs = injection.goods
        val bad = injection.bad
        if (refs.isEmpty() && bad == null) return null
        val left = if (refs.isEmpty()) "" else refs.joinToString("") { "《$it》" }
        return when {
            bad == null -> "本次参考：$left"
            refs.isEmpty() -> "避开：《$bad》"
            else -> "本次参考：$left；避开：《$bad》"
        }
    }

    /** 设置页「注入预览」用的一句话标题（不跑模型，只说明会喂什么）。 */
    fun previewHeadline(injection: TasteInjection?): String =
        when {
            injection == null -> "现在还没有可注入的偏好或范例（下一轮按 190 的默认写法出稿）"
            injection.goods.isEmpty() && injection.bad == null -> "本轮只注入偏好规则，没有范例"
            else -> cardNote(injection) ?: "本轮只注入偏好规则，没有范例"
        }

    /** 首次建档案时的模板（三段空标题＋用法说明；用户可手填，也可从设置页加）。 */
    fun template(stamp: String): String = buildString {
        appendLine("# 口味档案")
        appendLine()
        appendLine("<!-- 口味档案 v1 · $stamp · 每条必须带「证据：」；删掉的条目下一轮就不再注入 -->")
        appendLine("<!-- 来源三种：① 台账里的 ✔/火了/赚钱；② 你否决或让改的那次；③ 你口述。没有证据的条目一律不生效。 -->")
        appendLine()
        appendLine("## 题材偏")
        appendLine("<!-- 暂无 -->")
        appendLine()
        appendLine("## 写法偏")
        appendLine("<!-- 暂无 -->")
        appendLine()
        appendLine("## 禁区（优先级最高，命中直接不出候选）")
        appendLine("<!-- 暂无 -->")
    }
}
