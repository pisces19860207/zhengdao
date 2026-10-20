// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第六十批 · P2-7：`RootfsInstaller.cleanupPartial` **不许回到 onCreate 主线程**。
 *
 * ## 为什么要按源码钉
 *
 * 它是**递归删目录**（上次安装中断留下的 tmp 树 / 替换备份），最坏 1.5–2GB。放在
 * `onCreate` 里同步跑 = 冷启动掉帧到 ANR，而单测**测不出主线程卡顿**（JVM 没有 Looper，
 * 也不该为这一条引 Robolectric）。所以判据只能是「这行代码在不在主线程同步段里」。
 *
 * ## 钉的是三件事，不是「出现过」这一件
 *
 * 1. `onCreate` 的函数体里**没有任何** `cleanupPartial`（挪回去就红）；
 * 2. 它出现在 [com.example.zhengdao.TerminalActivity.startSessionOffMainThread] 的
 *    **后台 worker（`Thread({`）之后**——只写在这个函数的别处（比如函数体开头、
 *    Thread 之前）照样是主线程，必须红；
 * 3. 顺序仍是「先清残局、再算启动计划」：清理可能把 `rootfs.old` 复原成 `rootfs`，
 *    而 `prepareLaunchPlan` 读的就是这棵树，调换顺序＝把唯一的环境当残局删掉。
 *
 * 负例（`p60neg.py` P2-7-a/b/c）分别改回 onCreate、挪到 Thread 之前、删掉 runCatching，
 * 三条都必须红。
 */
class StartupCleanupGateTest {

    // ── 源码读取（cwd 可能是仓根或 app/，与仓库其它源码门禁同一兜底法） ──────

    private fun repoFile(rel: String): File = File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun raw(rel: String): String = repoFile(rel).readText().replace("\r\n", "\n")

    /** 剥注释：判「必须有」时用，避免 KDoc 里提一嘴就算过。 */
    private fun code(rel: String): String = raw(rel)
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private val activitySrc = "src/main/java/com/example/zhengdao/TerminalActivity.kt"

    /**
     * 从签名所在行切到该函数的收尾大括号（类成员是 4 空格缩进，函数体更深）。
     * 比数括号稳：本仓函数体里有大量 lambda 与字符串模板，数括号容易在 `"$x"` 上翻车。
     */
    private fun funBody(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("找不到函数签名：$signature（函数被改名了？门禁跟着改）", start >= 0)
        val end = src.indexOf("\n    }", start)
        assertTrue("找不到函数收尾：$signature", end > start)
        return src.substring(start, end)
    }

    @Test
    fun `P2-7_onCreate 同步段不许再出现 cleanupPartial`() {
        val onCreate = funBody(code(activitySrc), "override fun onCreate(savedInstanceState: Bundle?)")
        assertFalse(
            "cleanupPartial 又回到 onCreate 主线程了（最坏递归删 1.5–2GB ⇒ 掉帧/ANR）",
            onCreate.contains("cleanupPartial"),
        )
        assertTrue(
            "onCreate 里应当留一句指向后台入口的说明（读代码的人才知道它去哪了）",
            raw(activitySrc).contains("startSessionOffMainThread"),
        )
    }

    @Test
    fun `P2-7_清理必须在后台 worker 里且早于启动计划`() {
        val body = funBody(code(activitySrc), "private fun startSessionOffMainThread()")
        val workerAt = body.indexOf("Thread({")
        val cleanAt = body.indexOf("RootfsInstaller.cleanupPartial(")
        val planAt = body.indexOf("prepareLaunchPlan(")
        assertTrue("清理必须在这个后台入口里（挪去别处 = 判据失效）", cleanAt > 0)
        assertTrue("清理必须在后台 worker 线程里，不许留在主线程同步段", workerAt in 0 until cleanAt)
        assertTrue("顺序必须是「先清残局、再算启动计划」", cleanAt < planAt)
    }

    @Test
    fun `P2-7_清理失败要落 RunLog 不许静默`() {
        val body = funBody(code(activitySrc), "private fun startSessionOffMainThread()")
        val cleanAt = body.indexOf("RootfsInstaller.cleanupPartial(")
        assertTrue(cleanAt > 0)
        // 只看清理那一小段（±600 字），避免被后面启动计划自己的 try/catch 糊过去
        val window = body.substring(maxOf(0, cleanAt - 200), minOf(body.length, cleanAt + 600))
        assertTrue(
            "清理必须**就地**包在 runCatching 里（漏网异常不许带走会话启动）",
            window.contains("runCatching { RootfsInstaller.cleanupPartial(this) }"),
        )
        assertTrue("失败必须走 onFailure 落 RunLog（用户能在「设置 → 运行日志」看到）", window.contains(".onFailure {"))
        assertTrue("失败必须落 RunLog（不能只打 Log.w 了事）", window.contains("RunLog.log("))
    }

    @Test
    fun `P2-7_判别器自证_切片真的能抓到 onCreate 里的那行`() {
        // 反向自证：拿一段「onCreate 里同步调用」的坏文本喂给同一套判据，必须判红
        val bad = "override fun onCreate(savedInstanceState: Bundle?) {\n" +
            "        RootfsInstaller.cleanupPartial(applicationContext)\n" +
            "    }"
        val body = funBody(bad, "override fun onCreate(savedInstanceState: Bundle?)")
        assertTrue("判别器抓不住「写在 onCreate 里」，说明切片失效", body.contains("cleanupPartial"))
    }
}
