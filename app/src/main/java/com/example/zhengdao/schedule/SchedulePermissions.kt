// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.schedule

import android.app.AlarmManager
import android.content.Context
import android.os.PowerManager
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
 *
 * ## 第二百一十批（2026-10-25 · 入口与权限归位）：三个「系统设置页 Intent 构造」已搬走
 *
 * 原 `exactAlarmSettingsIntent` / `batterySettingsIntent` / `appDetailsIntent` 三个函数
 * 随「申请入口唯一化」搬进 `ui/SettingsPermissionCard.kt`（设置页「申请权限」卡）——
 * 它们是**入口**不是判定：用户 2026-10-25 要求「各种权限申请放到设置里弄一个申请权限的卡」，
 * 源码门禁（`PermissionEntryGateTest`）钉死四类申请 API 只允许出现在那张卡的实现文件里。
 * 本文件剩下的四个函数（三个判定 + 一个路由）一格未动，调度与降级照旧。
 * 历史对照：定时任务页原来那个「去设置」按钮（发精确闹钟专属页 Intent）同批改成
 * 一行「去『设置 → 申请权限』」指路文案；终端与资料页的定时任务三项权限行整卡撤掉。
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
}
