// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第三百一十四批 · 漂移闸的**接线层**：把 300 交来的判据（DriftCheck／DriftStore）
// 接进回放路径。**判据一个字不改**——本文件只做三件事：
//
//   ① 只读探针（[DriftPageReader]／[BrowserDriftReader]）：对契约里登记的选择器逐个
//      `querySelector`，产出 [SelectorPresence]（存在性＋可见性）。
//      **只取存在性**：不抓正文、不截图、不读任何元素文本——命中结果是**按位置回抄
//      契约里的原串**（[DriftProbe.hitSelectors]），结构上就装不进页面正文；
//   ② 四环调度（[gate]）：回放前 `preflight` ⇒ 三态处置（照常／问人／停），
//      问人之后的两个答案分别走 `recordVerdict`（照跑先记账）与 `recalibrate`（这是新版）。
//      顺序照抄 300 交付报告 §2.3，一处没动；
//   ③ 记账：照跑前先 `recordVerdict`（一致归零／漂移 +1／连 3 次停用并进未办成单）；
//      校准的账由 `SkillStore.apply` 写（300 定稿：一次改动一行，不补同义行）。
//
// 三条纪律（与 255/300 同源）：
// - 本层不做任何判断：闸怎么判是 [DriftCheck.preflight] 的事，账怎么记是
//   [DriftStore.recordVerdict] 的事——本文件只把「现场读数」按口径喂进去、把结论搬回来；
// - 探针失败不冒充读数：execJs 不通 ⇒ 空命中 ⇒ preflight 判 NoData（fail-closed），
//   不会把「判不了」说成「对得上」；
// - 没有页面（tabId 空）不设闸：回放执行器自己会停在「取不到当前页面」，
//   这里不替它空转一遍（也没有页面可问）。
package com.example.zhengdao.baopu

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 一个选择器在页面上的**存在性读数**（不携带任何页面内容——[selector] 永远是契约里的原串）。 */
data class SelectorPresence(
    /** 契约里登记的原串（按位置回抄，不是浏览器回显）。 */
    val selector: String,
    /** 页面上找得到这个元素（`document.querySelector` 非 null）。 */
    val present: Boolean,
    /** 找得到且有渲染盒（`getClientRects` 非空）；找不到时恒 false。 */
    val visible: Boolean,
)

/** 只读探针口：JVM 单测用假实现，生产用 [BrowserDriftReader]（经既有 execJs 桥）。 */
interface DriftPageReader {
    /** 当前页地址（取不到 ⇒ null，preflight 判 NoData）。 */
    fun currentUrl(): String?

    /**
     * 对 [selectors] 逐个查存在性；顺序与入参一一对应。
     * execJs 不通 ⇒ null（调用方按「空命中」处理，不冒充读数）。
     */
    fun selectorPresence(selectors: List<String>): List<SelectorPresence>?
}

internal object DriftProbe {

    /**
     * 存在性读数 ⇒ 现场命中集：**只回抄契约原串**（300 硬口径 1/2——
     * 不是「要查的清单」，也不做 CSS 归一化）。
     */
    fun hitSelectors(presence: List<SelectorPresence>): List<String> =
        presence.filter { it.present }.map { it.selector }.filter { it.isNotBlank() }

    /** 读数 ⇒ 现场观察（喂 [DriftCheck.preflight] 的那一口；读数缺位按空命中处理）。 */
    fun observe(url: String?, presence: List<SelectorPresence>?): DriftObservation.Web =
        DriftObservation.Web(url = url, selectors = hitSelectors(presence.orEmpty()))
}

/** 回放工具带进来的「人对漂移问题的回答」（wire 值见 [DriftChoice.ofWire]）。 */
enum class DriftChoice(val wire: String) {
    /** 先问人（默认）：闸降级为需确认，把三选一的问题交出去。 */
    ASK("ask"),

    /** 照跑：先记一笔再跑（不记就永远数不到 3）。 */
    PROCEED("proceed"),

    /** 这是新版：重新校准（写回契约）后跑。 */
    NEW_VERSION("new_version"),

    /** 算了：不跑也不记（没执行就不算一次现实漂移）。 */
    CANCEL("cancel"),
    ;

    companion object {
        /** 不认得的值 ⇒ null（调用方 fail-closed 退回 ASK，再问一遍）。 */
        fun ofWire(wire: String?): DriftChoice? = entries.firstOrNull { it.wire == wire }
    }
}

/** 闸的结局：回放路径只认「继续跑」与「停下（原因给人看）」两种。 */
sealed interface DriftGateOutcome {

    /** 可以跑（照常／已记账照跑／已校准照跑）。 */
    data object Proceed : DriftGateOutcome

    /** 降级为需确认：把问题文本交回调用方（agent 转述给人，三选一）。 */
    data class Ask(val reason: String) : DriftGateOutcome

    /** 停：停用／已停用／校准被拒／用户说不跑。 */
    data class Stop(val reason: String) : DriftGateOutcome
}

internal object DriftWire {

    /** 校准的确认人（本批唯一确认通道＝人在对话里答「这是新版」，由 agent 转述）。 */
    const val CONFIRMED_BY = "用户（对话里确认：这是新版）"

    /** 一次闸判定的装配（分支共享的上下文；免得每个分支都拖九个参数）。 */
    private class GateCtx(
        val pre: DriftPreflight,
        val live: DriftObservation.Web,
        val projectRoot: File,
        val name: String,
        val unfinishedDirs: List<File>,
        val globalRoot: File,
        val personaFiles: PersonaFiles,
        val stampText: String,
        val nowMs: Long,
    )

    /**
     * 回放前的漂移闸（四环全在这一个口上；**判据零改动**）。
     *
     * 口径：
     * - 技能没有 web 指纹 ⇒ 不设闸（探针只管网页；文件/命令指纹另有取数路，本批不接）；
     * - 没有页面可探（reader 为空）⇒ 不设闸，回放执行器会自己停下；
     * - 其余照 300 交付报告 §2.3 逐行执行；分支处置见 [onRun]／[onConfirm]／[onDisabled]。
     */
    fun gate(
        reader: DriftPageReader?,
        projectRoot: File,
        name: String,
        choice: DriftChoice,
        unfinishedDirs: List<File>,
        globalRoot: File,
        personaFiles: PersonaFiles,
        stampText: String,
        nowMs: Long,
    ): DriftGateOutcome {
        val md = DriftStore.skillMdFile(projectRoot, name).takeIf { it.isFile }
            ?.let { f -> runCatching { f.readText(Charsets.UTF_8) }.getOrNull() }
            ?: return DriftGateOutcome.Proceed
        val fps = (SkillContract.parse(md) as? SkillContract.Parsed.Contracted)
            ?.contract?.fingerprints.orEmpty()
        val webFp = fps.firstOrNull { it is DriftFingerprint.Web } as? DriftFingerprint.Web
            ?: return DriftGateOutcome.Proceed
        val r = reader ?: return DriftGateOutcome.Proceed
        val live = DriftProbe.observe(r.currentUrl(), r.selectorPresence(webFp.selectors))
        val pre = DriftCheck.preflight(fps, live, DriftStore.load(projectRoot, name))
        val ctx = GateCtx(pre, live, projectRoot, name, unfinishedDirs, globalRoot, personaFiles, stampText, nowMs)
        return when (pre.gate) {
            DriftGate.RUN -> onRun(ctx)
            DriftGate.CONFIRM -> onConfirm(ctx, choice)
            DriftGate.DISABLED -> onDisabled(ctx, choice)
        }
    }

    /** RUN＝照常；环④：一致的结论也落账（归零）。停用章是 record 算出来的极端情形，照样停。 */
    private fun onRun(ctx: GateCtx): DriftGateOutcome {
        ctx.pre.verdict
            ?.let { record(ctx, it) }
            ?.let { upd -> if (upd.disabledNow) return DriftGateOutcome.Stop(disabledLine(upd)) }
        return DriftGateOutcome.Proceed
    }

    /** CONFIRM＝问人三选一（ASK 把问题交回去；PROCEED 先记账再跑；NEW_VERSION 校准；CANCEL 不跑不记）。 */
    private fun onConfirm(ctx: GateCtx, choice: DriftChoice): DriftGateOutcome = when (choice) {
        DriftChoice.ASK -> DriftGateOutcome.Ask(askLine(ctx.pre))
        DriftChoice.PROCEED -> {
            val upd = ctx.pre.verdict?.let { record(ctx, it) }
            if (upd?.disabledNow == true) DriftGateOutcome.Stop(disabledLine(upd)) else DriftGateOutcome.Proceed
        }
        DriftChoice.NEW_VERSION -> recalibrate(ctx)
        DriftChoice.CANCEL -> DriftGateOutcome.Stop(CANCEL_LINE)
    }

    /**
     * DISABLED＝停用是硬状态（照跑/问人都不许绕），唯一的解钥是「人确认这是新版」⇒ recalibrate——
     * 这是 DriftCheck 自己的设计（unfinishedItem 的 fixHint 就写这句话），接线照办。
     */
    private fun onDisabled(ctx: GateCtx, choice: DriftChoice): DriftGateOutcome = when (choice) {
        DriftChoice.NEW_VERSION -> recalibrate(ctx)
        else -> DriftGateOutcome.Stop(
            ctx.pre.reason + "（确认这是新版：回放时用 drift=new_version）",
        )
    }

    /** 「这是新版」的处置：校准成了 ⇒ 照常跑；被拒（人没确认/读数不全）⇒ 把原因交回去。 */
    private fun recalibrate(ctx: GateCtx): DriftGateOutcome = when (
        val done = DriftStore.recalibrate(
            projectRoot = ctx.projectRoot,
            globalRoot = ctx.globalRoot,
            personaFiles = ctx.personaFiles,
            name = ctx.name,
            live = ctx.live,
            confirmedBy = CONFIRMED_BY,
            stampText = ctx.stampText,
            nowMs = ctx.nowMs,
        )
    ) {
        is RecalibrateOutcome.Done -> DriftGateOutcome.Proceed
        is RecalibrateOutcome.Refused -> DriftGateOutcome.Stop(done.reason)
    }

    private fun record(ctx: GateCtx, verdict: DriftVerdict): DriftUpdate =
        DriftStore.recordVerdict(ctx.projectRoot, ctx.name, verdict, ctx.nowMs, ctx.unfinishedDirs)

    /** 问人的那一段（三选一，答案从回放工具的 drift 参数回来）。 */
    fun askLine(pre: DriftPreflight): String = buildString {
        append(pre.reason)
        append("——请选一个：")
        append("①「照跑」＝这次先记一笔再跑（连着对不上 3 次会自动停用）；")
        append("②「这是新版」＝把现在的页面指纹写回契约，立刻恢复照常；")
        append("③「算了」＝这次不跑也不记。")
        append("（回放工具的 drift 参数：proceed／new_version／cancel）")
    }

    private fun disabledLine(upd: DriftUpdate): String =
        (listOf("回放停：") + upd.notes).joinToString("\n")

    val CANCEL_LINE = "已停下：这次没跑，也没记账（技能状态一个字没动）。"
}

/**
 * 生产探针：经既有 execJs 桥查玄览当前页（**不新开桥**，与 BrowserTeachDom 同一条包装解析）。
 * JS 只做 `querySelector`＋`getClientRects`（存在性＋可见性），一个字节都不取页面文本。
 */
internal class BrowserDriftReader(private val tabId: Int) : DriftPageReader {

    private fun exec(code: String): JSONObject? {
        val raw = com.example.zhengdao.bridge.BrowserCommands.execJs(code, tabId.toString())
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (!o.optBoolean("ok")) return null
        val result = o.optString("result").takeIf { it.isNotBlank() } ?: return null
        return runCatching { JSONObject(result) }.getOrNull()
    }

    override fun currentUrl(): String? =
        exec(TeachScripts.CURRENT_URL)?.optString("u")?.takeIf { it.isNotBlank() }

    override fun selectorPresence(selectors: List<String>): List<SelectorPresence>? {
        if (selectors.isEmpty()) return emptyList()
        // JSONArray.toString 产出合法的 JS 数组字面量（选择器里的引号/反斜杠由 JSON 转义兜住）。
        val jsSelectors = JSONArray(selectors).toString()
        val code = "(function(){var s=$jsSelectors;var out=[];" +
            "for(var i=0;i<s.length;i++){var el=null;try{el=document.querySelector(s[i])}catch(e){}" +
            "var v=false;if(el){try{v=el.getClientRects().length>0}catch(e){}}" +
            "out.push([!!el,v])}" +
            "return JSON.stringify({r:out})})()"
        val o = exec(code) ?: return null
        val rows = o.optJSONArray("r") ?: return null
        return (0 until selectors.size).map { i ->
            val pair = rows.optJSONArray(i)
            SelectorPresence(
                selector = selectors[i],
                present = pair?.optBoolean(0) == true,
                visible = pair?.optBoolean(1) == true,
            )
        }
    }
}
