// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百六十七批 · 租约的**内存账**（进程级单例）：授予 / 命中 / 撤销 / 越权降权。
//
// 一条最重要的结构：**内存是权威，盘只是证据**。
// 启动时**不读盘**，所以"进程重启不复活"不是一条判定，而是**没有那条代码路径**——
// 这比"读到行再比时间戳"稳（那种做法一旦时钟被改就会复活）。
//
// 会话维度（定点 D5）：租约带 `sessionId`，命中要求**相等**——A 会话的租约在 B 会话
// 不命中（新会话不继承）。会话号取自 `ToolRegistry.currentSessionId`。
//
// 淘汰（定点 U1）：换会话**不清**租约（到期自然失效），所以内存里会攒着别的会话的租约；
// 打满 [LeaseLimits.MAX_LIVE] 时**先淘汰其它会话的**，再按 grantedAt 淘汰本会话最老的
// ——防旧会话占位把新会话挤掉。
package com.example.zhengdao.baopu.authz

import java.io.File
import org.json.JSONObject

/** 一张租约（内存权威；盘上的 `grant` 行是它的快照）。 */
internal data class LeaseGrant(
    val id: String,
    val cls: String,
    val scope: LeaseScope,
    val ttlMs: Long,
    val cap: Int,
    val used: Int,
    /** 来源卡（= `ToolCall.id`，与她点的那张卡可按 id 串起来）。 */
    val sourceCardId: String,
    val grantedAtMs: Long,
    val sessionId: Long,
    val revokedAtMs: Long? = null,
    val revokeWhy: String? = null,
) {
    /** 还剩几次（不为负）。 */
    fun remaining(): Int = (cap - used).coerceAtLeast(0)

    /** 过时间窗了没（**绝对计时**：从授予那一刻算，不滑动）。 */
    fun expired(nowMs: Long): Boolean = nowMs - grantedAtMs >= ttlMs
}

/** 未命中的原因（闭集；顺序见 [LeaseStore.hit]）。 */
internal enum class LeaseMissReason(val wire: String) {
    /** 这个会话一张租约都没有（含进程重启后内存空）。 */
    NOT_GRANTED("not_granted"),

    /** 这个会话有租约，但没有这一类的。 */
    CATEGORY_MISMATCH("category_mismatch"),

    /** 撤了（人撤 / 降权撤 / 被淘汰）。 */
    REVOKED("revoked"),

    /** 过时间窗。 */
    EXPIRED("expired"),

    /** 次数用尽（**不是撤销**：状态是"用完了"，不是"收回了"）。 */
    EXHAUSTED("exhausted"),

    /** 目标越出范围。 */
    OUT_OF_SCOPE("out_of_scope"),
    ;
}

/** 一次命中的结论。 */
internal sealed interface LeaseVerdict {
    /** 命中：免卡放行。 */
    data class Hit(val leaseId: String, val cls: String, val cost: Int, val remaining: Int) : LeaseVerdict

    /** 未命中：原样弹卡。 */
    data class Miss(val reason: LeaseMissReason) : LeaseVerdict
}

/** 判定点扣掉、等收口落账的那一笔（`opId → 租约`，有界）。 */
internal data class LeaseUse(val leaseId: String, val cls: String, val cost: Int, val opId: String)

internal object LeaseStore {

    /** 未命中/降权只认这三道闸的拒——别的一律不计越权（未知工具/停机/停用组不算）。 */
    val VIOLATION_GATES: Set<String> = setOf("risk_matrix", "task_grant", "path")

    private const val PENDING_CAP = 256
    private const val COUNTED_CAP = 256

    private val lock = Any()
    private val grants = LinkedHashMap<String, LeaseGrant>()
    private val pending = LinkedHashMap<String, LeaseUse>()
    private val violations = HashMap<String, Int>()
    /** 已计过越权的 `op|gate`（批准后的第二遍 prepare 会再拒一次同一笔——不许重复计）。 */
    private val counted = LinkedHashSet<String>()
    private var seq = 0

    /**
     * 授一张租约（她点了卡上那一句才进来）。
     *
     * @return 新租约（内存权威；盘上落一行 `grant`）
     */
    fun grant(
        offer: LeaseOffer,
        sessionId: Long,
        cardId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): LeaseGrant = synchronized(lock) {
        evictFor(sessionId, nowMs)
        seq += 1
        val g = LeaseGrant(
            id = "ls-$nowMs-$seq",
            cls = offer.cls,
            scope = offer.scope,
            ttlMs = offer.ttlMs,
            cap = offer.cap,
            used = 0,
            sourceCardId = cardId,
            grantedAtMs = nowMs,
            sessionId = sessionId,
        )
        pruneRevoked()
        grants[g.id] = g
        LeaseLedger.append(
            base(LeaseKinds.GRANT, g, nowMs)
                .put("tool", "")
                .put("result", "granted"),
        )
        g
    }

    /**
     * 命中判定（**顺序即法条**，调换顺序就是换语义）。
     *
     * 会话不等 ⇒ 对本次调用来说"这个会话没有这一类租约"（新会话不继承，定点 D5）。
     */
    fun hit(
        sessionId: Long,
        cls: String,
        targets: List<File>,
        args: JSONObject,
        cost: Int,
        opId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): LeaseVerdict {
        synchronized(lock) {
            val mine = grants.values.filter { it.sessionId == sessionId }
            if (mine.isEmpty()) return LeaseVerdict.Miss(LeaseMissReason.NOT_GRANTED)
            val sameClass = mine.filter { it.cls == cls }
            if (sameClass.isEmpty()) return LeaseVerdict.Miss(LeaseMissReason.CATEGORY_MISMATCH)
            val alive = sameClass.filter { it.revokedAtMs == null }
            if (alive.isEmpty()) return LeaseVerdict.Miss(LeaseMissReason.REVOKED)
            val fresh = alive.filterNot { it.expired(nowMs) }
            if (fresh.isEmpty()) {
                alive.forEach { expire(it, nowMs) }
                return LeaseVerdict.Miss(LeaseMissReason.EXPIRED)
            }
            val usable = fresh.filter { it.remaining() >= cost }
            if (usable.isEmpty()) return LeaseVerdict.Miss(LeaseMissReason.EXHAUSTED)
            val inScope = usable.filter { it.scope.within(targets, args) }
            val pick = inScope.maxByOrNull { it.grantedAtMs }
                ?: return LeaseVerdict.Miss(LeaseMissReason.OUT_OF_SCOPE)
            val after = pick.copy(used = pick.used + cost)
            grants[pick.id] = after
            pending[opId] = LeaseUse(pick.id, pick.cls, cost, opId)
            while (pending.size > PENDING_CAP) pending.remove(pending.keys.first())
            return LeaseVerdict.Hit(pick.id, pick.cls, cost, after.remaining())
        }
    }

    /** 收口取回这一笔（没有 ⇒ null：不是租约放行的动作）。 */
    fun takePending(opId: String): LeaseUse? = synchronized(lock) { pending.remove(opId) }

    /**
     * 落"用一次"的两行（`use` 带结果 ＋ `outcome`）。
     *
     * 为什么在收口落而不在判定点落：判定点要**零 IO**（判定在管线上、每轮都跑），
     * 而且"用了一次"这句话只有执行完了才是事实（287/292 的"问过≠做过"同款纪律）。
     */
    fun noteUsed(use: LeaseUse, tool: String, result: String, nowMs: Long = System.currentTimeMillis()) {
        val g = synchronized(lock) { grants[use.leaseId] } ?: return
        LeaseLedger.append(
            base(LeaseKinds.USE, g, nowMs)
                .put("op", use.opId)
                .put("tool", tool)
                .put("cost", use.cost)
                .put("remaining", g.remaining())
                .put("result", result),
        )
        LeaseLedger.append(
            base(LeaseKinds.OUTCOME, g, nowMs)
                .put("op", use.opId)
                .put("tool", tool)
                .put("result", result),
        )
    }

    /** 撤一张（[leaseId] 为空 ⇒ 撤这个会话的全部）。返回撤掉几张。 */
    fun revoke(sessionId: Long, leaseId: String? = null, why: String = "user", nowMs: Long = System.currentTimeMillis()): Int =
        synchronized(lock) {
            val victims = grants.values.filter {
                it.sessionId == sessionId && it.revokedAtMs == null && (leaseId == null || it.id == leaseId)
            }
            victims.forEach { revokeOne(it, why, nowMs) }
            victims.size
        }

    /**
     * 越权记账（[gate] 是拒它的那道闸；不在 [VIOLATION_GATES] 里 ⇒ 一个字不写）。
     *
     * 满 [LeaseLimits.VIOLATION_STEP] 次 ⇒ **降一档**（cap 减半，剩余至少留 1）；
     * 再满一轮 ⇒ **撤销**（`revoke/why=downgrade`）。
     *
     * @return 真被降/撤的那一张（给上层镜像进治理账本；没动静 ⇒ null）
     */
    fun noteRejected(
        sessionId: Long,
        cls: String,
        gate: String?,
        tool: String,
        opId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): LeaseGrant? {
        if (gate == null || gate !in VIOLATION_GATES) return null
        return synchronized(lock) {
            val alive = grants.values.filter { it.sessionId == sessionId && it.cls == cls && it.revokedAtMs == null }
            if (alive.isEmpty()) return null
            if (!counted.add("$opId|$gate")) return null
            while (counted.size > COUNTED_CAP) counted.remove(counted.first())
            val key = "$sessionId|$cls"
            val n = (violations[key] ?: 0) + 1
            violations[key] = n
            alive.firstOrNull()?.let {
                LeaseLedger.append(base(LeaseKinds.VIOLATION, it, nowMs).put("op", opId).put("tool", tool).put("count", n))
            }
            if (n % LeaseLimits.VIOLATION_STEP != 0) return null
            downgrade(sessionId, cls, n, tool, nowMs)
        }
    }

    /** 这个会话当前生效的租约（读数/测试用；按授予时刻倒序）。 */
    fun active(sessionId: Long): List<LeaseGrant> =
        synchronized(lock) {
            grants.values.filter { it.sessionId == sessionId && it.revokedAtMs == null }.sortedByDescending { it.grantedAtMs }
        }

    /**
     * **当下活着**几张（跨会话；未撤销且未过期——第五百五十八批读数用的只读访问器）。
     *
     * 为什么必须有这一口（而不是让读数侧逐会话数 [active]）：读数是**全机**的，
     * `active` 是会话维的；且"过期"在内存里是惰性撤销（[hit] 摸到才算），状态读数要
     * 按时刻**如实**判。只加这一口：grant/hit/revoke/evict 一个字没动，467 判定逻辑零改动。
     */
    fun liveCount(nowMs: Long): Int = synchronized(lock) {
        grants.values.count { it.revokedAtMs == null && !it.expired(nowMs) }
    }

    /** 直测收尾（生产不调）。 */
    @Synchronized
    fun resetForTest() {
        grants.clear()
        pending.clear()
        violations.clear()
        counted.clear()
        seq = 0
        LeaseLedger.resetForTest()
        LeaseOfferChoice.resetForTest()
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    /**
     * 打满就淘汰：**先别的会话**（最老），再本会话最老（定点 U1）。
     *
     * ⚠️ 数的是**活着**的租约（`revokedAtMs == null`）：撤掉的那张仍留在表里（`hit` 要能
     * 回一句"已撤销"，不能当成"从没授过"），所以不能拿 `grants.size` 当循环条件——
     * 那会永远 ≥ 上限、原地死循环。
     */
    private fun evictFor(sessionId: Long, nowMs: Long) {
        while (true) {
            val live = grants.values.filter { it.revokedAtMs == null }
            if (live.size < LeaseLimits.MAX_LIVE) return
            val victim = live.filter { it.sessionId != sessionId }.minByOrNull { it.grantedAtMs }
                ?: live.minByOrNull { it.grantedAtMs }
                ?: return
            revokeOne(victim, "evicted", nowMs)
        }
    }

    /** 已撤的旧行别在内存里无限攒（账在盘上，内存只留判断要用的那一小段）。 */
    private fun pruneRevoked() {
        val revoked = grants.values.filter { it.revokedAtMs != null }.sortedBy { it.revokedAtMs }
        val over = revoked.size - LeaseLimits.MAX_LIVE * 2
        if (over > 0) revoked.take(over).forEach { grants.remove(it.id) }
    }

    private fun revokeOne(g: LeaseGrant, why: String, nowMs: Long) {
        val gone = g.copy(revokedAtMs = nowMs, revokeWhy = why)
        grants[g.id] = gone
        LeaseLedger.append(base(LeaseKinds.REVOKE, gone, nowMs).put("why", why))
    }

    private fun expire(g: LeaseGrant, nowMs: Long) {
        revokeOne(g, "expired", nowMs)
    }

    private fun downgrade(sessionId: Long, cls: String, n: Int, tool: String, nowMs: Long): LeaseGrant? {
        val alive = grants.values.filter { it.sessionId == sessionId && it.cls == cls && it.revokedAtMs == null }
        val first = alive.minByOrNull { it.grantedAtMs } ?: return null
        if (n >= LeaseLimits.VIOLATION_STEP * 2) {
            alive.forEach { revokeOne(it, "downgrade", nowMs) }
            return first.copy(revokedAtMs = nowMs, revokeWhy = "downgrade")
        }
        alive.forEach { g ->
            val next = g.used + maxOf(1, g.remaining() / 2)
            grants[g.id] = g.copy(cap = next)
            LeaseLedger.append(
                base(LeaseKinds.DOWNGRADE, g, nowMs)
                    .put("tool", tool)
                    .put("cap_before", g.cap)
                    .put("cap_after", next),
            )
        }
        return grants[first.id]
    }

    /** 一行账的公共底（七种 kind 共用同一条形状，字段只增不改）。 */
    private fun base(kind: String, g: LeaseGrant, nowMs: Long): JSONObject = JSONObject()
        .put("kind", kind)
        .put("id", g.id)
        .put("at", nowMs)
        .put("cls", g.cls)
        .put("scope", g.scope.wire())
        .put("ttl", g.ttlMs)
        .put("cap", g.cap)
        .put("used", g.used)
        .put("card", g.sourceCardId)
        .put("session", g.sessionId)
}

/**
 * UI → 内核的**租约旁路**（与 171 的 [ConfirmScopeChoice] 同型：按 callId 记一条，
 * 批准时消费、消费即删）。
 *
 * 为什么需要它：卡面能拿到报价（`ConfirmRequest.leaseOffer`），但"点了租约那一句"
 * 这个意图要传回内核（`ConfirmFlow.resumeWithApproval`）——中间隔着一个 `onApprove()`。
 * callId 是单张挂起卡的锚，同刻只有一张卡，条目消费即删、不会跨卡串味。
 */
internal object LeaseOfferChoice {

    private const val CAP = 16

    private val pending = HashMap<String, LeaseOffer>()

    @Synchronized
    fun record(callId: String, offer: LeaseOffer) {
        if (pending.size >= CAP) pending.clear()
        pending[callId] = offer
    }

    /** 取出并移除（没点 ⇒ null）。 */
    @Synchronized
    fun consume(callId: String): LeaseOffer? = pending.remove(callId)

    @Synchronized
    fun discard(callId: String) {
        pending.remove(callId)
    }

    @Synchronized
    fun resetForTest() {
        pending.clear()
    }
}
