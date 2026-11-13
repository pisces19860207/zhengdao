// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百三十单 · MCP 装配账本的 prefs 落盘（pin / LRU / 预算比例）。
//
// 为什么落 prefs 不进 BaopuDb：pin 是「用户手点的标记」，LRU 是「上次用过的时刻」——
// 都是低频、可重建的小数据；进库表就要动 VERSION 与迁移，本单明令不许碰版本号
// （与 McpServerStore 同一条红线）。存的是 wireName 与毫秒，没有凭据、没有 URL。
package com.example.zhengdao.baopu.mcp

import android.content.Context
import androidx.core.content.edit

internal class PrefsMcpBudgetStorage(context: Context) : McpBudgetStorage {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY, null)

    override fun write(json: String) {
        // 仓内统一用 core-ktx 的 edit {}（默认 apply），避开 lint 的 ApplySharedPref / UseKtx。
        prefs.edit { putString(KEY, json) }
    }

    companion object {
        /** 与 server 配置同份 prefs 文件（低频整份覆盖；key 不同互不干扰）。 */
        const val FILE = "baopu_mcp"

        private const val KEY = "assembly"
    }
}
