// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百四十四批 · ConsiderLog 覆盖扩展（批次 1）：候选留痕主池。
// 纯内存环形池 + 确定性配额；零 IO、零时钟、零随机、零业务语义。
// 与 811 试点同口径（滚动保留 / 淘汰计数 / 查询 / 导出），本单加：
//   · 单产生器占池 ≤20%（防一个产生器冲掉整窗）；
//   · 超配额记 OVER_LIMIT 截断计数，不许静默丢；
//   · 诊断模式走独立池（见 ConsiderDiagnostic），不混主池。
package com.example.zhengdao.baopu.state.consider

import java.io.File

/**
 * 候选留痕主池。
 *
 * 只用内存环形队列：不引入数据库、不自动落盘（工单边界）。导出由调用方给目标文件。
 * 淘汰可查：池满后挤最旧一条，[evictedCount] 只增；[totalAccepted] = [size] + [evictedCount]。
 * 配额可查：单产生器最多占 [quotaPerGenerator] 条（= 容量 × 20% / 100，向下取整）；
 * 超额的那条按 [ConsiderVerdict.OVER_LIMIT] 计数进 [truncationCount]，绝不静默丢。
 *
 * 线程安全：读写口 `@Synchronized`（产生器可能在不同线程记录）。
 * 确定性：本件不读时钟、不读随机，atMs 由调用方注入。
 */
internal class ConsiderLog(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "容量必须是正数：$capacity" }
    }

    private val ring = ArrayDeque<ConsiderRecord>(capacity)
    private val perGenerator = HashMap<String, Int>()
    private var evicted = 0L
    private var accepted = 0L
    private var truncated = 0L

    /** 单产生器配额：容量 × 20% 向下取整，且至少 1（容量 1..4 的退化池也必须能记一条）。 */
    val quotaPerGenerator: Int get() = maxOf(1, capacity / 5)

    /**
     * 记一条。返回 [ConsiderVerdict.OVER_LIMIT] 表示超配额被截断（已计入 [truncationCount]），
     * 其余表示已入池。超配额不丢弃语义——计数 + 返回码都在，可查可导。
     */
    @Synchronized
    fun record(record: ConsiderRecord): ConsiderVerdict {
        val used = perGenerator[record.generatorId] ?: 0
        if (used >= quotaPerGenerator) {
            truncated++
            return ConsiderVerdict.OVER_LIMIT
        }
        ring.addLast(record)
        perGenerator[record.generatorId] = used + 1
        accepted++
        while (ring.size > capacity) {
            val old = ring.removeFirst()
            val n = perGenerator.getValue(old.generatorId) - 1
            if (n == 0) perGenerator.remove(old.generatorId) else perGenerator[old.generatorId] = n
            evicted++
        }
        return record.verdict
    }

    /** 查询（条件可单用可组合，全空 = 全量；按插入序升序）。 */
    @Synchronized
    fun query(
        generatorId: String? = null,
        verdict: ConsiderVerdict? = null,
        strategy: ConsiderStrategy? = null,
        fromMs: Long? = null,
        toMs: Long? = null,
    ): List<ConsiderRecord> = ring.filter { r ->
        (generatorId == null || r.generatorId == generatorId) &&
            (verdict == null || r.verdict == verdict) &&
            (strategy == null || r.strategy == strategy) &&
            (fromMs == null || r.atMs >= fromMs) &&
            (toMs == null || r.atMs <= toMs)
    }

    @Synchronized fun size(): Int = ring.size
    fun capacity(): Int = capacity
    @Synchronized fun evictedCount(): Long = evicted
    @Synchronized fun totalAccepted(): Long = accepted
    @Synchronized fun truncationCount(): Long = truncated

    /** 某产生器当前在池条数（配额对账用）。 */
    @Synchronized fun usedBy(generatorId: String): Int = perGenerator[generatorId] ?: 0

    /** 对账恒等式：入池总数 = 在池条数 + 淘汰条数。 */
    @Synchronized fun accountingHolds(): Boolean = accepted == ring.size.toLong() + evicted

    /** 导出 JSONL（每行六键、键序固定，UTF-8 中文原样；每行含结尾换行）。空池 ⇒ 空串。 */
    @Synchronized
    fun toJsonl(): String = buildString {
        ring.forEach { append(it.toJsonLine()).append('\n') }
    }

    /** 写 JSONL 到指定文件（UTF-8，覆盖写），返回行数。落盘位置由调用方决定。 */
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
        perGenerator.clear()
        evicted = 0L
        accepted = 0L
        truncated = 0L
    }

    companion object {
        /** 生产侧共用的一本（各产生器用 generatorId 区分来源；811 试点同款口径）。 */
        val shared = ConsiderLog()

        /** 滚动保留默认条数（811 工单：最近 2000 条）。 */
        const val DEFAULT_CAPACITY = 2_000

        /** 单产生器占池上限：20%。 */
        const val QUOTA_PERCENT = 20

        /** 单次调用允许写入主池的硬上限：> 2000 条即红（工单降采样口径⑤）。 */
        const val MAX_WRITE_PER_CALL = 2_000
    }
}
