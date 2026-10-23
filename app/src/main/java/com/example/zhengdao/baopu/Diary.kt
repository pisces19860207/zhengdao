// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.baopu

import com.example.zhengdao.util.TimeFormats

/**
 * 情景记忆（日记）层——数据模型、编解码与结构判据（147 阶段①，纯函数 JVM 直测）。
 *
 * 依据：《抱朴情景记忆设计方案-2026-10-22.md》v1.2（146 批，判据 1/2/3/11/16/21）＋
 * 《派工单-2026-10-22-第一百四十七批》红线七条。分工铁律（方案 §1）：
 * **日记只记「发生了什么」**（时序流水＋产物引用，自动记、可即忘）；
 * **结论走学习卡**（确认制、落 MemoryStore）——两层不许混，见 [DiaryRules]。
 *
 * 文件形态（与 MemoryStore 同哲学：**文件是真相，索引可重建**；
 * 目录＝`<工作区根>/记忆/日记/<yyyy-MM-dd>.md`，作用域随工作区——硬点 1）：
 * ```
 * ---
 * id: 42-20261022
 * date: 2026-10-22
 * session_id: 42
 * time_from: 1729580000000
 * time_to: 1729583600000
 * title: 工具 3 次 · 产物 2 个
 * evidence: observed
 * ok_steps: 3
 * failed_steps: 0
 * created_at: 1729583600000
 * ---
 *
 * 工具成功 3 次 · 失败 0 次 · 产物 2 个
 *
 * - 相对路径/a.md
 * - 相对路径/b/c.md
 * ```
 *
 * 三防（用户 18:10 拍板）在本文件的落点：
 * ①**证据标记**——[DiaryEvidence] 必填、缺省按最保守的 `INFERRED` 解析（缺字段不冒充实测）；
 * ②**产物可核对**——[DiaryEntry.artifacts] 全部为工作区相对路径（[DiaryRules.relativePath]
 * 拒绝对路径与越界 `..`），删前可核对（③阶段确认卡直接列这份清单）；
 * ③**硬删只删日记**——本层没有任何指向人设/安全文件的通路（负例判据 9 钉）。
 */
internal enum class DiaryEvidence { OBSERVED, CITED, INFERRED }

/**
 * 一条日记（**会话×日**粒度，upsert 键＝[DiaryRules.diaryId]）。
 *
 * [provenance 三元组]：`sessionId` ＋ `timeFromMs..timeToMs` 时间窗（本仓 run_log
 * 无 runId，轮次以时间窗定义——`stepsInWindow(fromMs, toMsExcl)` 既有口径），
 * 语义等价于「可回溯到真实消息与事件」；偏差已在 147 派工单 §偏差 2 记录。
 */
internal data class DiaryEntry(
    val id: String,
    val date: String,
    val sessionId: Long,
    val timeFromMs: Long,
    val timeToMs: Long,
    val title: String,
    val summary: String,
    val artifacts: List<String> = emptyList(),
    val evidence: DiaryEvidence = DiaryEvidence.INFERRED,
    val okSteps: Int = 0,        // 当日累计：成功工具步（同会话同日多轮 upsert 相加）
    val failedSteps: Int = 0,    // 当日累计：失败步（如实计，不粉饰）
    val createdAt: Long = 0L,
)

/** 编解码（[MemoryStore.EntryCodec] 同款纪律：容错但**不装**——关键字段缺 ⇒ null，调用方跳过并报出，不静默）。 */
internal object DiaryCodec {

    /** 渲染。`summary` 永远是正文第一行；产物以 `- ` 行跟在空行后（逗号安全，路径永不截断）。 */
    fun render(e: DiaryEntry): String = buildString {
        append("---\n")
        append("id: ").append(e.id).append('\n')
        append("date: ").append(e.date).append('\n')
        append("session_id: ").append(e.sessionId).append('\n')
        append("time_from: ").append(e.timeFromMs).append('\n')
        append("time_to: ").append(e.timeToMs).append('\n')
        append("title: ").append(e.title.replace('\n', ' ')).append('\n')
        append("evidence: ").append(e.evidence.name.lowercase()).append('\n')
        append("ok_steps: ").append(e.okSteps).append('\n')
        append("failed_steps: ").append(e.failedSteps).append('\n')
        append("created_at: ").append(e.createdAt).append('\n')
        append("---\n\n")
        append(e.summary.trim()).append('\n')
        if (e.artifacts.isNotEmpty()) {
            append('\n')
            e.artifacts.forEach { append("- ").append(it).append('\n') }
        }
    }

    /** 解析。缺 id/date/session_id/summary 之一 ⇒ null；evidence 缺/坏 ⇒ 最保守的 `INFERRED`。 */
    fun parse(text: String): DiaryEntry? {
        if (!text.startsWith("---\n")) return null
        val end = text.indexOf("\n---\n", startIndex = 4)
        if (end < 0) return null
        val head = text.substring(4, end)
        val body = text.substring(end + 5)
        val fields = LinkedHashMap<String, String>()
        for (line in head.split('\n')) {
            if (line.isBlank()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            fields[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        val id = fields["id"]?.takeIf { it.isNotEmpty() } ?: return null
        val date = fields["date"]?.takeIf { it.isNotEmpty() } ?: return null
        val sessionId = fields["session_id"]?.toLongOrNull() ?: return null
        // 正文：第一个非空行 = summary；其后每个 `- ` 行 = 一条产物引用。
        val bodyLines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val summary = bodyLines.firstOrNull() ?: return null
        val artifacts = bodyLines.drop(1)
            .filter { it.startsWith("- ") }
            .map { it.removePrefix("- ").trim() }
            .filter { it.isNotEmpty() }
        return DiaryEntry(
            id = id,
            date = date,
            sessionId = sessionId,
            timeFromMs = fields["time_from"]?.toLongOrNull() ?: 0L,
            timeToMs = fields["time_to"]?.toLongOrNull() ?: 0L,
            title = fields["title"].orEmpty(),
            summary = summary,
            artifacts = artifacts,
            evidence = enumOf<DiaryEvidence>(fields["evidence"]) ?: DiaryEvidence.INFERRED,
            // 老文件没有这两行 ⇒ 0（容错不装；计数账坏了按 0 重算，不拿坏值虚报）。
            okSteps = fields["ok_steps"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            failedSteps = fields["failed_steps"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            createdAt = fields["created_at"]?.toLongOrNull() ?: 0L,
        )
    }

    private inline fun <reified T : Enum<T>> enumOf(raw: String?): T? =
        raw?.takeIf { it.isNotEmpty() }?.let { name ->
            enumValues<T>().firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
}

/**
 * 结构判据与写入闸（全部纯函数；判据号对应方案 §8）。
 *
 * **为什么摘要是拼装的不是模型写的**（判据 1 的结构根据）：`summary`/`title` 全部由
 * 计数与路径**组合**而成（[composeSummary]），调用方传进来的只有数字与已验证的路径——
 * 模型自由文本在结构上进不了日记，结论只能走学习卡管线。
 */
internal object DiaryRules {

    /** 敏感样式（判据 21）：命中 ⇒ 整条不写（「脱敏失败宁可整条不写」，方案 §3）。 */
    private val SENSITIVE = listOf(
        Regex("sk-[A-Za-z0-9]{16,}"),
        Regex("(?i)api[_-]?key\\s*[=:]"),
        Regex("(?i)token\\s*[=:]\\s*\\S"),
        Regex("(?i)password\\s*[=:]\\s*\\S"),
        Regex("Bearer [A-Za-z0-9._-]{8,}"),
        Regex("(?i)authorization\\s*[:=]"),
    )

    /** 有没有产出（判据 3，**结构化判据**）：成功工具步 > 0 或 新增产物 > 0，两者皆无 ⇒ 不记。 */
    internal fun hasActivity(okSteps: Int, artifacts: Int): Boolean = okSteps > 0 || artifacts > 0

    /** 标题（结构化拼装）。 */
    internal fun composeTitle(okSteps: Int, artifacts: Int): String =
        "工具 $okSteps 次 · 产物 $artifacts 个"

    /** 摘要（结构化拼装；失败如实计，不粉饰）。 */
    internal fun composeSummary(okSteps: Int, failedSteps: Int, artifacts: Int): String =
        "工具成功 $okSteps 次 · 失败 $failedSteps 次 · 产物 $artifacts 个"

    /**
     * 工作区相对路径守卫（判据 16）：拒绝对路径（`/`、`\\`、盘符）与越界 `..`；
     * 合格返回归一后的相对路径，不合格返回 null（调用方丢弃并计数，不静默吞）。
     */
    internal fun relativePath(raw: String): String? {
        val p = raw.trim().replace('\\', '/')
        if (p.isEmpty()) return null
        if (p.startsWith('/') || p.startsWith("~")) return null
        if (Regex("^[A-Za-z]:").containsMatchIn(p)) return null
        if (p.split('/').any { it == ".." }) return null
        return p
    }

    /** 产物清单清洗：逐条过相对路径守卫 → 去重保序（调用方把丢弃数记进运行日志）。 */
    internal fun sanitizeArtifacts(raws: List<String>): List<String> =
        raws.mapNotNull(::relativePath).distinct()

    /** 敏感样式命中（判据 21）——`summary` 与产物路径**合在一起**查（路径也能夹带私货）。 */
    internal fun containsSensitive(summary: String, artifacts: List<String>): Boolean =
        SENSITIVE.any { it.containsMatchIn(summary) || artifacts.any { p -> it.containsMatchIn(p) } }

    /** 条目 id：`<sessionId>-<yyyyMMdd>`（会话×日 upsert 键）。 */
    internal fun diaryId(sessionId: Long, date: String): String =
        "$sessionId-${date.replace("-", "")}"

    /** 条目日期（`TimeFormats.dayStamp` 唯一真源；id 里的紧凑形由它派生，不另设 formatter）。 */
    internal fun dateOf(epochMs: Long): String = TimeFormats.dayStamp(epochMs)

    /**
     * **修正闸**（157 补 147 缺口：原来只有追加/浓缩/归档，没有「修正已有条目」）。
     *
     * provenance 六字段**一个字节不许改**——`id`/`date`/`sessionId`/`timeFromMs`/
     * `timeToMs`/`createdAt`（「可回溯到真实消息与事件」就在这六个里；时间窗还是
     * run_log 轮次的定义）。可改的只有 `title`/`summary`/`artifacts`/`evidence`/
     * `okSteps`/`failedSteps`（记错了数、挂错产物路径才改这些）。
     *
     * 脱敏闸同样管修正（[containsSensitive]）——修正不是夹带敏感样式的后门。
     *
     * @param existing 盘上的原条目（不存在由调用方先拒，本函数不见 null）
     * @param patch 修正后的条目
     * @return null = 放行；非 null = 拒绝理由（调用方原样记账/回灌）
     */
    internal fun amendGuard(existing: DiaryEntry, patch: DiaryEntry): String? {
        if (patch.id != existing.id) {
            return "id 不许改（${existing.id} → ${patch.id}）：provenance 字段不可变（157）"
        }
        if (patch.date != existing.date) {
            return "date 不许改（${existing.date} → ${patch.date}）：provenance 字段不可变（157）"
        }
        if (patch.sessionId != existing.sessionId) {
            return "sessionId 不许改（${existing.sessionId} → ${patch.sessionId}）：provenance 字段不可变（157）"
        }
        if (patch.timeFromMs != existing.timeFromMs || patch.timeToMs != existing.timeToMs) {
            return "时间窗不许改（${existing.timeFromMs}..${existing.timeToMs} → " +
                "${patch.timeFromMs}..${patch.timeToMs}）：provenance 字段不可变（157）"
        }
        if (patch.createdAt != existing.createdAt) {
            return "createdAt 不许改（${existing.createdAt} → ${patch.createdAt}）：provenance 字段不可变（157）"
        }
        if (containsSensitive(patch.summary, patch.artifacts)) {
            return "脱敏闸命中：修正也不许带敏感样式（判据 21）"
        }
        return null
    }
}
