// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十八批 A（2026-11-10）· 低风险自进化四域的**三条红线**（判据④的判定件）。
//
// 红线出自《L3 批·完整自进化》设计 §二 的三行 ❌（能进化的域之外，永不自进化）：
//   · **APP 自身代码**：不许自改源码（要改走人审的派工单）；
//   · **A 内核**（身份/凭据/停机）：永不自进化；
//   · **B/C 头三条**（不可逆动作／外发·花钱·账号／改制度）：永不自进化。
//
// 为什么是"识别候选"而不是"拦截执行"：四域的候选在**进灰度之前**就该被这道门分掉——
// 红线候选一个字节都不该写到盘上，等到写盘那一层再拦已经晚了（写盘层有自己的闸，
// 但那是"动作"的闸；这里管的是"候选"的闸，两层不是一回事）。
//
// 与既有机制的关系（派工单判据「只用既有机制，不许新建进化机制」）：
//   · 本文件只做**纯分类**（文本 → 命中哪条红线），不落账、不拦人、不改档；
//     拒并记账走 [LowRiskEvo.recordRefusal]（既有 `ADVISOR_OPINION` 账，
//     「被拦下来的意见也记这一类」是 506 给这一类写明的用途）；
//   · 模式表是**判定常量**，与 473 的阈值常量同款：不读配置、不读模型自述。
//
// 口径（负例与误伤的边界都钉在这句）：只认**路径形/名字形**的指认，
// 不认散文里的泛提——「改一下 MainActivity.kt」命中（指认了源码文件），
// 「用 Kotlin 写个函数」不命中（没指认任何源码落点）。误伤方向宁可偏紧：
// 候选被拒只是回到候选区，人随时可以在设置页写，损失是一次提示，不是一次事故。
package com.example.zhengdao.baopu.evolution.lowrisk

/**
 * 一条自进化候选（四域共用的最小形状）。
 *
 * @param domain 域线值（473 登记表 `CanaryCapability.wire`）
 * @param target 候选想动的目标（路径/键；没有明确目标的域传一个固定标，如 `system:tail`）
 * @param body 候选正文（模型自拟内容的域才带，如技能正文、收编行；没有就空串）
 * @param deep 是不是「改结构」类（517 深度档管的那一类：技能步骤/提示词结构/注入策略——
 *   深度档不到「深」时这类候选不自动做；四域在产线上都不产出 deep 候选，
 *   这一格是给"有人日后把结构类候选接进来"时门自动收紧用的）
 */
internal data class EvoCandidate(
    val domain: String,
    val target: String,
    val body: String = "",
    val deep: Boolean = false,
)

/** 三条红线（**闭集**；线值是账行里的稳定键，改字面量＝老账读不出来）。 */
internal enum class EvoRedLine(val wire: String, val cn: String) {

    /** APP 自身代码：自改源码、改构建脚本——要改走人审的派工单。 */
    APP_SOURCE("app_source", "APP 自身代码"),

    /** A 内核：身份/凭据/停机——永不自进化（476/471 那一族）。 */
    KERNEL("kernel", "A 内核（身份/凭据/停机）"),

    /** B/C 头三条：不可逆动作／外发·花钱·账号／改制度——永不自进化。 */
    HEAD_THREE("head_three", "B/C 头三条（不可逆/外发·花钱·账号/改制度）"),
    ;

    companion object {
        /** 线值 ⇒ 红线；认不出 ⇒ null（自造线值不渲染，也不静默当别的线）。 */
        fun ofWire(wire: String): EvoRedLine? = entries.firstOrNull { it.wire == wire }
    }
}

/** 三条红线的识别器（纯函数：候选进、命中的线出；null＝没碰线）。 */
internal object RedLines {

    /** APP 自身代码：路径形的源码/构建指认（无路径的泛提不算——口径见文件头）。 */
    private val APP_SOURCE_TOKENS = listOf(
        "app/src", "core/src", "policy/src", "src/main", "src/test", "src/androidTest",
        ".gradle.kts", "settings.gradle", "build.gradle", "gradle/wrapper",
        "native-core", "rust/src",
    )

    /** A 内核：身份/凭据/停机三族的**名与账**（476 的身份表、凭据绑定、471 的停机闸）。 */
    private val KERNEL_TOKENS = listOf(
        "执行体", "身份表", "IdentityStore", "CredentialStore", "McpVault",
        "HaltGate", "停机闸", "A_KERNEL",
    )

    /** B/C 头三条：改制度／不可逆动作／外发·花钱·账号（逐词指认，不认散文泛提）。 */
    private val HEAD_THREE_TOKENS = listOf(
        "制度总账", "法典", "constitution",
        "永久删除", "彻底删除", "不可恢复", "格式化",
        "转账", "支付密码", "付款", "下单购买", "发短信", "群发",
        "账号密码", "上传到服务器", "上传到云端",
    )

    /** 候选碰没碰红线；碰了 ⇒ 哪一条（target 与 body 都查；先查谁就是哪条先命中）。 */
    fun hit(c: EvoCandidate): EvoRedLine? {
        val haystack = c.target + "\n" + c.body
        return when {
            touches(haystack, APP_SOURCE_TOKENS) -> EvoRedLine.APP_SOURCE
            touches(haystack, KERNEL_TOKENS) -> EvoRedLine.KERNEL
            touches(haystack, HEAD_THREE_TOKENS) -> EvoRedLine.HEAD_THREE
            else -> null
        }
    }

    /** 一句话读数（拒绝账与提示共用；候选两个字段都进，事后能对账）。 */
    fun readout(c: EvoCandidate, line: EvoRedLine): String =
        "候选[${c.domain}]目标=${c.target} 命中红线：${line.cn}"

    private fun touches(text: String, tokens: List<String>): Boolean = tokens.any { text.contains(it) }
}
