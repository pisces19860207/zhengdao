// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第四百九十一批（设计修订，用户 2026-11-06）一②「启动自动发现：扫默认导入目录，
// 发现即校验（是否直接合并按授权档，保守=先提示一句）」。
//
// 为什么挂在 Application.onCreate（房型照 FreeTextMemoryWiring）：
// 用户把 zip 丢进 `Download/证道/导入/` 之后，App 可能还开着、可能刚被杀——"发现"这件事
// 必须发生在**进程起来**的那一次，而不是某个页面被打开的那一次。
//
// 三条纪律：
// 1. **不在主线程**：扫盘 + 解 zip 校验是 IO，冷启动时抢主线程只会让首屏卡；
// 2. **不静默**：发现了就进「最近问题」（一句话），校验不过也说；说了还不合并；
// 3. **不自动合并**：最保守档。499（授权档）落地后，改的只有 [BackupInbox.refresh] 那一处。
package com.example.zhengdao.backup

import android.app.Application
import com.example.zhengdao.core.RunLog

internal object BackupWiring {

    /** 冷启动后等一会儿再扫：让首屏、RunLog、各 install 先跑完（同 FreeTextMemoryWiring 口径）。 */
    private const val START_DELAY_MS = 3_000L

    fun install(app: Application) {
        val ctx = app.applicationContext
        Thread {
            runCatching {
                Thread.sleep(START_DELAY_MS)
                val snap = BackupInbox.refresh(ctx)
                if (snap == null) RunLog.log("[备份收件箱] 默认导入目录里没有待导入的 zip")
            }.onFailure { e ->
                // 扫不动只记日志：备份是"有就用"的功能，不该把冷启动拖出问题
                RunLog.log("[备份收件箱] 扫描失败（${e.javaClass.simpleName}）：${e.message ?: "无细节"}")
            }
        }.apply { isDaemon = true; name = "zhengdao-backup-inbox" }.start()
    }
}
