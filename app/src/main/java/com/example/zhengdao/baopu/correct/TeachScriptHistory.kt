// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 C（阶段三前段）· 纠正改脚本的**版本留痕与回滚**（判据④）。
//
// 落点：`技能/<名>/修订/<旧版本12位>/`——每次纠正写盘前，把**当时的** `回放.json` 与
// `验收.json` 原样拷进去（上一版的完整机器态）；纠正本体仍走 `TeachReplayStore.saveConfirmed`
// （凭证/不可逆/入库三闸照走，一个字节不绕）；写完落 `SkillAudit` 一行
// `action=script_corrected`（beforeSha=旧版/afterSha=新版）。
// 回滚＝取**最新**一份修订，把两份文件原样放回＋落 `script_rollback` 一行——
// 「回滚后脚本回到上一版且记录 rollback」（判据④后半句）。
//
// 边界：
//   · SKILL.md 的人读正文由 saveConfirmed 按新版生成，回滚后不自动重写（机器真源是
//     回放.json；正文漂移如实挂账，下次任何确认写盘会刷新）；
//   · 验收.json 随修订一起备份/恢复——旧版的回放样本不丢（643 的「同版本成功 ≥3 次」
//     在回滚后照常成立）；
//   · 修订目录只增不删（留痕即审计；量级＝纠正次数，人的动作撑不起清理需求）。
package com.example.zhengdao.baopu.correct

import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.SkillAudit
import com.example.zhengdao.baopu.TeachReplayStore
import com.example.zhengdao.baopu.TeachSkill
import java.io.File

/** 一次修订的读数（回滚列表与账行共用）。 */
internal data class ScriptRevision(val version: String, val dir: File, val savedAtMs: Long)

internal object TeachScriptHistory {

    const val DIR_NAME = "修订"
    const val ACTION_CORRECTED = "script_corrected"
    const val ACTION_ROLLBACK = "script_rollback"

    /** 纠正写盘：备份上一版 → saveConfirmed 三闸 → 留痕。返回人读结果（成功含新旧版本）。
     *  globalRoot/personaFiles 由调用方（gateway，有 appCtx）注入——本类不裸构造人设件。 */
    fun applyCorrection(
        projectRoot: File,
        globalRoot: File,
        personaFiles: PersonaFiles,
        name: String,
        edited: TeachSkill,
        reason: String,
        nowMs: Long,
    ): String {
        val current = TeachReplayStore.load(projectRoot, name)
            ?: return "没有这个回放技能：$name——纠正无从谈起"
        val oldVer = TeachReplayStore.sha256Of(current).take(12)
        backup(projectRoot, name, oldVer)
        val newVer = TeachReplayStore.sha256Of(edited).take(12)
        val result = TeachReplayStore.saveConfirmed(
            draft = com.example.zhengdao.baopu.TeachSkillDraft(edited, warnings = emptyList(), summary = reason),
            projectRoot = projectRoot,
            globalRoot = globalRoot,
            personaFiles = personaFiles,
            stampText = nowMs.toString(36),
        )
        if (result.written == null) return "纠正没有落盘：${result.reason}（上一版原样保留）"
        SkillAudit.record(
            skillFile = TeachReplayStore.skillMdFile(projectRoot, name),
            scope = "teach-correct",
            name = name,
            action = ACTION_CORRECTED,
            reason = reason,
            beforeSha = oldVer,
            indexUpdated = false,
            nowMs = nowMs,
            source = "边看边纠",
        )
        return "已确认改脚本：$name $oldVer → $newVer（上一版已备份到 修订/$oldVer，留痕 $ACTION_CORRECTED）" +
            "——用 teach_correct_resume 从目标步重跑"
    }

    /** 回滚到上一版：最新修订的两份文件原样放回＋留痕 `script_rollback`。 */
    fun rollbackToPrevious(projectRoot: File, name: String, nowMs: Long): String {
        val skillDir = File(projectRoot, "技能/$name")
        val revRoot = File(skillDir, DIR_NAME)
        val latest = revRoot.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
            ?: return "没有可回滚的修订——这份技能还没被纠正改过"
        val restored = runCatching {
            val replay = File(latest, TeachReplayStore.REPLAY_FILE)
            if (!replay.isFile) return "修订目录缺回放.json（$latest）——如实拒，不猜"
            val current = TeachReplayStore.load(projectRoot, name)
            val currentVer = current?.let { TeachReplayStore.sha256Of(it).take(12) } ?: "-"
            replay.copyTo(File(skillDir, TeachReplayStore.REPLAY_FILE), overwrite = true)
            val acceptance = File(latest, TeachReplayStore.ACCEPTANCE_FILE)
            if (acceptance.isFile) {
                acceptance.copyTo(File(skillDir, TeachReplayStore.ACCEPTANCE_FILE), overwrite = true)
            }
            currentVer
        }.getOrElse { return "回滚失败：${it.javaClass.simpleName}" }
        SkillAudit.record(
            skillFile = TeachReplayStore.skillMdFile(projectRoot, name),
            scope = "teach-correct",
            name = name,
            action = ACTION_ROLLBACK,
            reason = "回滚到上一版（修订/${latest.name}）",
            beforeSha = restored,
            indexUpdated = false,
            nowMs = nowMs,
            source = "边看边纠",
        )
        return "已回滚：$name 回到上一版（${latest.name}），留痕 $ACTION_ROLLBACK"
    }

    /** 修订列表（旧→新；UI 与账核对用）。 */
    fun revisionsOf(projectRoot: File, name: String): List<ScriptRevision> {
        val revRoot = File(File(projectRoot, "技能/$name"), DIR_NAME)
        return revRoot.listFiles()?.filter { it.isDirectory }?.map { ScriptRevision(it.name, it, it.lastModified()) }
            ?.sortedBy { it.version } ?: emptyList()
    }

    /** 上一版备份（回放.json＋验收.json 原样拷进 修订/<旧版本>/）。 */
    private fun backup(projectRoot: File, name: String, oldVer: String) {
        runCatching {
            val skillDir = File(projectRoot, "技能/$name")
            val revDir = File(skillDir, "$DIR_NAME/$oldVer").apply { mkdirs() }
            File(skillDir, TeachReplayStore.REPLAY_FILE).takeIf { it.isFile }?.copyTo(File(revDir, TeachReplayStore.REPLAY_FILE), overwrite = true)
            File(skillDir, TeachReplayStore.ACCEPTANCE_FILE).takeIf { it.isFile }?.copyTo(File(revDir, TeachReplayStore.ACCEPTANCE_FILE), overwrite = true)
        }
    }
}
