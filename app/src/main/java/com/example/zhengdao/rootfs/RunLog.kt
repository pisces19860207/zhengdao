// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.rootfs

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志：关键事件（安装/会话/错误）落盘为一个受限大小的文件，
 * 供用户一键复制反馈给开发者/AI 定位问题。
 */
object RunLog {

    private const val MAX_BYTES = 512 * 1024
    private const val NAME = "zhengdao-log.txt"

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun log(line: String) {
        val ctx = appContext ?: return
        try {
            val f = File(ctx.filesDir, NAME)
            if (f.length() > MAX_BYTES) {
                // 简单轮转：保留后半段
                val keep = f.readText().takeLast(MAX_BYTES / 2)
                f.writeText("…（旧日志已截断）…\n$keep")
            }
            val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText("[$ts] $line\n")
        } catch (_: Throwable) {
        }
    }

    fun file(): File? = appContext?.let { File(it.filesDir, NAME) }?.takeIf { it.isFile }

    fun readAll(): String = file()?.readText() ?: "（暂无日志）"
}
