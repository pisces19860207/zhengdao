// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百一十八批（2026-11-12，缺陷 539-(5)）：**高风险采纳确认层的 diff 取值只有一份算法**。
//
// 缺陷原话（569 验机轮二）：「『采纳前』显示人设夹内容，『采纳后（将写入）』才是工具约束文本，
// 前后文对不上」。根因不是排版：确认层把「后」直接取成 `EvolutionSuggestion.payload`（那只是
// 一段**要追加的正文**），而写盘落的是「原正文 ＋ 空行 ＋ payload」——两处各算一份，于是弹窗上
// 「前」是全文、「后」是增量，逐字对不上。
//
// 纪律：这是**取值来源**问题，不是显示格式问题。所以「写盘用的那一份正文」与「弹窗展示的那一份
// 正文」必须是**同一个函数**的输出（[appended]），谁都别再自己拼一遍 `"\n\n"`。
//
// 边界（与其它 *Rules 文件一致）：本文件不碰 Context、不碰 Compose、不读盘；输入普通值，
// 输出普通值/数据类，全部可在 JVM 里直测。
package com.example.zhengdao.baopu

import java.io.File

internal object AdoptPreviewRules {

    /** 追加式改动里原正文与新增段之间的分隔（写盘与预览共用这一个常量）。 */
    const val APPEND_SEPARATOR: String = "\n\n"

    /**
     * 采纳前/后的一对正文。
     *
     * 两条都必须**逐字**等于盘上那份（`before` ＝ 此刻读到的正文，`after` ＝ 这次会写进去的正文），
     * 不许拿「增量」冒充「之后」，也不许拿「摘要」冒充任何一边。
     */
    internal data class Preview(val before: String, val after: String) {

        /**
         * 变化的那一段：`after` 去掉 `before`（去尾空白）前缀后剩下的部分。
         *
         * 不是追加式改动（`after` 不以 `before` 为前缀）⇒ null —— 那说明这两份不是同一个文件的前后，
         * 谁都不许把它解释成「把旧的删了」。
         */
        fun added(): String? {
            val head = before.trimEnd()
            if (!after.startsWith(head)) return null
            return after.removePrefix(head).trim()
        }
    }

    /**
     * 人设追加的**唯一算法**（写盘与弹窗都调它）：
     * 原正文为空 ⇒ 就是这一段；否则原正文（去尾空白）＋ [APPEND_SEPARATOR] ＋ 这一段。
     */
    fun appended(current: String, payload: String): String =
        if (current.isBlank()) payload else current.trimEnd() + APPEND_SEPARATOR + payload

    /** 人设类（PERSONA_APPEND／TOOL_RULE）的前后对照：前＝C 层现读正文，后＝真会写进去的正文。 */
    fun personaAppend(current: String?, payload: String): Preview {
        val before = current.orEmpty()
        return Preview(before = before, after = appended(before, payload))
    }

    /** 技能正文的前后对照：前＝落点文件现读正文（首写为空），后＝真会写进去的正文（已包装过）。 */
    fun skillBody(previous: String?, body: String): Preview =
        Preview(before = previous.orEmpty(), after = body)

    /**
     * 技能正文的落点文件——与 `SkillStore.plan` 的 PROJECT 支**同一口径**
     * （名字安全化 `SkillStore.sanitizeName` ＋ 项目技能目录）。
     *
     * 为什么单独给一个：确认层原先用 `skillFileOf`（项目优先、全局兜底）读「前」，
     * 而写盘只会写项目目录 ⇒ 只有全局旧同名技能时，弹窗拿全局正文当「前」、
     * 却把新正文写进项目文件，前后文同样对不上。读「前」必须读**会被覆盖的那一个**。
     */
    fun skillTargetFile(projectRoot: File, rawName: String): File? =
        SkillStore.sanitizeName(rawName)?.let {
            File(File(SkillRules.projectSkillDir(projectRoot), it), SkillRules.SKILL_FILE)
        }
}
