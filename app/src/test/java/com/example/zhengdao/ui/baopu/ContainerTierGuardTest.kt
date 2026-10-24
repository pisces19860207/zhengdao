// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 四档容器守卫（第一百三十七批，派工单 §0② / 设计语言 §6.1）。
 *
 * 钉四件事：
 * 1. **只有四档**——行动卡 / 信息卡 / 记录条的容器函数只许在 `BaopuContainers.kt` 定义；
 *    正文档没有容器函数（零代码表达「没有边界」），由消息行的透明 Surface 承担。
 * 2. **边界位置四选一、不许叠加**——行动卡只许「四边 2dp」、信息卡只许「左边 4dp」、
 *    记录条只许「下边 1dp」；同一容器里出现第二种边界通道即红。
 * 3. **尺寸常量与设计文档一致**（2/4/44/48/1/16/12/8；48 = 139 批的记录条命中热区）。
 * 4. **行动卡的调用点只有五个已知文件**——多一个就是「新造第五种卡」或「到处行动卡」，
 *    也就破了一屏最多 1 张的规矩。
 *
 * 为什么用源码级断言而不是 UI 测试：本模块没有 Compose UI 测试基建（无 Robolectric），
 * 与 `ContrastGuardTest` / `RouteRestoreGuardTest` 同惯例——判据取文本、断言可执行。
 */
class ContainerTierGuardTest {

    private fun repoFile(rel: String): File {
        val cands = listOf(File(rel), File("../$rel"))
        return cands.firstOrNull { it.isFile || it.isDirectory }
            ?: throw AssertionError("找不到 $rel（试过：${cands.joinToString { it.absolutePath }}）")
    }

    /** 某个包里所有 .kt 的「路径 → 文本」，用于跨文件扫描。 */
    private fun sources(pkgRel: String): Map<String, String> {
        val dir = repoFile(pkgRel)
        return dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.name to it.readText() }
    }

    @Test
    fun `门禁_四档容器只在 BaopuContainers 定义`() {
        val all = sources(BAOPU_DIR)
        val tiers = listOf("BaopuActionContainer", "BaopuInfoContainer", "BaopuRecordRow")
        tiers.forEach { name ->
            val defs = all.filterValues { Regex("""fun\s+$name\s*\(""").containsMatchIn(it) }.keys
            assertTrue("$name 必须且只能在 BaopuContainers.kt 定义，实际定义处：$defs", defs == setOf("BaopuContainers.kt"))
        }
        // 反证：不许再多出第四个容器函数——**任何** `internal fun BaopuXxx(` 都必须落在三档里。
        // 2026-10-22 负例实测：早期写法只匹配 Container/Row/Slot/Card 结尾，用 `BaopuTierFiveBox`
        // 就能绕过去（负例①当时没红）⇒ 改成白名单式，尾部命名不再有豁免。
        val declared = all["BaopuContainers.kt"].orEmpty()
            .let { Regex("""internal fun (Baopu\w*)\s*\(""").findAll(it).map { m -> m.groupValues[1] }.toSet() }
        assertTrue(
            "BaopuContainers.kt 里的容器函数只许这三个，多了：${declared - tiers.toSet()}",
            declared == tiers.toSet(),
        )
    }

    @Test
    fun `门禁_行动卡只有四边描边一种边界`() {
        val src = repoFile("$BAOPU_DIR/BaopuContainers.kt").readText()
        val body = funBody(src, "BaopuActionContainer")
        assertTrue("行动卡必须用 .border(…) 画四边", body.contains(".border("))
        assertTrue("行动卡描边必须用 2dp 常量", body.contains("BAO_ACTION_STROKE_DP.dp"))
        assertTrue("行动卡不许再画左边竖条（边界叠加）", !body.contains("BAO_INFO_BAR_DP"))
        assertTrue("行动卡不许再画下边发丝线（边界叠加）", !body.contains("drawBehind"))
        assertTrue("行动卡必须有浅青标题条", body.contains("primaryContainer"))
    }

    @Test
    fun `门禁_信息卡只有左边竖条一种边界`() {
        val src = repoFile("$BAOPU_DIR/BaopuContainers.kt").readText()
        val body = funBody(src, "BaopuInfoContainer")
        assertTrue("信息卡必须画左竖条", body.contains("BAO_INFO_BAR_DP.dp"))
        assertTrue("信息卡不许描四边（§2.1 第 3 条：1dp 发丝线看不见，改走形状通道）", !body.contains(".border("))
        assertTrue("信息卡不许画下边发丝线", !body.contains("drawBehind"))
        assertTrue("信息卡底必须是 paperDeep（surfaceVariant）", body.contains("surfaceVariant"))
    }

    @Test
    fun `门禁_记录条只有下边发丝线一种边界`() {
        val src = repoFile("$BAOPU_DIR/BaopuContainers.kt").readText()
        val body = funBody(src, "BaopuRecordRow")
        assertTrue("记录条必须在下边画 1dp 发丝线", body.contains("drawBehind") && body.contains("BAO_HAIRLINE_DP"))
        assertTrue("记录条不许描四边", !body.contains(".border("))
        assertTrue("记录条不许填底（它落在纸面上）", !body.contains(".background("))
        assertTrue("记录条行高必须取 44dp 常量", body.contains("BAO_RECORD_HEIGHT_DP.dp"))
        assertTrue("记录条命中热区必须取 48dp 常量（139）", body.contains("BAO_RECORD_TOUCH_DP.dp"))
    }

    @Test
    fun `门禁_四档尺寸常量与设计文档一致`() {
        val src = repoFile("$BAOPU_DIR/BaopuContainers.kt").readText()
        val expect = mapOf(
            "BAO_ACTION_STROKE_DP" to 2,
            "BAO_INFO_BAR_DP" to 4,
            "BAO_INFO_INSET_DP" to 12,
            "BAO_RECORD_HEIGHT_DP" to 44,
            "BAO_RECORD_TOUCH_DP" to 48,
            "BAO_RECORD_INSET_DP" to 8,
            "BAO_HAIRLINE_DP" to 1,
        )
        expect.forEach { (name, v) ->
            assertTrue(
                "$name 应为 $v，实际取不到或不等",
                Regex("""const val $name = $v\b""").containsMatchIn(src),
            )
        }
    }

    @Test
    fun `门禁_行动卡调用点只许六个已知文件`() {
        val all = sources(BAOPU_DIR)
        val callers = all.filterValues { it.contains("BaopuActionContainer(") }.keys
            .filter { it != "BaopuContainers.kt" }
        val expect = setOf(
            "BaopuAskUserCard.kt", // 提问
            "BaopuChoiceCard.kt", // 选择
            "BaopuConfirmCard.kt", // 确认（含恢复确认：restoreConfirmCard 最终也渲染这条链）
            "BaopuSkillCard.kt", // 技能提案（御批）
            "DecisionCard.kt", // 决策卡
            "SkillCard.kt", // 技能记忆入口（184 批：说一句就记，只有一个「记住它」）
        )
        assertTrue(
            "行动卡调用点只许这六个（多一个＝新造卡型或到处行动卡，破「一屏最多 1 张」）：实际 $callers",
            callers.toSet() == expect,
        )
    }

    @Test
    fun `门禁_消息流卡不再自带底色自造容器`() {
        // 这 6 个文件里的卡必须整体走容器：不许再出现自己 new 的 Surface 当卡壳。
        // （DecisionCard 例外：它的 Surface 只用来画警示带 / 证据 chip / 徽标，不是卡壳。）
        listOf(
            "BaopuAskUserCard.kt", "BaopuChoiceCard.kt", "BaopuConfirmCard.kt",
            "BaopuLearnCard.kt", "BaopuPostAdoptionCard.kt", "BaopuMemoryCard.kt",
        ).forEach { f ->
            val src = repoFile("$BAOPU_DIR/$f").readText()
            assertTrue("$f 仍在自造 Surface 卡壳（应改用四档容器）", !src.contains("Surface("))
        }
        val decision = repoFile("$BAOPU_DIR/DecisionCard.kt").readText()
        assertTrue("决策卡主壳必须走 BaopuActionContainer", decision.contains("BaopuActionContainer("))
    }

    @Test
    fun `门禁_正文档在消息行里透明无边界`() {
        val src = repoFile("$BAOPU_DIR/BaopuMessageList.kt").readText()
        assertTrue("助手消息必须透明底（正文档）", src.contains("Color.Transparent"))
        assertTrue("助手消息必须无圆角形状（正文档）", src.contains("RectangleShape"))
        // 139：轮次摘要行已换成轮尾过程条；记录条容器由过程条复用（不许新造第五种）。
        val bar = repoFile("$BAOPU_DIR/BaopuProcessBar.kt").readText()
        assertTrue("轮次过程条必须走记录条容器", bar.contains("BaopuRecordRow("))
        assertTrue("消息列表不许再自己画记录条", !src.contains("BaopuRecordRow("))
    }

    /** 取某个顶层函数的花括号正文（第一个 `{` 到匹配的 `}`），够用的朴素配对。 */
    private fun funBody(src: String, funName: String): String {
        val start = src.indexOf("fun $funName(")
        if (start < 0) throw AssertionError("找不到 fun $funName")
        val open = src.indexOf('{', start)
        if (open < 0) throw AssertionError("fun $funName 没有函数体")
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("fun $funName 的花括号不配对")
    }

    private companion object {
        const val BAOPU_DIR = "app/src/main/java/com/example/zhengdao/ui/baopu"
    }
}
