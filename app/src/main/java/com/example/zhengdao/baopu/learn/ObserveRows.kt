// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 · 学习板块阶段一 · 观察行模型与会话账（A 观察）。
//
// 行**键封闭集**：事件行只有 a/url/el/nc/t/d/s（输入事件多一个 vs）——没有第六列放正文，
// 「不记正文、不记整页 HTML」在数据结构上封死（同 633 语音账「三字段闭集」的房型）。
// 敏感第二道过滤（纵深防御）：页内源头闸之后，原生侧对每条行**再过一次**敏感词表，
// 命中整条丢弃。文件房型照 VoiceLedger：单追加点、坏行跳过、封顶、写失败 false 由调用方说。
package com.example.zhengdao.baopu.learn

import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 事件种类：点击／输入（type+select 并一档记 input）／导航（页行表达）／滚动。 */
internal enum class ObserveKind(val wire: String) {
    CLICK("click"), TYPE("type"), SELECT("select"), SCROLL("scroll");

    companion object {
        fun of(wire: String): ObserveKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** 输入值的**形状**（不是值）：empty/short/digits/date/long——形状出页，值永不出页。 */
internal enum class ValueShape(val wire: String) {
    EMPTY("empty"), SHORT("short"), DIGITS("digits"), DATE("date"), LONG("long");

    companion object {
        fun of(wire: String): ValueShape? = entries.firstOrNull { it.wire == wire }
    }
}

/** 元素身份面（与页内 `faces()` 同形状；十级定位同源，无 text 无 value）。 */
internal data class ObserveFaces(
    val tag: String,
    val role: String,
    val name: String,
    val aria: String,
    val testid: String,
    val id: String,
    val inputName: String,
    val placeholder: String,
    val inputType: String,
    val auto: String,
    val formLabel: String,
    val xp: String,
    val cls: List<String>,
) {
    /** 敏感源自判（第二道闸的判据；与页内词表同源）。 */
    fun isSensitive(): Boolean = inputType.equals("password", true) ||
        AUTO_SENS.containsMatchIn(auto) ||
        SENSITIVE.containsMatchIn("$inputName $id $placeholder $aria $formLabel")

    fun toJson(): JSONObject = JSONObject()
        .put("tag", tag).put("role", role).put("name", name).put("aria", aria)
        .put("testid", testid).put("id", id).put("inputName", inputName)
        .put("placeholder", placeholder).put("inputType", inputType).put("auto", auto)
        .put("formLabel", formLabel).put("xp", xp).put("cls", JSONArray(cls))

    companion object {
        val KEYS = setOf(
            "tag", "role", "name", "aria", "testid", "id", "inputName",
            "placeholder", "inputType", "auto", "formLabel", "xp", "cls",
        )
        private val AUTO_SENS = Regex("(?i)one-time-code|cc-|current-password|new-password")

        fun fromJson(o: JSONObject): ObserveFaces = ObserveFaces(
            tag = o.optString("tag"), role = o.optString("role"), name = o.optString("name"),
            aria = o.optString("aria"), testid = o.optString("testid"), id = o.optString("id"),
            inputName = o.optString("inputName"), placeholder = o.optString("placeholder"),
            inputType = o.optString("inputType"), auto = o.optString("auto"),
            formLabel = o.optString("formLabel"), xp = o.optString("xp"),
            cls = o.optJSONArray("cls")?.let { a -> (0 until a.length()).mapNotNull { k -> a.optString(k) } } ?: emptyList(),
        )
    }
}

/** 敏感词表（与 ObserveScript 页内词表同源；Kotlin 侧第二道）。 */
internal val SENSITIVE: Regex = Regex("(?i)pass|pwd|密码|验证码|校验码|captcha|otp|短信")

/** 一条结构化事件。 */
internal data class ObserveEvent(
    val kind: ObserveKind,
    val ts: Long,
    val docId: String,
    val seq: Int,
    val url: String,
    val nodeCount: Int,
    val faces: ObserveFaces,
    val shape: ValueShape?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("k", "ev").put("t", ts).put("doc", docId).put("seq", seq)
        .put("a", kind.wire).put("url", url).put("nc", nodeCount)
        .put("el", faces.toJson())
        .also { if (shape != null) it.put("vs", shape.wire) }

    companion object {
        /** 事件行键闭集（el 内的键闭集见 [ObserveFaces.KEYS]）：多一列都是违规。 */
        val KEYS = setOf("k", "t", "doc", "seq", "a", "url", "nc", "el", "vs")

        fun fromJson(o: JSONObject): ObserveEvent? {
            val kind = ObserveKind.of(o.optString("a")) ?: return null
            val faces = runCatching { ObserveFaces.fromJson(o.getJSONObject("el")) }.getOrNull() ?: return null
            val vs = o.optString("vs", "")
            return ObserveEvent(
                kind = kind, ts = o.optLong("t", 0L), docId = o.optString("doc"),
                seq = o.optInt("seq", 0), url = o.optString("url"), nodeCount = o.optInt("nc", 0),
                faces = faces, shape = if (vs.isEmpty()) null else ValueShape.of(vs),
            )
        }
    }
}

/** 节点表的一行（纯结构身份；敏感节点身份字段抹空，只留形状与路径）。 */
internal data class ObserveNode(val faces: ObserveFaces, val sensitive: Boolean) {
    fun toJson(): JSONObject = faces.toJson().put("sens", sensitive)

    companion object {
        fun fromJson(o: JSONObject): ObserveNode {
            val f = ObserveFaces.fromJson(o)
            // 双保险：页内已抹空，这里再抹一次（readYourOwn 防御；sens 位以判定为准）
            val sens = o.optBoolean("sens", false) || f.isSensitive()
            return ObserveNode(
                faces = if (sens) f.copy(name = "", aria = "", id = "", inputName = "", placeholder = "", formLabel = "") else f,
                sensitive = sens,
            )
        }
    }
}

/** 一份页面语义节点表（每个文档第一次被收割时交一份；只结构、无正文、无 HTML）。 */
internal data class ObservePage(
    val ts: Long,
    val docId: String,
    val url: String,
    val title: String,
    val nodeCount: Int,
    val nodes: List<ObserveNode>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("k", "pg").put("t", ts).put("doc", docId).put("url", url)
        .put("title", title).put("nc", nodeCount)
        .put("nodes", JSONArray(nodes.map { it.toJson() }))

    companion object {
        fun fromJson(o: JSONObject): ObservePage = ObservePage(
            ts = o.optLong("t", 0L), docId = o.optString("doc"), url = o.optString("url"),
            title = o.optString("title"), nodeCount = o.optInt("nc", 0),
            nodes = o.optJSONArray("nodes")?.let { a ->
                (0 until a.length()).mapNotNull { k -> runCatching { ObserveNode.fromJson(a.getJSONObject(k)) }.getOrNull() }
            } ?: emptyList(),
        )
    }
}

/** 一次收割的产物（页内脚本返回值的 Kotlin 形状）。 */
internal data class DrainResult(val docId: String, val events: List<ObserveEvent>, val page: ObservePage?) {
    companion object {
        /** 解 execJs 的返回：外层 {"result":"<转义 json>"}（去敏＋限长既有路径），内层才是账。 */
        fun parse(raw: String): DrainResult? = runCatching {
            val outer = JSONObject(raw)
            val inner = outer.optString("result")
            if (inner.isEmpty()) return null
            val o = JSONObject(inner)
            val evs = o.optJSONArray("ev")?.let { a ->
                (0 until a.length()).mapNotNull { k ->
                    runCatching { ObserveEvent.fromJson(a.getJSONObject(k)) }.getOrNull()
                }
            } ?: emptyList()
            val pg = o.optJSONObject("pg")?.let { ObservePage.fromJson(it) }
            DrainResult(o.optString("doc"), evs, pg)
        }.getOrNull()
    }
}

/**
 * 一个观察会话：内存累积（去重＋敏感过滤）＋ jsonl 追加写，收会话时交出全部行供校验。
 *
 * 会话 id＝起始时刻（base36），文件名 `会话-<id>.jsonl`——24h 自清按 id 判旧（判据④）。
 */
internal class ObserveSession(
    val id: String,
    private val file: File,
    private val nowMs: () -> Long,
) {
    private val seen = HashSet<String>()
    private val events = ArrayList<ObserveEvent>()
    private val pagesByDoc = LinkedHashMap<String, MutableList<ObservePage>>()
    private var closed = false

    val eventCount: Int get() = synchronized(LOCK) { events.size }
    val pageCount: Int get() = synchronized(LOCK) { pagesByDoc.size }

    /** 收割入账（去重 doc#seq；敏感整条丢弃；页行 URL/节点数变了才追加，每文档封顶）。 */
    fun ingest(result: DrainResult): Boolean = synchronized(LOCK) {
        if (closed) return false
        var wrote = false
        result.events.forEach { ev ->
            if (ev.faces.isSensitive()) return@forEach // 整条丢弃（第二道闸）
            val key = ev.docId + "#" + ev.seq
            if (!seen.add(key)) return@forEach
            if (events.size >= MAX_EVENTS) return@forEach
            events += ev
            wrote = appendLine(ev.toJson()) || wrote
        }
        if (result.page != null) wrote = appendPage(result.page) || wrote
        wrote
    }

    /** 该文档**最新一份带节点表**的页行（单步补录的定位复验用；心跳行不带节点，跳过）。 */
    fun latestPage(docId: String): ObservePage? = synchronized(LOCK) {
        pagesByDoc[docId]?.lastOrNull { it.nodes.isNotEmpty() }
    }

    /** 空闲判（收会话用）。 */
    fun isIdle(idleMs: Long): Boolean = synchronized(LOCK) {
        val last = events.lastOrNull()?.ts ?: openedAtMs()
        nowMs() - last >= idleMs
    }

    /** 收会话：落 close 行＋冻结，交出快照（判据⑤校验器的输入）。 */
    fun close(): ObserveSnapshot = synchronized(LOCK) {
        if (!closed) {
            appendLine(JSONObject().put("k", "close").put("t", nowMs()))
            closed = true
        }
        ObserveSnapshot(
            openedAtMs(),
            events.toList(),
            pagesByDoc.values.flatMap { it.toList() },
        )
    }

    /**
     * 页行照收（脚本侧 2s 心跳已节流；同事实的心跳行**保留**——「看过、没变化」正是
     * 缺证据的证据）；每文档滑动封顶，装满丢最旧。
     */
    private fun appendPage(page: ObservePage): Boolean {
        val list = pagesByDoc.getOrPut(page.docId) { ArrayList() }
        if (list.size >= MAX_PAGES_PER_DOC) list.removeAt(0)
        list += page
        return appendLine(page.toJson())
    }

    /** 超长行**整条不落**（截半条 JSON 是垃圾，不是账）；写失败 false 由调用方说。 */
    private fun appendLine(o: JSONObject): Boolean {
        val line = o.toString()
        if (line.length > MAX_ROW_CHARS) return false
        return runCatching {
            file.parentFile?.mkdirs()
            file.appendText(line + "\n", Charsets.UTF_8)
            true
        }.onFailure { RunLog.log("学习板块：观察行落盘失败 ${it.javaClass.simpleName}") }.getOrDefault(false)
    }

    private fun openedAtMs(): Long = id.toLongOrNull(36) ?: nowMs()

    companion object {
        const val MAX_EVENTS = 2_000
        const val MAX_PAGES_PER_DOC = 8
        const val MAX_ROW_CHARS = 20_000
        const val FILE_PREFIX = "会话-"
        const val FILE_SUFFIX = ".jsonl"
        private val LOCK = ObserveSession::class.java

        fun fileNameFor(id: String): String = FILE_PREFIX + id + FILE_SUFFIX
    }
}

/** 收会话时交出的只读快照（校验器输入；纯数据，JVM 直测）。 */
internal data class ObserveSnapshot(
    val openedAtMs: Long,
    val events: List<ObserveEvent>,
    val pages: List<ObservePage>,
)
