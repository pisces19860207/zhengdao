package com.example.zhengdao.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-补-11：运行日志弹窗**必须能滚动**。
 *
 * 审计原文：「运行日志弹窗无滚动（`SettingsLogDialog` 的 `Text` 上无 `verticalScroll`）」。
 * 症结：日志最多 6000 字（`takeLast(6000)`），AlertDialog 的可用高度装不下，
 * 而 `Text` 不滚动 ⇒ 用户只能看到**开头一段**、下面既看不到也滑不动。
 *
 * 为什么用源码级门禁而不是 Compose UI 测试：本仓 `app/src/androidTest` 从来是
 * 插桩用例（真机/模拟器才跑），而 Compose UI 测试在单测 lane 里跑不起来；
 * 这条缺陷的形态极稳定（"弹窗正文有没有挂滚动修饰符"），源码级断言足够挡住回归，
 * 也与 `PullToRefreshGuardTest` / `LocalLifecycleOwnerGuardTest` 同一套做法。
 */
class SettingsLogDialogScrollGuardTest {

    private val source: String =
        File("src/main/java/com/example/zhengdao/ui/SettingsCommon.kt").readText()

    /** 取 `SettingsLogDialog` 的函数体（到下一个顶层 fun 声明为止）。 */
    private fun dialogBody(): String {
        val start = source.indexOf("internal fun SettingsLogDialog(")
        assertTrue("SettingsLogDialog 必须在 SettingsCommon.kt 里（逐字搬自设置页原实现）", start >= 0)
        val rest = source.substring(start)
        val cut = listOf("\ninternal fun ", "\nprivate fun ", "\n@Composable\ninternal fun")
            .map { rest.indexOf(it, 1) }
            .filter { it > 0 }
            .minOrNull()
        return if (cut == null) rest else rest.substring(0, cut)
    }

    @Test
    fun `门禁_日志弹窗正文挂了 verticalScroll 且不再裸 Text`() {
        val body = dialogBody()
        assertTrue(
            "弹窗正文的 Text 必须挂 Modifier.verticalScroll(rememberScrollState())",
            body.contains("verticalScroll(") && body.contains("rememberScrollState()"),
        )
        assertFalse(
            "不得退回「裸 Text(text.takeLast(6000), …)」的无滚动形态（B-补-11 的本体）",
            body.contains("text = { Text(text.takeLast(6000), style = MaterialTheme.typography.bodySmall) }"),
        )
    }

    @Test
    fun `门禁_三处二级页仍共用同一个弹窗（复制全部与 takeLast 都在）`() {
        val body = dialogBody()
        assertTrue("「复制全部」入口必须还在", body.contains("common_copy_all"))
        assertTrue("截断上限 6000 字必须还在（改的是可滚动，不是放宽截断）", body.contains("takeLast(6000)"))
        assertTrue(
            "三个二级页（环境 / 帮助 / 存储与缓存）共用它：调用点必须 ≥ 3 处",
            countCallSites() >= 3,
        )
    }

    /** 调用点在**别的文件**里（SettingsEnvActivity / SettingsHelpActivity / SettingsStorageActivity）。 */
    private fun countCallSites(): Int {
        val uiDir = File("src/main/java/com/example/zhengdao/ui")
        val call = Regex("SettingsLogDialog\\(")
        return uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "SettingsCommon.kt" }
            .sumOf { call.findAll(it.readText()).count() }
    }
}
