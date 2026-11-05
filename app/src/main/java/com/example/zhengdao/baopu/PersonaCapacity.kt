// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单 2026-11-05 第四百六十四批 ①（人设容量）。
//
// 464 之前「单条条款 ≤800 字符」是写死的三个数（800 / 2000 / 4 段）。用户口径：
// **单条上限 800 → 可配 4000；超长分段写入不丢；回执写"写成 N 段/M 字"**。
// 所以这里只做两件判据的事：
//   1. 把上限变成**一个有区间的可调旋钮**（默认 4000，[configure] 收窄到 [200, 8000]）；
//      旋钮只有一个落点 —— [PersonaPatchRules] 的三个数、设置页输入框、append_persona
//      工具都从这里取值，**不许有第二份 800**（464 的验收判据之一就是找不到裸 800）。
//   2. 把「超长」从**拒收**改成**分段**：按空行/小标题切，切不开才按字符硬切，
//      保证**非空白字符一个不丢**（[split] 的后置判据）。
//
// 为什么分段有封顶（[MAX_SEGMENTS]）而不是无限切：一条真有几万字的内容多半是把
// 聊天记录当成约束文件塞进来了。那种情况**宁可明说拒收**，也不悄悄切成 60 段落盘
// ——「不丢」不能变成「不筛」。
//
// 本文件零 Android 依赖、零 I/O，纯函数，JVM 单测直接断言。
package com.example.zhengdao.baopu

/**
 * 人设的容量口径（464 ①）。
 *
 * 判据（见 `PersonaCapacityTest`）：
 * - 默认上限 4000 ⇒ 一条 3000 字条款**一次写下**，不再报「条款太长」；
 * - 上限可配，但配不出区间（低于 [MIN_MAX_SECTION_CHARS] 或高于 [MAX_MAX_SECTION_CHARS]
 *   ⇒ 收到区间端点，且回执里说的是**生效值**，不是用户给的值）；
 * - [split] 切完再拼回去，**非空白字符逐字相等**（不丢）；每段长度 ≤ 上限；
 * - 超过 [MAX_SEGMENTS] 段 ⇒ 返回 null（明说拒收，不静默截断）。
 */
internal object PersonaCapacity {

    /** 单条条款的默认上限（464 前这里是 800）。 */
    const val DEFAULT_MAX_SECTION_CHARS = 4_000

    /** 可配区间的下端：再低就把一整句话剁成渣，「分段」就没意义了。 */
    const val MIN_MAX_SECTION_CHARS = 200

    /**
     * 可配区间的上端 ＝ 单层人设的字符量级（与 [PersonaRules.PERSONA_TOKEN_LIMIT] 同数量级）。
     * 人设是**约束文件**，一条就能顶满一层 ⇒ 再高就是把缰绳换成绳子。
     */
    const val MAX_MAX_SECTION_CHARS = 8_000

    /** 一条条款最多允许切成几段（超了 ⇒ [splitWithinLimit] 返回 null 明说拒收）。 */
    const val MAX_SEGMENTS = 24

    /** 一段「## 类别」小标题的开销（算整条补丁上限时用，取宽口径）。 */
    const val HEADING_CHARS = 32

    /** 当前配置值；null ＝ 没配过，用默认。 */
    @Volatile
    private var configured: Int? = null

    /** 生效中的单条上限（**全系统只有这一个出口**）。 */
    fun maxSectionChars(): Int = configured ?: DEFAULT_MAX_SECTION_CHARS

    /**
     * 配一个上限，返回**真正生效**的值（区间外收进端点，不报错也不静默失效）。
     *
     * 持久化与设置页开关不在本单（464 把 UI 明确留给「UI 批再加」），
     * 所以这里只做进程内旋钮：重启回默认，调用方拿到的返回值就是生效值。
     */
    fun configure(raw: Int): Int = raw.coerceIn(MIN_MAX_SECTION_CHARS, MAX_MAX_SECTION_CHARS).also { configured = it }

    /** 回到默认上限（测试之间不互相污染，也留给设置页「恢复默认」）。 */
    fun reset() {
        configured = null
    }

    /** 一条条款最多能有多长（＝单条上限 × 段数封顶）；设置页输入框按它放行，不做静默砍字。 */
    fun maxClauseChars(): Int = maxSectionChars() * MAX_SEGMENTS

    /**
     * 整条补丁允许的字符数：条款上限 × 段数 ＋ 小标题开销。
     *
     * 它**跟着上限走**（464 前是恒 2000）。上限放开到 4000 后还留 2000，
     * 等于一边说「可以写长了」一边在总长上再砍一刀 —— 判据① 就是这么失败的。
     */
    fun maxPatchChars(): Int =
        maxSectionChars() * PersonaPatchRules.MAX_SECTIONS + HEADING_CHARS * PersonaPatchRules.MAX_SECTIONS

    /**
     * 超长条款 ⇒ 分段（**不丢字**）；不超长 ⇒ 原样一段（一字不改）。
     *
     * @return 段列表（顺序即写入顺序）；超过 [MAX_SEGMENTS] 段 ⇒ null（调用方必须明说拒收）
     */
    fun splitWithinLimit(text: String, maxChars: Int = maxSectionChars()): List<String>? {
        val segs = split(text, maxChars)
        return if (segs.size > MAX_SEGMENTS) null else segs
    }

    /**
     * 纯切分（不含段数封顶）：**空行 → 小标题 → 硬切**三级边界，一级一级退让。
     *
     * 保证两件事：① 每段长度 ≤ [maxChars]；② 各段的非空白字符拼起来 ＝ 原文的
     * 非空白字符（换行与空白会被规范化，字一个不丢）。
     */
    fun split(text: String, maxChars: Int = maxSectionChars()): List<String> {
        val body = text.trim()
        if (body.isEmpty()) return emptyList()
        val limit = maxChars.coerceAtLeast(1)
        if (body.length <= limit) return listOf(body)
        return pack(blocksOf(body), limit)
    }

    /** 回执：`写成 N 段，共 M 字`（M 按**非空白字符**数，与 [split] 的「不丢」同一口径）。 */
    fun receipt(segments: List<String>): String =
        "写成 ${segments.size} 段，共 ${segments.sumOf { it.count { c -> !c.isWhitespace() } }} 字"

    /** 按空行切块，块内遇到 markdown 小标题（`#`…`######`）再开一块；行内容逐字保留。 */
    private fun blocksOf(text: String): List<String> {
        val out = mutableListOf<String>()
        val cur = mutableListOf<String>()
        text.lineSequence().forEach { line ->
            val blank = line.isBlank()
            val heading = !blank && line.trimStart().startsWith("#")
            if (blank || (heading && cur.isNotEmpty())) {
                if (cur.isNotEmpty()) {
                    out += cur.joinToString("\n")
                    cur.clear()
                }
            }
            if (!blank) cur += line
        }
        if (cur.isNotEmpty()) out += cur.joinToString("\n")
        return out.filter { it.isNotBlank() }
    }

    /** 贪心装填：装得下就并段，装不下就收口开新段；单块超限先硬切成片再装。 */
    private fun pack(blocks: List<String>, limit: Int): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        blocks.flatMap { b -> if (b.length > limit) hardSlice(b, limit) else listOf(b) }.forEach { piece ->
            if (cur.isNotEmpty() && cur.length + 2 + piece.length > limit) {
                out += cur.toString()
                cur.setLength(0)
            }
            if (cur.isNotEmpty()) cur.append("\n\n")
            cur.append(piece)
            if (cur.length >= limit) {
                out += cur.toString()
                cur.setLength(0)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out.filter { it.isNotBlank() }
    }

    /** 一块切不开 ⇒ 优先在窗口内最后一个换行处切，换行太靠前就直接按字符切（绝不跳字）。 */
    private fun hardSlice(block: String, limit: Int): List<String> {
        val out = mutableListOf<String>()
        var rest = block
        while (rest.length > limit) {
            val nl = rest.lastIndexOf('\n', limit - 1)
            val cut = if (nl >= limit / 2) nl else limit
            out += rest.substring(0, cut).trimEnd()
            rest = rest.substring(cut).trimStart('\n')
        }
        if (rest.isNotBlank()) out += rest.trimEnd()
        return out.filter { it.isNotBlank() }
    }
}
