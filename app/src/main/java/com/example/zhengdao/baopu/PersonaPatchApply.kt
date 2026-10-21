// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单 2026-10-20 第九十六批 §2/§3/§4（98 总单子项 B）。
//
// 这一层只做三件事，且**只有这三件事能写人设**：
//   [apply]       —— 御批之后落盘（作用域/同意范围/只许追加/写前备份全部在这里复核）
//   [importTyped] —— 只收 typed patch JSON（角色卡整卡在 [PersonaPatchRules.parsePatches] 就被拒）
//   [reset]       —— 清空一层正文（先备份，可回滚；不是删文件）
//
// 为什么不把判据写进 UI/工具：人设是**约束文件**，写入口必须只有一个真相。
// 工具（append_persona）、候选桥（86→88）、以后的设置页导入，一律调这里。
package com.example.zhengdao.baopu

import java.io.File

/** 用户在御批卡上选的范围。 */
enum class PersonaConsent {
    /** 只写当前工作区（C 层）。 */
    WORKSPACE,

    /** 明确同意写全局（只有 B 层能走这里）。 */
    GLOBAL,

    /** 拒绝：一个字节都不写。 */
    DECLINED,
}

/** 一次应用的结果（失败必须带原因，不许静默）。 */
sealed interface PersonaApplyResult {
    /** 成功。[backup] 是这次写入前自动留下的那份（首次写入为 null）。 */
    data class Applied(val layer: PersonaLayer, val rendered: String, val backup: File?) : PersonaApplyResult

    /** 拒绝：文件没动。 */
    data class Rejected(val reason: String) : PersonaApplyResult
}

/** 导入的结果（整批成功或整批拒，不做部分导入）。 */
sealed interface PersonaImportResult {
    data class Imported(val layer: PersonaLayer, val count: Int, val backup: File?) : PersonaImportResult

    data class Rejected(val reason: String) : PersonaImportResult
}

/**
 * 人设补丁的落盘壳（薄 I/O；判据在 [PersonaPatchRules]，文件读写走 [PersonaFiles]）。
 */
internal object PersonaPatchApply {

    /** 当前工作区的标识（C 层补丁用它做跨区判据）。 */
    fun workspaceIdOf(files: PersonaFiles): String? =
        runCatching { files.projectFile().parentFile?.canonicalPath }.getOrNull()

    /**
     * 按用户同意范围落盘。
     *
     * 四条硬判据（顺序即优先级，别调换）：
     * 1. 补丁本身合法（白名单/长度/必填）；
     * 2. **拒绝** ⇒ 不写（审计留一行）；
     * 3. 范围对齐：`WORKSPACE` 只能写 C 且 workspaceId 必须等于当前工作区；
     *    `GLOBAL` 只能写 B（C 层补丁不许「升级」成全局）；
     * 4. 只许追加（[PersonaRules.appendOnly]）——改/删既有条款一律拒。
     *
     * 写盘走 [PersonaFiles.applyProposal]：它会再复核 `canApply`（A 恒 false）并写前自动备份。
     */
    fun apply(
        patch: PersonaPatch,
        consent: PersonaConsent,
        currentWorkspaceId: String?,
        files: PersonaFiles,
        source: String = "卡片",
    ): PersonaApplyResult {
        PersonaPatchRules.validate(patch)?.let {
            PersonaAudit.rejected(it, source)
            return PersonaApplyResult.Rejected(it)
        }
        if (consent == PersonaConsent.DECLINED) {
            PersonaAudit.rejected("用户没有同意", source)
            return PersonaApplyResult.Rejected("用户没有同意（没有写任何人设）")
        }
        val layer = when (consent) {
            PersonaConsent.WORKSPACE -> {
                if (patch.scope != PersonaPatchScope.C_PROJECT) {
                    val why = "全局人设（B 层）必须明确选「全局」并同意；不能按本工作区写入"
                    PersonaAudit.rejected(why, source)
                    return PersonaApplyResult.Rejected(why)
                }
                if (patch.workspaceId != currentWorkspaceId || currentWorkspaceId.isNullOrBlank()) {
                    val why = "这条补丁属于别的工作区，不能写进当前工作区（C 层不跨区）"
                    PersonaAudit.rejected(why, source)
                    return PersonaApplyResult.Rejected(why)
                }
                PersonaLayer.C_PROJECT
            }
            PersonaConsent.GLOBAL -> {
                if (patch.scope != PersonaPatchScope.B_GLOBAL) {
                    val why = "只有 B 层（全局人设）能写到全局；C 层补丁只能写本工作区"
                    PersonaAudit.rejected(why, source)
                    return PersonaApplyResult.Rejected(why)
                }
                PersonaLayer.B_GLOBAL
            }
            PersonaConsent.DECLINED -> error("前面已处理")
        }
        val current = files.read(layer).orEmpty()
        val next = PersonaPatchRules.render(current, patch.sections)
        if (!PersonaRules.appendOnly(current, next)) {
            val why = "${layer.name} 人设只能追加：拟写入内容不是「原有正文 + 末尾新条款」"
            PersonaAudit.rejected(why, source)
            return PersonaApplyResult.Rejected(why)
        }
        val proposal = PersonaRules.propose(layer, current, next, patch.reason)
            ?: run {
                val why = "A 内核不可提案改写（${layer.name}）"
                PersonaAudit.rejected(why, source)
                return PersonaApplyResult.Rejected(why)
            }
        if (!files.applyProposal(proposal)) {
            val why = "写盘失败（人设文件没写进去）"
            PersonaAudit.rejected(why, source)
            return PersonaApplyResult.Rejected(why)
        }
        val label = if (layer == PersonaLayer.B_GLOBAL) "全局" else "工作区"
        PersonaAudit.applied(layer, label, source, patch.sections)
        return PersonaApplyResult.Applied(layer, next, PersonaBackup.latestOf(files.fileOf(layer)))
    }

    /** 读出一层正文用于导出（A 没有文件 ⇒ null）。 */
    fun export(files: PersonaFiles, layer: PersonaLayer): String? =
        if (layer == PersonaLayer.A_KERNEL) null else files.read(layer)

    /**
     * 导入 typed patch（**整批成功或整批拒**）。
     *
     * 先按给定层把整批渲染一遍（模拟追加链），任一环节不合法 ⇒ 直接拒、不写盘；
     * 全部通过才写一次 —— 半批入库比整批拒更难回滚。
     * 同意范围由**目标层**决定：B ⇒ GLOBAL，C ⇒ WORKSPACE（导入是用户自己发起的动作）。
     */
    fun importTyped(
        files: PersonaFiles,
        layer: PersonaLayer,
        rawJson: String,
        currentWorkspaceId: String?,
    ): PersonaImportResult {
        if (layer == PersonaLayer.A_KERNEL) {
            PersonaAudit.rejected("A 内核不可导入", "导入")
            return PersonaImportResult.Rejected("A 内核不可导入（只读）")
        }
        val parsed = when (val p = PersonaPatchRules.parsePatches(rawJson)) {
            is PersonaPatchParse.Rejected -> {
                PersonaAudit.rejected(p.reason, "导入")
                return PersonaImportResult.Rejected(p.reason)
            }
            is PersonaPatchParse.Ok -> p.patches
        }
        val wantScope = if (layer == PersonaLayer.B_GLOBAL) PersonaPatchScope.B_GLOBAL else PersonaPatchScope.C_PROJECT
        if (parsed.any { it.scope != wantScope }) {
            val why = "补丁作用域与目标层不一致（${layer.name} 只收 ${wantScope.key}）"
            PersonaAudit.rejected(why, "导入")
            return PersonaImportResult.Rejected(why)
        }
        if (layer == PersonaLayer.C_PROJECT && parsed.any { it.workspaceId != currentWorkspaceId }) {
            val why = "补丁属于别的工作区，不能导入当前工作区（C 层不跨区）"
            PersonaAudit.rejected(why, "导入")
            return PersonaImportResult.Rejected(why)
        }
        var simulated = files.read(layer).orEmpty()
        for (p in parsed) {
            val next = PersonaPatchRules.render(simulated, p.sections)
            if (!PersonaRules.appendOnly(simulated, next)) {
                val why = "第 ${parsed.indexOf(p) + 1} 条不是纯追加（整批拒）"
                PersonaAudit.rejected(why, "导入")
                return PersonaImportResult.Rejected(why)
            }
            simulated = next
        }
        val proposal = PersonaRules.propose(layer, files.read(layer).orEmpty(), simulated, "导入 typed patch ×${parsed.size}")
            ?: return PersonaImportResult.Rejected("A 内核不可提案改写")
        if (!files.applyProposal(proposal)) {
            PersonaAudit.rejected("写盘失败", "导入")
            return PersonaImportResult.Rejected("写盘失败（人设文件没写进去）")
        }
        parsed.forEach { PersonaAudit.applied(layer, if (layer == PersonaLayer.B_GLOBAL) "全局" else "工作区", "导入", it.sections) }
        return PersonaImportResult.Imported(layer, parsed.size, PersonaBackup.latestOf(files.fileOf(layer)))
    }

    /**
     * 重置一层：**先备份再把正文写空**（可回滚；不是删文件、不碰备份）。
     * 文件本来就不存在/已经为空 ⇒ 拒（没有可重置的东西，不造空文件）。
     */
    fun reset(files: PersonaFiles, layer: PersonaLayer): PersonaApplyResult {
        if (layer == PersonaLayer.A_KERNEL) {
            PersonaAudit.rejected("A 内核不可重置", "设置页")
            return PersonaApplyResult.Rejected("A 内核不可重置（只读）")
        }
        val f = files.fileOf(layer)
        if (!f.isFile || files.read(layer).isNullOrBlank()) {
            val why = "${layer.name} 还没有正文，没有可重置的东西"
            PersonaAudit.rejected(why, "设置页")
            return PersonaApplyResult.Rejected(why)
        }
        val backup = PersonaBackup.backupBefore(f, PersonaBackup.nowStamp())
            ?: return PersonaApplyResult.Rejected("重置失败：备份当前版没成功（没有动原文件）")
        return runCatching {
            f.writeText("", Charsets.UTF_8)
            PersonaAudit.reset(layer)
            PersonaApplyResult.Applied(layer, "", backup)
        }.getOrElse { PersonaApplyResult.Rejected("重置失败：${it.javaClass.simpleName}") }
    }
}
