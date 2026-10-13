package com.example.zhengdao.browser

import android.os.Debug
import com.example.zhengdao.rootfs.RunLog

/**
 * 空 WebView 内存基线探针 —— 阶段 0 三条之一（2026-10-14 派工单并入第 ① 刀）。
 *
 * 设计依据：《证道-浏览器Tab技术选型与架构草案-2026-10-13》v1.1 §6 阶段 0：
 * "真机验证：Safe Browsing 回调触发、shouldInterceptRequest 拦 intent://、
 * **空 WebView 内存占用基线**"。
 *
 * 用法：[BrowserHolder] 首建 WebView 前后各采一次，差值即"空 WebView 增量"，
 * 写 RunLog（镜像 logcat，tag `zhengdao`，蓝总管真机采数）。
 *
 * 注意：本类依赖 Android framework（Debug.MemoryInfo / /proc），**单测不覆盖**；
 * 编译过 = 跑得通，真机读数归蓝总管采（派工单第 6 条分工）。
 */
object BrowserMemoryProbe {

    private const val TAG = "XuanLanMem"

    /** 进程内存快照（KB）：totalPss（Debug.MemoryInfo）与 VmRSS（/proc/self/status）。 */
    fun snapshot(): Pair<Long, Long> {
        // 注意：Debug.getMemoryInfo 是 void 出参式（往 info 里填），不是返回式
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val pss = info.totalPss.toLong()
        var rss = -1L
        runCatching {
            java.io.File("/proc/self/status").forEachLine { line ->
                if (line.startsWith("VmRSS:")) {
                    rss = line.trim().removePrefix("VmRSS:").replace("kB", "").trim().toLong()
                    return@forEachLine
                }
            }
        }
        return pss to rss
    }

    /** 采一次并写 RunLog + logcat（label 用于区分前后快照）。 */
    fun log(label: String) {
        val (pss, rss) = snapshot()
        RunLog.log("$TAG $label pss=${pss}KB rss=${rss}KB")
    }
}
