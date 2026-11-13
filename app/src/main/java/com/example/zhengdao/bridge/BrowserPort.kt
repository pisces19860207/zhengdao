// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.bridge

import android.content.Context
import com.example.zhengdao.core.RunLog
import java.io.File

/**
 * 玄览浏览器层的**唯一执行端口**（622「零模型执行端口」：浏览器层不当智能体，只暴露
 * 观察/动作/证据/回放/逃生口——规划留在抱朴，这里只做"放行/拦下/回闸"三件事）。
 *
 * ## 为什么要有这个端口（622 批 A①）
 *
 * 旧格局（BrowserCommands KDoc 那张分层图）：`BrowserCommands` 是命令执行层，三个调用方
 * ——抱朴网关（BaopuBrowserWiring）/ 示教回放（TeachReplay·TeachForm）/ 探针
 * （DriftWire·ImpactFacts）——**各自在入口把关**：URL scheme 靠 BridgePolicy、域名单靠
 * TeachSiteTrust、限速没有、急停只活在工具闸（GuardPipeline），一到浏览器这一层就散了。
 * 383 批删 HTTP 桥时留下的那句「调用方各自在入口把关」就是欠账：闸散在 N 处，等于没有闸。
 *
 * 622 收口：所有浏览器动作只许从本端口派发——**动作枚举＋参数校验＋URL/域名策略＋
 * 单域限速＋急停检查，五道闸全在 [gate] 这一处判**；判过才落到 [BrowserCommands]
 * （执行层：WebView/线程/脱敏/限长，一字未动）。源码断言（BrowserPortSourceGateTest）
 * 钉死：`bridge/` 之外不许出现任何 `BrowserCommands.` 引用——旧入口全部改成只认端口。
 *
 * ## 三档闸（622 批 A③）
 *
 * | 档 | 语义 | 端口行为 |
 * |---|---|---|
 * | [PortTier.READ] | 只读与浏览（open/scroll/snapshot/back/…） | 直接执行 |
 * | [PortTier.LOCAL] | 本地可逆（填入未提交/选中/切标签/关标签…） | 执行**并记账** |
 * | [PortTier.EXTERNAL] | 外部不可逆（提交/发布/支付/删除） | **拒绝执行**，回抱朴走闸 |
 *
 * 静态档位写在 [PortAction] 上；**动态升档**只有一条路：CLICK / ACT 的语义面
 * （[PortCall.subject]）命中 [PortIrreversible] 拒点词 ⇒ 升 EXTERNAL（词表只有一份，
 * 与示教层共用）。自由 JS（[PortAction.EXEC_JS]）静态 EXTERNAL：端口根本不提供
 * 自由脚本的执行口——页面内 JS 只能走 PROBE（固定只读查询）或 ACT（示教动作），
 * 新能力要显式加动作枚举，没有"顺手 eval 一下"的后门。
 *
 * ## 五道闸的次序（[gate]）
 *
 * 1. **急停**（[haltCheck] ⇒ 生产接 HaltGate）：停机中一个动作都不发——放在第一道，
 *    因为急停的语义是"从此刻起什么都不许做"，比任何单条策略都硬；
 * 2. **三档**：EXTERNAL 当场回闸（[PortStop.Escalated]），词表判据见 [PortIrreversible]；
 * 3. **参数校验**：URL scheme（BridgePolicy.checkNavigateUrl）、selector（防 JS 注入逃逸）、
 *    必填参数——拒绝的话连标签都不用看；
 * 4. **URL/域名策略**：动作带目标 URL 的判目标；其余判**当前页**的 host 是否落在
 *    调用方声明的授权域（[PortPolicy.allowedDomains]；null＝不收窄，scheme 闸仍在）。
 *    判据在端口内统一（负例：越白名单 ⇒ 拒并记账）；
 * 5. **单域限速**：同一 host 的动作数在滑动窗口内封顶（防失控循环打爆某个站点）。
 *
 * 拒绝（无论哪道闸）一律**记账**（[ledgerSink]：生产写 RunLog，域拒绝与升档还进
 * 决策记录）——「拒了什么」本身就是要留下的事实。
 *
 * ## 线程与返回值
 *
 * 约定与 [BrowserCommands] 一致：**不许在主线程调**（onMain 自等死锁，见其类 KDoc）。
 * 返回值维持各调用方的既有形状（JSON 字符串 / ShotCapture / EvidenceCapture）——
 * 收口只改"从哪里判"，不改回执形状，调用方的下游测试不回退。
 */
object BrowserPort {

    // ── 词表与档位 ──────────────────────────────────────────────────────────

    /** 三档闸的档位（语义见类 KDoc 的表）。 */
    enum class PortTier {
        /** 只读与浏览：直接执行。 */
        READ,

        /** 本地可逆：执行并记账。 */
        LOCAL,

        /** 外部不可逆：拒绝执行，回抱朴走闸。 */
        EXTERNAL,
    }

    /** 调用方身份（记账归属；限速键不按它分——限速按 host）。 */
    enum class PortCaller(val wire: String) {
        /** 抱朴工具路径（browser_* 工具；风险闸在工具层 CONFIRM）。 */
        BAOPU_AGENT("baopu_agent"),

        /** 示教录制（收割/事实采集——只读观察，页面由人操作）。 */
        TEACH_RECORD("teach_record"),

        /** 回放（查询/动作/取证截图/起点导航；授权域随技能）。 */
        TEACH_REPLAY("teach_replay"),

        /** 表单填充（FormFiller 的读写口）。 */
        TEACH_FORM("teach_form"),

        /** 契约漂移探针（存在性＋可见性，不取文本）。 */
        DRIFT_PROBE("drift_probe"),

        /** 影响面读取（formState 预览）。 */
        IMPACT_PROBE("impact_probe"),

        /** 补拍（VisualEvidenceQueue 的静默截图）。 */
        VISUAL_EVIDENCE("visual_evidence"),
    }

    /**
     * 动作枚举：端口词汇表里**只有这些动作**，每个带自己的静态档位。
     * 新能力必须显式加枚举＋显式选档——没有字符串动作名，没有"其它"兜底分支。
     */
    enum class PortAction(val wire: String, val tier: PortTier) {
        // ── READ：只读与浏览（622 口径：open/scroll/snapshot 这类直接执行） ──
        NAVIGATE("navigate", PortTier.READ),
        NEW_TAB("new_tab", PortTier.READ),
        BACK("back", PortTier.READ),
        FORWARD("forward", PortTier.READ),
        RELOAD("reload", PortTier.READ),
        SCROLL("scroll", PortTier.READ),
        SCROLL_INTO_VIEW("scroll_into_view", PortTier.READ),
        WAIT("wait", PortTier.READ),
        AWAIT_STABLE("await_stable", PortTier.READ),
        STATUS("status", PortTier.READ),
        SNAPSHOT("snapshot", PortTier.READ),
        EVIDENCE("evidence", PortTier.READ),
        SCREENSHOT("screenshot", PortTier.READ),
        TABS_LIST("tabs_list", PortTier.READ),
        /** 固定只读查询（示教/表单/漂移/影响面探针的脚本族；脚本由各模块的模板构造）。 */
        PROBE("probe", PortTier.READ),

        // ── LOCAL：本地可逆（执行并记账） ──
        CLICK("click", PortTier.LOCAL),
        TYPE("type", PortTier.LOCAL),
        SELECT("select", PortTier.LOCAL),
        HOVER("hover", PortTier.LOCAL),
        TAP("tap", PortTier.LOCAL),
        SWIPE("swipe", PortTier.LOCAL),
        LONG_PRESS("long_press", PortTier.LOCAL),
        KEY("key", PortTier.LOCAL),
        SWITCH_TAB("switch_tab", PortTier.LOCAL),
        CLOSE_TAB("close_tab", PortTier.LOCAL),
        /** 示教动作（十级定位器命中唯一才动手的脚本族；语义面命中拒点词 ⇒ 升档）。 */
        ACT("act", PortTier.LOCAL),

        // ── EXTERNAL：端口拒绝执行，回抱朴走闸 ──
        /**
         * 自由 JS。端口**不提供**自由脚本的执行口（见类 KDoc）——枚举里留着它，
         * 是为了让"有人想要自由 JS"这件事有一个明确的、带解释的回闸出口。
         */
        EXEC_JS("exec_js", PortTier.EXTERNAL),
    }

    /**
     * 一次调用的全部参数（可空字段按动作取用；多了不报错、缺了在参数闸拒）。
     *
     * 为什么是一把大参数包而不是每动作一个类：五道闸要在**一处**判所有动作——
     * 若每动作一个形状，闸就得每形状写一遍，等于没收口。缺参数的"空字段"由
     * 参数闸按动作点名（比形状系统早报、报得也跟动作语境一致）。
     */
    data class PortCall(
        val caller: PortCaller,
        val action: PortAction,
        val policy: PortPolicy = PortPolicy.OPEN,
        val tabId: String? = null,
        val context: Context? = null,
        val url: String? = null,
        val selector: String? = null,
        val text: String? = null,
        val value: String? = null,
        val mode: String? = null,
        val fullPage: String? = null,
        val x: Int? = null,
        val y: Int? = null,
        val x2: Int? = null,
        val y2: Int? = null,
        val dx: Int? = null,
        val dy: Int? = null,
        val durationMs: Int? = null,
        val key: String? = null,
        val ms: Int? = null,
        /** 固定脚本（PROBE/ACT 专用：由各模块的模板构造，端口不收自由 JS）。 */
        val script: String? = null,
        /** 候选元素语义面（text/name/aria/testid，可空；CLICK/ACT 升档判据的输入）。 */
        val subject: List<String> = emptyList(),
    )

    /**
     * 调用方声明的域策略：[allowedDomains] 为 null ＝ 不收窄（scheme 闸仍在，抱朴工具
     * 路径用它——风险在工具层 CONFIRM）；非空 ＝ 本调用的动作只许落在这些域内
     * （示教/回放路径传技能的 allowedDomains）。判据本体在 [gate]（端口内统一）。
     */
    data class PortPolicy(val allowedDomains: List<String>?) {
        companion object {
            val OPEN: PortPolicy = PortPolicy(null)
        }
    }

    /** 一次闸停（reason 是给人/模型看的一句话；wire 是机器键，测试与账本按它筛）。 */
    sealed class PortStop(val wire: String, val reason: String) {
        /** 急停生效中。 */
        class Halted(level: String) : PortStop("halt", "急停生效中（$level）：浏览器动作一律不发——解除只能人工在安全台操作")

        /** 外部不可逆：回抱朴走闸（不是错误，是档位）。 */
        class Escalated(reason: String) : PortStop("escalate", reason)

        /** 策略/参数拒绝。 */
        class Denied(reason: String) : PortStop("deny", reason)

        /** 单域限速命中。 */
        class Throttled(reason: String) : PortStop("throttle", reason)
    }

    /** 派发结局：判过闸 ＝ 带执行结果；判不过 ＝ 带停机原因。 */
    sealed interface PortOutcome {
        /** JSON 回包（BrowserCommands 的原样形状）。 */
        data class Done(val json: String) : PortOutcome

        /** 截图的落盘引用（或带原因的失败）。 */
        data class Shot(val capture: ShotCapture) : PortOutcome

        /** 证据快照（或带原因的失败）。 */
        data class Evidence(val capture: EvidenceCapture) : PortOutcome

        /** 闸停。 */
        data class Stopped(val stop: PortStop) : PortOutcome
    }

    /** 一次记账事件（拒绝/升档/本地可逆执行都发；值字段一律不进事件——只记目标形状）。 */
    data class LedgerEvent(
        val caller: PortCaller,
        val action: PortAction,
        val kind: String, // deny / escalate / local / throttle / halt
        val reason: String,
        val target: String,
        val atMs: Long,
    )

    // ── 装配（接线处一次性；测试用 resetForTest 还原） ────────────────────────

    /** 急停检查（生产由接线处指向 HaltGate.halted；JVM 测试直接替换）。 */
    @Volatile
    var haltCheck: () -> Boolean = { com.example.zhengdao.baopu.safety.HaltGate.halted() }
        private set

    /** 记账出口（生产由接线处指向 RunLog＋决策记录；缺省＝只进 RunLog）。 */
    @Volatile
    private var ledgerSink: (LedgerEvent) -> Unit = { e ->
        runCatching { RunLog.log(portLedgerLine(e)) }
    }

    /** 时钟（限速窗口用；JVM 测试注入假钟）。 */
    @Volatile
    var clock: () -> Long = System::currentTimeMillis
        private set

    /** 接线口（ZhengdaoApp 装配期调一次；幂等，重复调用只替换）。限速参数可调（测试收紧用）。 */
    fun install(
        halt: () -> Boolean,
        ledger: (LedgerEvent) -> Unit,
        nowMs: () -> Long = System::currentTimeMillis,
        rateMax: Int = DEFAULT_RATE_MAX,
        rateWindowMs: Long = DEFAULT_RATE_WINDOW_MS,
    ) {
        haltCheck = halt
        ledgerSink = ledger
        clock = nowMs
        limiter = RateLimiter(rateMax.coerceAtLeast(1), rateWindowMs.coerceAtLeast(1))
    }

    /** 测试还原口（缺省装配＝HaltGate＋RunLog；限速窗口清空）。 */
    fun resetForTest() {
        install({ com.example.zhengdao.baopu.safety.HaltGate.halted() }, { e ->
            runCatching { RunLog.log(portLedgerLine(e)) }
        })
    }

    /** 账本一行（与 RunLog 的中文口径一致；不带值字段）。 */
    private fun portLedgerLine(e: LedgerEvent): String =
        "浏览器端口｜${e.kind}｜${e.caller.wire}｜${e.action.wire}｜${e.target}｜${e.reason}"

    // ── 单域限速 ────────────────────────────────────────────────────────────

    /** 单域窗口默认值：60 秒内每 host 至多 150 个动作、全进程至多 450（回放一步 ≈6 次调用，十步技能远离此线）。 */
    const val DEFAULT_RATE_MAX: Int = 150
    const val DEFAULT_RATE_WINDOW_MS: Long = 60_000L
    private const val GLOBAL_RATE_KEY = "\u0000global"

    /**
     * 滑动窗口计数（进程内；重启即清——限速是熔断不是惩罚）。
     * 全进程上限＝单域上限 ×3（[DEFAULT_RATE_MAX]=150 ⇒ 全进程 450）：全局桶若与单域
     * 同限，回放/巡检的正常并发就会被别的 host 的流量提前顶死——全局桶只防失控总量。
     */
    private class RateLimiter(private val maxPerWindow: Int, private val windowMs: Long) {
        private val globalMax = maxPerWindow * GLOBAL_RATE_FACTOR
        private val hits = HashMap<String, MutableList<Long>>()

        /** true＝放行（并记一笔）；false＝超窗。 */
        @Synchronized
        fun allow(key: String, nowMs: Long, max: Int): Boolean {
            val list = hits.getOrPut(key) { mutableListOf() }
            list.removeAll { it <= nowMs - windowMs }
            if (list.size >= max) return false
            list += nowMs
            return true
        }

        fun hostMax(): Int = maxPerWindow
        fun globalLimit(): Int = globalMax
    }

    /** 全进程上限因子（见 [RateLimiter] KDoc）。 */
    const val GLOBAL_RATE_FACTOR: Int = 3

    /** 坐标系动作的缺省时长（与调用方旧值一致：滑动 300ms、长按 500ms）。 */
    private const val DEFAULT_SWIPE_MS: Int = 300
    private const val DEFAULT_PRESS_MS: Int = 500

    @Volatile
    private var limiter = RateLimiter(DEFAULT_RATE_MAX, DEFAULT_RATE_WINDOW_MS)

    // ── 五道闸（唯一判点） ────────────────────────────────────────────────────

    /**
     * 全部五道闸。返回 null ＝ 放行（往下执行）；非 null ＝ 拦下（记账后原样返回给调用方）。
     *
     * 次序见类 KDoc：急停 → 三档 → 参数 → 域 → 限速。任何一道拦下都先记账再回——
     * 「拒了什么」必须找得到（判据② 的负例就断言这条）。
     */
    private fun gate(call: PortCall): PortStop? {
        // ① 急停：停机中什么都不发（软停/硬停都拦——HaltGate.halted 的语义）
        if (haltCheck()) {
            val level = com.example.zhengdao.baopu.safety.HaltGate.level().cn
            return ledgered(call, PortStop.Halted(level))
        }
        // ② 三档：静态档位 ＋ 语义面升档
        tierStop(call)?.let { return ledgered(call, it) }
        // ③ 参数校验（scheme/selector/必填；判据与 BridgePolicy 同源）
        paramStop(call)?.let { return ledgered(call, it) }
        // ④ URL/域名策略（带目标 URL 的判目标，其余判当前页）
        domainStop(call)?.let { return ledgered(call, it) }
        // ⑤ 单域限速
        throttleStop(call)?.let { return ledgered(call, it) }
        return null
    }

    /** 拦下统一走这里：先记账再回（记账失败不影响拦截——拦截是闸的事，账是账的事）。 */
    private fun ledgered(call: PortCall, stop: PortStop): PortStop {
        runCatching {
            ledgerSink(
                LedgerEvent(
                    caller = call.caller,
                    action = call.action,
                    kind = stop.wire,
                    reason = stop.reason,
                    target = ledgerTargetOf(call),
                    atMs = clock(),
                ),
            )
        }
        return stop
    }

    /** LOCAL 执行成功后的记账（判据⑤/A③：本地可逆也要留痕）。 */
    private fun ledgerLocal(call: PortCall) {
        runCatching {
            ledgerSink(
                LedgerEvent(
                    caller = call.caller,
                    action = call.action,
                    kind = "local",
                    reason = "本地可逆动作已执行",
                    target = ledgerTargetOf(call),
                    atMs = clock(),
                ),
            )
        }
    }

    /** 账本的目标形状：URL 动作记 host，元素动作记 selector 回声（值字段永不进账）。 */
    private fun ledgerTargetOf(call: PortCall): String = when {
        call.url != null -> BridgePolicy.hostOf(call.url) ?: "?"
        call.selector != null -> call.selector.take(80)
        else -> ""
    }

    /**
     * ② 三档判定。静态 EXTERNAL 直接回闸；CLICK/ACT 的语义面命中拒点词 ⇒ 动态升档。
     * 词表只有一份（[PortIrreversible]，示教层同源）。
     */
    private fun tierStop(call: PortCall): PortStop? {
        if (call.action.tier == PortTier.EXTERNAL) {
            return PortStop.Escalated(
                "「${call.action.wire}」属外部不可逆档：端口不执行——回抱朴走闸" +
                    "（提交/发布/支付/删除类一律经确认卡；自由 JS 不在端口提供）",
            )
        }
        if (call.action == PortAction.CLICK || call.action == PortAction.ACT) {
            PortIrreversible.reasonIn(call.subject)?.let { return PortStop.Escalated(it) }
        }
        return null
    }

    /**
     * ③ 参数校验。只校验"会进 JS/引擎的形状"（URL scheme、selector、必填）；
     * 数值域（坐标/时长）由执行层既有口径夹取，端口不重复——两处判就两处漂。
     */
    private fun paramStop(call: PortCall): PortStop? {
        when (call.action) {
            PortAction.NAVIGATE, PortAction.NEW_TAB -> {
                val v = BridgePolicy.checkNavigateUrl(call.url)
                if (v is BridgePolicy.Verdict.Deny) return PortStop.Denied(v.reason)
            }

            PortAction.SNAPSHOT -> {
                val v = BridgePolicy.checkSnapshotMode(call.mode)
                if (v is BridgePolicy.Verdict.Deny) return PortStop.Denied(v.reason)
            }

            PortAction.PROBE, PortAction.ACT ->
                if (call.script.isNullOrBlank()) return PortStop.Denied("${call.action.wire} 缺固定脚本")

            PortAction.TYPE -> if (call.text == null) return PortStop.Denied("type 缺 text")
            PortAction.SELECT -> if (call.value == null) return PortStop.Denied("select 缺 value")
            else -> Unit
        }
        return selectorStop(call)
    }

    /** selector 系动作的公共校验（@ref 句柄不走 CSS 校验——与 BrowserCommands 同口径）。 */
    private fun selectorStop(call: PortCall): PortStop? {
        val needsSelector = when (call.action) {
            PortAction.CLICK, PortAction.TYPE, PortAction.SELECT, PortAction.HOVER,
            PortAction.SCROLL_INTO_VIEW,
            -> true

            PortAction.SCROLL, PortAction.SNAPSHOT -> call.selector != null
            else -> false
        }
        if (!needsSelector) return null
        val sel = call.selector ?: return PortStop.Denied("${call.action.wire} 缺 selector")
        if (sel.startsWith("@")) return null
        val v = BridgePolicy.checkSelector(sel)
        return if (v is BridgePolicy.Verdict.Deny) PortStop.Denied(v.reason) else null
    }

    /**
     * ④ URL/域名策略。带目标 URL 的动作判目标；其余动作判**当前页** host——
     * 页面已经越出授权域时，后续动作一个都不放（回放的"每步出域即停"由端口兜底）。
     * 白名单判据本体在 [BridgePolicy.hostAllowedIn]（纯函数，与示教层同源）。
     */
    private fun domainStop(call: PortCall): PortStop? {
        val domains = call.policy.allowedDomains ?: return null
        val url = when (call.action) {
            PortAction.NAVIGATE, PortAction.NEW_TAB -> call.url
            else -> currentUrlOf(call.tabId)
        }
        if (url.isNullOrBlank()) {
            return PortStop.Denied("拿不到页面地址（标签没开或没载入）：授权域判不了——fail-closed 不放行")
        }
        if (!BridgePolicy.hostAllowedIn(url, domains)) {
            val host = BridgePolicy.hostOf(url) ?: "?"
            return PortStop.Denied(
                "URL/域名策略：$host 不在授权域（${domains.joinToString("、")}）——拒并记账",
            )
        }
        return null
    }

    /** 当前页地址（活跃标签或 tabId 指定标签的 state.url；只读，不动标签）。 */
    private fun currentUrlOf(tabId: String?): String? = runCatching {
        if (tabId.isNullOrBlank()) {
            com.example.zhengdao.ui.browser.BrowserTabManager.activeTab()?.state?.url
        } else {
            val id = tabId.trim().toIntOrNull()
            com.example.zhengdao.ui.browser.BrowserTabManager.tabById(id ?: return null)?.state?.url
        }
    }.getOrNull()

    /** ⑤ 单域限速（键＝目标/当前页 host；拿不到 host 的调用反正到不了页面，不计数）。 */
    private fun throttleStop(call: PortCall): PortStop? {
        val url = when (call.action) {
            PortAction.NAVIGATE, PortAction.NEW_TAB -> call.url
            else -> currentUrlOf(call.tabId)
        }
        val host = url?.let { BridgePolicy.hostOf(it) } ?: return null
        val now = clock()
        val globalOk = limiter.allow(GLOBAL_RATE_KEY, now, limiter.globalLimit())
        val hostOk = limiter.allow(host, now, limiter.hostMax())
        return if (globalOk && hostOk) {
            null
        } else {
            PortStop.Throttled(
                "单域限速：$host 在窗口内动作数已到上限（${limiter.hostMax()}/${DEFAULT_RATE_WINDOW_MS} ms）——熔断片刻再试",
            )
        }
    }

    // ── 派发（判过闸 → 执行） ────────────────────────────────────────────────

    /**
     * 唯一派发口：先过 [gate]，判过才按动作落到执行层。执行段只做"选哪个
     * BrowserCommands 入口"，不做任何判断——判断只发生在 [gate]。
     */
    fun dispatch(call: PortCall): PortOutcome {
        gate(call)?.let { return PortOutcome.Stopped(it) }
        // 要 Context 的两个动作（建 WebView / 落截图盘）没有 Context 就如实拒，不 NPE
        val ctx = call.context
        if ((call.action == PortAction.NEW_TAB || call.action == PortAction.SCREENSHOT) && ctx == null) {
            return PortOutcome.Stopped(PortStop.Denied("${call.action.wire} 需要应用上下文（context 缺失）"))
        }
        // 执行段只做"选哪个 BrowserCommands 入口"，按四族分流（判断只发生在 gate）
        return when (call.action) {
            PortAction.NAVIGATE, PortAction.NEW_TAB, PortAction.BACK, PortAction.FORWARD, PortAction.RELOAD ->
                execNavigation(call, ctx)
            PortAction.STATUS, PortAction.SNAPSHOT, PortAction.EVIDENCE, PortAction.SCREENSHOT,
            PortAction.TABS_LIST, PortAction.PROBE, PortAction.AWAIT_STABLE,
            -> execObserve(call, ctx)
            PortAction.SCROLL, PortAction.SCROLL_INTO_VIEW, PortAction.WAIT -> execScrollWait(call)
            PortAction.CLICK, PortAction.TYPE, PortAction.SELECT, PortAction.HOVER, PortAction.TAP,
            PortAction.SWIPE, PortAction.LONG_PRESS, PortAction.KEY, PortAction.SWITCH_TAB,
            PortAction.CLOSE_TAB, PortAction.ACT,
            -> execLocal(call)
            // EXTERNAL 到不了这里（tierStop 已拦）；穷尽性兜底
            PortAction.EXEC_JS -> PortOutcome.Stopped(PortStop.Escalated("自由 JS 不在端口执行"))
        }
    }

    /** 导航族（READ）：开/开新签/后退/前进/重载。 */
    private fun execNavigation(call: PortCall, ctx: Context?): PortOutcome = when (call.action) {
        PortAction.NAVIGATE -> PortOutcome.Done(BrowserCommands.navigate(call.url, call.tabId))
        PortAction.NEW_TAB -> PortOutcome.Done(BrowserCommands.newTab(ctx!!, call.url))
        PortAction.BACK -> PortOutcome.Done(BrowserCommands.goBack(call.tabId))
        PortAction.FORWARD -> PortOutcome.Done(BrowserCommands.goForward(call.tabId))
        PortAction.RELOAD -> PortOutcome.Done(BrowserCommands.reload(call.tabId))
        else -> PortOutcome.Stopped(PortStop.Denied("不该到导航族：${call.action.wire}"))
    }

    /** 观察族（READ）：状态/快照/证据/截图/标签列表/探针/稳等。 */
    private fun execObserve(call: PortCall, ctx: Context?): PortOutcome = when (call.action) {
        PortAction.STATUS -> PortOutcome.Done(BrowserCommands.status(call.tabId))
        PortAction.SNAPSHOT -> PortOutcome.Done(BrowserCommands.snapshot(call.mode, call.selector, call.tabId))
        PortAction.EVIDENCE -> PortOutcome.Evidence(BrowserCommands.evidenceSnapshot(call.tabId))
        PortAction.SCREENSHOT -> PortOutcome.Shot(
            BrowserCommands.captureShotRef(ctx!!, call.tabId, call.fullPage, call.selector),
        )
        PortAction.TABS_LIST -> PortOutcome.Done(BrowserCommands.tabsList())
        PortAction.PROBE -> PortOutcome.Done(BrowserCommands.execJs(call.script!!, call.tabId))
        PortAction.AWAIT_STABLE -> PortOutcome.Done(BrowserCommands.awaitStable(call.ms ?: 2_000))
        else -> PortOutcome.Stopped(PortStop.Denied("不该到观察族：${call.action.wire}"))
    }

    /** 滚动与等待族（READ）。 */
    private fun execScrollWait(call: PortCall): PortOutcome = when (call.action) {
        PortAction.SCROLL -> PortOutcome.Done(executeScroll(call))
        PortAction.SCROLL_INTO_VIEW -> PortOutcome.Done(BrowserCommands.scrollIntoView(call.selector!!, call.tabId))
        PortAction.WAIT -> PortOutcome.Done(BrowserCommands.await(call.ms ?: 0, call.selector, call.tabId))
        else -> PortOutcome.Stopped(PortStop.Denied("不该到滚动等待族：${call.action.wire}"))
    }


    /** 本地可逆族（LOCAL：执行＋记账）。selector/text/value 的非空由参数闸保证。 */
    private fun execLocal(call: PortCall): PortOutcome = when (call.action) {
        PortAction.CLICK, PortAction.TYPE, PortAction.SELECT, PortAction.HOVER,
        PortAction.SWITCH_TAB, PortAction.CLOSE_TAB, PortAction.ACT,
        -> execLocalTargeted(call)
        PortAction.TAP, PortAction.SWIPE, PortAction.LONG_PRESS, PortAction.KEY -> execLocalPointed(call)
        else -> PortOutcome.Stopped(PortStop.Denied("不该到本地可逆族：${call.action.wire}"))
    }

    /** 本地可逆·目标系（元素/标签动作）。 */
    private fun execLocalTargeted(call: PortCall): PortOutcome = when (call.action) {
        PortAction.CLICK -> PortOutcome.Done(ledgerLocalJson(call, BrowserCommands.clickChecked(call.selector!!, call.tabId)))
        PortAction.TYPE -> PortOutcome.Done(ledgerLocalJson(call, BrowserCommands.typeChecked(call.selector!!, call.text!!, call.tabId)))
        PortAction.SELECT -> PortOutcome.Done(ledgerLocalJson(call, BrowserCommands.selectOption(call.selector!!, call.value!!, call.tabId)))
        PortAction.HOVER -> PortOutcome.Done(ledgerLocalJson(call, BrowserCommands.hover(call.selector!!, call.tabId)))
        PortAction.SWITCH_TAB -> PortOutcome.Done(ledgerLocalJson(call, BrowserCommands.activeTab(call.tabId)))
        PortAction.CLOSE_TAB -> PortOutcome.Done(ledgerLocalJson(call, BrowserCommands.closeTab(call.tabId)))
        PortAction.ACT -> PortOutcome.Done(ledgerLocalJson(call, BrowserCommands.execJs(call.script!!, call.tabId)))
        else -> PortOutcome.Stopped(PortStop.Denied("不该到本地可逆·目标系：${call.action.wire}"))
    }

    /** 本地可逆·坐标系（触摸/按键动作；数值域由执行层夹取）。 */
    private fun execLocalPointed(call: PortCall): PortOutcome {
        val x = call.x ?: 0
        val y = call.y ?: 0
        val dur = call.durationMs
        return when (call.action) {
            PortAction.TAP -> PortOutcome.Done(ledgerLocalJson(call, BrowserCommands.tap(x, y, call.tabId)))
            PortAction.SWIPE -> PortOutcome.Done(
                ledgerLocalJson(
                    call,
                    BrowserCommands.swipe(
                        x, y,
                        call.x2 ?: 0, call.y2 ?: 0,
                        dur ?: DEFAULT_SWIPE_MS,
                        call.tabId,
                    ),
                ),
            )
            PortAction.LONG_PRESS -> PortOutcome.Done(
                ledgerLocalJson(call, BrowserCommands.longPress(x, y, dur ?: DEFAULT_PRESS_MS, call.tabId)),
            )
            PortAction.KEY -> PortOutcome.Done(ledgerLocalJson(call, BrowserCommands.keyPress(call.key ?: "", call.tabId)))
            else -> PortOutcome.Stopped(PortStop.Denied("不该到本地可逆·坐标系：${call.action.wire}"))
        }
    }

    /** SCROLL 的两种落法：dx/dy＝相对滚动（端口内固定脚本）；x/y/selector＝既有绝对/元素滚动。 */
    private fun executeScroll(call: PortCall): String =
        if (call.dx != null || call.dy != null) {
            BrowserCommands.execJs(
                "(function(){window.scrollBy(${call.dx ?: 0},${call.dy ?: 0});return 'OK'})()",
                call.tabId,
            )
        } else {
            BrowserCommands.scroll(call.x?.toString(), call.y?.toString(), call.selector, call.tabId)
        }

    /** LOCAL 动作的记账（JSON 路出口；执行层的回包原样透传，账只记"做了"。） */
    private fun ledgerLocalJson(call: PortCall, json: String): String {
        // 回包失败（ok:false）也照记：动作发过了、页面没接——账面要能看出"发过"。
        ledgerLocal(call)
        return json
    }

    // ── 字符串出口的统一翻译（调用方拿到的形状与旧入口一致） ────────────────────

    /** Stopped → 调用方 JSON（带 stop 机器键；测试与账本按它筛 deny/escalate）。 */
    private fun stoppedJson(stop: PortStop): String =
        "{\"ok\":false,\"error\":\"${com.example.zhengdao.browser.PageSnapshot.jsonEscape(stop.reason)}\",\"stop\":\"${stop.wire}\"}"

    /** JSON 路的便捷出口：判过闸＝执行层回包；拦下＝带 stop 键的错误 JSON。 */
    private fun run(call: PortCall): String =
        when (val o = dispatch(call)) {
            is PortOutcome.Done -> o.json
            is PortOutcome.Stopped -> stoppedJson(o.stop)
            else -> stoppedJson(PortStop.Denied("该动作不走 JSON 出口"))
        }

    private fun runShot(call: PortCall): ShotCapture =
        when (val o = dispatch(call)) {
            is PortOutcome.Shot -> o.capture
            is PortOutcome.Stopped -> ShotCapture.Bad(o.stop.reason)
            else -> ShotCapture.Bad("该动作不走截图出口")
        }

    private fun runEvidence(call: PortCall): EvidenceCapture =
        when (val o = dispatch(call)) {
            is PortOutcome.Evidence -> o.capture
            is PortOutcome.Stopped -> EvidenceCapture.Bad(o.stop.reason)
            else -> EvidenceCapture.Bad("该动作不走证据出口")
        }

    // ── 类型化入口（三处旧入口只许调这些；每个都是"装包 → dispatch → 翻译"） ─────

    /** 打开 URL（currentTab=false ⇒ 开新标签；即 622 口径里的 open）。 */
    fun open(caller: PortCaller, url: String, newTab: Boolean, context: Context?, tabId: String?, policy: PortPolicy): String =
        run(PortCall(caller = caller, action = if (newTab) PortAction.NEW_TAB else PortAction.NAVIGATE, policy = policy, url = url, tabId = tabId, context = context))

    fun navigate(caller: PortCaller, url: String, tabId: String?, policy: PortPolicy): String =
        run(PortCall(caller = caller, action = PortAction.NAVIGATE, policy = policy, url = url, tabId = tabId))

    fun status(caller: PortCaller, tabId: String?, policy: PortPolicy): String =
        run(PortCall(caller = caller, action = PortAction.STATUS, policy = policy, tabId = tabId))

    fun snapshot(caller: PortCaller, mode: String?, selector: String?, tabId: String?, policy: PortPolicy): String =
        run(PortCall(caller = caller, action = PortAction.SNAPSHOT, policy = policy, mode = mode, selector = selector, tabId = tabId))

    fun evidence(caller: PortCaller, tabId: String?, policy: PortPolicy): EvidenceCapture =
        runEvidence(PortCall(caller = caller, action = PortAction.EVIDENCE, policy = policy, tabId = tabId))

    fun screenshotRef(
        caller: PortCaller,
        context: Context,
        tabId: String?,
        fullPage: String?,
        selector: String?,
        policy: PortPolicy,
    ): ShotCapture =
        runShot(
            PortCall(caller = caller, action = PortAction.SCREENSHOT, policy = policy, tabId = tabId, context = context, fullPage = fullPage, selector = selector),
        )

    /** 点击（[subject] 是候选元素语义面——命中拒点词 ⇒ 回闸；空面＝交给上游确认闸兜底）。 */
    fun click(caller: PortCaller, target: String, tabId: String?, subject: List<String>, policy: PortPolicy): String =
        run(PortCall(caller = caller, action = PortAction.CLICK, policy = policy, selector = target, tabId = tabId, subject = subject))

    fun typeInto(caller: PortCaller, target: String, text: String, tabId: String?, policy: PortPolicy): String =
        run(PortCall(caller = caller, action = PortAction.TYPE, policy = policy, selector = target, text = text, tabId = tabId))

    fun selectOption(caller: PortCaller, target: String, value: String, tabId: String?, policy: PortPolicy): String =
        run(PortCall(caller = caller, action = PortAction.SELECT, policy = policy, selector = target, value = value, tabId = tabId))

    fun hover(caller: PortCaller, target: String, tabId: String?, policy: PortPolicy): String =
        run(PortCall(caller = caller, action = PortAction.HOVER, policy = policy, selector = target, tabId = tabId))

    fun scrollIntoView(caller: PortCaller, target: String, tabId: String?, policy: PortPolicy): String =
        run(PortCall(caller = caller, action = PortAction.SCROLL_INTO_VIEW, policy = policy, selector = target, tabId = tabId))

    /** 绝对坐标/元素滚动（工具路径；相对滚动走 [scrollBy]）。 */
    fun scroll(caller: PortCaller, x: Int?, y: Int?, selector: String?, tabId: String?, policy: PortPolicy): String =
        run(PortCall(caller = caller, action = PortAction.SCROLL, policy = policy, x = x, y = y, selector = selector, tabId = tabId))

    /** 相对滚动（抱朴工具路径的 up/down/left/right；622 口径里 scroll 属只读）。 */
    fun scrollBy(caller: PortCaller, dx: Int, dy: Int, tabId: String?, policy: PortPolicy): String =
        run(PortCall(caller = caller, action = PortAction.SCROLL, policy = policy, dx = dx, dy = dy, tabId = tabId))

    fun tap(caller: PortCaller, cssX: Int, cssY: Int, tabId: String?, policy: PortPolicy): String =
        run(PortCall(caller = caller, action = PortAction.TAP, policy = policy, x = cssX, y = cssY, tabId = tabId))

    fun swipe(caller: PortCaller, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, tabId: String?, policy: PortPolicy): String =
        run(PortCall(caller = caller, action = PortAction.SWIPE, policy = policy, x = x1, y = y1, x2 = x2, y2 = y2, durationMs = durationMs, tabId = tabId))

    fun longPress(caller: PortCaller, cssX: Int, cssY: Int, durationMs: Int, tabId: String?, policy: PortPolicy): String =
        run(PortCall(caller = caller, action = PortAction.LONG_PRESS, policy = policy, x = cssX, y = cssY, durationMs = durationMs, tabId = tabId))

    fun key(caller: PortCaller, key: String, tabId: String?, policy: PortPolicy): String =
        run(PortCall(caller = caller, action = PortAction.KEY, policy = policy, key = key, tabId = tabId))

    fun wait(caller: PortCaller, ms: Int, selector: String?, tabId: String?, policy: PortPolicy): String =
        run(PortCall(caller = caller, action = PortAction.WAIT, policy = policy, ms = ms, selector = selector, tabId = tabId))

    fun awaitStable(caller: PortCaller, ms: Int, policy: PortPolicy): String =
        run(PortCall(caller = caller, action = PortAction.AWAIT_STABLE, policy = policy, ms = ms))

    fun goBack(caller: PortCaller, tabId: String?, policy: PortPolicy): String =
        run(PortCall(caller = caller, action = PortAction.BACK, policy = policy, tabId = tabId))

    fun goForward(caller: PortCaller, tabId: String?, policy: PortPolicy): String =
        run(PortCall(caller = caller, action = PortAction.FORWARD, policy = policy, tabId = tabId))

    fun reload(caller: PortCaller, tabId: String?, policy: PortPolicy): String =
        run(PortCall(caller = caller, action = PortAction.RELOAD, policy = policy, tabId = tabId))

    fun tabsList(caller: PortCaller, policy: PortPolicy): String =
        run(PortCall(caller = caller, action = PortAction.TABS_LIST, policy = policy))

    fun switchTab(caller: PortCaller, tabId: Int, policy: PortPolicy): String =
        run(PortCall(caller = caller, action = PortAction.SWITCH_TAB, policy = policy, tabId = tabId.toString()))

    fun closeTab(caller: PortCaller, tabId: Int, policy: PortPolicy): String =
        run(PortCall(caller = caller, action = PortAction.CLOSE_TAB, policy = policy, tabId = tabId.toString()))

    /**
     * 固定只读查询（PROBE）：脚本由调用方模块的**模板**构造（TeachScripts/FormScripts/
     * 漂移探针/影响面），端口不收自由 JS——这个参数是模板产物，不是模型的输入。
     */
    fun probe(caller: PortCaller, script: String, tabId: String?, policy: PortPolicy): String =
        run(PortCall(caller = caller, action = PortAction.PROBE, policy = policy, script = script, tabId = tabId))

    /**
     * 示教动作（ACT）：十级定位器命中唯一才动手的脚本族。[subject] 是候选元素语义面
     * （CLICK 步带上；命中拒点词 ⇒ 端口回闸——回放再想点提交按钮，端口也拦）。
     */
    fun act(caller: PortCaller, script: String, subject: List<String>, tabId: String?, policy: PortPolicy): String =
        run(PortCall(caller = caller, action = PortAction.ACT, policy = policy, script = script, tabId = tabId, subject = subject))

    /**
     * 存量迁移目录的转发口（**非动作**，不过闸）：`BrowserCommands.shotDir` 只服务
     * 截图存量迁移（MediaPathGateTest 钉着它）。放在端口是为了让"bridge 之外不许出现
     * BrowserCommands 引用"的源码断言零例外。
     */
    fun legacyShotDir(context: Context): File = BrowserCommands.shotDir(context)
}
