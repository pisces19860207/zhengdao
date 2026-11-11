// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十九批（2026-11-11）· 样本池的**装配缝**（读账、攒样本、判够不够、拒了记账）。
//
// 本文件只有四个动作，一句判据都不自己写：
//   · 攒（[record]）⇒ `EvoSamplePool.append`（append-only，去重归读侧折叠）；
//   · 读（[reading]／[readout]）⇒ `EvoSampleRules.reading`（口径只有一个来源）；
//   · 拒（[promote]）⇒ 判据不足就**拒并记账**——记账走既有参谋意见账
//     （`ConsequenceWiring.recordAdvisor`：506 给这一类写明的用途就是"被拦下来的意见也记这一行"，
//     不记就等于没人知道有人试过强行转正）。**不新开账本 kind、不改账本闭集**（派工单 ⑤）。
//   · 重放（[replay]）⇒ 把备份的原始行喂回池（判据④）。
//
// 为什么不在这里改档：转正的手是 473 的梯子（`ConsequenceWiring.decideCanaryBand`）。
// 本批只回答"样本够不够格谈这件事"，够格了就交给梯子——如果这里顺手把档写上去，
// 就等于在 589 里又造了一个改档口，495/517 的聚合口径当场被绕过（派工单 ⑤ 明令不许）。
//
// 装配：`install(wsRoot)` 一处落根（`<工作区>/.zd-evo/`），由装配层在拿到工作区根之后调一次。
// 本批**不动任何既有装配文件**（生产 diff＝0，与 376 同型），接线点两处列在交付报告里：
//   ① `BaopuRuntime` 的装配段（与 `DecisionLedger.install`／`ConsequenceWiring.install` 同处）：
//      `EvoSampleWiring.install(BaopuRuntime.workspaceRoot(appCtx))`（`BaopuRuntime.kt:217`，
//      主根＝`BaopuWorkspace.hostDir`，与 `.zd-ledger.jsonl` 同一个根）；
//   ② 观察落账之后（`LowRiskEvo.observeRun` 那一行之后）：
//      `EvoSampleWiring.record(EvoSampleEvent(域, 会话, 事件号, 动作类, 时刻))`。
// 没装配 ⇒ 读数是"没装配"，攒样本返回 false，绝不抛（启动路径不许被样本池挡住）。
package com.example.zhengdao.baopu.evolution.samples

import com.example.zhengdao.baopu.consequence.AdvisorOpinion
import com.example.zhengdao.baopu.consequence.AdvisorRules
import com.example.zhengdao.baopu.consequence.CanaryCapability
import com.example.zhengdao.baopu.consequence.ConsequenceWiring
import com.example.zhengdao.core.RunLog
import java.io.File

/** 一次"要转正"的裁决（够格＝放行到 473 的梯子；不够格＝拒＋记账）。 */
internal data class EvoPromoteOutcome(
    val allowed: Boolean,
    val reason: String,
    /** 拒行有没有被参谋意见账收下（判据③的"记账"半边；盘上真有没有由读账核）。 */
    val refusalAccepted: Boolean,
)

/** 样本池的装配缝。 */
internal object EvoSampleWiring {

    /** 参谋账里"这是样本门出的意见"的标（advisor 线值前缀；读侧按它筛本批的拒行）。 */
    internal const val ADVISOR_PREFIX: String = "evo-samples:"

    @Volatile
    private var wsRoot: File? = null

    /** 装配样本池的落点（工作区根；本批只在测试与将来的装配段里调）。 */
    fun install(root: File) {
        wsRoot = root
    }

    /** 测试收尾用（谁装谁还）。 */
    internal fun uninstallForTest() {
        wsRoot = null
    }

    /** 装上了没有（读数字符串要如实说"没装配"，不装假绿）。 */
    fun installed(): Boolean = wsRoot != null

    /**
     * 攒一条样本。
     *
     * 没装配／域不在册／盘写不上 ⇒ false（**不抛**）：样本是判定的料，不是动作的闸，
     * 攒不上不许把正在跑的那件事弄挂（同 506 `stamp` 的口径：记录是留痕不是闸门）。
     */
    fun record(event: EvoSampleEvent): Boolean {
        val root = wsRoot ?: return false
        val ok = EvoSamplePool.append(root, event)
        if (!ok) RunLog.log("样本没攒上（${event.domain}／${event.eventId}）：读数会少这一条")
        return ok
    }

    /** 一个域的样本读数（没装配／没跑过 ⇒ 0 条、样本不足——fail-closed，不编数）。 */
    fun reading(domain: String): EvoSampleReading {
        val root = wsRoot ?: return EvoSampleReading.empty(domain)
        return EvoSampleRules.reading(domain, EvoSamplePool.read(root, domain))
    }

    /**
     * 全表读数（`evo_samples`，每域一行：次数／天数／结论）。
     *
     * 逐域走 473 的登记表（不是"盘上有哪个文件就报哪个域"）：一个从来没过样本的域
     * 也必须出现在读数里，否则"没跑过"与"这域不存在"长得一样。
     */
    fun readout(): List<String> = CanaryCapability.entries.map { reading(it.wire).line() }

    /** 全表读数拼成一块（体检单/日志用；与 [readout] 同源）。 */
    fun readoutBlock(): String =
        if (installed()) readout().joinToString("\n") else "${EvoSampleReading.READING_KEY}：样本池没装配"

    /**
     * 要转正：够格才放行，不够格**拒并记账**（判据③）。
     *
     * 三条拒路各说各的（账上一眼能看出是次数不够还是天数不够）：
     *   · 没登记在册的域 ⇒ 拒（也不记账：不是"差样本"，是"问错对象"）；
     *   · `INSUFFICIENT`（<8 次）⇒ 拒＋账单里写"样本不足"；
     *   · `CANARY_ONLY`（≥8 但不够转正）⇒ 拒＋账单里写"只够灰度"。
     *
     * @return [EvoPromoteOutcome.allowed]＝true 才许去 473 的梯子谈转正
     */
    fun promote(domain: String, nowMs: Long): EvoPromoteOutcome {
        if (CanaryCapability.ofWire(domain) == null) {
            return EvoPromoteOutcome(false, "没登记在册的域「$domain」：连样本池都没有这一域", false)
        }
        val r = reading(domain)
        if (r.promotable()) {
            return EvoPromoteOutcome(true, "样本够转正：${r.describe()}", false)
        }
        val why = refusalReason(r)
        val accepted = ConsequenceWiring.recordAdvisor(
            AdvisorOpinion(
                advisor = ADVISOR_PREFIX + domain,
                advice = AdvisorRules.ADVICE_FAIL,
                reason = why,
                risks = listOf(r.verdict.wire),
                aboutOwnOutput = false,
                atMs = nowMs,
            ),
        ) == null
        RunLog.log("样本门拒转正（$domain）：$why")
        return EvoPromoteOutcome(false, why, accepted)
    }

    /**
     * 拒的那句话（判据③要能在账上读到"为什么不许"）。
     *
     * 三种说全：差在哪条线、差多少、下一步只能做什么。数字取自同一份读数，不另算。
     */
    internal fun refusalReason(r: EvoSampleReading): String = when (r.verdict) {
        EvoSampleVerdict.INSUFFICIENT ->
            "样本不足，不许转正：${r.events} 次／${r.days} 天" +
                "（转正要 ≥${EvoSampleRules.PROMOTE_EVENTS} 次且 ≥${EvoSampleRules.PROMOTE_DAYS} 天；" +
                "灰度也要 ≥${EvoSampleRules.CANARY_EVENTS} 次）"
        EvoSampleVerdict.CANARY_ONLY ->
            "样本只够灰度，不许转正：${r.events} 次／${r.days} 天" +
                "（转正要 ≥${EvoSampleRules.PROMOTE_EVENTS} 次且 ≥${EvoSampleRules.PROMOTE_DAYS} 天）"
        EvoSampleVerdict.PROMOTE_READY -> "样本够转正：${r.describe()}"
    }

    /**
     * 重放一批原始行（判据④：池可重放）。
     *
     * @return 真正写进去的行数（读侧去重前）；重放已存在的行不会让读数变大
     */
    fun replay(domain: String, lines: List<String>): Int {
        val root = wsRoot ?: return 0
        if (CanaryCapability.ofWire(domain) == null) return 0
        return EvoSamplePool.replay(root, domain, lines)
    }
}
