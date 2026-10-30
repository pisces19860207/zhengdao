// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第三百批 · 契约漂移的**落盘壳**（纯规则在 `DriftCheck`）。
//
// 落两样东西：
// - `技能/<名>/漂移.json` —— 状态快照（连续次数／最近结论／停用落章／校准次数）。
//   它**不是第二本账**：只存事件推不出来的东西；停用另记 `技能/_变更.jsonl` 一行、
//   并进 290 的「未办成单」（`记忆/未办成单.jsonl`），三处按同一时刻对得上。
// - `技能/<名>/SKILL.md` —— 重新校准时**只改指纹行**，正文其余字节原样；
//   写盘走 184 的唯一写口 `SkillStore.plan/apply`（本类不自己 fopen 技能文件）。
//
// 三条纪律：
// 1. **校准必须有人**：[recalibrate] 的 `confirmedBy` 空 ⇒ 直接拒，盘上一个字节不动
//    （负例④"静默重写契约"在这条路上没有出口——它连写口都不进）；
// 2. **停用不删不改**：自动停用只写 `漂移.json` 与两本账，SKILL.md 一个字不改；
//    「重新校准」也不再补第二行账——`SkillStore.apply` 为那次改动写的那一行
//    （reason 里带「重新校准」与确认人）就是那条账，一次改动一行才对得上；
// 3. **记账失败不挡事**：审计/未办成单写失败只 log，漂移状态本身照常落盘。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.scout.UnfinishedLedger
import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONObject

/** 一次重新校准的结局（落盘之后）。 */
internal sealed interface RecalibrateOutcome {

    data class Done(
        val fingerprint: DriftFingerprint,
        val state: DriftState,
        val note: String,
        val backup: String?,
    ) : RecalibrateOutcome

    data class Refused(val reason: String) : RecalibrateOutcome
}

internal object DriftStore {

    /** 状态快照文件名（与 `SKILL.md`／`回放.json`／`验收.json` 同目录旁挂）。 */
    const val DRIFT_FILE = "漂移.json"

    const val SCHEMA = "zl-drift/1"

    fun file(projectRoot: File, name: String): File =
        File(SkillRules.projectSkillDir(projectRoot), "$name/$DRIFT_FILE")

    fun skillMdFile(projectRoot: File, name: String): File =
        File(SkillRules.projectSkillDir(projectRoot), "$name/${SkillRules.SKILL_FILE}")

    /** 读状态；没有/读不出 ⇒ 空状态（**没有账不等于有罪**：第一次跑总是没有历史）。 */
    fun load(projectRoot: File, name: String): DriftState {
        val f = file(projectRoot, name)
        if (!f.isFile) return DriftState()
        val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return DriftState()
        return fromJson(text) ?: DriftState()
    }

    /** 写状态（临时文件转正：半份 JSON 不会被读成"停用解除"）。 */
    fun save(projectRoot: File, name: String, state: DriftState): Boolean = runCatching {
        val f = file(projectRoot, name)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "$DRIFT_FILE.tmp")
        tmp.writeText(toJson(state).toString(), Charsets.UTF_8)
        if (f.exists() && !f.delete()) return@runCatching false
        tmp.renameTo(f)
    }.getOrDefault(false)

    /**
     * 记一次比对结论（执行前比完就记，不等到执行完）。
     *
     * 第 3 次不一致时：状态落章 ＋ `_变更.jsonl` 一行 ＋ 未办成单一笔（原因＝现实漂移）。
     * [unfinishedDirs] 由调用方给（`ScoutDirs.ledgerDirs(ctx)`）；给空表 ⇒ 只记状态与审计。
     */
    fun recordVerdict(
        projectRoot: File,
        name: String,
        verdict: DriftVerdict,
        atMs: Long,
        unfinishedDirs: List<File> = emptyList(),
    ): DriftUpdate {
        val update = DriftCheck.record(load(projectRoot, name), verdict, atMs)
        if (!save(projectRoot, name, update.state)) {
            RunLog.log("漂移状态写盘失败：$name（本次判定照常返回，盘上可能还是旧账）")
        }
        if (update.disabledNow) {
            update.notes.forEach { RunLog.log("漂移停用：$name｜$it") }
            noteDisable(projectRoot, name, update.state, unfinishedDirs, atMs)
        }
        return update
    }

    /** 停用的两笔账：技能变更账（可回查）＋ 未办成单（用户看得见）。失败只 log。 */
    private fun noteDisable(
        projectRoot: File,
        name: String,
        state: DriftState,
        unfinishedDirs: List<File>,
        atMs: Long,
    ) {
        val md = skillMdFile(projectRoot, name)
        runCatching {
            SkillAudit.record(
                skillFile = md,
                scope = "project",
                name = name,
                action = "skill_drift_disable",
                reason = "连续 ${DriftCheck.DISABLE_STREAK} 次指纹对不上（${state.disabledWhy.orEmpty()}）",
                beforeSha = SkillAudit.shaOf(md),
                indexUpdated = false,
                nowMs = atMs,
                source = "drift",
            )
        }.onFailure { RunLog.log("漂移停用审计写失败：$name（${it.javaClass.simpleName}）") }
        if (unfinishedDirs.isEmpty()) return
        runCatching { UnfinishedLedger(unfinishedDirs).record(DriftCheck.unfinishedItem(name, state)) }
            .onFailure { RunLog.log("未办成单写失败：$name（${it.javaClass.simpleName}）") }
    }

    /**
     * 用户确认"这是新版"之后：把现场指纹写回契约。
     *
     * 顺序：**先判人**（空 ⇒ 拒，一个字节不写）→ 纯层算新正文 → 唯一写口落盘 → 记校准账 → 状态复位。
     * 落盘失败 ⇒ 状态也不动（免得"账上已恢复、盘上还是旧指纹"）。
     */
    fun recalibrate(
        projectRoot: File,
        globalRoot: File,
        personaFiles: PersonaFiles,
        name: String,
        live: DriftObservation,
        confirmedBy: String,
        stampText: String,
        nowMs: Long,
    ): RecalibrateOutcome {
        if (confirmedBy.isBlank()) {
            return RecalibrateOutcome.Refused("重新校准必须由人确认（\"这是新版\"）——机器不许自己改写契约")
        }
        val md = skillMdFile(projectRoot, name).takeIf { it.isFile }
            ?: return RecalibrateOutcome.Refused("没有这条技能的 SKILL.md：$name")
        val body = runCatching { md.readText(Charsets.UTF_8) }.getOrNull()
            ?: return RecalibrateOutcome.Refused("SKILL.md 读不出来：$name")
        val planned = when (
            val r = DriftCheck.recalibrate(body, live, confirmedBy, load(projectRoot, name), nowMs)
        ) {
            is RecalibrationResult.Refused -> return RecalibrateOutcome.Refused(r.reason)
            is RecalibrationResult.Planned -> r
        }
        val draft = SkillStore.statusOf(body) == "draft"
        val proposal = SkillProposal(
            scope = SkillScope.PROJECT,
            name = name,
            body = planned.body,
            reason = planned.note,
            draft = draft,
        )
        val plan = SkillStore.plan(proposal, projectRoot, globalRoot, stampText)
        plan.blocked?.let { return RecalibrateOutcome.Refused("校准没写成：$it") }
        val written = when (
            val r = SkillStore.apply(plan, personaFiles, personaFiles.read(PersonaLayer.C_PROJECT).orEmpty())
        ) {
            is SkillWriteResult.Rejected -> return RecalibrateOutcome.Refused("校准没写成：${r.reason}")
            is SkillWriteResult.Written -> r
        }
        if (!save(projectRoot, name, planned.state)) {
            RunLog.log("校准后状态写盘失败：$name（正文已换新，下次比对会重算）")
        }
        // 「重新校准」那一条账**不重复写**：`SkillStore.apply` 已经为这次改动写了 `_变更.jsonl`
        // 一行（action=update，reason＝`planned.note`，里面带「重新校准」与确认人）。
        // 一次改动一行，才有得对账；再补一行同义账只会让人以为改了两次。
        return RecalibrateOutcome.Done(planned.fingerprint, planned.state, planned.note, written.backup)
    }

    // ── 状态序列化（固定键序；空字段不写，字节稳定）────────────────────────────

    fun toJson(state: DriftState): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("streak", state.streak)
        put("last", state.lastVerdict)
        put("lastWhy", state.lastWhy)
        put("at", state.lastAtMs)
        put("digest", state.lastDigest)
        put("recalibrations", state.recalibrations)
        state.disabledAtMs?.let { put("disabledAt", it) }
        state.disabledWhy?.let { put("disabledWhy", it) }
        state.lastRecalibratedAtMs?.let { put("recalibratedAt", it) }
    }

    fun fromJson(text: String): DriftState? = runCatching {
        val o = JSONObject(text)
        if (o.optString("schema") != SCHEMA) return@runCatching null
        DriftState(
            streak = o.optInt("streak", 0).coerceAtLeast(0),
            lastVerdict = o.optString("last"),
            lastWhy = o.optString("lastWhy"),
            lastAtMs = o.optLong("at", 0L),
            lastDigest = o.optString("digest"),
            disabledAtMs = o.optLong("disabledAt", 0L).takeIf { it > 0L },
            disabledWhy = o.optString("disabledWhy").takeIf { it.isNotBlank() },
            recalibrations = o.optInt("recalibrations", 0).coerceAtLeast(0),
            lastRecalibratedAtMs = o.optLong("recalibratedAt", 0L).takeIf { it > 0L },
        )
    }.getOrNull()
}
