// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十八批 · 判决经验账：**落盘的那一本**（283 预留的 `JudgmentExperienceStore` 口）。
//
// 283 交的是接口＋内存桩，注释里写着"280 落盘后换实现，本文件零改动"——本批就是那次换实现：
// 桩换成这本 `判决经验账.jsonl`，接口一个字没动（签名是 283 定的，改签名就是改法）。
//
// 为什么必须落盘（不是"顺手做的持久化"）：判据①说"同类卡 **30 天**被关 ≥3 次"，
// 判据②说"**删掉表** ⇒ 行为退回原样"。30 天横跨多少次重启，删表要删的是文件——
// 内存桩这两条都验不了：重启即空，等于学到的东西每早清零，而"删表"根本没有可删的东西。
//
// 房型照 274/277/278（同一条链路的三本账）：
//   - 落点＝208 已装配的那棵私有目录（`抱朴记忆/`，取自 `DecisionLedger.installedRoot()`），
//     **不是模型指的工作区路径**（L6 门② 白名单那一格写的就是这条理由）；
//   - 追加-only：不覆盖、不删除、不改写老行（改行等于改历史）；
//   - 缺理由／缺证据／缺类名 ⇒ **不落账**（没有理由的经验没有信息，L33 同一口径）；
//   - 坏行读时跳过（回放不许被一行脏数据整本读不出）；
//   - 写失败不抛：账是留痕不是闸门，落不上只记一行日志。
//
// 读侧有一条**硬上限**（[READ_CAP]）：只防病态文件把内存吃掉，不做时间过滤——
// "不设时间窗"是 283 判据④的本体，这一条不违反它（截的是行数，不是年份）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 判决经验账（文件实现，283 的 [JudgmentExperienceStore] 口）。
 *
 * 与内存桩的差别只有两处：真的落盘、`readAll` 从盘上读。语义（哪些行算"关"、窗口怎么数）
 * 一个字没动——那些都在 [LearnPolicy] 里，本文件只管字节。
 */
internal object JudgmentExperienceLedger : JudgmentExperienceStore {

    /** 账本文件名（与 208/意见判定/追认队列/御批同一棵目录：`抱朴记忆/`）。 */
    const val FILE_NAME = "判决经验账.jsonl"

    /** 行格式版本（改形状才 +1；加字段不算）。 */
    const val VERSION = 1

    /** 读侧硬上限（只看最近这么多行；不是时间窗）。 */
    const val READ_CAP = 20_000

    /** 内存里最多留多少条（没装配落点时的兜底；装配了也与盘上那份并集去重）。 */
    private const val MEMORY_CAP = 500

    private val memory = ArrayList<ExperienceRow>()

    /**
     * 落一行（追加）。
     *
     * 三件不齐不落：类名空／理由空／证据空——283 的行注释里写着"缺理由的行不落账"，
     * 这一条从注释挪进实现，桩与真账同一口径。
     */
    @Synchronized
    override fun append(row: ExperienceRow) {
        if (row.cardClass.isBlank() || row.reason.isBlank() || row.evidence.isEmpty()) {
            RunLog.log("抱朴经验账：行不齐（缺类/缺理由/缺证据），不落（${row.kind.wire} ${row.key}）")
            return
        }
        memory.add(row)
        if (memory.size > MEMORY_CAP) memory.removeAt(0)
        val root = root() ?: return
        runCatching {
            val f = fileIn(root)
            f.parentFile?.mkdirs()
            f.appendText(row.toJson().toString() + "\n", Charsets.UTF_8)
        }.onFailure {
            RunLog.log("抱朴经验账：没落上（${row.id}）——${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** 全本读（盘上那份 ＋ 本进程内存那份，按 id 去重）。**不设时间窗**。 */
    @Synchronized
    override fun readAll(): List<ExperienceRow> {
        val fromFile = root()?.let { r ->
            runCatching {
                val f = fileIn(r)
                if (!f.isFile) emptyList() else {
                    val lines = f.readLines(Charsets.UTF_8)
                    val capped = if (lines.size > READ_CAP) lines.takeLast(READ_CAP) else lines
                    capped.mapNotNull { parse(it) }
                }
            }.getOrDefault(emptyList())
        }.orEmpty()
        return (fromFile + memory).distinctBy { it.id }
    }

    /** 落点文件。 */
    fun fileIn(globalRoot: File): File = File(globalRoot, FILE_NAME)

    /** 一行结论（只出计数，不出行内容——体检/审计的对外面）。 */
    @Synchronized
    fun conclusion(): String {
        val rows = readAll()
        val closes = rows.count { it.kind == ExperienceKind.SIGNAL && (it.key == "signal:dismiss" || it.key == "signal:reject") }
        return "判决经验账：${rows.size} 行（类 ${rows.map { it.cardClass }.distinct().size} 个，" +
            "关 $closes 次，落点=${if (root() == null) "内存（未装配）" else FILE_NAME}）"
    }

    /** 清空（测试专用：与 274/283 的 resetForTest 同一条链路，谁用谁还）。 */
    @Synchronized
    fun resetForTest() {
        memory.clear()
    }

    // ── 行的形状 ───────────────────────────────────────────────────────────

    private fun ExperienceRow.toJson(): JSONObject = JSONObject()
        .put("v", VERSION)
        .put("id", id)
        .put("at", atMs)
        .put("kind", kind.wire)
        .put("class", cardClass)
        .put("key", key)
        .put("why", reason)
        .put("ev", JSONArray(evidence))

    /** 坏行（缺 id/缺时刻/种类认不出/证据为空）一律跳过——读历史不许被一行脏数据整本堵死。 */
    private fun parse(raw: String): ExperienceRow? {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val id = o.optString("id")
        val at = o.optLong("at", 0L)
        val kind = ExperienceKind.entries.firstOrNull { it.wire == o.optString("kind") } ?: return null
        val cls = o.optString("class")
        val ev = (o.optJSONArray("ev") ?: JSONArray()).let { a ->
            (0 until a.length()).mapNotNull { i -> a.optString(i).ifEmpty { null } }
        }
        if (id.isEmpty() || at <= 0L || cls.isEmpty() || ev.isEmpty()) return null
        return ExperienceRow(
            id = id,
            atMs = at,
            kind = kind,
            cardClass = cls,
            key = o.optString("key"),
            reason = o.optString("why"),
            evidence = ev,
        )
    }

    /** 账本落点（与 208 同一棵目录；没装配 ⇒ 只走内存）。 */
    private fun root(): File? = DecisionLedger.installedRoot()
}
