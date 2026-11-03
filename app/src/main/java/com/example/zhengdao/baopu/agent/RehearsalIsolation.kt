// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百五十八批 · 军师与影子的**隔离件**（派单【隔离】四条硬规矩里的第 ④ 条）。
//
// 派单已经把另外三条钉在别处，这里不重复实现、只把「同任务串行」这一条落下来：
// · ①目录互不读 ⇒ 判据在 [RehearsalSandbox.refuse]（军师侧）＋影子那边本来就只收
//   **相对工作区**的声明路径、绝对写法直接拒（`FileShadowSandbox.prepareOpen` 的越界格），
//   两个根还分别长在 `cacheDir/rehearsal/` 与 `<工作区>/.shadow/` 两棵不同的树上；
// · ②runId 前缀 ⇒ [RehearsalSandbox.RUN_ID_PREFIX]（`rh-`）与影子账行的 `sh:` 前缀；
// · ③两边都不写真实目录、不写主账 ⇒ 红线在 [RehearsalSandbox] 与 `GuardPipeline.writeLedgers`
//   各自的测试里钉（本批不碰 208 那本账，一条都不写）。
//
// ## 为什么串行不是并发
// 同一个任务上「先推演一遍」和「先在副本重做一遍」都要占内存、都要建一棵副本树。
// 两棵树叠在同一份声明对象上，跑完谁都说不清对方那一份改动算谁的——派单原话是
// 「防内存争用与副本混用」。所以这一根道**同一时刻只许一个持道者**，抢不到就排队／放弃，
// 不并发、不插队、不共享副本。
package com.example.zhengdao.baopu.agent

import java.util.concurrent.ConcurrentHashMap

/** 谁持这根道。 */
internal enum class RehearsalLaneOwner(val cn: String) {
    /** 军师（本批：推演盘）。 */
    REHEARSAL("军师"),

    /** 影子（296/361：副本重做比对）。 */
    SHADOW("影子"),
}

/**
 * 同任务一根道（派单【隔离】④：军师与影子对同一任务**串行**跑）。
 *
 * 键是任务级（会话/轮次那一级，不是文件级）：一个任务里的多个动作本来就是一条链，
 * 链中途被另一位的副本插进来，比整链排队更糟。
 */
internal object RehearsalLane {

    private val busy = ConcurrentHashMap<String, RehearsalLaneOwner>()

    /**
     * 抢道：空着才给，已有持道者（不论是谁）一律 false。
     *
     * 同一位重复抢也拒——推演自己叠两层副本树同样是「副本混用」。
     */
    fun tryBegin(taskKey: String, owner: RehearsalLaneOwner): Boolean {
        val key = taskKey.trim()
        if (key.isEmpty()) return false
        return busy.putIfAbsent(key, owner) == null
    }

    /** 还道：只有持道者本人能还（拿别人的道去还＝把正在跑的那位踢下线）。 */
    fun end(taskKey: String, owner: RehearsalLaneOwner): Boolean =
        busy.remove(taskKey.trim(), owner)

    /** 现在谁持着这根道（null＝空）。 */
    fun current(taskKey: String): RehearsalLaneOwner? = busy[taskKey.trim()]

    /** 抢不到道时给报告的一句话（说得出被谁挡的，不写成"系统忙"这种糊话）。 */
    fun waitNote(taskKey: String): String {
        val holder = current(taskKey)
        return if (holder == null) "道是空的（抢道失败：任务键为空）" else "同任务的${holder.cn}正在跑 ⇒ 排队"
    }

    /** 测试清场用（生产不调：进程内状态，重启自然归零）。 */
    internal fun resetForTest() {
        busy.clear()
    }
}
