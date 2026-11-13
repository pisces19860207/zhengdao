// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.startup

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.example.zhengdao.BuildConfig
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.BaopuWorkspace
import com.example.zhengdao.core.AppPrefs
import com.example.zhengdao.core.RunLog
import java.io.File

/**
 * 装配总闸（Android 侧门面：把 [StartupAssembler] 接上真实的引擎、真实的盘、真实的日志）。
 *
 * ## 一条链路，一个出口
 *
 * ```
 * Application.onCreate ──► StartupGate.install ──► StartupAssembler.ensure
 *                                    │                     │
 *                                    │              成功 ──┴──► Ready ──► 主界面
 *                                    │              失败 ─────► Failed ─► 错误页
 *                                    └──► 诊断自动落盘（产物/启动失败-<时间>.txt）
 * ```
 *
 * 全仓**只有这一处**构造抱朴引擎（`BaopuRuntime.engine`）。在此之前它是两处
 * `runCatching { engine(this) }`（Application 一处、MainActivity 一处），失败只写一行
 * "退回懒建"日志——那不叫处理，叫把失败推给下一个取用它的人（下一次取用恰好在
 * 组合期，于是变成冷启闪退）。629 之后：**装配失败是一个状态，不是一行日志**。
 *
 * ## 界面怎么拿到它
 *
 * [state] 是 Compose 快照状态 ⇒ `StartupRoot` 直接读，点"重试"时页面自己换走，不需要
 * Activity 重建。安全模式**只对本进程有效**（不落盘）：一次装配失败就把用户永久钉在
 * 受限界面上（除非他手动退出）才是真的隐性降级；下次冷启重新装一次，装成了就正常进。
 */
internal object StartupGate {

    /** 上次装配成功的时刻（毫秒）。 */
    private const val KEY_LAST_OK_AT = "startup_last_ok_at"

    /** 上次装配成功时的版本名（用来区分"上个版本还能跑"）。 */
    private const val KEY_LAST_OK_VERSION = "startup_last_ok_version"

    /** debug 包的注入开关文件名（见 [injectIfAsked]）。 */
    private const val INJECT_FILE = "inject-assembly-fail"

    /** 当前装配状态（界面读它决定画哪一屏）。 */
    var state: StartupState by mutableStateOf<StartupState>(StartupState.Idle)
        private set

    /** 最近一份诊断的展示路径（相对工作区，如 `产物/启动失败-20261113-140501.txt`）。 */
    var dumpPath: String? by mutableStateOf<String?>(null)
        private set

    private var assembler: StartupAssembler? = null

    /** 诊断只落一份：同一份失败现场重入（自动落盘 + 用户点按钮）时复用同一个文件。 */
    private val dumpLock = Any()
    private var dumpedFor: StartupFailure? = null

    /**
     * 冷启装机（Application.onCreate 调）。幂等：重复调用不会重装，也不会自动重试。
     */
    fun install(context: Context) = ensure(context)

    /** 确保"这一次启动该装的那一次"已经跑过；跑没跑过由状态机判，不看调用次数。 */
    fun ensure(context: Context) {
        val app = context.applicationContext
        val machine = assembler ?: StartupAssembler(assemble = { assembleEngine(app) })
            .also { assembler = it }
        machine.ensure()
        publish(app)
    }

    /** 错误页的"重试"、安全模式的"退出安全模式"：都是再装一次（判据④）。 */
    fun retry(context: Context) {
        val app = context.applicationContext
        val machine = assembler ?: StartupAssembler(assemble = { assembleEngine(app) })
            .also { assembler = it }
        machine.retry()
        publish(app)
    }

    /** 错误页的"以安全模式继续"（判据③）：只从失败态可进，进去就不再装配。 */
    fun enterSafeMode() {
        val machine = assembler ?: return
        machine.enterSafeMode()
        state = machine.state
        RunLog.log("启动：用户选择安全模式继续（抱朴不启动，只留设置与日志）")
    }

    /**
     * 错误页／安全模式的"导出诊断"：**幂等**，已落过就直接给出同一份路径。
     *
     * 在调用线程上同步写（几 KB 的小文件，一次 `writeText`）——为它再起线程只会让
     * "按钮点了没反应"这个体验问题多出一个竞态。写不出来就返回 null，界面照实说。
     */
    fun exportDiagnostics(context: Context): String? {
        val app = context.applicationContext
        val failure = currentFailure() ?: return dumpPath
        return writeDump(app, failure)
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private fun currentFailure(): StartupFailure? = when (val s = state) {
        is StartupState.Failed -> s.failure
        is StartupState.SafeMode -> s.failure
        StartupState.Idle, StartupState.Ready -> null
    }

    /**
     * 真正的装配动作：**成功行与失败行只在这里分叉**（判据⑤）。
     *
     * `markOk` 与 [StartupLedgerLine.ok] 都在引擎真的返回之后才执行；任何一步抛出，
     * 它们都不会跑 ⇒ 失败路径上不存在"已完成"类账行。这不是靠自觉，是行序决定的。
     */
    private fun assembleEngine(app: Context) {
        injectIfAsked(app)
        BaopuRuntime.engine(app)
        markOk(app)
        RunLog.log(StartupLedgerLine.ok())
    }

    /**
     * debug 包的"装配必炸"注入开关（真机验收判据①用）。
     *
     * 用法（debug 包）：`adb shell run-as <包名> sh -c 'mkdir -p files/baopu && echo 1 > files/baopu/inject-assembly-fail'`
     * 然后强杀重开 App ⇒ 装配在这一步抛出 [InjectedAssemblyFailure] ⇒ 错误页。
     * 删掉那个文件再点"重试" ⇒ 正常进主界面（判据④）。
     *
     * 为什么用文件而不是 SP/BroadcastReceiver：不新增任何外部入口（广播/Activity 都是
     * 攻击面与 lint 预算），且 `BuildConfig.DEBUG` 把整段挡在正式包之外 —— 正式包里
     * 这段连读盘都不会发生。
     */
    private fun injectIfAsked(app: Context) {
        if (!BuildConfig.DEBUG) return
        val flag = File(File(app.filesDir, "baopu"), INJECT_FILE)
        if (flag.isFile) throw InjectedAssemblyFailure("debug 注入开关存在：baopu/$INJECT_FILE")
    }

    /** 记下行成功（只有真成功才写；判据⑤）。 */
    private fun markOk(app: Context) {
        runCatching {
            AppPrefs.prefs(app).edit {
                putLong(KEY_LAST_OK_AT, System.currentTimeMillis())
                putString(KEY_LAST_OK_VERSION, BuildConfig.VERSION_NAME)
            }
        }
    }

    private fun publish(app: Context) {
        val machine = assembler ?: return
        state = machine.state
        (machine.state as? StartupState.Failed)?.let { writeDumpAsync(app, it.failure) }
    }

    /** 失败即自动落盘（后台线程）：用户没点"导出诊断"也留得下证据。 */
    private fun writeDumpAsync(app: Context, failure: StartupFailure) {
        if (dumpedFor == failure) return
        Thread { runCatching { writeDump(app, failure) } }
            .apply { isDaemon = true; name = "zd-startup-dump" }
            .start()
    }

    private fun writeDump(app: Context, failure: StartupFailure): String? = synchronized(dumpLock) {
        val already = dumpPath
        if (dumpedFor == failure && already != null) {
            already
        } else {
            writeFresh(app, failure)
        }
    }

    private fun writeFresh(app: Context, failure: StartupFailure): String? {
        val filesDir = app.filesDir.absolutePath
        // 整段都在 runCatching 里（含 hostDir 解析）：诊断写不出来是"少一份证据"，
        // 绝不许反过来把错误页也弄崩——那才是真正的二次事故。
        val file = runCatching {
            StartupDiagnostics.export(
                dir = File(BaopuWorkspace.hostDir(app), StartupDiagnostics.DIR_NAME),
                facts = facts(app),
                failure = failure,
                sanitize = { line -> RunLog.redact(line, filesDir) },
            )
        }.getOrNull()
        if (file == null) {
            RunLog.log("启动：诊断落盘失败（${StartupDiagnostics.DIR_NAME}/ 写不进去）")
            return null
        }
        dumpedFor = failure
        val shown = "${StartupDiagnostics.DIR_NAME}/${file.name}"
        dumpPath = shown
        RunLog.log("启动：诊断已落盘 $shown")
        return shown
    }

    /** 诊断需要的静态事实（白名单，见 [StartupFacts]）。 */
    private fun facts(app: Context): StartupFacts {
        val prefs = AppPrefs.prefs(app)
        val okAt = prefs.getLong(KEY_LAST_OK_AT, 0L)
        return StartupFacts(
            versionName = BuildConfig.VERSION_NAME,
            gitSha = BuildConfig.GIT_SHA,
            buildTime = BuildConfig.BUILD_TIME,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            lastOkAtMillis = okAt.takeIf { it > 0L },
            lastOkVersion = prefs.getString(KEY_LAST_OK_VERSION, null),
            nowMillis = System.currentTimeMillis(),
        )
    }
}

/**
 * 注入进去的那个异常。
 *
 * 单独一个类型而不是复用 `IllegalStateException`：真机验收时错误页上写的就是类型名
 * （"抱朴没能启动：InjectedAssemblyFailure"），一眼能分清"这是注入验的"还是"真坏了"。
 */
internal class InjectedAssemblyFailure(message: String) : IllegalStateException(message)
