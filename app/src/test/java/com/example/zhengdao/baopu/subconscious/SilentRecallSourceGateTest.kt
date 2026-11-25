// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十五批 · 源码门禁：行为用例证明「现在没做」，源码门钉「做不了」。
// 每条判别器先喂违规样本自证咬得住，再扫真源码（防恒真绿灯）。
package com.example.zhengdao.baopu.subconscious

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SilentRecallSourceGateTest {

    private fun projectFile(rel: String): File {
        val cwd = File(".").canonicalFile
        return listOf(File(cwd, rel), File(cwd.parentFile, rel), File(cwd, "app/$rel"))
            .firstOrNull { it.exists() } ?: error("找不到 $rel（cwd=${cwd.absolutePath}）")
    }

    private fun stripComments(src: String): String = src
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

    private val srcRoot: File by lazy { projectFile("src/main/java/com/example/zhengdao") }

    private val mainSources: List<Pair<String, String>> by lazy {
        srcRoot.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .map { it.relativeTo(srcRoot).path.replace('\\', '/') to it.readText(Charsets.UTF_8) }
            .toList()
    }

    private val newFiles = listOf(
        "SilentRecall.kt", "SilentRecallLedger.kt", "SilentRecallSwitch.kt", "SilentRecallStore.kt",
    )

    private fun newCode(name: String): String =
        stripComments(projectFile("src/main/java/com/example/zhengdao/baopu/subconscious/$name").readText())

    private val allNewCode: String by lazy { newFiles.joinToString("\n") { newCode(it) } }

    private val bannedCopy = listOf(
        "建议", "应该", "记得", "别忘了", "尽快", "抓紧", "要不要", "试试",
        "加油", "别担心", "辛苦了", "请处理", "提醒你",
        "should", "suggest", "remind", "don't forget", "hurry", "cheer up",
    )

    private fun bannedIn(text: String): String? = bannedCopy.firstOrNull { text.contains(it, ignoreCase = true) }

    /** 字符串字面量（禁词只扫字面量：标识符与注释不算）。 */
    private fun literals(src: String): List<String> =
        Regex("\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"").findAll(src).map { it.groupValues[1] }.toList()

    /** 从函数签名起按花括号配平取函数体（判据：开关闸必须排在 publish 之前）。 */
    private fun functionBody(src: String, signature: String): String? {
        val start = src.indexOf(signature).takeIf { it >= 0 } ?: return null
        val open = src.indexOf('{', start).takeIf { it >= start } ?: return null
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
        }
        return null
    }

    @Test
    fun `判别器先自证_禁词与字面量提取都咬得住`() {
        assertNotNull("禁词判别器必须咬住催办样本", bannedIn("建议处理这条账"))
        assertNull("事实陈述不该命中", bannedIn("账行 L2-1 的结论是 FAIL"))
        assertEquals(listOf("账行", "标题"), literals("val a = \"账行\"\nval b = \"标题\""))
    }

    @Test
    fun `判据四_新件零推送渠道_零通知API`() {
        val forbidden = listOf(
            "NotificationManager", "NotificationCompat", "SafetyNotifier", "QuietCardGate(",
            "startForeground", "sendBroadcast", "NotificationChannel",
        )
        val hit = forbidden.filter(allNewCode::contains)
        assertTrue("新件出现推送 API：$hit", hit.isEmpty())
        // 判别器负例：喂一段含通知构造的源码必须红。
        assertTrue("判别器没咬住样本", forbidden.any { "val n = NotificationCompat.Builder(ctx)".contains(it) })
    }

    @Test
    fun `判据四_新件与双语文案零催办零建议零安慰`() {
        val xmlFiles = listOf(
            "src/main/res/values/strings_baopu_silent_recall.xml",
            "src/main/res/values-en/strings_baopu_silent_recall.xml",
        )
        val xmlText = xmlFiles.joinToString("\n") { projectFile(it).readText(Charsets.UTF_8) }
        val xmlValues = Regex("<string[^>]*>([^<]*)</string>").findAll(xmlText).map { it.groupValues[1] }.toList()
        assertTrue("双语文案键必须有内容", xmlValues.size >= 10)
        (literals(allNewCode) + xmlValues).forEach { text ->
            assertNull("出现禁词：$text", bannedIn(text))
        }
    }

    @Test
    fun `判据三_放行口唯一_引擎侧恰一处drain`() {
        val offerCallers = mainSources
            .filter { (_, src) -> stripComments(src).contains("SilentMailbox.offer(") }
            .map { it.first }
        assertEquals(
            "offer 只许在控制器点开分支（现在：$offerCallers）",
            listOf("ui/baopu/BaopuChatController.kt"),
            offerCallers,
        )
        val engine = stripComments(projectFile("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt").readText())
        assertEquals(
            "引擎里 SilentMailbox.drain() 恰一次（多一处就是第二放行口）",
            1,
            Regex(Regex.escape("SilentMailbox.drain()")).findAll(engine).count(),
        )
    }

    @Test
    fun `判据二_只浮一行字_不推卡不弹窗`() {
        val controller = stripComments(
            projectFile("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt").readText(),
        )
        assertFalse("静默回想不进建议页签行列表（无 silent: 行键）", controller.contains("silent:"))

        val screen = projectFile("src/main/java/com/example/zhengdao/ui/baopu/DecisionCenterScreen.kt").readText()
        val start = screen.indexOf("// 625：只有一行字").takeIf { it >= 0 } ?: error("页签里找不到 625 那一行字")
        val end = screen.indexOf("DecisionTabBody(", start).takeIf { it >= start } ?: error("找不到页签列表入口")
        val block = screen.substring(start, end)
        assertTrue("那一行字必须是可点的 Text", block.contains(".clickable"))
        assertFalse("625 不许弹窗", block.contains("AlertDialog"))
        assertFalse("625 不许造卡", block.contains("DecisionCenterRow"))
        assertFalse("625 不许走卡规格", controller.contains("silentCardSpec"))
    }

    @Test
    fun `判据六_开关闸排在publish之前_关时零行零账`() {
        val controller = stripComments(
            projectFile("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt").readText(),
        )
        val body = functionBody(controller, "fun refreshSilentRecall()") ?: error("找不到 refreshSilentRecall 函数体")
        val gate = body.indexOf("SilentRecallSwitch.get(appCtx)")
        val publish = body.indexOf("SilentLineStore.publish")
        val append = body.indexOf("SilentRecallLedger.append")
        assertTrue("找不到开关闸", gate >= 0)
        assertTrue("找不到 publish", publish >= 0)
        assertTrue("开关闸必须排在 publish 之前（关时连算都不算）", gate < publish)
        assertTrue("开关闸必须排在落账之前", gate < append)
    }

    @Test
    fun `判据一_候选id逐字取自入参_源码无硬编码账行号`() {
        assertTrue("项目账候选 id 必须取 item.id", allNewCode.contains("ledgerId = item.id"))
        assertTrue("L0 候选 id 必须取 record.id", allNewCode.contains("ledgerId = record.id"))
        assertFalse("新件不许出现硬编码账行号", allNewCode.contains("\"L2-"))
        assertTrue("选择器必须逐条验引用", allNewCode.contains("checkCandidateRefs(candidates, knownIds)"))
    }
}
