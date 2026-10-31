// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十三批（2026-10-31）· 208 的**词表**从 :app 的 `DecisionLedger.kt` 搬进 :policy。
//
// 为什么搬这五个、不搬别的：派工单 293 ③ 只切「账本接口化」这一刀——宰相（`MediatorWiring`）
// 要往 208 写一行，就得一手拿**形状**（`DecisionDraft` 与它的四个枚举）、一手拿**口**（`LedgerPort`）。
// 形状若还留在 :app，`:policy` 就得反过来依赖 :app，白名单匣当场失效；所以形状必须跟着口一起搬过去。
//
// 边界（本批不许越）：
//   · 只搬**声明**。落盘、幂等、变更点、哈希、`toJson` 全留在 :app 的 `DecisionLedger.kt`
//     ——`DecisionRecord`／`DecisionAppend` 那两条带 `org.json`／`java.io` 的类型**没搬**，
//     搬了就是把 IO 壳塞进白名单匣，正是本批负例点名的「把 android 依赖临时留在 :policy」。
//   · 只抽**一个**账本接口。`RoyalDecisionLedger`／`SelfRulingLedger`／产物台账／进化档案
//     一个都不动（另见交付报告：其余账本的接线是各批自己的事）。
//   · 判据、字面量、默认值一个字没改；`wire` 是稳定键，改它等于让老账本读不出来。
//   · 唯一的机械改动是可见性：`internal` 以编译单元为界，:app 看不见 :policy 的 internal，
//     故顶层声明放宽为公开（放宽清单见交付报告）。
package com.example.zhengdao.baopu

/** 这件事归哪一类（**闭集**：十类＋回滚行＋104 批的授权行；不许自造，见 [ofWire]）。 */
enum class DecisionKind(
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
     * 第二百五十五批：连续 2 次执行失败 ⇒ 技能**降为草稿**（派工单点名的 `skillDemoted` 那一行；
     * evidence 带两次失败执行的 opId）。
     *
     * 为什么不复用 [SKILL_DRAFT_RETIRE]：那个记的是「闲置」（30 天未用／超 20 条），
     * 这条记的是「用坏了」——抽检与复盘时必须分得开。
     * 不进 [REQUIRED]（208 点名的是十类，这类是本批新增的行为账）。
     * `reversible=false`：正文没动、人确实能手改回去，但决策中心没有「草稿→正式」的通用回滚入口
     * （[SKILL_PROMOTE] 的回滚是反方向），标可回滚就是骗人。
     */
    SKILL_DEMOTED("skill_demoted", DecisionGroup.SKILL, false, DecisionAudit.WEEKLY),

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
     * 第二百二十八批 C 片：回放的**站点信任**加/撤（`evidence` 带方向：
     * `site_trust_add:<host>`／`site_trust_remove:<host>`）。
     *
     * 可回滚（`reversible=true`）：加的回滚＝撤、撤的回滚＝加——名单文件的两个纯文件
     * 操作就是回滚通道；回滚行本身由 [ROLLBACK_OF] 记，回滚动作不再重复写本类。
     */
    SITE_TRUST("site_trust", DecisionGroup.GRANT, true, DecisionAudit.NONE),

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

    /**
     * 第二百四十二批：**计划即契约的勘验对账不符**（实际动作超出契约／实际风险高于预估／
     * 实际问人次数与 `expectedAsks` 对不上）。
     *
     * 为什么不塞进 [EVAL_VERDICT]：206 那条记的是"两个实现谁对谁错"的**裁决**，
     * 这条记的是"同一次运行里说好的和做到的差在哪"——抽检时要能按它筛出
     * "哪类计划估算老是不准"，混进裁决组就筛不出来。
     *
     * 不进 [REQUIRED]：派工单 §1 D 段只要求「不符 ⇒ 写 208」，没给这类记录点过名，
     * 而 REQUIRED 是 208 那张"点名的十类"闭集。
     */
    PLAN_CONTRACT_FINDING("plan_contract_finding", DecisionGroup.EVAL, false, DecisionAudit.WEEKLY),

    /**
     * 第二百九十二批 P1：**账本自己的口径变更点**（派工单 292-P1 修法④）。
     *
     * 历史账行不许改（只增不改），但同一本账里前后是两种口径——「prepare 出卡之前就记」
     * 与「执行成功之后才记」。不标出来，抽检的人拿着一行「删进回收站」无从判断
     * 那一行到底是做过的事还是当时的一念头；标在文档里不算数，得在**账上**。
     *
     * 为什么单开一类而不是塞进 [AUTO_ACT]：AUTO_ACT 记的是「模型动过手」，这条记的是
     * 「账本从这一天起改了记法」——它不是副作用，没有回滚通道（`reversible=false`），
     * 也不进抽检队列（`NONE`）。
     * 不进 [REQUIRED]：那是 208 点名的十类**业务账**；这一条是账本的自述，
     * 由 [DecisionLedger] 自己在第一笔新口径的账落进这本账时标一次（不是各调用方各标）。
     */
    LEDGER_POLICY("ledger_policy", DecisionGroup.EVAL, false, DecisionAudit.NONE),
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
enum class DecisionGroup(val wire: String) {
    /** §2.7 待抽检组（只放抽检命中的记录，不是对象组）。 */
    SAMPLING("sampling"),
    SKILL("skill"),
    GRANT("grant"),
    ARTIFACT("artifact"),
    EVAL("eval"),
    DIARY("diary"),
}

/** 抽检档（§2.7：真删与正式退役 100%，其余每周 ≤5 条配额）。 */
enum class DecisionAudit {
    /** 必抽（100%）。 */
    ALWAYS,

    /** 每周配额（≤ [DecisionSampling.WEEKLY_QUOTA] 条）。 */
    WEEKLY,

    /** 不抽（可逆、见过就够）。 */
    NONE,
}

/** 谁做的（记录行的 `actor`）。 */
enum class DecisionActor(val wire: String) {
    USER("user"),
    MODEL("model"),
    AUTO("auto"),
    ;

    companion object {
        fun ofWire(wire: String): DecisionActor = entries.firstOrNull { it.wire == wire } ?: AUTO
    }
}

/**
 * 写一条记录的输入（[reversible] 为 null ⇒ 按 kind 的默认；非 null ⇒ **只能降级**）。
 */
data class DecisionDraft(
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
    /**
     * 第二百九十二批 P1：**这一条按 `evidence` 幂等**（同一个事件键在这本账里只许有一行）。
     *
     * 为什么是 draft 上的一格、而不是让调用方改走 [DecisionLedger.recordOnce]：
     * 208 的通用账由 `GuardPipeline` 那**唯一**一处 `DecisionLedger.record(` 落
     * （`TaskGrantWiringGateTest` 与 291 的部位普查都把那一处钉着），换入口就是改接线；
     * 而「同一个动作重跑不许补第二条」是**账本**的属性，不是某个调用方的自觉——
     * 放在账本里，明天新增的调用点也躲不掉。
     * 缺省 `false` ⇒ 存量调用点一个字不改、行为逐字节不变。
     */
    val uniqueByEvidence: Boolean = false,
)
