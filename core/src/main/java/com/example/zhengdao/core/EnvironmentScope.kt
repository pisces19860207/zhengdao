// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 依据的公开接口：Android 官方文档（android.os.Environment#isExternalStorageManager，
//   API 30+；Android 11 起 READ/WRITE_EXTERNAL_STORAGE 对 targetSdk≥30 已 inert）。
package com.example.zhengdao.core

import android.content.Context

/**
 * 「手机存储可直通」判定 —— **全 App 唯一实现**（第二百四十批：终端解耦第一刀）。
 *
 * 搬自 `terminal/ProotLauncher.storageGranted`（逐字搬，含兼容通道），
 * 从"借终端类"改成 `:core` 自己的一份：抱朴的 `BaopuWorkspace.storageGranted()`
 * 与 `ZhengdaoDirs.storageGranted()` 都委托到这里，终端侧 `ProotLauncher` 反向委托回来，
 * 于是全仓仍然只有**一个**判据（v1.3 E2 消双判的纪律不变）。
 *
 * 判据本身（第㉕刀抬 targetSdk 36 后改为**双通道**，依据 ERRATA E-134 与派工单第㉕刀）：
 *
 * - **targetSdk 36（现行）**：READ/WRITE_EXTERNAL_STORAGE 运行时权限在 API 30+ 已
 *   inert（不再授予共享存储访问，系统对请求直接静默拒绝）⇒ 唯一通道是「所有文件访问」
 *   （[android.os.Environment.isExternalStorageManager]）。
 * - **targetSdk 28（旧口径，分支保留）**：Android 11+ 对 target≤29 的 App 自动保持
 *   legacy 存储视图，拿到 WRITE_EXTERNAL_STORAGE 即可读写共享存储。
 *
 * 历史：2026-10-04 曾因「targetSdk=28 拿不到所有文件访问开关」把判定钉死在 WRITE
 * 权限上（isExternalStorageManager 恒 false）；抬 36 后那个前提消失、开关可达 ⇒
 * 两条都查，任一条真即算通（target 28 设备走后者、target 36 走前者）。
 *
 * ⚠️ 第二百四十批**刻意保留** WRITE 那条兼容通道（而不是只写 isExternalStorageManager）：
 * 老安装（targetSdk 还是 28 时被授予过 WRITE）在升级后权限仍留着自己，此时
 * 只查 `isExternalStorageManager` 会把 true 判成 false —— 那是**语义变化**，不是搬家。
 */
object EnvironmentScope {

    /** 共享存储可直通（所有文件访问 或 旧口径的 WRITE 权限，任一条成立即算通）。 */
    fun hasAllFilesAccess(context: Context): Boolean =
        runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false) ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}
