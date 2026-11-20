// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.ui.adaptive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 746 接线门禁（防"判据在测试里、生产没接"的两张皮）。
 * 沿用 StateLifetimeGateTest 的剥注释文本门禁：文件里去掉注释后仍出现真实接线才算数。
 */
class AdaptiveWiringGateTest {
    private fun code(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel", f.isFile)
        return stripComments(f.readText())
    }

    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '"' && src.startsWith("\"\"\"", i) -> {
                    val end = src.indexOf("\"\"\"", i + 3).let { if (it < 0) src.length else it + 3 }
                    out.append(src, i, end); i = end
                }
                c == '"' || c == '\'' -> {
                    val q = c; out.append(c); i++
                    while (i < src.length) {
                        val d = src[i]; out.append(d); i++
                        if (d == '\\' && i < src.length) { out.append(src[i]); i++; continue }
                        if (d == q) break
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
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    @Test
    fun `home路由必须挂自适应壳`() {
        val s = code("src/main/java/com/example/zhengdao/MainActivity.kt")
        assertTrue("home 路由必须经 AdaptiveShell", "AdaptiveShell" in s)
        assertTrue("必须读取档位供布局使用", "LocalLayoutConfig.current" in s)
        assertTrue("expanded 宽度约束必须接线", "widthIn(max = editorMax)" in s)
    }

    @Test
    fun `折叠适配层是唯一依赖androidx_window的文件`() {
        val main = code("src/main/java/com/example/zhengdao/MainActivity.kt")
        assertFalse("MainActivity 不该直接依赖 androidx.window（全走适配层）", "androidx.window" in main)
        val adapter = code("src/main/java/com/example/zhengdao/ui/adaptive/FoldStateAdapter.kt")
        assertTrue("适配层必须真用 WindowInfoTracker", "WindowInfoTracker" in adapter)
        assertTrue("适配层必须真用 FoldingFeature", "FoldingFeature" in adapter)
    }

    @Test
    fun `SAF打开保存必须走ActivityResult_API`() {
        // 判据⑤静态部分：Activity Result API 绑定 SavedStateRegistry，配置变更/分屏不崩。
        val backup = code("src/main/java/com/example/zhengdao/ui/BackupSection.kt")
        assertTrue("导入必须走 rememberLauncherForActivityResult(OpenDocument)",
            "rememberLauncherForActivityResult" in backup && "OpenDocument" in backup)
        val help = code("src/main/java/com/example/zhengdao/ui/SettingsHelpActivity.kt")
        assertTrue("导出必须走 rememberLauncherForActivityResult(CreateDocument)",
            "rememberLauncherForActivityResult" in help && "CreateDocument" in help)
        assertTrue("导出必须真写 openOutputStream", "openOutputStream" in help)
    }

    @Test
    fun `主界面不声明configChanges_靠rememberSaveable恢复`() {
        // 判据②④的机理前提：MainActivity 重建 ⇒ rememberSaveable 从 Bundle 恢复。
        // 若有人给 MainActivity 加了 orientation|screenSize，状态就改走"不重建"路径，
        // 与本单的夹具口径不符 ⇒ 门禁拦下。
        val manifest = code("src/main/AndroidManifest.xml")
        val mainBlock = manifest.substringAfter("android:name=\".MainActivity\"", "")
            .substringBefore("<activity", "")
            .substringBefore("</application", "")
        assertFalse("MainActivity 不得声明 configChanges（否则状态口径与本单夹具不符）",
            "configChanges" in mainBlock)
    }
}
