// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第三百四十二批 · 任务契约对象化（设计稿：私有侧 `docs/设计-任务契约对象化-2026-11-01.md`）。
//
// 一句话：把"一张军令状"收敛成**一个可序列化、可校验、可回执的对象**，六样字段全是
// 已有机制的形状，本文件不发明新制度：
//   · `goal`         ← 军令状（312 `baopu/plan/PlanPledge.kt`：`goal` ＋每项 `accept`）
//   · `deliverables` ← 交付物＋每项验收契约（右相输入 `baopu/verify/ChancellorInput.kt` 的 `ChancellorDeliverable`；
//                      任务类型×契约表归 332，本层**只承载声明、不做判定**）
//   · `authority`    ← 授权边界（`baopu/authz/TaskGrant.kt` 的对象面／动作面）
//   · `budget`       ← 有界额度（墙钟照 `GrantLimits.MAX_LIFETIME_MONO_MS`、步数照 `BaopuKernel.MAX_STEPS`、
//                      打扰次数照 `inspect/OpinionBudget.kt` 的 `perDayRoyalMax`）
//   · `evidence`     ← 六类证据（`exists`／`sha256`／CI／测试 XML／账行／法条门禁，340 派工单前言点名的那套）
//   · `exit`         ← 暂停／取消／超时／失败（失败四动作照 `agent/FailurePolicy.kt` 的 `FailureAction`，
//                      回滚照 `law/RevocationSlip.kt`，超时交人照 `law/TimeoutFallback.kt`）
//   · `TaskReceipt`  ← 外部执行体**只能交这个**：accepted／rejected／running／done／failed ＋证据
//
// **本批只做对象，不发布任何协议**：没有 A2A／REST／Webhook，没有网络，没有磁盘写入，
// 也没有任何生产调用方（接线归后续批）。设计稿 §1 的理由照抄：闭环没验完就发接口＝把没验过的
// 东西许给别人，而接口一旦发布就是承诺。
//
// 三条硬规矩（每条都有用例钉，见 `TaskContractTest`）：
// 1. **done ≠ 验收通过**：回执五态是执行体**自报**的，`done` 只表示"我交完了"；合格与否在右相。
//    所以回执类型里**没有验收字段**，JSON 里夹带验收键 ⇒ 整行拒收（不是忽略，是拒收）。
// 2. **外部执行体不许写我们的账本**：回执只能**引用**账行（`EvidenceKind.LEDGER` 是一条引用），
//    不能携带写账指令 ⇒ 夹带账本键同样整行拒收；且本文件零 I/O（源码级门禁反钉）。
// 3. **外发永远为否**：`TaskAuthority.egressAllowed` 不是构造参数而是恒 `false` 的只读属性，
//    JSON 里出现 `egress=true` ⇒ 拒收。读写范围可以谈，外发没得谈。
//
// `:core` 的依赖方向门禁（`CoreBoundaryGateTest`）不许本文件 import `baopu.**`，所以上面那些
// 真源只能抄形状＋标出处，不能直接引用常量；四个额度上限就是这份抄本，**收成一个真源是移交项**
// （交付报告 §6）。
package com.example.zhengdao.core

import java.nio.file.Paths
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

// ── 闭集词表 ─────────────────────────────────────────────────────────────

/**
 * 六类证据（340 派工单前言点名的那套）。
 *
 * 口径照 332 派工单：**每种证据只证明有限事实**——`sha256` 一致只说明字节相同，不说明内容符合意图。
 * 所以本枚举回答的是"必须交什么"，不回答"合不合格"；后者是右相拿着 332 契约表判的事。
 *
 * [LEDGER] 是一条**账行引用**（"账在某某行"），不是写账指令——写账是我们的事，见文件头硬规矩 2。
 */
enum class EvidenceKind(val wire: String, val cn: String) {
    EXISTS("exists", "产物存在"),
    SHA256("sha256", "字节一致"),
    CI("ci", "CI 出件"),
    TEST_XML("test_xml", "测试 XML"),
    LEDGER("ledger", "账行引用"),
    LAW_GATE("law_gate", "法条门禁"),
    ;

    companion object {
        fun ofWire(raw: String): EvidenceKind? = entries.firstOrNull { it.wire == raw.trim().lowercase() }
    }
}

/**
 * 回执五态——外部执行体**自报**到哪一步。
 *
 * 两处最容易读错，故各标一句：
 * - [ACCEPTED] ＝"我接单了"，**不是**"验收通过"；
 * - [DONE]     ＝"我交完了"，**不是**"合格"。
 * 验收通过没有对应取值，这是刻意的：回执这个类型里放不下"右相已判合格"这件事。
 */
enum class ReceiptState(val wire: String, val cn: String) {
    ACCEPTED("accepted", "接单"),
    REJECTED("rejected", "拒单"),
    RUNNING("running", "在做"),
    DONE("done", "交完"),
    FAILED("failed", "失败"),
    ;

    companion object {
        fun ofWire(raw: String): ReceiptState? = entries.firstOrNull { it.wire == raw.trim().lowercase() }
    }
}

/**
 * 出口动作（闭集）。
 *
 * 失败档四个取值与 `agent/FailurePolicy.kt` 的 `FailureAction`（Retry／Fallback／AskUser／Abort）
 * 逐字对齐；`ROLLBACK` 与 `KEEP_MARKED` 分别照 `law/RevocationSlip.kt`（撤销单带回滚点）
 * 与 208 批"追加新行不改旧行"的留存纪律。哪个出口允许哪几个动作，见 [TaskExit]。
 */
enum class ExitAction(val wire: String, val cn: String) {
    HOLD("hold", "挂起保留现场"),
    KEEP_MARKED("keep_marked", "留存并记账"),
    ROLLBACK("rollback", "回滚到快照"),
    ABORT("abort", "中止"),
    RETRY("retry", "重试"),
    FALLBACK("fallback", "换通道降级"),
    ASK_USER("ask_user", "交人"),
    ;

    companion object {
        fun ofWire(raw: String): ExitAction? = entries.firstOrNull { it.wire == raw.trim().lowercase() }
    }
}

// ── 六样之一：目标 ────────────────────────────────────────────────────────

/**
 * 任务目标（一句话＋判据）。
 *
 * @param text 目标本身，一行（上限 [MAX_TEXT_CHARS]，与 `SubAgentRules.MAX_GOAL_CHARS` 同口径）。
 * @param criteria 判据清单——"怎么算做到了"，每条一句可机验的话；**不许写价值判断词**
 *   （禁词表照 `verify/GapItem.BANNED_VALUE_WORDS`）。
 */
data class TaskGoal(val text: String, val criteria: List<String>) {

    fun toJson(): JSONObject = JSONObject()
        .put(KEY_TEXT, text)
        .put(KEY_CRITERIA, JSONArray(criteria))

    companion object {
        const val KEY_TEXT = "text"
        const val KEY_CRITERIA = "criteria"
        val KEYS = setOf(KEY_TEXT, KEY_CRITERIA)

        /** 目标一句话的字数上限（同 `SubAgentRules.MAX_GOAL_CHARS`）。 */
        const val MAX_TEXT_CHARS = 200

        /** 一条判据的字数上限（同 `SubAgentRules.MAX_ACCEPTANCE_CHARS`：一行放得下才算"一句话"）。 */
        const val MAX_CRITERION_CHARS = 200

        /** 禁词表（照 `verify/GapItem.BANNED_VALUE_WORDS` 的三个字，一个字不许改）。 */
        val BANNED_VALUE_WORDS = listOf("建议", "应该", "更好")

        fun from(o: JSONObject): TaskGoal? {
            if (o.unknownKeysIn(KEYS).isNotEmpty()) return null
            val text = o.needString(KEY_TEXT) ?: return null
            val criteria = o.needArray(KEY_CRITERIA)?.stringValuesOrNull() ?: return null
            return TaskGoal(text = text, criteria = criteria)
        }
    }
}

// ── 六样之二：交付物 ──────────────────────────────────────────────────────

/**
 * 一项交付物＋它的验收契约。
 *
 * @param id 项号（同一张契约内唯一，回执与账行都按它对齐）。
 * @param what 交的是什么（一句话）。
 * @param acceptance 验收契约——逐条"过/不过"都判得出来的话；332 的任务类型表往这里挂，本层不判。
 */
data class TaskDeliverable(
    val id: String,
    val what: String,
    val acceptance: List<String>,
) {

    fun toJson(): JSONObject = JSONObject()
        .put(KEY_ID, id)
        .put(KEY_WHAT, what)
        .put(KEY_ACCEPTANCE, JSONArray(acceptance))

    companion object {
        const val KEY_ID = "id"
        const val KEY_WHAT = "what"
        const val KEY_ACCEPTANCE = "acceptance"
        val KEYS = setOf(KEY_ID, KEY_WHAT, KEY_ACCEPTANCE)

        /** 一张契约最多几项交付物（照 `GrantLimits.MAX_OBJECTS`＝10）。 */
        const val MAX_DELIVERABLES = 10

        /** 一条验收契约的字数上限（同 `SubAgentRules.MAX_ACCEPTANCE_CHARS`）。 */
        const val MAX_ACCEPTANCE_CHARS = 200

        fun from(o: JSONObject): TaskDeliverable? {
            if (o.unknownKeysIn(KEYS).isNotEmpty()) return null
            val id = o.needString(KEY_ID) ?: return null
            val what = o.needString(KEY_WHAT) ?: return null
            val acceptance = o.needArray(KEY_ACCEPTANCE)?.stringValuesOrNull() ?: return null
            return TaskDeliverable(id = id, what = what, acceptance = acceptance)
        }
    }
}

// ── 六样之三：授权边界 ────────────────────────────────────────────────────

/**
 * 授权边界：能读哪、能写哪、能不能外发。
 *
 * 两条内置规矩，不靠调用方自觉：
 * 1. **外发永远为否**——[egressAllowed] 不是构造参数，是恒 `false` 的只读属性；序列化写出 `false`，
 *    解析遇到 `true` 判形状不合格（见文件头硬规矩 3）。
 * 2. **写必在读内**——每个 [writeRoots] 根必须落在某个 [readRoots] 根之内；要写就先要能读，
 *    反过来（写了没读过）在账上对不出证据。
 *
 * 路径是**声明**（相对沙盒根的 posix 风格路径），不是文件系统调用：本层不做存在性判定，
 * 越界解释由装配层按 `PathGuard` 的组件级口径处理。词法判 `..` 与 `PathGuard.linkStaysInside`
 * 同一口径（纯词法归一，不碰盘）。
 */
data class TaskAuthority(
    val readRoots: List<String>,
    val writeRoots: List<String> = emptyList(),
) {

    /** 能不能外发：恒否。想改它，得先改这个类型——而那是另一件事、另一张派工单。 */
    val egressAllowed: Boolean get() = false

    /** 落在读范围之外的写根（空表＝授权闭合）。 */
    fun writeRootsEscapingRead(): List<String> =
        writeRoots.filter { w -> readRoots.none { r -> staysInside(r, w) } }

    fun toJson(): JSONObject = JSONObject()
        .put(KEY_READ_ROOTS, JSONArray(readRoots))
        .put(KEY_WRITE_ROOTS, JSONArray(writeRoots))
        .put(KEY_EGRESS, egressAllowed)

    companion object {
        const val KEY_READ_ROOTS = "read_roots"
        const val KEY_WRITE_ROOTS = "write_roots"
        const val KEY_EGRESS = "egress"
        val KEYS = setOf(KEY_READ_ROOTS, KEY_WRITE_ROOTS, KEY_EGRESS)

        /** 最多声明几个根（读＋写合计，照 `GrantLimits.MAX_OBJECTS`＝10）。 */
        const val MAX_ROOTS = 10

        fun from(o: JSONObject): TaskAuthority? {
            if (o.unknownKeysIn(KEYS).isNotEmpty()) return null
            val read = o.needArray(KEY_READ_ROOTS)?.stringValuesOrNull() ?: return null
            val write = o.needArray(KEY_WRITE_ROOTS)?.stringValuesOrNull() ?: return null
            // 外发只能是无或 false；出现 true（或不是布尔）⇒ 这张单子不收。
            if (!o.isNull(KEY_EGRESS)) {
                val egress = o.needBoolean(KEY_EGRESS) ?: return null
                if (egress) return null
            }
            return TaskAuthority(readRoots = read, writeRoots = write)
        }

        /**
         * [path] 是否词法落在 [root] 内（组件级比较，与 `PathGuard.isInside` 同口径，但不碰文件系统）。
         * 根自身算在内（写满整个读范围是合法声明）。
         */
        fun staysInside(root: String, path: String): Boolean {
            val base = lexical(root) ?: return false
            val p = lexical(path) ?: return false
            return p.startsWith(base)
        }

        /** 声明式路径的词法归一；不合法（绝对／带 `..`／反斜杠／盘符／空）⇒ null。 */
        fun lexical(raw: String): java.nio.file.Path? {
            val s = raw.trim()
            if (s.isEmpty()) return null
            if (s.contains('\\')) return null
            // 分隔符开头一律拒：`Path.isAbsolute` 在 Windows JVM 上对 "/etc/passwd" 给 false
            // （它要求盘符＋根同时存在），只信它会让同一条判据在真机与单测上两种口径。
            if (s.startsWith("/")) return null
            if (DRIVE_PREFIX.matches(s)) return null
            val p = runCatching { Paths.get(s).normalize() }.getOrNull() ?: return null
            if (p.isAbsolute) return null
            if (p.nameCount == 0) return null
            return if (p.startsWith("..")) null else p
        }

        private val DRIVE_PREFIX = Regex("^[A-Za-z]:")
    }
}

// ── 六样之四：预算 ────────────────────────────────────────────────────────

/**
 * 预算：时间／步数／打扰次数，三档都是**上限**（不是申请额度）。
 *
 * 四个闸值各有真源，但 `:core` 不许 import `:app`（`CoreBoundaryGateTest` 钉死），
 * 所以这里是带出处的抄本——收成一个真源是移交项：
 * - [MAX_WALL_MS] ← `authz/TaskGrant.kt` 的 `GrantLimits.MAX_LIFETIME_MONO_MS`（60 分钟：一张授权的寿命上限）
 * - [MIN_WALL_MS] ← `agent/multiagent/SubAgentRules.kt` 的 `MIN_TIMEOUT_MS`（10 秒：低于它就是没打算干完）
 * - [MAX_STEPS]   ← `kernel/BaopuKernel.kt` 的 `MAX_STEPS`（3 段×100：引擎跑不出比这更多的步）
 * - [MAX_INTERRUPTIONS] ← `inspect/OpinionBudget.kt` 的 `perDayRoyalMax`（一天最多 3 次御批打扰）
 *
 * 设计稿第六行还写了"金额"，但代码里没有金额机制（只有 token 预算），本批按工单只做这三档。
 */
data class TaskBudget(
    val maxWallMs: Long,
    val maxSteps: Int,
    val maxInterruptions: Int,
) {

    fun toJson(): JSONObject = JSONObject()
        .put(KEY_MAX_WALL_MS, maxWallMs)
        .put(KEY_MAX_STEPS, maxSteps)
        .put(KEY_MAX_INTERRUPTIONS, maxInterruptions)

    companion object {
        const val KEY_MAX_WALL_MS = "max_wall_ms"
        const val KEY_MAX_STEPS = "max_steps"
        const val KEY_MAX_INTERRUPTIONS = "max_interruptions"
        val KEYS = setOf(KEY_MAX_WALL_MS, KEY_MAX_STEPS, KEY_MAX_INTERRUPTIONS)

        const val MIN_WALL_MS = 10_000L
        const val MAX_WALL_MS = 3_600_000L
        const val MIN_STEPS = 1
        const val MAX_STEPS = 300

        /** 打扰次数可以为 0（＝全程自决，一次都不问人），上限 3。 */
        const val MIN_INTERRUPTIONS = 0
        const val MAX_INTERRUPTIONS = 3

        fun from(o: JSONObject): TaskBudget? {
            if (o.unknownKeysIn(KEYS).isNotEmpty()) return null
            val wall = o.needLong(KEY_MAX_WALL_MS) ?: return null
            val steps = o.needInt(KEY_MAX_STEPS) ?: return null
            val interrupts = o.needInt(KEY_MAX_INTERRUPTIONS) ?: return null
            return TaskBudget(maxWallMs = wall, maxSteps = steps, maxInterruptions = interrupts)
        }
    }
}

// ── 六样之五：出口 ────────────────────────────────────────────────────────

/**
 * 出口：暂停／取消／超时／失败各走哪条。
 *
 * 四个槽都是必填（缺一个 ⇒ 拒收），因为"没写怎么办"恰恰是最贵的那种没写：真出事时只能靠猜。
 * 每槽允许的动作文是闭集（越界 ⇒ [TaskContract.violations] 记一条）：
 * - 暂停：[ExitAction.HOLD]（原样挂起）／[ExitAction.ROLLBACK]（回到快照再挂）
 * - 取消：[ExitAction.ROLLBACK]／[ExitAction.KEEP_MARKED]（留下已做的，记账）
 * - 超时：[ExitAction.ABORT]／[ExitAction.ROLLBACK]／[ExitAction.ASK_USER]（照 `TimeoutFallback` 的交人分支）
 * - 失败：[ExitAction.RETRY]／[ExitAction.FALLBACK]／[ExitAction.ASK_USER]／[ExitAction.ABORT]
 *   （与 `FailurePolicy.FailureAction` 四选一逐字对齐）
 *
 * 四个槽都**不许**出现"自动重签／自动扩大授权"这类动作：改契约是左相的事，出事时执行体只能停或问。
 */
data class TaskExit(
    val onPause: ExitAction,
    val onCancel: ExitAction,
    val onTimeout: ExitAction,
    val onFailure: ExitAction,
) {

    /** 越界的槽位（空表＝四槽合规）。 */
    fun outOfRangeSlots(): List<String> = listOf(
        KEY_ON_PAUSE to onPause,
        KEY_ON_CANCEL to onCancel,
        KEY_ON_TIMEOUT to onTimeout,
        KEY_ON_FAILURE to onFailure,
    ).filter { (slot, action) -> allowedFor(slot)?.contains(action) != true }
        .map { (slot, action) -> "$slot=${action.wire}（允许：${allowedFor(slot)?.joinToString("／") { it.wire }}）" }

    fun toJson(): JSONObject = JSONObject()
        .put(KEY_ON_PAUSE, onPause.wire)
        .put(KEY_ON_CANCEL, onCancel.wire)
        .put(KEY_ON_TIMEOUT, onTimeout.wire)
        .put(KEY_ON_FAILURE, onFailure.wire)

    companion object {
        const val KEY_ON_PAUSE = "on_pause"
        const val KEY_ON_CANCEL = "on_cancel"
        const val KEY_ON_TIMEOUT = "on_timeout"
        const val KEY_ON_FAILURE = "on_failure"
        val KEYS = setOf(KEY_ON_PAUSE, KEY_ON_CANCEL, KEY_ON_TIMEOUT, KEY_ON_FAILURE)

        val ON_PAUSE = setOf(ExitAction.HOLD, ExitAction.ROLLBACK)
        val ON_CANCEL = setOf(ExitAction.ROLLBACK, ExitAction.KEEP_MARKED)
        val ON_TIMEOUT = setOf(ExitAction.ABORT, ExitAction.ROLLBACK, ExitAction.ASK_USER)
        val ON_FAILURE = setOf(ExitAction.RETRY, ExitAction.FALLBACK, ExitAction.ASK_USER, ExitAction.ABORT)

        /** 某个槽允许哪几个动作；槽名不认识 ⇒ null。 */
        fun allowedFor(slot: String): Set<ExitAction>? = when (slot) {
            KEY_ON_PAUSE -> ON_PAUSE
            KEY_ON_CANCEL -> ON_CANCEL
            KEY_ON_TIMEOUT -> ON_TIMEOUT
            KEY_ON_FAILURE -> ON_FAILURE
            else -> null
        }

        fun from(o: JSONObject): TaskExit? {
            if (o.unknownKeysIn(KEYS).isNotEmpty()) return null
            val pause = o.needString(KEY_ON_PAUSE)?.let { ExitAction.ofWire(it) } ?: return null
            val cancel = o.needString(KEY_ON_CANCEL)?.let { ExitAction.ofWire(it) } ?: return null
            val timeout = o.needString(KEY_ON_TIMEOUT)?.let { ExitAction.ofWire(it) } ?: return null
            val failure = o.needString(KEY_ON_FAILURE)?.let { ExitAction.ofWire(it) } ?: return null
            return TaskExit(onPause = pause, onCancel = cancel, onTimeout = timeout, onFailure = failure)
        }
    }
}

// ── 契约本体 ──────────────────────────────────────────────────────────────

/**
 * 任务契约：一张军令状的可序列化形状（目标｜交付物｜授权｜预算｜证据｜出口）。
 *
 * 它是**载体不是裁判**：本类型能回答"这张单子许了什么、要交什么、越界了没"，
 * 不能回答"这件事做完了吗"——后者要证据、要 332 的契约表、要右相。
 * 所以这里没有 `passed`／`accepted`／`score` 任何一格：契约外发明"综合评分"是 332 明令的负例。
 *
 * 序列化口径照仓内既有形状（`EmergencyReceipt`／`encodeSnapshot` 一路的做法）：
 * 固定键序的 `toJson()` ＋ 严收未知键的 `parse()` ＋ 行格式版本 [VERSION]。
 * 校验分两遍：`parse` 只管"这行是不是这个形状"，[violations] 管"这个形状合不合规"——
 * **拒收**（不是我们的单子）和**不合格**（是我们的单子但写歪了）不许混为一谈。
 */
data class TaskContract(
    val contractId: String,
    val revision: Int,
    val goal: TaskGoal,
    val deliverables: List<TaskDeliverable>,
    val authority: TaskAuthority,
    val budget: TaskBudget,
    val evidence: List<EvidenceKind>,
    val exit: TaskExit,
) {

    /** 一项都不违 ⇒ 空表；每条是一句"哪儿不对"（写回派工单与拒单理由都够用）。 */
    fun violations(): List<String> {
        val v = ArrayList<String>()
        if (contractId.isBlank()) v += "contract_id 为空"
        if (revision < MIN_REVISION) v += "revision 从 $MIN_REVISION 起（$revision）"

        if (goal.text.isBlank()) v += "goal.text 为空"
        if (goal.text.length > TaskGoal.MAX_TEXT_CHARS) v += "goal.text 超 ${TaskGoal.MAX_TEXT_CHARS} 字"
        if (goal.criteria.isEmpty()) v += "goal.criteria 至少一条判据"
        if (goal.criteria.size > MAX_CRITERIA) v += "goal.criteria 超 $MAX_CRITERIA 条（${goal.criteria.size}）"
        goal.criteria.forEachIndexed { i, c ->
            if (c.isBlank()) v += "goal.criteria[$i] 为空"
            if (c.length > TaskGoal.MAX_CRITERION_CHARS) v += "goal.criteria[$i] 超 ${TaskGoal.MAX_CRITERION_CHARS} 字"
            TaskGoal.BANNED_VALUE_WORDS.forEach { w ->
                if (c.contains(w)) v += "goal.criteria[$i] 含价值判断词「$w」"
            }
        }

        if (deliverables.isEmpty()) v += "deliverables 至少一项"
        if (deliverables.size > TaskDeliverable.MAX_DELIVERABLES) {
            v += "deliverables 超 ${TaskDeliverable.MAX_DELIVERABLES} 项（${deliverables.size}）"
        }
        deliverables.forEach { d ->
            if (d.id.isBlank()) v += "deliverable.id 为空"
            if (d.what.isBlank()) v += "deliverable[${d.id}] what 为空"
            if (d.acceptance.isEmpty()) v += "deliverable[${d.id}] 没有验收契约"
            d.acceptance.forEachIndexed { i, a ->
                if (a.isBlank()) v += "deliverable[${d.id}].acceptance[$i] 为空"
                if (a.length > TaskDeliverable.MAX_ACCEPTANCE_CHARS) {
                    v += "deliverable[${d.id}].acceptance[$i] 超 ${TaskDeliverable.MAX_ACCEPTANCE_CHARS} 字"
                }
                TaskGoal.BANNED_VALUE_WORDS.forEach { w ->
                    if (a.contains(w)) v += "deliverable[${d.id}].acceptance[$i] 含价值判断词「$w」"
                }
            }
        }
        deliverables.map { it.id.trim() }.groupingBy { it }.eachCount()
            .filterValues { it > 1 }
            .forEach { (id, n) -> v += "deliverable.id 重复：$id ×$n" }

        if (authority.readRoots.isEmpty()) v += "authority.read_roots 至少一个根（没读范围的单子无从取证）"
        if (authority.writeRootsEscapingRead().isNotEmpty()) {
            v += "authority.write_roots 越出读范围：${authority.writeRootsEscapingRead().joinToString()}"
        }
        (authority.readRoots + authority.writeRoots).forEach { r ->
            if (TaskAuthority.lexical(r) == null) v += "授权根不是合法声明：「$r」"
        }
        val rootCount = authority.readRoots.size + authority.writeRoots.size
        if (rootCount > TaskAuthority.MAX_ROOTS) v += "授权根超 ${TaskAuthority.MAX_ROOTS} 个（$rootCount）"
        if (authority.egressAllowed) v += "authority.egress 必须为 false"

        if (budget.maxWallMs !in TaskBudget.MIN_WALL_MS..TaskBudget.MAX_WALL_MS) {
            v += "budget.max_wall_ms 越界：${budget.maxWallMs}（${TaskBudget.MIN_WALL_MS}–${TaskBudget.MAX_WALL_MS}）"
        }
        if (budget.maxSteps !in TaskBudget.MIN_STEPS..TaskBudget.MAX_STEPS) {
            v += "budget.max_steps 越界：${budget.maxSteps}（${TaskBudget.MIN_STEPS}–${TaskBudget.MAX_STEPS}）"
        }
        if (budget.maxInterruptions !in TaskBudget.MIN_INTERRUPTIONS..TaskBudget.MAX_INTERRUPTIONS) {
            v += "budget.max_interruptions 越界：${budget.maxInterruptions}" +
                "（${TaskBudget.MIN_INTERRUPTIONS}–${TaskBudget.MAX_INTERRUPTIONS}）"
        }

        if (evidence.isEmpty()) v += "evidence 至少一类（没有证据要求的单子无法验收）"
        evidence.groupingBy { it }.eachCount().filterValues { it > 1 }
            .forEach { (k, n) -> v += "evidence 重复：${k.wire} ×$n" }

        v += exit.outOfRangeSlots()
        return v
    }

    /** 形状与额度全不违（[violations] 为空表的读法糖）。 */
    fun isWellFormed(): Boolean = violations().isEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put(KEY_VERSION, VERSION)
        .put(KEY_CONTRACT_ID, contractId)
        .put(KEY_REVISION, revision)
        .put(KEY_GOAL, goal.toJson())
        .put(KEY_DELIVERABLES, JSONArray().also { arr -> deliverables.forEach { arr.put(it.toJson()) } })
        .put(KEY_AUTHORITY, authority.toJson())
        .put(KEY_BUDGET, budget.toJson())
        .put(KEY_EVIDENCE, JSONArray(evidence.map { it.wire }))
        .put(KEY_EXIT, exit.toJson())

    /** 落盘串（给日志与回执对齐用；哈希请用 [canonicalText]，那个才跨实现稳定）。 */
    fun jsonText(): String = toJson().toString()

    /**
     * 规范串：键名**字典序**输出、数组保持原序。
     *
     * 为什么不直接拿 [jsonText] 去哈希：`JSONObject` 内部是 HashMap，`toString()` 的键序
     * 不跨 JSON 实现保证——同一张单子在 JVM 单测与真机 Android 上可能算出两个哈希，
     * 那 `[TaskReceipt.contractSha256]` 就成了一张对不上的指纹。排序后与插入序无关，
     * 于是"指纹绑对象"这件事才立得住。
     */
    fun canonicalText(): String = canonicalOf(toJson())

    /** 契约指纹（sha256 十六进制小写）——回执靠它绑住"我交的是哪一版单子"。 */
    fun sha256(): String = sha256Hex(canonicalText())

    companion object {
        /** 行格式版本（改形状才 +1；加字段不算）。 */
        const val VERSION = 1

        const val KEY_VERSION = "v"
        const val KEY_CONTRACT_ID = "contract_id"
        const val KEY_REVISION = "revision"
        const val KEY_GOAL = "goal"
        const val KEY_DELIVERABLES = "deliverables"
        const val KEY_AUTHORITY = "authority"
        const val KEY_BUDGET = "budget"
        const val KEY_EVIDENCE = "evidence"
        const val KEY_EXIT = "exit"
        val KEYS = setOf(
            KEY_VERSION, KEY_CONTRACT_ID, KEY_REVISION, KEY_GOAL,
            KEY_DELIVERABLES, KEY_AUTHORITY, KEY_BUDGET, KEY_EVIDENCE, KEY_EXIT,
        )

        /** 版本号从 1 起；每重签一次 +1（对齐军令状 `PledgeSnapshot.version`）。 */
        const val MIN_REVISION = 1

        /** 判据条数上限（照 `SubAgentRules.MAX_SUBTASKS`＝5 的"别把单子写成论文"口径）。 */
        const val MAX_CRITERIA = 5

        fun from(o: JSONObject): TaskContract? {
            if (o.unknownKeysIn(KEYS).isNotEmpty()) return null
            if (o.needInt(KEY_VERSION) != VERSION) return null
            val id = o.needString(KEY_CONTRACT_ID) ?: return null
            val revision = o.needInt(KEY_REVISION) ?: return null
            val goal = o.needObject(KEY_GOAL)?.let(TaskGoal::from) ?: return null
            val rawDeliverables = o.needArray(KEY_DELIVERABLES) ?: return null
            val deliverables = (0 until rawDeliverables.length()).mapNotNull { i ->
                rawDeliverables.optJSONObject(i)?.let(TaskDeliverable::from)
            }
            if (deliverables.size != rawDeliverables.length()) return null
            val authority = o.needObject(KEY_AUTHORITY)?.let(TaskAuthority::from) ?: return null
            val budget = o.needObject(KEY_BUDGET)?.let(TaskBudget::from) ?: return null
            val kinds = o.needArray(KEY_EVIDENCE)?.stringValuesOrNull() ?: return null
            val evidence = kinds.mapNotNull { EvidenceKind.ofWire(it) }
            if (evidence.size != kinds.size) return null
            val exit = o.needObject(KEY_EXIT)?.let(TaskExit::from) ?: return null
            return TaskContract(
                contractId = id,
                revision = revision,
                goal = goal,
                deliverables = deliverables,
                authority = authority,
                budget = budget,
                evidence = evidence,
                exit = exit,
            )
        }

        /** 读不回原样 ⇒ null（与 `EmergencyReceipt.parse` 同一口径：坏行宁可不认，不许认成半张单子）。 */
        fun parse(raw: String): TaskContract? = runCatching { from(JSONObject(raw.trim())) }.getOrNull()

        /** 为什么收不下这张单子（null＝收得下）。拒收理由要能直接写进回函。 */
        fun rejectionReason(raw: String): String? {
            val o = runCatching { JSONObject(raw.trim()) }.getOrNull() ?: return "不是合法 JSON 对象"
            val unknown = o.unknownKeysIn(KEYS)
            if (unknown.isNotEmpty()) return "未知键：${unknown.joinToString()}"
            if (o.needObject(KEY_AUTHORITY)?.let { it.unknownKeysIn(TaskAuthority.KEYS).isNotEmpty() } == true) {
                return "authority 段有未知键"
            }
            if (o.needObject(KEY_AUTHORITY)?.needBoolean(TaskAuthority.KEY_EGRESS) == true) {
                return "外发不可谈判：egress 只许 false"
            }
            return if (from(o) == null) "形状不符（缺键、类型不对或版本不认识）" else null
        }
    }
}

// ── 回执 ──────────────────────────────────────────────────────────────────

/**
 * 一条证据引用（回执里的元素）。
 *
 * @param kind 哪类证据（闭集，照契约的 [EvidenceKind]）。
 * @param ref **引用**：路径、64 位 sha256、CI 出件号、测试 XML 路径、账行号或法条款号。
 *   本层不验它指向的东西在不在——那要么由右相判时去取，要么由装配层去查；回执里写它只是个指针。
 */
data class ReceiptEvidence(val kind: EvidenceKind, val ref: String) {

    fun toJson(): JSONObject = JSONObject().put(KEY_KIND, kind.wire).put(KEY_REF, ref)

    companion object {
        const val KEY_KIND = "kind"
        const val KEY_REF = "ref"
        val KEYS = setOf(KEY_KIND, KEY_REF)

        fun from(o: JSONObject): ReceiptEvidence? {
            if (o.unknownKeysIn(KEYS).isNotEmpty()) return null
            val kind = o.needString(KEY_KIND)?.let { EvidenceKind.ofWire(it) } ?: return null
            val ref = o.needString(KEY_REF) ?: return null
            return ReceiptEvidence(kind = kind, ref = ref)
        }
    }
}

/**
 * 回执：外部执行体**唯一**允许交回来的东西。
 *
 * 三条设计上的"不给口子"（文件头硬规矩 1、2 的落点）：
 * 1. 五态是**自报**，没有"验收通过"这一格；夹带验收字段 ⇒ 整行拒收（[VERDICT_KEYS]）。
 * 2. 没有写账字段，账行的引用只是 [EvidenceKind.LEDGER] 那种指针；夹带账本字段 ⇒ 整行拒收（[LEDGER_KEYS]）。
 * 3. 没有改单字段——授权／预算／交付物都是左相的东西；夹带 ⇒ 整行拒收（[CONTRACT_KEYS]）。
 * 三张表之外的未知键同样拒收：回执是窄口，宽一分就多一分被做弱的余地。
 */
data class TaskReceipt(
    val contractId: String,
    val contractSha256: String,
    val state: ReceiptState,
    val atMs: Long,
    val evidence: List<ReceiptEvidence> = emptyList(),
    val note: String = "",
) {

    /** 这张回执绑的是不是这张契约（id 与指纹两样都对才算绑住）。 */
    fun bindsTo(contract: TaskContract): Boolean =
        contractId == contract.contractId && contractSha256 == contract.sha256()

    /** 相对契约还缺哪几类必交证据——**只算缺口，不下合格结论**（合格是右相判的）。 */
    fun missingEvidenceFor(contract: TaskContract): List<EvidenceKind> =
        contract.evidence.filter { need -> evidence.none { it.kind == need } }

    /** 回执自报的问题（空表＝这一行本身写得对）。 */
    fun violations(): List<String> {
        val v = ArrayList<String>()
        if (contractId.isBlank()) v += "contract_id 为空"
        if (!SHA256_HEX.matches(contractSha256)) v += "contract_sha256 不是 64 位十六进制"
        if (atMs < 0) v += "at_ms 不许为负"
        if (note.length > MAX_NOTE_CHARS) v += "note 超 $MAX_NOTE_CHARS 字"
        evidence.map { it.kind }.groupingBy { it }.eachCount().filterValues { it > 1 }
            .forEach { (k, n) -> v += "evidence 重复：${k.wire} ×$n" }
        evidence.forEachIndexed { i, e -> if (e.ref.isBlank()) v += "evidence[$i].ref 为空" }
        // "交完了"和"做砸了/不接"都不能是空话：前者要带证据，后者要带原因。
        if (state == ReceiptState.DONE && evidence.isEmpty()) v += "done 却不带任何证据（done 只是自报，更要留下可对的东西）"
        if (state == ReceiptState.FAILED && note.isBlank()) v += "failed 必须写原因"
        if (state == ReceiptState.REJECTED && note.isBlank()) v += "rejected 必须写拒因"
        return v
    }

    fun isWellFormed(): Boolean = violations().isEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put(KEY_VERSION, VERSION)
        .put(KEY_CONTRACT_ID, contractId)
        .put(KEY_CONTRACT_SHA256, contractSha256)
        .put(KEY_STATE, state.wire)
        .put(KEY_AT_MS, atMs)
        .put(KEY_EVIDENCE, JSONArray().also { arr -> evidence.forEach { arr.put(it.toJson()) } })
        .put(KEY_NOTE, note)

    fun jsonText(): String = toJson().toString()

    companion object {
        /** 行格式版本（改形状才 +1；加字段不算）。 */
        const val VERSION = 1

        const val KEY_VERSION = "v"
        const val KEY_CONTRACT_ID = "contract_id"
        const val KEY_CONTRACT_SHA256 = "contract_sha256"
        const val KEY_STATE = "state"
        const val KEY_AT_MS = "at_ms"
        const val KEY_EVIDENCE = "evidence"
        const val KEY_NOTE = "note"
        val KEYS = setOf(
            KEY_VERSION, KEY_CONTRACT_ID, KEY_CONTRACT_SHA256,
            KEY_STATE, KEY_AT_MS, KEY_EVIDENCE, KEY_NOTE,
        )

        /** 一条说明的字数上限（同 `SubAgentRules.MAX_OVERALL_GOAL_CHARS`＝500：原因也是给人读的）。 */
        const val MAX_NOTE_CHARS = 500

        /**
         * 验收类禁键：这些字段只能由**右相**写。回执里出现 ⇒ 整行拒收，
         * 因为"我通过了验收"是执行体不能自证的事（done ≠ 验收通过）。
         */
        val VERDICT_KEYS = setOf(
            "accepted_by", "acceptance", "accepted_what", "verdict", "pass", "passed",
            "score", "grade", "review", "signed_off", "right_phase",
        )

        /** 写账类禁键：账由我们记，外部执行体只能引用账行，不能交来一条写账指令。 */
        val LEDGER_KEYS = setOf(
            "ledger", "ledger_write", "ledger_op", "ledger_row", "write_ledger",
            "append_ledger", "record", "book", "account", "commit",
        )

        /** 改单类禁键：授权、预算、交付物是左相的东西，出事时执行体只能停或问（见 [TaskExit]）。 */
        val CONTRACT_KEYS = setOf(
            "authority", "budget", "deliverables", "exit", "scope", "grant",
            "extend", "budget_delta", "revision", "contract",
        )

        private val BANNED_GROUPS = listOf(
            VERDICT_KEYS to "done 不等于验收通过：验收字段只能由右相写（回执不许夹带 ${VERDICT_KEYS.joinToString()}）",
            LEDGER_KEYS to "账由我们记：回执只能引用账行，不许携带账本字段（${LEDGER_KEYS.joinToString()}）",
            CONTRACT_KEYS to "执行体不许改自己的单子：授权／预算／交付物归左相（${CONTRACT_KEYS.joinToString()}）",
        )

        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        /** 夹带了哪一类禁键（null＝没有）。三张表分别对应三条硬规矩。 */
        fun bannedGroup(keys: Set<String>): String? {
            val hit = BANNED_GROUPS.firstOrNull { (banned, _) -> keys.any { it.lowercase() in banned } }
            return hit?.let { (banned, why) ->
                "$why｜命中键：${keys.filter { it.lowercase() in banned }.joinToString()}"
            }
        }

        fun from(o: JSONObject): TaskReceipt? {
            if (o.unknownKeysIn(KEYS).isNotEmpty()) return null
            if (o.needInt(KEY_VERSION) != VERSION) return null
            val id = o.needString(KEY_CONTRACT_ID) ?: return null
            val sha = o.needString(KEY_CONTRACT_SHA256) ?: return null
            val state = o.needString(KEY_STATE)?.let { ReceiptState.ofWire(it) } ?: return null
            val at = o.needLong(KEY_AT_MS) ?: return null
            val note = o.needString(KEY_NOTE) ?: return null
            val rawEvidence = o.needArray(KEY_EVIDENCE) ?: return null
            val evidence = (0 until rawEvidence.length()).mapNotNull { i ->
                rawEvidence.optJSONObject(i)?.let(ReceiptEvidence::from)
            }
            if (evidence.size != rawEvidence.length()) return null
            return TaskReceipt(
                contractId = id,
                contractSha256 = sha,
                state = state,
                atMs = at,
                evidence = evidence,
                note = note,
            )
        }

        /** 收不下就返回 null——宁可不认，不许认成半张回执（同一口径见 `TaskContract.parse`）。 */
        fun parse(raw: String): TaskReceipt? = runCatching { rejectIfSmuggled(JSONObject(raw.trim())) }.getOrNull()

        /** 为什么收不下这条回执（null＝收得下）。 */
        fun rejectionReason(raw: String): String? {
            val o = runCatching { JSONObject(raw.trim()) }.getOrNull() ?: return "不是合法 JSON 对象"
            return rejectionReason(o)
        }

        fun rejectionReason(o: JSONObject): String? {
            bannedGroup(o.keysToSet())?.let { return it }
            val unknown = o.unknownKeysIn(KEYS)
            if (unknown.isNotEmpty()) return "未知键：${unknown.joinToString()}"
            return if (from(o) == null) "形状不符（缺键、类型不对或版本不认识）" else null
        }

        /** 先查夹带，再认形状；任何一步不过都返回 null（拒收）。 */
        private fun rejectIfSmuggled(o: JSONObject): TaskReceipt? =
            if (rejectionReason(o) != null) null else from(o)
    }
}

// ── 层内私用工具（不外露：:core 之外看不见，免得变成第二套"公共 API"）──────

/** 这张表的键集（拒收未知键要靠它）。 */
internal fun JSONObject.unknownKeysIn(allowed: Set<String>): List<String> =
    keysToSet().filterNot { it in allowed }.sorted()

internal fun JSONObject.keysToSet(): Set<String> {
    val out = LinkedHashSet<String>()
    val it = keys()
    while (it.hasNext()) out.add(it.next())
    return out
}

/** 必须有、必须是字符串（`optString` 会把缺失与 null 都变成空串，那就分不出"没写"和"写了空"）。 */
internal fun JSONObject.needString(key: String): String? {
    val v = opt(key) ?: return null
    return v as? String
}

internal fun JSONObject.needBoolean(key: String): Boolean? {
    val v = opt(key) ?: return null
    return v as? Boolean
}

/** 必须有、必须是整数（拒绝 5.0 与 "5"：数字字段写成字符串是回执被做弱的老路子）。 */
internal fun JSONObject.needInt(key: String): Int? = when (val v = opt(key)) {
    is Int -> v
    is Long -> if (v in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) v.toInt() else null
    else -> null
}

internal fun JSONObject.needLong(key: String): Long? = when (val v = opt(key)) {
    is Long -> v
    is Int -> v.toLong()
    else -> null
}

internal fun JSONObject.needObject(key: String): JSONObject? = opt(key) as? JSONObject

internal fun JSONObject.needArray(key: String): JSONArray? = opt(key) as? JSONArray

/** 全元素必须是字符串，出现别的类型 ⇒ null（宁缺勿错）。 */
internal fun JSONArray.stringValuesOrNull(): List<String>? {
    val out = ArrayList<String>(length())
    for (i in 0 until length()) {
        out.add(opt(i) as? String ?: return null)
    }
    return out
}

/** 规范串：对象按键名字典序、数组保原序、字符串按 JSON 转义（跨 JSON 实现稳定）。 */
internal fun canonicalOf(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> {
        val keys = value.keysToSet().sorted()
        buildString {
            append('{')
            keys.joinTo(this, ",") { k -> JSONObject.quote(k) + ':' + canonicalOf(value.get(k)) }
            append('}')
        }
    }
    is JSONArray -> buildString {
        append('[')
        (0 until value.length()).joinTo(this, ",") { i -> canonicalOf(value.opt(i)) }
        append(']')
    }
    is String -> JSONObject.quote(value)
    is Boolean -> value.toString()
    is Number -> value.toString()
    else -> JSONObject.quote(value.toString())
}

internal fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
