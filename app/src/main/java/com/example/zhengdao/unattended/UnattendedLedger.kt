// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百八十八单 · 离场值守的**事件账**（JSONL 一行一事，只增不删）。
// 离场报告（[UnattendedReport]）与"被中断要补报"都从这本账算——账不记全，报告就是编的。
//
// 三条纪律：
// 1. **编解码是纯函数**：[encodeLine]／[parseLine] 不摸盘，JVM 单测直接钉；
//    IO（读文件、追加、轮转）收在 [UnattendedLedger] 一个口里。
// 2. **一行一事、只增不删**：没有"改账"的口；纠错靠追加一条新事件（照账本的老规矩）。
// 3. **文本三连转义**：text 里的 \\、\t、\n 先转义再落盘——正文里出现制表符/换行
//    不能把一行账撕成三行（撕了的那两行就是解析事故）。
package com.example.zhengdao.unattended

/** 值守事件的种类（闭集；报告的分节按这几格数，一个不自造）。 */
internal enum class UnattendedEventKind(val wire: String, val cn: String) {
    ARM("arm", "开值守"),
    DISARM("disarm", "收值守"),
    QUEUE("queue", "圈外入队"),
    STOP("stop", "红线停手"),
    PAUSE("pause", "环境暂停"),
    RESUME("resume", "环境复工"),
    DEGRADE("degrade", "降级停手"),
    SUMMARY("summary", "B级汇总"),
    REPORT("report", "离场报告"),
    INTERRUPT("interrupt", "中断补报"),
    ;

    companion object {
        /** 认不出就 null（账本自造值不静默当别的格）。 */
        fun ofWire(wire: String): UnattendedEventKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** 一条值守事件（[atMs] 是事件时刻；[text] 是一行人话，不许带换行——落盘前已转义）。 */
internal data class UnattendedEvent(
    val kind: UnattendedEventKind,
    val atMs: Long,
    val text: String,
)

internal object UnattendedEventCodec {

    /** 行格式：`wire<TAB>atMs<TAB>text`，text 三连转义。 */
    fun encode(event: UnattendedEvent): String =
        "${event.kind.wire}\t${event.atMs}\t${escape(event.text)}"

    /** 认不出的格／坏时刻／空行 ⇒ null（不许把坏行硬读成别的事件）。 */
    fun parse(line: String): UnattendedEvent? {
        val parts = line.split('\t')
        if (parts.size != 3) return null
        val kind = UnattendedEventKind.ofWire(parts[0]) ?: return null
        val at = parts[1].toLongOrNull() ?: return null
        return UnattendedEvent(kind, at, unescape(parts[2]))
    }

    private fun escape(text: String): String = text
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")

    private fun unescape(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (val next = text[i + 1]) {
                    't' -> sb.append('\t')
                    'n' -> sb.append('\n')
                    '\\' -> sb.append('\\')
                    else -> {
                        sb.append(c).append(next)
                    }
                }
                i += 2
            } else {
                sb.append(c)
                i += 1
            }
        }
        return sb.toString()
    }
}

/**
 * 事件账的唯一 IO 口（filesDir/unattended/events.jsonl；全部方法可在任意线程调，
 * 内部一把锁——服务心跳、看门狗、引擎弹卡闸都可能来记一笔）。
 */
internal object UnattendedLedger {

    private const val DIR = "unattended"
    private const val FILE = "events.jsonl"

    /** 超过 1MB 轮转成 events.1（只留一代——账要能翻，但不许无界长）。 */
    private const val ROTATE_BYTES: Long = 1L shl 20

    /** 追加一条（调用方顺序无保证；时刻由调用方给，本层不读钟）。 */
    fun append(ctx: android.content.Context, event: UnattendedEvent) {
        synchronized(this) {
            runCatching {
                val dir = java.io.File(ctx.filesDir, DIR).apply { mkdirs() }
                val file = java.io.File(dir, FILE)
                if (file.length() >= ROTATE_BYTES) {
                    val gen = java.io.File(dir, "$FILE.1")
                    if (gen.exists()) gen.delete()
                    file.renameTo(gen)
                }
                file.appendText(UnattendedEventCodec.encode(event) + "\n")
            }
        }
    }

    /** 全量读回（坏行跳过——账上留了坏行，但不许它挡住好行）。 */
    fun readAll(ctx: android.content.Context): List<UnattendedEvent> = synchronized(this) {
        runCatching {
            val file = java.io.File(java.io.File(ctx.filesDir, DIR), FILE)
            if (!file.exists()) return emptyList()
            file.readLines().mapNotNull { UnattendedEventCodec.parse(it) }
        }.getOrDefault(emptyList())
    }

    /** 某时刻之后的事件（报告与汇总的取数口）。 */
    fun readSince(ctx: android.content.Context, sinceMs: Long): List<UnattendedEvent> =
        readAll(ctx).filter { it.atMs >= sinceMs }
}
