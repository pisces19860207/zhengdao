// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第七百四十七批 · 人物锚定：越界记录账（派工单④⑤，判据③④⑤的落点）。
// 记录形状照派工单④ 一字不动：{fromState, toState, evidence[], at, who}。
// seq 与 digest 是完整性附注（账本自证用），不是第 6、7 个语义字段——规格没给的语义字段一个不加。
//
// 三条口径：
// · **只追加不改写**（判据③）：账本内部是私有可变列表，对外只给副本，没有 set／remove／编辑口。
//   改写历史的真实入口是「重载」（存档恢复）：restore 对整条链重算摘要，接不上就拒装填。
// · 「当时七维」不塞进越界记录（规格④ 只有五个字段），另存一条同样只追加的快照链；
//   回放＝记录链里 at≤t 的最后一条（当时第几格）＋快照链里 at≤t 的最后一条（当时七维）。
// · 摘要链只锁本批自己的账，不锁 670 的两台机（拓扑改动归 TRACK_SPEC_MAJOR 那道闸管）；
//   判据④「三条链互不覆盖」在这里的体现是：账本只存关系轨迹的跨格，不碰沦陷链／升温链的任何字段。
package com.example.zhengdao.baopu.state.anchor

import com.example.zhengdao.baopu.state.track.RelationState
import java.security.MessageDigest

/** 一次跨格记录（派工单④ 的五字段＋账本自证的 seq／digest）。 */
data class CrossRecord(
    val seq: Int,
    val fromState: RelationState,
    val toState: RelationState,
    val evidence: List<String>,
    val at: Long,
    val who: String,
    override val digest: String,
) : ChainNode {
    override fun sequence(): Int = seq
    override fun moment(): Long = at
    override fun digestChain(): String = digest

    /** 摘要正文：prev 是链上条的摘要，所以抽条、换序、改字段都会让它算不回来。 */
    override fun body(prev: String): String = bodyOf(seq, fromState, toState, evidence, at, who, prev)

    companion object {
        fun bodyOf(seq: Int, from: RelationState, to: RelationState, evidence: List<String>, at: Long, who: String, prev: String?): String =
            "rec|$seq|${from.name}|${to.name}|${evidence.joinToString("＋")}|$at|$who|${prev ?: CrossLedger.GENESIS}"
    }
}

/** 某一刻的七维全量快照（判据⑤ 回放的另一半；只追加，理由同 CrossRecord）。 */
data class DimSnapshot(
    val seq: Int,
    val at: Long,
    val profileId: String,
    val dimensions: String,
    override val digest: String,
) : ChainNode {
    override fun sequence(): Int = seq
    override fun moment(): Long = at
    override fun digestChain(): String = digest
    override fun body(prev: String): String = bodyOf(seq, at, profileId, dimensions, prev)

    companion object {
        fun bodyOf(seq: Int, at: Long, profileId: String, dimensions: String, prev: String?): String =
            "snap|$seq|$at|$profileId|$dimensions|${prev ?: CrossLedger.GENESIS}"
    }
}

/** 摘要链节点：越界记录与七维快照在体检时看同一件事——序号、时间、正文、摘要。 */
internal interface ChainNode {
    val digest: String
    fun sequence(): Int
    fun moment(): Long
    fun digestChain(): String
    fun body(prev: String): String
}

/** 回放结果：给一个时间点，重建「当时第几格＋当时七维」。 */
data class Replay(
    val at: Long,
    val record: CrossRecord?,
    val snapshot: DimSnapshot?,
) {
    val grid: String get() = record?.let { AnchorTrack.gridOf(it.toState) } ?: AnchorTrack.GRID_EMPTY
    val dimensions: String get() = snapshot?.dimensions ?: "当时还没有七维快照（回放不编初始档案）"
    val isEmpty: Boolean get() = record == null && snapshot == null
}

/**
 * 越界账本。写入口只有 append／appendSnapshot 两个，都是追加；读出口给副本，改副本不动账本。
 */
class CrossLedger(private val now: () -> Long = System::currentTimeMillis) {

    private val recordChain = mutableListOf<CrossRecord>()
    private val snapChain = mutableListOf<DimSnapshot>()

    val records: List<CrossRecord> get() = recordChain.toList()
    val snapshots: List<DimSnapshot> get() = snapChain.toList()

    /**
     * 追加一条跨格记录。
     * 没证据 ⇒ 拒（判据② 的第二道闸：引擎拦一次，账本不收没证据的记录）；
     * who 为空 ⇒ 拒（派工单④ 的字段不许留空）；
     * 时间回拨 ⇒ 拒（回放按时间取最后一条，回拨等于改写历史）。
     */
    fun append(from: RelationState, to: RelationState, evidence: List<String>, who: String, at: Long = now()): CrossRecord {
        if (evidence.none { it.isNotBlank() }) throw AnchorSpecException("没证据的跨格不进账（判据②）：$from→$to")
        if (who.isBlank()) throw AnchorSpecException("越界记录必须写明 who")
        val last = recordChain.lastOrNull()
        if (last != null && at < last.at) throw AnchorSpecException("时间回拨＝改写历史，拒：$at < ${last.at}")
        val seq = (last?.seq ?: 0) + 1
        val record = CrossRecord(seq, from, to, evidence.toList(), at, who, sha256(CrossRecord.bodyOf(seq, from, to, evidence, at, who, last?.digest)))
        recordChain += record
        return record
    }

    /** 追加一条七维快照（档案改过就重新存；回放取的是「当时」那一份，不是现在这一份）。 */
    fun appendSnapshot(profile: AnchorProfile, at: Long = now()): DimSnapshot {
        val last = snapChain.lastOrNull()
        if (last != null && at < last.at) throw AnchorSpecException("快照时间回拨，拒：$at < ${last.at}")
        val seq = (last?.seq ?: 0) + 1
        val dimensions = profile.snapshot()
        val snap = DimSnapshot(seq, at, profile.id, dimensions, sha256(DimSnapshot.bodyOf(seq, at, profile.id, dimensions, last?.digest)))
        snapChain += snap
        return snap
    }

    /** 判据⑤：给时间点重建当时状态；账本为空时给出空回放（不编造初始态）。 */
    fun replay(at: Long): Replay = Replay(
        at = at,
        record = recordChain.lastOrNull { it.at <= at },
        snapshot = snapChain.lastOrNull { it.at <= at },
    )

    /** 整条链体检：列出每一处对不上的地方；空表＝账本没被动过。 */
    fun verify(): List<String> = verifyChains(recordChain, snapChain)

    companion object {
        /** 链头（第一条记录的 prev 摘要）：固定串，不参与业务语义。 */
        const val GENESIS = "genesis"

        /**
         * 从存档恢复（判据③ 的拒点）：任何一条的字段被改过、被抽掉、被插进中间
         * ⇒ 摘要链重算接不上 ⇒ 直接拒，不把被改写的历史装进账本。
         */
        fun restore(records: List<CrossRecord>, snapshots: List<DimSnapshot> = emptyList()): CrossLedger {
            val problems = verifyChains(records, snapshots)
            if (problems.isNotEmpty()) throw AnchorSpecException("账本不自洽，拒绝恢复：${problems.joinToString("；")}")
            return CrossLedger().also { it.recordChain += records }.also { it.snapChain += snapshots }
        }

        /** 两条链一起体检（restore 与 verify 共用一套口径，免得两处漂）。 */
        private fun verifyChains(records: List<CrossRecord>, snapshots: List<DimSnapshot>): List<String> {
            val problems = mutableListOf<String>()
            checkChain(records, "越界记录", problems)
            checkChain(snapshots, "快照", problems)
            return problems
        }

        private fun checkChain(nodes: List<ChainNode>, what: String, problems: MutableList<String>) {
            nodes.forEachIndexed { i, node ->
                val prev = nodes.getOrNull(i - 1)?.digestChain() ?: GENESIS
                val expected = sha256(node.body(prev))
                if (node.sequence() != i + 1) problems += "$what 第 ${i + 1} 条 seq＝${node.sequence()}，序号不连续"
                if (i > 0 && node.moment() < nodes[i - 1].moment()) problems += "$what 第 ${i + 1} 条时间回拨"
                if (node.digestChain() != expected) problems += "$what 第 ${i + 1} 条摘要对不上（历史被改）"
            }
        }

        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }.substring(0, DIGEST_HEX)

        private const val DIGEST_HEX = 16
    }
}
