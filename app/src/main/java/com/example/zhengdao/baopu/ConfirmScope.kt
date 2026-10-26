// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百七十一批（确认卡降噪 v2）：授权**范围**模型 ＋ 同 key 去重账本 ＋ 审计。
// 派工单 §1：decision key = 工具 + 规范化参数；同一 key 在同一作用域内只问一次；
// 四档范围「仅这一次（默认）/ 本次运行 / 本会话 / 本技能」；高风险与扣费类永远只给
// 「仅这一次」；授权可撤销、运行/会话结束自动清、审计记「谁授权/范围/次数」。
//
// 为什么纯逻辑住 `baopu/` 包而不住 `agent/`：账本与路径授权的账本
// （[WorkspaceGrants]，157）是**同一层**的东西——都是「用户批过什么」的事实，
// 判据不该依赖引擎。本文件无 Android/Compose/SQLite，JVM 直测。
package com.example.zhengdao.baopu

import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 授权范围四档（派工单 §1；UI 明示，**默认仅这一次**）。
 *
 * 四档的**寿命边界**（covers 判据的惟一来源，别处不许再判一次）：
 * - [ONLY_ONCE]：只覆盖批准的那一次 ⇒ **永不入账**（没有授权可继承）；
 * - [THIS_RUN]：本次运行内有效，`endRun` 即失效；
 * - [THIS_SESSION]：本会话内有效（跨运行仍有效——157 定的「一次会话问一次」同款口径），
 *   `endSession` 即失效；
 * - [THIS_SKILL]：本技能内有效（同一工具的重复调用；参数变了仍是新 key）。
 *   与 [THIS_RUN] 的差别是**意图粒度**更窄（单个技能的重复动作，不是整轮），
 *   寿命上同运行结束——引擎目前没有「技能结束」信号（183/189 会建），
 *   届时加 `endSkill` 即可，四档语义不用再改。
 *
 * [label] 只给**引擎日志/审计**用（RunLog 是中文人话，同
 * [RunRules.CONFIRM_TIMEOUT_NOTE] 的房型）；界面按钮文案走 strings.xml
 * （派工单 §3.1：界面文案全走 strings，中英各一份）——两边各一份是**分层**决定的，
 * 改一端记得同步另一端（check-string-text.py 卡漏 string）。
 */
enum class ConfirmScope(val key: String, val label: String) {
    ONLY_ONCE("only_once", "仅这一次"),
    THIS_RUN("this_run", "本次运行内"),
    THIS_SESSION("this_session", "本会话内"),
    THIS_SKILL("this_skill", "本技能内"),
    ;

    companion object {
        /** 稳定键 → 档位（审计行回读用；认不出的键按 ONLY_ONCE——保守）。 */
        fun fromKey(key: String): ConfirmScope =
            entries.firstOrNull { it.key == key } ?: ONLY_ONCE
    }
}

/**
 * 确认 decision key（派工单 §1：**工具 + 规范化参数**）。
 *
 * 「作用域」为什么不进键：键回答「这是不是同一个请求」，作用域回答「这个请求
 * 被放行的广度」——两件事。同一个 write_file 批「本会话」之后，本会话内再出现
 * 同一个 key 不再问；批「仅这一次」则下次照问。若把作用域拼进键，「同 key 同
 * 作用域只问一次」会退化成恒真命题（自己断言自己）。
 *
 * 规范化只做**不改变语义**的归一：JSON 键序（模型两次输出的键序可能不同）、
 * 嵌套对象/数组递归排序。字符串值**逐字保留**（含首尾空格——改了就是另一个请求）。
 */
internal object ConfirmKeys {

    /** decision key（`工具|规范化参数`；竖线不会出现在工具名里，无歧义）。 */
    fun of(tool: String, args: JSONObject): String = "$tool|${canonical(args)}"

    /** 规范化参数的紧凑 canonical 串（键序确定；值原样）。 */
    fun canonical(args: JSONObject): String = canonicalValue(args)

    private fun canonicalValue(value: Any?): String = when (value) {
        null -> "null"
        is JSONObject -> {
            val keys = value.keys().asSequence().toList().sorted()
            keys.joinToString(",", "{", "}") { k ->
                JSONObject.quote(k) + ":" + canonicalValue(value.opt(k))
            }
        }

        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") {
            canonicalValue(value.opt(it))
        }

        is String -> JSONObject.quote(value)
        else -> value.toString() // 数字/布尔：原样（1 与 1.0 是两个请求，正确）
    }
}

/**
 * 高风险分类（派工单 §1：**写盘/删除/终端/安装/扣费类永远只给「仅这一次」**）。
 *
 * 表是**白名单**制的反面：只有明确列出的「网页操作」类可以拿范围授权，
 * 其余一律高风险。为什么反过来写：新工具（含外部 MCP，名字服务端定）默认
 * **不给范围**——漏一个工具名只会让它更安全（每次照问），不会静默放行。
 * 这与 [ToolSpec.readOnly] 的「默认按写处理」是同一条保守纪律。
 *
 * 173（L3 自主权限）会建更细的风险档；本表先按派工单点名的四类落地，
 * 网页交互类按 §1 未点名 ⇒ 可范围授权。
 */
internal object ConfirmRisk {

    /** 可以拿范围授权的工具（派工单 §1 高风险清单之外只剩网页操作类）。 */
    private val SCOPE_ELIGIBLE = setOf(
        "browser_open",
        "browser_snapshot",
        "browser_click",
        "browser_type",
        "browser_scroll",
        "browser_tap",
        "browser_swipe",
        "browser_long_press",
        "browser_key",
        "browser_wait",
        // 228 批 B 片七件（B-3 select/hover 与 B-2 导航三件、B-4 切换/关标签都是网页操作；
        // scroll_into_view 与 tabs_list 走 FREE 免确认，不在本表——漏列只会照问，更安全）
        "browser_back",
        "browser_forward",
        "browser_reload",
        "browser_select",
        "browser_hover",
        "browser_tabs_switch",
        "browser_tabs_close",
    )

    /** 高风险 ⇒ 永远只给「仅这一次」（负例「高风险被范围授权绕过」的判据本体）。 */
    fun isHighRisk(tool: String): Boolean = tool !in SCOPE_ELIGIBLE

    /** 这张卡上能摆哪些范围（高风险 ⇒ 只剩 [ConfirmScope.ONLY_ONCE]）。 */
    fun scopesFor(tool: String): List<ConfirmScope> =
        if (isHighRisk(tool)) {
            listOf(ConfirmScope.ONLY_ONCE)
        } else {
            listOf(
                ConfirmScope.ONLY_ONCE,
                ConfirmScope.THIS_RUN,
                ConfirmScope.THIS_SESSION,
                ConfirmScope.THIS_SKILL,
            )
        }
}

/** 一条授权审计（「谁授权的、范围、次数」——派工单 §1 第 38 行原话）。 */
internal data class ConfirmAuditEntry(
    val at: Long,
    val sessionId: Long,
    val tool: String,
    val key: String,
    /** 授权类事件（[ConfirmAuditDecision.GRANTED]）非空；其余事件为空。 */
    val scope: ConfirmScope?,
    val decision: ConfirmAuditDecision,
    /** 该授权至今被免卡放行的次数（USED 时是本次累加后的值）。 */
    val useCount: Int,
)

/** 审计处置（短标签可 grep；与 [OutsideDecision] 的四值同款思路：分得清「问过/刚批/早已放行」）。 */
internal enum class ConfirmAuditDecision(val tag: String, val text: String) {
    /** 用户批了范围授权（记账）。 */
    GRANTED("granted", "用户授权范围"),

    /** 命中授权免卡放行一次（useCount 累加）。 */
    USED("used", "范围授权放行"),

    /** 用户/设置页撤销。 */
    REVOKED("revoked", "撤销授权"),

    /** 运行结束自动清（THIS_RUN/THIS_SKILL 失效）。 */
    RUN_ENDED("run_ended", "运行结束自动清"),

    /** 会话结束自动清（全部失效）。 */
    SESSION_ENDED("session_ended", "会话结束自动清"),

    /** 高风险工具要了范围授权 ⇒ 被摁回「仅这一次」（负例靶心，必须留痕）。 */
    DOWNGRADED("downgraded", "高风险摁回仅这一次"),
}

/**
 * 进程级授权账本（与 [WorkspaceGrants] 同款房型：进程级单例、按 sessionId 分账）。
 *
 * 为什么按 sessionId 分账而不是全局一张表：「换会话不继承」是负例判据——
 * 全局表会让会话 B 白捡会话 A 的授权。会话内跨**运行**仍有效（THIS_SESSION
 * 与 157 的路径授权同口径：一次会话问一次）。
 *
 * 生死：授权只住内存（不落库）——进程被杀全部失效，重启后重新问。
 * 这是**故意**的：落库要把「谁在什么时候批了什么范围」变成跨重启事实，
 * 而 171 的红线是「不降低高风险确认」，内存账本是保守的那一端。
 * （路径根的授权另有 [WorkspaceGrants] 落库管，两本账不要混。）
 */
internal object ConfirmGrants {

    /** 审计上限（越界/授权都是低频事件，但手机进程不该无限长）。 */
    private const val AUDIT_CAP = 500

    private class Grant(
        val sessionId: Long,
        val key: String,
        val tool: String,
        val scope: ConfirmScope,
        val grantedAt: Long,
    ) {
        @Volatile var useCount: Int = 0
        @Volatile var lastUsedAt: Long = grantedAt

        /** 收尾类审计（撤销/失效/清单行共用一条构造；decision 说清是哪一种）。 */
        fun ended(at: Long, decision: ConfirmAuditDecision) = ConfirmAuditEntry(
            at = at,
            sessionId = sessionId,
            tool = tool,
            key = key,
            scope = scope,
            decision = decision,
            useCount = useCount,
        )
    }

    private class SessionLedger {
        val grants: ConcurrentHashMap<String, Grant> = ConcurrentHashMap()
        val audit: MutableList<ConfirmAuditEntry> = Collections.synchronizedList(mutableListOf())
    }

    private val sessions = ConcurrentHashMap<Long, SessionLedger>()

    /**
     * 这个 key 当前是否被有效授权覆盖（命中即累加使用次数并记审计）。
     *
     * 高风险工具**永远 false**（纵深防御：就算上游漏摁，这里也不放行——
     * 负例「高风险被范围授权绕过」判据的第二道，第一道在
     * [com.example.zhengdao.baopu.agent.ConfirmFlow.resumeWithApproval]）。
     */
    fun covers(sessionId: Long, key: String, tool: String): Boolean {
        if (ConfirmRisk.isHighRisk(tool)) return false
        val g = sessions[sessionId]?.grants?.get(key) ?: return false
        g.useCount += 1
        g.lastUsedAt = System.currentTimeMillis()
        record(
            ConfirmAuditEntry(
                at = g.lastUsedAt,
                sessionId = sessionId,
                tool = tool,
                key = key,
                scope = g.scope,
                decision = ConfirmAuditDecision.USED,
                useCount = g.useCount,
            ),
        )
        return true
    }

    /** 记账（幂等：同 key 同 scope 重复批只更新时间戳，不换 scope）。 */
    fun grant(sessionId: Long, key: String, tool: String, scope: ConfirmScope) {
        val ledger = sessions.getOrPut(sessionId) { SessionLedger() }
        val now = System.currentTimeMillis()
        val existing = ledger.grants[key]
        if (existing != null && existing.scope == scope) {
            existing.lastUsedAt = now
        } else {
            ledger.grants[key] = Grant(sessionId, key, tool, scope, now)
        }
        record(
            ConfirmAuditEntry(
                at = now,
                sessionId = sessionId,
                tool = tool,
                key = key,
                scope = scope,
                decision = ConfirmAuditDecision.GRANTED,
                useCount = existing?.useCount ?: 0,
            ),
        )
    }

    /** 撤销（key 为空 ⇒ 撤这个会话的全部）。返回撤掉的条数。 */
    fun revoke(sessionId: Long, key: String? = null): Int {
        val ledger = sessions[sessionId] ?: return 0
        val victims = if (key == null) {
            ledger.grants.values.toList()
        } else {
            listOfNotNull(ledger.grants[key])
        }
        val now = System.currentTimeMillis()
        victims.forEach { g ->
            ledger.grants.remove(g.key)
            record(g.ended(now, ConfirmAuditDecision.REVOKED))
        }
        if (key == null) sessions.remove(sessionId)
        return victims.size
    }

    /** 运行结束 ⇒ THIS_RUN/THIS_SKILL 失效（THIS_SESSION 跨运行仍有效，157 同口径）。 */
    fun endRun(sessionId: Long) {
        val ledger = sessions[sessionId] ?: return
        val now = System.currentTimeMillis()
        ledger.grants.values
            .filter { it.scope == ConfirmScope.THIS_RUN || it.scope == ConfirmScope.THIS_SKILL }
            .forEach { g ->
                ledger.grants.remove(g.key)
                record(g.ended(now, ConfirmAuditDecision.RUN_ENDED))
            }
    }

    /** 会话结束 ⇒ 这个会话的授权全清（换会话不继承的结构性保证）。 */
    fun endSession(sessionId: Long) {
        val ledger = sessions.remove(sessionId) ?: return
        val now = System.currentTimeMillis()
        ledger.grants.values.forEach { g -> record(g.ended(now, ConfirmAuditDecision.SESSION_ENDED)) }
    }

    /** 高风险摁回「仅这一次」的留痕（ ConfirmFlow 调；账本自己不 downgrade）。 */
    fun auditDowngraded(sessionId: Long, key: String, tool: String) {
        record(
            ConfirmAuditEntry(
                at = System.currentTimeMillis(),
                sessionId = sessionId,
                tool = tool,
                key = key,
                scope = ConfirmScope.ONLY_ONCE,
                decision = ConfirmAuditDecision.DOWNGRADED,
                useCount = 0,
            ),
        )
    }

    /** 这个会话当前生效的授权（设置页清单/测试读数用；按批准时间倒序）。 */
    fun activeGrants(sessionId: Long): List<ConfirmAuditEntry> =
        sessions[sessionId]?.grants?.values
            ?.sortedByDescending { it.grantedAt }
            ?.map { it.ended(it.grantedAt, ConfirmAuditDecision.GRANTED) }
            ?: emptyList()

    /** 审计流水（测试/日志读数；返回拷贝，调用方改不动账本）。 */
    fun auditFor(sessionId: Long): List<ConfirmAuditEntry> =
        sessions[sessionId]?.audit?.toList() ?: emptyList()

    /** 直测收尾：全清（生产不调；与 [WorkspaceGrants.resetForTest] 同款纪律）。 */
    fun resetForTest() {
        sessions.clear()
    }

    private fun record(entry: ConfirmAuditEntry) {
        val ledger = sessions.getOrPut(entry.sessionId) { SessionLedger() }
        ledger.audit.add(entry)
        while (ledger.audit.size > AUDIT_CAP) {
            ledger.audit.removeAt(0)
        }
    }
}

/**
 * UI → 内核的**作用域旁路**（第一百七十一批的接线桥）。
 *
 * 为什么需要它：卡面（`ui/baopu/BaopuConfirmCard.kt`，本批可改）能拿到用户选的
 * 范围，但「选范围 → 引擎」的正常路径要过
 * `BaopuScreen → BaopuChatController → RunEngine.resumeWithApproval`——
 * 这三个文件都不在本批占用清单里（§0.4）。旁路按 callId 记一条，内核
 * （ConfirmFlow.resumeWithApproval，本批可改）消费时移除，一次交互内完成：
 * 点范围按钮 ⇒ record ⇒ onApprove ⇒ resumeWithApproval ⇒ consume。
 *
 * 为什么安全：callId 是单张挂起卡的锚（陈旧点击判别就靠它），同刻只有一张卡；
 * 条目**消费即删**，不会跨卡串味。上限 [CAP] 条纯防呆（用户选了范围却没点批准、
 * 卡被超时收走的那种残留）。
 *
 * 接线落地后（蓝总管按报告 §接线接上 onApproveWithScope）本对象即可删。
 */
internal object ConfirmScopeChoice {

    private const val CAP = 16

    private val pending: ConcurrentHashMap<String, ConfirmScope> = ConcurrentHashMap()

    fun record(callId: String, scope: ConfirmScope) {
        if (pending.size >= CAP) pending.clear()
        pending[callId] = scope
    }

    /** 取出并移除（没有 ⇒ null = 用户没选范围 = 仅这一次）。 */
    fun consume(callId: String): ConfirmScope? = pending.remove(callId)

    fun discard(callId: String) {
        pending.remove(callId)
    }

    /** 直测收尾（生产不调）。 */
    fun resetForTest() {
        pending.clear()
    }
}
