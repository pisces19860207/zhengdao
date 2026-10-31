// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：浏览器六工具的**真身**（进程内直调玄览）。工具层只认 `BrowserGateway` 接口，
// 本文件把接口接到 `BrowserCommands`（与 HTTP 桥同一套处理函数，差别只在**没过 token**：
// 抱朴与玄览同进程，不走桥）。
//
// 为什么每次现取活跃标签：`BrowserTabManager` 的 WebView 会随标签回收被销毁；工具层若
// 抓着旧实例不放，就会复现「Application attempted to call on a destroyed WebView」。
package com.example.zhengdao.baopu

import android.content.Context
import com.example.zhengdao.baopu.agent.BaopuBrowser
import com.example.zhengdao.baopu.agent.BaopuTeach
import com.example.zhengdao.baopu.agent.BrowserGateway
import com.example.zhengdao.baopu.agent.PageInfo
import com.example.zhengdao.baopu.agent.PreviewSink
import com.example.zhengdao.baopu.agent.PreviewTool
import com.example.zhengdao.baopu.agent.Shot
import com.example.zhengdao.baopu.agent.TeachGateway
import com.example.zhengdao.baopu.agent.TeachTools
import com.example.zhengdao.baopu.inspect.VisualEvidenceQueue
import com.example.zhengdao.baopu.kernel.BaopuKernel
import com.example.zhengdao.bridge.BrowserCommands
import com.example.zhengdao.browser.SensitiveRedactor
import com.example.zhengdao.core.RunLog
import com.example.zhengdao.ui.browser.BrowserHolder
import com.example.zhengdao.ui.browser.BrowserTabManager
import com.example.zhengdao.ui.browser.LocalPreviewHolder
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 把 [BrowserGateway] 接到玄览（进程内直调，不经 HTTP 桥、不要 token）。
 *
 * 幂等：重复调用只替换同一份网关。
 *
 * 第五十九批 §5.1 起这里还接第二个落点：`preview_local_file` 的 [PreviewSink]
 * （把已经读出来、守过的文本登记成一次「本地预览」请求）。
 * 两件事同住一个文件的原因见 install() 里那段注释——"玄览的接线"只许有一个地方。
 */
internal object BaopuBrowserWiring {

    /** 滚动步长（一次 tool 调用的位移；滚太多会跳过内容）。 */
    private const val SCROLL_STEP = 600

    fun install(context: Context) {
        val appCtx = context.applicationContext
        installTeach(appCtx)
        BaopuBrowser.gateway = object : BrowserGateway {

            override fun open(url: String, newTab: Boolean): String =
                if (newTab) {
                    BrowserCommands.newTab(appCtx, url)
                } else {
                    BrowserCommands.navigate(url)
                }

            override fun snapshot(selector: String?): String =
                BrowserCommands.snapshot("text", selector)

            // 262 批：证据快照（不依赖前台可见——DOM/属性读取；失败 Bad 翻成异常，
            // 工具层 runCatching 接住翻 ToolOutcome.Failed——与 screenshot 同规）
            override fun evidence(): String =
                when (val r = BrowserCommands.evidenceSnapshot()) {
                    is BrowserCommands.EvidenceCapture.Ok -> r.text
                    is BrowserCommands.EvidenceCapture.Bad -> throw IllegalStateException(r.error)
                }

            // 第一百七十七批 S3：抱朴这条线走**带前后校验**的内核（桥那条仍是原 `click`，
            // 回执形状不变）——「点好了」要带「页面变没变」，不许只说一句成功。
            override fun click(target: String): String =
                BrowserCommands.clickChecked(target)

            override fun typeInto(target: String, text: String): String =
                BrowserCommands.typeChecked(target, text)

            override fun scroll(direction: String): String {
                // 用 scrollBy（相对位移）而不是 BrowserCommands.scroll 的 scrollTo（绝对坐标）：
                // 绝对坐标要知道当前滚到哪了，工具层并不知道；scrollBy 超出范围由页面自己 clamp。
                val (dx, dy) = when (direction) {
                    "up" -> 0 to -SCROLL_STEP
                    "down" -> 0 to SCROLL_STEP
                    "left" -> -SCROLL_STEP to 0
                    "right" -> SCROLL_STEP to 0
                    else -> 0 to 0
                }
                return BrowserCommands.execJs("(function(){window.scrollBy($dx,$dy);return 'OK'})()")
            }

            /**
             * 第一百七十七批 S1：截图走 ref 出口，把**相对路径**交给工具层（→ 引擎读图）。
             * 失败用异常表达：工具层 `runCatching` 会翻成 `ToolOutcome.Failed`，
             * 这样模型看到的是「失败：截图失败：<原因>」，而不是一条看着像成功的回执。
             *
             * 268：落盘一条路（都进系统截屏夹 `Pictures/Screenshots/`），桥的 `album`
             * 参数已撤；这里的 [album] 只决定回执文案与「本轮读不读像素」（`Shot.album`）——
             * 用户要的那张，模型这轮不必再看一遍像素。
             */
            override fun screenshot(fullPage: Boolean, album: Boolean): Shot =
                when (
                    val r = BrowserCommands.captureShotRef(
                        appCtx,
                        null,
                        if (fullPage) "1" else "0",
                    )
                ) {
                    is BrowserCommands.ShotCapture.Ok -> {
                        val ref = r.ref
                        val info = pageInfo()
                        val text = buildString {
                            append(if (album) "已截图并存进相册（系统截屏夹）：" else "已截图：")
                            append(ref.file)
                            if (!album) {
                                append("（").append(ref.mode).append(' ')
                                append(ref.width).append('×').append(ref.height)
                                append("，").append(ref.bytes).append(" 字节）")
                            }
                            if (info != null && info.url.isNotBlank()) {
                                append("；页面：").append(SensitiveRedactor().redactUrl(info.url))
                            }
                            if (ref.note != null) append("\n（").append(ref.note).append('）')
                        }
                        Shot(text, if (album) null else ref.file, album = album)
                    }

                    is BrowserCommands.ShotCapture.Bad ->
                        throw IllegalStateException(r.error)
                }

            override fun tap(cssX: Int, cssY: Int): String =
                BrowserCommands.tap(cssX, cssY)

            override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String =
                BrowserCommands.swipe(x1, y1, x2, y2, durationMs)

            override fun longPress(cssX: Int, cssY: Int, durationMs: Int): String =
                BrowserCommands.longPress(cssX, cssY, durationMs)

            override fun key(key: String): String =
                BrowserCommands.keyPress(key)

            override fun waitFor(ms: Int, selector: String?): String =
                BrowserCommands.await(ms, selector)

            // 228 批 A-3：stable 等待接玄览内核（readyState==complete ＋ DOM 连续 200ms 无变化）
            override fun waitForStable(ms: Int): String =
                BrowserCommands.awaitStable(ms)

            // ── 228 批 B 片九件：与桥同一套 BrowserCommands 内核（差别只在没过 token） ──

            override fun goBack(): String = BrowserCommands.goBack()

            override fun goForward(): String = BrowserCommands.goForward()

            override fun reload(): String = BrowserCommands.reload()

            override fun selectOption(target: String, value: String): String =
                BrowserCommands.selectOption(target, value)

            override fun hover(target: String): String =
                BrowserCommands.hover(target)

            override fun scrollIntoView(target: String): String =
                BrowserCommands.scrollIntoView(target)

            override fun tabsList(): String = BrowserCommands.tabsList()

            override fun switchTab(tabId: Int): String =
                BrowserCommands.activeTab(tabId.toString())

            override fun closeTab(tabId: Int): String =
                BrowserCommands.closeTab(tabId.toString())

            /**
             * 当前页身份：读活跃标签的 `state`（**只读**，不改 `BrowserTabManager`——
             * 那是 172 的文件）。拿不到 ⇒ null，授权闸按「未知页面」照样要授权。
             */
            override fun pageInfo(): PageInfo? =
                BrowserTabManager.activeTab()?.let { PageInfo(it.state.url, it.state.title) }
        }

        // ── 本地预览（第五十九批 §5.1：`preview_local_file` 的显示落点） ──────────────
        //
        // 为什么也挂在这里：本文件就是「把工具接到玄览」那一层（文件头写明），
        // 预览的显示落点与浏览器六件是同一件事的两个入口，另开一个 Wiring 文件等于
        // 让"玄览的接线"散在两处——下次给玄览加运行时开关就得改两个地方。
        //
        // 为什么**投主线程**：BrowserTabManager 全类限 UI 线程（Compose snapshot 状态 +
        // WebView 构造要主 Looper；直调的那条崩溃原文写在 BrowserCommands 类 KDoc，第㉓刀）。
        // 工具执行在引擎的工作线程上，所以这里必须投。
        //
        // 为什么**投完就回、不等显示**（与上面 BrowserCommands.onMain 的区别）：
        // 这里要落的是"请求已登记"，不是"页面已画出来"。等待会把工具轮次押在主线程上，
        // 而主线程正可能卡在 WebView 构造里（§B 61 实测过 ≥100s）。登记与消费分开，
        // 界面没组合（用户还不在玄览）时请求就地等着，切过去那一帧被消费——
        // 这也是 [com.example.zhengdao.ui.browser.LocalPreviewHolder] 用可观察状态的原因。
        //
        // 越界与读文件都不在这一层：工具侧已用现成的 BaopuGuardCore 判过、读的是工作区内
        // 的文件（§5.2「只加载工作区内的文件，走既有守卫」），这里只负责"送进页签"。
        PreviewTool.sink = object : PreviewSink {
            override fun show(relPath: String, text: String): String? {
                main.post {
                    LocalPreviewHolder.request(relPath, text)
                    // 与抱朴预览卡「在玄览打开」同一条 B 路径：请求由 MainActivity 的
                    // ON_RESUME 消费。用户此刻就在玄览 ⇒ 这一句是 no-op；在别的 Tab ⇒
                    // 回前台时切过去。工具的回话里也照 BaopuPreview 那条纪律写明"切到玄览"，
                    // 不让用户把"没立刻出现"当成"点了没反应"（2026-10-18 那条教训）。
                    BrowserHolder.requestBrowserTab()
                }
                return null
            }
        }

        // ── 262 批第二步：切屏台真身 ＋ 预约式补拍的触发口 ──────────────────────
        //
        // 两件都挂这里（「玄览的接线只许有一个地方」）：引擎只认 ScreenSwitch 接口
        // （[com.example.zhengdao.baopu.ScreenSwitchDesk]），真身是 BrowserHolder 的
        // 状态机；预约队列的消费口挂在 onBrowserShown 上——玄览上屏那一刻投后台线程
        // drain（静默补拍当前页）。**必须投后台**：钩子在组合期（主线程）回调，
        // 桥命令（captureShotRef）主线程直调＝onMain 自等死锁（类 KDoc 第㉓刀）。
        ScreenSwitchDesk.impl = object : ScreenSwitch {
            override fun browserOnScreen(): Boolean = BrowserHolder.browserOnScreenNow()

            // 两个切换都引擎线程直调：requestTabTo 写的是 @Volatile＋AtomicBoolean＋可观察
            // 版本号，跨线程写由快照机制兜底（主线程下一次组合就消费）；不 post 的关键理由
            // 是 restoreAfterShot 的返回值要如实进引擎审计行——post 就只能乐观回 true，
            // 「用户手动切走就不抢」的裁决会被吞进异步里。
            override fun openForShot(): Int? = BrowserHolder.openForShot()

            override fun restoreAfterShot(origin: Int?): Boolean =
                BrowserHolder.restoreAfterShot(origin)

            // ── 262 批 §6 三级链路的四个口：都是转发，判据与动作的真身在
            //    BrowserHolder（1dp 载入台）/BrowserTabState（isRenderable）——
            //    本类是接线，不设第二真相。
            override fun pageRenderable(): Boolean =
                runCatching { BrowserHolder.pageRenderableNow() }.getOrDefault(false)

            override fun appInForeground(): Boolean =
                runCatching { BrowserHolder.appInForegroundNow() }.getOrDefault(true)

            override fun loadForShot(): Boolean =
                runCatching { BrowserHolder.loadForShot() }.getOrDefault(false)

            override fun detachForShot() {
                runCatching { BrowserHolder.detachForShot() }
            }
        }
        BrowserHolder.browserShownListener = {
            Thread {
                val ctx = appCtxRef?.get() ?: return@Thread
                VisualEvidenceQueue.drain(
                    shoot = { item ->
                        val shot = runCatching {
                            BrowserCommands.captureShotRef(ctx, null, "0")
                        }.getOrNull()
                        when (shot) {
                            is BrowserCommands.ShotCapture.Ok -> VisualEvidenceQueue.Done(
                                reason = item.reason,
                                enqueuedAtMs = item.enqueuedAtMs,
                                atMs = System.currentTimeMillis(),
                                shotRel = shot.ref.file,
                                error = null,
                            )
                            is BrowserCommands.ShotCapture.Bad ->
                                VisualEvidenceQueue.Done(
                                    reason = item.reason,
                                    enqueuedAtMs = item.enqueuedAtMs,
                                    atMs = System.currentTimeMillis(),
                                    shotRel = null,
                                    error = shot.error,
                                )
                            null -> VisualEvidenceQueue.Done(
                                reason = item.reason,
                                enqueuedAtMs = item.enqueuedAtMs,
                                atMs = System.currentTimeMillis(),
                                shotRel = null,
                                error = "补拍失败：桥调用异常",
                            )
                        }
                    },
                    append = { done ->
                        VisualEvidenceQueue.appendDone(teachGlobalRoot(ctx), done)
                    },
                )
            }.apply { name = "zd-visual-evidence"; isDaemon = true }.start()
        }
    }

    /** 进程级主线程 Handler（与 WebViewEngine 同规：不用 View 自身的 post，未 attach 时会压队列）。 */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    // ══ 228 批 C 片：示教/回放五件工具的真身（[TeachGateway]）═════════════════════
    //
    // 与浏览器件同住本文件的理由（文件头写明）：「玄览的接线」只许有一个地方。
    //
    // 会话模型用 lazy 单例（与 BaopuChatController.teachModel 每次新建不同）：工具侧的
    // stop → save 跨两次工具调用，草稿必须**共享**才接得上；UI 那条路不受影响（各自持有）。
    // 已知并存现象：UI 录制与 agent 录制同开时，页面桥（__zdTeachBridge）同名互替——
    // 轮询兜底仍在，事件不丢，只是两路各收一份；CONFIRM 弹卡保证 agent 发起录制时用户知情。

    @Volatile
    private var appCtxRef: WeakReference<Context>? = null

    private val teachScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main) }

    private val teachModel by lazy {
        val ctx = appCtxRef?.get() ?: error("BaopuBrowserWiring.install 还没跑")
        TeachSessionUiModel(
            scope = teachScope,
            context = ctx,
            tabId = { BrowserTabManager.activeTab()?.id },
            projectRoot = { BaopuRuntime.workspaceRoot(ctx) },
            globalRoot = { teachGlobalRoot(ctx) },
            personaFiles = { BaopuRuntime.personaFiles(ctx) },
        )
    }

    /** 示教停下来的草稿（stop 产出、save 消费；save 成败都清）。 */
    @Volatile
    private var lastDraft: TeachSkillDraft? = null

    @Volatile
    private var lastTeachError: String? = null

    private fun teachGlobalRoot(ctx: Context): File =
        File(ctx.filesDir, BaopuKernel.Data.DIR_GLOBAL_MEMORY)

    private fun installTeach(ctx: Context) {
        appCtxRef = WeakReference(ctx)
        BaopuTeach.gateway = object : TeachGateway {

            override fun currentUrl(): String? =
                BrowserTabManager.activeTab()?.state?.url?.takeIf { it.isNotBlank() }

            override fun trustedHosts(): List<String> = TeachSiteTrust.load(teachGlobalRoot(ctx))

            override fun trustAdd(raw: String): String? {
                val host = TeachSiteTrust.normalize(raw) ?: return "「$raw」不像可以信任的域名"
                if (!TeachSiteTrust.add(teachGlobalRoot(ctx), host)) {
                    return "信任名单没写成（盘上写失败了），没有当成已信任"
                }
                recordSiteTrust(ctx, "site_trust_add:$host", "信任站点 $host：示教与回放可在该域自动执行")
                return null
            }

            override fun trustRemove(raw: String): String? {
                val host = raw.trim().lowercase()
                if (!TeachSiteTrust.remove(teachGlobalRoot(ctx), host)) {
                    return "名单里没有「$host」——撤销了个寂寞（teach_site_trust list 现看名单）"
                }
                recordSiteTrust(ctx, "site_trust_remove:$host", "撤销信任 $host：示教与回放不再在该域自动执行")
                return null
            }

            override fun startRecording(): String {
                val model = teachModel
                if (model.recording) {
                    return "已经在录制了：这段示教还在进行（teach_record_stop 结束当前这段）"
                }
                lastTeachError = null
                model.start { msg -> lastTeachError = msg }
                return "开始示教：请像平常一样在页面上操作，做完说一声让我停（teach_record_stop）。" +
                    "我只记录、不代做。"
            }

            override fun stopRecording(): String {
                val model = teachModel
                if (!model.recording) {
                    return "现在没有在录制（先 teach_record_start 开始示教）"
                }
                val done = awaitMain<Pair<TeachStopKind, TeachSkillDraft?>> { onDone ->
                    model.stop { kind, draft -> onDone(kind to draft) }
                } ?: return "结束示教超时：录制还在收尾，稍后再试一次 teach_record_stop"
                val (kind, draft) = done
                lastDraft = draft
                return when (kind) {
                    TeachStopKind.DRAFT -> buildString {
                        val d = draft
                        append("已录到 ").append(d?.skill?.steps?.size ?: 0)
                        append(" 步，生成技能草稿「").append(d?.skill?.name ?: "?").append("」")
                        d?.summary?.takeIf { it.isNotBlank() }?.let { append("：").append(it) }
                        if (!d?.warnings.isNullOrEmpty()) {
                            append("\n注意：").append(d.warnings.joinToString("；"))
                        }
                        append("\n确认保存用 teach_skill_save（name 省略即用这个名字）。")
                    }
                    TeachStopKind.EMPTY_NO_EVENTS -> "没有录到任何操作：页面没动过，没有可存的技能。"
                    TeachStopKind.EMPTY_NAV_ONLY -> "只录到了页面跳转，没有可复用的操作，不会生成技能。"
                }
            }

            override fun saveDraft(name: String): String {
                val d = lastDraft
                    ?: return "现在没有待保存的示教草稿（先 teach_record_start → teach_record_stop 教一遍）"
                val skill = if (name.isBlank()) d.skill else d.skill.copy(name = name)
                val msg = awaitMain<String> { onDone ->
                    teachModel.save(skill, d.warnings) { onDone(it) }
                } ?: return "保存超时：草稿还留着，再试一次 teach_skill_save"
                lastDraft = null
                return msg
            }

            override fun skillAllowedDomains(name: String): List<String>? =
                TeachReplayStore.load(BaopuRuntime.workspaceRoot(ctx), name)?.allowedDomains

            override fun replay(name: String, sentence: String?, driftChoice: String?): String {
                val root = BaopuRuntime.workspaceRoot(ctx)
                // 第三百一十四批：人对漂移问题的回答接线进回放层；不认得的值退回 ASK（再问一遍）。
                val choice = DriftChoice.ofWire(driftChoice) ?: DriftChoice.ASK
                return if (sentence == null) {
                    TeachTools.reportLine(
                        TeachReplay.replaySkill(
                            context = ctx,
                            projectRoot = root,
                            workspace = root,
                            name = name,
                            tabId = BrowserTabManager.activeTab()?.id,
                            driftChoice = choice,
                        ),
                    )
                } else {
                    val run = replaySkillWithParams(
                        context = ctx,
                        projectRoot = root,
                        workspace = root,
                        name = name,
                        sentence = sentence,
                        tabId = BrowserTabManager.activeTab()?.id,
                        driftChoice = choice,
                    )
                    when {
                        run.ask != null -> run.ask
                        run.report != null -> {
                            val note = run.noteLine()
                            if (note.isEmpty()) TeachTools.reportLine(run.report)
                            else TeachTools.reportLine(run.report) + "（" + note + "）"
                        }
                        else -> "没跑起来：" + (run.stopReason ?: "原因不明")
                    }
                }
            }
        }
    }

    /** 站点信任的决策留痕（actor=USER：每一笔都过了确认卡才发生）。 */
    private fun recordSiteTrust(ctx: Context, evidence: String, reason: String) {
        DecisionLedger.recordTo(
            teachGlobalRoot(ctx),
            DecisionDraft(
                kind = DecisionKind.SITE_TRUST,
                actor = DecisionActor.USER,
                reason = reason,
                evidence = evidence,
                before = "",
                after = "",
                atMs = System.currentTimeMillis(),
            ),
        )
        DecisionLedger.lastError?.let { RunLog.log("site_trust 留痕失败：$it") }
    }

    /**
     * 工作线程 → Main 回调的桥（工具执行在引擎工作线程，会话模型回调在 Main）。
     * 限时等：等不到就回超时话术，**不无限吊住工具轮次**。
     */
    private inline fun <T> awaitMain(timeoutMs: Long = 15_000L, call: (onDone: (T) -> Unit) -> Unit): T? {
        val latch = CountDownLatch(1)
        var out: T? = null
        call { v ->
            out = v
            latch.countDown()
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return out
    }
}
