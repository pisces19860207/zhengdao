// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十一批 · 待办中心的**落盘口**（全仓唯一碰 `产物/.todo.jsonl` 的地方）。
//
// 落点 `<工作区>/产物/.todo.jsonl`（用户点名的路径）——点号开头，理由只有一条且是硬的：
// `ReflectionRunner.isCountedProduct` 把点号开头的文件排除在「产物 N 件」之外
// （与 322 小黑板 `产物/.board.md`、512 游标同一口径）。待办不是产物，读数里多一件就是账不实。
//
// 三条边界：
//   1. **append-only**：只追加，不改历史行、不删行。删除＝追加一条 `confirm`，
//      看板上由 [TodoRules.fold] 的 replay 决定"还露不露面"。这样「账本保留一行」不是靠
//      某人记得备份，而是**结构上删不掉**（判据④）。
//   2. **写盘先写 `.tmp` 再改名的思路不适用**：追加一行是原子的单次 write，半行最坏只是
//      末尾坏一行的 JSON（读侧跳过坏行不崩，[rows] 里就是这条口径），不会把整本账写坏。
//   3. **不认识 Android**：只吃 `java.io.File` + `org.json`，JVM 单测直接打（同 `BoardStore` 房型）。
package com.example.zhengdao.baopu.todo

import com.example.zhengdao.baopu.MemoryLayout
import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONObject

/** 一次写入的结果（成功给条目；失败给**人话**，调用方原样回灌给模型/界面）。 */
internal sealed class TodoWrite {
    data class Ok(val item: TodoItem, val created: Boolean = false) : TodoWrite()

    data class Refused(val reason: String) : TodoWrite()
}

/** 待办账本的 IO 壳（判据全在 [TodoRules]，验收在 [TodoVerify]）。 */
internal object TodoStore {

    /** 稳定键：点号开头 ⇒ 不计入「产物 N 件」。改这个名字等于换一本账。 */
    const val FILE_NAME: String = ".todo.jsonl"

    /** 落点：`<工作区>/产物/.todo.jsonl`。 */
    fun fileOf(wsRoot: File): File = File(MemoryLayout.productsDir(wsRoot), FILE_NAME)

    /**
     * 读全本账（不存在 ⇒ 空表：没有待办是合法状态，不是错误）。
     *
     * 坏行**跳过不崩**：追加写被打断时最后一行可能是半截 JSON，那一行不算数，
     * 但前面的行照读——整块看板不该因为一行坏字节变成"读不出"。
     */
    fun rows(wsRoot: File): List<TodoRow> {
        val f = fileOf(wsRoot)
        if (!f.isFile) return emptyList()
        val lines = runCatching { f.readLines(Charsets.UTF_8) }.getOrElse {
            RunLog.log("待办账读不出：${it.javaClass.simpleName}")
            return emptyList()
        }
        return lines.mapNotNull { parse(it) }
    }

    /** 折成看板（含已删除的条目；要"还留着的"用 [TodoRules.live]）。 */
    fun items(wsRoot: File): List<TodoItem> = TodoRules.fold(rows(wsRoot))

    /** 收藏起态那一行要的两个数。 */
    fun counts(wsRoot: File): TodoCounts = TodoRules.counts(items(wsRoot))

    /**
     * 追加一行（唯一写口）。
     *
     * @return false ＝没写成（盘上原有内容一个字节没动），调用方必须如实说"没记上"
     */
    fun append(wsRoot: File, row: TodoRow): Boolean = runCatching {
        val f = fileOf(wsRoot)
        val dir = f.parentFile ?: return@runCatching false
        if (!dir.isDirectory && !dir.mkdirs()) return@runCatching false
        // 那个位置上要是躺着一件不是普通文件的东西（同名目录之类），一律不动它、如实失败
        if (f.exists() && !f.isFile) return@runCatching false
        f.appendText(row.toJson().toString() + "\n", Charsets.UTF_8)
        true
    }.getOrElse {
        RunLog.log("待办账写失败：${it.javaClass.simpleName}")
        false
    }

    /** 造一行（[TodoStore] 内与工具层共用，字段口径只此一处）。 */
    fun row(
        op: TodoOp,
        id: String,
        who: TodoActor,
        atMs: Long,
        text: String = "",
        source: String = "",
        verdict: String = "",
        reason: String = "",
    ): TodoRow = TodoRow(
        op = op,
        id = id,
        who = who,
        atMs = atMs,
        text = text,
        source = source,
        verdict = verdict,
        reason = TodoRules.oneLine(reason),
    )

    /**
     * 立一条待办（模型调 `todo_add`）。
     *
     * 同摘要且**还没从看板上消失** ⇒ 不重复立，回既有那条（幂等：模型每轮记一遍同一件事
     * 不会把看板刷成一堆重复行）。
     */
    fun add(wsRoot: File, rawText: String, sourceId: String, nowMs: Long): TodoWrite {
        TodoRules.refusal(rawText)?.let { return TodoWrite.Refused(it) }
        val text = TodoRules.normalize(rawText)
        val all = items(wsRoot)
        TodoRules.live(all).firstOrNull { it.text == text }?.let { return TodoWrite.Ok(it, created = false) }
        val rows = rows(wsRoot)
        val id = TodoRules.nextId(rows)
        // 574：来源空就落 null（行里不写 source 键）——不再造「未注明」占位号；
        // 旧行里已有的「未注明」不回填、不迁移，照读照列。
        val source = TodoRules.oneLine(sourceId)
        if (!append(wsRoot, row(TodoOp.ADD, id, TodoActor.MODEL, nowMs, text = text, source = source))) {
            return TodoWrite.Refused("待办账写不进去（盘上原有内容没动）——如实说「没记上」，别当成记上了。")
        }
        val item = TodoRules.fold(rows(wsRoot)).first { it.id == id }
        RunLog.log("todo_add: $id 立起来（${TodoRules.charCount(text)} 字）")
        return TodoWrite.Ok(item, created = true)
    }

    /**
     * **用户点的那一下**（只由界面调用；模型永远走不到这里）。
     *
     * 判据⑤的两条硬边界都在 [TodoRules.confirmRefusal] 里：只有用户、且必须已到「已完成」。
     * 过闸之后追加一行 `confirm`——**不删任何历史行**（判据④：账本仍有那一行）。
     */
    fun confirm(wsRoot: File, id: String, actor: TodoActor, nowMs: Long): TodoWrite {
        val item = items(wsRoot).firstOrNull { it.id == id }
        TodoRules.confirmRefusal(item, actor)?.let { return TodoWrite.Refused(it) }
        val target = item ?: return TodoWrite.Refused("没有这条待办（id 不对）。")
        if (!append(wsRoot, row(TodoOp.CONFIRM, id, TodoActor.USER, nowMs))) {
            return TodoWrite.Refused("待办账写不进去——看板上它还在（没删成）。")
        }
        RunLog.log("todo_confirm: $id 用户确认删除（账本保留该条全部历史行）")
        return TodoWrite.Ok(target.copy(confirmedAtMs = nowMs))
    }

    /** 解析一行（坏行 ⇒ null；见 [rows] 的口径）。 */
    private fun parse(line: String): TodoRow? {
        val raw = line.trim()
        if (raw.isEmpty()) return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val op = TodoOp.ofWire(o.optString("op")) ?: return null
        val who = TodoActor.ofWire(o.optString("who")) ?: return null
        val id = o.optString("id")
        if (id.isEmpty()) return null
        return TodoRow(
            op = op,
            id = id,
            who = who,
            atMs = o.optLong("at", 0L),
            text = o.optString("text"),
            source = o.optString("source"),
            verdict = o.optString("verdict"),
            reason = o.optString("reason"),
        )
    }
}

/** 一行 → JSON（键名固定；少写一个键就是历史读不回来，不走"默认值兜"）。 */
internal fun TodoRow.toJson(): JSONObject {
    val o = JSONObject()
        .put("v", TodoRules.VERSION)
        .put("op", op.wire)
        .put("id", id)
        .put("who", who.wire)
        .put("at", atMs)
    if (text.isNotEmpty()) o.put("text", text)
    if (source.isNotEmpty()) o.put("source", source)
    if (verdict.isNotEmpty()) o.put("verdict", verdict)
    if (reason.isNotEmpty()) o.put("reason", reason)
    return o
}
