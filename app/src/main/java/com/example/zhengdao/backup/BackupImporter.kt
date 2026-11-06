// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第四百九十一批②「一键导入：默认不覆盖，冲突列项让人选；导入前校验 manifest；
// 失败不留半状态（先解临时目录）」——三条判据各自对应下面一段。
//
// 为什么是"先解临时目录、再校验、最后才动真文件"：
//  ① 默认不覆盖要能在**一个字节都没改**的前提下知道"会撞哪些"；
//  ② manifest 校验必须打在**真解出来的字节**上（清单说多大、hash 多少，都以落盘文件重算为准）；
//  ③ 失败不留半状态靠"暂存 + 备份 + 回滚"三步：先全部写到 `*.zd491-tmp`，全部对得上才
//    逐个换名；换名途中任何一步失败，用 `*.zd491-bak` 把已经换掉的那些退回去。
package com.example.zhengdao.backup

import com.example.zhengdao.baopu.archive.ExtractOutcome
import com.example.zhengdao.baopu.archive.ZipExtractor
import com.example.zhengdao.baopu.archive.ZipIndex
import com.example.zhengdao.baopu.archive.ZipIndexResult
import com.example.zhengdao.core.RunLog
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 打开（并校验）一个备份包的结果。 */
internal sealed interface OpenResult {
    data class Ready(val preview: ImportPreview) : OpenResult
    data class Rejected(val reason: String) : OpenResult
}

/** 一个待还原条目：备份里的那一份、本机现在的样子、以及两者的关系。 */
internal data class ImportItem(
    val entry: BackupEntry,
    /** 解到临时目录里的那份（设置条目没有实文件 ⇒ null）。 */
    val tempFile: File?,
    /** 本机落点（`chat/` 之类不还原的 ⇒ null）。 */
    val live: File?,
    val state: BackupRules.ItemState,
)

/** 导入预览：**还没改任何东西**，界面拿它列冲突、等用户勾选。 */
internal class ImportPreview(
    val manifest: BackupManifest,
    val tempDir: File,
    val items: List<ImportItem>,
    /** `settings/settings.json` 的原文（界面与写回由壳层做；文件层不认 SharedPreferences）。 */
    val settingsJson: String?,
) {
    val conflicts: List<ImportItem> get() = items.filter { it.state == BackupRules.ItemState.CONFLICT }

    val news: List<ImportItem> get() = items.filter { it.state == BackupRules.ItemState.NEW }

    val same: List<ImportItem> get() = items.filter { it.state == BackupRules.ItemState.SAME }

    val untargeted: List<ImportItem> get() = items.filter { it.state == BackupRules.ItemState.UNTARGETED }

    /** 删掉临时目录（导入完成、取消、或失败后都要调）。 */
    fun cleanup() {
        runCatching { tempDir.deleteRecursively() }
    }
}

/** 落盘结果：成功给读数，失败明确说"改了没有"（好让界面照实说，不糊弄）。 */
internal sealed interface ApplyOutcome {
    data class Ok(
        val applied: Int,
        val unchanged: Int,
        val settingsJson: String?,
        /** 实际写进去的条目路径（回执要能点名，不许只说"写了几项"）。 */
        val written: List<String>,
    ) : ApplyOutcome
    data class Failed(val reason: String, val rolledBack: Boolean) : ApplyOutcome
}

/**
 * 备份包 → 本机落点。
 *
 * 不 import 任何 Android 设施（只有 [File] 与 `java.nio.file`）⇒ 真临时目录直测，
 * "冲突不覆盖""失败不留半状态"两条判据都能在 JVM 里演出来。
 */
internal object BackupImporter {

    /** 暂存名后缀（`x.jsonl` → `x.jsonl.zd491-tmp`）。 */
    const val TMP_SUFFIX = ".zd491-tmp"

    /** 备份名后缀（换名前把原文件拷一份到这里）。 */
    const val BAK_SUFFIX = ".zd491-bak"

    /** 设置快照在包里的固定路径。 */
    const val SETTINGS_PATH = "settings/settings.json"

    /**
     * 解包到 [workRoot] 下的新临时目录，校验清单，产出预览。
     *
     * @param workRoot 临时目录的父目录（App 传 cacheDir；单测传系统临时目录）
     */
    fun open(zip: File, mounts: List<BackupMount>, workRoot: File): OpenResult {
        if (!zip.isFile) return OpenResult.Rejected("找不到这个文件：${zip.name}")
        val members = when (val r = ZipIndex.parse(zip)) {
            is ZipIndexResult.Bad -> return OpenResult.Rejected(r.reason)
            is ZipIndexResult.Ok -> r.members.filterNot { it.isDirectory }.map { it.name }
        }
        if (members.none { it == BackupManifestCodec.MANIFEST_NAME }) {
            return OpenResult.Rejected("这个 zip 里没有 ${BackupManifestCodec.MANIFEST_NAME}——不是证道的备份包")
        }
        val tempDir = newTempDir(workRoot)
        val extracted = ZipExtractor.extract(zip, tempDir)
        if (extracted !is ExtractOutcome.Ok) {
            runCatching { tempDir.deleteRecursively() }
            val why = when (extracted) {
                is ExtractOutcome.Rejected -> extracted.reason
                is ExtractOutcome.Failed -> extracted.reason
                is ExtractOutcome.Ok -> ""
            }
            return OpenResult.Rejected("包解不开：$why")
        }
        return verify(tempDir, members, mounts)
    }

    private fun newTempDir(workRoot: File): File {
        runCatching { workRoot.mkdirs() }
        var n = 0
        while (n < 1000) {
            val d = File(workRoot, "zd491-import-${System.currentTimeMillis()}-$n")
            if (d.mkdirs()) return d
            n++
        }
        return File(workRoot, "zd491-import-${System.nanoTime()}").apply { mkdirs() }
    }

    /** 校验：清单先过、包里不许有清单外的东西、每条的 size/hash 以**落盘文件**重算。 */
    private fun verify(tempDir: File, members: List<String>, mounts: List<BackupMount>): OpenResult {
        val raw = runCatching { File(tempDir, BackupManifestCodec.MANIFEST_NAME).readText() }.getOrElse {
            runCatching { tempDir.deleteRecursively() }
            return OpenResult.Rejected("清单读不出来：${it.javaClass.simpleName}")
        }
        val manifest = when (val r = BackupManifestCodec.decode(raw)) {
            is ManifestRead.Bad -> {
                runCatching { tempDir.deleteRecursively() }
                return OpenResult.Rejected("清单不合格：${r.reason}")
            }
            is ManifestRead.Ok -> r.manifest
        }
        val hashBad = hashMismatch(tempDir, manifest)
        if (hashBad != null) {
            runCatching { tempDir.deleteRecursively() }
            return OpenResult.Rejected(hashBad)
        }
        val stray = strayMembers(members, manifest)
        if (stray != null) {
            runCatching { tempDir.deleteRecursively() }
            return OpenResult.Rejected(stray)
        }
        return OpenResult.Ready(buildPreview(tempDir, manifest, mounts))
    }

    /** 逐条重算 size/sha256；返回第一条对不上的说明（null ＝ 全对得上）。 */
    private fun hashMismatch(tempDir: File, manifest: BackupManifest): String? {
        for (e in manifest.entries) {
            val f = File(tempDir, e.path)
            if (!f.isFile) return "包里少了这一条：${e.path}"
            if (f.length() != e.size) {
                return "字节数对不上：${e.path}（清单写 ${e.size}，实际 ${f.length()}）"
            }
            val sha = BackupRules.sha256Of(f)
            if (sha != e.sha256) return "hash 对不上：${e.path}"
        }
        return null
    }

    /** 包里有、清单没写的条目 ⇒ 整包拒（清单外的文件没有落点，也不该被"顺手"带进去）。 */
    private fun strayMembers(members: List<String>, manifest: BackupManifest): String? {
        val known = manifest.entries.mapTo(HashSet()) { it.path }
        known += BackupManifestCodec.MANIFEST_NAME
        val stray = members.firstOrNull { it !in known } ?: return null
        return "包里有清单外的文件：$stray（清单与内容对不上，不敢导）"
    }

    private fun buildPreview(tempDir: File, manifest: BackupManifest, mounts: List<BackupMount>): ImportPreview {
        val items = ArrayList<ImportItem>(manifest.entries.size)
        var settingsJson: String? = null
        for (e in manifest.entries) {
            if (e.path == SETTINGS_PATH) {
                settingsJson = runCatching { File(tempDir, e.path).readText() }.getOrNull()
                items += ImportItem(e, File(tempDir, e.path), null, BackupRules.ItemState.UNTARGETED)
                continue
            }
            val live = BackupLayout.resolve(mounts, e.path)?.live
            val state = if (live == null) {
                BackupRules.ItemState.UNTARGETED
            } else {
                BackupRules.stateOf(live, e.sha256)
            }
            items += ImportItem(e, File(tempDir, e.path), live, state)
        }
        return ImportPreview(manifest, tempDir, items, settingsJson)
    }

    /**
     * 按选择落盘。
     *
     * @param overwrite 用户**明确勾选覆盖**的条目路径；不在这个集合里的冲突一律不动（默认不覆盖）
     */
    fun apply(preview: ImportPreview, overwrite: Set<String>): ApplyOutcome {
        val chosen = preview.items.filter { it.state == BackupRules.ItemState.NEW || it.state == BackupRules.ItemState.CONFLICT }
            .filter { it.state == BackupRules.ItemState.NEW || it.entry.path in overwrite }
        if (chosen.isEmpty()) {
            return ApplyOutcome.Ok(0, preview.items.size, preview.settingsJson, emptyList())
        }
        val staged = stageAll(chosen)
        if (staged is StageResult.Failed) {
            return ApplyOutcome.Failed(staged.reason, rolledBack = true)
        }
        val plans = (staged as StageResult.Ok).plans
        val done = commitAll(plans)
        if (done is CommitResult.Failed) {
            return ApplyOutcome.Failed(done.reason, rolledBack = done.rolledBack)
        }
        val appliedCount = plans.size
        return ApplyOutcome.Ok(
            applied = appliedCount,
            unchanged = preview.items.size - appliedCount,
            settingsJson = preview.settingsJson,
            written = chosen.map { it.entry.path },
        )
    }

    // ── 第一步：全部暂存并核对 ──────────────────────────────────────────────

    private data class Plan(val live: File, val tmp: File)

    private sealed interface StageResult {
        data class Ok(val plans: List<Plan>) : StageResult
        data class Failed(val reason: String) : StageResult
    }

    private fun stageAll(chosen: List<ImportItem>): StageResult {
        val plans = ArrayList<Plan>(chosen.size)
        for (item in chosen) {
            val live = item.live ?: continue
            val tmp = File(live.parentFile, live.name + TMP_SUFFIX)
            val src = item.tempFile
            if (src == null || !src.isFile) {
                discard(plans)
                return StageResult.Failed("临时文件不见了：${item.entry.path}")
            }
            val staged = runCatching {
                live.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
                src.copyTo(tmp, overwrite = true)
            }.isSuccess
            if (!staged || BackupRules.sha256Of(tmp) != item.entry.sha256) {
                runCatching { if (tmp.isFile) tmp.delete() }
                discard(plans)
                return StageResult.Failed("暂存失败：${item.entry.path}")
            }
            plans += Plan(live, tmp)
        }
        return StageResult.Ok(plans)
    }

    /** 清掉已经写下的暂存文件（一个真文件都还没动）。 */
    private fun discard(plans: List<Plan>) {
        for (p in plans) runCatching { p.tmp.delete() }
    }

    // ── 第二步：逐个换名（失败回滚）───────────────────────────────────────────

    private sealed interface CommitResult {
        data object Done : CommitResult
        data class Failed(val reason: String, val rolledBack: Boolean) : CommitResult
    }

    private fun commitAll(plans: List<Plan>): CommitResult {
        // 每个元素：换了名的 live 与其备份（null ＝ 原本不存在，回滚时删掉）
        val done = ArrayList<Pair<File, File?>>()
        return try {
            for (p in plans) {
                val bak = if (p.live.isFile) File(p.live.parentFile, p.live.name + BAK_SUFFIX) else null
                if (bak != null) p.live.copyTo(bak, overwrite = true)
                Files.move(p.tmp.toPath(), p.live.toPath(), StandardCopyOption.REPLACE_EXISTING)
                done += p.live to bak
            }
            // 全换成功才清备份；清不掉只是留个垃圾文件，不影响结果
            for ((_, bak) in done) if (bak != null) runCatching { bak.delete() }
            for (p in plans) runCatching { if (p.tmp.isFile) p.tmp.delete() }
            CommitResult.Done
        } catch (e: Exception) {
            val ok = rollback(done)
            // 暂存文件是**我们自己**建的，任何结局都要清掉（不然用户目录里留一堆 .zd491-tmp）
            for (p in plans) runCatching { if (p.tmp.isFile) p.tmp.delete() }
            // ⚠️ 这里**绝不能**删备份：回滚没成功时，`.zd491-bak` 是用户原件唯一的副本
            RunLog.log("备份导入换名失败（${e.javaClass.simpleName}），回滚${if (ok) "成功" else "未完成"}")
            CommitResult.Failed("写入失败（${e.javaClass.simpleName}）：${e.message ?: "没有更多信息"}", ok)
        }
    }

    /** 逆序退回：有备份的用备份换回去，原本没有的删掉。任一失败 ⇒ 返回 false（界面照实说）。 */
    private fun rollback(done: List<Pair<File, File?>>): Boolean {
        var ok = true
        for ((live, bak) in done.asReversed()) {
            val one = runCatching {
                if (bak != null) {
                    Files.move(bak.toPath(), live.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } else {
                    live.delete()
                }
            }.isSuccess
            if (!one) ok = false
        }
        return ok
    }
}
