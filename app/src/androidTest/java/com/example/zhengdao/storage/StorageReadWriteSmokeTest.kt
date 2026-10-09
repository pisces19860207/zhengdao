// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的公开资料：Android instrumented test 官方文档、Environment 官方文档。
package com.example.zhengdao.storage

import android.os.Environment
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.zhengdao.terminal.ProotLauncher
import com.example.zhengdao.ui.SystemInfoProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 存储读取**真机冒烟**（#1，2026-10-09）。
 *
 * 守的是第 0 步那次事故：`READ_EXTERNAL_STORAGE` 被扣了 `maxSdkVersion="32"` 帽子 ⇒
 * Android 13+ 上 READ 权限为空 ⇒ **能写（私有目录）不能读**，hermes 找不到用户文件。
 * 静态配置那半边由 `app/src/test/java/com/example/zhengdao/storage/StoragePermissionGuardTest.kt`
 * 在 CI 上守着；这一份守"真的读得到、真的写得回"，必须在真机上跑：
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.example.zhengdao.storage.StorageReadWriteSmokeTest
 * ```
 *
 * ⚠️ **务必带上 `leaveApksInstalledAfterRun=true`**（2026-10-09 真机事故）：AGP 默认在跑完
 * connectedAndroidTest 后**卸载 App**，而 `files/` 里装着整台"手机上的 Linux"（rootfs 201MB +
 * hermes 4.4G + 太极 opencode 289MB）——一次跑测就能把用户的运行环境清空。加上那个 flag，
 * AGP 跑完保留已安装的 APK（数据自然保住）。真被清了也能救：公共区
 * `Download/证道/{rootfs,agents,cache}` 不受卸载影响，重装后首页会给「恢复上次装过的 N 个 Agent」。
 *
 * 用例自己会补授存储权限（`@Before` 走 UiAutomation 跑 `pm grant`）：instrumented test 不走
 * MainActivity 的冷启动补授权，不补就是"READ 权限为空 ⇒ 全都读不到"的假红。
 *
 * 为什么不进 CI 的模拟器：本包只打 `arm64-v8a`（`app/build.gradle.kts:103-110`，用户 2026-10-06 定案
 * 「不做 x86_64 模拟器支持」），GitHub 的 x86_64 模拟器装不上；所以 CI 里只保证它能**编译**
 * （`ci.yml` 的 `:app:assembleDebugAndroidTest`）。
 *
 * 写法上刻意与 [com.example.zhengdao.rootfs.ExtractBaselineTest] 的"缺条件就静默 return"相反：
 * 依赖设备状态的那两条用 `Assume.assumeTrue`（报告里显示为 skipped，看得见），
 * 真正的回归点（列目录、真实可读、写回读回）一律**硬断言**，不许悄悄放过。
 */
@RunWith(AndroidJUnit4::class)
class StorageReadWriteSmokeTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(s: String) = Log.i(TAG, s)

    /**
     * instrumented test 里 App 是"被拉起来的"，不走冷启动补授权这条路，
     * 所以这里用 UiAutomation（shell 身份）替目标包补上 READ / WRITE。
     * 不补的话，明明是同一台好设备，也会跑出第 0 步那个 bug 的假象（列表返回 null、写 EPERM）。
     */
    @Before
    fun ensureStoragePermission() {
        listOf(
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
        ).forEach { perm ->
            runCatching {
                InstrumentationRegistry.getInstrumentation().uiAutomation
                    .executeShellCommand("pm grant ${ctx.packageName} $perm")
                    .close()
            }
        }
        log("权限位：READ=${granted("android.permission.READ_EXTERNAL_STORAGE")} " +
            "WRITE=${granted("android.permission.WRITE_EXTERNAL_STORAGE")}")
    }

    private fun granted(perm: String): Boolean =
        ctx.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED

    @Test
    fun sdcardRootIsListableAndHasDownload() {
        assertTrue(
            "共享存储没挂载（state=${Environment.getExternalStorageState()}）——所有读都会失败",
            Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED,
        )
        val entries = ROOT.listFiles()
        assertTrue(
            "列出 /storage/emulated/0 失败（返回 null）——这正是第 0 步那个 bug 的形态：" +
                "READ_EXTERNAL_STORAGE 带 maxSdkVersion 帽子时 Android 13+ 的 READ 权限为空",
            entries != null,
        )
        val names = entries!!.map { it.name }.sorted()
        log("ls /storage/emulated/0 => $names")
        assertTrue("列表里看不到 Download：$names", names.contains("Download"))
    }

    @Test
    fun realReadableMatchesHealthCheck() {
        // 体检的「存储权限」项现在就用这个函数（EnvHealth.storageVerdict 的 readable 参数）：
        // 它为 false 时体检会报红并给出「权限帽子或存储视图受限」的文案。
        assertTrue(
            "SystemInfoProvider.sharedStorageReadable() = false ⇒ Download 目录读不到",
            SystemInfoProvider.sharedStorageReadable(),
        )
    }

    @Test
    fun canReadUserFileInDownload() {
        val f = firstReadableUserFile()
        assumeTrue("设备 Download 下没有可读的真实文件（先拷一个进去再跑这条）", f != null)
        val first = f!!.readBytes()
        val second = f.readBytes()
        log("读过真实文件：${f.absolutePath}（${first.size} 字节，sha256=${sha256(first).take(12)}…）")
        assertTrue("真实文件读出来是空的：${f.absolutePath}", first.isNotEmpty())
        assertEquals("两次读取长度不一致（读到一半被截？）", first.size, second.size)
        assertTrue("两次读取内容不一致", first.contentEquals(second))
    }

    @Test
    fun writeOwnFileAndReadBack() {
        val dir = File(PUBLIC_WS, "logs").apply { mkdirs() }
        val file = File(dir, "storage-smoke-${System.currentTimeMillis()}.tmp")
        // 内容里放中文 + 换行 + 多字节，顺带覆盖"文本写读"这条常见路径
        val payload = buildString {
            repeat(120) { append("第 $it 行：证道存储读写冒烟 ✓ / line $it\n") }
        }.toByteArray()
        try {
            file.writeBytes(payload)
            assertTrue("文件没写出来：${file.absolutePath}", file.isFile)
            assertEquals("写进去的字节数与落盘不一致", payload.size.toLong(), file.length())
            val back = file.readBytes()
            assertEquals("读回的 sha256 与写入不一致", sha256(payload), sha256(back))
            log("写回一致：${file.absolutePath}（${back.size} 字节）")

            // 追加 → 复读（覆盖 O_APPEND 路径）
            file.appendText("追加一行\n")
            assertTrue(file.readText().endsWith("追加一行\n"))

            // 改名 → 仍可读（覆盖 rename 在共享存储上的行为，FUSE 下最容易出问题的一步）
            val renamed = File(dir, file.name.replace(".tmp", ".renamed"))
            assertTrue("改名失败", file.renameTo(renamed))
            assertTrue("改名后读不到", renamed.isFile && renamed.length() > 0)

            assertTrue("删除失败", renamed.delete())
            assertFalse("删除后文件还在：${renamed.absolutePath}", renamed.exists())
        } finally {
            file.delete()
        }
    }

    @Test
    fun guestCanSeeSharedStorage() {
        val plan = ProotLauncher.buildLaunchPlan(ctx, tmuxSession = TMUX)
        assumeTrue(
            "本机没有可用的 Debian 环境（proot 回退模式）——这条要在装好环境的真机上跑",
            !plan.isFallback,
        )
        val out = runInGuest(plan, "ls /storage/emulated/0/")
        log("guest 内 ls /storage/emulated/0 => ${out.trim().replace('\n', ' ')}")
        assertTrue("guest 里看不到 Download（-b 绑定或权限有问题）：$out", out.contains("Download"))
    }

    // ── 工具 ──

    private fun firstReadableUserFile(): File? {
        val download = File(ROOT, "Download")
        val ours = File(download, "证道")
        // 优先"用户自己拷进来的"（不在我们自己的目录里），其次任何可读文件
        val candidates = download.walkTopDown()
            .maxDepth(3)
            .filter { it.isFile && it.length() > 0 && it.canRead() }
            .take(200)
            .toList()
        return candidates.firstOrNull { !it.absolutePath.startsWith(ours.absolutePath) } ?: candidates.firstOrNull()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * 用 App 自己的启动计划跑一条 guest 命令（与终端里那条链路同源：同一个 proot 二进制、
     * 同一份 `-b` 绑定表），拿 stdout 回来。
     *
     * 生产计划收尾的是**交互式**会话（`tmux new-session -A …`），往它的 stdin 写命令没用
     * （tmux 要的是 TTY，第一版就是这么失败的：拿回来一串乱码）。所以这里只把计划里的
     * 收尾命令换成我们自己的 `/bin/bash -lc <command>`，**proot 选项与绑定一个不动** ——
     * 换的是"跑什么"，不是"怎么进去"。
     */
    private fun runInGuest(plan: ProotLauncher.LaunchPlan, command: String, timeoutMs: Long = 30_000): String {
        val args = plan.args.copyOf()
        // 收尾命令一律以 "/bin/bash" 开头（ProotLauncher 的三种分支都如此），取最后一次出现
        val cut = args.indexOfLast { it == "/bin/bash" }
        val head = if (cut > 0) args.copyOfRange(0, cut) else args
        val pb = ProcessBuilder(listOf(plan.cmd) + head + listOf("/bin/bash", "-lc", command))
        plan.env.forEach { kv ->
            val i = kv.indexOf('=')
            if (i > 0) pb.environment()[kv.substring(0, i)] = kv.substring(i + 1)
        }
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out = StringBuilder()
        val reader = Thread {
            runCatching { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
        }
        reader.isDaemon = true
        reader.start()
        p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        reader.join(2000)
        if (p.isAlive) p.destroyForcibly()
        return out.toString()
    }

    private companion object {
        const val TAG = "STORAGE-SMOKE"
        const val TMUX = "zd-storage-smoke"
        val ROOT = File("/storage/emulated/0")
        val PUBLIC_WS = File(ROOT, "Download/证道")
    }
}
