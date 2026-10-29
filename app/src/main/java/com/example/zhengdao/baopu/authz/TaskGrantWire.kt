// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十六批 P1①②（249-B 接线层）：把 249 那条**从没被生产调用过**的六步
// （`TaskGrantGate.evaluate`，实测 `app/src/main` 零引用）接到真实执行路径上，并给它补上
// 法典 L26 的三元组绑定。本层是**唯一允许碰盘的构造层**：
//   · `TaskGrant.kt`／`TaskGrantRuntime.kt`／`TaskGrantGate.kt`／`TaskGrantInspect.kt`／
//     `TaskGrantSection.kt` 五份文件被 249 的源级门禁钉着「零 IO」——本批**一个字节没动**它们，
//     需要读盘的事实全部落在这一份新文件里（2026-10-28 用户补的规矩：新数据构造一律放新文件）；
//   · 判定仍然在那两处：本层只**算事实**（这次动作是谁、几档、几个字节、对象现状如何、
//     三元组长什么样），一个"放行/拦下"的结论都不自己下。
//
// ## 三个缺口怎么补的（2026-10-29 用户逐条裁定）
// · **对象清单从哪来**（Q3）：契约的 `touches` 说的是目录／主机名，不是"列得出的文件清单"。
//   本层按她给的口径展开：**stat 得出文件 ⇒ 进清单；是目录 ⇒ 只列一层；列不出来
//   或超过 10 个 ⇒ 这一轮不生成授权**（照旧逐次问）。宁可少一张免问的单，不多一张
//   盖不住范围的单——判据取窄口径。
// · **任务身份**（Q5）：任务＝这次批准的那份计划，`taskId` 用 `PlanContract` 的
//   规范化摘要（[contractDigest]）；计划变了＝任务变了＝老授权自然对不上（六步第①步的
//   `foreign_task` 那一格会拦住）。
// · **sessionId**（Q6）：走 [TaskGrantBoard] 内存板，**不改** `prepare/execute/executeApproved`
//   三个签名（牵动全量测试面的那种改法她明确不要）。
//
// ## 顺序为什么是「L26 先、六步后」
// 三元组说的是"批的是哪一份"，六步说的是"这份授权盖不盖得住这次动作"。份都对不上，
// 拿它去盖范围就是拿别人的授权办事 ⇒ 所以 [decide] 先比三元组，不符直接按**未授权**收，
// 连六步都不进（判据②「改一个参数用旧授权 ⇒ 必拒」就落在这儿）。
package com.example.zhengdao.baopu.authz

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionAppend
import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.RiskVerdict
import com.example.zhengdao.baopu.agent.RunTraceRules
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.plan.PlanContract
import com.example.zhengdao.baopu.plan.TouchFace
import com.example.zhengdao.baopu.plan.TouchFaceKind
import java.io.File
import java.security.MessageDigest

/** 本层算出来的"授权盖不住"的几种原因（一句话人话，进日志与报告，不进判定）。 */
internal enum class WireRefusal(val cn: String) {
    NO_CONTRACT("这一轮没有已批准的计划契约"),
    NO_PATH_FACE("契约声明的目标里没有可点的文件路径（只有主机名或空声明）"),
    NOT_LISTABLE("有声明列不出来（既不是文件也不是能列一层的目录）"),
    TOO_MANY_OBJECTS("列出来的对象超过 10 个"),
    EMPTY_OBJECTS("一个可写的对象都没列出来"),
    FILE_OVER_LIMIT("有对象已经超过单文件上限 2MB（这份清单不该整批授权）"),
    NO_SESSION("会话 id 不可用（内存板还没接上这一轮）"),
    LEDGER_NOT_ASSEMBLED("208 没装配落点：批准没记账 ⇒ 不开单"),
    INSTALL_REFUSED("快照装不进来（校验或进程代号或有效期不合格）"),
    ;

    override fun toString(): String = cn
}

/** 一次判定的产物：本层把事实交给管线，管线只按 [disposition] 处置。 */
internal enum class WiringDisposition {
    /** 这一格不归授权链管（读工具／没有可测的写入字节／矩阵结论缺失）⇒ 执行路径逐字不变。 */
    NOT_APPLICABLE,

    /** 没有生效的单，或 L26 三元组对不上 ⇒ **视为未授权**，照今天默认流程走。 */
    UNAUTHORIZED,

    /** 六步全过**且**三元组相符 ⇒ 这一格免卡（P1 生产不可达：第⑤步恒没影子 ⇒ NO_DATA）。 */
    ALLOW,

    /** 六步判了硬拒 ⇒ 连问都不问（管线出 [com.example.zhengdao.baopu.agent.GuardPipeline.Result.Rejected]）。 */
    REJECT,

    /** 六步判了"停"（限额用尽／影子没数据／同 opId 重复落账）⇒ 不免卡，照今天默认流程走。 */
    STOPPED,
    ;
}

/** [TaskGrantWire.decide] 的读数（管线拿它写日志，报告拿它打「六步真实读数」）。 */
internal data class WiringResult(
    val disposition: WiringDisposition,
    /** 判定用的请求（ALLOW 时管线执行完要拿它落账与 commit）。 */
    val request: ActionRequest? = null,
    /** 六步的结论（没进六步＝null：三元组先拦下，或这一格根本不归授权管）。 */
    val decision: GateDecision? = null,
    /** 一句话原因（不符在哪、为什么不适用）。 */
    val note: String = "",
) {
    val allow: Boolean get() = disposition == WiringDisposition.ALLOW
}

/** 本层的两个入口共用的开单产物。 */
internal data class OpenResult(
    val installed: Boolean,
    val grantId: String = "",
    val reason: String = "",
)

/** 六步接线的**注入接口**（与 242 的 `ContractSource` 同房型：管线不读单例，判据才打得住）。 */
internal interface GrantWiring {
    fun decide(
        call: ToolCall,
        targets: List<File>,
        verdict: RiskVerdict?,
        root: File,
        isWrite: Boolean,
        nowMonoMs: Long,
        epoch: String,
        shadow: ShadowSignal = ShadowSignal.None,
        log: (String) -> Unit = { },
    ): WiringResult
}

/** 构造层（唯一碰盘的那一层）。 */
internal object TaskGrantWire : GrantWiring {

    /** 授权有效期（§2 上限内取满格；到期就得重新开单）。 */
    const val LIFETIME_MONO_MS = GrantLimits.MAX_LIFETIME_MONO_MS

    /** 可测字节的参数名：参数里没有 `content` 就说不清"这次写多少字节"，那一格不进授权链（拿猜的字节数比上限＝假宽）。 */
    private const val CONTENT_KEY = "content"

    // ── Q3：把契约的声明展开成"列得出的文件清单" ────────────────────────────

    /**
     * 契约声明 ⇒ 可点的对象清单（用户 2026-10-29 裁定的三条口径，一条都不放宽）：
     * stat 得出文件 ⇒ 进清单；是目录 ⇒ **只列一层**（目录本身不是可写的对象）；
     * 列不出来 ⇒ 整份不生成（不是"跳过这一格"）。
     *
     * 相对写法一律相对工作区根；超过 [GrantLimits.MAX_OBJECTS] 也整份不生成——
     * 截断清单＝卡上说的和授权盖的范围不一致，那是 L26 要拦的那类事。
     */
    internal fun objectsOf(contract: PlanContract, root: File): Pair<List<GrantObject>, WireRefusal?> {
        val specs = contract.allTouchSpecs.mapNotNull { TouchFace.of(it) }
            .filter { it.kind == TouchFaceKind.PATH }
            .map { it.value }
            .distinct()
        if (specs.isEmpty()) return emptyList<GrantObject>() to WireRefusal.NO_PATH_FACE
        val taskId = contractDigest(contract)
        val out = linkedMapOf<String, GrantObject>()
        specs.forEach { spec ->
            val f = File(root, spec)
            when {
                f.isFile -> if (!putObject(out, taskId, root, f)) return emptyList<GrantObject>() to WireRefusal.FILE_OVER_LIMIT
                f.isDirectory -> {
                    val kids = runCatching { f.listFiles() }.getOrNull()
                        ?.filter { it.isFile }
                        ?.sortedBy { it.name }
                        .orEmpty()
                    // `listFiles()` 返回 null＝列不出来（权限／IO／竞态）：目录形态**又**一个都没列出来
                    // ⇒ 不能算"这格里没东西"，只能算"这一格说不好"。说不好就不开单。
                    if (runCatching { f.listFiles() }.getOrNull() == null) {
                        return emptyList<GrantObject>() to WireRefusal.NOT_LISTABLE
                    }
                    kids.forEach { kid ->
                        if (!putObject(out, taskId, root, kid)) return emptyList<GrantObject>() to WireRefusal.FILE_OVER_LIMIT
                    }
                }
                else -> return emptyList<GrantObject>() to WireRefusal.NOT_LISTABLE
            }
        }
        val objects = out.values.toList()
        if (objects.isEmpty()) return emptyList<GrantObject>() to WireRefusal.EMPTY_OBJECTS
        if (objects.size > GrantLimits.MAX_OBJECTS) return objects to WireRefusal.TOO_MANY_OBJECTS
        return objects to null
    }

    /** 收一个文件进清单（objectId＝相对根的正斜杠写法；同名去重）。假＝超单文件上限。 */
    private fun putObject(out: MutableMap<String, GrantObject>, taskId: String, root: File, f: File): Boolean {
        val rel = runCatching { f.absoluteFile.toRelativeString(root.absoluteFile) }
            .getOrDefault(f.name).replace('\\', '/')
        if (rel in out) return true
        val bytes = if (f.isFile) f.length() else 0L
        if (bytes > GrantLimits.MAX_BYTES_PER_FILE) return false
        val state = if (f.isFile) {
            ObjectState.Present(sha256File(f), bytes)
        } else {
            ObjectState.Absent
        }
        out[rel] = GrantObject(objectId = rel, revision = 0, taskId = taskId, state = state)
        return true
    }

    /**
     * 任务身份（Q5）：**任务＝这次批准的那份计划**，id 就是契约的规范化摘要。
     *
     * 为什么不含时刻／步骤勾选状态：勾选每天都在变，任务却是同一件事；把这些塞进摘要
     * 会让老授权因为"计划打到第 3 步"而自认对不上——那是把身份判成进度判。
     * 反过来，目标、只读区、上限、预估问几次**都在**摘要里：她批的那份变了，摘要就变。
     */
    internal fun contractDigest(contract: PlanContract): String = sha256Hex(
        buildString {
            append("goal=").append(contract.goal).append('|')
            append("asks=").append(contract.expectedAsks).append('|')
            contract.steps.sortedBy { it.n }.forEach { s ->
                append("step").append(s.n).append(':').append(s.intent).append(':')
                append(s.risk.name).append(':')
                append(s.mayTouch.sorted().joinToString(",")).append(';')
            }
            append("|touches=").append(contract.touches.sorted().joinToString(","))
            append("|readonly=").append(contract.readonly.sorted().joinToString(","))
            append("|stopIf=").append(contract.stopIf.sorted().joinToString(","))
        },
    ).take(16)

    /** 卡面参数摘要（L26 三元组第二格；限额／有效期／清单／契约号全在里面）。 */
    internal fun presentedArgsOf(grant: TaskGrant, objectsDigest: String, actionId: String): String {
        val budget = grant.budget.clamped()
        return RunTraceRules.argsHashOf(
            listOf(
                "grant=${grant.grantId}",
                "contract=${grant.contractHash}",
                "session=${grant.sessionId}",
                "actions=$actionId",
                "objects=$objectsDigest",
                "maxObjects=${budget.maxObjects}",
                "maxActions=${budget.maxActions}",
                "maxFile=${budget.maxBytesPerFile}",
                "maxTotal=${budget.maxBytesTotal}",
                "maxRisk=${grant.maxRisk}",
                "lifetime=${grant.expiresMonoMs - grant.issuedMonoMs}",
            ).joinToString("|"),
        )
    }

    /** 一份候选单（对象清单＋动作＋上限＋三元组）；列不出来 ⇒ null ＋ 原因（照旧逐次问）。 */
    fun buildOffer(
        contract: PlanContract?,
        root: File,
        sessionId: Long,
        epoch: String,
        nowMonoMs: Long,
        wallMs: Long,
    ): Pair<GrantOffer?, WireRefusal?> {
        if (contract == null) return null to WireRefusal.NO_CONTRACT
        if (sessionId <= 0L) return null to WireRefusal.NO_SESSION
        val (objects, refusal) = objectsOf(contract, root)
        if (refusal != null) return null to refusal
        val contractHash = contractDigest(contract)
        val actions = GrantActionType.GRANTABLE
        val actionId = ApprovalBindings.actionIdOf(actions)
        val objectsDigest = ApprovalBindings.objectsDigestOf(objects.map { it.objectId })
        val lifetime = LIFETIME_MONO_MS.coerceAtMost(GrantLimits.MAX_LIFETIME_MONO_MS)
        val skeleton = TaskGrant(
            grantId = grantIdOf(contractHash, sessionId, epoch),
            sessionId = sessionId,
            taskId = contractHash,
            processEpoch = epoch,
            contractHash = contractHash,
            objects = objects,
            actions = actions,
            maxRiskOrdinal = maxRiskOf(contract),
            budget = GrantBudget(),
            issuedMonoMs = nowMonoMs,
            expiresMonoMs = nowMonoMs + lifetime,
            issuedWallMs = wallMs,
            // 这两个字段是「批」这一侧的事实：候选阶段还没有批准 ⇒ 空串占位，
            // 成交（[openGrant]）时才填真值——validate 会拦空串，正好挡住"没批就装"。
            approvalEventId = "",
            presentedPayloadHash = "",
        )
        val binding = ApprovalBinding(
            actionId = actionId,
            argsHash = presentedArgsOf(skeleton, objectsDigest, actionId),
            objectsDigest = objectsDigest,
        )
        return GrantOffer(skeleton, TaskGrantFacts.of(skeleton), binding) to null
    }

    /**
     * 成交：点「开工」/debug 探针走的同一个口（Q2 说"同时出 API"，就是这个）。
     *
     * 顺序是有讲究的三段，谁也换不得：
     * 1. 先把这次批准落进 208（[DecisionKind.TOOL_GRANT]），拿到那条记录的 id——
     *    `approvalEventId` 要的是**真发生过的那次批准**，编一个 id 就是把勘验骗了；
     * 2. 用真 id ＋ 卡面参数摘要装快照（[TaskGrantRuntime.install] 内部还会再校验一遍）；
     * 3. 装上了才让内存板认这张单（[TaskGrantBoard.beginOpen]）——绑定三元组与候选同一份来源。
     */
    fun openGrant(
        offer: GrantOffer,
        selection: GrantSelection,
        root: File,
        epoch: String,
        nowMonoMs: Long,
        wallMs: Long,
        log: (String) -> Unit = { },
    ): OpenResult {
        val base = offer.grant
        val chosenObjects = base.objects.filter { it.handle in selection.objects || it.objectId in selection.objects }
        val chosenActions = base.actions.filter { it in selection.actions }
        if (chosenObjects.isEmpty() || chosenActions.isEmpty()) {
            return OpenResult(false, reason = "一个都没勾＝这一单不授权免问（照旧逐次问）")
        }
        val actionId = ApprovalBindings.actionIdOf(chosenActions)
        val objectsDigest = ApprovalBindings.objectsDigestOf(chosenObjects.map { it.objectId })
        val approval = DecisionLedger.record(
            DecisionDraft(
                kind = DecisionKind.TOOL_GRANT,
                actor = DecisionActor.USER,
                reason = "开工：授权 ${chosenObjects.size} 个对象内的${chosenActions.joinToString("、") { it.cn }}",
                evidence = "task_grant:${base.grantId}",
                before = "未授权（逐次问）",
                after = "${chosenObjects.size} 个对象 · ${chosenActions.size} 类动作 · 60 分钟",
                atMs = wallMs,
                sessionId = base.sessionId,
                // 开的那张单本身可以撤销（点「撤销本单」），但撤销不删这条批准记录 ⇒ 记录本身不可回滚。
                reversible = false,
            ),
        ) ?: return OpenResult(false, reason = WireRefusal.LEDGER_NOT_ASSEMBLED.cn)
        val grant = base.copy(
            objects = chosenObjects,
            actions = chosenActions,
            approvalEventId = approval.id,
        )
        val presented = presentedArgsOf(grant, objectsDigest, actionId)
        val final = grant.copy(presentedPayloadHash = presented)
        // 绑定的三元组**由装进内存的那份快照算**，不另外拼——卡上存的与执行时重算的
        // 必须是同一个构造，否则 L26 比的是两份口径。
        val binding = bindingOf(final)
        val issues = TaskGrantRules.validate(final)
        if (issues.isNotEmpty()) {
            return OpenResult(false, reason = "快照不合格：" + issues.joinToString("、") { it.cn })
        }
        return when (val r = TaskGrantRuntime.install(final, epoch, nowMonoMs)) {
            is InstallResult.Installed -> {
                TaskGrantBoard.beginOpen(GrantOffer(final, TaskGrantFacts.of(final), binding), nowMonoMs)
                log("抱朴授权：开工（单 ${final.grantId.take(10)}，对象 ${final.objectCount} 个，账 ${approval.id}）")
                OpenResult(true, grantId = final.grantId)
            }
            is InstallResult.Refused -> OpenResult(false, reason = "${WireRefusal.INSTALL_REFUSED.cn}：${r.detail}")
        }
    }

    /** 撤销本单（Q2 的 API 侧；按三元组定位，不符就不撤）。 */
    fun revoke(
        target: ApprovalBinding,
        nowMonoMs: Long,
        log: (String) -> Unit = { },
    ): Pair<Boolean, String> {
        val granted = TaskGrantBoard.bindingOfOpened()
            ?: return false to "没有开着的单可撤"
        val allowed = ApprovalBindings.revokeAllowed(granted, target)
        TaskGrantBoard.noteRevoke(allowed.first, allowed.second)
        if (allowed.first) {
            TaskGrantRuntime.end(GrantEndReason.USER_REVOKED, nowMonoMs)
            log("抱朴授权：撤销本单（三元组相符）")
        } else {
            log("抱朴授权：撤销被拦——${allowed.second}")
        }
        return allowed
    }

    // ── 执行路径的入口（管线每次写动作问一次） ─────────────────────────────

    /**
     * 这一次动作在授权链里的读数（管线**只**按 [WiringResult.disposition] 处置，自己不再判）。
     *
     * 拿不到的事实 ⇒ [WiringDisposition.NOT_APPLICABLE]（执行路径逐字节不变），不是"放行"：
     * · 读工具（`isWrite=false`）——授权管的是"改盘"；
     * · 目标不是恰好一个文件（`move_path` 两端、无地点的动作）——窄口径，不猜；
     * · 参数里没有 `content`——写多少字节说不清，就不拿猜的字节数去比上限；
     * · 矩阵结论缺失（MCP 表外命名空间）——那一组本来就不归本地规则管。
     */
    override fun decide(
        call: ToolCall,
        targets: List<File>,
        verdict: RiskVerdict?,
        root: File,
        isWrite: Boolean,
        nowMonoMs: Long,
        epoch: String,
        // 默认值写在接口那一侧（override 不许再给）：P1 生产恒 `None`——影子预演没有调用方。
        // 留这一格是为了负例 N6——测试注入 `Rehearsed(CONSISTENT)` 后 ALLOW 必须真的免卡，
        // 否则"放行分支"就只是一句死代码（判据补强归 239，本批不自己造适配器）。
        shadow: ShadowSignal,
        log: (String) -> Unit,
    ): WiringResult {
        if (!isWrite || verdict == null) return WiringResult(WiringDisposition.NOT_APPLICABLE, note = "读／表外")
        if (targets.size != 1) {
            return WiringResult(WiringDisposition.NOT_APPLICABLE, note = "目标不是恰好一个文件（${targets.size} 个）")
        }
        if (!call.arguments.has(CONTENT_KEY)) {
            return WiringResult(WiringDisposition.NOT_APPLICABLE, note = "参数里没有可测的写入内容")
        }
        val state = TaskGrantRuntime.active(nowMonoMs) ?: return WiringResult(
            WiringDisposition.UNAUTHORIZED,
            note = "没有生效的任务级授权（${TaskGrantRuntime.current()?.endReason?.wire ?: "none"}）",
        )
        val target = targets.first()
        val objectId = runCatching { target.absoluteFile.toRelativeString(root.absoluteFile) }
            .getOrDefault(target.name).replace('\\', '/')
        val request = requestOf(call, state.grant, objectId, target, verdict, nowMonoMs, epoch, shadow)
        val binding = bindingOf(state.grant)
        // L26 先判：份都对不上，不配谈范围。
        val grantedBinding = TaskGrantBoard.bindingOfOpened()
        val bound = ApprovalBindings.executeAllowed(grantedBinding, binding)
        if (!bound.first) {
            val reading = GrantReading(
                opId = request.opId, outcome = GateOutcome.ASK_SINGLE, code = ApprovalBindings.CODE_MISMATCH,
                steps = listOf(StepReading(GrantStep.OBJECTS, null, bound.second).line()),
                bindingNote = bound.second, grantId = state.grant.grantId, atMonoMs = nowMonoMs,
            )
            TaskGrantBoard.noteReading(reading)
            TaskGrantBoard.setPending(null)
            log("抱朴授权：${reading.line()}")
            return WiringResult(WiringDisposition.UNAUTHORIZED, request = request, note = bound.second)
        }
        val decision = TaskGrantGate.evaluate(request, state)
        return dispose(request, state, binding, decision, nowMonoMs, log)
    }

    /**
     * 六步读数落板 + 按结局映射处置——**从 decide 里照搬出来的后半，一个判定字节没改**：
     * 拆它只为不新增 detekt LongMethod 条目（第二百七十六批 a 的硬墙 326 条只准降不准升，
     * 而 `decide` 是本批新函数、基线里没有它）。249 的六步照旧在前，L26 的三元组照旧在更前。
     */
    private fun dispose(
        request: ActionRequest,
        state: GrantState,
        binding: ApprovalBinding,
        decision: GateDecision,
        nowMonoMs: Long,
        log: (String) -> Unit,
    ): WiringResult {
        val reading = GrantReading(
            opId = request.opId,
            outcome = decision.outcome,
            code = decision.code,
            steps = decision.steps.map { it.line() },
            bindingNote = "三元组相符（${binding.objectsDigest}）",
            grantId = state.grant.grantId,
            atMonoMs = nowMonoMs,
        )
        TaskGrantBoard.noteReading(reading)
        log("抱朴授权：${reading.line()}")
        val disposition = when (decision.outcome) {
            GateOutcome.ALLOW -> {
                // 免卡成立：把请求交给管线，执行**成功**后由 finish() 落授权账并钉 N7 那一格。
                // 这里就先挂上；任何一次重新判定（同 opId 再走一遍）只要不 ALLOW 就清场——
                // 否则"没做成的事"会在板上一直挂着"已放行"，重试成功时补出一条假账。
                TaskGrantBoard.setPending(request)
                WiringDisposition.ALLOW
            }
            GateOutcome.REJECT -> WiringDisposition.REJECT
            GateOutcome.STOPPED -> WiringDisposition.STOPPED
            else -> WiringDisposition.UNAUTHORIZED
        }
        if (disposition != WiringDisposition.ALLOW) TaskGrantBoard.setPending(null)
        return WiringResult(
            disposition = disposition,
            request = request,
            decision = decision,
            note = if (disposition == WiringDisposition.ALLOW) {
                ""
            } else {
                decision.steps.lastOrNull()?.detail.orEmpty()
            },
        )
    }

    /**
     * 执行成功后落账（六步第⑥步的「落」；管线 `finish()` 调，与通用 `auto_act` **二选一**）。
     *
     * 「执行后的状态」按盘上**现在**的样子重算：拿授权快照那一版当"之后"就是假账——
     * 内容正是被这一格改掉的，摘要不变等于说"我没改"。
     *
     * @return 这一格的账**有没有落定**（APPENDED 或 DUPLICATE 都算落过；FAILED／没装配＝false，
     *         调用方只 log，**不回滚**已经发生的写入——208 那条「记录是留痕不是闸门」的口径）
     */
    fun recordApplied(request: ActionRequest, target: File, wallMs: Long, log: (String) -> Unit = { }): Boolean {
        val ledgerRoot = DecisionLedger.installedRoot()
        val append = if (ledgerRoot == null) {
            log("抱朴授权：这一格的账没处落（208 未装配落点）")
            DecisionAppend.FAILED
        } else {
            TaskGrantLedger.recordApplied(ledgerRoot, request, wallMs, stateAfter = objectStateOf(target))
        }
        // 内存额度与对象状态**无论账落没落都要推进**：账没落是留痕缺了一行，
        // 但写入是真发生了的——不推进就会让"用了 5 次"读成"用了 4 次"，上限判成假宽。
        TaskGrantRuntime.active(request.nowMonoMs)?.commit(
            request.opId,
            request.objectId,
            request.candidateBytes,
            objectStateOf(target),
        )
        return append != DecisionAppend.FAILED
    }

    /** 一个文件此刻的对象状态（不存在＝[ObjectState.Absent]；读不动＝摘要空串，交给六步第①步判不符）。 */
    internal fun objectStateOf(f: File): ObjectState =
        if (f.isFile) ObjectState.Present(sha256File(f), f.length()) else ObjectState.Absent

    /** 单调钟毫秒（本仓既有口径 `System.nanoTime()/1_000_000`；到期判定只认它，不认墙钟）。 */
    fun monoMs(): Long = System.nanoTime() / 1_000_000

    /**
     * 生效中这份快照的三元组（**执行前重算**用；L26 判据①「执行前重算三者，与授权时存的比」）。
     *
     * 为什么比的是快照而不是工具调用参数：任务级授权批的是**那一份授权**（几个对象、哪两类
     * 动作、限额多大、管到什么时候），不是一条命令行。调用参数有别人管——路径落在清单内是
     * 六步第①步、写字节数是第③步、"这一下是不是重复"是 opId 幂等键。
     * 反过来，把摘要算在调用参数上会出现"卡上批的是一份、执行时比的是另一份"永远对不上，
     * 那是把 L26 判成恒假——比不接还糟。
     * 与 [presentedArgsOf] 同一个构造 ⇒ 同一份快照永远同一个三元组。
     */
    internal fun bindingOf(grant: TaskGrant): ApprovalBinding {
        val actionId = ApprovalBindings.actionIdOf(grant.actions)
        val objectsDigest = ApprovalBindings.objectsDigestOf(grant.objectIds)
        return ApprovalBinding(
            actionId = actionId,
            argsHash = presentedArgsOf(grant, objectsDigest, actionId),
            objectsDigest = objectsDigest,
        )
    }

    /** 六步要的请求（所有事实在这儿一次算清；判定层因此可以继续保持零 IO）。
     *
     * `opId`＝模型的 tool-call id：重试同一张卡复用同一个 id ⇒ 六步第⑥步的幂等（`<opId>:applied` 只落一条）才有意义。 */
    internal fun requestOf(
        call: ToolCall,
        grant: TaskGrant,
        objectId: String,
        target: File,
        verdict: RiskVerdict,
        nowMonoMs: Long,
        epoch: String,
        // 生产恒 `None`——管线那一格不传，走这个默认值；测试注入 `Rehearsed(CONSISTENT)`
        // 只为负例 N6 证明"放行"分支是活的（影子适配器本批不做，判据补强归 239）。
        shadow: ShadowSignal = ShadowSignal.None,
    ): ActionRequest {
        val exists = target.isFile
        val bytes = call.arguments.optString(CONTENT_KEY).toByteArray(Charsets.UTF_8).size.toLong()
        return ActionRequest(
            opId = call.id,
            engineRequestId = call.id,
            sessionId = grant.sessionId,
            taskId = grant.taskId,
            objectId = objectId,
            action = if (exists) GrantActionType.EDIT_TEXT else GrantActionType.CREATE_TEXT,
            candidateBytes = bytes,
            risk = verdict.risk ?: RiskClass.R2,
            mustAskIndependently = mustAskIndependentlyOf(verdict),
            boundary = boundaryFactsOf(verdict),
            // P1 不开自动档的**唯一原因**就写在这一格：影子预演在生产里没有调用方（判据归 239 补强）。
            shadow = shadow,
            before = if (exists) ObjectState.Present(sha256File(target), target.length()) else ObjectState.Absent,
            nowMonoMs = nowMonoMs,
            processEpoch = epoch,
        )
    }

    /** 契约里最高的一档，压到授权能覆盖的上限（R2）——预估超 R2 的计划本来就该逐次问。 */
    internal fun maxRiskOf(contract: PlanContract): Int =
        (contract.steps.maxOfOrNull { it.risk.ordinal } ?: 0).coerceAtMost(GrantLimits.MAX_RISK_ORDINAL)

    /** 单号：契约摘要＋会话＋进程代号定出来的稳定值（同一轮重开同一张卡 ⇒ 同一个号）。 */
    internal fun grantIdOf(contractHash: String, sessionId: Long, epoch: String): String =
        "g" + sha256Hex("$contractHash|$sessionId|$epoch").take(12)

    /** 文件内容摘要（授权快照与执行前状态都用它；读不动 ⇒ 当"不存在"处理，不猜一版）。 */
    private fun sha256File(f: File): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }.getOrDefault("")
}
