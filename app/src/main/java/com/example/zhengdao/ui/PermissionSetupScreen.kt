// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import androidx.core.net.toUri
import com.example.zhengdao.R
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.zhengdao.terminal.ProotLauncher
import com.example.zhengdao.ui.theme.zhengdaoReady
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import com.example.zhengdao.ui.util.pressClickable
import com.example.zhengdao.util.HumanizeError

// 状态行「已授权／已开启」用的强调色：2026-10-22 起是**青**（纸墨黛青语言删掉了绿通道）
// （浅色 #2C5F54 / 深色 #7FA89B，见 theme/Theme.kt 的 zhengdaoReady()），
// 「青＝可行动」，已授权就是可行动。

/**
 * 首启「权限」页的状态与判据（2026-10-11）。
 *
 * 为什么集中在 object 里：进入本页的判定发生在 [com.example.zhengdao.MainActivity.onCreate]
 * 的**系统对话框之前**（那里不是 Compose 环境，拿不到 LocalContext），页面自身又要用同一套
 * 判定来画状态，两处必须是同一份代码——否则就是项目里反复踩过的「双判」问题
 * （同类教训见 terminal/Workspace.kt:32-37 的 v1.3 E2 消双判）。
 */
object PermissionSetup {

    /** 路由名：叠在起始页之上的一页（与设置页 "settings" 同风格，不用 EnvHealth 的 "-" 连字符名）。 */
    const val ROUTE = "permission_setup"

    /**
     * 引导标记写在哪份 prefs：与首启历史标记同源，即 `"zhengdao-ui"`。
     *
     * ⚠️ **不是** SettingsScreen.kt 里那个 `object Settings` 的 prefs（那份叫 `"zhengdao-settings"`，
     * 存的是工作区模式 / 已装环境版本）——
     * 首启标记历来都在 `"zhengdao-ui"`（`last_route` 等首启状态在那一份；
     * terminal/TerminalPrefs.kt 的一次性提示也用它）。放错盒子以后排查「为什么又弹了」会找不到。
     */
    private const val PREFS = "zhengdao-ui"

    /** 「已经引导过」标记键（含用户点「稍后再说」的情形）。 */
    private const val KEY_GUIDED = "permission_setup_guided"

    /**
     * 通知权限的字面量（与 MainActivity.kt:146 首启那段通知请求一字不差）。
     * 全文件只此一处：判定与 launcher 拉起都用它，免得两处各写一遍字符串、改一处漏一处。
     */
    const val NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"

    /** 是否已经引导过这一页（写过标记就不算首次）。 */
    fun guided(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_GUIDED, false)

    /**
     * 记下「已引导过」。**点过任何一个底部按钮都要写**（「稍后再说」也算数）——
     * 用户要摆脱的就是反复弹，标记只在用户选择过之后才写，见 [shouldGuide] 的说明。
     */
    fun markGuided(ctx: Context) {
        // R-23（审计第⑮刀，2026-10-14）：**必须同步写盘（commit）**，不能用 apply()。
        // 这个标记决定"下次冷启动还叠不叠这一页"，而它是在用户点过底部按钮之后立刻写的：
        // apply() 只把改动排进内存、异步落盘，进程若在这之后被杀（用户点完就杀后台是常态），
        // 标记就丢了 ⇒ 下次冷启动又叠一次引导页 —— 正是要消灭的"反复弹"。
        // 全仓另一处同步写盘的口径见 ui/AgentManifest.kt 的清单缓存（先验签后持久化）。
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            putBoolean(KEY_GUIDED, true)
        }
    }

    /**
     * 共享存储（必需项）是否已授权。
     *
     * 判据：**不做 READ+WRITE 双查**，直接委托 [ProotLauncher.storageGranted] 这个单一判定源。
     * 该实现是**双通道**（第㉕刀，targetSdk 28 → 36）：
     * - targetSdk 36（现行）：查「所有文件访问」（READ/WRITE 运行时权限在 API 30+ 已 inert）；
     * - targetSdk 28（旧口径）：查 WRITE_EXTERNAL_STORAGE（Android 11+ 对 target≤29 保持
     *   legacy 存储视图，授权即可读写共享存储；manifest 另有 requestLegacyExternalStorage）。
     * 若本页自己另查一份 READ+WRITE，用户只授了其中一个时，本页与设置页会给出不同结论
     * （v1.3 E2 修的就是这个）。
     */
    fun storageGranted(ctx: Context): Boolean = ProotLauncher.storageGranted(ctx)

    /**
     * 「所有文件访问」（MANAGE_EXTERNAL_STORAGE）是否已开启。
     *
     * 第㉕刀（2026-10-15）起真话变了：targetSdk 28 时代该开关**只对 target≥30 的 App 存在**、
     * 系统对证道永远返回 false（2026-10-04 修复记录：此前用它做存储判定，恒 false），所以
     * 这一行当时只作**可选增强**展示、绝不参与「必需项就绪」判定。抬到 36 后开关**可达**，
     * 且它就是共享存储的唯一通道 ⇒ 必需项判定已经 [storageGranted] 路由到它（双通道的
     * 前者），本行从"装饰"变成"用户要真去点的那个"。
     */
    fun manageAllFilesGranted(): Boolean =
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    /**
     * 通知权限（POST_NOTIFICATIONS，安卓 13+ 运行时权限，AndroidManifest.xml:31 已声明）。
     *
     * 这里只是**展示状态**：本权限此前就已在首启申请（MainActivity.kt:144-152，requestCode 101），
     * 本次只是把它从「又一个系统对话框」改成列表里的一行，没有新增任何权限申请。
     */
    fun notificationsGranted(ctx: Context): Boolean = runCatching {
        androidx.core.content.ContextCompat.checkSelfPermission(ctx, NOTIFICATIONS_PERMISSION)
    }.getOrDefault(PackageManager.PERMISSION_DENIED) == PackageManager.PERMISSION_GRANTED

    /**
     * 必需项是否有缺。
     *
     * 当前**只有共享存储一项**是必需的：通知缺失只是收不到「装完清理」等提示
     * （真机验收结论记在 MainActivity 首启通知请求上方那段注释里：拒绝不影响任何功能）。
     * 第㉕刀（targetSdk 36）起「所有文件访问」可达，它就是共享存储的唯一通道 ——
     * [storageGranted] 的双通道判定已经把它算进必需项（见该函数 KDoc），无需在此另列。
     */
    fun missingRequired(ctx: Context): Boolean = !storageGranted(ctx)

    /**
     * 首启是否要把这一页叠出来：**没引导过** 且 **必需项有缺**。
     *
     * 引导过（含「稍后再说」）就不再自动出现——反复弹正是用户反馈要摆脱的东西；
     * 之后要改权限走设置页「权限」卡（那份按用户要求保持原样）。
     *
     * ⚠️ 对账留痕（§B 顺序 15，2026-10-14）：审计报告写的是「写过 `KEY_GUIDED` 后
     * `shouldGuide` **恒 false**」——那是**旧文件 `ui/PermissionUi.kt` 的实现**；本实现
     * （v2.0.14 `084c811` 起）已经是「按当前缺哪些权限」判断的，**不再恒 false**。
     * 本刀真正要修的是**另一条通道**（[shouldAutoRequest] 与 MainActivity 里
     * 「已引导过就逐个补请求缺的权限」那段）——那里才是"每次都弹"的来源。
     */
    fun shouldGuide(ctx: Context): Boolean = !guided(ctx) && missingRequired(ctx)

    // ── 「本项权限已自动请求过一次」标记（§B 顺序 15，2026-10-14） ────────────────
    //
    // 为什么需要：MainActivity 冷启动那段在「已引导过」时会逐个补请求缺的权限。
    // 只判"缺不缺"的话，用户**拒绝过**（或系统永远不给，如 targetSdk=28 在部分 ROM 上）
    // 就会**每次冷启动再弹一次**系统框 —— 这正是用户反馈的"每次都申请权限 + 弹通知框"。
    //
    // 为什么不用系统的 `shouldShowRequestPermissionRationale()`：它返回 false 有两种含义
    // （从没问过 / 勾了"不再询问"），无法区分 ⇒ 不可靠。改用自己的一次性标记：
    // **每项权限最多自动请求一次**；之后要补权限走权限页 / 设置页「权限」卡（用户主动点，
    // 不受本判据限制）。

    private const val KEY_STORAGE_ASKED = "perm_storage_asked"
    private const val KEY_NOTIFY_ASKED = "perm_notify_asked"

    /** 共享存储权限是否已经**自动**请求过一次。 */
    fun storageAsked(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_STORAGE_ASKED, false)

    /** 记下「共享存储权限已自动请求过一次」。**必须在发起请求之前写**（请求中途被杀也不会重问）。 */
    fun markStorageAsked(ctx: Context) {
        // R-23（审计第⑮刀，2026-10-14）：上面这行 KDoc 就是判据——"在**发起请求之前**写"，
        // 而 apply() 是异步落盘：请求刚发出、系统框还在屏上时进程被杀，标记没落地 ⇒ 下次冷启动
        // 又请求一次。改 commit() 与 KDoc 对齐（本文件 3 处标记统一走同步写盘）。
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            putBoolean(KEY_STORAGE_ASKED, true)
        }
    }

    /** 通知权限是否已经**自动**请求过一次。 */
    fun notifyAsked(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NOTIFY_ASKED, false)

    /** 记下「通知权限已自动请求过一次」。 */
    fun markNotifyAsked(ctx: Context) {
        // R-23（审计第⑮刀，2026-10-14）：与 [markStorageAsked] 同一理由——"已问过"标记必须在
        // 发起请求之前真正落盘，否则请求过程中被杀就会再问一次。改 commit()（同步写盘）。
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            putBoolean(KEY_NOTIFY_ASKED, true)
        }
    }

    /**
     * 冷启动是否还该**自动**弹这一次系统权限框：**缺权限** 且 **本项从没自动问过**。
     *
     * 抽成纯函数是为了能 JVM 单测（本类别的判定都要 Context，测不了）。真机读数归蓝总管采。
     */
    fun shouldAutoRequest(asked: Boolean, missing: Boolean): Boolean = missing && !asked
}

/**
 * 首启「权限」页（2026-10-11）。
 *
 * 背景：用户反馈「新安装时申请各种权限的页面……单个卡片有点晃眼也不符合使用习惯」。
 * 此前首启是**一次一个系统对话框**串起来弹：READ/WRITE（MainActivity requestCode 100）、
 * POST_NOTIFICATIONS（101）、再叠一个「开启完整存储访问」AlertDialog。现在收成一页：
 * 权限列在**一张卡里的若干行**，每行自己点开授权，底部一个按钮进主界面。
 *
 * 判据与取舍（都是有意为之，改之前先读）：
 * - 视觉语言沿用设置页「权限」卡的排版习惯（SectionCard 的卡片 + SettingRow 的行节奏：
 *   文字 + 右侧状态 + 行尾 ›），不另起一套审美；状态色用 `zhengdaoReady()`——
 *   青＝可行动，浅色 #2C5F54（纸底 6.66:1）、深色 #7FA89B（卡面 5.97:1），
 *   见 ui/theme/Theme.kt（2026-10-22 第一百三十七批：绿色已从语言里删除）。
 * - 未授权时点整行直接去授权；已授权时点整行去系统「应用详情」看/改（权限已给，
 *   再拉一次请求系统会直接回「已授予」，表现就是点了没反应）。
 * - 「网络访问」是安装时权限，**行不可点、不画 ›**：没事可做的地方给一个能点的行，
 *   比不给点更糟（点了没反应，用户会以为坏了）。
 * - 底部只有一个主按钮，文案随状态变：「完成，进入证道」（必需项已就绪）／
 *   「稍后再说」（未就绪也能进）。两个文案做的事相同（记已引导 + 进主界面），
 *   区别只在显示的那句话是否与当前状态相符——**不拦人**是这个 App 一以贯之的做法
 *   （与首启权限「拒绝不打断使用」同一立场）。
 * - 硬件返回键**也写**已引导标记（§B 顺序 15，2026-10-14 改口径）：按返回退回起始页，但标记
 *   照写。历史口径是「返回不写标记 ⇒ 下次冷启动再出现一次，这是刻意的」，**本刀以验收判据
 *   为准**（判据 = 连续冷启动不得自动弹框；按返回不写标记就必然再弹一次，与判据冲突）。
 *   要主动补权限仍走设置页「权限」卡 —— 首启那个「开启完整存储访问」AlertDialog 已撤
 *   （见 MainActivity 的说明），设置页是唯一的"随时可达"权限入口。
 *
 * 系统栏由本页自行避让（edge-to-edge 下的既有约定，各页面各自补偿，见 MainActivity 的入口注释）；
 * 返回前台（从系统授权页回来）时重读一次状态，见下面的 ON_RESUME 观察者。
 */
@Composable
fun PermissionSetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val res = LocalResources.current

    // §B 顺序 15（2026-10-14）：硬件返回键**也写已引导标记**。
    // 原口径是「返回不写标记 ⇒ 下次冷启动再出现一次」（历史留痕见本页 KDoc），但它与验收
    // 判据「连续冷启动不得自动弹框」直接冲突——按返回的人下次一开机又会见到这一页。
    // 处置：以判据为准 —— 返回等同「稍后再说」（两者都写标记）；要主动补权限走设置页「权限」卡。
    androidx.activity.compose.BackHandler {
        PermissionSetup.markGuided(ctx)
        onDone()
    }

    // 这三项由三个不同的系统页/对话框改变，且返回顺序不可预期，
    // 统一在 ON_RESUME 与每个 launcher 的回执里重读（不用单一 tick——各自重读更抗"只变了一项"）。
    var storageOk by remember { mutableStateOf(PermissionSetup.storageGranted(ctx)) }
    var cameraOk by remember {
        mutableStateOf(
            ctx.checkSelfPermission(android.Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var notifyOk by remember { mutableStateOf(PermissionSetup.notificationsGranted(ctx)) }
    // 授权失败的人话提示（照 SettingsScreen 权限卡的 permHint 写法）：红字，成功时清空。
    var hint by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        storageOk = PermissionSetup.storageGranted(ctx)
        cameraOk = ctx.checkSelfPermission(android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        notifyOk = PermissionSetup.notificationsGranted(ctx)
    }

    // 存储读写：RequestMultiplePermissions（与设置页 permLauncher 同一契约）。
    // 回执里**重新读系统状态**而不是信 result：部分 ROM 会把弹窗静默掉，
    // 而 result 只在"弹过了"时才有值。
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraOk = granted || ctx.checkSelfPermission(android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refresh()
        hint = if (PermissionSetup.storageGranted(ctx)) {
            res.getString(R.string.data_perm_granted)
        } else {
            res.getString(R.string.perm_hint_storage_denied)
        }
    }
    // 134 批：minSdk=36 ⇒ READ/WRITE_EXTERNAL_STORAGE 请求分支已删（死代码，lint ObsoleteSdkInt）。
    // permLauncher 保留是历史契约（与设置页同名同签名），当前无调用点；不删是为了不牵动
    // 「权限页与设置页同一套 launcher」这条对照，真正该删时连同两边一起删。

    // 通知：单个权限用 RequestPermission 即可。
    val notifyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refresh()
        hint = if (PermissionSetup.notificationsGranted(ctx)) {
            null
        } else {
            res.getString(R.string.perm_hint_notify_denied)
        }
    }

    // 从系统页回来（「所有文件访问」设置页、应用详情页）重读状态：
    // 这些页面没有回执回调，只能靠 ON_RESUME（与 SettingsScreen 权限卡里的重读同款做法）。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // 系统「应用详情」页：已授权的行点进去看/改权限。
    val openAppDetails: () -> Unit = {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData("package:${ctx.packageName}".toUri())
            )
        }.onFailure {
            Toast.makeText(ctx, res.getString(R.string.perm_open_failed, HumanizeError.title(it, res)), Toast.LENGTH_SHORT).show()
        }
    }

    // 「所有文件访问」系统页：优先带包名的专属页，个别 ROM 没有这个 Activity 时回落通用页
    //（与 HomeScreen.openStorageGrant / SettingsScreen.launchManage 同一行为，三处必须一致）。
    val openManageAllFiles: () -> Unit = {
        val ok = runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData("package:${ctx.packageName}".toUri())
            )
        }.isSuccess
        if (!ok) {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }.onFailure {
                Toast.makeText(ctx, res.getString(R.string.perm_open_failed, HumanizeError.title(it, res)), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val allReady = storageOk

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 上半屏可滚（小屏上四行也能看全），底部按钮固定——按钮被滚出屏幕等于用户找不到出口。
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.perm_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.perm_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            // 卡片标题叫「授权状态」而不是「权限」：页头已经是大字「权限」，
            // 上下再来一个「权限」就是设置页犯过的重复（旧版外层"设置"与页内大字"设置"叠着，
            // 见 MainActivity 里设置页顶栏那段注释）。
            SectionCard(stringResource(R.string.perm_status_card)) {
                PermissionRow(
                    label = stringResource(R.string.perm_storage_label),
                    granted = storageOk,
                    status = if (storageOk) stringResource(R.string.data_perm_granted)
                    else stringResource(R.string.data_perm_not_granted),
                    desc = stringResource(R.string.perm_storage_detail),
                    onClick = {
                        // 第㉕刀（targetSdk 36）：READ/WRITE 运行时权限在 30+ 已 inert，
                        // 点下去只会被静默拒绝、用户看到"点了没反应" ⇒ 直接送他去
                        // 「所有文件访问」系统页（共享存储 36 上的唯一通道）。
                        // 134 批：原 `SDK_INT >= 30` 分支恒真（minSdk=36），旧版请求分支是死代码，
                        // 已按 lint ObsoleteSdkInt 删除。
                        if (storageOk) openAppDetails() else openManageAllFiles()
                    },
                )
                RowGapDivider()
                // 2026-10-23 用户：「那两个权限其实是一个意思，第二个可以改成相机权限」。
                // 存储那两行（共享存储 + 所有文件访问）语义重叠，第二行改相机（CAMERA 已在清单里）。
                PermissionRow(
                    label = stringResource(R.string.perm_camera_label),
                    granted = cameraOk,
                    status = if (cameraOk) stringResource(R.string.data_perm_granted)
                    else stringResource(R.string.data_perm_not_granted),
                    desc = stringResource(R.string.perm_camera_detail),
                    onClick = {
                        if (cameraOk) {
                            openAppDetails()
                        } else {
                            cameraLauncher.launch(android.Manifest.permission.CAMERA)
                        }
                    },
                )
                RowGapDivider()
                PermissionRow(
                    label = stringResource(R.string.perm_notify_label),
                    granted = notifyOk,
                    status = if (notifyOk) stringResource(R.string.data_perm_granted)
                    else stringResource(R.string.data_perm_not_granted),
                    desc = stringResource(R.string.perm_notify_detail),
                    onClick = {
                        if (notifyOk) {
                            openAppDetails()
                        } else {
                            notifyLauncher.launch(PermissionSetup.NOTIFICATIONS_PERMISSION)
                        }
                    },
                )
                RowGapDivider()
                // 网络访问：安装时权限（INTERNET），必然已授予 ⇒ granted 恒 true、onClick = null
                //（不可点、不画 ›：没事可做的地方给个能点的行，点了没反应比不给点更糟）
                PermissionRow(
                    label = stringResource(R.string.perm_net_label),
                    granted = true,
                    status = stringResource(R.string.perm_net_value),
                    desc = stringResource(R.string.perm_net_detail),
                    onClick = null,
                )
            }

            hint?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    // P2-11：错误正文 zhengdaoErrorText()（error 色 3.55:1 不达 AA）
                    color = zhengdaoErrorText(),
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Button(
                onClick = {
                    // 点过就走人（含「稍后再说」）：标记只在这里写，见 PermissionSetup.markGuided 说明
                    PermissionSetup.markGuided(ctx)
                    onDone()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(
                    text = if (allReady) stringResource(R.string.perm_done)
                    else stringResource(R.string.perm_later),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (!allReady) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.perm_storage_required_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * 权限行：一行标题 + 右侧状态 + 一行小字说明，可选点击（[onClick] 为 null 时不可点、不画 ›）。
 *
 * 为什么不直接复用 SettingsScreen 的 `internal fun SettingRow`：SettingRow 的行尾**恒有 › 且恒可点**，
 * 而本页的「网络访问」是纯展示行、每行还要多带一行小字说明。改 SettingRow 会波及设置页
 * （用户明确要求设置页保持原样），故这里写本页专用的等价小组件，排版节奏与之一致。
 */
@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    status: String,
    desc: String,
    onClick: (() -> Unit)?,
) {
    val base = Modifier.fillMaxWidth()
    Column(
        modifier = (if (onClick != null) {
            base.pressClickable(onClickLabel = label, onClick = onClick)
        } else {
            base
        }).padding(vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                // P2-11：绿侧早已用语义色，红侧对齐（error 色 3.55:1 不达 AA）
                color = if (granted) zhengdaoReady() else zhengdaoErrorText(),
            )
            if (onClick != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "›",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 行间分隔：与设置页权限卡里那处 `0.5.dp + outlineVariant` 的分隔线同款。 */
@Composable
private fun RowGapDivider() {
    Spacer(Modifier.height(2.dp))
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(2.dp))
}
