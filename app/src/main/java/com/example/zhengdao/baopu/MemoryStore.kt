// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：抱朴-方案-2026-10-16.md §9（文件是真相，数据库只是索引——索引坏了/删了
// 能重建；记忆必须落盘成人能读的文件）；派工单第八批判据 1（删掉 baopu.db，
// reindex 能从 抱朴记忆/ 的 .md/.json 重建）。
package com.example.zhengdao.baopu

import com.example.zhengdao.rootfs.RunLog
import java.io.File

/**
 * 记忆的前后置码（front-matter `.md` ⇄ [MemoryEntry]），**纯函数**，可 JVM 单测。
 *
 * 文件形态（人能读、终端 agent 也能读）：
 * ```
 * ---
 * id: 1729000000-称呼偏好
 * scope: project
 * kind: preference
 * title: 称呼偏好
 * tags: 称呼, 用户
 * status: confirmed
 * source_session: 3
 * source_file: 资料库/原始/设定.md
 * created_at: 1729000000000
 * updated_at: 1729000000000
 * use_count: 0
 * last_used_at: 0
 * ---
 *
 * 正文……
 * ```
 */
internal object EntryCodec {

    fun render(e: MemoryEntry): String = buildString {
        append("---\n")
        append("id: ").append(e.id).append('\n')
        append("scope: ").append(e.scope.name.lowercase()).append('\n')
        append("kind: ").append(e.kind.name.lowercase()).append('\n')
        append("title: ").append(e.title.replace('\n', ' ')).append('\n')
        append("tags: ").append(e.tags.joinToString(", ")).append('\n')
        append("status: ").append(e.status.name.lowercase()).append('\n')
        append("source_session: ").append(e.sourceSession.replace('\n', ' ')).append('\n')
        append("source_file: ").append(e.sourceFile.replace('\n', ' ')).append('\n')
        append("created_at: ").append(e.createdAt).append('\n')
        append("updated_at: ").append(e.updatedAt).append('\n')
        append("use_count: ").append(e.useCount).append('\n')
        append("last_used_at: ").append(e.lastUsedAt).append('\n')
        append("---\n\n")
        append(e.body.trim()).append('\n')
    }

    /**
     * 解析。容错但**不装**：缺关键字段（id/scope/kind/title/body）⇒ null
     * （调用方跳过该文件并在结果里报出来，不静默）。
     */
    fun parse(text: String): MemoryEntry? {
        if (!text.startsWith("---\n")) return null
        val end = text.indexOf("\n---\n", startIndex = 4)
        if (end < 0) return null
        val head = text.substring(4, end)
        val body = text.substring(end + 5).trim()
        val fields = LinkedHashMap<String, String>()
        for (line in head.split('\n')) {
            if (line.isBlank()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            fields[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        val id = fields["id"]?.takeIf { it.isNotEmpty() } ?: return null
        val scope = enumOf<MemoryScope>(fields["scope"]) ?: return null
        val kind = enumOf<MemoryKind>(fields["kind"]) ?: return null
        val title = fields["title"]?.takeIf { it.isNotEmpty() } ?: return null
        if (body.isEmpty()) return null
        val tags = fields["tags"].orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return MemoryEntry(
            id = id,
            scope = scope,
            kind = kind,
            title = title,
            body = body,
            tags = tags,
            sourceSession = fields["source_session"].orEmpty(),
            sourceFile = fields["source_file"].orEmpty(),
            status = enumOf<MemoryStatus>(fields["status"]) ?: MemoryStatus.CANDIDATE,
            createdAt = fields["created_at"]?.toLongOrNull() ?: 0L,
            updatedAt = fields["updated_at"]?.toLongOrNull() ?: 0L,
            useCount = fields["use_count"]?.toIntOrNull() ?: 0,
            lastUsedAt = fields["last_used_at"]?.toLongOrNull() ?: 0L,
        )
    }

    private inline fun <reified T : Enum<T>> enumOf(raw: String?): T? =
        raw?.takeIf { it.isNotEmpty() }?.let { name ->
            enumValues<T>().firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
}

/**
 * 记忆库（刀 0b · 2026-10-16）：**文件是真相**，SQLite 只是索引。
 *
 * 目录形态：
 * - 项目记忆：`<projectRoot>/记忆/<id>.md`
 * - 全局记忆：`<globalRoot>/记忆/<id>.md`
 *
 * 设计要点：
 * 1. **只有 CONFIRMED 才落盘**（判据 3：候选 → 待确认 → 落盘，没有确认不写盘）；
 * 2. 落盘前过 [MemoryRules.validate] ＋ 去重（同 scope+kind+正文近似不重复入库）；
 * 3. [loadAll] 就是 reindex 的地基——把 `baopu.db` 删掉，扫一遍目录全回来；
 * 4. 本类不碰 Context、不建线程（写由调用方线程做；索引同步是刀 1 接线时的事），
 *    全部逻辑可用临时目录 JVM 直测。
 */
internal class MemoryStore(
    private val globalRoot: File,
    private val projectRoot: File,
) {

    /** 项目记忆目录（跟工作区）。 */
    fun projectDir(): File = File(projectRoot, DIR_ENTRIES)

    /** 全局记忆目录（跟 App）。 */
    fun globalDir(): File = File(globalRoot, DIR_ENTRIES)

    fun dirFor(scope: MemoryScope): File = when (scope) {
        MemoryScope.PROJECT -> projectDir()
        MemoryScope.GLOBAL -> globalDir()
    }

    /**
     * 落盘一条**已确认**的记忆。
     * @return 写成的文件；拒绝时返回 null（原因进 [WriteResult]）。
     */
    fun write(entry: MemoryEntry): WriteResult {
        if (entry.status != MemoryStatus.CONFIRMED) {
            return WriteResult(false, null, "未确认的记忆不落盘（status=${entry.status.name.lowercase()}）")
        }
        val v = MemoryRules.validate(entry)
        if (v is MemoryRules.Validation.Bad) {
            return WriteResult(false, null, "候选不合格：${v.reason}")
        }
        val ok = (v as MemoryRules.Validation.Ok).normalized
        val existing = loadAll()
        if (MemoryRules.dedupeAgainst(listOf(ok), existing).isEmpty()) {
            return WriteResult(false, null, "与既有记忆重复（同 scope ＋ 同 kind ＋ 正文近似），不重复入库")
        }
        val dir = dirFor(ok.scope)
        val file = File(dir, ok.id + ".md")
        if (file.exists()) {
            return WriteResult(false, null, "同名文件已存在（${file.name}）——记忆 id 撞了，换标题或稍候重试")
        }
        return try {
            dir.mkdirs()
            file.writeText(EntryCodec.render(ok), Charsets.UTF_8)
            WriteResult(true, file, "")
        } catch (e: Exception) {
            // 第七十一批②：失败要有声音（不打印记忆正文；调用方 UI 提示在飞，待下一批）
            RunLog.log("记忆写入失败：id=${ok.id} reason=写盘异常（${e.javaClass.simpleName}）")
            WriteResult(false, null, "写盘失败（${e.javaClass.simpleName}）")
        }
    }

    /** 改一条（按 id 找文件整份重写；id 不变 ⇒ 文件位置不变）。 */
    fun update(entry: MemoryEntry): Boolean {
        val file = findFile(entry.id) ?: return false
        // 第七十一批②：这里的 false 之前纯静默（返回值被调用方忽略，用户以为记住了）——
        // 至少在本方法内落一条日志；reason 只给异常类别，不带记忆正文。
        val written = runCatching {
            file.writeText(EntryCodec.render(entry), Charsets.UTF_8)
            true
        }.getOrDefault(false)
        if (!written) {
            RunLog.log("记忆写入失败：id=${entry.id} reason=改写落盘异常")
        }
        return written
    }

    /** 删一条（判据：可单条删）。文件不在 ⇒ false。 */
    fun delete(id: String): Boolean {
        val file = findFile(id) ?: return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    /**
     * 扫全部目录解析出全部记忆（**reindex 的地基**：文件是真相）。
     *
     * 排序键 = (createdAt, id) —— **显式稳定排序**（追加四第 2 条）：
     * 只按 createdAt 排，同时间的条目会落回文件系统遍历顺序（跨设备/跨次不确定），
     * 拼进上下文就是两次不同的前缀。
     */
    fun loadAll(): List<MemoryEntry> {
        val out = mutableListOf<MemoryEntry>()
        for (dir in listOf(globalDir(), projectDir())) {
            val files = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (f in files) {
                if (!f.isFile || f.extension != "md") continue
                val entry = runCatching { EntryCodec.parse(f.readText(Charsets.UTF_8)) }.getOrNull()
                if (entry != null) out += entry
            }
        }
        return out.sortedWith(compareBy({ it.createdAt }, { it.id }))
    }

    /**
     * 重建索引：与 [loadAll] 同源（文件即真相），额外返回**解析失败的文件名**——
     * 坏文件要报出来，不静默跳过（用户可能刚手改过 front-matter）。
     */
    fun reindex(): ReindexResult {
        val entries = mutableListOf<MemoryEntry>()
        val broken = mutableListOf<String>()
        for (dir in listOf(globalDir(), projectDir())) {
            val files = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (f in files) {
                if (!f.isFile || f.extension != "md") continue
                val entry = runCatching { EntryCodec.parse(f.readText(Charsets.UTF_8)) }.getOrNull()
                if (entry != null) entries += entry else broken += f.name
            }
        }
        return ReindexResult(entries.sortedWith(compareBy({ it.createdAt }, { it.id })), broken)
    }

    /** 召回（按当前任务，≤ 上界；判据 2：不全量灌，截断明说）。 */
    fun recall(query: String, budgetTokens: Int = MemoryRules.RECALL_TOKEN_BUDGET): RecallResult =
        MemoryRules.recall(loadAll().filter { it.status == MemoryStatus.CONFIRMED }, query, budgetTokens)

    /** 按 id 找文件（两个目录都找）。 */
    fun findFile(id: String): File? {
        for (dir in listOf(globalDir(), projectDir())) {
            val f = File(dir, "$id.md")
            if (f.isFile) return f
        }
        return null
    }

    data class WriteResult(val ok: Boolean, val file: File?, val reason: String)

    data class ReindexResult(val entries: List<MemoryEntry>, val brokenFiles: List<String>)

    companion object {
        const val DIR_ENTRIES = "记忆"
    }
}
