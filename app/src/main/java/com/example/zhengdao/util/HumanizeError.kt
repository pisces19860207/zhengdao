// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.util

import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.FileAlreadyExistsException
import javax.net.ssl.SSLException

/**
 * 异常 → 「人话」用户可读文案（单行 title）。
 *
 * ## 为什么存在
 *
 * 项目里此前 6+ 处直接把 `it.message`（Java 异常原文）喂给用户：出现 "Permission denied"、
 * "Connection refused (errno=111)"、"`/data/user/0/com.example.zhengdao/cache` failed" —
 * 这些对**非技术用户**（项目定位：独立作者）只是噪音，他们看完不知道下一步该做什么。
 *
 * ## 设计原则（参照 GOV.UK / MD3 / iOS HIG 2025-2026 风格化错误指南）
 *
 * 1. **说问题不甩栈**——返回"是什么错了"（如"没有写入权限"），而不是 Java 内部堆栈。
 * 2. **先分类，再看原文**——网络类异常单独成类（见 [title] 里的网络分支），
 *    绝不因为它们是 `IOException` 子类就被并进"文件读写失败"：那会把用户引去查存储，
 *    而真正的问题是网络。
 * 3. **不透露敏感诊断**——文件系统路径、errno 数字、stack trace 不进 title（这些已在 RunLog）。
 *    iOS HIG：避免把调试细节塞给用户。
 * 4. **≤ 70 字符**（MD3 Toast 规范：Android 12+ 系统 Toasts 限 2 行）。
 * 5. **保留原始**——`t.message` 仍可经 [com.example.zhengdao.rootfs.RunLog] 落盘
 *    （Snackbar 的"查看日志"按钮可看到），不丢可诊断性。
 * 6. **未知异常兜底保留类简名**——给"出错了"而不是再吐一个 NPE；但把异常类简名放进括号，
 *    报障时对得上号（`t.message` 原文仍只在日志里，不进用户文案）。
 *
 * ## 关于 hint() / full()（2026-10-08 删除）
 *
 * 本对象曾提供 `hint(t)`（"下一步"建议）与 `full(t)`（`title：hint` 拼接）。走查时全库 grep
 * 确认**只有单测在调用、生产代码一律只用 `title()`**；而能接线的入口（MainActivity、
 * OcManager）不在本次改动范围内。于是按"最小且诚实"删掉这层死 API，而不是为了留住测试
 * 把一串没人要的建议硬塞进生产文案。
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

        // ── 网络类（必须排在 `is IOException` 之前）──────────────────────────
        // SSLException / SocketException / EOFException 全是 IOException 的子类：
        // 放到后面就会被 ioTitle 一律报成"文件读写失败"，用户据此去查存储只会白忙。
        // ⚠️ SocketException 必须排在 ConnectException **之后**——ConnectException 是它的子类，
        //    顺序颠倒会让"服务器拒绝连接"退化成笼统的"连接中断"。
        is UnknownHostException -> "找不到服务器（请检查网络）"
        is SocketTimeoutException -> "网络超时（请重试）"
        is SSLException -> "安全连接失败（请检查网络）"
        is ConnectException -> "连不上服务器（请检查网络）"
        is SocketException -> "网络连接中断（请重试）"
        is EOFException -> "网络连接中断（请重试）"

        is IOException -> ioTitle(t)

        // 兜底：仍是"出错了"，但带上异常类简名（如「出错了（SocketTimeoutException）」）。
        // 旧版只给"出错了"，用户/支持都无从对号；类简名不含路径与 errno，不违反原则 3。
        // 匿名类的 simpleName 是空串 → 退回"未知类型"，避免出现"出错了（）"。
        else -> "出错了（${t.javaClass.simpleName.ifBlank { "未知类型" }}）"
    }

    // ── IOException 子分支（按 message 关键词）─────────────────────────────

    /**
     * 磁盘满判定：只认**明确的**磁盘满标记。
     *
     * 旧实现是 `message.contains("space")`：路径里带 "My Space" 的普通失败会被误报成
     * "存储空间不足"，然后把用户引去"清存储"——建议指向了错误的方向。
     */
    private fun isDiskFull(t: IOException): Boolean {
        val m = t.message?.lowercase() ?: return false
        return "no space left on device" in m || "enospc" in m || "disk full" in m
    }

    private fun ioTitle(t: IOException): String = when {
        isDiskFull(t) -> "存储空间不足"
        t.message?.contains("Permission denied", ignoreCase = true) == true -> "没有写入权限"
        t.message?.contains("File too large", ignoreCase = true) == true -> "文件太大"
        else -> "文件读写失败"
    }
}
