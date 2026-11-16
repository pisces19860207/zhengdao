// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百七十二批 · 判据⑤（源码断言）：**模型不得自动选**——裁定这条路上不许出现
// 自动 resolve 的代码路径。四条硬断言，全部剥注释后再看（KDoc 里提名字是说明，不算引用）：
//   ① 工具层（`baopu/agent/**`，模型够得着的地方）零裁定引用：没有「替我选」的工具；
//   ② `ObserveCenter.adjudicate(` 的调用方只有治理中心「学习」区一张卡（人不经对话流）；
//   ③ 认 `DraftChoice`／`DraftAdjudication` 的文件只有白名单里那几个——归纳侧
//      （SessionMerger／DraftInducer／LearnSubmit）在名单外，长不出「按命中多的那套代选」；
//   ④ 判据⑥的源码半边：645 的 OBSERVE 端口（`bridge/BrowserPort.kt`）与 623 的胶囊
//      （`ui/browser/NotLearnedCapsulePill.kt`）零裁定引用——本单没往那两处接线。
//
// 自检带负例（[violations] 是纯函数，喂合成源码必须报红）：只断言「真源码没这词」会恒真。
package com.example.zhengdao.baopu.learn

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

internal class DraftAdjudicateSourceGateTest {

    /** 允许认识 `DraftChoice` / `DraftAdjudication*` 的文件（相对 main 源码根）。 */
    private val adjudicationFiles = setOf(
        "com/example/zhengdao/baopu/learn/DraftAdjudication.kt", // 定义处
        "com/example/zhengdao/baopu/learn/AdjudicationCard.kt", // 卡的纯层读数
        "com/example/zhengdao/baopu/learn/SkillDraft.kt", // 转正门（判据本体）
        "com/example/zhengdao/baopu/learn/DraftConverter.kt", // 转换前落地裁定
        "com/example/zhengdao/baopu/learn/ObserveCenter.kt", // 唯一的写入口 + 唯一的读口
        "com/example/zhengdao/ui/baopu/LearnBoardSection.kt", // 唯一的按钮来源（人）
    )

    /** 允许调 `ObserveCenter.adjudicate(` 的文件——只有治理中心学习区那张卡。 */
    private val adjudicateCallers = setOf("com/example/zhengdao/ui/baopu/LearnBoardSection.kt")

    private fun strip(s: String): String = s
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    /**
     * 纯判据：给一份「相对路径 → 源码」，返回违规清单（空＝合规）。
     * 抽成函数是为了能喂合成源码验负例（否则这条断言恒真）。
     */
    fun violations(sources: Map<String, String>): List<String> {
        val out = mutableListOf<String>()
        sources.forEach { (path, raw) ->
            val code = strip(raw)
            val touches = code.contains("DraftChoice") || code.contains("DraftAdjudication")
            if (code.contains("ObserveCenter.adjudicate(") && path !in adjudicateCallers) {
                out += "$path 调了 ObserveCenter.adjudicate（裁定只能由人从学习区点）"
            }
            if (path.startsWith("com/example/zhengdao/baopu/agent/") && touches) {
                out += "$path 在工具层引用了裁定（模型不得自动选）"
            }
            if (touches && path !in adjudicationFiles) {
                out += "$path 认识裁定类型但不在白名单（防止归纳侧长出代选路径）"
            }
        }
        return out
    }

    private val mainRoot = File("src/main/java").let { if (it.isDirectory) it else File("app/src/main/java") }

    private fun productionSources(): Map<String, String> {
        assertTrue("找不到 main 源码根：${mainRoot.path}", mainRoot.isDirectory)
        return mainRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(mainRoot).invariantSeparatorsPath to it.readText() }
    }

    // ── 负例先行：判据本身不是恒真 ───────────────────────────────────────────────

    @Test
    fun `负例_合成源码里的自动裁定路径必须报红`() {
        assertTrue("干净源码必须零违规：${violations(mapOf("com/example/zhengdao/baopu/learn/Foo.kt" to "internal fun a() = 1"))}",
            violations(mapOf("com/example/zhengdao/baopu/learn/Foo.kt" to "internal fun a() = 1")).isEmpty())
        val auto = mapOf(
            "com/example/zhengdao/baopu/learn/SessionMerger.kt" to
                "internal fun pick() = DraftChoice.A // 按命中数代选",
            "com/example/zhengdao/baopu/agent/tools/LearnTools.kt" to
                "internal fun quiet() { ObserveCenter.adjudicate(\"d1\", 1, DraftChoice.A) }",
            "com/example/zhengdao/ui/baopu/BaopuChat.kt" to
                "internal fun inChat() { ObserveCenter.adjudicate(\"d1\", 1, DraftChoice.B) }",
        )
        val found = violations(auto)
        assertTrue("至少三条（归并侧／工具层／对话流）：$found", found.size >= 3)
        assertTrue("归并侧必须报红：$found", found.any { it.contains("SessionMerger.kt") })
        assertTrue("工具层必须报红：$found", found.any { it.contains("LearnTools.kt") && it.contains("模型不得自动选") })
        assertTrue("对话流必须报红：$found", found.any { it.contains("BaopuChat.kt") })
    }

    @Test
    fun `负例_只有注释提到裁定不算违规`() {
        val kdoc = mapOf(
            "com/example/zhengdao/baopu/learn/DraftInducer.kt" to
                "/** 这里不碰 DraftChoice：裁定是人做的事\n * 也不调 ObserveCenter.adjudicate( */\ninternal fun nothing() = Unit",
        )
        assertTrue("剥注释后不报红：${violations(kdoc)}", violations(kdoc).isEmpty())
    }

    // ── 真源码：四条硬断言 ───────────────────────────────────────────────────

    @Test
    fun `判据5_工具层零裁定引用_且只有学习区一张卡能调`() {
        val sources = productionSources()
        val found = violations(sources)
        assertTrue("真源码必须合规：$found", found.isEmpty())
        // 反向确认扫描真读到了东西（不是空集恒真）
        assertTrue("扫描面太小，判据不成立", sources.size > 500)
        assertTrue("裁定定义处必须在扫描面里", sources.containsKey("com/example/zhengdao/baopu/learn/DraftAdjudication.kt"))
    }

    @Test
    fun `判据6_645端口与623胶囊零裁定引用`() {
        val sources = productionSources()
        val port = sources["com/example/zhengdao/bridge/BrowserPort.kt"]
            ?: error("找不到 bridge/BrowserPort.kt——判据⑥的扫描面没了")
        val pill = sources["com/example/zhengdao/ui/browser/NotLearnedCapsulePill.kt"]
            ?: error("找不到 623 胶囊——判据⑥的扫描面没了")
        listOf("645 端口" to port, "623 胶囊" to pill).forEach { (name, code) ->
            val stripped = strip(code)
            assertTrue("$name 被本单接上了裁定（判据⑥不许）", !stripped.contains("DraftChoice") && !stripped.contains("DraftAdjudication"))
            assertTrue("$name 调了 adjudicate（判据⑥不许）", !stripped.contains("ObserveCenter.adjudicate("))
        }
    }
}
