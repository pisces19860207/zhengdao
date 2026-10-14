// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审计第 2 期 **B-3** 的验收门禁：**下载/安装进行中，「清理旧版本安装包」这条路必须禁用**。
 *
 * 为什么必须禁：这个按钮最终会调 `RootfsCache.cleanupNonCurrent`，它会把正在下载的 `.part`
 * 残片删掉（越权的"清理"还会删非当前版本的包）。而此刻 `RootfsDownloader` 正拿着同一个
 * `.part` 在写 —— 删掉之后收尾的 `renameTo` / `copyTo` 必然失败，整条传输白跑。
 *
 * 判据分两层（R-24 口径：把"纯文本断言"换成真判据）：
 *  ① **接线**：`SettingsStorageActivity.kt` 的两处按钮（触发按钮 ＋ 确认框的确认按钮）必须在
 *     **代码里**（非注释）写上 `enabled = !InstallFlow.isRunning()`；
 *  ② **状态**：`InstallFlow.isRunning()` 背后的 `InstallProgress` 忙/闲语义必须真的成立 ——
 *     ①只证明"线接上了"，②证明"线本身有电"。合起来才是"下载中打开确认框 ⇒ 确认按钮 enabled=false"。
 *
 * 为什么不上 Compose UI 测试：项目当前 Compose UI 测试为 0（P4-5），加 `ui-test-junit4` 依赖
 * ＋定规则不该塞进本刀（与 `StateLifetimeGateTest` / `SettingsDestructiveConfirmGateTest` 同一判断）。
 * ① 之所以先剥注释再判，就是为了堵住旧写法"把 `enabled` 那行注释掉照样绿"的洞。
 */
class InstallerCleanBusyGateTest {

    private val file = "src/main/java/com/example/zhengdao/ui/SettingsStorageActivity.kt"
    private val guard = "enabled = !InstallFlow.isRunning()"

    private fun code(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return stripComments(f.readText())
    }

    /** 只剥 `//` 与 `/* */`（保留字符串字面量）：注释里写一句 `enabled = …` 不算数。 */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '"' && src.startsWith("\"\"\"", i) -> { // 原始字符串 """..."""
                    val end = src.indexOf("\"\"\"", i + 3).let { if (it < 0) src.length else it + 3 }
                    out.append(src, i, end)
                    i = end
                }
                c == '"' || c == '\'' -> { // 普通字符串 / 字符字面量（保留）
                    val quote = c
                    out.append(c)
                    i++
                    while (i < src.length) {
                        val d = src[i]
                        out.append(d)
                        i++
                        if (d == '\\' && i < src.length) { out.append(src[i]); i++; continue }
                        if (d == quote) break
                    }
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(src.length)
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    @Test
    fun `B-3 触发按钮在安装进行中必须禁用`() {
        val s = code(file)
        val trigger = s.indexOf("installerCleanConfirm = true")
        assertTrue("找不到清理按钮的触发点（锚点变了就更新本测试）", trigger >= 0)
        val btnStart = s.lastIndexOf("OutlinedButton(", trigger)
        assertTrue("触发点之前必须有一个 OutlinedButton(", btnStart >= 0)
        val head = s.substring(btnStart, trigger)
        assertTrue(
            "打开确认框的那个按钮必须写 $guard（下载中点它会把正在写的 .part 删掉）；实际参数：\n$head",
            head.contains(guard),
        )
    }

    @Test
    fun `B-3 确认框的确认按钮在安装进行中必须禁用`() {
        val s = code(file)
        val dialog = s.indexOf("if (installerCleanConfirm) {")
        assertTrue("找不到清理确认框（锚点变了就更新本测试）", dialog >= 0)
        val confirm = s.indexOf("confirmButton = {", dialog)
        assertTrue("确认框里必须有一个 confirmButton 分支", confirm > dialog)
        val tb = s.indexOf("TextButton(", confirm)
        assertTrue("confirmButton 分支里必须有一个 TextButton", tb > confirm)
        val args = s.substring(tb, s.indexOf("onClick = {", tb))
        assertTrue(
            "确认框的确认按钮（执行 cleanupNonCurrent 那个）必须写 $guard；实际参数：\n$args",
            args.contains(guard),
        )
    }

    @Test
    fun `B-3 isRunning 的忙闲语义成立`() {
        InstallProgress.clear()
        assertFalse("空闲时不该是忙", InstallProgress.isRunning())
        InstallProgress.start("下载中")
        // ① 的判据 `!InstallFlow.isRunning()` 依赖这根线真的有电：
        // InstallFlow.isRunning() = InstallProgress.isRunning() || RootfsInstaller.isInstalling()
        assertTrue(
            "InstallProgress 一忙（下载/安装进行中），!InstallFlow.isRunning() 就必须为假 ⇒ 按钮 disabled",
            InstallFlow.isRunning(),
        )
        InstallProgress.clear()
        assertFalse("清掉之后回到空闲", InstallProgress.isRunning())
    }
}
