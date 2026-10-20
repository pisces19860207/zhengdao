// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第六十八批 §2 的源码门禁：体检「修复」不再用裸 `Thread`，改走现有 scope + IO，
 * 并且该行有「修复中…」状态、禁止连点。
 *
 * 判据（先剥注释再断言）：
 * ① 全文件不得再出现 `Thread(` / `Thread {`；
 * ② `EnvHealth.fixDetailed` 与复检 `EnvHealth.inspect` 都被 `withContext(Dispatchers.IO)` 包住；
 * ③ 进行中状态用 `fixingCheckId`：按钮 `enabled = fixingCheckId == null`、该行显示
 *    `home_fixing`、`finally` 里恢复；
 * ④ 线程体里的语义原样保留：try/catch、复检、`IssueCenter.report/resolve`、
 *    两条路径各一次 `healthEpoch++`；取消单独 rethrow，不冒充修复失败。
 *
 * 负例：把 `reloadScope.launch` 改回 `Thread {` ⇒ ① 红。
 */
class HealthFixCoroutineGateTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun src(rel: String): String {
        val f = repoFile(rel)
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

    /** 只剥 `//` 与块注释（保留字符串字面量、保留换行以稳住定位逻辑）。 */
    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it + 3 }
                    out.append(text, i, end)
                    i = end
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    out.append(c)
                    i++
                    while (i < text.length) {
                        val d = text[i]
                        out.append(d)
                        i++
                        if (d == '\\' && i < text.length) { out.append(text[i]); i++; continue }
                        if (d == quote) break
                    }
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(text.length)
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun home(): String = stripComments(src("src/main/java/com/example/zhengdao/ui/HomeScreen.kt"))

    /** 只截 `c.fixId != null` 这一条修复路径，避免把文件里别处的协程/IO 算进来。 */
    private fun fixBlock(): String {
        val s = home()
        val start = s.indexOf("c.fixId != null -> TextButton(")
        val end = s.indexOf("tcmd != null -> TextButton(", start)
        assertTrue("找不到体检修复分支（c.fixId != null）", start >= 0)
        assertTrue("找不到修复分支的结束边界（后面应是 tcmd 分支）", end > start)
        return s.substring(start, end)
    }

    private fun assertOnIo(block: String, anchor: String) {
        val at = block.indexOf(anchor)
        assertTrue("找不到 IO 锚点 `$anchor`", at >= 0)
        val ctx = block.lastIndexOf("withContext(", at)
        assertTrue("`$anchor` 之前必须有 withContext(", ctx >= 0)
        val wrapper = block.substring(ctx, at)
        assertTrue(
            "`$anchor` 必须跑在 Dispatchers.IO 上（不能回主线程做盘 IO）",
            wrapper.contains("Dispatchers.IO"),
        )
    }

    @Test
    fun `全文件不得再有裸Thread`() {
        val threads = Regex("""\bThread\s*[({]""").findAll(home()).count()
        assertEquals(
            "HomeScreen 剥注释后仍有 $threads 处裸 Thread —— 体检修复必须走 scope + Dispatchers.IO",
            0,
            threads,
        )
    }

    @Test
    fun `修复与复检都在协程IO里`() {
        val b = fixBlock()
        assertTrue("修复必须挂在现有 reloadScope 上（与回前台刷新同一个入口）", b.contains("reloadScope.launch {"))
        assertOnIo(b, "EnvHealth.fixDetailed")
        assertOnIo(b, "EnvHealth.inspect")
    }

    @Test
    fun `进行中状态_禁用连点与修复中文案`() {
        val s = home()
        val b = fixBlock()
        assertTrue(
            "必须有进行中状态（null = 空闲）",
            s.contains("var fixingCheckId by remember { mutableStateOf<String?>(null) }"),
        )
        assertTrue("按钮在修复进行中必须禁用", b.contains("enabled = fixingCheckId == null"))
        assertTrue("点击时进入修复中", b.contains("fixingCheckId = c.id"))
        assertTrue("finally 里必须恢复空闲", b.contains("finally {") && b.contains("fixingCheckId = null"))
        assertTrue("该行按钮要显示「修复中…」", b.contains("R.string.home_fixing"))
        assertTrue("防连点的兜底判断保留", b.contains("if (fixingCheckId != null) return@TextButton"))
    }

    @Test
    fun `线程体语义原样保留`() {
        val b = fixBlock()
        assertTrue("catch Throwable 的兜底必须保留", b.contains("catch (t: Throwable)"))
        assertTrue("协程取消必须单独 rethrow（不能冒充修复失败）", b.contains("catch (ce: CancellationException)"))
        assertTrue("失败仍要上报 IssueCenter", b.contains("IssueCenter.report("))
        assertTrue("修复成功仍要 resolve 旧问题", b.contains("IssueCenter.resolve("))
        assertEquals(
            "正常完成与失败两条路径各要 healthEpoch++ 一次（触发复检）",
            2,
            Regex(Regex.escape("healthEpoch++")).findAll(b).count(),
        )
        assertTrue("复检仍按当前项 id 找回结果", b.contains(".firstOrNull { it.id == c.id }"))
        assertFalse("回主线程不再走 Handler（协程作用域本来就在主线程）", b.contains("android.os.Handler"))
    }

    @Test
    fun `修复文案中英都在且非空`() {
        val zh = repoFile("src/main/res/values/strings.xml").readText()
        val en = repoFile("src/main/res/values-en/strings.xml").readText()
        val marker = "name=\"home_fixing\">"
        assertTrue("中文缺 home_fixing", zh.contains(marker))
        assertTrue("英文缺 home_fixing", en.contains(marker))
        assertFalse("中文 home_fixing 是空串", zh.contains(marker + "</string>"))
        assertFalse("英文 home_fixing 是空串", en.contains(marker + "</string>"))
    }
}
