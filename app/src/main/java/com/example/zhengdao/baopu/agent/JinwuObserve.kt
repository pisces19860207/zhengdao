// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百二十八批 · 金吾卫喂入口通电——313 已交巡查逻辑（JinwuPatrol）但生产零调用，
// 本文件是接线层，收口 313 交付报告自列的三处留白：
//   ① 喂入口：[GuardPipeline] 每次工具定论后各调一行 [observe]；
//      Result → 结果码的映射是纯函数 [resultCodeOf]（五种结果各一测），
//      schema 判别对 result 自带的 call 重跑 `ToolRegistry.validateCall`
//      ——与管线第①段同一个口径，不是第二口径；
//   ② 装配：[attach]（ZhengdaoApp 调，必须在 AppForeground.install 之后）。
//      装配失败 ⇒ [isAvailable]=false ＋ wiring 账记 unavailable——
//      「没巡查」和「巡查没问题」必须分得清，观测层没装配不许静默装作绿；
//   ③ attempts 跨进程续跑：金吾卫的步账与右相的差距板都是进程态，跨进程的计数
//      落在本文件自己的磁盘账（gap-attempts.json，每次喂板后整表快照）。
//      重启后第一次触发把**历史总数**喂板（差值 = 磁盘计数 − 板上在册 attempts），
//      「第 3 次交人」才到得了——归零的话交人线永远差一口。
//
// 落点偏差（派工单写 inspect/JinwuObserve.kt，落在 agent/）：勘验层只读门禁
// （InspectReadOnlyGateTest）禁 inspect/** import agent 除白名单四名——映射要
// GuardPipeline.Result、判 schema 要 ToolRegistry，进 inspect 就是撞墙；且该门禁
// 禁非台账 inspect 文件有任何写口，attempts 落盘进不去。agent 包免 import 同包类型，
// agent→inspect/verify 的借名在 OpinionWiring／LearnWiring／ShadowSandbox 都有先例；
// 写口在 L6 写路径白名单登记（(文件,API) 粒度，理由在白名单行上）。
//
// 勘验来源（GapSource 第三家）本批只留位不实现：本文件不产那条来源的条目
// （派工单门禁：若产出真实条目 ⇒ 红）。
package com.example.zhengdao.baopu.agent

import android.content.Context
import com.example.zhengdao.baopu.inspect.JinwuPatrol
import com.example.zhengdao.baopu.inspect.StepResultCode
import com.example.zhengdao.baopu.inspect.ToolStep
import com.example.zhengdao.baopu.verify.GapFeedback
import com.example.zhengdao.baopu.verify.GapItem
import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONObject

internal object JinwuObserve {

    /** 应用私有目录名（`filesDir/jinwu-patrol/`：attempts 磁盘账 ＋ wiring 装配账）。 */
    private const val DIR_NAME = "jinwu-patrol"

    /** attempts 磁盘账：gapKey → 历史总触发次数（每次喂板后整表快照，判据③续跑）。 */
    private const val ATTEMPTS_FILE = "gap-attempts.json"

    /** wiring 装配账：一行一条 available/unavailable——体检单取数点（291 对接留白）。 */
    private const val WIRING_FILE = "wiring.jsonl"

    // ── 装配态（②） ─────────────────────────────────────────────────────────

    @Volatile private var root: File? = null
    @Volatile private var ok: Boolean = false
    @Volatile private var reason: String? = null

    /** 磁盘账的内存影（attach 时读入，之后内存为准、每次喂板写盘快照）。 */
    private val diskCounts = LinkedHashMap<String, Int>()

    /** 装配成功与否（判据③：false ⇒ 体检侧记 unavailable，不许与「巡查没问题」混同）。 */
    val isAvailable: Boolean get() = ok

    /** 装配失败的原因（available 时 null）。 */
    val unavailableReasonOrNull: String? get() = reason

    /** 装配（生产入口：ZhengdaoApp；AppForeground.install 之后）。幂等，重装先 resetForTest。 */
    fun attach(context: Context) = attachAt(File(context.filesDir, DIR_NAME))

    /**
     * 测试缝：路径为 null／建不出目录／磁盘账读不了 ⇒ 装配失败（unavailable），
     * 失败事实进 wiring 账（账也写不了 ⇒ 降级 RunLog，不静默）。
     */
    @Synchronized
    fun attachAt(target: File?) {
        if (root != null) return
        root = target
        if (target == null) {
            markUnavailable("装配路径为空")
            return
        }
        val made = runCatching { target.isDirectory || target.mkdirs() }.getOrDefault(false)
        if (!made) {
            markUnavailable("目录建不了：${target.path}")
            return
        }
        val loaded = runCatching { readAttempts(File(target, ATTEMPTS_FILE)) }
        if (loaded.isFailure) {
            markUnavailable("attempts 账读不了：${loaded.exceptionOrNull()?.javaClass?.simpleName}")
            return
        }
        diskCounts.putAll(loaded.getOrDefault(emptyMap()))
        ok = true
        wire("available", null)
    }

    private fun markUnavailable(why: String) {
        ok = false
        this.reason = why
        wire("unavailable", why)
    }

    /** wiring 账一行；写不了降级 RunLog——装配事实（含失败）不许只有内存知道。 */
    private fun wire(state: String, why: String?) {
        val line = JSONObject()
            .put("at", System.currentTimeMillis())
            .put("state", state)
            .put("reason", why ?: "")
            .toString()
        val file = root?.let { File(it, WIRING_FILE) }
        val written = file != null && runCatching { file.appendText(line + "\n") }.isSuccess
        if (!written) RunLog.log("金吾卫装配账写不了（$state：${why ?: "-"}）——wiring.jsonl 缺行")
    }

    // ── 喂入口（①：GuardPipeline 收口处各调一行） ────────────────────────────

    /**
     * 每次工具定论后喂一步：映射成结果码 → 金吾卫触发采样 → 差距喂板（差值续跑）。
     * 未装配 ⇒ no-op（观测层不挡业务；「没巡查」由 wiring 账的缺席承载）。
     */
    @Synchronized
    fun observe(result: GuardPipeline.Result, call: ToolCall, registry: ToolRegistry) {
        if (!ok) return
        val effective = resultCallOf(result) ?: call
        // schema 判别对 result 自带的 call 重跑第①段同一把尺子（非 null ⇒ 与 schema 不符）。
        // 执行失败带的 call 已过校验，重跑必 null ⇒ 如实归 FAILED——不虚报 schema 违规。
        val schemaInvalid = result is GuardPipeline.Result.Failed &&
            registry.validateCall(effective.name, effective.arguments) != null
        val spec = registry.find(effective.name)
        val step = ToolStep(
            atMs = System.currentTimeMillis(),
            callId = effective.id,
            tool = effective.name,
            argsDigest = JinwuPatrol.argsDigestOf(effective.arguments),
            resultCode = resultCodeOf(result, schemaInvalid),
            // planStep／taskReadOnly 的真值在引擎与任务层（不在本批租约）——留白：
            // 布尔缺省时 T3/T4 不会虚报（没有事实就不产条目），接线归后续批次。
            writeTool = spec?.let { !it.readOnly } ?: false,
        )
        val gaps = JinwuPatrol.observe(step)
        if (gaps.isNotEmpty()) feedBoard(gaps)
    }

    /** Result 自带的 call（Failed/Rejected 形状上可空——收口处用入参 call 兜底）。 */
    private fun resultCallOf(result: GuardPipeline.Result): ToolCall? = when (result) {
        is GuardPipeline.Result.Done -> result.call
        is GuardPipeline.Result.Failed -> result.call
        is GuardPipeline.Result.Rejected -> result.call
        is GuardPipeline.Result.NeedsConfirm -> result.call
    }

    /**
     * Result → 结果码**纯映射**（判据①：五种结果各一测）。
     * 超时与普通失败在管线里同走 `Result.Failed`（结果码闭集没有超时值）——如实归
     * FAILED，不虚设第四种失败；是否 schema 违规由喂入口现算传入，映射自己不摸 registry。
     */
    internal fun resultCodeOf(result: GuardPipeline.Result, schemaInvalid: Boolean): StepResultCode =
        when (result) {
            is GuardPipeline.Result.Done -> StepResultCode.DONE
            is GuardPipeline.Result.NeedsConfirm -> StepResultCode.NEEDS_CONFIRM
            is GuardPipeline.Result.Rejected -> StepResultCode.REJECTED
            is GuardPipeline.Result.Failed ->
                if (schemaInvalid) StepResultCode.SCHEMA_INVALID else StepResultCode.FAILED
        }

    // ── attempts 跨进程续跑（③） ────────────────────────────────────────────

    /**
     * 差值喂板：磁盘账先自增＋整表快照落盘，再按「磁盘计数 − 板上在册」喂——
     * 板活着时差值为 1（merge 自会累加）；板空（重启后）喂历史总数，attempts 接续不断档。
     */
    private fun feedBoard(gaps: List<GapItem>) {
        val boardAttempts = GapFeedback.pending().associateBy({ it.gapKey }, { it.attempts })
        val toFeed = gaps.map { gap ->
            val disk = (diskCounts[gap.gapKey] ?: 0) + 1
            diskCounts[gap.gapKey] = disk
            gap.copy(attempts = (disk - (boardAttempts[gap.gapKey] ?: 0)).coerceAtLeast(1))
        }
        val snapshot = root?.let { File(it, ATTEMPTS_FILE) }
        val json = JSONObject()
        diskCounts.forEach { (key, count) -> json.put(key, count) }
        val written = snapshot != null && runCatching { snapshot.writeText(json.toString()) }.isSuccess
        if (!written) RunLog.log("金吾卫 attempts 账写不了——跨进程续跑可能断档")
        GapFeedback.record(toFeed)
    }

    private fun readAttempts(file: File): Map<String, Int> {
        if (!file.exists()) return emptyMap()
        val json = JSONObject(file.readText())
        val out = LinkedHashMap<String, Int>()
        json.keys().forEach { key -> out[key] = json.getInt(key) }
        return out
    }

    /** 直测收尾（生产不调）：只清内存态、**不删磁盘账**——模拟进程重启靠的就是这一点。 */
    @Synchronized
    fun resetForTest() {
        root = null
        ok = false
        reason = null
        diskCounts.clear()
    }
}
