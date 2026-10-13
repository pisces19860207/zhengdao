package com.example.zhengdao.bridge

import com.example.zhengdao.browser.BrowserEngine
import com.example.zhengdao.browser.SensitiveRedactor

/**
 * Browser Agent Bridge —— 终端外部 agent → 桥 → WebView（§4.5 核心组件）。
 *
 * 设计依据：《证道-浏览器Tab技术选型与架构草案-2026-10-13》v1.1 §4.5。
 *
 * 传输模型（§4.5 原文）：
 * ```
 * 终端 agent (proot, 同设备)
 *         │  HTTP POST 127.0.0.1:<BRIDGE_PORT>/bridge/v1/<cmd>  (+ Basic token)
 *         ▼
 * ┌──────────────────────────────────────────────┐
 * │ BrowserAgentBridge                            │
 * │   ① 验 token   ② 查 allowlist   ③ 特权走 Policy │
 * └──────────────────────┬───────────────────────┘
 *                        ▼
 *              BrowserEngine → WebView (SecurityGateway)
 * ```
 *
 * 硬约束（② 文档定稿）：
 * - 只绑 127.0.0.1，**不绑 0.0.0.0**（外网够不着；proot 内 agent 经同设备环回可达）。
 * - 鉴权：Authorization: Basic base64("browser:<token>")，token 首启随机生成、
 *   存 App 私有 SharedPreferences（不落文件、不联网）——复用太极随机口令模型（oc/OcClient.kt:130）。
 * - 命令 allowlist：仅 [BridgeCommand] 枚举内的命令；未知命令一律拒。
 * - 凡 agent 请求改浏览器配置 / 越硬红线 → 一律拒（§4.4「红线判定」行）。
 * - **太极（内置 OpenCode）不接入本桥**（用户 2026-10-13 定调，§4.5 注：走自身插件）。
 *
 * 状态：骨架（未实现）。阶段 3 填肉（依赖阶段 0 环回可达实测）。
 */
class BrowserAgentBridge(
    private val engine: BrowserEngine,
    private val policy: BridgePolicy,
    private val redactor: SensitiveRedactor,
    private val tokenStore: BridgeTokenStore,
    private val port: Int = DEFAULT_PORT,
) {

    /** 启动环回服务。TODO(阶段3)：HTTP 服务框架选型（轻量内嵌 server，勿引重型依赖）。 */
    fun start() {
        // TODO(阶段3)：
        // 1. 绑定 127.0.0.1:port（只绑环回！绑 0.0.0.0 是安全事故）
        // 2. 每个请求：① Basic token 校验（失败 401）→ ② 命令 allowlist 命中 → ③ policy.evaluate（红线拒）
        // 3. 分发到 engine；所有出桥数据过 redactor（§4.4 强制脱敏，agent 不可绕）
        TODO("阶段3")
    }

    fun stop() {
        // TODO(阶段3)
        TODO("阶段3")
    }

    companion object {
        /**
         * 候选端口 14080（③ 待定）。
         * 阶段 0 须实测确认不与 OpenCode 14000 及既有端口（可能的 14001）冲突（§6 阶段 0）。
         */
        const val DEFAULT_PORT = 14080

        /** 端点路径前缀（§4.5 传输模型图：/bridge/v1/<cmd>）。 */
        const val PATH_PREFIX = "/bridge/v1/"
    }
}
