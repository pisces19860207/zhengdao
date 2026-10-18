// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第二十三批 A 段 · 技能可写（2026-10-18 用户口径：「**行，就按你说的给，skills他就可以自己总结了，
// 填、摸索风月AI这些网络平台的新规则和机制就更好了**」）。
//
// 在此之前技能**只能看和删**：正文全靠用户手动往目录里放文件（第十九批只做了「封卷时提炼一句索引」）。
//
// 五条铁律（都写成了判据，见 `SkillStoreTest`）：
//   1. **写入口只有这一个**（房型照 `PersonaFiles`）：不许别处再 fopen 一次 SKILL.md；
//   2. **写前必备份**：同名文件先存成 `<名>.SKILL.md.bak-<时间戳>`（与 write_file 的 `.baopu-bak` 同口径）；
//   3. **必过确认卡**：卡上要显示 技能名／落点（全局还是项目）／正文前 200 字／字数／备份文件名；
//   4. **索引与正文是一件事**：只写文件不写索引，模型等于看不见它（复用 `SkillRules.addIndexLine`）；
//   5. **不删**：删除永远手动（第十九批 §2.5）。本文件**故意没有 delete**。
//
// 落点沿用 `SkillRules.globalSkillDir` / `projectSkillDir`，**不另起目录**。
package com.example.zhengdao.baopu

import java.io.File

/** 技能落点：跟 App 走（全局）还是跟工作区走（项目）。 */
enum class SkillScope { GLOBAL, PROJECT }

/**
 * 一次「写技能」的提案（**用户点保存之前**它什么也没做**）。
 *
 * @param draft 试用期：新技能默认草稿（`status=draft`）。草稿**不进人设索引**，
 *   模型默认看不到，只在设置页可见并标「草稿」；用满 [SkillStore.PROMOTE_USES] 次才谈转正。
 */
data class SkillProposal(
    val scope: SkillScope,
    val name: String,
    val body: String,
    val reason: String,
    val draft: Boolean = true,
)

/** 写盘结果（要能直接给用户看一句话，失败不许静默）。 */
sealed class SkillWriteResult {
    data class Written(val file: File, val backup: String?, val indexUpdated: Boolean) : SkillWriteResult()
    data class Rejected(val reason: String) : SkillWriteResult()
}

/**
 * 一份**可审的写入计划**（确认卡上的每一行都从这儿取，不许 UI 自己再算一遍）。
 *
 * @param blocked 非 null ⇒ 这次写是被判据挡住的（名字非法／正文超长／索引超上限），
 *   确认卡应当直接显示它并禁用「保存」。
 */
internal data class SkillWritePlan(
    val proposal: SkillProposal,
    val file: File,
    val backupName: String?,
    val preview: String,
    val charCount: Int,
    val indexEntry: SkillRules.IndexLine,
    val blocked: String?,
)

/**
 * 技能的**唯一写入口**。
 *
 * 只做四件事：`plan`（纯判据，出确认卡内容）、`apply`（备份→写正文→写索引）、
 * `backupsOf`／`restore`（回滚到上一版）。**没有删除**。
 */
object SkillStore {

    /** 确认卡上展示的正文预览字数。 */
    const val PREVIEW_CHARS = 200

    /** 单个技能正文上限（防把整本书塞进一个 SKILL.md）。 */
    const val BODY_MAX_CHARS = 20_000

    /** 技能名上限（目录名）。 */
    const val NAME_MAX_CHARS = 32

    /** 草稿用满几次可以「转正」。 */
    const val PROMOTE_USES = 3

    /** 备份文件名后缀（`<名>.SKILL.md.bak-20261018-214500`）。 */
    const val BACKUP_MARK = ".SKILL.md.bak-"

    /**
     * 技能名安全化：只留 **字母／数字／中文／`_`／`-`**，其余一律剥掉；再截 [NAME_MAX_CHARS]。
     * 结果为空 ⇒ 返回 null（调用方必须拒绝，不许用「未命名」兜底写盘）。
     *
     * 为什么不用路径字符白名单式替换：技能名会变成目录名，`..`／分隔符／控制字符都该直接被
     * **拒绝**而不是被替换成一个看起来无害的名字——替换会让人以为写成了，其实写到了别处。
     */
    fun sanitizeName(raw: String): String? {
        val kept = raw.trim().filter { ch ->
            ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch.code in 0x4E00..0x9FFF
        }
        if (kept.isEmpty()) return null
        return if (kept.length <= NAME_MAX_CHARS) kept else kept.take(NAME_MAX_CHARS)
    }

    /**
     * 出计划（**纯函数**：只读传入的目录状态，不建目录、不写盘）。
     *
     * @param projectRoot 抱朴当前工作区（`BaopuWorkspace.hostDir`）
     * @param globalRoot 全局人设根（跟 App 走）
     * @param backupStamp 备份文件用的时间戳（由调用方给，便于测试）
     * @param indexCountAfterAdd 加这一条之后索引总行数（用来查 [SkillRules.INDEX_CAP]）
     */
    internal fun plan(
        proposal: SkillProposal,
        projectRoot: File,
        globalRoot: File,
        backupStamp: String,
        indexCountAfterAdd: Int = 0,
    ): SkillWritePlan {
        val safe = sanitizeName(proposal.name)
        val dir = when (proposal.scope) {
            SkillScope.PROJECT -> SkillRules.projectSkillDir(projectRoot)
            SkillScope.GLOBAL -> SkillRules.globalSkillDir(globalRoot)
        }
        val name = safe ?: "未命名"
        val file = File(File(dir, name), SkillRules.SKILL_FILE)
        val exists = file.isFile

        val blocked = when {
            safe == null -> "技能名不可用（只许字母／数字／中文／_／-，且不能为空）"
            proposal.body.isBlank() -> "正文是空的，写进去也没有用"
            proposal.body.length > BODY_MAX_CHARS ->
                "正文太长（${proposal.body.length} 字 > 上限 $BODY_MAX_CHARS）"
            !proposal.draft && indexCountAfterAdd > SkillRules.INDEX_CAP ->
                "索引已满（$indexCountAfterAdd > ${SkillRules.INDEX_CAP}），先删几条再加入"
            else -> null
        }

        // 草稿也进索引（2026-10-18 用户口径）：不进索引 ⇒ 明天看不见 ⇒ 重新摸索＝白烧 token。
        val draftMark = if (proposal.draft) "（草稿·未验证，用前先 read_file 核对）" else ""
        return SkillWritePlan(
            proposal = proposal.copy(name = name),
            file = file,
            backupName = if (exists) "$name$BACKUP_MARK$backupStamp" else null,
            preview = proposal.body.take(PREVIEW_CHARS),
            charCount = proposal.body.length,
            indexEntry = SkillRules.IndexLine(
                name = name,
                scenario = firstSentence(proposal.body) + draftMark,
                scope = scopeOf(proposal.scope),
            ),
            blocked = blocked,
        )
    }

    /**
     * 真落盘：**备份 → 写正文 → 写索引**（三步都有失败口径）。
     *
     * 索引这一半只在**非草稿**时做：草稿不进人设索引（模型默认看不到它）。
     * 索引写入仍然走 `PersonaFiles.applyProposal` —— 那是人设层唯一的合法写盘口，
     * 技能模块**不许**自己 fopen 一次人设文件。
     */
    internal fun apply(
        plan: SkillWritePlan,
        personaFiles: PersonaFiles,
        currentIndexText: String,
    ): SkillWriteResult {
        plan.blocked?.let { return SkillWriteResult.Rejected(it) }
        val file = plan.file
        return runCatching {
            file.parentFile?.mkdirs()
            // ① 备份（同名已在 ⇒ 先复制一份，绝不覆盖原件的最后一份）
            val backup = plan.backupName?.let { name ->
                val b = File(file.parentFile, name)
                file.copyTo(b, overwrite = true)
                b.name
            }
            // ② 正文（草稿要带 status 头，转正时再改）
            file.writeText(withDraftHeader(plan.proposal), Charsets.UTF_8)
            // ③ 索引（草稿不写；正式走人设层唯一写盘口）
            // 草稿同样写索引（见 plan 里 draftMark 的理由）
            val indexUpdated = if (false) {
                false
            } else {
                val edit = SkillRules.addIndexLine(currentIndexText, plan.indexEntry)
                val layer = when (plan.proposal.scope) {
                    SkillScope.PROJECT -> PersonaLayer.C_PROJECT
                    SkillScope.GLOBAL -> PersonaLayer.B_GLOBAL
                }
                when (edit) {
                    is SkillRules.EditResult.Ok -> personaFiles.applyProposal(
                        PersonaProposal(
                            layer = layer,
                            current = currentIndexText,
                            proposed = edit.newText,
                            reason = "新增技能索引：${plan.proposal.name}",
                        ),
                    )
                    else -> false
                }
            }
            SkillWriteResult.Written(file = file, backup = backup, indexUpdated = indexUpdated)
        }.getOrElse { SkillWriteResult.Rejected("写盘失败：${it.javaClass.simpleName}") }
    }

    /**
     * 删技能（2026-10-18 用户口径：技能也要能删）。
     *
     * ⚠️ 与「删除永远手动」的关系：这里**不是硬删** —— 把 `SKILL.md` 改名成
     * `<名>.SKILL.md.deleted-<时间戳>` 留在原地（原件一个字节不动），索引行由调用方一并去掉。
     * 真要彻底删，用户在文件管理器里删那个 `.deleted-*` 文件。
     * 硬红线只有一条：**`资料库/原始/` 不许碰**（技能目录不在那儿）。
     */
    fun archive(file: File, stamp: String): SkillWriteResult {
        if (!file.isFile) return SkillWriteResult.Rejected("这个技能没有正文文件，没什么可删的")
        return runCatching {
            val gone = File(file.parentFile, "${file.name}.deleted-$stamp")
            file.renameTo(gone)
            SkillWriteResult.Written(file = gone, backup = null, indexUpdated = false)
        }.getOrElse { SkillWriteResult.Rejected("改名失败：${it.javaClass.simpleName}") }
    }
    /** 某个技能目录下的备份（新→旧）。回滚按钮用它。 */
    fun backupsOf(file: File): List<File> {
        val dir = file.parentFile ?: return emptyList()
        val stem = file.nameWithoutExtension // "SKILL"
        return dir.listFiles { f -> f.isFile && f.name.contains(BACKUP_MARK) }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }

    /** 恢复到上一版（最近一份备份）。恢复本身也是一次写盘，所以同样先备份当前正文。 */
    fun restore(file: File, backupStamp: String): SkillWriteResult {
        val latest = backupsOf(file).firstOrNull()
            ?: return SkillWriteResult.Rejected("这个技能还没有备份，没有可恢复的版本")
        return runCatching {
            val cur = File(file.parentFile, "${file.nameWithoutExtension}$BACKUP_MARK$backupStamp")
            if (file.isFile) file.copyTo(cur, overwrite = true)
            latest.copyTo(file, overwrite = true)
            SkillWriteResult.Written(file = file, backup = cur.name, indexUpdated = false)
        }.getOrElse { SkillWriteResult.Rejected("恢复失败：${it.javaClass.simpleName}") }
    }

    /**
     * C 段骨架：**平台机制**类技能的固定四段（第二十三批 §2.4）。
     *
     * 为什么固定四段：用户要的是「抱朴自己摸索风月AI 这类平台的填写方式，然后写成技能」。
     * 摸索的产物如果没有固定形状，下次它自己都读不懂自己上次写了什么。四段是：
     * ① 平台/页面（URL ＋ 抓取时间）② 字段清单（字段名＝页面上的字面文案／类型／必填否／样例）
     * ③ 三套格式的认脸与差异 ④ 已知坑。
     *
     * **只立骨架**：完整闭环依赖读页（刀 4）＋视觉通道，本批不假装已经能自动摸索。
     */
    const val PLATFORM_SKILL_TEMPLATE: String = """# 平台机制：<平台名>
<!-- 由抱朴实测填写；没实测的段落留空并写明「未实测」，不许猜 -->

## ① 平台 / 页面（哪个网站、哪个页面）
- URL：
- 抓取时间：
- 页面语言 / 面向群体（男性向 or 女性向，按题材定）：

## ② 字段清单（字段名用页面上的字面文案）
| 字段 | 类型 | 必填 | 取值样例 | 备注 |
| --- | --- | --- | --- | --- |
|  |  |  |  |  |

## ③ 格式认脸与差异
- 顶层是 `world_book` 数组、条目用 `value` / `enable` / `sort` 的那类平台
- SillyTavern：`entries` 是字典，键为 uid；用 `content` / `disable`（**反义**）/ `order`
- Tavo：`entries` 是数组，元素含 `keywords` / `content`
- 认脸顺序：先看顶层键 → 再看 `entries` 是数组还是字典 → 最后才看扩展名

## ④ 已知坑
- 
"""

    // ── 草稿头与试用期（纯函数，JVM 可测） ──────────────────────────────────

    /** `<!-- status=draft uses=0 -->` 这一行（**纯注释**，不改 SKILL.md 原有格式）。 */
    fun draftHeader(status: String = "draft", uses: Int = 0): String =
        "<!-- status=$status uses=$uses -->"

    /** 写正文时把草稿头放在第一行；正式技能不加头（保持文件干净）。 */
    internal fun withDraftHeader(p: SkillProposal): String =
        if (p.draft) draftHeader() + "\n" + p.body else p.body

    /** 读头里的 status（缺头视为 `active`）。 */
    fun statusOf(body: String): String =
        Regex("""<!--\s*status=(\w+)""").find(body)?.groupValues?.get(1) ?: "active"

    /** 读头里的 uses（缺省 0）。 */
    fun usesOf(body: String): Int =
        Regex("""uses=(\d+)""").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** 用一次 ⇒ 返回替换后的正文（uses+1）；草稿用满 [PROMOTE_USES] 次时 UI 才出「转正」。 */
    fun bumpUses(body: String): String {
        val n = usesOf(body) + 1
        val status = statusOf(body)
        return if (Regex("""<!--\s*status=\w+\s*uses=\d+""").containsMatchIn(body)) {
            Regex("""(<!--\s*status=\w+\s*uses=)(\d+)""").replace(body) { m -> m.groupValues[1] + n }
        } else {
            draftHeader(status, n) + "\n" + body
        }
    }

    /** 可以转正了？草稿且用满次数。 */
    fun canPromote(body: String): Boolean = statusOf(body) == "draft" && usesOf(body) >= PROMOTE_USES

    private fun scopeOf(scope: SkillScope): MemoryScope = when (scope) {
        SkillScope.PROJECT -> MemoryScope.PROJECT
        SkillScope.GLOBAL -> MemoryScope.GLOBAL
    }

    /** 索引行的「场景」取正文第一句（截断到 24 字，避免索引被一句话撑爆）。 */
    private fun firstSentence(body: String): String {
        val line = body.lineSequence().map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("<!--") }
            .orEmpty()
            .removePrefix("#").trim()
        return if (line.length <= 24) line else line.take(24) + "…"
    }
}
