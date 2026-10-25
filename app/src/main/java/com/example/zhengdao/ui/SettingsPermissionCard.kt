// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.zhengdao.R
import com.example.zhengdao.schedule.SchedulePermissions
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import com.example.zhengdao.ui.theme.zhengdaoReady
import com.example.zhengdao.util.HumanizeError

/**
 * 「申请权限」集中卡（第二百一十批 · 入口与权限归位）。
 *
 * 用户 2026-10-25 三条要求里的第二条：「定时任务的权限等等各种权限申请应该放到证道 APP 的
 * 设置里弄一个申请权限的卡」。落地成 Hub 顶部这一张卡，四行：
 * 所有文件访问 / 通知 / 精确闹钟 / 电池优化豁免。
 *
 * ## 为什么这张卡是「申请入口唯一化」的那个唯一
 *
 * 同批把散落各处的申请入口全部收了回来：主页「所有文件访问」按钮、终端与资料页的
 * 定时任务三项权限行、首启权限页（PermissionSetupScreen 整页退役）、定时任务页的
 * 「去设置」按钮——都改成一行「去设置 → 申请权限」指路文案。**权限状态仍然到处可以显示**
 * （体检、设置 Hub 的「权限待处理」、定时任务页顶部的路线行都照旧），但**发起申请**只此一处。
 * 源码门禁见 `PermissionEntryGateTest`：四类申请 API（`ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`、
 * 通知的运行时请求、`ACTION_REQUEST_SCHEDULE_EXACT_ALARM`、电池优化两件套）只允许出现在本文件。
 *
 * ## 判定不改，只搬入口
 *
 * 四行状态全部委托既有单一判定源，本文件不新写任何一条判据：
 * - 所有文件访问 ⇒ [PermissionSetup.manageAllFilesGranted]（`isExternalStorageManager` 的唯一封装）；
 * - 通知 ⇒ [PermissionSetup.notificationsGranted]（`SchedulePermissions` 也委托它，两处同源）；
 * - 精确闹钟 ⇒ [SchedulePermissions.canScheduleExactAlarms]（调度器排闹钟时用的同一份）；
 * - 电池豁免 ⇒ [SchedulePermissions.isIgnoringBatteryOptimizations]。
 *
 * 原来散在 `schedule/SchedulePermissions.kt` 里的三个「系统设置页 Intent 构造」
 * （精确闹钟 / 电池列表 / 应用详情）随本批搬进本文件——那是入口不是判定，调度与降级
 * 判决一格未动（见该文件 KDoc 的留痕）。
 *
 * ## 每一行都要说清「不给会怎样」
 *
 * 四行的说明文字直接复用既有文案（`data_manage_hint` / `sched_perm_*_desc`）——
 * 那些句子本来就写着降级口径，本次只是换了住处，一个字没改。
 *
 * 已授予的行点进去是**系统应用详情页**（看/撤），不再拉一次请求：再拉系统直接回
 * 「已授予」，表现就是点了没反应。精确闹钟与电池豁免给不给都是同一个系统页
 * （专属开关页 / 列表页），不搞两套去路。
 */
@Composable
fun SettingsPermissionCard() {
    val ctx = LocalContext.current
    val res = LocalResources.current

    // 四项状态各自现读；从系统页回来没有回执，统一在 ON_RESUME 与通知请求的回执里重读
    // （与设置页权限卡同款做法：拒绝后回来要显示未给，不许缓存旧值）。
    var allFilesOk by remember { mutableStateOf(PermissionSetup.manageAllFilesGranted()) }
    var notifyOk by remember { mutableStateOf(PermissionSetup.notificationsGranted(ctx)) }
    var exactOk by remember { mutableStateOf(SchedulePermissions.canScheduleExactAlarms(ctx)) }
    var batteryOk by remember { mutableStateOf(SchedulePermissions.isIgnoringBatteryOptimizations(ctx)) }

    fun refresh() {
        allFilesOk = PermissionSetup.manageAllFilesGranted()
        notifyOk = PermissionSetup.notificationsGranted(ctx)
        exactOk = SchedulePermissions.canScheduleExactAlarms(ctx)
        batteryOk = SchedulePermissions.isIgnoringBatteryOptimizations(ctx)
    }

    // 通知：单个运行时权限用 RequestPermission 契约；回执里重新读系统状态，
    // 不信 result——部分 ROM 会把弹窗静默掉，result 只在"弹过了"时才有值。
    val notifyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // ── 四个系统页Intent（第二百一十批从 schedule/SchedulePermissions.kt 搬来）──
    // 打开失败必须说出来（人话 Toast），不许点了没反应还不吭声。

    /** 系统「应用详情」页：已授予的行点进去看/改/撤。 */
    val openAppDetails: () -> Unit = {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData("package:${ctx.packageName}".toUri())
            )
        }.onFailure { toastOpenFailed(ctx, res, it) }
    }

    /**
     * 「所有文件访问」系统页：优先带包名的专属页，个别 ROM 没有这个 Activity 时
     * 回落通用列表页（与退役前 HomeScreen / PermissionSetupScreen / SettingsDataActivity
     * 三处同一行为，现在收成这一处）。
     */
    val openAllFilesPage: () -> Unit = {
        val ok = runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData("package:${ctx.packageName}".toUri())
            )
        }.isSuccess
        if (!ok) {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }.onFailure { toastOpenFailed(ctx, res, it) }
        }
    }

    /** 「精确闹钟」系统设置页：Android 12 起只能由用户去那里允许，运行时弹窗给不了。 */
    val openExactAlarmPage: () -> Unit = {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData("package:${ctx.packageName}".toUri())
            )
        }.onFailure { toastOpenFailed(ctx, res, it) }
    }

    /**
     * 「忽略电池优化」的**列表页**——刻意不用 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
     * 那个一键框（134 批 lint(BatteryLife) 口径：不诱导授权，用户自己在列表里找）。
     */
    val openBatteryListPage: () -> Unit = {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }.onFailure { toastOpenFailed(ctx, res, it) }
    }

    // 卡级一句：不给会怎样的总口径（四行各自还有更具体的说法）。
    Text(
        text = stringResource(R.string.settings_perm_card_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))

    // ── 行 1：所有文件访问（全文件/搜索用）──
    SettingRow(
        label = stringResource(R.string.data_manage_row),
        value = if (allFilesOk) stringResource(R.string.data_manage_on)
        else stringResource(R.string.data_manage_off),
        valueColor = if (allFilesOk) zhengdaoReady()
        else MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = { if (allFilesOk) openAppDetails() else openAllFilesPage() },
    )
    Text(
        text = stringResource(R.string.data_manage_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(10.dp))
    SettingsDivider()
    Spacer(Modifier.height(10.dp))

    // ── 行 2：通知（定时任务到点/失败提示、装完清理提示都走它）──
    SettingRow(
        label = stringResource(R.string.sched_perm_notify),
        value = if (notifyOk) stringResource(R.string.data_perm_granted)
        else stringResource(R.string.data_perm_not_granted),
        valueColor = if (notifyOk) zhengdaoReady() else zhengdaoErrorText(),
        onClick = {
            // 已给 ⇒ 去系统页看/撤；未给 ⇒ 就地拉起运行时请求（本 App 唯一的通知申请点）。
            if (notifyOk) openAppDetails()
            else notifyLauncher.launch(PermissionSetup.NOTIFICATIONS_PERMISSION)
        },
    )
    Text(
        text = stringResource(R.string.sched_perm_notify_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(10.dp))
    SettingsDivider()
    Spacer(Modifier.height(10.dp))

    // ── 行 3：精确闹钟（定时任务准点打响）──
    SettingRow(
        label = stringResource(R.string.sched_perm_exact),
        value = if (exactOk) stringResource(R.string.data_perm_granted)
        else stringResource(R.string.data_perm_not_granted),
        valueColor = if (exactOk) zhengdaoReady() else zhengdaoErrorText(),
        // 这一项不是运行时权限弹窗能给的（Android 12 起只能由用户去系统页允许），
        // 给了/没给都是同一个去向：那张专属页上就是这一个开关。
        onClick = { openExactAlarmPage() },
    )
    Text(
        text = stringResource(R.string.sched_perm_exact_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(10.dp))
    SettingsDivider()
    Spacer(Modifier.height(10.dp))

    // ── 行 4：电池优化豁免（可选，只给入口不诱导）──
    SettingRow(
        label = stringResource(R.string.sched_perm_battery),
        value = if (batteryOk) stringResource(R.string.sched_state_exempt)
        else stringResource(R.string.sched_state_not_exempt),
        // 豁免与否都不是"错误"：没豁免只是可能被杀，用中性色，不用红字吓人。
        valueColor = if (batteryOk) zhengdaoReady()
        else MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = { openBatteryListPage() },
    )
    Text(
        text = stringResource(R.string.sched_perm_battery_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 打开系统页失败的人话提示（异常原文进日志，用户看的给人话）。 */
private fun toastOpenFailed(ctx: Context, res: Resources, t: Throwable) {
    Toast.makeText(
        ctx,
        res.getString(R.string.perm_open_failed, HumanizeError.title(t, res)),
        Toast.LENGTH_SHORT,
    ).show()
}
