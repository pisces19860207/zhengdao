package com.example.zhengdao.bridge

/**
 * 桥命令集（高权限 allowlist）—— §4.5 命令清单的代码化。
 *
 * 设计依据：《证道-浏览器Tab技术选型与架构草案-2026-10-13》v1.1 §4.5「命令清单」表 +
 * 「权限模型」二分区表。
 *
 * 高权限区（agent 全开，浏览器的那些都能碰）：snapshot / execJS / click / type / scroll /
 * navigate / newTab / activeTab / closeTab / status / screenshot / 页面触发的下载落盘。
 * 硬红线区（全禁，浏览器本身不可改）：WebView 安全配置、导航拦截规则、桥 allowlist/端口/token、
 * addJavascriptInterface 重绑、App 私有数据/其他 App、浏览器 UI/引擎/代码、脱敏开关（§4.3 表）。
 * ——红线区没有对应命令，是本 allowlist 的设计意图：越红线的请求在协议层就不存在。
 */
sealed class BridgeCommand {

    /**
     * 页面快照。mode=text|dom, selector?。
     * 返回可见文本/结构 DOM/页面数据（含 cookie、localStorage）；
     * **所有离开桥的数据强制脱敏 PII**（agent/AI 不可关闭或绕过，§4.4）。
     */
    data class Snapshot(val mode: String = "text", val selector: String? = null, val tabId: String? = null) : BridgeCommand()

    /**
     * 页面内 JS 自由执行（含读 cookie/storage、外联 fetch——由页面同源策略约束）。
     * 结果字符串化且**限长 64KB**（§4.5，防 OOM）；
     * **返回值同样过 SensitiveRedactor**（agent 无法借 execJS 读原始隐私值绕过，§4.4）。
     */
    data class ExecJS(val code: String, val tabId: String? = null) : BridgeCommand()

    /** 点击任意元素；选择器限简单 CSS。 */
    data class Click(val selector: String, val tabId: String? = null) : BridgeCommand()

    /** 向任意输入框写入，**含密码框**（密码框只是页面里一个输入框——用户定调高权限，§4.3）。 */
    data class Type(val selector: String, val text: String, val tabId: String? = null) : BridgeCommand()

    /** 滚动。{x?, y?} 或 {selector}。 */
    data class Scroll(val x: Int? = null, val y: Int? = null, val selector: String? = null, val tabId: String? = null) : BridgeCommand()

    /**
     * 加载任意 http/https。危险 scheme（file:// / intent:// / tel://）由浏览器本身拦截，agent 无法绕过。
     * 高权限下**不经域名白名单/用户确认**（§4.5 时序图注意项）。
     */
    data class Navigate(val url: String) : BridgeCommand()

    /** 开标签；受标签数上限约束（建议 20，§4.2）。 */
    data class NewTab(val url: String? = null) : BridgeCommand()

    /** 切换 agent 操作的活跃标签。 */
    data class ActiveTab(val tabId: String) : BridgeCommand()

    /** 关标签。 */
    data class CloseTab(val tabId: String) : BridgeCommand()

    /** 返回活跃标签 {url, title, loading}。 */
    data object Status : BridgeCommand()

    /**
     * 截屏。tabId?, fullPage?。
     * 输出 PNG 写入**系统截图目录 Pictures/Screenshots**（MediaStore，前缀 zhengdao_browser_，
     * §4.4 用户定调），返回文件 uri/路径供 agent 引用。
     */
    data class Screenshot(val tabId: String? = null, val fullPage: Boolean = false) : BridgeCommand()

    /**
     * 页面触发的下载/落盘：默认允许（高权限）；页面下载文件落 Download/，不越出浏览器沙箱；
     * 截图例外——统一走系统截图目录（§4.5 命令清单最后一行）。
     */
    data object Download : BridgeCommand()
}
