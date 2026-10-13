package com.example.zhengdao.terminal

/**
 * 终端链接识别器（B 路径：URL 文本正则）—— §4.6 L1 识别层。
 *
 * 设计依据：《证道-浏览器Tab技术选型与架构草案-2026-10-13》v1.1 §4.6（合并 10-12 审计报告
 * 原「OSC 8」计划：docs/archive/audit-reports-2026-10-11-12/ 的终端显示交互现代化方案）。
 *
 * 为什么先做 B 不做 A（③ 判断，② 事实支撑）：
 * agent 日常输出（git clone https://…、apt 源、curl 示例、文档链接）绝大多数是**裸 URL 文本**；
 * 正则识别零内核改动、当天可验。OSC 8 标准超链接（A 路径）要改 vendored 三件套
 * （TerminalEmulator 转义解析 + screen buffer 字段 + 手势），留作后续立项。
 *
 * 已知风险（③ 待实测）：代码里的 URL 字符串/路径可能被误判为链接（如源码、错误堆栈）；
 * 滚动时命中精度需真机验证。
 *
 * 状态：骨架（未实现）。阶段 1.5 填肉。
 */
object TerminalLinkScanner {

    /** 一次链接命中：[start, end) 为行内字符区间，[url] 为目标地址。 */
    data class LinkMatch(val start: Int, val end: Int, val url: String)

    /**
     * http(s) URL 初版模式：遇空白/常见闭合符即止。
     * TODO(阶段1.5)：真机样本校准（去尾部标点：中英文句号/逗号/括号/引号的误吞）；
     * 评估误报率后决定是否加"代码上下文豁免"（如行首缩进的源码行）。
     */
    private val URL_PATTERN = Regex("""https?://[^\s<>"'`)\]}，。；！""]+""")

    /** 扫一行终端文本，返回全部链接命中（按出现顺序）。 */
    fun findLinks(line: String): List<LinkMatch> {
        if (line.isEmpty()) return emptyList()
        // TODO(阶段1.5)：URL_PATTERN.findAll(line).map { LinkMatch(it.range.first, it.range.last + 1, it.value) }
        return emptyList()
    }
}
