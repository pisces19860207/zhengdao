// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百五十四批 · 轨迹摘要（模型提交口的**读**半边）。
//
// 一句话：把一次教学轨迹压成 **≤300 字**的步骤表，给模型引用；**只给「做了什么」**。
//
// 三条硬规矩（派工单逐字）：
//   ① **只有三样东西出页**：步骤序号＋动作＋定位种类（click/type/select + testid/role/
//      name/xp…）。不给页面正文、不给元素标签文字、不给 URL、不给标题——模型要的是
//      「这段轨迹长什么形状」，不是页面内容；
//   ② **敏感字段整条丢弃**（与 [ObserveSession.ingest] 同一条判据 [ObserveFaces.isSensitive]）：
//      密码/验证码这类输入位连「第几步」都不出现；
//   ③ **300 字是硬上限**：超了按步截断并标「共 N 步」，不静默丢尾巴。
//
// 为什么摘要要做成纯函数：它是模型侧唯一的「看轨迹」渠道，必须在 JVM 里可直测
// （判据⑤：夹具带正文关键词与敏感样本，断言一个字都漏不出去）。
package com.example.zhengdao.baopu.learn

/** 一次教学轨迹 → 人读步骤表（纯函数，零 IO）。 */
internal object TraceSummary {

    /** 字数硬上限（派工单：≤300 字）。数的是 [String.length]，不做字节换算。 */
    const val MAX_CHARS: Int = 300

    /** 没有可成步的操作时的定稿说法（与 DraftInducer 的 NoSteps 同口径）。 */
    const val NO_STEPS = "最近一次教学轨迹：没有可成步的操作（只有滚动或空会话）"

    /**
     * 摘要。
     *
     * @param snapshot 收口后的会话快照（[ObserveCenter] 留的那份）
     * @param maxChars 字数上限（测试可压低做截断验证；生产恒 [MAX_CHARS]）
     */
    fun of(snapshot: ObserveSnapshot, maxChars: Int = MAX_CHARS): String {
        val actionable = snapshot.events.filter { it.kind != ObserveKind.SCROLL }
        val kept = actionable.filterNot { it.faces.isSensitive() }
        val dropped = actionable.size - kept.size
        if (kept.isEmpty()) return NO_STEPS
        val head = buildString {
            append("最近一次教学轨迹（会话 ").append(snapshot.openedAtMs.toString(36))
            append("，共 ").append(kept.size).append(" 步")
            if (dropped > 0) append("，已略去 ").append(dropped).append(" 条敏感操作")
            append("）")
        }
        val lines = kept.mapIndexed { i, ev ->
            (i + 1).toString() + ". " + actionName(ev.kind) + " · " + locatorKinds(ev.faces)
        }
        return assemble(head, lines, maxChars)
    }

    /** 逐步拼接；装不下就截断并标「共 N 步」（末尾那句不超上限）。 */
    private fun assemble(head: String, lines: List<String>, maxChars: Int): String {
        val sb = StringBuilder(head)
        var used = 0
        for (line in lines) {
            val candidate = "\n" + line
            if (sb.length + candidate.length > maxChars) break
            sb.append(candidate)
            used += 1
        }
        if (used == lines.size) return sb.toString()
        val note = "\n…（共 " + lines.size + " 步，摘要已截断）"
        while (sb.length + note.length > maxChars && sb.length > head.length) {
            val cut = sb.lastIndexOf("\n")
            if (cut <= head.length) break
            sb.setLength(cut)
        }
        val out = sb.toString() + note
        return if (out.length <= maxChars) out else out.take(maxChars)
    }

    /** 动作名（与 [ObserveKind] 一一对应，不认别的写法）。 */
    private fun actionName(kind: ObserveKind): String = when (kind) {
        ObserveKind.CLICK -> "点击"
        ObserveKind.TYPE -> "输入"
        ObserveKind.SELECT -> "选择"
        ObserveKind.SCROLL -> "滚动"
    }

    /**
     * 定位**种类**（不是值、不是标签文字）：身份面里哪几级有货，按十级顺序点出来。
     *
     * 空面（全空且无候选）⇒ 「无定位」——草案会在结构门被判「定位全空」，摘要如实先说。
     */
    private fun locatorKinds(f: ObserveFaces): String {
        val kinds = buildList {
            if (f.testid.isNotBlank()) add("testid")
            if (f.role.isNotBlank()) add("role")
            if (f.name.isNotBlank()) add("name")
            if (f.aria.isNotBlank()) add("aria")
            if (f.id.isNotBlank()) add("id")
            if (f.inputName.isNotBlank()) add("inputName")
            if (f.placeholder.isNotBlank()) add("placeholder")
            if (f.formLabel.isNotBlank()) add("formLabel")
            if (f.inputType.isNotBlank()) add("inputType")
            if (f.xp.isNotBlank()) add("xp")
        }
        return if (kinds.isEmpty()) "无定位" else kinds.joinToString("+")
    }
}
