// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.terminal

import android.content.Context
import com.example.zhengdao.core.AppPrefs
import com.example.zhengdao.rootfs.RunLog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 历史遗留记忆插件（`opencode-mem`）一次性迁移（第一百二十一批）。
 *
 * ## 为什么是「迁移」而不是「删库」
 *
 * 2026-10-06 曾把第三方记忆插件 `opencode-mem` 写进 `opencode.json` 的 `plugin`
 * 数组，想解决"重开就忘"。实测结论（证据等级②，KDoc 与真机追查，见
 * [isLegacyMemPlugin] 的头注）：该插件**装上后从未产出过一条记忆**（它要求
 * `opencodeProvider` + `opencodeModel` 同时配置才会启用自动捕获，首版只写了插件名），
 * 却带 656 MB 本地向量模型 + 1.9 GB 依赖。2026-10-07 用户拍板整个摘除。
 *
 * ⇒ **没有旧记忆数据可迁**（不是"搬数据"，是"清残留"）；本迁移只做一件事：
 * 把历史配置里还留着的该插件项从 `plugin` 数组里摘掉，**其它插件与其它配置项
 * 一个字都不动**——`plugin` 数组是用户可自定的位置，误伤别的插件等于替用户做决定
 * （判定复用 [isLegacyMemPlugin]，两种真实形态都认：裸名与 `@版本` spec）。
 *
 * ## 三条硬性质（派工单 §2）
 *
 * 1. **幂等**：三层——① SharedPreferences 一次性标记（与 `Workspace.migrate` 同房型，
 *    升级不重跑）；② 文件级「无残留就不动盘、不备份」；③ 二次运行同一文件 ⇒ 零改动。
 * 2. **可回滚**：改写前先把原件复制成 `<名>.bak-<时间戳>`（同目录），再走
 *    「写临时文件 → 原子重命名」；中途失败原文件不动，备份仍在。
 * 3. **失败不阻塞、可重试**：任何一个文件读写失败都往上抛，[runOnce] **不置标记** ⇒
 *    下次重启自然重试；[runOnce] 自己绝不抛给调用方之外的地方（调用方在后台线程里
 *    兜住记日志），冷启动路径上一行 IO 都不挡。
 *
 * ## 落点
 *
 * 宿主 home 目录取法与 `ProotLauncher.buildLaunchPlanLocked` 同源
 * （`File(context.filesDir, "home")`）；两个历史落点都是相对它的相对路径：
 * `.config/opencode/opencode.json`（opencode 默认位）与
 * `.zhengdao/taiji/config/opencode/opencode.json`（太极实例位）。
 * 文件不存在 ⇒ 正常跳过（新装用户本来就没有残留）。
 */
internal object LegacyMemMigration {

    private const val TAG = "LegacyMemMigration"

    /** 一次性标记（升级不丢 ⇒ 升级也不会重跑；键名带版本号，将来换形状才知道要重迁）。 */
    private const val KEY_MIGRATED = "legacy_mem_plugin_migrated_v1"

    /** opencode.json 的两个历史落点（相对 homeDir；ProotLauncher 同源拼接）。 */
    private val CONFIG_RELS = listOf(
        ".config/opencode/opencode.json",
        ".zhengdao/taiji/config/opencode/opencode.json",
    )

    /**
     * 启动时跑一次（由 `ZhengdaoApp` 的后台杂务线程调用——与自动清理垃圾同一条
     * `zd-auto-clean` 线，**刻意不新增裸线程**：`BareThreadGuardTest` 对账本已贴红线 32）。
     *
     * @return 本次实际动过的文件（相对 home 的路径）；空列表＝没有残留或已迁移过。
     * @throws java.io.IOException 等：任一动过盘的文件失败就抛，**不置标记** ⇒ 下次重启重试。
     */
    fun runOnce(ctx: Context): List<String> {
        val prefs = AppPrefs.prefs(ctx)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return emptyList()
        val home = File(ctx.filesDir, "home")
        val stamp = System.currentTimeMillis()
        val touched = mutableListOf<String>()
        for (rel in CONFIG_RELS) {
            if (migrateConfigFile(File(home, rel), stamp)) touched += rel
        }
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
        if (touched.isNotEmpty()) {
            RunLog.log(
                "遗留插件迁移：清理 ${touched.joinToString("、")} 里的 opencode-mem 残留" +
                    "（原件已备份为同目录 .bak-$stamp）",
            )
        }
        return touched
    }

    /**
     * 单个配置文件的迁移（**纯函数**：只吃 [file]，不碰 Context/prefs，JVM 可直测）。
     *
     * 判「要不要动」的顺序（任何一步不成立都原样返回 false、盘上零字节改动）：
     * 1. 文件不存在 ⇒ false；
     * 2. 读不出/不是合法 JSON（含 JSONC 带注释的历史文件）⇒ 记一行日志后 false
     *    （解析不了就不猜，opencode 自己都读不了的文件不是我们该改的）；
     * 3. 没有 `plugin` 键、或它不是数组 ⇒ false（不猜用户的配置形状）；
     * 4. 数组里没有 [isLegacyMemPlugin] 命中的项 ⇒ false（**无残留不动盘**）。
     *
     * 有残留时：滤掉命中项（保留其余项的**原值与原顺序**；滤空则删掉 `plugin` 键），
     * 先备份再「写临时文件 → 重命名」原子替换。整文件按 org.json 两空格缩进重写
     * （配置文件本就机器生成，不保原字节；备份里有原件）。
     *
     * @param backupStamp 备份名时间戳（测试传固定值；生产传 [System.currentTimeMillis]）。
     * @return true = 动过盘（已备份）；false = 无需动。
     */
    fun migrateConfigFile(file: File, backupStamp: Long = System.currentTimeMillis()): Boolean {
        if (!file.isFile) return false
        val json = runCatching { JSONObject(file.readText()) }.getOrNull()
        if (json == null) {
            RunLog.log("$TAG：$file 不是合法 JSON，跳过（不猜、不改）")
            return false
        }
        val arr = json.optJSONArray("plugin") ?: return false
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val v = arr.get(i)
            if (v is String && isLegacyMemPlugin(v)) continue
            kept.put(v) // 非字符串项（数字/布尔/对象）原样保留，不猜语义
        }
        if (kept.length() == arr.length()) return false // 无残留：不动盘、不备份
        if (kept.length() == 0) json.remove("plugin") else json.put("plugin", kept)
        val parent = file.parentFile ?: return false
        val backup = File(parent, "${file.name}.bak-$backupStamp")
        val tmp = File(parent, "${file.name}.tmp-$backupStamp")
        runCatching { tmp.delete() }
        // ① 先备份（可回滚）。不带 overwrite：备份路径已存在（时间戳撞了）⇒ 直接失败
        //    重试，**绝不静默覆盖旧备份**（那正是「可回滚」三个字的命门）。
        file.copyTo(backup)
        tmp.writeText(json.toString(2)) // ② 再写临时文件
        if (!tmp.renameTo(file)) { // ③ 最后原子替换；失败则原文件未动，抛出去重试
            runCatching { tmp.delete() }
            throw IllegalStateException("$TAG：重命名失败 ${file.absolutePath}")
        }
        return true
    }
}
