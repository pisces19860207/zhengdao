// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.schedule

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri
import com.example.zhengdao.ui.PermissionSetup

/**
 * 定时任务要用的三项权限，判定只在这一个 object 里（派工单 §2）。
 *
 * 为什么单独建一份而不是往 `PermissionSetup` 里塞：`PermissionSetup` 管的是首启那一套
 * （存储 / 相机 / 通知 / 网络），它的「必需项」口径牵动首启叠不叠页；本批这三项是**功能级可选项**，
 * 缺了只是走降级路径，不能把首启引导判据搅浑。通知那一项**不另立判定**——
 * 直接委托既有单一来源 [PermissionSetup.notificationsGranted]，免得两处各查一遍得出不同结论
 * （本仓 E2「消双判」的同款教训）。
 *
 * 三个降级口子（页面上每一行都要把这个说出来，不许让用户猜）：
 * - 不给通知 ⇒ 任务照跑，只落会话，不弹通知；
 * - 不给精确闹钟 ⇒ 走 [ScheduleRules.Route.WORK_ONLY]，到点可能推迟（页面明示「可能不准点」）；
 * - 不给电池优化豁免 ⇒ 后台可能被杀，靠 WorkManager 重排与下次打开补跑兜。
 */
object SchedulePermissions {

    /**
     * 精确闹钟权限的字面量。
     *
     * 只声明 `SCHEDULE_EXACT_ALARM`，**不声明** `USE_EXACT_ALARM`：后者是装机即授、用户撤不掉的
     * normal 权限，Google Play 把它划给「闹钟/日历是核心功能」的 App，且要求提交时做权限声明
     * （2026-10-24 核到的口径见交付报告 §0.6）。一条 AI 会话提醒不够格，宁可不准点也不占这个名额。
     */
    const val EXACT_ALARM_PERMISSION = "android.permission.SCHEDULE_EXACT_ALARM"

    /** 通知（Android 13+ 运行时权限）——委托既有判定源，本批不新增申请点。 */
    fun notificationsGranted(context: Context): Boolean =
        PermissionSetup.notificationsGranted(context)

    /**
     * 能不能排精确闹钟。
     *
     * Android 14（targetSdk≥33 的新装应用）起**默认拒绝**，且缺权限时
     * `setExactAndAllowWhileIdle` 是**抛 SecurityException**、不是悄悄降级——
     * 所以这里先问、调度器那里再兜一层 catch，两处都判才算稳。
     */
    fun canScheduleExactAlarms(context: Context): Boolean = runCatching {
        context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms()
    }.getOrDefault(false) == true

    /** 是否已被用户豁免电池优化（可选项，只给入口不诱导）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false) == true

    /** 当前该走哪条触发路径（调度与页面文案共用）。 */
    fun route(context: Context): ScheduleRules.Route =
        ScheduleRules.routeOf(canScheduleExactAlarms(context))

    /**
     * 「精确闹钟」系统设置页：优先带包名的专属页（用户在那儿给/撤），
     * 个别 ROM 没这个 Activity 时回落应用详情页（与 `PermissionSetupScreen.openManageAllFiles`
     * 同一处理形状：回落失败要说不出来，不许静默）。
     */
    fun exactAlarmSettingsIntent(context: Context): Intent =
        runCatching {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData("package:${context.packageName}".toUri())
        }.getOrDefault(appDetailsIntent(context))

    /** 「忽略电池优化」的**列表页**（不是 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 那个一键框）。 */
    fun batterySettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData("package:${context.packageName}".toUri())
}
