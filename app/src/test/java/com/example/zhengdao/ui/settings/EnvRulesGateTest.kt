// 第八十批 · §B 80（2026-10-20）：设置页纯逻辑抽取的**源码门禁**。
//
// 纯函数单测只能证明"判据对"，证明不了"页面真的只调这一份"。
// 这里钉三件事：
//   ① 页面里那段旧实现必须**真的删干净**（不能一边留旧 when、一边加新调用）；
//   ② 页面只留调用，且调用次数与预期一致（漏接一处就红）；
//   ③ helper 自己必须是纯的（不 import android / androidx / java.io，不碰 Context / File）。
package com.example.zhengdao.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EnvRulesGateTest {

    private val page = File(
        "src/main/java/com/example/zhengdao/ui/SettingsEnvActivity.kt"
    )
    private val rules = File(
        "src/main/java/com/example/zhengdao/ui/settings/EnvRules.kt"
    )

    private fun text(f: File): String {
        assertTrue("找不到文件：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    private fun count(haystack: String, needle: String): Int {
        var n = 0
        var i = haystack.indexOf(needle)
        while (i >= 0) {
            n++
            i = haystack.indexOf(needle, i + needle.length)
        }
        return n
    }

    // ── ① 旧实现必须删干净 ───────────────────────────────────────────────

    @Test
    fun `门禁_页面里不留旧的三分支when树`() {
        val s = text(page)
        // 旧实现的分支标签；只要还留着,就说明页面上有两份判据
        assertFalse("页面里还有 `localEnv == null ->`，说明旧 when 树没删干净", s.contains("localEnv == null ->"))
        assertFalse("页面里还有 `localEnv.equals(idx.env` 旧判据", s.contains("localEnv.equals(idx.env"))
    }

    @Test
    fun `门禁_页面里不留进度节流与修复包门槛的旧算式`() {
        val s = text(page)
        assertFalse("页面里还有 `% 10 == 0L` 的进度节流", s.contains("% 10 == 0L"))
        assertFalse("页面里还有 100_000_000L 的修复包门槛", s.contains("100_000_000L"))
        assertFalse("页面里还有 `(done * 100 / total).toInt()`", s.contains("(done * 100 / total).toInt()"))
    }

    @Test
    fun `门禁_页面里不留旧的图标与颜色映射`() {
        val s = text(page)
        assertFalse("页面里还有旧的三元映射 `val (icon, color) = when`", s.contains("val (icon, color) = when"))
        assertFalse("页面里还在拼 `\"\$icon \"`", s.contains("\"\$icon \""))
    }

    @Test
    fun `门禁_页面里只剩一处正则_releases的tag`() {
        val s = text(page)
        // 迁移前页面里有四条正则：老 manifest 的 version / url / sha256，加上 Releases 的 tag。
        // 前三条现在归 EnvRules.parseLegacyManifest 管，页面只该剩 tag 那一条。
        assertEquals("页面里的正则只该剩 Releases 的 tag_name 那一条", 1, s.split("Regex(").size - 1)
    }

    // ── ② 只留调用，且一处不漏 ───────────────────────────────────────────

    @Test
    fun `门禁_五个片段都接上了`() {
        val s = text(page)
        assertEquals("更新判定只该在一处调用", 1, count(s, "EnvRules.planUpdate("))
        assertEquals("版本号展示只该在一处调用", 1, count(s, "EnvRules.displayVersion("))
        assertEquals("是否最新只该在一处判（弹不弹框的唯一来源）", 1, count(s, "EnvRules.isUpToDate("))
        assertEquals("老 feed 解析只该在一处调用", 1, count(s, "EnvRules.parseLegacyManifest("))
        assertEquals("安装状态展示只该在一处调用", 1, count(s, "EnvRules.phaseView("))
        assertEquals("修复包判定只该在一处调用", 1, count(s, "EnvRules.isRepairCandidate("))
        // 全量/增量两条下载各一处
        assertEquals("两条下载路径都该走同一条节流判据", 2, count(s, "EnvRules.progressPercent("))
    }

    @Test
    fun `门禁_页面导入了helper`() {
        assertTrue(
            "页面必须 import EnvRules",
            text(page).contains("import com.example.zhengdao.ui.settings.EnvRules"),
        )
    }

    @Test
    fun `门禁_判据挂在读盘之前_与迁移前同序`() {
        val s = text(page)
        val isUpToDate = s.indexOf("!EnvRules.isUpToDate(")
        val planUpdate = s.indexOf("val plan = EnvRules.planUpdate(")
        assertTrue("两处都该在", isUpToDate > 0 && planUpdate > 0)
        assertTrue(
            "isUpToDate（挂 pending 的判据）必须排在 planUpdate（会读盘）之前，" +
                "否则判定里读盘抛异常时就不再挂 pending 了",
            isUpToDate < planUpdate,
        )
    }

    // ── ③ helper 自己是纯的 ─────────────────────────────────────────────

    /** 去掉注释行之后的真代码：门禁要判的是代码，不是 KDoc 里的散文。 */
    private fun codeOnly(src: String): String = src.lines().filterNot { line ->
        val t = line.trimStart()
        t.startsWith("//") || t.startsWith("*") || t.startsWith("/**") || t.startsWith("*/")
    }.joinToString("\n")

    @Test
    fun `门禁_helper不碰Android上下文与文件系统`() {
        val s = codeOnly(text(rules))
        for (bad in listOf(
            "import android.",
            "import androidx.compose",
            "import java.io",
            "import java.net",
            "Context",
            "File(",
            "stringResource",
            "getString",
        )) {
            assertFalse("EnvRules 必须保持纯函数，却出现了 `$bad`", s.contains(bad))
        }
    }
}
