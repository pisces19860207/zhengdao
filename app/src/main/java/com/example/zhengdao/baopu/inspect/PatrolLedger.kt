// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第二百三十九批 §2.4：报告落 208「巡按」栏——**先要有账，栏里才有得渲染**。
//
// 房型照 219 的 `抽检结论.jsonl`（[com.example.zhengdao.baopu.DecisionReview]）与
// 237 的审计栏台账：追加式一行一条、`@Synchronized`、父目录不存在就建、坏行跳过。
//
// 为什么不写进 208 的 `决策记录.jsonl`：208 的一行是**一个决策**（谁对什么做了什么），
// 巡按的一行是**一次复核**（我查了哪六项、各定什么性）。塞进同一本账，
// 决策中心的分组卡与抽检配额都会把巡按当决策抽——而纠察不许被抽（§5①：它没有写权，
// 拿它当可回滚的决策就是骗人）。所以自成一本，栏位在界面里仍是 208 那一页的「巡按」栏。
//
// 本文件**只写自己的账**：全仓勘验层（`baopu/inspect/`）没有一处写被纠对象的数据或技能正文，
// 这条由 `InspectReadOnlyGateTest` 从源码层钉（§5①）。
package com.example.zhengdao.baopu.inspect

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 一条巡按留痕（= 巡按栏里可点开的那一次）。 */
internal data class PatrolEntry(
    val id: String,
    val atMs: Long,
    val headline: String,
    val lines: List<String>,
    val hits: Int,
    val noData: Int,
    /** 手动「巡一遍」还是夜间低峰自己跑的（§2.1 两个入口，账上要分得开）。 */
    val manual: Boolean,
    /** 这次巡按耗时（真机三天的读数就记在这儿）。 */
    val elapsedMs: Long,
    /**
     * §7.2 额度那一行的读数（**不进正文三行**，进台账与点开的明细）。
     *
     * 为什么不并进 [lines]：三行是"人 3 秒看完"的形制（§3），额度是政策层的事，
     * 挤进正文就会把命中项挤掉——那正是纠察该报的事被人看不见。
     */
    val allowanceLine: String = "",
    /** 六项明细（渲染用；**不交给模型**——负例③由源码门禁钉，不是靠这里不写）。 */
    val findings: List<InspectFinding>,
)

/** 巡按台账。 */
internal object PatrolLedger {

    /** 文件名（与 208/219 同一棵 `抱朴记忆/`）。 */
    const val FILE_NAME = "巡按记录.jsonl"

    fun fileIn(globalRoot: File): File = File(globalRoot, FILE_NAME)

    /** 由报告造留痕（纯函数）。 */
    fun entryOf(
        report: PatrolReport,
        manual: Boolean,
        elapsedMs: Long,
        allowanceLine: String = "",
    ): PatrolEntry = PatrolEntry(
        id = report.id,
        atMs = report.atMs,
        headline = report.headline,
        lines = report.lines,
        hits = report.hits,
        noData = report.noData,
        manual = manual,
        elapsedMs = elapsedMs,
        allowanceLine = allowanceLine,
        findings = report.findings,
    )

    /** 追加一条（失败返回 false：巡按的账丢一条不是事故，调用方 log 一句就够）。 */
    @Synchronized
    fun append(globalRoot: File, entry: PatrolEntry): Boolean = runCatching {
        val f = fileIn(globalRoot)
        f.parentFile?.mkdirs()
        f.appendText(entry.toJson().toString() + "\n", Charsets.UTF_8)
        true
    }.getOrDefault(false)

    /** 现读（新→旧，最多 [limit] 次巡按；坏行跳过）。 */
    fun read(globalRoot: File, limit: Int = 30): List<PatrolEntry> = runCatching {
        val f = fileIn(globalRoot)
        if (!f.isFile) return@runCatching emptyList()
        f.readLines(Charsets.UTF_8).asSequence()
            .mapNotNull { parseLine(it) }
            .sortedByDescending { it.atMs }
            .take(limit)
            .toList()
    }.getOrDefault(emptyList())

    /** 最近一次（巡按栏默认只渲染它——点开的明细也从这条出，不重新查盘）。 */
    fun latest(globalRoot: File): PatrolEntry? = read(globalRoot, limit = 1).firstOrNull()

    /**
     * 一行 ⇒ 一条。
     *
     * 认不出检查号或认定态 ⇒ **整条丢**（不静默折算成别的项）：
     * 台账里的 `wire` 是稳定键，读到没见过的字面量只能是别的批次或写坏了，
     * 那种行渲染出来就是假账。
     */
    fun parseLine(raw: String): PatrolEntry? {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val id = o.optString("id")
        if (id.isEmpty()) return null
        val items = o.optJSONArray("findings") ?: JSONArray()
        val findings = (0 until items.length()).mapNotNull { i ->
            val f = items.optJSONObject(i) ?: return@mapNotNull null
            val check = InspectCheck.ofWire(f.optString("check")) ?: return@mapNotNull null
            val verdict = InspectVerdict.entries.firstOrNull { it.wire == f.optString("verdict") }
                ?: return@mapNotNull null
            InspectFinding(
                check = check,
                verdict = verdict,
                oneLine = f.optString("one_line"),
                suggestion = f.optString("suggestion"),
                evidence = strList(f, "evidence"),
                count = f.optInt("count", 0),
                atMs = f.optLong("at", 0L),
            )
        }
        return PatrolEntry(
            id = id,
            atMs = o.optLong("at", 0L),
            headline = o.optString("headline"),
            lines = strList(o, "lines"),
            hits = o.optInt("hits", 0),
            noData = o.optInt("no_data", 0),
            manual = o.optBoolean("manual", false),
            elapsedMs = o.optLong("elapsed", 0L),
            allowanceLine = o.optString("allowance"),
            findings = findings,
        )
    }

    /** 字符串数组取值（缺键 ⇒ 空表；`org.json` 的 opt 会返回 null，不能直接点 `.length()`）。 */
    private fun strList(o: JSONObject, key: String): List<String> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optString(i).ifEmpty { null } }
    }

    /** 留痕 ⇒ 一行 JSON（键名固定，用例逐个钉）。 */
    fun PatrolEntry.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("at", atMs)
        .put("headline", headline)
        .put("lines", JSONArray(lines))
        .put("hits", hits)
        .put("no_data", noData)
        .put("manual", manual)
        .put("elapsed", elapsedMs)
        .put("allowance", allowanceLine)
        .put(
            "findings",
            JSONArray().apply {
                findings.forEach { f ->
                    put(
                        JSONObject()
                            .put("check", f.check.wire)
                            .put("verdict", f.verdict.wire)
                            .put("one_line", f.oneLine)
                            .put("suggestion", f.suggestion)
                            .put("evidence", JSONArray(f.evidence))
                            .put("count", f.count)
                            .put("at", f.atMs),
                    )
                }
            },
        )
}
