// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.library

import com.example.zhengdao.ui.baopu.BaopuIntents
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 静态快捷方式门禁 —— **134 批起改为退役守卫**。
 *
 * 沿革：刀 2 D 段第 1 条曾加过 `res/xml/shortcuts.xml`（静态快捷方式），但 manifest 从没挂
 * `android.app.shortcuts` 的 meta-data（git 全历史可查：该属性从未出现），所以它**从未生效**。
 * 129 批把它记成「待用户裁决」；134 批 lint 清零按「UnusedResources 确认全仓零引用后删除」
 * 退役了 XML 与两条标签文案。
 *
 * 本门禁保留（不删测试类：套件数只许涨不许跌），改钉四件仍然成立的事：
 * 1. 死资源不许复活（`res/xml/shortcuts.xml` 与 manifest meta-data 都必须在）；
 * 2. 外部 Intent 入口（`BaopuIntents.ACTION_BACKUP`）与 MainActivity 的路由仍共认一个字面值；
 * 3. 不为这条 action 增加 manifest intent-filter（权限段零改动的旧口径）；
 * 4. 两条标签文案已随资源退役，两语都不许残留死键。
 */
class ShortcutsXmlTest {

    private fun appFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private val manifest: String
        get() = appFile("src/main/AndroidManifest.xml").readText()

    @Test
    fun `静态快捷方式XML已退役不许复活`() {
        val f = appFile("src/main/res/xml/shortcuts.xml")
        assertFalse("134 批已删（从未挂接的静态快捷方式资源）；复活需先立账", f.exists())
    }

    @Test
    fun `Manifest不引用shortcuts资源`() {
        assertFalse(
            "manifest 不许再出现 android.app.shortcuts / @xml/shortcuts（死资源已退役 134）",
            manifest.contains("android.app.shortcuts") || manifest.contains("@xml/shortcuts"),
        )
    }

    @Test
    fun `ACTION_BACKUP仍由MainActivity消费`() {
        val main = appFile("src/main/java/com/example/zhengdao/MainActivity.kt").readText()
        assertTrue(
            "MainActivity 仍要认 BaopuIntents.ACTION_BACKUP（外部 Intent 入口不许随快捷方式一起退役）",
            main.contains("intent?.action == com.example.zhengdao.ui.baopu.BaopuIntents.ACTION_BACKUP"),
        )
        assertTrue(
            "命中 action 后仍要投递到抱朴备份请求",
            main.contains("BaopuIntents.requestBackup()"),
        )
    }

    @Test
    fun `ACTION_BACKUP字面值未漂移`() {
        val screen = appFile("src/main/java/com/example/zhengdao/ui/baopu/BaopuBackupScreen.kt").readText()
        assertEquals(
            "ACTION_BACKUP 的字面值必须仍是 com.example.zhengdao.action.BAOPU_BACKUP",
            "com.example.zhengdao.action.BAOPU_BACKUP",
            BaopuIntents.ACTION_BACKUP,
        )
        assertTrue(
            "BaopuIntents.ACTION_BACKUP 仍声明在同一处（源码字面值可逐字核对）",
            screen.contains("const val ACTION_BACKUP = \"com.example.zhengdao.action.BAOPU_BACKUP\""),
        )
    }

    @Test
    fun `不为备份新增intent-filter`() {
        // 显式 intent 不需要过滤器 ⇒ Manifest 不许为它加 intent-filter（权限段零改动）
        assertFalse(
            "不许往 Manifest 里加 BAOPU_BACKUP 的 intent-filter",
            manifest.contains(BaopuIntents.ACTION_BACKUP),
        )
    }

    @Test
    fun `两条快捷方式标签文案已随资源退役`() {
        val zh = appFile("src/main/res/values/strings.xml").readText()
        val en = appFile("src/main/res/values-en/strings.xml").readText()
        listOf("baopu_shortcut_backup", "baopu_shortcut_backup_long").forEach { key ->
            assertFalse("中文文案表不许再留死键 $key", zh.contains("name=\"$key\""))
            assertFalse("英文文案表不许再留死键 $key", en.contains("name=\"$key\""))
        }
    }

    @Test
    fun `外部Intent入口在两条生命周期路径都接线`() {
        val main = appFile("src/main/java/com/example/zhengdao/MainActivity.kt").readText()
        // onCreate 与 onNewIntent 都要经过同一个分发口（134 批退役的是静态快捷方式资源，
        // 不是这条外部 Intent 能力）。至少两处调用：onCreate + onNewIntent。
        val calls = Regex("handleBaopuShortcut\\(").findAll(main).count()
        assertTrue("handleBaopuShortcut 至少要在 onCreate 与 onNewIntent 两处调用，实际 $calls", calls >= 2)
    }
}
