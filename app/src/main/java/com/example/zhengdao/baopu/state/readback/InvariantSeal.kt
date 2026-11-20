// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百二十二批 · 读回契约：不变量行的「人批封印」。
//
// 判据「不变量只有人批才变」的机械落地（沿 621 大纲审批的形态）：
//   · digest 只吃"身体摘要 + 不变量行文本"，不吃时间——批了之后再改一个字就失配；
//   · 批准入口只有 [InvariantSeal.approve]，who 非 [WHO_USER] 直接拒（模型无工具走到这里）；
//   · 生效条件：seal.digest == 当前摘要+行的 digest（[InvariantSeal.validFor]）——
//     改版即旧批作废，与 457 reissueAck「旧版上的批准不再覆盖新要求」同构。
//
// 三行只是文本上下文、不进任何闸：本文件不接 RunContractWiring / RiskMatrix / law，
// 只产出一个"这行能不能当不变量用"的判定供装配层取行，绝不写库、不落盘、不连模型。
package com.example.zhengdao.baopu.state.readback

import java.security.MessageDigest
import java.util.Locale

/**
 * 一次人批：批的是某一版身体摘要 [basis] 下的不变量行 [line]。
 * [digest] = sha256(basis + "\n" + line) 的前 16 位十六进制（与 621 digestOf 同款）。
 */
internal data class InvariantSeal(
    val basis: String,
    val line: String,
    val digest: String,
    val who: String,
    val atMs: Long,
) {
    /** 当前身体摘要 + 行文本是否与批准时逐字一致（一致才允许该行当不变量生效）。 */
    fun validFor(currentBasis: String, currentLine: String): Boolean =
        digest == digestOf(currentBasis, currentLine)

    companion object {
        /** 唯一有权批准的人：用户本人（工具把卡上那一下原样传进来）。 */
        const val WHO_USER = "用户"

        /** 内容摘要：`sha256:<hex16>`，只吃内容、不吃时间。 */
        fun digestOf(basis: String, line: String): String {
            val canonical = basis + "\n" + line
            val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            return "sha256:" + bytes.take(8).joinToString("") { String.format(Locale.ROOT, "%02x", it) }
        }

        /**
         * 唯一批准入口。who 非 [WHO_USER] 直接拒（返回 null，不产封印）。
         * basis/line 由调用方从 BodyState 现算——本函数不编任何数值，也不接受外部传入的行文本做"批准"。
         */
        fun approve(basis: String, line: String, who: String, atMs: Long): InvariantSeal? =
            if (who != WHO_USER) null
            else InvariantSeal(basis, line, digestOf(basis, line), WHO_USER, atMs)
    }
}

/** 装配层取"当前生效的不变量行"的纯判定：没批 / 失配一律回落到当轮现算的真读数。 */
internal object InvariantReadout {
    /**
     * 生效行：
     *  - seal 为空或谁批的不是用户 ⇒ 现算真读数（尚未人批，不冒充已批准）；
     *  - seal 对当前 basis+line 有效 ⇒ 用已批准的原文（逐字不变）；
     *  - seal 失配（身体或文本被改过）⇒ 旧批作废，回落现算真读数，绝不拿失效批准顶数。
     */
    fun effectiveLine(seal: InvariantSeal?, basis: String, freshLine: String): String =
        if (seal != null && seal.who == InvariantSeal.WHO_USER && seal.validFor(basis, freshLine)) {
            seal.line
        } else {
            freshLine
        }

    /** 该 sealed 行当前是否仍生效（供 UI/测试查询，不改变取行逻辑）。 */
    fun isEffective(seal: InvariantSeal?, basis: String, freshLine: String): Boolean =
        seal != null && seal.who == InvariantSeal.WHO_USER && seal.validFor(basis, freshLine)
}
