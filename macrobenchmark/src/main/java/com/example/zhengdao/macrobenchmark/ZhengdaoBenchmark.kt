// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.macrobenchmark

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 证道 UI（Compose）真机性能基准。
 *
 * ── 这份基准测的是什么 ────────────────────────────────────────────────
 * 四个场景，每个跑 [ITERATIONS] 轮取中位数：
 *   1. 冷启动            —— 进程被杀后从零拉起，到首屏内容可见（StartupTimingMetric）
 *   2. 抱朴页加载        —— 丹房 → 抱朴 的切页与首屏渲染（FrameTimingMetric）
 *   3. 终端页切换        —— 点「逍遥」（nav_terminal）拉起 TerminalActivity（FrameTimingMetric）
 *   4. 设置页滚动        —— 设置页连续三段滑动的帧耗时（FrameTimingMetric）
 *
 * ── 为什么这么写（几个必须知道的坑）─────────────────────────────────
 * · **必须清栈回主页**：被测 App 会记忆上次页面（SharedPreferences 的 last_route），
 *   而终端是**独立 Activity 且吃掉返回键**。若只是 pressHome + 启动主 Activity，
 *   Android 会把任务栈原样带回，栈顶是终端 → 后面几轮测的根本不是主页。
 *   所以统一用 FLAG_ACTIVITY_CLEAR_TASK 显式重建主页栈（[homeIntent]）。
 * · **等待必须断言**：找不到锚点就 check 失败，绝不静默跑完——沉默的测量比没有更糟
 *   （会产出一份看起来正常、实则没测到东西的报告）。
 * · **锚点选文案而非坐标**：Compose 没有 testTag 也能定位；且换机型/换分辨率不用改。
 *
 * 运行方式见 docs/milestones/ 下的《Compose 性能基线报告》。
 */
@RunWith(AndroidJUnit4::class)
class ZhengdaoBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /**
     * 类级 UiDevice：@Before 里还没有 MacrobenchmarkScope 可用，只能自己取。
     * measureRepeated 的块里同名 device 会解析到 scope 的实例（内层接收者优先），两者等价。
     */
    private val device: UiDevice by lazy {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    /**
     * 每个测试开始前把 App 停回主页。
     *
     * ⚠️ 必须做：被测 App 会记忆上次页面（SharedPreferences.last_route）。上一个
     *    测试若停在「设置」页，冷启动就会直接落在设置页 —— 底栏锚点（抱朴/证道）
     *    自然不存在，coldStartup 必败（真机已复现）。环境状态要靠归位，不能靠运气。
     */
    @Before
    fun parkOnHome() {
        device.pressHome()
        device.executeShellCommand("am start -n $PKG/.MainActivity --activity-clear-task")
        if (!device.waitForAnyText(HOME_TAB, timeoutMs = 10_000)) {
            // 恢复到了二级页 → 返回键退回主页（见 launchHome 的说明）
            device.pressBack()
            device.waitForAnyText(HOME_TAB, timeoutMs = 6_000)
        }
        device.pressHome()
    }

    // ── 1. 冷启动 ──────────────────────────────────────────────────────
    // StartupTimingMetric 给 three 个数：timeToInitialDisplay（首帧/初始化显示）、
    // timeToFullDisplay（内容完全显示，App 需 reportFullyDrawn）、以及总时长。
    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = PKG,
        metrics = listOf(StartupTimingMetric()),
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD,
        // COLD 模式下 killProcess 在 setupBlock 之前发生，此处只能按 Home
        setupBlock = { device.pressHome() },
    ) {
        startActivityAndWait()
        // 首屏锚点：底栏「抱朴」恒在；「证道」是丹房大标题（落设置页时两者都没有，
        // 那种情况下基准会直接失败——这正是我们要的可见失败，见类注释）。
        check(device.waitForAnyText(HOME_TAB, HOME_TITLE)) { "冷启动后 ${SETTLE_MS}ms 内首屏未就绪" }
    }

    // ── 2. 抱朴页加载 ──────────────────────────────────────────────────
    // 测的是「切页 + 抱朴页首屏渲染」的帧耗时：抱朴页要先起 opencode serve 才能进
    // 对话态，等待锚点覆盖三种稳态（已就绪 / 未安装 / 启动失败），任一出现即算渲染完成。
    @Test
    fun baopuTabLoad() = benchmarkRule.measureRepeated(
        packageName = PKG,
        metrics = listOf(FrameTimingMetric()),
        iterations = ITERATIONS,
        startupMode = StartupMode.WARM,
        setupBlock = {
            launchHome()
            ensureDanfangTab()
        },
    ) {
        check(device.tapText(HOME_TAB)) { "未找到底部「$HOME_TAB」Tab" }
        check(
            device.waitForAnyText(
                "描述你的任务…",       // 已就绪：输入框占位文案
                "尚未安装 OpenCode",   // 未安装态
                "OpenCode 启动失败",   // 启动失败态
            )
        ) { "抱朴页 ${SETTLE_MS}ms 内未进入任何稳态" }
    }

    // ── 3. 终端页切换 ──────────────────────────────────────────────────
    // 「终端」直接拉起 TerminalActivity（独立 Activity，无底部导航）。
    //
    // 完成信号 = 终端页自己的**真实锚点**「ESC」可见（`res/layout/term_keys.xml`
    // 的 `term_key_esc`，终端页恒在）。第一百六十九批之前等的是「底栏抱朴消失」——
    // 那条在主页上就已经成立（gone 立即 true），量到的根本不是切换。
    //
    // 判据顺序（派工单 §2）：**先断言起点（主页在、终端锚点不在），再点，再等目标出现**；
    // 任何一步不成立都 fail，绝不「空等即过」。
    @Test
    fun terminalSwitch() = benchmarkRule.measureRepeated(
        packageName = PKG,
        metrics = listOf(FrameTimingMetric()),
        iterations = ITERATIONS,
        startupMode = StartupMode.WARM,
        setupBlock = {
            launchHome()
            ensureDanfangTab()
        },
    ) {
        // 起点：底栏「逍遥」（nav_terminal）在（主页），终端锚点不在（还没进终端页）。
        check(device.waitForText(TERMINAL_TAB, timeoutMs = SETTLE_MS)) { "主页底栏未出现「$TERMINAL_TAB」" }
        check(device.findObject(By.text(TERMINAL_ANCHOR)) == null) {
            "起点已在终端页（锚点「$TERMINAL_ANCHOR」已可见）⇒ 这一轮测不到切换。" +
                "诊断：前台=${device.currentPackageName}，可见标记=[${device.screenMarkers()}]"
        }
        check(device.tapText(TERMINAL_TAB)) { "未找到底部「$TERMINAL_TAB」Tab" }
        check(device.wait(Until.hasObject(By.text(TERMINAL_ANCHOR)), SETTLE_MS)) {
            "点「$TERMINAL_TAB」后 ${SETTLE_MS}ms 内终端锚点「$TERMINAL_ANCHOR」仍未出现。" +
                "诊断：前台=${device.currentPackageName}，可见标记=[${device.screenMarkers()}]"
        }
    }

    /**
     * **负例**：只把 App 拉到主页，**不点「逍遥」**——上面那条基准的锚点等待必须失败。
     *
     * 为什么单列一条：正向跑通只证明「等到了」，证明不了「没变化时会不会也过」。
     * 这里不点任何东西，直接复用同一个锚点等待；若它返回 true，说明锚点判据是假的
     * （例如旧版用主页上也存在的元素当完成信号），本用例当场红。
     *
     * ⚠️ 不用 measureRepeated：页面不动就没有新帧，FrameTimingMetric 会以
     *    「no renderthread slices」失败——那是取不到帧，不是「锚点没区分力」。
     *    负例判的是行为，不是性能，所以只跑一次 UiAutomator 断言。
     */
    @Test
    fun terminalSwitchNegativeUnchangedPageMustFail() {
        device.executeShellCommand("am start -n $PKG/.MainActivity")
        check(device.waitForText(TERMINAL_TAB, timeoutMs = SETTLE_MS)) { "主页底栏未出现「$TERMINAL_TAB」" }
        val appeared = device.wait(Until.hasObject(By.text(TERMINAL_ANCHOR)), NEGATIVE_WAIT_MS)
        check(!appeared) {
            "页面没有切换却等到了「$TERMINAL_ANCHOR」⇒ 锚点不具区分力，正向基准的读数不可信"
        }
    }

    // ── 4. 设置页滚动 ──────────────────────────────────────────────────
    // 只测滚动本身的帧耗时：进页动作放在 setupBlock，避免"打开页面"的一次性开销
    // 混进滚动基线里（两者是不同的优化对象，分开才可比）。
    @Test
    fun settingsScroll() = benchmarkRule.measureRepeated(
        packageName = PKG,
        metrics = listOf(FrameTimingMetric()),
        iterations = ITERATIONS,
        startupMode = StartupMode.WARM,
        setupBlock = {
            launchHome()
            ensureDanfangTab()
            check(device.tapText("设置")) { "未找到「设置」入口" }
            check(device.waitForText("存储占用")) { "设置页未打开" }
        },
    ) {
        device.flingScrollable(SCROLL_TIMES)
    }
}

// ── 常量 ──────────────────────────────────────────────────────────────

private const val PKG = "com.example.zhengdao"

/** 每个场景的重复轮数：5 轮是中位数稳定与总耗时（含 proot 启动）之间的折中。 */
private const val ITERATIONS = 5

/** 单次等待上限（ms）。终端要拉起 PRoot，给得宽一些。 */
private const val SETTLE_MS = 20_000L

/** 设置页滚动段数。 */
private const val SCROLL_TIMES = 3

/** 主页底栏的终端入口文案：`nav_terminal`＝逍遥（2026-10-14 四 Tab 命名定稿）。 */
private const val TERMINAL_TAB = "逍遥"

/** 主页底栏第一格：`nav_taiji`＝抱朴（恒在，冷启动/回主页的就绪锚点）。 */
private const val HOME_TAB = "抱朴"

/** 丹房页大标题（AGENTS Tab 落位后的内容锚点）。 */
private const val HOME_TITLE = "证道"

/**
 * 终端页的真实锚点：`res/layout/term_keys.xml` 里恒在的 `key_esc`（文案「ESC」）。
 * 选它而不是工具栏标题：终端里跑命令时标题可能变，ESC 是物理键行的一部分，页面一拉起就在。
 */
private const val TERMINAL_ANCHOR = "ESC"

/** 负例等待：页面没切换时不该等到锚点；3 秒足够暴露「主页上就有」的假锚点。 */
private const val NEGATIVE_WAIT_MS = 3_000L

/** 轮询间隔（ms）。 */
private const val POLL_MS = 50L

// ── 操作原语 ──────────────────────────────────────────────────────────

/**
 * 显式重建主页任务栈。
 *
 * 为什么不能用默认的 launch intent：任务栈里若残留 TerminalActivity，
 * 单纯启动 MainActivity 只会把整个栈带回前台（栈顶仍是终端）。CLEAR_TASK
 * 先把栈清空，MainActivity 才真的在最上层。
 */
private fun homeIntent(): Intent = Intent(Intent.ACTION_MAIN).apply {
    setClassName(PKG, "$PKG.MainActivity")
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
}

/**
 * 回主页：按 Home → **杀进程** → 清栈启动主 Activity → 等底栏出现。
 *
 * ⚠️ 为什么必须先 killProcess（真机实测，别删）：
 *    上一场景结束时终端页（TerminalActivity）压在栈顶。仅靠
 *    FLAG_ACTIVITY_CLEAR_TASK 清不掉它 —— dumpsys 显示启动后栈顶仍是 TerminalActivity，
 *    基准随即卡死在"找不到主页锚点"。而 force-stop 之后再 CLEAR_TASK 启动，
 *    任务栈会整个重建（t4792 → t4793），栈顶必定是 MainActivity。
 *    代价：每轮都是冷启动后再做被测动作 —— 起点一致，这正是基线想要的。
 */
private fun MacrobenchmarkScope.launchHome() {
    device.pressHome()
    killProcess()
    startActivityAndWait(homeIntent())
    // 先耐心等首屏：冷启动后立刻查无障碍树会偶发落空（Compose 首帧还没上屏）
    if (!device.waitForAnyText(HOME_TAB, timeoutMs = 8_000)) {
        // 恢复到了二级页（设置/插件，都没有底栏）→ 用**系统返回键**退回主页。
        // ⚠️ 别改回「找『返回』文字节点再点」：真机实测这种查找在这里偶发返回 null
        //    （界面明明有「‹ 返回」，诊断却报可见标记=[]），原因未明；而 BACK 是系统级
        //    动作，不依赖无障碍树。二级页按返回 = popBackStack 回主页，不会退出 App。
        device.pressBack()
        if (!device.waitForAnyText(HOME_TAB, timeoutMs = 6_000)) {
            device.pressBack()
            device.waitForAnyText(HOME_TAB, timeoutMs = 6_000)
        }
    }
    check(device.waitForAnyText(HOME_TAB, timeoutMs = 8_000)) {
        "主页未在 8s 内就绪。诊断：前台=${device.currentPackageName}，可见标记=[${device.screenMarkers()}]"
    }
}

/** 失败时把屏幕上真实可见的锚点列出来——猜不如看（这 App 的复位坑已经踩了三次）。 */
private fun UiDevice.screenMarkers(): String = listOf(
    HOME_TAB, HOME_TITLE, "设置", "返回", "存储占用", TERMINAL_TAB, "怡然",
    "ESC", "CTRL", "描述你的任务…", "尚未安装 OpenCode",
).filter { findObject(By.text(it)) != null }.joinToString(",")
/** 保证停在丹房 Tab（抱朴页加载 / 设置入口的起点），起点一致才谈得上对比。 */
private fun MacrobenchmarkScope.ensureDanfangTab() {
    if (device.findObject(By.text("证道")) == null) {
        check(device.tapText("怡然")) { "未找到底部「怡然」Tab（nav_agents，丹房所在页）" }
        check(device.waitForText("证道")) { "丹房页未就绪" }
    }
}

// ── UiAutomator 扩展 ──────────────────────────────────────────────────

/** 等某段文案出现。 */
private fun UiDevice.waitForText(text: String, timeoutMs: Long = SETTLE_MS): Boolean =
    wait(Until.hasObject(By.text(text)), timeoutMs)

/** 等多段文案中任意一段出现（抱朴页有几种稳态，都要认）。 */
private fun UiDevice.waitForAnyText(
    vararg texts: String,
    timeoutMs: Long = SETTLE_MS,
): Boolean {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (SystemClock.uptimeMillis() < deadline) {
        for (t in texts) {
            if (findObject(By.text(t)) != null) return true
        }
        SystemClock.sleep(POLL_MS)
    }
    return false
}

/** 点某段文案（找不到返回 false，由调用方决定要不要断言）。 */
private fun UiDevice.tapText(text: String, timeoutMs: Long = SETTLE_MS): Boolean {
    val obj = wait(Until.findObject(By.text(text)), timeoutMs) ?: return false
    obj.click()
    return true
}

/**
 * 在当前可滚动区域内做若干次上滑。
 * 找不到 scrollable 节点（Compose 语义应暴露它）时退化为整屏滑动——退化本身不会
 * 让基准失真太多，但会在报告里体现为"滚动区域未命中"。
 */
private fun UiDevice.flingScrollable(times: Int) {
    val bounds: Rect = findObject(By.scrollable(true))?.visibleBounds
        ?: Rect(0, 0, displayWidth, displayHeight)
    val cx = bounds.centerX()
    val top = bounds.top + bounds.height() / 6
    val bottom = bounds.bottom - bounds.height() / 6
    repeat(times) {
        swipe(cx, bottom, cx, top, 40)
        SystemClock.sleep(150)
    }
}
