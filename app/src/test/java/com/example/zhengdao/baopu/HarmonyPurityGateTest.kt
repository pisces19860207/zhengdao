// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十八批 · 纯函数门禁：三个组件必须**零 Android / 零 IO / 零网络 / 零时钟**。
//
// 调和器会被两端（人侧与模型侧）同时依赖，一旦它摸盘、读网或自己取时间，
// 单测就不可能复现「静置三十分钟降一档」这类判据，画像也可能被上传。
// 所以这里直接扫源码：允许 `kotlin.math.pow` 这种纯计算，禁止一切 IO / 平台句柄。
//
// 扫之前先证明门禁本身会咬人（合成违规行必须被抓出来），免得「全绿」只是没扫到。
package com.example.zhengdao.baopu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarmonyPurityGateTest {

    private val dir = File("src/main/java/com/example/zhengdao/baopu")

    private val files = listOf(
        "HarmonyMediator.kt" to "internal object HarmonyMediator",
        "ReframeFilter.kt" to "internal object ReframeFilter",
        "PatienceProfile.kt" to "internal data class PatienceProfile",
    )

    private val forbidden = listOf(
        "android.", "androidx.",
        "java.io.", "java.nio.", "java.net.",
        "System.currentTimeMillis", "System.nanoTime", "Clock.",
        "File(", "Files.", "Paths.", "RandomAccessFile",
        "SharedPreferences", "Preferences", "openFileOutput", "contentResolver",
        "okhttp", "HttpURLConnection", "URL(", "Socket",
        "kotlinx.coroutines", "Thread(", "Handler", "Looper", "Context",
        "UUID", "Math.random",
        "BaopuChatController", "BaopuPreset", "ui.baopu", "agent.",
    )

    /** 只扫代码行：整行注释与行尾注释都剔掉，注释里说「零 IO」不算依赖。 */
    private fun scan(src: String): List<String> = src.lineSequence()
        .map { it.trimStart() }
        .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("*/") }
        .map { it.substringBefore("//") }
        .filter { line -> forbidden.any { line.contains(it) } }
        .toList()

    @Test
    fun `门禁_合成违规行会被抓出来`() {
        listOf(
            "import java.io.File",
            "val now = System.currentTimeMillis()",
            "val f = File(\"/tmp/x\")",
            "val p = context.getSharedPreferences(\"x\", 0)",
            "import kotlinx.coroutines.runBlocking",
            "import android.util.Log",
        ).forEach { line ->
            assertTrue("没抓住违规：$line", scan(line).isNotEmpty())
        }
        listOf(
            "// import java.io.File",
            " * 零 Android / 零 IO / 零网络",
            "val p = kotlin.math.pow(2.0, 3.0)",
        ).forEach { line ->
            assertTrue("误伤良性行：$line", scan(line).isEmpty())
        }
    }

    @Test
    fun `三个组件文件都在_且声明形制正确`() {
        files.forEach { (name, declaration) ->
            val f = File(dir, name)
            assertTrue("文件不在盘上：${f.absolutePath}", f.isFile)
            assertTrue("文件里找不到声明：$declaration", f.readText().contains(declaration))
        }
    }

    @Test
    fun `三个组件零IO零平台句柄零挂接依赖`() {
        files.forEach { (name, _) ->
            val hits = scan(File(dir, name).readText())
            assertEquals("${name} 里出现违规依赖：$hits", emptyList<String>(), hits)
        }
    }

    @Test
    fun `调和器自己不看时间_时间由调用方传入`() {
        val source = File(dir, "HarmonyMediator.kt").readText()
        assertTrue("observe 必须接收 now 参数", source.contains("now: Long"))
        assertTrue("衰减窗口必须是常量，不许现取时钟", source.contains("DECAY_WINDOW_MILLIS"))
        assertTrue(
            "不许出现无参数取时间",
            !source.contains("currentTimeMillis") && !source.contains("nanoTime"),
        )
    }

    @Test
    fun `调和器没有权限与事实标准入口`() {
        val source = File(dir, "HarmonyMediator.kt").readText()
        listOf("permission", "Permission", "allow", "deny", "confirmed", "confirm(").forEach { token ->
            assertTrue("调和器不该有权限入口：$token", !source.contains(token))
        }
    }
}
