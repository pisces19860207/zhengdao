// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单《第九十三批 · 模型路由 v1》§2「不新建第二套状态／当前模型仍只有一份真相」、
// §3 负例（日志打印 API key ⇒ 门禁红）与 **第一百六十九批 A 案**（AUTO_ROUTE 死开关整条移除，
// 全仓不许再出现「读了但永远写不了」的开关）。
//
// 为什么用源码形状门禁而不是行为用例：要钉的是「不存在第二条路」这种结构性事实
// —— 行为用例只能证明某条路径对，证明不了别处没有暗仓。同款范式见
// ChannelSwitchTruthGateTest（64 批）/ MemoryWriteLogGateTest / CompressionSummaryShadowGateTest。
package com.example.zhengdao.baopu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型路由与能力过滤的结构性门禁（G1–G9）。
 *
 * 每一条都对应派工单里一句红线；负例演练（报告 §六）逐条把这些判定去掉，
 * 对应用例必须红 —— 也就是说这些门禁自己是「会疼的」，不是装饰。
 *
 * G1 在第一百六十九批改成**反向门禁**：AUTO_ROUTE 实测是死开关（`setAutoRoute` 生产零调用、
 * key 私有 ⇒ 每轮读到的永远是 false），按派工单 A 案删除；从此全仓出现任何 `auto_route` /
 * `autoRoute` / `AUTO_ROUTE` 残留都必须红，防止有人再把「只读不写」的开关接回来。
 */
class ModelRoutingGateTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun read(rel: String): String = repoFile(rel).readText()

    /** 去掉注释后的代码文本（KDoc 里提"不许出现 ProviderPrefs"这类纪律声明不算引用）。 */
    private fun codeOnly(src: String): String =
        src.split("\n").joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    private val routerFile = "src/main/java/com/example/zhengdao/baopu/ModelRouter.kt"
    private val tableFile = "src/main/java/com/example/zhengdao/baopu/ModelCapabilityTable.kt"
    private val prefsFile = "src/main/java/com/example/zhengdao/baopu/ProviderPrefs.kt"
    private val runtimeFile = "src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt"
    private val engineFile = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"

    private val routerCode = codeOnly(read(routerFile))
    private val tableCode = codeOnly(read(tableFile))
    private val prefsCode = codeOnly(read(prefsFile))

    // ── G1 死开关已删除（169 批）：全仓不许再出现 auto_route 残留 ─────────────

    @Test
    fun 生产代码里不许出现任何auto_route开关残留() {
        val residue = Regex("(?i)auto_?route|routepreference")
        val hits = mainSources()
            .filter { residue.containsMatchIn(codeOnly(it.readText())) }
            .map { it.name }
            .sorted()
        assertEquals(
            "AUTO_ROUTE 已按 169 批 A 案整条删除；再出现读写任何一半都是死开关复活",
            emptyList<String>(),
            hits,
        )
        assertFalse(
            "写入口 setAutoRoute 不许复活",
            mainSources().any { codeOnly(it.readText()).contains("setAutoRoute") },
        )
    }

    @Test
    fun 路由判据里不再有自动排序与开关入口() {
        listOf("enum class Preference", "sealed interface Plan", "fun plan(", "fun rank(", "fun keyOf(", "fun preferenceOf(", "fun cnName(")
            .forEach { gone ->
                assertFalse("169 批删掉的自动路径不许回来：$gone", routerCode.contains(gone))
            }
        assertTrue("能力过滤必须还在（看图的 VisionRouting 在用）", routerCode.contains("fun missingOf("))
        assertTrue(routerCode.contains("fun satisfies("))
    }

    // ── G2 表不重复存 Provider 已有的列（§2 单一真相） ───────────────────────

    @Test
    fun 能力表里不许有vision与窗口与输出上限这三列() {
        listOf("vision", "contextWindow", "maxOutput", "reasoningEffort").forEach { col ->
            assertFalse(
                "$col 的唯一真相在 Provider（表里存一份就是第二真相，两份必漂）",
                Regex("val\\s+$col\\b").containsMatchIn(tableCode),
            )
        }
        // Provider 那一侧仍然只有一处定义
        val providerCode = codeOnly(read("src/main/java/com/example/zhengdao/baopu/Provider.kt"))
        listOf("vision", "contextWindow", "maxOutput").forEach { col ->
            assertEquals("$col 在 Provider 里恰一处声明", 1, Regex("val\\s+$col\\s*:").findAll(providerCode).count())
        }
    }

    @Test
    fun 成本与延迟这几列只住在表里_别处不许再声明一遍() {
        listOf("inputPer1k", "outputPer1k", "latencyP50Ms", "latencyP90Ms", "qualityTier").forEach { col ->
            val owners = mainSources().filter { Regex("val\\s+$col\\s*[:=]").containsMatchIn(codeOnly(it.readText())) }
            assertEquals("$col 只许在能力表里声明一次", listOf("ModelCapabilityTable.kt"), owners.map { it.name })
        }
    }

    // ── G3 判据零 Android 依赖、且不写日志（§5「打印密钥」这条路整个封掉） ────

    @Test
    fun 路由与表两个判据文件不碰Android也不写任何日志() {
        listOf("ModelRouter.kt 路由判据" to routerCode, "ModelCapabilityTable.kt 能力表" to tableCode).forEach { (name, code) ->
            listOf("android", "SharedPreferences", "getSharedPreferences", "RunLog", "Log.", "println", "print(")
                .forEach { forbidden ->
                    assertFalse("$name 是纯判据，不许出现 $forbidden", code.contains(forbidden))
                }
            // 「Context」按**类型名**判，不按子串判：判据合法地写着
            // ContextAssembler.ContextBudgets.windowOf —— 那是「窗口没声明怎么算」的**唯一一处**
            // 回退（Candidate KDoc 里点名的复用），与 android.content.Context 不是一个东西。
            assertFalse(
                "$name 不许出现 Android 的 Context（裸词 Context；子串 ContextAssembler 不算）",
                Regex("\\bContext\\b").containsMatchIn(code),
            )
            // 这条例外不是空白支票：真出现 ContextAssembler 就必须是 windowOf 那一处写法
            if (code.contains("ContextAssembler")) {
                assertEquals(
                    "$name 里的 ContextAssembler 只许是窗口回退复用这一处",
                    1,
                    Regex("ContextAssembler\\.ContextBudgets\\.windowOf\\(").findAll(code).count(),
                )
            }
        }
        // 路由文件根本不认识 Key 这个概念（它拿到的候选由 ProviderPrefs 备好）
        listOf("apiKey" to routerCode, "apiKey" to tableCode, "keyFor" to routerCode).forEach { (word, code) ->
            assertFalse("路由判据不许碰 Key：$word 出现在判据里就是第二入口", code.contains(word))
        }
    }

    // ── G4 话术不许内插 Provider 对象（data class toString 会把 apiKey 打出来） ──

    @Test
    fun 路由的任何字符串模板都不许直接内插一个Provider() {
        val holes = Regex("\\$\\{([^{}]*)}").findAll(routerCode).map { it.groupValues[1].trim() }.toList()
        assertTrue("路由话术里必须有模板（否则这条门禁是空的）：${holes.size}", holes.isNotEmpty())
        val providerNames = setOf("current", "head", "picked", "p", "it", "provider", "candidate", "c")
        val bad = holes.filter { it in providerNames || it.endsWith(".toString()") && it.removeSuffix(".toString()") in providerNames }
        // 负例 N3 的实测教训：`${current}` 会红，`$current` 同样泄 Key —— 上面那条只查带花括号的一半，
        // 裸写法必须单独再查一遍（`$want` / `$tier` 这些安全变量不在名单里，不误伤）。
        val bare = Regex("\\$([A-Za-z_][A-Za-z0-9_]*)").findAll(routerCode).map { it.groupValues[1] }.toList()
        val badBare = bare.filter { it in providerNames }
        assertEquals(
            "只许取 .id / .model / .label 这些安全字段；整个 Provider 进字符串就是泄 Key " +
                "（Provider 是 data class，toString() 带 apiKey）。违规模板：${bad + badBare}",
            emptyList<String>(),
            bad + badBare,
        )
        // ProviderPrefs 的路由区段同理：新加的话术不在这里，它只回 Provider 对象
        assertFalse("路由话术不许进 SP 薄壳（薄壳不产人类可读文案）", prefsCode.contains("排序选中"))
    }

    // ── G5 不新建第二套状态（§2「回退复用 64」） ────────────────────────────

    @Test
    fun 路由判据是零状态的_且不认识第六十四批的状态机() {
        assertFalse("路由不许持有可变状态字段（var）：什么时候切、切失败怎么办全在 64", Regex("\\bvar\\s").containsMatchIn(routerCode))
        listOf("Fsm", "ChannelSwitch", "Coordinator", "SafePoint", "selectChannel", "PENDING", "SWITCHING")
            .forEach { word -> assertFalse("路由判据里出现 $word 就是第二套切换逻辑", routerCode.contains(word)) }
        // 机器（ChannelSwitchFsm）与协作者/快照（ChannelSwitchCoordinator / ChannelSwitchSnapshot）
        // 同住一个文件；RunEngine 是构造点，代码里出现的是后两个名字 ⇒ 三个都得查。
        val owners = mainSources()
            .filter {
                Regex("ChannelSwitchFsm|ChannelSwitchCoordinator|ChannelSwitchSnapshot")
                    .containsMatchIn(codeOnly(it.readText()))
            }
            .map { it.name }
            .sorted()
        assertEquals(
            "状态机只许住在 64 那两个生产文件里（机器与协作者同文件；RunEngine 是构造点）",
            listOf("ChannelSwitchFsm.kt", "RunEngine.kt"),
            owners,
        )
    }

    // ── G6 表的消费者唯一 ──────────────────────────────────────────────────

    @Test
    fun 能力表只被路由判据消费_不散落到别处更不能散落UI() {
        val owners = mainSources().filter { codeOnly(it.readText()).contains("ModelCapabilityTable") }.map { it.name }.sorted()
        assertEquals(
            "表只有一个读者（ModelRouter）；UI 要显示路由状态得走 State，不许直接读表",
            listOf("ModelCapabilityTable.kt", "ModelRouter.kt"),
            owners,
        )
    }

    // ── G7 「现在是谁」与「下一个是谁」互不越界 ─────────────────────────────

    @Test
    fun 解析当前通道的resolve里没有路由入参_也没有查表() {
        val body = prefsCode.substringAfter("internal fun resolve(").substringBefore("internal fun ")
        listOf("ModelRouter", "ModelCapabilityTable", "autoRoute", "preference", "need").forEach { w ->
            assertFalse("resolve 里出现 $w ⇒ 「当前通道」被路由改，就是默认改通道", body.contains(w))
        }
        // 反过来也一样：回退只选人、不改 SP，而且它的签名里不许再长出路由参数
        val fbSignature = prefsCode.substringAfter("internal fun fallbackFrom(").substringBefore("): Provider?")
        listOf("autoRoute", "preference", "need", "ModelRouter").forEach { w ->
            assertFalse("fallbackFrom 签名里出现 $w ⇒ 死开关借尸还魂", fbSignature.contains(w))
        }
    }

    // ── G8 装配层只读接线 ──────────────────────────────────────────────────

    @Test
    fun 装配层不再读取任何路由开关_回退只剩一条调用() {
        val runtime = codeOnly(read(runtimeFile))
        listOf("autoRoute", "routePreference", "setAutoRoute", "AUTO_ROUTE", "ModelRouter")
            .forEach { w -> assertFalse("169 批删掉的读取不许回来：$w", runtime.contains(w)) }
        assertEquals(
            "回退调用仍只有一处（行为＝名单第一个，唯一路径）",
            1,
            Regex("ProviderPrefs\\.fallbackFrom\\(").findAll(runtime).count(),
        )
        assertFalse("装配层不许查能力表（表只被路由判据读）", runtime.contains("ModelCapabilityTable"))
    }

    @Test
    fun 引擎侧零改动_路由没绕开64的漏斗自己切通道() {
        val engine = codeOnly(read(engineFile))
        listOf("ModelRouter", "ModelCapabilityTable", "AUTO_ROUTE", "autoRoute", "routePreference")
            .forEach { w -> assertFalse("RunEngine 里出现 $w ⇒ 路由绕过了熔断/安全点那两条既有出口", engine.contains(w)) }
        // 64 的漏斗计数仍然是两处 provider 写入口（本批一行没动它）
        assertEquals(
            "provider 字段写入口仍然恰两处：①熔断降级 ②安全点切换（路由只改「下一个是谁」的选法）",
            2,
            Regex("(?<![.=!<>])provider\\s*=\\s*[^=]").findAll(engine).count(),
        )
    }

    // ── G9 表体自证（源码侧：重复行与"没依据"的写法） ────────────────────────

    @Test
    fun 表体里没数据的那几列一律写null_不许写0或空串冒充() {
        val rows = read(tableFile).substringAfter("val ROWS: List<Row> = listOf(").substringBefore("\n    )")
        listOf("inputPer1k", "outputPer1k", "currency", "latencyP50Ms", "latencyP90Ms", "qualityTier").forEach { col ->
            val vals = Regex("$col = ([^,\\n]+)").findAll(rows).map { it.groupValues[1].trim() }.toSet()
            assertEquals("$col 在表体里只能写 null（写 0 / 空串就是拿「没数据」当依据）", setOf("null"), vals)
        }
    }

    private fun mainSources(): List<File> =
        repoFile("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
}
