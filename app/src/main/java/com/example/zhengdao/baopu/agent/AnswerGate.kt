// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百二十七批 §0.5：收尾闸的三条**程序判据**（工作模式）——
// `verifyTouched` 为空（纯问答/纯查单/纯分析）时，这一轮也验收一次。
//
// 三条口径（派工单原文）：
//   ① 数字必须来自表：回答里的具体数字在本轮工具回灌里找不到
//      ⇒「数字可能不是来自表：「X」」；
//   ② 缺字段写"未填"：回答出现推测口吻＋数字 ⇒「疑似推算，不是读数」；
//   ③ 查不到就说查不到：同一段回答里既有"没找到/查不到/没有"又有
//      "结论/就是/共计/结果是" ⇒「结论与"没找到"矛盾」。
//
// 纪律：只报 finding——**不判卷、不打分、不阻断**，也**不引模型裁判**；
// 零 Android/IO/网络，输入两个字符串、输出一串值，JVM 直测。
//
// 工作/创作模式本身还没有设置项（173ac 只到设计；**未实现，见台账 173ac**）——
// 调用方拿不准时按工作模式，这正是 173ac 的默认口径。
package com.example.zhengdao.baopu.agent

internal object AnswerGate {

    /** 本轮工具回灌最多攒多少字符（只用于"数字有没有出现过"，不参与任何落盘）。 */
    const val MAX_GROUND_CHARS = 200_000

    /** 同一条回答最多报几个"数字不在表里"（其余照旧进日志摘要，页顶只报总数）。 */
    const val MAX_NUMBER_FINDINGS = 5

    /** 一条可疑。[text] 就是给人看的那一句（口径见文件头）。 */
    data class Finding(val check: Check, val text: String)

    /** 三类判据（闭集：报告/日志都按它归类）。 */
    enum class Check {
        NUMBER_NOT_FROM_TABLE,
        SPECULATION,
        NOT_FOUND_CONTRADICTION,
    }

    // 日期/时间先抹掉：它们不是"表里的读数"，混进来只会制造假可疑。
    private val DATE_LIKE = Regex(
        """\d{4}\s*[-/.年]\s*\d{1,2}\s*[-/.月]\s*\d{1,2}\s*日?|\d{4}\s*年|\d{1,2}:\d{2}(?::\d{2})?""",
    )

    // 数字（含千分位与小数）；前后不许紧贴数字/分隔符，避免把 1,234 拆成 1 和 234。
    private val NUMBER = Regex("""(?<![\d,，.])(\d[\d,，]*(?:\.\d+)?)""")

    // 一位数只有在紧跟数据单位时才算"具体数字"（"第 3 步"这种不是表里的读数）。
    private val UNIT = Regex(
        """万元|亿元|元|块|条|件|个|次|人|天|小时|分钟|秒|%|％|MB|GB|KB|万|亿|笔|单|页|篇|份|户|名|项|台|套|张|斤|公斤|公里|米|度|分""",
    )

    // "约"必须紧跟数字才算推测口吻（"约定"不算）。
    private val SPECULATION = Regex("""大概|估计|应该是|可能是|约(?=\s*\d)""")

    private val NOT_FOUND = Regex("""没找到|查不到|没有""")
    private val CONCLUSION = Regex("""结论|就是|共计|结果是""")

    /**
     * 三条判据一次跑完。
     *
     * @param answer 这一轮模型给出的最终回答
     * @param ground 本轮**成功工具结果**的回灌原文（多段直接拼）
     * @param workMode 工作模式才判；创作模式（false）一条都不报
     */
    fun check(answer: String, ground: String, workMode: Boolean): List<Finding> {
        if (!workMode || answer.isBlank()) return emptyList()
        val scan = DATE_LIKE.replace(answer, " ")
        val grounded = numbersOf(ground)
        val out = mutableListOf<Finding>()

        var numberHits = 0
        val seen = LinkedHashSet<String>()
        for (m in NUMBER.findAll(scan)) {
            val raw = m.groupValues[1]
            val canon = canonical(raw)
            if (!seen.add(canon)) continue
            if (!isConcrete(canon, scan, m.range.last + 1)) continue
            if (canon in grounded) continue
            out += Finding(Check.NUMBER_NOT_FROM_TABLE, "数字可能不是来自表：「${raw.take(20)}」")
            if (++numberHits >= MAX_NUMBER_FINDINGS) break
        }

        if (SPECULATION.containsMatchIn(answer) && NUMBER.containsMatchIn(scan)) {
            out += Finding(Check.SPECULATION, "疑似推算，不是读数")
        }
        if (NOT_FOUND.containsMatchIn(answer) && CONCLUSION.containsMatchIn(answer)) {
            out += Finding(Check.NOT_FOUND_CONTRADICTION, "结论与\"没找到\"矛盾")
        }
        return out
    }

    /** 页顶那一句（只报总数＋第一条，不刷屏）；没有可疑 ⇒ null（一个字都不显示）。 */
    fun notice(findings: List<Finding>): String? {
        if (findings.isEmpty()) return null
        val more = if (findings.size > 1) " 等" else ""
        return "这轮有 ${findings.size} 条可疑：${findings.first().text}$more"
    }

    /** 日志摘要（最多 [max] 条，避免 run_log 被一段长清单撑爆）。 */
    fun brief(findings: List<Finding>, max: Int = 3): String =
        findings.take(max).joinToString("；") { it.text } + if (findings.size > max) "…" else ""

    /** 把一次成功工具结果并进本轮回灌（超上限就截断；返回后仍保证有界）。 */
    fun appendGround(sb: StringBuilder, text: String) {
        if (text.isEmpty() || sb.length >= MAX_GROUND_CHARS) return
        val room = MAX_GROUND_CHARS - sb.length
        sb.append(if (text.length <= room) text else text.take(room)).append('\n')
    }

    /** 一段文本里出现过的数字（归一后；给 ground 建索引）。 */
    internal fun numbersOf(text: String): Set<String> {
        if (text.isEmpty()) return emptySet()
        val scan = DATE_LIKE.replace(text, " ")
        return NUMBER.findAll(scan).mapTo(HashSet()) { canonical(it.groupValues[1]) }
    }

    /** 去千分位、去小数尾零 ⇒ "1,234.00" 与 "1234" 是同一个数。 */
    internal fun canonical(raw: String): String {
        val plain = raw.replace(",", "").replace("，", "")
        if (!plain.contains('.')) return plain
        return plain.trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }

    /** 一位数只在紧跟数据单位时算具体数字；多位数一律算。 */
    private fun isConcrete(canon: String, scan: String, afterIndex: Int): Boolean {
        if (canon.replace(".", "").length >= 2) return true
        val tail = scan.substring(afterIndex.coerceAtMost(scan.length)).trimStart()
        return UNIT.find(tail)?.range?.first == 0
    }
}
