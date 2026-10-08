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

    /**
     * 「推荐插件已预置过」的标记键：只预置一次，之后由用户在插件页说了算。
     *
     * ⚠️ v1.2 升到 v2：v1 那一次预置写进的是**死路径**（`home/.zhengdao/taiji/...`，
     * serve 根本不读），等于没预置过。换键让它在正确的文件里补做一次——
     * 只此一次，之后用户在插件页关掉就真的关掉了。
     */
    private const val KEY_PLUGIN_PRESET = "plugin_preset_memory_v2"

    /** 人设文件（AGENTS.md）上一次写入时对应的工作区路径。沿用旧键名，避免换键触发无谓重写。 */
    private const val KEY_PERSONA_WS = "agents_md_ws_taiji"

    /**
     * `watcher.ignore`（官方配置项，glob 数组）：让 OpenCode 的**文件监听**跳过大目录。
     *
     * ⚠️ 只在配置里**还没有** `watcher` 字段时才写——用户自己配的 watcher 一律不动。
     * `opencode/` 是 App 自己落在工作区里的 OpenCode 安装包缓存（实测 68.6 MB，
     * 见 v1.2 阶段 0 意外发现 ①），Agent 不该监听也不该搜它。
     */
    private val WATCHER_IGNORE: org.json.JSONArray
        get() = org.json.JSONArray(
            listOf(
                "node_modules/**",
                "dist/**",
                "build/**",
                ".git/**",
                "opencode/**",
                "**/*.log",
            )
        )

    /**
     * 幂等移除 `plugin` 数组里历史遗留的第三方记忆插件（opencode-mem）。
     * 其它插件一律保留（**含 `[spec, opts]` 数组形态，原样放回，不做字符串化**——
     * 旧实现用 `optString` 取值，遇到数组形态会被字符串化，等于悄悄改坏用户配置）；
     * 数组清空后连 `plugin` 键一起删，避免留下空数组。
     * @return 是否发生了改动（调用方据此决定要不要写盘）
     */
    private fun stripLegacyMemPlugin(obj: org.json.JSONObject): Boolean {
        val arr = obj.optJSONArray("plugin") ?: return false
        val remain = org.json.JSONArray()
        var removed = false
        for (i in 0 until arr.length()) {
            val item = arr.opt(i)
            val name = com.example.zhengdao.ui.PluginManager.specOf(item) ?: ""
            if (isLegacyMemPlugin(name)) {
                removed = true
                continue
            }
            remain.put(item)
        }
        if (!removed) return false
        if (remain.length() == 0) obj.remove("plugin") else obj.put("plugin", remain)
        return true
    }

    /**
     * 幂等确保某插件在 `plugin` 数组里——"同包不同版本段"视为已存在
     * （`pkg` 与 `pkg@latest` 是同一个包）。判定与写入都只认数组项的第 0 项，
     * 兼容 `"pkg"` 与 `["pkg", { 选项 }]` 两种写法。
     * @return 是否新增了条目
     */
    private fun ensurePluginEnabled(obj: org.json.JSONObject, spec: String): Boolean {
        val arr = obj.optJSONArray("plugin")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val s = com.example.zhengdao.ui.PluginManager.specOf(arr.opt(i))
                if (s != null && com.example.zhengdao.ui.PluginManager.samePackage(s, spec)) return false
            }
        }
        (arr ?: org.json.JSONArray().also { obj.put("plugin", it) }).put(spec)
        return true
    }

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

        // .ignore（v1.2 阶段 2.2）：让 Agent 的 grep / 搜索类工具跳过大目录。
        // ⚠️ 官方 opencode.json **没有** `.ignore` 这个配置项（已核对 opencode.ai/docs/config），
        //    "让搜索跳过大目录"只能靠**在工作区根放 .ignore 文件**（opencode 的搜索工具
        //    尊重 .ignore / .gitignore）。因此这里由 App 代放一次。
        // 幂等：用户自己的 .ignore 一个字都不改（只在本来没有时才生成）。
        runCatching {
            val ignore = File(wsHost, ".ignore")
            if (!ignore.isFile) {
                ignore.writeText(
                    "node_modules/\n" +
                        "dist/\n" +
                        "build/\n" +
                        ".git/\n" +
                        // App 自己的 OpenCode 安装包缓存就落在工作区里（实测 68.6 MB），
                        // 对 Agent 是纯噪音，必须跳过
                        "opencode/\n" +
                        "*.log\n"
                )
                RunLog.log("工作区 .ignore 已生成（跳过 opencode/ 等大目录）")
            }
        }.onFailure {
            // 失败必须可见：静默吞掉会让"搜索没跳过 opencode/"变成查不出来的玄学问题
            RunLog.log("工作区 .ignore 生成失败: ${it.message}（路径 $wsHost）")
        }

        // ★ 把 Debian 自带的 /etc/skel 骨架落到 bind 挂载的 home 里（2026-10-07 根因修复）。
        //
        // 为什么必须做：Agent 官方安装脚本（agy / claude / hermes）一律把命令发布到
        // ~/.local/bin。让这个目录进 PATH 的逻辑**Debian 一开始就有**，写在
        // /etc/skel/.profile 里：
        //     if [ -d "$HOME/.local/bin" ] ; then PATH="$HOME/.local/bin:$PATH" ; fi
        // 但 /etc/skel 只在 useradd 建用户时复制到 home —— 本项目把 files/home 直接
        // bind 挂到 guest 的 /root，root 的 home 从来不是 useradd 建的，home 里也没有
        // .profile ⇒ 这段逻辑**从未执行过**。后果：装好的 agy/claude 在终端里一律
        // "command not found"，用户看到的现象就是「点启动，Agent 起不来」。
        // （hermes 当年"敲 hermes 没反应"是同一个坑，当时只给它单独软链绕过去了；
        //   见下方保留的 hermes 特例——治根的就是这里。）
        //
        // 幂等且非破坏：只在目标文件不存在时写入，绝不覆盖用户自己改过的 dotfile。
        // 只落 .profile —— PATH 逻辑就在它里面；.bashrc 会顺带改提示符与别名，
        // 与本项目自己的 banner/光标 profile.d 有交互风险，不引入。
        // tmux 的每个 pane 都是 login shell（ps 里可见 `-bash`），会读 ~/.profile，
        // 故新开窗口立刻生效，不必重启 App。
        runCatching {
            val name = ".profile"
            val dst = File(homeDir, name)
            val src = File(rootfsDir, "etc/skel/$name")
            if (!dst.isFile && src.isFile) {
                dst.writeText(src.readText())
                RunLog.log("已从 /etc/skel 落位 ~/$name（补回 ~/.local/bin 的 PATH 逻辑）")
            }
        }.onFailure { RunLog.log("落位 home 骨架失败: ${it.message}") }

        // hermes 命令立即可用（用户反馈：装完敲 hermes 没反应）：安装器把命令发布在
        // /root/.local/bin（home 层），但**早已存在的 shell 的 PATH 是启动时的快照**，
        // 拿不到后装的目录。/usr/local/bin 天然在所有 shell 的 PATH 里且属系统层——
        // 软链过去，新旧 shell 全覆盖。未装 hermes 时跳过，不留悬空链接。
        // （上面的 skel 落位已从根上解决 PATH；这段保留是为"骨架落位前就已存在的旧
        //   会话"兜底——它们的 PATH 快照里没有 ~/.local/bin，但一定有 /usr/local/bin。）
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

        // 终端内缓存清理命令 zzclean（用户 2026-10-08：「可不可以做一个终端自己清理缓存的方法」）。
        // 落 `/usr/local/bin`：系统层、天然在所有 shell 的 PATH 里，**新旧 shell 都拿得到**
        // （与上面 hermes 软链同一个理由）。脚本正文由 CacheCleaner 生成 —— 命令清单与
        // 设置页那条 "在终端中清理缓存" 同源，不会出现两边清的东西不一样。
        // ⚠️ 每次启动**按内容比对后重写**，而不是"文件在就不管"：脚本正文会随 App 版本更新，
        //    只判存在会让老环境永远停在旧脚本上（上一条 zz-cursor-bar.sh 就是那种写法）。
        // ⚠️ 执行位也要一起判：内容相同但丢了 +x 的旧环境会被判成"已就绪"，终端里敲 zzclean
        //    直接 Permission denied（2026-10-08 审查）。判据因此是「内容不同 **或** 不可执行」
        //    时重写并重设执行位——幂等语义不变（内容与执行位都对时依然一次都不写）。
        runCatching {
            val binDir = File(rootfsDir, "usr/local/bin")
            if (binDir.isDirectory || binDir.mkdirs()) {
                val zz = File(binDir, "zzclean")
                val want = CacheCleaner.zzcleanScript()
                if (!zz.isFile || zz.readText() != want || !zz.canExecute()) {
                    zz.writeText(want)
                    zz.setExecutable(true, false)
                    RunLog.log("终端清理命令已就绪：/usr/local/bin/zzclean")
                }
            }
        }.onFailure { RunLog.log("写入 zzclean 失败：${it.message}") }
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
        // ── 太极实例的 OpenCode 预置：人设 + 性能字段 ────────────────────────────
        // - AGENTS.md：人设 + 文件地图（治"忘了自己在手机里 / 找不到文件"）。
        // - snapshot=false：性能（每次工具调用省一个 git 子进程）。
        // - watcher.ignore：文件监听跳过大目录（官方配置项，glob 数组）。
        // - plugin 数组：只做"遗留插件清理" + "首次安装预置一次"，之后插件页是唯一权威。
        //
        // ⚠️ v1.2 阶段 2.0 路径修复（真机查证）：以前写**两份，而且两份都错**——
        //   - 终端默认实例 `~/.config/opencode`：终端那份 npm 版 opencode 已随 v1.2
        //     主线一卸载，写它没有读者；
        //   - 太极实例 `home/.zhengdao/taiji/config/opencode`：**不是** serve 读的目录
        //     （serve 的 XDG_CONFIG_HOME = `files/oc/xdg/config`）⇒ 太极的 Agent
        //     **从来没收到过 AGENTS.md**，插件页的开关也一直改在没人读的文件上。
        //   现在只写一份，且写对地方：[com.example.zhengdao.oc.OcManager.configDir]。
        runCatching {
            val cfgDir = com.example.zhengdao.oc.OcManager.configDir(context)
            if (cfgDir.isDirectory || cfgDir.mkdirs()) {
                val prefsUi = com.example.zhengdao.ui.Settings.prefs(context)
                val presetOnce = !prefsUi.getBoolean(KEY_PLUGIN_PRESET, false)
                // 人设：opencode 原生读取 <XDG_CONFIG_HOME>/opencode/AGENTS.md 作为全局规则。
                // 文件地图随工作区设置动态更新（0.6）：映射变化才重写，平时不动用户文件。
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
                        "- /root —— 你的 home；各 Agent 配置在此（~/.hermes、~/.claude 等）\n\n" +
                        "## 能力边界\n" +
                        "- 无 root，不要尝试需要 root 的操作\n" +
                        "- 禁止执行 apt upgrade（会损坏环境）；装依赖用 pip / npm\n" +
                        "- 找不到用户文件时：先 ls /workspace 和 /sdcard/Download，把已搜索的路径列出来再下结论，不要直接放弃\n"
                    )
                val agents = File(cfgDir, "AGENTS.md")
                val lastPersonaWs = prefsUi.getString(KEY_PERSONA_WS, null)
                if (!agents.isFile || lastPersonaWs != wsPath) {
                    agents.writeText(persona)
                    prefsUi.edit().putString(KEY_PERSONA_WS, wsPath).apply()
                    RunLog.log("太极 AGENTS.md 已写入（工作区映射: $wsPath）")
                }
                // opencode.json：经 OcManager.updateConfig 做**字段级 merge**（只补缺失项，
                // 绝不覆盖用户 / 插件页已写的内容）
                com.example.zhengdao.oc.OcManager.updateConfig(context) { obj ->
                    var changed = false
                    // snapshot=false：性能（每次工具调用省一个 git 子进程）
                    if (!obj.has("snapshot")) { obj.put("snapshot", false); changed = true }
                    // autoupdate=false（用户定稿：默认不打扰，更新走设置页手动检查）
                    if (!obj.has("autoupdate")) { obj.put("autoupdate", false); changed = true }
                    // watcher.ignore：文件监听跳过大目录（官方配置项；已有 watcher 时不动）
                    if (!obj.has("watcher")) {
                        obj.put("watcher", org.json.JSONObject().put("ignore", WATCHER_IGNORE))
                        changed = true
                    }
                    // 遗留清理：移除历史预置的记忆插件 opencode-mem（幂等，其它插件不动）
                    if (stripLegacyMemPlugin(obj)) changed = true
                    // 推荐插件预置：**仅首次安装做一次**（否则用户关掉后下次启动又被加回来）
                    if (presetOnce) {
                        com.example.zhengdao.ui.PluginManager.DEFAULT_ON.forEach {
                            if (ensurePluginEnabled(obj, it)) changed = true
                        }
                    }
                    changed
                }
                // 预置流程结束：此后不再自动干预插件配置，插件页的开关是唯一权威。
                if (presetOnce) prefsUi.edit().putBoolean(KEY_PLUGIN_PRESET, true).apply()
            }
        }

        // tmux 预置（2026-10-05 补）：开启鼠标支持，使**触摸滑动能滚动历史**。
        // 背景见 TerminalView.onScroll 的定制注释：tmux 采用全屏重绘，不产生本地回滚
        // 缓冲（实测 histRows=0），必须把触摸滑动转成滚轮事件、且 tmux 端开启 mouse，
        // 才能滚动 pane 自己的历史。已有配置时按需追加，不覆盖用户自有设置。
        runCatching {
            val f = File(homeDir, ".tmux.conf")
            val cur = if (f.isFile) f.readText() else ""
            val add = buildString {
                if (!Regex("(?m)^\\s*set(-option)?\\s+-g\\s+mouse\\b").containsMatchIn(cur)) {
                    append("set -g mouse on\n")
                    RunLog.log("tmux 配置已补：set -g mouse on（触摸滑动可滚动历史）")
                }
                // 剪贴板预置（2026-10-08 真机实测补）：三档负载（100 / 9000 / 12345 字符）
                // 端到端验证「终端内的 OSC 52 → 手机剪贴板」这条链。
                // tmux 的 set-clipboard 默认是 external —— 实测这种模式下**pane 里应用发出的
                // OSC 52 不会被转发给外层终端**（App 收不到、剪贴板不变、也没有「已复制」提示）；
                // 只有在 on 模式下 tmux 才会既存自己的 buffer、又把 OSC 52 透传给 App。
                // 因此这里必须显式开 on，否则 TerminalEmulator 里那套 OSC 52 处理（含 100 KiB
                // 上限修复）在 tmux 里等于白做。
                if (!Regex("(?m)^\\s*set(-option)?\\s+-g\\s+set-clipboard\\b").containsMatchIn(cur)) {
                    append("set -g set-clipboard on\n")
                    RunLog.log("tmux 配置已补：set -g set-clipboard on（终端内 OSC 52 才能写进手机剪贴板）")
                }
            }
            if (add.isNotEmpty()) f.writeText((if (cur.isBlank()) "" else cur.trimEnd() + "\n") + add)
        }

        // TZ（双保险的第二层）：tmux server / date / Node 等都读它。带 zoneinfo 的
        // 环境用地理名；万一哪个 rootfs 变体没装 zoneinfo，glibc 解析不了地理名，
        // 就退到 POSIX 自包含写法 CST-8（= UTC+8，POSIX 偏移符号西正东负，零依赖）
        val tzName = if (File(rootfsDir, "usr/share/zoneinfo/Asia/Shanghai").isFile) "Asia/Shanghai" else "CST-8"
        val env = mutableListOf(
            // ⚠️ /root/.local/bin 必须在 PATH 里（2026-10-07 真机血案）：Agent 安装器
            // （agy 官方脚本、claude 官方脚本、npm 全局装）一律把命令发布到 ~/.local/bin，
            // 而这里原先只有系统路径 ⇒ 装好的 agy/claude 敲出来是 "command not found"，
            // 用户看到的现象就是「点启动没反应、Agent 没起来」。实测 files/home/.local/bin
            // 里躺着 201MB 的 agy 与指向 claude 的软链，但 shell 根本找不到它们。
            // 放在最前（用户级目录优先于系统层，与常规 Linux 习惯一致）。
            "PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
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
            // ── 网络优化（v1.2）─────────────────────────────────────────────────
            // 禁 IPv6（P0）：Bun 在"DNS 返回全局 IPv6 但接口只有链路本地地址"的网络下会
            //   优先连 IPv6 并**一直挂到超时**（oven-sh/bun#25619）。本 flag 在 Bun 源码
            //   `src/env_var.zig` 里真实存在（2026-10-07 已核对），不是臆造项。
            //   ⚠️ 本机实测未复现卡死（curl opencode.ai 1.50s vs -4 的 1.45s），
            //   这里按用户决定**预防性**开启。
            //   （2026-10-07 更正：原注释写「与 /etc/hosts 里钉的 IPv4 互为双保险」已不成立——
            //    opencode.ai 的钉 IP 早前已撤除，见 EnvSelfHeal.HOSTS_UNPINS/"stripObsoletePins"。）
            "BUN_FEATURE_FLAG_DISABLE_IPV6=1",
            // 遥测屏蔽（P1）：网络不稳时的无退避重试会拖出大量失败 DNS 查询（发热/耗电）。
            //   前两个是各类 CLI 通用的退出开关（未设时进程忽略，零副作用），
            //   第三个是 **Claude Code 官方**文档的非必要流量开关。
            "DISABLE_TELEMETRY=1",
            "DISABLE_ERROR_REPORTING=1",
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1",
            // 本机环回**不走代理**（P0）：代理 App 若把 HTTP_PROXY 注入环境，
            //   访问 127.0.0.1 的本地服务会被错误地送去代理、形成回环。
            "NO_PROXY=localhost,127.0.0.1,::1",
            "no_proxy=localhost,127.0.0.1,::1",
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

        // ── 公共存放区直通（2026-10-08 用户拍板：日志 / 缓存 / Agent 脚本账本都放
        // `Download/证道`，**不跟随工作区**）──
        // 工作区可能是用户自己的内容目录（真机上被设成了 `Download/男性`），
        // 所以 Agent 安装脚本不能再用 `/workspace/agents/scripts` 这个路径——
        // 那会指到用户的内容目录里去。改为把 `Store.root` 单独挂一份到 guest 的
        // `/opt/zhengdao`：路径与工作区无关、两种模式（共享 / 仅私有兜底）都成立，
        // 脚本、账本、包缓存、日志在 guest 里也都能直接翻。
        // 挂载点先在 rootfs 里建好（proot 不保证替调用方创建落点）。
        runCatching { File(rootfsDir, "opt/zhengdao").mkdirs() }
        args.addAll(arrayOf("-b", "${Store.root(context).absolutePath}:${Store.GUEST_ROOT}"))

        // ── 包缓存搬进公共区（用户 2026-10-08：「下载的东西都放到 download 证道 文件夹里」）──
        // Agent 重装的大头从来不是安装脚本，而是 npm / uv / pip 的**包缓存**（真机实测：
        // hermes 的 uv 缓存 254 MB、npm 缓存几十 MB）。把这三处挂到
        // `Download/证道/cache/<kind>` 之后，重装 App 再装 Agent，依赖直接从本地缓存走。
        //
        // 为什么必须走 bind 而不是环境变量：hermes 的 pm 会**剥离 UV_* 环境变量**
        //（见 AgentInstaller 的注释），uv 缓存位置唯一可靠的注入点就是挂载点；
        // npm / pip 各自也有写死的默认目录，环境变量只是可选覆盖。
        // 挂载目标一律落在 bind 进来的 home 之内（`/root/…`），因此目标目录必须先存在。
        // 没存储权限 / 仅私有模式就整段跳过——仅私有模式下目录随卸载删除，
        // 搬过去没有任何收益，只会多一层不必要的读写绕路。
        if (storageGranted(context) && wsShared) {
            bindSharedCache(context, args, homeDir, "npm", ".npm")
            bindSharedCache(context, args, homeDir, "uv", ".hermes/cache/uv")
            bindSharedCache(context, args, homeDir, "uv", ".cache/uv")
            bindSharedCache(context, args, homeDir, "pip", ".cache/pip")
        }

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
                // ⚠️ 仅主会话可 kill——其它副会话与 zhengdao 共存于同一 server，
                // kill 会连带杀掉它们的会话。
                args.addAll(
                    arrayOf(
                        "/bin/bash",
                        "-lc",
                        "${memPrefix}tmux kill-server 2>/dev/null; exec tmux new-session -A -s zhengdao",
                    )
                )
            } else {
                // 副会话：不 kill、直接 attach-or-create。
                // （v1.2 删掉了 `taiji` 副会话分支：它启动的是终端里 npm 版 opencode 的
                //   /usr/local/bin/taiji 脚本，该 opencode 已随主线一卸载，分支不可达。）
                val paneCmd = "/bin/bash -l"
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
            // 横幅文案（v1.2 改写）：原先那条「两个 opencode、不必卸载任何一个」的说明已作废——
            // 终端里的 npm 版 opencode 随 v1.2 主线一卸载了。现在 App 里只有太极 Tab 那一份
            // OpenCode，这里改为直接告诉用户它在哪，避免"我终端里的 opencode 怎么没了"。
            //
            // 2026-10-08 追加：安装结论横幅。App 侧是原生 TerminalView，**不能往里注入文本**
            //（写入 pty 会被当成用户输入），所以「环境已从本地缓存装好、本次没联网下载」这类
            // 结论只能借这条现成通道送进终端可见区：TerminalActivity.writeTerminalNotice()
            // 把结论写进 files/install-notice.txt，这里读出来附在横幅末尾并删除（只显示一次，
            // 不每次开会话都刷屏）。读失败/没有就跳过，不影响启动。
            val installNotice = try {
                val f = File(homeDir.parentFile ?: rootfsDir, "install-notice.txt")
                if (f.isFile) f.readText().trim().also { f.delete() } else ""
            } catch (_: Throwable) {
                ""
            }
            File(rootfsDir, "tmp/.zhengdao-banner-pending").writeText(
                "[提示] 不要执行 apt upgrade（可能损坏环境）；优先用 pip / npm 装依赖\n" +
                    "[网络] 安装失败时：检查代理 App 的「分应用代理」是否已勾选证道\n" +
                    "[提示] OpenCode 在「太极」Tab 里（App 内置版，开箱即用）\n" +
                    if (installNotice.isNotBlank()) "[环境] $installNotice\n" else ""
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

    /**
     * 把 guest 的一个包缓存目录挂到公共区 `Download/证道/cache/<kind>`（幂等，可反复调用）。
     *
     * 首次调用会把私有 home 里的旧缓存**搬进**公共区（用户不必因为这次改动重下 254 MB），
     * 之后每次只是挂一遍。目标路径 `/root/<rel>` 落在 `-b homeDir:/root` 这条 bind
     * **之内**：proot 按顺序解析 bind，落点先由 home 那条变成宿主上的 `homeDir/<rel>`，
     * 再被本覆盖 ⇒ 所以这个落点目录必须真实存在（proot 不会替你建）。
     *
     * 任何一步失败都只是**少挂一个缓存**：捕获后记日志，绝不让终端起不来。
     */
    private fun bindSharedCache(
        context: Context,
        args: MutableList<String>,
        homeDir: File,
        kind: String,
        rel: String,
    ) {
        try {
            val pub = Store.cacheDir(context, kind)
            val mount = File(homeDir, rel)
            val moved = Store.adoptDir(pub, mount) // 幂等：目标已有同名条目就跳过
            if (!mount.isDirectory) mount.mkdirs()
            if (moved > 0) {
                RunLog.log("缓存搬家: ~/$rel → ${pub.absolutePath}（$moved 项，重装 App 后免重下）")
            }
            args.addAll(arrayOf("-b", "${pub.absolutePath}:/root/$rel"))
        } catch (t: Throwable) {
            RunLog.log("缓存挂载失败($kind → /root/$rel)：${t.message}（该缓存留在私有 home）")
        }
    }

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
