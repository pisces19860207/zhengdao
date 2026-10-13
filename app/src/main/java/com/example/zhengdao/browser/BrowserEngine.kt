package com.example.zhengdao.browser

/**
 * 浏览器引擎抽象接口 —— 玄览（浏览器 Tab）与浏览器桥共用的唯一内核接缝。
 *
 * 设计依据：《证道-浏览器Tab技术选型与架构草案-2026-10-13》v1.1 §2.3（内核可替换性）、
 * §4.1（分层：用户模式 + Agent 模式共用系统 WebView 内核，安全网关统一收口）。
 * 证据等级：② 设计文档已定稿（v1.0 封版复核通过，v1.1 增补不影响本节）。
 *
 * 立场（§2.3 "集中，而非抽象"）：当前唯一实现是 [WebViewEngine]（系统 WebView）。
 * 本接口把 WebView 特有调用收敛到单点——未来若换 GeckoView 或其他引擎，
 * 只换实现、不动上层（桥 / UI / 终端链接）。不做多引擎工厂（§2.3 明确不过度设计）。
 *
 * 状态：骨架（未实现）。实现见 [WebViewEngine]（阶段 1）。
 */
interface BrowserEngine {

    /** 引擎标识（诊断/status 命令用）。 */
    val engineId: String

    /**
     * 加载 URL。
     * 边界：调用方须先过 scheme allowlist 预检（浏览器地址栏 / 终端链接见
     * [com.example.zhengdao.terminal.TerminalLinkPolicy] / 桥见 [com.example.zhengdao.bridge.BridgePolicy]）；
     * 实现层仍须硬拦 file:// / intent:// / tel:// / content://（属"浏览器本身"红线，§4.4 导航拦截行）。
     */
    fun loadUrl(url: String)

    /**
     * 在页面上下文执行 JS，异步拿回字符串结果。
     * 安全红线（§4.4）：返回值必须过 [SensitiveRedactor] 脱敏 PII——
     * agent 无法借 execJS 读原始隐私值绕过。结果限长（如 64KB，防 OOM，§4.5 命令清单 execJS 行）。
     */
    fun evaluateJavascript(script: String, callback: (String?) -> Unit)

    /**
     * 截图（PNG 字节）。
     * agent 模式：落系统截图目录 Pictures/Screenshots（MediaStore，文件名前缀 zhengdao_browser_，
     * 用户可随手删；不落 App 私有目录）——§4.4「截图落盘位置」行，用户 2026-10-13 定调。
     */
    fun captureScreenshot(callback: (ByteArray?) -> Unit)

    /** 设导航委托（URL 拦截 / 页面事件）。用户模式与桥共用同一委托链。 */
    fun setNavigationDelegate(delegate: NavigationDelegate?)

    /**
     * 应用安全配置（Safe Browsing / 混合内容 / 文件访问 / JS 接口策略等）。
     * 注意：本方法属"硬红线区"——agent 无权触发（§4.3 二分区表），仅 App 自身启动时应用一次。
     */
    fun applySecurityConfig(config: EngineSecurityConfig)
}
