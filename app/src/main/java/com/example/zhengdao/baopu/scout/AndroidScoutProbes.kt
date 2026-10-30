// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十批 · 斥候的"眼睛"：**Android 读数层**（只产 [ProbeSignal]，不判态、不写账）。
//
// 这一层是全批唯一碰 Android API 的地方，所以边界写在这里：
// - **只问"能不能"，不问"是什么"**：不看相册、不读剪贴板内容、不列任何用户目录；
// - 每一项都 `runCatching`：探针自己失败 ⇒ [ProbeSignal.Failed]（**保守归到"不可用"**，
//   绝不悄悄当成"可以做"——派工单负例「把不可用说成可以做」的靶心）；
// - 不弹窗、不申请权限：首启静默探（派工单「首启 ≤2 秒、后台跑、不弹窗」）。
package com.example.zhengdao.baopu.scout

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.zhengdao.core.EnvironmentScope
import com.example.zhengdao.ui.PermissionSetup
import java.io.File

internal object AndroidScoutProbes {

    /** 设备/版本指纹（判"App 或系统版本变了要不要重探"；不含任何用户数据）。 */
    fun device(ctx: Context): ScoutDevice {
        val pkg = ctx.packageManager
        val versionName = runCatching { pkg.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
        val versionCode = runCatching {
            pkg.getPackageInfo(ctx.packageName, 0).longVersionCode
        }.getOrDefault(0L)
        return ScoutDevice(
            model = Build.MODEL.orEmpty(),
            release = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            versionName = versionName,
            versionCode = versionCode,
        )
    }

    /** 全部探一遍（顺序＝[Capability] 枚举序；每项独立兜底，一项炸了不影响别项）。 */
    fun probeAll(ctx: Context): Map<Capability, ProbeSignal> =
        Capability.entries.associateWith { cap -> probe(ctx, cap) }

    fun probe(ctx: Context, capability: Capability): ProbeSignal = runCatching {
        when (capability) {
            Capability.STORAGE -> storage(ctx)
            Capability.CAMERA -> camera(ctx)
            Capability.NOTIFICATIONS -> notifications(ctx)
            Capability.ACCESSIBILITY -> accessibility(ctx)
            Capability.LOCATION -> location()
            Capability.CLIPBOARD -> clipboard()
            Capability.NETWORK -> network(ctx)
            Capability.BATTERY_OPTIMIZATION -> batteryOptimization(ctx)
            Capability.HEADPHONES -> headphones(ctx)
            Capability.ROOT -> root()
            Capability.VIRTUALIZATION -> virtualization()
        }
    }.getOrElse { e ->
        ProbeSignal.Failed("${e.javaClass.simpleName}: ${e.message.orEmpty()}".trim().trimEnd(':'))
    }

    // ── 各项 ────────────────────────────────────────────────────────────────

    /** 存储：共享存储可直通（复用 :core 的**唯一判定**，不写第二份）。 */
    private fun storage(ctx: Context): ProbeSignal =
        if (EnvironmentScope.hasAllFilesAccess(ctx)) {
            ProbeSignal.Granted()
        } else {
            ProbeSignal.NotGranted("需要「所有文件访问」授权")
        }

    /** 相机：先看有没有硬件，再看权限（没摄像头的手机问权限是白问）。 */
    private fun camera(ctx: Context): ProbeSignal {
        val hasCamera = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        if (!hasCamera) return ProbeSignal.Unsupported("这台手机没有摄像头")
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        return if (granted) ProbeSignal.Granted() else ProbeSignal.NotGranted("相机权限没开")
    }

    /** 通知：运行时权限（13+）＋ 系统里的通知总开关，两者任一没开都算不能用。 */
    private fun notifications(ctx: Context): ProbeSignal {
        if (!PermissionSetup.notificationsGranted(ctx)) {
            return ProbeSignal.NotGranted("通知权限没开")
        }
        val enabled = runCatching {
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        }.getOrDefault(false)
        return if (enabled) ProbeSignal.Granted() else ProbeSignal.NotGranted("通知在系统里被关掉了")
    }

    /** 无障碍：读系统开关本身（**不读任何窗口内容**——那要无障碍服务，抱朴不是）。 */
    private fun accessibility(ctx: Context): ProbeSignal {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return ProbeSignal.Unsupported("这台手机没有无障碍服务")
        return if (am.isEnabled) ProbeSignal.Granted() else ProbeSignal.NotGranted("系统无障碍开关没开")
    }

    /** 位置：本安装包**没有申请**位置权限 ⇒ 如实报"本应用做不了"，不假装成"去开一下就行"。 */
    private fun location(): ProbeSignal = ProbeSignal.Unsupported("本应用没有申请位置权限")

    /** 剪贴板：只报系统规则（前台才可读），**不读内容**。 */
    private fun clipboard(): ProbeSignal = ProbeSignal.Granted(note = "只在前台可用")

    /** 网络：有默认网络且已验证连通才算"能用"。 */
    private fun network(ctx: Context): ProbeSignal {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return ProbeSignal.Unsupported("这台手机没有网络服务")
        val active = cm.activeNetwork ?: return ProbeSignal.NotGranted("现在没有网络")
        val caps = cm.getNetworkCapabilities(active) ?: return ProbeSignal.NotGranted("现在没有网络")
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return ProbeSignal.NotGranted("网络没有互联网")
        }
        return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            ProbeSignal.Granted()
        } else {
            ProbeSignal.NotGranted("网络未连通（可能被限流）")
        }
    }

    /** 电池优化：进白名单才不会被系统掐（不是权限，但同样是"能不能干活"的前提）。 */
    private fun batteryOptimization(ctx: Context): ProbeSignal {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return ProbeSignal.Unsupported("这台手机没有电源服务")
        return if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            ProbeSignal.Granted()
        } else {
            ProbeSignal.NotGranted("后台可能被系统掐断")
        }
    }

    /**
     * 耳机：只看当前音频输出里有没有耳机类设备（**不录音、不听任何东西**）。
     *
     * 没插时归 [ProbeSignal.Unsupported]：这是**当下没有输出设备**，不是"权限没给"——
     * 报成 denied 会让清单写"要用时会问你要不要开"，而耳机根本没有什么可"开"的。
     */
    private fun headphones(ctx: Context): ProbeSignal {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ProbeSignal.Unsupported("这台手机没有音频服务")
        val types = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )
        val present = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrDefault(emptyArray())
            .any { it.type in types }
        return if (present) ProbeSignal.Granted() else ProbeSignal.Unsupported("没插耳机")
    }

    /** root：只看 su 在不在（读文件是否存在＝元数据，不执行、不提权）。 */
    private fun root(): ProbeSignal {
        val candidates = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/sbin/su",
        )
        val found = candidates.firstOrNull { runCatching { File(it).exists() }.getOrDefault(false) }
        return if (found != null) ProbeSignal.Granted(note = "su 在 $found") else ProbeSignal.Unsupported("这台手机没有 root")
    }

    /** 虚拟化：`/dev/kvm` 在且可读才算支持（只看设备节点，不打开它）。 */
    private fun virtualization(): ProbeSignal {
        val kvm = File("/dev/kvm")
        val usable = runCatching { kvm.exists() && kvm.canRead() }.getOrDefault(false)
        return if (usable) ProbeSignal.Granted(note = "/dev/kvm 可用") else ProbeSignal.Unsupported("没有 /dev/kvm（不支持硬件虚拟化）")
    }
}
