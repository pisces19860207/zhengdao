// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百九十批 · 种子技能版本迁移（2026-11-06 派工单）：
// 「同名技能不覆盖」是 207 批的红线，它保护用户自建/改过的技能，本单一个字不改。
// 本文件是**另一条通道**：只对「内置种子、且正文与旧版基线逐字节一致」的那一份放行升级。
//
// 按来源分流（不是一刀切）：
//   ① 有种子痕迹 + 正文 == v1 基线 ⇒ 自动升 v2，旧版进 .bak- 归档、审计记 v1→v2；
//   ② 有种子痕迹 + 正文都不命中 ⇒ 被人改过，不覆盖，只提示「内置有新版 vN→vM，看差异吗」；
//   ③ 无种子痕迹（用户自建同名）⇒ 不覆盖、**不提示**，哪怕正文恰好照抄内置基线。
//
// ⚠️ 种子痕迹是**必要条件**：只靠 hash 命中就放行，会让「用户自建但内容照抄内置」被覆盖，
//    那正是本单红线（独立评审抓出的阻断，返工点）。hash 只用来判「改没改过」，不用来判「是谁的」。
//
// 三条纪律：
//   1. 技能正文只经 SkillStore.plan/apply（备份→正文→索引）；旁挂标记是账本，与正文件彻底同名脱钩；
//   2. 归档绝不用 SkillStore.archive：`.deleted-*` 会被 SeedSkills.hasDeletedTrace 认成「用户删过」
//      并写进拒绝名单 ⇒ 用 apply 自带的 `.bak-` 备份，戳上带 seed-v 标记；
//   3. 每条独立 try/catch、失败只落 RunLog：升级在启动后的机会窗口跑，不许影响 App。
package com.example.zhengdao.baopu

import com.example.zhengdao.core.RunLog
import org.json.JSONObject
import java.io.File

/** 盘上正文相对内置清单独有的三档（纯判据，供测试直接咬单字节变异）。 */
internal enum class BodyVerdict { LATEST, BASELINE, OTHER }

/** 一次迁移对某条技能的结论（闭集五态；[hint] 只有 [UpgradeOutcome.ModifiedByUser] 非空）。 */
internal sealed interface UpgradeOutcome {
    val name: String
    val hint: String?

    data class Upgraded(
        override val name: String,
        val from: Int,
        val to: Int,
        val archivedAs: String,
    ) : UpgradeOutcome {
        override val hint: String? get() = null
    }

    /** 已经是内置最新版（含刚装好的 v2）。 */
    data class AlreadyCurrent(override val name: String) : UpgradeOutcome {
        override val hint: String? get() = null
    }

    /** 种子系但被人改过：不覆盖，只提示。 */
    data class ModifiedByUser(
        override val name: String,
        val from: Int,
        val to: Int,
        override val hint: String,
    ) : UpgradeOutcome

    /** 用户自建同名：不覆盖、不提示。 */
    data class UserOwned(override val name: String) : UpgradeOutcome {
        override val hint: String? get() = null
    }

    /** 没装／被拒／读不到资产／写失败／已回滚：不升级，原因进 RunLog。 */
    data class Left(override val name: String, val why: String) : UpgradeOutcome {
        override val hint: String? get() = null
    }
}

/** 一次机会窗口跑完的汇总（UI 拿 [modified] 的结构化字段渲染中英提示）。 */
internal data class UpgradeReport(val outcomes: List<UpgradeOutcome>) {
    val upgraded: List<UpgradeOutcome.Upgraded> get() = outcomes.filterIsInstance<UpgradeOutcome.Upgraded>()
    val modified: List<UpgradeOutcome.ModifiedByUser> get() = outcomes.filterIsInstance<UpgradeOutcome.ModifiedByUser>()
    val hints: List<String> get() = outcomes.mapNotNull { it.hint }
}

internal object SeedSkillUpgrade {

    /** 旁挂账本（不带正文件名前缀，连语义上都不碰「技能正文写入口唯一」门禁）。 */
    const val MARKER_FILE = "_seed-version.json"

    /** 归档备份戳里的识别段：与用户手动编辑产生的普通备份区分开。 */
    const val ARCHIVE_TAG = "seed-v"

    const val AUDIT_ACTION = "seed_upgrade"

    /** 与 SkillStore.plan 的草稿标同值（升级保留原状态，索引标不许跟着丢）。 */
    const val DRAFT_MARK = "（草稿·未验证，用前先 read_file 核对）"

    /** 一次机会窗口的参数包（避免长参数列表，也让每条判定都用同一份读数）。 */
    private data class Window(
        val readAsset: (String) -> String?,
        val globalRoot: File,
        val projectRoot: File,
        val personaFiles: PersonaFiles,
        val backupStamp: String,
        val nowMs: Long,
    )

    /** 覆盖前读到的盘上状态（写盘后 beforeSha 就没法重算了，必须提前带走）。 */
    private data class OnDiskState(
        val nameDir: File,
        val target: File,
        val onDisk: String,
        val builtin: String,
        val beforeSha: String,
    )

    /**
     * 正文三档判定：**同一把尺子**量盘上文件、旧版基线、APK 内置新版。
     * 指纹覆盖剥头后的原始字节（机器头由系统按使用/转正重算，不进 hash；行尾字节要算）。
     */
    fun classify(onDiskBody: String, baselineBody: String, builtinBody: String): BodyVerdict {
        val current = SeedSkillCatalog.bodySha(onDiskBody)
        if (current.isEmpty()) return BodyVerdict.OTHER
        if (current == SeedSkillCatalog.bodySha(builtinBody)) return BodyVerdict.LATEST
        if (current == SeedSkillCatalog.bodySha(baselineBody)) return BodyVerdict.BASELINE
        return BodyVerdict.OTHER
    }

    /**
     * 启动后的机会窗口里跑一次（调用方负责 IO 线程与异常隔离）。
     * 每条独立 try/catch：任何一条炸了都只落日志，绝不把异常抛给启动流程。
     */
    fun upgrade(
        readAsset: (String) -> String?,
        globalRoot: File,
        projectRoot: File,
        personaFiles: PersonaFiles,
        backupStamp: String,
        nowMs: Long = System.currentTimeMillis(),
    ): UpgradeReport {
        val w = Window(readAsset, globalRoot, projectRoot, personaFiles, backupStamp, nowMs)
        val outcomes = SeedSkillCatalog.upgradable().map { entry ->
            runCatching { upgradeOne(entry, w) }
                .getOrElse { t ->
                    RunLog.log("种子技能升级异常：${entry.name}（${t.javaClass.simpleName}）")
                    UpgradeOutcome.Left(entry.name, "异常：${t.javaClass.simpleName}")
                }
        }
        outcomes.forEach { logOutcome(it) }
        return UpgradeReport(outcomes)
    }

    private fun upgradeOne(entry: SeedSkillCatalog.Entry, w: Window): UpgradeOutcome {
        val name = entry.name
        if (name in SeedSkills.readDeclined(globalRoot = w.globalRoot)) {
            return UpgradeOutcome.Left(name, "用户删过，已在拒绝名单")
        }
        val skillDir = SkillRules.globalSkillDir(w.globalRoot)
        val nameDir = File(skillDir, name)
        val target = File(nameDir, SkillRules.SKILL_FILE)
        if (!target.isFile) return UpgradeOutcome.Left(name, "还没装，交给首装")
        // 用户明确回滚过 ⇒ 尊重他的选择，下次启动不再自动升回去（也不再提示）。
        if (isRolledBack(nameDir)) return UpgradeOutcome.Left(name, "用户已回滚到旧版，不再自动升级")
        // 来源闸门：没有种子痕迹的一律是「用户自建同名」，hash 命中也不许放行。
        if (!hasSeedTrace(skillDir, name) && !hasMarker(nameDir, name)) {
            return UpgradeOutcome.UserOwned(name)
        }
        val onDisk = runCatching { target.readText(Charsets.UTF_8) }.getOrNull()
            ?: return UpgradeOutcome.Left(name, "盘上正文读不出")
        val builtin = w.readAsset(SeedSkills.assetPathOf(name))
            ?: return UpgradeOutcome.Left(name, "assets 里读不到内置新版")
        val baseline = entry.previousBody ?: return UpgradeOutcome.AlreadyCurrent(name)
        return when (classify(onDisk, baseline, builtin)) {
            BodyVerdict.LATEST -> {
                ensureMarker(nameDir, name, entry.version, w.nowMs)
                UpgradeOutcome.AlreadyCurrent(name)
            }
            BodyVerdict.BASELINE -> writeUpgrade(
                entry, w, OnDiskState(nameDir, target, onDisk, builtin, SkillAudit.shaOf(target)),
            )
            BodyVerdict.OTHER -> UpgradeOutcome.ModifiedByUser(
                name, entry.fromVersion, entry.version,
                SeedSkillCatalog.hint(entry.fromVersion, entry.version),
            )
        }
    }

    /** 判据 + plan + apply：只换正文，旧机器头（status/uses）原样保留。 */
    private fun writeUpgrade(
        entry: SeedSkillCatalog.Entry,
        w: Window,
        s: OnDiskState,
    ): UpgradeOutcome {
        val name = entry.name
        val findings = SkillWritePolicy.forbiddenFindings(s.builtin)
        if (findings.isNotEmpty()) {
            return UpgradeOutcome.Left(name, "内置新版正文不干净：${findings.joinToString("、")}")
        }
        val bytes = SkillWritePolicy.utf8Bytes(s.builtin)
        if (bytes == null || bytes > SkillWritePolicy.BODY_MAX_BYTES) {
            return UpgradeOutcome.Left(name, "内置新版超 ${SkillWritePolicy.BODY_MAX_BYTES} 字节或编码不合法")
        }
        val content = SeedSkillCatalog.stripMachineHeader(s.builtin)
        val head = s.onDisk.lineSequence().firstOrNull()?.trim().orEmpty()
        val keptHead = if (head.startsWith("<!--") && head.contains("status=")) head else SkillStore.draftHeader()
        val mark = if (SkillStore.statusOf(s.onDisk) == "draft") DRAFT_MARK else ""
        val proposal = SkillProposal(
            scope = SkillScope.GLOBAL,
            name = name,
            body = content,
            reason = "种子技能版本迁移 v${entry.fromVersion}→v${entry.version}",
            draft = false,
        )
        // 戳以数字时间戳开头：不劫持技能页「恢复到上一版」（那条按备份名字典序取最新）。
        val stamp = "${w.backupStamp}-${ARCHIVE_TAG}${entry.fromVersion}-to-v${entry.version}"
        val planned = SkillStore.plan(proposal, w.projectRoot, w.globalRoot, stamp)
        planned.blocked?.let { return UpgradeOutcome.Left(name, it) }
        // draft=false 只为让 apply 不再补一个头：正文里已经带着原来那一行头（含 uses/status）。
        // 索引行则按旧状态重新挂草稿标，避免「正文是草稿、索引却写正式」的半条。
        val adjusted = planned.copy(
            proposal = proposal.copy(body = "$keptHead\n$content"),
            indexEntry = SkillRules.IndexLine(
                name = name,
                scenario = SkillStore.purposeOf(content) + mark,
                scope = MemoryScope.GLOBAL,
            ),
        )
        val indexText = w.personaFiles.read(PersonaLayer.B_GLOBAL).orEmpty()
        return when (val r = SkillStore.apply(adjusted, w.personaFiles, indexText)) {
            is SkillWriteResult.Rejected -> UpgradeOutcome.Left(name, r.reason)
            is SkillWriteResult.Written -> {
                val archived = r.backup.orEmpty()
                finalizeUpgrade(entry, w, s, archived, r.indexUpdated)
                UpgradeOutcome.Upgraded(name, entry.fromVersion, entry.version, archived)
            }
        }
    }

    /** 升级留痕：审计一行「vN→vM，来源=种子」＋旁挂版本标记（都失败也只出声，不回滚升级）。 */
    private fun finalizeUpgrade(
        entry: SeedSkillCatalog.Entry,
        w: Window,
        s: OnDiskState,
        archived: String,
        indexUpdated: Boolean,
    ) {
        val audited = SkillAudit.record(
            skillFile = s.target,
            scope = "global",
            name = entry.name,
            action = AUDIT_ACTION,
            reason = "v${entry.fromVersion}→v${entry.version}，来源=种子",
            beforeSha = s.beforeSha,
            indexUpdated = indexUpdated,
            nowMs = w.nowMs,
            source = SeedSkillCatalog.SOURCE_SEED,
        )
        if (!audited) RunLog.log("种子技能升级审计行写入失败：${entry.name}")
        if (!writeMarker(s.nameDir, entry.name, entry.version, entry.fromVersion, archived, w.nowMs)) {
            RunLog.log("种子技能版本标记写入失败：${entry.name}")
        }
    }

    /** 回滚：精确核对「最新备份 == 本次升级归档」，仍走 SkillStore.restore；并落账防止下次又升回去。 */
    fun rollback(globalRoot: File, name: String, backupStamp: String): Boolean {
        val target = File(File(SkillRules.globalSkillDir(globalRoot), name), SkillRules.SKILL_FILE)
        val nameDir = target.parentFile ?: return false
        val latest = SkillStore.backupsOf(target).firstOrNull() ?: return false
        if (!latest.name.contains(ARCHIVE_TAG)) return false
        val recorded = markerArchive(nameDir) ?: return false
        if (latest.name != recorded) return false
        if (SkillStore.restore(target, backupStamp) !is SkillWriteResult.Written) return false
        val entry = SeedSkillCatalog.entryOf(name) ?: return true
        val marked = writeMarker(
            nameDir = nameDir, name = name, version = entry.fromVersion,
            from = entry.fromVersion, archived = recorded, nowMs = System.currentTimeMillis(),
            rolledBack = true,
        )
        if (!marked) RunLog.log("种子技能回滚标记写入失败：$name（下次启动可能再升一次）")
        return true
    }

    private fun markerFile(nameDir: File): File = File(nameDir, MARKER_FILE)

    private fun hasMarker(nameDir: File, name: String): Boolean = runCatching {
        val f = markerFile(nameDir)
        f.isFile && JSONObject(f.readText(Charsets.UTF_8)).optString("name") == name
    }.getOrDefault(false)

    private fun isRolledBack(nameDir: File): Boolean = runCatching {
        val f = markerFile(nameDir)
        f.isFile && JSONObject(f.readText(Charsets.UTF_8)).optBoolean("rolledBack", false)
    }.getOrDefault(false)

    private fun markerArchive(nameDir: File): String? = runCatching {
        val f = markerFile(nameDir)
        if (!f.isFile) null else JSONObject(f.readText(Charsets.UTF_8)).optString("archivedAs").takeIf { it.isNotEmpty() }
    }.getOrDefault(null)

    /** 首装（未经本通道）的 v2 也补一个标记，让下一版不必只靠审计行认来源。 */
    private fun ensureMarker(nameDir: File, name: String, version: Int, nowMs: Long) {
        if (hasMarker(nameDir, name)) return
        writeMarker(nameDir, name, version, version, "", nowMs)
    }

    private fun writeMarker(
        nameDir: File,
        name: String,
        version: Int,
        from: Int,
        archived: String,
        nowMs: Long,
        rolledBack: Boolean = false,
    ): Boolean = runCatching {
        val o = JSONObject()
            .put("name", name)
            .put("builtinVersion", version)
            .put("from", from)
            .put("archivedAs", archived)
            .put("ts", nowMs)
            .put("source", SeedSkillCatalog.SOURCE_SEED)
        if (rolledBack) o.put("rolledBack", true)
        markerFile(nameDir).writeText(o.toString(), Charsets.UTF_8)
        true
    }.getOrDefault(false)

    /** 种子痕迹：207 批首装的 `action=seed` 或本单升级留的 `action=seed_upgrade`。 */
    private fun hasSeedTrace(skillDir: File, name: String): Boolean {
        val text = runCatching { SkillAudit.fileIn(skillDir).readText(Charsets.UTF_8) }.getOrDefault("")
        if (text.isBlank()) return false
        return text.lines().any { line ->
            (line.contains("\"action\":\"seed\"") || line.contains("\"action\":\"seed_upgrade\"")) &&
                line.contains("\"name\":\"$name\"")
        }
    }

    private fun logOutcome(o: UpgradeOutcome) {
        val line = when (o) {
            is UpgradeOutcome.Upgraded ->
                "种子技能升级：${o.name} v${o.from}→v${o.to}（旧版归档 ${o.archivedAs}）"
            is UpgradeOutcome.ModifiedByUser -> "种子技能不覆盖：${o.name}（${o.hint}）"
            is UpgradeOutcome.UserOwned -> "种子技能同名占用：${o.name}（用户自建，永不覆盖、不提示）"
            is UpgradeOutcome.Left -> "种子技能升级跳过：${o.name}（${o.why}）"
            is UpgradeOutcome.AlreadyCurrent -> ""
        }
        if (line.isNotEmpty()) RunLog.log(line)
    }
}
