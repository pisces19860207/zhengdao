// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 622 A②：可版本化动作脚本的**导出/导入口**。
//
// 脚本本体就是技能 JSON（schema zl-teach/2，规范序列化）——导出＝把技能库里的脚本
// 复制成单文件（产物/玄览脚本/<名>.玄览脚本.json），导入＝把这个文件经**四道闸**装回
// 技能库（与 teach_skill_save 同一条写口 TeachReplayStore.saveConfirmed）。
//
// 四道闸（导入一个都不许跳；导出带前三道——导出的东西也可能被别人导入）：
//   ① schema 闸：只认 zl-teach/1（老脚本）与 zl-teach/2（含目标站/最终快照），
//      且**严格解析**——多一个未知键整份不收（手改塞 loop/自由字段的负例在这里死）；
//   ② 凭证闸：脚本里扫出明文凭证 ⇒ 一个字节不导/不导（「不存凭据」跨设备成立）；
//   ③ 不可逆闸：CLICK 步语义面命中拒点词 ⇒ 整份拒绝（判据④：**脚本层直接拒不可逆动作**
//      ——不许进门，回放更不会执行到它）；
//   ④ 入库闸：走 saveConfirmed 既有全链（名字安全化＋准入验收闸 G2/G3/G1＋184 写口）。
//
// 为什么导出也要扫 ②③：导出文件是「准备给人带走/传给别人」的东西——带病导出等于
// 把闸的责任推给下一个工作区。宁可在这里拒，不让脏脚本出门。
package com.example.zhengdao.baopu

import com.example.zhengdao.bridge.PortIrreversible
import java.io.File

internal object TeachScriptExchange {

    /** 导出目录（工作区内产物区）：`产物/玄览脚本/`。 */
    const val EXPORT_DIR = "玄览脚本"

    /** 导出文件扩展名（整份脚本就是一个 JSON，扩展名只给人认）。 */
    const val EXPORT_SUFFIX = ".玄览脚本.json"

    /** 导出/导入的一句话结局（file 非空＝成功；reason 说明失败）。 */
    data class Result(val file: File?, val reason: String?) {
        companion object {
            fun ok(file: File): Result = Result(file, null)
            fun reject(reason: String): Result = Result(null, reason)
        }

        /** 给工具/界面看的一句话。 */
        fun message(name: String): String = when {
            file != null -> "已完成：${file.absolutePath}"
            else -> reason ?: "未完成（原因不明）"
        }
    }

    /** 导出落点（目录保证存在；名字经技能库同款安全化）。 */
    fun exportFile(workspace: File, name: String): File =
        File(File(MemoryLayout.productsDir(workspace), EXPORT_DIR), "${SkillStore.sanitizeName(name) ?: "unnamed"}$EXPORT_SUFFIX")

    /**
     * 导出：读技能 → 凭证闸 → 不可逆闸 → 写规范序列化的单文件。
     * 盘上没有这个技能/读不出 ⇒ 如实拒绝（不导空文件）。
     */
    fun export(projectRoot: File, workspace: File, name: String): Result {
        val skill = TeachReplayStore.load(projectRoot, name)
            ?: return Result.reject("没有这个回放技能：$name（先在玄览里教一遍）")
        val json = skill.toJsonText()
        // ② 凭据闸：不存凭据跨导出成立（盘上老文件是手改过的也可能带进来）
        val credentialFindings = TeachSafety.credentialFindings(json)
        if (credentialFindings.isNotEmpty()) {
            return Result.reject("脚本里有明文凭证，导出拒绝：${credentialFindings.joinToString("；")}")
        }
        // ③ 不可逆闸：带病脚本不出门（判据④ 的导出半边）
        val irreversible = irreversibleFindings(skill)
        if (irreversible.isNotEmpty()) {
            return Result.reject("脚本里含不可逆动作（提交/支付/删除类点击），导出拒绝：${irreversible.joinToString("；")}")
        }
        val out = exportFile(workspace, name)
        return runCatching {
            out.parentFile?.mkdirs()
            out.writeText(json, Charsets.UTF_8)
            Result.ok(out)
        }.getOrElse { Result.reject("导出写文件失败：${it.javaClass.simpleName}") }
    }

    /**
     * 导入：读导出文件 → ① schema/严格解析 → ② 凭据闸 → ③ 不可逆闸 → 授权域非空
     * → ④ saveConfirmed 落技能库（同人重导＝替换，与「重教一遍」同语义）。
     */
    fun import(
        workspace: File,
        globalRoot: File,
        personaFiles: PersonaFiles,
        name: String,
        stampText: String,
    ): Result {
        val file = exportFile(workspace, name)
        if (!file.isFile) {
            return Result.reject(
                "没有这个导出脚本：${file.name}（先 teach_skill_export 导出，或核对名字）",
            )
        }
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrElse {
            return Result.reject("导出脚本读不出来：${it.javaClass.simpleName}")
        }
        // ① schema 闸（严格解析：未知键/缺定位链/空步骤一律不收）
        val skill = TeachSkill.fromJson(text, strict = true)
            ?: return Result.reject(
                "不是可导入的玄览动作脚本（只认 zl-teach/1、zl-teach/2 且键集严格）：" +
                    "${file.name}",
            )
        if (skill.name != name) {
            return Result.reject(
                "脚本里的技能名是「${skill.name}」，与要导入的名字「$name」不符——一个文件一个脚本",
            )
        }
        // ② 凭据闸（判据：「不存凭据」——导入的脚本进技能库前最后一道）
        val credentialFindings = TeachSafety.credentialFindings(text)
        if (credentialFindings.isNotEmpty()) {
            return Result.reject("脚本里有明文凭证，导入拒绝：${credentialFindings.joinToString("；")}")
        }
        // ③ 不可逆闸（判据④：脚本层直接拒不可逆动作——不许进门）
        val irreversible = irreversibleFindings(skill)
        if (irreversible.isNotEmpty()) {
            return Result.reject("脚本里含不可逆动作（提交/支付/删除类点击），导入拒绝：${irreversible.joinToString("；")}")
        }
        // 授权域非空：空域脚本导进来也跑不了（回放 fail-closed），不如在门口说明
        if (skill.allowedDomains.isEmpty()) {
            return Result.reject("脚本没有授权域（allowedDomains 为空）——导入后回放必拒，先修脚本")
        }
        // ④ 入库闸：走 saveConfirmed 既有全链（名字安全化＋验收闸＋184 写口＋凭证扫描）
        val draft = TeachSkillDraft(
            skill = skill,
            warnings = listOf("导入自 ${TeachScriptExchange.EXPORT_DIR}（${file.name}）"),
            summary = TeachLearner.summarize(skill, emptyList()),
        )
        val saved = TeachReplayStore.saveConfirmed(draft, workspace, globalRoot, personaFiles, stampText)
        return when {
            saved.written != null -> Result.ok(saved.written!!)
            else -> Result.reject("导入落库被拒：${saved.reason ?: "原因不明"}")
        }
    }

    /**
     * 脚本层不可逆扫描（判据④）：CLICK 步的语义面命中拒点词 ⇒ 逐条列出。
     *
     * 语义面＝元素快照（可见文本/可访问名）＋定位链里的 aria/testid 值——录制层本就
     * 拒记这些动作，这里拦的是**手改文件塞进来**的那条路。词表与端口/示教层同源
     * （[PortIrreversible]，全仓唯一一份）。
     */
    fun irreversibleFindings(skill: TeachSkill): List<String> {
        val out = mutableListOf<String>()
        for (s in skill.steps) {
            if (s.action != TeachAction.CLICK) continue
            val aria = s.locators.firstOrNull { it.kind == TeachLocatorKind.ARIA_LABEL }?.value
            val testid = s.locators.firstOrNull { it.kind == TeachLocatorKind.TESTID }?.value
            val reason = PortIrreversible.reasonIn(s.snapshot.text, s.snapshot.name, aria, testid)
            if (reason != null) out += "第 ${s.index} 步：$reason"
        }
        return out
    }
}
