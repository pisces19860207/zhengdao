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
    }
}
