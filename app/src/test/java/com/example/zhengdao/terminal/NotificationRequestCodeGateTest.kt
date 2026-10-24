// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 第六十批 · P2-12：三处「点通知回 App」的 `PendingIntent` 必须用**互不相同**的请求码。
 *
 * ## 故障形态
 *
 * `PendingIntent` 的等价性是「**requestCode + Intent.filterEquals**」，**extras 不参与比较**。
 * 三条通知（缓存清理 / 安装进度 / 会话保活）都是 `MainActivity` + `SINGLE_TOP`，原先请求码
 * 全是 0 ⇒ 系统认为它们是**同一个** PendingIntent；`FLAG_UPDATE_CURRENT` 会拿最后一次
 * `getActivity` 的 extras 覆盖前面的。于是「点缓存通知」可能落到主界面（`open_terminal`
 * 被抹掉），或反过来点了却进终端 —— 谁的 extras 生效取决于构建顺序，真机表现为随机。
 *
 * ## 判据
 *
 * - 三处请求码必须是**命名常量**（不许裸数字），且三个**值互不相同**；
 * - 服务内另外两条动作（停会话 / 切保活）也各自命名 —— 它们和上面同处一个服务，
 *   将来有人加通知时最容易被复制成同一个 0/1/2；
 * - 全仓这三个文件里不许再出现「`PendingIntent.getXxx(ctx, 数字,`」形态。
 *
 * 门禁按**源码文本**解析常量值：不加载 Android 类（`SessionService` 是 Service，
 * JVM 单测里实例化不了），也顺带把「常量改名/改值」变成一次可读的红。
 */
class NotificationRequestCodeGateTest {

    private fun repoFile(rel: String): File = File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun raw(rel: String): String = repoFile(rel).readText().replace("\r\n", "\n")

    private fun code(rel: String): String = raw(rel)
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private val cacheSrc = "src/main/java/com/example/zhengdao/terminal/CacheNotifier.kt"
    private val installSrc = "src/main/java/com/example/zhengdao/terminal/InstallNotifier.kt"
    private val serviceSrc = "src/main/java/com/example/zhengdao/terminal/SessionService.kt"
    private val completionSrc = "src/main/java/com/example/zhengdao/baopu/BaopuCompletionNotifier.kt"

    /** 从源码里取一个 `const val NAME = <数字>` 的值（取不到 ⇒ 直接红）。 */
    private fun constValue(src: String, file: String, name: String): Int {
        val m = Regex("const val $name\\s*=\\s*(\\d+)").find(src)
        assertNotNull("$file 里必须有 `const val $name = <数字>`（请求码要命名、要可读）", m)
        return m!!.groupValues[1].toInt()
    }

    @Test
    fun `P2-12_三处请求码必须是命名常量且互不相同`() {
        val cache = code(cacheSrc)
        val install = code(installSrc)
        val service = code(serviceSrc)
        val completion = code(completionSrc)

        val reqCache = constValue(cache, "CacheNotifier.kt", "REQ_CACHE")
        val reqInstall = constValue(install, "InstallNotifier.kt", "REQ_INSTALL")
        val reqKeep = constValue(service, "SessionService.kt", "REQ_KEEPALIVE")
        val reqSession = constValue(completion, "BaopuCompletionNotifier.kt", "REQ_SESSION")

        assertEquals(
            "四条通知的请求码必须互不相同（PendingIntent 等价性只看请求码 + filterEquals，" +
                "撞了就会互相改 extras：缓存通知能清掉「回到终端」）：" +
                "cache=$reqCache install=$reqInstall keepalive=$reqKeep session=$reqSession",
            4,
            setOf(reqCache, reqInstall, reqKeep, reqSession).size,
        )

        assertTrue(
            "CacheNotifier 必须用 REQ_CACHE 建 PendingIntent",
            Regex("PendingIntent\\.getActivity\\(\\s*ctx,\\s*REQ_CACHE,").containsMatchIn(cache),
        )
        assertTrue(
            "InstallNotifier 必须用 REQ_INSTALL 建 PendingIntent",
            Regex("PendingIntent\\.getActivity\\(\\s*ctx,\\s*REQ_INSTALL,").containsMatchIn(install),
        )
        assertTrue(
            "SessionService 必须用 REQ_KEEPALIVE 建 PendingIntent",
            Regex("PendingIntent\\.getActivity\\(\\s*this,\\s*REQ_KEEPALIVE,").containsMatchIn(service),
        )
        // 同一服务里的两个动作也要命名（防止将来又撞回 0/1/2）
        assertTrue(
            "停会话动作要用 REQ_STOP_SESSION 常量",
            Regex("PendingIntent\\.getService\\(\\s*this,\\s*REQ_STOP_SESSION,").containsMatchIn(service),
        )
        assertTrue(
            "切保活动作要用 REQ_TOGGLE_KEEPALIVE 常量",
            Regex("PendingIntent\\.getService\\(\\s*this,\\s*REQ_TOGGLE_KEEPALIVE,").containsMatchIn(service),
        )
    }

    @Test
    fun `P2-12_五个请求码两两不同`() {
        val service = code(serviceSrc)
        val values = listOf(
            "REQ_CACHE" to constValue(code(cacheSrc), "CacheNotifier.kt", "REQ_CACHE"),
            "REQ_INSTALL" to constValue(code(installSrc), "InstallNotifier.kt", "REQ_INSTALL"),
            "REQ_KEEPALIVE" to constValue(service, "SessionService.kt", "REQ_KEEPALIVE"),
            "REQ_STOP_SESSION" to constValue(service, "SessionService.kt", "REQ_STOP_SESSION"),
            "REQ_TOGGLE_KEEPALIVE" to constValue(service, "SessionService.kt", "REQ_TOGGLE_KEEPALIVE"),
            "REQ_SESSION" to constValue(code(completionSrc), "BaopuCompletionNotifier.kt", "REQ_SESSION"),
        )
        assertEquals("六个请求码必须两两不同：$values", values.size, values.map { it.second }.toSet().size)
    }

    @Test
    fun `P2-12_不许再出现裸数字请求码`() {
        listOf(
            cacheSrc to "CacheNotifier.kt",
            installSrc to "InstallNotifier.kt",
            serviceSrc to "SessionService.kt",
            completionSrc to "BaopuCompletionNotifier.kt",
        )
            .forEach { (rel, name) ->
                val src = code(rel)
                Regex("PendingIntent\\.get(Activity|Service|Broadcast)\\(\\s*[A-Za-z_][A-Za-z0-9_.]*,\\s*\\d")
                    .findAll(src)
                    .forEach { fail("$name 里还有裸数字请求码：${it.value}（请求码必须命名，否则下次又撞）") }
            }
    }
}
