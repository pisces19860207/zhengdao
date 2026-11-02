// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-11-02-第三百七十四批-流程编排雏形 §一（三个对象＋最小闸）＋ §二（不做）＋ §三 判据 ①–④。
//
// 本文件是**判定层**：零 Android / 零 IO / 零时钟（时刻一律由调用方传入），
// 与 `PlanContract.kt`（242）/ `PlanPledge.kt`（312）同一个房型，JVM 直测。
//
// 治的是**流程没有对象**：军令状（312）、缺口项（313）、自决单、裁定、免打扰都已经有形状，
// 只有「派活那张单」「干活回过来的三样」「干到哪儿了」还停在 markdown 里。
// 运行状态面板八项里第 3 项（哪些任务已停滞）缺数据源，366 要判停滞、要判谎报，
// 先得有能挂账的行——本批就是把这三行形状定下来，一列不加一列不减。
//
// 三样东西的分工（字段照派工单点名）：
// 1. **派工单 DispatchOrder**：id／title／criteria／lease／budget／base／assignedTo／at。
//    闸：criteria 空 ⇒ 拒（无判据不派活）· lease 空 ⇒ 拒（没租约不许开工）。
// 2. **回传 ReturnItem**：kind ∈ {PROBLEM, NEED_DECISION, FYI}／text／evidence。
//    闸：PROBLEM 与 NEED_DECISION 必须带 evidence（空 ⇒ 拒）；FYI 可不带。
// 3. **进度自报 ProgressNote**：orderId／done／total／etaMinutes／blockers。
//    闸：done > total ⇒ 拒 · 连续两份比上一份倒退 ⇒ 拒 · 连续两次同进度 ⇒ StallState.STALLED。
//
// 四条边界（判据②：与军令状/缺口项不重复造，引用即可）：
// - **判据不另造对象**：criteria 就是 `List<String>`，形状照 342 的 `TaskGoal.criteria`；
//   每条一句可验的话，不建 Criterion 类、不建判据表（判据表是 310/364 那套纪元账的活）。
// - **证据不另造对象**：evidence 就是 `List<String>`，形状与「没证据不进账」的口径都照 313 的
//   `GapItem.evidence`；本批不把回传转成 GapItem（转不转、转成哪个 kind 是消费方的判断）。
// - **军令状不在单里**：立状（312 的 `PledgeSnapshot`）是承接方拿到单之后**回**的另一行账，
//   挂在本单 id 下面；DispatchOrder 不加 pledge 字段（加了就是两个真源）。
// - **租约不在这里判相交**：lease 是在飞表那一格的抄本，相交判定归 `tools/check-inflight.py`
//   （派工六步②「冲突预检」）。本层只判「有没有写租约」，不判「租约撞了谁」。
//
// §二 的三条「不做」原样不做：✗ 面板/UI（UI 冻结）✗ 自动派活（人是唯一派活者）✗ 消息队列。
// 因此本文件**不存状态、不落盘、不排程**：进度比对要的「上一份读数」由调用方带进来，
// 消费顺序是 `progressRejection` ⇒ `progressRegressionRejection` ⇒ `stallOf`
// （被前两道拒掉的读数不进比对）——这就是 366 对接的口子（判据③）。
package com.example.zhengdao.baopu.plan

/**
 * ① 派工单：中书/吏部拟的那张单。八列照派工单点名，**一列不加一列不减**
 * （多一列就是第二份真源，少一列单子读不全）。
 *
 * @param id 单号。批次号由行政中心统一分配并写死在派工单里（在飞表「编号归口」）。
 * @param title 一句话说这单要办成什么（不是目标全文，全文在军令状里）。
 * @param criteria 判据——「怎么算做到了」，每条一句可机验的话。空 ⇒ 拒（无判据不派活）。
 * @param lease 租约：本单要动的文件路径，**窄口径**（只列文件、不占目录）。空 ⇒ 拒（没租约不许开工）。
 * @param budget 预估时间上界，单位**分钟**（与 [ProgressNote.etaMinutes] 同单位；
 *   协议-分片与接管 §一「预估」那条写的是「token/时间上界」，本机 token 查不到 ⇒ 只记时间）。
 *   null＝没预估——本批对它不设闸（§一只把「超预算」记进偏差账，不拦开工）。
 * @param base 开工时的基线 commit（在飞表「基线」那格）。
 * @param assignedTo 承接方，按 **agent** 记不是按会话记（派工六步：agent 有共享记忆，会话是一次性容器）。
 * @param at 派发时刻（毫秒，与 312/242 的 `atMs` 同口径）。本层不读时钟，一律由调用方传入。
 */
internal data class DispatchOrder(
    val id: String,
    val title: String,
    val criteria: List<String>,
    val lease: List<String>,
    val budget: Int?,
    val base: String,
    val assignedTo: String,
    val at: Long,
)

/**
 * ② 回传的三类（闭集，派工单点名；没有第四类——塞不进这三类的回传不配占人的注意力）。
 * 账上写枚举名（`PROBLEM`／`NEED_DECISION`／`FYI`），与派工单逐字一致。
 */
internal enum class ReturnKind(val cn: String) {
    PROBLEM("报问题"),
    NEED_DECISION("要人拍板"),
    FYI("只知会"),
    ;

    /** 前两类要人动手、必须带据；FYI 只是知会，可以不带（派工单 §一 那行的原文口径）。 */
    fun requiresEvidence(): Boolean = this != FYI
}

/**
 * ② 回传一件：承接方在片中／收工时报回来的三样之一。
 *
 * @param kind 三类之一。
 * @param text 一句话（协议-进度自报 §二「固定一行，不许写小作文」同口径；本批不给它设长度闸）。
 * @param evidence 证据（账行／路径＋hash／工具名＋参数摘要）。
 *   PROBLEM／NEED_DECISION 空 ⇒ 拒；FYI 可以空。带了就不许塞空行——空行是假据不是无据。
 */
internal data class ReturnItem(
    val kind: ReturnKind,
    val text: String,
    val evidence: List<String>,
)

/**
 * ③ 进度自报一条：承接方按节奏报的那一行（每完成一片一次＋定时心跳）。
 *
 * @param orderId 报的是哪张单。**跨单号不比对**——比对只在同一张单的连续两份读数之间做。
 * @param done 已完成的片数。只算**独立可验收的片**（协议-分片与接管 §一：不是「做了一半」）。
 * @param total 本单切成的片数。
 * @param etaMinutes 预计还要几分钟（与 DispatchOrder.budget 同单位）；null＝没估（§三「预计会延期」
 *   要的是「在超时前说」，估没估都得报，所以不设闸）。
 * @param blockers 卡住的东西，一行一个；空＝没卡。§三 那条「卡住不许沉默」要靠人读这个字段，
 *   本批不把「没报阻塞」判成违规（报没报是节奏问题，归 366 的心跳超时）。
 */
internal data class ProgressNote(
    val orderId: String,
    val done: Int,
    val total: Int,
    val etaMinutes: Int?,
    val blockers: List<String>,
)

/**
 * 连续两份读数比出来的两态（闭集，判据③交给 366 的就是它；没有第三态——
 * 「看不出来」不是一种状态，看不出来就是没比对成立，返回 [ON_TRACK] 并由调用方自己决定要不要问）。
 */
internal enum class StallState(val cn: String) {
    ON_TRACK("有进展"),
    STALLED("停滞"),
}

/** 366 要的是一位「停没停」，不该自己重写相等判断（判据③：接口对齐）。 */
internal val StallState.stalled: Boolean get() = this == StallState.STALLED

/**
 * 最小闸：派工单 §一「最小闸（纯规则）」那三行逐字落地，一条不加。
 *
 * 闸一律返回**拒因**（null＝放行），不用构造期 `require`：
 * 派工单被拒时人要看得见被拒的那份原文（照 `law/EmergencyGate.kt` 的 `admit(): String?` 房型）；
 * 313 的 GapItem 用 require 是因为它一进账就得成形，本层的对象先进的是「拟单」这一步。
 */
internal object ProcessObjectRules {

    /**
     * 派工单闸：没判据不许派活，没租约不许开工（§一 点名那两条）。
     * 空集**和**「有行但行里是空白」同一个拒法——口径照 313 的 `GapItem`（`isNotEmpty() && none { isBlank() }`）。
     */
    fun dispatchRejection(order: DispatchOrder): String? = when {
        order.criteria.isEmpty() -> "派工单 ${order.id} 没有判据——无判据不派活"
        order.criteria.any { it.isBlank() } -> "派工单 ${order.id} 的判据里有空行（等于没判据）"
        order.lease.isEmpty() -> "派工单 ${order.id} 没有租约——没租约不许开工"
        order.lease.any { it.isBlank() } -> "派工单 ${order.id} 的租约里有空路径（撞不了也查不了）"
        else -> null
    }

    /** 回传闸：报问题／要人拍板的必须带据，只知会的可以不带。 */
    fun returnRejection(item: ReturnItem): String? = when {
        item.evidence.isNotEmpty() && item.evidence.any { it.isBlank() } ->
            "回传「${item.kind.name}」的证据里有空行（空行不是证据）"
        item.kind.requiresEvidence() && item.evidence.isEmpty() ->
            "回传「${item.kind.name}」没带证据——没有证据的结论不进账（L14 同款）"
        else -> null
    }

    /** 进度闸（单份读数）：做完的比总数还多＝自相矛盾，拒。 */
    fun progressRejection(note: ProgressNote): String? =
        if (note.done > note.total) {
            "进度 ${note.done}/${note.total} 自相矛盾（做完的比本单切出的片还多）"
        } else {
            null
        }

    /**
     * 进度闸（连续两份读数）：片数倒退 ⇒ 拒。
     * 366 把「报完成但片不齐」判成 INCOMPLETE（谎报），倒退是同一条判据的另一半——
     * 片不会自己消失，要么读数错、要么有人在改口径却不重签。
     * 跨单号不比对（返回 null）：两张单之间没有「倒退」可言。
     */
    fun progressRegressionRejection(prev: ProgressNote, current: ProgressNote): String? = when {
        prev.orderId != current.orderId -> null
        current.done < prev.done ->
            "单 ${current.orderId} 进度从 ${prev.done}/${prev.total} 退到 ${current.done}/${current.total}（片不会自己消失）"
        else -> null
    }

    /**
     * 停滞标记（判据③：366 消费的就是这一个读数）。
     *
     * 「上一份读数」由调用方带进来（本层不存状态、不读时钟）；首份读数（prev＝null）不判停滞，
     * 跨单号不比对。§一 那句「连续两次同进度」按**做完的片数**判：
     * 总数变了而片没多出来＝仍然没进展，把它算成「有进展」就是给拖延留口子。
     */
    fun stallOf(prev: ProgressNote?, current: ProgressNote): StallState =
        if (prev != null && prev.orderId == current.orderId && current.done == prev.done) {
            StallState.STALLED
        } else {
            StallState.ON_TRACK
        }
}
