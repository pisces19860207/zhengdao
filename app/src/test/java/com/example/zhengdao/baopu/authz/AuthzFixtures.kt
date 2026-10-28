// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百四十九批：本批测试共用的夹具（**不放进生产源**：夹具里的"合法授权"是一种
// 测试约定，进了 main 就会被人当成默认值）。
package com.example.zhengdao.baopu.authz

import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.inspect.ShadowVerdict

internal object AuthzFixtures {

    const val EPOCH = "epoch-249"
    const val NOW = 1_000_000L
    const val MIN = 60_000L

    fun obj(
        id: String,
        rev: Int = 0,
        state: ObjectState = ObjectState.Absent,
        location: ObjectLocation = ObjectLocation.CONTROLLED_TASK_STORE,
        taskId: String = "t1",
    ): GrantObject = GrantObject(
        objectId = id,
        revision = rev,
        taskId = taskId,
        location = location,
        state = state,
    )

    fun grant(
        objects: List<GrantObject> = listOf(obj("a"), obj("b")),
        actions: List<GrantActionType> = listOf(GrantActionType.CREATE_TEXT, GrantActionType.EDIT_TEXT),
        budget: GrantBudget = GrantBudget(),
        issued: Long = NOW,
        expires: Long = NOW + 30 * MIN,
        maxRiskOrdinal: Int = 2,
        epoch: String = EPOCH,
        taskId: String = "t1",
    ): TaskGrant = TaskGrant(
        grantId = "grant-249",
        sessionId = 7L,
        taskId = taskId,
        processEpoch = epoch,
        contractHash = "contract-hash-249",
        objects = objects,
        actions = actions,
        maxRiskOrdinal = maxRiskOrdinal,
        budget = budget,
        issuedMonoMs = issued,
        expiresMonoMs = expires,
        issuedWallMs = 1_760_000_000_000L,
        approvalEventId = "auto_act:1760000000000:abcd1234:1",
        presentedPayloadHash = "presented-hash-249",
    )

    /** 真装一次（走的与生产同一条路：快照合法 → 进程代号 → 没到期）。 */
    fun state(
        grant: TaskGrant = grant(),
        epoch: String = EPOCH,
        now: Long = NOW,
    ): GrantState {
        val result = TaskGrantRuntime.install(grant, epoch, now)
        if (result !is InstallResult.Installed) throw AssertionError("夹具装不进来：$result")
        return result.state
    }

    fun request(
        objectId: String = "a",
        action: GrantActionType = GrantActionType.CREATE_TEXT,
        candidateBytes: Long = 64L,
        risk: RiskClass = RiskClass.R2,
        opId: String = "op-1",
        shadow: ShadowSignal = ShadowSignal.Rehearsed(ShadowVerdict.CONSISTENT),
        before: ObjectState = ObjectState.Absent,
        now: Long = NOW + 1_000L,
        epoch: String = EPOCH,
        taskId: String = "t1",
        mustAsk: Boolean = false,
        boundary: BoundaryFacts = BoundaryFacts(),
    ): ActionRequest = ActionRequest(
        opId = opId,
        engineRequestId = "eng-$opId",
        sessionId = 7L,
        taskId = taskId,
        objectId = objectId,
        action = action,
        candidateBytes = candidateBytes,
        risk = risk,
        mustAskIndependently = mustAsk,
        boundary = boundary,
        shadow = shadow,
        before = before,
        nowMonoMs = now,
        processEpoch = epoch,
    )
}
