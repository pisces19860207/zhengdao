// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第十七批 §1.1/§1.2（A 段报计划 ＋ B 段落库）＋ §3（J1–J4 全压本内核）。
// 房型照 CompressionRunner：**内核零 Android**（store 是接口），引擎壳只接线
// （sessionId ＋ 轮次事件）；JVM 单测注假 store 就能把判据全部跑绿。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.PlanRules.PlanItem
import com.example.zhengdao.baopu.PlanStore
import org.json.JSONObject

/**
 * update_plan 的内核（第十七批）：**校验归一化 ⇒ sticky done ⇒ 落库 ⇒ 回灌**。
 *
 * 三条判据的落地处（派工单 §3）：
 * - J1：参数校验（空 items 拒绝；超 8 条 / 超 60 字截断并 notice）——在
 *   [PlanRules.parseItems]（纯函数，本类只转发）；
 * - J2：全量覆盖——[PlanRules.applyOverwrite] 的 incoming 就是整张表；
 * - J3：勾选只增不减——同上（sticky done）；
 * - J4：落库可回读——[PlanStore.insert] / [PlanStore.latest]（实现侧 SQL 见
 *   `BaopuSql.planPayloadsBySession`）。
 *
 * 线程：submit 跑在 `zd-baopu-tool`（GuardPipeline 的 ToolRunner 里）；
 * newRound/seedFromStore 跑在 `zd-baopu-run`（引擎循环）。两者不并发（工具执行
 * 是引擎循环顺序调的，单活跃门全局只有一个会话）⇒ current 一个 [Volatile] 够用。
 *
 * @param sessionId 当前轮会话（引擎喂；落库要知道落到哪）
 * @param store 计划存取（接口；Android 侧实现 SessionPlanStore，单测用假件）
 */
internal class PlanController(
    private val sessionId: () -> Long,
    private val store: PlanStore,
    private val log: (String) -> Unit = { com.example.zhengdao.rootfs.RunLog.log(it) },
) : PlanSink {

    /** 当前轮的计划（内存态；库里的真相是 kind='plan' 的 part）。 */
    @Volatile
    private var current: List<PlanItem> = emptyList()

    /**
     * 一次 update_plan（工具执行器入口）。
     *
     * Failed（空 items / 坏条目）⇒ **不落库、不动内存态**：错误原文回灌给模型，
     * 它下一版自己改（与其它工具的「可重试×1」同口径）。
     */
    override fun submit(args: JSONObject): ToolOutcome {
        val parsed = PlanRules.parseItems(args)
        if (parsed is PlanRules.Parse.Error) {
            log("抱朴计划：拒绝（${parsed.message}）")
            return ToolOutcome.Failed(parsed.message)
        }
        val ok = parsed as PlanRules.Parse.Ok
        val applied = PlanRules.applyOverwrite(current, ok.items)
        current = applied.items
        val payload = PlanRules.toJson(applied.items)
        val sid = sessionId()
        store.insert(sid, payload)
        log(
            "抱朴计划：已更新（session=$sid，共 ${applied.items.size} 条、" +
                "${PlanRules.doneCount(applied.items)} 完成" +
                if (ok.notices.isEmpty() && applied.stickyNotes.isEmpty()) {
                    "）"
                } else {
                    "；notice ${ok.notices.size + applied.stickyNotes.size} 条）"
                },
        )
        return ToolOutcome.Ok(PlanRules.renderAck(applied.items, ok.notices + applied.stickyNotes))
    }

    /**
     * 新一轮开始（用户发了新消息）：清内存计划。
     *
     * 为什么不从库继承上一轮的计划：轮是「一个用户请求」的单位，新请求的计划
     * 是新任务；继承会让上一轮打过的勾把这一轮的同名条目提前钉成 done。
     */
    /**
     * 还剩几条没勾（DONE 之外都算）。
     *
     * 用途：跑完一轮时给用户一句实话——「计划里还有 N 条没勾」。
     * 2026-10-19 用户真机：「完成了没有把计划里的全部勾完，就像是没有按计划走」——
     * 不许把没做完的装成做完了，也不许静默。
     */
    fun unfinishedCount(): Int = current.count { it.status != PlanRules.STATUS_DONE }

    /**
     * 回灌给模型的「计划提醒」（2026-10-19 用户报「做完一步不打勾」；真机库读数：
     * 6 条 plan 快照里 `done` 出现 0 次——**模型从没标**，不是 UI 不刷新）。
     *
     * 只加在**普通工具结果**后面（`update_plan` 自己的 ack 已经有同类话术，别叠）；
     * 内容刻意短：剩余条数＋前两条＋一句「不标等于没做」。
     */
    fun reminderLine(): String? {
        if (current.isEmpty()) return null
        val todo = current.filter { it.status != PlanRules.STATUS_DONE }
        if (todo.isEmpty()) return null
        val names = todo.take(2).joinToString("；") { it.text }
        val more = if (todo.size > 2) "…" else ""
        return "【计划提醒】还有 ${todo.size} 条没勾（$names$more）。" +
            "打勾是干活的一部分：一步开始时先标 doing，做完那一刻立刻再调一次 update_plan、整表重发标 done；" +
            "卡住就留在 doing 并说明。" +
            "不许攒到最后批量补、不许偷偷划掉、不许假装做完——不标就等于没做（用户看到的是没打勾）。"
    }

    fun newRound() {
        current = emptyList()
    }

    /**
     * 续跑回种（进程被杀后）：从库读该会话最后一条计划。
     *
     * 不回种的后果：模型重发计划时 sticky done 的 previous 是空表 ⇒ 已完成的
     * 条目被打回 doing，用户看到「打完的勾又亮了」（派工单 §1.2「重启/回看就
     * 没了」的另一半——内存态这一半）。
     */
    fun seedFromStore(sessionId: Long) {
        current = PlanRules.fromJson(store.latest(sessionId) ?: return)
    }
}
