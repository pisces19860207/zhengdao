// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百批（2026-10-21）：**人批之后**的回滚执行面。
//
// 全仓唯一允许调 `SkillStore.restore` / `PersonaBackup.restore` 的地方就是这一个函数——
// 门禁测试按「源码里这两个调用只允许出现在本函数内」钉死（E3）。为什么这么狠：
// 一旦哪里偷偷接一条「自动回滚」，用户的文件就会在没有点头的情况下被换掉，
// 而这类事故是**不可逆**的（回滚本身也会覆盖当前版）。
//
// 两条纪律：
//   1. `approvedBy` 为空 ⇒ 直接拒，不碰文件；
//   2. 回滚失败要说清原因（现有 restore 的 Rejected 文案原样带出去，不自己编）。
package com.example.zhengdao.baopu

import java.io.File

/** 回滚结果（一句话足够 UI 说清楚）。 */
internal sealed class PostAdoptionRollbackResult {
    /** 真的换回去了；[backupName] 是「当前版被另存」的那份（可再退回来）。 */
    data class Done(val backupName: String) : PostAdoptionRollbackResult()

    /** 没做，原因可读。 */
    data class Rejected(val reason: String) : PostAdoptionRollbackResult()
}

internal object PostAdoptionRollback {

    /**
     * 用户点了「回滚」之后才执行（派工单 §4）。
     *
     * @param approvedBy 谁批的（用户操作来源；空 ⇒ 拒）
     * @param rollbackAvailable 采纳当时记下的「有没有备份」；false ⇒ 直接拒（不许现找一份凑）
     * @param skillFile 技能正文文件（技能通道）
     * @param personaFile 人设文件（索引提案通道）
     * @param stamp 回滚时给「当前版」存的备份时间戳
     */
    fun applyOnUserApproval(
        channel: PostAdoptionChannel,
        rollbackAvailable: Boolean,
        approvedBy: String,
        skillFile: File?,
        personaFile: File?,
        stamp: String,
    ): PostAdoptionRollbackResult {
        if (approvedBy.isBlank()) return PostAdoptionRollbackResult.Rejected("没有用户确认，不回滚")
        if (!rollbackAvailable) {
            return PostAdoptionRollbackResult.Rejected("这条采纳没有可用备份，退不回去")
        }
        return when (channel) {
            PostAdoptionChannel.LEARN_CARD, PostAdoptionChannel.PROPOSAL_CANDIDATE -> {
                if (skillFile == null || !skillFile.isFile) {
                    return PostAdoptionRollbackResult.Rejected("技能文件不在了，没有可回滚的目标")
                }
                when (val r = SkillStore.restore(skillFile, stamp)) {
                    is SkillWriteResult.Written ->
                        PostAdoptionRollbackResult.Done(r.backup ?: "（当前版没有另存）")

                    is SkillWriteResult.Rejected -> PostAdoptionRollbackResult.Rejected(r.reason)
                }
            }

            PostAdoptionChannel.SKILL_PROPOSAL -> {
                if (personaFile == null || !personaFile.isFile) {
                    return PostAdoptionRollbackResult.Rejected("人设文件不在了，没有可回滚的目标")
                }
                when (val r = PersonaBackup.restore(personaFile, stamp)) {
                    is PersonaBackupResult.Done -> PostAdoptionRollbackResult.Done(r.backup.name)
                    is PersonaBackupResult.Rejected -> PostAdoptionRollbackResult.Rejected(r.reason)
                }
            }
        }
    }
}
