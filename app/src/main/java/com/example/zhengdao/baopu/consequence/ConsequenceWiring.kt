// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零六批（2026-11-07）· 后果治理的**装配缝**。
//
// 本文件是全包第二处碰盘的地方，但只碰**账本**（经 [DecisionLedger]）与**身份表**（经 [IdentityStore]）；
// 判据一条不在这儿写——[ConsequenceStore] 的注释说过为什么，这里补一条：
// 缝上的函数每一个都只做「读回来 → 问判据 → 落一行」，中间不许有 if 判的是别的东西。
//
// 三条口径：
// 1. **档位与深度都从账读回**（派工单 §二·②）。所以本文件没有 SharedPreferences，
//    也没有"内存里记一下上次是什么档"。唯一的内存值是 [executorId] 那一串身份 id，
//    它是**缓存**、不是状态：装的时候读一次，之后每次改身份都跟着新表刷新；
//    重启后重新从盘上读，所以它不构成第二处真相。
// 2. **自动转全开与自动回灰都在这条缝上**（§四 L3 放宽：达标落账即可，不需要人点批准）。
//    人只保留两件事：把能力从「关闭」打开，和那颗一键回灰。
// 3. **落账失败不许把动作报成成功**，但也不回滚动作——208 的口径（记录是留痕不是闸门）。
//    这里统一走 [stamp]：写不成只 log 一句，那句话里必带判据层给的原因。
package com.example.zhengdao.baopu.consequence

import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.core.RunLog
import java.io.File
import java.util.UUID

/**
 * 装配入口。
 *
 * 接线位（本批**没**改任何别人的装配文件，故这里点名，交验收方按行接）：
 *   · [install] 与 [DecisionLedger.install] 同处、同一次启动（今天那是 `BaopuRuntime.kt:906` 附近），
 *     顺序必须先装账本落点、后装身份——身份自己也要落一行「首启生成」的账。
 *   · 五族凭据各自的写侧往 [CredentialStore] 投一行（[CredentialKind.home] 列了去哪一族找）。
 *   · 灰度观察行由被灰度的那族能力在跑完之后调 [observe]（470 的赔付结论进来当 `permanentLoss`）。
 *   · 深度档在每次自进化动手前调 [decideDepth]（左相成本位＋右相证据位由调用方算好传入）。
 */
internal object ConsequenceWiring {

    /** 身份 id 的内存缓存（只在 install 与每次改身份后刷新；重启必重读盘 ⇒ 不是第二处真相）。 */
    @Volatile
    private var cachedExecutor: String = ""

    private var installedDir: File? = null

    /**
     * 首启装配（派工单 §三·①）。
     *
     * @return 在用的身份 id（生成成功或读回来的老 id）；盘完全读不动也返回**空串**，
     *         空串进账本＝那一行没有身份，读侧按老行处理——**不抛**，启动不许被身份挡住。
     */
    fun install(filesDir: File, nowMs: Long, entropy: () -> String = { UUID.randomUUID().toString() }): String {
        installedDir = filesDir
        val fresh = IdentityStore.ensureFirstRun(filesDir, nowMs, entropy)
        cachedExecutor = if (fresh != null) fresh.id else IdentityStore.activeId(filesDir)
        // 盖章口**先装再落账**：首启那一行自己也该带着刚生成的那个 id，反过来就先落一条没身份的账。
        DecisionLedger.executorId = { cachedExecutor }
        if (fresh != null) {
            RunLog.log("执行体身份首启生成：${fresh.id}")
            val rows = IdentityStore.load(filesDir)
            stamp(
                ConsequenceLedger.draftOf(IdentityEvent(IdentityOp.ISSUE, AUTO_ACTOR, fresh.id), rows, nowMs),
                nowMs,
                "身份首启生成",
            )
        }
        return cachedExecutor
    }

    /** 测试收尾用（同 `DecisionLedger.uninstallForTest` 那条「谁装谁还」）。 */
    internal fun uninstallForTest() {
        installedDir = null
        cachedExecutor = ""
        DecisionLedger.executorId = { "" }
    }

    /** 现在盖章用的身份 id（没装配 ⇒ 空串）。 */
    fun executorId(): String = cachedExecutor

    /**
     * 改身份（撤销／轮换／解除撤销）：判据拒 ⇒ 盘和账都不动；判据放行 ⇒ 先盘后账。
     *
     * @return 一句话结局（给设置页/巡按栏直接显示；**没落账时这句里带原因**）
     */
    fun mutateIdentity(m: IdentityMutation, nowMs: Long): String {
        val dir = installedDir ?: return "身份机制没装配，这次改动没做"
        val outcome = IdentityStore.mutate(dir, m, nowMs) { UUID.randomUUID().toString() }
        return when (outcome) {
            is IdentityOutcome.Refused -> {
                RunLog.log("身份改动被拒：${outcome.reason}")
                "被拒：${outcome.reason}"
            }
            is IdentityOutcome.Done -> {
                // 缓存**跟着新表重算**，不许直接写成 outcome.newId：
                // 撤销那一支的 newId 就是刚被撤掉的那个 id，写进去等于账本继续盖一个已作废的身份。
                cachedExecutor = IdentityStore.activeId(dir)
                val draft = ConsequenceLedger.draftOf(outcome.event, outcome.rows, nowMs)
                stamp(draft, nowMs, "身份${outcome.event.op.cn}")
                "已改：${outcome.previousId.ifBlank { "无" }}→${cachedExecutor.ifBlank { "（没有在用身份）" }}（名下凭据按身份表判定）"
            }
        }
    }

    /**
     * 撤销之后还剩几条凭据被闸拦住（②「全失效」的可核对读数；也是**撤销回执**上那句）。
     *
     * 这一句存在的全部理由：判据②要能回答"拦住了几条"。回答不出来就等价于没人验过。
     */
    fun revocationNote(): String {
        val dir = installedDir ?: return "身份机制没装配"
        val rows = IdentityStore.load(dir)
        val denied = CredentialBinding.deniedCountAfterRevocation(CredentialStore.load(dir), rows)
        return ConsequenceLedger.denialNote(denied)
    }

    /** 落一条灰度观察行（指标的唯一来源；内存计数没有入口）。 */
    fun observe(s: CanaryObservation, atMs: Long): Boolean =
        stamp(ConsequenceLedger.draftOf(s, atMs), atMs, "灰度观察 ${s.capability}")

    /**
     * 达标自动转全开／坏消息自动回灰（§四：落账即可，不需要人逐个点）。
     *
     * 读的是账、算的是指标、写的还是账——三件事中间没有一格是"人批准的档位"。
     * [passedRounds] 由调用方按上一轮读数给（连续两轮达标才转，见 `PASS_ROUNDS_FOR_FULL`）。
     */
    fun decideCanaryBand(capability: String, nowMs: Long, passedRounds: Int = 1): CanaryBand {
        val current = currentCanaryBand(capability)
        val m = CanaryMetrics.of(samplesOf(capability), nowMs)
        val next = CanaryLadder.nextBand(current, m, passedRounds)
        if (next == current) return current
        val trigger = if (next == CanaryBand.FULL) CanaryLadder.TRIGGER_AUTO_PROMOTE else CanaryLadder.TRIGGER_AUTO_REVERT
        stamp(
            ConsequenceLedger.draftOf(
                CanaryChange(capability, current.wire, next.wire, AUTO_ACTOR, nowMs, m, trigger),
            ),
            nowMs,
            "灰度档位 $capability",
        )
        return next
    }

    /** 人按的那颗「一键回灰」（④：回灰可以自动，也可以一键；但**不许一键到关闭**）。 */
    fun humanRevertCanary(capability: String, nowMs: Long): CanaryBand {
        val current = currentCanaryBand(capability)
        val next = CanaryLadder.humanRevert(current) ?: return current
        val m = CanaryMetrics.of(samplesOf(capability), nowMs)
        stamp(
            ConsequenceLedger.draftOf(
                CanaryChange(capability, current.wire, next.wire, HUMAN_ACTOR, nowMs, m, CanaryLadder.TRIGGER_HUMAN),
            ),
            nowMs,
            "一键回灰 $capability",
        )
        return next
    }

    /** 现在生效的灰度档（从账读）。 */
    fun currentCanaryBand(capability: String): CanaryBand =
        ConsequenceLedger.currentCanaryBand(rows(), capability)

    /** 现在生效的深度档（从账读；没账 ⇒ 浅，追加判据②）。 */
    fun currentDepth(capability: String): EvolutionDepth =
        ConsequenceLedger.currentDepth(rows(), capability)

    /** 某能力的灰度观察样本（从账读，判据层拿它算指标）。 */
    fun samplesOf(capability: String): List<CanarySample> =
        ConsequenceLedger.samplesOf(rows(), capability)

    /**
     * 定深度档（追加判据①③④）。
     *
     * @param readings 当时的读数**字符串清单**，由调用方从上面那几个函数算好后传入；
     *                 本函数不自己拼数字，免得"读数"变成一句形容词。
     * @return 定下来的档（被拒 ⇒ 原样返回 [current]，并 log 一句为什么）
     */
    fun decideDepth(
        capability: String,
        cost: DepthCostFacts,
        evidence: DepthEvidenceFacts,
        nowMs: Long,
        basis: List<String>,
        readings: List<String>,
    ): EvolutionDepth {
        val current = currentDepth(capability)
        val band = currentCanaryBand(capability)
        val next = DepthGate.nextDepth(current, cost, evidence, band)
        if (next == current) return current
        stamp(
            ConsequenceLedger.draftOf(
                DepthChange(capability, current.wire, next.wire, AUTO_ACTOR, nowMs, basis, readings),
            ),
            nowMs,
            "进化深度 $capability",
        )
        return next
    }

    /**
     * 收一条模型参谋意见（追加判据⑤：**被拦也要记一行**）。
     *
     * 被拦的原因原样进账——只拦不记，这条判据就没人验得动（查不到"模型试过一次"，
     * 与"模型没试过"在账上长得一模一样）。
     */
    fun recordAdvisor(o: AdvisorOpinion): String? {
        val rejection = AdvisorRules.rejection(o)
        stamp(ConsequenceLedger.draftOf(o, rejection), o.atMs, "参谋意见")
        return rejection
    }

    /**
     * 落一条赔付（四件缺一不落；缺哪件 log 哪件）。
     *
     * [Compensation.mustReportToHuman] 就是 §四 那一格：**只有**「赔不了／永久损失」才报人，
     * 能自动恢复的那一类在这儿只留一行日志，不打扰。
     */
    fun recordCompensation(c: Compensation, atMs: Long): List<String> {
        val defects = c.defects()
        if (defects.isNotEmpty()) {
            RunLog.log("赔付没落账（四件缺一不算）：${defects.joinToString("｜")}")
            return defects
        }
        if (c.mustReportToHuman) {
            RunLog.log("要报人：${c.tool}／${c.opId} 判了「${RecoveryVerdict.CANNOT_PAY_MARK}」，清单 ${c.lossList.size} 项")
        }
        stamp(ConsequenceLedger.draftOf(c, atMs), atMs, "赔付 ${c.opId}")
        return emptyList()
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** 机制这一侧的动作actor（线值 `auto`；模型那一头一律被判据拒）。 */
    private const val AUTO_ACTOR: String = "auto"

    /** 人御批那一头的线值。 */
    private const val HUMAN_ACTOR: String = "user"

    private fun rows(): List<DecisionRecord> {
        val root = DecisionLedger.installedRoot() ?: return emptyList()
        return DecisionLedger.read(root)
    }

    /** 唯一的落账口：null 草稿＝判据层已经拒了，这里只负责把原因留成一句可读的日志。 */
    private fun stamp(draft: DecisionDraft?, atMs: Long, what: String): Boolean {
        if (draft == null) {
            RunLog.log("$what 没落账（判据层拒绝，原因见上一条 lastError）：${DecisionLedger.lastError ?: "无"}")
            return false
        }
        val ok = DecisionLedger.record(draft) != null
        if (!ok) RunLog.log("$what 落账失败：${DecisionLedger.lastError ?: "未知"}（时刻 $atMs）")
        return ok
    }
}
