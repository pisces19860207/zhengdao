// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百五十二批 · 外观卡装配：把 817 的判定/渲染层接进 738 的注入管道（生产端唯一一处）。
// 工单只给两处接线，本文件不扩面：① BaopuRuntime 一处 install()；② CalibrationWiring 尾部一条缝。
//
// 卡源至今是 provider 而不是真源，原因写在报告里：仓内没有 roleId→封闭档卡 的来源
// （剧本 人物表 CharBible.traits 是自由文本，抽成档归 853；660→七维的连续量→档 阈值 ○待核，
// 747 明确「不猜阈值」）。所以未绑卡源 ⇒ 一条都不注 ⇒ 与"没有本单"逐字节一致（判据②）；
// 真源单落地时只改 install 的那一个实参，本层与 738 都不动。
//
// 渲染与判定一律复用 817：摘要用 CastingSheet（≤120 字与敏感组排除在它内部），
// 写后校验用 SceneCheck。本层不再剪一次长度、不新造任何档名。
package com.example.zhengdao.baopu.state.casting

import com.example.zhengdao.baopu.calibration.CalibrationWiring
import com.example.zhengdao.baopu.state.bodyparts.TorsoChecks
import com.example.zhengdao.baopu.state.bodyparts.TorsoPerson
import com.example.zhengdao.baopu.state.bodyparts.TorsoRuntime
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/** 852 装配层。房型照 798 [com.example.zhengdao.baopu.textbook.inject.TextbookRuntime]：install 挂缝、detach 即回滚。 */
internal object CastingRuntime {

    /** 注入行的行头（只加前缀，摘要正文逐字取自 817，不改字）。 */
    const val LINE_HEAD = "[外观]"

    /** 回执行标签——判据① 要在工作区/测试输出里 grep 到的就是它。 */
    const val TRACE_TAG = "[casting]"

    /** 内存回执只留最近这些条（面板与单测读它；本层不写盘，落盘归装配方给的 traceSink）。 */
    private const val TRACE_KEEP = 64

    /** 卡源缝：roleId→卡。null／未装配 ⇒ 空表。 */
    @Volatile
    var cardSource: (() -> List<CastingCard>)? = null

    /** 回执出口：装配方可把它接到日志/账本；null ⇒ 只留内存回执。 */
    @Volatile
    var traceSink: ((String) -> Unit)? = null

    private val receipts = ConcurrentLinkedDeque<String>()

    /** 判据⑤ 的计数落点：写后校验被调了几场，只有场边界动它。 */
    private val writtenScenes = AtomicInteger(0)

    /**
     * 一次装配：把 852 的缝挂进 738 尾部（纯追加，不动三行与 L1/L2 预算）。
     * [cards] 缺省＝不绑卡源 ⇒ [sceneNotes] 恒空 ⇒ 主链输出与"没有本单"逐字节一致。
     */
    fun install(cards: (() -> List<CastingCard>)? = null, trace: ((String) -> Unit)? = null) {
        cardSource = cards
        traceSink = trace
        CalibrationWiring.castingProvider = { sceneNotes() }
    }

    /** 回滚位：与没有本单逐字节一致（判据② 的另一半）。 */
    fun detach() {
        CalibrationWiring.castingProvider = null
        cardSource = null
        traceSink = null
        receipts.clear()
        writtenScenes.set(0)
    }

    /** 测试复位：与 detach 同效（装配缝回到出厂未接）。 */
    fun resetForTest() = detach()

    /** 本轮要注入的外观摘要：一卡一行，行序＝卡序（稳定，供确定性比对）。 */
    fun sceneNotes(): List<String> {
        val cards = runCatching { cardSource?.invoke() }.getOrDefault(emptyList()).orEmpty()
        if (cards.isEmpty()) return emptyList()
        val out = ArrayList<String>(cards.size)
        cards.forEach { card ->
            val sheet = CastingSheet.of(card)
            out += "$LINE_HEAD ${sheet.summary}"
            trace("写前 role=${sheet.roleId} 档位数=${sheet.vector.size} 摘要=${sheet.summary}")
        }
        return out
    }

    /**
     * 每场写后的唯一校验口：只在**场边界**被调一次（判据⑤），逐段调用不属于本口。
     * [observed]／[sceneTraces] 的真来源是 853 的描写抽取；未给就是空表，
     * 817 会照事实判「无描写／像标本」——本层不编一致，也不把空表粉成通过。
     */
    fun onSceneWritten(
        sceneId: String,
        observed: Map<String, List<String>>,
        sceneTraces: Set<String>,
        cards: List<CastingCard> = currentCards(),
        persons: List<TorsoPerson> = TorsoRuntime.currentPersons(),
    ): SceneReport {
        val report = SceneCheck.check(cards, observed, sceneTraces)
        val torso = TorsoChecks.scene(persons)
        writtenScenes.incrementAndGet()
        trace("写后 场=$sceneId 结论=${report.overall.label} 明细=${report.line().replace("\n", " ／ ")}")
        TorsoRuntime.record(sceneId, torso)
        return report
    }

    /** 当前卡（写后校验默认拿它，省得调用方再传一遍同一份）。 */
    fun currentCards(): List<CastingCard> =
        runCatching { cardSource?.invoke() }.getOrDefault(emptyList()).orEmpty()

    /** 已校验过的场数（判据⑤ 的计数断言读这里）。 */
    fun writtenSceneCount(): Int = writtenScenes.get()

    /** 内存回执（最近的在前一条都不丢，只截尾 [TRACE_KEEP] 条）。 */
    fun receiptLines(): List<String> = receipts.toList()

    private fun trace(body: String) {
        val line = "$TRACE_TAG $body"
        receipts.addLast(line)
        while (receipts.size > TRACE_KEEP) receipts.pollFirst()
        runCatching { traceSink?.invoke(line) }
    }
}
