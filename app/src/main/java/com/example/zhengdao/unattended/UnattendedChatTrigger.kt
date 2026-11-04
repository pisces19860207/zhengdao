// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百四十一单 · 一句话值守的**接线层**：纯判定件（UnattendedPhrase）×状态仓
// （UnattendedStatus）×账（UnattendedLedger）×通知（UnattendedNotifier）的落笔处。
//
// 四条纪律（对应 441 派工单【安全边界】与判据）：
// 1. **唯一喂入口**：本件的 onUserSend 只许从 BaopuChatController.send() 调——
//    "主界面正常发送"是用户亲口说话的唯一通道；点通知走深链、点问卡按钮走
//    engine.answerChoice/answerGroup，都不经过 send ⇒ 天然不触发隐式解除（判据⑤负例，
//    UnattendedChatTriggerGateTest 按源码钉）。网页内容/工具返回值**根本没有一条道**
//    能进本件（判据⑥负例的结构保证）。
// 2. **值守中任何回话都算人回来了**（判据④）：不再猜这句话像不像出门——人既然在打字，
//    就是在场；含"我出门了"也一样（同一条消息里既"回来了"又"出门了"是矛盾话，
//    按先回来处理，要再出门请再说一遍——报告会告诉人值守已收）。
//    唯一区分：明说"回来了/到家了" ⇒ 显式出场，留痕写"她说回来了"。
// 3. **plan 纯函数**（JVM 可测）：判什么与怎么执行分两半——[plan] 只算结论不留痕，
//    [execute] 只按结论落账不判断；判据①②③④⑦的行为级测试钉在 plan 上。
// 4. **进场第一件事是通知**（派工单③）：armedNow 豁免前台抑制的理由见 UnattendedNotifier。
package com.example.zhengdao.unattended

import android.content.Context
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.core.RunLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object UnattendedChatTrigger {

    /** 一句话的处置格（闭集）。 */
    enum class Kind { NONE, ARMED, ASKED, DISARMED_EXPLICIT, DISARMED_IMPLICIT }

    /**
     * 一句话的处置单（[plan] 的产物；[execute] 的指令）。
     * 所有字段要么全空（NONE），要么按格填齐——半张单不许出本件。
     */
    data class Plan(
        val kind: Kind,
        /** 进场：托付范围说明＝用户原话（账上留的是她说的话，不是机器编的概括）。 */
        val scopeText: String = "",
        /** 进场带时长：到点自动收值守的时刻；没带 ⇒ null。 */
        val autoDisarmAtMs: Long? = null,
        /** 进场回执里"预计 X"那句。 */
        val planCn: String = "",
        /** 出场：解除理由的人话（先落 DISARM 账、再进报告头）。 */
        val closeReasonCn: String? = null,
        /** 上屏的一句（engine.notice；NONE 为 null）。 */
        val noticeLine: String? = null,
    )

    /**
     * 判什么（纯函数；[armedAtMs] 是开值守时刻，未开值守时传 null）。
     *
     * 判序（纪律 2）：开了值守 ⇒ 除明说回来外一律隐式出场；
     * 没开 ⇒ 显式出场是"无事可做"（本就没值守），其余按话判。
     */
    internal fun plan(armed: Boolean, text: String, nowMs: Long, armedAtMs: Long?): Plan {
        val verdict = UnattendedPhrase.classify(text)
        // ── 开着值守：任何回话都算人回来了（判据④；明说回来 ⇒ 显式格）──
        if (armed) {
            val span = armedAtMs?.let { UnattendedReport.formatSpan(nowMs - it) } ?: "不足 1 分钟"
            return if (verdict is UnattendedPhraseVerdict.ExitExplicit) {
                Plan(
                    kind = Kind.DISARMED_EXPLICIT,
                    closeReasonCn = "她说回来了，收值守（值守了 $span）",
                    noticeLine = "值守已收（她说回来了，值守了 $span）；离场报告已生成",
                )
            } else {
                Plan(
                    kind = Kind.DISARMED_IMPLICIT,
                    closeReasonCn = "因用户回话而解除，值守了 $span",
                    noticeLine = "值守已收（因用户回话而解除，值守了 $span）；离场报告已生成",
                )
            }
        }
        // ── 没开值守：按话判 ──
        return when (verdict) {
            is UnattendedPhraseVerdict.Arm -> Plan(
                kind = Kind.ARMED,
                scopeText = text.trim(),
                autoDisarmAtMs = verdict.durationMs?.let { nowMs + it },
                planCn = UnattendedPhrase.planCn(verdict.durationMs),
                noticeLine = "已进值守（${UnattendedPhrase.planCn(verdict.durationMs)}），按圈干活；" +
                    "说「我回来了」或回任意一句话即收值守",
            )
            is UnattendedPhraseVerdict.AskArm -> Plan(
                kind = Kind.ASKED,
                noticeLine = "要开离场模式吗？明说一句「我出门了」我就按圈值守",
            )
            // 没开值守时说"回来了"：本就没值守，无事可做（收两次是空账）。
            is UnattendedPhraseVerdict.ExitExplicit -> NONE_PLAN
            is UnattendedPhraseVerdict.None -> NONE_PLAN
        }
    }

    private val NONE_PLAN = Plan(kind = Kind.NONE)

    /**
     * 怎么执行（装配层；IO 落在 [Dispatchers.IO]，上屏那句由调用方在主线程贴）。
     * 返回处置单原样带回——调用方按 [Plan.noticeLine] 上屏，本件不摸 UI。
     */
    suspend fun onUserSend(ctx: Context, text: String, nowMs: Long): Plan {
        val armed = UnattendedStatus.armed()
        val armedAt = UnattendedStatus.armedAtMs()
        val p = plan(armed, text, nowMs, armedAt)
        return withContext(Dispatchers.IO) {
            when (p.kind) {
                Kind.ARMED -> {
                    UnattendedRecorder.arm(ctx, p.scopeText, nowMs, p.autoDisarmAtMs)
                    UnattendedDutyService.start(ctx)
                    UnattendedNotifier.armedNow(ctx, p.planCn)
                    RunLog.log("一句话值守：进场（${p.planCn}；${if (p.autoDisarmAtMs != null) "到点自动收" else "没带时长"}）")
                }
                Kind.DISARMED_EXPLICIT, Kind.DISARMED_IMPLICIT -> {
                    val queue = runCatching {
                        BaopuRuntime.engineIfBuilt()?.awayDeferredHeadlines().orEmpty()
                    }.getOrDefault(emptyList())
                    UnattendedRecorder.disarmAndReport(ctx, nowMs, queue, closeReasonCn = p.closeReasonCn)
                    UnattendedDutyService.stop(ctx)
                }
                Kind.ASKED -> RunLog.log("一句话值守：低置信 ⇒ 只反问不进场（判据⑦）")
                Kind.NONE -> Unit
            }
            p
        }
    }
}
