// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百三十五批 · 因果链骨架：检查器（判据③「断链 ⇒ 红」的牙在这里）。
//
// 检查分两层，特意分开写：
//   · **静态审计**（[CausalChecks.audit]）：只看事件本身的字段就能判的——重复 id、父指向不存在、
//     父比自己晚、父类型不合法、强度越界、写了自己不该写的维度、绝对档越界、部位没点名。
//   · **回放审计**（[CausalReplay.issues]）：只有按时间顺序走一遍才知道的——增量写法没有基线。
// 合成一个函数会逼着静态审计也去做排序模拟，红起来的理由就说不清是哪一层了。
package com.example.zhengdao.baopu.state.causal

/** 一条检查结论。[code] 给机器（测试按码断言），[message] 给人。 */
internal data class CausalIssue(val code: String, val message: String, val eventId: String) {
    override fun toString(): String = "$code@$eventId：$message"
}

/**
 * 三根链的形状：写成**边**，不写成排队的节点列表。
 *
 * 为什么：链②是分叉的——「环境变化 →（湿/温）→ 体感」与「环境变化 → 光影」是两条支线，
 * 不是「体感之后才有光影」。硬写成一条线会逼着把光影挂到体感下面，那是假的因果关系；
 * 而每条边仍然用「祖先链」验，所以断链照样判红。
 */
internal data class CausalChainSpec(val id: String, val label: String, val edges: List<Pair<CausalKind, CausalKind>>) {
    /** 这根链涉及的全部节点（按首次出现次序），报告里用。 */
    val kinds: List<CausalKind> get() = (edges.map { it.first } + edges.map { it.second }).distinct()
}

/** 三条链的边集（唯一真相；报告与测试都从这里取，不许另写一份）。 */
internal object CausalChains {
    val INJURY = CausalChainSpec(
        "chain.injury", "受伤→步态改变→后续受限",
        listOf(
            CausalKind.STRIKE to CausalKind.GAIT,
            CausalKind.GAIT to CausalKind.RESTRICT,
        ),
    )

    val ENVIRONMENT = CausalChainSpec(
        "chain.environment", "环境变化→皮肤湿→皮肤温→体感（光影并挂环境）",
        listOf(
            CausalKind.AMBIENT to CausalKind.WET,
            CausalKind.WET to CausalKind.TEMP,
            CausalKind.TEMP to CausalKind.FEEL,
            CausalKind.AMBIENT to CausalKind.LIGHT,
        ),
    )

    val FATIGUE = CausalChainSpec(
        "chain.fatigue", "动作→疲劳累积→恢复时限",
        listOf(
            CausalKind.ACT to CausalKind.FATIGUE,
            CausalKind.FATIGUE to CausalKind.RECOVER,
        ),
    )

    val ALL: List<CausalChainSpec> = listOf(INJURY, ENVIRONMENT, FATIGUE)
}

/** 静态审计。返回空表＝干净；顺序与日志顺序一致，便于对着报告逐条看。 */
internal object CausalChecks {

    const val DUPLICATE_ID = "causal.duplicate-id"
    const val EMPTY_LOG = "causal.empty-log"
    const val BROKEN_PARENT = "causal.broken-parent"
    const val UNKNOWN_PARENT = "causal.unknown-parent"
    const val BACKWARD_TIME = "causal.backward-time"
    const val ILLEGAL_PARENT = "causal.illegal-parent"
    const val INTENSITY_RANGE = "causal.intensity-range"
    const val MECHANISM_REQUIRED = "causal.mechanism-required"
    const val MECHANISM_FOREIGN = "causal.mechanism-foreign"
    const val DIM_NOT_ALLOWED = "causal.dim-not-allowed"
    const val LEVEL_OUT_OF_RANGE = "causal.level-out-of-range"
    const val SITE_UNNAMED = "causal.site-unnamed"
    const val DELTA_WITHOUT_BASE = "causal.delta-without-base"
    const val CHAIN_BROKEN = "causal.chain-broken"

    fun audit(log: CausalLog): List<CausalIssue> {
        if (log.events.isEmpty()) return listOf(CausalIssue(EMPTY_LOG, "事件日志为空", ""))
        val out = ArrayList<CausalIssue>()
        out.addAll(duplicates(log))
        log.events.forEach { out.addAll(auditOne(log, it)) }
        return out
    }

    private fun duplicates(log: CausalLog): List<CausalIssue> {
        val seen = HashSet<String>()
        return log.events.filter { !seen.add(it.eventId) }
            .map { CausalIssue(DUPLICATE_ID, "事件 id 重复：${it.eventId}", it.eventId) }
    }

    private fun auditOne(log: CausalLog, e: CausalEvent): List<CausalIssue> {
        val out = ArrayList<CausalIssue>()
        out.addAll(auditLink(log, e))
        if (!e.intensityInRange) {
            out.add(CausalIssue(INTENSITY_RANGE, "强度档越界：${e.intensity}（0–${CausalEvent.INTENSITY_MAX}）", e.eventId))
        }
        out.addAll(auditMechanism(e))
        out.addAll(auditWrites(e))
        if (e.site == SiteRef.Unnamed) {
            out.add(CausalIssue(SITE_UNNAMED, "部位没点名（825 §二：缺项 ⇒ 红）", e.eventId))
        }
        return out
    }

    private fun auditLink(log: CausalLog, e: CausalEvent): List<CausalIssue> {
        val pid = e.parentEventId
        if (pid == null) {
            return if (e.kind.isRoot) {
                emptyList()
            } else {
                listOf(CausalIssue(BROKEN_PARENT, "${e.kind.label} 缺父事件（该类型不是根）", e.eventId))
            }
        }
        val parent = log.byId[pid]
            ?: return listOf(CausalIssue(UNKNOWN_PARENT, "父事件不存在：$pid", e.eventId))
        val out = ArrayList<CausalIssue>()
        if (parent.at > e.at) {
            out.add(CausalIssue(BACKWARD_TIME, "父事件 ${parent.eventId} 比子事件更晚（${parent.at} > ${e.at}）", e.eventId))
        }
        if (parent.kind !in e.kind.parents) {
            out.add(
                CausalIssue(
                    ILLEGAL_PARENT,
                    "${e.kind.label} 的父不许是 ${parent.kind.label}（允许：${e.kind.parents.joinToString { it.label }}）",
                    e.eventId,
                ),
            )
        }
        return out
    }

    private fun auditMechanism(e: CausalEvent): List<CausalIssue> = when {
        e.kind == CausalKind.STRIKE && e.mechanism == null ->
            listOf(CausalIssue(MECHANISM_REQUIRED, "受伤事件必须给 748 的机制", e.eventId))
        e.kind != CausalKind.STRIKE && e.mechanism != null ->
            listOf(CausalIssue(MECHANISM_FOREIGN, "${e.kind.label} 不该带受伤机制", e.eventId))
        else -> emptyList()
    }

    private fun auditWrites(e: CausalEvent): List<CausalIssue> {
        val out = ArrayList<CausalIssue>()
        (e.immediate + e.delayed.map { it.write }).forEach { w ->
            if (!e.kind.mayWrite(w.dim)) {
                out.add(
                    CausalIssue(
                        DIM_NOT_ALLOWED,
                        "${e.kind.label} 不许写 ${w.dim.label}（只许：${e.kind.writes.joinToString { it.label }}）",
                        e.eventId,
                    ),
                )
            }
            if (w.escape()) {
                out.add(CausalIssue(LEVEL_OUT_OF_RANGE, "${w.dim.label} 档越界：${(w as GradeWrite.To).level}", e.eventId))
            }
        }
        return out
    }

    /** 链完整性：spec 里每一条边，都必须有一条**祖先链**连着（不是「日志里都有这两个类型」）。 */
    fun chainIssues(log: CausalLog, spec: CausalChainSpec): List<CausalIssue> {
        val out = ArrayList<CausalIssue>()
        spec.edges.forEach { (a, b) ->
            val linked = log.events.any { e ->
                e.kind == b && log.ancestry(e.eventId).any { it.kind == a }
            }
            if (!linked) {
                out.add(CausalIssue(CHAIN_BROKEN, "${spec.label}：缺 ${a.label}→${b.label} 这一段", spec.id))
            }
        }
        return out
    }

    fun allChainIssues(log: CausalLog): List<CausalIssue> = CausalChains.ALL.flatMap { chainIssues(log, it) }
}
