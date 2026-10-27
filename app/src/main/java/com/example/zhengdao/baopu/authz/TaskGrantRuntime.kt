// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百四十九批：授权的**运行时状态**（§4：只在内存，重启一律不恢复）。
//
// 为什么这个文件里一个 `File` 都没有：任务级授权一旦能落盘，就会有人做「重启后恢复」——
// 而那正是本批派工单点名作废的悬挂授权窗口的另一张脸。状态只活在这个进程的堆里，
// 进程没了它就没；[ProcessEpoch] 每次进程起来换一个值，老快照连装都装不进来
// （负例④「重启恢复」的判据是**结构上做不到**，不是"我们记得去清"）。
package com.example.zhengdao.baopu.authz

import java.util.Collections
import java.util.UUID

/** 进程代号：每次进程起来换一个（只住内存 ⇒ 重启必换）。 */
internal object ProcessEpoch {
    val current: String = UUID.randomUUID().toString()
}

/** 授权为什么结束（**闭集**；§2 第四要素三种 ＋ 运行时不得不停的几种）。 */
internal enum class GrantEndReason(val wire: String, val cn: String) {
    /** 任务跑完了。 */
    TASK_ENDED("task_ended", "任务结束"),

    /** 用户点停。 */
    USER_STOPPED("user_stopped", "用户点停"),

    /** 到期（单调钟）。 */
    EXPIRED("expired", "到期"),

    /** 进程重启（老快照与新进程代号对不上）。 */
    PROCESS_RESTARTED("process_restarted", "进程重启"),

    /** 用户撤销。 */
    USER_REVOKED("user_revoked", "用户撤销"),

    /** 动作数／字节数超了快照里的额度。 */
    LIMIT_EXCEEDED("limit_exceeded", "超出上限"),

    /** 碰到受保护对象（原件区／密码框）。 */
    PROTECTED_HIT("protected_hit", "碰到受保护对象"),

    /** 越界（对象不在清单、动作不在授权…）。 */
    BOUNDARY_VIOLATION("boundary_violation", "越界"),

    /** 影子跑不出结论（UNKNOWN）⇒ 停，不拖成无限弹卡。 */
    NO_DATA("no_data", "影子跑不出结论"),
    ;

    companion object {
        fun ofWire(wire: String): GrantEndReason? = entries.firstOrNull { it.wire == wire }
    }
}

/** 装不进来的原因（[InstallResult.Refused] 用；与 [GrantEndReason] 同一套词）。 */
internal sealed interface InstallResult {
    data class Installed(val state: GrantState) : InstallResult

    data class Refused(val reason: GrantEndReason, val detail: String) : InstallResult
}

/**
 * 一张授权在内存里的**活体**（状态机只有「活／已结束」两态）。
 *
 * 所有可变格都 @Volatile：写来自引擎线程，读来自 UI/勘验线程——
 * 与 `ConfirmFlow` 那四个字段同一条教训（P2-10）。
 */
internal class GrantState internal constructor(
    val grant: TaskGrant,
    /** 装配时的进程代号（必须与 [grant] 的一致，否则根本装不进来）。 */
    val epoch: String,
) {
    @Volatile
    var endReason: GrantEndReason? = null
        private set

    @Volatile
    var endedMonoMs: Long = 0L
        private set

    @Volatile
    var actionsUsed: Int = 0
        private set

    @Volatile
    var bytesWritten: Long = 0L
        private set

    private val applied: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())
    private val states: MutableMap<String, ObjectState> = Collections.synchronizedMap(mutableMapOf<String, ObjectState>())

    val alive: Boolean get() = endReason == null

    /** 已经落过账的动作（`opId` 集合；勘验/幂等读它）。 */
    fun appliedOps(): Set<String> = applied.toSet()

    fun alreadyApplied(opId: String): Boolean = applied.contains(opId)

    /** 对象此刻的状态（没动过 ⇒ 快照里的那一个）。 */
    fun stateOf(objectId: String): ObjectState =
        states[objectId] ?: grant.objectOf(objectId)?.state ?: ObjectState.Absent

    /** 结束（**只认第一个原因**：第一个原因才是事实，后面的都是回声）。 */
    fun end(reason: GrantEndReason, nowMonoMs: Long) {
        if (endReason != null) return
        endReason = reason
        endedMonoMs = nowMonoMs
    }

    /**
     * 一个动作真落地后记一次。
     *
     * @return false ＝ 这个 `opId` 已经记过（「一动作两账」在状态层也拦一次）
     */
    fun commit(opId: String, objectId: String, bytes: Long, newState: ObjectState): Boolean {
        if (!applied.add(opId)) return false
        actionsUsed += 1
        bytesWritten += bytes.coerceAtLeast(0L)
        states[objectId] = newState
        return true
    }
}

/**
 * 进程级授权槽（**一个进程一张**：新授权顶掉旧的，旧的那张随即便签为结束）。
 *
 * 没有 `restore`／`load`／`save` 这类入口——不是漏了，是本批 §4 的硬线：
 * 「运行状态只在内存；重启一律不恢复」。谁要加，先过负例④。
 */
internal object TaskGrantRuntime {

    @Volatile
    private var installed: GrantState? = null

    /** 现装的授权（可能是已结束的那张；勘验要读它的结论）。 */
    fun current(): GrantState? = installed

    /**
     * 装上这张快照。
     *
     * 三道门按顺序：快照合法 → 进程代号对得上 → 还没到期。
     * 任何一道不过都**不装**，返回原因（调用方据此走单次人批或直接停）。
     */
    fun install(grant: TaskGrant, epoch: String, nowMonoMs: Long): InstallResult {
        val issues = TaskGrantRules.validate(grant)
        if (issues.isNotEmpty()) {
            return InstallResult.Refused(
                GrantEndReason.BOUNDARY_VIOLATION,
                issues.joinToString("、") { it.wire },
            )
        }
        if (epoch != grant.processEpoch) {
            return InstallResult.Refused(
                GrantEndReason.PROCESS_RESTARTED,
                "快照 epoch=${grant.processEpoch} 当前=$epoch",
            )
        }
        if (nowMonoMs >= grant.expiresMonoMs) {
            return InstallResult.Refused(GrantEndReason.EXPIRED, "到期 ${grant.expiresMonoMs} ≤ 现在 $nowMonoMs")
        }
        installed?.end(GrantEndReason.TASK_ENDED, nowMonoMs)
        val state = GrantState(grant, epoch)
        installed = state
        return InstallResult.Installed(state)
    }

    /** 活着的授权；到期就地签结束并返回 null。 */
    fun active(nowMonoMs: Long): GrantState? {
        val s = installed ?: return null
        if (s.alive && nowMonoMs >= s.grant.expiresMonoMs) s.end(GrantEndReason.EXPIRED, nowMonoMs)
        return s.takeIf { it.alive }
    }

    /** 结束当前这张（点停/任务结束/撤销都走这里）。 */
    fun end(reason: GrantEndReason, nowMonoMs: Long) {
        installed?.end(reason, nowMonoMs)
    }

    /** 任务结束：只有同一张任务的那张授权才结束（别的任务不被误伤）。 */
    fun endTask(taskId: String, nowMonoMs: Long) {
        val s = installed ?: return
        if (s.grant.taskId == taskId) s.end(GrantEndReason.TASK_ENDED, nowMonoMs)
    }

    /** 直测收尾（生产不调；与 `ConfirmGrants.resetForTest` 同款纪律）。 */
    fun resetForTest() {
        installed = null
    }
}
