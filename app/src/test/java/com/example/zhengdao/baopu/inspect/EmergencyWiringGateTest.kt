// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百一十五批 · 紧急闸的**源级接线门禁**（判据⑤"有生产调用点"的本体；行为判据见 EmergencyWiringTest）。
//
// ## 为什么门禁打在源码上
// 紧急闸的三道口（人批／止损／追认）全是"接线容易被悄悄拆掉"的形状：
// 删一句 `EmergencyClocks.` 调用、换成一个手抄字面量，行为测试照样绿——只有盯着生产源码才看得见。
// 本文件照 `LawSourceWiringGateTest` 的口径做（只认代码行、注释不算、只扫生产），钉四件事：
//
//   1. **接线必须两根都在**（负例④）：EmergencyWiring.kt 对 EmergencyGate 与 EmergencyClocks
//      的点号调用逐字钉住——只接 Gate 不接 Clocks，或删掉任何一根 ⇒ 红；
//   2. **自决放行点点名册**（判据①）：生产里凡写 `SelfRulingLedger.note`（自决放行的落账口）
//      的文件必须在本文件的名册上——名单外冒出第二个自决放行点（没过 EmergencyWiring 的
//      紧急卡门）⇒ 红；
//   3. **拒因唯一出自 law/**：EmergencyWiring.kt 里必须有 `EmergencyGate.selfRulingRejection(`
//      那一次调用——本文件自己编拒因＝第二个口径 ⇒ 红；
//   4. **止损签名里没有"等人"的参数**（判据②的形状面）：executeStopLoss 的参数表不许出现
//      approved／approval／confirm／await——等人的口子在形状上就不许存在。
package com.example.zhengdao.baopu.inspect

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** inspect/ 在生产树里的相对路径（被钉的接线宿主）。 */
private const val WIRING_PATH = "com/example/zhengdao/baopu/inspect/EmergencyWiring.kt"

/**
 * 自决放行点的点名册（`SelfRulingLedger.note` 的生产调用方）。
 *
 * 现册两行：
 * - 277 的 OpinionWiring（SKIP 才落自决单）；
 * - 283 的 LearnWiring（学习器压下时落 L33 自决单）。
 * 两处的紧急卡收敛都不在 315（agent/ 禁区，见交付报告偏差①）；名单外新增即红。
 */
private val SELF_RULING_SITES = listOf(
    "com/example/zhengdao/baopu/agent/LearnWiring.kt",
    "com/example/zhengdao/baopu/agent/OpinionWiring.kt",
)

/** 止损签名里不许出现的"等人"字样（参数名与参数表都不许有）。 */
private val WAITING_TOKENS = listOf("approved", "approval", "confirm", "await")

class EmergencyWiringGateTest {

    private fun repoFile(rel: String): File = File(rel).let { if (it.exists()) it else File("app/$rel") }

    private val mainRoot = repoFile("src/main/java")

    /** `app/src/main` 全部 Kotlin 源，[code] 已剥注释（注释里的字面不算接线）。 */
    private val mainSources: List<Pair<String, String>> by lazy {
        require(mainRoot.isDirectory) { "main 源目录没找到：${mainRoot.absolutePath}" }
        mainRoot.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .map { it.relativeTo(mainRoot).path.replace('\\', '/') to codeOnly(it.readText()) }
            .toList()
    }

    private fun codeOnly(src: String): String = src
        .split("\n")
        .filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }
        .joinToString("\n")

    private fun source(path: String): String =
        mainSources.firstOrNull { it.first == path }?.second
            ?: error("生产源不见了：$path（拆文件必须同步改本门禁的名册）")

    // ── 1. 接线两根都在，一根都不能少（判据⑤＋负例④） ──────────────────────────

    @Test
    fun 判据五_两根法条接线逐字钉住() {
        val wiring = source(WIRING_PATH)
        val gateRefs = Regex("""\bEmergencyGate\s*\.""").findAll(wiring).count()
        val clockRefs = Regex("""\bEmergencyClocks\s*\.""").findAll(wiring).count()
        assertTrue("EmergencyGate 没接：紧急卡的人批判据还是零引用", gateRefs > 0)
        assertTrue(
            "只接了 EmergencyGate 不接 EmergencyClocks：三时钟（止损 0s／上报 3s／追认 24h）必须同样出自 law/",
            clockRefs > 0,
        )
        // 点号调用逐字钉死：删掉任何一根（哪怕还留着一根）都算拆线。
        assertEquals("EmergencyGate 的接线数变了：少一处＝拆线，多一处＝第二个口径", 3, gateRefs)
        assertEquals("EmergencyClocks 的接线数变了：少一处＝拆线，多一处＝第二个口径", 6, clockRefs)
    }

    // ── 2. 自决放行点点名册（判据①的源级面） ────────────────────────────────────

    @Test
    fun 判据一_自决放行点不许绕开紧急卡门() {
        val sites = mainSources.mapNotNull { (path, code) ->
            if (Regex("""\bSelfRulingLedger\s*\.\s*note\b""").containsMatchIn(code)) path else null
        }.sorted()
        assertEquals(
            "自决放行点（SelfRulingLedger.note 的调用方）名单变了：新落点必须先过 " +
                "EmergencyWiring 的 selfRulingRejection（紧急卡必须人批），并把新宿主登记进本门禁",
            SELF_RULING_SITES,
            sites,
        )
    }

    @Test
    fun 拒因唯一出自law层() {
        val wiring = source(WIRING_PATH)
        assertTrue(
            "自决拒因必须调 EmergencyGate.selfRulingRejection：本文件自己编拒因＝第二个口径",
            wiring.contains("EmergencyGate.selfRulingRejection("),
        )
        assertTrue(
            "自决门必须是 Opinion 的入口形状（fun Opinion.selfRulingRejection）",
            wiring.contains("fun Opinion.selfRulingRejection(): String?"),
        )
    }

    // ── 3. 止损签名里没有"等人"的参数（判据②的形状面） ──────────────────────────

    @Test
    fun 判据二_止损签名里没有等人的口子() {
        val wiring = source(WIRING_PATH)
        // 参数表取到函数体开括号为止——不能 substringBefore(")")：默认值里的
        // System.currentTimeMillis() 会让截断提前发生，排在它后面的审批位就漏检了
        //（N2 负例实测：那种写法行为面照红、形状面却绿）。
        val signature = wiring
            .substringAfter("fun executeStopLoss(", "")
            .substringBefore("{")
        assertTrue("executeStopLoss 签名没找到", signature.isNotEmpty())
        WAITING_TOKENS.forEach { token ->
            assertTrue(
                "止损签名里出现「$token」：止损不许等人批，参数表里不许有审批位",
                !signature.lowercase().contains(token),
            )
        }
    }
}
