// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审计第 2 期 **R-25**（第⑯刀 #2）的验收门禁：**存储页不得再有主线程盘 IO 的老写法**。
 *
 * 背景：`SettingsStorageActivity.kt` 原有三处裸 `Thread { … }.start()`（重列缓存/清理/回退），
 * 收尾再用 `android.os.Handler(Looper.getMainLooper()).post { … }` 把状态赋值与
 * **落盘**（LastValues.saveText）甩回主线程 —— 盘 IO 跑在主线程上。
 * 现在：文件既有的 `scope`（rememberCoroutineScope）+ `withContext(Dispatchers.IO)` 做盘 IO，
 * 回主线程只做状态赋值。
 *
 * 判据（R-24 口径：先剥注释，再结构断言）：
 * ① 剥注释后的代码里**不得再出现**裸 `Thread(` 与 `Handler(`（注释里提不算数）；
 * ② 正向锚点：`withContext(Dispatchers.IO)` 仍包住三处盘 IO（列缓存 / cleanupNonCurrent / 回退安装）。
 */
class SettingsStorageMainThreadIoGateTest {

    private val file = "src/main/java/com/example/zhengdao/ui/SettingsStorageActivity.kt"

    private fun code(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return stripComments(f.readText())
    }

    /** 只剥 `//` 与块注释（保留字符串字面量）：注释里提一句 Thread 不算数。 */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '"' && src.startsWith("\"\"\"", i) -> {
                    val end = src.indexOf("\"\"\"", i + 3).let { if (it < 0) src.length else it + 3 }
                    out.append(src, i, end)
                    i = end
                }
                c == '"' || c == '\'' -> {
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
    fun `R-25 存储页剥注释后不得再有裸 Thread 与 Handler`() {
        val s = code(file)
        val threads = Regex("""\bThread\s*\(""").findAll(s).count()
        val handlers = Regex("""\bHandler\s*\(""").findAll(s).count()
        assertTrue(
            "$file：剥注释后仍发现 $handlers 处 Handler( —— 状态回主线程应走 scope 协程，" +
                "不得再走 Handler(mainLooper).post（R-25 回潮）",
            handlers == 0,
        )
        assertTrue(
            "$file：剥注释后仍发现 $threads 处裸 Thread( —— 盘 IO 应走 scope + withContext(Dispatchers.IO)" +
                "（R-25 回潮）",
            threads == 0,
        )
    }

    @Test
    fun `R-25 三处盘 IO 仍在 withContext Dispatchers IO 里`() {
        val s = code(file)
        // 正向锚点：三处盘 IO 调用依然存在（锚点变了就更新本测试）
        listOf(
            "RootfsCache.listArchives(ctx)",
            "RootfsCache.cleanupNonCurrent(ctx)",
            "RootfsInstaller.install(ctx, target, rollEnv, rollSha)",
        ).forEach { anchor ->
            val idx = s.indexOf(anchor)
            assertTrue("$file 里找不到 `$anchor`（锚点变了就更新本测试）", idx >= 0)
        }
        // 三处都应被 withContext(Dispatchers.IO) 包住：调用点之前最近的 withContext( 必须是 IO 版
        listOf(
            "RootfsCache.listArchives(ctx)",
            "RootfsCache.cleanupNonCurrent(ctx)",
            "RootfsInstaller.install(ctx, target, rollEnv, rollSha)",
        ).forEach { anchor ->
            val idx = s.indexOf(anchor)
            val ctxCall = s.lastIndexOf("withContext(Dispatchers.IO)", idx)
            assertTrue(
                "$file：`$anchor` 之前必须有一个 withContext(Dispatchers.IO)（盘 IO 不得回主线程）",
                ctxCall in 0 until idx,
            )
        }
    }
}
