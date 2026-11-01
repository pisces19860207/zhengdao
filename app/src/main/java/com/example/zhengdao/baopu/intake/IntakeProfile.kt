// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百四十五批 · 准入 ④**画像层**＋硬规矩（纯函数：不碰盘、不读时钟，时刻由调用方喂）。
//
// 三条硬规矩（派工单原文，逐条落在这里）：
//   ① **未过品行考 ⇒ 只能接只读任务**——品行考四道题有一道没过，写任务一律不接；
//   ② **越权一次 ⇒ 降权并记违规**——一次越权立刻降到只读档，画像里记一笔污点；
//   ③ **版本变化 ⇒ 重探**——版本指纹对不上，旧考核全部作废，重探完才按新考核放行。
//
// 两处刻意的"不做"：
//   * 不做职位表、不做工位图（派工单硬规矩）——权限档只有 FULL/READ_ONLY 两档；
//   * 自报（简历）不参与任何放行判定——画像里根本没有自报字段，放行只看探针＋品行考＋行为账。
package com.example.zhengdao.baopu.intake

/** 任务收尾的四种账：办成 / 被退回 / 越权。画像只吃这三味。 */
enum class TaskResult { SUCCESS, RETURNED, VIOLATION }

object IntakeProfile {

    /** 初次准入：五枚探针＋四道品行考判完，建画像。品行考全过才给 FULL，否则只读。 */
    fun intake(
        agentId: String,
        version: String,
        probes: List<ProbeOutcome>,
        conduct: List<ConductOutcome>,
    ): AgentProfile {
        val base = AgentProfile(
            agentId = agentId,
            vouchedVersion = version,
            probes = probes.associateBy { it.probe },
            conduct = conduct,
        )
        return base.copy(clearance = clearanceFor(base))
    }

    /** 权限档只由品行考决定（探针记档进画像，不单独降档——派工单硬规矩只挂品行考）。 */
    private fun clearanceFor(profile: AgentProfile): Clearance =
        if (profile.conductPassed) Clearance.FULL else Clearance.READ_ONLY

    /** 任务准接判定：只读任务谁来都能接；写任务要四关全过。 */
    fun canAccept(profile: AgentProfile, kind: TaskKind, currentVersion: String = profile.vouchedVersion): Boolean {
        if (kind == TaskKind.READ_ONLY) return true
        return profile.clearance == Clearance.FULL &&
            profile.conductPassed &&
            profile.vouchedVersion == currentVersion
    }

    /** 版本变化检测：与画像作证版本对不上 ⇒ 必须重探。 */
    fun needsReprobe(profile: AgentProfile, currentVersion: String): Boolean =
        profile.vouchedVersion != currentVersion

    /** 任务收尾记账：越权一次 ⇒ 降权＋记违规（硬规矩②）。 */
    fun onTaskResult(
        profile: AgentProfile,
        result: TaskResult,
        atMs: Long,
        detail: String,
    ): AgentProfile = when (result) {
        TaskResult.SUCCESS -> profile.copy(successes = profile.successes + 1)
        TaskResult.RETURNED -> profile.copy(returneds = profile.returneds + 1)
        TaskResult.VIOLATION -> profile.copy(
            violations = profile.violations + ViolationRecord(atMs, detail),
            clearance = Clearance.READ_ONLY,
        )
    }

    /**
     * 版本变化后的重探：探针＋品行考全部重考，画像作证版本换新。
     * 重探能让"没考过/考砸了"恢复 FULL；**洗不掉越权污点**——有违规记录的，重探后仍只读，
     * 恢复只能走 [reinstate]（显式人事动作，不随重探自动发生）。
     */
    fun reprobe(
        profile: AgentProfile,
        newVersion: String,
        probes: List<ProbeOutcome>,
        conduct: List<ConductOutcome>,
    ): AgentProfile {
        val reExamined = profile.copy(
            vouchedVersion = newVersion,
            probes = probes.associateBy { it.probe },
            conduct = conduct,
        )
        return reExamined.copy(clearance = clearanceFor(reExamined))
            .let { if (it.violations.isNotEmpty()) it.copy(clearance = Clearance.READ_ONLY) else it }
    }

    /** 显式恢复 FULL：只把品行考过了的人捞回来；品行考仍不过，恢复了也还是只读。 */
    fun reinstate(profile: AgentProfile): AgentProfile =
        profile.copy(clearance = clearanceFor(profile))

    /** 画像一行（给人看；字段顺序就是考核顺序）。 */
    fun portrait(profile: AgentProfile): String {
        val probesHit = ProbeId.entries.count { profile.probes[it]?.pass == true }
        val conduct = if (profile.conductPassed) "过" else "不过"
        val version = profile.vouchedVersion
        return "版本 $version · 探针 $probesHit/5 · 品行考 $conduct · " +
            "成功 ${profile.successes} · 退回 ${profile.returneds} · 越权 ${profile.violations.size} · " +
            "权限 ${profile.clearance.name}"
    }
}
