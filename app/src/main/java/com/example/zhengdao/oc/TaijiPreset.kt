// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 依据的公开接口：OpenCode 官方配置文档（AGENTS.md 全局规则 / opencode.json）、Android 官方文档（应用私有目录）。
package com.example.zhengdao.oc

import android.content.Context
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.terminal.isLegacyMemPlugin
import com.example.zhengdao.ui.PluginManager
import java.io.File

/**
 * 太极实例的**预置**：人设（AGENTS.md）＋ `opencode.json` 的性能/插件字段。
 *
 * ## 为什么从 terminal/ProotLauncher 搬到这里（2026-10-09，ERRATA E-069）
 *
 * 这块内容**全是太极的**，原先却写在 [com.example.zhengdao.terminal.ProotLauncher] 里，
 * 于是有两个毛病：
 *  1. **由不相关的路径触发**：只有"用户开了一次终端"才写。只用太极、从不进终端的用户
 *     永远拿不到新人设（v2.0.6 装机验证时，正是"开一次终端"才让人设落盘的）。
 *  2. **概念串味**：用户 2026-10-09 定稿「彻底把 opencode 和终端分开」，太极的预置就该在
 *     太极自己的启动路径上做。
 *
 * 现在唯一调用点是 `ui/taiji/TaijiScreen` 拉 serve 之前（与终端启动完全解耦）。
 *
 * ## 人设版本
 * - **v2**（2026-10-09，E-067）治的是"谎称自己在 Debian 里"。
 * - **v3**（2026-10-09，E-069）把**工作区**也钉死：太极的工作区已从终端的共享工作区
 *   （`Download/证道`）搬到 App 私有的 `files/oc/workspace`，人设里写清"能读写的就这一处、
 *   别再满手机找活干"。版本号一变，老装机下次进太极就会重写。
 */
object TaijiPreset {

    /** 人设文件（AGENTS.md）上一次写入时对应的工作区路径。沿用旧键名，避免换键触发无谓重写。 */
    private const val KEY_PERSONA_WS = "agents_md_ws_taiji"

    /** 人设**文案版本**（沿用 ProotLauncher 时代的键）。 */
    private const val KEY_PERSONA_VER = "agents_md_ver_taiji"
    private const val PERSONA_VERSION = 3

    /**
     * 插件预置"只做一次"的开关。
     *
     * ⚠️ v1.2 升到 v2：v1 那一次预置写进的是**死路径**（`home/.zhengdao/taiji/...`，
     * serve 根本不读），等于没预置过。换键让它在正确的文件里补做一次——
     * 只此一次，之后用户在插件页关掉就真的关掉了。
     */
    private const val KEY_PLUGIN_PRESET = "plugin_preset_memory_v2"

    /**
     * `watcher.ignore`（官方配置项，glob 数组）：让 OpenCode 的**文件监听**跳过大目录。
     *
     * ⚠️ 只在配置里**还没有** `watcher` 字段时才写——用户自己配的 watcher 一律不动。
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
     * 太极启动前调用（幂等，可重复调）：
     *  1. 把公共区遗留的安装包缓存搬进 App 私有目录（[OcManager.migrateLegacyCache]）；
     *  2. 确保私有工作区/配置目录存在；
     *  3. 按版本号决定是否重写人设；
     *  4. `opencode.json` 做字段级 merge（性能字段 + 遗留插件清理 + 首装预置）。
     *
     * 任何一步失败都只留日志、不抛——**不能因为预置失败就让太极起不来**。
     */
    fun ensure(ctx: Context) {
        runCatching {
            OcManager.migrateLegacyCache(ctx)
            val ws = OcManager.workspaceDir(ctx).apply { mkdirs() }
            OcManager.configDir(ctx).let { if (!it.isDirectory) it.mkdirs() }
            writePersonaIfNeeded(ctx, ws)
            applyConfigPreset(ctx)
        }.onFailure { RunLog.log("太极: 预置失败 ${it.message}") }
    }

    /** 人设文案（纯函数，便于单测锁住"不许再出现 Debian / 共享工作区"这类事实错误）。 */
    internal fun personaText(wsPath: String): String = (
        "# 证道「太极」运行环境说明（每次对话开始前必读）\n\n" +
            "## 你在哪\n" +
            "你是证道 App 内置的「太极」（一个 OpenCode 实例），**跑在安卓手机上、App 自己的进程里**，\n" +
            "不在 Debian 里，也不是远程服务器。\n" +
            "你的 shell 工具用的是安卓自带的 `/bin/sh`（mksh），`PATH` 只有 `/system/bin`：\n" +
            "**没有 python / pip / node / npm / git**，也**看不到**「终端」里那套 Linux 目录树：\n" +
            "那是 proot Debian 13.7 的地盘，你既进不去，也不要试着 `cd` 过去（你的目录见下）。\n" +
            "不要声称自己是 Linux/Debian 服务器；也不要说「我没有文件系统」——你的工作区见下。\n\n" +
            "## 你的工作区（唯一的工作目录）\n" +
            "`$wsPath`\n" +
            "- 这是 App 分给太极的**私有目录**，读写都在这里；用 `ls` 看到的就是全部。\n" +
            "- 用户问「文件在哪」，答案就是这个目录；产出也放进这个目录。\n" +
            "- **不要满手机到处找别的目录来干活**：`/sdcard` 只在用户明确指了某个文件时才去读，\n" +
            "  也不要再进终端的工作区（那是「终端」Tab 的地盘，与你无关）。\n\n" +
            "## 你能做 / 干不了\n" +
            "- 能做：读写工作区里的文件、整理与生成文本、看用户贴进来的内容、写代码文件（写不等于能跑）。\n" +
            "- 干不了：装依赖、跑 `python`/`node`/`git`/`ffmpeg`、编译、批量处理资料库、\n" +
            "  `.docx`/`.pdf` 提取 ⇒ **直接告诉用户去「终端」Tab**：那里是完整的 Debian 环境，\n" +
            "  工具齐全，还能装 hermes / Claude Code 等 Agent 来干这些活。\n\n" +
            "## 纪律\n" +
            "- **不要编造命令输出**：没跑过就说没跑过；失败了就把原始报错贴出来，别" +
            "「猜一个看起来对的结果」。\n" +
            "- 找文件先 `ls` 看一眼再下结论，别把「我没找到」直接说成「不存在」。\n" +
            "- 不要声称「我不在手机上」——你就在手机里，只是能用的工具比终端少。\n"
        )

    /** 人设落盘（文件不在 / 路径变了 / 文案版本变了 → 重写）。 */
    private fun writePersonaIfNeeded(ctx: Context, ws: File) {
        val cfgDir = OcManager.configDir(ctx)
        if (!cfgDir.isDirectory && !cfgDir.mkdirs()) return
        val agents = File(cfgDir, "AGENTS.md")
        val prefs = com.example.zhengdao.ui.Settings.prefs(ctx)
        val wsPath = ws.absolutePath
        val lastWs = prefs.getString(KEY_PERSONA_WS, null)
        val lastVer = prefs.getInt(KEY_PERSONA_VER, 0)
        if (!agents.isFile || lastWs != wsPath || lastVer != PERSONA_VERSION) {
            agents.writeText(personaText(wsPath))
            prefs.edit().putString(KEY_PERSONA_WS, wsPath)
                .putInt(KEY_PERSONA_VER, PERSONA_VERSION).apply()
            RunLog.log("太极 AGENTS.md 已写入（工作区: $wsPath，人设 v$PERSONA_VERSION）")
        }
    }

    /** `opencode.json`：性能字段 + 遗留插件清理 + 首装预置（全部走字段级 merge）。 */
    private fun applyConfigPreset(ctx: Context) {
        val prefs = com.example.zhengdao.ui.Settings.prefs(ctx)
        val presetOnce = !prefs.getBoolean(KEY_PLUGIN_PRESET, false)
        OcManager.updateConfig(ctx) { obj ->
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
                PluginManager.DEFAULT_ON.forEach { if (ensurePluginEnabled(obj, it)) changed = true }
            }
            changed
        }
        // 预置流程结束：此后不再自动干预插件配置，插件页的开关是唯一权威。
        if (presetOnce) prefs.edit().putBoolean(KEY_PLUGIN_PRESET, true).apply()
    }

    /**
     * 幂等移除 `plugin` 数组里历史遗留的第三方记忆插件（opencode-mem）。
     * 其它插件一律保留（**含 `[spec, opts]` 数组形态，原样放回，不做字符串化**——
     * 旧实现用 `optString` 取值，遇到数组形态会被字符串化，等于悄悄改坏用户配置）；
     * 数组清空后连 `plugin` 键一起删，避免留下空数组。
     * @return 是否发生了改动（调用方据此决定要不要写盘）
     */
    internal fun stripLegacyMemPlugin(obj: org.json.JSONObject): Boolean {
        val arr = obj.optJSONArray("plugin") ?: return false
        val remain = org.json.JSONArray()
        var removed = false
        for (i in 0 until arr.length()) {
            val item = arr.opt(i)
            val name = PluginManager.specOf(item) ?: ""
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
    internal fun ensurePluginEnabled(obj: org.json.JSONObject, spec: String): Boolean {
        val arr = obj.optJSONArray("plugin")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val s = PluginManager.specOf(arr.opt(i))
                if (s != null && PluginManager.samePackage(s, spec)) return false
            }
        }
        (arr ?: org.json.JSONArray().also { obj.put("plugin", it) }).put(spec)
        return true
    }
}
