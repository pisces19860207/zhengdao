// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 · 学习板块阶段一 · 未学会清单（F）。
//
// 落 `filesDir/抱朴记忆/观察/未学会.json`（231 判据：可查看、可清空、随 A 片 24h 规则）。
// 文件形状：{items:[未消], resolved:[已消,封顶]}——**未消数恒等于 items.size**（判据⑦：
// 清单与数字一致），已消条目留着当阶段二（B 归纳）的原料，不出现在清单与橙点里。
// 读不动按空算（不猜，StopStateStore 口径）；每条带 createdAt，24h 未处理自动清（口径裁定①）。
package com.example.zhengdao.baopu.learn

import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 触发类型（E 的四类，派工单逐字：定位不唯一／该步缺证据／参数不确定／意图不确定）。 */
internal enum class LearnTriggerType(val wire: String) {
    AMBIGUOUS_LOCATOR("ambiguous"),
    NO_EVIDENCE("no_evidence"),
    PARAM_UNCERTAIN("param"),
    INTENT_UNCERTAIN("intent");

    companion object {
        fun of(wire: String): LearnTriggerType? = entries.firstOrNull { it.wire == wire }
    }
}

/** 清单上的一条（一张未答/已答的问题卡）。 */
internal data class NotLearnedItem(
    val id: String,
    val type: LearnTriggerType,
    val createdAt: Long,
    val sessionId: String,
    val stepSeq: Int,
    val url: String,
    val pageName: String,
    val kind: String,
    val faces: ObserveFaces?,
    val question: String,
    val options: List<String>,
    val freeTextPrompt: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("type", type.wire).put("createdAt", createdAt)
        .put("sessionId", sessionId).put("stepSeq", stepSeq).put("url", url)
        .put("pageName", pageName).put("kind", kind)
        .put("question", question)
        .put("options", JSONArray(options))
        .put("freeTextPrompt", freeTextPrompt)
        .also { if (faces != null) it.put("el", faces.toJson()) }

    companion object {
        val KEYS = setOf(
            "id", "type", "createdAt", "sessionId", "stepSeq", "url", "pageName",
            "kind", "el", "question", "options", "freeTextPrompt",
        )

        fun fromJson(o: JSONObject): NotLearnedItem? {
            val type = LearnTriggerType.of(o.optString("type")) ?: return null
            return NotLearnedItem(
                id = o.optString("id"), type = type, createdAt = o.optLong("createdAt", 0L),
                sessionId = o.optString("sessionId"), stepSeq = o.optInt("stepSeq", 0),
                url = o.optString("url"), pageName = o.optString("pageName"), kind = o.optString("kind"),
                faces = o.optJSONObject("el")?.let { runCatching { ObserveFaces.fromJson(it) }.getOrNull() },
                question = o.optString("question"),
                options = o.optJSONArray("options")?.let { a -> (0 until a.length()).map { k -> a.optString(k) } } ?: emptyList(),
                freeTextPrompt = o.optString("freeTextPrompt"),
            )
        }
    }
}

/** 已消条目（清单上不显示；答案文本是阶段二归纳的原料）。 */
internal data class ResolvedItem(
    val id: String,
    val type: LearnTriggerType,
    val resolution: String, // "answered"（答问消项）／"taught"（就教这一步）
    val detail: String,
    val atMs: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("type", type.wire).put("resolution", resolution)
        .put("detail", detail).put("atMs", atMs)

    companion object {
        fun fromJson(o: JSONObject): ResolvedItem? {
            val type = LearnTriggerType.of(o.optString("type")) ?: return null
            return ResolvedItem(
                id = o.optString("id"), type = type, resolution = o.optString("resolution"),
                detail = o.optString("detail"), atMs = o.optLong("atMs", 0L),
            )
        }
    }
}

/** 清单状态（判据⑦：count 恒等于 items.size，由这一个纯函数出，不许两处算）。 */
internal data class LearnBoardState(
    val items: List<NotLearnedItem> = emptyList(),
    val resolved: List<ResolvedItem> = emptyList(),
) {
    val count: Int get() = items.size
}

/** 未学会清单的读写壳（单文件覆盖写；读不动按空算）。 */
internal class NotLearnedStore(private val file: File) {

    private var cached: LearnBoardState = load()

    fun state(): LearnBoardState = synchronized(LOCK) { cached }

    /** 新触发入清单（同 id 跳过——重复收割不重复出卡）。 */
    fun add(items: List<NotLearnedItem>) {
        synchronized(LOCK) {
            val known = cached.items.map { it.id }.toSet()
            val fresh = items.filter { it.id !in known }
            if (fresh.isEmpty()) return
            cached = LearnBoardState(cached.items + fresh, cached.resolved)
            persist()
        }
    }

    /** 答问消项（口径裁定⑤：已答即移出未学会，答案进 resolved）。 */
    fun answer(id: String, text: String, atMs: Long) = resolve(id, "answered", text, atMs)

    /** 教完消项（判据⑥⑥⑦：教完数字减一）。 */
    fun taught(id: String, detail: String, atMs: Long) = resolve(id, "taught", detail, atMs)

    /** 可清空（231 判据；未消＋已消一起清，用户明说才算）。 */
    fun clear() {
        synchronized(LOCK) {
            cached = LearnBoardState()
            persist()
        }
    }

    /** 24h 自清（判据④；过期未处理条目整条移出——过期也计入「归零隐藏」）。 */
    fun prune(nowMs: Long, ttlMs: Long = TTL_MS) {
        synchronized(LOCK) {
            val horizon = nowMs - ttlMs
            val items = cached.items.filter { it.createdAt > horizon }
            val resolved = cached.resolved.filter { it.atMs > horizon }.takeLast(MAX_RESOLVED)
            if (items.size == cached.items.size && resolved.size == cached.resolved.size) return
            cached = LearnBoardState(items, resolved)
            persist()
        }
    }

    private fun resolve(id: String, resolution: String, detail: String, atMs: Long) {
        synchronized(LOCK) {
            val item = cached.items.firstOrNull { it.id == id } ?: return
            val items = cached.items.filterNot { it.id == id }
            val resolved = (cached.resolved + ResolvedItem(id, item.type, resolution, detail, atMs)).takeLast(MAX_RESOLVED)
            cached = LearnBoardState(items, resolved)
            persist()
        }
    }

    private fun persist() = runCatching {
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject()
                .put("items", JSONArray(cached.items.map { it.toJson() }))
                .put("resolved", JSONArray(cached.resolved.map { it.toJson() }))
                .toString(),
            Charsets.UTF_8,
        )
        true
    }.onFailure { RunLog.log("学习板块：未学会清单写盘失败 ${it.javaClass.simpleName}") }.getOrDefault(false)

    private fun load(): LearnBoardState = runCatching {
        if (!file.isFile) return LearnBoardState()
        val o = JSONObject(file.readText(Charsets.UTF_8))
        LearnBoardState(
            items = o.optJSONArray("items")?.let { a ->
                (0 until a.length()).mapNotNull { k -> runCatching { NotLearnedItem.fromJson(a.getJSONObject(k)) }.getOrNull() }
            } ?: emptyList(),
            resolved = o.optJSONArray("resolved")?.let { a ->
                (0 until a.length()).mapNotNull { k -> runCatching { ResolvedItem.fromJson(a.getJSONObject(k)) }.getOrNull() }
            } ?: emptyList(),
        )
        }.getOrDefault(LearnBoardState())

    companion object {
        const val FILE_NAME = "未学会.json"
        const val MAX_RESOLVED = 50
        const val TTL_MS = 86_400_000L
        private val LOCK = NotLearnedStore::class.java
    }
}
