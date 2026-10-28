// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：262 批第二步（工单原文「预约式：巡按排队，检测到用户已切到玄览时顺手跑
// 需要截图的项（用户没感觉）」）。落盘与账本形态照 ShadowLedger 同款（globalRoot
// 下 jsonl 追加、坏行跳过）；无 Android 依赖 ⇒ JVM 直测。
package com.example.zhengdao.baopu.inspect

import java.io.File
import org.json.JSONObject

/**
 * 预约式视觉证据队列（262 批第二步）。
 *
 * 本批交付的是**队列＋触发机制**：`BaopuBrowserWiring.install` 把
 * [com.example.zhengdao.ui.browser.BrowserHolder.browserShownListener] 接到 [drain]——
 * 玄览上屏那一刻投后台线程静默补拍（拍的是**当下**的活跃页），结果逐条落
 * [FILE_NAME] 账。谁往里塞（巡按的哪条检查产「需要截图」的项）是后续批的事——
 * 239 巡按纯吃日志账本，加页面证据源要先有检查消费口，另行开工。
 *
 * 为什么拍当下页而不是入队时的页：切回原页签的滚动/加载时序不可控，v1 取
 * 「上屏那一刻玄览正显示什么就拍什么」，理由里写明想要什么——**如实**，不假装
 * 补到的是当时的页。
 */
internal object VisualEvidenceQueue {

    /** 一条待补拍项（v1 只带理由）。 */
    data class Item(val reason: String, val enqueuedAtMs: Long)

    /** 一次补拍的结局（成功带相对路径、失败带原因——不许静默空）。 */
    data class Done(
        val reason: String,
        val enqueuedAtMs: Long,
        val atMs: Long,
        val shotRel: String?,
        val error: String?,
    )

    private val items = ArrayDeque<Item>()

    /** 账本文件（globalRoot 同 ShadowLedger/PatrolLedger 的根：全局记忆目录）。 */
    const val FILE_NAME: String = "页面证据.jsonl"

    /** 队列上限：塞爆丢最旧的（取证是尽力而为，不是义务——不许把内存拖成账本）。 */
    const val MAX_QUEUE: Int = 20

    /** 入队一条；空理由不收。 */
    @Synchronized
    fun enqueue(reason: String, nowMs: Long = System.currentTimeMillis()) {
        if (reason.isBlank()) return
        items.addLast(Item(reason.trim().take(200), nowMs))
        while (items.size > MAX_QUEUE) items.removeFirst()
    }

    /** 现存待拍项（快照）。 */
    @Synchronized
    fun pending(): List<Item> = items.toList()

    /**
     * 玄览上屏时补拍：**取走全部**（持锁）、锁外逐个执行——shoot 里是桥命令
     * （`captureShotRef` 系），调用方必须投后台线程（主线程直调＝onMain 自等死锁）。
     *
     * @param shoot 真身注入：拍一张，返回 [Done]（成功/失败都是 Done）
     * @param append 账本落盘注入（生产传 [appendDone] 的偏函数；测试用内存假账）
     */
    fun drain(shoot: (Item) -> Done, append: (Done) -> Boolean) {
        val batch = synchronized(this) {
            val b = items.toList()
            items.clear()
            b
        }
        batch.forEach { one -> runCatching { append(shoot(one)) } }
    }

    // ── 账本（jsonl；形态照 ShadowLedger：追加/现读/坏行跳过） ────────────────

    fun fileIn(globalRoot: File): File = File(globalRoot, FILE_NAME)

    fun appendDone(globalRoot: File, d: Done): Boolean = runCatching {
        val f = fileIn(globalRoot)
        f.parentFile?.mkdirs()
        f.appendText(toJson(d).toString() + "\n", Charsets.UTF_8)
        true
    }.getOrDefault(false)

    fun toJson(d: Done): JSONObject = JSONObject()
        .put("reason", d.reason)
        .put("enqueued_at", d.enqueuedAtMs)
        .put("at", d.atMs)
        .put("shot", d.shotRel ?: "")
        .put("error", d.error ?: "")

    fun parseLine(raw: String): Done? = runCatching {
        val o = JSONObject(raw)
        Done(
            reason = o.optString("reason"),
            enqueuedAtMs = o.optLong("enqueued_at", 0L),
            atMs = o.optLong("at", 0L),
            shotRel = o.optString("shot").ifBlank { null },
            error = o.optString("error").ifBlank { null },
        )
    }.getOrNull()

    /** 现读（新→旧；上限 [limit]）。 */
    fun read(globalRoot: File, limit: Int = 100): List<Done> = runCatching {
        val f = fileIn(globalRoot)
        if (!f.isFile) return@runCatching emptyList()
        f.readLines(Charsets.UTF_8).asSequence()
            .mapNotNull { parseLine(it) }
            .sortedByDescending { it.atMs }
            .take(limit)
            .toList()
    }.getOrDefault(emptyList())
}
