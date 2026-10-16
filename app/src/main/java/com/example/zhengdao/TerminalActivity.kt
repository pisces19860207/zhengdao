// 独立开发声明：本文件为本项目从零编写。终端渲染/模拟引擎为 Termux 官方
// terminal-emulator + terminal-view（Apache-2.0，v0.119.0-beta.3，聚合分发见
// PROVENANCE.md 与 THIRD-PARTY-LICENSES.md）。
//
// ⚠️ 许可证更正（2026-10-05）：此前误标为 GPL-3.0。上游 termux-app/LICENSE.md
// 的 Exceptions 一节明确：terminal-view 与 terminal-emulator 为 Apache-2.0，
// 仅 termux-app 主应用本体为 GPL-3.0（本项目未聚合主应用）。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao

import android.app.AlertDialog
import android.app.ComponentCaller
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.zhengdao.rootfs.RootfsDownloader
import com.example.zhengdao.rootfs.RootfsIndexFetcher
import com.example.zhengdao.rootfs.RootfsInstaller
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.util.HumanizeError
import com.example.zhengdao.ui.AgentRepository
import com.example.zhengdao.ui.AppState
import com.example.zhengdao.ui.browser.BrowserHolder
import com.example.zhengdao.terminal.KnowledgeBaseHints
import com.example.zhengdao.terminal.NetWatch
import com.example.zhengdao.terminal.ProotLauncher
import com.example.zhengdao.terminal.SessionManager
import com.example.zhengdao.terminal.TerminalLinkPolicy
import com.example.zhengdao.terminal.TerminalLinkScanner
import com.example.zhengdao.terminal.TerminalPrefs
import com.example.zhengdao.terminal.TerminalPasteWriter
import com.example.zhengdao.terminal.SessionService
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全屏终端 Activity（Mac 风格窗卡 + Termux 原生 TerminalView + 快捷键条）。
 *
 * 架构（原生终端方案，替代 WebView/xterm.js——性能对齐 Termux/太墟）：
 *   键盘/快捷键条/粘贴 → SessionManager.write → pty → 子进程（proot→tmux→bash）
 *   子进程输出 → Termux 引擎（原生状态机）→ TerminalView 自绘
 * 会话归 SessionManager 进程级持有（M2：UI 关 ≠ 会话死，前台服务保活）。
 */
class TerminalActivity : ComponentActivity(), com.termux.view.TerminalViewClient,
    com.termux.terminal.TerminalSessionClient {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var termView: TerminalView
    private var toolbarTitle: TextView? = null
    private var ctrlButton: TextView? = null
    private var shiftButton: TextView? = null
    private var stickyCtrl = false
    private var stickyShift = false
    private val installPromptShown = AtomicBoolean(false)
    private val installing = AtomicBoolean(false)
    /** 当前会话是否由 tmux 保持（绿点分屏按钮的前置条件） */
    private var usesTmux = false

    /**
     * 粘贴专用单线程通道（🔴3 修 ANR，2026-10-12）。
     *
     * 为什么单线程：写 pty 必须与用户操作**同序**，两条粘贴并发会把字符交错送进 shell。
     * 为什么 daemon：页面销毁时不必等它（`onDestroy` 里还会显式 shutdownNow 兜一道），
     * 进程被回收也不能留一条非 daemon 线程把 VM 挂住。
     * 线程是**首次提交任务时才创建**的 ⇒ 这个字段本身不花任何代价。
     */
    private val pasteExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "zd-paste").apply { isDaemon = true }
    }
    private val pasteWriter by lazy { TerminalPasteWriter(pasteExecutor) }

    /**
     * 当前 tmux 窗格数（**客户端计数**，只在本次进程内可信）。
     *
     * - 新起会话 = 1（tmux new-session 只开一块）；
     * - attach 到旧会话 = **-1（未知）**：pane 数只有服务端知道，而查询要经
     *   `C-b :` 命令提示符（竞态，见分屏按钮注释），不为一次显示去冒这个险。
     * - 未知时的取舍：按"可以分，但分完按 2 记"处理——最坏情况是偶发分出 3 块，
     *   用户点黄点就能收回；好过为一条计数去发一条可能拼坏的命令。
     */
    private var paneCount = -1
    /** 待执行的自动命令（一键安装/启动）；attach 与 fresh 两条路径都要注入 */
    private var pendingAutocmd: String? = null

    /**
     * 本次请求要进入哪个 Agent（**仅当带 [pendingAutocmd] 时有意义**）。
     *
     * 「安装中」按钮（HomeScreen）会传 agent_id 但不带命令——那只是"带我去看看
     * 正在跑的安装"，不该触发任何会话切换，故此时本字段保持 null。
     */
    private var pendingAgentId: String? = null

    /**
     * 本轮请求是否已经路由过。
     *
     * 路由会**杀会话**，绝不能重入：onResume 每次都会走到 ensureStartedAndAttach，
     * 若不设这道闸，一次正常的回前台就可能把会话来回换掉。
     * onNewIntent（= 新的一次请求）会把它置回 false。
     */
    private var routed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 终端打开期间保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        RootfsInstaller.cleanupPartial(applicationContext)
        RunLog.init(applicationContext)
        SessionManager.init(applicationContext)
        // 只在全新启动读取（Activity 异常重建会带原 intent，避免同一命令重跑两遍）
        if (savedInstanceState == null) {
            readRequestFromIntent(intent)
        }
        setContentView(R.layout.activity_main)

        // 边到边（第㉕刀，targetSdk 28 → 36）：Android 15+ 对 targetSdk 35+ 的应用**强制**
        // 边到边（窗口不再自动给系统栏留位；36 起 windowOptOutEdgeToEdgeEnforcement 也被忽略，
        // 无 opt-out）。本页是全仓唯一的经典 View 布局页，做法 = 关掉 decor 的自动适配 ＋ 在
        // 根 View 上显式吃 insets：系统栏与输入法的高度叠加成根布局 padding —— 与 target 28
        // 时代「内容夹在两根栏之间」的视觉一致（顶栏不被状态栏压、底部键栏不被导航栏/键盘压）。
        // 其余各页（Compose）走的是同款 setDecorFitsSystemWindows(false) + insets 修饰符。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val terminalRoot = findViewById<android.view.View>(R.id.terminal_root)
        val baseL = terminalRoot.paddingLeft
        val baseT = terminalRoot.paddingTop
        val baseR = terminalRoot.paddingRight
        val baseB = terminalRoot.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(terminalRoot) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(baseL + bars.left, baseT + bars.top, baseR + bars.right, baseB + bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // 顶部安装进度横幅（2026-10-08）：点一下收起。此前它是**死控件**——布局里存在、
        // 注释还写着进度都靠它，但代码从未引用（用户"终端里也没有显示"的根因之一）。
        // 若正在安装 / 刚装完，重进本页也要把结论带回来，不能只活在这一屏的生命周期里。
        //
        // B-补-13 / B-补-21③（2026-10-16）：
        // - 「无形交互」：布局侧已加 ✕ 图标（`drawableEnd`）+ `clickable`/`focusable`，
        //   读屏侧在写 `contentDescription` 时补「（点一下收起）」——**正文一字不动**，
        //   因为 [dismissNetworkBanner] 按文案精确比对（见其 KDoc）；
        // - 横幅挂 `accessibilityLiveRegion = POLITE`：安装进度/失败是**异步冒出来的
        //   状态**，不挂 liveRegion 读屏用户根本不会被通知（全仓此前 liveRegion 命中 0）。
        findViewById<android.widget.TextView>(R.id.status_banner)?.apply {
            isClickable = true
            isFocusable = true
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
            setOnClickListener { visibility = android.view.View.GONE }
            com.example.zhengdao.ui.InstallProgress.state.value?.let { st ->
                text = st.text
                contentDescription = bannerSpoken(st.text)
                visibility = android.view.View.VISIBLE
            }
        }

        toolbarTitle = findViewById(R.id.toolbar_title)
        // 红点 = 关闭终端返回主界面（UI 关，会话由前台服务继续保活）
        findViewById<android.view.View>(R.id.btn_close).setOnClickListener { finish() }
        // 左边缘右滑 = 同红点（用户 2026-10-07 指定）。真机实测：终端里既没有任何
        // 手势代码，左边缘的**系统返回手势也不触发**（主页能触发、终端不行）——
        // 于是整页唯一的出口是左上角那个 11dp 的小红点，单手够不到、也别扭。
        //
        // 2026-10-08 用户投诉「终端页右滑不能返回」后按实测重写（见 E-023）：
        // 旧实现把监听挂在两个子 View 上、排除区设在 `view.post` 的时机，实测
        // `dumpsys window` 里的 mSystemGestureExclusion 是**残缺碎块**而非整矩形，
        // 真手指从边缘起手照样被系统抢走；识别带 32dp、阈值 56dp 又与真人落点错开，
        // 留下 140–190px 的死区。现在改成 Activity 层**旁观**手势 + 排除区挂窗口根 View。
        installEdgeSwipeToClose()
        // 绿点 = tmux 上下分屏（最多 2 块，用户定）。
        // ⚠️ 改走**即时键绑定** `C-b "`：原先走 `C-b :` 命令提示符 + run-shell 条件判断，
        //    而提示符是异步打开的、固定延迟必然存在竞态（本文件 injectPendingAutocmd
        //    早已因同一竞态从 `C-b :` 改成了 `C-b c`，分屏却还留在老路上）。
        //    pane 数守卫改为客户端计数（见 [paneCount] 注释）——宁可计数保守，
        //    也不要一条会命中竞态的命令。
        findViewById<android.view.View>(R.id.btn_split).setOnClickListener {
            when {
                !usesTmux -> noTmuxHint()
                paneCount >= 2 -> Toast.makeText(
                    this, "已经是上下两块了（手机屏幕小，最多两块）", Toast.LENGTH_SHORT
                ).show()
                else -> {
                    runCatching { SessionManager.write(byteArrayOf(0x02, '"'.code.toByte())) }
                    paneCount = if (paneCount > 0) paneCount + 1 else 2
                    Toast.makeText(this, "已分成上下两块", Toast.LENGTH_SHORT).show()
                }
            }
        }
        // 黄点 = 关闭当前分屏。`C-b x` 是 kill-pane 的即时键绑定 —— **不再补发 `y`**
        // （2026-10-12 · §B 顺序 22 ②）：guest 的 `~/.tmux.conf` 里现在会写入
        // `bind-key x kill-pane`（`ProotLauncher.tmuxConfAppend`，理由见那里的 KDoc），
        // 覆盖掉 tmux 默认那条 `confirm-before`，一次按键就关。原先这里发完 `C-b x`
        // 再延迟 250 ms 盲发一个 `y` —— 那条按键没有任何依据能保证此刻屏幕上正等着
        // 确认：未启用 tmux、pane 数判断漂移、确认框还没起来时，`y` 会直接落进 pane 里
        // 前台程序的 stdin（在跑 agent TUI 时就是乱打字，即审计的 A4）。
        findViewById<android.view.View>(R.id.btn_yellow).setOnClickListener {
            when {
                !usesTmux -> noTmuxHint()
                paneCount == 1 -> Toast.makeText(this, "只剩一块，不用关", Toast.LENGTH_SHORT).show()
                else -> {
                    runCatching { SessionManager.write(byteArrayOf(0x02, 'x'.code.toByte())) }
                    paneCount = if (paneCount > 1) paneCount - 1 else 1
                }
            }
        }
        // 三个点各加一条长按 = 重看说明（首次进入已自动弹过一次，之后在这里复查）。
        // 点击是高频动作，说明不能占点击位；长按不影响正常点击。
        for (id in intArrayOf(R.id.btn_close, R.id.btn_split, R.id.btn_yellow)) {
            findViewById<android.view.View>(id)?.setOnLongClickListener {
                showTrafficLightsHelp()
                true
            }
        }
        findViewById<android.view.View>(R.id.window_card).clipToOutline = true

        termView = findViewById(R.id.terminal_native)
        termView.mClient = this
        // §B-补-49 🟢4/🟢6（2026-10-14）：会话结束通道接线。
        // 原设计：本 Activity 实现 TerminalSessionClient 并期望被注册——但全仓无人调用
        // updateTerminalSessionClient()，会话真正注册的是 SessionManager 的匿名 client
        // ⇒ 本类的 onSessionFinished 永不触发、SessionManager.onSessionDied 无赋值点（死钩子）。
        // 修法：走 SessionManager 已暴露的钩子通道（与 onCopyText/onPasteRequest 同模式）。
        // ⚠️ 不替换 client：替换会连带丢掉 SessionManager 的 onTextChanged→onViewUpdate 视图刷新。
        SessionManager.onSessionDied = { code -> handleSessionFinished(code) }
        // 文字选择菜单的复制/粘贴落地（SessionManager 转发到本 Activity 实现）
        SessionManager.onCopyText = { text ->
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("zhengdao-term", text))
            runOnUiThread { Toast.makeText(this, getString(R.string.term_copied_chars, text.length), Toast.LENGTH_SHORT).show() }
        }
        SessionManager.onPasteRequest = {
            runOnUiThread {
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
                // 🔴3（2026-10-12）：这里原来直接 `SessionManager.write(text)` 在**主线程**写 pty，
                // 与 doPaste() 是同一个 ANR 面（文字选择菜单的"粘贴"同样能贴进任意长文本）。
                if (text.isNotEmpty()) pasteText(text)
            }
        }
        // 「更多」菜单：showContextMenu() 需要注册上下文菜单才有效
        registerForContextMenu(termView)
        // ⚠️ 两个必须（均 2026-10-05 真机实测得出）：
        // 1) 必须先建渲染器再 attachSession——TerminalView 的 mRenderer 只在
        //    setTextSize()/setTypeface() 中创建（构造函数不建），而 updateSize()
        //    会读 mRenderer.mFontWidth。漏掉 → attachSession→updateSize 空指针崩溃
        //    （FATAL NPE at TerminalView.updateSize:988）。
        // 2) 字号必须换算成 px 再传——TerminalView.setTextSize 的形参虽标注
        //    "density-independent"，但内部直接 mTextPaint.setTextSize(textSize) 当
        //    **px** 用。传 14 会得到 14px 的极小字，进而算出 158 列 × 87 行的荒谬
        //    网格（实测 view=1270x1489、density=3.5、emu=158x87）。× density 后恢复正常。
        // 字号、配色与画布留白改为可配置（设置页「终端外观」），默认 12dp + 经典黑底白字 + 8dp 留白。
        // 内部完成三件事：写调色板与视图背景、给画布容器留白与底色、按 dp→px 换算设置字号
        // ⚠️ 仍然必须先于 attachSession 调用——mRenderer 只在 setTextSize 里创建，
        //    漏掉会在 attachSession→updateSize 处空指针崩溃。
        // ⚠️ 留白写在 canvas_host（外层容器）上，不写在 termView 上：updateSize() 用
        //    getWidth() 算列数且不减 padding，直接给视图加留白会算错列数（见布局文件注释）。
        TerminalPrefs.applyTo(termView, findViewById(R.id.canvas_host), this)
        wireKeyBar()
    }

    /** 回退 shell 活跃标记：fallback 下不注入 autocmd（命令会打进系统 sh） */
    private var fallbackActive = false

    /**
     * 网络探针状态（2026-10-14 第二版：改成"活体指示"）。判定逻辑在 [NetWatch]（纯逻辑、可单测），
     * 本类只管调度与横幅。
     *
     * 为什么不再"一次会话只探一次"：用户实测这条横幅的价值正是**发现代理 App 被系统后台杀掉了**
     * ——只探一次的话，会话中途代理被杀就再也报不出来，而"探通即收起"也永远等不到第二次观测。
     * 现在：周期探 + **只在状态翻转时**写日志（既不丢报警，也不刷 RunLog）。
     */
    private val netWatch = NetWatch()
    private var netProbeScheduled = false
    private val netProbeRunnable = Runnable {
        netProbeScheduled = false
        probeNetwork()
        scheduleNetProbe()
    }

    // 字号与配色已移到 TerminalPrefs（设置页可配），此处不再硬编码。

    /** 三点说明是否已在本 Activity 生命周期内弹过（防旋转/多次 resume 重复弹） */
    private var dotsHintShownThisRun = false

    /**
     * 未启用 tmux 时的统一提示（v1.1.1 阶段 2.2）。
     *
     * 旧文案只说"当前会话未启用 tmux"——不懂终端的人看完仍然不知道为什么、
     * 也不知道该怎么办。这里补一句人话：tmux 是什么、什么时候会有。
     */
    private fun noTmuxHint() {
        Toast.makeText(
            this,
            "分屏要靠 tmux（一个「关掉页面也不停」的会话管家）；当前会话没启用——" +
                "环境装好后下次启动自动启用。长按圆点可看三个点的说明",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * 左边缘右滑 = 关闭终端（与红点同义）。
     *
     * 只在**最左侧 [EDGE_SWIPE_WIDTH_DP] 的条带**里识别：终端本身的触摸要留给滚动与选字。
     *
     * 2026-10-08 真机复盘的三个坑（用户报"右滑不能返回"）：
     * ① 排除区挂在 `view.post` 里，那时高度常常还是 0，被 coerceAtLeast(1) 截成 1px 高
     *    → `dumpsys window` 里 mSystemGestureExclusion 是**残缺碎块**而非整矩形，
     *    系统返回手势没被让开，边缘起手照样被系统接管；现在挂在**窗口根 View** 上、
     *    布局完成后按真实高度设置、尺寸变化时重设。
     * ② 识别带 32dp(112px) 比真人拇指落点窄：落点在 48dp 上下时，既被排除区挡住系统返回、
     *    又超出本页识别带 ⇒ "怎么滑都没反应"（实测起手 170px 完全无响应）。放宽到 56dp。
     * ③ 触发阈值 56dp(196px) 对"快速一甩"太严（实测 dx=43dp 的一甩被无视），且只在 MOVE 判定、
     *    UP 不兜底。降到 40dp，并在 UP 再判一次。
     *
     * 事件归属：**不消费任何事件**。手势判定放在 [dispatchTouchEvent] 里旁观，
     * 命中才 `finish()` —— 这样终端自己的点击、长按选词、滑动滚动一律照旧
     * （旧实现用 OnTouchListener 吞掉按下，会把条带内的终端手势一起吃掉）。
     */
    private fun installEdgeSwipeToClose() {
        val root = window.decorView
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyEdgeExclusion(root) }
        root.post { applyEdgeExclusion(root) }
    }

    /** 把左侧条带从系统返回手势手里要过来（宽 = 识别带，高 = 真实高度）。 */
    private fun applyEdgeExclusion(view: android.view.View) {
        runCatching {
            val h = view.height
            if (h > 0) {
                val w = (EDGE_SWIPE_WIDTH_DP * resources.displayMetrics.density).toInt()
                view.systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, w, h))
            }
        }
        Unit
    }

    // 边缘右滑的旁观状态（不参与事件消费，见 [installEdgeSwipeToClose]）
    private var edgeStartX = 0f
    private var edgeStartY = 0f
    private var edgeTracking = false

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        val edge = EDGE_SWIPE_WIDTH_DP * density
        val trigger = EDGE_SWIPE_TRIGGER_DP * density
        val slop = EDGE_SWIPE_SLOP_DP * density
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                edgeTracking = ev.x <= edge
                if (edgeTracking) {
                    edgeStartX = ev.x
                    edgeStartY = ev.y
                }
            }
            android.view.MotionEvent.ACTION_MOVE,
            android.view.MotionEvent.ACTION_UP -> {
                if (edgeTracking) {
                    val dx = ev.x - edgeStartX
                    val dy = kotlin.math.abs(ev.y - edgeStartY)
                    // 横向占优 + 走够阈值 + 纵向没跑偏，才算「右滑返回」；
                    // 纵向占优的滑动一律放过（终端继续滚动）。
                    //
                    // 2026-10-10 UIUX 审计 B7 修：**排除"正在选词"**。
                    // 长按选中文字后拖动选择手柄，轨迹恰好是"横向 >40dp、纵向 <40dp"——
                    // 和右滑返回判据完全同形。旧代码不查选词状态 ⇒ 起手点又常在左边缘，
                    // 于是"想选一段命令"被判成"右滑关闭"，终端连带这一屏直接没了，且无撤销。
                    // [TerminalView.isSelectingText()] 是上游公开方法（terminal-view:1463）。
                    val selectingText = ::termView.isInitialized && termView.isSelectingText()
                    if (!selectingText && dx > trigger && dx > dy && dy < slop) {
                        edgeTracking = false
                        android.util.Log.d(
                            "EdgeSwipe",
                            "命中 ${if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) "UP 兜底" else "MOVE"}" +
                                " dx=$dx dy=$dy startX=$edgeStartX",
                        )
                        finish()
                    }
                }
                if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) edgeTracking = false
            }
            android.view.MotionEvent.ACTION_CANCEL -> edgeTracking = false
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * 顶部三个圆点的功能说明（v1.1.1 阶段 2.2）。
     *
     * - 首次进终端自动弹一次（看完写进偏好，不再打扰）；
     * - 之后长按任意一个圆点可复查（点击仍是高频动作，说明不能占点击位）。
     */
    private fun showTrafficLightsHelp() {
        val msg = "● 红点：关掉这个页面（命令还在后台跑，回来接着用）\n" +
            "● 绿点：上下分成两块（上面敲命令，下面看输出）\n" +
            "● 黄点：收回一块，合回一屏\n" +
            "← 屏幕最左边往右滑：一样是关掉这个页面\n" +
            "\n" +
            "关于「一个终端」：整个 App 只有这一条会话（tmux 里叫 zhengdao）。\n" +
            "· 点同一个 Agent：切回原来那个，不重启——聊到一半的上下文还在；\n" +
            "· 点别的 Agent / 点安装：这条会话整条换掉，不会两份东西并存；\n" +
            "· 分屏（绿点）是同一会话里分上下两块，不是开第二个会话。\n" +
            "环境还没装好时 tmux 不可用，只有红点能用。\n" +
            "\n" +
            "想再看一次说明：长按任意一个圆点。"
        runCatching {
            AlertDialog.Builder(this)
                .setTitle("顶部三个圆点")
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .show()
        }
        TerminalPrefs.markDotsHintShown(this)
        dotsHintShownThisRun = true
    }

    override fun onResume() {
        super.onResume()
        ensureStartedAndAttach()
        // 网络探针：终端页可见时跑（周期性，见 startNetWatch / NetWatch）
        startNetWatch()
        // 首次进入弹一次三点说明（等终端画面先出来，别挡在黑屏上）
        if (!dotsHintShownThisRun && !TerminalPrefs.dotsHintShown(this)) {
            mainHandler.post { showTrafficLightsHelp() }
        }
    }

    override fun onPause() {
        // 页面不可见就停探针（省电；回到页面时 onResume 会重新起）
        stopNetWatch()
        super.onPause()
    }

    /**
     * singleTask 复用实例时的新 Intent（2026-10-07 修：任务栈曾叠到 10 个终端页）。
     *
     * ⚠️ 必须实现：manifest 改成 singleTask 后，重复请求**不再走 onCreate**，
     *    只把既有实例带回前台并通过这里投递新 Intent。若此处不接 autocmd，
     *    第二次点「安装」的画面就是——页面回来了，命令却没跑：用户眼里
     *    正是"点了没反应"。onCreate 读 intent 那段因此不能删（两条路径互补）。
     *
     * 会话保活不受影响：会话由 SessionManager + 前台服务持有，Activity 复用与否无关。
     *
     * ⚠️ 这里是**新的一次请求**：必须把 [routed] 置回 false 再走一遍路由，
     *    否则「在 claude 的终端里点 agy」会变成"attach 回 claude"——
     *    用户点了 agy 却看到 claude，是本模型下最坏的一种错。
     */
    // 第㉚刀：activity-compose 1.13 起平台签名带 ComponentCaller（API 36+），旧单参
    // `Intent?` 覆写不再是 open 面。行为零变化：caller 不参与路由逻辑。
    override fun onNewIntent(intent: Intent, caller: ComponentCaller) {
        super.onNewIntent(intent, caller)
        setIntent(intent)   // 后续 getIntent() 也要拿到新参数
        readRequestFromIntent(intent)
        routed = false
        ensureStartedAndAttach()
    }

    /** 从 Intent 读一次「进终端请求」（autocmd + agent_id）。onCreate 与 onNewIntent 共用。 */
    private fun readRequestFromIntent(intent: Intent?) {
        val cmd = intent?.getStringExtra("autocmd")?.takeIf { it.isNotBlank() }
        pendingAutocmd = cmd
        // agent_id 只在**带着命令**时才算一次请求：见 pendingAgentId 注释。
        pendingAgentId = if (cmd == null) {
            null
        } else {
            intent.getStringExtra("agent_id")?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * 会话路由 —— 「全局单会话」模型的核心（用户 2026-10-07 定稿的规则）。
     *
     * 决策本身在 [com.example.zhengdao.terminal.SessionRouter]（纯函数、有单测逐格钉住），
     * 这里只负责把决策落到会话上。规则一句话：
     *   同一个 Agent → attach 回去（不重启、不新增）；
     *   换 Agent / 安装 / 别的命令 / 旧会话已死 → 把这条会话整条 kill 掉再起一条。
     *
     * 为什么"换 Agent 就 kill"而不是"并存"：手机上一条 tmux 会话已经占满一屏，
     * 并存只会让用户在两份东西之间来回找（旧实现就是 `C-b c` 一直开新窗口，
     * 实测点到 `5:bash`，用户不知道安装跑在哪一屏）。分屏要用 tmux 的 **pane**
     * （绿点 / `C-b "`），那是同一会话内的两块，不是两个会话。
     */
    private fun routeSessionIfNeeded() {
        if (routed) return
        routed = true
        val cmd = pendingAutocmd
        val agentId = pendingAgentId
        val action = com.example.zhengdao.terminal.SessionRouter.decide(
            requestHasCommand = cmd != null,
            requestAgentId = agentId,
            requestIsLaunch = agentId != null && cmd?.trim() == launchCmdOf(agentId),
            currentAgentId = SessionManager.currentAgentId,
            hasSession = SessionManager.hasSession(),
        )
        when (action) {
            com.example.zhengdao.terminal.SessionRouter.Action.KEEP_SESSION ->
                RunLog.log("路由：本次只打开终端（无命令）→ 会话原样保留")

            com.example.zhengdao.terminal.SessionRouter.Action.ATTACH_CURRENT -> {
                // 同一个 Agent 再点一次：把"要注入的命令"丢掉，直接接回原来那条会话。
                // —— 用户在 Agent 里聊到一半的上下文、滚过的输出，全都还在。
                pendingAutocmd = null
                val name = runCatching {
                    AppState.agents(this).firstOrNull { it.id == agentId }?.name
                }.getOrNull() ?: agentId.orEmpty()
                RunLog.log("路由：$agentId 就是当前会话 → attach 复用，不重启")
                Toast.makeText(this, "$name 已在运行，已切回原来那个（不重启）", Toast.LENGTH_LONG).show()
            }

            com.example.zhengdao.terminal.SessionRouter.Action.REPLACE_SESSION -> {
                val old = SessionManager.currentAgentId
                if (SessionManager.hasSession() || old != null) {
                    RunLog.log("路由：kill 旧会话（agent=${old ?: "-"}）→ 起新的（agent=${agentId ?: "-"}）")
                }
                SessionManager.kill(this)                       // killCurrentSession()
                // 登记新会话里是谁：安装命令成功后会自动启动该 Agent（见 AgentInstaller），
                // 所以安装也登记同一个 id —— 装完那一刻终端里跑的就是它。
                SessionManager.setCurrentAgent(agentId)
            }
        }
    }

    // ⚠️ 已删除 `restoreLastAgentIfAny()`（2026-10-11，用户真机反馈）。
    //
    // 它当年要解的是 E-018 那个坑：「杀 App 再开，还是刚才那个 Agent」——proot / tmux 都是
    // 本进程的子进程，进程被杀时现场一起没，于是退而求其次，**把启动命令重放一遍**。
    //
    // 被删的理由（用户原话）：「进入环境或者终端不应该默认某个 agent 啊，我一进去就是
    // hermes 的命令然后就进入 hermes 的 tui 了……那个默认才是要命的」。
    // 也就是这条兜底把"我想回到刚才那个 Agent"办成了"App 替我决定跑哪个 Agent"：
    // 冷启动后**任何**一次进终端（洞天 tab、丹房点「安装中」看进度、通知栏）都会重放，
    // 用户没有机会得到一个干净的 shell。
    //
    // 代价说明（写清楚，免得以后又被当成 bug 修回来）：进程被系统/用户杀掉后，重进终端
    // **不会**再自动回到那个 Agent，落点是裸 shell，用户自己敲 `hermes` 或点丹房的启动卡。
    // 进程还活着（切后台、关页面）时本来就是真正的 attach，那条路径没动。

    /** 会话存活 → 仅 attach（引擎自动重排恢复画面）；否则启动新会话。安装后复用。 */
    private fun ensureStartedAndAttach() {
        // ⚠️ 必须挂上"引擎输出 → 视图重绘"回调：
        // 会话由 SessionManager 持有，引擎回调（onTextChanged）打到 SessionManager 的
        // client 上；视图层不接这根线，pty 照常输出但画面永不刷新（2026-10-05 实测白屏根因）。
        SessionManager.onViewUpdate = { scheduleScreenUpdate() }
        // 先路由（可能把旧会话整条换掉），再决定起/接
        routeSessionIfNeeded()
        if (!SessionManager.hasSession()) {
            // 全新会话 + 本次没带命令 ⇒ 「这条会话里是谁」的记录要一起清掉：
            // 它是上一条会话的遗留（进程被杀时 prefs 里的 `current_agent` 照样留着），
            // 而眼前这条会话是个裸 shell。留着它会**两处出错**：
            //   ① 丹房的启动卡片被路由判成 ATTACH_CURRENT（"Hermes 已在运行，已切回原来那个"）
            //      ⇒ 用户点了启动却什么都没发生；
            //   ② 运行日志把这条裸 shell 记成 agent=hermes，事后查现场会被自己误导。
            // 放在 start() 之前就是为了 ② —— start() 里那行「会话启动…」直接读这个字段。
            if (pendingAutocmd == null) SessionManager.setCurrentAgent(null)

            // §B 顺序 6 / P1-3（2026-10-14）：把 SessionManager.start() 挪出主线程。
            //
            // 它内部依次做：killInternal → **ProotLauncher.buildLaunchPlan**（重活：算
            // SHA256、拷 assets、`Store.adoptDir` 搬 JM 级共享缓存）→ 建 TerminalSession
            // → 起前台服务。而本函数的三个调用点（onResume / onNewIntent /
            // relaunchDebian 的 runOnUiThread）**全在主线程** ⇒ 冷启动必卡主线程。
            //
            // 改法：重活丢后台线程，UI 赋值与 attach 回主线程（对外行为不变 ——
            // 仍是「起会话 → 更新顶栏 → attach」这一串，只是中间让出主线程一次）。
            startSessionOffMainThread()
            return
        }
        // 全新会话且本次没带命令 ⇒ 什么都不跑，落在一个**干净的 shell** 上。
        //
        // ⚠️ 这里原先是 `restoreLastAgentIfAny()`：把上次那个 Agent 的启动命令重放一遍
        //    （E-018 的"杀 App 再开还是它"）。用户 2026-10-11 真机反馈推翻了它——
        //    「进入环境或者终端不应该默认某个 agent」：一进终端就自动跑 `hermes`、
        //    直接进它的 TUI，看着像 App 自己替他做了决定（2026-10-11 日志实测：
        //    每次冷启动进终端都记一行「恢复：上次会话里是 hermes…」）。
        //    要跑 Agent 就点丹房的启动卡片（那条**带命令**，走 [routeSessionIfNeeded]）；
        //    进程**还活着**时进终端仍是真正的 attach，现场照旧保留。
        //    台账：ERRATA E-113、FEATURE-LEDGER §A #52。
        //
        // ⚠️ 同时必须把「这条会话里是谁」的记录清掉。记录是**上一条会话**的遗留
        //    （prefs 里的 `current_agent`，进程被杀时它照样留着），而眼前这条会话
        //    是个裸 shell：留着它，用户去点丹房的「Hermes」启动卡时会被路由判成
        //    ATTACH_CURRENT（"Hermes 已在运行，已切回原来那个"）⇒ **点了没反应**。
        //    只有本次**没带命令**时才清：带命令时记录已由路由层登记成正确的那个。
        //    （清除动作在 `SessionManager.start()` **之前**，见上面那处。）
        // 注：本分支的执行已挪进 [startSessionOffMainThread]（§B 顺序 6 / P1-3），
        //    上面这段决策的说明留在入口处，读代码时不必跳进后台线程才发现口径。
        usesTmux = SessionManager.usesTmux
        fallbackActive = false
        // 同一串短标题：attach 到已有会话也走这条（理由见上面新会话那处的注释）
        toolbarTitle?.text = "证道 - Debian 13.7"
        attachViewToSession()
    }

    /**
     * 起会话的防重入闸门（§B 顺序 6 / P1-3）。
     *
     * 语义与"为什么必须有"写在 [com.example.zhengdao.terminal.SessionStartGate] 的 KDoc 里
     * —— 抽成独立类是为了能 JVM 单测（本刀判据要求初始化**幂等/可重入**）。
     */
    private val sessionStartGate = com.example.zhengdao.terminal.SessionStartGate()

    /**
     * §B 顺序 6 / P1-3：把**重活**（`SessionManager.prepareLaunchPlan`：SHA256 / 拷 assets /
     * `Store.adoptDir` 搬 JM 级缓存）丢后台线程；**起会话本身回主线程做**。
     *
     * ⚠️ 为什么只挪一半（2026-10-14 真机退回的教训）：`SessionManager.start()` 里的
     * `new TerminalSession(...)` 会创建 `TerminalSession$MainThreadHandler`（**无参** Handler
     * 构造，vendored `com/termux/terminal/TerminalSession.java`），要求所在线程已
     * `Looper.prepare()`。上一版把整段 start() 丢后台 ⇒ 真机抛
     * `Can't create handler inside thread Thread[zd-session-start…]`，终端直接起不来
     * （黑屏、无 proot 进程）。所以分工是：**重活后台、起会话主线程**。
     */
    private fun startSessionOffMainThread() {
        // 已在启动中 ⇒ 这一次忽略（否则会起两条会话；语义见 SessionStartGate）
        if (!sessionStartGate.tryEnter()) return
        val t0 = android.os.SystemClock.uptimeMillis()
        val worker = Thread({
            // 只在这里做重活。失败**不许吞**（真机退回的教训）：落 RunLog + Log.e，
            // 回主线程后明确告诉用户，而不是静默黑屏。
            val plan = try {
                SessionManager.prepareLaunchPlan(this)
            } catch (t: Throwable) {
                val msg = "冷启动：buildLaunchPlan 失败 ${t.javaClass.simpleName}: ${t.message}"
                com.example.zhengdao.rootfs.RunLog.log(msg)
                android.util.Log.e("ZhengdaoColdStart", msg, t)
                null
            }
            val cost = android.os.SystemClock.uptimeMillis() - t0
            // 打点（判据：冷启动期间主线程不得出现 >16ms 的长任务；真机读数归蓝总管采）
            android.util.Log.i("ZhengdaoColdStart", "prepareLaunchPlan cost=${cost}ms")
            com.example.zhengdao.rootfs.RunLog.log(
                "冷启动打点：prepareLaunchPlan ${cost}ms（含 SHA256 / 拷 assets / 缓存搬家）"
            )
            runOnUiThread {
                sessionStartGate.exit()
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (plan == null) {
                    // 重活失败：说人话 + 落日志（不再静默黑屏）
                    com.example.zhengdao.rootfs.RunLog.log(
                        "冷启动：启动计划没算出来，放弃起会话（失败原因见上一条）"
                    )
                    android.widget.Toast.makeText(
                        this,
                        "启动失败：环境准备没完成（详情见 设置 → 运行日志）",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@runOnUiThread
                }
                // 起会话**必须在主线程**（MainThreadHandler 的 Looper 约束，见本方法 KDoc）
                val started = try {
                    SessionManager.start(this, plan)
                } catch (t: Throwable) {
                    val msg = "冷启动：SessionManager.start 失败 ${t.javaClass.simpleName}: ${t.message}"
                    com.example.zhengdao.rootfs.RunLog.log(msg)
                    android.util.Log.e("ZhengdaoColdStart", msg, t)
                    null
                }
                if (started == null) {
                    android.widget.Toast.makeText(
                        this,
                        "启动失败：会话没起来（详情见 设置 → 运行日志）",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@runOnUiThread
                }
                usesTmux = started.usesTmux
                SessionManager.usesTmux = started.usesTmux
                fallbackActive = started.isFallback
                // 全新会话只有一块窗格（attach 到旧会话时 paneCount 保持 -1 = 未知，
                // 见 paneCount 注释：服务端有几块我们问不到，不去猜）
                paneCount = 1
                // 2026-10-11 用户反馈「后面遮挡的有点多了」：原串 `证道 — Debian 13.7 · bash`
                // 在 17sp 粗体下要 ~190dp，而 375dp 屏的顶栏给标题只剩 ~150dp
                //（三个圆点热区占 104dp + 复制/粘贴占 ~92dp）⇒「· bash」整段被省略号吃掉、
                // `13.7` 也只剩半截。改成短标题，并由 activity_main.xml 里 toolbar_title 的
                // autoSize 在更窄的屏上自动降号兜底（宁可小一号，不再截断）。
                // ⚠️ 回退态那条 `证道 — 系统 shell（环境未安装）` 同样偏长，本次未动。
                toolbarTitle?.text =
                    if (started.isFallback) "证道 — 系统 shell（环境未安装）" else "证道 - Debian 13.7"
                // 环境未装：给安装引导（本地有归档则零交互自动装）。
                // ⚠️ v1.1 重写 TerminalActivity 时此调用曾丢失——新用户装完 App 卡在
                // fallback shell、没有任何安装入口（2026-10-07 恢复数据时实测发现）。
                if (fallbackActive) promptInstallOnce()
                attachViewToSession()
            }
        }, "zd-session-start")
        // P4-2：一次性启动 worker，必须 daemon——非 daemon 线程挂着会让整个进程
        // 退不干净（项目其余 27 处裸 Thread 一律 isDaemon=true，本条补对齐）。
        worker.isDaemon = true
        worker.start()
    }

    /**
     * attach 视图 + 尺寸兜底 + 待注入命令（原 ensureStartedAndAttach 尾部，两条分支共用）。
     *
     * 视尺寸就绪后 attach：首次 attach 触发进程 spawn（新会话）或重排恢复（旧会话）。
     */
    private fun attachViewToSession() {
        termView.post {
            SessionManager.session?.let { if (termView.mTermSession !== it) termView.attachSession(it) }
            // 兜底：attach 时若视图尚未完成测量，updateSize 会因宽高为 0 早退，且此后
            // 没有尺寸变化事件来重试——下一帧补一次，确保 emulator 初始化、进程 spawn。
            termView.post { termView.updateSize() }
            // 回退 shell 不注入 autocmd（命令会打进系统 sh）——保留待 Debian 会话就绪时注入
            if (!fallbackActive) injectPendingAutocmd()
        }
        termView.requestFocus()
    }

    /**
     * 合并重绘（每帧最多一次）：引擎的 onTextChanged 可能来自 pty 读取线程，
     * 且高频输出时每块都 post 会积压主线程队列。合并到单次 onScreenUpdated。
     */
    private val screenUpdateQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun scheduleScreenUpdate() {
        if (screenUpdateQueued.compareAndSet(false, true)) {
            mainHandler.post {
                screenUpdateQueued.set(false)
                termView.onScreenUpdated()
            }
        }
    }

    /**
     * 该 Agent 的官方启动命令。
     *
     * 用途只剩一个：**区分「点启动」和「点安装」**——命令等于 launchCmd 的是启动，
     * 否则（带清障/脚本/自动启动的一长串）是安装。两者在单会话模型里待遇不同：
     * 启动 → 同一个 Agent 就 attach 复用；安装 → 无条件 kill 重开。
     *
     * ⚠️ 同名进程计数（countProcesses）已随单会话模型一起删掉（用户 2026-10-07）：
     *    那是"多窗口并存 + 靠进程数判重"的思路，与"全局单会话、换就 kill"冲突——
     *    单会话下不可能有两个实例，要记的是"这条会话里是谁"（SessionManager.currentAgentId）。
     */
    private fun launchCmdOf(agentId: String?): String? {
        if (agentId.isNullOrBlank()) return null
        return runCatching {
            AppState.agents(this).firstOrNull { it.id == agentId }?.launchCmd?.trim()
        }.getOrNull()
    }

    /**
     * 注入待执行命令（一键安装 / 启动）。⚠️ attach/fresh 两条路径都要走，否则进了终端没反应。
     *
     * 幂等已由**路由层**负责（见 routeSessionIfNeeded）：同 Agent 的重复点击在那里就被
     * 拦下并转为 attach，走不到这里。所以这里只管把命令写进当前 pane。
     *
     * ⚠️ 写入的是**当前 pane**（用户 2026-10-07 定：安装与启动就在我正在用的这一屏）。
     *   早先是 `C-b c` 先开一个新窗口——好处是不打扰正在跑的 TUI，代价是窗口越积越多
     *   （实测点到 `5:bash`），用户还得自己去窗口列表里找安装跑到哪了。单会话模型下
     *   分屏用 pane（绿点 / `C-b "`），窗口数恒为 1。
     *   已知并接受的代价：当前 pane 里正开着 Agent 的交互界面时，这行命令会被当成输入
     *   交给它 —— 想同时看两件事就用绿点分屏。
     */
    private fun injectPendingAutocmd() {
        if (fallbackActive) return
        val cmd = pendingAutocmd ?: return
        // §B-补-49 🟢3(b)（2026-10-14）：先判就绪、先写，成功再清——原实现 `pendingAutocmd = null`
        // 在写入**之前**，而写入失败（含"会话未就绪时静默丢字节"）既不重试也不留痕，
        // 命令永久丢失且零日志；onEmulatorSet 的下一轮重试拿到的是 null。
        val session = SessionManager.session
        if (session == null || !session.isRunning) {
            RunLog.log("注入启动命令：会话未就绪，保留待重试")
            return
        }
        val ok = runCatching { SessionManager.write("$cmd\n".toByteArray(Charsets.UTF_8)) }
            .onFailure { RunLog.log("注入启动命令失败，保留待重试：${it.message}") }
            .isSuccess
        if (!ok) return
        pendingAutocmd = null

        // ⚠️ 只有**安装**才登记「安装中」：启动也登记的话，卡片会在 Agent 明明好好跑着的
        //    时候显示"正在安装"、并在 90 秒宽限期后翻成「上次安装未完成」——
        //    用户 2026-10-07 报的"启动一下就报失败"里就有这一份。（判据同路由：命令 ≠ launchCmd）
        val aid = pendingAgentId
        if (aid != null && cmd.trim() != launchCmdOf(aid)) {
            AgentRepository.markInstalling(this, aid)
        }
    }

    // ⚠️ 已删除 `sendTmuxCommand()`（v1.1.1 阶段 2.1）：它走 `C-b :` 命令提示符 +
    // 固定 150ms 延迟，而提示符是异步打开的——本文件早先已因同一竞态把
    // injectPendingAutocmd 改成 `C-b c`，分屏却还留在老路上。删掉它，免得以后被重新用上。

    /**
     * 网络探针（异步，不阻塞）：npmjs ping 不通 → **终端页顶部横幅**提示；恢复即自动收起。
     *
     * B-补-2 修正（2026-10-11）：这里原先用 `SessionManager.write("echo '…'\n")` 把一整行
     * **打进会话**。本方法由 [onEmulatorSet]（每次会话 spawn 完成）触发、5–10 秒后才回来，
     * 那时 pane 里很可能已经在跑 `hermes` / `vim` 这类吃 stdin 的程序 ⇒ 那行会被当成
     * "用户输入"（对 Agent 会话就是**凭空发一条消息**）。改成 App 侧横幅：用户看得见、
     * 点一下收起、一个字节都不进会话——也正是 2026-10-05 那次改动的原始意图
     * （"非 2xx / 超时 → 横幅代理提示，不阻塞会话"）。见 ERRATA E-095。
     *
     * 2026-10-14 第二版（用户逐字：「那个网络不通其实功能还是很好用的，能证明 VPN 之类的
     * APP 在后台被手机系统杀掉了」）—— 从"开卡时探一次"改成**活体指示**：
     * 1. **周期性重探**：终端页可见时每 [NetWatch.PROBE_INTERVAL_MS] 探一次（`onResume` 起、
     *    `onPause` 停）。
     * 2. **判定交给 [NetWatch]**：首次观测立刻定状态（不让用户等两轮）；之后连续两次失败才判
     *    "不通"（一次抖动不闪横幅），一次成功立刻判"通"（恢复要快）。**只在状态翻转时写日志**。
     * 3. **探通即收起**（沿用）：只收自己写的那一条（比对 [R.string.term_no_network]），
     *    不碰安装进度 / 失败结论横幅。
     *
     * 真机依据（2026-10-14，用户提醒"不走代理直连也该通"之后实测）：代理 App 未运行、系统里
     * 没有任何 VPN 网络时，App 内 WebView 直连 `https://www.baidu.com` 正常、
     * `https://registry.npmjs.org/-/ping` 返回 `{}` ⇒ **不开代理直连也能通**；横幅原文案
     * （"去代理 App 的『分应用代理』里勾选证道"）方向是错的，已改。
     */
    private fun startNetWatch() {
        if (fallbackActive) return
        if (netProbeScheduled) return
        netProbeScheduled = true
        // 先立刻探一次（真机实测：只挂周期任务的话，用户要等满一个周期才看得到报警），
        // 之后由 [netProbeRunnable] 每 [NetWatch.PROBE_INTERVAL_MS] 续一次。
        mainHandler.post(netProbeRunnable)
    }

    /** 停用探针（终端页不可见时调用）：撤掉排队的下一次探测。 */
    private fun stopNetWatch() {
        netProbeScheduled = false
        mainHandler.removeCallbacks(netProbeRunnable)
    }

    private fun scheduleNetProbe() {
        if (netProbeScheduled) return
        netProbeScheduled = true
        mainHandler.postDelayed(netProbeRunnable, NetWatch.PROBE_INTERVAL_MS)
    }

    private fun probeNetwork() {
        Thread {
            val ok = try {
                val c = java.net.URL("https://registry.npmjs.org/-/ping").openConnection()
                    as java.net.HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 5000
                val r = c.responseCode in 200..299
                runCatching { c.inputStream.close() }
                r
            } catch (_: Throwable) { false }
            runOnUiThread { applyNetProbe(ok) }
        }.apply { isDaemon = true; name = "zd-net-probe" }.start()
    }

    /** 一次观测落地：状态翻转才写日志 + 换横幅（同状态重复探测不留痕迹）。 */
    private fun applyNetProbe(ok: Boolean) {
        val flipped = netWatch.observe(ok)
        if (netWatch.observations == 1) {
            RunLog.log(
                "网络探针：registry.npmjs.org ${if (ok) "可达（直连可用）" else "不通"} —— " +
                    "此后每 ${NetWatch.PROBE_INTERVAL_MS / 1000} 秒探一次，只在状态翻转时记一行"
            )
        } else if (flipped) {
            RunLog.log(
                if (ok) "网络探针：恢复连通 —— 收起顶部网络横幅"
                else "网络探针：registry.npmjs.org 不通（多半是代理 App 被系统杀掉）—— 顶部横幅提示"
            )
        }
        if (!netWatch.online) {
            // 正在装东西时别把安装进度横幅刷掉：安装流程自己会报网络失败（E-095 附带守卫）。
            if (com.example.zhengdao.ui.InstallProgress.state.value == null) {
                showBanner(getString(R.string.term_no_network))
            }
        } else {
            dismissNetworkBanner()
        }
    }

    /**
     * 收起**本方法自己写的**那条网络横幅。
     *
     * 只认 `term_no_network` 这条文案：横幅是安装进度与失败结论共用的同一个
     * `@+id/status_banner`（见 [showBanner]），不能把"环境更新完成"之类的结论一起抹掉。
     */
    private fun dismissNetworkBanner() {
        runOnUiThread {
            findViewById<android.widget.TextView>(R.id.status_banner)?.let { b ->
                if (b.visibility == android.view.View.VISIBLE &&
                    b.text?.toString() == getString(R.string.term_no_network)
                ) {
                    b.visibility = android.view.View.GONE
                }
            }
        }
    }

    /** 快捷键条：固定序列直发；CTRL/SHIFT 为粘滞键，修饰下一次输入（设计文档 §7）。 */
    private fun wireKeyBar() {
        // 间距档位里的单键高度（默认 48dp）在这里落实：每个键都是 TextView，值收归 TerminalPrefs。
        findViewById<android.view.View>(R.id.key_bar)?.let { TerminalPrefs.applyKeyBarHeight(it, this) }
        ctrlButton = findViewById(R.id.key_ctrl)
        shiftButton = findViewById(R.id.key_shift)
        val sequences = mapOf(
            R.id.key_esc to "\u001b",
            R.id.key_up to "\u001b[A",
            R.id.key_down to "\u001b[B",
            R.id.key_left to "\u001b[D",
            R.id.key_right to "\u001b[C",
            R.id.key_pgup to "\u001b[5~",
            R.id.key_pgdn to "\u001b[6~",
            R.id.key_pipe to "|",
            R.id.key_minus to "-",
            R.id.key_slash to "/",
            // HOME / END：编辑长命令时跳到行首 / 行尾（bash readline 认这两组序列）
            R.id.key_home to "\u001b[H",
            R.id.key_end to "\u001b[F",
        )
        // §B-补-49 🟢5（2026-10-14）：会改写/清空 shell 当前行的键按下后，资料库行缓冲
        // 必须跟着失效——否则回车时拿的是**旧内容**去检索（附上的线索与用户当下这句话无关）。
        // 判据取"是否改变行内容"：↑/↓（历史召回，整行换内容）、ESC（取消当前行编辑）；
        // ←/→/HOME/END 只移动光标、行内容不变，**不清**（清了反而丢线索）。
        val lineChangingKeys = setOf(R.id.key_up, R.id.key_down, R.id.key_esc)
        for ((id, seq) in sequences) {
            findViewById<TextView>(id)?.setOnClickListener {
                sendKey(seq)
                if (id in lineChangingKeys) KnowledgeBaseHints.clearLine()
            }
        }
        // 复制：把当前屏幕可见内容送剪贴板。
        // 想要复制「选定区域」请用**长按终端**唤起选择手柄 + 系统工具栏的复制
        // （走 onCopyTextToClipboard）。此按钮是"整屏快拷"的兜底，方便把报错整屏带走。
        findViewById<TextView>(R.id.btn_copy_top)?.setOnClickListener {
            val text = runCatching {
                val em = SessionManager.session?.emulator ?: return@runCatching null
                val top = termView.getTopRow()
                em.screen.getSelectedText(0, top, em.mColumns, top + em.mRows)?.toString()
            }.getOrNull()?.trimEnd()
            if (text.isNullOrEmpty()) {
                Toast.makeText(this, getString(R.string.term_nothing_to_copy), Toast.LENGTH_SHORT).show()
            } else {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
                Toast.makeText(this, getString(R.string.term_copied_screen, text.length), Toast.LENGTH_SHORT).show()
            }
        }
        // ^C 中断：独立按钮。点一下直接发 Ctrl+C（0x03 = ETX），
        // 不必先点亮粘滞 CTRL——中断是高频应急操作，两步走来不及。
        findViewById<TextView>(R.id.key_interrupt)?.setOnClickListener {
            runCatching { SessionManager.write(byteArrayOf(0x03)) }
                .onFailure {
                    // P2-6（issue #17）：用户看到的必须是"人话"（HumanizeError），
                    // 异常原文进 RunLog 供排查——设计文档原则 3/5：正文不透露路径与 errno。
                    RunLog.log("发送中断失败：${it.javaClass.simpleName}: ${it.message}")
                    Toast.makeText(
                        this,
                        "发送中断失败：${HumanizeError.title(it, resources)}",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            // §B-补-49 🟢5：^C 把当前行整行作废（readline 语义），行缓冲必须清——
            // 否则被中断那半句还会在下次回车时被当成提问内容。
            KnowledgeBaseHints.clearLine()
        }
        // 粘贴：顶部工具栏入口（快捷键条末位让给更常用的退格）
        findViewById<TextView>(R.id.btn_paste_top)?.setOnClickListener { doPaste() }
        // Delete（前向删除）：发 CSI 3~（xterm 的 Delete 序列），键盘上的这颗键现在
        // 干的是"删掉光标所在的那个字"，与手机输入法自带的退格不再重复（用户 2026-10-11）。
        // ⚠️ 这里**不再**调 KnowledgeBaseHints.onUserBackspace()：那是退格语义（把缓冲的
        // 最后一个字退掉），Delete 是前向删——光标通常在行尾，本来也无字可删，硬退一格
        // 反而会把用户刚打进去的字从检索缓冲里抹掉。
        findViewById<TextView>(R.id.key_backspace)?.setOnClickListener {
            sendKey("\u001b[3~")
        }
        findViewById<TextView>(R.id.key_tab)?.setOnClickListener {
            // SHIFT+TAB = Backtab（\u001b[Z），Claude Code 的模式切换依赖它
            sendKey(if (stickyShift) "\u001b[Z" else "\t")
            if (stickyShift) clearSticky(ctrl = false, shift = true)
        }
        ctrlButton?.setOnClickListener {
            stickyCtrl = !stickyCtrl
            refreshStickyUi()
        }
        shiftButton?.setOnClickListener {
            stickyShift = !stickyShift
            refreshStickyUi()
        }
    }

    /**
     * 快捷键条统一发送口：优先走 TerminalView 的原生输入接口。
     * - 单字符（如 | - / TAB）→ `inputCodePoint()`：粘滞 CTRL/SHIFT 由视图层自动
     *   应用（TerminalView 内部读 readControlKey/readShiftKey），修饰键真正生效。
     * - 转义序列（方向键 / PGUP / PGDN / ESC / Backtab）→ 直接写 pty：视图层没有
     *   承载多字节序列的输入接口，这也是 Termux 官方 ExtraKeysView 的处理方式。
     */
    private fun sendKey(seq: String) {
        runCatching {
            if (seq.length == 1) {
                termView.inputCodePoint(
                    com.termux.view.TerminalView.KEY_EVENT_SOURCE_SOFT_KEYBOARD,
                    seq[0].code, false, false
                )
            } else {
                SessionManager.write(seq)
            }
        }.onFailure {
            // P2-6：同 ^C——人话给用户，原文进 RunLog。
            RunLog.log("发送失败：${it.javaClass.simpleName}: ${it.message}")
            Toast.makeText(
                this,
                "发送失败：${HumanizeError.title(it, resources)}",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /** 粘贴：读系统剪贴板后交给后台通道（顶部工具栏按钮与快捷键条按钮共用）。 */
    private fun doPaste() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(this, getString(R.string.term_clipboard_empty), Toast.LENGTH_SHORT).show()
            return
        }
        pasteText(text)
    }

    /**
     * 把一段文本写进 pty —— **写入本身不在主线程**（🔴3，2026-10-12）。
     *
     * 改动前的写法是主线程上 `runCatching { SessionManager.write(text) }`，而
     * `TerminalSession.write()` 会落进只有 4096 字节的 `ByteQueue` 并在满了以后
     * `wait()` 到 guest 读走为止：粘一篇文档 = 主线程干等几秒到几十秒 ⇒ 系统 ANR
     * （用户的观感是"点了粘贴就卡住，过一会儿弹无响应"）。现在整段写盘交给
     * [pasteWriter]（单线程、按 4 KiB 分块、每块前查会话是否还活着），
     * 主线程只负责读剪贴板与弹提示。
     *
     * 另一处顺带修的旧毛病（2026-10-08 那版就没盖全）：会话还没 spawn 时
     * `TerminalSession.write()` 会因为 `mShellPid > 0` 不成立而**静默什么都不做**，
     * 而旧代码照样弹"已粘贴 N 个字符"——用户以为粘进去了。现在这种情况会明确说
     * "会话未就绪"，成功提示也只在真的写完后出。
     */
    private fun pasteText(text: String) {
        if (text.isEmpty()) return
        if (SessionManager.session?.isRunning != true) {
            Toast.makeText(this, getString(R.string.term_session_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, getString(R.string.term_pasting, text.length), Toast.LENGTH_SHORT).show()
        pasteWriter.submit(
            text = text,
            isSessionAlive = { SessionManager.session?.isRunning == true },
            writeChunk = { SessionManager.write(it) },
        ) { result ->
            runOnUiThread {
                result
                    .onSuccess {
                        Toast.makeText(this, getString(R.string.term_pasted, it), Toast.LENGTH_SHORT).show()
                    }
                    .onFailure {
                        // R-29（第⑯刀）：内部异常原文不进 UI —— 用户看 HumanizeError 人话，原文只落 RunLog
                        com.example.zhengdao.rootfs.RunLog.log(
                            "粘贴失败（原文）：${it.javaClass.simpleName}: ${it.message}"
                        )
                        Toast.makeText(this, getString(R.string.term_paste_failed, HumanizeError.title(it, resources)), Toast.LENGTH_SHORT).show()
                    }
            }
        }
        // FIX-F：粘贴是"直写 pty"，同样绕过 onCodePoint ⇒ 单喂一口到行缓冲，
        // 这样「粘一段问题 + 回车」也能被检索。多行/超长粘贴会被它自己丢掉（那不是一次提问）。
        KnowledgeBaseHints.onUserText(text)
    }

    private fun clearSticky(ctrl: Boolean, shift: Boolean) {
        if (ctrl) stickyCtrl = false
        if (shift) stickyShift = false
        refreshStickyUi()
    }

    private fun refreshStickyUi() {
        val active = ContextCompat.getColor(this, R.color.term_key_active)
        val idle = ContextCompat.getColor(this, R.color.term_key_idle)
        ctrlButton?.setTextColor(if (stickyCtrl) active else idle)
        shiftButton?.setTextColor(if (stickyShift) active else idle)
    }

    // ── TerminalViewClient（视图层回调；粘滞键供硬件键盘与 IME 共用）──
    /**
     * 双指缩放 → 调整字号（原先直接 return scale，等于手势空转）。
     * 与设置页「终端外观」共用同一份偏好（TerminalPrefs），缩放即快捷改字号，
     * 在设置页里也能看到档位跟着变了。
     *
     * 按 Termux 的惯例：累计缩放因子超过 ±10% 才触发一次档位切换，切完把因子
     * 重置为 1.0，避免一次捏合连跳好几档。
     */
    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            val options = TerminalPrefs.SIZE_OPTIONS
            val cur = TerminalPrefs.sizeDp(this)
            val idx = options.indexOf(cur).let { if (it < 0) options.indexOf(TerminalPrefs.DEFAULT_SIZE_DP) else it }
            val next = (if (scale > 1f) idx + 1 else idx - 1).coerceIn(0, options.size - 1)
            if (next != idx) {
                val newDp = options[next]
                TerminalPrefs.saveSize(this, newDp)
                // 字号单位换算见 TerminalPrefs 注释：必须 × density（TerminalView 按 px 处理）
                termView.setTextSize((newDp * resources.displayMetrics.density).toInt())
                Toast.makeText(this, "字号 $newDp", Toast.LENGTH_SHORT).show()
            }
            return 1.0f   // 重置累计因子
        }
        return scale
    }
    override fun onSingleTapUp(e: android.view.MotionEvent) {
        // 证道追加（2026-10-14，台账 §B-12 / 派工单第 ② 刀）：B 路径——非 OSC 8 的裸 URL 文本。
        // OSC 8 链接已在手势层命中并短路（TerminalGestureController.onSingleTapUp 调
        // onLinkOpen 返回 true 就不来这里），走到这里说明该格无 OSC 8 链接；
        // 再扫所在行找覆盖该列的 URL，命中走同一套策略 + 打开链。
        val bareUrl = findBareUrlAt(e)
        if (bareUrl != null) {
            onLinkOpen(bareUrl)
            return
        }
        // 点击终端即弹软键盘（TerminalView 不是 EditText，不会自动弹）
        termView.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(termView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * OSC 8 链接 / B 路径 URL 的统一打开策略（证道 2026-10-14，§B-12）。
     *
     * 落点（派工单）：解析结果走 TerminalLinkPolicy，**打开交玄览的 B 路径**——
     * App 内打开不新开 Activity：写 BrowserHolder.pendingUrl + 请求主页切 Tab + finish()
     * 回主页，由 MainActivity ON_RESUME 消费切 Tab 请求落到玄览。
     */
    override fun onLinkOpen(uri: String): Boolean {
        return when (TerminalLinkPolicy.classify(uri)) {
            TerminalLinkPolicy.Verdict.OPEN_IN_APP -> {
                BrowserHolder.pendingUrl = uri
                BrowserHolder.requestBrowserTab()
                finish() // 回主页（主页 ON_RESUME 消费切 Tab 请求）
                true
            }
            TerminalLinkPolicy.Verdict.OPEN_SYSTEM -> {
                val ok = runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)))
                }.isSuccess
                if (!ok) {
                    Toast.makeText(this, getString(R.string.term_browser_failed, ""), Toast.LENGTH_SHORT).show()
                }
                true
            }
            TerminalLinkPolicy.Verdict.REJECT -> {
                Toast.makeText(this, getString(R.string.term_link_blocked), Toast.LENGTH_SHORT).show()
                true
            }
            TerminalLinkPolicy.Verdict.CONFIRM -> {
                // 一期不产生该判定（needsConfirm 恒 false，"已知域名簿"留二期）——
                // 真出现时按最严处理：不打开。
                Toast.makeText(this, getString(R.string.term_link_blocked), Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    /** B 路径：tap 列所在行的裸 URL 命中（OSC 8 未覆盖时）。 */
    private fun findBareUrlAt(e: android.view.MotionEvent): String? {
        val em = SessionManager.session?.emulator ?: return null
        val colRow = termView.getColumnAndRow(e, false) ?: return null
        val col = colRow[0]
        val row = colRow[1]
        val screen = em.screen
        val line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row))
        val text = String(line.mText, 0, line.spaceUsed)
        val charIndex = line.findStartOfColumn(col)
        return TerminalLinkScanner.findLinkCovering(text, charIndex)
    }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true  // 中文 IME 组合输入需要
    // 刻意返回 false（2026-10-12 补注释，审计 §2.5 的"确认后删接口"确认结论）：
    //   上游注释原话是「某些 ROM 上 ctrl+space 没这个 workaround 不行，但在本来就能用的设备上
    //   它会把 ctrl+space 弄坏」——本项目真机不需要它，所以关掉。
    //   为什么不能"删接口"：它是 vendored Termux 的契约方法
    //   （`app/src/main/java/com/termux/view/TerminalViewClient.java:35`，被 `TerminalImeBridge.java:226` 调用），
    //   删掉就得改上游文件、并在 PROVENANCE 里记一笔 —— 收益为零，故保留。
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
        // 粘滞 CTRL 对硬件键盘同样生效
        // FIX-F（2026-10-10，E-084）：硬件键盘走的是另一条路（TerminalView 里
        // KeyHandler.getCode() 直接把 "\r" 写进 pty），不经过 onCodePoint ——
        // 所以回车与退格在这里单独喂给行缓冲／交给注入器处理。
        // ⚠️ 挂在"用户按键"这条路径上是有意的：App 自己写的启动命令不从这里过，
        //    所以恢复会话不会被重复注入。
        if (e != null) {
            when (keyCode) {
                // 回车仍在"粘滞 Ctrl 之外"这条守卫里：Ctrl 点亮时的回车语义是 Ctrl+Enter，
                // 不该被「提交提问」那套逻辑接管（那会改写用户正要送进 pty 的内容）。
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                    if (!stickyCtrl && KnowledgeBaseHints.onUserCodePoint(
                            this, '\r'.code, ctrlDown = false
                        ) { payload -> writeToSession(session, payload) }
                    ) return true
                // 退格**不受粘滞 Ctrl 影响**（审计 C4）：不管 Ctrl 亮没亮，shell 行上的字都被删掉了，
                // 行缓冲必须跟着退 —— 否则"已删掉的字"会在下一次提交时被当成提问的一部分。
                // 近似之处：Ctrl+退格在 readline 里删的是一个词，这里只退一个字符；
                // 宁可少退（残留一个词的前缀）也不多留（整词留在缓冲里）。
                KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL ->
                    KnowledgeBaseHints.onUserBackspace()
            }
        }
        return false
    }
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: android.view.MotionEvent?): Boolean = false  // 默认=文字选择
    override fun readControlKey(): Boolean = stickyCtrl
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = stickyShift
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        // 粘滞 CTRL：作用于下一个单字符输入（Ctrl+字母 = 0x01–0x1a）
        if (stickyCtrl && codePoint in 97..122) {
            session?.writeCodePoint(false, codePoint - 96)
            clearSticky(ctrl = true, shift = false)
            return true
        }
        // FIX-F（2026-10-10，E-084）：行缓冲 + 回车拦截。
        // 返回 true ⇒ 这个码点被接管（只可能是回车），由注入器稍后把"资料库线索"连同回车一起送出。
        // 关着开关时它恒返回 false，本行等于不存在（零行为变化）。
        if (KnowledgeBaseHints.onUserCodePoint(this, codePoint, ctrlDown) { payload ->
                writeToSession(session, payload)
            }
        ) return true
        return false
    }

    /**
     * FIX-F 的 pty 出口：注入器在**后台线程**里回调本方法，这里负责切回主线程再写。
     *
     * ⚠️ 优先写"当前这个会话"（用户就是对着它按的回车）；拿不到再退回
     * [SessionManager.write]（与快捷键条／粘贴同一条通道）。写失败只落日志——
     * 注入失败绝不能表现成"用户回车没反应"。
     */
    private fun writeToSession(session: TerminalSession?, payload: String) {
        mainHandler.post {
            runCatching {
                // §B-补-49 🟢3(c)（2026-10-14）：优先**当前会话**——传进来的 `session` 是
                // 按键那一刻捕获的对象，1.5 秒后才写，期间它可能已退出（写入静默丢失、
                // onFailure 永不触发，回车与提示块一起蒸发）。用户就是对着当前会话按的回车。
                val s = SessionManager.session ?: session
                if (s != null) {
                    val bytes = payload.toByteArray(Charsets.UTF_8)
                    s.write(bytes, 0, bytes.size)
                } else {
                    SessionManager.write(payload)
                }
            }.onFailure { RunLog.log("资料库线索：写入终端失败（${it.message}）") }
        }
    }
    override fun onEmulatorSet() {
        // 光标持续闪烁（用户要求）：必须先设频率再启动状态，且只能在 emulator 就绪后调用。
        // 第二个参数 startOnlyIfCursorEnabled 传 false = 无条件开始闪烁（默认光标即启用）。
        // 600ms 一次，接近常见终端手感（可选范围 100–2000ms）。
        runCatching {
            termView.setTerminalCursorBlinkerRate(600)
            termView.setTerminalCursorBlinkerState(true, false)
        }.onFailure { RunLog.log("光标闪烁启动失败: ${it.message}") }

        // 进程 spawn 完成（新会话）：注入一键安装/启动命令 + 起网络探针（活体指示）
        mainHandler.post {
            if (!fallbackActive) startNetWatch()
            injectPendingAutocmd()
        }
    }
    override fun logError(tag: String, message: String) { RunLog.log("E: $message") }
    override fun logWarn(tag: String, message: String) { RunLog.log("W: $message") }
    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        RunLog.log("E: $message ${e.message}")
    }
    override fun logStackTrace(tag: String, e: Exception) { RunLog.log("E: ${e.message}") }

    // ── TerminalSessionClient（引擎回调）──
    override fun onTextChanged(changedSession: TerminalSession) {
        // Termux 引擎自动驱动视图重绘；此处仅保留钩子
    }
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {
        // 兜底路径：当前实际触发的是 SessionManager.onSessionDied 通道（见 onCreate 的接线）——
        // 本 override 保留是因为接口要求实现、且将来若改用 client 注册仍能生效。
        handleSessionFinished(runCatching { finishedSession.getExitStatus() }.getOrDefault(-1))
    }

    /**
     * 会话结束的公共处理（§B-补-49 🟢4，2026-10-14）：onSessionDied 通道与
     * [onSessionFinished] 兜底共用，避免两处逻辑漂移。
     * 由 pty 读线程回调（onSessionDied 在 SessionManager 的引擎回调里 invoke）⇒ 必须切主线程。
     */
    private fun handleSessionFinished(code: Int) {
        // FIX-F：会话没了，行缓冲里那半句也就不该存在（避免下次进来串味）。
        KnowledgeBaseHints.reset()
        runOnUiThread {
            // 2026-10-10 UIUX 审计 P1-4 修：原 Toast 带 "code=$code" 是开发术语，对非技术用户
            // 没有任何信息量（甚至会让人以为"出错了"）。改成中文大白话：会话**已经**结束
            // 是事实陈述；exit status 仍可在日志里查，但不必每次都弹到用户脸上。
            android.util.Log.i("TerminalSession", "session finished code=$code")
            Toast.makeText(
                this,
                "这一屏的会话已经结束了。命令在后台跑完了，下次进来就是新的一屏。",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    /**
     * 系统文本选择工具栏点「复制」时走到这里（路径：
     * TextSelectionCursorController → session.onCopyTextToClipboard → 本回调）。
     * ⚠️ 此前是空实现，等于复制按钮点了没反应——必须把内容真正写进剪贴板。
     */
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        if (text.isEmpty()) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
        Toast.makeText(this, getString(R.string.term_copied_chars, text.length), Toast.LENGTH_SHORT).show()
    }

    /** 系统文本选择工具栏点「粘贴」时走到这里：读剪贴板并写回终端。 */
    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isNotEmpty()) runCatching { SessionManager.write(text) }
    }

    // ── 「更多」上下文菜单（选择工具栏 MORE → showContextMenu → 本节）──
    // 用户点名的能力：选中文字 → 转浏览器搜索。Termux 的 ACTION_MORE 在弹菜单前会把
    // 选中文字存进 TerminalView.getStoredSelectedText()（选择模式已停，文字仍可用）。
    private val menuWebSearch = 101
    private val menuClear = 102
    // 103 曾是「重载字号」：2026-10-11 用户要求删掉（字号改由双指缩放 + 设置页「终端外观」
    // 查看，菜单里那一项既冗余又占位）。ID 不复用，免得将来翻旧日志时对不上。
    private val menuReset = 104

    override fun onCreateContextMenu(
        menu: android.view.ContextMenu,
        v: android.view.View,
        menuInfo: android.view.ContextMenu.ContextMenuInfo?
    ) {
        super.onCreateContextMenu(menu, v, menuInfo)
        val sel = runCatching { termView.storedSelectedText }.getOrNull()?.toString()?.trim()
        menu.setHeaderTitle(getString(R.string.term_menu_title))
        // 复制/粘贴不再入列：顶部工具栏 + 选择工具条已有两条通路（用户定）
        // 文案 2026-10-11 由「浏览器搜索选中文字」改「搜索选中文字」：现在交给系统默认搜索
        // （ACTION_WEB_SEARCH），未必是浏览器，标题别再替用户假定。
        menu.add(0, menuWebSearch, 0, getString(R.string.term_menu_web_search)).isEnabled = !sel.isNullOrEmpty()
        // 清屏（v1.1.1 阶段 2.3）：走 Ctrl-L（不往 stdin 写命令，免得在 TUI 里变成输入）。
        menu.add(0, menuClear, 0, getString(R.string.term_menu_clear))
        menu.add(0, menuReset, 0, getString(R.string.term_menu_reset))
    }

    override fun onContextItemSelected(item: android.view.MenuItem): Boolean {
        val sel = runCatching { termView.storedSelectedText }.getOrNull()?.toString()
        when (item.itemId) {
            menuWebSearch -> {
                val q = sel?.trim().orEmpty()
                if (q.isEmpty()) {
                    Toast.makeText(this, getString(R.string.term_no_selection), Toast.LENGTH_SHORT).show()
                } else {
                    openWebSearch(q)
                }
                termView.unsetStoredSelectedText()
                return true
            }
            menuClear -> {
                // Ctrl-L（0x0C）= clear-screen：bash / zsh / 多数 TUI 都认，
                // 且不往 stdin 灌命令（Tab 补全状态或跑着 TUI 时不会被当输入）。
                runCatching { SessionManager.write(byteArrayOf(0x0C)) }
                    .onSuccess { Toast.makeText(this, getString(R.string.term_cleared), Toast.LENGTH_SHORT).show() }
                    .onFailure {
                        // R-29（第⑯刀）：内部异常原文不进 UI —— 用户看 HumanizeError 人话，原文只落 RunLog
                        com.example.zhengdao.rootfs.RunLog.log(
                            "清屏写入失败（原文）：${it.javaClass.simpleName}: ${it.message}"
                        )
                        Toast.makeText(this, getString(R.string.term_clear_failed, HumanizeError.title(it, resources)), Toast.LENGTH_SHORT).show()
                    }
                return true
            }
            menuReset -> {
                runCatching {
                    SessionManager.session?.emulator?.reset()
                    Toast.makeText(this, getString(R.string.term_reset_done), Toast.LENGTH_SHORT).show()
                }
                return true
            }
            else -> return super.onContextItemSelected(item)
        }
    }

    /**
     * 选中文字 → 交给**手机自己的默认搜索 / 浏览器**（用户 2026-10-11 要求）。
     *
     * 判据：旧实现把搜索引擎写死成 bing 的 URL —— 等于我们替用户选好了搜索引擎。
     * `ACTION_WEB_SEARCH`（系统「网页搜索」动作）由系统里设的默认搜索 App 处理：
     * 用户装什么、设什么就用什么（Chrome / 夸克 / 各家自带浏览器都认这个动作）。
     * 只有设备上**没有**任何 App 能处理该动作时（极罕见），才回落到「用浏览器打开一个
     * 搜索 URL」——这条兜底路径沿用原来的 bing 地址，保证功能不至于完全没有。
     */
    private fun openWebSearch(query: String) {
        val viaSystem = Intent(Intent.ACTION_WEB_SEARCH)
            .putExtra(android.app.SearchManager.QUERY, query)
        if (runCatching { startActivity(viaSystem) }.isSuccess) return
        runCatching {
            val url = "https://www.bing.com/search?q=" + java.net.URLEncoder.encode(query, "UTF-8")
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }.onFailure {
            // R-29（第⑯刀）：内部异常原文不进 UI —— 用户看 HumanizeError 人话，原文只落 RunLog
            com.example.zhengdao.rootfs.RunLog.log(
                "唤起搜索失败（原文）：${it.javaClass.simpleName}: ${it.message}"
            )
            Toast.makeText(this, getString(R.string.term_browser_failed, HumanizeError.title(it, resources)), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
    /**
     * 光标样式：**细竖线（BAR）**——用户要求"光标细一点"。
     * 可选值：BLOCK(0 块状) / UNDERLINE(1 下划线) / BAR(2 竖线)。
     * 注：TUI 应用（Claude Code / Hermes 等）运行时会用自己的转义序列覆盖此样式，属正常。
     */
    override fun getTerminalCursorStyle(): Int =
        com.termux.terminal.TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR
    // ── 安装流程（进度走 InstallFlow：顶部横幅 + 通知栏 + RunLog；不放 2 秒 Toast）──

    /**
     * 本地已有安装包（用户需求：**检测到就自动安装，没有才下载**）。返回归档路径或 null。
     *
     * 2026-10-08 修正：此前只认 `Download/证道/` 根目录里那一个固定文件名，而 App 自己下载的包
     * 落在 `Download/zhengdao/cache/`（拉丁名）——于是**自己下过的包自己认不出**，重装一次就要
     * 重下一遍 326MB。现在统一交给 [RootfsCache.findLocalArchive]：根目录固定名、缓存目录
     * `Download/证道/rootfs/`、从旧目录搬过来的，全都算「已有安装包」。
     */
    private fun findLocalArchive(): File? {
        if (!ProotLauncher.storageGranted(this)) return null
        return com.example.zhengdao.rootfs.RootfsCache.findLocalArchive(
            this,
            preferredName = ProotLauncher.DEFAULT_ROOTFS_URL.substringAfterLast('/'),
        )
    }

    /** 回退会话首次出现时：本地有归档 → 直接自动安装（零交互）；否则给下载入口。 */
    private fun promptInstallOnce() {
        val local = findLocalArchive()
        if (local != null) {
            // 用户原话：「我以为要重新下载呢」。本地包这条路必须**明说不联网**——
            // 否则"提示只有 2 秒 + 终端里看不到进度"叠加起来，用户只能靠猜。
            val mb = local.length() / (1024 * 1024)
            RunLog.log("检测到本地归档，自动安装: ${local.path}")
            installStatus(getString(R.string.term_local_cache_installing, mb))
            startInstallFromFile(local)
            return
        }
        if (!installPromptShown.compareAndSet(false, true)) return
        mainHandler.post {
            val msg = if (!ProotLauncher.storageGranted(this)) {
                getString(R.string.term_first_run_need_grant)
            } else {
                getString(R.string.term_first_run)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.term_install_title))
                .setMessage(msg)
                .setPositiveButton(getString(R.string.term_start_download)) { _, _ -> startInstall(ProotLauncher.DEFAULT_ROOTFS_URL) }
                .setNeutralButton(getString(R.string.term_pick_file)) { _, _ ->
                    try {
                        startActivityForResult(
                            android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                            }, 2001
                        )
                    } catch (t: Throwable) {
                        // P2-7：文件选择器起不来不能静默——用户点了"选择文件"却毫无反应，
                        // 是最难排查的一类"没反应"。这仍是 best-effort（不中断对话框），
                        // 但必须留一句可查的。
                        RunLog.log("打开文件选择器失败（忽略）：${t.message}")
                    }
                }
                .setNegativeButton(getString(R.string.common_later), null)
                .show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (t: Throwable) {
                // P2-7：权限持久化失败不中断安装（本次进程内仍可用），但要留痕——
                // 它意味着重启后这个目录可能读不到，是"装到一半回来又要重新选"的根因。
                RunLog.log("持久化 URI 权限失败（忽略，本次安装继续）：${t.message}")
            }
            installFromSafUri(uri)
        }
    }

    /**
     * 安装进度（2026-10-08 重做；起因见 `InstallProgress` 注释里的用户原话）。
     *
     * 一次写四处，缺一处用户就还是"看不见"：
     * 1. **终端页常驻横幅** `@+id/status_banner`——它一直在布局里，注释还写着"安装/下载/
     *    解压等进度提示统一由本横幅承载"，但代码里**从来没有引用过**（死控件）；
     * 2. `InstallProgress`（Compose 状态，主页与设置页都能读）；
     * 3. **通知栏常驻进度**（离开终端页也看得见——用户当时就是跑去设置页反复点下载）；
     * 4. `RunLog`（落 `Download/证道/logs/`，可回看）。
     *
     * **不再发 2 秒 Toast**：那正是"提示时间有点短"的根源。失败才补一条长 Toast（见
     * [installFailed]）——成功时横幅已经常驻在屏幕上，再弹一个反而挡终端。
     */
    private fun installStatus(text: String, percent: Int = -1) {
        com.example.zhengdao.ui.InstallFlow.update(this, text, percent)
        showBanner(text)
    }

    /** 失败专用：横幅 + 通知 + **长 Toast**（失败必须被看见，不能只留在横幅里）。 */
    private fun installFailed(text: String) {
        com.example.zhengdao.ui.InstallFlow.fail(this, text)
        showBanner(text)
        runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    }

    /**
     * P2-6（issue #17）：安装失败的**异常**入口——原文落 RunLog，用户看到人话。
     *
     * 旧写法：三处调用点各自把 t.message 拼进 installFailed 的文案——Java 异常原文
     * （"Permission denied" / errno / 绝对路径）直接弹到用户脸上。HumanizeError 的
     * 设计原则 3/5：正文不透露敏感诊断、原始 message 仍可经 RunLog 查。
     */
    private fun installFailed(t: Throwable) {
        RunLog.log("安装失败（原文）：${t.javaClass.simpleName}: ${t.message}")
        installFailed("安装失败：${HumanizeError.title(t, resources)}（重进 App 可再试）")
    }

    /**
     * B-补-13（2026-10-16）：横幅的**读屏文案** = 正文 + 「（点一下收起）」。
     *
     * 只动 `contentDescription`，**一个字都不动 `text`**：`dismissNetworkBanner()` 靠
     * `b.text?.toString() == getString(R.string.term_no_network)` 精确比对来决定能不能
     * 收起「网络不通」那条（它的 KDoc 解释了为什么不能连带抹掉别的结论）。
     */
    private fun bannerSpoken(text: String): String = "$text（点一下收起）"

    /** 写终端页顶部常驻横幅（用户可点它收起）。 */
    private fun showBanner(text: String) {
        runOnUiThread {
            findViewById<android.widget.TextView>(R.id.status_banner)?.apply {
                this.text = text
                // B-补-13：正文不动，只补读屏提示（见 bannerSpoken 的注释）。
                contentDescription = bannerSpoken(text)
                visibility = android.view.View.VISIBLE
            }
        }
    }

    /**
     * 安装成功：横幅常驻结论 + 通知改成可划掉的「已就绪」+ 写一行给终端
     * （下次开会话时由 ProotLauncher 的启动横幅带出来；写文件的是
     * [com.example.zhengdao.ui.InstallFlow.finish]）。
     */
    private fun finishInstall(text: String) {
        com.example.zhengdao.ui.InstallFlow.finish(this, text)
        showBanner(text)
    }

    /**
     * 抢"本页正在装"的名额（#11 / E-078）：先问进程级真锁，再抢本 Activity 的实例标志。
     *
     * 只抢实例标志不够：设置页四条长流程、增量更新在同一进程里各走各的，而「环境替换」这一步
     * 进程内只能有一个（[RootfsInstaller.withInstallLock]），否则两边交错 = 刚装好的环境被再换一次。
     * 在入口就拦下，用户看到的是"已有任务在跑"，而不是等几分钟后收到一句"安装失败"。
     */
    private fun claimInstallSlot(): Boolean {
        if (RootfsInstaller.isInstalling()) {
            runOnUiThread {
                Toast.makeText(this, "已有安装/更新任务在跑，等它结束后再试", Toast.LENGTH_LONG).show()
            }
            return false
        }
        return installing.compareAndSet(false, true)
    }

    /** SAF 选中归档：拷入公共缓存 → 校验 → 解压 → 切 Debian。压缩包保留（重装免下载）。 */
    private fun installFromSafUri(uri: android.net.Uri) {
        if (!claimInstallSlot()) return
        val appContext = applicationContext
        Thread {
            com.example.zhengdao.ui.InstallFlow.start(
                this.applicationContext, "从本地文件安装环境", fromLocal = true
            )
            try {
                installStatus("从本地文件安装…")
                val archive = File(com.example.zhengdao.rootfs.RootfsCache.dir(appContext), "debian-13.7-base-arm64.tar.zst")
                // N-2（审计第 2 期）：原先是「先 delete 最终名，再把所选文件直拷进最终名」——
                // 拷贝中途失败/被杀会在缓存里留下一个顶着正式名字的半截包（findLocalArchive 只看
                // 字节数，够大就当"已有安装包"），而原来那份好包已经被 delete 掉了。
                // 改成与 RootfsDownloader.downloadOne 同一范式：先写 .part 临时名，收尾 rename 到
                // 最终名（rename 失败退回复制）——正式名要么是完整的包、要么不存在，没有半截态。
                val staging = File(archive.parentFile, archive.name + ".part")
                staging.delete()
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        staging.outputStream().use { input.copyTo(it) }
                    } ?: throw IllegalStateException("无法读取所选文件")
                    if (!staging.renameTo(archive)) {
                        staging.copyTo(archive, overwrite = true)
                        staging.delete()
                    }
                } catch (t: Throwable) {
                    staging.delete() // 半截只可能留在 .part 上，正式名不受污染
                    throw t
                }
                installStatus("本地包读取完成（${archive.length() / (1024 * 1024)} MB），开始解压")
                RootfsInstaller.ensureFreeSpace(appContext, archive.length())
                // 用户自选文件没有任何校验值可比对 ⇒ 按"不确定就传 null"：装完不写 env 行，
                // 下次检查更新看到"无版本记录"会老实走全量（宁可多下一次，不可错走增量）。
                // 同理也不传 archiveSha256：说不清这个包是谁，就不该给未来的"同源"推断留依据
                // （见 RootfsInstaller.envForReinstall 的 KDoc）。
                RootfsInstaller.install(appContext, archive) { }
                com.example.zhengdao.rootfs.RootfsCache.pruneKeep(appContext)
                // 装完立刻置位：主页/欢迎页的环境状态不必等回到前台再刷新（v1.2）
                com.example.zhengdao.ui.RootfsState.markInstalled()
                finishInstall("安装完成！安装包已保留在缓存（重装免下载，本次未联网下载）")
                relaunchDebian()
            } catch (t: Throwable) {
                installFailed(t)
            } finally {
                installing.set(false)
            }
        }.apply { isDaemon = true; name = "zd-install-saf" }.start()
    }

    /**
     * 「本机上次装成的那个包」的 sha256（P3-6，2026-10-10）。
     *
     * 标记文件 `<filesDir>/rootfs/.zhengdao-rootfs-ok` 里记着当初装成时的 `archive-sha256=`；
     * 它是**同一台手机上的记忆**，用来兜住"签名索引 / 本地边车 / 线上都拿不到校验值"这一档：
     * 以前这一档是「跳过校验照装」，现在至少要能对上"上次装过的那个包"才放行。
     */
    private fun rememberedArchiveSha(ctx: android.content.Context): String? =
        com.example.zhengdao.rootfs.RootfsMarker.installedArchiveSha256(File(ctx.filesDir, "rootfs"))

    /** 从本地归档安装：已在缓存则直接用，否则拷入 → 校验 → 解压 → 切 Debian。压缩包保留。 */
    private fun startInstallFromFile(local: File) {
        if (!claimInstallSlot()) return
        val appContext = applicationContext
        Thread {
            com.example.zhengdao.ui.InstallFlow.start(
                appContext, "使用本地缓存包安装（不联网下载）", fromLocal = true
            )
            try {
                val cacheCopy = File(com.example.zhengdao.rootfs.RootfsCache.dir(appContext), local.name)
                val archive = if (local.canonicalPath == cacheCopy.canonicalPath) local else run {
                    if (!cacheCopy.isFile || cacheCopy.length() != local.length()) {
                        installStatus("复制本地安装包到缓存（约 1 分钟）…")
                        local.copyTo(cacheCopy, overwrite = true)
                    }
                    cacheCopy
                }
                // 校验值以**索引**为准（索引 > 本地 .sha256 > 线上 .sha256 > 上次装过的那个包的 sha256；
                // 见 RootfsCache.pickExpectedSha / E-053 / P3-6）：
                // 本地边车是没人维护的遗留文件，远端一换包它必然过期，拿它当真会把"包是对的"误报成
                // "安装失败：SHA256 校验失败"（2026-10-08 用户真机报的就是这个）。
                val idx = runCatching { RootfsIndexFetcher.fetch() }.getOrNull()
                val sidecar = File(local.parentFile, local.name + ".sha256")
                val sidecarText = if (sidecar.isFile) {
                    runCatching { sidecar.readText() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                } else null
                // 本地边车本身过期/缺失时才去抓线上那份（省一次网络往返）
                val onlineSha = if (sidecarText == null) {
                    RootfsDownloader.fetchText(ProotLauncher.DEFAULT_ROOTFS_URL + ".sha256")
                } else null
                val choice = com.example.zhengdao.rootfs.RootfsCache.pickExpectedSha(
                    localName = archive.name,
                    localSize = archive.length(),
                    indexUrl = idx?.url,
                    indexSize = idx?.size ?: 0L,
                    indexSha = idx?.sha256,
                    sidecar = sidecarText,
                    onlineSha = onlineSha,
                    rememberedSha = rememberedArchiveSha(appContext),
                )
                val expectedSha = choice.sha
                if (choice.staleSidecar) {
                    RunLog.log("本地 .sha256 伴生文件已过期（sidecar=$sidecarText 索引=$expectedSha），按索引校验")
                }
                if (expectedSha.isNullOrBlank()) {
                    // ── P3-6（2026-10-10）：这里以前是「未找到校验文件，跳过完整性校验」然后照装 ──
                    // 也就是：一个谁都能替换的本地包，只要拿不到任何校验值就被无条件解压进
                    // App 私有目录（fail-open）。四个来源现在都试过了（签名索引 / 本地 .sha256 /
                    // 线上 .sha256 / 本机上次装成的那个包），一个都没有 ⇒ 拒绝安装，并说清怎么办。
                    throw IllegalStateException(
                        "拿不到这个安装包的 SHA256 校验值（签名索引、本地 ${sidecar.name}、线上都试过），" +
                            "为安全起见停止安装。请联网后重试「联网下载运行环境」；" +
                            "若这个包是官方渠道拿到的，可以在它旁边放一份同名 .sha256 文件再装"
                    )
                } else {
                    RootfsDownloader.verifySha256(archive, expectedSha)
                    installStatus("SHA256 校验通过（来源：${choice.source}）")
                    if (choice.staleSidecar) {
                        // 自愈：把过期边车改写成索引值，别让同一个坑下次再踩（写失败不影响安装）
                        runCatching { sidecar.writeText("$expectedSha\n") }
                            .onSuccess { RunLog.log("已把 ${sidecar.name} 重写为索引校验值") }
                            .onFailure { RunLog.log("重写 ${sidecar.name} 失败（忽略）：${it.message}") }
                    }
                }
                installStatus("开始解压（约需几分钟，请勿离开）")
                // 首装也顺手记下 env（协议 §5）：本机装的是哪个"内容版本"，下次更新才可能走增量。
                // 索引取不到就作罢（失败容忍为 null，绝不阻塞/中断安装）。
                // 信任锚（用户 2026-10-08 规则）：只有索引 sha256 == 本次实际校验通过的 sha256 才写 env。
                val envToWrite = RootfsInstaller.envForMarker(idx?.env, idx?.sha256, expectedSha)
                RootfsInstaller.ensureFreeSpace(appContext, archive.length())
                // 把"装的是哪个包"一并写进标记：将来「修复环境/回退」重装同一个包时，
                // 靠它把 env 原样写回（否则一次修复就抹掉增量基线，见 RootfsInstaller.envForReinstall）。
                RootfsInstaller.install(appContext, archive, envToWrite, expectedSha) { }
                com.example.zhengdao.rootfs.RootfsCache.pruneKeep(appContext)
                // 装完立刻置位：主页/欢迎页的环境状态不必等回到前台再刷新（v1.2）
                com.example.zhengdao.ui.RootfsState.markInstalled()
                finishInstall("安装完成！安装包已保留在缓存（重装免下载）")
                relaunchDebian()
            } catch (t: Throwable) {
                installFailed(t)
            } finally {
                installing.set(false)
            }
        }.apply { isDaemon = true; name = "zd-install-cache" }.start()
    }

    /** 下载 → SHA256 校验 → 解压（原子）→ 切 Debian。压缩包落公共缓存并保留。 */
    private fun startInstall(url: String) {
        if (!claimInstallSlot()) return
        val appContext = applicationContext
        Thread {
            com.example.zhengdao.ui.InstallFlow.start(
                appContext, "联网下载运行环境包", fromLocal = false
            )
            try {
                installStatus("开始下载运行环境（断点续传）…")
                val archive = com.example.zhengdao.rootfs.RootfsCache.archiveFor(appContext, url)
                // 索引先拿：它与下面"下载时用的校验值"必须同源，否则同一条链上有两种强度（E-050 的老坑）。
                // 索引是唯一被 Ed25519 签名背书的来源（E-052）；名字/字节数对得上就用它的 sha256，
                // 否则退回线上 `$url.sha256`（并保留镜像兜底：主源不通时不能因为拿不到 sha 就白白重下 326MB）。
                val idx = runCatching { RootfsIndexFetcher.fetch() }.getOrNull()
                val onlineSha = RootfsDownloader.withMirrorFallback("$url.sha256")
                    .firstNotNullOfOrNull { RootfsDownloader.fetchText(it) }
                val expectedSha = com.example.zhengdao.rootfs.RootfsCache.pickExpectedSha(
                    localName = archive.name,
                    localSize = if (archive.isFile) archive.length() else -1L,
                    indexUrl = idx?.url,
                    indexSize = idx?.size ?: 0L,
                    indexSha = idx?.sha256,
                    sidecar = null,
                    onlineSha = onlineSha,
                    rememberedSha = rememberedArchiveSha(appContext),
                ).sha
                var needDownload = true
                // 本次"实际校验通过的 SHA256"（信任锚要用它跟索引对账，见下面的 envForMarker）
                var actualSha: String? = expectedSha
                if (archive.isFile && !expectedSha.isNullOrBlank()) {
                    runCatching {
                        RootfsDownloader.verifySha256(archive, expectedSha)
                        needDownload = false
                        installStatus("检测到已下载的完整安装包，跳过下载")
                    }
                }
                if (needDownload) {
                    var lastPercent = -1L
                    actualSha = RootfsDownloader.download(
                        urls = RootfsDownloader.withMirrorFallback(url),
                        dest = archive,
                        shaUrls = RootfsDownloader.withMirrorFallback("$url.sha256"),
                    ) { done, total ->
                        if (total > 0) {
                            val percent = ((done * 100 / total).coerceIn(0, 100) / 20) * 20
                            if (percent != lastPercent) {
                                lastPercent = percent
                                installStatus(
                                    "下载中 $percent%（${done / (1024 * 1024)}/${total / (1024 * 1024)} MB）",
                                    percent.toInt(),
                                )
                            }
                        }
                    } ?: expectedSha
                } else {
                    installStatus("检测到已下载的完整安装包，跳过下载")
                }
                // 对账：下载时用的是"文件自带的 sha"，索引给的才是权威值。两者不一致就按索引再哈希一次
                // （对不上直接抛，安装失败——fail-closed），一致则零成本（192MB 哈希约 1 秒，能省则省）。
                if (!expectedSha.isNullOrBlank() && !expectedSha.equals(actualSha, ignoreCase = true)) {
                    RootfsDownloader.verifySha256(archive, expectedSha)
                    actualSha = expectedSha
                }
                installStatus("开始解压（约需几分钟，请勿离开）")
                // 首装也顺手记下 env（协议 §5）：下次更新才可能走增量。索引取不到就作罢。
                // 信任锚（用户 2026-10-08 规则）：只有索引 sha256 == 实际校验通过的 sha256 才写 env。
                val envToWrite = RootfsInstaller.envForMarker(idx?.env, idx?.sha256, actualSha)
                RootfsInstaller.ensureFreeSpace(appContext, archive.length())
                // 同"本地包"路径：把实际装进去的那个包的 sha256 写进标记，供将来重装做"同源"推断。
                RootfsInstaller.install(appContext, archive, envToWrite, actualSha) { }
                com.example.zhengdao.rootfs.RootfsCache.pruneKeep(appContext)
                // 装完立刻置位：主页/欢迎页的环境状态不必等回到前台再刷新（v1.2）
                com.example.zhengdao.ui.RootfsState.markInstalled()
                finishInstall("安装完成！安装包已保留在缓存（重装免下载）")
                relaunchDebian()
            } catch (t: Throwable) {
                installFailed(t)
            } finally {
                installing.set(false)
            }
        }.apply { isDaemon = true; name = "zd-install-download" }.start()
    }

    /** 安装完成后切换到 Debian：杀旧会话（含回退 shell）→ 重开（自动 spawn 新 Debian）。 */
    private fun relaunchDebian() {
        SessionManager.kill(this)
        runOnUiThread {
            Toast.makeText(this, "正在切换到 Debian 13.7 (bash)…", Toast.LENGTH_SHORT).show()
            ensureStartedAndAttach()
        }
    }

    /**
     * 宽窄切换（竖屏 ↔ 横屏 / 折叠屏展开）：重载快捷键条布局。
     *
     * 资源限定符 res/layout[-w600dp]/term_keys.xml 只在**布局加载那一刻**参与匹配；
     * 而本 Activity 声明了 configChanges（旋转不重建，以免终端会话状态丢失），
     * 因此旋转不会自动重新选布局——必须手动 reinflate，
     * 否则横屏会一直沿用竖屏的两行版（2026-10-05 真机实测）。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        reloadKeyBar()
    }

    /** 清空容器、按当前宽度重新加载快捷键条（两行 ↔ 单行），随后重新绑定事件。 */
    private fun reloadKeyBar() {
        val container = findViewById<android.widget.FrameLayout>(R.id.key_bar_container) ?: return
        container.removeAllViews()
        layoutInflater.inflate(R.layout.term_keys, container, true)
        wireKeyBar()
    }

    override fun onDestroy() {
        // M2：会话归 SessionManager 持有，UI 销毁不杀会话（前台服务继续保活）。
        // 但必须断开视图重绘回调，否则会持有已销毁的 View（内存泄漏 + 空刷）。
        SessionManager.onViewUpdate = null
        // 审计 🟡-4：同一批全局回调里原先只清了 onViewUpdate，另两条各自捕获本 Activity
        // （getSystemService / Toast），不清同样会把已销毁的 Activity 钉在单例上。
        SessionManager.onCopyText = null
        SessionManager.onPasteRequest = null
        // 🔴3（2026-10-12）：粘贴通道是本次新增的后台线程，页面没了就收掉。
        // shutdownNow 会打断正在 `wait()` 的 pty 写 —— 那笔粘贴转为失败回调，前台已无
        // Toast，但 RunLog 之外不该再有人持有本 Activity（回调里 runOnUiThread 已被销毁）。
        pasteExecutor.shutdownNow()
        // FIX-F：清掉行缓冲（Activity 都没了，那半句提问也不该留着）。
        KnowledgeBaseHints.reset()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            com.example.zhengdao.rootfs.RootfsDownloader.releaseIdleResources()
        }
        // D-4（第⑱刀）：内存压力下回收玄览 WebView（仅 COMPLETE 档且玄览离线时才真回收）。
        com.example.zhengdao.ui.browser.BrowserHolder.onTrimMemory(level)
    }

    companion object {
        /**
         * 左边缘右滑关闭终端：识别条带宽（dp）。
         * 32dp 实测不够——真人拇指落点常在 48dp 上下，落在那儿时系统返回手势被排除区挡住、
         * 又超出本页识别带，"怎么滑都没反应"（2026-10-08 真机复查用户投诉）。56dp 接近
         * 主流机型"边缘返回"的手感；再宽就会明显侵占终端左侧的拖选区域。
         */
        const val EDGE_SWIPE_WIDTH_DP = 56f

        /**
         * 左边缘右滑关闭终端：横向位移阈值（dp）。
         * 56dp 对"快速一甩"太严（实测 dx=150px≈43dp 的一甩被完全无视），降到 40dp。
         */
        const val EDGE_SWIPE_TRIGGER_DP = 40f

        /** 纵向容差（dp）：手指越抖越容易超，40dp 以内都算"横向占优"。 */
        const val EDGE_SWIPE_SLOP_DP = 40f

        // ⚠️ 已删除（用户 2026-10-07 定稿「全局单会话」模型时一并去掉）：
        //   · `recentLaunches` + `launchGuardMs`（20 秒"启动中"窗口守卫）
        //   · `countProcesses()`（扫 /proc 数同名进程）
        //   两者都是"多窗口并存 + 靠进程数判重"思路的产物，方向与单会话模型相反：
        //   单会话下旧的已经被 kill 了，不可能有两个实例，要判断的只是
        //   "这条会话里是谁"——那就是 SessionManager.currentAgentId 一个字段。
    }
}
