// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第一百七十批 B · MCP 真入口：配置存储的 Android 实现（SharedPreferences）。
//
// 为什么是 prefs 不是数据库：这是「用户手填的地址」，不是会话数据；进数据库表就要动
// 库版本号与迁移脚本，而本批明令不许碰版本号。prefs 一份 JSON 就够了。
package com.example.zhengdao.baopu.mcp

import android.content.Context
import androidx.core.content.edit

/**
 * MCP server 配置的 prefs 存储（明文，**只存地址与名字，不存请求头**）。
 *
 * 写用 `apply()`：配置是低频、可重建的数据（丢了顶多重填一次地址），为它付一次同步
 * 磁盘写不值得；也避开 lint 的 `ApplySharedPref`（本仓不许压制告警）。
 */
internal class PrefsMcpServerStorage(context: Context) : McpServerStorage {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY, null)

    override fun write(json: String) {
        // 仓内统一用 core-ktx 的 edit {}（默认 apply），避开 lint 的 ApplySharedPref / UseKtx。
        prefs.edit { putString(KEY, json) }
    }

    companion object {
        /** 与 baopu_tools 分开一份：工具开关是高频读写，配置是低频整份覆盖。 */
        const val FILE = "baopu_mcp"

        private const val KEY = "servers"
    }
}
