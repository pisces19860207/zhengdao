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
package com.example.zhengdao.baopu

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** 这件事归哪一类（**闭集**：十类＋回滚行＋104 批的授权行；不许自造，见 [ofWire]）。 */
internal enum class DecisionKind(
    /** JSONL 里 `kind` 字段的字面量（稳定键：改字面量等于让老账本读不出来）。 */
    val wire: String,
    /** 归到哪个对象组（历史 tab 的分组卡按它折）。 */
    val group: DecisionGroup,
    /** 这类事天生能不能回滚（写进每条记录；只能被降级，不能被改写）。 */
    val reversible: Boolean,
    /** 抽检档：必抽 100%／每周配额／不抽。 */
    val audit: DecisionAudit,
) {
    /** 草稿转正（手动／满 3 次自动，207 §1.5）⇒ 回退为草稿。 */
    SKILL_PROMOTE("skill_promote", DecisionGroup.SKILL, true, DecisionAudit.NONE),

    /** 192 免批判据判 AUTO 后的落地 ⇒ 撤销该技能（`.deleted-*`）。 */
    SKILL_AUTO_WRITE("skill_auto_write", DecisionGroup.SKILL, true, DecisionAudit.NONE),

    /** 正式技能退役／归档 ⇒ 恢复正文与索引行。 */
    SKILL_RETIRE("skill_retire", DecisionGroup.SKILL, true, DecisionAudit.ALWAYS),

    /** 治理器软退役草稿（30 天未用／超 20 条）⇒ 恢复正文与索引行。 */
    SKILL_DRAFT_RETIRE("skill_draft_retire", DecisionGroup.SKILL, true, DecisionAudit.WEEKLY),

    /**
     * 搜索范围授权／撤销（197）。
     *
     * **不可回滚**（`reversible=false`）——197/198 时代它由设置页开关产生，写入点全仓一处，
     * 决策中心拿不到第二条写入口 ⇒ 标成可回滚就是骗人。
     * **第二百二十九批起新行不再产生**（开关随 229 删除：授权即全盘，没有意愿可拨）；
     * kind 本身保留——老账本里的历史行还要读得出来、还能在决策中心渲染。
     */
    READ_SCOPE("read_scope", DecisionGroup.GRANT, false, DecisionAudit.NONE),

    /** 178 勾选批量删 ⇒ 从回收站恢复。 */
    ARTIFACT_DELETE("artifact_delete", DecisionGroup.ARTIFACT, true, DecisionAudit.NONE),

    /** 日记归档／浓缩／清理空条目 ⇒ 归档可回、浓缩与清理不可回（由写入方降级）。 */
    DIARY_MAINTAIN("diary_maintain", DecisionGroup.DIARY, true, DecisionAudit.WEEKLY),

    /** 日记真删 ⇒ **不可回滚**（147 边界③：删了就没了）⇒ 必抽检。 */
    DIARY_PURGE("diary_purge", DecisionGroup.DIARY, false, DecisionAudit.ALWAYS),

    /** 206 对拍结论（ADOPT/DEFER/REJECT／UNRUN）⇒ 不回滚，只留档。 */
    EVAL_VERDICT("eval_verdict", DecisionGroup.EVAL, false, DecisionAudit.NONE),

    /** 自治块的通用副作用（模型自己动手改了东西，没有更专门的 kind）。 */
    AUTO_ACT("auto_act", DecisionGroup.ARTIFACT, true, DecisionAudit.NONE),

    /** 回滚本身（派工单 §2：回滚也要留一条）——组随被回滚那条走。 */
    ROLLBACK_OF("rollback_of", DecisionGroup.GRANT, false, DecisionAudit.NONE),

    /** 104 批既有的工具组授权行（复用，不新造语义；不进抽检）。 */
    TOOL_GRANT("tool_grant", DecisionGroup.GRANT, true, DecisionAudit.NONE),

    /**
     * 第二百二十三批：**未分类工具被闸问到**（fail-closed 的留痕）。
     *
     * 为什么必须有这一类、不能塞进 [AUTO_ACT]：AUTO_ACT 记的是"模型已经真的动了手"，
     * 而这条记的是"闸没有判据、只好先问"——前者是既成事实，后者是**配置缺口**。
     * 混在一起，抽检时就分不出"这句该不该问"和"这件事该不该做"。
     *
     * `wire` 与 `RiskVerdict.auditTag` 逐字相同（`unclassified_tool`）：那就是这条账的机器键，
     * 一个字都不许两边各写一个。
     */
    RISK_UNCLASSIFIED("unclassified_tool", DecisionGroup.GRANT, false, DecisionAudit.NONE),
    ;

    companion object {
        /** 线值 ⇒ kind；**认不出就 null**（自造 kind 一律不渲染，也不静默当别的类）。 */
        fun ofWire(wire: String): DecisionKind? = entries.firstOrNull { it.wire == wire }

        /** 派工单 §1＋§2.7 点名的那十类（自造 kind 的负例就比这张表）。 */
        val REQUIRED: List<DecisionKind> = listOf(
            SKILL_PROMOTE, SKILL_AUTO_WRITE, SKILL_RETIRE, SKILL_DRAFT_RETIRE, READ_SCOPE,
            ARTIFACT_DELETE, DIARY_MAINTAIN, DIARY_PURGE, EVAL_VERDICT, AUTO_ACT,
        )
    }
}

/** 记录按**对象**归组（§2.5 的分组卡；组必须纯净——混进别组的 kind 即红）。 */
internal enum class DecisionGroup(val wire: String) {
    /** §2.7 待抽检组（只放抽检命中的记录，不是对象组）。 */
    SAMPLING("sampling"),
    SKILL("skill"),
    GRANT("grant"),
    ARTIFACT("artifact"),
    EVAL("eval"),
    DIARY("diary"),
}

/** 抽检档（§2.7：真删与正式退役 100%，其余每周 ≤5 条配额）。 */
internal enum class DecisionAudit {
    /** 必抽（100%）。 */
    ALWAYS,

    /** 每周配额（≤ [DecisionSampling.WEEKLY_QUOTA] 条）。 */
    WEEKLY,

    /** 不抽（可逆、见过就够）。 */
    NONE,
}

/** 谁做的（记录行的 `actor`）。 */
internal enum class DecisionActor(val wire: String) {
    USER("user"),
    MODEL("model"),
    AUTO("auto"),
    ;

    companion object {
        fun ofWire(wire: String): DecisionActor = entries.firstOrNull { it.wire == wire } ?: AUTO
    }
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
 * 写一条记录的输入（[reversible] 为 null ⇒ 按 kind 的默认；非 null ⇒ **只能降级**）。
 */
internal data class DecisionDraft(
    val kind: DecisionKind,
    val actor: DecisionActor,
    val reason: String,
    val evidence: String,
    val before: String,
    val after: String,
    val atMs: Long,
    val sessionId: Long = -1L,
    val rollbackRef: String = "",
    val reversible: Boolean? = null,
    val rollbackOf: String? = null,
    /**
     * 组覆盖（只有 `rollback_of` 用）：回滚行要跟着被回滚那条待在**同一个组**，
     * 否则分组卡的「可撤销数」会在两个组之间对不上。其余 kind 传了就按 kind 的组走。
     */
    val groupOverride: DecisionGroup? = null,
)

/**
 * 决策记录流（追加式 JSONL，`抱朴记忆/决策记录.jsonl`）。
 *
 * 房型照 [EvolutionAuditLog]：只追加、不覆盖、`@Synchronized`、父目录不存在就建。
 * 与它不同的是**写失败不抛**：记录是留痕不是闸门——动作已经发生了，账本卡住不许
 * 把用户已经看到的动作回滚（71 批① 口径）。失败由 [lastError] 给调用方 log。
 */
internal object DecisionLedger {

    /** 文件名（与记忆/技能同一棵目录，用户可看可备份）。 */
    const val FILE_NAME = "决策记录.jsonl"

    /** 生产落点（装配层 [install] 注入；null＝没装配，记录只进内存——测试与早期启动都安全）。 */
    @Volatile
    private var root: File? = null

    /** 最近的写失败（给调用方 log 的一句话；null＝没失败过）。 */
    @Volatile
    var lastError: String? = null
        private set

    private var seq = 0

    /** 装配层调用（只存目录，不存 Context——`StaticFieldLeak` 的教训）。 */
    fun install(globalRoot: File) {
        root = globalRoot
        seq = 0
    }

    /** 现读装配的落点（测试断言「装到哪了」用）。 */
    fun installedRoot(): File? = root

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
     */
    @Synchronized
    fun recordTo(globalRoot: File, draft: DecisionDraft): DecisionRecord? {
        val rec = build(draft) ?: return null
        return runCatching {
            val f = fileIn(globalRoot)
            f.parentFile?.mkdirs()
            f.appendText(rec.toJson().toString() + "\n", Charsets.UTF_8)
            lastError = null
            rec
        }.getOrElse {
            lastError = "决策记录写失败（${draft.kind.wire}）：${it.javaClass.simpleName}: ${it.message}"
            null
        }
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
