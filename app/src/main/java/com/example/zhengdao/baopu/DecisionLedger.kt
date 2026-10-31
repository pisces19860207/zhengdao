// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零八批（2026-10-25 用户：「决策中心的功能那么强大应该用起来的」）：
// **决策记录流**——凡是「自动做了／替你做了／改了状态」的事，都在这里留一条，
// 能查、能筛、能回滚。落点 `<App私有>/抱朴记忆/决策记录.jsonl`。
//
// 为什么是一条独立记录流、不是第二套审计（先例：100 批 `PostAdoptionLog`）：
//   - 各域的既有账本**一个字节都不动**（技能 `_变更.jsonl`、产物台账、进化档案、
//     `决策审计.jsonl` 各记各的专业事实）；
//   - 决策中心要的是**一种形状**（kind/actor/reason+证据/before→after/at/session/
//     reversible/rollbackRef），把五种异构格式在读取时归一，代价是每加一类
//     「副作用但零记录」的路径就多一处解析分叉，而回滚要的**稳定引用**根本没处可取；
//   - 所以本流只记「统一形状＋回滚引用」，与 100 批「一条独立记录流，不是第二套审计」
//     同一判词。不建表（`BaopuDb.VERSION` 一个字节不动）。
//
// 三条硬纪律（负例逐条红，见 `DecisionLedgerTest`）：
//   ① `reason` 与 `evidence` **缺一条就整条不落**（宁可无账，不落空账）；
//   ② `reversible` 只能被**降级**（kind 说不可回滚的，谁都改不成可回滚）；
//   ③ 回滚失败**不许**留 `rollback_of`、不许把目标行标成已回滚。
//
// 本文件零 Android 依赖（只 file＋org.json），JVM 用真临时目录直测。
//
// 第二百九十三批 ③（2026-10-31）：本文件的两处搬家与一处新增，都在**形状与口**的界线上：
//   · 208 的词表（`DecisionKind`／`DecisionGroup`／`DecisionAudit`／`DecisionActor`／`DecisionDraft`）
//     搬进 `:policy` 的 `DecisionDraft.kt`——宰相要落账就得拿得到形状，而 :policy 不许反向依赖 :app。
//     **搬的是逐字文本**：wire 字面量、注释、默认值一个没改（逐行对照见交付报告）。
//     跟着搬的只有可见性：`internal` 以编译单元为界，故顶层声明放宽为公开。
//   · 本对象实现 `:policy` 的 [LedgerPort]，并在既有的 [install]／[uninstallForTest] 里登记／摘除
//     ——**没有新增装配点**：装账本＝装端口，还是那一处 `install`。
//   · `DecisionRecord`／`DecisionAppend` **没搬**：一个带 `org.json`、一个是 `recordOnce` 的三态，
//     都是实现侧的事，搬进白名单匣就是把 IO 壳塞进匣子。
package com.example.zhengdao.baopu

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * [DecisionLedger.recordOnce] 的三态（第二百四十九批）。
 *
 * `DUPLICATE` **不是错误**：同一个动作被重跑时，第二条账应当被吞掉——
 * 一动作两账会让抽检分不清「真做了两次」还是「记了两次」。
 */
internal enum class DecisionAppend(val wire: String) {
    /** 新落的。 */
    APPENDED("appended"),

    /** 这个事件键已经有一条账（不重复落）。 */
    DUPLICATE("duplicate"),

    /** 写不进去（缺键、账本损坏、盘错）。 */
    FAILED("failed"),
}


/** 一条决策记录（JSONL 的一行；键名固定，测试逐个钉）。 */
internal data class DecisionRecord(
    val id: String,
    val kind: DecisionKind,
    val group: DecisionGroup,
    val actor: DecisionActor,
    /** 一句话＋**证据 id**（`evidence` 单独一列，缺一不落）。 */
    val reason: String,
    val evidence: String,
    /** 改了什么（可读摘要，不是正文）。 */
    val before: String,
    val after: String,
    val atMs: Long,
    /** 来源会话；-1＝无会话（系统级动作）。 */
    val sessionId: Long,
    val reversible: Boolean,
    /** 回滚要用的引用（路径／备份名／id；不可回滚 ⇒ 空串）。 */
    val rollbackRef: String,
    /** 回滚行专用：被回滚那条的 id。 */
    val rollbackOf: String? = null,
    /**
     * 读侧标记（**不落盘**）：这条已经被回滚过。
     *
     * 222 真机验收 G3：账本里早有 `rollback_of` 行，但记录页没有消费它 ⇒ 回滚之后撤销钮还在、
     * 组头「N 条可撤销」也不降。由 [read] 统一回填：被任何一条 `rollback_of` 指到的记录，
     * `rolledBack=true` 且 `reversible` 降级为 false（只降级，不改写历史）。
     */
    val rolledBack: Boolean = false,
) {
    /** 写进 JSONL 的一行。 */
    fun toJson(): JSONObject = JSONObject()
        .put("v", VERSION)
        .put("id", id)
        .put("kind", kind.wire)
        .put("group_if_known", group.wire)
        .put("actor", actor.wire)
        .put("reason", reason)
        .put("evidence", evidence)
        .put("before", before)
        .put("after", after)
        .put("at", atMs)
        .put("session", sessionId)
        .put("reversible", reversible)
        .put("rollback_ref", rollbackRef)
        .put("rollback_of", rollbackOf ?: JSONObject.NULL)

    companion object {
        /** 行格式版本（与 `BaopuDb.VERSION` 无关：这是文件格式，不是库结构）。 */
        const val VERSION = 1

        const val VERDICT_CONFIRMED = "confirmed"
        const val VERDICT_WRONG = "wrong"
    }
}

/**
 * 决策记录流（追加式 JSONL，`抱朴记忆/决策记录.jsonl`）。
 *
 * 房型照 [EvolutionAuditLog]：只追加、不覆盖、`@Synchronized`、父目录不存在就建。
 * 与它不同的是**写失败不抛**：记录是留痕不是闸门——动作已经发生了，账本卡住不许
 * 把用户已经看到的动作回滚（71 批① 口径）。失败由 [lastError] 给调用方 log。
 */
internal object DecisionLedger : LedgerPort {

    /** 文件名（与记忆/技能同一棵目录，用户可看可备份）。 */
    const val FILE_NAME = "决策记录.jsonl"

    /** 生产落点（装配层 [install] 注入；null＝没装配，记录只进内存——测试与早期启动都安全）。 */
    @Volatile
    private var root: File? = null

    /** 最近的写失败（给调用方 log 的一句话；null＝没失败过）。 */
    @Volatile
    override var lastError: String? = null
        private set

    private var seq = 0

    /**
     * 装配层调用（只存目录，不存 Context——`StaticFieldLeak` 的教训）。
     *
     * 293 ③ 起这里顺手把本对象登记成 `:policy` 的账本端口：**装账本＝装端口，还是这一个装配点**。
     * 拆成两处（装目录一处、装端口一处）就是"落点装了、端口没装"那种静默不落账。
     */
    fun install(globalRoot: File) {
        root = globalRoot
        seq = 0
        LedgerPorts.ledger = this
    }

    /** 现读装配的落点（测试断言「装到哪了」用）。 */
    fun installedRoot(): File? = root

    /**
     * 卸掉落点（**只有测试会调**；装配层不调）。
     *
     * 为什么需要：落点是**进程级全局**。某个用例 [install] 之后如果不还原，同 JVM 里
     * 「没装配落点就不落」那类用例就会随**执行顺序**变红（第二百二十四批实证：
     * 新增一个测试类改变了类扫描顺序，RiskWiringTest 装完没还 ⇒ DecisionLedgerTest.L11 红）。
     * 修在装配点这一侧（谁装谁还），而不是把那条断言改宽。
     *
     * 293 ③：账本端口跟着落点一起摘——同一句"谁装谁还"，否则上一个用例装的端口会带着
     * 它的临时目录被下一个用例写进去。
     */
    internal fun uninstallForTest() {
        root = null
        seq = 0
        LedgerPorts.ledger = null
    }

    /** 落点文件。 */
    fun fileIn(globalRoot: File): File = File(globalRoot, FILE_NAME)

    /**
     * 写一条（落点＝装配的那个目录）。
     *
     * @return 落成的那条（含生成好的 id）；校验不过／没装配／写失败 ⇒ null
     */
    fun record(draft: DecisionDraft): DecisionRecord? {
        val r = root ?: run {
            lastError = "决策记录没装配落点（${draft.kind.wire}）"
            return null
        }
        return recordTo(r, draft)
    }

    /**
     * [LedgerPort] 的实现：**逐字转发** [record]，只是把「落成的那条」折成「落没落」。
     *
     * 为什么不返回 `DecisionRecord`：那个类型带 `toJson()`（org.json），而 :policy 的白名单
     * 只许 `:core`＋stdlib——把它的返回值递进匣子，等于让匣子间接依赖 org.json。
     * 失败原因走 [lastError]，与 `record` 返回 null 时调用方本来该读的那一句是同一句。
     */
    override fun write(draft: DecisionDraft): Boolean = record(draft) != null

    /**
     * 「搜索范围」这件事的**专用落点**（`read_scope`）。
     *
     * 为什么单独给一个函数、而不是让调用方自己拼一条 [DecisionDraft]：
     * `BaopuSkillCategoryTest.T5` 把设置页的源码钉着「正文头里不许出现 kind 那种字段」
     * （扫的是**整份文件**的代码文本）。把 kind 字面量收在数据层，调用方只留一次调用，
     * 那条门禁就不必为此放宽——它的本意（分类不许进正文头）一个字都没动。
     *
     * **第二百二十九批起：新行不再产生。** 读侧开关随 229 删除（授权即全盘，没有意愿可拨），
     * 全仓已没有 [recordReadScope] 的调用点（`DecisionWiringGateTest` 钉住零调用）。
     * 本函数与 [DecisionKind.READ_SCOPE] **都保留**：老账本里的历史行还要读得出来、
     * 还能在决策中心渲染（删 kind ＝ 老账变哑账，派工单 §3 负例点名）。
     */
    fun recordReadScope(wasOn: Boolean, on: Boolean, nowMs: Long = System.currentTimeMillis()): DecisionRecord? =
        record(
            DecisionDraft(
                kind = DecisionKind.READ_SCOPE,
                actor = DecisionActor.USER,
                reason = if (on) "把手机文件纳入搜索范围" else "把手机文件移出搜索范围",
                evidence = "scope:search_phone_on",
                before = if (wasOn) "开" else "关",
                after = if (on) "开" else "关",
                atMs = nowMs,
                // 不是偷懒：198 的 ⑥① 把拨开关的写入点钉死在设置页一处，
                // 决策中心没有第二条写入口 ⇒ 标成可回滚就是骗人（派工单负例③）。
                reversible = false,
            ),
        )

    /**
     * 写一条到指定目录（测试与「跨根」场景用）。
     *
     * 校验在前（[validate]），落盘在后——**校验不过一个字节都不写**。
     * [DecisionDraft.uniqueByEvidence] 为真时先查一次账（与 [recordOnce] 共用
     * [rowsWithEvidence]）：同键已有行就**不写第二条**——是「不写」，不是「重写整本账把它抹掉」，
     * 历史账行一个字节都不许动（第二百九十二批 P1 判据③／负例「改历史账行」）。
     */
    @Synchronized
    fun recordTo(globalRoot: File, draft: DecisionDraft): DecisionRecord? {
        if (draft.uniqueByEvidence) {
            // 三态里"能继续"只有一种：读得动、且这个键一条都没有（空表）。
            val hits = rowsWithEvidence(globalRoot, draft.evidence)
            if (hits == null || hits.isNotEmpty()) return null
        }
        val rec = build(draft) ?: return null
        val written = runCatching {
            val f = fileIn(globalRoot)
            f.parentFile?.mkdirs()
            f.appendText(rec.toJson().toString() + "\n", Charsets.UTF_8)
            lastError = null
            rec
        }.getOrElse {
            lastError = "决策记录写失败（${draft.kind.wire}）：${it.javaClass.simpleName}: ${it.message}"
            null
        }
        // 第二百九十二批 P1 修法④：这本账第一次用「执行后才记账」的新口径落行 ⇒ 当场标一条口径变更点。
        // 标在账本里而不是标在管线里：管线只许有一处 208 写入（276 的那道闸钉着），
        // 而「谁先动这本账、谁负责说清口径」本来就是账本自己的事。
        if (written != null && draft.uniqueByEvidence) stampChangePoint(globalRoot)
        return written
    }

    /**
     * 一次读盘的**幂等查重**（[recordOnce] 与 `uniqueByEvidence` 那一路共用——一把机制只实现一遍）。
     *
     * 查与写必须落在**同一个方法锁**里：先 `read` 再 `recordTo` 中间没有锁，
     * 两个线程可以同时"没查到"然后各写一行（249 批说过的话，这里不把它重新拆开）。
     *
     * @return `emptyList()`＝账上还没有这个键；非空＝已有同键的行；
     *         `null`＝读不动**或**碰到坏行 ⇒ 调用方一律**不写**（宁可缺这一条，
     *         也不往一本读不出来的账后面追加，更没法保证同键那条不在场）
     */
    private fun rowsWithEvidence(globalRoot: File, eventKey: String): List<DecisionRecord>? =
        runCatching {
            val f = fileIn(globalRoot)
            if (!f.isFile) return@runCatching emptyList()
            val hits = mutableListOf<DecisionRecord>()
            for (line in f.readLines(Charsets.UTF_8)) {
                if (line.isBlank()) continue
                val parsed = parseLine(line)
                if (parsed == null) {
                    lastError = "幂等账碰到坏行，这次不追加（$eventKey）"
                    return@runCatching null
                }
                if (parsed.evidence == eventKey) hits += parsed
            }
            hits
        }.getOrElse {
            lastError = "幂等账读失败：${it.javaClass.simpleName}: ${it.message}"
            null
        }

    /** 口径变更点的事件键（`evidence` 与键逐字相同——249 那条纪律）。 */
    const val POLICY_EVENT_KEY = "ledger_policy:exec_then_record"

    /**
     * 口径变更点：第二百九十二批 P1（2026-10-31，本机时区零点）起，业务账只在**执行成功之后**落行。
     *
     * 写死成一个时刻，而不是"这台机器第一次落账那天"：变更的是**口径**，不是开机日——
     * 一台三个月后才第一次写账的手机，账上也该说「10-31 之后＝执行后记账」。
     */
    const val POLICY_CHANGE_MS = 1_793_376_000_000L

    /** 标一次口径变更点（同一本账只有一条：走 [recordOnce] 的事件键幂等）。 */
    private fun stampChangePoint(globalRoot: File) {
        recordOnce(
            globalRoot,
            POLICY_EVENT_KEY,
            DecisionDraft(
                kind = DecisionKind.LEDGER_POLICY,
                actor = DecisionActor.AUTO,
                reason = "账本口径变更：自 2026-10-31 起业务账只在执行成功后落行——拒绝／取消／超时一律不落",
                evidence = POLICY_EVENT_KEY,
                before = "prepare（出卡之前）就记账：没做的事也被记成做了",
                after = "执行成功之后才记账：账上只有真做过的事",
                atMs = POLICY_CHANGE_MS,
            ),
        )
    }

    /**
     * **幂等追加**（第二百四十九批）：同一个 `eventKey` 全本账只许有一条。
     *
     * 为什么要一个专门的入口、而不是让调用方先 [read] 一遍再 [recordTo]：
     * 那两步之间没有锁，两个线程可以同时"没查到"然后各写一行；这里查与写在
     * 同一个 `@Synchronized` 里完成（与方法锁的是同一把：`DecisionLedger` 这个实例）。
     *
     * 三条纪律：
     * 1. `draft.evidence` 必须**逐字等于** `eventKey`——账本反查只认 `evidence` 一列，
     *    两处各写一个键就会在"查不到"和"写得进"之间留一条缝；
     * 2. 账本里出现**坏行**就整次拒写（宁可这一条不落，也不往后追加到读不出来的账里）；
     * 3. 失败一律 [DecisionAppend.FAILED]，**不抛**（记录是留痕不是闸门，71 批① 口径）。
     *
     * @return APPENDED 新落；DUPLICATE 已有同键（**不写**）；FAILED 写不进去
     */
    @Synchronized
    fun recordOnce(globalRoot: File, eventKey: String, draft: DecisionDraft): DecisionAppend {
        if (eventKey.isBlank()) {
            lastError = "幂等账缺事件键（${draft.kind.wire}）"
            return DecisionAppend.FAILED
        }
        if (draft.evidence != eventKey) {
            lastError = "幂等账的事件键必须与 evidence 逐字相同（$eventKey／${draft.evidence}）"
            return DecisionAppend.FAILED
        }
        // 查重只认 [rowsWithEvidence] 这一把机制（292-P1：uniqueByEvidence 那一路走同一个读盘口）。
        val existing = rowsWithEvidence(globalRoot, eventKey) ?: return DecisionAppend.FAILED
        if (existing.isNotEmpty()) return DecisionAppend.DUPLICATE
        return if (recordTo(globalRoot, draft) != null) DecisionAppend.APPENDED else DecisionAppend.FAILED
    }

    /** 校验＋组装（纯函数：给定 draft，要么给一条合法记录，要么 null 并留 [lastError]）。 */
    @Synchronized
    fun build(draft: DecisionDraft): DecisionRecord? {
        // ① reason／evidence 缺一不落（负例②）
        if (draft.reason.isBlank()) {
            lastError = "决策记录缺 reason（${draft.kind.wire}）——不落空账"
            return null
        }
        if (draft.evidence.isBlank()) {
            lastError = "决策记录缺证据 id（${draft.kind.wire}／${draft.reason}）——不落空账"
            return null
        }
        if (draft.atMs <= 0L) {
            lastError = "决策记录时刻不合法（${draft.kind.wire}）"
            return null
        }
        // ② 只能降级：kind 说不可回滚的，谁传 true 都改不成可回滚
        val reversible = draft.kind.reversible && (draft.reversible ?: true)
        // ③ 回滚行必须指得出目标；非回滚行不许带 rollbackOf
        if (draft.kind == DecisionKind.ROLLBACK_OF && draft.rollbackOf.isNullOrBlank()) {
            lastError = "回滚记录没带 rollback_of"
            return null
        }
        if (draft.kind != DecisionKind.ROLLBACK_OF && !draft.rollbackOf.isNullOrBlank()) {
            lastError = "非回滚记录带了 rollback_of（${draft.kind.wire}）"
            return null
        }
        val group = draft.groupOverride ?: draft.kind.group
        seq += 1
        return DecisionRecord(
            id = idOf(draft, seq),
            kind = draft.kind,
            group = group,
            actor = draft.actor,
            reason = draft.reason,
            evidence = draft.evidence,
            before = draft.before,
            after = draft.after,
            atMs = draft.atMs,
            sessionId = draft.sessionId,
            reversible = reversible,
            rollbackRef = if (reversible) draft.rollbackRef else "",
            rollbackOf = draft.rollbackOf,
        )
    }

    /**
     * 现读（新→旧）。坏行／**自造 kind** 一律跳过（不炸解析、不当成别的类）。
     *
     * @param limit 读多少条（记录页按组再分页，这里只封住一次读盘的量）
     */
    fun read(globalRoot: File, limit: Int = READ_LIMIT): List<DecisionRecord> = runCatching {
        val f = fileIn(globalRoot)
        if (!f.isFile) return@runCatching emptyList()
        val rows = f.readLines(Charsets.UTF_8).asSequence()
            .mapNotNull(::parseLine)
            .sortedByDescending { it.atMs }
            .toList()
        // G3：把「已被回滚」回填到原记录上（只降级，不改写账本）。
        val rolledBackIds = rows.asSequence()
            .filter { it.kind == DecisionKind.ROLLBACK_OF }
            .mapNotNull { it.rollbackOf }
            .toSet()
        rows.asSequence()
            .map { r ->
                if (r.id in rolledBackIds) r.copy(reversible = false, rolledBack = true) else r
            }
            .take(limit)
            .toList()
    }.getOrElse {
        lastError = "决策记录读失败：${it.javaClass.simpleName}: ${it.message}"
        emptyList()
    }

    /** 一行 ⇒ 一条（认不出 kind／缺 id／缺时刻 ⇒ null）。 */
    fun parseLine(raw: String): DecisionRecord? {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val kind = DecisionKind.ofWire(o.optString("kind")) ?: return null
        val id = o.optString("id")
        val at = o.optLong("at", 0L)
        val reason = o.optString("reason")
        val evidence = o.optString("evidence")
        if (id.isEmpty() || at <= 0L || reason.isEmpty() || evidence.isEmpty()) return null
        // 组：认 `group_if_known`（回滚行要留在被回滚那条的组里）；认不出就按 kind 的组
        val group = DecisionGroup.entries.firstOrNull { it.wire == o.optString("group_if_known") }
            ?: kind.group
        return DecisionRecord(
            id = id,
            kind = kind,
            group = group,
            actor = DecisionActor.ofWire(o.optString("actor")),
            reason = reason,
            evidence = evidence,
            before = o.optString("before"),
            after = o.optString("after"),
            atMs = at,
            sessionId = o.optLong("session", -1L),
            reversible = o.optBoolean("reversible", kind.reversible),
            rollbackRef = o.optString("rollback_ref"),
            rollbackOf = if (o.has("rollback_of") && !o.isNull("rollback_of")) o.optString("rollback_of") else null,
        )
    }

    /** 一页读多少条（记录页 ≤50 行渲染，读盘留两倍余量）。 */
    const val READ_LIMIT = 200

    /** 记录 id：`<kind>:<时刻>:<证据短哈希>:<序号>`（同毫秒同类的两条也分得开）。 */
    private fun idOf(draft: DecisionDraft, seq: Int): String =
        "${draft.kind.wire}:${draft.atMs}:${sha8(draft.evidence)}:$seq"

    /** 短的稳定摘要（8 位十六进制）。 */
    private fun sha8(s: String): String = runCatching {
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(8)
    }.getOrDefault("00000000")
}

/**
 * 回滚的执行口（各域自己的实现由装配层注入；决策中心只认「成没成」）。
 *
 * 为什么是函数类型不是接口：装配层拿得到 Context／库／盘，域实现散在各自的写口上；
 * 一个 lambda 就够了，多一层 interface 只是多一个文件。
 */
internal fun interface DecisionRollbackHandler {
    /** @return true＝真的回滚成功（只有 true 才会留 `rollback_of`）。 */
    fun rollback(record: DecisionRecord): Boolean
}

/** 回滚执行＋留痕（**失败不留痕、不标成功**——负例③④的落点）。 */
internal object DecisionRollbacks {

    /** 装配层注入（null ⇒ 决策中心的回滚钮点了只会说「这里没有回滚通道」）。 */
    @Volatile
    var handler: DecisionRollbackHandler? = null

    /**
     * 回滚一条并留一条 `rollback_of`。
     *
     * @return 成功（含成功留痕）⇒ true
     */
    fun run(
        target: DecisionRecord,
        root: File,
        handler: DecisionRollbackHandler?,
        atMs: Long,
        actor: DecisionActor = DecisionActor.USER,
    ): Boolean {
        val h = handler ?: return false
        if (!target.reversible) return false
        val ok = runCatching { h.rollback(target) }.getOrDefault(false)
        if (!ok) return false
        // 回滚行：组随被回滚那条走（分组卡里它出现在同一组，计数才说得通）
        DecisionLedger.recordTo(
            root,
            DecisionDraft(
                kind = DecisionKind.ROLLBACK_OF,
                actor = actor,
                reason = "回滚：${target.reason}",
                evidence = target.id,
                before = target.after,
                after = target.before,
                atMs = atMs,
                sessionId = target.sessionId,
                rollbackRef = target.rollbackRef,
                reversible = false,
                rollbackOf = target.id,
                groupOverride = target.group,
            ),
        ) ?: return true // 回滚本身成功了，账没记上也不许谎报失败
        return true
    }
}

