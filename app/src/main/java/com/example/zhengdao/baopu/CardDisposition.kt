// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十二批 P1 追加裁定（2026-10-31 用户）：**「被拒」不能丢，但它不许进业务账。**
//
// 分界写死在这里，别处不许改口：
// · `DecisionLedger` ＝ **事实**（替你做过什么：删了哪个文件、写了哪一行）；
// · `CardDisposition` ＝ **处置**（这张卡的下场：谁、哪张卡、几点、选了哪个）。
//
// 为什么必须分表、id 不许互通（裁定原文三条）：
// 1. 业务账的口径只能是"它记事实"。一旦允许"问过／想做"进来，三个月后就没人分得清
//    哪一行是做了、哪一行是想做——那正是 292 这个 bug 的病根（她点拒绝，账上写着删了）。
// 2. 处置是 318（DISMISS/REASK 学习）与审计的信号源，**不是**动作流水：抽检的分母、
//    回放的动作序列都只认事实账。两本混一本，两边同时算错（与 274/278 分家的理由同源）。
// 3. 所以这里**只留一张表结构＋一个写入口**：本批的写出点是 `agent/ConfirmFlow.kt`
//    的确认卡（批／驳／不用了／超时），318 的卡面补入口（不用了／再说一遍）走同一个
//    [CardDisposition.record]。除这一处之外别处要写，先改这张表的结构再说。
//
// 「谁」这一格只有两个值：用户、计时器。机器侧的结论（四器、宰相自身判断）**没有入口**
// ——照 283 那条纪律（标签只能来自人）；超时不是有人表态，是时钟把卡收走了，
// 所以它的形状是 `actor=时钟 + choice=不用了 + why=没人表态`，读的人一眼看得懂。
//
// 房型照 274/277/278/298（同一条链路上的另外几本账）：
// · 落点＝208 已装配的那棵私有目录（`抱朴记忆/`，取自 [DecisionLedger.installedRoot]），
//   **不是模型指的工作区路径**；文件名独立（`卡处置.jsonl`）；
// · 追加-only：不覆盖、不删除、不改写老行（改行＝改历史）；
// · 缺落点（哪张卡认不出）⇒ **不落账**——没有落点的处置是编的；
// · 理由留空 ⇒ 用该选项的类别句兜底（理由列不许空白）；
// · 坏行读时跳过；写失败不抛：账是留痕不是闸门，落不上只记一行日志。
package com.example.zhengdao.baopu

import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONObject

/**
 * 卡处置账（进程内单例；append-only，落 `抱朴记忆/卡处置.jsonl`）。
 *
 * 一行 ＝ 一张卡的下场。**这一本里没有任何"做成了什么"**：它连工具执行的结局都不记
 * （那是事实账与轨迹的事），只记"这张卡被怎么处理的"。
 */
internal object CardDisposition {

    /** 账本文件名（与 208／意见判定／追认队列／御批／判决经验账同一棵目录，各自一个文件）。 */
    const val FILE_NAME = "卡处置.jsonl"

    /** 行格式版本（改形状才 +1；加字段不算）。 */
    const val VERSION = 1

    /** 读侧上限（只看最近这么多行；不是时间窗——回放不许拿"太久没看"当过滤条件）。 */
    const val READ_LIMIT = 5_000

    /** 本账自己的 id 前缀（**与业务账的 opId 不互通**：两边互认不出对方的行，见 [CardDispositionRow.parse]）。 */
    const val ID_PREFIX = "cd:"

    private const val MEMORY_CAP = 200

    /** 选了哪个（五个，闭集；照 2026-10-31 裁定原文「批·驳·搁·不用了·再说一遍」）。 */
    enum class Choice(val wire: String, val cn: String) {
        /** 批：按这张卡办（业务账那一侧随后会有事实行——两本各记各的，不互相顶替）。 */
        APPROVE("approve", "批"),

        /** 驳：这一步不做（本批那条判据钉的就是这一格：驳 ⇒ 事实账零行、这里一行）。 */
        REJECT("reject", "驳"),

        /** 搁：以后再说，不是"关"（照 283 的口径，搁不计进"别再弹"）。 */
        HOLD("hold", "搁"),

        /** 不用了：这张卡到此为止、不再问（用户关卡/点停止，或计时器把卡收走）。 */
        DISMISS("dismiss", "不用了"),

        /** 再说一遍：同一件事用户事后又问了 ⇒ 之前压错了（318 写这一格）。 */
        REASK("reask", "再说一遍"),
        ;

        companion object {
            /** 认不出就 null（自造值不进账，也不静默当别的类）。 */
            fun ofWire(wire: String): Choice? = entries.firstOrNull { it.wire == wire }
        }
    }

    /** 哪张卡（三样，闭集；本批只有 [CONFIRM] 有生产写出点）。 */
    enum class Face(val wire: String, val cn: String) {
        /** 确认卡：管线第③段弹的那张「要动盘了，点一下」（[Choice] 四格都用它）。 */
        CONFIRM("confirm", "确认卡"),

        /** 御案卡：决策中心那张三裁（**它的下场另有 278 的御批账**，这一格留给 318 的卡面补入口）。 */
        ROYAL("royal", "御案卡"),

        /** 偏离卡：242 契约偏离那一问。 */
        DEVIATION("deviation", "偏离卡"),
        ;

        companion object {
            fun ofWire(wire: String): Face? = entries.firstOrNull { it.wire == wire }
        }
    }

    /** 谁选的（两格，闭集：机器侧结论没有入口）。 */
    enum class Actor(val wire: String, val cn: String) {
        USER("user", "用户"),

        /** 计时器：没人表态，前台到线把卡收走——是"不用了"，不是有人驳。 */
        CLOCK("clock", "计时器"),
        ;

        companion object {
            fun ofWire(wire: String): Actor? = entries.firstOrNull { it.wire == wire }
        }
    }

    /**
     * 一行处置。
     *
     * @param cardId **这张卡**的落点（确认卡＝那次工具调用的 id）：空白 ⇒ 不落账。
     *        它是"哪张卡"的一格，不是业务账的键——账与账之间不许拿它互相当对方的行。
     * @param subject 卡讲的是什么事的一句人话（确认卡＝摘要；只供审计阅读，不参与判定）
     * @param why 为什么这么处置（留空 ⇒ 用该选项的类别句；理由列不许空白）
     */
    data class CardDispositionRow(
        val id: String,
        val atMs: Long,
        val face: Face,
        val cardId: String,
        val choice: Choice,
        val actor: Actor,
        val subject: String,
        val why: String,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("v", VERSION)
            .put("id", id)
            .put("at", atMs)
            .put("face", face.wire)
            .put("card", cardId)
            .put("choice", choice.wire)
            .put("actor", actor.wire)
            .put("subject", subject)
            .put("why", why)

        companion object {
            /**
             * 坏行一律跳过（读历史不许被一行脏数据整本堵死）。
             *
             * 认不出 `face`／`choice`／`actor` 任何一格、或 id 不带本账前缀、或缺落点 ⇒ null。
             * 这一条同时是"分表"的机械形状：208 那本账的行（没有 face/choice/actor 三列）
             * 到这里必然解析失败，反之亦然——两本账谁也不认对方的行，拿处置账补业务账补不上。
             */
            fun parse(raw: String): CardDispositionRow? {
                val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
                val id = o.optString("id")
                val at = o.optLong("at", 0L)
                val face = Face.ofWire(o.optString("face")) ?: return null
                val choice = Choice.ofWire(o.optString("choice")) ?: return null
                val actor = Actor.ofWire(o.optString("actor")) ?: return null
                val card = o.optString("card")
                if (!id.startsWith(ID_PREFIX) || at <= 0L || card.isBlank()) return null
                return CardDispositionRow(
                    id = id,
                    atMs = at,
                    face = face,
                    cardId = card,
                    choice = choice,
                    actor = actor,
                    subject = o.optString("subject"),
                    why = o.optString("why"),
                )
            }
        }
    }

    private val memory = ArrayList<CardDispositionRow>()
    private var seq = 0

    /**
     * **唯一写入口**（本批裁定：表结构留好、写入口留一个）。
     *
     * @return 落下的行；缺落点或写失败 ⇒ null（不抛：账是留痕不是闸门）
     */
    @Synchronized
    fun record(
        choice: Choice,
        face: Face,
        cardId: String,
        actor: Actor = Actor.USER,
        subject: String = "",
        why: String = "",
        atMs: Long = System.currentTimeMillis(),
    ): CardDispositionRow? {
        if (cardId.isBlank()) {
            RunLog.log("抱朴处置账：处置缺落点（哪张卡认不出），不落（$choice）")
            return null
        }
        val row = CardDispositionRow(
            id = "$ID_PREFIX$atMs:${++seq}",
            atMs = atMs,
            face = face,
            cardId = cardId.trim(),
            choice = choice,
            actor = actor,
            subject = subject.trim(),
            why = why.trim().ifEmpty { defaultWhy(choice, actor) },
        )
        memory.add(row)
        if (memory.size > MEMORY_CAP) memory.removeAt(0)
        val root = root() ?: return row
        runCatching {
            val f = fileIn(root)
            f.parentFile?.mkdirs()
            f.appendText(row.toJson().toString() + "\n", Charsets.UTF_8)
        }.onFailure {
            RunLog.log("抱朴处置账：没落上（${row.id}）——${it.javaClass.simpleName}: ${it.message}")
        }
        RunLog.log(logLine(row))
        return row
    }

    /** 全本读（盘上那份 ＋ 本进程内存那份，按 id 去重；新→旧，不设时间窗）。 */
    @Synchronized
    fun replay(limit: Int = 0): List<CardDispositionRow> {
        val fromFile = root()?.let { r ->
            runCatching {
                val f = fileIn(r)
                if (!f.isFile) emptyList() else {
                    val lines = f.readLines(Charsets.UTF_8)
                    val capped = if (lines.size > READ_LIMIT) lines.takeLast(READ_LIMIT) else lines
                    capped.mapNotNull { CardDispositionRow.parse(it) }
                }
            }.getOrDefault(emptyList())
        }.orEmpty()
        val all = (fromFile + memory).distinctBy { it.id }.sortedByDescending { it.atMs }
        return if (limit > 0) all.take(limit) else all
    }

    /** 某一张卡的下场（318 与审计按它对号；一张卡多次被处置就按时刻倒序列出）。 */
    @Synchronized
    fun rowsFor(cardId: String): List<CardDispositionRow> = replay().filter { it.cardId == cardId.trim() }

    /** 落点文件。 */
    fun fileIn(globalRoot: File): File = File(globalRoot, FILE_NAME)

    /** 一句结论（只出计数，不出行内容——体检/审计的对外面）。 */
    @Synchronized
    fun conclusion(): String {
        val rows = replay()
        val byUser = rows.count { it.actor == Actor.USER }
        return "卡处置账：${rows.size} 行（人裁 $byUser，时钟收走 ${rows.size - byUser}，" +
            "落点=${if (root() == null) "内存（未装配）" else FILE_NAME}）"
    }

    /** 该选项的类别句（理由列不许空白；超时的句子必须自证"没人表态"，不许读成有人驳）。 */
    internal fun defaultWhy(choice: Choice, actor: Actor): String = when (choice) {
        Choice.APPROVE -> "用户批了这张卡"
        Choice.REJECT -> "用户驳了这张卡：这一步不做"
        Choice.HOLD -> "用户搁置：以后再说"
        Choice.DISMISS -> if (actor == Actor.CLOCK) {
            "确认超时，没人表态 ⇒ 这张卡作废（既不算批也不算驳）"
        } else {
            "这张卡不用了（关卡或点停止）"
        }
        Choice.REASK -> "用户事后又问了同一件事：之前压错了"
    }

    internal fun logLine(row: CardDispositionRow): String =
        "抱朴处置账：${row.choice.cn}｜${row.face.cn}=${row.cardId}｜${row.actor.cn}｜${row.why}"

    @Synchronized
    fun resetForTest() {
        memory.clear()
        seq = 0
    }

    /** 账本落点（与 208 同一棵目录；没装配 ⇒ 只走内存）。 */
    private fun root(): File? = DecisionLedger.installedRoot()
}
