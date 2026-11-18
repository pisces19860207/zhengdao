// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第五百二十批 · 杏仁核·快探的**探针账**（习惯化/敏化状态变化的流水）。
//
// 这不是权威账。权威账（run_log / BaopuDb / 授权档）是别处的地盘，快探一条都不写
// （源码断言钉死：本包禁 BaopuDb/run_log/execSQL/Ledger 等词）。这里只是快探自己的
// 状态变化流水：什么时候对哪类刺激降敏了、什么时候因事故敏化了、什么时候衰减了、
// 什么时候被人一键回了默认——判据②"账里有降敏行"的"账"就是它。
//
// 持久化（跨重启）不在本批：落库/落文件归接线单。本批账是内存账，
// 账挂了不影响快探读数（判据⑥失败兜底：append 抛异常被快探吞掉，行为与账好着时逐字一致）。
package com.example.zhengdao.baopu.amygdala

/** 账行类别：只记状态变化，不记每一次打分（打分走返回值，举手走 BioLog 一行）。 */
internal enum class AccountKind { DESSENSITIZE, SENSITIZE, DECAY, RESET }

/** 一行账。atMs 是状态变化时刻（账是流水，这里必须有时刻）；note 一句话，无随机。 */
internal data class AccountEntry(
    val kind: AccountKind,
    val classKey: String,
    val atMs: Long,
    val factor: Double,
    val note: String,
)

/** 探针账的缝：内存账之外，接线单将来可落文件/表——快探主件只认这个接口。 */
internal interface ProbeAccount {
    fun append(entry: AccountEntry)
    fun entries(): List<AccountEntry>
}

/** 内存账（本批默认实现）。同步加锁：引擎多线程边界都可能打到快探。 */
internal class InMemoryProbeAccount : ProbeAccount {
    private val rows = ArrayDeque<AccountEntry>()

    @Synchronized
    override fun append(entry: AccountEntry) {
        rows.addLast(entry)
        if (rows.size > MAX_ROWS) rows.removeFirst()
    }

    @Synchronized
    override fun entries(): List<AccountEntry> = rows.toList()

    private companion object {
        /** 内存账封顶：快探是常驻组件，账不能无界涨（溢出丢最老的，账还在）。 */
        const val MAX_ROWS = 512
    }
}
