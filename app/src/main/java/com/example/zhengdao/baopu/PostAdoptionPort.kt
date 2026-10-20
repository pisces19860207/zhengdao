// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百批（2026-10-21）：**回环的执行面**——采纳入账、窗口到期复测、出建议卡。
//
// 一条硬边界：本文件**不出手**。它只判、只记账、只把「建议」交出去；
// 回滚在人点了之后才由 [PostAdoptionRollback.applyOnUserApproval] 执行。
//
// 三个依赖都用闭包注入（不在本文件里 new DB / 不读盘）：
//   读数 `readOutcomes`：生产传 `{ name -> SkillEffects.outcomesFor(db, name) }`
//   版本 `versionOf`   ：生产传盘上正文的 sha12（[SkillAudit.shaOf]）
//   时钟 `clock`       ：测试注入固定值——窗口判定必须能复现，不许靠 sleep。
package com.example.zhengdao.baopu

/** 给 UI 的一条建议（**只有建议**：没有 apply、没有 restore）。 */
internal data class PostAdoptionNotice(
    val eventId: String,
    val channel: PostAdoptionChannel,
    val skillName: String,
    val verdict: PostAdoptionVerdict,
    val reason: PostAdoptionReason,
    /** 采纳前基线（四键计数）。 */
    val before: EffectSnapshot,
    /** 采纳后增量（现读 − 基线）。 */
    val delta: EffectSnapshot,
    val backupIds: List<String>,
    val appliedAtMs: Long,
)

/** 事件里的基线 ⇒ 快照（卡面要能并排显示「采纳前 / 采纳后」）。 */
internal fun baselineSnapshot(event: PostAdoptionEvent): EffectSnapshot = EffectSnapshot(
    successes = event.baselineOf(SkillOutcome.SUCCESS),
    failures = event.baselineOf(SkillOutcome.FAILURE),
    corrections = event.baselineOf(SkillOutcome.USER_CORRECTED),
    retries = event.baselineOf(SkillOutcome.RETRIED),
)

/**
 * 回环端口：采纳入账、复测、结案。
 *
 * 所有写操作都是**追加**（不改历史行）；写失败向上抛，由调用方只 log——
 * 留痕是账本不是闸门，账本卡住采纳就是本末倒置（57 批同口径）。
 */
internal class PostAdoptionPort(
    private val log: PostAdoptionLog,
    private val readOutcomes: (String) -> Map<SkillOutcome, Int>?,
    private val versionOf: (String) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 账本文件（测试与报告要看落点）。 */
    fun file(): java.io.File = log.file()

    /**
     * 记一条采纳事件。
     *
     * 先按「同一技能只留一条窗口」把旧的作废（[PostAdoptionLedger.supersedeOnNewAdoption]），
     * 再追加本条——顺序不许反：反了会有一瞬间两条都开着，读完立刻双计。
     *
     * @return 落盘成功与否（false 时调用方只 log，不影响采纳本身）
     */
    fun onAdopted(event: PostAdoptionEvent): Boolean = runCatching {
        val now = clock()
        val ledger = log.load()
        ledger?.supersedeOnNewAdoption(event, now)?.forEach(log::append)
        log.append(event)
        true
    }.getOrElse {
        com.example.zhengdao.rootfs.RunLog.log(
            "抱朴：采纳事件没记上（不影响采纳）${it.javaClass.simpleName}: ${it.message}",
        )
        false
    }

    /**
     * 复测所有还开着的事件（挂在会话收尾）。
     *
     * 每条的处置：
     *   KEEP                ⇒ 记 KEPT 结案，**不弹卡**；
     *   ROLLBACK / RETIRE   ⇒ 没弹过就记 SUGGESTED 并返回一张卡（同一条只弹一次）；
     *   判不了且可结案        ⇒ 记 CLOSED（NO_USE／基线未知／正文被改），不打扰用户；
     *   其余（未到期/样本不足）⇒ 什么都不写，等下轮。
     *
     * @return 本轮**新**要弹的卡（通常 0 或 1 张）
     */
    fun recheck(): List<PostAdoptionNotice> {
        val now = clock()
        val ledger = log.load() ?: return emptyList()
        val out = ArrayList<PostAdoptionNotice>()
        for (event in ledger.open()) {
            val judged = runCatching {
                judgePostAdoption(
                    event = event,
                    after = readOutcomes(event.skillName),
                    nowMs = now,
                    currentVersion = versionOf(event.skillName),
                    minSamples = event.windowUses,
                )
            }.getOrElse {
                com.example.zhengdao.rootfs.RunLog.log(
                    "抱朴：采纳后复测跳过（${it.javaClass.simpleName}）：${it.message}",
                )
                continue
            }
            when {
                judged.verdict == PostAdoptionVerdict.KEEP -> log.append(
                    PostAdoptionSettlement(
                        eventId = event.eventId,
                        verdict = PostAdoptionVerdict.KEEP,
                        action = PostAdoptionAction.KEPT,
                        atMs = now,
                        reason = judged.reason,
                        delta = judged.delta,
                    ),
                )

                judged.terminal -> log.append(
                    PostAdoptionSettlement(
                        eventId = event.eventId,
                        verdict = judged.verdict,
                        action = PostAdoptionAction.CLOSED,
                        atMs = now,
                        reason = judged.reason,
                        delta = judged.delta,
                    ),
                )

                judged.verdict == PostAdoptionVerdict.ROLLBACK ||
                    judged.verdict == PostAdoptionVerdict.RETIRE -> {
                    if (!ledger.alreadySuggested(event.eventId)) {
                        log.append(
                            PostAdoptionSettlement(
                                eventId = event.eventId,
                                verdict = judged.verdict,
                                action = PostAdoptionAction.SUGGESTED,
                                atMs = now,
                                reason = judged.reason,
                                delta = judged.delta,
                            ),
                        )
                        out += PostAdoptionNotice(
                            eventId = event.eventId,
                            channel = event.channel,
                            skillName = event.skillName,
                            verdict = judged.verdict,
                            reason = judged.reason,
                            before = baselineSnapshot(event),
                            delta = judged.delta,
                            backupIds = event.backupIds,
                            appliedAtMs = event.appliedAtMs,
                        )
                    }
                }

                else -> Unit
            }
        }
        return out
    }

    /**
     * 用户在卡上做了处置 ⇒ 记一条（回滚本身在 [PostAdoptionRollback] 里做）。
     *
     * @param action 只许 SUGGESTED 之后的三种人批动作之一（KEPT/APPLIED/DISMISSED/LATER）
     */
    fun recordUserAction(
        eventId: String,
        action: PostAdoptionAction,
        verdict: PostAdoptionVerdict?,
        reason: PostAdoptionReason? = null,
        delta: EffectSnapshot? = null,
    ): Boolean {
        require(action != PostAdoptionAction.CLOSED || verdict == PostAdoptionVerdict.DEFER) {
            "CLOSED 只在判不了时记，且结论必须是 DEFER"
        }
        return runCatching {
            log.append(
                PostAdoptionSettlement(
                    eventId = eventId,
                    verdict = verdict,
                    action = action,
                    atMs = clock(),
                    reason = reason,
                    delta = delta,
                ),
            )
            true
        }.getOrElse {
            com.example.zhengdao.rootfs.RunLog.log(
                "抱朴：采纳效果处置没记上 ${it.javaClass.simpleName}: ${it.message}",
            )
            false
        }
    }
}
