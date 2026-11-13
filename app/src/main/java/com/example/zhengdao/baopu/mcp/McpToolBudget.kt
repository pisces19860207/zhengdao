// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百三十单 · MCP 工具上下文膨胀（全局预算＋按需装配）——预算读数与账本。
//
// 病灶：单 server 有 McpSchema.MAX_TOOLS=64 的上限，但全局没有预算——
// McpServerStore.MAX_SERVERS=16 ⇒ 理论 16×64=1024 个工具定义每轮都带在 tools 数组里。
// 本文件给出两样纯 JVM 的东西：
// 1. [McpToolBudget]：全局工具 schema 预算（按可用上下文比例，默认 8%，可配）；
//    度量只用全系统唯一尺子 [TokenEstimator]，不私算第二把尺。
// 2. [McpBudgetStore]：pin（用户钉住、必装）与 LRU（最近用过优先）的账本，
//    JSON 编解码可 JVM 直测；生产侧随 prefs 落盘（[PrefsMcpBudgetStorage]）。
//
// 红线一条（判据⑤）：**不许截断 schema 后假装能调**。预算装不下就整具不装
// （整装整卸），schema 一个字节都不改——改 schema 的代码在本文件根本不存在。
package com.example.zhengdao.baopu.mcp

import com.example.zhengdao.baopu.TokenEstimator
import org.json.JSONArray
import org.json.JSONObject

internal object McpToolBudget {

    /** 默认比例：工具 schema 总预算 = 可用上下文 × 8%（派工单原话）。 */
    const val DEFAULT_RATIO = 0.08

    /** 比例可配的夹取范围：太小等于没有 MCP 工具，太大会把输入挤爆。 */
    const val MIN_RATIO = 0.01
    const val MAX_RATIO = 0.50

    /**
     * 全局工具 schema 预算（token）＝ 可用上下文 × 比例。
     *
     * 「可用上下文」由调用方用 [com.example.zhengdao.baopu.TokenBudget.availableContext]
     * 算好传入（窗口 − 输出上限 − 系统提示预留 − 工具 schema 预留）；本函数只做比例乘法，
     * 不重复推导窗口口径——那把尺子已经有一处定义，第二处必漂移。
     */
    fun budgetTokens(availableContextTokens: Int, ratio: Double): Int =
        Math.round(availableContextTokens.coerceAtLeast(0) * clampRatio(ratio)).toInt()

    /** 比例夹取（坏配置不许把预算打到 0 或爆窗口）。 */
    fun clampRatio(raw: Double): Double = when {
        raw.isNaN() || raw <= 0.0 -> DEFAULT_RATIO
        raw < MIN_RATIO -> MIN_RATIO
        raw > MAX_RATIO -> MAX_RATIO
        else -> raw
    }

    /** MCP 的额度 ＝ 全局预算 − 内置工具已占（内置必带，先扣它们）。 */
    fun mcpAllowanceTokens(globalBudgetTokens: Int, builtInTokens: Int): Int =
        (globalBudgetTokens - builtInTokens.coerceAtLeast(0)).coerceAtLeast(0)

    /** 一个 schema 的 token 估算（唯一尺子 [TokenEstimator]；JSON 序列化是确定性的）。 */
    fun schemaTokens(schema: JSONObject): Int = TokenEstimator.estimate(schema.toString())

    /** 一个 schema 的字节量（面板读数 {schemaBytes} 用；UTF-8）。 */
    fun schemaBytes(schema: JSONObject): Long =
        schema.toString().toByteArray(Charsets.UTF_8).size.toLong()

    /**
     * 单轮意图打分器：名字＋描述里命中「本轮用户话里的词」越多分越高。
     *
     * 口径刻意简单（预筛只要个排序，不要个 NLP）：
     * - ASCII 词（≥3 字符，含 `_`/`-` 连写）整词包含匹配——工具名是 slug，描述常带英文；
     * - CJK 二元组包含匹配——中文没有空格分词，二字窗够把「搜索」「文件」这类意图词接住。
     * 没有意图文本 ⇒ 恒 0（预筛自然关闭，回落到 LRU/连接序）。
     */
    fun intentScorer(intentText: String?): (String, String) -> Int {
        val text = intentText?.trim().orEmpty()
        if (text.isEmpty()) return { _, _ -> 0 }
        val lower = text.lowercase()
        val asciiWords = ASCII_WORD.findAll(lower).map { it.value }.toSet()
        val cjkGrams = cjkBigrams(lower)
        return { name, description ->
            val hay = "${name.lowercase()} ${description.lowercase()}"
            val asciiHits = asciiWords.count { hay.contains(it) }
            val cjkHits = cjkGrams.count { hay.contains(it) }
            asciiHits + cjkHits
        }
    }

    private val ASCII_WORD = Regex("[a-z0-9][a-z0-9_-]{2,}")

    /** 相邻 CJK 字符的二元组（「帮我搜索文件」→ 搜索/索文/文件）。 */
    private fun cjkBigrams(text: String): Set<String> {
        val grams = LinkedHashSet<String>()
        var i = 0
        while (i < text.length - 1) {
            val a = text[i]
            if (!isCjk(a)) {
                i++
                continue
            }
            val b = text[i + 1]
            if (isCjk(b)) {
                grams.add("$a$b")
                i += 2
            } else {
                i++
            }
        }
        return grams
    }

    private fun isCjk(c: Char): Boolean = c.code >= 0x2E80 && c.code <= 0x9FFF
}

/**
 * pin / LRU / 预算比例 的账本。
 *
 * - **pin**（用户在面板钉住）：装配必带，哪怕超出预算（判据②）；
 * - **LRU**：`tools/call` 真发出去就记时刻（[recordUsed]），装配按「最近用过优先」；
 * - **ratio**：预算比例的覆盖值；null = 用 [McpToolBudget.DEFAULT_RATIO]。
 *
 * 落盘：整份 JSON 覆盖写（同 [McpServerStore] 的口径；条目少、低频，整份写不会半拆）。
 * `storage = null` 即纯内存（单测与「配置只在内存」的旧调用点）。
 */
internal class McpBudgetStore(
    private val storage: McpBudgetStorage?,
    private val log: (String) -> Unit = {},
) {

    private val pins = LinkedHashSet<String>()
    private val lru = LinkedHashMap<String, Long>()

    /** null = 没配过（用默认比例）；一旦配上，clamp 后的值才是真相。 */
    @Volatile
    private var ratio: Double? = null

    /** 读盘（构造后显式调一次；坏 JSON 只跳过自己，不整份丢）。 */
    fun load() {
        val raw = storage?.read() ?: return
        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            log("[mcp] 装配账本不是合法 JSON，按空处理")
            return
        }
        val pinArray = root.optJSONArray(KEY_PINS) ?: JSONArray()
        val loadedPins = LinkedHashSet<String>()
        for (i in 0 until pinArray.length()) {
            val name = pinArray.optString(i).trim()
            if (name.isNotEmpty()) loadedPins.add(name)
        }
        val loadedLru = LinkedHashMap<String, Long>()
        val lruObj = root.optJSONObject(KEY_LRU)
        if (lruObj != null) {
            for (k in lruObj.keys()) {
                val at = lruObj.optLong(k, 0L)
                if (at > 0L) loadedLru[k] = at
            }
        }
        val loadedRatio = if (root.has(KEY_RATIO)) {
            val rawRatio = root.optDouble(KEY_RATIO, Double.NaN)
            if (rawRatio.isNaN()) null else McpToolBudget.clampRatio(rawRatio)
        } else {
            null
        }
        synchronized(this) {
            pins.clear()
            pins.addAll(loadedPins)
            lru.clear()
            lru.putAll(loadedLru)
            ratio = loadedRatio
        }
    }

    @Synchronized
    fun pins(): Set<String> = pins.toSet()

    @Synchronized
    fun isPinned(wireName: String): Boolean = pins.contains(wireName)

    /** @return true = 状态真的变了（调用方据此刷新面板）。 */
    fun pin(wireName: String): Boolean {
        if (wireName.isBlank()) return false
        val changed = synchronized(this) { pins.add(wireName) }
        if (changed) persist()
        return changed
    }

    /** @return true = 状态真的变了。 */
    fun unpin(wireName: String): Boolean {
        val changed = synchronized(this) { pins.remove(wireName) }
        if (changed) persist()
        return changed
    }

    /** LRU 读数（wireName → 最后一次真正发请求的时刻；没有记录的名字不在表里）。 */
    @Synchronized
    fun lastUsedAtMs(): Map<String, Long> = lru.toMap()

    /** `tools/call` 真发出去才记（授权被拒的没发出去，不算「用过」）。 */
    fun recordUsed(wireName: String, atMs: Long) {
        if (wireName.isBlank()) return
        synchronized(this) {
            lru[wireName] = atMs
            // LRU 只保留有意义的最近账（512 条封顶，防一个死循环调用把它撑成无底洞）。
            while (lru.size > LRU_MAX) {
                val oldest = lru.minByOrNull { it.value }?.key ?: break
                lru.remove(oldest)
            }
        }
        persist()
    }

    @Synchronized
    fun ratio(): Double? = ratio

    /** @return true = 状态真的变了（含覆盖成 null=回默认）。 */
    fun setRatio(value: Double?): Boolean {
        val clamped = value?.let { McpToolBudget.clampRatio(it) }
        val changed = synchronized(this) {
            if (clamped == ratio) false else {
                ratio = clamped
                true
            }
        }
        if (changed) persist()
        return changed
    }

    private fun persist() {
        val sink = storage ?: return
        // 锁内取快照再写盘（写盘慢，不许拿着锁写；快照防迭代中被并发改坏）。
        val (pinsCopy, lruCopy, ratioCopy) = synchronized(this) {
            Triple(pins.toList(), lru.toMap(), ratio)
        }
        val pinsArray = JSONArray()
        pinsCopy.forEach { pinsArray.put(it) }
        val lruObj = JSONObject()
        lruCopy.forEach { (name, at) -> lruObj.put(name, at) }
        val root = JSONObject()
            .put("v", FORMAT_VERSION)
            .put(KEY_PINS, pinsArray)
            .put(KEY_LRU, lruObj)
        if (ratioCopy != null) root.put(KEY_RATIO, ratioCopy)
        try {
            sink.write(root.toString())
        } catch (e: Exception) {
            log("[mcp] 装配账本写盘失败（${e.javaClass.simpleName}）——内存仍是真相")
        }
    }

    companion object {
        private const val FORMAT_VERSION = 1
        private const val KEY_PINS = "pins"
        private const val KEY_LRU = "lru"
        private const val KEY_RATIO = "ratio"

        private const val LRU_MAX = 512

        /** 纯内存账本（单测与「配置只在内存」的调用点）。 */
        fun inMemory(): McpBudgetStore = McpBudgetStore(null)
    }
}

/** 一份字符串存储（同 [McpServerStorage] 的房型：让编解码在纯 JVM 里可测）。 */
internal interface McpBudgetStorage {
    fun read(): String?

    /** 写入整份 JSON（覆盖）；实现方失败可以抛，[McpBudgetStore.persist] 会兜住。 */
    fun write(json: String)
}
