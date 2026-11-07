// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十批 · 许愿流水线接线层：把既有零件接进 WishPorts。
// 不新造引擎/沙盒——技能走 SkillAutoWrite/SkillStore，试跑走 RehearsalRunner，
// 卡片走 CardBuild/CardFormat，内容走 OutputCitationGate，小说走 NovelProjectStore。
// 本件只提供工厂函数；装配点归 BaopuRuntime（与 SkillTools.spec 同层）。
package com.example.zhengdao.baopu.wish

import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.SkillAutoOutcome
import com.example.zhengdao.baopu.SkillAutoWrite
import com.example.zhengdao.baopu.SkillPromote
import com.example.zhengdao.baopu.SkillProposal
import com.example.zhengdao.baopu.SkillRules
import com.example.zhengdao.baopu.SkillScope
import com.example.zhengdao.baopu.SkillStore
import com.example.zhengdao.baopu.SkillWriteResult
import java.io.File

/** 技能产物的接线工厂（③草稿＋⑥转正/回滚；④试跑由调用方构造 RehearsalPlan）。 */
internal object WishWiring {

    /** ③ 草稿：走 SkillAutoWrite.tryAutoWrite（draft=true，不进生产）。 */
    fun skillWriteDraft(
        projectRoot: File,
        globalRoot: File,
        personaFiles: PersonaFiles,
        stamp: String,
    ): (WishContext, String) -> Boolean = { ctx, body ->
        writeDraft(ctx, projectRoot, globalRoot, personaFiles, stamp, body)
    }

    /** ⑥ 转正：canPromote 闸＋备份＋改头＋审计（与 SkillTools.lifecycle 同口径）。 */
    fun skillPromote(projectRoot: File, personaFiles: PersonaFiles, stamp: String): (WishContext) -> Boolean = { ctx ->
        promoteSkill(ctx, projectRoot, stamp)
    }

    /** ⑥ 回滚：走 SkillAutoWrite.undoAutoWrite（改名 .deleted-*，不真删）。 */
    fun skillRollback(projectRoot: File, personaFiles: PersonaFiles, stamp: String): (WishContext, String) -> Boolean = { ctx, _ ->
        rollbackSkill(ctx, projectRoot, personaFiles, stamp)
    }

    /**
     * ③ 真写草稿。名字必须先过 `sanitizeName`：`SkillWritePolicy` 的 NAME_CONFLICT 否决比对的是
     * `sanitizeName(p.name) != p.name.trim()`，直接把许愿原话当名字递进去 ⇒ 原文里的空格／标点
     * 一律命中否决 ⇒ 每次都是 NeedsConfirm，草稿一条也落不下去。转正／回滚找的是净化后的目录名，
     * 三条路必须同一个口径，否则写进去的和找不到的不是同一条。
     */
    private fun writeDraft(
        ctx: WishContext,
        projectRoot: File,
        globalRoot: File,
        personaFiles: PersonaFiles,
        stamp: String,
        body: String,
    ): Boolean {
        val name = skillName(ctx.wishText) ?: return false
        val proposal = SkillProposal(
            scope = SkillScope.PROJECT,
            name = name,
            body = body,
            reason = "许愿流水线 #${ctx.wishId}",
            draft = true,
        )
        val outcome = SkillAutoWrite.tryAutoWrite(proposal, projectRoot, globalRoot, personaFiles, stamp)
        return outcome is SkillAutoOutcome.Written
    }

    private fun promoteSkill(ctx: WishContext, projectRoot: File, stamp: String): Boolean {
        val name = skillName(ctx.wishText) ?: return false
        val file = File(File(SkillRules.projectSkillDir(projectRoot), name), SkillRules.SKILL_FILE)
        if (!file.isFile) return false
        val body = file.readText()
        if (!SkillStore.canPromote(body)) return false
        val backup = File(file.parentFile, "$name${SkillStore.BACKUP_MARK}$stamp")
        return runCatching {
            file.copyTo(backup, overwrite = false)
            file.writeText(SkillStore.promoteBody(body), Charsets.UTF_8)
            SkillPromote.auditPromote(file, "project", name, SkillPromote.REASON_FULL_USES)
        }.isSuccess
    }

    private fun rollbackSkill(ctx: WishContext, projectRoot: File, personaFiles: PersonaFiles, stamp: String): Boolean {
        val name = skillName(ctx.wishText) ?: return false
        val result = SkillAutoWrite.undoAutoWrite(name, projectRoot, personaFiles, stamp)
        return result is SkillWriteResult.Written
    }

    /** 技能名称安全化（与 SkillStore.sanitizeName 同口径，供外部构造 proposal 用）。 */
    fun skillName(wishText: String): String? = SkillStore.sanitizeName(wishText.take(32))
}