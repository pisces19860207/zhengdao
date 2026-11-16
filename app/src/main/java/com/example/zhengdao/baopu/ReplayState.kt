// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百七十一批 · 回放可见（状态源）：`BrowserScreen.kt:217` 那句「回放中当前无状态源」
// 是这一批的起点——先造状态源，再谈可见。
//
// 三处可见物（顶部指示条 / 玄览胶囊进度 / 失败卡）读的都是**这一个** [ReplayState]：
// · 写口只有回放执行器那条链（`TeachExecutor` 的 onStep / onFailed 回调，装配在 `TeachReplay`）；
// · 读口是 UI（`ReplayUi.kt` 的指示条与失败卡、`XuanlanCapsule.kt` 的胶囊进度字）；
// · 页面不许直接写它（与 [TeachSessionState] 同一条纪律：单一真相源）。
//
// 为什么放在 `baopu` 而不是 `ui`：状态的生命周期跟一次回放（后台协程）走，不跟组合走。
// 切页 / 切标签不结束回放，也不该清零指示条——这正是 623 那条教训（remember 随组合销毁）。
//
// 线程契约：写口在 IO 协程里，读口在组合里。Compose 的 `mutableStateOf` 写入是快照安全的，
// 后台线程写、UI 线程读是既定用法（638 的轮询同型）；「停止」这一个跨线程信号走原子量，
// 不用 Compose 状态，避免执行器为了读一个标志去抢快照。
package com.example.zhengdao.baopu

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一次回放的失败事实（失败卡的三行数据＋「让抱朴改这条技能」那句失败报告的原料）。
 *
 * 字段与判据④逐条对应：第几步（[step]）／目标（[target]）／命中几个（[hits]）／原因（[reason]）。
 * [hits] 允许为空：有些失败（域外、期望断言超时）不是定位失败，此时卡上印「未知」而不是编一个 0。
 */
data class ReplayFailure(
    val skillName: String,
    val step: Int?,
    val total: Int,
    val target: String,
    val hits: Int?,
    val reason: String,
    val suggestion: String? = null,
) {

    /** 给抱朴的失败报告（判据④：非空，且带得清「哪一步、什么目标、命中几个、为什么」）。 */
    fun reportText(): String = buildString {
        append("回放没跑完：技能「").append(skillName.ifBlank { "未命名" }).append("」")
        append(step?.let { "第 $it 步" } ?: "起手检查")
        if (total > 0) append("（共 $total 步）")
        appendLine("。")
        append("目标：").append(target.ifBlank { "（这一步没有目标元素）" })
        appendLine()
        append("定位命中：").append(hits?.let { "$it 个" } ?: "未知")
        appendLine()
        append("原因：").append(reason)
        suggestion?.takeIf { it.isNotBlank() }?.let { appendLine(); append("建议：").append(it) }
        appendLine()
        append("请照这份失败报告改这条技能（改完说一句改了什么）。")
    }
}

/** 回放可见物的文案口径（纯函数；JVM 直测「≤40 字」与截断，不靠真机）。 */
internal object ReplayText {

    /** 判据①：指示条整行 ≤40 字。 */
    const val BAR_MAX = 40

    /** 目标名在句子里最多占的字数（超过就省略号，别把整句挤没）。 */
    private const val TARGET_MAX = 12

    /**
     * 顶部指示条整行的收口：`<步号头> ＋ 动作短语`，整行硬性 ≤40 字（判据①）。
     *
     * 头（「回放中 2/5 · 」）来自字符串资源（跟界面语言走），动作短语由 [actionPhrase] 生。
     * 截断只动**动作短语**：步号永远完整（那是「跑到哪了」的唯一刻度），超出用省略号收。
     * 万一某种语言的步号头本身就超过 40 字，整行按 40 截——判据是硬上限，不因翻译放水。
     */
    fun fitBar(head: String, action: String): String {
        val room = BAR_MAX - head.length
        if (room <= 0) return head.take(BAR_MAX)
        val body = if (action.length <= room) action else action.take((room - 1).coerceAtLeast(0)) + "…"
        return (head + body).take(BAR_MAX)
    }

    /** 一步要动什么 → 一句人话（[currentAction] 的唯一生成处）。 */
    fun actionPhrase(action: TeachAction, target: String): String = when (action) {
        TeachAction.CLICK -> "正在点「${short(target)}」"
        TeachAction.TYPE -> "正在填「${short(target)}」"
        TeachAction.SELECT -> "正在选「${short(target)}」"
        TeachAction.SCROLL -> "正在滚到「${short(target)}」"
        TeachAction.WAIT -> "正在等页面变化"
    }

    /** 胶囊里的进度字（48dp 装不下整句；形态不动，只换这一行字）。 */
    fun capsuleText(index: Int, total: Int): String = if (total > 0) "$index/$total" else "回放"

    private fun short(target: String): String {
        val t = target.trim().ifBlank { "这一步" }
        return if (t.length <= TARGET_MAX) t else t.take(TARGET_MAX - 1) + "…"
    }
}

/**
 * 回放的**唯一**状态源（判据⑤）：指示条、胶囊进度、失败卡全读这里。
 *
 * 写口只有三个，且都只由回放那条链调用：[begin] / [noteStep] / [markFailure]，收口 [end]。
 * 「停止钮」是第四条：[requestStop] 只置一个原子标志＋立刻把 [playing] 落下（判据③：
 * 指示条当场消失），执行器在下一步动手前看到标志就停——页面动作不再继续。
 */
object ReplayState {

    /** 回放进行中（判据①：它为真才有指示条；判据③：它为假指示条必须不存在）。 */
    var playing: Boolean by mutableStateOf(false)
        private set

    /** 当前第几步（1 起；判据②：单调 1→N）。 */
    var stepIndex: Int by mutableIntStateOf(0)
        private set

    /** 这条技能一共几步（判据②：N 与脚本步数一致，来自 `skill.steps.size`）。 */
    var stepTotal: Int by mutableIntStateOf(0)
        private set

    /** 当前步在干什么（判据②那句「正在点「X」」的原料；空串＝还没有步）。 */
    var currentAction: String by mutableStateOf("")
        private set

    /** 失败事实（非 null ⇒ 失败卡在屏）。跑成功 / 人点了停止 ⇒ 恒为 null。 */
    var failure: ReplayFailure? by mutableStateOf(null)
        private set

    /** 这一趟的当前目标（失败卡上「目标」那一行的兜底来源）。 */
    private var currentTarget: String = ""

    private var skillName: String = ""

    /** 停止请求：执行器在后台线程轮询读它，所以是原子量而不是 Compose 状态。 */
    private val stopFlag = AtomicBoolean(false)

    /** 回放开始（判据①的起点：这里置真，UI 那条 LaunchedEffect 就切玄览）。 */
    fun begin(name: String, total: Int) {
        skillName = name
        stepTotal = total.coerceAtLeast(0)
        stepIndex = 0
        currentAction = ""
        currentTarget = ""
        failure = null
        stopFlag.set(false)
        playing = true
    }

    /** 进入第 [index] 步（执行器每步动手前调一次；判据②的刻度就写在这里）。 */
    fun noteStep(index: Int, total: Int, action: TeachAction, target: String) {
        if (total > 0) stepTotal = total
        stepIndex = index
        currentTarget = target
        currentAction = ReplayText.actionPhrase(action, target)
    }

    /**
     * 这一步失败了（执行器 stop() 时调）。
     *
     * 人点了停止就不成卡：那不是「技能坏了」，是他的意思（判据③的负例：停留才算红）。
     */
    fun markFailure(index: Int?, total: Int, target: String, hits: Int?, reason: String) {
        if (stopFlag.get()) return
        failure = ReplayFailure(
            skillName = skillName,
            step = index?.takeIf { it > 0 },
            total = if (total > 0) total else stepTotal,
            target = target.ifBlank { currentTarget },
            hits = hits,
            reason = reason,
        )
    }

    /** 回放收口（成功、失败、异常都走这里）：指示条消失，失败卡（若已立）留着等人点。 */
    fun end() {
        playing = false
    }

    /** 停止钮：立刻落下指示条＋让执行器在下一步前停（不再动页面）。 */
    fun requestStop() {
        stopFlag.set(true)
        failure = null
        playing = false
    }

    /** 执行器轮询的停止判据。 */
    fun stopRequested(): Boolean = stopFlag.get()

    /** 失败卡上「先不管」／「让抱朴改」／「重新教」三个出口都先收卡。 */
    fun dismissFailure() {
        failure = null
    }

    /**
     * 只给夹具用：模拟新进程 / 相邻用例互不污染（生产代码不许调用）。
     * 真机语义由「纯内存 + 不落盘」保证：进程被杀，回放本来也一起没了。
     */
    internal fun reset() {
        playing = false
        stepIndex = 0
        stepTotal = 0
        currentAction = ""
        currentTarget = ""
        skillName = ""
        failure = null
        stopFlag.set(false)
    }
}
