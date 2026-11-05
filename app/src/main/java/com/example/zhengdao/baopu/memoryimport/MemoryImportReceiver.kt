// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第四百五十五批《收编接线》②「给一个可执行入口（命令行/调试即可，UI 归 U 批）」。
// 形制沿用 459 的 PromptExportReceiver：一次性广播，验收方一条 adb 命令就能复现。
package com.example.zhengdao.baopu.memoryimport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 「现在收编一次」的可执行触发（455 ②；UI 归 U 批，本批只给能真跑的口）：
 *
 * ```
 * adb shell am broadcast -a com.example.zhengdao.action.IMPORT_MEMORY_NOW \
 *     -p com.example.zhengdao
 * ```
 *
 * 安全边界（与 459 同一条规矩，`android.permission.DUMP`）：这个口子会让 App 读源文件、
 * 写自己的记忆目录。不设权限的话任何第三方 App 都能反复戳它；`DUMP` 是
 * signature|privileged 级系统权限——普通 App 拿不到，`adb shell` 恰好持有 ⇒
 * 「验收方能跑、别的 App 跑不了」。
 *
 * 不看冷启日闸（手动就是想跑）；收编幂等（451 判据③），连发几条也只是重复同一结果。
 * U 批要做按钮时请直接调 [FreeTextMemoryWiring.runNow]，不要绕广播。
 */
class MemoryImportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_IMPORT_NOW) return
        val ctx = context.applicationContext
        // 收编要读盘写盘：goAsync 把生死交给后台线程，不占主线程（读盘探针只管主线程）。
        val pending = goAsync()
        Thread {
            try {
                FreeTextMemoryWiring.runNow(ctx)
            } finally {
                pending.finish()
            }
        }.apply { name = "zhengdao-memory-import" }.start()
    }

    companion object {
        /** 广播 action（与 AndroidManifest 里的 intent-filter 逐字一致）。 */
        const val ACTION_IMPORT_NOW: String = "com.example.zhengdao.action.IMPORT_MEMORY_NOW"
    }
}
