package com.example.zhengdao.terminal

/**
 * 终端链接识别器（B 路径：URL 文本正则）—— §4.6 L1 识别层。
 *
 * 设计依据：《证道-浏览器Tab技术选型与架构草案-2026-10-13》v1.1 §4.6（合并 10-12 审计报告
 * 原「OSC 8」计划：docs/archive/audit-reports-2026-10-11-12/ 的终端显示交互现代化方案）。
 *
 * 分工（2026-10-14 第 ② 刀合刀落地）：**OSC 8 标准超链接**（A 路径）由 `TerminalEmulator`
 * 解析、行区间登记、渲染下划线，手势层命中后直接给 URI；本类负责 **B 路径**——终端输出
 * 里的裸 URL 文本（`git clone https://…`、apt 源、curl 示例），tap 时由 TerminalActivity
 * 扫所在行命中。两条路共用同一套打开策略（TerminalLinkPolicy → 玄览）。
 *
 * 已知风险（③ 待实测）：代码里的 URL 字符串/路径可能被误判；尾部标点误吞；
 * 组合字符下列→字符索引映射精度。真机读数归蓝总管采。
 */
object TerminalLinkScanner {

    /** 一次链接命中：[start, end) 为行内**字符索引**区间（非列——列→索引映射由调用方用 findStartOfColumn 做），[url] 为目标地址。 */
    data class LinkMatch(val start: Int, val end: Int, val url: String)

    /**
     * http(s) URL 模式：遇空白/常见闭合符即止。
     * TODO(阶段1.5 之后)：真机样本校准误报（源码行/路径）；尾部标点已由 §B 顺序 30 处理。
     */
    private val URL_PATTERN = Regex("""https?://[^\s<>"'`)\]}，。；！""]+""")

    /**
     * 尾部句读标点（ASCII）——**只在它后面是空白或行尾时才裁**（§B 顺序 30，2026-10-14 真机：
     * `https://example.com/trailing-test,` 被连逗号一起打开；ASCII 标点原不在排除集里）。
     *
     * 为什么不把 `,.;:!?` 塞进 [URL_PATTERN] 的排除集一律剥：`.` 与 `:` 是 URL 的合法字符
     * （`https://` 的冒号、`example.com` / `v1.2` 的点），句中逗号也可能是 URL 的一部分
     * （`/a,b`）——一律剥会把好 URL 也切坏。所以只处理**尾部连续标点段**，且该段之后
     * 必须是空白或行尾；后随其它非空白字符（如 `/a,b` 的 `b`）时原样保留。
     */
    private const val TRAILING_PUNCT = ",.;:!?"

    /** 扫一行终端文本，返回全部链接命中（按出现顺序）。 */
    fun findLinks(line: String): List<LinkMatch> {
        if (line.isEmpty()) return emptyList()
        return URL_PATTERN.findAll(line).mapNotNull { m ->
            val start = m.range.first
            val rawEnd = m.range.last + 1
            var end = rawEnd
            while (end > start && line[end - 1] in TRAILING_PUNCT) end--
            if (end < rawEnd) {
                // 尾标点之后若还有非空白字符，说明它们是 URL 的一部分（`/a,b`），保留。
                val next = line.getOrNull(rawEnd)
                if (next != null && !next.isWhitespace()) end = rawEnd
            }
            if (end <= start) null else LinkMatch(start, end, line.substring(start, end))
        }.toList()
    }

    /**
     * B 路径点按命中：字符索引 [charIndex] 落在某条链接区间内则返回其 URL，否则 null。
     * 调用方把 tap 的**列**经 `TerminalRow.findStartOfColumn(column)` 转成字符索引再调本方法。
     */
    fun findLinkCovering(line: String, charIndex: Int): String? {
        if (line.isEmpty() || charIndex < 0) return null
        return findLinks(line).firstOrNull { charIndex >= it.start && charIndex < it.end }?.url
    }
}
