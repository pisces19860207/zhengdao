// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十六批 §1/§4：技能的**落盘层**。两条铁律：
//
// 1. **确认才落盘**：学习器产出的是内存草稿，本类只在用户点了「保存技能」之后动手；
// 2. **写盘前先扫凭证**（负例 7）：技能 JSON 里出现明文密码/凭证 ⇒ 一个字节不写。
//
// 落两样东西（同一目录，一个技能一个家）：
// - `技能/<名>/回放.json` —— 机器回放数据（本批新立的格式，schema zl-teach/1）；
// - `技能/<名>/SKILL.md` —— 人读卡（用途/步骤摘要/定位链/指纹），**走 184 的写口**
//   （[SkillStore.plan]/[apply]＋索引一行，房型与 SkillCardWriter.save 同款，不新造）。
//
// 审计：每次**执行**一行 JSON，落 `技能/_回放审计.jsonl`（房型照 SkillAudit 的 _变更.jsonl）。
// 执行器永不回写技能文件——本文件没有任何入口能让执行器改技能（「技能不自动改」结构性成立）。
package com.example.zhengdao.baopu

import com.example.zhengdao.rootfs.RunLog
import java.io.File
import java.security.MessageDigest

internal object TeachReplayStore {

    /** 回放数据文件名（与 SKILL.md 同目录旁挂）。 */
    const val REPLAY_FILE = "回放.json"

    /** 执行审计文件（技能根目录下，追加一行一条）。 */
    const val AUDIT_FILE = "_回放审计.jsonl"

    /** 落盘结果：written 非空＝成功（skillMd 与回放.json 都在）；否则 reason 说明。 */
    data class SaveResult(val written: File?, val reason: String?) {
        companion object {
            fun ok(file: File) = SaveResult(file, null)
            fun reject(reason: String) = SaveResult(null, reason)
        }
    }

    fun sidecarFile(projectRoot: File, name: String): File =
        File(SkillRules.projectSkillDir(projectRoot), "$name/$REPLAY_FILE")

    fun skillMdFile(projectRoot: File, name: String): File =
        File(SkillRules.projectSkillDir(projectRoot), "$name/${SkillRules.SKILL_FILE}")

    /**
     * 确认后的落盘（唯一写口）。顺序：凭证扫描 → 名字安全化 → 回放.json 先写临时文件 →
     * SKILL.md 走 184 写口 → 临时文件转正。SKILL.md 成功而回放.json 转正失败 ⇒ 返回失败
     * 原因（人可以手删半条，机器不静默吞）。
     */
    fun saveConfirmed(
        draft: TeachSkillDraft,
        projectRoot: File,
        globalRoot: File,
        personaFiles: PersonaFiles,
        stampText: String,
    ): SaveResult {
        val json = draft.skill.toJsonText()
        // ① 凭证扫描（负例 7）：发现问题一个字节不写
        val findings = TeachSafety.credentialFindings(json)
        if (findings.isNotEmpty()) {
            return SaveResult.reject("技能里有明文凭证，写盘前拒绝：${findings.joinToString("；")}")
        }
        val safeName = SkillStore.sanitizeName(draft.skill.name)
            ?: return SaveResult.reject("技能名不可用（只许字母／数字／中文／_／-，且不能为空）")

        // ② 回放.json 先进临时文件（不转正前盘上没有它）
        val target = sidecarFile(projectRoot, safeName)
        val tmp = File(target.parentFile, "$REPLAY_FILE.tmp-$stampText")
        runCatching {
            target.parentFile?.mkdirs()
            tmp.writeText(json, Charsets.UTF_8)
        }.getOrElse {
            return SaveResult.reject("回放数据写临时文件失败：${it.javaClass.simpleName}")
        }

        // ③ SKILL.md 走 184 写口（房型＝SkillCardWriter.save：先探盘上状态，头按现状重算）
        val humanBody = skillMdBody(draft.skill, draft.warnings)
        val probe = SkillStore.plan(
            proposal = SkillProposal(
                scope = SkillScope.PROJECT,
                name = safeName,
                body = humanBody,
                reason = "示教录制（教它一次）",
                draft = true,
            ),
            projectRoot = projectRoot,
            globalRoot = globalRoot,
            backupStamp = stampText,
        )
        probe.blocked?.let {
            runCatching { tmp.delete() }
            return SaveResult.reject(it)
        }
        val existingBody = if (probe.file.isFile) {
            runCatching { probe.file.readText(Charsets.UTF_8) }.getOrNull()
        } else {
            null
        }
        val isDraft = existingBody?.let { SkillStore.statusOf(it) == "draft" } ?: true
        val uses = existingBody?.let { SkillStore.usesOf(it) } ?: 0
        val finalBody = if (isDraft) {
            SkillStore.draftHeader(status = "draft", uses = uses) + "\n" + humanBody
        } else {
            humanBody
        }
        val proposal = SkillProposal(
            scope = SkillScope.PROJECT,
            name = safeName,
            body = finalBody,
            reason = "示教录制（教它一次）",
            draft = false,
        )
        val planned = SkillStore.plan(
            proposal = proposal.copy(draft = isDraft),
            projectRoot = projectRoot,
            globalRoot = globalRoot,
            backupStamp = stampText,
        )
        if (planned.file.absolutePath != probe.file.absolutePath) {
            runCatching { tmp.delete() }
            return SaveResult.reject("技能名「$safeName」落不到它自己的目录，先改个名字")
        }
        // apply 用带好头的 proposal（draft=false，头在 finalBody 里）——与 SkillCardWriter 同款，
        // 不这样会把草稿头写两遍。
        when (val applied = SkillStore.apply(planned.copy(proposal = proposal), personaFiles, personaFiles.read(PersonaLayer.C_PROJECT).orEmpty())) {
            is SkillWriteResult.Rejected -> {
                runCatching { tmp.delete() }
                return SaveResult.reject(applied.reason)
            }
            is SkillWriteResult.Written -> Unit
        }

        // ④ 转正（同目录 rename 原子性够用；失败如实报，不静默）
        return if (tmp.renameTo(target) || runCatching {
                tmp.copyTo(target, overwrite = true).let { tmp.delete() }
            }.getOrDefault(false) && target.isFile
        ) {
            SaveResult.ok(target)
        } else {
            SaveResult.reject("SKILL.md 已写（${planned.file.name}），但回放数据转正失败——请重存一次")
        }
    }

    /** 读回技能（项目层；解析失败/不存在 ⇒ null）。 */
    fun load(projectRoot: File, name: String): TeachSkill? {
        val f = sidecarFile(projectRoot, name)
        if (!f.isFile) return null
        return runCatching { TeachSkill.fromJson(f.readText(Charsets.UTF_8)) }.getOrNull()
    }

    /** 执行审计追加一行（失败只记日志，不影响回放结果——审计不该弄丢回放本身）。 */
    fun appendAudit(projectRoot: File, line: String): Boolean = runCatching {
        val root = SkillRules.projectSkillDir(projectRoot)
        root.mkdirs()
        File(root, AUDIT_FILE).appendText(line + "\n", Charsets.UTF_8)
        true
    }.getOrElse {
        RunLog.log("回放审计行写入失败：${it.javaClass.simpleName}")
        false
    }

    /** 规范序列化的 SHA-256（「技能未变（哈希不变）」判据用）。 */
    fun sha256Of(skill: TeachSkill): String {
        val d = MessageDigest.getInstance("SHA-256").digest(skill.toJsonText().toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    /** SKILL.md 人读卡（技能卡三件套：用途、步骤摘要、定位链＋指纹）。 */
    internal fun skillMdBody(skill: TeachSkill, warnings: List<String>): String = buildString {
        appendLine("# 回放技能：${skill.name}")
        appendLine()
        appendLine("由玄览「教它一次」示教生成；机器回放数据在同目录 `${REPLAY_FILE}`（schema ${TeachSkill.SCHEMA}）。")
        appendLine()
        appendLine("## 用途")
        appendLine(skill.purpose.ifBlank { "（示教时没写用途，回放前补一句）" })
        appendLine()
        appendLine("## 步骤")
        skill.steps.forEach { s ->
            appendLine("${s.index}. ${TeachLearner.stepLine(s)}")
        }
        appendLine()
        appendLine("## 定位链（tier 越小越优先；十级：role→testid→aria→id→name→placeholder→文本→表单上下文→CSS→XPath）")
        skill.steps.forEach { s ->
            appendLine("- 第 ${s.index} 步：${s.locators.joinToString(" → ") { it.describe() }}")
        }
        appendLine()
        appendLine("## 指纹（起点页关键表头/按钮；对不上 ⇒ 回放停手报「页面结构变了」）")
        skill.fingerprint.anchors.forEach { appendLine("- [${it.kind}] ${it.name}") }
        appendLine()
        appendLine("## 安全")
        appendLine("- 授权域：${skill.allowedDomains.joinToString("、").ifEmpty { "（空——回放会拒绝执行）" } }")
        appendLine("- 点过的元素类别：${skill.clickTagWhitelist.joinToString("/").ifEmpty { "（没有点击步骤）" } }")
        appendLine("- 拒点词（提交|确认|删除|作废|发货|派车|结算|支付|充值）命中 ⇒ 代码层面一律不点")
        if (warnings.isNotEmpty()) {
            appendLine("- 示教警示：")
            warnings.forEach { appendLine("  - $it") }
        }
    }
}
