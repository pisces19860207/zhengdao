// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-26-第二百二十批-后果预览 §3（注入限流）＋ §2.5（无卡只记账、可关）。
//
// 本文件放四个「外围」件：注入口（限流）、事后账（决策中心 208 读它）、
// 一次性信箱（引擎消费）、开关（默认开）、耗时读数（p50/p95）。
// 卡面文案在 `ImpactLine`；这里一句中文都不拼。
package com.example.zhengdao.baopu

import android.content.Context
import androidx.core.content.edit
import com.example.zhengdao.baopu.agent.ToolCall
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * 给模型的注入（**限流，别吵**）。
 *
 * 规则（派工单 §3.2，逐条落到代码）：
 * 1. **只对非只读（矩阵 R1 以上）、批量、覆盖、删除**注入；R0 只读**一律不注入**
 *    （第二百二十三批：门槛跟着矩阵的口径，见 [noteFor] 里的注释）；
 * 2. 单次 ≤ [MAX_CHARS] 字；
 * 3. 同一句在 [DEDUPE_MS] 内只注一次（模型同一动作反复来，别把同一句话灌三遍）；
 * 4. **注入不改变确认清单**——本对象只产一句 note，不碰任何确认/权限判据；
 * 5. 227 §0.6 的聚合提示走**同一个入口**（[noteFor] 的 `historyHint`）：
 *    同样只 R2/R3、同样 ≤ [MAX_CHARS]、同样与影响提示共用上面那格 [DEDUPE_MS] 节流。
 */
internal object ImpactInject {

    /** 单次注入字数上限（派工单 §3.2：≤40 字）。 */
    const val MAX_CHARS = 40

    /** 同一句话的去重窗口（毫秒）。 */
    const val DEDUPE_MS = 8_000L

    private var lastText: String? = null
    private var lastAtMs = 0L

    /** 测试用：清空去重记忆。 */
    fun reset() {
        synchronized(this) {
            lastText = null
            lastAtMs = 0L
        }
    }

    /**
     * 该不该给模型注入这一句。
     *
     * @param historyHint 227 §0.6 的聚合提示（同类动作近 7 天被拒/判错 ≥3 次的**一句事实**，
     *   由 [AuditHint.lineFor] 算好传进来）。非空时优先于「这一次会怎样」——它更能改变
     *   模型下一步做法；但它**不是免检通道**：R2/R3、字数、去重三条照样过。
     * @return 要注入的 note（≤40 字）；null = 不注入（只读/无事实/重复）
     */
    fun noteFor(
        call: ToolCall,
        impact: Impact,
        nowMs: Long = System.currentTimeMillis(),
        historyHint: String? = null,
    ): String? {
        if (impact.kind == Impact.Kind.NONE) return null
        if (impact.unknown) return null // "未知"没有信息量，注进去只是噪音
        // 第二百二十三批：档位改由风险矩阵给（RiskGate.tierOf＝RiskMatrix.classify 的底档）。
        // 门槛从「R2 以上」改成「非只读（R1 以上）」；227 又补了「聚合提示」一路（historyHint）。
        val tier = RiskGate.tierOf(call)
        if (tier < 1) return null // R0 只读一律不注入
        val hint = historyHint?.trim()?.takeIf { it.isNotEmpty() }
        val text = if (hint != null) {
            clamp(hint)
        } else {
            val worth = impact.overwriteTotal > 0 || impact.deleteCount > 0 ||
                impact.moved > 0 || impact.collisions > 0 || impact.newFiles > 0
            if (!worth) return null
            val line = ImpactLine.text(impact) ?: return null
            ImpactLine.fit("【影响】$line")
        }
        if (text.length > MAX_CHARS) return null // 兜底：宁可不注，不许超字数
        synchronized(this) {
            if (text == lastText && nowMs - lastAtMs < DEDUPE_MS) return null
            lastText = text
            lastAtMs = nowMs
        }
        return text
    }

    /** 单行 ＋ ≤ [MAX_CHARS]（聚合提示最坏二十来个字；这里是兜底，不是模板）。 */
    private fun clamp(raw: String): String {
        val one = raw.replace(Regex("""\s+"""), " ").trim()
        return if (one.length <= MAX_CHARS) one else one.take(MAX_CHARS - 1) + "…"
    }
}

/**
 * 影响提示的事后账（**决策中心 208 读它**）。
 *
 * 只存三样：时刻、动作名、一行摘要（＋它的 sha256）。**不存文件名清单**——存的就是
 * 卡面那一行原文：单个覆盖时会带**一个**文件名（`覆盖「a.md」`），既不是整份名单、也不带
 * 目录；再存原始清单等于给日志加一份目录结构（派工单 §4 负例 6）。
 *
 * 进程内环形缓冲（[CAPACITY] 条），不落盘：预览的红线是"只读"，为了一个事后可查的
 * 审计去写盘，等于自己破自己的红线。要长期留痕由 208 落库。
 */
internal object ImpactLedger {

    /** 最多留多少条（够看"刚才那几下"；不是长期账本）。 */
    const val CAPACITY = 64

    /**
     * @param atMs 记账时刻
     * @param tool 工具名
     * @param summary 卡面那一行（或注入那一句）
     * @param hash summary 的 sha256 前 12 位（对账/去重用；不存原始数据）
     * @param shown 是否真的显示过（false = 无卡场景，只记账）
     */
    data class Record(
        val atMs: Long,
        val tool: String,
        val summary: String,
        val hash: String,
        val shown: Boolean,
    )

    private val ring = ArrayDeque<Record>()

    fun record(
        tool: String,
        summary: String,
        shown: Boolean,
        atMs: Long = System.currentTimeMillis(),
    ): Record {
        val r = Record(atMs, tool, summary, sha12(summary), shown)
        synchronized(this) {
            ring.addLast(r)
            while (ring.size > CAPACITY) ring.removeFirst()
        }
        return r
    }

    /** 最近的在前（决策中心列表习惯：新的在上）。 */
    fun snapshot(): List<Record> = synchronized(this) { ring.toList().asReversed() }

    /** 测试用。 */
    fun clear() = synchronized(this) { ring.clear() }

    internal fun sha12(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(12)
    }
}

/**
 * 引擎下一次请求时取走的那一格（**一次性消费**，最多一条）。
 *
 * 为什么不是"直接塞进引擎字段"：预览在 UI 线程算、引擎在自己的线程拼提示词，两边
 * 只能靠一个线程安全的信箱交接；而且必须**取走即清**——同一句话灌两次就是噪音，
 * 用户点一次批准不该让模型记住三遍。
 */
internal object ImpactMailbox {

    private var pending: String? = null

    /** 放进信箱（已有未取走的一条 ⇒ 覆盖：只保留**最新**那次动作的影响）。 */
    fun offer(text: String) {
        synchronized(this) { pending = text }
    }

    /** 取走（没有 ⇒ 空列表；有 ⇒ 一条，且立刻清空）。 */
    fun drain(): List<String> = synchronized(this) {
        val t = pending
        pending = null
        if (t.isNullOrBlank()) emptyList() else listOf(t)
    }

    fun clear() = synchronized(this) { pending = null }
}

/**
 * 设置页那个开关：**「显示影响提示」，默认开**。
 *
 * 关掉之后必须与现在（本批之前）完全一致 ⇒ 所有显示点都只看 [get]，
 * 一个字母都不许绕过它（负例「关掉开关仍有提示」的判据本体）。
 *
 * 存法是 SharedPreferences（与 `ProdEvalSettings`／`VisionConsent` 同款）；
 * 读失败按**默认开**处理：宁可多一行小字，也不让一次 IO 故障把功能悄悄关掉。
 */
internal object ImpactPrefs {

    /** 开关所在的 prefs 文件（与其它抱朴设置同房型，独立文件避免互踩）。 */
    const val PREFS_NAME = "baopu_impact"

    /** 键名（只此一处，设置页与显示点共用）。 */
    const val KEY_SHOW = "show_impact_line"

    fun get(ctx: Context): Boolean = runCatching {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_SHOW, true)
    }.getOrDefault(true)

    /** 写入口（设置页唯一调用点）。写失败按没写处理：下次读到的还是旧值。 */
    fun set(ctx: Context, on: Boolean) {
        runCatching {
            // 用 KTX 的 `edit { }` 而不是 `.edit().putBoolean().apply()`：后者 lint 的
            // `UseKtx` 会记一条（本仓 lint 预算是 0，一分都不许涨）。
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putBoolean(KEY_SHOW, on) }
        }
    }

    /** 纯逻辑重载（JVM 测试用：不碰 Android）。 */
    fun resolve(stored: Boolean?): Boolean = stored ?: true
}

/**
 * 耗时读数（报告里的 p50/p95 就从这来；也是"300ms 到底有没有兜住"的现场证据）。
 *
 * 只记**最近 [CAPACITY] 次**——预览是高频小动作，无界增长的内存账本就是新的泄漏。
 */
internal object ImpactStats {

    const val CAPACITY = 128

    private val samples = ArrayDeque<Long>()
    private val total = AtomicLong(0)

    fun record(elapsedMs: Long) {
        synchronized(this) {
            samples.addLast(elapsedMs)
            while (samples.size > CAPACITY) samples.removeFirst()
        }
        total.incrementAndGet()
    }

    /** @return p50（毫秒；没有样本 ⇒ -1） */
    fun p50(): Long = percentile(50)

    /** @return p95（毫秒；没有样本 ⇒ -1） */
    fun p95(): Long = percentile(95)

    /** 累计记过多少笔。 */
    fun count(): Long = total.get()

    /** 超过预算的次数（降级发生过几次）。 */
    fun overBudget(budgetMs: Long = ImpactPreview.BUDGET_MS): Int =
        synchronized(this) { samples.count { it > budgetMs } }

    fun clear() {
        synchronized(this) { samples.clear() }
        total.set(0)
    }

    private fun percentile(p: Int): Long {
        val list = synchronized(this) { samples.toList().sorted() }
        if (list.isEmpty()) return -1L
        val idx = ((list.size - 1) * p / 100).coerceIn(0, list.size - 1)
        return list[idx]
    }
}
