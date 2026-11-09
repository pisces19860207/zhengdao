// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十四批（2026-11-09）· **项目账本**（第一段：数据层）。
//
// 用户口径（2026-11-09 派工单原文）：
//   每个工作区一本任务账（做了啥/在做啥/没做啥/冻结啥）；模型维护、右相判完成、左相判提醒、他只看；
//   五态「在飞／待验收／已收／冻结／撤」；**append-only**；**无 sourceMsgId 拒建**（承接"不准编造"）；
//   「已收」**不可退回**「在飞」；归档＝默认视图隐藏（账不删）。
//
// 本文件只放**纯判据与纯数据**（零 IO、零时钟、零 Android、零 JSON 依赖）：
//   - 五态与合法迁移（[LedgerRules.allows] / [LedgerRules.transitionRefusal] / [LedgerRules.fold]）；
//   - 标题上限 40 字 ＋ **sourceMsgId 必填**（判据①：嘴上的事不许进账）；
//   - 记录态那一行 `在飞 N · 待验收 M · 已收 K · 冻结 J · 撤 X` 的唯一算法（[LedgerRules.headerLine]）。
//
// 为什么「已收」不是随便写的一个状态值（判据②的结构保证）：
//   `fold` 里把状态改成「已收」**只认一条路**——`kind=done` 且 `verdict=PASS` 那一行。
//   所以哪怕有人手改账文件写一行 `add…status=accepted`，重放出来它仍然是在飞：
//   「右相 PASS 才可已收」不是靠调用方自觉，是靠**重放规则**把别的路全堵死。
//
// 为什么「撤」也做成终点：撤＝这件事不做了（账留着当"没做啥"的证据）。允许撤→在飞会让
//   「一件事到底做过没有」变成看最后一次点哪个按钮，账本就失去留痕的意义了。
//
// 40 字与 sourceMsgId 的闸都放在**写入侧**（与 541 同一口径）：渲染侧截断最坏——模型以为写进去了，
// 界面上看到的是半句。这里直接拒，拒因带"现在多少 / 上限多少 / 怎么改"。
package com.example.zhengdao.baopu.ledger

/**
 * 账上的五个状态（派工单点名；`wire` 是落进账文件的值，**不许改**——改了等于把历史行读成另一个状态）。
 *
 * @param wire 账文件里落的值
 * @param cn 界面/回执上的人话
 */
internal enum class LedgerState(val wire: String, val cn: String) {
    /** 立起来了、还在做。 */
    FLYING("flying", "在飞"),

    /** 做完了、等验收（右相判不了或判 FAIL 时停在这里）。 */
    PENDING("pending", "待验收"),

    /** 右相判过 PASS。**终点**：不许退回「在飞」（判据④）。 */
    ACCEPTED("accepted", "已收"),

    /** 暂时不做（不是撤：这件事还活着，解冻能回「在飞」）。 */
    FROZEN("frozen", "冻结"),

    /** 不做了。**终点**：账留着当"没做啥"的证据。 */
    WITHDRAWN("withdrawn", "撤"),
    ;

    companion object {
        fun ofWire(raw: String): LedgerState? = entries.firstOrNull { it.wire == raw }

        /**
         * 模型给 wire（`flying`）或给人话（`在飞`）都收——两种写法都见过，
         * 只认一种会把"标了个中文状态"变成一次无解的重试。别的值一律 null（调用方点名报错）。
         */
        fun ofAny(raw: String): LedgerState? {
            val t = raw.trim()
            if (t.isEmpty()) return null
            return entries.firstOrNull { it.wire == t || it.cn == t }
        }
    }
}

/**
 * 账本行的五种动作（append-only；没有"改历史行"这回事）。
 *
 * `freeze` 与 `status` 分开记：冻结是"暂停"这一个动作，日后要按动作统计停留时长时，
 * 只在 status 行里看值是分不出"改成冻结"还是"从冻结改回在飞"的。
 */
internal enum class LedgerKind(val wire: String) {
    ADD("add"),
    STATUS("status"),
    DONE("done"),
    FREEZE("freeze"),
    ARCHIVE("archive"),
    ;

    companion object {
        fun ofWire(raw: String): LedgerKind? = entries.firstOrNull { it.wire == raw }
    }
}

/** 谁动的手（归档只有用户能点；验收只有右相能写）。 */
internal enum class LedgerActor(val wire: String) {
    MODEL("model"),
    RIGHT_MINISTER("right_minister"),
    USER("user"),
    ;

    companion object {
        fun ofWire(raw: String): LedgerActor? = entries.firstOrNull { it.wire == raw }
    }
}

/**
 * 账本的一行（`<工作区>/.zd-ledger.jsonl` 的一行）。
 *
 * 派工单点名的六个键：`id / at / kind / title / status / sourceMsgId`；
 * 另外两个是可选的取证键（`verdict` / `reason`，只有 `done` 行写）——
 * 少了它们「谁验的、判的什么」就只剩一个状态字，出事时查不出原因。
 */
internal data class LedgerRow(
    val kind: LedgerKind,
    val id: String,
    val atMs: Long,
    val title: String = "",
    val status: String = "",
    val sourceMsgId: String = "",
    val verdict: String = "",
    val reason: String = "",
)

/** 账上的一件事（由账文件 replay 出来；**没有正文**——只有一句话标题）。 */
internal data class LedgerItem(
    val id: String,
    val title: String,
    /** 这件事从哪来（用户那句话/缺口编号的 id）。**必填**（判据①）。 */
    val sourceMsgId: String,
    val createdAtMs: Long,
    val status: LedgerState,
    /** 右相最近一次结论（"PASS"／"FAIL"／"UNVERIFIABLE"；空串＝还没验过）。 */
    val verdict: String = "",
    val reason: String = "",
    val verifiedAtMs: Long = 0L,
    /** > 0 ＝ 用户确认归档过（默认视图里不出现；账本那一行一条不少）。 */
    val archivedAtMs: Long = 0L,
)

/** 记录态那一行要的几个数（[LedgerRules.headerLine] 是它唯一的渲染口）。 */
internal data class LedgerCounts(
    val flying: Int,
    val pending: Int,
    val accepted: Int,
    val frozen: Int,
    val withdrawn: Int,
    /** 已归档的（默认视图看不见，但账里在）。 */
    val archived: Int,
)

/** 项目账本的数据判据（纯函数；IO 在 [LedgerStore]，验收在 [LedgerVerify]）。 */
internal object LedgerRules {

    /** 账文件形状版本（改字段名/语义时一起升）。 */
    const val VERSION: Int = 1

    /** 标题上限（≤40 字；一字不多）。 */
    const val MAX_TITLE_CHARS: Int = 40

    /** `sourceMsgId` 上限：它是一张凭证号，不是一段话（超了多半是把正文塞进来了）。 */
    const val MAX_SOURCE_CHARS: Int = 64

    /** 理由一行的上限（账本与回执都只放一行，不塞整份报告）。 */
    const val MAX_REASON_CHARS: Int = 200

    /** 右相结论的三个 wire（与 `ChancellorVerdict` 同名）。 */
    const val VERDICT_PASS: String = "PASS"
    const val VERDICT_UNVERIFIABLE: String = "UNVERIFIABLE"

    /** 字数按**码点**数（一个 emoji 在 `length` 里算 2，按 `length` 判会把 39 字当成 42 字拒掉）。 */
    fun charCount(text: String): Int = text.codePointCount(0, text.length)

    /** 前 [max] 个字（按码点切，不劈开代理对；短于上限原样返回）。 */
    fun takeChars(text: String, max: Int): String {
        if (charCount(text) <= max) return text
        return text.substring(0, text.offsetByCodePoints(0, max))
    }

    /** 所有空白（含换行/制表）压成一个空格再 trim——一行就是一行。 */
    fun normalize(raw: String): String = raw.replace(Regex("\\s+"), " ").trim()

    /** 标题能不能收（null ＝ 通过；非 null ＝ 拒因，原话回灌给模型）。 */
    fun titleRefusal(raw: String): String? {
        val title = normalize(raw)
        if (title.isEmpty()) {
            return "拒收：标题不能为空。一句话说清「这件事是什么」（≤$MAX_TITLE_CHARS 字），再调一次。"
        }
        val n = charCount(title)
        if (n > MAX_TITLE_CHARS) {
            return "拒收：标题 $n 字 > 上限 $MAX_TITLE_CHARS 字。账上一条只放**一句话**" +
                "（做什么就够，正文/链接/清单不要抄进来）。前 $MAX_TITLE_CHARS 字是「" +
                takeChars(title, MAX_TITLE_CHARS) + "」——按这个长度重写一条再调一次。"
        }
        return null
    }

    /**
     * `sourceMsgId` 能不能收（判据①：**无 sourceMsgId 拒建**）。
     *
     * 为什么这一条是硬的：账本的价值全在"这件事从哪来"。没有来源的条目，
     * 事后既查不出是谁说的、也判不了该不该做——那正是「不准编造」要挡的东西。
     */
    fun sourceRefusal(raw: String): String? {
        val src = normalize(raw)
        if (src.isEmpty()) {
            return "拒建：没有 sourceMsgId。账上每一条都必须带上「这件事从哪来」" +
                "（用户那句话的编号／缺口编号／提案 id）——**没有来源的条目一个字都不许进账**。" +
                "先拿到那个 id，再调一次。"
        }
        val n = charCount(src)
        if (n > MAX_SOURCE_CHARS) {
            return "拒建：sourceMsgId $n 字 > 上限 $MAX_SOURCE_CHARS 字。这里只放一个**凭证号**" +
                "（用户那句话的编号/缺口编号），不要把正文塞进这个字段。"
        }
        return null
    }

    /** 立一条之前的两道闸（先标题、后来源：模型一次只改一个错，回灌才管用）。 */
    fun addRefusal(rawTitle: String, rawSource: String): String? =
        titleRefusal(rawTitle) ?: sourceRefusal(rawSource)

    /**
     * 五态的合法迁移（写入侧与重放**共用这一张表**，两处不许各写一套）。
     *
     * 已收与撤是终点：`ACCEPTED` / `WITHDRAWN` 出去的路是空集——判据④就钉在这里。
     * 冻结只回「在飞」（解冻）或「撤」：冻结的东西不能直接去待验收（它压根没在做了）。
     */
    fun allows(from: LedgerState, to: LedgerState): Boolean = when (from) {
        LedgerState.FLYING ->
            to == LedgerState.PENDING || to == LedgerState.FROZEN ||
                to == LedgerState.WITHDRAWN || to == LedgerState.ACCEPTED
        LedgerState.PENDING ->
            to == LedgerState.FLYING || to == LedgerState.FROZEN ||
                to == LedgerState.WITHDRAWN || to == LedgerState.ACCEPTED
        LedgerState.FROZEN -> to == LedgerState.FLYING || to == LedgerState.WITHDRAWN
        LedgerState.ACCEPTED, LedgerState.WITHDRAWN -> false
    }

    /** 从一个状态出发能去的目标（拒因里点名"现在能去哪"，比只说"不行"少一轮猜）。 */
    fun targetsFrom(from: LedgerState): List<LedgerState> =
        LedgerState.entries.filter { allows(from, it) }

    /** 能不能把这条改成 [target]（null ＝ 放行；非 null ＝ 拒因）。 */
    fun transitionRefusal(item: LedgerItem?, target: LedgerState): String? {
        if (item == null) {
            return "没有这条（id 不对，或者从来没立过）：先用 ledger_list 看一眼，别自己编 id。"
        }
        if (item.archivedAtMs > 0L) {
            return "这条已归档（默认视图里看不见了，账本那一行还在）——归档之后不再改状态。"
        }
        if (item.status == target) return "它现在就是「${target.cn}」，不用再标一次。"
        if (item.status == LedgerState.ACCEPTED) {
            return "「已收」是终点：不能退回「${target.cn}」（判据④：已收的东西不许回在飞）。" +
                "真要重做，另立一条新的。"
        }
        if (item.status == LedgerState.WITHDRAWN) {
            return "这条已经「撤」了（撤是终点，账留着当证据）。要重做就新立一条。"
        }
        if (!allows(item.status, target)) {
            val can = targetsFrom(item.status).joinToString("、") { it.cn }
            return "「${item.status.cn}」不能直接到「${target.cn}」——现在能去的是：$can。"
        }
        return null
    }

    /**
     * 账文件 replay：把 append-only 的行折成"此刻账上是什么"。
     *
     * 非法迁移**一律不生效**（不抛、不崩：账是留痕，坏行不该让整本账读不出来）：
     * - `status/freeze/done` 行只按 [allows] 那张表生效；
     * - 改成「已收」**只认** `kind=done` 且 `verdict=PASS` 那一行（判据②的结构保证）；
     * - 已归档的条目，后续行一个字都不再改它（账本那几行照留，只是不再进默认视图）。
     */
    fun fold(rows: List<LedgerRow>): List<LedgerItem> {
        val items = LinkedHashMap<String, LedgerItem>()
        for (row in rows) {
            val cur = items[row.id] ?: opened(row)?.also { items[row.id] = it } ?: continue
            items[row.id] = applied(cur, row)
        }
        return items.values.toList()
    }

    /** `add` 行开一条；不是 add 或标题为空 ⇒ null（后面的行没有可落的条目）。 */
    private fun opened(row: LedgerRow): LedgerItem? {
        if (row.kind != LedgerKind.ADD || row.title.isBlank()) return null
        return LedgerItem(
            id = row.id,
            title = row.title,
            sourceMsgId = row.sourceMsgId,
            createdAtMs = row.atMs,
            // 出生即「在飞」：`add` 行里的 status 只是留痕，**不参与判定**——
            // 否则手写一行 `add…status=accepted` 就能凭空造出一条「已收」（判据②的洞）。
            status = LedgerState.FLYING,
        )
    }

    /** 单行落在单条上的效果（只走合法迁移；非法行原样返回）。 */
    private fun applied(cur: LedgerItem, row: LedgerRow): LedgerItem = when (row.kind) {
        LedgerKind.ADD -> cur
        LedgerKind.ARCHIVE -> if (cur.archivedAtMs > 0L) cur else cur.copy(archivedAtMs = row.atMs)
        LedgerKind.STATUS, LedgerKind.FREEZE, LedgerKind.DONE -> moved(cur, row)
    }

    /** status/freeze/done 三行的共同落点（判据②的那条硬路只在这里）。 */
    private fun moved(cur: LedgerItem, row: LedgerRow): LedgerItem {
        val target = LedgerState.ofWire(row.status) ?: return cur
        if (cur.archivedAtMs > 0L || !allows(cur.status, target)) return cur
        if (target == LedgerState.ACCEPTED && !(row.kind == LedgerKind.DONE && row.verdict == VERDICT_PASS)) {
            return cur
        }
        return cur.copy(
            status = target,
            verdict = row.verdict.ifEmpty { cur.verdict },
            reason = row.reason.ifEmpty { cur.reason },
            verifiedAtMs = if (row.verdict.isEmpty()) cur.verifiedAtMs else row.atMs,
        )
    }

    /** 默认视图里还露面的（归档的不算）。 */
    fun live(items: List<LedgerItem>): List<LedgerItem> = items.filter { it.archivedAtMs == 0L }

    /** 已归档的（「账不删」的证据：它们还在，只是默认不露）。 */
    fun archived(items: List<LedgerItem>): List<LedgerItem> = items.filter { it.archivedAtMs > 0L }

    /** 记录态那几个数（口径见 [LedgerCounts]）。 */
    fun counts(items: List<LedgerItem>): LedgerCounts {
        val shown = live(items)
        return LedgerCounts(
            flying = shown.count { it.status == LedgerState.FLYING },
            pending = shown.count { it.status == LedgerState.PENDING },
            accepted = shown.count { it.status == LedgerState.ACCEPTED },
            frozen = shown.count { it.status == LedgerState.FROZEN },
            withdrawn = shown.count { it.status == LedgerState.WITHDRAWN },
            archived = items.size - shown.size,
        )
    }

    /** 记录态那一行（判据里的字面形状；界面与回执都不许自己拼）。 */
    fun headerLine(counts: LedgerCounts): String =
        "在飞 ${counts.flying} · 待验收 ${counts.pending} · 已收 ${counts.accepted} · " +
            "冻结 ${counts.frozen} · 撤 ${counts.withdrawn}"

    /** 行文本：**只有那句话**（状态在 [statusLine] 里，别混进来）。 */
    fun rowText(item: LedgerItem): String = item.title

    /** 行右侧那句状态（人话＋右相结论一句＋归档标记；没有就不多写）。 */
    fun statusLine(item: LedgerItem): String = buildString {
        append(item.status.cn)
        when {
            item.verdict == VERDICT_PASS -> append(" · 右相 PASS")
            item.verdict.isNotEmpty() -> append(" · 右相 ").append(item.verdict)
        }
        if (item.archivedAtMs > 0L) append(" · 已归档")
    }

    /** 下一条的 id（`L<序号>`；序号取**全部历史行**里最大的 +1 ⇒ 归档后重立不复用旧 id）。 */
    fun nextId(rows: List<LedgerRow>): String {
        val max = rows.map { it.id }
            .filter { it.startsWith("L") }
            .mapNotNull { it.removePrefix("L").toIntOrNull() }
            .maxOrNull() ?: 0
        return "L${max + 1}"
    }

    /** 理由压成一行（换行/长文不许进账行与回执）。 */
    fun oneLine(raw: String): String {
        val one = normalize(raw)
        return if (charCount(one) <= MAX_REASON_CHARS) one else takeChars(one, MAX_REASON_CHARS) + "…"
    }
}
