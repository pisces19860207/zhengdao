// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百二十七批 §0.6：审计的**唯一**回灌——聚合而成的一句事实。
//
// 账本（决策记录/抽检结论）只给人看/回滚；回灌给模型的只能是这一句：
//   「这类写入你近 7 天拒过 3 次」
//
// 硬边界（逐条都有单测）：
//   ① 同一类动作＝同 Impact.Kind ＋ 同域；近 7 天被拒（回滚行）或判错（抽检 wrong）≥3 次；
//   ② 一行 ≤ [MAX_CHARS] 字、无换行；
//   ③ **不列具体条目、不带时间/证据 id、不把台账原文塞进上下文**——句子只含
//      动作类别与次数（域只参与匹配，不打印）；
//   ④ 零 Android/IO：账本由调用方读好传进来，本对象只做纯计算，JVM 直测。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.ToolCall

internal object AuditHint {

    /** 回看窗口（派工单 §0.6：近 7 天）。 */
    const val WINDOW_MS = 7L * 24 * 60 * 60 * 1000

    /** 达到几次才值得说一句（< 3 次不打扰）。 */
    const val MIN_EVENTS = 3

    /** 一句上限（与 ImpactInject 一致：≤40 字）。 */
    const val MAX_CHARS = 40

    /** 一次「被拒/判错」信号（域只参与匹配，不进入句子）。 */
    data class Event(val kind: Impact.Kind, val domain: String, val atMs: Long)

    /** 一个动作类别（Kind＋域）。 */
    data class ActionClass(val kind: Impact.Kind, val domain: String)

    /**
     * 当前这次调用该不该带一句聚合提示。
     *
     * @param records 决策记录（[DecisionLedger.read] 的结果；回滚行＋原记录都要在）
     * @param reviews 抽检结论（[DecisionReview.read] 的结果）
     * @return 那句 ≤40 字的事实；null = 不到 3 次/不同类/窗口外
     */
    fun lineFor(
        call: ToolCall,
        impact: Impact,
        records: List<DecisionRecord>,
        reviews: List<DecisionReview.Entry>,
        nowMs: Long,
    ): String? {
        val current = classOf(call, impact.kind) ?: return null
        return line(current.kind, current.domain, events(records, reviews), nowMs)
    }

    /**
     * 纯计数版（单测直接喂事件）：同 kind＋同 domain、窗口内 ≥ [MIN_EVENTS] ⇒ 一句话。
     */
    fun line(kind: Impact.Kind, domain: String, events: List<Event>, nowMs: Long): String? {
        val label = labelOf(kind) ?: return null
        val since = nowMs - WINDOW_MS
        val count = events.count { it.kind == kind && it.domain == domain && it.atMs in since..nowMs }
        if (count < MIN_EVENTS) return null
        return fit("这类${label}你近 7 天拒过 $count 次")
    }

    /**
     * 把两本账归一成「被拒/判错」信号。
     *
     * 被拒＝回滚行（用户把做过的动作撤了，这是目前账本里唯一持久化的"拒绝"证据；
     * 确认卡上的"拒绝"本身不落账——那是 208 账本范围外的事，本批不新增账）。
     * 判错＝抽检结论里 verdict=wrong 的**当前**结论（后写覆盖前写）。
     * 同一条记录既被回滚又被判错 ⇒ 只算一次（取较晚的时刻），不重复计数。
     */
    fun events(records: List<DecisionRecord>, reviews: List<DecisionReview.Entry>): List<Event> {
        val byId = records.associateBy { it.id }
        val signals = LinkedHashMap<String, Long>()
        records.asSequence()
            .filter { it.kind == DecisionKind.ROLLBACK_OF }
            .forEach { rb ->
                val id = rb.rollbackOf ?: return@forEach
                signals.merge(id, rb.atMs) { a, b -> maxOf(a, b) }
            }
        DecisionReview.latestPerRecord(reviews).values.asSequence()
            .filter { it.verdict == DecisionRecord.VERDICT_WRONG }
            .forEach { rv -> signals.merge(rv.recordId, rv.atMs) { a, b -> maxOf(a, b) } }
        return signals.mapNotNull { (id, atMs) ->
            val record = byId[id] ?: return@mapNotNull null
            val cls = recordClassOf(record) ?: return@mapNotNull null
            Event(cls.kind, cls.domain, atMs)
        }
    }

    // ── 映射（动作 → 类别；域只用于"同类"匹配）──────────────────────────

    /** 文件类工具才按路径**父目录**分域；其余工具整类一个域（选择器等太长，不拿来当域）。 */
    private val FILE_TOOLS = setOf(
        "write_file", "edit_file", "delete_path", "move_path",
        "copy_path", "bulk_move", "bulk_copy", "organize",
    )

    private fun classOf(call: ToolCall, kind: Impact.Kind): ActionClass? {
        if (kind == Impact.Kind.NONE) return null
        val target = when (call.name) {
            "write_file", "edit_file", "delete_path" -> call.arguments.optString("path")
            "move_path" -> call.arguments.optString("from")
            "copy_path" -> call.arguments.optString("from")
            "bulk_move", "bulk_copy", "organize" -> call.arguments.optString("dir")
            else -> ""
        }
        return ActionClass(kind, domainOf(call.name, target))
    }

    private fun recordClassOf(r: DecisionRecord): ActionClass? = when (r.kind) {
        DecisionKind.AUTO_ACT -> {
            val body = r.evidence.removePrefix("tool:")
            val tool = body.substringBefore(':')
            val target = body.substringAfter(':', "")
            kindOf(tool)?.let { ActionClass(it, domainOf(tool, target)) }
        }
        DecisionKind.ARTIFACT_DELETE -> ActionClass(Impact.Kind.DELETE, "产物")
        DecisionKind.DIARY_MAINTAIN, DecisionKind.DIARY_PURGE ->
            ActionClass(Impact.Kind.DIARY_MAINTAIN, "日记")
        DecisionKind.SKILL_PROMOTE, DecisionKind.SKILL_AUTO_WRITE,
        DecisionKind.SKILL_RETIRE, DecisionKind.SKILL_DRAFT_RETIRE ->
            ActionClass(Impact.Kind.SKILL_GOVERN, "技能")
        else -> null
    }

    private fun kindOf(tool: String): Impact.Kind? = when (tool) {
        "write_file", "edit_file" -> Impact.Kind.WRITE
        "delete_path" -> Impact.Kind.DELETE
        "move_path" -> Impact.Kind.MOVE
        "copy_path", "bulk_copy" -> Impact.Kind.WRITE
        "bulk_move", "organize" -> Impact.Kind.MOVE
        "browser_click" -> Impact.Kind.CLICK
        "schedule_task" -> Impact.Kind.SCHEDULE
        else -> null
    }

    /**
     * 域：文件类取**父目录名**；目标缺失/非文件类 ⇒ 工具名本身。
     *
     * 取父目录而不是首段：账本里的 target 是工具参数原文（相对路径 `资料库/a.md`
     * 与绝对路径 `/storage/…/资料库/a.md` 都会出现），按首段取前者得 `资料库`、
     * 后者得 `storage`——同一个目录被拆成两个域，聚合提示就永远攒不够 3 次。
     */
    private fun domainOf(tool: String, target: String): String =
        if (tool in FILE_TOOLS && target.isNotBlank()) {
            val parts = target.replace('\\', '/').split('/').filter { it.isNotBlank() }
            parts.dropLast(1).lastOrNull() ?: tool
        } else {
            tool
        }

    private fun labelOf(kind: Impact.Kind): String? = when (kind) {
        Impact.Kind.WRITE -> "写入"
        Impact.Kind.DELETE -> "删除"
        Impact.Kind.MOVE -> "移动"
        Impact.Kind.CLICK -> "提交"
        Impact.Kind.SCHEDULE -> "定时任务"
        Impact.Kind.SKILL_REPLAY -> "回放"
        Impact.Kind.SKILL_GOVERN -> "技能治理"
        Impact.Kind.DIARY_MAINTAIN -> "日记整理"
        Impact.Kind.NONE -> null
    }

    /** 单行 ＋ ≤ [MAX_CHARS]（模板最坏也就二十来个字，这里只是兜底）。 */
    private fun fit(raw: String): String {
        val one = raw.replace(Regex("""\s+"""), " ").trim()
        return if (one.length <= MAX_CHARS) one else one.take(MAX_CHARS - 1) + "…"
    }
}
