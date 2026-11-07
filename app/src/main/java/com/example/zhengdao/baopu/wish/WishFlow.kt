// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十六批 · 许愿接线：流水线的**装配与门面**（Context 只到这一处，与 SkillTools.spec
// 在 BaopuRuntime 同层）。职责只有三件：
//   ① 入口：`intercepts` 判定这句话要不要拦下来进许愿流——拦不拦先问 **506 的灰度登记表**
//      （CanaryCapability.WISH_PIPELINE；关闭档＝普通聊天，放行进引擎，本件不造第二把开关）；
//   ② 门面：submit/confirm 的结果翻成一句通知上屏（engine.showNotice），零新增 UI；
//   ③ 装配：WishIOWiring.ports（三类取数 IO）＋ WishWiring（技能三口）接成真 WishPorts。
package com.example.zhengdao.baopu.wish

import android.content.Context
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.agent.WishIOWiring
import com.example.zhengdao.baopu.consequence.CanaryBand
import com.example.zhengdao.baopu.consequence.CanaryCapability
import com.example.zhengdao.baopu.consequence.CanaryRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object WishFlow {

    @Volatile private var pipelineRef: WishPipeline? = null

    /** 最近一条未决许愿（确认回话省略 wishId 时接它；进程内存活，账上有底）。 */
    @Volatile private var lastPending: String? = null

    /** 506 登记表里许愿流的当前档（缺省灰度；OFF ⇒ 入口不拦）。 */
    internal fun band(): CanaryBand =
        CanaryRegistry.defaults()[CanaryCapability.WISH_PIPELINE] ?: CanaryBand.OFF

    /** 这句话要不要在壳层拦下来（send() 的唯一问询口）。 */
    fun intercepts(text: String): Boolean {
        if (WishTrigger.confirmRequest(text) != null) return true
        if (!WishTrigger.isWishRequest(text)) return false
        return band() != CanaryBand.OFF
    }

    /**
     * 许愿流的一次进场（IO 落 [Dispatchers.IO]；返回值是要上屏的那句，null＝不拦）。
     * 本件不摸 UI：上屏由调用方（BaopuChatController）贴。
     */
    suspend fun onUserSend(context: Context, text: String): String? = withContext(Dispatchers.IO) {
        val pipeline = pipeline(context)
        val confirm = WishTrigger.confirmRequest(text)
        if (confirm != null) {
            val wishId = confirm.wishId ?: lastPending
                ?: return@withContext "没有待确认的许愿；先许一个（例：我想要一个能把长文变成卡片的工具）"
            return@withContext noticeOf(pipeline.confirm(wishId, confirm.ids))
        }
        noticeOf(pipeline.submit(text.trim(), WishTrigger.productOf(text)))
    }

    /** 流水线单例（进程一份；判据在纯函数件里，这里只装配）。 */
    fun pipeline(context: Context): WishPipeline {
        pipelineRef?.let { return it }
        synchronized(this) {
            pipelineRef?.let { return it }
            val appCtx = context.applicationContext
            val workspace = BaopuRuntime.workspaceRoot(appCtx)
            val ports = WishIOWiring.ports(
                workspace = workspace,
                persona = BaopuRuntime.personaFiles(appCtx),
                cacheRoot = appCtx.cacheDir,
                readSkillAsset = { name -> readAsset(appCtx, name) },
                stamp = ::stamp,
            )
            return WishPipeline(workspace, ports).also { pipelineRef = it }
        }
    }

    /** 技能资产读件（CONTENT 取数的真源；坏路径交 null，试跑照实报读不到）。 */
    private fun readAsset(ctx: Context, name: String): String? = runCatching {
        ctx.assets.open("baopu/skills/$name/SKILL.md").readBytes().toString(Charsets.UTF_8)
    }.getOrNull()

    /** 备份/归档时间戳：与 SkillTools.stamp() 同一把（TimeFormats.fileStamp，B-补-24 门禁口径）。 */
    private fun stamp(): String = com.example.zhengdao.core.TimeFormats.fileStamp()

    // ── 结果 ⇒ 通知一句（零新增 UI；通知就是回话） ────────────────────────

    internal fun noticeOf(outcome: WishOutcome): String = when (outcome) {
        is WishOutcome.AwaitingConfirmation -> pendingNotice(outcome)
        is WishOutcome.InGray ->
            "许愿 ${outcome.wishId} 已进灰度（${outcome.uses}/${WishGrayRelease.PROMOTE_THRESHOLD}）；" +
                "草稿在 许愿/${outcome.wishId}/草稿/，真项目没动"
        is WishOutcome.Promoted ->
            "许愿 ${outcome.wishId} 灰度达标已转正（${outcome.product}）；机器账已记一行"
        is WishOutcome.RolledBack ->
            "许愿 ${outcome.wishId} 已自动回滚：${outcome.reason}"
        is WishOutcome.Rejected -> "许愿被拒：${outcome.reason}"
        is WishOutcome.Halted ->
            "许愿 ${outcome.wishId} 试跑连续失败 ${outcome.failures} 次，已停并落账；草稿未转正"
        // 第五百二十六批：两态的通知文案（入口判定与三类取数一行未动——这里只是 when 多两支）
        is WishOutcome.PromoteFailed ->
            "许愿 ${outcome.wishId} 转正没写成（${outcome.reason}）：草稿还在灰度，账上没记转正，下次使用再试"
        is WishOutcome.RollbackFailed ->
            "许愿 ${outcome.wishId} 回滚也没写成（${outcome.reason}）：半截态需人工处理，别当已回滚"
    }

    /** 待确认通知：把候选验收摆出来，回「许愿确认 <编号>」挑 1–3 条。 */
    private fun pendingNotice(outcome: WishOutcome.AwaitingConfirmation): String {
        lastPending = outcome.wishId
        val ids = outcome.spec.candidates.joinToString("；") { "${it.id} ${it.label}" }
        return "许愿已收（${outcome.wishId}）。目标：${outcome.spec.goal}。候选验收：$ids。" +
            "回「许愿确认 ${outcome.spec.candidates.take(2).joinToString(" ") { it.id }}」" +
            "（挑 1–3 条编号，可换）我就生成草稿并在沙盒试跑"
    }
}
