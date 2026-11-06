// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零四单 · 469「外部监督」的**起居注**——值守期间每次自动动作一行
// （何时/依据/做了什么/结果），外加叫停/解除/回滚/自校验/关键通知的留证行。
// 派工单判据①：值守 30 分钟 ⇒ 起居注行数 == 实际动作数——所以 ACTION 行只由
// GuardPipeline 的执行收口（finish）这一个地方写，别处不补（多一个写口就对不上数）。
//
// 三条纪律（照 UnattendedLedger 388 的房型）：
// 1. **编解码是纯函数**：encode/parse 不摸盘，JVM 单测直接钉；
//    IO（读文件、追加、轮转）收在 [SupervisionDiary] 一个口里。
// 2. **一行一事、只增不删**：没有"改账"的口；纠错靠追加新事件。
// 3. **文本三连转义**：\\、\t、\n 先转义再落盘——字段里出现制表符/换行不能把一行撕成多行。
package com.example.zhengdao.baopu.safety

/** 起居注事件的种类（闭集；一行的第 1 格）。 */
enum class DiaryKind(val wire: String, val cn: String) {
    /** 一次自动动作执行收口（成功/失败/工具自拒都算——见 GuardPipeline.finish 的调用点）。 */
    ACTION("action", "动作"),

    /** 叫停留证（谁停的/何时/当时在做什么；469④）。 */
    STOP("stop", "叫停"),

    /** 人工解除停机。 */
    LIFT("lift", "解除"),

    /** 一次回滚执行（含回滚了哪些粒度与几件）。 */
    ROLLBACK("rollback", "回滚"),

    /** 回滚后的自校验结果（判据④「回滚后自校验落账」）。 */
    VERIFY("verify", "自校验"),

    /** 关键动作通知（469②；通知≠要人处理，只是让你知道——账上也只是行）。 */
    NOTIFY("notify", "关键通知"),

    /** 停机期拦下的动作/自动触发（防自启闸拦的每一笔都留一行）。 */
    BLOCK("block", "拦停"),
    ;

    companion object {
        /** 认不出就 null（账本自造值不静默当别的格）。 */
        fun ofWire(wire: String): DiaryKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 一条起居注（判据①的行：何时＝atMs；依据＝why；做了什么＝what；结果＝result）。
 * 每格不许带换行——落盘前已转义。
 */
data class DiaryEvent(
    val kind: DiaryKind,
    val atMs: Long,
    val why: String,
    val what: String,
    val result: String,
) {
    /** 人话一行（页面上直接显示）。 */
    val line: String get() = "${kind.cn}｜$why｜$what｜$result"
}

/** 起居注行的编解码（纯函数；列序 wire\tatMs\twhy\twhat\tresult）。 */
internal object DiaryCodec {

    fun encode(e: DiaryEvent): String =
        "${e.kind.wire}\t${e.atMs}\t${escape(e.why)}\t${escape(e.what)}\t${escape(e.result)}"

    /** 列数不对/格认不出/坏时刻 ⇒ null（坏行不硬读）。 */
    fun parse(line: String): DiaryEvent? {
        val parts = line.split('\t')
        if (parts.size != 5) return null
        val kind = DiaryKind.ofWire(parts[0]) ?: return null
        val at = parts[1].toLongOrNull() ?: return null
        return DiaryEvent(kind, at, unescape(parts[2]), unescape(parts[3]), unescape(parts[4]))
    }

    internal fun escape(text: String): String = text
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
                    else -> sb.append(c).append(next)
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
 * 起居注的唯一 IO 口（filesDir/baopu/safety/diary.jsonl；任意线程可调，内部一把锁）。
 * 写口只有一个 [append]——判据①的行数对账靠它不多头。
 */
object SupervisionDiary {

    private const val DIR = "baopu/safety"
    private const val FILE = "diary.jsonl"

    /** 超 1MB 轮转成 diary.1（只留一代——账要能翻，但不许无界长）。 */
    private const val ROTATE_BYTES: Long = 1L shl 20

    /** 追加一条（时刻由调用方给，本层不读钟）。 */
    fun append(ctx: android.content.Context, event: DiaryEvent) {
        synchronized(this) {
            runCatching {
                val dir = java.io.File(ctx.filesDir, DIR).apply { mkdirs() }
                val file = java.io.File(dir, FILE)
                if (file.length() >= ROTATE_BYTES) {
                    val gen = java.io.File(dir, "$FILE.1")
                    if (gen.exists()) gen.delete()
                    file.renameTo(gen)
                }
                file.appendText(DiaryCodec.encode(event) + "\n")
            }
        }
    }

    /** 全量读回（坏行跳过——账上留了坏行，但不许它挡住好行）。 */
    fun readAll(ctx: android.content.Context): List<DiaryEvent> = synchronized(this) {
        runCatching {
            val file = java.io.File(java.io.File(ctx.filesDir, DIR), FILE)
            if (!file.exists()) return emptyList()
            file.readLines().mapNotNull { DiaryCodec.parse(it) }
        }.getOrDefault(emptyList())
    }

    /** 某时刻之后的行（值守期对账的取数口）。 */
    fun readSince(ctx: android.content.Context, sinceMs: Long): List<DiaryEvent> =
        readAll(ctx).filter { it.atMs >= sinceMs }
}
