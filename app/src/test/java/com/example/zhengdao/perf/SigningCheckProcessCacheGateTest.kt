// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百一十八批 §2 **T2** 的钉桩：签名自检走进程级缓存。
//
// ## 为什么钉源码
// `signerSha256()` 是一次 `PackageManager.getPackageInfo(GET_SIGNING_CERTIFICATES)`
// **binder 往返**，要 Context，JVM 单测跑不了（本仓库不引 Robolectric，口径同
// `StartupCleanupGateTest`）。所以判据只能是"这段代码在不在、以什么形状在"。
// 语义那半边由既有的 `SigningCheckTest`（7 条，纯函数 `hexOf` + 白名单判定）守着。
//
// ## 钉的是什么
// - `check()` **第一行**就读缓存：冷启动第二次调用、关于页那次调用不再走 binder；
// - **UNKNOWN 不许进缓存**：读不到签名只是"这一次没读到"，缓存它等于把偶发失败
//   固化成永久的"未识别"——那是改行为，不是优化；
// - 判定出口与赋值点各只有一处：多一条路就可能绕过上述约束；
// - 三个调用点一个都不必改（缓存藏在 `check()` 内部），这里反过来钉"没人把它们改成别的路子"。
package com.example.zhengdao.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SigningCheckProcessCacheGateTest {

    private fun src(rel: String): String {
        val f = listOf(
            File("src/main/java/com/example/zhengdao/$rel"),
            File("app/src/main/java/com/example/zhengdao/$rel"),
            File("../../app/src/main/java/com/example/zhengdao/$rel"),
        ).firstOrNull { it.isFile } ?: error("gate cannot find $rel - pin path needs update")
        return f.readText()
    }

    private fun checkBody(): String = src("util/SigningCheck.kt")
        .substringAfter("fun check(ctx: Context): Result {")
        .substringBefore("\n    /** 当前安装包第一个签名证书")

    @Test
    fun `T2_先读进程缓存且UNKNOWN不落缓存`() {
        val check = checkBody()
        assertTrue("check() does not read process cache first", check.contains("cachedResult?.let { return it }"))
        // 三个出口都从 hex 判出来（不许有第四条路）
        assertEquals("Result decision site count", 1, check.split("OFFICIAL_SHA256S ->").size - 1)
        // UNKNOWN = 这一次没读到；缓存它 = 把偶发失败固化成永久语义 ⇒ 明令不许
        assertTrue("UNKNOWN cached unconditionally", check.contains("if (result != Result.UNKNOWN) cachedResult = result"))
        // 写入点只能有这一处
        assertEquals("cachedResult assignment count", 1, check.split("cachedResult = result").size - 1)
        assertTrue("@Volatile lost cross-thread visibility", src("util/SigningCheck.kt").contains("@Volatile"))
    }

    @Test
    fun `T2_三个调用点都还走同一个入口`() {
        val app = src("ZhengdaoApp.kt")
        val main = src("MainActivity.kt")
        val about = src("ui/SettingsAboutActivity.kt")
        assertTrue("app-entry signature self-check missing", app.contains("SigningCheck.check(this)"))
        assertTrue("MainActivity signature warning missing", main.contains("SigningCheck.check(this)"))
        assertTrue("About page signature line missing", about.contains("SigningCheck.check(ctx)"))
        assertTrue("MainActivity no longer gates on UNOFFICIAL", main.contains("SigningCheck.Result.UNOFFICIAL"))
    }
}
