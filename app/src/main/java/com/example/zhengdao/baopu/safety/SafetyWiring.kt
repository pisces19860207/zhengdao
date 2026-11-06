// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零四单 · 安全三件的**生产装配**：把 HaltGate（471）、SupervisionDiary（469）、
// KeyActionNotifier/RollbackVault（469②/470）缝到 GuardPipeline 的监督缝上，
// 并给调度/值守/事件入口提供防自启闸（471③）。
//
// L3 放宽口径（2026-11-06 用户）在这里落成行为：
// · 叫停/回滚/通知都**自动触发**——硬停收口的对飞回滚、连败报信、批量/不可逆报信
//   都不等任何人批准；
// · 通知**只是让你知道**（文案不写"请处理"）；
// · 唯一人工点＝解除停机（[liftByHuman] 只由安全台页调用；模型路径被 HaltGate.lift 拒）。
package com.example.zhengdao.baopu.safety

import android.content.Context
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.agent.ActiveSessionGate
import com.example.zhengdao.baopu.agent.DeviationWiring
import com.example.zhengdao.core.RunLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 通知（弹的条件、样式）归 [SafetyNotifier]；本文件只管"什么时候该发"。 */
object SafetyWiring {

    /** 在飞的写动作（opId → 开始时刻）：硬停收口时按单回滚的账。 */
    private val inFlight = ConcurrentHashMap<String, Long>()

    /** 连败计数（跨工具连着数；成功即清零——"连续失败"的字面）。 */
    private val failStreak = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var appCtx: Context? = null

    /** 装配口（ZhengdaoApp onCreate 调一次）。 */
    fun install(ctx: Context) {
        appCtx = ctx.applicationContext
        RollbackVault.install(File(ctx.filesDir, ""))
        RunLog.log("安全台：装配完成（停机闸/起居注/回滚保险箱就位）")
    }

    private fun ctx(): Context? = appCtx

    private fun diary(
        kind: DiaryKind,
        why: String,
        what: String,
        result: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val c = ctx() ?: return
        SupervisionDiary.append(c, DiaryEvent(kind, nowMs, why, what, result))
    }

    /** 现在在做什么（停止留证的"当时"；单活跃门＋本轮 runId 拼一行）。 */
    private fun nowWhere(): String {
        val session = ActiveSessionGate.current() ?: "无活跃会话"
        val run = DeviationWiring.currentRunId().ifBlank { "无轮次" }
        val flying = inFlight.size
        return "会话 $session｜轮次 $run" + if (flying > 0) "｜在飞动作 $flying 件" else ""
    }

    /** 监督的"依据"行：值守中就说值守，否则说运行＋本轮。 */
    private fun basisLine(): String {
        val watch = runCatching { com.example.zhengdao.unattended.UnattendedStatus.armed() }.getOrDefault(false)
        val run = DeviationWiring.currentRunId().ifBlank { "无轮次" }
        return (if (watch) "值守" else "运行") + "·$run"
    }

    private fun sessionId(): String = ActiveSessionGate.current().orEmpty()

    /**
     * 任务/轮次粒度标签（470② 的"任务"）：有任务契约 id 就用它（任务可跨多轮），
     * 没有就落到本轮 runId——粒度永远有名有姓。
     */
    private fun runId(): String =
        DeviationWiring.currentTaskId().ifBlank { DeviationWiring.currentRunId() }

    // ── 管线监督缝（生产实现） ───────────────────────────────────────────

    /** GuardPipeline 用的钩子（BaopuRuntime 注入）。 */
    val hooks: SafetyHooks = object : SafetyHooks {

        override fun intercept(toolName: String): String? {
            // 判据⑦的模型路径：任何"解除停机"形状的工具调用一律原话拒绝——哪怕没在停机。
            if (toolName.contains("lift_halt") || toolName.contains("解除停机")) {
                return HaltGate.LIFT_REFUSED_LINE
            }
            if (!HaltGate.halted()) return null
            val rec = HaltGate.record()
            return "已停机（${rec?.level?.cn ?: "停机"}，${rec?.by ?: "未知"} 停的；停机留证：${rec?.where ?: "未记"}）。" +
                "人工解除前不执行任何新动作：$toolName 未执行"
        }

        override fun bigFileNote(tool: String, paths: List<String>): String? {
            val big = paths.count { p ->
                val f = File(p)
                f.isFile && f.length() > RollbackRules.BIG_FILE_BYTES
            }
            if (big == 0) return null
            val route = if (tool == "delete_path") "delete_path 的原件已在回收站、可取回" else "原件不整份备份、回滚不能字节还原"
            return "（安全回滚说明：$big 件原件 >10MB——$route）"
        }

        override fun beforeRun(tool: String, opId: String, paths: List<String>) {
            val now = System.currentTimeMillis()
            if (paths.isNotEmpty()) inFlight[opId] = now
            // 470①：写类动作前把原件入库（此刻的字节就是"最后一次已知好状态"）。
            RollbackVault.stash(opId, tool, paths, sessionId(), runId(), now)
        }

        override fun afterRun(
            tool: String,
            readOnly: Boolean,
            riskTier: String,
            opId: String,
            paths: List<String>,
            ok: Boolean,
            toolRejected: Boolean,
            brief: String,
        ) {
            val startedAt = inFlight.remove(opId)
            val what = if (paths.isEmpty()) tool else "$tool → ${paths.size} 个目标（${paths.firstOrNull()?.substringAfterLast('/')}）"
            val result = when {
                ok -> "成功${brief.take(60)}"
                toolRejected -> "工具自拒${brief.take(60)}"
                else -> "失败${brief.take(60)}"
            }
            // 判据①：每次自动动作一行（唯一写口在 GuardPipeline.finish）。
            diary(DiaryKind.ACTION, why = basisLine(), what = what, result = result)
            if (readOnly) return
            // 469②：关键动作通知（自动触发；通知只是让你知道）。
            if (ok) {
                KeyActionRules.kindOf(tool, riskTier, paths.size)?.let { kind ->
                    SafetyNotifier.keyAction(ctx(), kind, what)
                    diary(DiaryKind.NOTIFY, why = "469② 关键动作", what = what, result = "已通知（${kind.cn}）")
                }
                failStreak.set(0)
            } else {
                val prev = failStreak.getAndIncrement()
                if (KeyActionRules.streakShouldNotify(prev)) {
                    SafetyNotifier.keyAction(ctx(), KeyActionKind.FAIL_STREAK, "已连败 ${prev + 1} 次：$what")
                    diary(DiaryKind.NOTIFY, why = "469② 连败报信", what = what, result = "已连败 ${prev + 1} 次，已通知")
                }
            }
            // 471①＋470：硬停期间收尾的在飞写动作——自动回滚（先有兜底，L3 不要人批）。
            if (HaltGate.hardHalted() && startedAt != null) {
                hardRollbackInFlight(opId, what)
            }
        }
    }

    /** 硬停在飞的收口回滚（按单笔回滚＋自校验落账）。 */
    private fun hardRollbackInFlight(opId: String, what: String) {
        val outcome = RollbackVault.rollback(RollbackScope.ByOp(opId), System.currentTimeMillis())
        diary(
            DiaryKind.ROLLBACK,
            why = "471 硬停中止当前动作，470 兜底",
            what = what,
            result = outcome.summaryLine,
        )
        outcome.lines.take(6).forEach {
            diary(DiaryKind.VERIFY, why = "470③ 回滚自校验", what = it, result = "已落账")
        }
    }

    // ── 叫停（469③ / 471①；App 内与通知栏都从这里走） ─────────────────────

    /** 软停＝叫停：不再发起新动作，当前步跑完即停。 */
    fun softStop(by: String) {
        val where = nowWhere()
        val now = System.currentTimeMillis()
        HaltGate.softStop(by, where, now)
        // 469④ 停止留证：谁停的/何时/当时在做什么——一行全带上。
        diary(DiaryKind.STOP, why = "谁停的：$by", what = "当时：$where", result = "软停（当前步跑完即停），人工解除前不再发起新动作", nowMs = now)
        SafetyNotifier.halted(ctx(), HaltLevel.SOFT, by)
    }

    /** 硬停：软停的全部＋中止当前动作（引擎砍流；在飞写动作收口时自动回滚）。 */
    fun hardStop(by: String) {
        val where = nowWhere()
        val now = System.currentTimeMillis()
        HaltGate.hardStop(by, where, now)
        diary(DiaryKind.STOP, why = "谁停的：$by", what = "当时：$where", result = "硬停（中止当前动作），人工解除前调度/值守/事件一律不启动", nowMs = now)
        runCatching { BaopuRuntime.engineIfBuilt()?.stop() }
            .onFailure { RunLog.log("安全台：硬停砍流没成 ${it.javaClass.simpleName}: ${it.message}") }
        val flying = inFlight.size
        if (flying > 0) {
            diary(DiaryKind.STOP, why = "471 硬停", what = "在飞动作 $flying 件", result = "已进待回滚：收口时自动按 470 回滚并自校验", nowMs = now)
        }
        SafetyNotifier.halted(ctx(), HaltLevel.HARD, by)
    }

    /** 人工解除（唯一保留的人工点；只由安全台页调用）。 */
    fun liftByHuman(): Boolean {
        val now = System.currentTimeMillis()
        val ok = HaltGate.lift(byHuman = true, nowMs = now)
        if (ok) {
            diary(DiaryKind.LIFT, why = "谁解除的：人工（安全台）", what = "停机解除", result = "恢复正常运行")
        }
        return ok
    }

    // ── 防自启闸（471③：停机到人工解除前，调度/值守/事件一律不启动） ────────

    /**
     * 自动触发入口先问这一句：true＝已拦下（本趟不跑）。
     * 拦下的每一笔都落一行 BLOCK（不静默——静默的拦截没法对账）。
     */
    fun blockAutoStartIfHalted(source: String): Boolean {
        if (!HaltGate.halted()) return false
        val rec = HaltGate.record()
        RunLog.log("安全台：停机期拦下自动触发（$source；${rec?.level?.cn ?: "停机"}）")
        diary(
            DiaryKind.BLOCK,
            why = "471③ 防自启（${rec?.level?.cn ?: "停机"}中）",
            what = "自动触发：$source",
            result = "已拦下，不启动；人工解除后恢复",
        )
        return true
    }
}
