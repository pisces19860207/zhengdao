// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.terminal

/**
 * 历史遗留插件判定（2026-10-07）。
 *
 * 2026-10-06 曾把第三方记忆插件 `opencode-mem` 写进 `opencode.json` 的 `plugin` 数组，
 * 想解决"重开就忘"。实测：它要求 `opencodeProvider` + `opencodeModel` 同时配置才会启用
 * 自动捕获（源码 `dist/config.js::getAutoCaptureProviderStatus()`），首版只写了插件名，
 * 因此**装上后从未产出过一条记忆**；同时它的本地向量模型占 656 MB、两份依赖占 1.9 GB。
 * 2026-10-07 用户拍板整个摘除。
 *
 * 这里只做**幂等清理**：历史配置里若还留着该插件项就移除，其它插件一律不动。
 * 判定单独抽成纯函数是为了可单测——`plugin` 数组里既有 `"opencode-mem"` 也有
 * opencode 解析出的带版本 spec 形态（真机缓存目录名即 `opencode-mem@latest`）。
 */
internal fun isLegacyMemPlugin(name: String): Boolean =
    name == "opencode-mem" || name.startsWith("opencode-mem@")
