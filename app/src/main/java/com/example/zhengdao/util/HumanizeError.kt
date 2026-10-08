// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.util

import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.FileAlreadyExistsException

/**
 * 异常 → 「人话」用户可读文案（单行 title + 可选 hint）。
 *
 * ## 为什么存在
 *
 * 项目里此前 6+ 处直接把 `it.message`（Java 异常原文）喂给用户：出现 "Permission denied"、
 * "Connection refused (errno=111)"、"`/data/user/0/com.example.zhengdao/cache` failed" —
 * 这些对**非技术用户**（项目定位：独立作者）只是噪音，他们看完不知道下一步该做什么。
 *
 * ## 设计原则（参照 GOV.UK / MD3 / iOS HIG 2025-2026 风格化错误指南）
 *
 * 1. **说问题不甩栈**——返回"是什么错了"（如"没有写入权限"），而不是 Java 内部类名。
 * 2. **给修复动作**——可选 [hint] 字段写"在系统设置里允许管理全部文件"等具体下一步。
 *    MD3：「诊断 + 行动」两步，不要把诊断当文案。
 * 3. **不透露敏感诊断**——文件系统路径、errno 数字、stack trace 不进 title（这些已在 RunLog）。
 *    iOS HIG：避免把调试细节塞给用户。
 * 4. **≤ 70 字符**（MD3 Toast 规范：Android 12+ 系统 Toasts 限 2 行）。
 * 5. **保留原始**——`t.message` 仍可经 [com.example.zhengdao.rootfs.RunLog] 落盘
 *    （Snackbar 的"查看日志"按钮可看到），不丢可诊断性。
 * 6. **未知异常兜底**——给"出错了"而不是再吐一个 NPE。
 *
 * ## 何时用
 *
 * - **Toast / Snackbar 错误文案**（用户能看见的）→ 走 [title]。
 * - **RunLog.log**（开发/支持看的）→ 继续用 `t.message` / `t.javaClass.simpleName`。
 * - **业务断言 / 测试**→ 继续用原 `Throwable`。
 *
 * 2026-10-08 新增；与 [com.example.zhengdao.rootfs.RunLog] 并存而非替换。
 *
 * ## 包位置
 *
 * 放在 `com.example.zhengdao.util`（顶层）而非 `ui.util`——
 * [com.example.zhengdao.oc.OcManager] 这种非 UI 业务模块也要用，
 * ui→oc 反向依赖会破坏分层。
 */
object HumanizeError {

    /** 单行「人话」问题描述，可直接 Toast/Snackbar。 */
    fun title(t: Throwable): String = when (t) {
        is SecurityException -> "没有权限"
        is FileNotFoundException -> "文件找不到"
        is FileAlreadyExistsException -> "目标位置已存在同名文件"
        is UnknownHostException -> "找不到服务器（请检查网络）"
        is ConnectException -> "连不上服务器（请检查网络）"
        is SocketTimeoutException -> "网络超时（请重试）"
        is IOException -> ioTitle(t)
        else -> "出错了"
    }

    /** 「下一步」建议。null = 没有比 title 更具体的指引（用户已知道要重试）。 */
    fun hint(t: Throwable): String? = when (t) {
        is SecurityException -> "到系统设置里给证道打开相关权限"
        is FileNotFoundException -> "文件可能已被移走，重新选一次"
        is UnknownHostException,
        is ConnectException,
        is SocketTimeoutException -> "检查 Wi-Fi 或移动数据是否可用"
        is IOException -> ioHint(t)
        else -> "稍后重试；还不行就看看运行日志"
    }

    /** 组合：`title`，`hint` 非 null 时换行追加。 */
    fun full(t: Throwable): String {
        val h = hint(t) ?: return title(t)
        return "${title(t)}：$h"
    }

    // ── IOException 子分支（按 message 关键词）─────────────────────────────

    private fun ioTitle(t: IOException): String = when {
        t.message?.contains("space", ignoreCase = true) == true -> "存储空间不足"
        t.message?.contains("Permission denied", ignoreCase = true) == true -> "没有写入权限"
        t.message?.contains("File too large", ignoreCase = true) == true -> "文件太大"
        else -> "文件读写失败"
    }

    private fun ioHint(t: IOException): String? = when {
        t.message?.contains("space", ignoreCase = true) == true ->
            "到「设置 → 存储」清一些旧版本或旧日志"
        t.message?.contains("Permission denied", ignoreCase = true) == true ->
            "到「系统设置 → 应用管理 → 证道 → 权限」检查存储权限"
        else -> null
    }
}
