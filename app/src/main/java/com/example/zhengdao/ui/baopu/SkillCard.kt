// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第一百八十四批（2026-10-24，用户口径「让抱朴自己记技能也是，现在特别不方便」）。
//
// 这张卡补的是**入口**，不是写盘：用户说「记住这个技能／以后都这么做／把这个流程记下来」时，
// 抱朴先出一张卡（名字、用途一句话、正文预览、落点、备份名），用户点「记住它」才落盘。
// 落盘仍只有 `SkillStore.plan/apply` 这一条路（写前备份、正文＋索引一齐写）——本文件不另开写口。
//
// 三件必须留在本文件里、不能塞回 Compose 的事（JVM 里直接可测）：
//   1. 入口判据：哪句话算「要记技能」、哪次重复成功值得主动提一张；
//   2. 卡片内容的唯一取法：预览／用途／备份名全部问 `SkillStore.plan` 要，UI 不自己切字符串；
//   3. 确认后的保存：`SkillCardWriter.save`，编辑了名字／正文也从这里过同一套判据。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.MemoryScope
import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.PersonaLayer
import com.example.zhengdao.baopu.SkillHygiene
import com.example.zhengdao.baopu.SkillProposal
import com.example.zhengdao.baopu.SkillRules
import com.example.zhengdao.baopu.SkillSchemaPrecheck
import com.example.zhengdao.baopu.SkillScope
import com.example.zhengdao.baopu.SkillStore
import com.example.zhengdao.baopu.SkillWriteResult
import com.example.zhengdao.core.TimeFormats
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import java.io.File

/**
 * 一张技能卡的来路：用户明说的／「同一流程又成功了一次」的主动提议／反思器候选
 * （第二百零五批：反思器产出的候选卡复用这张卡，来源标单独一个值，不冒充用户说的）。
 */
internal enum class SkillCardSource { USER_SAID, REPEAT_SUCCESS, REFLECTION }

/**
 * 卡片上**用户可改的两个字段**（名字／正文）＋来路。
 *
 * scope 固定 `PROJECT`：本单红线「只写项目技能，全局仍不许自写」。留这个字段是为了让
 * 「用户说了全局」这件事在数据层就被拒（[SkillCardRules.build]），而不是靠 UI 记得不显示。
 */
internal data class SkillCardDraft(
    val name: String,
    val body: String,
    val reason: String,
    val source: SkillCardSource = SkillCardSource.USER_SAID,
    val scope: SkillScope = SkillScope.PROJECT,
)

/** 出卡结果：要么能看、要么直接说不行（全局底座／名字不可用都在这里说清）。 */
internal sealed interface SkillCardBuild {
    data class Ready(val view: SkillCardView) : SkillCardBuild
    data class Refused(val reason: String) : SkillCardBuild
}

/**
 * 卡片要显示的每一项（**全部算好**，UI 只渲染）。
 *
 * @param preview 正文前 [SkillStore.PREVIEW_CHARS] 字
 * @param purpose 用途一句话（与索引场景同一取法）
 * @param backupName 同名技能已存在时的备份名；null ＝ 首次保存，不覆盖任何文件
 * @param blocked 非 null ⇒ 这张卡不能点「记住它」（空正文／名字不可用／索引满／schema 预检不过）
 * @param isDraft 草稿 ⇒ 索引行会带「草稿·未验证」标
 * @param schemaCheckedCount 正文里有几个工具示例过了 schema 预检（第一百八十五批）；
 *   null ＝ 正文里没有可预检的示例，卡上不显示这一行。失败时不走这行——
 *   失败原因已经带在 [blocked] 里（红字＋禁用保存）。
 */
internal data class SkillCardView(
    val draft: SkillCardDraft,
    val file: File,
    val purpose: String,
    val preview: String,
    val charCount: Int,
    val backupName: String?,
    val blocked: String?,
    val isDraft: Boolean,
    val exists: Boolean,
    val schemaCheckedCount: Int? = null,
    /**
     * 289①：与既有技能重名/近似时的人话说明（null ＝ 没查出来，行为照旧）。
     * 非 null ⇒ 卡上必须给「升级／新建」二选一（[altName] 是新建分支的建议名，可为 null）。
     */
    val duplicateNote: String? = null,
    val altName: String? = null,
) {
    /** 「记住它」能不能点：判据只有一条——数据层没挡住。 */
    val canRemember: Boolean get() = blocked == null

    /** 卡上「落点」那一行：永远项目技能，不显示绝对路径（用户看不懂 App 私有目录）。 */
    val target: String get() = "${SkillRules.SKILL_DIR}/${file.parentFile?.name}/${SkillRules.SKILL_FILE}"
}

/** 入口判据与出卡内容的**唯一真相**（纯函数；不读时钟、不写盘）。 */
internal object SkillCardRules {

    /**
     * 用户「明说要记技能」的原话标记（派工单 §1 三句＋常用变体）。
     *
     * 与 `SkillRefine.LEARN_MARKERS`（「记住」「记下来」）是**包含关系**：那边管「这次学到」
     * 的泛信号，这里管「要一张技能卡」的强意图。两句都命中不算两张卡——上层按一次 offer 处理。
     */
    val REMEMBER_MARKERS = listOf(
        "记住这个技能", "记住这个流程", "把这个流程记下来",
        "以后都这么做", "以后都这样做", "以后照这个做",
        "记成技能", "做成技能", "存成技能",
    )

    /** 用户说了「全局」⇒ 这张卡只能拒绝（全局底座由用户在设置页改，抱朴不许自写）。 */
    const val GLOBAL_REFUSAL = "全局底座请到设置里改"

    /** 这句话是不是在要求「把刚才的做法记成技能」。 */
    fun isRememberRequest(userText: String): Boolean =
        userText.isNotBlank() && REMEMBER_MARKERS.any { userText.contains(it) }

    /** 用户这句话里有没有把落点指向全局。 */
    fun asksGlobal(userText: String): Boolean = userText.contains("全局")

    /**
     * 重复成功的主动提议（派工单 §1 第二条）：同一流程成功 [threshold] 次才提，**提过就不再提**。
     *
     * @param signatures 最近几轮的「流程签名」（[flowSignature] 生成，旧→新）
     * @param current 这一轮的签名
     * @param alreadyOffered 这个签名是否已经提过（用户拒绝过的也算提过——不许复读）
     */
    fun repeatedSuccessDue(
        signatures: List<String>,
        current: String,
        alreadyOffered: Boolean,
        threshold: Int = 2,
    ): Boolean = !alreadyOffered && current.isNotBlank() && signatures.count { it == current } >= threshold

    /**
     * 流程签名：这一轮**真正成功**的工具名，按调用顺序拼起来。
     *
     * 只收成功（GuardPipeline 的 Done）——失败/被闸拦下的尝试不算「这个流程能跑通」。
     * 用工具名而不是参数：同一套做法换个文件名仍是同一个流程（参数进签名会让签名永远不重复）。
     */
    fun flowSignature(successfulToolNames: List<String>): String =
        successfulToolNames.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(">")

    /**
     * 出卡：把用户（或模型）给的草稿交给 `SkillStore.plan` 算出预览、备份名与阻断原因。
     *
     * **不写盘**——用户点「记住它」之前，技能目录与索引一个字节都不动（判据：未确认无落盘）。
     */
    fun build(
        draft: SkillCardDraft,
        projectRoot: File,
        globalRoot: File,
        stamp: String,
        indexCountAfterAdd: Int = 0,
        existingLines: List<SkillRules.IndexLine> = emptyList(),
    ): SkillCardBuild {
        if (draft.scope != SkillScope.PROJECT) return SkillCardBuild.Refused(GLOBAL_REFUSAL)
        val humanBody = SkillStore.contentWithoutHeader(draft.body)
        // 先按「新草稿」问一次路径：名字安全化与落点只有 plan 这一处真源。
        val probe = SkillStore.plan(
            proposal = SkillProposal(
                scope = SkillScope.PROJECT,
                name = draft.name,
                body = humanBody,
                reason = draft.reason,
                draft = true,
            ),
            projectRoot = projectRoot,
            globalRoot = globalRoot,
            backupStamp = stamp,
            indexCountAfterAdd = indexCountAfterAdd,
        )
        val existingBody = if (probe.file.isFile) {
            runCatching { probe.file.readText(Charsets.UTF_8) }.getOrNull()
        } else {
            null
        }
        // 已经在养的技能保留它现在的状态：草稿再改还是草稿，转正过的不会被一次编辑打回草稿。
        val isDraft = existingBody?.let { SkillStore.statusOf(it) == "draft" } ?: true
        val plan = SkillStore.plan(
            proposal = SkillProposal(
                scope = SkillScope.PROJECT,
                name = draft.name,
                body = humanBody,
                reason = draft.reason,
                draft = isDraft,
            ),
            projectRoot = projectRoot,
            globalRoot = globalRoot,
            backupStamp = stamp,
            indexCountAfterAdd = indexCountAfterAdd,
            existingLines = existingLines,
        )
        // 289①：重名/近似命中 ⇒ 卡上摆「升级／新建」二选一（不静默覆盖）。
        val duplicateNote = SkillCardRules.duplicateNoteOf(draft, plan.duplicate, existingLines)
        // 第一百八十五批：正文里有没有工具示例、预检过没过，卡上要有一句交代——
        // 通过显示数量；失败原因在 plan.blocked 里（同一套判据，UI 不自己再判一遍）。
        val schema = SkillSchemaPrecheck.precheck(humanBody)
        return SkillCardBuild.Ready(
            SkillCardView(
                draft = draft.copy(scope = SkillScope.PROJECT),
                file = plan.file,
                purpose = SkillStore.purposeOf(humanBody),
                preview = humanBody.take(SkillStore.PREVIEW_CHARS),
                charCount = humanBody.length,
                backupName = plan.backupName,
                blocked = plan.blocked,
                isDraft = isDraft,
                exists = existingBody != null,
                schemaCheckedCount = if (plan.blocked == null && schema.checked > 0) schema.checked else null,
                duplicateNote = duplicateNote,
                altName = duplicateNote?.let {
                    SkillHygiene.alternativeName(draft.name, existingLines.map { l -> l.name }.toSet() + draft.name)
                },
            ),
        )
    }

    /** 289①：卡上的重复说明一句（含新建建议名；null ＝ 没查出来）。从 [build] 拆出。 */
    private fun duplicateNoteOf(
        draft: SkillCardDraft,
        dup: SkillHygiene.Duplicate?,
        existingLines: List<SkillRules.IndexLine>,
    ): String? = dup?.let {
        val taken = existingLines.map { l -> l.name }.toSet() + draft.name
        SkillHygiene.duplicateGuidance(it, SkillHygiene.alternativeName(draft.name, taken))
    }

}

/**
 * 确认后的落盘（**唯一写口**）：编辑过名字／正文也从这里过同一套判据。
 *
 * 两个不能交给调用方的细节：
 * 1. 机器头按**盘上现有状态**重算（草稿保留 uses，转正过的不打回草稿）——用户在卡上改的是
 *    人话正文，状态不是他能编辑的字段；
 * 2. 同名覆盖仍由 `SkillStore.apply` 先备份再写；索引与正文一齐落，不留半条。
 */
internal object SkillCardWriter {

    /** 289①「新建」分支的名字校验结果（从 [save] 拆出）。 */
    private sealed interface NewNameCheck {
        data class Ok(val draft: SkillCardDraft) : NewNameCheck
        data class Reject(val reason: String) : NewNameCheck
    }

    /**
     * 新名字过三道闸：安全化不改写 → 自己不撞既有 → 场景也不近似。
     * 任何一道不过都给一句带「升级／新建」的人话，绝不静默换名落盘。
     */
    private fun checkNewName(
        draft: SkillCardDraft,
        asNewName: String,
        existingLines: List<SkillRules.IndexLine>,
    ): NewNameCheck {
        val safe = SkillStore.sanitizeName(asNewName)
            ?: return NewNameCheck.Reject("新名字不可用（只许字母／数字／中文／_／-）")
        if (safe != asNewName) {
            return NewNameCheck.Reject("新名字要被改写成「$safe」，请直接用后者")
        }
        val scenario = SkillStore.purposeOf(SkillStore.contentWithoutHeader(draft.body))
        SkillHygiene.findDuplicate(safe, scenario, existingLines)?.let { dup ->
            val alt = SkillHygiene.alternativeName(safe, existingLines.map { it.name }.toSet() + safe)
            return NewNameCheck.Reject("「$safe」也撞上了：" + SkillHygiene.duplicateGuidance(dup, alt))
        }
        return NewNameCheck.Ok(draft.copy(name = safe))
    }


    fun save(
        draft: SkillCardDraft,
        projectRoot: File,
        globalRoot: File,
        personaFiles: PersonaFiles,
        stamp: String,
        /** 289①：非 null ⇒ 用户在卡上选了「新建」——按这个名字另立一条，不碰既有技能。 */
        asNewName: String? = null,
        /** 两层既有索引行（重名/近似探查；不给就查不出——旧行为）。 */
        existingLines: List<SkillRules.IndexLine> = emptyList(),
    ): SkillWriteResult {
        if (draft.scope != SkillScope.PROJECT) {
            return SkillWriteResult.Rejected(SkillCardRules.GLOBAL_REFUSAL)
        }
        // 289①「新建」分支：换名后重名/近似判据必须**再过一遍**——
        // 建议名若也撞上（或用户手改的名字仍近似），拒绝并再给一次二选一，绝不静默。
        val effectiveDraft = if (asNewName == null) draft else when (
            val check = SkillCardWriter.checkNewName(draft, asNewName, existingLines)
        ) {
            is NewNameCheck.Ok -> check.draft
            is NewNameCheck.Reject -> return SkillWriteResult.Rejected(check.reason)
        }
        val humanBody = SkillStore.contentWithoutHeader(effectiveDraft.body)
        val probe = SkillStore.plan(
            proposal = SkillProposal(
                scope = SkillScope.PROJECT,
                name = effectiveDraft.name,
                body = humanBody,
                reason = effectiveDraft.reason,
                draft = true,
            ),
            projectRoot = projectRoot,
            globalRoot = globalRoot,
            backupStamp = stamp,
        )
        probe.blocked?.let { return SkillWriteResult.Rejected(it) }
        val existingBody = if (probe.file.isFile) {
            runCatching { probe.file.readText(Charsets.UTF_8) }.getOrNull()
        } else {
            null
        }
        val isDraft = existingBody?.let { SkillStore.statusOf(it) == "draft" } ?: true
        val uses = existingBody?.let { SkillStore.usesOf(it) } ?: 0
        val finalBody = if (isDraft) {
            SkillStore.draftHeader(status = "draft", uses = uses) + "\n" + humanBody
        } else {
            humanBody
        }
        // 正文已经带好头 ⇒ 提案 draft=false（withDraftHeader 原样写），索引行仍按真实状态算。
        val proposal = SkillProposal(
            scope = SkillScope.PROJECT,
            name = effectiveDraft.name,
            body = finalBody,
            reason = effectiveDraft.reason,
            draft = false,
        )
        val planned = SkillStore.plan(
            proposal = proposal.copy(draft = isDraft),
            projectRoot = projectRoot,
            globalRoot = globalRoot,
            backupStamp = stamp,
        )
        if (planned.file.absolutePath != probe.file.absolutePath) {
            return SkillWriteResult.Rejected("技能名「${effectiveDraft.name}」落不到它自己的目录，先改个名字")
        }
        return SkillStore.apply(
            plan = planned.copy(proposal = proposal),
            personaFiles = personaFiles,
            currentIndexText = personaFiles.read(PersonaLayer.C_PROJECT).orEmpty(),
        )
    }
}

/**
 * 控制器侧的最小宿主：持有一张**未确认**的技能卡，处理改字、确认、收起。
 *
 * 为什么单独一层：Compose 里只留渲染，接线处（引擎出卡、控制器保存）各自 2–3 行；
 * 本类不依赖 Android，JVM 里能用真临时目录把「不出卡不落盘／确认后文件＋索引都在」走完整条链。
 */
internal class SkillCardHost(
    private val projectRoot: () -> File,
    private val globalRoot: () -> File,
    private val personaFiles: () -> PersonaFiles,
    private val stamp: () -> String = { TimeFormats.fileStamp() },
) {

    var draft: SkillCardDraft? = null
        private set

    var view: SkillCardView? = null
        private set

    /**
     * 289①：卡上「二选一」的当前选择——false＝升级覆盖既有（默认保留旧口径），true＝按
     * [SkillCardView.altName] 另建一条。只在本卡有重复命中（duplicateNote 非 null）时有意义。
     */
    var preferNew: Boolean = false

    /** 两层既有索引行（重名/近似探查的输入；只读）。 */
    private fun existingLines(): List<SkillRules.IndexLine> {
        val pf = personaFiles()
        return SkillRules.parseIndexLines(pf.read(PersonaLayer.B_GLOBAL), MemoryScope.GLOBAL) +
            SkillRules.parseIndexLines(pf.read(PersonaLayer.C_PROJECT), MemoryScope.PROJECT)
    }

    /** 引擎／控制器出卡（未确认，不落盘）。全局请求在这里就被拒。 */
    fun offer(newDraft: SkillCardDraft): SkillCardBuild {
        val built = SkillCardRules.build(newDraft, projectRoot(), globalRoot(), stamp(), existingLines = existingLines())
        when (built) {
            is SkillCardBuild.Ready -> {
                draft = newDraft
                view = built.view
            }
            is SkillCardBuild.Refused -> dismiss()
        }
        return built
    }

    /** 卡上改名字／正文：重新算预览、备份名与阻断原因；被拒（全局）时保留原卡。 */
    fun edit(newDraft: SkillCardDraft): SkillCardBuild {
        val built = SkillCardRules.build(newDraft, projectRoot(), globalRoot(), stamp(), existingLines = existingLines())
        draft = newDraft
        if (built is SkillCardBuild.Ready) view = built.view
        return built
    }

    /** 点「记住它」：过 [SkillCardWriter]，成功才收卡；失败留着卡和原因，让用户改完再点。 */
    fun remember(): SkillWriteResult? {
        val d = draft ?: return null
        // 289①：用户选了「新建」⇒ 按建议名另立；没选（升级）⇒ 照旧覆盖（先备份）。
        val asNewName = if (preferNew) view?.altName else null
        val r = SkillCardWriter.save(
            d,
            projectRoot(),
            globalRoot(),
            personaFiles(),
            stamp(),
            asNewName = asNewName,
            existingLines = existingLines(),
        )
        if (r is SkillWriteResult.Written) dismiss()
        return r
    }

    /** 「先不记」／换轮：一个字节都不写。 */
    fun dismiss() {
        draft = null
        view = null
    }
}

/**
 * 技能卡（派工单 §1 那条「底部一个大按钮记住它」）。
 *
 * 只渲染：名字、用途一句话、正文预览（前 200 字，可展开成编辑框）、落点、备份名、字数、
 * 阻断红字。所有数值来自 [SkillCardView]，本函数不自己数次数、不自己切字符串。
 */
@Composable
internal fun BaopuSkillCard(
    view: SkillCardView,
    onEdit: (SkillCardDraft) -> Unit,
    onRemember: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** 289①：当前选择（false＝升级覆盖）；卡上没有重复命中时忽略。 */
    preferNew: Boolean = false,
    /** 289①：用户点了「升级／另建」任一边（宿主把它落到 [SkillCardHost.preferNew]）。 */
    onPreferNew: (Boolean) -> Unit = {},
) {
    var editingBody by remember { mutableStateOf(false) }
    val sourceLabel = when (view.draft.source) {
        SkillCardSource.USER_SAID -> stringResource(R.string.skill_card_source_user)
        SkillCardSource.REPEAT_SUCCESS -> stringResource(R.string.skill_card_source_repeat)
        SkillCardSource.REFLECTION -> stringResource(R.string.skill_card_source_reflection)
    }
    BaopuActionContainer(
        title = stringResource(R.string.skill_card_title),
        badge = sourceLabel,
        modifier = modifier,
    ) {
        Text(
            text = view.purpose.ifBlank { stringResource(R.string.skill_card_purpose_empty) },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = view.draft.name,
            onValueChange = { onEdit(view.draft.copy(name = it)) },
            label = { Text(stringResource(R.string.skill_card_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        if (editingBody) {
            OutlinedTextField(
                value = view.draft.body,
                onValueChange = { onEdit(view.draft.copy(body = it)) },
                label = { Text(stringResource(R.string.skill_card_body)) },
                minLines = 5,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = view.preview + if (view.charCount > view.preview.length) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.skill_card_chars, view.charCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { editingBody = !editingBody }) {
                Text(
                    stringResource(
                        if (editingBody) R.string.skill_card_body_done else R.string.skill_card_body_edit,
                    ),
                )
            }
        }
        Text(
            text = stringResource(R.string.skill_card_target, view.target),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = view.backupName?.let { stringResource(R.string.skill_card_backup, it) }
                ?: stringResource(R.string.skill_card_backup_none),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (view.isDraft) {
                stringResource(R.string.skill_card_draft_hint, SkillStore.PROMOTE_USES)
            } else {
                stringResource(R.string.skill_card_active_hint)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        view.schemaCheckedCount?.let { n ->
            Text(
                text = stringResource(R.string.skill_card_schema_pass, n),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        view.blocked?.let { reason ->
            Text(
                text = stringResource(R.string.skill_card_blocked, reason),
                style = MaterialTheme.typography.labelSmall,
                color = zhengdaoErrorText(),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // 289①：重名/近似命中 ⇒ 「升级／新建」二选一（不静默覆盖）。默认停在「升级」，
        // 但必须**看见**这句说明——选择本身不许被静掉。
        if (view.duplicateNote != null) {
            Text(
                text = view.duplicateNote.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !preferNew,
                    onClick = { onPreferNew(false) },
                    label = {
                        Text(
                            stringResource(R.string.skill_card_duplicate_upgrade),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
                view.altName?.let { alt ->
                    FilterChip(
                        selected = preferNew,
                        onClick = { onPreferNew(true) },
                        label = {
                            Text(
                                stringResource(R.string.skill_card_duplicate_new, alt),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onRemember,
                enabled = view.canRemember,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.skill_card_remember))
            }
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.skill_card_dismiss))
            }
        }
    }
}
