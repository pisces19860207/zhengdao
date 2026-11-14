// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十八批 C（2026-11-14）· 域②路由与使臣五流的**纯判据**（零 IO、零时钟、零 Android）。
//
// 派工单 §一⑧：「549 的状态机；路由可调，状态语义不可改」。两条边界钉死在这里：
//   · **路由可调**：选哪个 peer、走哪条路（[RouteInput] 的偏好＋路径标）是进化层能自调的输入；
//   · **状态语义不可改**：五流状态机（549 的 [com.example.zhengdao.baopu.peer.A2aTaskState]／
//     [com.example.zhengdao.baopu.peer.EnvoyLaw]／EnvoyTrack）的一字一句不许动——本文件**只引用**
//     549 的闭集，不复制字面量、不另立第二份状态真相（改 549 这里跟着变，门禁钉的是 549 本体）。
//
// 「冲突时以状态机为准」落在 [flowLegal]：路由假设的收场迁移合法性**就是** 549 的迁移法条
// （[com.example.zhengdao.baopu.peer.EnvoyLaw.legal]），本层不另判——路由偏好再怎么走，
// 也走不出状态机允许的链；假设了一条非法迁移（如终态翻案）⇒ 状态机赢，路由候选被拒。
package com.example.zhengdao.baopu.evolution.highrisk

import com.example.zhengdao.baopu.peer.A2aTaskState
import com.example.zhengdao.baopu.peer.EnvoyLaw

/**
 * 域②的路由输入：选哪个 peer（[preferredPeer]）、走哪条路（[path] 路径标）。
 *
 * **不含任何状态语义字段**——状态机是路由的下游，不是路由的一部分。路由自调只挪这两个值，
 * 五流状态词表/终态集/要人集/迁移法条全归 549，本对象碰都不碰。
 */
internal data class RouteInput(val preferredPeer: String, val path: String)

/** 域②的路由编解码与状态语义守卫（纯函数；落账 before/after 与读回共用同一把尺）。 */
internal object RouteSemantics {

    /** 549 状态词表（只引用闭集，不复制字面量——第二份真相＝漂移入口）。 */
    val STATE_WORDS: Set<String> = A2aTaskState.entries.map { it.wire }.toSet()

    /** 549 终态集（completed/failed/canceled/rejected——只引用，不另判）。 */
    val TERMINAL_WORDS: Set<String> = A2aTaskState.entries.filter { it.terminal }.map { it.wire }.toSet()

    /** 549「停下来要人」集（input-required/auth-required——只引用，不另判）。 */
    val NEEDS_HUMAN_WORDS: Set<String> = A2aTaskState.entries.filter { it.needsHuman }.map { it.wire }.toSet()

    /** 路由输入 ⇒ 稳定串（`peer|path`；空偏好不成路由）。落账与读回都走它，不另拼一套。 */
    fun encode(input: RouteInput): String = "${input.preferredPeer}|${input.path}"

    /** 稳定串 ⇒ 路由输入（空串/空偏好 ⇒ null＝调用方用缺省路由；路径标可空）。 */
    fun decode(text: String): RouteInput? {
        if (text.isBlank()) return null
        val peer = text.substringBefore('|')
        if (peer.isBlank()) return null
        return RouteInput(peer, text.substringAfter('|', ""))
    }

    /**
     * 冲突时以状态机为准：路由假设的迁移合法性＝549 的迁移法条（[EnvoyLaw.legal]）。
     * 本层一字不另判——终态粘住、退回 submitted 的限制、UNKNOWN 兜底全归 549。
     */
    fun flowLegal(from: A2aTaskState?, to: A2aTaskState): Boolean = EnvoyLaw.legal(from, to)
}