package com.example.zhengdao.bridge

/**
 * 桥策略 —— 高权限默认放行 + 硬红线拦截（§4.5「权限模型」二分区表的代码化）。
 *
 * 设计依据：《证道-浏览器Tab技术选型与架构草案-2026-10-13》v1.1 §4.3 / §4.5。
 * 用户定调（2026-10-13，②）："浏览器页对终端里的权限很高，浏览器的那些都能碰，
 * 除了浏览器本身不可以改动。"
 *
 * 实现立场：
 * - [BridgeCommand] allowlist 之外的请求 → DENY（协议层不存在）。
 * - 触及硬红线区的请求（改安全配置 / 改拦截规则 / 改桥自身 / 重绑 JS 接口 / 读写 App 私有数据
 *   / 改浏览器 UI 引擎 / 关脱敏）→ DENY（immutable，agent 权限再高也越不过，§4.3）。
 * - 其余（高权限区全部命令）→ ALLOW。
 * - 注意：高权限模式下**不设"问用户"档**——JS 弹窗（onJsAlert 等）也按高权限默认放行
 *   （agent 可据此判断，§4.5 权限模型表注）。仅 §4.6 终端链接点击（用户模式）才有"每次询问"选项。
 */
interface BridgePolicy {

    /** 对一条桥命令做红线判定。默认 ALLOW；命中硬红线返回 DENY。 */
    fun evaluate(command: BridgeCommand): Verdict

    /**
     * 导航尝试的安全判定（浏览器本身行为）。
     * file:// / intent:// / tel:// / content:// → DENY；http/https → ALLOW。
     * 与 agent 权限无关：这是锁死的浏览器容器在拦截（§4.3）。
     */
    fun onNavigationAttempt(url: String): Verdict

    companion object {
        /** 硬红线区清单（§4.3 二分区表，immutable）。 */
        val RED_LINES: List<String> = listOf(
            "WebView 安全配置（文件访问/混合内容/Safe Browsing 开关）",
            "导航安全拦截规则（危险 scheme 拦截）",
            "本桥 allowlist / 端口 / token",
            "addJavascriptInterface 重绑",
            "读写 App 私有数据 / 其他 App",
            "浏览器 UI / 引擎 / 代码",
            "隐私脱敏开关（agent/AI 不可关闭或绕过）",
        )
    }
}

/** 策略判定结果。高权限模式只有两档（无 ASK_USER，见接口 KDoc）。 */
enum class Verdict {
    ALLOW,
    DENY,
}
