// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十九批 · 两笔顺带收尾的存量迁移（设计 §7 四步／§8 行 13、14）。
//
// 四步一条不少：**备份 → 复制 → 校验 → 待删清单**，而且**旧目录一个都不删**
// （用户「永不自动删」的红线；真要删走 178 的勾选批量删）。
//
// 两笔活：
//   ① `screenshots/` ⇒ `截屏/`：177 已把这层改名，但**真机上装着旧包**，
//      新包一落盘就同时存在两套同名图。这里按「先哈希对账再复制」处理：
//      同名同哈希 ⇒ 已在位（不复制）；同名不同内容 ⇒ 换名复制（绝不覆盖）；
//      复制完**逐文件复核 sha256**，对不上的当场删掉复制品并记欠账。
//   ② 根直放的 `人设-本项目.md` ＋ `.bak-*`：**只登记，不搬不删**。新写入已落
//      设计位置（`<工作区>/抱朴记忆/`），`.bak` 的产生位置改到 `备份/人设/`
//      （`PersonaBackup`），根上这些是历史存量 ⇒ 登记成清单交给人。
//
// 幂等：第二遍必须全零（`ShotReport.isEmpty`／`PersonaReport.isEmpty` 就是判据）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.kernel.BaopuKernel
import com.example.zhengdao.core.RunLog
import com.example.zhengdao.core.TimeFormats
import java.io.File

/** 两笔迁移共用的「对账报告」出口（同一个目录，方便一起看）。 */
internal object MediaMigration {

    /** `screenshots/` 的旧目录名（177 之前）。 */
    const val LEGACY_SHOT_DIR = "screenshots"

    /** 待删清单文件名（落在旧目录里，App **不会**照着它删）。 */
    const val PENDING_DELETE = ".待删.txt"

    /**
     * 一次截屏目录迁移的结果（数字全部可对账，`summary()` 直接进日志）。
     *
     * [isEmpty] 的口径是**这一遍没搬东西**（`copied == 0`）——设计 §7 的「二遍全零」说的是
     * 「不再搬」，不是「不再看见已经在位的那些」。
     */
    data class ShotReport(
        val legacyDir: String = "",
        val targetDir: String = "",
        val copied: Int = 0,
        val identical: Int = 0,
        val skipped: List<String> = emptyList(),
        val renamed: List<String> = emptyList(),
        val failedVerify: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean get() = copied == 0

        fun summary(): String = "截屏目录迁移：复制 $copied 项，已在位 $identical 项" +
            (if (renamed.isEmpty()) "" else "，改名 ${renamed.size} 项") +
            (if (skipped.isEmpty()) "" else "，跳过 ${skipped.size} 项") +
            (if (failedVerify.isEmpty()) "" else "，哈希复核失败 ${failedVerify.size} 项（已删复制品）")
    }

    /** 人设存量登记结果（`registered` 为空 ⇒ 幂等，第二遍什么都不做）。 */
    data class PersonaReport(
        val registered: List<String> = emptyList(),
        val designFileExists: Boolean = false,
        val registerFile: String = "",
    ) {
        val isEmpty: Boolean get() = registered.isEmpty()

        fun summary(): String = if (registered.isEmpty()) {
            "人设存量登记：无需登记" + (if (designFileExists) "（设计位置已有）" else "")
        } else {
            "人设存量登记：登记 ${registered.size} 项（不搬不删）"
        }
    }

    /**
     * ① 截屏目录迁移。
     *
     * @param workspace 抱朴工作区（对账报告落 `产物/_迁移存档/`）
     * @param legacy 旧目录（`screenshots/`；不存在 ⇒ 空报告）
     * @param target 新目录（`截屏/`；不存在就建）
     */
    fun migrateShots(workspace: File, legacy: File, target: File): ShotReport {
        if (!legacy.isDirectory) return ShotReport()
        val legacyFiles = runCatching { legacy.listFiles { f -> f.isFile && !isHidden(f) } }
            .getOrNull()?.sortedBy { it.name } ?: return ShotReport(legacy.path, target.path)
        if (legacyFiles.isEmpty()) return ShotReport(legacy.path, target.path)
        runCatching { target.mkdirs() }

        val legacyItems = mutableListOf<MediaOwnership.Item>()
        val skipped = mutableListOf<String>()
        for (f in legacyFiles) {
            val sha = runCatching { MediaOwnership.sha256(f) }.getOrNull()
            if (sha == null) skipped += f.name else legacyItems += MediaOwnership.Item(f.name, sha)
        }
        val targetItems = runCatching { target.listFiles { f -> f.isFile && !isHidden(f) } }.getOrNull()
            ?.mapNotNull { f -> runCatching { MediaOwnership.Item(f.name, MediaOwnership.sha256(f)) }.getOrNull() }
            ?: emptyList()

        val plan = MediaOwnership.planShotMigration(legacyItems, targetItems)
        val byName = legacyFiles.associateBy { it.name }
        val copiedNames = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val renamed = mutableListOf<String>()

        for (copy in plan.copies) {
            val src = byName[copy.from] ?: continue
            val dst = File(target, copy.to)
            val sha = legacyItems.first { it.name == copy.from }.sha256
            val ok = runCatching {
                if (dst.exists()) return@runCatching false // 计划外的同名：绝不覆盖
                src.copyTo(dst, overwrite = false)
                // 校验：逐文件复核哈希，对不上就删掉复制品（宁可没搬，也不能搬坏的进来）
                val got = runCatching { MediaOwnership.sha256(dst) }.getOrNull()
                if (!got.equals(sha, ignoreCase = true)) {
                    runCatching { dst.delete() }
                    false
                } else {
                    true
                }
            }.getOrDefault(false)
            if (ok) {
                copiedNames += copy.to
                if (copy.to != copy.from) renamed += "${copy.from} → ${copy.to}"
            } else {
                failed += copy.from
            }
        }

        val report = ShotReport(
            legacyDir = legacy.path,
            targetDir = target.path,
            copied = copiedNames.size,
            identical = plan.identical.size,
            skipped = skipped + plan.skipped + failed,
            renamed = renamed,
            failedVerify = failed,
        )
        // 待删清单：**只写不改盘**。旧目录留着，用户在文件管理器里自己决定什么时候删。
        runCatching {
            val lines = buildList {
                add("# 这些文件已经复制到 ${target.name}/，哈希已核对。App 不会自动删，你确认后再删。")
                add("# 对账时间：${TimeFormats.fileStamp()}")
                copiedNames.sorted().forEach { add(it) }
                plan.identical.sorted().forEach { add("$it  （${MediaOwnership.WHY_SAME}）") }
            }
            File(legacy, PENDING_DELETE).writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        }
        writeRecon(workspace, "截屏目录", report.summary(), report.failedVerify + report.skipped)
        if (!report.isEmpty) RunLog.log("抱朴" + report.summary())
        return report
    }

    /**
     * ② 人设存量登记（**不搬、不删**）。
     *
     * 认这些名字（都在工作区**根**上，是 157「根里没有裸文件」的反例）：
     * `人设-本项目.md`、`人设-本项目.md.bak-<stamp>`。
     */
    fun registerPersonaResidue(workspace: File): PersonaReport {
        val names = runCatching { workspace.listFiles { f -> f.isFile } }.getOrNull()
            ?.map { it.name }
            ?.filter { isPersonaResidue(it) }
            ?.sorted()
            ?: emptyList()
        val designFile = File(File(workspace, PersonaPackRules.DIR_ROOT), PersonaFiles.FILE_PERSONA_PROJECT)
        val registerFile = File(backupPersonaDir(workspace), "存量-登记.txt")
        if (names.isEmpty()) {
            return PersonaReport(designFileExists = designFile.isFile, registerFile = registerFile.path)
        }
        val body = buildString {
            appendLine("# 根目录上的人设存量（199 登记；App **不搬不删**，新写入已改落 抱朴记忆/）")
            appendLine("# 设计位置：" + designFile.path + (if (designFile.isFile) "（已存在）" else "（还没有）"))
            appendLine("# 文件\t字节\tsha256")
            for (n in names) {
                val f = File(workspace, n)
                val sha = runCatching { MediaOwnership.sha256(f) }.getOrNull() ?: "(读不了)"
                appendLine("$n\t${f.length()}\t$sha")
            }
        }
        val same = runCatching { registerFile.isFile && registerFile.readText() == body }.getOrDefault(false)
        val written = if (same) false else runCatching {
            registerFile.parentFile?.mkdirs()
            registerFile.writeText(body, Charsets.UTF_8)
            true
        }.getOrDefault(false)
        if (!written) return PersonaReport(designFileExists = designFile.isFile, registerFile = registerFile.path)
        RunLog.log("抱朴人设存量登记：${names.size} 项（只登记，不搬不删）")
        return PersonaReport(names, designFileExists = designFile.isFile, registerFile = registerFile.path)
    }

    /** 根上的这一项是不是人设存量（本体或它的 `.bak-*`）。 */    fun isPersonaResidue(name: String): Boolean =
        name == PersonaFiles.FILE_PERSONA_PROJECT ||
            PersonaBackup.isBackupOf(name, PersonaFiles.FILE_PERSONA_PROJECT)

    /** 人设的备份家（`<工作区>/备份/人设/`：`.bak` 的产生位置，199 起）。 */
    fun backupPersonaDir(workspace: File): File =
        File(File(workspace, BaopuKernel.Data.DIR_BACKUP), "人设")

    /**
     * 隐藏文件不算存量。
     *
     * 理由：本迁移自己会在旧目录里写 `.待删.txt`——第二遍把它当成一张「截图」搬进
     * `截屏/` 就闹笑话了（也会让「二遍全零」永远不成立）。`.DS_Store`/`._x.jpg`
     * 这类系统垃圾同理（搜索那边也是按隐藏名排除的）。
     */
    private fun isHidden(f: File): Boolean = f.name.startsWith(".")

    /** 对账报告落 `产物/_迁移存档/<日期>-<主题>对账.txt`（设计 §7 第 3 步的固定出口）。 */
    private fun writeRecon(workspace: File, topic: String, summary: String, details: List<String>) {
        runCatching {
            val dir = File(
                File(workspace, BaopuKernel.Data.DIR_PRODUCTS),
                BaopuKernel.Data.DIR_MIGRATED_ARCHIVE,
            )
            dir.mkdirs()
            val f = File(dir, "${TimeFormats.fileStamp()}-$topic 对账.txt")
            f.writeText(
                buildString {
                    appendLine(summary)
                    appendLine("时间：${TimeFormats.fileStamp()}")
                    if (details.isNotEmpty()) {
                        appendLine("明细（跳过/复核失败）：")
                        details.forEach { appendLine("· $it") }
                    }
                    appendLine("（旧目录未删；见旧目录里的 $PENDING_DELETE）")
                },
                Charsets.UTF_8,
            )
        }
    }
}
