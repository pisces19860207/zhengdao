// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百一十一批 · 候选留痕（试点版）：通用件 `ConsiderLog`。
//
// 背景（790 实跑咬出来的原话）：「细化结果从未入库」——候选算完就丢，既白烧算力、
// 又无法复盘。这不是 790 独有：任何「生成候选再筛选」的链路都会犯。本件只做**留痕**，
// 不参与判定、不打分、不排序、不改任何选优逻辑（纯旁路）。
//
// 入池的是**每一个候选，含被否决的**（判据①）；四要素固定为 产生器 id／输入摘要／
// 否决原因码（枚举，非自由文本）／时刻（判据②）；滚动保留 + 淘汰计数，不许静默截断
// （判据③）；可按产生器／原因码／时间窗查询，可导出 JSONL。
package com.example.zhengdao.baopu.consider

import java.io.File

/**
 * 否决原因码（**闭集枚举**——判据②：原因码不许自由文本）。
 *
 * 语义只描述「这个候选在这一层的去向」，不描述产生器的领域细节；各产生器把自己的
 * 拒收理由映射到这六个码之一，映射口径写在各产生器的接入注里。
 */
internal enum class ConsiderVerdict {
    /** 入选：过了本层筛选，进入下游（入池不等于被采用，只是「没被这层砍」）。 */
    ACCEPTED,

    /** 与本批已产生的候选重复（同批去重，先见先立）。 */
    DUPLICATE,

    /** 没到产生器的最低门槛（太短／太弱／标记不全这类形态问题）。 */
    BELOW_BAR,

    /** 过了形态门槛但没过校验（超长／缺来源／标签不合规这类内容问题）。 */
    INVALID,

    /** 合格但超出本次上限（截断就算丢，必须留痕——否则就是静默截断）。 */
    OVER_LIMIT,

    /** 被策略层挡住（红线／档位未开／已存在不重复提）。 */
    BLOCKED,
}

/**
 * 一条候选留痕（四要素，判据②）。
 *
 * @param generatorId 产生器 id（稳定字面量，如 `memory-refine`；同一链路跨版本不变）
 * @param inputDigest 该候选的**输入摘要**：产生器吃到的原始文本片段，截断到
 *   [ConsiderLog.DIGEST_LIMIT] 字符（超长加 `…`）。只留摘要不留全文——池要控体积，
 *   全文在产生器自己的账里
 * @param verdict 否决原因码（[ConsiderVerdict] 闭集）
 * @param atMs 时刻（毫秒；由调用方传入其业务时刻，本件不读时钟——纯函数侧不许碰时钟）
 */
internal data class ConsiderRecord(
    val generatorId: String,
    val inputDigest: String,
    val verdict: ConsiderVerdict,
    val atMs: Long,
)

/**
 * 候选留痕池（滚动保留，默认最近 [DEFAULT_CAPACITY] 条）。
 *
 * 只用内存环形队列：**不引入数据库、不自动落盘**（工单边界）。要给外面看时用
 * [exportJsonl] 由壳层决定写哪儿——池本身零 IO 依赖，纯 JVM 可测。
 *
 * 淘汰是**可查的**：池满后再进一条就挤掉最旧一条，[evictedCount] 只增不减；
 * [totalRecorded] = [size] + [evictedCount] 恒成立（对账用）。静默截断在这里
 * 是红：任何一次挤出都必须计进 [evictedCount]。
 *
 * 线程安全：所有读写口 `@Synchronized`（产生器可能在不同线程上记录）。
 */
internal class ConsiderLog(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "容量必须是正数：$capacity" }
    }

    private val ring = ArrayDeque<ConsiderRecord>(capacity)
    private var evicted = 0L
    private var total = 0L

    /** 记一条（返回落池的那条；被挤出的是更旧的一条，只增 [evictedCount]）。 */
    @Synchronized
    fun record(record: ConsiderRecord): ConsiderRecord {
        ring.addLast(record)
        total++
        while (ring.size > capacity) {
            ring.removeFirst()
            evicted++
        }
        return record
    }

    /** 批量记（顺序即插入序，先到先得；同一批里的先后语义由调用方给）。 */
    @Synchronized
    fun recordAll(records: List<ConsiderRecord>): Int {
        records.forEach { record(it) }
        return records.size
    }

    /**
     * 查询（三个条件可单用可组合，全部为空 = 全量；返回按插入序升序）。
     *
     * @param generatorId 按产生器筛（null = 不限）
     * @param verdict 按原因码筛（null = 不限）
     * @param fromMs 时间窗下界（含，null = 不限）
     * @param toMs 时间窗上界（含，null = 不限）
     */
    @Synchronized
    fun query(
        generatorId: String? = null,
        verdict: ConsiderVerdict? = null,
        fromMs: Long? = null,
        toMs: Long? = null,
    ): List<ConsiderRecord> = ring.filter { r ->
        (generatorId == null || r.generatorId == generatorId) &&
            (verdict == null || r.verdict == verdict) &&
            (fromMs == null || r.atMs >= fromMs) &&
            (toMs == null || r.atMs <= toMs)
    }

    /** 池内当前条数（≤ [capacity]）。 */
    @Synchronized
    fun size(): Int = ring.size

    /** 容量（滚动上界）。 */
    fun capacity(): Int = capacity

    /** 被挤出池外的条数（单调递增；不许静默截断，判据③）。 */
    @Synchronized
    fun evictedCount(): Long = evicted

    /** 累计记录过的条数（含已挤出的）：恒等于 [size] + [evictedCount]。 */
    @Synchronized
    fun totalRecorded(): Long = total

    /**
     * 导出 JSONL（每行一个对象、键序固定：generatorId／inputDigest／verdict／atMs；
     * 每行含结尾换行）。空池 ⇒ 空串。
     */
    @Synchronized
    fun toJsonl(): String = buildString {
        ring.forEach { append(it.toJsonLine()).append('\n') }
    }

    /**
     * 把当前池写成 JSONL 文件（UTF-8，覆盖写）。
     *
     * 落盘位置由调用方决定——本件不猜「抱朴记忆/」在哪，也不自动写（试点期只证明
     * 「候选不丢」，写盘接线留给消费方）。
     *
     * @return 写出的行数
     */
    @Synchronized
    fun exportJsonl(target: File): Int {
        val lines = ring.size
        target.writeText(toJsonl(), Charsets.UTF_8)
        return lines
    }

    /** 清池并复位计数（会话切换／测试隔离）。 */
    @Synchronized
    fun reset() {
        ring.clear()
        evicted = 0L
        total = 0L
    }

    companion object {
        /** 滚动保留的默认条数（工单：默认最近 2000 条）。 */
        const val DEFAULT_CAPACITY = 2_000

        /** 输入摘要的字符上限（只做复盘线索，不是原文）。 */
        const val DIGEST_LIMIT = 40

        /** 生产侧共用的那一本（各产生器往这一本里记；产生器 id 区分来源）。 */
        val shared = ConsiderLog()

        /** 摘要口径：截到 [DIGEST_LIMIT]，超长补 `…`；空白压缩成单空格（JSONL 一行一条）。 */
        fun digestOf(text: String): String {
            val flat = text.replace('\n', ' ').replace('\r', ' ').trim()
            return if (flat.length <= DIGEST_LIMIT) flat else flat.take(DIGEST_LIMIT) + "…"
        }
    }
}

/** 一条记录的 JSONL 行（键序固定，便于逐字节比对与外部工具消费）。 */
private fun ConsiderRecord.toJsonLine(): String = buildString {
    append("{\"generatorId\":\"").append(jsonEscape(generatorId)).append("\",")
    append("\"inputDigest\":\"").append(jsonEscape(inputDigest)).append("\",")
    append("\"verdict\":\"").append(verdict.name).append("\",")
    append("\"atMs\":").append(atMs).append('}')
}

/** 最小 JSON 字符串转义（引号／反斜杠／控制字符；中文原样输出）。 */
private fun jsonEscape(s: String): String = buildString(s.length + 8) {
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
