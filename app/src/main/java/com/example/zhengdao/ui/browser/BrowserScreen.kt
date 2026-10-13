package com.example.zhengdao.ui.browser

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 玄览主界面骨架 —— 用户模式（§4.1 分层 / §4.2 用户模式要点）。
 *
 * 设计依据：《证道-浏览器Tab技术选型与架构草案-2026-10-13》v1.1 §4.2。
 *
 * 用户模式要点（② 文档定稿，填肉时逐项落地）：
 * - **地址栏**：URL 校验 + http→https 自动升级（外部 http 明文被网络策略拦，升级避免加载失败）；
 *   空输入走搜索引擎。
 * - **多标签**（阶段 2）：每标签独立 WebView + setDataDirectorySuffix（cookie/storage 隔离）
 *   + 独立渲染优先级；上限建议 20；后台标签降 RENDERER_PRIORITY_WAIVED；
 *   setSupportMultipleWindows(false) + javaScriptCanOpenWindowsAutomatically=false。
 * - **导航按钮** + WebBackForwardList 历史栈。
 * - 安全：启动即 applySecurityConfig(EngineSecurityConfig())（默认加固值，§4.4）。
 *
 * 状态：骨架（未实现）。阶段 1 填肉。
 */
@Composable
fun BrowserScreen(
    /** 外部打开入口传入的待加载 URL（§4.6：终端链接点击 / 太极插件 / 桥分享 / 系统分享共用）。 */
    pendingUrl: String? = null,
    /** pendingUrl 消费后回调（防重复加载）。 */
    onConsumePendingUrl: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // TODO(阶段1)：布局 = 地址栏 + WebView(AndroidView) + Tab 栏
    // TODO(阶段1)：pendingUrl 非空 → LaunchedEffect 导航到该 URL 后 onConsumePendingUrl()
    // TODO(阶段2)：多标签管理（WebView 池 + dataDirectorySuffix + 优先级）
    TODO("阶段1")
}
