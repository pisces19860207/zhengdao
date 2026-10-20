// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第七十五批（结构拆分第四阶段，§B 80 切片）验收：ProotLauncher 启动计划纯逻辑
// 抽取至 ProotLaunchRules 后的行为对账。
//
// 三条证据链：
// 1. **改前/改后逐字节对账**（本文件核心）：把抽取前的 env 表 / proot argv /
//    回退计划按原样硬编码为「改前」基准，helper 输出必须与之逐字节一致——
//    顺序、字面量、插值结果全部钉死；
// 2. **逐函数行为**：每个函数 ≥3 例（正常 / 边界 / 空值 / 含空格与中文的路径）；
// 3. **源码门禁**：launcher 里不得残留第二份实现（env 表 / fallbackPlan /
//    内存前缀 / tmux 命令），13 处 helper 调用点必须在位，副作用编排
//    （持锁 / 写盘 / RunLog / 软链 / IssueCenter）必须仍在 launcher。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §B 80 切片「ProotLauncher 启动计划纯逻辑抽取」的验收（2026-10-20）。
 *
 * 被测对象 `ProotLaunchRules`（internal object）：`buildLaunchPlanLocked()`
 * 里「值进值出」的部分——env 表、proot 参数串、存储/权限判定、回退计划构造、
 * 启动就绪判定。文件查询（`isFile`/`canExecute`）、软链刷新、mkdirs、
 * AppPrefs 读、RunLog、持锁调用按派工单口径**留在 ProotLauncher**，本测试
 * 第三部分用源码门禁钉住这条分界。
 */
class ProotLaunchRulesTest {

    /** Gradle 单测的工作目录是模块目录 `app/`（与 TmuxConfAppendTest 同一约定）。 */
    private fun appFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun launcherSrc(): String =
        appFile("src/main/java/com/example/zhengdao/terminal/ProotLauncher.kt")
            .also { assertTrue("找不到 ProotLauncher.kt：${it.path}", it.isFile) }
            .readText()

    private fun rulesSrc(): String =
        appFile("src/main/java/com/example/zhengdao/terminal/ProotLaunchRules.kt")
            .also { assertTrue("找不到 ProotLaunchRules.kt：${it.path}", it.isFile) }
            .readText()

    /**
     * 剥掉 Kotlin 行注释 / 块注释 / 字符串内容，只留代码骨架。
     * 纯度门禁要用它——helper 的 KDoc 里光明正大写着「不碰 Android Context」，
     * 直接扫全文会把注释里的名词误判成副作用用法。
     */
    private fun codeOnly(src: String): String {
        val sb = StringBuilder()
        var i = 0
        var inStr = false
        while (i < src.length) {
            val c = src[i]
            val n = if (i + 1 < src.length) src[i + 1] else ' '
            if (inStr) {
                if (c == '\\') { i += 2; continue }
                if (c == '"') inStr = false
                i++
                continue
            }
            if (c == '"') { inStr = true; i++; continue }
            if (c == '/' && n == '/') {
                while (i < src.length && src[i] != '\n') i++
                continue
            }
            if (c == '/' && n == '*') {
                i += 2
                while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                i += 2
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    // ── 一、改前/改后对账（核心证据） ──────────────────────────────────────
    // 「改前」基准 = 抽取前 buildLaunchPlanLocked 里的原样字面量（值已按样本
    // 输入代入）；路径样本刻意含空格与中文（真机 /storage/emulated/0 下的
    // 「工作区」「证道」目录），插值错一位都会被抓到。

    @Test
    fun `env表_16项与改前逐字节一致_路径含空格与中文`() {
        // 改前：val env = mutableListOf( ... )，样本值代入后的 16 项
        val expected = listOf(
            "PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME=/root",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "TZ=Asia/Shanghai",
            "PROOT_TMP_DIR=/data/user/0/com.example.zhengdao/files/proot tmp",
            "UV_LINK_MODE=copy",
            "UV_PYTHON=/usr/bin/python3",
            "LD_LIBRARY_PATH=/data/user/0/com.example.zhengdao/files/rt:/data/app/~~a1b2c3/lib/arm64",
            "PROOT_LOADER=/data/app/~~a1b2c3/lib/arm/libproot-loader.so",
            "BUN_FEATURE_FLAG_DISABLE_IPV6=1",
            "DISABLE_TELEMETRY=1",
            "DISABLE_ERROR_REPORTING=1",
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1",
            "NO_PROXY=localhost,127.0.0.1,::1",
            "no_proxy=localhost,127.0.0.1,::1",
        )
        val actual = ProotLaunchRules.envEntries(
            tzName = "Asia/Shanghai",
            prootTmpPath = "/data/user/0/com.example.zhengdao/files/proot tmp",
            rtDirPath = "/data/user/0/com.example.zhengdao/files/rt",
            nativeDirPath = "/data/app/~~a1b2c3/lib/arm64",
            loaderBinPath = "/data/app/~~a1b2c3/lib/arm/libproot-loader.so",
        )
        assertEquals("env 项数变了（顺序即语义）", expected.size, actual.size)
        expected.forEachIndexed { i, want ->
            assertEquals("env[$i] 与改前不一致", want, actual[i])
        }
    }

    @Test
    fun `env表_TZ两种取值都随tzName原样流入`() {
        val geo = ProotLaunchRules.envEntries("Asia/Shanghai", "/tmp", "/rt", "/nat", "/loader")
        assertEquals("TZ=Asia/Shanghai", geo[4])
        val posix = ProotLaunchRules.envEntries("CST-8", "/tmp", "/rt", "/nat", "/loader")
        assertEquals("zoneinfo 缺失时退到 CST-8", "TZ=CST-8", posix[4])
        // 其余 15 项不受 tzName 影响
        assertEquals(geo - "TZ=Asia/Shanghai", posix - "TZ=CST-8")
    }

    @Test
    fun `完整argv_共享存储主会话mem512场景与改前逐字节一致`() {
        // 改前编排顺序（原 buildLaunchPlanLocked :501–628）：
        //   固定段 → [共享存储 bind + /sdcard] → 工作区 bind → 公共区 bind
        //   →（npm/pip 缓存 bind：bindSharedCache，未抽取，不参与本对账）
        //   → 收尾命令（永远在最后：bind 追加到 bash 之后会被当脚本路径执行，exit 127）
        val prootBinPath = "/data/app/~~p/lib/arm64/libproot.so"
        val rootfsPath = "/data/user/0/com.example.zhengdao/files/rootfs"
        val homePath = "/data/user/0/com.example.zhengdao/files/home"
        val wsHostPath = "/storage/emulated/0/Android/data/com.example.zhengdao/files/工作区"
        val storeRootPath = "/storage/emulated/0/Download/证道"
        val expected = listOf(
            prootBinPath,
            "--kill-on-exit",
            "--link2symlink",
            "-r", rootfsPath,
            "-0",
            "-w", "/root",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "$homePath:/root",
            "-b", "/storage/emulated/0:/storage/emulated/0",
            "-b", "/sdcard:/sdcard",
            "-b", "$wsHostPath:/workspace",
            "-b", "$storeRootPath:/opt/zhengdao",
            "/bin/bash",
            "-lc",
            "ulimit -v 524288; tmux kill-server 2>/dev/null; exec tmux new-session -A -s zhengdao",
        )
        val actual = buildList {
            addAll(ProotLaunchRules.baseArgs(prootBinPath, rootfsPath, homePath))
            addAll(ProotLaunchRules.sharedStorageBindArgs())
            addAll(ProotLaunchRules.workspaceBindArg(wsHostPath))
            addAll(ProotLaunchRules.storeRootBindArg(storeRootPath, "/opt/zhengdao"))
            addAll(
                ProotLaunchRules.guestCommandArgs(
                    tmuxSession = "zhengdao",
                    memPrefix = ProotLaunchRules.memLimitPrefix(512),
                    hasTmux = true,
                )
            )
        }
        assertEquals("argv 与改前不一致（共 ${expected.size} 项）", expected, actual)
    }

    @Test
    fun `完整argv_仅私有副会话无mem场景与改前逐字节一致`() {
        val prootBinPath = "/data/app/~~p/lib/arm64/libproot.so"
        val rootfsPath = "/data/user/0/com.example.zhengdao/files/rootfs"
        val homePath = "/data/user/0/com.example.zhengdao/files/home"
        val wsHostPath = "/storage/emulated/0/Android/data/com.example.zhengdao/files/工作区"
        val storeRootPath = "/storage/emulated/0/Download/证道"
        val expected = listOf(
            prootBinPath,
            "--kill-on-exit",
            "--link2symlink",
            "-r", rootfsPath,
            "-0",
            "-w", "/root",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "$homePath:/root",
            // 仅私有模式：无共享存储 bind、无 /sdcard（sharedStorageAllowed=false）
            "-b", "$wsHostPath:/workspace",
            "-b", "$storeRootPath:/opt/zhengdao",
            "/bin/bash",
            "-lc",
            "exec tmux new-session -A -s 'taiji' /bin/bash -l",
        )
        val actual = buildList {
            addAll(ProotLaunchRules.baseArgs(prootBinPath, rootfsPath, homePath))
            addAll(ProotLaunchRules.workspaceBindArg(wsHostPath))
            addAll(ProotLaunchRules.storeRootBindArg(storeRootPath, "/opt/zhengdao"))
            addAll(
                ProotLaunchRules.guestCommandArgs(
                    tmuxSession = "taiji",
                    memPrefix = ProotLaunchRules.memLimitPrefix(0),
                    hasTmux = true,
                )
            )
        }
        assertEquals("argv 与改前不一致（共 ${expected.size} 项）", expected, actual)
        // 副会话命令里不得出现 kill-server（会连带杀掉共存于同一 server 的其它会话）
        assertFalse("副会话不该 kill-server", actual.last().contains("kill-server"))
    }

    @Test
    fun `回退计划_五处字段与改前逐字节一致_路径含空格与中文`() {
        // 改前：private fun fallbackPlan(filesDir, cacheDir) = LaunchPlan(...)（:830–843）
        val filesDir = File("/data/user/0/com.example.zhengdao/files")
        val cacheDir = File("/data/user/0/com.example.zhengdao/cache")
        val plan = ProotLaunchRules.fallbackPlan(filesDir, cacheDir)
        assertEquals("/system/bin/sh", plan.cmd)
        assertEquals(listOf("-l"), plan.args.toList())
        assertEquals(
            listOf(
                "PATH=/system/bin:/system/xbin:/vendor/bin",
                "HOME=${filesDir.absolutePath}",
                "TMPDIR=${cacheDir.absolutePath}",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
            ),
            plan.env.toList(),
        )
        assertTrue("isFallback 必须为 true（调用方据此撤问题）", plan.isFallback)
    }

    // ── 二、逐函数行为（每函数 ≥3 例） ────────────────────────────────────

    @Test
    fun `tzNameFor_有zoneinfo用地理名_没有退POSIX自包含写法`() {
        assertEquals("Asia/Shanghai", ProotLaunchRules.tzNameFor(true))
        assertEquals("CST-8", ProotLaunchRules.tzNameFor(false))
    }

    @Test
    fun `baseArgs_固定段与改前逐字节一致_顺序即语义`() {
        // 16 个元素：proot 本体 + 2 开关 + (-r rootfs) + -0 + (-w /root) + 4 组 bind。
        // home bind 必须是最后一项（其后是调用点追加的存储/缓存 bind 与收尾命令）。
        val expected = listOf(
            "/data/app/~~p/lib/arm64/libproot.so",
            "--kill-on-exit",
            "--link2symlink",
            "-r", "/data/user/0/com.example.zhengdao/files/rootfs",
            "-0",
            "-w", "/root",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/data/user/0/com.example.zhengdao/files/home:/root",
        )
        val actual = ProotLaunchRules.baseArgs(
            prootBinPath = "/data/app/~~p/lib/arm64/libproot.so",
            rootfsPath = "/data/user/0/com.example.zhengdao/files/rootfs",
            homePath = "/data/user/0/com.example.zhengdao/files/home",
        )
        assertEquals(expected, actual)
        // home bind 必须是最后一项（其后是调用点追加的存储/缓存 bind 与收尾命令）
        assertTrue(
            "home bind 不在固定段末尾：${actual.last()}",
            actual.last() == "/data/user/0/com.example.zhengdao/files/home:/root",
        )
    }

    @Test
    fun `baseArgs_路径含空格与中文时原样拼入不做转义`() {
        val actual = ProotLaunchRules.baseArgs(
            prootBinPath = "/data/app/~~p/lib/arm64/libproot.so",
            rootfsPath = "/storage/emulated/0/Download/证道/rootfs",
            homePath = "/storage/emulated/0/Android/data/com.example.zhengdao/files/home 目录",
        )
        assertEquals("/storage/emulated/0/Download/证道/rootfs", actual[4])
        assertEquals(
            "/storage/emulated/0/Android/data/com.example.zhengdao/files/home 目录:/root",
            actual.last(),
        )
    }

    @Test
    fun `sharedStorageBindArgs_固定两个bind_共享原路径加sdcard视图`() {
        assertEquals(
            listOf("-b", "/storage/emulated/0:/storage/emulated/0", "-b", "/sdcard:/sdcard"),
            ProotLaunchRules.sharedStorageBindArgs(),
        )
    }

    @Test
    fun `guestSymlinkTargetFor_共享路径去掉前缀挂到sdcard视图`() {
        assertEquals(
            "/sdcard/Download/证道",
            ProotLaunchRules.guestSymlinkTargetFor("/storage/emulated/0/Download/证道"),
        )
    }

    @Test
    fun `guestSymlinkTargetFor_恰好是共享根本身时落到sdcard根本身`() {
        assertEquals("/sdcard", ProotLaunchRules.guestSymlinkTargetFor("/storage/emulated/0"))
    }

    @Test
    fun `guestSymlinkTargetFor_非共享路径原样挂载不变`() {
        // 仅私有模式的工作区在 App 私有目录：removePrefix 不命中 ⇒ 原路径追加
        val priv = "/data/user/0/com.example.zhengdao/files/workspace"
        assertEquals("/sdcard$priv", ProotLaunchRules.guestSymlinkTargetFor(priv))
    }

    @Test
    fun `guestSymlinkTargetFor_路径含空格与中文时保持原样`() {
        assertEquals(
            "/sdcard/Android/data/com.example.zhengdao/files/工作区 备份",
            ProotLaunchRules.guestSymlinkTargetFor(
                "/storage/emulated/0/Android/data/com.example.zhengdao/files/工作区 备份"
            ),
        )
    }

    @Test
    fun `workspaceBindArg_工作区目录绑到guest的workspace`() {
        assertEquals(
            listOf("-b", "/storage/emulated/0/Android/data/com.example.zhengdao/files/工作区:/workspace"),
            ProotLaunchRules.workspaceBindArg(
                "/storage/emulated/0/Android/data/com.example.zhengdao/files/工作区"
            ),
        )
    }

    @Test
    fun `workspaceBindArg_仅私有模式的私有目录也走同一格式`() {
        assertEquals(
            listOf("-b", "/data/user/0/com.example.zhengdao/files/workspace:/workspace"),
            ProotLaunchRules.workspaceBindArg("/data/user/0/com.example.zhengdao/files/workspace"),
        )
    }

    @Test
    fun `storeRootBindArg_公共存放区绑到指定guest挂载点`() {
        assertEquals(
            listOf("-b", "/storage/emulated/0/Download/证道:/opt/zhengdao"),
            ProotLaunchRules.storeRootBindArg("/storage/emulated/0/Download/证道", "/opt/zhengdao"),
        )
    }

    @Test
    fun `storeRootBindArg_换挂载点时guest侧跟着换`() {
        assertEquals(
            listOf("-b", "/storage/emulated/0/Download/证道:/opt/other"),
            ProotLaunchRules.storeRootBindArg("/storage/emulated/0/Download/证道", "/opt/other"),
        )
    }

    @Test
    fun `parseMemLimitMb_正常数字原样解析`() {
        assertEquals(512, ProotLaunchRules.parseMemLimitMb("512"))
        assertEquals(1, ProotLaunchRules.parseMemLimitMb("1"))
        assertEquals(0, ProotLaunchRules.parseMemLimitMb("0"))
    }

    @Test
    fun `parseMemLimitMb_读不到与非法值都归零_默认关闭`() {
        assertEquals(0, ProotLaunchRules.parseMemLimitMb(null))
        assertEquals(0, ProotLaunchRules.parseMemLimitMb(""))
        assertEquals(0, ProotLaunchRules.parseMemLimitMb("abc"))
        assertEquals(0, ProotLaunchRules.parseMemLimitMb("1.5"))
        assertEquals(0, ProotLaunchRules.parseMemLimitMb("512MB"))
        assertEquals(0, ProotLaunchRules.parseMemLimitMb(" "))
    }

    @Test
    fun `parseMemLimitMb_负值按toIntOrNull原样返回_由前缀规则兜底成空`() {
        // 与原实现一致：解析层不夹负值，负值在 memLimitPrefix 处落地为空串
        assertEquals(-3, ProotLaunchRules.parseMemLimitMb("-3"))
        assertEquals("", ProotLaunchRules.memLimitPrefix(-3))
    }

    @Test
    fun `memLimitPrefix_开启时才生成ulimit前缀`() {
        assertEquals("ulimit -v 524288; ", ProotLaunchRules.memLimitPrefix(512))
        assertEquals("ulimit -v 1048576; ", ProotLaunchRules.memLimitPrefix(1024))
    }

    @Test
    fun `memLimitPrefix_关闭与零值一律空串`() {
        assertEquals("", ProotLaunchRules.memLimitPrefix(0))
        assertEquals("", ProotLaunchRules.memLimitPrefix(-1))
    }

    @Test
    fun `memLimitPrefix_MB换算成KB倍乘1024`() {
        // ulimit -v 的单位是 KB：memLimitMb * 1024
        assertEquals("ulimit -v 1024; ", ProotLaunchRules.memLimitPrefix(1))
        assertEquals("ulimit -v 10240; ", ProotLaunchRules.memLimitPrefix(10))
    }

    @Test
    fun `guestCommandArgs_主会话先kill-server再attach_orphan_server可恢复`() {
        assertEquals(
            listOf(
                "/bin/bash",
                "-lc",
                "tmux kill-server 2>/dev/null; exec tmux new-session -A -s zhengdao",
            ),
            ProotLaunchRules.guestCommandArgs("zhengdao", "", true),
        )
    }

    @Test
    fun `guestCommandArgs_副会话不kill直接attach_or_create`() {
        assertEquals(
            listOf(
                "/bin/bash",
                "-lc",
                "exec tmux new-session -A -s 'taiji' /bin/bash -l",
            ),
            ProotLaunchRules.guestCommandArgs("taiji", "", true),
        )
        // 名字恰好等于主会话才算主会话；"zhengdao2" 是副会话
        assertEquals(
            listOf(
                "/bin/bash",
                "-lc",
                "exec tmux new-session -A -s 'zhengdao2' /bin/bash -l",
            ),
            ProotLaunchRules.guestCommandArgs("zhengdao2", "", true),
        )
    }

    @Test
    fun `guestCommandArgs_没有tmux降级为裸bash`() {
        assertEquals(
            listOf("/bin/bash", "-lc", "exec /bin/bash -l"),
            ProotLaunchRules.guestCommandArgs("zhengdao", "", false),
        )
    }

    @Test
    fun `guestCommandArgs_内存前缀原样拼在命令最前面`() {
        assertEquals(
            listOf(
                "/bin/bash",
                "-lc",
                "ulimit -v 524288; exec tmux new-session -A -s 'taiji' /bin/bash -l",
            ),
            ProotLaunchRules.guestCommandArgs("taiji", "ulimit -v 524288; ", true),
        )
        assertEquals(
            listOf(
                "/bin/bash",
                "-lc",
                "ulimit -v 1048576; exec /bin/bash -l",
            ),
            ProotLaunchRules.guestCommandArgs("zhengdao", "ulimit -v 1048576; ", false),
        )
    }

    @Test
    fun `guestCommandArgs_会话名带单引号时按原样嵌入_白名单校验在调用点`() {
        // helper 不做校验（isLegalSessionName 在 launcher 前置）；这里只钉住
        // 「原样嵌入」的等值语义——嵌入方式与改前一致（单引号包裹）。
        assertEquals(
            listOf(
                "/bin/bash",
                "-lc",
                "exec tmux new-session -A -s 'a b' /bin/bash -l",
            ),
            ProotLaunchRules.guestCommandArgs("a b", "", true),
        )
    }

    @Test
    fun `sharedStorageAllowed_真值表四种组合全对`() {
        assertTrue(ProotLaunchRules.sharedStorageAllowed(true, true))
        assertFalse("没存储权限不该 bind", ProotLaunchRules.sharedStorageAllowed(false, true))
        assertFalse("仅私有模式不该 bind", ProotLaunchRules.sharedStorageAllowed(true, false))
        assertFalse(ProotLaunchRules.sharedStorageAllowed(false, false))
    }

    @Test
    fun `rootfsReady_四项全就绪才走Debian`() {
        assertTrue(
            ProotLaunchRules.rootfsReady(
                prootIsFile = true, prootExecutable = true,
                loaderIsFile = true, markerIsFile = true,
            )
        )
    }

    @Test
    fun `rootfsReady_任何一项缺失都不就绪`() {
        assertFalse(ProotLaunchRules.rootfsReady(false, true, true, true))
        assertFalse(ProotLaunchRules.rootfsReady(true, false, true, true))
        assertFalse("loader 缺失（jniLibs 化后必须显式指）", ProotLaunchRules.rootfsReady(true, true, false, true))
        assertFalse("安装标记缺失＝完全没装环境", ProotLaunchRules.rootfsReady(true, true, true, false))
        assertFalse(ProotLaunchRules.rootfsReady(false, false, false, false))
    }

    @Test
    fun `fallbackPlan_相同输入构造结果稳定可重复`() {
        val filesDir = File("/data/user/0/com.example.zhengdao/files")
        val cacheDir = File("/data/user/0/com.example.zhengdao/cache")
        val a = ProotLaunchRules.fallbackPlan(filesDir, cacheDir)
        val b = ProotLaunchRules.fallbackPlan(filesDir, cacheDir)
        // LaunchPlan 用数组字段，equals 按引用比——逐字段比内容
        assertEquals(a.cmd, b.cmd)
        assertEquals(a.args.toList(), b.args.toList())
        assertEquals(a.env.toList(), b.env.toList())
        assertEquals(a.isFallback, b.isFallback)
    }

    // ── 三、源码门禁（结构不回归） ────────────────────────────────────────

    @Test
    fun `launcher_不得残留env表第二份实现`() {
        val launcher = launcherSrc()
        for (lit in listOf(
            "DISABLE_TELEMETRY=1",
            "BUN_FEATURE_FLAG_DISABLE_IPV6=1",
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1",
            "PATH=/root/.local/bin:/usr/local/sbin",
        )) {
            assertFalse("launcher 里还留着 env 字面量「$lit」——抽取不完整或有人加回了第二份实现", launcher.contains(lit))
        }
        assertFalse("launcher 里还有 env 表构造函数", launcher.contains("val env = mutableListOf("))
        // TZ 内联选择也不在了（zoneinfo 文件查询可以留，选择逻辑必须走 helper）
        assertFalse("launcher 里还有内联 TZ 二选一", launcher.contains("else \"CST-8\""))
        // helper 侧恰好一份
        val rules = rulesSrc()
        for (lit in listOf(
            "DISABLE_TELEMETRY=1",
            "BUN_FEATURE_FLAG_DISABLE_IPV6=1",
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1",
            "PATH=/root/.local/bin:/usr/local/sbin",
        )) {
            assertEquals("helper 里「$lit」应恰好一份", 1, rules.split(lit).size - 1)
        }
    }

    @Test
    fun `launcher_不得残留fallbackPlan私有定义_两个早返点都走helper`() {
        val launcher = launcherSrc()
        assertFalse("私有 fallbackPlan 定义应已删除", launcher.contains("private fun fallbackPlan"))
        assertEquals(
            "两个早返点（运行库准备失败 :149、rootfs 未就绪 :234）都必须走 helper",
            2,
            launcher.split("ProotLaunchRules.fallbackPlan(files, context.cacheDir)").size - 1,
        )
        val rules = rulesSrc()
        assertEquals("helper 里 fallbackPlan 定义应恰好一份", 1, rules.split("fun fallbackPlan(").size - 1)
    }

    @Test
    fun `launcher_不得残留内存前缀与tmux收尾命令的字面量实现`() {
        val launcher = launcherSrc()
        assertFalse("内存前缀实现应已搬走", launcher.contains("\"ulimit -v \${memLimitMb * 1024}; \""))
        assertFalse("tmux 主会话命令实现应已搬走", launcher.contains("\${memPrefix}tmux kill-server"))
        assertFalse("tmux 副会话命令实现应已搬走", launcher.contains("\${memPrefix}exec tmux new-session -A -s '"))
        assertFalse("副会话 paneCmd 变量应已删掉", launcher.contains("paneCmd"))
        // 固定段参数串（arrayOf 形态）也应搬走
        assertFalse("固定段参数串实现应已搬走", launcher.contains("\"--kill-on-exit\","))
        assertFalse("共享存储 bind 实现应已搬走", launcher.contains("val shared = \"/storage/emulated/0\""))
    }

    @Test
    fun `launcher_15处helper调用点全部在位`() {
        val launcher = launcherSrc()
        val expectCalls = mapOf(
            "ProotLaunchRules.tzNameFor(" to 1,
            "ProotLaunchRules.envEntries(" to 1,
            "ProotLaunchRules.baseArgs(" to 1,
            "ProotLaunchRules.sharedStorageBindArgs()" to 1,
            "ProotLaunchRules.sharedStorageAllowed(" to 2,
            "ProotLaunchRules.guestSymlinkTargetFor(" to 1,
            "ProotLaunchRules.workspaceBindArg(" to 1,
            "ProotLaunchRules.storeRootBindArg(" to 1,
            "ProotLaunchRules.parseMemLimitMb(" to 1,
            "ProotLaunchRules.memLimitPrefix(" to 1,
            "ProotLaunchRules.guestCommandArgs(" to 1,
            "ProotLaunchRules.rootfsReady(" to 1,
        )
        for ((call, want) in expectCalls) {
            assertEquals("调用点 $call 数量不对", want, launcher.split(call).size - 1)
        }
        // fallbackPlan 的两处在上一断言里单独核；这里核总数 15 次调用
        val total = expectCalls.values.sum() + 2
        val actual = Regex("ProotLaunchRules\\.(tzNameFor|envEntries|baseArgs|sharedStorageBindArgs|sharedStorageAllowed|guestSymlinkTargetFor|workspaceBindArg|storeRootBindArg|parseMemLimitMb|memLimitPrefix|guestCommandArgs|rootfsReady|fallbackPlan)\\(")
            .findAll(launcher).count()
        assertEquals("helper 调用总数不对（另有 2 处 KDoc 提及不算调用）", total, actual)
    }

    @Test
    fun `helper_必须是纯计算_不碰任何Android副作用`() {
        val code = codeOnly(rulesSrc())
        for (bad in listOf(
            "Context", "RunLog", "writeText", "mkdirs", "Os.symlink",
            "AppPrefs", "synchronized", "android.system", "IssueCenter",
        )) {
            assertFalse("helper 代码里出现了副作用符号「$bad」——纯逻辑层被污染", code.contains(bad))
        }
    }

    @Test
    fun `已纯函数未搬_三件仍在launcher且helper没有第二份`() {
        val launcher = launcherSrc()
        val rules = rulesSrc()
        for (fn in listOf("isLegalSessionName", "tmuxConfAppend", "sharedCacheBindings")) {
            assertTrue("$fn 应仍在 launcher", launcher.contains("fun $fn"))
            assertFalse("$fn 不该被搬进 helper（避免两份实现）", rules.contains("fun $fn"))
        }
    }

    @Test
    fun `副作用编排原位_持锁写盘日志软链文件查询都还在launcher`() {
        val launcher = launcherSrc()
        assertTrue("持锁调用必须留在 launcher", launcher.contains("withPrepareLock"))
        assertTrue("buildLaunchPlanLocked 本体必须留在 launcher", launcher.contains("private fun buildLaunchPlanLocked"))
        assertTrue("工作区软链刷新失败日志必须留在 launcher", launcher.contains("RunLog.log(\"工作区软链刷新失败"))
        assertTrue("软链刷新本体必须留在 launcher", launcher.contains("android.system.Os.symlink(target, link.absolutePath)"))
        assertTrue("横幅写盘必须留在 launcher", launcher.contains(".zhengdao-banner-pending"))
        assertTrue("zoneinfo 文件查询必须留在 launcher", launcher.contains("File(rootfsDir, \"usr/share/zoneinfo/Asia/Shanghai\").isFile"))
        assertTrue("tmux 文件查询必须留在 launcher", launcher.contains("File(rootfsDir, \"usr/bin/tmux\").isFile"))
        assertTrue("内存上限日志必须留在 launcher", launcher.contains("RunLog.log(\"guest 内存上限"))
    }
}
