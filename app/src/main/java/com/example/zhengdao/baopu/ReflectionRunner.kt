// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百零五批 · 反思器 v1 的**IO 壳**（房型照 `DiaryStore`：判据全在 [ReflectionRules]，
// 本类只做「从盘上/库里把四源读出来」与「进化档案 append」两件事）。
//
// 读（[scan]，**零写盘**）：
//   ① 自动日记：[DiaryStore.loadAll]（**只读**——不传 db、不落任何索引）；
//   ② 技能变更账：`技能/_变更.jsonl`（两层目录都读，带行号）；
//   ③ 产物台账：`<工作区>/产物/台账.md` ＋ 产物目录清单；
//   ④ 失败轨迹：`run_trace`（失败分类）；**无表/读失败 ⇒ run_log 兜底**（错误摘要当分类）。
// 写（[present]/[decide]）：**只 append** `<工作区>/产物/进化档案.md`——这是派工单 §3 点名的
// 产出（候选卡＋档案一行，决定=待定）；技能目录、索引、人设、日记一个字节不碰
// （写技能归 184/192 的确认通道，红线「只出候选卡，不写盘、不改技能」）。
//
// 触发不在这里（谁、什么时候调）：见 [ReflectionTrigger] 与接线（控制器 init 的「进抱朴时
// 若今天没跑过」＋用户说「复盘一下」；技能页的「现在复盘一次」按钮）。
package com.example.zhengdao.baopu

import com.example.zhengdao.core.RunLog
import org.json.JSONObject
import java.io.File

/**
 * 反思器（只出候选，不写技能）。
 *
 * @param wsRoot 抱朴当前工作区根（`BaopuRuntime.workspaceRoot`）
 * @param globalRoot 全局记忆根（`filesDir/抱朴记忆`——全局技能目录与 B 层索引在这棵下）
 * @param db 会话库（失败轨迹源；null ⇒ 该源按空处理——测试与无库环境用）
 * @param productLedger 产物台账文件；null ⇒ 默认 `<工作区>/产物/台账.md`
 * @param log 失败与「本轮无候选」的留痕（默认 RunLog；测试注入内存实现）
 */
internal class ReflectionRunner(
    private val wsRoot: File,
    private val globalRoot: File,
    private val db: BaopuDb? = null,
    private val productLedger: File? = null,
    private val log: (String) -> Unit = { RunLog.log(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** 扫描（**只读**：盘上一个字节不写——负例「确认前写盘」的靶子就是这一步）。 */
    fun scan(): ReflectionScan {
        val inputs = assemble()
        return ReflectionScanRules.scan(inputs, archiveText(), MAX_CARDS)
    }

    /**
     * 出卡（派工单 §3 的「产出」）：
     * - 四源全空/无候选 ⇒ **只记一行日志**（不弹卡、不写档案）；
     * - 有候选 ⇒ 每张 append 一行「待定」进进化档案（append-only；文件不存在先写表头）
     *   并把卡交回调用方展示。技能目录与索引在这条路径上**不可能**被碰（本类没有那条路）。
     */
    fun present(scan: ReflectionScan): List<ReflectionCandidate> {
        if (scan.allEmpty || scan.candidates.isEmpty()) {
            log(scan.logLine)
            return emptyList()
        }
        val file = archiveFile(wsRoot)
        val date = ReflectionDedup.today(clock())
        val appended = scan.candidates.joinToString("\n") { ReflectionArchive.renderLine(it, date) }
        runCatching {
            val parent = file.parentFile
            if (parent != null && !parent.isDirectory) parent.mkdirs()
            val header = if (file.isFile) "" else ReflectionArchive.header() + "\n"
            file.appendText(header + appended + "\n", Charsets.UTF_8)
        }.onFailure {
            log("反思器：进化档案追加失败（${it.javaClass.simpleName}：${it.message}）——候选卡照常给出")
        }
        log(scan.logLine + "；进化档案记 ${scan.candidates.size} 行（待定）")
        return scan.candidates
    }

    /** 扫描＋出卡一步走（接线方便口；行为＝[scan] 后 [present]）。 */
    fun scanAndPresent(): Pair<ReflectionScan, List<ReflectionCandidate>> {
        val s = scan()
        return s to present(s)
    }

    /**
     * 用户对着卡做了决定（采纳/否决/搁置）：**追加**一行（历史行不改）——
     * 「否决」会让同键 30 天内不再提（[ReflectionDedup]）。
     */
    fun decide(candidate: ReflectionCandidate, decision: String) {
        val file = archiveFile(wsRoot)
        val date = ReflectionDedup.today(clock())
        runCatching {
            val parent = file.parentFile
            if (parent != null && !parent.isDirectory) parent.mkdirs()
            val header = if (file.isFile) "" else ReflectionArchive.header() + "\n"
            file.appendText(header + ReflectionArchive.renderLine(candidate, date, decision) + "\n", Charsets.UTF_8)
        }.onFailure {
            log("反思器：决定（$decision）写档案失败（${it.javaClass.simpleName}：${it.message}）")
        }
    }

    /** 进化档案现读（去重与「下一批先读档案」的入口）。 */
    fun archiveText(): String? {
        val f = archiveFile(wsRoot)
        if (!f.isFile) return null
        return runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
    }

    // ── 四源装配（全部只读；哪源读不了 ⇒ 按空处理并在可得性表里说实话）────────

    private fun assemble(): ReflectionInputs {
        val now = clock()
        val (diary, diaryFiles, diaryRootExists) = diaryPart()
        val ledger = ledgerLines()
        val products = productPart()
        val failures = failureRows(now)
        return ReflectionInputs(
            nowMs = now,
            wsRoot = wsRoot,
            globalRoot = globalRoot,
            diary = diary,
            diaryFiles = diaryFiles,
            diaryRootExists = diaryRootExists,
            skillLedger = ledger,
            productLedgerFile = products.first,
            productLedgerText = products.second,
            productFiles = products.third,
            failures = failures,
            skillDirs = skillDirRefs(ledger),
        )
    }

    /** ①自动日记：[DiaryStore.loadAll]（文件是真相；db 传 null＝不碰索引）。 */
    private fun diaryPart(): Triple<List<DiaryEntry>, Map<String, File>, Boolean> {
        val store = DiaryStore(MemoryLayout.memoryDir(wsRoot), db = null)
        val snapshot = runCatching { store.loadAll() }.getOrElse {
            log("反思器：日记读失败（${it.javaClass.simpleName}）——按空处理")
            DiaryStore.Snapshot(emptyList(), 0)
        }
        val files = mutableMapOf<String, File>()
        for (e in snapshot.entries) {
            files.putIfAbsent(e.date, store.dayFile(e.date))
        }
        return Triple(snapshot.entries, files, MemoryLayout.diaryDir(wsRoot).isDirectory)
    }

    /** ②技能变更账：两层 `技能/_变更.jsonl` 都读（项目层在前）；坏行跳过不算失败。 */
    private fun ledgerLines(): List<SkillAuditLine> {
        val out = mutableListOf<SkillAuditLine>()
        for (dir in listOf(SkillRules.projectSkillDir(wsRoot), SkillRules.globalSkillDir(globalRoot))) {
            val f = SkillAudit.fileIn(dir)
            if (!f.isFile) continue
            val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            text.lines().forEachIndexed { i, raw ->
                if (raw.isBlank()) return@forEachIndexed
                val o = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEachIndexed
                val name = o.optString("name")
                if (name.isEmpty()) return@forEachIndexed
                out += SkillAuditLine(
                    file = f,
                    lineNo = i + 1,
                    ts = o.optLong("ts", 0L),
                    scope = o.optString("scope"),
                    name = name,
                    action = o.optString("action"),
                    reason = o.optString("reason"),
                    indexUpdated = o.optBoolean("indexUpdated", false),
                )
            }
        }
        return out
    }

    /** ③产物台账＋产物目录清单（台账默认 `产物/台账.md`；外置根那套归长文线，本类不重复实现）。 */
    private fun productPart(): Triple<File?, String?, List<File>> {
        val dir = MemoryLayout.productsDir(wsRoot)
        val files = if (dir.isDirectory) {
            dir.listFiles { f -> f.isFile }?.sortedBy { it.name } ?: emptyList()
        } else {
            emptyList()
        }
        val ledger = productLedger ?: File(dir, PRODUCT_LEDGER_NAME)
        if (!ledger.isFile) return Triple(ledger, null, files)
        val text = runCatching { ledger.readText(Charsets.UTF_8) }.getOrNull()
        return Triple(ledger, text, files)
    }

    /** ④失败轨迹：run_trace 有表用 run_trace（失败分类）；读不了 ⇒ run_log 兜底（错误摘要当分类）。 */
    private fun failureRows(nowMs: Long): List<ReflectionFailureRow> {
        val d = db ?: return emptyList()
        val since = nowMs - ReflectionExtract.FAILURE_WINDOW_MS
        val fromTrace = runCatching {
            d.read { db ->
                db.rawQuery(
                    "SELECT failure_kind, started_at, run_id, seq FROM run_trace " +
                        "WHERE status='FAILED' AND failure_kind<>'' AND started_at>=? " +
                        "ORDER BY started_at DESC LIMIT $FAILURE_ROW_LIMIT",
                    arrayOf(since.toString()),
                ).use { c ->
                    val rows = ArrayList<ReflectionFailureRow>(c.count)
                    while (c.moveToNext()) {
                        rows += ReflectionFailureRow(
                            kind = c.getString(0),
                            atMs = c.getLong(1),
                            runId = c.getString(2),
                            seq = c.getInt(3),
                            fromTrace = true,
                        )
                    }
                    rows
                }
            }
        }.getOrNull()
        if (fromTrace != null) return fromTrace
        // 兜底：run_log 的错误首行截断当分类（run_trace 无表/老库——派工单 §1「无表则读 run_log」）
        return runCatching {
            d.read { db ->
                db.rawQuery(
                    "SELECT err, created_at, id, step FROM run_log " +
                        "WHERE ok=0 AND created_at>=? " +
                        "ORDER BY created_at DESC LIMIT $FAILURE_ROW_LIMIT",
                    arrayOf(since.toString()),
                ).use { c ->
                    val rows = ArrayList<ReflectionFailureRow>(c.count)
                    while (c.moveToNext()) {
                        rows += ReflectionFailureRow(
                            kind = c.getString(0).lineSequence().firstOrNull()?.trim()?.take(48).orEmpty(),
                            atMs = c.getLong(1),
                            runId = "log${c.getLong(2)}",
                            seq = c.getInt(3),
                            fromTrace = false,
                        )
                    }
                    rows
                }
            }
        }.getOrElse {
            log("反思器：失败轨迹读不了（${it.javaClass.simpleName}）——该源按空处理")
            emptyList()
        }
    }

    /**
     * 两层技能目录＋索引行（带行号）的合并读数——「账实不符」形状的原料。
     * 索引文件取人设层那两份（B 全局/C 项目；与 [SkillIndexReconcile] 同一口径，只读）。
     */
    private fun skillDirRefs(ledger: List<SkillAuditLine>): List<SkillDirRef> {
        val persona = PersonaFiles(globalRoot = globalRoot, projectRoot = wsRoot)
        val indexFiles = listOf(
            PersonaLayer.C_PROJECT to persona.read(PersonaLayer.C_PROJECT),
            PersonaLayer.B_GLOBAL to persona.read(PersonaLayer.B_GLOBAL),
        )
        val refsByName = mutableMapOf<String, MutableList<IndexLineRef>>()
        for ((layer, text) in indexFiles) {
            val file = persona.fileOf(layer)
            if (text == null) continue
            text.lines().forEachIndexed { i, raw ->
                val s = raw.trim()
                if (!s.startsWith(SkillRules.INDEX_MARK)) return@forEachIndexed
                val parsed = SkillRules.parseIndexLines(s, scopeOf(layer))
                parsed.firstOrNull()?.let { line ->
                    refsByName.getOrPut(line.name) { mutableListOf() } += IndexLineRef(file, i + 1, line)
                }
            }
        }
        val lastWriteByName = ledger
            .filter { it.action == "write" || it.action == "update" }
            .groupBy { it.name }
            .mapValues { (_, lines) -> lines.maxBy { it.ts } }

        val out = mutableListOf<SkillDirRef>()
        val seenNames = mutableSetOf<String>()
        for ((scopeTag, dir) in listOf("project" to SkillRules.projectSkillDir(wsRoot), "global" to SkillRules.globalSkillDir(globalRoot))) {
            val subs = dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: continue
            for (sub in subs) {
                seenNames += sub.name
                val files = sub.listFiles()?.map { it.name } ?: emptyList()
                val skillDir = SkillIndexReconcile.SkillDir(sub.name, files)
                val bodyFile = if (skillDir.hasBody) File(sub, SkillRules.SKILL_FILE) else null
                val body = bodyFile?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }
                out += SkillDirRef(
                    dir = skillDir,
                    scopeTag = scopeTag,
                    bodyFile = bodyFile,
                    bodyStatus = body?.let { SkillStore.statusOf(it) },
                    bodyUses = body?.let { SkillStore.usesOf(it) } ?: 0,
                    hasHeader = body?.lineSequence()?.firstOrNull()?.let {
                        it.trim().startsWith("<!--") && it.contains("status=")
                    } == true,
                    indexRefs = refsByName[sub.name].orEmpty(),
                    lastWriteLine = lastWriteByName[sub.name],
                )
            }
        }
        // 索引有行、目录整个不存在 ⇒ 同样是悬空（204 的「索引悬空」另一半：目录被手删）。
        // 造一个空目录的合成行，判据层按「有行无正文」处理，不在 IO 壳里另写一套判。
        for ((name, refs) in refsByName) {
            if (name in seenNames || refs.isEmpty()) continue
            out += SkillDirRef(
                dir = SkillIndexReconcile.SkillDir(name, emptyList()),
                scopeTag = if (refs.first().line.scope == MemoryScope.GLOBAL) "global" else "project",
                bodyFile = null,
                bodyStatus = null,
                bodyUses = 0,
                hasHeader = false,
                indexRefs = refs,
                lastWriteLine = lastWriteByName[name],
            )
        }
        return out
    }

    private fun scopeOf(layer: PersonaLayer): MemoryScope = when (layer) {
        PersonaLayer.B_GLOBAL -> MemoryScope.GLOBAL
        PersonaLayer.C_PROJECT -> MemoryScope.PROJECT
        PersonaLayer.A_KERNEL -> MemoryScope.PROJECT
    }

    companion object {
        /** 每轮最多出几张卡（派工单 §2「≤3 条，宁少不滥」）。 */
        const val MAX_CARDS = 3

        /** 产物台账文件名（与 179 长文线同一落点；外置根那套在它那边）。 */
        const val PRODUCT_LEDGER_NAME = "台账.md"

        /** 失败轨迹单轮回看行数上限（聚合够用即可，不整库扫）。 */
        const val FAILURE_ROW_LIMIT = 300

        /** 进化档案落点：`<工作区>/产物/进化档案.md`。 */
        fun archiveFile(wsRoot: File): File =
            File(MemoryLayout.productsDir(wsRoot), ReflectionArchive.FILE_NAME)
    }
}
