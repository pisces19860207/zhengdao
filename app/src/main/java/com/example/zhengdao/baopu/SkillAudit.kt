// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第五十八批（2026-10-19 用户「C 层可以让他自治」「按你的来」）：
// 项目技能自动写/改的**审计记录**——每次改动追加一行 JSON，供人查/回滚。
// 只写旁挂文件 `技能/_变更.jsonl`，不碰 SKILL.md 的既有格式。
package com.example.zhengdao.baopu

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal object SkillAudit {

    const val FILE_NAME = "_变更.jsonl"

    /** 技能目录下的审计文件。 */
    fun fileIn(skillDir: File): File = File(skillDir, FILE_NAME)

    /** 正文的短摘要（sha256 前 12 位；文件不存在 ⇒ "-"）。 */
    fun shaOf(file: File): String = runCatching {
        val d = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        d.joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(12)
    }.getOrDefault("-")

    /**
     * 追加一条审计。**失败只吞掉**（审计不能把写入搞失败），由调用方 log。
     *
     * @param skillFile 技能正文（用来推技能目录与摘要）
     */
    fun record(
        skillFile: File,
        scope: String,
        name: String,
        action: String,
        reason: String,
        beforeSha: String,
        indexUpdated: Boolean,
        nowMs: Long,
    ): Boolean = runCatching {
        val dir = skillFile.parentFile?.parentFile ?: return@runCatching false
        dir.mkdirs()
        val o = JSONObject()
            .put("ts", nowMs)
            .put("scope", scope)
            .put("name", name)
            .put("action", action)
            .put("reason", reason)
            .put("beforeSha", beforeSha)
            .put("afterSha", shaOf(skillFile))
            .put("indexUpdated", indexUpdated)
        fileIn(dir).appendText(o.toString() + "\n", Charsets.UTF_8)
        true
    }.getOrDefault(false)
}
