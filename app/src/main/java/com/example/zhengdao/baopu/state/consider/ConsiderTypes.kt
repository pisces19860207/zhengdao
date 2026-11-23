// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百四十四批 · ConsiderLog 覆盖扩展（批次 1）：记录类型。
// 基线 origin/main —— 与 811 试点（feat/consider-log，未合）同语义但不 import 它：
// 落点按派工单取 baopu/state/consider/，四字段同口径，另加 sampledFrom/strategy 两扩展字段。
// 记录层不懂业务语义：原因码由产生器映射，本包只存、只查、只导出。
package com.example.zhengdao.baopu.state.consider

/** 原因码闭集（与 811 试点同六码，便于日后归一；不许自由文本）。 */
internal enum class ConsiderVerdict {
    /** 过了本层筛选（入池不等于被采用，只是没被这层砍）。 */
    ACCEPTED,

    /** 同批重复（先见先立）。 */
    DUPLICATE,

    /** 没到产生器的门槛。 */
    BELOW_BAR,

    /** 过了形态门槛但没过校验。 */
    INVALID,

    /** 合格但超出上限（截断必留痕，不许静默丢）。 */
    OVER_LIMIT,

    /** 被策略层挡住。 */
    BLOCKED,
}

/** 产生器降采样策略：wire 值即判据②钉死的四态。 */
internal enum class ConsiderStrategy(val wire: String) {
    FIRST_K("firstK"),
    STRIDE("stride"),
    RESERVOIR("reservoir"),
    DIAGNOSTIC_ONLY("diagnosticOnly"),
    ;

    companion object {
        /** 闭集 wire 字面量，供 JSONL 自证与负例检查。 */
        val WIRES: Set<String> = entries.map { it.wire }.toSet()

        fun fromWire(wire: String): ConsiderStrategy = entries.first { it.wire == wire }
    }
}

/** 一条留痕：四字段（产生器/输入摘要/原因码/时刻）＋两扩展字段（sampledFrom/strategy）。 */
internal data class ConsiderRecord(
    val generatorId: String,
    val inputDigest: String,
    val verdict: ConsiderVerdict,
    val atMs: Long,
    val sampledFrom: Int,
    val strategy: ConsiderStrategy,
)

/** 输入摘要：只留复盘线索，不留全文。double 按千分位取整再拼，避开 Locale/格式化差异（确定性）。 */
internal fun digestOf(angles: List<Double>, limit: Int = DIGEST_LIMIT): String {
    val flat = angles.joinToString(",") { (it * 1000.0).toLong().toString() }
    return if (flat.length <= limit) flat else flat.take(limit) + "…"
}

/** 最小 JSON 字符串转义（引号／反斜杠／控制字符；中文原样输出）。 */
internal fun jsonEscape(s: String): String = buildString(s.length + 8) {
    for (c in s) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> append(c)
        }
    }
}

/** 一条记录的 JSONL 行：键序固定，便于逐字节比对与外部工具消费。 */
internal fun ConsiderRecord.toJsonLine(): String = buildString {
    append("{\"generatorId\":\"").append(jsonEscape(generatorId)).append("\",")
    append("\"inputDigest\":\"").append(jsonEscape(inputDigest)).append("\",")
    append("\"verdict\":\"").append(verdict.name).append("\",")
    append("\"atMs\":").append(atMs).append(',')
    append("\"sampledFrom\":").append(sampledFrom).append(',')
    append("\"strategy\":\"").append(strategy.wire).append("\"}")
}

/** 诊断文件命名（纯字符串，不读时钟；日期由调用方/壳层给）。 */
internal fun diagFileName(date: String): String = "consider-$date-diag.jsonl"

/** 主池导出文件命名（同上）。 */
internal fun mainFileName(date: String): String = "consider-$date.jsonl"

internal const val DIGEST_LIMIT = 40
