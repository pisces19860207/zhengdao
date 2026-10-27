// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第二百三十九批 §2：勘验的**取数壳**——六项判据全是纯函数（`*Check.kt`），
// 脏活（读库、读盘、读台账）集中在这一个文件里，界面与用例都不碰 SQL。
//
// 三条纪律（都有源码门禁钉着，写在 `InspectReadOnlyGateTest`）：
// 1. **只读**：本文件只有 `rawQuery`，一个 `execSQL`/`writeText`/`delete` 都没有；
// 2. **每条 SQL 都带 `?` 绑定与 `LIMIT`**（§2.2 抽检而非全检——扫全库的负例就在这一层犯）；
// 3. **输入只有日志与账本**：不接 `LlmClient`、不拼 prompt、不吃模型自述（§7.3 第三条）。
//
// 一处口径必须先说清（不然 I1 就成了猜）：**「哪些工具算写侧」**。
// 生产里这个事实住在 `ToolSpec.readOnly`，而拿它要把整张注册表装一遍
// （`BaopuToolCatalog.loadInto` 会顺手改掉 `CapabilitiesTool.sink` 这个进程级单例——
// 一次巡按不该有这种副作用）。所以这里改用 219 的 `RiskTable.DEFAULT`：
// 那张表里 `writePathKeys`/`deleteLike`/`externalTargetFlags` 就是「会不会改盘」的同一份事实，
// 而 `writeToolNames` 的产物由用例钉住名册（少一个名字，用例就红）。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.BaopuDb
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.DecisionReview
import com.example.zhengdao.baopu.RiskTable
import com.example.zhengdao.baopu.SkillRules
import com.example.zhengdao.baopu.agent.AnswerGate
import java.io.File
import org.json.JSONObject

/** 一次巡按的读盘上限（池子大小；抽检的 k 在判据侧，别混在这里）。 */
internal object PatrolBudget {

    /** 日志类一次最多读这么多行（比 [InspectLimits.READ_LIMIT] 小：手机上一天几百行 Acting）。 */
    const val LOG_ROWS = 1_200

    /** I2 的答案池：抽 10 条前先在最近 [ANSWER_POOL] 条里等距取（不是全库）。 */
    const val ANSWER_POOL = 30

    /** 一条答案最多回看几段工具回灌当"表"。 */
    const val GROUND_ROWS = 8

    /** I3 的技能目录扫描上限（数目录便宜，读正文贵 ⇒ 只读抽中的那几条）。 */
    const val SKILL_DIR_POOL = 200
}

/** 勘验的取数口。 */
internal object PatrolSources {

    /** 记忆根（208 装配在哪，巡按的三本账就落在哪；没装配 ⇒ null，调用方直接不巡）。 */
    fun memoryRoot(): File? = DecisionLedger.installedRoot()

    /** 208 账本（读侧上限照 [InspectLimits.READ_LIMIT]）。 */
    fun ledger(root: File): List<DecisionRecord> = DecisionLedger.read(root)

    /** 219 的抽检结论（§7.2 通过率的唯一分母来源）。 */
    fun reviews(root: File): List<DecisionReview.Entry> = DecisionReview.read(root)

    /** I6 的影子台账。 */
    fun shadows(root: File): List<ShadowEntry> = ShadowLedger.read(root)

    /**
     * 写侧工具名册（"会不会改盘"）。
     *
     * 三个标记任一为真就算写侧：写目标参数、删除类、会把东西写到工作区外的布尔位。
     * `exempt` 名单（终端那类不在四档内的）永远不进名册——它压根不走 208 这套账。
     */
    fun writeToolNames(table: RiskTable = RiskTable.DEFAULT): Set<String> =
        table.tools.entries
            .filter { (name, rule) ->
                name !in table.exempt &&
                    (rule.writePathKeys.isNotEmpty() || rule.deleteLike || rule.externalTargetFlags.isNotEmpty())
            }
            .map { it.key }
            .toSet()

    /** 写侧动作痕迹（`run_log` 的 Acting 行，成功且工具在写侧名册里）。 */
    fun writeTraces(
        db: BaopuDb,
        fromMs: Long,
        writeNames: Set<String> = writeToolNames(),
        limit: Int = PatrolBudget.LOG_ROWS,
    ): List<WriteTrace> {
        if (writeNames.isEmpty()) return emptyList()
        val marks = writeNames.joinToString(",") { "?" }
        return runCatching {
            db.read { d ->
                val rows = ArrayList<WriteTrace>()
                d.rawQuery(
                    "SELECT created_at, tool, session_id, step FROM run_log " +
                        "WHERE state = 'Acting' AND ok = 1 AND created_at >= ? AND tool IN ($marks) " +
                        "ORDER BY id DESC LIMIT ?",
                    (listOf(fromMs.toString()) + writeNames + listOf(limit.toString())).toTypedArray(),
                ).use { c -> while (c.moveToNext()) rows += WriteTrace(c.getLong(0), c.getString(1), c.getLong(2), c.getInt(3)) }
                rows
            }
        }.getOrDefault(emptyList())
    }

    /** 摆卡痕迹（`run_log.state = 'AwaitConfirm'`；I4 的分子分母与 I6 的联键都从这来）。 */
    fun confirmTraces(db: BaopuDb, fromMs: Long, limit: Int = PatrolBudget.LOG_ROWS): List<ConfirmTrace> =
        runCatching {
            db.read { d ->
                val rows = ArrayList<ConfirmTrace>()
                d.rawQuery(
                    "SELECT created_at, session_id, step, tool FROM run_log " +
                        "WHERE state = 'AwaitConfirm' AND created_at >= ? ORDER BY id DESC LIMIT ?",
                    arrayOf(fromMs.toString(), limit.toString()),
                ).use { c -> while (c.moveToNext()) rows += ConfirmTrace(c.getLong(0), c.getLong(1), c.getInt(2), c.getString(3)) }
                rows
            }
        }.getOrDefault(emptyList())

    /** 任务数（`run_trace` 的 distinct run_id；I4 确认密度的分母）。 */
    fun taskCount(db: BaopuDb, fromMs: Long, limit: Int = PatrolBudget.LOG_ROWS): Int = runCatching {
        db.read { d ->
            var n = 0
            d.rawQuery(
                "SELECT COUNT(DISTINCT run_id) FROM (SELECT run_id FROM run_trace " +
                    "WHERE created_at >= ? ORDER BY id DESC LIMIT ?)",
                arrayOf(fromMs.toString(), limit.toString()),
            ).use { c -> if (c.moveToNext()) n = c.getInt(0) }
            n
        }
    }.getOrDefault(0)

    /** I5 的失败痕迹：`skill_event` 里 `kind='use' AND ok=0`（正文的 uses 计数不动，取事件账）。 */
    fun failures(db: BaopuDb, fromMs: Long, limit: Int = PatrolBudget.LOG_ROWS): List<SkillFailure> =
        runCatching {
            db.read { d ->
                val rows = ArrayList<SkillFailure>()
                d.rawQuery(
                    "SELECT name, at FROM skill_event WHERE kind = 'use' AND ok = 0 AND at >= ? " +
                        "ORDER BY id DESC LIMIT ?",
                    arrayOf(fromMs.toString(), limit.toString()),
                ).use { c -> while (c.moveToNext()) rows += SkillFailure(c.getString(0), c.getLong(1)) }
                rows
            }
        }.getOrDefault(emptyList())

    /**
     * I2 的答案池（助手正文 + 同一会话里在它之前的工具回灌当"表"）。
     *
     * `ground` 只从 `part(kind='tool')` 拼——那 227 的口径（"表里的读数"就是回灌原文），
     * 也是本批 §7.3 的口径：**证据来自账，不来自模型的自述**。
     */
    fun answerPool(db: BaopuDb, fromMs: Long, pool: Int = PatrolBudget.ANSWER_POOL): List<AnswerSample> =
        runCatching {
            db.read { d ->
                val heads = ArrayList<Triple<Long, Long, Long>>() // (messageId, sessionId, atMs)
                d.rawQuery(
                    "SELECT p.message_id, m.session_id, p.created_at FROM part p " +
                        "JOIN message m ON m.id = p.message_id " +
                        "WHERE p.kind = 'text' AND m.role = 'assistant' AND p.created_at >= ? " +
                        "ORDER BY p.id DESC LIMIT ?",
                    arrayOf(fromMs.toString(), pool.toString()),
                ).use { c -> while (c.moveToNext()) heads += Triple(c.getLong(0), c.getLong(1), c.getLong(2)) }
                heads.mapNotNull { (msgId, sessionId, at) ->
                    val answer = textOf(d, msgId) ?: return@mapNotNull null
                    if (answer.isBlank()) return@mapNotNull null
                    AnswerSample(
                        messageId = msgId,
                        sessionId = sessionId,
                        atMs = at,
                        answer = answer,
                        ground = groundOf(d, sessionId, at),
                    )
                }
            }
        }.getOrDefault(emptyList())

    /** 一条助手消息的正文（取第一条 text part；多段按 seq 拼到上限为止）。 */
    private fun textOf(d: android.database.sqlite.SQLiteDatabase, messageId: Long): String? = runCatching {
        val sb = StringBuilder()
        d.rawQuery(
            "SELECT payload FROM part WHERE message_id = ? AND kind = 'text' ORDER BY seq ASC LIMIT 4",
            arrayOf(messageId.toString()),
        ).use { c -> while (c.moveToNext() && sb.length < AnswerGate.MAX_GROUND_CHARS) sb.append(c.getString(0)).append('\n') }
        sb.toString().ifEmpty { null }
    }.getOrNull()

    /** 本轮工具回灌（同会话、这条回答之前的若干段 `result`）。 */
    private fun groundOf(d: android.database.sqlite.SQLiteDatabase, sessionId: Long, atMs: Long): String = runCatching {
        val sb = StringBuilder()
        d.rawQuery(
            "SELECT payload FROM part WHERE kind = 'tool' AND created_at <= ? " +
                "AND message_id IN (SELECT id FROM message WHERE session_id = ?) " +
                "ORDER BY id DESC LIMIT ?",
            arrayOf(atMs.toString(), sessionId.toString(), PatrolBudget.GROUND_ROWS.toString()),
        ).use { c ->
            while (c.moveToNext() && sb.length < AnswerGate.MAX_GROUND_CHARS) {
                // payload 房型 = {"tool":..,"step":..,"result":..}（RunEngine.persistToolExchange）
                val result = runCatching { JSONObject(c.getString(0)).optString("result") }.getOrNull()
                sb.append(result ?: c.getString(0)).append('\n')
            }
        }
        sb.toString()
    }.getOrDefault("")

    /**
     * I3 的技能样本（**两层目录一起**：项目优先、全局兜底）。
     *
     * 只 `listFiles` 数目录，正文**只读抽中的那 k 条**——先数再读是这条检查便宜的关键：
     * 真机上技能目录一两百个，全读正文就是 §5④ 点名的"全检拖垮手机"。
     */
    fun skillPool(
        projectRoot: File,
        globalRoot: File,
        k: Int = ContractHollowCheck.SAMPLE_K,
    ): List<SkillSample> {
        val dirs = listOf(
            "project" to SkillRules.projectSkillDir(projectRoot),
            "global" to SkillRules.globalSkillDir(globalRoot),
        )
        val pool = ArrayList<Triple<String, String, File>>()
        dirs.forEach { (scope, dir) ->
            val subs = runCatching {
                dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
            }.getOrDefault(emptyList())
            subs.take(PatrolBudget.SKILL_DIR_POOL).forEach { pool += Triple(scope, it.name, File(it, SkillRules.SKILL_FILE)) }
        }
        if (pool.isEmpty()) return emptyList()
        val picked = InspectSample.spread(pool, k.coerceAtLeast(1))
        return picked.mapNotNull { (scope, name, file) ->
            if (!file.isFile) return@mapNotNull null
            val body = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return@mapNotNull null
            SkillSample(name = name, body = body, scope = scope)
        }
    }

    /**
     * 现在生效的额度档（**从 208 读回来**，不另开一份 prefs）。
     *
     * 为什么不在 SharedPreferences 里存一份：§7.2 要求"每次额度变动写 208、可回滚"。
     * 账本一份、开关一份，回滚就只改得动账本改不动开关——决策中心点了撤销，
     * 引擎还在按新档免问，那是最坏的一种"看起来有账"。所以档的唯一真相就是最新那行账，
     * 而且 **已被回滚的那行不算**（`rolledBack` 由 208 读侧回填）。
     */
    fun currentBand(ledger: List<DecisionRecord>): AllowanceBand =
        ledger.filter { it.kind == DecisionKind.TOOL_GRANT && !it.rolledBack }
            .filter { it.evidence.startsWith(AllowanceLadder.EVIDENCE_PREFIX) }
            .sortedByDescending { it.atMs }
            .firstNotNullOfOrNull { bandOfEvidence(it.evidence) }
            ?: AllowanceBand.DEFAULT

    /** `allowance:<档>:<巡按 id>` ⇒ 档；认不出 ⇒ null（别的批次写的行不冒领额度档）。 */
    fun bandOfEvidence(evidence: String): AllowanceBand? {
        if (!evidence.startsWith(AllowanceLadder.EVIDENCE_PREFIX)) return null
        val body = evidence.substring(AllowanceLadder.EVIDENCE_PREFIX.length)
        return AllowanceBand.ofWire(body.substringBefore(':').trim())
    }

    /**
     * 「这条技能已经被收拾过」的名字集合（I5 的豁免口）。
     *
     * 三种证据都算：208 的退役行（`evidence = "skill:<层>/<名>"`）、
     * 正文软删成 `SKILL.md.deleted-*`、（将来）待学完名单——最后一种本仓还没有，
     * 所以传空集进来即可，**不许拿"没查"当"查过没问题"**。
     */
    fun archivedSkillNames(ledger: List<DecisionRecord>, projectRoot: File, globalRoot: File): Set<String> {
        val fromLedger = ledger.asSequence()
            .filter { it.kind == DecisionKind.SKILL_RETIRE || it.kind == DecisionKind.SKILL_DRAFT_RETIRE }
            .map { it.evidence.removePrefix("skill:").substringAfterLast('/') }
            .filter { it.isNotBlank() }
            .toSet()
        val fromDisk = mutableSetOf<String>()
        listOf(SkillRules.projectSkillDir(projectRoot), SkillRules.globalSkillDir(globalRoot)).forEach { dir ->
            val subs = runCatching { dir.listFiles()?.filter { it.isDirectory }.orEmpty() }.getOrDefault(emptyList())
            subs.forEach { sub ->
                val marked = runCatching {
                    sub.listFiles { f -> f.isFile && f.name.startsWith("${SkillRules.SKILL_FILE}.deleted-") }
                }.getOrNull()
                if (!marked.isNullOrEmpty()) fromDisk += sub.name
            }
        }
        return fromLedger + fromDisk
    }

    /**
     * 该跑影子的动作痕迹（I6 取数：写侧痕迹 ＋ 219 的档判据）。
     *
     * 这里**不重跑分类器**（那要拿 `ToolCall`＋参数，巡按只有工具名）——
     * 按名册的档底判：删除类与写盘类都属"会改盘"，一律要求影子；
     * 底档 R2/R3 的工具名同样进名单。拿不准的一律算"该跑"（fail-closed）。
     */
    fun shadowTraces(
        db: BaopuDb,
        fromMs: Long,
        table: RiskTable = RiskTable.DEFAULT,
    ): List<ShadowTrace> =
        writeTraces(db, fromMs, writeToolNames(table)).map { t ->
            ShadowTrace(
                atMs = t.atMs,
                sessionId = t.sessionId,
                step = t.step,
                tool = t.tool,
                requiresShadow = ShadowRehearsal.shouldRehearse(baseRiskOf(t.tool, table), reversible = true),
            )
        }

    /** 工具名 ⇒ 219 表里的底档（表里没有 ⇒ null＝没判过，按最严处理）。 */
    fun baseRiskOf(tool: String, table: RiskTable = RiskTable.DEFAULT): com.example.zhengdao.baopu.RiskClass? =
        table.tools[tool]?.base

    /**
     * 摆卡 → 落账 的间隔样本（I4「秒批率」的唯一来源；纯函数，不碰库）。
     *
     * 为什么只能推算：批准那一刻**没有专门落一列**（`enterAwaitConfirm` 只写摆卡行，
     * 用户点完之后的第一步落账是 `Acting` 行）。所以拿"同 (会话,步骤) 的第一条 Acting 行"
     * 当批准的近似时刻——它必然**晚于**真实点击，于是间隔偏大、秒批率偏低：
     * **只可能漏报，不可能冤枉人**。这个方向写在建议句里，不藏。
     */
    fun approvalGaps(acts: List<WriteTrace>, confirms: List<ConfirmTrace>): List<ApprovalGap> =
        confirms.mapNotNull { c ->
            val next = acts.filter { it.sessionId == c.sessionId && it.step == c.step && it.atMs >= c.atMs }
                .minByOrNull { it.atMs }
                ?: return@mapNotNull null
            ApprovalGap(confirmAtMs = c.atMs, sessionId = c.sessionId, step = c.step, deltaMs = next.atMs - c.atMs)
        }

    /** 免问直行的写动作数（同 (会话,步骤) 没摆过卡）。 */
    fun silentActCount(acts: List<WriteTrace>, confirms: List<ConfirmTrace>): Int =
        acts.count { MediatorBiasCheck.isSilent(it, confirms) }

    /**
     * 被回滚掉的"免问动作"数（I4 第一条率的分子；纯函数）。
     *
     * 链路：`rollback_of` → 被回滚那条（必须是 `auto_act`）→ 按「同名＋时刻容差」
     * 配到某条 Acting 痕迹 → 那条痕迹同 (会话,步骤) 有没有摆过卡。
     * 配不上痕迹的回滚**不计入分子**（只会在明细里如实标"配不上"），
     * 因为把"不知道"算成"免问出事"就是冤枉调和器。
     */
    fun silentRollbackCount(
        ledger: List<DecisionRecord>,
        acts: List<WriteTrace>,
        confirms: List<ConfirmTrace>,
        toleranceMs: Long = InspectLimits.GAP_TOLERANCE_MS,
    ): Int {
        val byId = ledger.associateBy { it.id }
        val actToolAt = acts.map { it.tool to it.atMs }
        var n = 0
        ledger.filter { it.kind == DecisionKind.ROLLBACK_OF }.forEach { rb ->
            val origin = rb.rollbackOf?.let { byId[it] }
                ?.takeIf { it.kind == DecisionKind.AUTO_ACT }
                ?: return@forEach
            val tool = AuditGapCheck.toolOfEvidence(origin.evidence) ?: return@forEach
            val paired = actToolAt.firstOrNull { (name, at) -> name == tool && kotlin.math.abs(at - origin.atMs) <= toleranceMs }
                ?: return@forEach
            val act = acts.first { it.tool == paired.first && it.atMs == paired.second }
            if (MediatorBiasCheck.isSilent(act, confirms)) n++
        }
        return n
    }
}
