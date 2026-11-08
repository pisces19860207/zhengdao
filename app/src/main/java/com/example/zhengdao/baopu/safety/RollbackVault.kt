// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零四单 · 470「强回滚」的**回滚保险箱**——写类动作前记「改哪些路径＋原字节
// hash（或备份）」；一键回滚到"最后一次已知好状态"（会话/任务两粒度）；回滚后自校验；
// **回滚自身可回滚**。全部走 java.io.File，不摸 android.* ⇒ JVM 单测直接钉判据④
// （删 5 个文件 ⇒ 回滚 ⇒ 5 个都回来且 hash 一致）。
//
// 派工单口径的分档（§二①原话）：
// - 小文件（≤10MB）：整份字节备份进保险箱；
// - >10MB：不整份备份，走**回收站**路线并在卡上说明——delete_path 本来就把原件挪进
//   工作区回收站（回滚时从那里取回，由 finish 侧回填 trash 路径）；其余写类工具只记
//   hash，如实标"不可字节回滚"，卡上说明由 SafetyHooks 的返回值带到确认卡上。
//
// 回滚可回滚的实现：回滚动手**之前**先把每个目标此刻的状态再存一炉（reversal stash），
// 挂在本次回滚自己的 opId 下 ⇒ 对这个 opId 再做一次回滚＝撤销上次回滚。
package com.example.zhengdao.baopu.safety

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** 原件保存方式（闭集四格）。 */
enum class StashKind(val wire: String, val cn: String) {
    /** 动作前不存在 ⇒ 回滚＝删掉它（如果现在有）。 */
    CREATE("create", "新建"),

    /** 动作前存在且 ≤10MB ⇒ 字节已备份，回滚＝整份放回＋hash 自校验。 */
    BACKUP("backup", "已备份"),

    /** delete_path 挪进回收站的大文件 ⇒ 回滚＝从回收站取回（路径由执行后回填）。 */
    TRASH("trash", "回收站可取"),

    /** >10MB 且工具不是 delete_path ⇒ 只记 hash；回滚时如实说"不可字节回滚"。 */
    HASH_ONLY("hash_only", "仅记hash"),
    ;

    companion object {
        fun ofWire(wire: String): StashKind = entries.firstOrNull { it.wire == wire } ?: HASH_ONLY
    }
}

/** 一条原件记录（保险箱账的行；status: pending=还没回滚过，rolled=已被某次回滚处理过）。 */
data class StashEntry(
    val opId: String,
    val tool: String,
    val path: String,
    val kind: StashKind,
    val sha256: String,
    val sizeBytes: Long,
    val backupRel: String,
    val sessionId: String,
    val runId: String,
    val atMs: Long,
    val rolled: Boolean = false,
    val trashPath: String = "",
    /** 非空＝这是某次回滚的 reversal 记录（值＝回滚单 opId）——只许按单笔触达，防级联。 */
    val reversalOf: String = "",
)

/** 回滚范围（判据②：会话/任务两粒度；ByOp 是"回滚自身可回滚"的通道）。 */
sealed interface RollbackScope {
    /** 按会话：回滚这个会话所有未回滚的改动。 */
    data class BySession(val sessionKey: String) : RollbackScope

    /** 按任务：回滚这一轮（runId）所有未回滚的改动。 */
    data class ByTask(val runId: String) : RollbackScope

    /** 按单笔动作（含某次回滚自己）。 */
    data class ByOp(val opId: String) : RollbackScope
}

/** 一次回滚的收成（自校验的数字都在这——结果落账用）。 */
data class RollbackOutcome(
    val restored: Int,
    val deleted: Int,
    val failed: Int,
    val hashOk: Int,
    val hashMismatch: Int,
    val rollbackOpId: String,
    val lines: List<String>,
) {
    /** 一行人话（起居注/决策账用）。 */
    val summaryLine: String
        get() = "还原 $restored／删回 $deleted／失败 $failed；hash 对 $hashOk、错 $hashMismatch（回滚单 $rollbackOpId）"
}

/** 纯规则：分档与 hash（JVM 直测；IO 在 [RollbackVault]）。 */
object RollbackRules {

    /** 派工单原话：小文件备份，>10MB 走回收站。 */
    const val BIG_FILE_BYTES: Long = 10L * 1024 * 1024

    /** 这份原件该走哪一档（动作前的存在性/大小已经调用方喂进来，本层不摸盘）。 */
    fun kindOf(exists: Boolean, sizeBytes: Long, tool: String): StashKind = when {
        !exists -> StashKind.CREATE
        sizeBytes <= BIG_FILE_BYTES -> StashKind.BACKUP
        tool == "delete_path" -> StashKind.TRASH
        else -> StashKind.HASH_ONLY
    }

    /** SHA-256（字节级，与 backup/BackupRules 同一口径：小写十六进制）。 */
    fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** 流式 hash（大文件不整读进内存）。 */
    fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/**
 * 回滚保险箱（进程级单例；落点 filesDir/baopu/safety/rollback/——[install] 注入，
 * JVM 单测可注临时目录）。
 */
object RollbackVault {

    private const val DIR = "baopu/safety/rollback"
    private const val INDEX = "index.jsonl"

    @Volatile
    private var baseDir: File? = null

    /** 装配口（BaopuRuntime 调一次；单测传临时目录）。 */
    fun install(root: File) {
        baseDir = root
    }

    /**
     * 装了没有（第五百三十一批 ④：子任务守卫要先问一句再谈撤回）。
     *
     * 为什么需要它：`stash/rollback` 内部是 `runCatching`（账本不拦执行），
     * 没装时只会静默返回空——守卫若照着「空结果是撤过了」报账，就是假绿。
     */
    fun isInstalled(): Boolean = baseDir != null

    private fun dir(): File = File(baseDir!!, DIR)
    private fun indexFile(): File = File(dir(), INDEX)

    // ── 动作前：入库 ─────────────────────────────────────────────────────

    /** 保险箱里一件原件的字节落点（<opId>/files/<序>-<名>）。 */
    private fun backupFile(opId: String, seq: Int, name: String): File =
        File(dir(), "$opId/files/$seq-$name")

    /**
     * 写类动作前把每个目标存一炉。
     *
     * @return 该笔动作的全部原件记录＋>10MB 的路径清单（卡上说明用）。
     */
    fun stash(
        opId: String,
        tool: String,
        paths: List<String>,
        sessionId: String,
        runId: String,
        nowMs: Long,
    ): List<StashEntry> {
        if (paths.isEmpty()) return emptyList()
        val made = mutableListOf<StashEntry>()
        synchronized(this) {
            runCatching {
                paths.forEachIndexed { idx, raw ->
                    val f = File(raw)
                    val exists = f.isFile
                    val size = if (exists) f.length() else 0L
                    val kind = RollbackRules.kindOf(exists, size, tool)
                    var backupRel = ""
                    var sha = ""
                    if (kind == StashKind.BACKUP) {
                        val dest = backupFile(opId, idx, f.name)
                        dest.parentFile?.mkdirs()
                        f.copyTo(dest, overwrite = true)
                        backupRel = dest.relativeTo(dir()).invariantSeparatorsPath
                        sha = RollbackRules.sha256Of(f)
                    } else if (exists) {
                        // 大文件流式 hash（TRASH/HASH_ONLY 都记——自校验/说明要用）
                        sha = RollbackRules.sha256Of(f)
                    }
                    made += StashEntry(
                        opId = opId,
                        tool = tool,
                        path = f.absolutePath,
                        kind = kind,
                        sha256 = sha,
                        sizeBytes = size,
                        backupRel = backupRel,
                        sessionId = sessionId,
                        runId = runId,
                        atMs = nowMs,
                    )
                }
                appendIndex(made)
            }
        }
        return made
    }

    /** delete_path 把大文件挪进工作区回收站后，把可取回的路径回填到该笔记录上。 */
    fun backfillTrash(opId: String, path: String, trashFile: String) {
        synchronized(this) {
            val all = readIndex()
            val hit = all.lastOrNull { it.opId == opId && it.path == path } ?: return
            if (hit.kind != StashKind.TRASH || hit.trashPath.isNotEmpty()) return
            rewriteIndex(all.map { if (it === hit) hit.copy(trashPath = trashFile) else it })
        }
    }

    // ── 动作后：一键回滚 ─────────────────────────────────────────────────

    /**
     * 回滚到"最后一次已知好状态"：范围内**未回滚**的原件按时间倒序逐件还原，
     * 每件还原前先把此刻状态再存一炉（挂在回滚单名下 ⇒ 回滚自身可回滚），
     * 还原后逐件 hash 自校验。
     */
    fun rollback(scope: RollbackScope, nowMs: Long): RollbackOutcome {
        val rbOpId = "rb-$nowMs"
        var restored = 0
        var deleted = 0
        var failed = 0
        var hashOk = 0
        var hashMismatch = 0
        val lines = mutableListOf<String>()
        synchronized(this) {
            runCatching {
                val targets = readIndex()
                    .filter { !it.rolled && matches(it, scope) }
                    .sortedByDescending { it.atMs }
                if (targets.isEmpty()) {
                    lines += "范围内没有未回滚的改动"
                }
                val reversals = mutableListOf<StashEntry>()
                targets.forEachIndexed { seq, entry ->
                    val f = File(entry.path)
                    // 先存"此刻"——回滚单自己的原件（撤销回滚＝对 rbOpId 再回滚一次）
                    reversals += snapshotCurrent(rbOpId, entry.tool, f, seq, entry.sessionId, entry.runId, nowMs)
                    val tally = restoreOne(entry)
                    restored += tally.restored
                    deleted += tally.deleted
                    failed += tally.failed
                    hashOk += tally.hashOk
                    hashMismatch += tally.hashMismatch
                    lines += tally.line
                }
                // 回滚单：标记已处理的原件＋收编 reversal（撤销通道）
                val touched = targets.map { it.copy(rolled = true) }.toSet()
                if (touched.isNotEmpty()) {
                    val all = readIndex()
                    rewriteIndex(
                        all.map { e -> touched.firstOrNull { it.path == e.path && it.opId == e.opId && it.atMs == e.atMs } ?: e } + reversals,
                    )
                }
            }.onFailure {
                failed++
                lines += "回滚没走完：${it.javaClass.simpleName}:${it.message}"
            }
        }
        return RollbackOutcome(restored, deleted, failed, hashOk, hashMismatch, rbOpId, lines)
    }

    /** 单件还原的小账（行＋四格计数；restoreOne 的产出）。 */
    private data class RestoreTally(
        val restored: Int,
        val deleted: Int,
        val failed: Int,
        val hashOk: Int,
        val hashMismatch: Int,
        val line: String,
    ) {
        companion object {
            /** 只有一行话、计数全零的收法（无需处理/不可还原的说明行）。 */
            fun note(line: String) = RestoreTally(0, 0, 0, 0, 0, line)
        }
    }

    /** 按档还原一件（470③ 的"回滚后自校验"落在 BACKUP/TRASH 两格里：还原即比 hash）。 */
    private fun restoreOne(entry: StashEntry): RestoreTally {
        val f = File(entry.path)
        return when (entry.kind) {
            StashKind.CREATE -> {
                if (!f.exists()) RestoreTally.note("无需还原（目标本就不在）：${f.name}")
                else if (f.delete()) RestoreTally(0, 1, 0, 0, 0, "已删回（动作前没有）：${f.name}")
                else RestoreTally(0, 0, 1, 0, 0, "删回失败：${f.name}")
            }
            StashKind.BACKUP -> {
                val bak = File(dir(), entry.backupRel)
                if (!bak.isFile) RestoreTally(0, 0, 1, 0, 0, "备份缺失，回不了：${f.name}")
                else restoreAndVerify(bak, f, entry.sha256, "已还原")
            }
            StashKind.TRASH -> {
                val trash = entry.trashPath.takeIf { it.isNotBlank() }?.let(::File)
                if (trash == null || !trash.isFile) {
                    RestoreTally(0, 0, 1, 0, 0, "回收站原件找不到，回不了：${f.name}")
                } else {
                    restoreAndVerify(trash, f, entry.sha256, "已从回收站取回")
                }
            }
            StashKind.HASH_ONLY -> RestoreTally(
                0, 0, 1, 0, 0,
                "原件 >10MB 未整份备份（卡上已说明），不可字节回滚：${f.name}",
            )
        }
    }

    /** 放回＋hash 自校验（成功/不一致都算 restored；不一致如实说——不许谎报全绿）。 */
    private fun restoreAndVerify(from: File, to: File, sha256: String, verb: String): RestoreTally {
        atomicRestore(from, to)
        return if (RollbackRules.sha256Of(to) == sha256) {
            RestoreTally(1, 0, 0, 1, 0, "$verb 且 hash 一致：${to.name}")
        } else {
            RestoreTally(1, 0, 0, 0, 1, "$verb 但 hash 不一致：${to.name}")
        }
    }

    /** 这笔记录归不归这次回滚的范围。reversal 记录（回滚的原件）只许按单笔触达——
     *  会话/任务粒度把它们扫进去会级联回滚（撤销了不该撤销的还原）。 */
    private fun matches(e: StashEntry, scope: RollbackScope): Boolean {
        if (e.reversalOf.isNotEmpty() && scope !is RollbackScope.ByOp) return false
        return when (scope) {
            is RollbackScope.BySession -> e.sessionId == scope.sessionKey
            is RollbackScope.ByTask -> e.runId == scope.runId
            is RollbackScope.ByOp -> e.opId == scope.opId
        }
    }

    /** 把目标此刻的状态存进回滚单（回滚前必做——撤销回滚的原料；带 reversalOf 防级联）。 */
    private fun snapshotCurrent(
        rbOpId: String,
        tool: String,
        f: File,
        seq: Int,
        sessionId: String,
        runId: String,
        nowMs: Long,
    ): StashEntry {
        val exists = f.isFile
        val size = if (exists) f.length() else 0L
        val kind = RollbackRules.kindOf(exists, size, tool)
        var backupRel = ""
        var sha = ""
        if (kind == StashKind.BACKUP) {
            val dest = backupFile(rbOpId, seq, f.name)
            dest.parentFile?.mkdirs()
            f.copyTo(dest, overwrite = true)
            backupRel = dest.relativeTo(dir()).invariantSeparatorsPath
            sha = RollbackRules.sha256Of(f)
        } else if (exists) {
            sha = RollbackRules.sha256Of(f)
        }
        return StashEntry(
            opId = rbOpId,
            tool = tool,
            path = f.absolutePath,
            kind = kind,
            sha256 = sha,
            sizeBytes = size,
            backupRel = backupRel,
            sessionId = sessionId,
            runId = runId,
            atMs = nowMs,
            reversalOf = rbOpId,
        )
    }

    /** 原子放回：先 .tmp 再 rename（与 write_file 同一口径——半截文件比不写更坏）。 */
    private fun atomicRestore(from: File, to: File) {
        to.parentFile?.mkdirs()
        val tmp = File(to.parentFile, to.name + ".rollback-tmp")
        from.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(to)) {
            tmp.copyTo(to, overwrite = true)
            tmp.delete()
        }
    }

    // ── 账（index.jsonl；重写式——保险箱是工具不是账本，状态要能改） ────────

    internal fun readIndex(): List<StashEntry> = runCatching {
        val f = indexFile()
        if (!f.exists()) return emptyList()
        f.readLines().mapNotNull { line ->
            runCatching { entryOfJson(JSONObject(line)) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun appendIndex(entries: List<StashEntry>) {
        if (entries.isEmpty()) return
        dir().mkdirs()
        indexFile().appendText(entries.joinToString("") { entryJson(it).toString() + "\n" })
    }

    private fun rewriteIndex(entries: List<StashEntry>) {
        dir().mkdirs()
        indexFile().writeText(entries.joinToString("") { entryJson(it).toString() + "\n" })
    }

    internal fun entryJson(e: StashEntry): JSONObject = JSONObject()
        .put("opId", e.opId)
        .put("tool", e.tool)
        .put("path", e.path)
        .put("kind", e.kind.wire)
        .put("sha256", e.sha256)
        .put("size", e.sizeBytes)
        .put("backup", e.backupRel)
        .put("session", e.sessionId)
        .put("run", e.runId)
        .put("at", e.atMs)
        .put("rolled", e.rolled)
        .put("trash", e.trashPath)
        .put("reversalOf", e.reversalOf)

    internal fun entryOfJson(o: JSONObject): StashEntry = StashEntry(
        opId = o.optString("opId"),
        tool = o.optString("tool"),
        path = o.optString("path"),
        kind = StashKind.ofWire(o.optString("kind")),
        sha256 = o.optString("sha256"),
        sizeBytes = o.optLong("size"),
        backupRel = o.optString("backup"),
        sessionId = o.optString("session"),
        runId = o.optString("run"),
        atMs = o.optLong("at"),
        rolled = o.optBoolean("rolled"),
        trashPath = o.optString("trash"),
        reversalOf = o.optString("reversalOf"),
    )
}
