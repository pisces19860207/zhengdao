// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第一百三十一批 · 模型选择 P0》②——同一 provider 可填多把 Key（本地保存、
// 不明文进日志）；请求遇 429/403/额度耗尽 ⇒ 切下一个 key；全部不可用 ⇒ 可读提示，不静默重试；
// 冷却/顺序/禁用单 key 的策略要写清；不自动创建/购买 Key、不内置任何 Key。
//
// 为什么这一层是**纯函数**（不吃 Context、不吃 SharedPreferences、不吃系统时间）：
// 本仓无 Robolectric ⇒ SP 薄壳在 JVM 单测里实例化不了（ProviderPrefs 同款教训）。
// 池子的存储格式、轮询顺序、冷却与禁用判据全在这里，JVM 直测；
// [ProviderPrefs] 那层只负责把一列字符串读进来、写回去。
package com.example.zhengdao.baopu

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个提供商名下**多把 Key 的池**（第一百三十一批 ②）。
 *
 * ## 策略（派工单要求"写清"的那三件）
 * - **顺序**：轮询（round-robin）。`cursor` 记着"上次在用哪一把"，失败就往后走一格，
 *   走到底回绕——不是每次都从第一把重试（那样第一把永远在挨 429）。
 * - **冷却**：按失败原因分档，见 [Outcome]——限流 [COOLDOWN_RATE_MS]（60 秒，
 *   429 是"暂时Too多请求"），额度耗尽／区域限制 [COOLDOWN_QUOTA_MS]（5 分钟，
 *   403 这类短时间内再试没意义）。到点自己回池，不需要用户动手。
 * - **禁用**：只有 [Outcome.KEY_REJECTED]（401：这把 Key 服务端根本不认）直接
 *   [Entry.disabled] 置位，**永久停手**——重试一百次也一样，只能用户在选模型里手动解禁
 *   或换一把。引擎永不自动解禁（自动解禁等于替用户决定"这把还能用"）。
 *
 * ## 全挂的时候（②「全部不可用 ⇒ 可读提示，不静默重试」）
 * [activeIndex] 返回 -1，[allUnavailableNotice] 出一句人话：几把都在冷却、最快多久回来、
 * 以及"可以再加一把 Key"。这句进界面而不是进循环——**不做静默重试**。
 *
 * ## Key 的保密纪律（② + 红线）
 * 池子只在内存与本文件里以原文形态存在；要给界面/日志看只能经 [mask]。
 * 本文件不 import 任何 Android/日志设施（有源码门禁钉这一点）。
 */
internal object KeyPool {

    /** 一把 Key 最多存几把：池子越大，坏通道探测越慢，8 把对个人用途封顶够用。 */
    const val MAX_KEYS = 8

    /** 429（限流）冷却：60 秒。 */
    const val COOLDOWN_RATE_MS = 60_000L

    /** 403 / 额度耗尽 / 区域限制冷却：5 分钟。 */
    const val COOLDOWN_QUOTA_MS = 300_000L

    /** [mask] 用来盖住前缀的那四个点（补充节给的字面形态 `••••a1b2`）。 */
    const val TAIL_DOTS = "••••"

    /** 失败分类（决定"冷却还是禁用"；只看服务端原文里的状态码与关键词）。 */
    enum class Outcome {
        /** 429 限流 ⇒ 短冷却。 */
        RATE_LIMITED,

        /** 403 / "quota" / "insufficient" / 余额不足 ⇒ 长冷却。 */
        QUOTA_OR_FORBIDDEN,

        /** 401 / invalid api key ⇒ 这把 Key 永久禁用（等用户手动解禁）。 */
        KEY_REJECTED,

        /** 与 Key 无关（超时、断流、5xx）⇒ **不动池子**，交回通道级策略。 */
        NOT_ABOUT_KEY,
    }

    /**
     * 一把 Key 及其状态。
     *
     * @param key 原文 Key（只在内存与 SP 里；界面/日志一律走 [mask]）
     * @param cooldownUntil epoch 毫秒；`<= now` 视为不在冷却
     * @param disabled 用户/策略禁用位（401 自动置位，只有用户能解）
     */
    data class Entry(
        val key: String,
        val cooldownUntil: Long = 0L,
        val disabled: Boolean = false,
    ) {
        fun usableAt(now: Long): Boolean = !disabled && cooldownUntil <= now

        /** 还要等多久才回来（毫秒）；已可用／永久禁用 ⇒ 0（禁用项不参与"最快恢复"）。 */
        fun waitMs(now: Long): Long = if (disabled) 0L else (cooldownUntil - now).coerceAtLeast(0L)
    }

    /**
     * 从服务端原文判"这次失败是不是这把 Key 的锅"（纯函数，不含正文语义）。
     *
     * 顺序有讲究：**401 先判**（它最硬：这把 Key 不认，别的词都别想把它降级成"再等等"）；
     * 429 次之（限流是量的问题）；403/额度再次；都不沾 ⇒ [Outcome.NOT_ABOUT_KEY]。
     */
    fun classify(raw: String): Outcome = when {
        raw.contains("401") ||
            (raw.contains("invalid", ignoreCase = true) && raw.contains("key", ignoreCase = true)) ->
            Outcome.KEY_REJECTED
        raw.contains("429") || raw.contains("rate limit", ignoreCase = true) ||
            raw.contains("too many requests", ignoreCase = true) -> Outcome.RATE_LIMITED
        raw.contains("403") || raw.contains("quota", ignoreCase = true) ||
            raw.contains("insufficient", ignoreCase = true) ||
            raw.contains("credit", ignoreCase = true) ||
            raw.contains("balance", ignoreCase = true) -> Outcome.QUOTA_OR_FORBIDDEN
        else -> Outcome.NOT_ABOUT_KEY
    }

    /** 该 [Outcome] 对应的处置：冷却时长（0 = 不等）＋要不要直接禁用。 */
    fun penaltyOf(outcome: Outcome): Pair<Long, Boolean> = when (outcome) {
        Outcome.RATE_LIMITED -> COOLDOWN_RATE_MS to false
        Outcome.QUOTA_OR_FORBIDDEN -> COOLDOWN_QUOTA_MS to false
        Outcome.KEY_REJECTED -> 0L to true
        Outcome.NOT_ABOUT_KEY -> 0L to false
    }

    /** 这把 Key 失败一次之后变成什么样（纯函数；[Outcome.NOT_ABOUT_KEY] ⇒ 原样返回）。 */
    fun penalize(entry: Entry, outcome: Outcome, now: Long): Entry {
        val (wait, disable) = penaltyOf(outcome)
        if (outcome == Outcome.NOT_ABOUT_KEY) return entry
        return entry.copy(cooldownUntil = if (wait > 0) now + wait else 0L, disabled = disable)
    }

    /** 整池判一次：把第 [index] 把按 [outcome] 处罚，其余原样（越界 ⇒ 原样返回，不抛）。 */
    fun penalizeAt(entries: List<Entry>, index: Int, outcome: Outcome, now: Long): List<Entry> {
        if (index !in entries.indices) return entries
        return entries.mapIndexed { i, e -> if (i == index) penalize(e, outcome, now) else e }
    }

    // ── 存储编解码（一格字符串存整个池子；坏数据 ⇒ 空池，不抛异常） ──────────────

    /**
     * 编码：`[{"k":"…","u":123,"d":false}, …]`。空池 ⇒ `"[]"`（写空数组而不是写空串，
     * 这样"用户把所有 Key 删光"与"从来没存过"在存储上是两件事，读取端能分开判）。
     */
    fun encode(entries: List<Entry>): String {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("k", e.key)
                    .put("u", e.cooldownUntil)
                    .put("d", e.disabled),
            )
        }
        return arr.toString()
    }

    /** 解码：不是合法 JSON、缺字段、脏类型 ⇒ 逐条跳过；整体读不出来 ⇒ 空池。 */
    fun decode(stored: String?): List<Entry> {
        val text = stored?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        return runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val k = o.optString("k")
                if (k.isBlank()) return@mapNotNull null
                Entry(
                    key = k,
                    cooldownUntil = o.optLong("u", 0L),
                    disabled = o.optBoolean("d", false),
                )
            }
        }.getOrNull() ?: emptyList()
    }

    /**
     * 老格式迁移（②「同一 provider 可填多个 key」之前的单 Key 格 `user_key_$id`）。
     *
     * 新格有内容 ⇒ 以新格为准（**不合并**：合并会把已经删掉的那把又捞回来）；
     * 新格空而老格有 ⇒ 当成一把入池。薄壳首次写入新格时会清掉老格（见 ProviderPrefs）。
     */
    fun migrate(stored: String?, legacy: String?): List<Entry> {
        val pool = decode(stored)
        if (pool.isNotEmpty()) return pool
        val old = legacy?.trim().orEmpty()
        return if (old.isEmpty()) emptyList() else listOf(Entry(old))
    }

    // ── 轮询判据（顺序的唯一真相；全部 Context-free） ──────────────────────────

    /** 此刻可用的下标（保持用户填的先后序）。 */
    fun usableIndices(entries: List<Entry>, now: Long): List<Int> =
        entries.mapIndexed { i, e -> i to e }.filter { it.second.usableAt(now) }.map { it.first }

    /**
     * 该用哪一把：从 [cursor] 起往后找第一把可用的，走到末尾回绕到 [cursor] 之前。
     * 一把都不行 ⇒ -1（调用方负责给可读提示，不许静默换回第一把硬试）。
     */
    fun activeIndex(entries: List<Entry>, cursor: Int, now: Long): Int {
        val n = entries.size
        if (n == 0) return -1
        val start = ((cursor % n) + n) % n
        for (step in 0 until n) {
            val i = (start + step) % n
            if (entries[i].usableAt(now)) return i
        }
        return -1
    }

    /** 当前该带出去的 Key 原文；没有可用的 ⇒ null（薄壳据此决定"这条通道暂时没 Key"）。 */
    fun activeKey(entries: List<Entry>, cursor: Int, now: Long): String? =
        activeIndex(entries, cursor, now).takeIf { it >= 0 }?.let { entries[it].key }

    /**
     * 第 [index] 把刚失败过 ⇒ 下一格该指哪：往后第一把可用的（禁用/冷却项自动跳过）。
     * 没有下一把 ⇒ -1（全挂）。
     */
    fun nextIndex(entries: List<Entry>, index: Int, now: Long): Int {
        val n = entries.size
        if (n == 0 || index !in entries.indices) return -1
        for (step in 1 until n) {
            val i = (index + step) % n
            if (entries[i].usableAt(now)) return i
        }
        return -1
    }

    /**
     * 换 Key（② 的核心一步）：处罚第 [index] 把 ＋ 给出该用的下一把。
     *
     * @return (新池子, 下一把下标, 下一把原文或 null)
     */
    fun rotate(
        entries: List<Entry>,
        index: Int,
        outcome: Outcome,
        now: Long,
    ): Triple<List<Entry>, Int, String?> {
        val punished = penalizeAt(entries, index, outcome, now)
        val next = nextIndex(punished, index, now)
        return Triple(punished, next, next.takeIf { it >= 0 }?.let { punished[it].key })
    }

    // ── 池子编辑（用户手动，App 不自动创建/购买 Key） ─────────────────────────

    /**
     * 加一把：空串／重复（逐字比）／超过 [MAX_KEYS] ⇒ **原样返回**，由调用方决定怎么说。
     * 新加的 Key 状态是"立即可用"（用户刚填的，没有前科）。
     */
    fun add(entries: List<Entry>, key: String): List<Entry> {
        val k = key.trim()
        if (k.isEmpty() || entries.size >= MAX_KEYS || entries.any { it.key == k }) return entries
        return entries + Entry(k)
    }

    /** 删一把（按原文精确匹配；池里没有就是没删）。 */
    fun remove(entries: List<Entry>, key: String): List<Entry> =
        entries.filterNot { it.key == key.trim() }

    /** 解禁／禁用一把（只有用户点；引擎不许调这个把 disabled 解掉）。 */
    fun setDisabled(entries: List<Entry>, index: Int, disabled: Boolean): List<Entry> {
        if (index !in entries.indices) return entries
        return entries.mapIndexed { i, e -> if (i == index) e.copy(disabled = disabled) else e }
    }

    /** 某把现在到底能不能用（UI 每行显示状态用）。 */
    fun statusOf(entry: Entry, now: Long): String = when {
        entry.disabled -> "已禁用"
        entry.cooldownUntil > now -> "冷却中"
        else -> "可用"
    }

    // ── 界面/日志只许看的形态 ───────────────────────────────────────────────

    /**
     * 打码：**永不返回原文**。只留**尾 4 位**，前面一律 `••••`
     * （第一百三十一批 11:51 补充节：「多 Key 只显示尾 4 位（`••••a1b2`），永不显示全 key」）。
     *
     * 为什么不再留头 4 位：OpenAI 形态的 Key 头部是**公共前缀**（`sk-`、`sk-or-`），
     * 头 4 位不帮忙认 Key，只多泄露一格结构；尾 4 位才是用户区分"这是我哪一把"的那几位。
     *
     * 为什么短 Key 连尾 4 位也不给：长度 ≤ 8 时尾 4 位就是**半个 Key**，
     * 屏幕上多露一位是一位的风险 ⇒ 整体压成 `••••`（认不出来，但至少没露）。
     */
    fun mask(key: String): String {
        val k = key.trim()
        if (k.length <= 8) return TAIL_DOTS
        return TAIL_DOTS + k.takeLast(4)
    }

    /** 全池最快恢复还要多久（毫秒）；没有"在冷却"的项 ⇒ 0（要么可用、要么都被禁用）。 */
    fun soonestRecoveryMs(entries: List<Entry>, now: Long): Long =
        entries.map { it.waitMs(now) }.filter { it > 0 }.minOrNull() ?: 0L

    /**
     * 全部不可用时的人话（②「可读提示，不静默重试」的唯一出口）。
     *
     * 三件事齐了才算说清：几把／为什么（禁用与冷却分别计数）／最快多久回来＋下一步能干什么。
     * 空池不走这里（那是"没填 Key"，由选模型抽屉那句注册提示负责）。
     */
    fun allUnavailableNotice(entries: List<Entry>, now: Long): String {
        val disabled = entries.count { it.disabled }
        val cooling = entries.count { !it.disabled && it.cooldownUntil > now }
        val sb = StringBuilder("这条模型下的 ${entries.size} 把 Key 现在都用不了")
        if (cooling > 0) {
            val seconds = (soonestRecoveryMs(entries, now) / 1000L).coerceAtLeast(1L)
            sb.append("（$cooling 把在冷却，最快 $seconds 秒后恢复）")
        }
        if (disabled > 0) sb.append("（$disabled 把已被禁用，要在选模型里手动解禁）")
        sb.append("；可以在选模型里再加一把 Key，或换一个提供商。")
        return sb.toString()
    }
}
