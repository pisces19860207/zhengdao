// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百八十九批 · 状态摘要的**接线壳**（本文件不含 Android import：账本怎么读由接线方给闭包）。
//
// 为什么要这一层：653 的摘要要读三本既有账（起居注／六器账／未学会清单），那三本都要
// `Context` 才能定位文件。内核（[RunStateAssembler]）是纯函数、要能在 JVM 里直测，
// 所以「读账」这件事以闭包形式注入——房型照 400 的 [LearnContextNote]：判据纯、IO 壳薄。
//
// 三条口径：
//   ① **没接线 ⇒ 一个字不加**：闭包没装（单测／未装配进程）时 [ackNote] 返回空串，
//      `update_plan` 的 ack 与接线前逐字节相同；
//   ② **空状态不注入**：渲染出「无」时返回空串——「无」是给人看的判据（⑤），不是往
//      模型上下文里塞的噪声；
//   ③ **读账失败当没有**：任何一本账读崩都只丢那一段，摘要照出（账是留痕，不是闸门）。
package com.example.zhengdao.baopu.state.summary

import com.example.zhengdao.baopu.PlanRules.PlanItem
import com.example.zhengdao.baopu.safety.DiaryEvent
import com.example.zhengdao.baopu.scout.UnfinishedItem
import com.example.zhengdao.baopu.state.track.CharacterTrack

/** 状态摘要的注入点（唯一：`update_plan` 的 ack 尾巴——模型每次更新计划即读到自己的状态）。 */
internal object StateSummaryWiring {

    /** 注入行的人类前缀（与正文之间用空格隔开；`StateSummary.MAX_CHARS` 不含它）。 */
    const val PREFIX: String = "[状态摘要] "

    @Volatile private var diarySource: (() -> List<DiaryEvent>)? = null
    @Volatile private var ledgerSource: (() -> List<UnfinishedItem>)? = null
    @Volatile private var unlearnedSource: (() -> List<String>)? = null
    @Volatile private var trackSource: (() -> CharacterTrack?)? = null

    /**
     * 装账本读取口（`ZhengdaoApp.onCreate` 的 install 链；重复调用以最后一次为准）。
     *
     * @param diary 起居注全本（旧→新）
     * @param ledger 未办成单当前单
     * @param unlearned 未学会清单的问题文本
     * @param track 神轨实例（670）；**当前生产没有产生方** ⇒ 缺省恒 null（欠约束见交付报告）
     */
    fun attach(
        diary: () -> List<DiaryEvent>,
        ledger: () -> List<UnfinishedItem>,
        unlearned: () -> List<String>,
        track: () -> CharacterTrack? = { null },
    ) {
        diarySource = diary
        ledgerSource = ledger
        unlearnedSource = unlearned
        trackSource = track
    }

    /** 单测隔离用：拔掉全部读取口（拔掉后 [ackNote] 恒空串）。 */
    fun resetForTest() {
        diarySource = null
        ledgerSource = null
        unlearnedSource = null
        trackSource = null
    }

    /**
     * `update_plan` ack 尾巴那一段（含前导换行；空串＝不加）。
     *
     * 跑在工具线程（`zd-baopu-tool`），不碰主线程；100 步夹具实测远低于 50ms 预算（判据⑥）。
     */
    fun ackNote(plan: List<PlanItem>, goal: String?): String {
        val diary = diarySource ?: return ""
        val text = runCatching {
            StateSummary.render(
                RunStateAssembler.assemble(
                    goal = goal,
                    plan = plan,
                    diary = runCatching(diary).getOrDefault(emptyList()),
                    ledger = readOrEmpty(ledgerSource) ?: emptyList(),
                    unlearned = readOrEmpty(unlearnedSource) ?: emptyList(),
                    track = readOrEmpty(trackSource),
                ),
            )
        }.getOrNull()
        return if (text.isNullOrBlank() || text == StateSummary.EMPTY) "" else "\n" + PREFIX + text
    }

    private fun <T> readOrEmpty(source: (() -> T)?): T? =
        source?.let { runCatching(it).getOrNull() }
}
