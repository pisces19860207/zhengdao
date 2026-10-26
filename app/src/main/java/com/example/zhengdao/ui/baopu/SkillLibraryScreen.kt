// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百零五批 · 追加件：**技能列表页**（给 184 的技能卡补生产渲染点）。
//
// 背景：`ui/baopu/SkillCard.kt`（184 的技能卡：名字/用途/正文预览/落点/备份名/大按钮）合入后
// 一直没有生产渲染点——反思器产出的候选没处显示。本页就是那个壳：
// - 按三组展示：草稿·未验证／已转正／已退役；
// - 每行**复用 [BaopuSkillCard]**（184 那张卡，一个组件不重写——源码门禁见 `SkillLibraryTest`）；
// - 行内动作全部走**既有口**：编辑/保存＝`SkillCardHost.remember`（184 唯一写口）、
//   转正＝`commitSkillDraft`（32 批唯一写口；转正自动进决策中心留痕＝207）、
//   删除＝`softDeleteSkill`（`SkillStore.archive` 软删＋索引去行，**不真删**，原件留 `.deleted-*`）、
//   恢复＝`restoreArchived`（34 批 B）；
// - 顶部复盘区：反思器（[ReflectionRunner]）的 ≤3 张候选卡；空态给「现在复盘一次」按钮。
//
// 算的都在纯函数里（JVM 直测）；Compose 只接线；文案全在 strings.xml（U6 同款口径）。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.zhengdao.R
import com.example.zhengdao.baopu.GovernancePlan
import com.example.zhengdao.baopu.MemoryScope
import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.ReflectionArchive
import com.example.zhengdao.baopu.ReflectionCandidate
import com.example.zhengdao.baopu.SeedSkills
import com.example.zhengdao.baopu.SkillAudit
import com.example.zhengdao.baopu.SkillDraftGovernor
import com.example.zhengdao.baopu.SkillIndexReconcile
import com.example.zhengdao.baopu.SkillPromote
import com.example.zhengdao.baopu.SkillRules
import com.example.zhengdao.baopu.SkillScope
import com.example.zhengdao.baopu.SkillStore
import com.example.zhengdao.baopu.SkillWriteResult
import com.example.zhengdao.baopu.TeachParam
import com.example.zhengdao.baopu.TeachParamRun
import com.example.zhengdao.util.TimeFormats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

// ── 纯函数区（JVM 直测；磁盘只读）──────────────────────────────────────────────

/** 一行技能的三态（按正文头 status 判；归档件按文件名判）。 */
internal enum class SkillLibraryStatus { DRAFT, ACTIVE, RETIRED }

/** 来源徽标：seed＝种子安装（207）／reflect＝反思器提议被采纳／manual＝其余（手记或模型记）。 */
internal enum class SkillLibrarySource { SEED, REFLECT, MANUAL }

internal data class SkillLibraryRow(
    val name: String,
    val scope: SkillScope,
    val status: SkillLibraryStatus,
    /** 草稿/正式＝SKILL.md；已退役＝`.deleted-<ts>` 那份原件（预览与恢复都靠它）。 */
    val file: File,
    val body: String,
    val uses: Int,
    val lastUseMs: Long?,
    val source: SkillLibrarySource,
    /** 已退役时的归档时间戳（[archivedStampOf] 切出来的；其余 null）。 */
    val archivedAt: String? = null,
    /** 追加件③：与哪条草稿疑似重复（只标不删；一键合并＝保留本条、退役对方）。 */
    val duplicateOf: String? = null,
)

/** 回放结果一句话：跑完/停在第几步（`TeachParamRun.answerLine`）＋被拦动作数＋每步证据目录。 */
internal fun replayAnswerLine(run: TeachParamRun): String = buildString {
    append(run.answerLine())
    run.report?.refusals?.takeIf { it.isNotEmpty() }?.let {
        append("\n拦下 ${it.size} 个不该点的动作（提交/支付类），没有替你点")
    }
    run.report?.evidenceDir?.let { append("\n每步证据：$it") }
}

/** 草稿行 ⇒ 32 批的 [SkillDraftRow]（转正/用一次唯一写口的入参形状；判据同源）。 */
internal fun SkillLibraryRow.toDraftRow(): SkillDraftRow? {
    if (status != SkillLibraryStatus.DRAFT) return null
    val f = File(file.parentFile, SkillRules.SKILL_FILE)
    return SkillDraftRow(
        name = name,
        scope = scope,
        file = f,
        body = body,
        uses = SkillStore.usesOf(body),
        canPromote = SkillStore.canPromote(body),
    )
}

/**
 * 两层技能目录 ⇒ 三组的行（**只读**）。
 *
 * - 草稿：`status=draft`（与 [draftSkillRows] 同判据）；正式：有头 `status=active` 或无头；
 * - 退役：没有 SKILL.md 本名、目录里有 `SKILL.md.deleted-*`（口径照 [archivedSkillRows]：
 *   同名多份各算各的，新→旧）；
 * - uses 只认数据层 [SkillStore.usesOf]（UI 不自己数——32 批 T2）；
 * - 来源徽标＝[skillSourceBadges]；最近使用＝调用方查库给的表（键=技能名）。
 */
internal fun skillLibraryRows(
    projectRoot: File,
    globalRoot: File,
    lastUseMs: Map<String, Long> = emptyMap(),
    badges: Map<String, SkillLibrarySource> = skillSourceBadges(projectRoot, globalRoot),
): List<SkillLibraryRow> {
    val out = mutableListOf<SkillLibraryRow>()
    listOf(
        SkillScope.PROJECT to SkillRules.projectSkillDir(projectRoot),
        SkillScope.GLOBAL to SkillRules.globalSkillDir(globalRoot),
    ).forEach { (scope, dir) ->
        val subs = dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
        subs.forEach { sub ->
            val bodyFile = File(sub, SkillRules.SKILL_FILE)
            if (bodyFile.isFile) {
                val body = runCatching { bodyFile.readText(Charsets.UTF_8) }.getOrNull() ?: return@forEach
                val status = when (SkillStore.statusOf(body)) {
                    "draft" -> SkillLibraryStatus.DRAFT
                    else -> SkillLibraryStatus.ACTIVE
                }
                out += SkillLibraryRow(
                    name = sub.name,
                    scope = scope,
                    status = status,
                    file = bodyFile,
                    body = body,
                    uses = SkillStore.usesOf(body),
                    lastUseMs = lastUseMs[sub.name],
                    source = badges[sub.name] ?: SkillLibrarySource.MANUAL,
                )
            } else {
                val gone = sub.listFiles { f -> f.isFile && f.name.startsWith(SkillIndexReconcile.SOFT_DELETE_MARK) }
                    ?.sortedByDescending { it.name }.orEmpty()
                gone.forEach { f ->
                    val body = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return@forEach
                    out += SkillLibraryRow(
                        name = sub.name,
                        scope = scope,
                        status = SkillLibraryStatus.RETIRED,
                        file = f,
                        body = body,
                        uses = SkillStore.usesOf(body),
                        lastUseMs = lastUseMs[sub.name],
                        source = badges[sub.name] ?: SkillLibrarySource.MANUAL,
                        archivedAt = archivedStampOf(f.name),
                    )
                }
            }
        }
    }
    return out
}

/**
 * 来源徽标：两层 `技能/_变更.jsonl` 按名字取**最新一条**审计行——
 * `source=seed`（种子安装/转正留痕）⇒ SEED；reason 含「反思器」⇒ REFLECT；其余 ⇒ MANUAL。
 * 没有审计行的（手放进目录的老技能）⇒ 不进表，落 MANUAL；种子名单兜底（与 207 同口径）。
 */
internal fun skillSourceBadges(projectRoot: File, globalRoot: File): Map<String, SkillLibrarySource> {
    data class Latest(var ts: Long, var source: SkillLibrarySource)

    val latest = mutableMapOf<String, Latest>()
    for (dir in listOf(SkillRules.projectSkillDir(projectRoot), SkillRules.globalSkillDir(globalRoot))) {
        val f = SkillAudit.fileIn(dir)
        if (!f.isFile) continue
        val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: continue
        text.lines().forEach { raw ->
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEach
            val name = o.optString("name")
            if (name.isEmpty()) return@forEach
            val ts = o.optLong("ts", 0L)
            val src = when {
                o.optString("source") == SkillPromote.SOURCE_SEED -> SkillLibrarySource.SEED
                o.optString("reason").contains("反思器") -> SkillLibrarySource.REFLECT
                else -> SkillLibrarySource.MANUAL
            }
            val cur = latest[name]
            if (cur == null || ts >= cur.ts) latest[name] = Latest(ts, src)
        }
    }
    val out = latest.mapValues { it.value.source }.toMutableMap()
    for (n in SeedSkills.NAMES) out.putIfAbsent(n, SkillLibrarySource.SEED)
    return out
}

/**
 * 「删除」＝**软删**（追加件红线：不真删文件）：
 * ① [SkillStore.archive] 把 SKILL.md 改名 `.deleted-<ts>`（原件字节不动）；
 * ② 索引去行：[SkillRules.removeIndexLine] ＋ 人设层唯一写口 `PersonaFiles.applyProposal`
 *    （[SkillStore.archive] 的文档口径就是「索引行由调用方一并去掉」）。
 * 已退役的行再来删 ⇒ 只补索引去行（正文早软删过了）。任何一步失败都**不**谎报成功。
 */
internal fun softDeleteSkill(
    row: SkillLibraryRow,
    personaFiles: PersonaFiles,
    stamp: String,
): SkillWriteResult {
    if (row.status != SkillLibraryStatus.RETIRED && row.file.isFile) {
        val r = SkillStore.archive(row.file, stamp)
        if (r !is SkillWriteResult.Written) return r
    }
    val layer = when (row.scope) {
        SkillScope.GLOBAL -> com.example.zhengdao.baopu.PersonaLayer.B_GLOBAL
        SkillScope.PROJECT -> com.example.zhengdao.baopu.PersonaLayer.C_PROJECT
    }
    val current = personaFiles.read(layer).orEmpty()
    return when (val edit = SkillRules.removeIndexLine(current, row.name)) {
        is SkillRules.EditResult.Ok -> {
            val written = personaFiles.applyProposal(
                com.example.zhengdao.baopu.PersonaProposal(
                    layer = layer,
                    current = current,
                    proposed = edit.newText,
                    reason = "删除技能：去索引行（${row.name}）",
                ),
            )
            if (written) {
                // 第二百零八批 §1／§2.7 `skill_retire`：正式技能退役**必抽检**（不可逆感最强的一类）
                com.example.zhengdao.baopu.DecisionLedger.record(
                    com.example.zhengdao.baopu.DecisionDraft(
                        kind = com.example.zhengdao.baopu.DecisionKind.SKILL_RETIRE,
                        actor = com.example.zhengdao.baopu.DecisionActor.USER,
                        reason = "退役技能：去索引行",
                        evidence = "skill:${if (row.scope == SkillScope.GLOBAL) "global" else "project"}/${row.name}",
                        before = "active",
                        after = "retired（.deleted-$stamp）",
                        atMs = System.currentTimeMillis(),
                        // 已退役的行再来删只补索引去行（正文早就 `.deleted-*` 过了，戳不是这一次的）
                        rollbackRef = if (row.status == SkillLibraryStatus.RETIRED) {
                            row.file.absolutePath
                        } else {
                            "${row.file.absolutePath}$ARCHIVED_MARK$stamp"
                        },
                    ),
                ) ?: com.example.zhengdao.rootfs.RunLog.log(
                    "退役技能：决策记录没写成 ${row.name}（${com.example.zhengdao.baopu.DecisionLedger.lastError}）",
                )
                SkillWriteResult.Written(file = row.file, backup = null, indexUpdated = true)
            } else {
                SkillWriteResult.Rejected("正文已软删，但索引行没去掉（${row.name}）——再删一次或去设置页看")
            }
        }
        else -> SkillWriteResult.Written(file = row.file, backup = null, indexUpdated = false)
    }
}

/** 反思器候选 ⇒ 184 卡的草稿（落点成技能的形状才给；落点非技能 ⇒ null，页上出说明行＋决定按钮）。 */
internal fun candidateDraft(c: ReflectionCandidate): SkillCardDraft? {
    val name = c.suggestedName?.let { SkillStore.sanitizeName(it) } ?: return null
    val body = c.suggestedBody ?: return null
    return SkillCardDraft(
        name = name,
        body = SkillStore.contentWithoutHeader(body),
        reason = "反思器提议：${c.claim}",
        source = SkillCardSource.REFLECTION,
    )
}

// ── 追加件③：草稿自动治理（执行壳——写盘全部走既有口，判据在 SkillDraftGovernor）──

/** 治理执行报告（每条动作都留痕；`SkillDraftGovernorTest` 逐条对账，缺账即红）。 */
internal data class GovernanceReport(
    /** 满 3 次自动转正（action=promote，reason=满3次——进决策中心）。 */
    val promoted: List<String> = emptyList(),
    /** 30 天未用软退役（action=skill_draft_retire）。 */
    val retiredIdle: List<String> = emptyList(),
    /** 超出 20 条最久未用软退役（同 action）。 */
    val retiredOverflow: List<String> = emptyList(),
    /** 疑似重复对（只标不删；技能页出「疑似重复（A/B）」＋一键合并）。 */
    val duplicates: List<Pair<String, String>> = emptyList(),
) {
    val isEmpty: Boolean
        get() = promoted.isEmpty() && retiredIdle.isEmpty() && retiredOverflow.isEmpty() && duplicates.isEmpty()
}

/** 两层索引的场景句（重复判据的原料；按名字精确对上）。 */
internal fun indexScenarios(personaFiles: PersonaFiles): Map<String, String> {
    val out = mutableMapOf<String, String>()
    for (layer in listOf(
        com.example.zhengdao.baopu.PersonaLayer.C_PROJECT,
        com.example.zhengdao.baopu.PersonaLayer.B_GLOBAL,
    )) {
        val text = personaFiles.read(layer) ?: continue
        SkillRules.parseIndexLines(text, if (layer == com.example.zhengdao.baopu.PersonaLayer.B_GLOBAL) MemoryScope.GLOBAL else MemoryScope.PROJECT)
            .forEach { out.putIfAbsent(it.name, it.scenario) }
    }
    return out
}

/**
 * 跑一轮草稿治理（**不弹卡**；技能页打开时在 IO 上跑）。
 *
 * 三条红线的执行点：
 * - 只软退役：[retireDraft] 走 `SkillStore.archive`（改名 `.deleted-*`，原件字节不动）＋索引去行；
 * - 不碰正式组：判据层 [SkillDraftGovernor.draftStats] 只收 `status=draft`；
 * - 留痕：转正经 [commitSkillDraft]（promote 审计），软退役经 [SkillAudit.record]
 *   （action=`skill_draft_retire`）——两类都进决策中心记录，可回退（退役在已退役组「恢复」）。
 */
internal fun governDrafts(
    projectRoot: File,
    globalRoot: File,
    personaFiles: PersonaFiles,
    lastUseMs: Map<String, Long>,
    nowMs: Long,
    stamp: String = TimeFormats.fileStamp(),
    log: (String) -> Unit = {},
    /**
     * 第二百二十批 · 影响提示的只读预览口：**判据算完、执行之前**回调一次
     * （[plan] = 四条规则的结论，[statsSize] = 草稿总数）。
     *
     * 为什么是回调而不是"再算一遍"：`draftStats` 要扫两层技能目录＋解析审计账，
     * 为了显示一行字再扫一遍是纯浪费；这里 Plan 已经在手上，白送。
     * 默认空实现 ⇒ 存量调用点（含测试）一个字不改。
     */
    onPreview: (GovernancePlan, Int) -> Unit = { _, _ -> },
): GovernanceReport {
    val scenarios = indexScenarios(personaFiles)
    val stats = SkillDraftGovernor.draftStats(projectRoot, globalRoot, lastUseMs, scenarios)
    if (stats.isEmpty()) return GovernanceReport()
    val plan = SkillDraftGovernor.plan(stats, nowMs)
    // 第二百二十批：预览在**执行之前**（这时一条都还没改；事后再说"要退役 3 条"就是假话）
    runCatching { onPreview(plan, stats.size) }

    // ③ 满 3 次 ⇒ 自动转正（唯一写口；治理路把「满3次」说进账本）
    val promoted = mutableListOf<String>()
    for (s in plan.promoteDue) {
        val row = SkillDraftRow(
            name = s.name, scope = s.scope, file = s.file, body = s.body,
            uses = s.uses, canPromote = SkillStore.canPromote(s.body),
        )
        val r = commitSkillDraft(
            row = row,
            newBody = promoteBody(s.body),
            stillDraft = false,
            projectRoot = projectRoot,
            globalRoot = globalRoot,
            personaFiles = personaFiles,
            backupStamp = stamp,
            reason = "草稿治理：满${SkillStore.PROMOTE_USES}次自动转正",
            promoteReason = SkillPromote.REASON_FULL_USES,
        )
        if (r is SkillWriteResult.Written) promoted += s.name
        else log("草稿治理：自动转正失败 ${s.name}（${(r as SkillWriteResult.Rejected).reason}）")
    }

    // ①② 软退役（idle 在前；overflow 的名单在判据层已去重）
    val retiredIdle = mutableListOf<String>()
    val retiredOverflow = mutableListOf<String>()
    for ((list, reason, sink) in listOf(
        Triple(plan.idleRetire, "30天未用", retiredIdle),
        Triple(plan.overflowRetire, "草稿超${SkillDraftGovernor.MAX_DRAFTS}条最久未用", retiredOverflow),
    )) {
        for (s in list) {
            when (val r = retireDraft(s, personaFiles, stamp, reason, nowMs, log)) {
                is SkillWriteResult.Written -> sink += s.name
                is SkillWriteResult.Rejected -> log("草稿治理：软退役失败 ${s.name}（${r.reason}）")
            }
        }
    }
    return GovernanceReport(
        promoted = promoted,
        retiredIdle = retiredIdle,
        retiredOverflow = retiredOverflow,
        duplicates = plan.duplicatePairs.map { it.first.name to it.second.name },
    )
}

/**
 * 软退役一条草稿（**永不真删**）：`SkillStore.archive` 改名＋索引去行＋
 * `skill_draft_retire` 审计行（决策中心记录可查；回退＝技能页已退役组的「恢复」）。
 */
internal fun retireDraft(
    stat: com.example.zhengdao.baopu.DraftStat,
    personaFiles: PersonaFiles,
    stamp: String,
    reason: String,
    nowMs: Long,
    log: (String) -> Unit = {},
): SkillWriteResult {
    val r = SkillStore.archive(stat.file, stamp)
    if (r !is SkillWriteResult.Written) return r
    val layer = when (stat.scope) {
        SkillScope.GLOBAL -> com.example.zhengdao.baopu.PersonaLayer.B_GLOBAL
        SkillScope.PROJECT -> com.example.zhengdao.baopu.PersonaLayer.C_PROJECT
    }
    val current = personaFiles.read(layer).orEmpty()
    var indexUpdated = false
    when (val edit = SkillRules.removeIndexLine(current, stat.name)) {
        is SkillRules.EditResult.Ok -> {
            indexUpdated = personaFiles.applyProposal(
                com.example.zhengdao.baopu.PersonaProposal(
                    layer = layer,
                    current = current,
                    proposed = edit.newText,
                    reason = "草稿治理：$reason（${stat.name}）",
                ),
            )
        }
        else -> Unit // 没有行可去（失联草稿）——正文软删已成立，索引本来就看不见它
    }
    val audited = SkillAudit.record(
        skillFile = r.file, // .deleted-* 那份：目录推导与正文同目录
        scope = if (stat.scope == SkillScope.GLOBAL) "global" else "project",
        name = stat.name,
        action = SkillPromote.ACTION_DRAFT_RETIRE,
        reason = reason,
        beforeSha = "-",
        indexUpdated = indexUpdated,
        nowMs = nowMs,
    )
    if (!audited) log("草稿治理：skill_draft_retire 审计写失败 ${stat.name}")
    // 第二百零八批 §1／§2.7 `skill_draft_retire`：治理器**自动**退的草稿也要进决策中心
    // （§2.7 把它列进「待抽检」的每周配额档——自动退草稿是最容易被判错的那一类）。
    com.example.zhengdao.baopu.DecisionLedger.record(
        com.example.zhengdao.baopu.DecisionDraft(
            kind = com.example.zhengdao.baopu.DecisionKind.SKILL_DRAFT_RETIRE,
            actor = com.example.zhengdao.baopu.DecisionActor.AUTO,
            reason = "草稿软退役：$reason",
            evidence = "skill:${if (stat.scope == SkillScope.GLOBAL) "global" else "project"}/${stat.name}",
            before = "draft",
            after = "retired（.deleted-$stamp）",
            atMs = nowMs,
            rollbackRef = r.file.absolutePath,
        ),
    ) ?: log("草稿治理：决策记录没写成 ${stat.name}（${com.example.zhengdao.baopu.DecisionLedger.lastError}）")
    return SkillWriteResult.Written(file = r.file, backup = null, indexUpdated = indexUpdated)
}

/** 行内动作（页面把点击收成这几个词，执行在控制器那一个口里）。 */
internal enum class SkillRowAction { PROMOTE, DELETE, RESTORE, MERGE, EVALUATE }

// ── 页面 ─────────────────────────────────────────────────────────────────────

/** 一行的渲染态：行数据＋一张 184 卡的宿主（编辑/保全都过它——本页不开第二个写口）。 */
private class RowUi(val row: SkillLibraryRow, val host: SkillCardHost) {
    var view by mutableStateOf<SkillCardView?>(null)
}

/** 技能页读数（控制器 IO 上装好递进来）：三组行＋进化档案里待定候选的条数。 */
internal data class SkillLibraryState(
    val rows: List<SkillLibraryRow> = emptyList(),
    val pendingCount: Int = 0,
)

@Composable
internal fun SkillLibraryDialog(
    controller: BaopuChatController,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf(SkillLibraryState()) }
    var message by remember { mutableStateOf<String?>(null) }

    // 读盘在 IO 上做（两层技能目录＋变更账＋skill_event＋进化档案），行卡视图也在那里建好。
    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) {
            state = controller.loadSkillLibrary()
        }
    }

    val drafts = state.rows.filter { it.status == SkillLibraryStatus.DRAFT }
    val active = state.rows.filter { it.status == SkillLibraryStatus.ACTIVE }
    val retired = state.rows.filter { it.status == SkillLibraryStatus.RETIRED }
    val empty = drafts.isEmpty() && active.isEmpty() && retired.isEmpty() &&
        controller.reflectionCards.isEmpty()

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .padding(16.dp)
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.skill_library_title), style = MaterialTheme.typography.titleMedium)
                    // 第二百零六批：每周一次批量对拍（最近 7 天改过的技能逐个跑；不强制、不阻塞）
                    TextButton(onClick = { controller.runWeeklyEval() }, enabled = !controller.reflectionBusy) {
                        Text(stringResource(R.string.eval_weekly))
                    }
                }
                message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                controller.reflectionNote?.let {
                    Text(it.text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // ── 复盘区：反思器候选（≤3；采纳走 184 卡宿主，否决/搁置进进化档案）────
                if (controller.reflectionCards.isNotEmpty()) {
                    Text(
                        stringResource(R.string.skill_library_review_section, controller.reflectionCards.size),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    controller.reflectionCards.forEach { c ->
                        ReflectionCandidateRow(
                            candidate = c,
                            controller = controller,
                            onDone = { msg ->
                                if (msg != null) message = msg
                                tick++
                            },
                        )
                    }
                } else if (state.pendingCount > 0) {
                    Text(
                        stringResource(R.string.skill_library_pending_count, state.pendingCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (empty) {
                    Text(stringResource(R.string.skill_library_empty), style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = { controller.runReflectionNow() },
                        enabled = !controller.reflectionBusy,
                    ) {
                        Text(stringResource(R.string.skill_library_review_now))
                    }
                } else {
                    SkillGroup(stringResource(R.string.skill_library_group_draft, drafts.size), drafts, controller) { msg ->
                        if (msg != null) message = msg
                        tick++
                    }
                    SkillGroup(stringResource(R.string.skill_library_group_active, active.size), active, controller) { msg ->
                        if (msg != null) message = msg
                        tick++
                    }
                    SkillGroup(stringResource(R.string.skill_library_group_retired, retired.size), retired, controller) { msg ->
                        if (msg != null) message = msg
                        tick++
                    }
                }

                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.common_close))
                }
            }
        }
    }
}

@Composable
private fun SkillGroup(
    title: String,
    rows: List<SkillLibraryRow>,
    controller: BaopuChatController,
    onActionDone: (String?) -> Unit,
) {
    if (rows.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleSmall)
    rows.forEach { row ->
        SkillRow(row = row, controller = controller, onActionDone = onActionDone)
    }
}

/** 一行＝复用的 184 卡＋元信息行（徽标/层/uses/最近使用）＋行内动作（转正/删除/恢复）。 */
@Composable
private fun SkillRow(
    row: SkillLibraryRow,
    controller: BaopuChatController,
    onActionDone: (String?) -> Unit,
) {
    val ui = remember(row.file.absolutePath) { RowUi(row, controller.newSkillCardHost()) }
    LaunchedEffect(row.file.absolutePath) {
        withContext(Dispatchers.IO) { ui.view = buildRowView(ui.host, row) }
    }
    var confirmDelete by remember(row.file.absolutePath) { mutableStateOf(false) }
    // 226-2：回放（参数非空时先要一句话；busy 防连点；ask 非空＝还差点参数，框继续开着）
    var replayParams by remember(row.file.absolutePath) { mutableStateOf<List<TeachParam>?>(null) }
    var replayAsk by remember(row.file.absolutePath) { mutableStateOf<String?>(null) }
    var replayBusy by remember(row.file.absolutePath) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        ui.view?.let { v ->
            BaopuSkillCard(
                view = v,
                onEdit = { d ->
                    (ui.host.edit(d) as? SkillCardBuild.Ready)?.let { ui.view = it.view }
                },
                onRemember = {
                    when (row.status) {
                        SkillLibraryStatus.RETIRED ->
                            onActionDone(controller.runSkillRowAction(row, SkillRowAction.RESTORE))
                        else -> when (val r = ui.host.remember()) { // 184 的唯一写口（编辑保存）
                            null -> onActionDone(null) // 宿主没卡（不该发生）：按无话可说处理
                            is SkillWriteResult.Written -> onActionDone(null)
                            is SkillWriteResult.Rejected -> onActionDone(r.reason)
                        }
                    }
                },
                onDismiss = { if (row.status != SkillLibraryStatus.RETIRED) ui.host.dismiss() },
            )
        }
        MetaLine(row)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (row.status == SkillLibraryStatus.DRAFT) {
                TextButton(onClick = {
                    onActionDone(controller.runSkillRowAction(row, SkillRowAction.PROMOTE))
                }) {
                    Text(stringResource(R.string.skill_library_promote))
                }
            }
            // 追加件③：疑似重复只标不删——「合并（保留这条）」把对方软退役（可恢复）
            if (row.status == SkillLibraryStatus.DRAFT && row.duplicateOf != null) {
                TextButton(onClick = {
                    onActionDone(controller.runSkillRowAction(row, SkillRowAction.MERGE))
                }) {
                    Text(stringResource(R.string.skill_library_merge))
                }
            }
            // 第二百二十六批第 2 条：回放（草稿/正式都有；退役不给）。没有玄览会话 ⇒ 一句人话。
            if (row.status != SkillLibraryStatus.RETIRED) {
                TextButton(
                    enabled = !replayBusy,
                    onClick = {
                        replayBusy = true
                        controller.prepareSkillReplay(row) { err, params ->
                            when {
                                err != null -> {
                                    replayBusy = false
                                    onActionDone(err)
                                }
                                params.isEmpty() -> controller.runSkillReplayAsync(row, "") { run ->
                                    replayBusy = false
                                    if (run.ask != null) {
                                        // 声明了参数却没取到（空表不该走到这）：给反问，不空跑
                                        onActionDone(run.ask)
                                    } else {
                                        onActionDone(replayAnswerLine(run))
                                    }
                                }
                                else -> {
                                    replayBusy = false
                                    replayAsk = null
                                    replayParams = params
                                }
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.skill_library_replay))
                }
            }
            // 第二百零六批：「跑一次对拍」（不强制、不阻塞——结果以便签＋进化档案给出）
            // G1：真跑含网络 ⇒ 交给控制器的 scope（内部切 IO），不许落在 Compose onClick 的主线程上。
            if (row.status != SkillLibraryStatus.RETIRED) {
                TextButton(onClick = {
                    controller.runSkillEvaluateAsync(row, onActionDone)
                }) {
                    Text(stringResource(R.string.eval_once))
                }
            }
            if (row.status == SkillLibraryStatus.RETIRED) {
                TextButton(onClick = {
                    onActionDone(controller.runSkillRowAction(row, SkillRowAction.RESTORE))
                }) {
                    Text(stringResource(R.string.skill_library_restore))
                }
            } else {
                TextButton(onClick = {
                    if (confirmDelete) onActionDone(controller.runSkillRowAction(row, SkillRowAction.DELETE))
                    else confirmDelete = true
                }) {
                    Text(
                        stringResource(
                            if (confirmDelete) R.string.skill_library_delete_confirm else R.string.skill_library_delete,
                        ),
                    )
                }
            }
        }
        replayParams?.let { params ->
            ReplayParamsDialog(
                skillName = row.name,
                labels = params.joinToString("、") { it.label },
                hint = replayAsk,
                busy = replayBusy,
                onCancel = {
                    replayParams = null
                    replayAsk = null
                },
                onSubmit = { sentence ->
                    replayBusy = true
                    controller.runSkillReplayAsync(row, sentence) { run ->
                        replayBusy = false
                        if (run.ask != null) {
                            replayAsk = run.ask
                        } else {
                            replayParams = null
                            replayAsk = null
                            onActionDone(replayAnswerLine(run))
                        }
                    }
                },
            )
        }
    }
}

/**
 * 第二百二十六批第 2 条：回放前的「一句话」框。
 *
 * 只收一句话（参数解析全在 200 批的 `TeachParams.plan`，界面一个字不猜）；
 * [hint] 非空＝上一轮没取全，把它的反问原样显示出来让人补一句；[busy] 时一个按钮都点不动。
 */
@Composable
private fun ReplayParamsDialog(
    skillName: String,
    labels: String,
    hint: String?,
    busy: Boolean,
    onCancel: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var sentence by remember(skillName) { mutableStateOf("") }
    Dialog(onDismissRequest = { if (!busy) onCancel() }) {
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.skill_replay_params_title, skillName, labels),
                    style = MaterialTheme.typography.titleSmall,
                )
                hint?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = sentence,
                    onValueChange = { sentence = it },
                    label = { Text(stringResource(R.string.skill_replay_sentence_label)) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel, enabled = !busy) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    TextButton(onClick = { onSubmit(sentence) }, enabled = !busy && sentence.isNotBlank()) {
                        Text(stringResource(R.string.skill_replay_run))
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaLine(row: SkillLibraryRow) {
    val src = stringResource(
        when (row.source) {
            SkillLibrarySource.SEED -> R.string.skill_library_source_seed
            SkillLibrarySource.REFLECT -> R.string.skill_library_source_reflect
            SkillLibrarySource.MANUAL -> R.string.skill_library_source_manual
        },
    )
    val layer = stringResource(
        if (row.scope == SkillScope.GLOBAL) R.string.skill_library_scope_global else R.string.skill_library_scope_project,
    )
    val last = row.lastUseMs?.let { TimeFormats.minuteStamp(it) }
        ?: stringResource(R.string.skill_library_never_used)
    val duplicate = row.duplicateOf?.let { stringResource(R.string.skill_library_duplicate, it) }.orEmpty()
    Text(
        text = stringResource(R.string.skill_library_meta, src, layer, row.uses, last) +
            (if (row.archivedAt != null) stringResource(R.string.skill_library_retired_at, row.archivedAt) else "") +
            duplicate,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 反思器候选行：落点能成技能的出 184 卡（记住它＝184 写盘）；落点非技能的出说明行＋决定按钮。 */
@Composable
private fun ReflectionCandidateRow(
    candidate: ReflectionCandidate,
    controller: BaopuChatController,
    onDone: (String?) -> Unit,
) {
    val draft = remember(candidate.dedupKey) { candidateDraft(candidate) }
    val host = remember(candidate.dedupKey) { controller.newSkillCardHost() }
    var view by remember(candidate.dedupKey) { mutableStateOf<SkillCardView?>(null) }
    LaunchedEffect(candidate.dedupKey) {
        val d = draft ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            (host.offer(d) as? SkillCardBuild.Ready)?.let { view = it.view }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Text(candidate.claim, style = MaterialTheme.typography.bodySmall)
        candidate.evidences.forEach { e ->
            Text(
                text = stringResource(R.string.skill_library_evidence, e.display, e.path, e.anchor),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        view?.let { v ->
            BaopuSkillCard(
                view = v,
                onEdit = { d ->
                    (host.edit(d) as? SkillCardBuild.Ready)?.let { view = it.view }
                },
                onRemember = {
                    when (val r = host.remember()) { // 184 的唯一写口：确认后才写技能与索引
                        null -> onDone(null)
                        is SkillWriteResult.Written -> {
                            controller.decideReflection(candidate, ReflectionArchive.DECISION_ADOPTED)
                            onDone(null)
                        }
                        is SkillWriteResult.Rejected -> onDone(r.reason)
                    }
                },
                onDismiss = {
                    controller.decideReflection(candidate, ReflectionArchive.DECISION_REJECTED)
                    onDone(null)
                },
            )
        } ?: run {
            // 落点不是技能的候选（如「按 204 对账结果修一轮」）：只给决定按钮，不出卡
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    controller.decideReflection(candidate, ReflectionArchive.DECISION_SHELVED)
                    onDone(null)
                }) { Text(stringResource(R.string.skill_library_shelve)) }
                TextButton(onClick = {
                    controller.decideReflection(candidate, ReflectionArchive.DECISION_REJECTED)
                    onDone(null)
                }) { Text(stringResource(R.string.skill_library_reject)) }
            }
        }
    }
}

private fun buildRowView(host: SkillCardHost, row: SkillLibraryRow): SkillCardView? {
    val draft = SkillCardDraft(
        name = row.name,
        body = SkillStore.contentWithoutHeader(row.body),
        reason = "技能页（${row.name}）",
        source = SkillCardSource.USER_SAID,
    )
    return (host.offer(draft) as? SkillCardBuild.Ready)?.view
}
