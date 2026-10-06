// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 本文件只做一件事：按 proot 官方文档记载的通用参数，组装启动命令与环境变量。
// 所有参数均为 proot 上游（proot-me/proot）文档公开的选项，语义见行内注释；
// 进程创建与 IO 由 Termux 官方 terminal-emulator 的 native 半边（libtermux.so）完成
// （v3.9：自研 Pty.kt / pty.c 已随终端渲染层切换而退役删除，历史实现保留在 git 中）。
package com.example.zhengdao.terminal

import android.content.Context
import com.example.zhengdao.rootfs.RunLog
import java.io.File
import java.io.IOException

object ProotLauncher {

    /**
     * 默认 RootFS 下载地址：latest 发布永远指向最新一次构建的产物，
     * 因此这条地址长期有效。M3 起改为由 ed25519 验签的 manifest 动态下发。
     */
    const val DEFAULT_ROOTFS_URL =
        "https://github.com/pisces19860207/zhengdao/releases/download/latest/debian-13.7-base-arm64.tar.zst"

    /**
     * 一次启动的完整计划。
     * @param cmd  宿主侧可直接 execve 的程序路径（proot 模式下 = proot 二进制；回退模式 = /system/bin/sh）
     * @param args 命令行参数（proot 模式下末尾附带 guest 内要执行的命令 /bin/bash -l）
     * @param env  传给子进程的环境变量（proot 会透传给 guest，guest 内进程全部可见）
     */
    data class LaunchPlan(
        val cmd: String,
        val args: Array<String>,
        val env: Array<String>,
        val isFallback: Boolean = false,
        /** 会话是否由 tmux 保持（决定绿点分屏按钮是否可用） */
        val usesTmux: Boolean = false,
    )

    /**
     * 手机存储可直通判定。⚠️ targetSdk 28 走 WRITE_EXTERNAL_STORAGE 运行时权限
     * （Android 11+ 对 target≤29 的 App 自动保持 legacy 存储视图，授权即可写共享
     * 存储）；「所有文件访问」开关只对 target≥30 的 App 存在，本 App 永远拿不到，
     * 不能用作判定（2026-10-04 修复：此前用 isExternalStorageManager 恒 false）。
     */
    fun storageGranted(context: Context): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 流式计算文件 SHA-256（版本固定校验用）。 */
    private fun sha256Of(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 组装启动计划：
     *  - 已安装 Debian 13.7 环境（rootfs 目录存在且带完成标记）→ 经 proot 启动 guest bash；
     *  - 未安装 → 回退到系统自带 shell，App 端行为完全一致，用户无感降级。
     */
    fun buildLaunchPlan(context: Context, tmuxSession: String = "zhengdao"): LaunchPlan {
        val files = context.filesDir
        val rootfsDir = File(files, "rootfs")

        // 2026-10-04 实测定论：自编译上游 proot 缺少 Android 适配（App 域内加载
        // guest 时静默退出 255，见设计文档 §4），本机实测只有带 Android 补丁的
        // Termux proot fork（GPL）能在 App 域内正常工作。基线切换为该 fork 的
        // 官方发行二进制（聚合分发，GPL 合规；PROVENANCE.md 有登记），随 APK
        // assets 内置，启动时释放到 files/termux-proot/ 并补执行位。
        // files/ 下的可执行文件 App 域可直接 execve（jnilLibs 方案留档不再使用）。
        val tpDir = File(files, "termux-proot").apply { mkdirs() }
        val prootBin = File(tpDir, "proot")
        val loaderBin = File(tpDir, "loader")
        // （2026-10-05）旧自编译 proot 的 jniLibs 方案（libproot.so 入 nativeLibraryDir）
        // 已随基线切换废弃并删除：本机实测它在 App 域内加载 guest 静默退出 255，
        // 现只走 assets/proot 的 Termux 官方发行二进制（files/termux-proot/proot）。
        // 该 .so 同时是 GPL 传染隐患（形似库、易被误 loadLibrary），故彻底移除。
        // 版本固定清单（asset 名 → 释放文件名 → SHA256；来源与版本见 PROVENANCE.md /
        // THIRD-PARTY-LICENSES.md，Termux proot 5.1.107.96 aarch64 官方构建产物）。
        // 兜底路径：已释放且 SHA256 校验通过 → 跳过（修环境/重装不重复释放）；
        // 缺失或损坏 → 从 assets 重新释放并补权限。
        val pinned = listOf(
            Triple("tproot", "proot", "1545b85b312505db6eb6908ff8b2ded0a77a3bd689c50ae85aa7c1d8445dd717"),
            Triple("tloader", "loader", "cbdef0e652c2b78af25d867e1719fdebbb0915e25aae2dd35b3b5c1835f6b551"),
            Triple("libtalloc.so", "libtalloc.so.2", "742b438c4d09e276985a61d44164c9de207b6d8cc268f3018cb3001961bcb309"),
            Triple("libandroid-shmem.so", "libandroid-shmem.so", "84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731"),
        )
        for ((asset, target, expectedSha) in pinned) {
            val dst = File(tpDir, target)
            if (dst.isFile && sha256Of(dst).equals(expectedSha, ignoreCase = true)) continue // 已就位
            context.assets.open("proot/$asset").use { input ->
                dst.outputStream().use { input.copyTo(it) }
            }
            val actual = sha256Of(dst)
            if (!actual.equals(expectedSha, ignoreCase = true)) {
                RunLog.log("严重: $target SHA256 不匹配（actual=$actual），已删除损坏副本")
                dst.delete()
                continue
            }
            val mode = if (target == "proot" || target == "loader") 493 else 420 // 0755 / 0644
            try { android.system.Os.chmod(dst.absolutePath, mode) } catch (_: Throwable) {}
            RunLog.log("已释放: $target（SHA256 校验通过）")
        }
        RunLog.log(
            "启动决策: proot=" + prootBin.isFile + " loader=" + loaderBin.isFile +
                " marker=" + File(rootfsDir, ".zhengdao-rootfs-ok").isFile
        )

        val installMarker = File(rootfsDir, ".zhengdao-rootfs-ok")
        val rootfsReady = prootBin.isFile && prootBin.canExecute() && loaderBin.isFile && installMarker.isFile

        if (!rootfsReady) {
            return fallbackPlan(files, context.cacheDir)
        }

        // DNS 兜底（设计文档 §4：proot 内没有 systemd-resolved，缺 resolv.conf 就是
        // "下载得动、上不了网"的第一大故障；App 每次启动前确保存在）。
        // 实现收口在 EnvSelfHeal（P7：与首页「环境体检→修复」共用同一份）
        EnvSelfHeal.ensureDnsFiles(File(rootfsDir, "etc/resolv.conf"), File(rootfsDir, "etc/hosts"))
        // 时区同步（用户反馈：tmux 状态栏时钟比手机慢 8 小时）：rootfs 镜像构建时
        // /etc/localtime 指向 Etc/UTC（实测 2026-10-05），guest 内 date/tmux 全按 UTC
        // 显示。启动前把 /etc/localtime 改指 Asia/Shanghai 并补 /etc/timezone；
        // 写不进去不阻断启动，下面 TZ 环境变量兜底
        runCatching { EnvSelfHeal.ensureTimezone(rootfsDir) }

        // home 与系统分离（设计文档 §8）：用户数据放 App 私有目录，bind 挂到 guest 的 /root，
        // 这样「修复环境」重解压系统层时不碰用户数据
        val homeDir = File(files, "home").apply { mkdirs() }
        val prootTmp = File(files, "proot-tmp").apply { mkdirs() }

        // 工作区解析（0.6 显性化）：唯一真相源 Workspace；bind/软链/人设/脚本落点共用。
        // 在所有预置块之前解析——AGENTS.md 人设的文件地图需要当前映射。
        val wsHost = Workspace.hostDir(context)
        val wsShared = Workspace.isShared(context)

        // hermes 命令立即可用（用户反馈：装完敲 hermes 没反应）：安装器把命令发布在
        // /root/.local/bin（home 层），但**早已存在的 shell 的 PATH 是启动时的快照**，
        // 拿不到后装的目录。/usr/local/bin 天然在所有 shell 的 PATH 里且属系统层——
        // 软链过去，新旧 shell 全覆盖。未装 hermes 时跳过，不留悬空链接。
        runCatching {
            if (File(homeDir, ".local/bin/hermes").isFile) {
                val link = File(rootfsDir, "usr/local/bin/hermes")
                if (!link.exists()) {
                    android.system.Os.symlink("/root/.local/bin/hermes", link.absolutePath)
                }
            }
        }

        // hermes uv 包装器接管（update 韧性）：hermes update 若拉到新的 pinned uv
        // 版本会新开 tools/uv-<ver> 目录、放下真实二进制——裸奔一次就硬链接失败。
        // 每次启动统一巡检：ELF 真身挪为 uv.real、原路径放包装器（实现收口 EnvSelfHeal）
        runCatching { EnvSelfHeal.ensureHermesUvWrappers(homeDir) }
        // 细光标（用户反馈块太粗）：每个 login shell 启动时发 DECSCUSR 6（bar 闪烁）。
        // tmux 可能随后覆盖，profile 方式让每个 shell（含分屏新 pane）重新声明。
        runCatching {
            val profileDir2 = File(rootfsDir, "etc/profile.d")
            if (profileDir2.isDirectory || profileDir2.mkdirs()) {
                val f = File(profileDir2, "zz-cursor-bar.sh")
                if (!f.isFile) {
                    f.writeText("printf '\\u001B[6 q'\n")
                }
            }
        }
        // npm 国内镜像（login shell 经 /etc/profile.d 自动生效）：官方 registry 从国内
        // 拉 Agent 及其二进制要 2-4 分钟，npmmirror 通常几十秒。写失败不阻断。
        runCatching {
            val profileDir = File(rootfsDir, "etc/profile.d")
            if (profileDir.isDirectory || profileDir.mkdirs()) {
                val f = File(profileDir, "zz-npm-registry.sh")
                if (!f.isFile || !f.readText().contains("npmmirror")) {
                    f.writeText("export NPM_CONFIG_REGISTRY=https://registry.npmmirror.com\n")
                }
            }
        }
        // uv 系统级配置（belt；主防护是 hermes 内嵌 uv 包装器巡检）：
        // hermes 的 install.sh 全局 UV_NO_CONFIG=1 且 pm 剥 UV_* 环境变量、重定向
        // XDG_CONFIG_HOME——用户级 uv.toml 全失效。/etc/uv/uv.toml 是 uv 官方配置
        // 发现层级里的系统级路径；UV_NO_CONFIG 未传到 pm 的 uv 子进程时它会被读到
        // → copy 模式兜底（实现收口 EnvSelfHeal）
        runCatching { EnvSelfHeal.ensureUvConfig(rootfsDir) }
        // home 与系统分离（设计文档 §8）：用户数据放 App 私有目录，bind 挂到 guest 的 /root，
        // 这样「修复环境」重解压系统层时不碰用户数据（homeDir/prootTmp 已在前面的
        // hermes 巡检块之前定义）

        // git 克隆加速（用户实测：v2vpn 下 github 克隆仍极慢，Hermes 安装卡在克隆）：
        // /root/.gitconfig 把 github.com 替换为可用的加速镜像（2026-10-05 实测 gh-proxy.com
        // 200/1.5s）。镜像失效时删除 /root/.gitconfig 即恢复直连。
        runCatching {
            val gitconfig = File(homeDir, ".gitconfig")
            if (!gitconfig.isFile) {
                gitconfig.writeText(
                    "[url \"https://gh-proxy.com/https://github.com/\"]\n" +
                        "\tinsteadOf = https://github.com/\n"
                )
                RunLog.log("git 镜像已配置（gh-proxy.com）")
            }
        }
        // OpenCode 调优（性能）：snapshot 会在每次工具调用时跑 git 子进程，
        // proot 下子进程开销被放大数倍 → 输入/响应明显卡顿。默认关闭；
        // 需要 undo 功能的用户可手动改回 true（牺牲性能）。
        // OpenCode 预置（字段级合并，0.5 步）：
        // - snapshot=false：性能（每次工具调用省 git 子进程，proot 下被放大数倍）
        // - plugin opencode-mem：跨会话记忆（用户痛点"重开就忘"；免 Key 走免费模型，
        //   用户确认可用；同名包有两个，认准 npm 的 tickernelz/opencode-mem）
        // - AGENTS.md：人设 + 文件地图（治"忘了自己在手机里/找不到文件"）。
        // 已有配置/文件时按字段合并或跳过，绝不覆盖用户自有内容。
        runCatching {
            val cfgDir = File(homeDir, ".config/opencode")
            if (cfgDir.isDirectory || cfgDir.mkdirs()) {
                val f = File(cfgDir, "opencode.json")
                val obj = if (f.isFile) runCatching {
                    org.json.JSONObject(f.readText())
                }.getOrElse {
                    RunLog.log("OpenCode 配置解析失败，按空配置重建（原内容已损坏）")
                    org.json.JSONObject()
                } else org.json.JSONObject()
                var changed = !f.isFile
                if (!obj.has("snapshot")) { obj.put("snapshot", false); changed = true }
                // autoupdate=false（用户定稿：默认不打扰，更新走设置页手动检查）
                if (!obj.has("autoupdate")) { obj.put("autoupdate", false); changed = true }
                val plugins = obj.optJSONArray("plugin") ?: org.json.JSONArray().also {
                    obj.put("plugin", it); changed = true
                }
                if (plugins.toString().contains("opencode-mem").not()) {
                    plugins.put("opencode-mem"); changed = true
                }
                if (changed) {
                    f.writeText(obj.toString(2))
                    RunLog.log("OpenCode 配置已合并（snapshot=false + opencode-mem 插件）")
                }
                // 人设：opencode 原生读取 <XDG_CONFIG_HOME>/opencode/AGENTS.md 作为全局规则。
                // 文件地图随工作区设置动态更新（0.6）：映射变化才重写，平时不动用户文件。
                // 双份：终端默认实例（~/.config/opencode）+ 太极实例（/root/.zhengdao/taiji/config）。
                val wsPath = wsHost.absolutePath
                val wsNote = if (wsShared) "手机文件管理器直接可见、可自由删除；卸载证道后该文件夹仍会保留（产出不丢）" else "应用专属目录，随应用卸载自动删除"
                val persona = (
                    "# 证道运行环境说明（每次对话开始前必读）\n\n" +
                        "## 你的身份\n" +
                        "你运行在用户的安卓手机上——一个由证道 App 通过 proot 运行的 Debian 13.7 环境。\n" +
                        "禁止声称「我不在手机上」「我没有文件系统」；你就在手机里，文件就在下面这些路径。\n\n" +
                        "## 文件地图\n" +
                        "- /workspace —— **产出与边界区**：Agent 的产出都放这里（手机侧：$wsPath；$wsNote）。用户在这里找产出、在这里自由删除\n" +
                        "- /sdcard —— 共享存储整体可读可写，用于查找资料；**产出约定只进 /workspace**，不要把共享存储其他位置当草稿区乱写\n" +
                        "- /mnt/phone —— 手机文件夹镜像（备用；仅当用户在证道设置里配置过「手机文件夹同步」才有内容）\n" +
                        "- /root —— 你的 home；各 Agent 配置在此（~/.config/opencode、~/.hermes 等）\n\n" +
                        "## 能力边界\n" +
                        "- 无 root，不要尝试需要 root 的操作\n" +
                        "- 禁止执行 apt upgrade（会损坏环境）；装依赖用 pip / npm\n" +
                        "- 找不到用户文件时：先 ls /workspace 和 /sdcard/Download，把已搜索的路径列出来再下结论，不要直接放弃\n"
                    )
                val prefsUi = com.example.zhengdao.ui.Settings.prefs(context)
                // 终端默认实例
                val agents = File(cfgDir, "AGENTS.md")
                val lastPersonaWs = prefsUi.getString("agents_md_ws", null)
                if (!agents.isFile || lastPersonaWs != wsPath) {
                    agents.writeText(persona)
                    prefsUi.edit().putString("agents_md_ws", wsPath).apply()
                    RunLog.log("AGENTS.md 已更新（工作区映射: $wsPath）")
                }
                // 太极实例（XDG 隔离，见 taiji 脚本）
                val taijiCfg = File(homeDir, ".zhengdao/taiji/config/opencode")
                if (taijiCfg.isDirectory || taijiCfg.mkdirs()) {
                    val agentsTaiji = File(taijiCfg, "AGENTS.md")
                    val lastPersonaWsT = prefsUi.getString("agents_md_ws_taiji", null)
                    if (!agentsTaiji.isFile || lastPersonaWsT != wsPath) {
                        agentsTaiji.writeText(persona)
                        prefsUi.edit().putString("agents_md_ws_taiji", wsPath).apply()
                        RunLog.log("太极实例 AGENTS.md 已更新（工作区映射: $wsPath）")
                    }
                    // taiji 实例的 opencode.json：同样关 snapshot/autoupdate + 记忆插件
                    val fT = File(taijiCfg, "opencode.json")
                    val objT = if (fT.isFile) runCatching {
                        org.json.JSONObject(fT.readText())
                    }.getOrElse { org.json.JSONObject() } else org.json.JSONObject()
                    var changedT = !fT.isFile
                    if (!objT.has("snapshot")) { objT.put("snapshot", false); changedT = true }
                    if (!objT.has("autoupdate")) { objT.put("autoupdate", false); changedT = true }
                    val pluginsT = objT.optJSONArray("plugin") ?: org.json.JSONArray().also {
                        objT.put("plugin", it); changedT = true
                    }
                    if (pluginsT.toString().contains("opencode-mem").not()) {
                        pluginsT.put("opencode-mem"); changedT = true
                    }
                    if (changedT) {
                        fT.writeText(objT.toString(2))
                        RunLog.log("太极实例 opencode.json 已预置（记忆插件同款）")
                    }
                }
            }
        }

        // taiji 启动脚本（太极 Tab 用，用户定稿）：XDG 四目录隔离 → 与洞天/终端的
        // opencode（默认 XDG）物理隔离；同一个 /usr/local/bin/opencode 二进制。
        // 放 /usr/local/bin（PATH 内），太极 Tab 的 autocmd 就一个词：taiji。
        runCatching {
            val taiji = File(rootfsDir, "usr/local/bin/taiji")
            val script = "#!/bin/sh\n" +
                "# 证道太极：OpenCode 独立实例（XDG 隔离，与终端默认实例互不干扰）\n" +
                "export XDG_CONFIG_HOME=/root/.zhengdao/taiji/config\n" +
                "export XDG_DATA_HOME=/root/.zhengdao/taiji/data\n" +
                "export XDG_CACHE_HOME=/root/.zhengdao/taiji/cache\n" +
                "export XDG_STATE_HOME=/root/.zhengdao/taiji/state\n" +
                "mkdir -p /root/.zhengdao/taiji/config /root/.zhengdao/taiji/data /root/.zhengdao/taiji/cache /root/.zhengdao/taiji/state\n" +
                "exec /usr/local/bin/opencode\n"
            if (!taiji.isFile || !taiji.readText().contains("XDG_CONFIG_HOME=/root/.zhengdao/taiji")) {
                taiji.writeText(script)
                runCatching { android.system.Os.chmod(taiji.absolutePath, 493) }
                RunLog.log("taiji 启动脚本已预置（XDG 隔离的 OpenCode 实例）")
            }
        }

        // tmux 预置（2026-10-05 补）：开启鼠标支持，使**触摸滑动能滚动历史**。
        // 背景见 TerminalView.onScroll 的定制注释：tmux 采用全屏重绘，不产生本地回滚
        // 缓冲（实测 histRows=0），必须把触摸滑动转成滚轮事件、且 tmux 端开启 mouse，
        // 才能滚动 pane 自己的历史。已有配置时按需追加，不覆盖用户自有设置。
        runCatching {
            val f = File(homeDir, ".tmux.conf")
            val cur = if (f.isFile) f.readText() else ""
            if (!Regex("(?m)^\\s*set(-option)?\\s+-g\\s+mouse\\b").containsMatchIn(cur)) {
                f.writeText((if (cur.isBlank()) "" else cur.trimEnd() + "\n") + "set -g mouse on\n")
                RunLog.log("tmux 配置已补：set -g mouse on（触摸滑动可滚动历史）")
            }
        }

        // TZ（双保险的第二层）：tmux server / date / Node 等都读它。带 zoneinfo 的
        // 环境用地理名；万一哪个 rootfs 变体没装 zoneinfo，glibc 解析不了地理名，
        // 就退到 POSIX 自包含写法 CST-8（= UTC+8，POSIX 偏移符号西正东负，零依赖）
        val tzName = if (File(rootfsDir, "usr/share/zoneinfo/Asia/Shanghai").isFile) "Asia/Shanghai" else "CST-8"
        val env = mutableListOf(
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME=/root",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "TZ=$tzName",
            // proot 自身需要：临时目录必须指向 App 可写路径（部分机型 /tmp 不可写）
            "PROOT_TMP_DIR=${prootTmp.absolutePath}",
            // ⚠️ 不要设 PROOT_NO_SECCOMP=1：这台 Honor/安卓16 实测证明，
            // 关闭 seccomp 加速后（纯 PTRACE_SYSCALL 模式）guest 会静默退出 255；
            // 默认的 seccomp 加速模式反而是本机唯一稳定的工作模式（2026-10-04 实测矩阵）。
            // 防硬链接报错双保险（实测坑 #4）：proot --link2symlink 之外，uv 也强制 copy 模式
            "UV_LINK_MODE=copy",
            // 明确告知 uv 系统解释器位置（Debian 13.7 自带 Python 3.13，不做版本管理）
            "UV_PYTHON=/usr/bin/python3",
            // Termux proot 的依赖库与外部 loader 定位（其 fork 的 loader 为独立文件）
            "LD_LIBRARY_PATH=${tpDir.absolutePath}", // libtalloc.so.2 / libandroid-shmem.so 在此目录
            "PROOT_LOADER=${File(files, "termux-proot/loader").absolutePath}",
        )

        val args = mutableListOf(
            prootBin.absolutePath,   // 宿主侧 execve 的目标：Termux fork 的 proot（files/ 下可执行）
            "--kill-on-exit",        // proot 退出时清掉 guest 内的全部进程，防孤儿
            "--link2symlink",        // 硬链接失败自动降级为符号链接（实测坑 #4：SELinux 拒绝非 root 硬链接）
            "-r", rootfsDir.absolutePath,          // guest 根目录
            "-0",                    // 假装 root（guest 内 uid/gid 显示为 0，apt 等工具可用）
            "-w", "/root",           // guest 内起始工作目录
            "-b", "/dev",            // 绑定设备树
            "-b", "/proc",           // 绑定进程信息
            "-b", "/sys",            // 绑定系统信息
            "-b", "${homeDir.absolutePath}:/root", // 用户 home（与系统层分离）
        )
        // 手机存储直通：共享存储绑进 guest 的相同路径 + /sdcard 视图。
        // 工作区解析已在预置块前完成（wsHost/wsShared，统一走 Workspace）
        if (storageGranted(context) && wsShared) {
            val shared = "/storage/emulated/0"
            args.addAll(arrayOf("-b", "$shared:$shared", "-b", "/sdcard:/sdcard"))
            // 工作区软链跟随当前工作区（guest 内 /root/工作区 直达；变更时重建）
            try {
                val link = File(context.filesDir, "home/工作区")
                link.parentFile?.mkdirs()
                val target = "/sdcard" + wsHost.absolutePath.removePrefix("/storage/emulated/0")
                val cur = runCatching { android.system.Os.readlink(link.absolutePath) }.getOrNull()
                if (cur != target) {
                    link.delete()
                    android.system.Os.symlink(target, link.absolutePath)
                }
            } catch (_: Throwable) {
            }
        }
        RunLog.log(
            "工作区: /workspace <- ${wsHost.absolutePath}" +
                if (wsShared) "（共享存储，卸载保留）" else "（仅私有，随卸载删除）"
        )
        args.addAll(arrayOf("-b", "${wsHost.absolutePath}:/workspace"))

        // 手机文件夹镜像（Plan B，2026-10-06）：SAF 镜像同步的落点。
        // ⚠️ 定位更正（同日勘误 E-005）：此前"/sdcard 直连不可用"的结论是 run-as
        // 探针的方法论假象（runas_app SELinux 域被 FUSE 拒，不代表 App 真身）——
        // 真身自 READ 帽子摘除（d414dca）后 /sdcard 读写全通，本镜像降级为
        // **备用方案**（个别 ROM 传统视图异常时的兜底），平时无需配置。
        val phoneMirror = File(files, "phone-mirror").apply { mkdirs() }
        args.addAll(arrayOf("-b", "${phoneMirror.absolutePath}:/mnt/phone"))
        RunLog.log("镜像绑定: /mnt/phone <- ${phoneMirror.absolutePath}")

        // guest 命令必须收尾：所有 proot 选项在前（2026-10-04 修复：存储 bind 被追加
        // 到 bash 之后时，bash 会把 bind 参数当脚本路径执行，exit 127）
        // 运行内存上限（用户第四批）：ulimit -v 限制 guest 进程虚拟地址空间，防单个
        // Agent / 编译任务无限膨胀拖垮整机。实测 3GB 下 Node v26 可正常启动（空闲态）。
        // ⚠️ M2 骨架结论（2026-10-04）：虚拟地址空间≠物理内存，Node/V8 真实负载下虚拟
        // 占用远超物理，硬限可能"正常用也崩"——因此默认关闭，交由 M2 的软监控
        // （读 /proc VmRSS + 通知栏警告）负责内存治理。设置页「运行内存上限」可手动开启。
        val memLimitMb = com.example.zhengdao.ui.Settings.prefs(context)
            .getString("guest_mem_limit_mb", "0")?.toIntOrNull() ?: 0
        val memPrefix = if (memLimitMb > 0) "ulimit -v ${memLimitMb * 1024}; " else ""
        // 会话保持（用户第三批）：tmux new-session -A = 有名为 zhengdao 的会话则 attach，
        // 没有则新建——终端断线重进不丢现场；Agent 经 autocmd 注入的命令同样落在会话内。
        // 外层 bash -l 先读 /etc/profile（UV_LINK_MODE 全局生效），再 exec 进 tmux。
        // 宿主侧检查 rootfs 是否带 tmux：旧包没有时降级裸 bash，guest 内不会报 command not found。
        val hasTmux = File(rootfsDir, "usr/bin/tmux").isFile
        if (hasTmux) {
            if (tmuxSession == "zhengdao") {
                // 主会话：kill-server 兜底（孤儿 tmux server 会让 attach 失败）。
                // ⚠️ 仅主会话可 kill——taiji 等副会话与 zhengdao 共存于同一 server，
                // kill 会连带杀掉太极的会话。
                args.addAll(
                    arrayOf(
                        "/bin/bash",
                        "-lc",
                        "${memPrefix}tmux kill-server 2>/dev/null; exec tmux new-session -A -s zhengdao",
                    )
                )
            } else {
                // 副会话（taiji 等）：不 kill、直接 attach-or-create；pane 主程序按会话名分发
                val paneCmd = if (tmuxSession == "taiji") "/usr/local/bin/taiji" else "/bin/bash -l"
                args.addAll(
                    arrayOf(
                        "/bin/bash",
                        "-lc",
                        "${memPrefix}exec tmux new-session -A -s $tmuxSession $paneCmd",
                    )
                )
            }
        } else {
            RunLog.log("rootfs 未带 tmux，降级为裸 bash 会话")
            args.addAll(
                arrayOf(
                    "/bin/bash",
                    "-lc",
                    "${memPrefix}exec /bin/bash -l",
                )
            )
        }
        if (memLimitMb > 0) RunLog.log("guest 内存上限: ${memLimitMb}MB (ulimit -v)")

        // apt 分层策略（用户第四批）：系统层不支持 apt upgrade；语言级依赖走 pip / npm。
        // 横幅告知 + 设置页同步提示，替代对用户行为的假设。
        // 终端网络（用户指定）：DNS 多路已就位；走代理的用户最常见故障是分应用代理
        // 没勾选证道——横幅提示一次，省一轮排障。
        // 启动横幅（用户定稿：只留最必要的两条提示）。文件投递 + profile.d 消费：
        // 每次 fresh 启动把提示文本写入 guest 的 /tmp，pane 内首个 bash 经 profile.d
        // `cat` 后删除。⚠️ 刻意不向 pty 键入命令——键入的命令会被 readline 回显，
        // 两行提示前面拖着三四行命令本体（真机实测，观感差）；文件方案纯输出、
        // 天然无输入竞争。外层 bash -lc 也读 profile.d，但 TMUX 变量未置 →
        // 不打印不消费（外层输出会被 tmux 全屏重绘吞掉，标记还被误耗）。
        // pending 文件每次启动重写（上轮残留只会显示一次本轮内容，不会叠印）。
        runCatching {
            val bannerDir = File(rootfsDir, "etc/profile.d")
            if (bannerDir.isDirectory || bannerDir.mkdirs()) {
                val f = File(bannerDir, "zz-banner.sh")
                if (!f.isFile || !f.readText().contains("zhengdao-banner-pending")) {
                    f.writeText(
                        "# 证道启动横幅：pane 内首个 shell 打印即删（TMUX 变量区分外层 shell）\n" +
                            "if [ -n \"${'$'}{TMUX:-}\" ] && [ -f /tmp/.zhengdao-banner-pending ]; then\n" +
                            "  cat /tmp/.zhengdao-banner-pending\n" +
                            "  rm -f /tmp/.zhengdao-banner-pending\n" +
                            "fi\n"
                    )
                }
            }
            File(rootfsDir, "tmp").mkdirs()
            File(rootfsDir, "tmp/.zhengdao-banner-pending").writeText(
                "[提示] 不要执行 apt upgrade（可能损坏环境）；优先用 pip / npm 装依赖\n" +
                    "[网络] 安装失败时：检查代理 App 的「分应用代理」是否已勾选证道\n"
            )
        } // 写不进去不阻断启动（横幅只是提示）

        return LaunchPlan(
            cmd = prootBin.absolutePath,
            args = args.toTypedArray(),
            env = env.toTypedArray(),
            usesTmux = hasTmux,
        )
    }

    /** hermes 专属 uv 包装器（base64 免转义注入；AgentInstaller 安装时与本次启动
     * 巡检共用）。逻辑：uv.real 缺失 → 从 GitHub pinned 地址下载 uv 0.12.3 arm64
     * （失败换 hermes 官方镜像），SHA256 校验后落位；exec 真身前强制
     * UV_LINK_MODE=copy + TMPDIR 兜底。真身获取失败退回系统 uv。
     * URL/SHA256 与 hermes install.sh 同源。源文件见 build/hermes-uv-wrapper.sh。 */
    const val HERMES_UV_WRAPPER_B64 =
        "IyEvYmluL2Jhc2gKIyB6aGVuZ2RhbyBpbmplY3Rpb24gbGF5ZXI6IGhlcm1lcyBwbSBzdHJpcHMgVVZfKiBlbnYgdmFycyBhbmQgaWdub3JlcyB1diBjb25maWcKIyBmaWxlcyAoVVZfTk9fQ09ORklHPTEpIC0tIHdyYXBwaW5nIGl0cyBvd24gcGlubmVkIHV2IGJpbmFyeSBpcyB0aGUgb25seQojIHJlbGlhYmxlIGluamVjdGlvbiBwb2ludC4gVGhlIHJlYWwgYmluYXJ5IGxpdmVzIG5leHQgdG8gdGhpcyBhcyB1di5yZWFsLgpEPSIkKGNkICIkKGRpcm5hbWUgIiQwIikiICYmIHB3ZCkiClI9IiREL3V2LnJlYWwiCmlmIFsgISAteCAiJFIiIF07IHRoZW4KICBUPSIkKG1rdGVtcCAtZCAyPi9kZXYvbnVsbCB8fCBlY2hvIC90bXAvLnpkdXYuJCQpIgogIG1rZGlyIC1wICIkVCIKICBmb3IgVSBpbiBcCiAgICBodHRwczovL2dpdGh1Yi5jb20vYXN0cmFsLXNoL3V2L3JlbGVhc2VzL2Rvd25sb2FkLzAuMTIuMy91di1hYXJjaDY0LXVua25vd24tbGludXgtZ251LnRhci5neiBcCiAgICBodHRwczovL2hlcm1lcy1hc3NldHMubm91c3Jlc2VhcmNoLmNvbS91cHN0cmVhbS9zaGEyNTYvYmI2NmNiNTJlN2IxODIzYWVkMTE4MzYzMGQ4ZDhlNWM5NTg4NDBkNTg0YTRjNTVlYzEwYTRjZmMxNjhkY2NhMiA7IGRvCiAgICBjdXJsIC1Mc1NmICIkVSIgLW8gIiRUL3V2LnRneiIgJiYgYnJlYWsKICBkb25lCiAgaWYgWyAtZiAiJFQvdXYudGd6IiBdICYmIFsgIiQoc2hhMjU2c3VtICIkVC91di50Z3oiIDI+L2Rldi9udWxsIHwgY3V0IC1kJyAnIC1mMSkiID0gImJiNjZjYjUyZTdiMTgyM2FlZDExODM2MzBkOGQ4ZTVjOTU4ODQwZDU4NGE0YzU1ZWMxMGE0Y2ZjMTY4ZGNjYTIiIF07IHRoZW4KICAgIHRhciAteHpmICIkVC91di50Z3oiIC1DICIkVCIgMj4vZGV2L251bGwKICAgIEY9IiQoZmluZCAiJFQiIC1uYW1lIHV2IC10eXBlIGYgMj4vZGV2L251bGwgfCBoZWFkIC1uMSkiCiAgICBbIC1uICIkRiIgXSAmJiBtdiAiJEYiICIkUiIgJiYgY2htb2QgMDc1NSAiJFIiCiAgZmkKICBybSAtcmYgIiRUIgpmaQppZiBbICEgLXggIiRSIiBdOyB0aGVuCiAgZWNobyAiW3poZW5nZGFvXSB1diB3cmFwcGVyOiBwaW5uZWQgdXYgdW5hdmFpbGFibGUsIGZhbGxpbmcgYmFjayB0byBzeXN0ZW0gdXYiID4mMgogIFsgLXggL3Vzci9sb2NhbC9iaW4vdXYgXSAmJiBleGVjIC91c3IvbG9jYWwvYmluL3V2ICIkQCIKICBleGl0IDEyNwpmaQpleHBvcnQgVVZfTElOS19NT0RFPWNvcHkKZXhwb3J0IFRNUERJUj0iJHtUTVBESVI6LS9yb290L3RtcH0iCmV4ZWMgIiRSIiAiJEAiCg=="

    /** 回退计划：系统自带 shell。功能完整可用，但不是 Debian 环境。 */
    private fun fallbackPlan(filesDir: File, cacheDir: File): LaunchPlan = LaunchPlan(
        cmd = "/system/bin/sh",
        args = arrayOf("-l"),
        env = arrayOf(
            "PATH=/system/bin:/system/xbin:/vendor/bin",
            "HOME=${filesDir.absolutePath}",
            "TMPDIR=${cacheDir.absolutePath}",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
        ),
        isFallback = true,
    )
}
