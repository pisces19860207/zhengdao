// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十六批 §2 执行器：回放，可自愈但不乱闯。
//
// 顺序铁律（每一步都一样）：域名白名单 → 定位 → 置信度门控 → 安全闸（拒点/拒填/
// 标签白名单）→ 动手 → expect 校验 → 取证。任何一环不过 ⇒ 停，宁可停错、不可点错。
//
// 技能文件全程只读：自愈结果只进报告（标「定位漂移：第 N 步 旧→新」），**不存在**
// 从执行器回写技能的代码路径（「技能不自动改」靠结构保证，不靠自觉）。
package com.example.zhengdao.baopu

import java.io.File

// ── 报告形状 ──────────────────────────────────────────────────────────────────

/** 一处定位漂移（自愈成功的证据，人看后手工改技能——技能文件不动）。 */
data class TeachDrift(val step: Int, val oldLocator: String, val newLocator: String)

/** 停下问人时的候选信息（给用户看，绝不替用户选）。 */
data class TeachAsk(
    val step: Int,
    val reason: String,
    val candidates: List<String>,
    val scanned: List<String>,
)

/** 一次回放的完整报告（审计行的数据源；ok=false 时 stopReason 必非空）。 */
data class TeachRunReport(
    val skillName: String,
    val ok: Boolean,
    val stepsDone: Int,
    val failedStep: Int?,
    val stopReason: String?,
    val durationMs: Long,
    val drifts: List<TeachDrift>,
    val refusals: List<String>,
    val ask: TeachAsk?,
    val evidenceDir: String?,
    /** 动手失败后滚入视口重试成功的步号（第二百二十八批 C 片；空 = 没发生过重试）。 */
    val retried: List<Int> = emptyList(),
    /**
     * 622 A④：下一步建议（失败时非空；「卡在第 N 步＋快照＋原因＋建议」的第四件）。
     * 判据见 [teachSuggestionOf]——纯函数映射，不猜页面，只给「人可以照做」的一句。
     */
    val suggestion: String? = null,
) {
    /** 审计一行（§3.5：技能名/步数/结果/耗时/漂移/被拒动作；622 加建议）。 */
    fun auditLine(nowMs: Long, opId: String? = null): String {
        val sb = StringBuilder("{")
        sb.append("\"ts\":").append(nowMs)
            .append(",\"skill\":\"").append(jsonEsc(skillName)).append("\"")
            .append(",\"steps\":").append(stepsDone)
            .append(",\"ok\":").append(ok)
        opId?.let { sb.append(",\"op\":\"").append(it).append('"') }
        failedStep?.let { sb.append(",\"failedStep\":").append(it) }
        stopReason?.let { sb.append(",\"reason\":\"").append(jsonEsc(it)).append("\"") }
        suggestion?.let { sb.append(",\"suggestion\":\"").append(jsonEsc(it)).append("\"") }
        sb.append(",\"durationMs\":").append(durationMs)
        if (drifts.isNotEmpty()) {
            sb.append(",\"drifts\":[")
            sb.append(drifts.joinToString(",") {
                "{\"step\":${it.step},\"old\":\"${jsonEsc(it.oldLocator)}\",\"new\":\"${jsonEsc(it.newLocator)}\"}"
            })
            sb.append("]")
        }
        if (refusals.isNotEmpty()) {
            sb.append(",\"refusals\":[")
            sb.append(refusals.joinToString(",") { "\"${jsonEsc(it)}\"" })
            sb.append("]")
        }
        if (retried.isNotEmpty()) {
            sb.append(",\"retries\":[").append(retried.joinToString(",")).append("]")
        }
        sb.append("}")
        return sb.toString()
    }

    private fun jsonEsc(s: String): String = s
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t")
}

// ── 取证口 ────────────────────────────────────────────────────────────────────

/** 每步截图＋DOM 摘要＋时间戳的落盘口（生产落 `<工作区>/产物/玄览技能/<技能名>/<时间>/`）。 */
interface TeachEvidenceSink {
    /** 开一次运行的证据目录，返回目录路径（测试/失败可返回 null＝不落盘）。 */
    fun begin(skillName: String, stamp: String): String?

    /** 第 [index] 步的证据：DOM 摘要＋截图引用（截图失败给 null，不阻塞回放）。 */
    fun step(index: Int, action: TeachAction, summary: String, screenshotRef: String?)

    /**
     * 622 A②：终态取证（**每次回放都采**，成功与失败都到——「最终快照」首先是证据）。
     * 缺省空实现：装饰器与测试桩不必都知道这一件。
     */
    fun final(summary: String, screenshotRef: String?) {
    }

    /** 收尾：整份报告 JSON。 */
    fun finish(report: TeachRunReport)
}

/**
 * 文件实现：真实目录、真实文件（JVM 单测直接用临时目录断言存在）。
 * [shot] 生产端接执行端口的截图（返回 hostPath）；测试不给（null 即不落截图，
 * DOM 摘要照落——截图是证据的增强项，不是回放的依赖项）。
 */
internal class FileTeachEvidenceSink(
    private val root: File,
    private val shot: ((step: Int) -> String?)? = null,
) : TeachEvidenceSink {
    private var dir: File? = null

    override fun begin(skillName: String, stamp: String): String? {
        val d = File(File(root, "玄览技能"), "${File(safeDirName(skillName)).name}-$stamp")
        return runCatching {
            d.mkdirs()
            dir = d
            d.absolutePath
        }.getOrElse { null }
    }

    override fun step(index: Int, action: TeachAction, summary: String, screenshotRef: String?) {
        val d = dir ?: return
        runCatching {
            File(d, "step-$index-${action.wire}.dom.txt").writeText(summary, Charsets.UTF_8)
            val ref = screenshotRef ?: shot?.invoke(index)
            ref?.let { File(d, "step-$index-${action.wire}.shot.ref.txt").writeText(it, Charsets.UTF_8) }
        }
    }

    override fun final(summary: String, screenshotRef: String?) {
        val d = dir ?: return
        runCatching {
            File(d, "final.dom.txt").writeText(summary, Charsets.UTF_8)
            val ref = screenshotRef ?: shot?.invoke(0)
            ref?.let { File(d, "final.shot.ref.txt").writeText(it, Charsets.UTF_8) }
        }
    }

    override fun finish(report: TeachRunReport) {
        val d = dir ?: return
        runCatching { File(d, "run.json").writeText(report.toString(), Charsets.UTF_8) }
    }

    private fun safeDirName(raw: String): String =
        raw.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifBlank { "未命名" }
}

// ── 执行器 ────────────────────────────────────────────────────────────────────

/**
 * 回放一个技能。**同步阻塞**（生产端 BrowserCommands.execJs 本就是同步桥，调用方负责
 * 放后台线程/协程；JVM 单测里假 DOM 瞬回）。
 */
internal class TeachExecutor(
    private val dom: TeachDom,
    private val evidence: TeachEvidenceSink,
    private val audit: (TeachRunReport, Long) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {

    companion object {
        /** expect 轮询上限（毫秒）：页面异步渲染的容错，不是无限等。 */
        const val EXPECT_TIMEOUT_MS = 3_000L

        /** 轮询间隔。 */
        const val EXPECT_POLL_MS = 150L
    }

    fun run(skill: TeachSkill): TeachRunReport {
        val started = clock()
        val drifts = mutableListOf<TeachDrift>()
        val refusals = mutableListOf<String>()
        val retried = mutableListOf<Int>()
        val evidenceDir = evidence.begin(skill.name, stamp(clock()))
        var stepsDone = 0

        fun stop(failedStep: Int?, reason: String, ask: TeachAsk? = null): TeachRunReport {
            // 622 A④：失败处**当场取证**（页面 DOM 摘要＋截图引用）——「卡在第 N 步」
            // 必须连「当时的页面长什么样」一起留下，不许只留一句原因。
            runCatching {
                evidence.step(
                    failedStep ?: 0,
                    TeachAction.WAIT,
                    "失败取证｜$reason\n${domSummary(dom, urlNow())}",
                    null,
                )
            }
            val report = TeachRunReport(
                skillName = skill.name,
                ok = false,
                stepsDone = stepsDone,
                failedStep = failedStep,
                stopReason = reason,
                durationMs = clock() - started,
                drifts = drifts,
                refusals = refusals,
                ask = ask,
                evidenceDir = evidenceDir,
                retried = retried,
                suggestion = teachSuggestionOf(reason, failedStep),
            )
            evidence.finish(report)
            audit(report, clock())
            return report
        }

        // ── ① 起点域白名单 ──
        val startUrl = (dom.ask(TeachQuery.CurrentUrl) as? TeachReply.Url)?.url
            ?: return stop(null, "取不到当前页面（标签可能还在加载）")
        if (!TeachSafety.domainAllowed(startUrl, skill.allowedDomains)) {
            return stop(null, "当前页不在授权域（${TeachSafety.hostOf(startUrl) ?: "?"}），回放只在授权域内执行")
        }

        // ── ② 结构指纹：关键表头/关键按钮的 name 集合对不上 ⇒ 停手（负例 2）──
        val anchors = dom.ask(TeachQuery.HeadersAndButtons) as? TeachReply.Anchors
            ?: return stop(null, "取不到页面结构指纹（页面可能还在加载）")
        val live = (anchors.headers + anchors.buttons).map { it.trim() }.toSet()
        val missing = skill.fingerprint.anchors.filter { it.name.trim() !in live }
        if (missing.isNotEmpty()) {
            return stop(
                null,
                "页面结构变了，请重新教一遍（关键锚点缺失：${missing.joinToString("、") { it.name }}）",
            )
        }

        // ── ③ 步骤循环 ──
        var beforeUrl = startUrl
        var beforeRows = rowCount()
        for (step in skill.steps) {
            // 域白名单：每步执行前再查一次（上一步可能已把页面带出去）
            val stepUrl = (dom.ask(TeachQuery.CurrentUrl) as? TeachReply.Url)?.url ?: beforeUrl
            if (!TeachSafety.domainAllowed(stepUrl, skill.allowedDomains)) {
                return stop(
                    step.index,
                    "页面已跳出授权域（${TeachSafety.hostOf(stepUrl) ?: "?"}），停下",
                )
            }

            // WAIT 步没有目标元素：只等 expect（翻页等待的落点），不定位、不点击
            if (step.action == TeachAction.WAIT) {
                val waitUrl = (dom.ask(TeachQuery.CurrentUrl) as? TeachReply.Url)?.url ?: beforeUrl
                val rowsAfter = verifyExpects(step, beforeUrl, beforeRows, dom)
                if (rowsAfter == null) {
                    return stop(
                        step.index,
                        "第 ${step.index} 步等待超时（${step.expect.joinToString("、") { it.kind.wire }}）——已停并取证",
                    )
                }
                evidence.step(step.index, step.action, domSummary(dom, waitUrl), null)
                stepsDone += 1
                beforeUrl = waitUrl
                beforeRows = rowCount()
                continue
            }

            val verdict = TeachResolve.gate(TeachResolve.resolve(step.locators, dom))

            val locator: TeachLocator
            val hit: TeachHit
            when (verdict) {
                is TeachResolve.Verdict.Heal -> {
                    // 0 命中 ⇒ 自愈：只重抽这一步，一次机会，零 LLM
                    when (val healed = TeachResolve.selfHeal(step.snapshot, dom)) {
                        is TeachResolve.HealOutcome.Healed -> {
                            drifts += TeachDrift(
                                step = step.index,
                                oldLocator = step.locators.minOfOrNull { it.tier }
                                    ?.let { t -> step.locators.first { it.tier == t }.describe() } ?: "（无）",
                                newLocator = healed.locator.describe(),
                            )
                            locator = healed.locator
                            hit = healed.hit
                        }
                        is TeachResolve.HealOutcome.Ambiguous -> {
                            return stop(
                                step.index,
                                "第 ${step.index} 步自愈后仍有 ${healed.candidates.size} 个候选，请人指定",
                                ask = TeachAsk(
                                    step = step.index,
                                    reason = "定位全空且语义快照命中多个元素",
                                    candidates = healed.candidates.map { it.tag + "／" + (it.name ?: it.text ?: "?") },
                                    scanned = step.locators.map { it.describe() },
                                ),
                            )
                        }
                        is TeachResolve.HealOutcome.NotFound -> {
                            return stop(
                                step.index,
                                "第 ${step.index} 步定位失败且自愈不成：${healed.reason}",
                                ask = TeachAsk(
                                    step = step.index,
                                    reason = healed.reason,
                                    candidates = emptyList(),
                                    scanned = step.locators.map { it.describe() },
                                ),
                            )
                        }
                    }
                }
                is TeachResolve.Verdict.AskCandidates -> {
                    // 命中 ≥2 或只命中低档 ⇒ 停下问人（负例 5），绝不选第一个
                    return stop(
                        step.index,
                        "第 ${step.index} 步：${verdict.reason}",
                        ask = TeachAsk(
                            step = step.index,
                            reason = verdict.reason,
                            candidates = verdict.candidates.map { it.tag + "／" + (it.name ?: it.text ?: "?") },
                            scanned = verdict.scanned,
                        ),
                    )
                }
                is TeachResolve.Verdict.Execute -> {
                    locator = verdict.locator
                    hit = verdict.hit
                }
            }

            // ── 安全闸（比门控更硬：代码层面拒点/拒填，负例 3/4 的靶子）──
            if (step.action == TeachAction.CLICK) {
                TeachSafety.refuseClickReason(hit.clickSurface())?.let { reason ->
                    refusals += "第 ${step.index} 步 ${reason}"
                    return stop(step.index, "第 ${step.index} 步被安全闸拦下：$reason")
                }
                if (skill.clickTagWhitelist.isNotEmpty() &&
                    hit.tag.uppercase() !in skill.clickTagWhitelist.map { it.uppercase() }
                ) {
                    val reason = "点击目标 <${hit.tag}> 不在示教时点过的元素类别里（" +
                        "${skill.clickTagWhitelist.joinToString("/")}）——只点示教时点过的那类元素"
                    refusals += "第 ${step.index} 步 $reason"
                    return stop(step.index, "第 ${step.index} 步被安全闸拦下：$reason")
                }
            }
            if (step.action == TeachAction.TYPE || step.action == TeachAction.SELECT) {
                hit.typeSurface()?.let { reason ->
                    refusals += "第 ${step.index} 步 $reason"
                    return stop(step.index, "第 ${step.index} 步被安全闸拦下：$reason")
                }
                if (step.value.isNullOrBlank()) {
                    return stop(step.index, "第 ${step.index} 步要输入但没有值，技能不完整")
                }
            }

            // ── 动手（第二百二十八批 C 片：失败自愈）──
            // act 失败＝什么都没发生过 ⇒ 重试安全；expect 不满足不在此列（动作已执行过，
            // 重试等于双击双提交）。重试的判据是**起点页指纹复核**：锚点不全在 ⇒ 页面可能
            // 已离开起点，重试不可判 ⇒ fail-closed 直接停。最多一次：滚入视口后重试同一
            // 动作，再败即停，**不存在循环重试**。
            // 622：CLICK 步把语义面带给端口（端口三档闸与上面的安全闸同源同词表——
            // 这里的安全闸拦「技能里的危险步」，端口拦「任何路径的未遂点击」）。
            val subjectFaces = if (step.action == TeachAction.CLICK) {
                listOfNotNull(hit.text, hit.name, hit.ariaLabel, hit.testid)
            } else {
                emptyList()
            }
            var acted = dom.ask(TeachQuery.Act(locator, step.action, step.value, subjectFaces))
            var actOk = acted as? TeachReply.Acted
            if (actOk == null || !actOk.ok) {
                val failReason = (acted as? TeachReply.Failed)?.reason
                    ?: actOk?.reason ?: "页面无响应"
                // 622：执行端口拦下的动作**不重试**——闸拦的不是「页面没反应」，重试等于
                // 再撞一次闸；原因原样停下（带取证），让「为什么被拦」看得见。
                if (failReason.contains("执行端口拦下")) {
                    return stop(step.index, "第 ${step.index} 步执行失败：$failReason")
                }
                if (!fingerprintLive(skill, dom)) {
                    return stop(
                        step.index,
                        "第 ${step.index} 步执行失败：$failReason（重试前指纹复核不通过，页面可能已离开起点，不敢重试）",
                    )
                }
                val scrolled = dom.ask(TeachQuery.Act(locator, TeachAction.SCROLL, null))
                if (scrolled !is TeachReply.Acted || !scrolled.ok) {
                    return stop(step.index, "第 ${step.index} 步执行失败：$failReason（滚入视口也不成）")
                }
                acted = dom.ask(TeachQuery.Act(locator, step.action, step.value, subjectFaces))
                actOk = acted as? TeachReply.Acted
                if (actOk == null || !actOk.ok) {
                    val retryReason = (acted as? TeachReply.Failed)?.reason
                        ?: actOk?.reason ?: "未知"
                    return stop(
                        step.index,
                        "第 ${step.index} 步执行失败：$retryReason（滚入视口重试一次后仍失败）",
                    )
                }
                retried += step.index
            }

            // ── expect 校验（不满足 ⇒ 停并取证——取证在 stop() 里统一做）──
            val urlAfter = actOk.urlAfter
            val rowsAfter = verifyExpects(step, beforeUrl, beforeRows, dom)
            if (rowsAfter == null) {
                return stop(
                    step.index,
                    "第 ${step.index} 步期望断言不满足（${step.expect.joinToString("、") { it.kind.wire }}）——已停并取证",
                )
            }

            // ── 域白名单：动作把页面带出授权域 ⇒ 停（负例 6）──
            if (!TeachSafety.domainAllowed(urlAfter, skill.allowedDomains)) {
                return stop(
                    step.index,
                    "第 ${step.index} 步后页面跳出授权域（${TeachSafety.hostOf(urlAfter) ?: "?"}），停下",
                )
            }

            // ── 取证：每步 DOM 摘要（截图由生产端 sink 补）──
            evidence.step(step.index, step.action, domSummary(dom, urlAfter), null)
            stepsDone += 1
            beforeUrl = urlAfter
            // 行数基线每步重读：URL 变化的步之后页面换了，旧行数不能带到下一步
            beforeRows = rowCount()
        }

        // ── ④ 最终快照（622 v2：终态校验点非空 ⇒ 收尾前逐条验；无论校不校，终态取证必采）──
        val tailUrl = urlNow()
        skill.finalSnapshot?.expects?.takeIf { it.isNotEmpty() }?.let { finals ->
            val finalStep = TeachStep(
                index = stepsDone + 1,
                action = TeachAction.WAIT,
                url = tailUrl,
                locators = emptyList(), // WAIT 步允许空定位链
                value = null,
                snapshot = TeachElementSnapshot("FINAL", null, null, null, null),
                expect = finals,
            )
            if (verifyExpects(finalStep, beforeUrl, beforeRows, dom) == null) {
                return stop(
                    stepsDone + 1,
                    "最终快照校验不满足（${finals.joinToString("、") { it.kind.wire }}）——" +
                        "步骤都跑完了，但收尾页面不在示教时的终态",
                )
            }
        }
        evidence.final(domSummary(dom, tailUrl), null)

        val report = TeachRunReport(
            skillName = skill.name,
            ok = true,
            stepsDone = stepsDone,
            failedStep = null,
            stopReason = null,
            durationMs = clock() - started,
            drifts = drifts,
            refusals = refusals,
            ask = null,
            evidenceDir = evidenceDir,
            retried = retried,
        )
        evidence.finish(report)
        audit(report, clock())
        return report
    }

    /** 逐条校验 expect（带轮询）；全部满足 ⇒ 返回校验后的行数，任一不满足 ⇒ null。 */
    private fun verifyExpects(
        step: TeachStep,
        beforeUrl: String,
        beforeRows: Int,
        dom: TeachDom,
    ): Int? {
        val deadline = clock() + EXPECT_TIMEOUT_MS
        var rows = beforeRows
        while (true) {
            var allOk = true
            for (e in step.expect) {
                val ok = when (e.kind) {
                    TeachExpectKind.URL_CHANGED -> stripFragment(
                        (dom.ask(TeachQuery.CurrentUrl) as? TeachReply.Url)?.url ?: "",
                    ) != stripFragment(beforeUrl)
                    TeachExpectKind.TEXT_APPEARS -> e.value?.let {
                        (dom.ask(TeachQuery.TextPresent(it)) as? TeachReply.Flag)?.present == true
                    } ?: false
                    TeachExpectKind.ROW_COUNT_CHANGE -> {
                        rows = rowCount()
                        rows != beforeRows
                    }
                    TeachExpectKind.ELEMENT_GONE -> e.value?.let {
                        (dom.ask(TeachQuery.TextPresent(it)) as? TeachReply.Flag)?.present == false
                    } ?: false
                }
                if (!ok) {
                    allOk = false
                    break
                }
            }
            if (allOk) return rows
            if (clock() >= deadline) return null
            sleeper(EXPECT_POLL_MS)
        }
    }

    private fun rowCount(): Int = (dom.ask(TeachQuery.RowCount) as? TeachReply.Count)?.n ?: 0

    /**
     * 起点页指纹锚点是否仍全在（自愈重试的判据）。拿不到结构 ⇒ 视为未通过：
     * 重试的前提「页面没换」证明不了就不重试（fail-closed）。
     */
    private fun fingerprintLive(skill: TeachSkill, dom: TeachDom): Boolean {
        val anchors = dom.ask(TeachQuery.HeadersAndButtons) as? TeachReply.Anchors ?: return false
        val live = (anchors.headers + anchors.buttons).map { it.trim() }.toSet()
        return skill.fingerprint.anchors.all { it.name.trim() in live }
    }

    private fun domSummary(dom: TeachDom, url: String): String =
        "url=$url\n" + ((dom.ask(TeachQuery.DomSummary) as? TeachReply.Summary)?.text ?: "")

    /** 当前页地址（取不到 ⇒ 空串——取证行照落，只是没有 url）。 */
    private fun urlNow(): String =
        (dom.ask(TeachQuery.CurrentUrl) as? TeachReply.Url)?.url ?: ""

    private fun stripFragment(url: String): String = url.substringBefore('#')
}

/** 证据目录用的时间戳（yyyyMMdd-HHmmss）。 */
internal fun stamp(nowMs: Long): String {
    val c = java.util.Calendar.getInstance()
    c.timeInMillis = nowMs
    return "%04d%02d%02d-%02d%02d%02d".format(
        c.get(java.util.Calendar.YEAR),
        c.get(java.util.Calendar.MONTH) + 1,
        c.get(java.util.Calendar.DAY_OF_MONTH),
        c.get(java.util.Calendar.HOUR_OF_DAY),
        c.get(java.util.Calendar.MINUTE),
        c.get(java.util.Calendar.SECOND),
    )
}

/**
 * 622 A④：失败原因 ⇒ **下一步建议**（「卡在第 N 步＋快照＋原因＋建议」的第四件）。
 *
 * 纯函数映射：只认执行器/端口已知的失败类别，每类给一句「人可以照做」的话；
 * 认不出的类别给通用兜底（查取证快照、重教或改参数）——不猜页面、不编计划
 * （工单口径：失败时不许自行改计划乱点，建议是给人看的路标，不是新计划）。
 */
internal fun teachSuggestionOf(reason: String, failedStep: Int?): String {
    val at = failedStep?.let { "第 $it 步" } ?: "起点检查"
    // 表驱动（detekt 复杂度口径：一串 contains-when 会超限；映射表一条一行，顺序即优先级）
    val rules: List<Pair<List<String>, String>> = listOf(
        listOf("执行端口拦下") to
            "该动作被执行端口判为外部不可逆（提交/支付/删除类）——这类动作必须回抱朴确认后由人执行；重教时跳过它",
        listOf("急停") to
            "先请用户在安全台解除停机（解除只能人工），再重新回放",
        listOf("不在授权域", "跳出授权域") to
            "把目标站点加进信任名单（teach_site_trust add），或先打开技能的起点页再回放",
        listOf("页面结构变了", "指纹复核不通过") to
            "页面已改版：重新教一遍生成新技能（teach_record_start），或在漂移闸里选「按新版重新校准」",
        listOf("期望断言不满足", "等待超时", "最终快照校验") to
            "校验点可能过时：对照取证快照里的页面，改技能里 $at 的校验点（expect），或重新教这一步",
        listOf("定位失败", "自愈", "候选", "定位全空") to
            "看取证快照：元素不在了就重新教；被遮挡就先滚动或等加载完成",
        listOf("被安全闸拦下", "拒点", "拒填") to
            "提交/支付/删除类按钮与密码框一律人工执行——技能里不该有这一步",
        listOf("没有值", "不完整") to
            "把缺的输入值补上（参数化回放把参数说全，或重教时填好值）",
    )
    for ((keys, advice) in rules) {
        if (keys.any { reason.contains(it) }) return advice
    }
    return "查 $at 的取证快照对照页面现状：重教、改参数或改校验点——不要让回放自行换路子乱点"
}
