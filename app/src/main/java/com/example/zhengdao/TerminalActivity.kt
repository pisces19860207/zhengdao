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
import com.example.zhengdao.rootfs.RootfsDownloader
import com.example.zhengdao.rootfs.RootfsIndexFetcher
import com.example.zhengdao.rootfs.RootfsInstaller
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.ui.AgentRepository
import com.example.zhengdao.ui.AppState
import com.example.zhengdao.terminal.ProotLauncher
import com.example.zhengdao.terminal.SessionManager
import com.example.zhengdao.terminal.TerminalPrefs
import com.example.zhengdao.terminal.SessionService
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import java.io.File
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

        // 顶部安装进度横幅（2026-10-08）：点一下收起。此前它是**死控件**——布局里存在、
        // 注释还写着进度都靠它，但代码从未引用（用户"终端里也没有显示"的根因之一）。
        // 若正在安装 / 刚装完，重进本页也要把结论带回来，不能只活在这一屏的生命周期里。
        findViewById<android.widget.TextView>(R.id.status_banner)?.apply {
            setOnClickListener { visibility = android.view.View.GONE }
            com.example.zhengdao.ui.InstallProgress.state.value?.let { st ->
                text = st.text
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
        // 黄点 = 关闭当前分屏。`C-b x` 是 kill-pane 的即时键绑定（默认带一次确认，补发 y）。
        findViewById<android.view.View>(R.id.btn_yellow).setOnClickListener {
            when {
                !usesTmux -> noTmuxHint()
                paneCount == 1 -> Toast.makeText(this, "只剩一块，不用关", Toast.LENGTH_SHORT).show()
                else -> {
                    runCatching {
                        SessionManager.write(byteArrayOf(0x02, 'x'.code.toByte()))
                        mainHandler.postDelayed({
                            runCatching { SessionManager.write("y".toByteArray(Charsets.UTF_8)) }
                        }, 250)
                    }
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
        // 文字选择菜单的复制/粘贴落地（SessionManager 转发到本 Activity 实现）
        SessionManager.onCopyText = { text ->
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("zhengdao-term", text))
            runOnUiThread { Toast.makeText(this, "已复制 ${text.length} 个字符", Toast.LENGTH_SHORT).show() }
        }
        SessionManager.onPasteRequest = {
            runOnUiThread {
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
                if (text.isNotEmpty()) SessionManager.write(text)
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
                    if (dx > trigger && dx > dy && dy < slop) {
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
        // 首次进入弹一次三点说明（等终端画面先出来，别挡在黑屏上）
        if (!dotsHintShownThisRun && !TerminalPrefs.dotsHintShown(this)) {
            mainHandler.post { showTrafficLightsHelp() }
        }
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
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
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

    /**
     * 「杀 App 再开，还是刚才那个 Agent」的兜底。
     *
     * 老实说一句：proot / tmux 都是**本 App 进程的子进程**，进程被系统或用户杀掉时
     * 它们跟着一起死（Android 不会替我们托管它）。所以这里的"回来还是它"不是 attach，
     * 而是**把那句启动命令重新执行一遍** —— 对用户来说结果是一样的。
     * （后台切走 30 分钟那种情况进程还活着，走的是真正的 attach，现场原样保留。）
     *
     * 只在"全新会话 + 本次请求没带命令"时触发：用户明确要求启动别的 Agent 时，
     * 不该被上一轮的残留插一脚。
     */
    private fun restoreLastAgentIfAny(): String? {
        val id = SessionManager.currentAgentId ?: return null
        val agent = runCatching {
            AppState.agents(this).firstOrNull { it.id == id }
        }.getOrNull()
        if (agent == null || !agent.installed || agent.launchCmd.isBlank()) {
            // 记录指向一个已经不存在的 Agent（卸载过 / 装了别的环境）→ 清掉，别下次再试
            SessionManager.setCurrentAgent(null)
            return null
        }
        RunLog.log("恢复：上次会话里是 ${agent.id}，重新执行它的启动命令")
        Toast.makeText(this, "上次在用的是 ${agent.name}，正在重新打开…", Toast.LENGTH_SHORT).show()
        return agent.launchCmd
    }

    /** 会话存活 → 仅 attach（引擎自动重排恢复画面）；否则启动新会话。安装后复用。 */
    private fun ensureStartedAndAttach() {
        // ⚠️ 必须挂上"引擎输出 → 视图重绘"回调：
        // 会话由 SessionManager 持有，引擎回调（onTextChanged）打到 SessionManager 的
        // client 上；视图层不接这根线，pty 照常输出但画面永不刷新（2026-10-05 实测白屏根因）。
        SessionManager.onViewUpdate = { scheduleScreenUpdate() }
        // 先路由（可能把旧会话整条换掉），再决定起/接
        routeSessionIfNeeded()
        if (!SessionManager.hasSession()) {
            val plan = SessionManager.start(this)
            usesTmux = plan.usesTmux
            SessionManager.usesTmux = plan.usesTmux
            fallbackActive = plan.isFallback
            // 全新会话只有一块窗格（attach 到旧会话时 paneCount 保持 -1 = 未知，
            // 见 paneCount 注释：服务端有几块我们问不到，不去猜）
            paneCount = 1
            toolbarTitle?.text = if (plan.isFallback) "证道 — 系统 shell（环境未安装）" else "证道 — Debian 13.7 · bash"
            // 环境未装：给安装引导（本地有归档则零交互自动装）。
            // ⚠️ v1.1 重写 TerminalActivity 时此调用曾丢失——新用户装完 App 卡在
            // fallback shell、没有任何安装入口（2026-10-07 恢复数据时实测发现）。
            if (fallbackActive) promptInstallOnce()
            // 新会话且本次没带命令 → 试试把上次那个 Agent 接回来
            if (!fallbackActive && pendingAutocmd == null) {
                restoreLastAgentIfAny()?.let {
                    pendingAutocmd = it
                    pendingAgentId = SessionManager.currentAgentId
                }
            }
        } else {
            usesTmux = SessionManager.usesTmux
            fallbackActive = false
            toolbarTitle?.text = "证道 — Debian 13.7 · bash"
        }
        // 视尺寸就绪后 attach：首次 attach 触发进程 spawn（新会话）或重排恢复（旧会话）
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
        pendingAutocmd = null
        runCatching { SessionManager.write("$cmd\n".toByteArray(Charsets.UTF_8)) }

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

    /** 轻量网络预检（异步，不阻塞）：npmjs ping 不通 → 键入一行代理提示。 */
    private fun networkPreCheck() {
        Thread {
            val ok = try {
                val c = java.net.URL("https://registry.npmjs.org/-/ping").openConnection()
                    as java.net.HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 5000
                val r = c.responseCode in 200..299
                runCatching { c.inputStream.close() }
                r
            } catch (_: Throwable) { false }
            if (!ok) {
                SessionManager.write("echo '[网络] ⚠️ 检测失败：请确认代理已开启，并在分应用代理中勾选「证道」'\n".toByteArray(Charsets.UTF_8))
            }
        }.start()
    }

    /** 快捷键条：固定序列直发；CTRL/SHIFT 为粘滞键，修饰下一次输入（设计文档 §7）。 */
    private fun wireKeyBar() {
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
        for ((id, seq) in sequences) {
            findViewById<TextView>(id)?.setOnClickListener { sendKey(seq) }
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
                Toast.makeText(this, "没有可复制的内容", Toast.LENGTH_SHORT).show()
            } else {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
                Toast.makeText(this, "已复制屏幕内容（${text.length} 字）", Toast.LENGTH_SHORT).show()
            }
        }
        // ^C 中断：独立按钮。点一下直接发 Ctrl+C（0x03 = ETX），
        // 不必先点亮粘滞 CTRL——中断是高频应急操作，两步走来不及。
        findViewById<TextView>(R.id.key_interrupt)?.setOnClickListener {
            runCatching { SessionManager.write(byteArrayOf(0x03)) }
                .onFailure {
                    Toast.makeText(this, "发送中断失败：${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
        // 粘贴：顶部工具栏入口（快捷键条末位让给更常用的退格）
        findViewById<TextView>(R.id.btn_paste_top)?.setOnClickListener { doPaste() }
        // 退格（⌫）：发 DEL(0x7F)。readline / bash 默认把 backward-delete-char
        // 绑在 0x7F，与桌面终端一致（不是 0x08 BS）。
        findViewById<TextView>(R.id.key_backspace)?.setOnClickListener {
            runCatching { SessionManager.write(byteArrayOf(0x7F)) }
                .onFailure {
                    Toast.makeText(this, "发送退格失败：${it.message}", Toast.LENGTH_SHORT).show()
                }
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
        }.onFailure { Toast.makeText(this, "发送失败：${it.message}", Toast.LENGTH_SHORT).show() }
    }

    /** 粘贴：读系统剪贴板直写会话（顶部工具栏按钮与快捷键条按钮共用）。 */
    private fun doPaste() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { SessionManager.write(text) }
            .onFailure { Toast.makeText(this, "粘贴失败：${it.message}", Toast.LENGTH_SHORT).show() }
        Toast.makeText(this, "已粘贴 ${text.length} 个字符", Toast.LENGTH_SHORT).show()
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
        // 点击终端即弹软键盘（TerminalView 不是 EditText，不会自动弹）
        termView.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(termView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true  // 中文 IME 组合输入需要
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
        // 粘滞 CTRL 对硬件键盘同样生效
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
        return false
    }
    override fun onEmulatorSet() {
        // 光标持续闪烁（用户要求）：必须先设频率再启动状态，且只能在 emulator 就绪后调用。
        // 第二个参数 startOnlyIfCursorEnabled 传 false = 无条件开始闪烁（默认光标即启用）。
        // 600ms 一次，接近常见终端手感（可选范围 100–2000ms）。
        runCatching {
            termView.setTerminalCursorBlinkerRate(600)
            termView.setTerminalCursorBlinkerState(true, false)
        }.onFailure { RunLog.log("光标闪烁启动失败: ${it.message}") }

        // 进程 spawn 完成（新会话）：注入一键安装/启动命令 + 网络预检
        mainHandler.post {
            if (!fallbackActive) networkPreCheck()
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
        val code = runCatching { finishedSession.getExitStatus() }.getOrDefault(-1)
        runOnUiThread {
            Toast.makeText(this, "会话已退出（code=$code）", Toast.LENGTH_LONG).show()
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
        Toast.makeText(this, "已复制 ${text.length} 个字符", Toast.LENGTH_SHORT).show()
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
    private val menuReloadFont = 103
    private val menuReset = 104

    override fun onCreateContextMenu(
        menu: android.view.ContextMenu,
        v: android.view.View,
        menuInfo: android.view.ContextMenu.ContextMenuInfo?
    ) {
        super.onCreateContextMenu(menu, v, menuInfo)
        val sel = runCatching { termView.storedSelectedText }.getOrNull()?.toString()?.trim()
        menu.setHeaderTitle("终端操作")
        // 复制/粘贴不再入列：顶部工具栏 + 选择工具条已有两条通路（用户定）
        menu.add(0, menuWebSearch, 0, "浏览器搜索选中文字").isEnabled = !sel.isNullOrEmpty()
        // 清屏 / 重载字号（v1.1.1 阶段 2.3）：清屏走 Ctrl-L（不往 stdin 写命令，
        // 免得在 TUI 里变成输入）；字号重载用于旋转或缩放后网格没跟上的兜底。
        menu.add(0, menuClear, 0, "清屏")
        menu.add(0, menuReloadFont, 0, "重载字号（当前 ${TerminalPrefs.sizeDp(this)}）")
        menu.add(0, menuReset, 0, "重置终端")
    }

    override fun onContextItemSelected(item: android.view.MenuItem): Boolean {
        val sel = runCatching { termView.storedSelectedText }.getOrNull()?.toString()
        when (item.itemId) {
            menuWebSearch -> {
                val q = sel?.trim().orEmpty()
                if (q.isEmpty()) {
                    Toast.makeText(this, "没有选中的文字", Toast.LENGTH_SHORT).show()
                } else {
                    runCatching {
                        val url = "https://www.bing.com/search?q=" +
                            java.net.URLEncoder.encode(q, "UTF-8")
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    }.onFailure {
                        Toast.makeText(this, "打开浏览器失败：${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                termView.unsetStoredSelectedText()
                return true
            }
            menuClear -> {
                // Ctrl-L（0x0C）= clear-screen：bash / zsh / 多数 TUI 都认，
                // 且不往 stdin 灌命令（Tab 补全状态或跑着 TUI 时不会被当输入）。
                runCatching { SessionManager.write(byteArrayOf(0x0C)) }
                    .onSuccess { Toast.makeText(this, "已清屏", Toast.LENGTH_SHORT).show() }
                    .onFailure {
                        Toast.makeText(this, "清屏失败：${it.message}", Toast.LENGTH_SHORT).show()
                    }
                return true
            }
            menuReloadFont -> {
                // 字号/配色/留白整体重刷 + 重算行列：旋转、缩放或改过设置后网格没跟上时用。
                runCatching {
                    // 留白写在容器上（见 applyTo 的注释），容器变了 → 视图尺寸随之变 → updateSize 才准
                    TerminalPrefs.applyTo(termView, findViewById(R.id.canvas_host), this)
                    termView.updateSize()
                    termView.onScreenUpdated()
                }.onFailure {
                    Toast.makeText(this, "重载字号失败：${it.message}", Toast.LENGTH_SHORT).show()
                }
                Toast.makeText(this, "字号 ${TerminalPrefs.sizeDp(this)} 已重载", Toast.LENGTH_SHORT).show()
                return true
            }
            menuReset -> {
                runCatching {
                    SessionManager.session?.emulator?.reset()
                    Toast.makeText(this, "终端已重置", Toast.LENGTH_SHORT).show()
                }
                return true
            }
            else -> return super.onContextItemSelected(item)
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
            installStatus("使用本地缓存包（$mb MB，不联网下载）—— 正在校验并解压…")
            startInstallFromFile(local)
            return
        }
        if (!installPromptShown.compareAndSet(false, true)) return
        mainHandler.post {
            val msg = if (!ProotLauncher.storageGranted(this)) {
                "首次使用需要下载运行环境：下载约 326MB，解压后占约 1.5–2GB。\n建议先点「授权存储」——授权后把安装包放进 Download/证道 文件夹，以后重装 App 无需重新下载。"
            } else {
                "首次使用需要下载运行环境：下载约 326MB，解压后占约 1.5–2GB。\n建议在 WiFi 下进行；支持断点续传，中断可重试。\n提示：也可以手动把安装包放到 Download/证道 文件夹，重启 App 即可免下载安装。"
            }
            AlertDialog.Builder(this)
                .setTitle("安装运行环境（Debian 13.7）")
                .setMessage(msg)
                .setPositiveButton("开始下载") { _, _ -> startInstall(ProotLauncher.DEFAULT_ROOTFS_URL) }
                .setNeutralButton("从文件选择") { _, _ ->
                    try {
                        startActivityForResult(
                            android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                            }, 2001
                        )
                    } catch (_: Throwable) {
                    }
                }
                .setNegativeButton("稍后", null)
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
            } catch (_: Throwable) {
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

    /** 写终端页顶部常驻横幅（用户可点它收起）。 */
    private fun showBanner(text: String) {
        runOnUiThread {
            findViewById<android.widget.TextView>(R.id.status_banner)?.apply {
                this.text = text
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

    /** SAF 选中归档：拷入公共缓存 → 校验 → 解压 → 切 Debian。压缩包保留（重装免下载）。 */
    private fun installFromSafUri(uri: android.net.Uri) {
        if (!installing.compareAndSet(false, true)) return
        val appContext = applicationContext
        Thread {
            com.example.zhengdao.ui.InstallFlow.start(
                this.applicationContext, "从本地文件安装环境", fromLocal = true
            )
            try {
                installStatus("从本地文件安装…")
                val archive = File(com.example.zhengdao.rootfs.RootfsCache.dir(appContext), "debian-13.7-base-arm64.tar.zst")
                archive.delete()
                contentResolver.openInputStream(uri)?.use { input ->
                    archive.outputStream().use { input.copyTo(it) }
                } ?: throw IllegalStateException("无法读取所选文件")
                installStatus("本地包读取完成（${archive.length() / (1024 * 1024)} MB），开始解压")
                RootfsInstaller.ensureFreeSpace(appContext, archive.length())
                // 用户自选文件没有任何校验值可比对 ⇒ 按"不确定就传 null"：装完不写 env 行，
                // 下次检查更新看到"无版本记录"会老实走全量（宁可多下一次，不可错走增量）。
                RootfsInstaller.install(appContext, archive) { }
                com.example.zhengdao.rootfs.RootfsCache.pruneKeep(appContext)
                // 装完立刻置位：主页/欢迎页的环境状态不必等回到前台再刷新（v1.2）
                com.example.zhengdao.ui.RootfsState.markInstalled()
                finishInstall("安装完成！安装包已保留在缓存（重装免下载，本次未联网下载）")
                relaunchDebian()
            } catch (t: Throwable) {
                installFailed("安装失败：${t.message}（重进 App 可再试）")
            } finally {
                installing.set(false)
            }
        }.start()
    }

    /** 从本地归档安装：已在缓存则直接用，否则拷入 → 校验 → 解压 → 切 Debian。压缩包保留。 */
    private fun startInstallFromFile(local: File) {
        if (!installing.compareAndSet(false, true)) return
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
                val sidecar = File(local.parentFile, local.name + ".sha256")
                val expectedSha = when {
                    sidecar.isFile -> sidecar.readText().trim()
                    else -> RootfsDownloader.fetchText(ProotLauncher.DEFAULT_ROOTFS_URL + ".sha256")
                }
                if (expectedSha.isNullOrBlank()) {
                    installStatus("未找到校验文件，跳过完整性校验")
                } else {
                    RootfsDownloader.verifySha256(archive, expectedSha)
                    installStatus("SHA256 校验通过")
                }
                installStatus("开始解压（约需几分钟，请勿离开）")
                // 首装也顺手记下 env（协议 §5）：本机装的是哪个"内容版本"，下次更新才可能走增量。
                // 索引取不到就作罢（失败容忍为 null，绝不阻塞/中断安装）。
                // 信任锚（用户 2026-10-08 规则）：只有索引 sha256 == 本次实际校验通过的 sha256 才写 env。
                val idx = runCatching { RootfsIndexFetcher.fetch() }.getOrNull()
                val envToWrite = RootfsInstaller.envForMarker(idx?.env, idx?.sha256, expectedSha)
                RootfsInstaller.ensureFreeSpace(appContext, archive.length())
                RootfsInstaller.install(appContext, archive, envToWrite) { }
                com.example.zhengdao.rootfs.RootfsCache.pruneKeep(appContext)
                // 装完立刻置位：主页/欢迎页的环境状态不必等回到前台再刷新（v1.2）
                com.example.zhengdao.ui.RootfsState.markInstalled()
                finishInstall("安装完成！安装包已保留在缓存（重装免下载）")
                relaunchDebian()
            } catch (t: Throwable) {
                installFailed("安装失败：${t.message}（重进 App 可再试）")
            } finally {
                installing.set(false)
            }
        }.start()
    }

    /** 下载 → SHA256 校验 → 解压（原子）→ 切 Debian。压缩包落公共缓存并保留。 */
    private fun startInstall(url: String) {
        if (!installing.compareAndSet(false, true)) return
        val appContext = applicationContext
        Thread {
            com.example.zhengdao.ui.InstallFlow.start(
                appContext, "联网下载运行环境包", fromLocal = false
            )
            try {
                installStatus("开始下载运行环境（断点续传）…")
                val archive = com.example.zhengdao.rootfs.RootfsCache.archiveFor(appContext, url)
                // 校验值也走镜像兜底：主源不通时不能因为拿不到 sha 就白白重下 326MB
                val expectedSha = RootfsDownloader.withMirrorFallback("$url.sha256")
                    .firstNotNullOfOrNull { RootfsDownloader.fetchText(it) }
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
                installStatus("开始解压（约需几分钟，请勿离开）")
                // 首装也顺手记下 env（协议 §5）：下次更新才可能走增量。索引取不到就作罢。
                // 信任锚（用户 2026-10-08 规则）：只有索引 sha256 == 实际校验通过的 sha256 才写 env。
                val idx = runCatching { RootfsIndexFetcher.fetch() }.getOrNull()
                val envToWrite = RootfsInstaller.envForMarker(idx?.env, idx?.sha256, actualSha)
                RootfsInstaller.ensureFreeSpace(appContext, archive.length())
                RootfsInstaller.install(appContext, archive, envToWrite) { }
                com.example.zhengdao.rootfs.RootfsCache.pruneKeep(appContext)
                // 装完立刻置位：主页/欢迎页的环境状态不必等回到前台再刷新（v1.2）
                com.example.zhengdao.ui.RootfsState.markInstalled()
                finishInstall("安装完成！安装包已保留在缓存（重装免下载）")
                relaunchDebian()
            } catch (t: Throwable) {
                installFailed("安装失败：${t.message}（重进 App 可再试）")
            } finally {
                installing.set(false)
            }
        }.start()
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
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            com.example.zhengdao.rootfs.RootfsDownloader.releaseIdleResources()
        }
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
