// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十一批（2026-11-15）· **剧本能力**（原创＋小说改编）的域模型与落盘格式。
//
// 用户口径（派工单原文级）：
//   1. **内部真源 jsonl**：场景 / 地点 / 时间 / 人物 / 动作 / 对白 / 转场，一行一场；
//      导出只是投影——Fountain 与中文标准剧本都是从这份 jsonl 算出来的，不许反向写回；
//   2. **不碰 .fdx、不做 PDF 排版**（本批只出 .fountain ＋ 中文标准剧本 .md）；
//   3. 原创：`script_outline`（人批）→ `script_write`（一场一次，不批不写）；
//   4. 改编：每场标来源（章＋行）；幅度三档由人**选**（缺省不许兜底成「大胆再创作」）；
//      人物言行与原作相冲 ⇒ 必须列冲突项，不许静默改。
//
// 本文件只放**数据形状与编解码**：不碰磁盘、不碰 Context、不碰 Compose。
// 落盘在 [ScriptStore]，判据在 ScriptOutlineRules / ScriptWriteRules，导出在
// FountainFormat / ChineseScriptFormat。
package com.example.zhengdao.baopu.script

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** 剧本工程的格式固定值（改一个就等于换一代文件，旧工程要能被读出来）。 */
internal object ScriptFormat {

    /** 项目头的格式标记。 */
    const val FORMAT = "zhengdao-script/1"

    /** 工作区里的根目录名（与 `小说/` 平级，不塞进 `资料库/`——那不是资料，是作品）。 */
    const val ROOT_DIR = "剧本"

    const val PROJECT_FILE = "project.json"
    const val OUTLINE_FILE = "大纲.json"
    const val SCENES_FILE = "场次.jsonl"

    /** 批准流水（append-only；点号开头 ⇒ 不计入「产物 N 件」，同 `产物/.board.md` 口径）。 */
    const val APPROVALS_FILE = ".批准.jsonl"

    /** 一部片最多多少场（再多就不是一部电影，是十集剧——该拆工程）。 */
    const val MAX_SCENES = 200

    /** 一场最多多少拍（80 拍已经远超正常场次密度，超了基本是把整章塞进一场）。 */
    const val MAX_BEATS = 80

    /** 一拍台词/动作的字数上限（超过就不是一拍，是一段散文）。 */
    const val MAX_BEAT_CHARS = 2000

    /** 场次标题/地点/时间的字数上限。 */
    const val MAX_LABEL_CHARS = 60

    /** 片名上限（同时是目录名上限）。 */
    const val MAX_TITLE_CHARS = 40

    /** 场次号 id 形状：`S01`…（三位补零，稳定可排序、和文件名/表头对得上）。 */
    fun idOf(no: Int): String = "S" + no.toString().padStart(2, '0')
}

/** 创作模式：原创 / 小说改编。 */
internal enum class ScriptMode(val key: String, val zh: String) {
    ORIGINAL("original", "原创"),
    ADAPT("adapt", "小说改编"),
    ;

    companion object {
        fun ofKey(raw: String): ScriptMode? = entries.firstOrNull { it.key == raw.trim().lowercase() }

        /** 人话清单（报错时用）。 */
        fun keys(): String = entries.joinToString(" / ") { it.key }
    }
}

/**
 * 改编幅度三档（**必须由人选**）。
 *
 * 为什么 `ofKey("")` 回 null 而不是回 [BOLD]：用户判据点名「档位缺省不许默认大胆再创作」——
 * 兜底成最大幅度的改写，等于让模型替用户决定「可以离原著远一点」。
 */
internal enum class ScriptTier(val key: String, val zh: String, val oneLine: String) {
    FAITHFUL("faithful", "忠于原文", "只做镜头化：情节、台词、人物言行照原文，不添加原作没有的事"),
    REWORK("rework", "适度重构", "可合并/调序/补过场，但人物性格与关键情节不动"),
    BOLD("bold", "大胆再创作", "可改人物弧光与结局；每一处偏离都要在 conflicts 里写清改了什么、为什么"),
    ;

    companion object {
        fun ofKey(raw: String): ScriptTier? = entries.firstOrNull { it.key == raw.trim().lowercase() }

        fun keys(): String = entries.joinToString(" / ") { "${it.key}（${it.zh}）" }

        /** 档位的人话（报表/工具结果里用；null ⇒ 明确说「还没选」）。 */
        fun textOf(tier: ScriptTier?): String = tier?.let { "${it.key}（${it.zh}）" } ?: "（还没选）"
    }
}

/**
 * 原文位置（**章＋行**，行的口径＝源文件里的绝对行号，用户打开文件一眼能数）。
 *
 * [from] / [to] 是闭区间；`from <= to` 由判据层核（本类只装数据）。
 */
internal data class SourceRef(val chapter: Int, val from: Int, val to: Int) {

    fun text(): String = "第${chapter}章 L$from–L$to"

    fun json(): JSONObject = JSONObject().put("chapter", chapter).put("from", from).put("to", to)

    companion object {
        fun of(o: JSONObject?): SourceRef? {
            if (o == null) return null
            val c = o.optInt("chapter", 0)
            val f = o.optInt("from", 0)
            val t = o.optInt("to", 0)
            if (c <= 0 || f <= 0 || t <= 0) return null
            return SourceRef(c, f, t)
        }
    }
}

/**
 * 人物设定里的一条「禁区」：这个人**不会**说的话/做的事＋它在原文里的依据。
 *
 * 为什么要把「不会做什么」显式写下来：反性格冲突没法靠词频猜（"她笑了"可以是温柔也可以是
 * 残忍），唯一的机器可核口径是**作者自己从原文摘出来的禁区**。写了禁区，工具就能在写正文时
 * 逐拍扫；不写，就只能靠模型自觉——那正是判据⑤不许的「静默通过」。
 */
internal data class Forbid(val phrase: String, val source: String) {

    fun json(): JSONObject = JSONObject().put("phrase", phrase).put("source", source)
}

/** 一个人物的设定（名字＋性格关键词＋禁区）。 */
internal data class CharBible(val name: String, val traits: List<String>, val forbids: List<Forbid>) {

    fun json(): JSONObject = JSONObject()
        .put("name", name)
        .put("traits", JSONArray().also { a -> traits.forEach { a.put(it) } })
        .put("forbids", JSONArray().also { a -> forbids.forEach { a.put(it.json()) } })
}

/** 大纲里的一场（**还没有正文**：只有一句戏核＋它对应原文的哪几行）。 */
internal data class OutlineScene(val id: String, val summary: String, val source: SourceRef?) {

    fun json(): JSONObject = JSONObject().put("id", id).put("summary", summary).also { o ->
        source?.let { o.put("source", it.json()) }
    }
}

/** 一次人批（谁批的、批的是哪一版、什么时候）。 */
internal data class Approval(val rev: Int, val digest: String, val who: String, val atMs: Long) {

    fun json(): JSONObject = JSONObject()
        .put("rev", rev)
        .put("digest", digest)
        .put("who", who)
        .put("at", atMs)
}

/**
 * 大纲（人批的对象）。
 *
 * [digest] 只由「内容」算（rev＋场次＋人物），不含时间/批准——所以「批了之后又改一个字」会让
 * digest 变，批准自动失效（[approved] 里那份旧 digest 对不上现 digest 时判据层拒写正文）。
 */
internal data class Outline(
    val rev: Int,
    val scenes: List<OutlineScene>,
    val characters: List<CharBible>,
    val digest: String,
    val approved: Approval?,
) {

    /** 现在的 digest 与批准时那一份是否一致（一致才允许写正文）。 */
    fun approvalValid(): Boolean = approved != null && approved.digest == digest

    fun sceneOf(id: String): OutlineScene? = scenes.firstOrNull { it.id == id }

    fun json(): JSONObject = JSONObject()
        .put("v", 1)
        .put("rev", rev)
        .put("digest", digest)
        .put("scenes", JSONArray().also { a -> scenes.forEach { a.put(it.json()) } })
        .put("characters", JSONArray().also { a -> characters.forEach { a.put(it.json()) } })
        .also { o -> approved?.let { o.put("approved", it.json()) } }
}

/** 拍的类型：动作 / 对白 / 转场（三种足够表达一场景，不做镜头术语的穷举）。 */
internal enum class BeatKind(val key: String, val zh: String) {
    ACTION("action", "动作"),
    DIALOGUE("dialogue", "对白"),
    TRANSITION("transition", "转场"),
    ;

    companion object {
        fun ofKey(raw: String): BeatKind? = entries.firstOrNull { it.key == raw.trim().lowercase() }

        fun keys(): String = entries.joinToString(" / ") { it.key }
    }
}

/**
 * 一「拍」（一段动作 / 一句台词 / 一次转场）。
 *
 * [src] 是可选的行号（章由场的 [SourceRef] 给）：标了就能点回原文某一行，比"整场对一段"
 * 更细；不标就按场的范围算。
 */
internal data class Beat(
    val kind: BeatKind,
    val text: String,
    val who: String?,
    val paren: String?,
    val src: Int?,
) {

    fun json(): JSONObject = JSONObject().put("kind", kind.key).put("text", text).also { o ->
        who?.let { o.put("who", it) }
        paren?.let { o.put("paren", it) }
        src?.let { o.put("src", it) }
    }
}

/** 一条冲突项（人物言行与原作相冲时，**必须**出现在这里）。 */
internal data class Conflict(val who: String, val source: String, val here: String, val why: String) {

    fun json(): JSONObject = JSONObject()
        .put("who", who)
        .put("source", source)
        .put("here", here)
        .put("why", why)

    fun text(): String = "$who：$here（原文：$source；为什么：$why）"
}

/** 一场正文（jsonl 的一行；[rewrite] 为 true 时同 id 的后一行覆盖前一行）。 */
internal data class Scene(
    val id: String,
    val no: Int,
    val location: String,
    val interior: Boolean,
    val time: String,
    val characters: List<String>,
    val beats: List<Beat>,
    val conflicts: List<Conflict>,
    val source: SourceRef?,
    val tier: ScriptTier?,
    val rewrite: Boolean,
    val atMs: Long,
) {

    fun json(): JSONObject = JSONObject()
        .put("v", 1)
        .put("id", id)
        .put("no", no)
        .put("location", location)
        .put("interior", interior)
        .put("time", time)
        .put("characters", JSONArray().also { a -> characters.forEach { a.put(it) } })
        .put("beats", JSONArray().also { a -> beats.forEach { a.put(it.json()) } })
        .put("conflicts", JSONArray().also { a -> conflicts.forEach { a.put(it.json()) } })
        .put("at", atMs)
        .put("tier", tier?.key ?: "")
        .put("rewrite", rewrite)
        .also { o -> source?.let { o.put("source", it.json()) } }

    /** 场景标题（`内景 咖啡馆 夜` / `外景 巷口 日`）——导出与自检共用这一份算法。 */
    fun heading(): String = "${if (interior) "内景" else "外景"} ${location} ${time}".trim()
}

/** 拍／场解码的两种结局（坏行给一句人话，不抛异常）。 */
internal sealed interface Parsed<out T> {
    data class Ok<T>(val value: T) : Parsed<T>
    data class Bad(val reason: String) : Parsed<Nothing>
}

/** 编解码（**唯一一份**；导出与读回都走这里，不许各自拼一遍 JSON）。 */
internal object ScriptCodec {

    /** 内容摘要：`sha256:<hex16>`。只吃内容，不吃时间——批了之后再动一个字就该换 digest。 */
    fun digestOf(rev: Int, scenes: List<OutlineScene>, characters: List<CharBible>): String {
        val canonical = JSONObject()
            .put("rev", rev)
            .put("scenes", JSONArray().also { a -> scenes.forEach { a.put(it.json()) } })
            .put("characters", JSONArray().also { a -> characters.forEach { a.put(it.json()) } })
            .toString()
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return "sha256:" + bytes.take(8).joinToString("") { "%02x".format(it) }
    }

    fun outlineJson(o: Outline): String = o.json().toString(2)

    /** 大纲解码（坏文件 ⇒ [Parsed.Bad]，带字段名）。 */
    fun outline(raw: JSONObject): Parsed<Outline> {
        val rev = raw.optInt("rev", 0)
        if (rev <= 0) return Parsed.Bad("大纲缺 rev（版本号），文件可能被改坏了")
        val sceneArr = raw.optJSONArray("scenes")
            ?: return Parsed.Bad("大纲缺 scenes 数组")
        val scenes = when (val parsed = scenesOf(sceneArr)) {
            is Parsed.Bad -> return parsed
            is Parsed.Ok -> parsed.value
        }
        val characters = biblesOf(raw.optJSONArray("characters"))
        val digest = raw.optString("digest").trim().ifEmpty { digestOf(rev, scenes, characters) }
        return Parsed.Ok(Outline(rev, scenes, characters, digest, approvalOf(raw.optJSONObject("approved"))))
    }

    /** 场次行解码（[lineNo] 只用于报错定位）。 */
    fun scene(line: String, lineNo: Int): Parsed<Scene> {
        val o = runCatching { JSONObject(line) }.getOrNull()
            ?: return Parsed.Bad("场次文件第 $lineNo 行不是 JSON（文件被手改坏了？）")
        val id = o.optString("id").trim()
        val no = o.optInt("no", 0)
        if (id.isEmpty() || no <= 0) return Parsed.Bad("场次文件第 $lineNo 行缺 id/no")
        val location = o.optString("location").trim()
        val time = o.optString("time").trim()
        if (location.isEmpty() || time.isEmpty()) {
            return Parsed.Bad("场次文件第 $lineNo 行（$id）缺 location/time")
        }
        val beats = when (val parsed = beatsOf(o.optJSONArray("beats"), lineNo, id)) {
            is Parsed.Bad -> return parsed
            is Parsed.Ok -> parsed.value
        }
        return Parsed.Ok(
            Scene(
                id = id,
                no = no,
                location = location,
                interior = o.optBoolean("interior", true),
                time = time,
                characters = strings(o.optJSONArray("characters")),
                beats = beats,
                conflicts = conflictsOf(o.optJSONArray("conflicts")),
                source = SourceRef.of(o.optJSONObject("source")),
                tier = ScriptTier.ofKey(o.optString("tier")),
                rewrite = o.optBoolean("rewrite", false),
                atMs = o.optLong("at", 0L),
            ),
        )
    }

    private fun scenesOf(arr: JSONArray): Parsed<List<OutlineScene>> {
        val scenes = ArrayList<OutlineScene>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: return Parsed.Bad("scenes[$i] 不是对象")
            val id = o.optString("id").trim()
            val summary = o.optString("summary").trim()
            if (id.isEmpty()) return Parsed.Bad("scenes[$i] 缺 id")
            if (summary.isEmpty()) return Parsed.Bad("scenes[$i]（$id）缺 summary")
            scenes += OutlineScene(id, summary, SourceRef.of(o.optJSONObject("source")))
        }
        return Parsed.Ok(scenes)
    }

    private fun biblesOf(arr: JSONArray?): List<CharBible> {
        if (arr == null) return emptyList()
        val out = ArrayList<CharBible>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            if (name.isEmpty()) continue
            val forbids = ArrayList<Forbid>()
            val fArr = o.optJSONArray("forbids")
            if (fArr != null) {
                for (j in 0 until fArr.length()) {
                    val f = fArr.optJSONObject(j) ?: continue
                    val phrase = f.optString("phrase").trim()
                    if (phrase.isEmpty()) continue
                    forbids += Forbid(phrase, f.optString("source").trim())
                }
            }
            out += CharBible(name, strings(o.optJSONArray("traits")), forbids)
        }
        return out
    }

    private fun beatsOf(arr: JSONArray?, lineNo: Int, id: String): Parsed<List<Beat>> {
        if (arr == null) return Parsed.Ok(emptyList())
        val beats = ArrayList<Beat>(arr.length())
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            val kind = BeatKind.ofKey(b.optString("kind"))
                ?: return Parsed.Bad("场次文件第 $lineNo 行（$id）第 ${i + 1} 拍的 kind 不认识")
            val text = b.optString("text")
            if (text.isEmpty()) return Parsed.Bad("场次文件第 $lineNo 行（$id）第 ${i + 1} 拍没有 text")
            beats += Beat(
                kind = kind,
                text = text,
                who = b.optString("who").trim().ifEmpty { null },
                paren = b.optString("paren").trim().ifEmpty { null },
                src = b.optInt("src", 0).takeIf { it > 0 },
            )
        }
        return Parsed.Ok(beats)
    }

    private fun conflictsOf(arr: JSONArray?): List<Conflict> {
        if (arr == null) return emptyList()
        val out = ArrayList<Conflict>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            out += Conflict(
                who = c.optString("who").trim(),
                source = c.optString("source").trim(),
                here = c.optString("here").trim(),
                why = c.optString("why").trim(),
            )
        }
        return out
    }

    private fun approvalOf(o: JSONObject?): Approval? {
        if (o == null) return null
        return Approval(
            rev = o.optInt("rev", 0),
            digest = o.optString("digest").trim(),
            who = o.optString("who").trim(),
            atMs = o.optLong("at", 0L),
        )
    }

    private fun strings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i).trim()
            if (s.isNotEmpty()) out += s
        }
        return out
    }
}
