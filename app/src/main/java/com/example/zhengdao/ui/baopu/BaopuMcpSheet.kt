// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第一百七十批 B · MCP 真入口：面板直接连（addServer → connect），不再「给模型发一句话」。
// 第二百一十六批 · MCP OAuth 2.1：**本文件退成一层薄委托**。
//
// 为什么不在这里继续长：本批把「一个输入框」改成「服务器清单 + 授权状态 + 每行动作」，
// 界面体积翻了几倍（McpServerScreen.kt）。保留这个入口函数是为了不动调用点
// （BaopuScreen 那一处 `BaopuMcpSheet(onDismiss = …)`），而不是留两套实现——
// 真入口、清单、授权按钮全在 McpServerScreen，门禁（McpEntryWiringTest）也改盯那里。
package com.example.zhengdao.ui.baopu

import androidx.compose.runtime.Composable

@Composable
internal fun BaopuMcpSheet(
    onDismiss: () -> Unit,
) {
    McpServerScreen(onDismiss = onDismiss)
}
