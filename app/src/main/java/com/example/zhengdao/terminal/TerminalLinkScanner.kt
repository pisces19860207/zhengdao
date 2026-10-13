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
     * TODO(阶段1.5 之后)：真机样本校准误报（源码行/路径）与尾部标点误吞。
     */
    private val URL_PATTERN = Regex("""https?://[^\s<>"'`)\]}，。；！""]+""")

    /** 扫一行终端文本，返回全部链接命中（按出现顺序）。 */
    fun findLinks(line: String): List<LinkMatch> {
        if (line.isEmpty()) return emptyList()
        return URL_PATTERN.findAll(line)
            .map { LinkMatch(it.range.first, it.range.last + 1, it.value) }
            .toList()
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
