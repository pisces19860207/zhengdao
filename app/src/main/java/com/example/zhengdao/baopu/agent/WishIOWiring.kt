// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十六批 · 许愿接线 ②三类取数 IO 接进沙盒。判据一句不改——本件只把
// WishPorts 的六个口接到既有真零件上：
//   · CARD：`card_build` 两次 build 全在军师副本（RehearsalSandbox，cacheDir/rehearsal/rh-*）
//     里跑，`card_inspect` 同口径（CardFormat 认脸＋CardCheck 体检）——真项目只读，成品目录
//     前后各称一次 hash；
//   · CONTENT：技能取数读 `assets/baopu/skills/微头条流程`（读件由装配层注入），写只落
//     `许愿/<wishId>/草稿/`，`产物/` 只读（台账查重＋前后 hash）；
//   · NOVEL：项目副本＝军师 stage 进副本的那份，草稿章写进**副本**的 `正文/`，
//     真书目录只读（前后 hash）；旧章字节不变在副本里逐个对。
// 沙盒复用 RehearsalSandbox（358 批），不另造第二套；自检读数交 WishSelfCheck 的纯判据。
//
// 为什么在 `agent/` 不在 `wish/`：510 的 WishSideEffectGateTest 按源码钉死「许愿包除技能
// 写口外没有任何生产写口」——本件是那道闸之后**登记进来的**新写口（L6 白名单
// `agent/WishIOWiring.kt`，理由见那一格），照 296/358 的做法把碰盘的活儿留在被扫的域里。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.ArtifactDeleteRules
import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.SkillRules
import com.example.zhengdao.baopu.SkillStore
import com.example.zhengdao.baopu.card.CardAssetParser
import com.example.zhengdao.baopu.card.CardBuild
import com.example.zhengdao.baopu.card.CardCheck
import com.example.zhengdao.baopu.card.CardFormat
import com.example.zhengdao.baopu.wish.CardCheckReading
import com.example.zhengdao.baopu.wish.ContentCheckReading
import com.example.zhengdao.baopu.wish.NovelCheckReading
import com.example.zhengdao.baopu.wish.RehearsalReading
import com.example.zhengdao.baopu.wish.SelfCheckVerdict
import com.example.zhengdao.baopu.wish.SkillTrialReading
import com.example.zhengdao.baopu.wish.WishContext
import com.example.zhengdao.baopu.wish.WishLedger
import com.example.zhengdao.baopu.wish.WishPorts
import com.example.zhengdao.baopu.wish.WishProduct
import com.example.zhengdao.baopu.wish.WishSelfCheck
import com.example.zhengdao.baopu.wish.WishWiring
import com.example.zhengdao.ui.baopu.BaopuLongformPolicy
import com.example.zhengdao.ui.baopu.BaopuLongformStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/** 三类取数 IO 的沙盒接线（判据在 WishSelfCheck/WishRedLine；这里只有 IO 与读数提取）。 */
internal object WishIOWiring {

    /** 草稿落点：`许愿/<wishId>/草稿/`（跟工作区走；转正才离开这里）。 */
    fun draftDir(workspace: File, wishId: String): File =
        File(File(workspace, WishLedger.DIR_NAME), "$wishId/草稿")

    /** 各类草稿的固定名（重试覆盖同一份：账上有版本，盘上只有当前稿）。 */
    private const val CARD_DRAFT = "母档.json"
    private const val CONTENT_DRAFT = "草稿卡.md"
    private const val NOVEL_DRAFT = "草稿章.md"

    /** 内容稿字数区间（机械自检的读数口径；骨架稿按它生成）。 */
    private const val CONTENT_MIN_WORDS = 50
    private const val CONTENT_MAX_WORDS = 2000

    /** 草稿章字数下限（上限照 book.json 的 chapterTargetWords 两倍宽）。 */
    private const val NOVEL_MIN_WORDS = 200

    /** 卡片转正的真落点（CardTools 的缺省 out_dir 同名）。 */
    private const val CARD_REAL_DIR = "成品"

    /** 试跑读数的暂存（rehearse 提取、collect 消费；wishId ⇒ WishSelfCheck 的读数件）。 */
    private val readings = ConcurrentHashMap<String, Any>()

    /** 试跑过程备注（collect 的 evidence 用）。 */
    private val notes = ConcurrentHashMap<String, String>()

    /** writeDraft 与 rehearse 之间的正文交接（同进程内存；账上另有 evidence）。 */
    private val bodies = ConcurrentHashMap<String, String>()

    /**
     * 全套真端口（装配层一次成形）。[readSkillAsset] 给「技能名 ⇒ 正文」：
     * 生产接 `Context.assets`，测试接仓库文件——本件不认识 Context。
     */
    fun ports(
        workspace: File,
        persona: PersonaFiles,
        cacheRoot: File,
        readSkillAsset: (String) -> String?,
        stamp: () -> String,
    ): WishPorts {
        val skillDraft = WishWiring.skillWriteDraft(
            workspace, skillGlobalRoot(persona, workspace), persona, stamp(),
        )
        return WishPorts(
            generateBody = { ctx -> generateBody(ctx) },
            writeDraft = { ctx, body -> writeDraft(workspace, persona, skillDraft, ctx, body) },
            runRehearsal = { ctx -> rehearse(workspace, cacheRoot, readSkillAsset, ctx) },
            collectCheck = { ctx -> collect(ctx) },
            doPromote = { ctx -> promote(workspace, persona, ctx, stamp()) },
            doRollback = { ctx, reason -> rollback(workspace, persona, ctx, reason, stamp()) },
        )
    }

    /** 技能全局根：与 SkillTools 同一条推导（人设全局文件的父目录；退回工作区根）。 */
    private fun skillGlobalRoot(persona: PersonaFiles, workspace: File): File =
        File(persona.globalFile().parentFile ?: workspace, "")

    // ── ③ 草稿生成（确定性骨架；内容质量不在本批判据内，报告里写明） ─────────

    private fun generateBody(ctx: WishContext): String = when (ctx.product) {
        WishProduct.SKILL -> skillBody(ctx)
        WishProduct.CARD -> cardSpecJson(ctx)
        WishProduct.CONTENT -> contentDraft(ctx)
        WishProduct.NOVEL -> novelChapter(ctx)
    }

    private fun goalOf(ctx: WishContext): String =
        ctx.spec?.goal?.trim().orEmpty().ifEmpty { ctx.wishText.trim() }

    /** 技能骨架：纯自然语言步骤（SkillWritePolicy 的免批尺：无代码块/外链/禁用词，≤2KB）。 */
    private fun skillBody(ctx: WishContext): String =
        "目标：${goalOf(ctx)}\n" +
            "步骤：\n" +
            "1. 收齐与目标有关的材料，缺什么先记下来再动手。\n" +
            "2. 按既定格式整理成稿，一次只改一处，改前留备份。\n" +
            "3. 写完重读一遍，与目标对上了才算完；对不上就回上一步。\n"

    /** 卡片母档骨架：format/name/persona/keywords 齐全，keywords 全部 ≥2 字（积分刺客尺）。 */
    private fun cardSpecJson(ctx: WishContext): String {
        val goal = goalOf(ctx)
        val name = WishWiring.skillName(ctx.wishText) ?: "许愿卡"
        val keywords = (listOf(name, "许愿") + goal.split(Regex("[^\\p{L}\\p{N}]+")))
            .filter { it.length >= 2 }
            .distinct()
            .take(3)
            .ifEmpty { listOf("许愿流水线") }
        return JSONObject()
            .put("format", CardBuild.FORMAT_WEB)
            .put("name", name)
            .put("persona", goal)
            .put("world", JSONArray().put(goal))
            .put("keywords", JSONArray(keywords))
            .put("style", "")
            .put("html", "")
            .put("css", "")
            .toString(2)
    }

    /** 内容草稿卡骨架：依据行非空（465 的形状格）＋字数落在区间内。 */
    private fun contentDraft(ctx: WishContext): String {
        val goal = goalOf(ctx)
        val base = "标题：许愿草稿-${WishWiring.skillName(goal) ?: "无题"}\n" +
            "依据：人设第 1 条（许愿流水线试跑样例，转正前不进台账）\n" +
            "$goal\n\n" +
            "（许愿草稿卡：灰度期只落草稿。开头一句点题；中段给做法；结尾一句收束。）\n"
        return padTo(base, CONTENT_MIN_WORDS) { "本段为骨架占位，按微头条流程的口径补写。$goal。" }
    }

    /** 草稿章骨架：只提设定表既有人物，不用引号（不造新名），字数过下限。 */
    private fun novelChapter(ctx: WishContext): String {
        val goal = goalOf(ctx)
        val base = "（许愿草稿章 v${ctx.draftVersion + 1}）本章按设定表既有人物推进，不引入新角色。$goal\n"
        return padTo(base, NOVEL_MIN_WORDS) { "本段为骨架占位：按大纲推进一小步，收在可以续写的地方。" }
    }

    /** 骨架不够长就按句补（确定性：同一输入同一长度）。 */
    private fun padTo(base: String, minWords: Int, filler: () -> String): String {
        val out = StringBuilder(base)
        while (NovelProjectStore.wordCount(out.toString()) < minWords) out.append(filler())
        return out.toString()
    }

    // ── ③ 落草稿（写只进许愿自己的草稿目录） ─────────────────────────────

    private fun writeDraft(
        workspace: File,
        persona: PersonaFiles,
        skillDraft: (WishContext, String) -> Boolean,
        ctx: WishContext,
        body: String,
    ): Boolean {
        bodies[ctx.wishId] = body
        return when (ctx.product) {
            WishProduct.SKILL -> skillDraft(ctx, body)
            WishProduct.CARD -> writeDraftFile(workspace, ctx, CARD_DRAFT, body)
            WishProduct.CONTENT -> writeDraftFile(workspace, ctx, CONTENT_DRAFT, body)
            WishProduct.NOVEL -> writeDraftFile(workspace, ctx, NOVEL_DRAFT, body)
        }
    }

    private fun writeDraftFile(workspace: File, ctx: WishContext, name: String, body: String): Boolean =
        runCatching {
            val f = File(draftDir(workspace, ctx.wishId), name)
            f.parentFile?.mkdirs()
            f.writeText(body, Charsets.UTF_8)
            true
        }.getOrDefault(false)

    // ── ④ 试跑（沙盒；真文件前后各称一次 hash） ──────────────────────────

    private fun rehearse(
        workspace: File,
        cacheRoot: File,
        readSkillAsset: (String) -> String?,
        ctx: WishContext,
    ): RehearsalReading = when (ctx.product) {
        WishProduct.SKILL -> rehearseSkill(workspace, ctx)
        WishProduct.CARD -> rehearseCard(workspace, cacheRoot, ctx)
        WishProduct.CONTENT -> rehearseContent(workspace, readSkillAsset, ctx)
        WishProduct.NOVEL -> rehearseNovel(workspace, cacheRoot, ctx)
    }

    private fun quotaOf(cacheRoot: File): Long =
        RehearsalRules.quotaBytes(runCatching { cacheRoot.usableSpace }.getOrDefault(64L * 1024 * 1024))

    /** 技能试跑：只读草稿，不写盘；真盘=技能目录前后 hash。 */
    private fun rehearseSkill(workspace: File, ctx: WishContext): RehearsalReading {
        val name = WishWiring.skillName(ctx.wishText)
            ?: return stash(ctx, RehearsalReading(false, true, "许愿原话凑不出合法技能名"))
        val skillDir = SkillRules.projectSkillDir(workspace)
        val before = hashTree(skillDir)
        val file = File(skillDir, "$name/${SkillRules.SKILL_FILE}")
        if (!file.isFile) return stash(ctx, RehearsalReading(false, true, "草稿没落盘：$name"))
        val text = file.readText()
        val body = bodies[ctx.wishId].orEmpty()
        val sample = body.take(40).isNotEmpty() && text.contains(body.trim().take(40))
        val after = hashTree(skillDir)
        val untouched = before == after
        readings[ctx.wishId] = SkillTrialReading(
            allStepsOk = SkillStore.statusOf(text) == "draft",
            realUntouched = untouched,
            sampleMatch = sample,
        )
        return stash(ctx, RehearsalReading(sample && untouched, untouched, "技能草稿试读：$name"))
    }

    /**
     * 卡片试跑：两次 `card_build` 全在军师副本里（试跑A／试跑B），认脸＋体检读副本产物；
     * 真项目只读——`成品/` 前后 hash。
     */
    private fun rehearseCard(workspace: File, cacheRoot: File, ctx: WishContext): RehearsalReading {
        val draft = File(draftDir(workspace, ctx.wishId), CARD_DRAFT)
        if (!draft.isFile) return stash(ctx, RehearsalReading(false, true, "母档草稿不存在"))
        val real = File(workspace, CARD_REAL_DIR)
        val before = hashTree(real)
        val sandbox = RehearsalSandbox(cacheRoot, quotaOf(cacheRoot))
        val reading = runCatching { trialCard(sandbox, ctx.wishId, draft.readText()) }
            .getOrElse { RehearsalReading(false, true, "试跑异常：${it.message}") }
        sandbox.cleanup()
        return stash(ctx, reading.copy(realUntouched = reading.realUntouched && before == hashTree(real)))
    }

    /** 副本内两次 build＋认脸＋体检（任何一格不过 ⇒ ok=false；读数照实交）。 */
    private fun trialCard(sandbox: RehearsalSandbox, wishId: String, specJson: String): RehearsalReading {
        val open = sandbox.open(wishId)
        if (open != null) return RehearsalReading(false, true, "副本开不起来：$open")
        val root = sandbox.copyRoot() ?: return RehearsalReading(false, true, "副本根没开")
        val spec = try {
            CardBuild.parseSpec(specJson)
        } catch (e: CardBuild.BuildError) {
            return RehearsalReading(false, true, "母档不合法：${e.message}")
        }
        val a = CardBuild.build(spec, root, "试跑A")
        val b = CardBuild.build(spec, root, "试跑B")
        val deterministic = shaNames(a.files) == shaNames(b.files)
        val worldbook = a.files.firstOrNull { it.name == "world_book.json" } ?: a.files.first()
        val bytes = worldbook.readBytes()
        val verdict = CardFormat.recognize(bytes)
        val errors = CardAssetParser.parse(bytes)?.let { CardCheck.check(it).errors } ?: emptyList()
        readings[wishId] = CardCheckReading(
            formatRecognized = verdict.isKnown,
            formValid = errors.isEmpty(),
            deterministic = deterministic,
        )
        val ok = deterministic && verdict.isKnown && errors.isEmpty()
        return RehearsalReading(ok, true, "副本两次 build（试跑A／试跑B）；认脸=${verdict.format.name} 体检错=${errors.size}")
    }

    /** 内容试跑：技能取数读资产；`产物/` 只读（查重＋前后 hash）；稿只认草稿目录那份。 */
    private fun rehearseContent(
        workspace: File,
        readSkillAsset: (String) -> String?,
        ctx: WishContext,
    ): RehearsalReading {
        val draft = File(draftDir(workspace, ctx.wishId), CONTENT_DRAFT)
        if (!draft.isFile) return stash(ctx, RehearsalReading(false, true, "草稿卡不存在"))
        val skill = readSkillAsset(SKILL_ASSET_NAME)
            ?: return stash(ctx, RehearsalReading(false, true, "技能资产读不到：$SKILL_ASSET_NAME"))
        val text = draft.readText()
        val real = productDir(workspace)
        val before = hashTree(real)
        val cite = citationCount(text)
        val words = NovelProjectStore.wordCount(text)
        val dups = ledgerDuplicates(workspace, text)
        val after = hashTree(real)
        readings[ctx.wishId] = ContentCheckReading(
            citationCount = cite, wordCount = words,
            minWords = CONTENT_MIN_WORDS, maxWords = CONTENT_MAX_WORDS, duplicateCount = dups,
        )
        val untouched = before == after
        val ok = skill.isNotBlank() && cite > 0 && words in CONTENT_MIN_WORDS..CONTENT_MAX_WORDS && dups == 0
        return stash(
            ctx,
            RehearsalReading(
                ok, untouched,
                "技能 ${skill.length} 字；依据 $cite 条；字数 $words；台账查重命中 $dups",
            ),
        )
    }

    /** 小说试跑：项目副本＝军师 stage 的那份；草稿章写进副本的 `正文/`；真书目录只读。 */
    private fun rehearseNovel(workspace: File, cacheRoot: File, ctx: WishContext): RehearsalReading {
        val book = NovelProjectStore.listProjects(workspace).firstOrNull()
            ?: return stash(ctx, RehearsalReading(false, true, "还没有小说项目：先建一个"))
        val draft = File(draftDir(workspace, ctx.wishId), NOVEL_DRAFT)
        if (!draft.isFile) return stash(ctx, RehearsalReading(false, true, "草稿章不存在"))
        val loaded = NovelProjectStore.load(workspace, "$NOVEL_ROOT_DIR/${book.name}")
            ?: return stash(ctx, RehearsalReading(false, true, "项目读不了：${book.name}"))
        val before = hashTree(book)
        val sandbox = RehearsalSandbox(cacheRoot, quotaOf(cacheRoot))
        val reading = runCatching {
            trialNovel(sandbox, ctx.wishId, workspace, book, loaded.second, draft.readText())
        }.getOrElse { RehearsalReading(false, true, "试跑异常：${it.message}") }
        sandbox.cleanup()
        return stash(ctx, reading.copy(realUntouched = reading.realUntouched && before == hashTree(book)))
    }

    /** 副本内落草稿章＋四格读数（三级齐／人物名／旧章不变／字数与目录合规）。 */
    private fun trialNovel(
        sandbox: RehearsalSandbox,
        wishId: String,
        workspace: File,
        book: File,
        meta: NovelMeta,
        chapter: String,
    ): RehearsalReading {
        val open = sandbox.open(wishId)
        if (open != null) return RehearsalReading(false, true, "副本开不起来：$open")
        val rel = "$NOVEL_ROOT_DIR/${book.name}"
        val staged = sandbox.stage(workspace, rel)
        if (staged.stage != RehearsalSandbox.Stage.COPIED) {
            return RehearsalReading(false, true, "副本没搬成：${staged.note}")
        }
        val copy = File(sandbox.copyRoot(), rel)
        val bodyDir = File(copy, NOVEL_BODY_DIR)
        val newChapter = File(bodyDir, "许愿草稿章.md")
        newChapter.parentFile?.mkdirs()
        newChapter.writeText(chapter, Charsets.UTF_8)
        val oldChanged = oldChaptersChanged(File(book, NOVEL_BODY_DIR), bodyDir)
        val newNames = quotedNames(chapter).filter { q -> meta.characters.none { it.first == q } }
        val words = NovelProjectStore.wordCount(chapter)
        val maxWords = (meta.chapterTargetWords * 2).coerceAtLeast(NOVEL_MIN_WORDS * 2)
        val conventions = File(copy, NOVEL_META_FILE).isFile && bodyDir.isDirectory
        readings[wishId] = NovelCheckReading(
            hasOutline = meta.outline.isNotBlank(),
            hasChapters = NovelProjectStore.chapters(copy).isNotEmpty(),
            hasBody = words > 0,
            newCharacterNames = newNames,
            oldChapterChanged = oldChanged,
            chapterWordsInRange = words in NOVEL_MIN_WORDS..maxWords,
            dirConventionsOk = conventions,
        )
        val ok = !oldChanged && newNames.isEmpty() && conventions && words in NOVEL_MIN_WORDS..maxWords
        return RehearsalReading(ok, true, "副本落章：${newChapter.name}；字数 $words；新人物 ${newNames.size}")
    }

    /** 旧章只许追加：真书每章在副本里都要找得到、字节一致。 */
    private fun oldChaptersChanged(realBody: File, copyBody: File): Boolean =
        realBody.listFiles { f -> f.isFile }.orEmpty().any { real ->
            val inCopy = File(copyBody, real.name)
            !inCopy.isFile || sha256(inCopy) != sha256(real)
        }

    // ── ⑤ 机械自检（读数从试跑暂存取；纯判据在 WishSelfCheck） ─────────────

    private fun collect(ctx: WishContext): SelfCheckVerdict {
        val note = notes[ctx.wishId] ?: ""
        return when (val r = readings[ctx.wishId]) {
            is SkillTrialReading -> WishSelfCheck.check(WishProduct.SKILL, skill = r)
            is CardCheckReading -> WishSelfCheck.check(WishProduct.CARD, card = r)
            is ContentCheckReading -> WishSelfCheck.check(WishProduct.CONTENT, content = r)
            is NovelCheckReading -> WishSelfCheck.check(WishProduct.NOVEL, novel = r)
            else -> SelfCheckVerdict.Fail("试跑读数缺失（$note）")
        }
    }

    // ── ⑥ 转正／回滚 ────────────────────────────────────────────────────

    private fun promote(
        workspace: File,
        persona: PersonaFiles,
        ctx: WishContext,
        stamp: String,
    ): Boolean = runCatching {
        when (ctx.product) {
            WishProduct.SKILL -> WishWiring.skillPromote(workspace, persona, stamp)(ctx)
            WishProduct.CARD -> promoteCard(workspace, ctx)
            WishProduct.CONTENT -> promoteContent(workspace, ctx)
            WishProduct.NOVEL -> promoteNovel(workspace, ctx)
        }
    }.getOrDefault(false)

    /** 卡片转正：真 build 进 `成品/<name>/`（CardBuild 自带不覆盖闸：已存在 ⇒ 整次拒绝）。 */
    private fun promoteCard(workspace: File, ctx: WishContext): Boolean {
        val draft = File(draftDir(workspace, ctx.wishId), CARD_DRAFT)
        if (!draft.isFile) return false
        return try {
            CardBuild.build(CardBuild.parseSpec(draft.readText()), workspace).files.isNotEmpty()
        } catch (e: CardBuild.BuildError) {
            false
        }
    }

    /**
     * 内容转正：稿进 `产物/`（179 命名＋序号）＋业务台账一行（appendLedger 的多写者协议）；
     * 机器账一行由流水线自己在 PROMOTED 落（`许愿/_流水线.jsonl`）——台账两处就是这一对。
     */
    private fun promoteContent(workspace: File, ctx: WishContext): Boolean {
        val draft = File(draftDir(workspace, ctx.wishId), CONTENT_DRAFT)
        if (!draft.isFile) return false
        val text = draft.readText()
        val dir = productDir(workspace)
        dir.mkdirs()
        val cal = Calendar.getInstance()
        val name = deliveredFileName(dir, cal, titleOf(text))
        File(dir, name).writeText(text, Charsets.UTF_8)
        val ledgerMsg = BaopuLongformStore.appendLedger(
            root = BaopuLongformStore.vaultRootUnder(workspace),
            productDir = dir, now = cal,
            files = listOf(name to NovelProjectStore.wordCount(text)),
        )
        return ledgerMsg == null
    }

    /** 179 命名：`YYYYMMDD_许愿_N_slug.md`（赛道＝许愿；序号照当日已有文件数）。 */
    private fun deliveredFileName(dir: File, cal: Calendar, title: String): String {
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val existing = dir.listFiles { f -> f.isFile }.orEmpty().map { it.name }.toSet()
        val base = BaopuLongformPolicy.trackFileName(y, m, d, TRACK, BaopuLongformPolicy.nextSeq(existing, y, m, d, TRACK), title, "md")
        return BaopuLongformPolicy.nextFileName(existing, base)
    }

    /** 小说转正：草稿章进真书目录的 `正文/`（同名即拒——续写只追加）。 */
    private fun promoteNovel(workspace: File, ctx: WishContext): Boolean {
        val book = NovelProjectStore.listProjects(workspace).firstOrNull() ?: return false
        val draft = File(draftDir(workspace, ctx.wishId), NOVEL_DRAFT)
        if (!draft.isFile) return false
        val bodyDir = File(NovelProjectStore.projectDir(workspace, book.name), NOVEL_BODY_DIR)
        bodyDir.mkdirs()
        val seq = "%03d".format(bodyDir.listFiles { f -> f.isFile }.orEmpty().size + 1)
        val target = File(bodyDir, "第${seq}章-许愿.md")
        if (target.exists()) return false
        draft.copyTo(target, overwrite = false)
        return true
    }

    /** 回滚：技能走 510 的撤销口；三类把草稿改名 `.deleted-<stamp>`（归档不真删）。 */
    private fun rollback(
        workspace: File,
        persona: PersonaFiles,
        ctx: WishContext,
        reason: String,
        stamp: String,
    ): Boolean {
        if (ctx.product == WishProduct.SKILL) {
            return WishWiring.skillRollback(workspace, persona, stamp)(ctx, reason)
        }
        val dir = draftDir(workspace, ctx.wishId)
        if (!dir.isDirectory) return true
        val drafts = listOf(CARD_DRAFT, CONTENT_DRAFT, NOVEL_DRAFT).map { File(dir, it) }
            .filter { it.isFile }
        if (drafts.isEmpty()) return true
        return drafts.all { runCatching { it.renameTo(File(dir, "${it.name}.deleted-$stamp")) }.getOrDefault(false) }
    }

    // ── 小件 ────────────────────────────────────────────────────────────

    private const val SKILL_ASSET_NAME = "微头条流程"
    private const val TRACK = "许愿"

    /** 产物目录（`<工作区>/产物`；与 GenerateImage.artifactDir 同一处真源的字面）。 */
    private fun productDir(workspace: File): File = File(workspace, "产物")

    private fun stash(ctx: WishContext, r: RehearsalReading): RehearsalReading {
        notes[ctx.wishId] = r.note
        return r
    }

    private fun titleOf(text: String): String =
        text.lineSequence().firstOrNull { it.startsWith("标题：") }
            ?.removePrefix("标题：")?.trim().orEmpty().ifEmpty { "许愿稿" }

    /** 依据行的条数（`依据：a、b、c` ⇒ 3；缺行 ⇒ 0）。 */
    private fun citationCount(text: String): Int =
        text.lineSequence().firstOrNull { it.startsWith("依据：") }
            ?.removePrefix("依据：")?.split("、", "，", ",")?.count { it.isNotBlank() } ?: 0

    /** 台账查重：草稿标题的 slug 在既有台账行名里出现几次。 */
    private fun ledgerDuplicates(workspace: File, draft: String): Int {
        val productDir = productDir(workspace)
        val ledger = BaopuLongformPolicy.pickLedger(BaopuLongformStore.vaultRootUnder(workspace), productDir)
        val rows = ArtifactDeleteRules.parseRows(runCatching { ledger.readText() }.getOrNull())
        val slug = BaopuLongformPolicy.slug(titleOf(draft))
        if (slug.isEmpty()) return 0
        return rows.count { it.second.name.contains(slug) }
    }

    /** 引号人名提取（「X」；草稿章骨架不用引号 ⇒ 空表）。 */
    private fun quotedNames(text: String): List<String> =
        Regex("「([^」]{1,12})」").findAll(text).map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }.distinct().toList()

    private fun shaNames(files: List<File>): String =
        files.sortedBy { it.name }.joinToString("|") { "${it.name}:${sha256(it)}" }

    internal fun sha256(f: File): String = runCatching {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                d.update(buf, 0, n)
            }
        }
        d.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    /** 目录指纹（相对路径＋摘要；目录不存在给「absent」——存在性与内容一起进 hash）。 */
    internal fun hashTree(dir: File): String {
        if (!dir.exists()) return "absent"
        val root = dir.absoluteFile
        val text = dir.walkTopDown().filter { it.isFile }
            .sortedBy { it.absolutePath }
            .joinToString("|") { f -> "${f.toRelativeString(root)}:${f.length()}:${sha256(f)}" }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
