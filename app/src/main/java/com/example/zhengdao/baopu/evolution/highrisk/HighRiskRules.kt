// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十八批 C（2026-11-14）· 高风险两域的**纯判据**（零 IO、零时钟、零 Android）。
//
// 与 568-A 的 [com.example.zhengdao.baopu.evolution.lowrisk.LowRiskGate] 同一条纪律：
// 判定件只分类、不落账、不拦人；落账与转手在 [HighRiskEvo]。本文件目前只装域①的方向判定
// （域②路由输入的状态语义守卫在第二笔提交里补进同一个对象，房型不变）。
//
// 域①「收窄自动生效、加宽过准入」的方向判定为什么是纯函数：方向只由「加了哪些键、删了哪些键」
// 决定，与档位/深度/准入无关——那些是 [HighRiskEvo] 转手既有链时问的。把方向单独抽出来，
// 负例（绕开准入直接加宽）才钉得住：加宽这一支无论删没删别的键，都必须走准入（保守口径）。
package com.example.zhengdao.baopu.evolution.highrisk

/** 白名单变更方向（**闭集三态**；线值是稳定键，改字面量＝老读数读不回来）。 */
internal enum class WhitelistDirection(val wire: String, val cn: String) {

    /** 只删不加（移除工具/缩权）⇒ 安全方向，自动生效。 */
    NARROW("narrow", "收窄"),

    /** 加了任何键（新增工具/提权）⇒ 必须过 467 准入。 */
    WIDEN("widen", "加宽"),

    /** 一个键都没动 ⇒ 不落空变动。 */
    NOOP("noop", "未变"),
    ;

    companion object {
        /** 线值 ⇒ 方向；认不出 ⇒ null（自造方向不渲染，也不静默当收窄）。 */
        fun ofWire(wire: String): WhitelistDirection? = entries.firstOrNull { it.wire == wire }
    }
}

/** 域①的方向判定与集合编解码（纯函数；落账 before/after 与读回共用同一把尺）。 */
internal object WhitelistRules {

    /**
     * 方向判定：加了任何键 ⇒ [WhitelistDirection.WIDEN]（哪怕同时删了别的——保守，加宽必须过准入）；
     * 只删不加 ⇒ [WhitelistDirection.NARROW]；都不动 ⇒ [WhitelistDirection.NOOP]。
     *
     * 「加宽优先」是刻意的：一次既加又删的变更，只要加了键就按加宽走准入——
     * 否则「删一个换一个」就成了绕开准入提权的缝（派工单负例「绕开准入直接加宽 ⇒ 红」钉的就是它）。
     */
    fun directionOf(before: Set<String>, after: Set<String>): WhitelistDirection {
        val added = after - before
        val removed = before - after
        return when {
            added.isNotEmpty() -> WhitelistDirection.WIDEN
            removed.isNotEmpty() -> WhitelistDirection.NARROW
            else -> WhitelistDirection.NOOP
        }
    }

    /** 集合 ⇒ 稳定串（排序相连；空集 ⇒ 空串）。落账与读回都走它，不另拼一套。 */
    fun encode(keys: Set<String>): String = keys.sorted().joinToString(",")

    /** 稳定串 ⇒ 集合（空串 ⇒ 空集；空白格滤掉——老账里手填的空格不该长出一个空键）。 */
    fun decode(text: String): Set<String> =
        if (text.isBlank()) emptySet() else text.split(",").filter { it.isNotBlank() }.toSet()
}