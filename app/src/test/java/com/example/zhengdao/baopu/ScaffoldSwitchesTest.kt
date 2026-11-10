// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十批：脚手架开关（历史预过滤／压卡摘要）的单测。
//
// 口径（照 149 组2 / CompressTriggerWiringGateTest 的先例）：
// · **判定用行为**：能拿到对象的走行为（ContextAssembler 渲染、纯函数真值表）；
// · **接线用源码形状**：RunEngine 的日志/触发在 Compose＋协程＋SQLite 里，JVM 打不到，
//   所以「引擎确实调了这两条闸」只能用剥注释后的源码结构钉——这是本仓既定的门禁范式，不是偷懒。
// · **补信任件禁止开关**：用**显式入口清单**钉，不按文件名扫（命名一变就漏）。
//
// 第五百五十七批：
// · ⑥「关掉压卡不发请求」**未做行为级验证**——RunEngine 构造要 BaopuDb（Android SQLite），
//   JVM 起不来（派工单⑥的退路：明写，不用纯函数冒充）。真值表只证判定层；新增的一格
//   证「判定层串在请求口上」：唯一的 runCompression 调用在 shouldRunCompression 的 if 体内。
// · ⑦ SP 读取那格从「恰 1 次」计数改结构断言（读取点存在＋键与默认值成对）；
//   UI 写口禁令与显式入口清单 560 已落地，本批核验保留（DataFence 声明在 GuardPipeline.kt:88，
//   清单的「停机/围栏入口」一行已覆盖派工单点名的围栏）。
// · 注入点不再钉实参名 appCtx；注释/字符串过滤换 CodeText（旧版 substringBefore("//")
//   会把字符串里的 "https://" 当注释截断）。
package com.example.zhengdao.baopu

import com.example.zhengdao.CodeText
import com.example.zhengdao.GateBodies
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaffoldSwitchesTest {

    // ── ① 行为：关掉历史预过滤 ⇒ 被摘要代言的原文重新进入上下文 ──────────────
    @Test
    fun `关闭历史预过滤后_被摘要代言的原文重新进入模型上下文`() {
        val history = listOf(
            HistoryMessage("old", HistoryMessage.ROLE_USER, "旧问题原文"),
            HistoryMessage("recent", HistoryMessage.ROLE_USER, "最近问题"),
        )
        val skipIds = setOf("old")

        fun render(enabled: Boolean): String = ContextAssembler.assemble(
            persona = emptyList(),
            facts = HardFacts("workspace", "model"),
            stickyMemories = emptyList(),
            newMemories = emptyList(),
            history = history,
            current = "",
            compressionSummary = "旧问题摘要",
            compressedIds = skipIds,
            skipIds = ScaffoldSwitches.historySkipIds(enabled, skipIds),
        ).render()

        val filtered = render(true)
        val unfiltered = render(false)
        assertFalse("开着预过滤：原文应被摘要代言", filtered.contains("旧问题原文"))
        assertTrue("关掉预过滤：原文应回来", unfiltered.contains("旧问题原文"))
        assertTrue("最近问题不该受影响", unfiltered.contains("最近问题"))
    }

    // ── ② 行为：压缩闸的真值表（纯函数级；引擎级不可达，见头注） ─────────────
    @Test
    fun `压卡摘要闸_开着且需要压缩才压缩_关掉一律不压`() {
        assertTrue(ScaffoldSwitches.shouldRunCompression(enabled = true, needsCompression = true))
        assertFalse(ScaffoldSwitches.shouldRunCompression(enabled = true, needsCompression = false))
        assertFalse("关掉开关后，即使需要压缩也不压", ScaffoldSwitches.shouldRunCompression(enabled = false, needsCompression = true))
        assertFalse(ScaffoldSwitches.shouldRunCompression(enabled = false, needsCompression = false))
    }

    // ── ③ 接线：引擎确实调了这两条闸（剥注释后的源码形状） ────────────────────
    @Test
    fun `引擎两处接线各一处_且都走脚手架闸`() {
        val engine = codeOnly(read(ENGINE))
        assertTrue("缺压卡闸调用", engine.contains("ScaffoldSwitches.shouldRunCompression("))
        assertTrue("缺历史预过滤调用", engine.contains("ScaffoldSwitches.historySkipIds("))
        assertEquals("压卡闸只许一处调用", 1, occurrences(engine, "ScaffoldSwitches.shouldRunCompression("))
        assertEquals("历史预过滤只许一处调用", 1, occurrences(engine, "ScaffoldSwitches.historySkipIds("))
        // 两个开关都必须由装配层注入（引擎不持 Context）；实参名不钉（appCtx 是局部名）。
        val runtime = codeOnly(read(RUNTIME))
        assertTrue("历史预过滤开关必须由装配层注入",
            Regex("""ScaffoldSwitches\.historyPrefilterEnabled\s*\(""").containsMatchIn(runtime))
        assertTrue("压卡摘要开关必须由装配层注入",
            Regex("""ScaffoldSwitches\.compressionPromptEnabled\s*\(""").containsMatchIn(runtime))
    }

    // ── ⑥ 接线（557）：压卡请求只在闸内发——「关掉 ⇒ 零请求」的结构保证 ─────────
    // 引擎级行为验证未做（头注已明写）；这一格钉的是请求口与闸的物理串联。
    @Test
    fun `压卡请求只在脚手架闸内发_闸外零调用`() {
        val engine = codeOnly(read(ENGINE))
        assertEquals(
            "runCompression 只许一处调用（闸外再有一处＝关掉开关也照压）",
            1,
            Regex("""(?<!fun )runCompression\(""").findAll(engine).count(),
        )
        val gated = GateBodies.blockBody(read(ENGINE), Regex("""if\s*\(\s*ScaffoldSwitches\.shouldRunCompression\s*\("""))
        assertTrue("引擎里压卡闸的 if 不在了", gated != null)
        assertTrue("唯一的 runCompression 调用必须在压卡闸的 if 体内", gated!!.contains("runCompression("))
    }

    // ── ④ 读取点：SP 键各有读取点，默认 true（557：存在性＋默认值，不钉恰 N 次） ──
    // `,?` 是 557 演练补出来的：把这一行 getBoolean 拆成三行并留尾逗号（ktlint 推荐的写法），
    // 原判据当场打红＝合法形状被误伤，故默认值判据必须容得下尾逗号。
    @Test
    fun `SP键读取点都在且默认开启`() {
        val source = codeOnly(read(SWITCHES))
        assertTrue("历史预过滤要有 SP 读取点且默认开",
            Regex("""getBoolean\(\s*ScaffoldSwitch\.HISTORY_PREFILTER\.key\s*,\s*true\s*,?\s*\)""").containsMatchIn(source))
        assertTrue("压卡摘要要有 SP 读取点且默认开",
            Regex("""getBoolean\(\s*ScaffoldSwitch\.COMPRESSION_PROMPT\.key\s*,\s*true\s*,?\s*\)""").containsMatchIn(source))
    }

    // ── ⑤ 补信任件禁止开关：显式入口清单（不按文件名扫） ──────────────────────
    @Test
    fun `补信任件五入口不读脚手架开关_UI只许读不许写`() {
        val trustEntries = listOf(
            "baopu/DecisionLedger.kt",          // 208 账本
            "baopu/authz/TaskGrantWire.kt",     // 授权
            "baopu/agent/GuardPipeline.kt",     // 停机/围栏入口（停机闸在管线）
            "baopu/agent/AskGovernance.kt",     // 授权档
            "baopu/consequence/ConsequenceLedger.kt", // 回滚/后果账
        )
        trustEntries.forEach { rel ->
            val f = repoFile("src/main/java/com/example/zhengdao/" + rel)
            assertTrue("清单里的入口文件应存在：$rel", f.isFile)
            val src = codeOnly(f.readText())
            assertFalse("$rel 不得读取脚手架开关", src.contains("ScaffoldSwitches"))
            assertFalse("$rel 不得持有脚手架 SP 键", src.contains("zhengdao-scaffold/"))
        }

        // UI 层：允许读（自治理中心/核对包要显示），**禁止写**（set 是变更口）
        val ui = repoFile("src/main/java/com/example/zhengdao/ui")
        assertTrue("UI 目录应存在", ui.isDirectory)
        ui.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val src = codeOnly(file.readText())
            assertFalse(
                "UI 不得调用脚手架写口：${file.path}",
                src.contains("ScaffoldSwitches.set("),
            )
        }
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────
    private fun read(rel: String): String = repoFile(rel).readText()

    /** 剥注释与字符串（CodeText 统一口径；字面量里的同名字样不算接线）。 */
    private fun codeOnly(src: String): String = CodeText.codeOnly(src)

    private fun occurrences(source: String, text: String): Int =
        Regex(Regex.escape(text)).findAll(source).count()

    private fun repoFile(path: String): File =
        File(path).let { if (it.exists()) it else File("app/$path") }

    private companion object {
        const val BASE = "src/main/java/com/example/zhengdao/"
        const val ENGINE = BASE + "baopu/agent/RunEngine.kt"
        const val RUNTIME = BASE + "baopu/BaopuRuntime.kt"
        const val SWITCHES = BASE + "baopu/ScaffoldSwitches.kt"
    }
}
