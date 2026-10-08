package com.example.zhengdao

import android.app.Application
import com.example.zhengdao.rootfs.RunLog

/**
 * 应用入口。
 *
 * 存在的**唯一理由**：保证 [RunLog] 在**任何入口**都被初始化。
 *
 * ## 历史教训（2026-10-06）
 *
 * `RunLog.init()` 原先只在 `TerminalActivity` 里调用。WebView 时代用户必然先进
 * 终端页，所以日志一直有；换到 Compose 原生 UI 之后用户**根本不进终端**
 * → `appContext == null` → `RunLog.log()` 把日志**静默丢掉**，跨多轮构建零输出，
 * SSE 断连原因完全无法定位（当时还被误判成"异常分支没触发"）。
 *
 * 放在 Application 里初始化，生命周期覆盖所有 Activity 与进程入口，
 * **以后新增任何入口都不必再记得调 init**——这是根治，不是打补丁。
 */
class ZhengdaoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        RunLog.init(this)
        autoCleanJunk()
    }

    /**
     * 自动清理垃圾（2026-10-08）。
     *
     * 用户原话：「终端的缓存机制还有自动缓存清理、自动清理垃圾信息可以有吗？」
     * ——代码层核实：`CacheCleaner.autoCleanNeeded()` / `maybeNotify()` **全仓库零调用点**，
     * 文档里那条"启动时 >500MB 才清"从未落地（另有 ERRATA 记档）。
     *
     * 这里只接**二档**（宿主侧的临时文件残留），因为它具备"可以无人值守地跑"的全部条件：
     * - 白名单写死在 [com.example.zhengdao.terminal.CacheCleaner.isStaleTempName]，
     *   只认带明确指纹的 `.<16hex>-<8digits>.so` 与 `mat-debug-*.log`；
     * - 24 小时年龄门槛（`TEMP_MIN_AGE_MS`），刚生成的文件不动；
     * - 有活动会话就跳过（可能正在跑安装/编译，那些残留正被持有）；
     * - 不碰 `.hermes/tools`、rootfs 系统层、home 用户数据（三档永不清）。
     *
     * **一档（npm / uv / apt 的官方 CLI 清理）仍然只走设置页那个按钮**：它要在 guest 里跑，
     * 输出应当可见、可中断，而且 `npm cache clean` 之后下次安装必然重新下载——这种"会变慢"
     * 的代价不该由一个静默的后台任务替用户决定。
     */
    private fun autoCleanJunk() {
        runCatching {
            Thread {
                runCatching {
                    val (needed, total) = com.example.zhengdao.terminal.CacheCleaner.autoCleanNeeded(this)
                    if (!needed) return@Thread
                    val freed = com.example.zhengdao.terminal.CacheCleaner.cleanTempFiles(this)
                    RunLog.log(
                        "启动自动清理（二档）: 清理前 ${total}MB，释放 " +
                            "${com.example.zhengdao.terminal.CacheCleaner.bytesToMb(freed)}MB"
                    )
                }
            }.start()
        }
    }
}
