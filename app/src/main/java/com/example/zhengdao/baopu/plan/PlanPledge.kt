// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-31-第三百一十二批-计划军令状 §做什么 1–5 ＋ §判据 ①–⑤ ＋
// §负例（各红）＋ 追加「右相回灌」①–⑤（含追加判据⑥⑦）。
//
// 本文件是**判定层**：零 Android / 零 IO / 零时钟（atMs 一律由调用方传入），
// 与 `PlanContract.kt`（242）同一个房型，JVM 直测。
//
// 治的是**目标缩水**：模型最隐蔽的漂移不是做错，是偷偷少做几项然后宣布完成。
// 五件事的分工（判据编号照派工单）：
// 1. **立状**：长程任务（≥5 步或跨会话，判据④的线）开工时把计划卡冻结成快照
//    （项数＋每项一句话＋验收方式），开工卡一次问清、人点过头之后写账；
// 2. **结项**：每项只有两种结局——`DONE`（附证据：文件/输出/账行）或
//    `ABANDONED`（必填原因：BLOCKED／NEEDS_HUMAN／UNSUPPORTED，判据⑤空原因拒）；
// 3. **收口**：逐项对快照，有一项没有结局 ⇒ 任务判未完成（判据②），缺口进「未办成单」；
// 4. **重签**：冻结后改计划必须显式（删哪项/加哪项/为什么＋走人批或自决单并标
//    「计划变更」），旧快照原样留在账上（判据③：两个版本都查得到）；
// 5. **禁止**：结项时「就地改计划」（把没做的删掉再宣布完成）——判据①对冻结后
//    一切未走重签的结构变更一律拒，收口只认快照，删不掉快照里的项。
//
// 追加段（右相回灌）：①回灌是结构化差距清单（哪项｜缺什么｜最小满足条件），只报
// 事实与标准，不带判词；②同一项最多回灌 2 次，第 3 次未过转「交人」（判据⑥）；
// ③回灌未结不许靠重签把缺口变完成（自决单无权删缺口项，人批除外）；④只报缺口
// 不教做法；⑦回灌文案不出现价值判断词（源级门禁在测试里扫本包源码，判据⑦）。
//
// 两条与 242（计划即契约）的边界：
// - 军令状管「计划表本身不许缩水」，契约管「动作不许越界」——两层并行，互不替岗；
// - 没立状（短任务／人拒绝）⇒ 本层对一切恒「不阻拦」，今天的执行路径逐字节不变
//   （军令状是封底不是门槛：没立状就没有结项门，也不编造读数）。
//
// 接线（后续批次）：三个参数键挂在 `update_plan` 上——`pledge`（立状）、
// `closeout`（结项申报）、`resign`（重签理由）；开工卡在 update_plan 之后弹
// （照 242 批准卡的房型），收口在收尾路径调（照契约对账的房型）。
package com.example.zhengdao.baopu.plan

import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.PlanRules.PlanItem
import com.example.zhengdao.baopu.scout.UnfinishedItem
import com.example.zhengdao.baopu.scout.UnfinishedOutcome
import com.example.zhengdao.baopu.scout.UnfinishedReason
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal object PlanPledgeRules {

    /** `update_plan` 参数里军令状三件的键名（接线批次把它们挂进 schema）。 */
    const val ARG_PLEDGE = "pledge"
    const val ARG_CLOSEOUT = "closeout"
    const val ARG_RESIGN = "resign"

    /** 账行的事件键（`计划军令状.jsonl` 的 `event` 字段；稳定值，改了老账读不出来）。 */
    const val EVENT_FREEZE = "freeze"
    const val EVENT_RESIGN = "resign"
    const val EVENT_SETTLE = "settle"

    /** 长程任务的步数线：≥5 步或跨会话 ⇒ 开工要立状（判据④：短任务不立状）。 */
    const val LONG_TASK_STEPS = 5

    /** 同一项最多回灌几次（追加②：第 3 次未过 ⇒ 交人）。 */
    const val MAX_FEEDBACK_ROUNDS = 2

    /** 交人的线：同一项第 [MAX_FEEDBACK_ROUNDS] 次回灌之后仍未过（判据⑥）。 */
    const val ESCALATE_FAILURES = MAX_FEEDBACK_ROUNDS + 1

    /** 验收方式一行字数上限（与计划条目同宽，卡面一行放得下）。 */
    const val MAX_ACCEPT_CHARS = 60

    /** 重签理由字数上限（卡面与账行都要放得下）。 */
    const val MAX_WHY_CHARS = 120

    /** 结局的两值闭集（派工单 §2：每项只有 DONE 或 ABANDONED）。 */
    enum class EndKind(val wire: String) {
        DONE("done"),
        ABANDONED("abandoned"),
        ;

        companion object {
            fun ofWire(raw: String): EndKind? = entries.firstOrNull { it.wire == raw.trim().lowercase() }
        }
    }

    /** ABANDONED 的原因闭集（派工单点名三值，一个字不许改）。 */
    enum class AbandonReason(val wire: String, val cn: String) {
        BLOCKED("blocked", "受阻"),
        NEEDS_HUMAN("needs_human", "要人来"),
        UNSUPPORTED("unsupported", "做不了"),
        ;

        companion object {
            fun ofWire(raw: String): AbandonReason? =
                entries.firstOrNull { it.wire == raw.trim().lowercase() }
        }
    }

    /** 快照里的一项：一句话（计划条目原文）＋ 验收方式（这项做完凭什么算做完）。 */
    data class PledgeEntry(val text: String, val accept: String)

    /**
     * 冻结的计划快照（军令状本体）。
     *
     * [id] 由内容＋版次哈希而来：同一版快照永远同一个 id（「同版不重复问」与
     * 「未办成单点回原状」都按它寻址）；[version] 从 1 起，重签一次加一。
     */
    data class PledgeSnapshot(
        val id: String,
        val version: Int,
        val goal: String,
        val entries: List<PledgeEntry>,
        val atMs: Long,
        val sessionId: Long,
    ) {
        val texts: List<String> get() = entries.map { it.text }
    }

    /** 一条结项申报（按条目原文寻址；[text] 与快照条目一致才对得上账）。 */
    data class EndingDeclaration(
        val text: String,
        val end: EndKind,
        /** DONE 的证据：文件路径／工具输出／账行引用；空 ⇒ 收口时按「缺证据」记缺口。 */
        val evidence: String = "",
        /** ABANDONED 的原因（判据⑤：空原因在解析层就拒）。 */
        val abandon: AbandonReason? = null,
        val detail: String = "",
    )

    /** 结项申报落成的结局。 */
    sealed interface Ending {
        data class Done(val evidence: String) : Ending
        data class Abandoned(val reason: AbandonReason, val detail: String) : Ending
    }

    /** 收口后的一项（有结局的那一半）。 */
    data class Settled(val text: String, val ending: Ending)

    /** 收口的一条缺口（缺什么＋最小满足条件；右相回灌只报这两件，不教做法）。 */
    data class SettleGap(
        val text: String,
        val missing: String,
        val minimal: String,
        /** 第几次收口没过（1 起；> [MAX_FEEDBACK_ROUNDS] ⇒ 已转交人，不再回灌）。 */
        val failures: Int,
    ) {
        val escalated: Boolean get() = failures > MAX_FEEDBACK_ROUNDS
    }

    /** 收口读数（结项门禁的产出；[complete] 为 false ⇒ 任务记未完成）。 */
    data class SettleVerdict(
        val snapshotId: String,
        val version: Int,
        val total: Int,
        val ended: List<Settled>,
        val gaps: List<SettleGap>,
    ) {
        val complete: Boolean get() = gaps.isEmpty()
        val escalatedTexts: List<String> get() = gaps.filter { it.escalated }.map { it.text }
    }

    // ── 立状（判据④的线在这里，不在卡上）────────────────────────────────────

    /** 长程任务：≥5 步或跨会话（派工单 §1 的「或」是字面或）。 */
    fun isLongTask(itemCount: Int, crossSession: Boolean): Boolean =
        itemCount >= LONG_TASK_STEPS || crossSession

    /** [parsePledge] 的结局。 */
    sealed interface PledgeParse {
        /** 长程任务：该弹开工卡（accepts 与计划条目按位对齐，冻结等人点头）。 */
        data class Need(val accepts: List<String>, val notices: List<String>) : PledgeParse

        /** 短任务／已立状：不弹卡（判据④）。带了的 pledge 参数以 notice 说明忽略。 */
        data class Skip(val notices: List<String>) : PledgeParse

        /** 长程任务缺验收方式等写法不对 ⇒ 错误原文回灌（不静默降级）。 */
        data class Error(val message: String) : PledgeParse
    }

    /**
     * 从 `update_plan` 参数里读立状。
     *
     * 为什么长程任务缺 `pledge` 是 [PledgeParse.Error] 而不是 [PledgeParse.Skip]：
     * 长程判定是**自动的**（≥5 步就够，不看模型意愿），静默当成没立状 ＝
     * 模型靠写长计划就能绕开结项门——那正是本批要拦的。短任务才 Skip。
     *
     * @param itemCount 本版计划条数（验收方式按位对齐，少一条都立不起来）
     * @param crossSession 跨会话续跑（接线给；续跑且军令状还在 ⇒ 走 [PledgeParse.Skip]）
     * @param pledged 军令状已生效（本版是进度更新，一次问清过就不再问）
     */
    fun parsePledge(
        args: JSONObject,
        itemCount: Int,
        crossSession: Boolean,
        pledged: Boolean = false,
    ): PledgeParse {
        val raw = args.opt(ARG_PLEDGE)
        if (!isLongTask(itemCount, crossSession)) {
            if (raw == null || raw == JSONObject.NULL) return PledgeParse.Skip(emptyList())
            return PledgeParse.Skip(
                listOf(
                    "计划不足 $LONG_TASK_STEPS 步且同一会话：短任务不立状，pledge 参数已忽略。",
                ),
            )
        }
        if (pledged) return PledgeParse.Skip(emptyList())
        val obj = raw as? JSONObject
            ?: return PledgeParse.Error(
                "长程任务（≥$LONG_TASK_STEPS 步或跨会话）开工要立状：" +
                    "参数「$ARG_PLEDGE」要 {\"acceptance\": [每项一条验收方式]}，与计划条目按位对齐。",
            )
        val accepts = stringList(obj.opt("acceptance"))
        if (accepts.size < itemCount) {
            return PledgeParse.Error(
                "立状缺验收方式：计划 $itemCount 项只给了 ${accepts.size} 条 acceptance，" +
                    "每项都要一条（这项做完凭什么算做完，卡上要一次问清）。",
            )
        }
        val notices = mutableListOf<String>()
        if (accepts.size > itemCount) {
            notices += "acceptance ${accepts.size} 条 > 计划 $itemCount 条，多出来的按位置丢掉。"
        }
        return PledgeParse.Need(accepts.take(itemCount).map { it.take(MAX_ACCEPT_CHARS) }, notices)
    }

    /** 组快照（冻结与重签共用；accepts 与 items 按位对齐，缺位以条目原文兜底）。 */
    fun snapshotOf(
        items: List<PlanItem>,
        accepts: List<String>,
        version: Int,
        goal: String,
        atMs: Long,
        sessionId: Long,
    ): PledgeSnapshot {
        val entries = items.mapIndexed { i, item -> PledgeEntry(item.text, accepts.getOrElse(i) { item.text }) }
        return PledgeSnapshot(idOf(goal, entries, version), version, goal, entries, atMs, sessionId)
    }

    /** 快照 id：内容＋版次的 sha 前 8 位（同版同 id ⇒ 重复冻结天然幂等）。 */
    private fun idOf(goal: String, entries: List<PledgeEntry>, version: Int): String {
        val payload = buildString {
            append(version).append('|').append(goal).append('|')
            entries.forEach { append(it.text).append('|').append(it.accept).append('|') }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(8)
    }

    // ── 结项申报（判据⑤的拒在这里）──────────────────────────────────────────

    /** [parseCloseout] 的结局。 */
    sealed interface CloseoutParse {
        data class Ok(val declarations: List<EndingDeclaration>, val notices: List<String>) : CloseoutParse
        data class Absent(val reason: String) : CloseoutParse
        data class Error(val message: String) : CloseoutParse
    }

    /**
     * 读结项申报。
     *
     * 判据⑤为什么在解析层就拒，而 DONE 缺证据不在这里拒：
     * 原因是弃单**诚实性**的下半张脸——没有原因的弃单没有任何信息，等于把「做不了」
     * 变成一句空话（半张申报当作没写，模型无从改起）；证据是完成**可验证性**的
     * 上半张脸——缺了它申报仍然成立，只是收口时过不了门（按缺口回灌，模型知道补什么）。
     */
    fun parseCloseout(args: JSONObject): CloseoutParse {
        val raw = args.opt(ARG_CLOSEOUT)
        if (raw == null || raw == JSONObject.NULL) {
            return CloseoutParse.Absent("这次没有申报结项：没申报的条目在收口时按「没有结局」记。")
        }
        val obj = raw as? JSONObject
            ?: return CloseoutParse.Error("参数「$ARG_CLOSEOUT」不是对象：要 {\"items\": [{text, end, …}]}。")
        val arr = obj.optJSONArray("items")
        if (arr == null || arr.length() == 0) {
            return CloseoutParse.Error(
                "closeout.items 是空的：每项都要申报结局（done 带证据，或 abandoned 带原因）。",
            )
        }
        val notices = mutableListOf<String>()
        val seen = LinkedHashMap<String, EndingDeclaration>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
                ?: return CloseoutParse.Error("closeout 第 ${i + 1} 条不是对象：要 {text, end, evidence?/reason?}。")
            val text = o.optString("text").trim()
            if (text.isEmpty()) {
                return CloseoutParse.Error("closeout 第 ${i + 1} 条缺「text」：按计划条目原文申报。")
            }
            val end = EndKind.ofWire(o.optString("end"))
                ?: return CloseoutParse.Error(
                    "closeout 第 ${i + 1} 条 end「${o.optString("end")}」只认 done / abandoned。",
                )
            val reason = AbandonReason.ofWire(o.optString("reason"))
            if (end == EndKind.ABANDONED && reason == null) {
                return CloseoutParse.Error(
                    "closeout 第 ${i + 1} 条 ABANDONED 缺原因：必填 blocked / needs_human / " +
                        "unsupported 之一（空原因的弃单不收——判据⑤）。",
                )
            }
            if (seen.containsKey(text)) notices += "「$text」重复申报，按最后一条记。"
            seen[text] = EndingDeclaration(
                text = text,
                end = end,
                evidence = o.optString("evidence").trim(),
                abandon = reason,
                detail = o.optString("detail").trim().take(MAX_ACCEPT_CHARS),
            )
        }
        return CloseoutParse.Ok(seen.values.toList(), notices)
    }

    /** 申报 ⇒ 有效结局；DONE 缺证据不算结局（派工单 §2「附证据」），收口按缺口报。 */
    fun validEnding(d: EndingDeclaration?): Ending? = when {
        d == null -> null
        d.end == EndKind.DONE && d.evidence.isNotBlank() -> Ending.Done(d.evidence)
        d.end == EndKind.ABANDONED && d.abandon != null -> Ending.Abandoned(d.abandon, d.detail)
        else -> null
    }

    // ── 收口（判据②的落点；快照是唯一底册，删项改不了它）────────────────────

    /**
     * 收口对账：逐项对快照，两项都有结局才算任务完成。
     *
     * @param declarations 模型申报过的结局（[parseCloseout] 攒下来的；可分多次给）
     * @param doneTexts 计划卡上已打勾的条目（只用来把「没申报」的缺口说得准一点：
     *   打了勾没申报 ＝ 缺申报；没打勾 ＝ 没有结局。**打勾本身不构成结局**）
     * @param failures 各项此前收口未过的次数（追加②的轮数；由板子记着传进来）
     */
    fun settle(
        snapshot: PledgeSnapshot,
        declarations: List<EndingDeclaration>,
        doneTexts: Set<String> = emptySet(),
        failures: Map<String, Int> = emptyMap(),
    ): SettleVerdict {
        val byText = declarations.associateBy { it.text }
        val ended = mutableListOf<Settled>()
        val gaps = mutableListOf<SettleGap>()
        for (entry in snapshot.entries) {
            val declared = byText[entry.text]
            val ending = validEnding(declared)
            if (ending != null) {
                ended += Settled(entry.text, ending)
            } else {
                val (missing, minimal) = gapOf(declared, entry.text, doneTexts)
                gaps += SettleGap(entry.text, missing, minimal, (failures[entry.text] ?: 0) + 1)
            }
        }
        return SettleVerdict(snapshot.id, snapshot.version, snapshot.entries.size, ended, gaps)
    }

    /** 缺口的两句话（缺什么｜最小满足条件）；「最小满足」说的是标准，不是做法。 */
    private fun gapOf(d: EndingDeclaration?, text: String, doneTexts: Set<String>): Pair<String, String> = when {
        d == null && text in doneTexts ->
            "打了勾但没申报结项" to "申报 closeout：end=done 并附一条证据（文件／输出／账行）"
        d == null ->
            "没有结局" to "end=done 带证据，或 end=abandoned 带原因（blocked/needs_human/unsupported）"
        d.end == EndKind.DONE -> "缺证据" to "附一条证据：文件／输出／账行"
        else -> "弃单缺原因" to "blocked / needs_human / unsupported 三选一"
    }

    /**
     * 右相回灌（追加①）：结构化差距清单——哪项｜缺什么｜最小满足条件。
     *
     * 只报事实与标准，不带判词、不带褒贬（追加判据⑦由 [hasValueJudgment] 与
     * 测试里的源级门禁双面把着）；完结 ⇒ null（不多说一个字）。
     */
    fun gapFeedback(verdict: SettleVerdict): String? {
        if (verdict.complete) return null
        val lines = buildList {
            verdict.gaps.filter { !it.escalated }.forEach {
                add("「${it.text}」缺${it.missing}；最小满足：${it.minimal}（第 ${it.failures} 次未过）")
            }
            verdict.gaps.filter { it.escalated }.forEach {
                add("「${it.text}」第 ${it.failures} 次未过，转交人")
            }
        }
        return "【结项未收口】${lines.joinToString("；")}。"
    }

    /**
     * 收口读数 ⇒ 「未办成单」的行（负例⑤的落点：缺口与 ABANDONED 都必须落单，
     * 不能只留在军令状的账里；DONE 的不成行）。
     *
     * 原因闭集是军令状自己的三值，不硬塞进斥候那套语义——统一记 OTHER，
     * 事实进 `reasonDetail`，`reasonRef` 点回原状（`pledge:<id>`，后悔权从原状进）。
     */
    fun unfinishedRows(verdict: SettleVerdict, atMs: Long): List<UnfinishedItem> {
        val rows = mutableListOf<UnfinishedItem>()
        verdict.ended.filter { it.ending is Ending.Abandoned }.forEach { s ->
            val a = s.ending as Ending.Abandoned
            rows += UnfinishedItem(
                intent = s.text,
                outcome = UnfinishedOutcome.NOT_DONE,
                reason = UnfinishedReason.OTHER,
                reasonDetail = "军令状弃单（${a.reason.cn}）" + a.detail.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty(),
                reasonRef = "pledge:${verdict.snapshotId}",
                fixable = a.reason != AbandonReason.UNSUPPORTED,
                fixHint = when (a.reason) {
                    AbandonReason.BLOCKED -> "阻塞解除后让它接着办这件事"
                    AbandonReason.NEEDS_HUMAN -> "这件事要人来办：${s.text}"
                    else -> ""
                },
                atMs = atMs,
            )
        }
        verdict.gaps.forEach { g ->
            rows += UnfinishedItem(
                intent = g.text,
                outcome = UnfinishedOutcome.NOT_DONE,
                reason = UnfinishedReason.OTHER,
                reasonDetail = "军令状结项：${g.missing}（第 ${g.failures} 次）" +
                    if (g.escalated) "，已转交人" else "",
                reasonRef = "pledge:${verdict.snapshotId}",
                fixable = true,
                fixHint = if (g.escalated) {
                    "这件事等人收场：亲手做完，或拍板弃单"
                } else {
                    "做完它，或按实情申报 abandoned 并写明原因"
                },
                atMs = atMs,
            )
        }
        return rows
    }

    // ── 重签（判据①③与追加③的落点）────────────────────────────────────────

    /** 重签的通道（追加④：走人批或自决单；自决单要留理由，回放按它认账）。 */
    enum class ResignChannel(val wire: String) {
        HUMAN("human"),
        SELF("self"),
    }

    /** [parseResign] 的结局。 */
    sealed interface ResignParse {
        /** 没带重签参数（配合 [EditGuard] 判「这次改动根本不许发生」）。 */
        data object Absent : ResignParse

        data class Ok(val reason: String) : ResignParse

        data class Error(val message: String) : ResignParse
    }

    fun parseResign(args: JSONObject): ResignParse {
        val raw = args.opt(ARG_RESIGN)
        if (raw == null || raw == JSONObject.NULL) return ResignParse.Absent
        val obj = raw as? JSONObject
            ?: return ResignParse.Error(
                "参数「$ARG_RESIGN」不是对象：要 {\"reason\": \"删哪项、加哪项、为什么\"}。",
            )
        val reason = obj.optString("reason").trim()
        if (reason.isEmpty()) {
            return ResignParse.Error(
                "重签缺「reason」：删哪项、加哪项、为什么，一样都不能少（负例：重签不写原因）。",
            )
        }
        return ResignParse.Ok(reason.take(MAX_WHY_CHARS))
    }

    /** [guardFrozenEdit] 的结论。 */
    sealed interface EditGuard {
        /** 不阻拦：没立状／只打勾推进（结构没变）——本层无话可说，今天路径逐字节不变。 */
        data object Pass : EditGuard

        data class Reject(val message: String) : EditGuard
    }

    /**
     * 冻结后的计划改动闸（判据①；接线在 update_plan 落库**之前**调）。
     *
     * 判定顺序（先写法、后批准、再标准）：
     * 1. 没立状／只打勾 ⇒ [EditGuard.Pass]（军令状缺席时本层沉默）；
     * 2. 重签理由没写好 ⇒ 拒（负例「重签不写原因」——给了人批也白给，理由是显式性的下半张脸）；
     * 3. 没拿到人批／自决单 ⇒ 拒（判据①：冻结后改计划必须走重签的批准）；
     * 4. 自决单删回灌期里的项 ⇒ 拒（追加③「不许改标准」：把缺口变完成的路堵死；
     *    人批的删项是人的权利，卡面上删了什么她看得见）。
     *
     * @param added/removed 这一版相对快照的文字增删（接线按原文比，只打勾 ⇒ 两表皆空）
     * @param channel null ＝ 这次改动还没拿到批准
     * @param openFeedback 还在回灌期里的项（收口没过且至今无有效结局；板子给）
     */
    fun guardFrozenEdit(
        snapshot: PledgeSnapshot?,
        added: List<String>,
        removed: List<String>,
        resign: ResignParse,
        channel: ResignChannel?,
        openFeedback: Set<String>,
    ): EditGuard {
        snapshot ?: return EditGuard.Pass
        if (added.isEmpty() && removed.isEmpty()) return EditGuard.Pass
        when (resign) {
            is ResignParse.Error -> return EditGuard.Reject(resign.message)
            ResignParse.Absent -> return EditGuard.Reject(
                "计划已立状冻结（第 ${snapshot.version} 版）：删项/加项必须显式重签——" +
                    "带「$ARG_RESIGN」参数写明删哪项、加哪项、为什么，经人批或自决单后才生效；" +
                    "旧快照会原样留在账上。不要把没做的项悄悄删掉再宣布完成。",
            )
            is ResignParse.Ok -> Unit
        }
        if (channel == null) {
            return EditGuard.Reject(
                "重签还没获批（人没点头，也没有自决单）：这次改动不生效，计划保持第 " +
                    "${snapshot.version} 版。重签走计划变更确认卡（人批），或按自治口径落自决单。",
            )
        }
        val shrinking = removed.filter { it in openFeedback }
        if (channel == ResignChannel.SELF && shrinking.isNotEmpty()) {
            return EditGuard.Reject(
                "回灌未结，不许改标准：「${shrinking.joinToString("、")}」还在结项缺口里，" +
                    "自决单无权把它们从计划里删掉。把它们做成（done＋证据）或如实弃单（abandoned＋原因）；" +
                    "确要删项，走计划变更确认卡让人来批。",
            )
        }
        return EditGuard.Pass
    }

    // ── 账：`计划军令状.jsonl` 的行（键序固定 ⇒ 写后读回逐字一致）─────────────
    //
    // 只追加：冻结一行、重签一行（旧行原样留着 ⇒ 判据③「两个版本都在账上」）、
    // 收口一行（DONE 的证据随之落账——证据形态就是「文件/输出/账行」的引用）。

    /** 冻结／重签行（重签多带 added/removed/why/channel ＋「计划变更」标记）。 */
    fun encodeSnapshot(
        s: PledgeSnapshot,
        event: String,
        added: List<String> = emptyList(),
        removed: List<String> = emptyList(),
        why: String = "",
        channel: ResignChannel? = null,
    ): JSONObject = JSONObject()
        .put("v", 1)
        .put("event", event)
        .put("id", s.id)
        .put("version", s.version)
        .put("goal", s.goal)
        .put(
            "items",
            JSONArray().also { arr ->
                s.entries.forEach { e ->
                    arr.put(JSONObject().put("text", e.text).put("accept", e.accept))
                }
            },
        )
        .put("at", s.atMs)
        .put("session", s.sessionId)
        .apply {
            if (event == EVENT_RESIGN) {
                put("change", "计划变更")
                put("added", JSONArray(added))
                put("removed", JSONArray(removed))
                put("why", why)
                put("channel", channel?.wire ?: "")
            }
        }

    /** 收口行（结项的账：结局＋证据＋缺口都留底）。 */
    fun encodeSettle(verdict: SettleVerdict, snapshot: PledgeSnapshot, atMs: Long): JSONObject = JSONObject()
        .put("v", 1)
        .put("event", EVENT_SETTLE)
        .put("id", snapshot.id)
        .put("version", snapshot.version)
        .put("complete", verdict.complete)
        .put("items", JSONArray().also { arr -> verdict.ended.forEach { arr.put(endingRow(it)) } })
        .put("gaps", JSONArray().also { arr ->
            verdict.gaps.forEach { g ->
                arr.put(
                    JSONObject()
                        .put("text", g.text)
                        .put("missing", g.missing)
                        .put("failures", g.failures),
                )
            }
        })
        .put("at", atMs)

    private fun endingRow(s: Settled): JSONObject = JSONObject().put("text", s.text).also { o ->
        when (val e = s.ending) {
            is Ending.Done -> o.put("end", EndKind.DONE.wire).put("evidence", e.evidence)
            is Ending.Abandoned ->
                o.put("end", EndKind.ABANDONED.wire).put("reason", e.reason.wire).put("detail", e.detail)
        }
    }

    /** 账行 ⇒ 快照（回读路径：认 freeze/resign 两类；坏行 ⇒ null，宁缺勿崩）。 */
    fun decodeSnapshot(obj: JSONObject): PledgeSnapshot? {
        val event = obj.optString("event")
        if (event != EVENT_FREEZE && event != EVENT_RESIGN) return null
        val id = obj.optString("id")
        val version = obj.optInt("version", 0)
        if (id.isEmpty() || version < 1) return null
        val arr = obj.optJSONArray("items") ?: return null
        val entries = mutableListOf<PledgeEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: return null
            val text = o.optString("text")
            if (text.isEmpty()) return null
            entries += PledgeEntry(text, o.optString("accept"))
        }
        if (entries.isEmpty()) return null
        return PledgeSnapshot(
            id = id,
            version = version,
            goal = obj.optString("goal"),
            entries = entries,
            atMs = obj.optLong("at", 0L),
            sessionId = obj.optLong("session", -1L),
        )
    }

    // ── 追加判据⑦：回灌文案不出现价值判断词 ─────────────────────────────────
    //
    // 词表拆字存放：本包源码里不出现完整词，测试里的源级门禁才能把这几个文件也扫
    // 进去——谁往回灌文案里加进一个完整的价值判断词，门禁当场红（尺子先量自己）。

    val VALUE_JUDGMENT_WORDS: List<String> = listOf(
        "建" + "议", "应" + "该", "最" + "好", "更" + "好", "不" + "妨",
        "尽" + "量", "鼓" + "励", "批" + "评", "评" + "价", "可" + "惜",
        "值" + "得",
    )

    /** 一段文案过不过得了⑦的门（测试的尺子；接线层回灌前也可自查）。 */
    fun hasValueJudgment(text: String): Boolean = VALUE_JUDGMENT_WORDS.any { text.contains(it) }

    /** 字符串数组，也接受单个字符串；空白条目丢掉（与 `PlanContractRules` 同口径）。 */
    private fun stringList(v: Any?): List<String> = when (v) {
        is JSONArray -> (0 until v.length()).mapNotNull { v.optString(it).trim().ifEmpty { null } }
        is String -> listOf(v.trim()).filter { it.isNotEmpty() }
        else -> emptyList()
    }
}
