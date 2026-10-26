// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-26-第二百二十三批-分类器接线 §1（四件）＋ §2（判据/负例）。
//
// 本文件是**接线层**，不是判定层：四档边界与阈值全部在 `RiskMatrix.kt`（219 的唯一一张表），
// 这里只做三件外围事——① 把闸手里的事实（工作区根/已存在目标/最近调用）折成 `RiskCtx`
// 交给矩阵；② 把矩阵的 `reasonCode` 翻成一句人话（卡面上那一行、拒绝原文）；
// ③ 未分类那次 fail-closed 记一条账。
//
// 为什么必须有这样一个文件、而不能直接在闸里拼：`GuardPipeline` 手里没有"最近调用时刻"
// 这种事实（它是执行层），`RiskMatrix` 又不许碰时钟/盘（它是纯判定层）。事实的采集与
// 文案的翻译只能由第三层做——这一层。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.ToolCall
import java.util.concurrent.ConcurrentHashMap

/**
 * 风险矩阵的**唯一**调用口（生产）。
 *
 * 三条纪律：
 * 1. **表只有一张**：`table` 参数缺省就是 [RiskTable.DEFAULT]，生产调用点一个字都不传；
 *   单测传"改了一行"的表来验「改表 ⇒ 判定变」（派工单 §2 硬要求）。
 * 2. **零 IO**：本层不查盘、不读时钟——根/已存在目标/时刻全由调用方给。
 * 3. **频率事实只在这里记**：矩阵要的是"同一工具最近几次调用的时刻"，那是**执行事实**，
 *    矩阵自己不该记（它会变成有状态的对象）；本层记，且只记最近 [RECENT_CAP] 次。
 */
internal object RiskGate {

    /** 每个工具留多少条最近调用时刻（矩阵只看一个窗口内的条数，8 条够用）。 */
    private const val RECENT_CAP = 8

    /**
     * 终端等**不归四档**的工具按哪档算（`ImpactRisk` 删掉后它的位置）。
     *
     * 为什么是 2 而不是 0：`tierOf` 的两个消费者都是「要不要多说一句」——
     * 说多一句只会更啰嗦，不会更危险。把不归四档的终端按"写"算，是本批之前的行为，
     * 本批不借机改它（派工单红线：只改判定接线）。
     */
    private const val EXEMPT_TIER = 2

    /** 工具名 → 最近调用时刻（进程内；不落盘）。 */
    private val recent: MutableMap<String, MutableList<Long>> = ConcurrentHashMap()

    /** 未分类记账的**内存回读**（落盘那条走 `DecisionLedger`；这条给日志与测试）。 */
    private val unclassified = ArrayDeque<String>()

    /**
     * 判一次（矩阵的唯一生产入口）。
     *
     * @param roots 工作区根（`证道/` 的绝对路径）；空 = 调用方没给根 ⇒ 绝对路径一律判"外面"（保守）
     * @param existing 已存在的**写目标**绝对路径（覆盖判定用；调用方查盘后给）
     */
    fun judge(
        call: ToolCall,
        roots: List<String> = emptyList(),
        existing: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis(),
        table: RiskTable = RiskTable.DEFAULT,
    ): RiskVerdict {
        val ctx = RiskCtx(
            nowMs = nowMs,
            recentCalls = snapshot(call.name),
            workspaceRoots = roots,
            existingPaths = existing,
            // 白名单没有配置来源（派工单 §1 只点名"内网 URL 硬拒"）⇒ null = 只拒内网/本机名
            allowedUrlDomains = null,
        )
        val verdict = RiskMatrix.classify(call, ctx, table)
        // 记在判完之后：本次自己不该算进"最近 20 次"（否则第 20 次就被误判超频）
        remember(call.name, nowMs)
        return verdict
    }

    /**
     * 「这个动作算几档」——**不是第二张表**，就是矩阵的底档。
     *
     * 220 的 `ImpactRisk.tierOf` 由它取代：调用方只要一个 0..3 的数（决定"要不要多说一句"），
     * 别处不许再有自己的边界。
     */
    fun tierOf(call: ToolCall): Int {
        val verdict = RiskMatrix.classify(call)
        return verdict.risk?.ordinal ?: EXEMPT_TIER
    }

    /**
     * 未分类工具（fail-closed）记一条账：**带工具名与矩阵版本**（派工单 §1 第 3 条）。
     *
     * @return 落成的那条（没装配落点 ⇒ null，**不影响判定**——记账是留痕不是闸门）
     */
    fun noteUnclassified(call: ToolCall, verdict: RiskVerdict, atMs: Long = System.currentTimeMillis()): DecisionRecord? {
        val tag = verdict.auditTag ?: return null
        val key = "${call.name}@${verdict.matrixVersion}"
        synchronized(unclassified) {
            unclassified.addLast(key)
            while (unclassified.size > 16) unclassified.removeFirst()
        }
        return DecisionLedger.record(
            DecisionDraft(
                kind = DecisionKind.RISK_UNCLASSIFIED,
                actor = DecisionActor.AUTO,
                reason = "工具「${call.name}」没有风险档：按 R2 处理且必须问（fail-closed）",
                evidence = "$tag:${call.name}:v${verdict.matrixVersion}",
                before = "unclassified",
                after = "R2+必问",
                atMs = atMs,
                // 这不是"改了什么"，回滚不了（未分类是配置缺口，修法是给表加一行）
                reversible = false,
            ),
        )
    }

    /** 未分类的内存回读（日志/单测；新的在后）。 */
    fun unclassifiedSeen(): List<String> = synchronized(unclassified) { unclassified.toList() }

    /** 直测收尾（生产不调：频率事实是进程态，清了等于"刚重启"）。 */
    fun resetForTest() {
        recent.clear()
        synchronized(unclassified) { unclassified.clear() }
    }

    private fun snapshot(tool: String): Map<String, List<Long>> {
        val list = recent[tool] ?: return emptyMap()
        return synchronized(list) { mapOf(tool to list.toList()) }
    }

    private fun remember(tool: String, atMs: Long) {
        if (tool.isBlank()) return
        val list = recent.getOrPut(tool) { mutableListOf() }
        synchronized(list) {
            list.add(atMs)
            while (list.size > RECENT_CAP) list.removeAt(0)
        }
    }
}

/**
 * 原因码 → 人话（**唯一一处文案**）。
 *
 * 三条硬约束：
 * 1. **≤ [ImpactLine.MAX_CHARS] 字且单行**：它占的是 220 那行小字的位置（派工单 §1 第 4 条
 *    「不新增卡片、≤1 行」），所以走 `ImpactLine.fit` 的同一把尺子，不另立字数上限；
 * 2. **只陈述判据**：不写"危险/建议/小心"这类评价词（同 `ImpactLine` 的纪律）；
 * 3. **不含工具名/路径**：卡面标题已经在说"要动什么"，这一行只说"为什么问你"。
 */
internal object RiskTell {

    /** 主判据的一句人话（[cardHint] 的素材；null = 没有判据 ⇒ 不摆这一行）。 */
    fun hint(code: RiskCode): String? = when (code) {
        RiskCode.NONE -> null
        RiskCode.PATH_ORIGINAL_RAW -> "原件区只读"
        RiskCode.PATH_OUTSIDE_WORKSPACE -> "目标在工作区外"
        RiskCode.OVERWRITE_EXISTING -> "将覆盖已有产物"
        RiskCode.BATCH_OVER_20 -> "一次动超过 20 个目标"
        RiskCode.DELETE_OVER_10 -> "一次删超过 10 个"
        RiskCode.SUBMIT_LIKE_BUTTON -> "点击的是提交类按钮"
        RiskCode.PASSWORD_FIELD -> "目标是密码或验证码框"
        RiskCode.PRIVATE_URL -> "目标是内网地址"
        RiskCode.URL_NOT_WHITELISTED -> "域名不在白名单"
        RiskCode.FREQ_OVER_LIMIT -> "同一动作短时间太频繁"
        RiskCode.UNCLASSIFIED_TOOL -> "这个工具没有登记风险档"
        RiskCode.EMPTY_TRASH -> "清空回收站，数量未知"
        RiskCode.SENSITIVE_SEMANTICS -> "含外发或账号金额内容"
        RiskCode.BASE_R3 -> "花钱、外发或账号类动作"
        RiskCode.ALWAYS_ASK -> "会产生无人值守的动作"
    }

    /**
     * 判定的主判据那一行（≤30 字；null ⇒ 这一行整个不渲染）。
     *
     * 为什么还要按**档位**兜一句：矩阵只在"升档"时产 `reason`，底档本身是 R2 的工具
     * （`delete_path` / `move_path` / `browser_click`…）在这一次调用里可能一条判据都没命中
     * ——于是 `reasonCode` 是 `none`。可卡还是要弹的（派工单 §1：R2 ⇒ 必问），
     * 卡上就欠一句"为什么"。这句按**档位**说，不按工具名说，所以不是第二张表。
     */
    fun cardHint(verdict: RiskVerdict): String? =
        hint(verdict.reasonCode) ?: tierHint(verdict.risk)

    /** 档位本身的人话（底档即 R2/R3 时用；R0/R1 没有"为什么问你"可说）。 */
    fun tierHint(risk: RiskClass?): String? = when (risk) {
        RiskClass.R2 -> "会改工作区外或删改已有东西"
        RiskClass.R3 -> "花钱、外发或账号类动作"
        else -> null
    }

    /**
     * 卡面上**那一行**（220 的位置，一个位置放两件事）。
     *
     * 合成规则（可测，写在报告里）：都无 ⇒ 不摆；只有一件 ⇒ 就是它；
     * 两件都在 ⇒ `原因｜影响`，超 [ImpactLine.MAX_CHARS] 就**保原因**——
     * 原因是"为什么拦你"，影响是"拦了会怎样"，前者是这张卡存在的理由。
     */
    fun cardLine(reason: String?, impact: String?): String? {
        val r = reason?.takeIf { it.isNotBlank() }
        val i = impact?.takeIf { it.isNotBlank() }
        if (r == null) return i?.let { ImpactLine.fit(it) }
        if (i == null) return ImpactLine.fit(r)
        val both = "$r｜$i"
        return if (both.length <= ImpactLine.MAX_CHARS) both else ImpactLine.fit(r)
    }

    /**
     * 硬拒时给模型的原文（`Result.Rejected` 走这条：终止本轮）。
     *
     * 为什么把 `reasonCode` 露在句首：模型看到"被拒（sensitive_field）"才知道换路的地方
     * 不是"再试一次"，而是"这条路根本不通"（208 的抽检也按这个键分组）。
     */
    fun rejectText(verdict: RiskVerdict): String {
        val why = verdict.reasons.joinToString("；") { it.detail }.ifBlank { "命中硬红线" }
        return "动作被拒（${verdict.reasonCode.id}）：$why"
    }
}
