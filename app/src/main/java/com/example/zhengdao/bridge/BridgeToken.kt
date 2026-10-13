package com.example.zhengdao.bridge

import android.content.Context
import android.content.SharedPreferences

/**
 * 桥 token 存取 —— §4.5「端点与鉴权」表的代码化。
 *
 * 设计依据：《证道-浏览器Tab技术选型与架构草案-2026-10-13》v1.1 §4.5。
 * 证据等级：② 复用太极随机口令模型（oc/OcClient.kt:130：首次启动随机生成、存 App 私有
 * SharedPreferences、不落文件不联网）。
 *
 * 鉴权格式（§4.5）：Authorization: Basic base64("browser:<token>")。
 *
 * token 下发（③ UX 待定，§6 风险）：外部 agent（Claude/Codex/Hermes/AGY）= 用户在
 * 「浏览器桥」设置项**一次性复制**后粘进 agent 环境。太极不在此列（不走本桥）。
 */
class BridgeTokenStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 取 token；不存在则生成并持久化（首启随机）。 */
    fun load(): String {
        prefs.getString(KEY_TOKEN, null)?.let { return it }
        val fresh = generate()
        prefs.edit().putString(KEY_TOKEN, fresh).apply()
        return fresh
    }

    /** 重新生成（用户主动轮换时才调；agent 无入口——红线③，§4.3）。 */
    fun rotate(): String {
        val fresh = generate()
        prefs.edit().putString(KEY_TOKEN, fresh).apply()
        return fresh
    }

    private fun generate(): String {
        // TODO(阶段3)：SecureRandom 生成足够长度的随机串（与太极随机口令同模型，oc/OcClient.kt:130）
        return java.util.UUID.randomUUID().toString() // 占位：UUID 可预测性不足，填肉时换 SecureRandom
    }

    companion object {
        private const val PREFS_NAME = "browser_bridge"
        private const val KEY_TOKEN = "bridge_token"
    }
}
