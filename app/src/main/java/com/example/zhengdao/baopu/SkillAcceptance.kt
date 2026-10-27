// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百五十五批 · 技能两段验收的**纯规则层**（只判不写；落盘在 `TeachReplayStore`，
// 事件只进既有两本账：`技能/_变更.jsonl` 与 208 `决策记录.jsonl`——本批**不新建第二本账**）。
//
// 第一段 入库闸（入口逐条过）：
//   G1 两站点跑通（≥2 个站点各成功 1 次）⇒ 正式；只有 1 个站点 ⇒ 标草稿并提示；
//   G2 success 必须可观察（示教技能＝每步都有 expect；文字技能＝契约 success 段）⇒ 不满足拒收；
//   G3 protected 未违反（正文/步骤/演练痕迹碰到受保护清单）⇒ 拒收并说明碰了什么。
//
// 第二段 用中校验（每次**执行实例**一条样本；窗口＝滚动 30 天）：
//   U1 连续 2 次失败 ⇒ 降为草稿（不再自动选中），208 记一条 `skill_demoted` 带两次 opId；
//   U2 定位漂移只记不改，近 30 天累计 ≥3 次 ⇒ 提示「该修选择器」；
//   U3 环境指纹变 ⇒ fail-closed 判 `SKILL_STALE`，后续回放一律停手（重教才清）；
//   U4 成功率 <60% 且样本 ≥10 ⇒ 标「待退役」（仍可手动用，不再自动推荐）；
//   U5 累计修正 ≥5 次 ⇒ 标「该重学」；
//   U6 样本 <10 ⇒ 记 unknown，不下结论、不按成功率降级。
//
// 幂等：每条样本带 `opId`（一次执行一个），同 opId 重复记账**一个数都不放大**（负例⑥）。
// 本文件零 IO、零时钟、零 Android（与 233/205 的纯层同一纪律）。
package com.example.zhengdao.baopu

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** 一次**执行实例**的验收读数（opId 幂等去重；site＝起点域名，G1 的站点数口径）。 */
internal data class AcceptanceSample(
    val opId: String,
    val atMs: Long,
    val ok: Boolean,
    val fingerprintOk: Boolean,
    val driftCount: Int,
    val site: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("op", opId)
        .put("ts", atMs)
        .put("ok", ok)
        .put("fp", fingerprintOk)
        .put("drifts", driftCount)
        .put("site", site)

    companion object {
        fun fromJson(o: JSONObject): AcceptanceSample? {
            val op = o.optString("op")
            if (op.isBlank()) return null
            val ts = o.optLong("ts", 0L)
            if (ts <= 0L) return null
            return AcceptanceSample(
                opId = op,
                atMs = ts,
                ok = o.optBoolean("ok", false),
                fingerprintOk = o.optBoolean("fp", true),
                driftCount = o.optInt("drifts", 0).coerceAtLeast(0),
                site = o.optString("site"),
            )
        }
    }
}

/**
 * 一条技能的验收状态（`技能/<名>/验收.json`，与 `回放.json` 同目录旁挂）。
 *
 * 只存**事件推不出来的东西**：样本、累计修正数、已降级/已判 stale 的落章与依据 opId。
 * 「待退役」「该重学」由样本/计数现算（可以恢复），不落章——免得技能变好了还挂着旧标。
 */
internal data class AcceptanceState(
    val samples: List<AcceptanceSample> = emptyList(),
    val correctionsTotal: Int = 0,
    val demotedAtMs: Long? = null,
    val demotionOpIds: List<String> = emptyList(),
    val staleAtMs: Long? = null,
    val staleOpId: String? = null,
) {
    /** 规范序列化（固定键序，与 TeachSkill.toJsonText 同款；空字段不写，字节稳定）。 */
    fun toJsonText(): String = buildString {
        append("{\"schema\":\"").append(SCHEMA).append('"')
        append(",\"samples\":[").append(
            samples.joinToString(",") { it.toJson().toString() },
        ).append(']')
        append(",\"corrections\":").append(correctionsTotal)
        demotedAtMs?.let {
            append(",\"demotedAt\":").append(it)
            append(",\"demotionOps\":[").append(demotionOpIds.joinToString(",") { "\"$it\"" }).append(']')
        }
        staleAtMs?.let {
            append(",\"staleAt\":").append(it)
            append(",\"staleOp\":\"").append(staleOpId.orEmpty()).append('"')
        }
        append('}')
    }

    companion object {
        const val SCHEMA = "zl-accept/1"

        /** 读回（schema 不对/坏行跳过；整份读不出 ⇒ null 由调用方当空状态，不静默改写盘上文件）。 */
        fun fromJson(text: String): AcceptanceState? = runCatching {
            val o = JSONObject(text)
            if (o.optString("schema") != SCHEMA) return@runCatching null
            val samples = mutableListOf<AcceptanceSample>()
            val arr = o.optJSONArray("samples") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                AcceptanceSample.fromJson(s)?.let { samples += it }
            }
            val ops = mutableListOf<String>()
            val oa = o.optJSONArray("demotionOps") ?: JSONArray()
            for (i in 0 until oa.length()) oa.optString(i).takeIf { it.isNotBlank() }?.let { ops += it }
            AcceptanceState(
                samples = samples,
                correctionsTotal = o.optInt("corrections", 0).coerceAtLeast(0),
                demotedAtMs = o.optLong("demotedAt", 0L).takeIf { it > 0L },
                demotionOpIds = ops,
                staleAtMs = o.optLong("staleAt", 0L).takeIf { it > 0L },
                staleOpId = o.optString("staleOp").takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }
}

/** 一次记账的结论（纯函数产物；执行壳照单写盘/留痕）。 */
internal data class AcceptanceUpdate(
    val state: AcceptanceState,
    val opId: String,
    val duplicate: Boolean,
    val demotedNow: Boolean,
    val demotionOpIds: List<String> = emptyList(),
    val staleNow: Boolean = false,
    val notes: List<String> = emptyList(),
)

/** 技能页/回放前给界面看的标（可多个；顺序稳定）。 */
internal enum class AcceptanceFlag(val label: String) {
    DEMOTED("已降为草稿"),
    STALE("环境变了"),
    RETIRING("待退役"),
    RELEARN("该重学"),
}

/** 一次体检结论（分子分母都给；unknown 时明说样本不够）。 */
internal data class AcceptanceVerdict(
    val status: String,
    val windowTotal: Int,
    val windowOk: Int,
    val rateKnown: Boolean,
    val flags: List<AcceptanceFlag>,
    val driftTip: String?,
    val staleBlock: String?,
    val sampleLine: String,
) {
    /** 自动选中：正式＋没降级/没过期/没待退役（草稿永远靠手动点名）。 */
    val autoSelectable: Boolean
        get() = status == "active" && flags.none {
            it == AcceptanceFlag.DEMOTED || it == AcceptanceFlag.STALE || it == AcceptanceFlag.RETIRING
        }

    /** 手动可用：stale 之外都行（降级/待退役只影响「自动」）。 */
    val canRunManually: Boolean get() = staleBlock == null
}

/** 回放前的一道闸（[blocked] 非空 ⇒ 一个页面动作都不做）。 */
internal data class Preflight(val blocked: String?, val notes: List<String>) {
    val isBlocked: Boolean get() = blocked != null
}

/** 入库前的验证痕迹（演练/示教在哪些站点成功过、碰到过什么）。 */
internal data class Rehearsal(
    val sites: List<String> = emptyList(),
    val touches: List<String> = emptyList(),
)

/** 入库闸的三态结论（Pass／只能落草稿／拒收）。 */
internal sealed interface EntryVerdict {
    data class Pass(val singleSite: Boolean, val notes: List<String>) : EntryVerdict
    data class DraftOnly(val reason: String, val notes: List<String> = emptyList()) : EntryVerdict
    data class Reject(val reason: String) : EntryVerdict
}

/** 过闸的对象：示教回放技能（机器断言）或文字技能（233 契约）。 */
internal sealed interface EntrySubject {
    /**
     * @param contractBody 盘上既有 SKILL.md 的正文（重存时用来读 protected；没有 ⇒ null）
     */
    data class Replay(val skill: TeachSkill, val contractBody: String? = null) : EntrySubject
    data class Prose(val body: String) : EntrySubject
}

internal object SkillAcceptance {

    /** 窗口＝滚动 30 天（派工单口径；与勘验的窗口一致）。 */
    const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000

    /** U6：样本 <10 ⇒ unknown。 */
    const val MIN_SAMPLES = 10

    /** U4：成功率下限 60%（用百分数整数比较，避免浮点误差）。 */
    const val SUCCESS_PERCENT = 60

    /** U1：连续 2 败 ⇒ 降级。 */
    const val DEMOTE_FAILURES = 2

    /** U2：漂移累计 3 次 ⇒ 提示修选择器。 */
    const val DRIFT_TIP = 3

    /** U5：累计修正 5 次 ⇒ 该重学。 */
    const val RELEARN_CORRECTIONS = 5

    /** 一次执行的机器键（同一份报告＋同一时刻 ⇒ 同一 opId；不同执行 ⇒ 不同 opId）。 */
    fun opIdOf(report: TeachRunReport, nowMs: Long): String {
        val seed = listOf(
            report.skillName,
            nowMs.toString(),
            report.ok.toString(),
            report.stepsDone.toString(),
            report.failedStep?.toString() ?: "-",
            report.durationMs.toString(),
            report.drifts.size.toString(),
            report.evidenceDir ?: "-",
        ).joinToString("|")
        val d = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        return "op-" + d.take(8).joinToString("") { "%02x".format(it) }
    }

    /** 窗口内样本（时间倒走/未来时间的样本不算——判不了就不猜）。 */
    fun windowOf(state: AcceptanceState, nowMs: Long): List<AcceptanceSample> =
        state.samples.filter { it.atMs in (nowMs - WINDOW_MS)..nowMs }

    /** 记一次执行（纯函数、opId 幂等）。 */
    fun applyRun(state: AcceptanceState, sample: AcceptanceSample, nowMs: Long): AcceptanceUpdate {
        if (state.samples.any { it.opId == sample.opId }) {
            return AcceptanceUpdate(
                state = state,
                opId = sample.opId,
                duplicate = true,
                demotedNow = false,
                notes = listOf("同一个 opId 已经记过账（${sample.opId}）——不重复计数"),
            )
        }
        val kept = state.samples.filter { it.atMs in (nowMs - WINDOW_MS)..nowMs }
        var s = state.copy(samples = kept + sample)
        val notes = mutableListOf<String>()

        var staleNow = false
        if (!sample.fingerprintOk && (s.staleAtMs == null || sample.atMs >= s.staleAtMs)) {
            s = s.copy(staleAtMs = sample.atMs, staleOpId = sample.opId)
            staleNow = s.staleAtMs == sample.atMs
            notes += "环境指纹对不上 ⇒ 判 SKILL_STALE（${sample.opId}）：后续回放停手，重教才清"
        }

        var demotedNow = false
        var demotionOps: List<String> = emptyList()
        if (s.demotedAtMs == null) {
            val trailing = s.samples.asReversed().takeWhile { !it.ok }.take(DEMOTE_FAILURES).toList()
            if (trailing.size >= DEMOTE_FAILURES) {
                demotionOps = trailing.asReversed().map { it.opId }
                s = s.copy(demotedAtMs = sample.atMs, demotionOpIds = demotionOps)
                demotedNow = true
                notes += "连续 $DEMOTE_FAILURES 次失败 ⇒ 降为草稿（依据 opId：${demotionOps.joinToString("、")}）"
            }
        }
        return AcceptanceUpdate(
            state = s,
            opId = sample.opId,
            duplicate = false,
            demotedNow = demotedNow,
            demotionOpIds = demotionOps,
            staleNow = staleNow,
            notes = notes,
        )
    }

    /** 记一次「用户纠正」（U5；累计不清零，重教才清）。 */
    fun noteCorrection(state: AcceptanceState): AcceptanceState =
        state.copy(correctionsTotal = state.correctionsTotal + 1)

    /** 重教一遍 ⇒ 验收从头来（样本、标、修正数都清；技能正文由写口换新）。 */
    fun onRelearned(): AcceptanceState = AcceptanceState()

    /** 体检（分子分母都给；U4 只在样本 ≥10 时下结论）。 */
    fun verdict(state: AcceptanceState, status: String, nowMs: Long): AcceptanceVerdict {
        val win = windowOf(state, nowMs)
        val ok = win.count { it.ok }
        val total = win.size
        val known = total >= MIN_SAMPLES
        val flags = mutableListOf<AcceptanceFlag>()
        if (state.demotedAtMs != null) flags += AcceptanceFlag.DEMOTED
        if (state.staleAtMs != null) flags += AcceptanceFlag.STALE
        if (known && ok * 100 < total * SUCCESS_PERCENT) flags += AcceptanceFlag.RETIRING
        if (state.correctionsTotal >= RELEARN_CORRECTIONS) flags += AcceptanceFlag.RELEARN

        val drift = win.sumOf { it.driftCount }
        val driftTip = if (drift >= DRIFT_TIP) {
            "定位漂移近 30 天累计 $drift 次（≥$DRIFT_TIP）——该修选择器了；技能正文不会自动改"
        } else {
            null
        }
        val staleBlock = state.staleAtMs?.let {
            "SKILL_STALE：环境变了（域名/页面标题/表头列名对不上），可能需重学；已 fail-closed 停手，" +
                "不带病执行（依据 ${state.staleOpId ?: "未记"}）"
        }
        val sampleLine = when {
            total == 0 -> "近 30 天 0 次（unknown：没有样本，不下结论）"
            !known -> "近 30 天 $total 次（成功 $ok/$total；样本 <$MIN_SAMPLES ⇒ unknown，不下结论）"
            else -> "近 30 天 $total 次（成功 $ok/$total，${ok * 100 / total}%）"
        }
        return AcceptanceVerdict(
            status = status,
            windowTotal = total,
            windowOk = ok,
            rateKnown = known,
            flags = flags.toList(),
            driftTip = driftTip,
            staleBlock = staleBlock,
            sampleLine = sampleLine,
        )
    }

    /** 回放前的闸（U3 fail-closed）：stale ⇒ 停；其余只给提示，不拦。 */
    fun preflight(state: AcceptanceState, status: String, nowMs: Long): Preflight {
        val v = verdict(state, status, nowMs)
        val notes = buildList {
            v.driftTip?.let(::add)
            if (AcceptanceFlag.RELEARN in v.flags) {
                add("补了 ${state.correctionsTotal} 次了（≥$RELEARN_CORRECTIONS）——建议重教一遍；本次照常跑")
            }
            add(v.sampleLine)
        }
        return Preflight(blocked = v.staleBlock, notes = notes)
    }

    /**
     * 入库闸 G1–G3（一条链：G2 → G3 → G1；G2/G3 不过＝拒收，G1 不过＝只能落草稿）。
     */
    fun entryGate(subject: EntrySubject, rehearsal: Rehearsal = Rehearsal()): EntryVerdict {
        val notes = mutableListOf<String>()
        val surface: List<String>
        when (subject) {
            is EntrySubject.Replay -> {
                val skill = subject.skill
                if (skill.steps.isEmpty()) {
                    return EntryVerdict.Reject("G2 未过：技能没有步骤，success 无从观察——先示教一遍")
                }
                val missing = skill.steps.filter { it.expect.isEmpty() }.map { it.index }
                if (missing.isNotEmpty()) {
                    return EntryVerdict.Reject(
                        "G2 未过：第 ${missing.joinToString("、")} 步没有可观察断言（expect）——" +
                            "回放没法证明成了；重录或补断言后再入库",
                    )
                }
                notes += "G2 过：每步都有可观察断言（${skill.steps.size} 步）"
                surface = replaySurface(skill) + rehearsal.touches
            }
            is EntrySubject.Prose -> {
                when (val p = SkillContract.parse(subject.body)) {
                    is SkillContract.Parsed.Absent -> return EntryVerdict.Reject(
                        "G2 未过：正文里没有契约行，success 只能靠「看起来成了」——" +
                            "先补一条可观察判据（`<!-- contract success element_appears=… -->` 或 `text_nonempty=…`）",
                    )
                    is SkillContract.Parsed.Invalid -> return EntryVerdict.Reject(
                        "G2 未过：契约读不成（${p.errors.joinToString("；")}）——按上面指的行号改好再写",
                    )
                    is SkillContract.Parsed.Contracted -> {
                        if (p.contract.success.isEmpty()) {
                            return EntryVerdict.Reject("G2 未过：契约里没有 success 段——补一条可观察的成功判据")
                        }
                        notes += "G2 过：success 可观察（${p.contract.success.joinToString("、") { "${it.kind.wire}=${it.value}" }}）"
                    }
                }
                surface = proseSurface(subject.body) + rehearsal.touches
            }
        }

        // G3：protected 逐条核（受保护清单只在文字技能的 SKILL.md 里；示教技能读盘上既有那份）
        val clauses = protectedClausesOf(subject)
        for (clause in clauses) {
            // 契约里的写法是「类别:值」（path:/admin、action:删除）；盘面上出现的是**值**
            // （正文里的 /admin、步骤里的「删除」）。两种都比，才拦得住。
            val needles = listOf(clause, clause.substringAfter(':', ""))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val hit = surface.firstOrNull { s -> needles.any { s.contains(it, ignoreCase = true) } }
            if (hit != null) {
                return EntryVerdict.Reject(
                    "G3 未过：碰到 protected「$clause」（$hit）——受保护的路径/动作不许动；" +
                        "改技能避开它，或由用户改契约后重写",
                )
            }
        }
        if (clauses.isNotEmpty()) notes += "G3 过：protected ${clauses.size} 条都没碰（${clauses.joinToString("、")}）"

        // G1：站点数
        val sites = rehearsal.sites.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        return when {
            sites.size >= 2 -> {
                notes += "G1 过：${sites.size} 个站点各验过（${sites.joinToString("、")}）"
                EntryVerdict.Pass(singleSite = false, notes = notes)
            }
            sites.size == 1 -> EntryVerdict.DraftOnly(
                reason = "G1 未过：只有一个站点（${sites[0]}）验过——先标草稿，再到第二个站点各成功一次",
                notes = notes + "G1：单站点（${sites[0]}）",
            )
            else -> EntryVerdict.DraftOnly(
                reason = "G1 未过：还没有站点跑通过——先标草稿",
                notes = notes,
            )
        }
    }

    /** 示教技能会碰到的面（步骤 URL／输入值／元素名与文案／定位值／演练痕迹）。 */
    private fun replaySurface(skill: TeachSkill): List<String> = buildList {
        add(skill.startUrl)
        skill.steps.forEach { s ->
            add(s.url)
            s.value?.takeIf { it.isNotBlank() }?.let { add(it) }
            s.snapshot.name?.takeIf { it.isNotBlank() }?.let { add(it) }
            s.snapshot.text?.takeIf { it.isNotBlank() }?.let { add(it) }
            add(s.snapshot.tag)
            s.locators.forEach { add(it.value) }
        }
    }

    /** 文字技能会碰到的面＝正文里的人话（契约行与状态头是机器注释，不算「要碰的东西」）。 */
    private fun proseSurface(body: String): List<String> = body.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !(it.startsWith("<!--") && it.endsWith("-->")) }

    /** 读受保护清单（示教技能从盘上既有契约读；读不成/没有 ⇒ 空表）。 */
    private fun protectedClausesOf(subject: EntrySubject): List<String> {
        val body = when (subject) {
            is EntrySubject.Prose -> subject.body
            is EntrySubject.Replay -> subject.contractBody ?: return emptyList()
        }
        val parsed = SkillContract.parse(body) as? SkillContract.Parsed.Contracted ?: return emptyList()
        return parsed.contract.protectedClauses.map { unquote(it) }.filter { it.isNotBlank() }
    }

    private fun unquote(raw: String): String {
        val s = raw.trim()
        val pairs = listOf(
            Pair('"', '"'), Pair('“', '”'), Pair('「', '」'), Pair('‘', '’'), Pair('\'', '\''),
        )
        val hit = pairs.firstOrNull { s.length >= 2 && s.startsWith(it.first) && s.endsWith(it.second) }
            ?: return s
        return s.substring(1, s.length - 1).trim()
    }
}
