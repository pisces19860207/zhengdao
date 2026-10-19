// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：抱朴-方案-2026-10-16.md §9（项目记忆跟工作区、全局记忆单独一层；
// 文件是真相，数据库只是索引）；派工单第八批 §8（B 层跟 App、C 层跟工作区）。
package com.example.zhengdao.baopu

import java.io.File

/**
 * 人设三层（刀 0b · 2026-10-16）。
 *
 * - **A 内核**：[BaopuPreset] —— 身份与边界纪律，**只读，永不被提案改写**（判据 4）；
 * - **B 全局**：`<App 私有>/抱朴记忆/全局/人设.md` —— 跟 App 走，换工作区不丢；
 * - **C 项目**：`<工作区>/抱朴记忆/人设-本项目.md` —— 跟工作区走。
 *
 * 降级定版（程枢版 §17.7）：B/C **缺失 ⇒ 用空、不报错**（首次本来就没有）；
 * **超限 ⇒ 截断到上限并在会话里提示一次**（不许静默截断），上限 8k tokens。
 */
enum class PersonaLayer { A_KERNEL, B_GLOBAL, C_PROJECT }

/** 一层人设在组装时的形态（truncated = 是否被截断——截断必须可见）。 */
data class PersonaSegment(
    val layer: PersonaLayer,
    val text: String,
    val truncated: Boolean = false,
)

/** 提案状态。B/C 的改写走「提案 → 人确认 → 落盘」，A 不接受提案。 */
enum class PersonaProposalStatus { PENDING, APPLIED, REJECTED }

/** 一条人设改写提案（附理由——用户审的就是「为什么改」）。 */
data class PersonaProposal(
    val layer: PersonaLayer,
    val current: String,
    val proposed: String,
    val reason: String,
    val status: PersonaProposalStatus = PersonaProposalStatus.PENDING,
)

/** 人设的**纯判据**（无 I/O，可 JVM 单测）。 */
internal object PersonaRules {

    /** 单层人设 token 上限（程枢版 §17.7：人设不该比对话还长）。 */
    const val PERSONA_TOKEN_LIMIT = 8_000

    /**
     * 哪一层允许被提案改写。A 内核恒 false —— **任何代码路径都不能改它**
     * （判据 4；连同 [BaopuPreset] 的字符串一起只读）。
     */
    fun canWrite(layer: PersonaLayer): Boolean = when (layer) {
        PersonaLayer.A_KERNEL -> false
        PersonaLayer.B_GLOBAL, PersonaLayer.C_PROJECT -> true
    }

    /**
     * 拼装人设段：顺序恒为 A → B → C（追加判据 1：前缀越稳定越好）。
     * 缺失的层跳过（不报错）；超限的层截断并置 truncated（不静默）。
     */
    fun assemble(
        kernelText: String,
        globalText: String?,
        projectText: String?,
        tokensOf: (String) -> Int = { TokenEstimator.estimate(it) },
    ): List<PersonaSegment> {
        val out = mutableListOf<PersonaSegment>()
        out += clip(PersonaLayer.A_KERNEL, kernelText, tokensOf)
        if (!globalText.isNullOrBlank()) out += clip(PersonaLayer.B_GLOBAL, globalText, tokensOf)
        if (!projectText.isNullOrBlank()) out += clip(PersonaLayer.C_PROJECT, projectText, tokensOf)
        return out
    }

    private fun clip(layer: PersonaLayer, text: String, tokensOf: (String) -> Int): PersonaSegment {
        val tokens = tokensOf(text)
        if (tokens <= PERSONA_TOKEN_LIMIT) return PersonaSegment(layer, text, truncated = false)
        // 按字符比例留余量截断（估算式保守，多留 10% 缓冲），尾部明标
        val keep = (text.length * (PERSONA_TOKEN_LIMIT.toDouble() / tokens) * 0.9).toInt()
            .coerceAtMost(text.length)
        return PersonaSegment(layer, text.take(keep) + TRUNCATION_MARK, truncated = true)
    }

    /** 截断标记（进上下文，用户/模型都看得见）。 */
    const val TRUNCATION_MARK = "\n…（人设超长，已截断；完整内容在文件里）"

    /**
     * 生成一条改写提案。A 内核的提案**直接拒**（返回 null）——
     * 调用方据此回「A 内核不可提案改写」。
     */
    fun propose(
        layer: PersonaLayer,
        current: String,
        proposed: String,
        reason: String,
    ): PersonaProposal? {
        if (!canWrite(layer)) return null
        if (proposed.isBlank()) return null
        return PersonaProposal(
            layer = layer,
            current = current,
            proposed = proposed.trim(),
            reason = reason.trim(),
        )
    }

    /**
     * 2026-10-19（第五十八批）：C 层人设**追加-only**判据。
     *
     * 允许「在原文末尾追加新条款」，不允许改/删既有条款——它是约束文件，
     * 自治到「能改自己缰绳」就过头了（用户「按你的来」）。
     *
     * @param current 现有正文（可为空）
     * @param proposed 拟写入的完整正文
     */
    fun appendOnly(current: String, proposed: String): Boolean {
        val cur = current.trimEnd()
        if (cur.isEmpty()) return proposed.isNotBlank()
        val next = proposed.trimEnd()
        if (!next.startsWith(cur)) return false
        // 必须真的长了东西，别拿原文原样再写一遍
        return next.length > cur.length
    }

    /** 提案能否落盘：必须 PENDING 且层可写。A 内核的提案永远到不了 APPLIED。 */
    fun canApply(p: PersonaProposal): Boolean =
        p.status == PersonaProposalStatus.PENDING && canWrite(p.layer)
}

/**
 * 人设文件的读写壳（刀 0b）。
 *
 * 根目录由调用方注入（App 私有 / 工作区），**本类不碰 Context** ——
 * Context 绑定在 `BaopuMemory`（刀 1 接线用）；这里全部逻辑可用临时目录 JVM 直测。
 */
internal class PersonaFiles(
    private val globalRoot: File,
    private val projectRoot: File,
) {

    /** B 全局人设文件（跟 App）。 */
    fun globalFile(): File = File(globalRoot, FILE_PERSONA)

    /** C 项目人设文件（跟工作区）。 */
    fun projectFile(): File = File(projectRoot, FILE_PERSONA_PROJECT)

    fun fileOf(layer: PersonaLayer): File = when (layer) {
        PersonaLayer.A_KERNEL -> throw IllegalArgumentException("A 内核没有文件（只在 BaopuPreset 里，只读）")
        PersonaLayer.B_GLOBAL -> globalFile()
        PersonaLayer.C_PROJECT -> projectFile()
    }

    /** 读一层；不存在/读不了 ⇒ null（上层按「缺失用空」处理）。 */
    fun read(layer: PersonaLayer): String? {
        val f = fileOf(layer)
        if (!f.isFile) return null
        return runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
    }

    /**
     * 写一层。**只接受已确认的提案**（[PersonaRules.canApply] 复核）——
     * 直接调 write 绕过提案的调用方是自己破坏确认制。
     */
    fun applyProposal(p: PersonaProposal): Boolean {
        if (!PersonaRules.canApply(p)) return false
        val f = fileOf(p.layer)
        return runCatching {
            f.parentFile?.mkdirs()
            // 第三十七批 D 段：B/C 层写入前**自动**备份（`人设-本项目.md.bak-<stamp>`）。
            // 放在这里而不是各调用点：这是「写人设」的唯一道口，做成不变式才不会漏
            // （设置页删索引、技能转正、采纳提案、技能写索引全汇到这一处）。
            // 文件还不存在时它返回 null —— 首次写入不造空备份。
            PersonaBackup.backupBefore(f, PersonaBackup.nowStamp())
            f.writeText(p.proposed, Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    companion object {
        const val FILE_PERSONA = "人设.md"
        const val FILE_PERSONA_PROJECT = "人设-本项目.md"
    }
}
