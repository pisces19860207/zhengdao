// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百九十一批 · **接线与红线门禁**（判据「导出里没有 key」「默认不含聊天原文」）。
//
// 为什么这两条要有源码级门禁：它们都能在真机上被"顺手改一行默认值"破坏，而破坏之后
// 不会编译失败、不会单测红——只会某一天用户的 Key 随备份一起被上传到网盘。
// 每个判别器都先喂一段**故意违规**的样本自证咬得住，再对真实源码断言干净（防恒真绿灯）。
package com.example.zhengdao.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupSourceGateTest {

    private fun projectFile(rel: String): File {
        val cwd = File(".").canonicalFile
        return listOf(File(cwd, rel), File(cwd.parentFile, rel), File(cwd, "app/$rel"))
            .firstOrNull { it.isFile } ?: error("找不到 $rel（cwd=${cwd.absolutePath}）")
    }

    private val shell: String by lazy { projectFile("src/main/java/com/example/zhengdao/backup/BackupShell.kt").readText() }
    private val section: String by lazy { projectFile("src/main/java/com/example/zhengdao/ui/BackupSection.kt").readText() }
    private val settings: String by lazy { projectFile("src/main/java/com/example/zhengdao/ui/SettingsScreen.kt").readText() }

    /** 只删整行注释与块注释（字符串里的英文单词要保留）。 */
    private fun stripComments(src: String): String = src
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .lines()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")

    // ── ① 密钥来源：壳层只许读 AppPrefs 那一个文件 ───────────────────────────

    /** 判别器：源码里出现 provider 那个 SP 文件名（Key 的家）就返回命中的那一行。 */
    private fun providerLeak(src: String): String? =
        stripComments(src).lines().firstOrNull { it.contains("zhengdao-baopu-provider") }

    @Test
    fun 判别器咬得住密钥来源() {
        assertNotNull("判别器必须能咬住违规样本", providerLeak("val p = ctx.getSharedPreferences(\"zhengdao-baopu-provider\", 0)"))
        assertNull("正常源码不该命中", providerLeak(shell))
    }

    @Test
    fun 壳层只读白名单里的设置文件() {
        val body = stripComments(shell)
        assertTrue("必须经白名单读设置", body.contains("SETTING_FILES"))
        assertTrue("白名单里必须有 AppPrefs.FILE_NAME", body.contains("AppPrefs.FILE_NAME"))
    }

    /**
     * 外观那个文件名在壳层是**字面量**（那边 `SP` 是 private），所以这里去源头读出来比对：
     * 谁改了 `AppearancePrefs.SP` 而没改壳层，这条就红——两个真源硬绑在一处。
     */
    @Test
    fun 外观设置文件名与源头一致() {
        val src = projectFile("src/main/java/com/example/zhengdao/ui/theme/AppearancePrefs.kt").readText()
        val name = Regex("const val SP = \"([a-z0-9-]+)\"").find(src)?.groupValues?.get(1)
        assertNotNull("AppearancePrefs 里必须还能找到 SP 常量", name)
        assertTrue("壳层白名单里必须有 $name", stripComments(shell).contains("\"$name\""))
    }

    // ── ② 默认不含聊天原文 ──────────────────────────────────────────────────

    /** 判别器：找到 includeChat 的初值声明，非 false 就咬。 */
    private fun chatDefaultWrong(src: String): String? {
        val line = stripComments(src).lines().firstOrNull { it.contains("includeChat by remember") }
        return if (line != null && !line.contains("mutableStateOf(false)")) line else null
    }

    @Test
    fun 判别器咬得住默认值被改成true() {
        assertNotNull(
            "判别器必须能咬住 true",
            chatDefaultWrong("var includeChat by remember { mutableStateOf(true) }"),
        )
        assertNull("真实界面必须是默认关", chatDefaultWrong(section))
    }

    // ── ③ 入口接上了 ────────────────────────────────────────────────────────

    @Test
    fun 设置页挂着备份这一块() {
        assertTrue("设置页必须调用 BackupSection()", stripComments(settings).contains("BackupSection()"))
    }

    // ── ④ 中英文案对称且都有人用 ─────────────────────────────────────────────

    private fun names(xml: String): Set<String> =
        Regex("<string name=\"([a-zA-Z0-9_]+)\">").findAll(xml).map { it.groupValues[1] }.toSet()

    @Test
    fun 备份文案中英对称且每条都有人用() {
        val zh = names(projectFile("src/main/res/values/strings_backup.xml").readText())
        val en = names(projectFile("src/main/res/values-en/strings_backup.xml").readText())
        assertTrue("中文文案不能为空", zh.isNotEmpty())
        assertEquals("中英必须一一对应：差 ${zh - en} / ${en - zh}", zh, en)
        val used = stripComments(section)
        val dead = zh.filterNot { used.contains("R.string.$it") }
        assertTrue("这些文案写了没人用：$dead", dead.isEmpty())
    }

    // ── ⑤ 壳层的两条红线写在源码里（改口径的人看得到）──────────────────────

    @Test
    fun 壳层不碰聊天库除非勾了() {
        val body = stripComments(shell)
        val guardAt = body.indexOf("if (includeChat)")
        val chatAt = body.indexOf("InMemoryFile(\"chat/")
        assertTrue("必须存在 includeChat 分支", guardAt >= 0)
        assertTrue("chat 条目必须写在 includeChat 分支里（位置 $chatAt vs $guardAt）", chatAt > guardAt)
    }
}
