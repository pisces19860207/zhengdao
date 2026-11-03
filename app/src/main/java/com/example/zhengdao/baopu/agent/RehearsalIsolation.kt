// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百五十八批 · 军师与影子的**隔离件**（派单【隔离】第 ④ 条＋§一⑥／§四⑤ 的并发上限）。
//
// 这一只文件里两根闸，管的是两件不同的事：
// · [RehearsalLane]＝【隔离】④「同一任务上军师与影子串行」；
// · [RehearsalSite]＝§一⑥／§四⑤「同一场地最多 2 个在跑，第 3 个排队并点名挡道的那两位」
//   （御裁 2026-11-04 04:29：这条必须实现、有牙，不移交）。
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

/**
 * 同场地的并发闸（派单 §一⑥「同场地 ≤2，超出排队」＋§四⑤「第 3 个 ⇒ 排队（有测试）」；
 * 御裁 2026-11-04 04:29 把它从"移交"改判成"本批实现，有牙"）。
 *
 * 场地＝**一份工作区**，键取 `workspaceRoot` 的绝对路径：两个目录各算各的额度，同一目录上的
 * 军师挤到第三个才排队。与 [RehearsalLane] 是两根不同的闸，不是一根的两档——
 * 【隔离】④ 管的是"同一任务上军师与影子不许叠"（一根道、一个持道者），这里管的是
 * "不同任务、同一份树"上有几趟推演可以同时在跑。上限 2 的理由照派单原文：**防内存争用与
 * 副本混用**——两棵副本树长在同一目录上还说得清谁改了哪份，第三棵开始说不清。
 *
 * 名额的持有人用 `runId`（`rh-…`）：报告点名挡道者时点的就是这个号，事后能在日志里对上。
 */
internal object RehearsalSite {

    /** 一个场地上同时最多几个（派单点名的那个 2，只在这里写一次）。 */
    const val MAX_CONCURRENT = 2

    // 每个场地一张**有序**表：LinkedHashSet 保抢进来的先后，报告点名才稳定、测试才好钉。
    private val running = ConcurrentHashMap<String, LinkedHashSet<String>>()

    /**
     * 进场。三种情况都拒：键空／runId 空（进了场没法还＝留一个无归属的永久名额）、
     * 场上已满 [MAX_CONCURRENT]（排队）、同一个 runId 已经在场上（同一位不许叠两份副本）。
     */
    fun tryEnter(siteKey: String, holderId: String): Boolean {
        val key = siteKey.trim()
        val id = holderId.trim()
        if (key.isEmpty() || id.isEmpty()) return false
        var entered = false
        running.compute(key) { _, holders ->
            val set = holders ?: LinkedHashSet()
            if (set.contains(id) || set.size >= MAX_CONCURRENT) {
                set
            } else {
                entered = true
                set.add(id)
                set
            }
        }
        return entered
    }

    /** 退场：只能还自己的名额（拿别人的 runId 去还＝把正在跑的那位的名额吞掉）。 */
    fun leave(siteKey: String, holderId: String): Boolean {
        val key = siteKey.trim()
        val id = holderId.trim()
        if (key.isEmpty() || id.isEmpty()) return false
        var left = false
        running.compute(key) { _, holders ->
            if (holders != null && holders.remove(id)) {
                left = true
                if (holders.isEmpty()) null else holders
            } else {
                holders
            }
        }
        return left
    }

    /** 此刻场上有谁（抢进来的先后）。 */
    fun holders(siteKey: String): List<String> =
        running[siteKey.trim()]?.toList() ?: emptyList()

    /** 场上占了几个。 */
    fun count(siteKey: String): Int = holders(siteKey).size

    /** 抢不进时那句：点名挡道的那几位＋上限，不写成"系统忙"这种糊话。 */
    fun waitNote(siteKey: String): String {
        val present = holders(siteKey)
        if (present.isEmpty()) return "场地或 runId 是空的 ⇒ 这个名额没人能还，所以不进"
        return "同场地已有 ${present.size} 个在跑（${present.joinToString("、")}）" +
            "⇒ 排队（上限 $MAX_CONCURRENT）"
    }

    /** 测试清场用（生产不调：进程内状态，重启自然归零）。 */
    internal fun resetForTest() {
        running.clear()
    }
}
