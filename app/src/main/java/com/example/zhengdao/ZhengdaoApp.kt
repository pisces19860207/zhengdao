// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao

import android.app.Application
import android.os.StrictMode
import com.example.zhengdao.core.RunLog
import com.example.zhengdao.core.ZhengdaoDirs

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
        // 第二百六十四批：debug 包装 StrictMode 主线程读盘探针（见 [installMainThreadDiskReadProbe]）。
        // 必须**最先**装：它要覆盖下面 RunLog.init 与各 install 里的每一处主线程读盘。
        installMainThreadDiskReadProbe()
        // 第二百四十批：RunLog 的「公共区老日志目录」与「单文件搬家」两件事改成注入
        // （先断环：RunLog 原先直接调 Store，而 Store 又调 RunLog，两者互为依赖）。
        RunLog.init(
            this,
            legacyLogsDir = { ZhengdaoDirs.logsDir(it) },
            adoptFile = ZhengdaoDirs::adoptFile,
        )
        // 第一百六十九批 §3：完成通知只在后台发 ⇒ 这里装前台探针（只数 started，不做别的）。
        AppForeground.install(this)
        // 第一百八十二批：知识库自动刷新（启动/回前台增量 ＋ MediaStore 可选加速 ＋ 前台周期兜底）。
        com.example.zhengdao.baopu.KnowledgeRefresh.install(this)
        // 第二百九十一批（第二刀）：御医体检的月位——一个月一次、非精确、只在充电时跑。
        // 这里只**排**（KEEP 幂等，重复开 App 不会把周期清零）；跑不跑由 Worker 里的月位闸判。
        com.example.zhengdao.baopu.inspect.HealthScheduler.ensureScheduled(this)
        // 第二百九十批：斥候——首启/版本变化/回前台（权限变更）后台探一遍"本机能干什么"，
        // 产 `Opinion(source=SCOUT)` ＋ 一页能力清单（`<工作区>/记忆/能力清单.md`）。
        // 必须**在 AppForeground.install 之后**：它要挂前/后台边沿监听（回前台＝用户可能刚改过权限）。
        com.example.zhengdao.baopu.scout.Scout.install(this)
        initWebViewDataDirectory()
        logSignature()
        keepalive()
    }

    // 2026-10-16 旧抱朴线清场：原先这里还有 registerOcShutdownOnFinish()
    // （第⑲刀 issue #14 P1-6 方案③：主页 Activity 结束时收 oc serve）。serve 本身已整条删除
    // （docs/ERRATA.md E-140），收它自然无从谈起 ⇒ 回调一并删除。这里留一行说明，免得以后
    // 有人翻到「App 退出要收什么」时重新发明一遍。

    /**
     * WebView 数据目录后缀（第⑨刀 B · 玄览阶段 2）——**进程初始化处统一设**。
     *
     * 为什么在这里：`WebView.setDataDirectorySuffix` 是**静态、每进程一次性**，必须在
     * 任何 WebView 创建前调用（② AOSP `WebView.java`："This API must be called before any
     * instances of WebView are created in this process and before any other methods in the
     * android.webkit package are called by this process"，迟了抛 IllegalStateException）。
     * `Application.onCreate` 是全进程最早的代码点。
     *
     * 后缀取值 `xuanlan`：把玄览的 WebView 数据（cookie/cache/localStorage）收进
     * `app_webview_xuanlan/` 独立目录——命名归属清晰，且将来若有第二个用 WebView 的
     * 进程不会撞默认目录。
     *
     * ⚠️ **单进程内多标签不可能各有数据目录**（同 API 的进程级语义决定）：标签间
     * cookie/storage 共享，与桌面浏览器普通标签一致；每标签隔离的是 WebView 实例本身
     * （历史栈/页面状态/JS 上下文/渲染优先级），见 BrowserTabManager 的 KDoc。
     *
     * 失败不致命：个别 WebView provider 不支持时 runCatching 兜住（日志留痕），
     * 浏览器功能本身不依赖这个后缀。
     */
    private fun initWebViewDataDirectory() {
        runCatching {
            android.webkit.WebView.setDataDirectorySuffix("xuanlan")
            RunLog.log("WebView 数据目录后缀 = xuanlan（进程级，玄览阶段 2）")
        }.onFailure { e ->
            RunLog.log("⚠️ WebView setDataDirectorySuffix 失败（不影响浏览器功能）：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * 保活留档（#5，2026-10-09）。
     *
     * 分两半，**同步 / 异步的界线是有意的**：
     * - [com.example.zhengdao.keepalive.KeepaliveWatcher.install] 同步跑（注册生命周期回调、
     *   `onTrimMemory`、未捕获异常处理器、巡检线程）——它不读磁盘也不起进程，必须赶在
     *   第一个 Activity 之前装好，否则"用户刚进界面就被杀"这一档会漏；
     * - [com.example.zhengdao.keepalive.KeepaliveArchive.onStartup] 丢后台线程——它要
     *   `logcat -d`（起一个进程）并读写若干文件，放在 `onCreate` 里会拖着冷启动。
     *   顺序上它**必须在 RunLog 之后**：归档出来的结论本身也要进日志。
     */
    private fun keepalive() {
        runCatching {
            com.example.zhengdao.keepalive.KeepaliveWatcher.install(this)
            Thread {
                runCatching {
                    com.example.zhengdao.keepalive.KeepaliveArchive.onStartup(this)
                    com.example.zhengdao.keepalive.KeepaliveWatcher.heartbeat(this, "进程启动（归档完成）")
                }
            }.apply { isDaemon = true; name = "zhengdao-keepalive-archive" }.start()
        }
    }

    /**
     * 启动签名自检（2026-10-09，ERRATA E-065）。
     *
     * 只落一行日志 —— 用户看得见的那份提示在 `MainActivity`（非官方包启动时弹一次）
     * 与设置页「关于」（常驻一行来源）。放这里是因为 [RunLog] 刚 init 完，
     * 日志是任何入口（含终端独立 Activity）都会有的共同出口。
     */
    private fun logSignature() {
        runCatching {
            when (com.example.zhengdao.util.SigningCheck.check(this)) {
                com.example.zhengdao.util.SigningCheck.Result.OFFICIAL ->
                    RunLog.log("启动自检：安装包签名 = 官方 ✓")
                com.example.zhengdao.util.SigningCheck.Result.UNOFFICIAL ->
                    RunLog.log("⚠️ 启动自检：安装包签名不是官方的 —— 这个包被重新签过名（重打包）。如果你不是从作者 GitHub 下载的，建议换回官方包。")
                com.example.zhengdao.util.SigningCheck.Result.UNKNOWN ->
                    RunLog.log("启动自检：读不到安装包签名（已跳过）")
            }
        }
    }

    // 第二百四十八批（第四刀）：`autoCleanJunk()`（缓存清理 + 旧代迁移 + 通知）随终端一起搬到
    // zhengdao-term —— 它清的是 rootfs/agent 缓存，主仓已经没有那些目录了。
    // 第二百四十八批（第四刀）：`autoCleanJunk()`（缓存清理 + 旧代迁移 + 通知）随终端一起搬到
    // zhengdao-term —— 它清的是 rootfs/agent 缓存，主仓已经没有那些目录了。

    /**
     * DEBUG 包的**主线程读盘探针**（第二百六十四批的证据装置，release 不装）。
     *
     * 为什么要有：判据是「StrictMode `detectDiskReads` 不再报」，而这句话只有在真机上带
     * 日志才验得了。`penaltyLog` 会把每一处违规打成一行，形如
     * `StrictMode policy violation; ~duration=12 ms: android.os.StrictMode$StrictModeDiskReadViolation`，
     * 其中 `~duration` 就是这一处读盘在主线程上真正耗掉的时间——改动前后拿它做读数。
     *
     * 只开 `detectDiskReads`（本批只治读盘）：写盘不在这一批的判据里，全开会把日志淹掉。
     * `StrictMode.setThreadPolicy` 只作用于**调用它的线程**（这里就是主线程），恰好是我们要的。
     * release 不装：StrictMode 有运行时开销，且 logcat 是外送面（N-4 的收窄口径）。
     */
    private fun installMainThreadDiskReadProbe() {
        if (!BuildConfig.DEBUG) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .penaltyLog()
                .build(),
        )
    }
}
