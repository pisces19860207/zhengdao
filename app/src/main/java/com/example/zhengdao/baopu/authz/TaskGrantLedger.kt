// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百四十九批：任务级授权内的动作**怎么落 208**（六步的第 6 步）。
//
// 一个动作一条账，键是 `<opId>:applied`。为什么不是「执行完再记一条」：
// 重跑（引擎重试／进程里同一个 opId 被排两次）会变成两条一模一样的账，
// 到抽检那一步就分不清「真做了两次」还是「记了两次」——负例⑧（一动作两账）的判据就是它。
//
// 本文件只管「记」；判定同不同意的仍然是 [TaskGrantGate] 的六步（分工同 239 §10①：
// 记账的账本不是闸门，闸门也不许替账本去重）。
package com.example.zhengdao.baopu.authz

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionAppend
import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import java.io.File

/** 任务级授权动作的账本（薄薄一层：形状全部复用 208，不新造第二套）。 */
internal object TaskGrantLedger {

    /** 幂等键（与 [TaskGrantGate.ledgerKeyOf] 同一拼法，**只有一个来源**）。 */
    fun keyOf(opId: String): String = TaskGrantGate.ledgerKeyOf(opId)

    /**
     * 记一条「授权内已执行」。
     *
     * @return APPENDED 新落；DUPLICATE 这个 opId 已经落过（**不重复落**）；FAILED 写不进去
     */
    fun recordApplied(
        root: File,
        request: ActionRequest,
        atMs: Long,
        stateBefore: ObjectState = request.before,
        stateAfter: ObjectState = request.before,
    ): DecisionAppend = DecisionLedger.recordOnce(
        root,
        keyOf(request.opId),
        DecisionDraft(
            kind = DecisionKind.AUTO_ACT,
            actor = DecisionActor.MODEL,
            reason = "任务级授权内执行${request.action.cn}",
            evidence = keyOf(request.opId),
            before = "${request.objectId}:${stateBefore.wire}",
            after = "${request.objectId}:${stateAfter.wire}",
            atMs = atMs,
            sessionId = request.sessionId,
            // 通用写盘没有统一的回滚引用 ⇒ 标可回滚就是骗人（208 负例③的同一条）。
            reversible = false,
        ),
    )

    /** 已落过的动作数（测试/勘验读数用；只数本文件写的那种账）。 */
    fun appliedOps(root: File): List<String> = DecisionLedger.read(root, DecisionLedger.READ_LIMIT)
        .filter { it.kind == DecisionKind.AUTO_ACT && it.evidence.endsWith(":applied") }
        .map { it.evidence.removeSuffix(":applied") }
}
