// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百三十六批 B · 预读回执闸：**存账层**（`抱朴记忆/预读回执.jsonl`）。
//
// 为什么两张纸必须进同一本账（派工单 ④／用户裁定）：账要回答的是
// **"它当时怎么理解的"**——翻到回执却不知道当时挂的是哪张必读清单，或反过来，
// 都等于没留痕。所以任务单、回执、拦/放、收工对照四类事件同键同本，一次回放到底。
//
// 房型照 274/277/278/298（同一条链路的四本账，`JudgmentExperienceLedger` 是最近的一本）：
//   · 落点＝208 已装配的那棵私有目录（`抱朴记忆/`，取自 `DecisionLedger.installedRoot()`），
//     **不是模型指的工作区路径**——L6 门② 白名单那一格写的就是这条理由；
//   · 追加-only：不覆盖、不删除、不改写老行（改行＝改历史）；
//   · 坏行读时跳过（一行脏数据不许把整本账堵死）；
//   · 写失败不抛：账是留痕不是闸门，落不上只 log 一行；
//   · 没装配落点 ⇒ 只走内存（纯 JVM 直测与早期启动都安全，与 298 同一兜底）。
package com.example.zhengdao.baopu.preread

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 账上一行的种类（闭集；六个事件正好覆盖判据①②③④各自要留的痕）。 */
internal enum class PreReadEventKind(val wire: String, val cn: String) {
    /** 派单侧附了必读清单（判据①）。 */
    ORDER("order", "任务单"),

    /** 承接侧交了的回执三行（判据②的"交了"）。 */
    RECEIPT("receipt", "回执"),

    /** 长程/跨会话自动要求回执（"要求"的第二条来源，账上要看得见来源）。 */
    ARM("arm", "自动要求"),

    /** 态①：本任务未要求预读（一个任务最多一行，不刷屏）。 */
    SILENT("silent", "未要求预读"),

    /** 拦住一笔写（判据①的"报错拦住"）。 */
    BLOCK("block", "拦住"),

    /** 交了之后第一次放行（判据②的"交了 ⇒ 放行"）。 */
    PASS("pass", "放行"),

    /** 收工对照（判据③：说改未改／清单外改动；不阻断，只落账）。 */
    CLOSEOUT("closeout", "收工对照"),

    /** 闸自己出问题了 ⇒ 放行并记账（判据⑥「不许拦死主流程」的留痕）。 */
    FAIL_OPEN("fail_open", "异常放行"),

    /** 承接侧声明"本任务不要求回执"（那句可操作提示给的第二条出口）。 */
    DECLARED("declared", "声明不要求"),
    ;

    companion object {
        fun ofWire(wire: String): PreReadEventKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** 账上一行。[text] 是一句话（必有），[detail] 是逐条正文（清单条目／回执三行／越界项）。 */
internal data class PreReadRow(
    val id: String,
    val atMs: Long,
    val taskKey: String,
    val kind: PreReadEventKind,
    val text: String,
    val detail: List<String> = emptyList(),
)

/** 存取口（内存版与文件版同一语义；拆接口只为 JVM 直测不碰盘）。 */
internal interface PreReadStore {
    fun append(row: PreReadRow)
    fun readAll(): List<PreReadRow>
}

/** SQL 之外的一本 JSONL（常量集中在 [PreReadFields] 一处的意义：老行字段名改不动）。 */
internal object PreReadFields {
    const val V = "v"
    const val ID = "id"
    const val AT = "at"
    const val TASK = "task"
    const val KIND = "kind"
    const val TEXT = "text"
    const val DETAIL = "detail"
}

/**
 * 预读回执账（文件实现）。
 *
 * 读侧有硬上限（[READ_CAP]）：只防病态文件把内存吃掉，不做时间过滤——
 * 判据④要的是"当初那一条"，截年份就没法回看老任务。
 */
internal object PreReadLedger : PreReadStore {

    /** 账本文件名（与 208/意见判定/追认队列/御批/判决经验同一棵目录：`抱朴记忆/`）。 */
    const val FILE_NAME = "预读回执.jsonl"

    /** 行格式版本（改形状才 +1；加字段不算）。 */
    const val VERSION = 1

    /** 读侧硬上限（只看最近这么多行；不是时间窗）。 */
    const val READ_CAP = 20_000

    /** 内存里最多留多少条（没装配落点时的兜底；装配了也与盘上那份并集去重）。 */
    private const val MEMORY_CAP = 2_000

    private val memory = ArrayList<PreReadRow>()

    /**
     * 落一行（追加）。缺键／缺时刻／种类认不出／没正文 ⇒ 不落（写了也回放不出来，
     * 与其攒一堆读不懂的行，不如当场 log 一句）。
     */
    @Synchronized
    override fun append(row: PreReadRow) {
        if (row.taskKey.isBlank() || row.atMs <= 0L || row.text.isBlank()) {
            RunLog.log("抱朴预读账：行不齐（缺任务/缺时刻/缺正文），不落（${row.kind.wire}）")
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
            RunLog.log("抱朴预读账：没落上（${row.id}）——${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** 全本读（盘上那份 ＋ 本进程内存那份，按 id 去重，保持时刻顺序）。 */
    @Synchronized
    override fun readAll(): List<PreReadRow> {
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

    /** 某个任务的全部行（时间正序）——判据④"回看"的取数口。 */
    @Synchronized
    fun rowsFor(taskKey: String): List<PreReadRow> =
        readAll().filter { it.taskKey == taskKey }.sortedBy { it.atMs }

    /** 落点文件。 */
    fun fileIn(globalRoot: File): File = File(globalRoot, FILE_NAME)

    /** 一行结论（体检/审计的对外面：只出计数，不出行内容）。 */
    @Synchronized
    fun conclusion(): String {
        val rows = readAll()
        val blocked = rows.count { it.kind == PreReadEventKind.BLOCK }
        return "预读回执账：${rows.size} 行（任务 ${rows.map { it.taskKey }.distinct().size} 个，" +
            "拦住 $blocked 笔，落点=${if (root() == null) "内存（未装配）" else FILE_NAME}）"
    }

    /** 清空（测试专用：谁用谁还，与 298 同一条链路）。 */
    @Synchronized
    fun resetForTest() {
        memory.clear()
    }

    // ── 行的形状 ───────────────────────────────────────────────────────────

    private fun PreReadRow.toJson(): JSONObject = JSONObject()
        .put(PreReadFields.V, VERSION)
        .put(PreReadFields.ID, id)
        .put(PreReadFields.AT, atMs)
        .put(PreReadFields.TASK, taskKey)
        .put(PreReadFields.KIND, kind.wire)
        .put(PreReadFields.TEXT, text)
        .put(PreReadFields.DETAIL, JSONArray(detail))

    /** 坏行（缺 id/缺时刻/缺任务/种类认不出/正文为空）一律跳过——回放不许被一行脏数据整本堵死。 */
    private fun parse(raw: String): PreReadRow? {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val id = o.optString(PreReadFields.ID)
        val at = o.optLong(PreReadFields.AT, 0L)
        val task = o.optString(PreReadFields.TASK)
        val kind = PreReadEventKind.ofWire(o.optString(PreReadFields.KIND)) ?: return null
        val text = o.optString(PreReadFields.TEXT)
        val detail = (o.optJSONArray(PreReadFields.DETAIL) ?: JSONArray()).let { a ->
            (0 until a.length()).mapNotNull { i -> a.optString(i).ifEmpty { null } }
        }
        if (id.isEmpty() || at <= 0L || task.isEmpty() || text.isEmpty()) return null
        return PreReadRow(id = id, atMs = at, taskKey = task, kind = kind, text = text, detail = detail)
    }

    /** 账本落点（与 208 同一棵目录；没装配 ⇒ 只走内存）。 */
    private fun root(): File? = DecisionLedger.installedRoot()
}
