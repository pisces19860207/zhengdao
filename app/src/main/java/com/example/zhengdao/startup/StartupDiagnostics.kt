// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.startup

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 「装配失败诊断」的正文与落盘（**纯函数层**：除 [export] 里那一次写文件外不碰 Android）。
 *
 * ## 落点与命名（629 判据③）
 *
 * `<工作区>/产物/启动失败-<yyyyMMdd-HHmmss>.txt`
 *
 * 为什么放 `产物/`：抱朴已经有这个约定（进化档案、整理回执都在这间屋），用户找得到、
 * 我们排障也找得到；`产物/` 还会随工作区走 —— 换了工作区目录，诊断也跟着换地方，
 * 不会像 `filesDir` 那样人根本捞不出来。
 *
 * ## 「含栈、不含密钥/正文」（判据②）
 *
 * 两条独立的保证，缺一不可：
 *   1. **白名单入参**：正文只由 [StartupFacts] 与 [StartupFailure] 拼成 —— 这两张表里
 *      没有会话正文、没有账本内容、没有密钥字段。写不出来的东西不会漏。
 *   2. **逐行脱敏**：异常消息里可能被夹带私货（例如 `json: {"token":"sk-…"}`），
 *      所以每一行落盘前都要过 `sanitize`（生产接的是 `RunLog.redact`，与"导出日志"
 *      同一个脱敏器，不是第二份规则），再过 [MAX_LINE_CHARS] 截断 —— 截断挡的是
 *      "整段请求体被塞进 message"这一类越界，不是美观问题。
 */
internal object StartupDiagnostics {

    /** 落盘目录名（与抱朴的 `产物/` 同一间屋，见文件头）。 */
    const val DIR_NAME = "产物"

    /** 文件名前缀（判据③逐字钉着这个名字）。 */
    const val FILE_PREFIX = "启动失败"

    /** 每行最多留多少字符：挡的是"整段 JSON/请求体被塞进异常消息"。 */
    const val MAX_LINE_CHARS = 240

    private const val SUFFIX = ".txt"

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val HUMAN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** 文件名里的时间戳（本地时区，秒级）。 */
    fun stamp(atMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        STAMP.format(Instant.ofEpochMilli(atMillis).atZone(zone))

    /** `启动失败-<stamp>.txt`。 */
    fun fileName(stamp: String): String = "$FILE_PREFIX-$stamp$SUFFIX"

    /** 人读的时刻（诊断正文里用；[atMillis] 为 null ⇒ 占位符）。 */
    fun humanTime(atMillis: Long?, zone: ZoneId = ZoneId.systemDefault()): String =
        atMillis?.let { HUMAN.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "—"

    /**
     * 诊断正文。
     *
     * @param sanitize 逐行脱敏器（生产＝`RunLog.redact`；单测传 `{ it }` 或假脱敏器）
     */
    fun body(
        facts: StartupFacts,
        failure: StartupFailure,
        sanitize: (String) -> String = { it },
        /**
         * 渲染时区。生产不传（＝系统默认时区：诊断给人看，用用户手机上的时刻才对）；
         * 单测显式传固定时区，否则 CI 的 UTC 与本机的 +08:00 会渲染出不同的钟点
         * （ci #1988 / emit #139：期望 14:05、实际 06:05，两例红都是这个）。
         */
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val sb = StringBuilder()
        sb.appendLine("证道 · 启动失败诊断")
        sb.appendLine("生成时间：${humanTime(facts.nowMillis, zone)}")
        sb.appendLine("———————————————————————————————")
        sb.appendLine("失败类型：${failure.type}")
        sb.appendLine("完整类名：${failure.typeFull}")
        sb.appendLine("消息：${failure.message ?: "（无）"}")
        sb.appendLine("发生时刻：${humanTime(failure.atMillis, zone)}")
        sb.appendLine("— 版本 —")
        sb.appendLine("版本名：${facts.versionName}")
        sb.appendLine("commit：${facts.gitSha}")
        sb.appendLine("构建时刻：${facts.buildTime}")
        sb.appendLine("— 机型 —")
        sb.appendLine("厂商：${facts.manufacturer}")
        sb.appendLine("型号：${facts.model}")
        sb.appendLine("系统：Android ${facts.androidRelease}（API ${facts.sdkInt}）")
        sb.appendLine("— 上次成功启动 —")
        sb.appendLine(
            if (facts.lastOkAtMillis == null) {
                "从没有成功启动过（首次启动就失败）"
            } else {
                "时刻：${humanTime(facts.lastOkAtMillis, zone)}（版本 ${facts.lastOkVersion ?: "未知"}）"
            },
        )
        sb.appendLine("— 栈 —")
        failure.stack.forEach { sb.appendLine(it) }
        // trimEnd：StringBuilder 收尾那个换行会被 lineSequence() 留成一条空串，正文就会多一行空行。
        // 正文的行数是要对账的（骨架 + 栈帧，见 StartupDiagnosticsTest 的结构判据），不许有虚行。
        return sb.toString().trimEnd('\n').lineSequence().joinToString("\n") { clip(sanitize(it)) }
    }

    /** 单行脱敏 + 截断（顺序不能反：先脱敏再裁，否则裁断的密钥会漏半截）。 */
    fun clip(line: String): String =
        if (line.length <= MAX_LINE_CHARS) line else line.take(MAX_LINE_CHARS) + "…（已截断）"

    /**
     * 落盘并返回文件（**幂等**：同一时刻的那一份已存在 ⇒ 直接复用，不重复写）。
     *
     * 为什么要幂等：失败时 `StartupGate` 会先自动落一份（哪怕用户没点"导出诊断"，
     * 证据也已经在盘上了），错误页上的按钮再点一次不该产出第二份 —— 两份名字只差
     * 几秒的诊断文件，排障时最先浪费掉的是人的时间。
     *
     * @param write 注入的写口（默认走真实磁盘；单测传收集器，就能在 JVM 上验文件名与正文）
     */
    fun export(
        dir: File,
        facts: StartupFacts,
        failure: StartupFailure,
        sanitize: (String) -> String = { it },
        zone: ZoneId = ZoneId.systemDefault(),
        stamp: String = stamp(failure.atMillis, zone),
        write: (File, String) -> Unit = ::writeToDisk,
    ): File {
        val file = File(dir, fileName(stamp))
        if (file.isFile) return file
        write(file, body(facts, failure, sanitize, zone))
        return file
    }

    /** 默认写口：先建目录再整篇写（诊断文件很小，一次写完不留半截）。 */
    private fun writeToDisk(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }
}
