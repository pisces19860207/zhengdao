// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十一批（2026-11-09）· **待办中心**（第一段：数据层）。
//
// 用户口径（2026-11-09 派工单原文）：
//   四态生命周期 `待做 →（模型标）已做 →（右相三态 PASS）已完成 →（用户点确认）自动删除该条`；
//   「删除只从看板移除，**账本保留一行**（谁建/谁做/谁验/何时删）」；
//   「未到『已完成』不许删；用户不确认就停在『已完成』」；
//   权责：「模型增改状态 · 右相判完成 · 用户只点最后一下 · 左相只管打扰」。
//
// 本文件只放**纯判据与纯数据**（零 IO、零时钟、零 Android、零 JSON 依赖）：
//   - 四态与合法迁移（[TodoRules.transitionRefusal] / [TodoRules.fold]）；
//   - 摘要上限 40 字（判据②：行里只有摘要，没有正文/来源——**在数据层就掐死**，
//     界面想显示正文也拿不到：本文件的行结构里根本没有正文字段）；
//   - 收起态那一行 `待办 N · 在做 M` 的唯一算法（[TodoRules.headerLine]）。
//
// 为什么把 40 字的闸放在写入侧而不是渲染侧：渲染侧截断最坏——模型以为写进去了，
// 界面上看到的是半句（与小黑板 BoardRules「拒写不静默截断」同一口径，542 的教训）。
//
// 第四态（"移除了"）**不进** [TodoState] 枚举：它不是一个"条目状态"，而是"条目还在不在看板上"。
// 把它塞进枚举会让 `state == COMPLETE` 与 `state == REMOVED` 打架（谁是真值？）。
// 这里的取法是：状态只有三个，移除由 [TodoItem.confirmedAtMs] > 0 表示，
// 且只有 [TodoOp.CONFIRM] 一个动作能写它——用户点的那一下。
package com.example.zhengdao.baopu.todo

/**
 * 看板上的三个状态（第四态"移除"见 [TodoItem.confirmedAtMs]）。
 *
 * @param wire 账本里落的值（**不许改**：改了等于把历史行读成另一个状态）
 * @param cn 界面/回执上的人话
 */
internal enum class TodoState(val wire: String, val cn: String) {
    /** 立起来了、模型还没标做完。 */
    TODO("todo", "待做"),

    /** 模型标了做完，右相还没判 PASS（或判的是 FAIL／UNVERIFIABLE）。 */
    MODEL_DONE("done", "已做"),

    /** 右相判 PASS。**只有**这一个状态能被人点确认删掉。 */
    COMPLETE("complete", "已完成"),
    ;

    companion object {
        fun ofWire(raw: String): TodoState? = entries.firstOrNull { it.wire == raw }
    }
}

/** 谁做的这笔（账本每行都带；「谁建/谁做/谁验/何时删」靠它分）。 */
internal enum class TodoActor(val wire: String, val cn: String) {
    MODEL("model", "模型"),
    RIGHT_MINISTER("right_minister", "右相"),
    USER("user", "用户"),
    ;

    companion object {
        fun ofWire(raw: String): TodoActor? = entries.firstOrNull { it.wire == raw }
    }
}

/** 账本动作（append-only 的四种行；没有第五种，也没有"改历史行"这回事）。 */
internal enum class TodoOp(val wire: String) {
    ADD("add"),
    DONE("done"),
    VERIFY("verify"),
    CONFIRM("confirm"),
    ;

    companion object {
        fun ofWire(raw: String): TodoOp? = entries.firstOrNull { it.wire == raw }
    }
}

/**
 * 账本的一行（`产物/.todo.jsonl` 的一行）。
 *
 * 一行只带它那件事要的字段（add 带 text/source，verify 带 verdict/reason，其余留空）——
 * 不给"万能行"开口子：万能行的下场是每条都填一半，读的人分不清哪半是这次写的。
 */
internal data class TodoRow(
    val op: TodoOp,
    val id: String,
    val who: TodoActor,
    val atMs: Long,
    val text: String = "",
    val source: String = "",
    val verdict: String = "",
    val reason: String = "",
)

/** 看板上的一条待办（由账本 replay 出来；**没有正文字段**，见文件头判据②）。 */
internal data class TodoItem(
    val id: String,
    val text: String,
    val sourceId: String,
    val createdAtMs: Long,
    val state: TodoState,
    /** 右相最近一次结论（"PASS"／"FAIL"／"UNVERIFIABLE"；空串＝还没验过）。 */
    val verdict: String = "",
    /** 右相给的理由（一行；FAIL/UNVERIFIABLE 必带，PASS 也留一句读数）。 */
    val verdictReason: String = "",
    val verifiedAtMs: Long = 0L,
    /** > 0 ＝ 用户已确认删过（看板上不再出现；账本那一行一条不少）。 */
    val confirmedAtMs: Long = 0L,
)

/** 收起态那一行要的两个数（[headerLine] 是它唯一的渲染口）。 */
internal data class TodoCounts(
    /** 待做。 */
    val todo: Int,
    /** 在做＝模型标了已做、还没到已完成（等右相 or 等人确认）。 */
    val doing: Int,
    /** 已完成、等人点确认。 */
    val complete: Int,
)

/** 待办中心的数据判据（纯函数；IO 在 [TodoStore]，验收在 [TodoVerify]）。 */
internal object TodoRules {

    /** 账本形状版本（改字段名/语义时一起升）。 */
    const val VERSION: Int = 1

    /** 摘要上限（判据②：≤40 字；一字不多）。 */
    const val MAX_SUMMARY_CHARS: Int = 40

    /** 右相结论的三个 wire（与 [com.example.zhengdao.baopu.verify.ChancellorVerdict] 同名）。 */
    const val VERDICT_PASS: String = "PASS"
    const val VERDICT_FAIL: String = "FAIL"
    const val VERDICT_UNVERIFIABLE: String = "UNVERIFIABLE"

    /** 理由一行的上限（账本与回执都只放一行，不塞整份报告）。 */
    const val MAX_REASON_CHARS: Int = 200

    /**
     * 字数（按**码点**数，不按 UTF-16 单元：一个 emoji 在 `length` 里算 2，
     * 按 `length` 判会把 39 个字的摘要当成 42 字拒掉）。
     */
    fun charCount(text: String): Int = text.codePointCount(0, text.length)

    /** 前 [max] 个字（按码点切，不劈开代理对；短于上限原样返回）。 */
    fun takeChars(text: String, max: Int): String {
        if (charCount(text) <= max) return text
        return text.substring(0, text.offsetByCodePoints(0, max))
    }

    /**
     * 摘要在写入侧做的整形：所有空白（含换行/制表）压成一个空格再 trim。
     *
     * 为什么必须压：这是"行里只有一句话"的结构保证——模型若把一条带换行的正文塞进来，
     * 不压就会在界面上变成两行，判据②当场破（而账本里还躺着一份多行文本）。
     */
    fun normalize(raw: String): String =
        raw.replace(Regex("\\s+"), " ").trim()

    /**
     * 能不能收这条待办（null ＝ 通过；非 null ＝ **拒因**，原话回灌给模型）。
     *
     * 拒因必须带"现在多少 / 上限多少"和"怎么办"——只说"超限"，模型下一轮会原样再写一遍
     * （小黑板 542 的实证）。
     */
    fun refusal(raw: String): String? {
        val text = normalize(raw)
        if (text.isEmpty()) {
            return "拒收：摘要不能为空。一句话说清「要做什么」（≤$MAX_SUMMARY_CHARS 字），再调一次。"
        }
        val n = charCount(text)
        if (n > MAX_SUMMARY_CHARS) {
            val over = takeChars(text, MAX_SUMMARY_CHARS)
            return "拒收：摘要 $n 字 > 上限 $MAX_SUMMARY_CHARS 字。待办只放**一句话**" +
                "（做什么就够，正文和来源不要抄进来）。前 $MAX_SUMMARY_CHARS 字是「$over」——" +
                "按这个长度重写一条，再调一次。"
        }
        return null
    }

    /** 还能不能标"已做"（已完成的不许再标；已删的不许动）。 */
    fun doneRefusal(item: TodoItem): String? = when {
        item.confirmedAtMs > 0L -> "这条已经确认删掉了（看板上没有它了）——别再标。"
        item.state == TodoState.COMPLETE -> "这条已经是「已完成」（右相判过 PASS），不用再标；等你点确认。"
        else -> null
    }

    /**
     * 能不能确认删除（**只由用户那一下触发**；模型与右相一律拒）。
     *
     * 两条硬边界都在这里，一个 if 都不许省：
     * ① 只有用户能删（模型不可自删——判据⑤）；② 未到「已完成」不许删（判据⑤前半句）。
     */
    fun confirmRefusal(item: TodoItem?, actor: TodoActor): String? {
        if (actor != TodoActor.USER) {
            return "拒：待办只能由**用户**点确认删——模型和右相都删不了（这是 541 的硬边界，" +
                "删的理由写不出「谁验过」）。"
        }
        if (item == null) return "没有这条待办（id 不对，或者已经删掉了）。"
        if (item.confirmedAtMs > 0L) return "这条已经确认删过了（看板上早就没有它）。"
        if (item.state != TodoState.COMPLETE) {
            return "还没到「已完成」，不能删（现在是「${item.state.cn}」）——右相判 PASS 之后才轮到你点确认。"
        }
        return null
    }

    /**
     * 账本 replay：把 append-only 的行折成"此刻看板上是什么"。
     *
     * 非法迁移**一律不生效**（不抛、不崩：账是留痕，坏行不该让整块看板读不出来）：
     * - `verify` 只在「已做」上生效——否则一条 verify 行就能把「待做」直接顶成「已完成」（绕过模型标记）；
     * - `done` 在「已完成」上不生效（完成了就是完成了）；
     * - `confirm` 只在「已完成」上生效（未到已完成不许删，历史里有这种行也不认）；
     * - 已确认删掉的条目，后续行一个字都不再改它（账本那几行照留，只是不再进看板）。
     */
    fun fold(rows: List<TodoRow>): List<TodoItem> {
        val items = LinkedHashMap<String, TodoItem>()
        for (row in rows) {
            val cur = items[row.id] ?: run {
                if (row.op != TodoOp.ADD || row.text.isBlank()) return@run null
                TodoItem(
                    id = row.id,
                    text = row.text,
                    sourceId = row.source,
                    createdAtMs = row.atMs,
                    state = TodoState.TODO,
                ).also { items[row.id] = it }
            }
            if (cur == null || cur.confirmedAtMs > 0L) continue
            items[row.id] = applied(cur, row)
        }
        return items.values.toList()
    }

    /** 单行落在单条上的效果（只走合法迁移；非法行原样返回）。 */
    private fun applied(cur: TodoItem, row: TodoRow): TodoItem = when (row.op) {
        TodoOp.ADD -> cur
        // 已完成就完成了：后来的 done 行不许把它打回「已做」（自测 `已完成之后的行不再改它`
        // 第一版就是这里少一条分支，当场红——COMPLETE 是单向终点，只有 confirm 能再动它）。
        TodoOp.DONE -> if (cur.state == TodoState.COMPLETE) cur else cur.copy(state = TodoState.MODEL_DONE)
        TodoOp.VERIFY -> if (cur.state == TodoState.MODEL_DONE) {
            cur.copy(
                verdict = row.verdict,
                verdictReason = row.reason,
                verifiedAtMs = row.atMs,
                state = if (row.verdict == VERDICT_PASS) TodoState.COMPLETE else TodoState.MODEL_DONE,
            )
        } else {
            cur
        }
        TodoOp.CONFIRM ->
            if (cur.state == TodoState.COMPLETE) cur.copy(confirmedAtMs = row.atMs) else cur
    }

    /** 看板上还留着的（已确认删掉的不算）。 */
    fun live(items: List<TodoItem>): List<TodoItem> = items.filter { it.confirmedAtMs == 0L }

    /** 收起态那两个数（口径见 [TodoCounts]）。 */
    fun counts(items: List<TodoItem>): TodoCounts {
        val shown = live(items)
        return TodoCounts(
            todo = shown.count { it.state == TodoState.TODO },
            doing = shown.count { it.state == TodoState.MODEL_DONE },
            complete = shown.count { it.state == TodoState.COMPLETE },
        )
    }

    /** 收起态那一行（判据①的字面形状；界面不许自己拼）。 */
    fun headerLine(counts: TodoCounts): String = "待办 ${counts.todo} · 在做 ${counts.doing}"

    /**
     * 行文本（判据②的结构保证）：**只有摘要**。
     *
     * 这里不去拼状态/来源/"来自哪个缺口"——那些是另一行的活（[statusLine]）。
     * 一行一件事，行文本就是那句话本身；≤40 字由 [refusal] 在写入侧掐死。
     */
    fun rowText(item: TodoItem): String = item.text

    /** 行右侧那句状态（人话＋右相结论一句；没有就不多写）。 */
    fun statusLine(item: TodoItem): String = buildString {
        append(item.state.cn)
        when {
            item.verdict == VERDICT_PASS -> append(" · 右相 PASS")
            item.verdict.isNotEmpty() -> append(" · 右相 ").append(item.verdict)
        }
    }

    /** 下一条的 id（`t<序号>`；序号取**全部历史行**里最大的 +1 ⇒ 删掉重立不复用旧 id）。 */
    fun nextId(rows: List<TodoRow>): String {
        val max = rows.map { it.id }
            .filter { it.startsWith("t") }
            .mapNotNull { it.removePrefix("t").toIntOrNull() }
            .maxOrNull() ?: 0
        return "t${max + 1}"
    }

    /** 理由压成一行（换行/长文不许进账本行与回执）。 */
    fun oneLine(raw: String): String {
        val one = normalize(raw)
        return if (charCount(one) <= MAX_REASON_CHARS) one else takeChars(one, MAX_REASON_CHARS) + "…"
    }
}
