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

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.core.RunLog
import java.io.File
import java.util.UUID
import org.json.JSONObject

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
     * 首启装配（派工单 §三·①；517 追加 U4 分流）。
     *
     * 三条路：
     *   · **真首装**（表空、没有恢复标记）⇒ 生成并落「首启生成」账——首启语义不变；
     *   · **恢复后缺身份**（表空、有恢复标记——[markRestored] 在备份导入完成时打的点）
     *     ⇒ **不生成**（U4：恢复后缺身份 ⇒ 提示「需铸新身份」，不许静默生成），
     *     返回空串，[needsIdentityMint] 与体检格把这件事说给人听，人工铸新后才恢复记账；
     *   · **有历史**（撤销／轮换过）⇒ 读回在用 id；没有在用的（已撤销）⇒ 空串——
     *     撤销后重启仍失效（U2），首启生成的前提已收成「整表为空」，这里不会长出新身份。
     *
     * @return 在用的身份 id（生成成功或读回来的老 id）；盘完全读不动也返回**空串**，
     *         空串进账本＝那一行没有身份，读侧按老行处理——**不抛**，启动不许被身份挡住。
     */
    fun install(filesDir: File, nowMs: Long, entropy: () -> String = { UUID.randomUUID().toString() }): String {
        installedDir = filesDir
        val rows = IdentityStore.load(filesDir)
        val restored = restoreMarkerAtMs(filesDir) > 0L
        val fresh = if (restored) null else IdentityRules.firstRunRow(rows, entropy(), nowMs)
        // 盘写不下去 ⇒ 当作没生成（同 506 ensureFirstRun 的口径：空表＝没有在用身份，安全侧）。
        val generated = fresh != null && IdentityStore.save(filesDir, rows + fresh)
        cachedExecutor = if (generated) fresh!!.id else IdentityStore.activeId(filesDir)
        // 盖章口**先装再落账**：首启那一行自己也该带着刚生成的那个 id，反过来就先落一条没身份的账。
        DecisionLedger.executorId = { cachedExecutor }
        if (generated) {
            RunLog.log("执行体身份首启生成：${fresh!!.id}")
            stamp(
                ConsequenceLedger.draftOf(
                    IdentityEvent(IdentityOp.ISSUE, AUTO_ACTOR, fresh.id),
                    rows + fresh,
                    nowMs,
                ),
                nowMs,
                "身份首启生成",
            )
        }
        if (!generated && restored && IdentityStore.activeId(filesDir).isEmpty()) {
            // U4 的那句提示：说一次进日志（表是空的，账行盖不上身份，体检格是常驻的提示面）。
            RunLog.log("执行体身份：恢复后没有身份（身份不进备份）——需人工铸新，不许静默生成（U4）")
        }
        sweepLegacyCredentials(nowMs)
        return cachedExecutor
    }

    /** 恢复后缺身份（U4）要不要人工铸新：表里没有在用 id，且这台机有过一次备份恢复。 */
    fun needsIdentityMint(): Boolean {
        val dir = installedDir ?: return false
        return IdentityStore.activeId(dir).isEmpty() && restoreMarkerAtMs(dir) > 0L
    }

    /**
     * 备份恢复完成的落点（备份包**不含**身份/凭据——U4 的另一半）：
     * 导入完成后由备份侧调一次，打一枚带**绝对时刻**的恢复标记。
     * 标记的作用只有一个：下一次装配时把「表空」从「真首装」里分出来，拦住静默生成。
     */
    fun markRestored(filesDir: File, nowMs: Long): Boolean = runCatching {
        val f = restoreMarkerFile(filesDir)
        f.parentFile?.mkdirs()
        f.writeText(
            JSONObject().put("schema", ConsequenceDirs.SCHEMA).put("restoredAt", nowMs).toString(),
            Charsets.UTF_8,
        )
        true
    }.getOrElse {
        RunLog.log("恢复标记写失败：${it.javaClass.simpleName}")
        false
    }

    private fun restoreMarkerFile(filesDir: File): File =
        File(ConsequenceDirs.root(filesDir), RESTORE_MARKER)

    private fun restoreMarkerAtMs(filesDir: File): Long = runCatching {
        val f = restoreMarkerFile(filesDir)
        if (!f.isFile) return@runCatching 0L
        JSONObject(f.readText(Charsets.UTF_8)).optLong("restoredAt", 0L)
    }.getOrDefault(0L)

    /**
     * Q1 迁移 sweep：老凭据打 `legacy ＋ 绝对到期日` 标（没打过的才打，到期日不漂）；
     * 已到期的提示**一次**（时刻记账在绑定表根上的 `legacyNoticeAt`）。
     */
    private fun sweepLegacyCredentials(nowMs: Long) {
        val dir = installedDir ?: return
        val creds = CredentialStore.load(dir)
        if (creds.isEmpty()) return
        val (stamped, newly) = LegacyCredentials.grandfather(creds, nowMs)
        if (newly > 0) {
            if (CredentialStore.save(dir, stamped)) {
                stamp(
                    legacyDraft("legacy 凭据迁移 $newly 条", newly, nowMs),
                    nowMs,
                    "legacy 凭据迁移",
                )
            }
        }
        val expired = LegacyCredentials.expiredCount(stamped, nowMs)
        val noticedAt = CredentialStore.legacyNoticeAtMs(dir)
        if (expired > 0 && noticedAt == 0L) {
            RunLog.log(
                "凭据迁移：legacy 已到期 $expired 条（迁移期止于签发后 " +
                    "${LegacyCredentials.DEFAULT_TTL_MS / DAY_MS} 天）——已自动失效，需用在用身份重签（提示一次）",
            )
            // 提示过就记账；save 会把这枚时刻原样带进新表（见 [CredentialStore.save]）。
            CredentialStore.save(dir, stamped)
            stamp(legacyDraft("legacy 凭据到期提示（$expired 条）", expired, nowMs), nowMs, "legacy 凭据到期")
        }
    }

    private fun legacyDraft(what: String, count: Int, atMs: Long): DecisionDraft =
        DecisionDraft(
            kind = DecisionKind.IDENTITY_STATE,
            actor = DecisionActor.AUTO,
            reason = "$what（到期日=绝对时刻，落绑定表；Q1 迁移期 ${LegacyCredentials.DEFAULT_TTL_MS / DAY_MS} 天）",
            evidence = "${ConsequenceEvidence.IDENTITY}legacy:$count",
            before = "legacy",
            after = if (count > 0) "stamped" else "none",
            atMs = atMs,
            rollbackRef = "",
            reversible = false,
        )

    /** 测试收尾用（同 `DecisionLedger.uninstallForTest` 那条「谁装谁还」）。 */
    internal fun uninstallForTest() {
        installedDir = null
        cachedExecutor = ""
        DecisionLedger.executorId = { "" }
    }

    /** 现在盖章用的身份 id（没装配 ⇒ 空串）。 */
    fun executorId(): String = cachedExecutor

    /**
     * 后果链装上了没有（568-B 域①的自调闸用）：没装配 ⇒ 阈值不自调、扫档不跑——
     * "机制没装配 ⇒ 不自己跑"与 568-A 的 LowRiskEvo 同一条纪律；判据层读到的是缺省档，
     * 但**写口**（提议落行）必须整条链在场才许动。
     */
    fun installed(): Boolean = installedDir != null

    /**
     * 改身份（撤销／轮换／铸新；517 U2 起**没有**"解除撤销"这个动词）：判据拒 ⇒ 盘和账都不动；
     * 判据放行 ⇒ 先盘后账。
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
    fun revocationNote(nowMs: Long): String {
        val dir = installedDir ?: return "身份机制没装配"
        val rows = IdentityStore.load(dir)
        val denied = CredentialBinding.deniedCountAfterRevocation(CredentialStore.load(dir), rows, nowMs)
        return ConsequenceLedger.denialNote(denied)
    }

    /**
     * 体检单「执行体」那一格（U1，517）：
     * `执行体：<id 前8> · 凭据 N 条 · 最近撤销 <绝对时刻>`。
     *
     * 读的全是表（身份表＋凭据绑定表），没有第二处真相；时刻走 [AbsoluteTimeGuard.absoluteOrBlank]
     * ——非空必是 ISO-8601 带时区的绝对时刻（U5）。
     * 四种说全：
     *   · 机制没装配 ⇒ 明说没装配（不装假绿）；
     *   · 恢复后缺身份（U4）⇒ 「需铸新身份」——人工入口在那句里点名，不许静默生成；
     *   · 已撤销（U2）⇒ 在用 id 为空，撤销时刻照实给，恢复的路只有铸新；
     *   · 在用 ⇒ id 前 8 位（前缀 `zdx-` 之外的有效段，全串太长体检单放不下，全 id 查账表可回）。
     */
    fun executorSheetLine(nowMs: Long = System.currentTimeMillis()): String {
        val dir = installedDir ?: return "执行体：机制没装配"
        val rows = IdentityStore.load(dir)
        val creds = CredentialStore.load(dir)
        val lastRevoked = AbsoluteTimeGuard.absoluteOrBlank(
            rows.filter { it.revokedAtMs > 0L }.maxOfOrNull { it.revokedAtMs } ?: 0L,
        )
        val tail = "凭据 ${creds.size} 条 · 最近撤销 ${lastRevoked.ifBlank { "无" }}"
        val active = IdentityRules.activeId(rows)
        if (needsIdentityMint()) {
            return "执行体：需铸新身份（U4：身份不进备份，恢复后人工铸新）· $tail"
        }
        if (active.isBlank()) {
            return "执行体：已撤销（U2：不可逆，恢复只能铸新身份）· $tail"
        }
        val shortId = active.removePrefix(IdentityFormat.PREFIX).take(8)
        val legacyDue = LegacyCredentials.expiredCount(creds, nowMs)
        val legacyNote = if (legacyDue > 0) " · legacy 到期 $legacyDue 条（已失效，需重签）" else ""
        return "执行体：$shortId · $tail$legacyNote"
    }

    /**
     * 人工铸新身份（U3）的装配口——**只转手**：actor 原样递进判据，
     * 模型与装配层调进来会在 [IdentityRules.refusalOf] 撞墙（那面墙是唯一一堵，本层不加第二道）。
     *
     * @return 一句话结局（同 [mutateIdentity]）
     */
    fun mintIdentity(nowMs: Long, targetId: String = ""): String =
        mutateIdentity(IdentityMutation(actor = HUMAN_ACTOR, op = IdentityRules.OP_MINT, targetId = targetId), nowMs)

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

    /**
     * 现在生效的判定阈值调整值（568-B 域①，从账读；没有调整行 ⇒ null＝调用方用政策表原值）。
     *
     * 档位与深度同一把尺：账上有行才生效，被回滚的行不算。
     */
    fun currentThreshold(capability: String): Int? =
        ConsequenceLedger.currentThreshold(rows(), capability)

    /**
     * 落一条判定阈值调整行（568-B 域①；**只转手**：区间判据在中风险门里判过才许调到这里，
     * 本函数自己一条判都不加——与 [decideCanaryBand] 的「读的是账、算的是指标、写的还是账」同款边界）。
     *
     * @return 账落上没有（盘写不上只 log，不抛——记录是留痕不是闸门）
     */
    fun applyThresholdChange(c: ThresholdChange): Boolean =
        stamp(ConsequenceLedger.draftOf(c), c.atMs, "判定阈值 ${c.capability}")

    /**
     * 现在生效的工具白名单调整值（568-C 域①，从账读；没有调整行 ⇒ null＝调用方用缺省集）。
     * 档位与深度同一把尺：账上有行才生效，被回滚的行不算。
     */
    fun currentWhitelist(capability: String): String? =
        ConsequenceLedger.currentWhitelist(rows(), capability)

    /**
     * 落一条工具白名单调整行（568-C 域①；**只转手**：方向判据与加宽准入在高风险门里判过才许调到这里，
     * 本函数自己一条判都不加——与 [applyThresholdChange] 同款边界）。
     *
     * @return 账落上没有（盘写不上只 log，不抛——记录是留痕不是闸门）
     */
    fun applyWhitelistChange(c: WhitelistChange): Boolean =
        stamp(ConsequenceLedger.draftOf(c), c.atMs, "工具白名单 ${c.capability}")

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

    /** 一天的毫秒数（legacy 迁移期与日志里那句话共用）。 */
    private const val DAY_MS: Long = 24L * 3_600_000L

    /** 备份恢复标记的文件名（`执行体/` 私有树下；备份不含这棵树 ⇒ 标记不会被恢复覆盖）。 */
    private const val RESTORE_MARKER: String = "恢复标记.json"

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
