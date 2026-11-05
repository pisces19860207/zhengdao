// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.feedback

/**
 * 诊断块的唯一组装口（第四百五十二批）。
 *
 * 隐私保证是**结构性**的，不是"先拼全文再过滤"：
 * - [project] 只按 [ALLOWED_KEYS] 五个键取值，上下文里其它键（密钥、token、聊天正文、
 *   文件内容、错误原文、路径）一行都不读；读不到就根本不存在于 [DiagnosticInput] 里。
 * - 账摘要与失败摘要的数据类只有枚举名、布尔与时刻，没有自由文本字段。
 * - [line] 是第二道兜底：压平换行、限长、抹掉常见密钥形状；它不负责识别任意聊天正文，
 *   保证"正文不出现"靠的是上一条（不读），不是靠这一条（过滤）。
 *
 * 零 Android 依赖、零 IO、零时钟，JVM 直测。
 */
internal object FeedbackDiagnostics {

    /** 诊断块字符上限（派工单判据③）。 */
    const val MAX_CHARS = 2_000

    const val LEDGER_LIMIT = 5
    const val FAILURE_LIMIT = 5

    const val KEY_VERSION = "version"
    const val KEY_MODEL = "model"
    const val KEY_SYSTEM = "system"
    const val KEY_LEDGER = "ledger"
    const val KEY_FAILURE = "failure"

    /** 唯一允许进入诊断块的键，闭集。其余键一律不读。 */
    val ALLOWED_KEYS: Set<String> = setOf(
        KEY_VERSION, KEY_MODEL, KEY_SYSTEM, KEY_LEDGER, KEY_FAILURE,
    )

    private const val LINE_MAX = 80
    private const val UNKNOWN = "（未知）"
    private const val REDACTED = "<隐去>"
    private const val TRUNCATED_WORD = "已截断"

    private val SECRET_SHAPE = Regex(
        "(?i)(sk-[A-Za-z0-9_\\-]{8,}|Bearer\\s+\\S+|" +
            "(?:token|api[_-]?key|password|authorization)\\s*[=:]\\s*\\S+)",
    )

    /** 只认白名单键：把任意上下文投影成诊断块能用的形状。 */
    fun project(context: Map<String, Any?>): DiagnosticInput = DiagnosticInput(
        device = DeviceFacts(
            version = line(context[KEY_VERSION]),
            model = line(context[KEY_MODEL]),
            system = line(context[KEY_SYSTEM]),
        ),
        ledger = ledgerOf(context[KEY_LEDGER]),
        failures = failuresOf(context[KEY_FAILURE]),
    )

    /** 拼装诊断块；[cap] 是唯一出口。 */
    fun build(input: DiagnosticInput): String = cap(buildString {
            append("证道诊断块\n")
            append("版本: ").append(line(input.device.version)).append('\n')
            append("机型: ").append(line(input.device.model)).append('\n')
            append("系统: ").append(line(input.device.system)).append('\n')
            append("\n最近账摘要（只读，最多 ").append(LEDGER_LIMIT).append(" 条）:\n")
            if (input.ledger.isEmpty()) {
                append("- （无账记录）\n")
            } else {
                input.ledger.take(LEDGER_LIMIT).forEach { row ->
                    append("- ").append(row.atMs).append(' ')
                        .append(line(row.kind)).append('/').append(line(row.group))
                        .append(" 可撤销=").append(row.reversible)
                        .append(" 已回滚=").append(row.rolledBack).append('\n')
                }
            }
            append("\n最近失败原因（只给分类，不给原文）:\n")
            if (input.failures.isEmpty()) {
                append("- （无失败记录）\n")
            } else {
                input.failures.take(FAILURE_LIMIT).forEach { row ->
                    append("- ").append(row.atMs).append(' ').append(line(row.kind)).append('\n')
                }
            }
            append("\n本块不含密钥、token、聊天正文与文件内容；不会自动上传。\n")
        })

    /** 长文本统一出口：超过 [MAX_CHARS] 字截断并注「已截断」，注记本身也计入上限。 */
    fun cap(text: String): String {
        if (text.length <= MAX_CHARS) return text
        val note = "\n…（诊断块超过 $MAX_CHARS 字，$TRUNCATED_WORD）"
        return text.take((MAX_CHARS - note.length).coerceAtLeast(0)) + note
    }

    /** 第二道兜底：压平换行、抹掉常见密钥形状、限长。 */
    private fun line(raw: Any?): String {
        val text = raw as? String ?: return UNKNOWN
        val flat = text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
        val clean = SECRET_SHAPE.replace(flat, REDACTED)
        return clean.take(LINE_MAX).ifBlank { UNKNOWN }
    }

    private fun ledgerOf(raw: Any?): List<LedgerDigest> {
        val rows = raw as? List<*> ?: return emptyList()
        return rows.filterIsInstance<LedgerDigest>().take(LEDGER_LIMIT)
    }

    private fun failuresOf(raw: Any?): List<FailureDigest> {
        val rows = raw as? List<*> ?: return emptyList()
        return rows.filterIsInstance<FailureDigest>().take(FAILURE_LIMIT)
    }
}

/** 一条账摘要：枚举名 + 布尔 + 时刻，没有自由文本列。 */
data class LedgerDigest(
    val atMs: Long,
    val kind: String,
    val group: String,
    val reversible: Boolean,
    val rolledBack: Boolean,
)

/** 一条失败摘要：分类标签 + 时刻，不含错误原文。 */
data class FailureDigest(val atMs: Long, val kind: String)

/** 设备三列：版本 / 机型 / 系统。 */
data class DeviceFacts(val version: String, val model: String, val system: String)

/** 诊断块的完整输入，只有这三类字段。 */
data class DiagnosticInput(
    val device: DeviceFacts,
    val ledger: List<LedgerDigest>,
    val failures: List<FailureDigest>,
)
