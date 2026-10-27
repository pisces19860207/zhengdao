// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 依据：派工单-2026-10-26-第二百三十九批-纠察 §9（影子执行／沙盒预演）＋ 用户 2026-10-27 下达原文。
//
// I6 是三权之外补的第三片：①免疫/熔断（已有 `loopBudget`/`hardDeny`）
// ②规格审查者（本批 I1–I5 的勘验）③**影子执行器/沙盒裁判**（这里）。
//
// 影子跑的是**同一工作区的临时副本**，三件事按 §9 逐条验：
//   1. 实际影响 = 推演所述（报 1 个改 3 个 ⇒ 不一致）；
//   2. 可回滚点真的可用（`.bak` 被人为删掉 ⇒ 影子阶段就发现）；
//   3. 验收判据能在影子里成立（`success` 的可观察条件先在影子上过一遍）。
//
// 分工写死（§10① 裁定 2026-10-27 后的口径；§4 vs §9 的冲突在这里切开）：
// - **判定归纠察**（本文件：纯函数，零 IO、零写权）；
// - **只出 finding，放不放行归执行侧与 hardDeny**：勘验永远不出阻断（裁定原文），
//   能拦路的只有 `hardDeny`（219 事先硬拒）与**人**（御批：看到 finding 自己喊停）。
//   执行侧愿意拿 [ShadowOutcome.verdict] 当它自己的放行判据，那是执行侧的闸；
//   本文件的产出是事实认定，不是闸门。高危 finding 可以"出声＋建议暂停这一批"，
//   但**不自己按停**（§10① 可加项，同样只到建议为止）。
// - **留痕归影子自己的账**（[ShadowLedger]，房型见那里的 §10② 裁定）。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.Impact
import com.example.zhengdao.baopu.RiskClass

/** 影子预演的定性（闭集；`wire` 是影子台账的稳定键）。 */
internal enum class ShadowVerdict(val wire: String, val cn: String) {

    /** 三件事全过：实际影响＝推演所称、回滚点在、判据在影子里成立。 */
    CONSISTENT("consistent", "影响与推演一致"),

    /** 三件事里至少一件不对 ⇒ 只出 finding，放不放行归执行侧与 hardDeny。 */
    MISMATCH("mismatch", "影响与推演不一致"),

    /** 影子接错了：读到的实测落在真实目标上（§9 负例「影子连真实目标」）。 */
    MISWIRED("miswired", "影子接到了真实目标"),

    /** 跑不出结论（推演自己标了"后果未知"、或影子里没读数、或判据没法观察）。 */
    UNVERIFIABLE("unverifiable", "影子跑不出结论"),
    ;

    companion object {
        fun ofWire(wire: String): ShadowVerdict? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 影子的**影响面读数**（推演所称与影子实测共用一个形状，否则"比对"就只是两套字段名对不上）。
 *
 * 只取 [Impact] 里"数得出来"的七格，不取句子：影响面一旦变成文案就没法逐项比对了。
 */
internal data class ImpactShape(
    val newFiles: Int = 0,
    val overwriteTotal: Int = 0,
    val deleteCount: Int = 0,
    val deleteBytes: Long = 0,
    val ledgerRows: Int = 0,
    val moved: Int = 0,
    val collisions: Int = 0,
    /** 影子实际碰到的路径（比对「越界」用；推演侧给空表＝不主张路径）。 */
    val touchedPaths: List<String> = emptyList(),
    /** 推演/实测这一侧是不是「未知」（[com.example.zhengdao.baopu.Impact.unknown]）。 */
    val unknown: Boolean = false,
) {
    /** 有没有可说的东西（全零且非未知 ⇒ 这个动作不产生影子里可见的影响）。 */
    val isEmpty: Boolean
        get() = !unknown && newFiles + overwriteTotal + deleteCount + moved + collisions == 0 &&
            deleteBytes == 0L && ledgerRows == 0 && touchedPaths.isEmpty()

    companion object {
        /** 由 220 的 [Impact] 造读数（**唯一**的取字段口：别处再抄一份字段清单就会漂移）。 */
        fun of(i: Impact, touchedPaths: List<String> = emptyList()): ImpactShape = ImpactShape(
            newFiles = i.newFiles,
            overwriteTotal = i.overwriteTotal,
            deleteCount = i.deleteCount,
            deleteBytes = i.deleteBytes,
            ledgerRows = i.ledgerRows,
            moved = i.moved,
            collisions = i.collisions,
            touchedPaths = touchedPaths,
            unknown = i.unknown,
        )
    }
}

/**
 * 回滚点的可用性（§9 第 2 件事）。
 *
 * 「在」不等于「能用」：字节数为 0 的 `.bak`、或大小与被保护原件对不上的 `.bak`，
 * 恢复时会把文件写成空的——那比没有回滚点更坏（用户以为能退，退完东西没了）。
 */
internal data class RollbackPoint(
    val path: String = "",
    val exists: Boolean = false,
    val bytes: Long = 0L,
    /** 被保护对象此刻的大小（影子副本里的原文件）；null = 读不到（新文件，本来就没有原件）。 */
    val originalBytes: Long? = null,
) {
    /** 这个回滚点能用吗。 */
    val usable: Boolean
        get() = exists && bytes > 0L && (originalBytes == null || bytes == originalBytes)
}

/**
 * 一条**验收判据**在影子里的观察结果（§9 第 3 件事）。
 *
 * @param observable 这条判据可不可观察（`success` 写成"完成"这类恒真词 ⇒ false；
 *   与 I3 [ContractHollowCheck] 同一个病，从两个方向各查一次）
 * @param expected 判据要求的读数（人话即可，本层不解析它）
 * @param actual 影子里实测的读数；空 = 没读到
 */
internal data class SuccessProbe(
    val name: String,
    val observable: Boolean,
    val expected: String = "",
    val actual: String = "",
) {
    /** 观察得了、且读数与要求对得上（大小写与首尾空白不计，别的都算差）。 */
    val satisfied: Boolean
        get() = observable && actual.isNotBlank() &&
            actual.trim().equals(expected.trim(), ignoreCase = true)
}

/** 一项子检查的落点（三件事各一项；`passed=null` = 这一件**验不了**，不当通过）。 */
internal data class ShadowItem(
    val key: String,
    val cn: String,
    val passed: Boolean?,
    /** 明细（只进审计栏；[ShadowOutcome.modelLine] 永远不含它）。 */
    val detail: String = "",
)

/** 一次影子预演的结论。 */
internal data class ShadowOutcome(
    val verdict: ShadowVerdict,
    val items: List<ShadowItem> = emptyList(),
    /** 推演所称（原样留着，审计栏要并排显示"报了什么/跑出什么"）。 */
    val claimed: ImpactShape = ImpactShape(),
    /** 影子实测（[ShadowVerdict.MISWIRED]/[ShadowVerdict.UNVERIFIABLE] 时可能为空形状）。 */
    val observed: ImpactShape = ImpactShape(),
) {
    /** 管线据此放行吗（只有 [ShadowVerdict.CONSISTENT] 过）。 */
    val allow: Boolean get() = verdict == ShadowVerdict.CONSISTENT

    /**
     * 给模型的那**一句**（§9：模型只收一句；负例「影子结果写进模型上下文」钉这里不含明细）。
     *
     * 括号里只允许出现**数字差**，不许出现路径、技能名、判据正文——
     * 那些是审计栏的东西，进了上下文就等于把明细塞回模型。
     */
    val modelLine: String
        get() = InspectText.line(
            when (verdict) {
                ShadowVerdict.CONSISTENT -> "影子：${ShadowVerdict.CONSISTENT.cn}"
                ShadowVerdict.MISMATCH ->
                    "影子：${ShadowVerdict.MISMATCH.cn}（${items.filter { it.passed == false }.map { it.cn }.joinToString("／")}）"
                ShadowVerdict.MISWIRED -> "影子：${ShadowVerdict.MISWIRED.cn}"
                ShadowVerdict.UNVERIFIABLE -> "影子：${ShadowVerdict.UNVERIFIABLE.cn}"
            },
        )
}

/**
 * 影子判据（纯函数）。
 *
 * 谁去建副本、谁去跑工具、谁去读实测——全在装配层（本批不接，见交付报告的挂接点）；
 * 本层只回答一句：**跑出来的这三件事对不对得上**。
 */
internal object ShadowRehearsal {

    /** 三件事的键（影子台账里逐项存；改字面量＝老影子记录读不出逐项态）。 */
    const val ITEM_EFFECT = "effect"
    const val ITEM_ROLLBACK = "rollback"
    const val ITEM_CRITERIA = "criteria"

    /**
     * 这组动作该不该跑影子（§9：只对 R2+ 与不可逆；R0/R1 跑影子是浪费）。
     *
     * @param risk 219 判的档（null = 没判过 ⇒ **要跑**：没档就按最严的走，fail-closed）
     */
    fun shouldRehearse(risk: RiskClass?, reversible: Boolean): Boolean =
        risk == null || risk == RiskClass.R2 || risk == RiskClass.R3 || !reversible

    /**
     * 比对（三件事逐项）。
     *
     * @param claimed 推演所称（220 给的那份 [Impact] 转成的形状）
     * @param observed 影子实测（同一形状）；null = 没跑出来
     * @param shadowRoot 影子副本根（**必须**与真实工作区不同；实测路径必须落在它下面）
     * @param realRoot 真实工作区根（用来识破"影子其实连着真盘"）
     * @param rollback 回滚点读数（R2+/不可逆动作必须有；null＝调用方没查 ⇒ 记「验不了」）
     * @param probes 验收判据（空表＝这个动作没有判据可验 ⇒ 「验不了」，不算通过）
     */
    fun compare(
        claimed: ImpactShape,
        observed: ImpactShape?,
        shadowRoot: String,
        realRoot: String,
        rollback: RollbackPoint?,
        probes: List<SuccessProbe>,
    ): ShadowOutcome {
        // ①先查接线：影子连着真实目标，后面三个数再漂亮也不能要。
        val touchedReal = observed != null && observed.touchedPaths.any { touchesReal(it, realRoot, shadowRoot) }
        if (shadowRoot.isBlank() || realRoot.isBlank() || normalize(shadowRoot) == normalize(realRoot) || touchedReal) {
            return ShadowOutcome(
                verdict = ShadowVerdict.MISWIRED,
                items = listOf(
                    ShadowItem(
                        key = "wiring",
                        cn = "影子没接在副本上",
                        passed = false,
                        detail = "shadow=${shortOf(shadowRoot)} real=${shortOf(realRoot)} " +
                            "实测越界=${observed?.touchedPaths?.count { touchesReal(it, realRoot, shadowRoot) } ?: 0} 条",
                    ),
                ),
                claimed = claimed,
                observed = observed ?: ImpactShape(),
            )
        }
        // ②再查能不能验：推演自己都不知道，或影子里压根没读数 ⇒ 出不了结论（不当通过）。
        if (claimed.unknown || observed == null) {
            return ShadowOutcome(
                verdict = ShadowVerdict.UNVERIFIABLE,
                items = listOf(
                    ShadowItem(
                        key = "measurable",
                        cn = "读数拿不到",
                        passed = null,
                        detail = if (claimed.unknown) "推演侧就标了后果未知" else "影子里没有实测读数",
                    ),
                ),
                claimed = claimed,
            )
        }
        val items = buildList {
            add(effectItem(claimed, observed))
            add(rollbackItem(rollback))
            add(criteriaItem(probes))
        }
        val failed = items.filter { it.passed == false }
        val unclear = items.filter { it.passed == null }
        return ShadowOutcome(
            // 「验不了」不许冒充清白：没有一项失败、但有一项验不了 ⇒ 结论跑不出。
            verdict = when {
                failed.isNotEmpty() -> ShadowVerdict.MISMATCH
                unclear.isNotEmpty() -> ShadowVerdict.UNVERIFIABLE
                else -> ShadowVerdict.CONSISTENT
            },
            items = items,
            claimed = claimed,
            observed = observed,
        )
    }

    /** 第 1 件事：实际影响 = 推演所述（逐项比数字，报出差在哪一格）。 */
    fun effectItem(claimed: ImpactShape, observed: ImpactShape): ShadowItem {
        val diffs = buildList {
            if (claimed.newFiles != observed.newFiles) add("新建 ${claimed.newFiles}≠${observed.newFiles}")
            if (claimed.overwriteTotal != observed.overwriteTotal) {
                add("覆盖 ${claimed.overwriteTotal}≠${observed.overwriteTotal}")
            }
            if (claimed.deleteCount != observed.deleteCount) add("删除 ${claimed.deleteCount}≠${observed.deleteCount}")
            if (claimed.deleteBytes != observed.deleteBytes) add("字节 ${claimed.deleteBytes}≠${observed.deleteBytes}")
            if (claimed.ledgerRows != observed.ledgerRows) add("台账 ${claimed.ledgerRows}≠${observed.ledgerRows}")
            if (claimed.moved != observed.moved) add("移动 ${claimed.moved}≠${observed.moved}")
            if (claimed.collisions != observed.collisions) add("撞名 ${claimed.collisions}≠${observed.collisions}")
        }
        // 影子里**多碰**了路径也算差：推演说改 1 个、影子摸到 3 个，就是 §9 点名的例子。
        val extraPaths = observed.touchedPaths.size - claimed.touchedPaths.size
        if (extraPaths > 0 && claimed.touchedPaths.isNotEmpty()) {
            return ShadowItem(
                key = ITEM_EFFECT,
                cn = "影响面与推演不符",
                passed = false,
                detail = (diffs + "多碰 $extraPaths 个对象").joinToString("；"),
            )
        }
        return ShadowItem(
            key = ITEM_EFFECT,
            cn = "影响面",
            passed = diffs.isEmpty(),
            detail = if (diffs.isEmpty()) "七格全等" else diffs.joinToString("；"),
        )
    }

    /** 第 2 件事：回滚点真的可用。 */
    fun rollbackItem(rollback: RollbackPoint?): ShadowItem {
        if (rollback == null) {
            return ShadowItem(key = ITEM_ROLLBACK, cn = "回滚点", passed = null, detail = "调用方没查回滚点")
        }
        return ShadowItem(
            key = ITEM_ROLLBACK,
            cn = if (rollback.usable) "回滚点可用" else "回滚点不可用",
            passed = rollback.usable,
            detail = "${shortOf(rollback.path)} 存=${rollback.exists} 字节=${rollback.bytes}" +
                " 原件=${rollback.originalBytes?.toString() ?: "无"}",
        )
    }

    /** 第 3 件事：验收判据能在影子里成立（判据本身空转 ⇒「验不了」，不算成立也不算对不上）。 */
    fun criteriaItem(probes: List<SuccessProbe>): ShadowItem {
        if (probes.isEmpty()) {
            return ShadowItem(key = ITEM_CRITERIA, cn = "验收判据", passed = null, detail = "一条判据都没有")
        }
        val hollow = probes.filter { !it.observable }.map { it.name }
        val unmet = probes.filter { it.observable && !it.satisfied }.map { "${it.name}≠${it.actual}" }
        // 三态与另外两件事同规矩：只有跑出来**对不上**才是否。恒真词「完成」是判据空转，
        // 归 I3 契约空洞那一格，不许在这儿冒充第二次不一致（同一病灶两处记账）。
        val state: Boolean? = when {
            unmet.isNotEmpty() -> false
            hollow.isNotEmpty() -> null
            else -> true
        }
        return ShadowItem(
            key = ITEM_CRITERIA,
            cn = when (state) {
                true -> "判据在影子里成立"
                false -> "判据在影子里不成立"
                else -> "判据不可观察"
            },
            passed = state,
            detail = when {
                state == true -> "${probes.size} 条全过"
                else -> buildList {
                    if (hollow.isNotEmpty()) add("不可观察：${hollow.joinToString("、")}" + "（I3 同病）")
                    if (unmet.isNotEmpty()) add("没满足：${unmet.joinToString("、")}")
                }.joinToString("；")
            },
        )
    }

    /** 路径归一（Windows 反斜杠＋大小写不敏感＋去尾分隔符；比较前一律走它）。 */
    private fun normalize(p: String): String =
        p.replace('\\', '/').trimEnd('/', ' ').lowercase()

    /**
     * 实测路径是不是落在**真实**工作区里。
     *
     * §9 写的影房型是「同一工作区的临时副本」⇒ 副本根通常就挂在真根底下，
     * 按前缀一刀切会把每一个正常的影子落点都判成接错（永远红＝永远没人信）。
     * 所以先扣掉副本根下的那些，剩下的仍以真根起头 ⇒ 才是真越界。
     */
    private fun touchesReal(path: String, realRoot: String, shadowRoot: String): Boolean {
        val n = normalize(path)
        val r = normalize(realRoot)
        val s = normalize(shadowRoot)
        if (r.isEmpty()) return false
        if (s.isNotEmpty() && (n == s || n.startsWith("$s/"))) return false
        return n == r || n.startsWith("$r/")
    }

    /** 明细里的路径只留末两段（审计栏看得出名，又不把整条家目录搬进账）。 */
    private fun shortOf(path: String): String =
        if (path.isBlank()) "—" else path.replace('\\', '/').split('/').takeLast(2).joinToString("/")
}
