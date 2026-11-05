// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单 2026-11-05 第四百六十四批 ②＋追加①③（锁头口径／三铁栅），以及
// `设计-记忆双系统-2026-11-05.md` 的四层口径（L0 原文 → L1 整理）与「整理的三档风险」。
// （485 那份调研单**至今只有派工单、没有交付**，所以本文件不引它为据。）
//
// **整理的对象是组织形态，不是意思**（工单原话）。所以这里的动作只有四件，全部确定性：
// 1. **分节合并同类**（同一个小标题下的条款并到一处，按小标题首次出现的顺序）；
// 2. **去重**（同一节内空白归一后完全相同的行只留第一条——留着的那行就是原文，依据自证）；
// 3. **长条款指针化**（结论 ＋ 〔原文：归档#L行〕，结论取首句，正文一律进归档）；
// 4. **归档**（整理前的文件**逐字节**存一份，hash 必须与整理前相等）。
// 生成式摘要**不做**（设计文档 §四：三档风险按顺序实现、不许跳级，①确定性那一档先做；
// §四③ 说「生成式摘要暂不做」并记了一句 485 的结论——会引入「整理者自己出题」的问题）。
// 本批的动作因此全部确定性；「原来能答的问题整理后仍能答」在这里落成**可机验的等价物**：
// 每句原文在产物里要么原样还在、要么被指针追得回（见 [preserveProblems]）。
//
// 四条边界（每条都有测试钉）：
// - **A 内核永不参与**：入口第一行就拒，连 `files.fileOf(内核层)` 都不碰（它本来就会抛）；
// - **锁头三条不可进化**：非空、非注释、非小标题的前 3 行**按行序**（464 追加① 定的就是这个
//   口径——所以锁的是「前 3 条」，不是「前 3 节」），
//   锁头区整段原样带走，整理只动它之后的行 ⇒ 「改/删/换序 ⇒ 拒」在这里是**构造性**成立的：
//   根本没把锁头行送进任何变换函数；
// - **禁 L1→L1 再提炼**（三铁栅①）：已经带指针的行**原样保留、永不二次提炼**，
//   所以「提炼产物」的输入永远是归档里那一行 L0 原文——不存在那条代码路径；
// - **单来源不进事实池**（三铁栅②）：「事实」节整节**不做指针化**（一条来源不足以
//   把原话换成结论），只允许去重；这条在代码里是一句 `if (facts) 不提炼`，不是文档承诺。
//
// 收益不足**就不整理**（464 判据② 把「≥20%」写成通过线，反过来就是本批的**不整理**线；
// 追加①③ 又定「删的原文必须可追」⇒ 宁可不动，不许为了凑数字丢内容）：达不到 [MIN_SAVING_PERCENT] ⇒
// 返回 NoGain，一个字节不动。写盘走 [PersonaFiles.applyProposal] 这**唯一**的写口
// （写前自动备份、A 恒不可写都在它里面复核），所以「失败 ⇒ 回滚」用的是同一套备份机制。
//
// 为什么这里**不**用 [PersonaRules.appendOnly] 卡住：append-only 管的是「用户/模型往人设里
// 加东西」那条通道（464 之前它就是防自治的缰绳）；整理的对象正是「加到最后太长」这件事，
// 拿 append-only 拒整理等于用问题本身否决解法。这里的缰绳换成三样更硬的东西，且全部机验：
// 锁头三条字节不变 ＋ 原文逐字节归档（hash 相等）＋ 盘上复验不过就自动回滚。
package com.example.zhengdao.baopu

import java.io.File

/** 一条原文指针：归档文件名 ＋ 归档里的行号（1 起，因为归档就是整理前那份文件的逐字节副本）。 */
internal data class PersonaPointer(val archiveName: String, val line: Int) {

    fun mark(): String = "$OPEN$archiveName$SEP$line$CLOSE"

    companion object {
        const val OPEN = "〔原文："
        const val CLOSE = "〕"
        private const val SEP = "#L"

        /** 这行里有没有指针（有 ⇒ 它是 L1 产物，本批**不许**再提炼它）。 */
        fun hasMark(line: String): Boolean = line.contains(OPEN)

        /**
         * 解出指针指向的那一行原文；解不出 ⇒ null。
         *
         * 归档名必须对得上：指向别的归档的指针**不算解出**——否则一轮整理能拿上一轮的
         * 行号蒙对，实际指的却是另一份文件。
         */
        fun resolve(mark: String, archiveName: String, archiveLines: List<String>): String? {
            val parts = mark.substringAfter(OPEN).substringBefore(CLOSE).split(SEP)
            if (parts.size != 2 || parts[0] != archiveName) return null
            val no = parts[1].toIntOrNull() ?: return null
            return archiveLines.getOrNull(no - 1)
        }
    }
}

/** 一次「指针化」产物：产物行、它来自哪一原行、指向归档哪一行。 */
internal data class PersonaCondensed(val product: String, val original: String, val pointer: PersonaPointer)

/** 一份整理方案（纯数据：先算好、验过，才允许落盘）。 */
internal data class PersonaOrganizePlan(
    val layer: PersonaLayer,
    val archiveName: String,
    val before: String,
    val after: String,
    val lockedHead: String,
    val condensed: List<PersonaCondensed>,
    val notes: List<String>,
) {
    /** 整理前后的 token 一律**现算**（尺子＝[TokenEstimator]，不存字段：存了就会与文本对不上）。 */
    val tokensBefore: Int get() = TokenEstimator.estimate(before)

    val tokensAfter: Int get() = TokenEstimator.estimate(after)

    val savedTokens: Int get() = (tokensBefore - tokensAfter).coerceAtLeast(0)

    /** 省了多少百分比（整理前为 0 ⇒ 视作 100%，走「没东西可整理」那条路）。 */
    fun savingPercent(): Int = if (tokensBefore <= 0) 100 else savedTokens * 100 / tokensBefore

    /** 给人看的 diff（这一轮对组织形态做了什么）。 */
    fun diffText(): String = if (notes.isEmpty()) "只是重新分节" else notes.joinToString("；")
}

/** 一次整理的结论（失败必须带原因，不许静默）。 */
internal sealed class PersonaOrganizeResult {
    /** 整理并落盘了；[backup] 是写前自动留下的那份（人设自己的备份，不是归档）。 */
    data class Done(val plan: PersonaOrganizePlan, val archive: File, val backup: File?) : PersonaOrganizeResult()

    /** 不必整理（没超预算 / 没冗余 / 收益不足）——**一个字节没动**。 */
    data class NoGain(val reason: String) : PersonaOrganizeResult()

    /** 拒了（A 内核 / 保全校验不过 / 写盘或回滚失败）——文件保持原样或已回滚。 */
    data class Rejected(val reason: String) : PersonaOrganizeResult()
}

/**
 * B/C 层人设的自整理（纯判据 ＋ 薄 I/O，JVM 临时目录可直测）。
 *
 * 触发：某层 token ≥ [PersonaRules.PERSONA_TOKEN_LIMIT] 的 [TRIGGER_PERCENT]（＝6400）。
 * 判据（见 `PersonaOrganizeTest`）：整理后 token ↓≥20%／归档 hash ＝＝整理前 hash／
 * 每个指针都解得出正确原文／保全失败自动回滚／锁头三条与 A 内核字节不变。
 */
internal object PersonaOrganize {

    /** 触发线：预算的 80%（工单定的数）。 */
    const val TRIGGER_PERCENT = 80

    /** 收益线：省不到 20% 就**不整理**（464 判据② 的「≥20%」反过来就是这条线：没冗余就别动）。 */
    const val MIN_SAVING_PERCENT = 20

    /** 锁头几条（工单：B 层前 3 条、C 层前 3 条）。**与 ③ 的常驻三条同一个数**。 */
    const val LOCKED_HEAD_LINES = PersonaRules.LOCKED_HEAD_LINES

    /** 多长的条款才值得指针化（再短就剁成两句反而更难读）。 */
    const val LONG_LINE_CHARS = 160

    /** 结论最多留几个字。 */
    const val CONCLUSION_MAX_CHARS = 80

    /** 归档目录里的文件名形状（`20261106-011500.md`，时间戳一律走 `TimeFormats.fileStamp()`）。 */
    const val ARCHIVE_SUFFIX = ".md"

    /** A 内核的话术（判据⑤ 直接钉这句话）。 */
    const val NO_A = "A 内核永不参与整理（它只读，连文件都没有）"

    private const val NO_FILE = "这一层还没有自己的文件，没有可整理的东西"
    private const val BLANK = "这一层是空的，没有可整理的东西"
    private const val NO_PLAN = "整理方案是空的（没有动原文件）"
    private const val WRITE_FAILED = "写盘失败（人设文件没写进去）"
    private const val ARCHIVE_FAILED = "归档写不进去（没有动原文件）"

    /** [SkillAudit.shaOf] 读不了时的哨兵值（两个 "-" 相等**不算** hash 对得上）。 */
    private const val SHA_NONE = "-"

    /** 否定与例外的字样（本批自加的一查：这些行**丢了就是改了意思**，依据 464 ②「不自动改意思」）。 */
    private val NEGATION = Regex("不许|不要|不能|不可|不得|禁止|严禁|除非|否则|例外|无需|不必")

    /** 结论取「第一句」的分句点。 */
    private val SENTENCE_END = charArrayOf('。', '！', '？', '；', '：', '\n')

    private val WHITESPACE = Regex("\\s+")

    /** 超预算没有（估算口径与全系统同一把尺 [TokenEstimator]）。 */
    fun overBudget(text: String, tokensOf: (String) -> Int = { TokenEstimator.estimate(it) }): Boolean =
        tokensOf(text) * 100 >= PersonaRules.PERSONA_TOKEN_LIMIT * TRIGGER_PERCENT

    /**
     * 锁头三条的下标（**非空、非注释、非小标题**的前 [LOCKED_HEAD_LINES] 条，按行序，0 起）。
     *
     * 「什么算一条」不在这里判——转读 [PersonaRules.clauseIndexes]（464 ②③ 共用一把尺）。
     */
    fun headLineIndexes(text: String, n: Int = LOCKED_HEAD_LINES): List<Int> = PersonaRules.clauseIndexes(text, n)

    /** 锁头三条的**原文行**（判据⑤/⑥ 直接拿它比字节）。 */
    fun lockedHeadLines(text: String, n: Int = LOCKED_HEAD_LINES): List<String> {
        val lines = text.split("\n")
        return headLineIndexes(text, n).map { lines[it] }
    }

    /**
     * 归档的家：**逐层各归各家**（工单给的字面路径 `<…>/抱朴记忆/人设/归档/`）。
     *
     * B 层那份在 App 私有目录里，归档也就留在私有目录 ——
     * 与 [PersonaBackup.backupHome] 同一口径：全局人设的内容不许被搬进共享存储。
     */
    fun archiveHome(file: File): File? = file.parentFile?.let {
        File(it, PersonaPackRules.DIR_PACKS + "/" + PersonaPackRules.DIR_ORGANIZE_ARCHIVE)
    }

    /** 归档文件名（一轮整理一份，永不覆盖旧的那份 —— 原文只增不减）。 */
    fun archiveName(stamp: String): String = stamp + ARCHIVE_SUFFIX

    /**
     * 整理一层（**默认口径：自动整理 ＋ 可回滚**，UI 批再加「看 diff」）。
     *
     * @param planner 出方案的纯函数；测试注入**故意丢信息**的方案，用来验「保全失败 ⇒ 回滚」
     * @param postCheck 写完之后的盘上复核；测试注入失败，用来验回滚真的把文件恢复了
     */
    fun organize(
        files: PersonaFiles,
        layer: PersonaLayer,
        stamp: String = PersonaBackup.nowStamp(),
        planner: (PersonaLayer, String, String) -> PersonaOrganizePlan =
            { l, before, name -> planOf(l, before, name) },
        postCheck: (File, PersonaOrganizePlan) -> String? = { f, plan -> checkOnDisk(f, plan) },
    ): PersonaOrganizeResult {
        if (layer == PersonaLayer.A_KERNEL || !PersonaRules.canWrite(layer)) {
            return PersonaOrganizeResult.Rejected(NO_A)
        }
        val file = files.fileOf(layer)
        if (!file.isFile) return PersonaOrganizeResult.NoGain(NO_FILE)
        val before = runCatching { file.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
        if (before.isBlank()) return PersonaOrganizeResult.NoGain(BLANK)
        if (!overBudget(before)) return PersonaOrganizeResult.NoGain(underText(before))
        val plan = planner(layer, before, archiveName(stamp))
        if (plan.after.isBlank()) return PersonaOrganizeResult.Rejected(NO_PLAN)
        if (plan.savingPercent() < MIN_SAVING_PERCENT) return PersonaOrganizeResult.NoGain(gainText(plan))
        val problems = preserveProblems(plan, before.split("\n"))
        if (problems.isNotEmpty()) return refused(layer, plan, problems.first())
        return land(files, file, layer, before, plan, stamp, postCheck)
    }

    /**
     * 触发点（第四百六十四批 ②：挂在**唯一写口**之后）。
     *
     * 整理失败**绝不能**把人设写入带倒，所以整段包 `runCatching`：这里只可能返回结论，不可能抛。
     */
    fun maybeOrganizeAfterWrite(files: PersonaFiles, layer: PersonaLayer): PersonaOrganizeResult =
        runCatching { organize(files, layer) }
            .getOrElse { PersonaOrganizeResult.Rejected("整理没跑起来：${it.javaClass.simpleName}") }

    /** 归档 → 盘上解指针 → hash 相等 → 一次落盘 → 复验（不过就回滚）。没有「半批」这个状态。 */
    private fun land(
        files: PersonaFiles,
        file: File,
        layer: PersonaLayer,
        before: String,
        plan: PersonaOrganizePlan,
        stamp: String,
        postCheck: (File, PersonaOrganizePlan) -> String?,
    ): PersonaOrganizeResult {
        val archived = archiveOf(file, before, plan.archiveName) ?: return refused(layer, plan, ARCHIVE_FAILED)
        val diskProblems = preserveProblems(plan, readArchiveLines(archived))
        if (diskProblems.isNotEmpty()) {
            return refused(layer, plan, "归档解不回原文：${diskProblems.first()}")
        }
        if (!sameBody(archived, file)) return refused(layer, plan, "归档 hash 与整理前不相等（没有动原文件）")
        val proposal = PersonaRules.propose(layer, before, plan.after, "自整理：${plan.diffText()}")
            ?: return PersonaOrganizeResult.Rejected(NO_A)
        if (!files.applyProposal(proposal)) return PersonaOrganizeResult.Rejected(WRITE_FAILED)
        val why = postCheck(file, plan) ?: return done(file, layer, plan, archived)
        val tail = rollback(file, stamp)
        record(layer, plan, "保全校验失败：$why（$tail）", applied = false)
        return PersonaOrganizeResult.Rejected("保全校验失败：$why（$tail）")
    }

    /** 写进去且盘上复验通过 ⇒ 账上一行、留痕一行。 */
    private fun done(
        file: File,
        layer: PersonaLayer,
        plan: PersonaOrganizePlan,
        archived: File,
    ): PersonaOrganizeResult {
        record(layer, plan, "省 ${plan.savedTokens} token，归档=${plan.archiveName}", applied = true)
        PersonaAudit.organized(layer, plan.savedTokens, plan.archiveName)
        return PersonaOrganizeResult.Done(plan, archived, PersonaBackup.latestOf(file))
    }

    /** 写归档（已存在 ⇒ null：**绝不覆盖**旧原文，覆盖就等于删）。 */
    private fun archiveOf(file: File, before: String, name: String): File? {
        val home = archiveHome(file) ?: return null
        return runCatching {
            if (!home.isDirectory) home.mkdirs()
            val dest = File(home, name)
            if (dest.exists()) return@runCatching null
            dest.writeBytes(before.toByteArray(Charsets.UTF_8))
            dest
        }.getOrNull()
    }

    /** 归档那份与整理前那份是不是同一串字节（读不出来一律算不等）。 */
    private fun sameBody(archived: File, file: File): Boolean {
        val sha = SkillAudit.shaOf(archived)
        return sha != SHA_NONE && sha == SkillAudit.shaOf(file)
    }

    /** 回滚 = [PersonaBackup.restore]：先把「刚整理的那份」另存，再放回整理前那份。 */
    private fun rollback(file: File, stamp: String): String {
        val back = PersonaBackup.restore(file, stamp)
        return if (back is PersonaBackupResult.Done) "已回滚（当前版也另存了一份）"
        else "回滚没成功：${(back as PersonaBackupResult.Rejected).reason}"
    }

    private fun refused(
        layer: PersonaLayer,
        plan: PersonaOrganizePlan,
        why: String,
    ): PersonaOrganizeResult {
        record(layer, plan, "整理被拒（$why）｜原文一个没动", applied = false)
        PersonaAudit.organizeRejected(why)
        return PersonaOrganizeResult.Rejected("保全校验没过：$why（没有写人设文件）")
    }

    private fun record(
        layer: PersonaLayer,
        plan: PersonaOrganizePlan,
        reason: String,
        applied: Boolean,
    ) {
        PersonaChangeLedger.record(
            who = PersonaWho.ORGANIZE,
            layer = layer,
            text = plan.diffText(),
            reason = reason,
            applied = applied,
            source = "自整理",
        )
    }

    private fun underText(before: String): String =
        "没超预算 $TRIGGER_PERCENT%（约 ${TokenEstimator.estimate(before)} token），不整理"

    private fun gainText(plan: PersonaOrganizePlan): String =
        "收益不足：只省 ${plan.savingPercent()}%（要 ≥$MIN_SAVING_PERCENT%），不整理"

    private fun readArchiveLines(f: File): List<String> =
        runCatching { f.readText(Charsets.UTF_8).split("\n") }.getOrDefault(emptyList())

    /**
     * 保全校验（本批自定的三查，依据是 464 追加①③「删的原文必须可追」与设计文档 §二①「单向可追」
     * ＋ 锁头 ＋ 产物留在正文里）。**任一条不过 ⇒ 一行都不许留下**。
     *
     * @param archiveLines 归档那份的行（整理前原文，逐字节）——指针就是按它的行号解的
     */
    internal fun preserveProblems(plan: PersonaOrganizePlan, archiveLines: List<String>): List<String> {
        val out = mutableListOf<String>()
        // 比 trim 后的头一段：整份 `after` 落盘前也会 trim，文件开头那几个空行不能算锁头被改
        val head = plan.lockedHead.trim()
        if (head.isNotEmpty() && !plan.after.trim().startsWith(head)) {
            out += "锁头三条被改了（前 $LOCKED_HEAD_LINES 条是基本规矩，不可进化）"
        }
        val reachable = normSet(plan.after)
        plan.condensed.forEach { checkOne(it, plan, archiveLines, reachable, out) }
        val originals = plan.condensed.mapTo(mutableSetOf()) { norm(it.original) }
        clausesAfter(archiveLines, plan.lockedHead).forEach { line ->
            missingLine(line, reachable, originals, out)
        }
        return out
    }

    /** 一条指针的四查：归档对得上、解得出、解出来就是那句原文、产物自己没再套指针。 */
    private fun checkOne(
        c: PersonaCondensed,
        plan: PersonaOrganizePlan,
        archiveLines: List<String>,
        reachable: Set<String>,
        out: MutableList<String>,
    ) {
        val mark = c.pointer.mark()
        if (c.pointer.archiveName != plan.archiveName) out += "指针指向了别的归档：$mark"
        val resolved = PersonaPointer.resolve(mark, plan.archiveName, archiveLines)
        if (resolved == null) out += "指针解不出原文：$mark"
        else if (norm(resolved) != norm(c.original)) out += "指针解出来不是那句原文：$mark"
        if (norm(c.product) !in reachable) out += "产物行没留在正文里：${c.product.take(30)}"
        if (PersonaPointer.hasMark(conclusionPart(c.product))) out += "产物里又套了指针（禁 L1→L1 再提炼）"
    }

    /** 一句原文整理后去哪了：要么原样还在，要么被某个指针带着；否定句丢了换句话报。 */
    private fun missingLine(
        line: String,
        reachable: Set<String>,
        originals: Set<String>,
        out: MutableList<String>,
    ) {
        val n = norm(line)
        if (n in reachable || n in originals) return
        out += if (NEGATION.containsMatchIn(line)) "否定/例外那句丢了：${line.take(30)}"
        else "原文追不回来：${line.take(30)}"
    }

    /** 盘上复核（写完再读回来比一遍；不过就回滚）。 */
    internal fun checkOnDisk(file: File, plan: PersonaOrganizePlan): String? {
        val now = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return "写完了却读不回来"
        if (now.trim() != plan.after.trim()) return "文件内容与整理方案不一致"
        val archive = File(archiveHome(file) ?: return "归档目录不见了", plan.archiveName)
        if (!archive.isFile) return "归档文件不见了（原文没了依据）"
        return preserveProblems(plan, readArchiveLines(archive)).firstOrNull()
    }

    /**
     * 出方案（**纯函数**：只吃文本，零 I/O）。
     *
     * 顺序：锁头区整段留下 → 之后的行按小标题分节（同名小节并到首次出现的那节 ＝「合并同类」）
     * → 节内去重 → 长条款指针化（事实节除外）→ 重新拼一份。
     */
    internal fun planOf(layer: PersonaLayer, before: String, archiveName: String): PersonaOrganizePlan {
        val (locked, rest) = PersonaRules.splitResident(before)
        val skipped = if (locked.isEmpty()) 0 else locked.split("\n").size
        val condensed = mutableListOf<PersonaCondensed>()
        val notes = mutableListOf<String>()
        val blocks = mutableListOf(locked)
        mergedBlocks(rest, skipped).forEach { (heading, items) ->
            val kept = keptLines(heading, items, archiveName, condensed, notes)
            blocks += if (heading.isEmpty()) kept.joinToString("\n")
            else (listOf(heading) + kept).joinToString("\n")
        }
        // 整份 trim：`propose` 落盘前也会 trim，这里不同步的话盘上复验必红
        val after = blocks.filter { it.isNotBlank() }.joinToString("\n\n").trim()
        return PersonaOrganizePlan(
            layer = layer,
            archiveName = archiveName,
            before = before,
            after = after,
            lockedHead = locked,
            condensed = condensed,
            notes = notes,
        )
    }

    /**
     * 「合并同类」落在这一层：[PersonaRules.blocksOf] 按原文顺序切节，同名小标题的条款
     * 并到**首次出现**的那节（节内保持原行序）。
     *
     * 为什么合并只在整理这边做：注入（③）要的是「按原文顺序取节」，把两处合成一个
     * 判据就变成「整理会改注入的顺序」——那是两回事，共用切法、各用各的策略。
     */
    private fun mergedBlocks(rest: String, skipped: Int): LinkedHashMap<String, MutableList<Pair<Int, String>>> {
        val merged = LinkedHashMap<String, MutableList<Pair<Int, String>>>()
        PersonaRules.blocksOf(rest, skipped).forEach { b ->
            merged.getOrPut(b.heading) { mutableListOf() } += b.clauses
        }
        return merged
    }

    /**
     * 一节里留哪些行：去重 ＋ 长条款指针化（产物进 [condensed]）。
     *
     * 上一轮的产物（已带指针）**原样带走**，不参与任何变换 —— 这就是「禁 L1→L1」的实现方式。
     */
    private fun keptLines(
        heading: String,
        items: List<Pair<Int, String>>,
        archiveName: String,
        condensed: MutableList<PersonaCondensed>,
        notes: MutableList<String>,
    ): List<String> {
        val facts = heading.contains(FEATURES_HEADING)
        val kept = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var dropped = 0
        var made = 0
        var untouched = 0
        items.forEach { (no, text) ->
            if (PersonaPointer.hasMark(text)) {
                kept += text
                return@forEach
            }
            if (!seen.add(norm(text))) {
                dropped++
                return@forEach
            }
            val one = pointerize(text, no, archiveName, facts)
            if (one == null) {
                kept += text
                if (facts && text.length > LONG_LINE_CHARS) untouched++
            } else {
                kept += one.first
                condensed += one.second
                made++
            }
        }
        val label = if (heading.isEmpty()) "开头散行" else heading
        if (dropped > 0) notes += "「$label」去掉 $dropped 行重复"
        if (made > 0) notes += "「$label」长条款指针化 $made 行"
        if (untouched > 0) notes += "「$label」$untouched 行长文不提炼（单来源不进事实池）"
        return kept
    }

    /** 一行值不值得换成「结论＋指针」；不值得 ⇒ null（原样留着）。 */
    private fun pointerize(
        text: String,
        lineNo: Int,
        archiveName: String,
        facts: Boolean,
    ): Pair<String, PersonaCondensed>? {
        if (facts || text.length < LONG_LINE_CHARS) return null
        val pointer = PersonaPointer(archiveName, lineNo)
        val product = "${conclusionOf(text)}${pointer.mark()}"
        // 省不了几个字就别折腾：整理是为了读得动，不是为了让数字好看
        if (product.length >= text.length) return null
        return product to PersonaCondensed(product, text, pointer)
    }

    /** 结论 ＝ 原句的头一节（首句之后一律不自己编），最多 [CONCLUSION_MAX_CHARS] 字。 */
    internal fun conclusionOf(text: String): String {
        val cut = text.indexOfFirst { c -> SENTENCE_END.contains(c) }
        val head = if (cut >= 0) text.substring(0, cut + 1) else text
        val short = head.take(CONCLUSION_MAX_CHARS)
        return if (short.endsWith("\n")) short.trimEnd() + " " else short
    }

    /** 产物行里「指针之前」那半截（判「有没有把指针套进结论」用）。 */
    private fun conclusionPart(product: String): String = product.substringBefore(PersonaPointer.OPEN)

    /** 锁头区之后的条款行（保全校验只扫这些；锁头自己由字节比对那条管）。 */
    private fun clausesAfter(lines: List<String>, lockedHead: String): List<String> {
        val skip = if (lockedHead.isEmpty()) 0 else lockedHead.split("\n").size
        return lines.drop(skip).filter { PersonaRules.isClause(it) }
    }

    private fun norm(s: String): String = s.trim().replace(WHITESPACE, "")

    private fun normSet(text: String): Set<String> = text.split("\n").mapTo(mutableSetOf()) { norm(it) }

    /** 「事实」节的识别字（三铁栅②：这一节不做指针化）。 */
    private const val FEATURES_HEADING = "事实"
}
