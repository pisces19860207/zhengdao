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
    private val registry: String by lazy { projectFile("src/main/java/com/example/zhengdao/baopu/agent/ToolRegistry.kt").readText() }
    private val risk: String by lazy { projectFile("src/main/java/com/example/zhengdao/baopu/RiskMatrix.kt").readText() }
    private val app: String by lazy { projectFile("src/main/java/com/example/zhengdao/ZhengdaoApp.kt").readText() }

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

    // ── ⑥ 模型入口：两件工具必须四张表全登记（组／表／默认开／风险档）──────────────

    /** 判别器：某个工具名在源码里"表登记行"是否齐全；缺哪条给哪条。 */
    private fun registrationGaps(src: String, name: String): List<String> {
        val body = stripComments(src)
        val gaps = ArrayList<String>()
        if (!body.contains("\"$name\" to ToolGroup.")) gaps += "GROUP_OF"
        if (!body.contains("Entry(\"$name\")")) gaps += "ENTRIES"
        if (!body.contains("\"$name\",")) gaps += "DEFAULT_ENABLED"
        if (!body.contains("\"$name\" to ToolRule(")) gaps += "RISK_TABLE"
        return gaps
    }

    @Test
    fun 判别器咬得住漏登记的工具体() {
        val fake = "val x = listOf(\"ghost\")"
        assertTrue("判别器必须能咬住未登记的名字", registrationGaps(fake, "ghost").isNotEmpty())
    }

    @Test
    fun 备份还原两件四张表全登记() {
        val all = registry + risk
        for (name in listOf("export_data", "import_data")) {
            val gaps = registrationGaps(all, name)
            assertTrue("$name 漏登记：$gaps", gaps.isEmpty())
        }
    }

    // ── ⑦ 启动自动发现：只发现、不自动合并 ───────────────────────────────────

    @Test
    fun 启动会扫导入目录且只提示不合并() {
        assertTrue("Application 起来必须扫一次导入目录", stripComments(app).contains("BackupWiring.install(this)"))
        val wiring = stripComments(projectFile("src/main/java/com/example/zhengdao/backup/BackupWiring.kt").readText())
        assertTrue("扫描入口是 BackupInbox.refresh", wiring.contains("BackupInbox.refresh("))
        assertFalse("自动发现**不许**自己合并：$wiring", wiring.contains("importNow("))
        assertFalse("自动发现**不许**自己落盘：$wiring", wiring.contains("BackupImporter.apply("))
    }

    // ── ⑧ 热加载必须真接线（不是只有端口没有实现）──────────────────────────────

    /** 派工单四②：冲突"列清单"要真的落到日志里（只写条数不算列清单）。 */
    @Test
    fun 冲突清单逐条落日志() {
        val body = stripComments(shell)
        assertTrue("必须有逐条写日志的出口", body.contains("logConflicts("))
        assertTrue("每条冲突要真的写进 RunLog", body.contains("RunLog.log(\"  冲突："))
    }

    @Test
    fun 导入后热加载真接在壳层里() {
        val body = stripComments(shell)
        assertTrue("导入完必须调热加载", body.contains("BackupHotReload.afterImport("))
        assertTrue("记忆索引走真 MemoryStore", body.contains("MemoryReloadPort("))
        assertTrue("检索索引走真 PhoneSearch 增量同步", body.contains("PhoneSearch.scan("))
        assertTrue("没有索引库时不许替用户建库（先判 dbExists）", body.contains("PhoneSearch.dbExists("))
    }
}
