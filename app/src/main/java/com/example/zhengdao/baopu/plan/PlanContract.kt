// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-27-第二百四十二批-计划即契约 §1（A 契约字段 / B 批准后即本次运行契约 /
// C 执行期三条硬规则 / D 勘验对账）＋ §3 模式关系 ＋ §4 五条判据 ＋ §5 七条负例。
//
// 本文件是**判定层**：零 Android / 零 IO / 零时钟 / 不弹卡 / 不写盘 / 不记日志。
// 事实（这次动作动到哪些目标、实际几档、是不是写、计划当前打到哪一格）全部由调用方
// 算好传进来——与 `RiskMatrix.kt`（219）同一个房型，所以能和它一起进 JVM 单测。
//
// 三条贯穿全文件的口径：
// 1. **契约是上限，不是下限**：没有契约 ⇒ 本层每条判据恒「不违反」，今天的执行路径
//    逐字节不变（负例①；也是「批准计划不构成 R2/R3 授权」的落地方式——契约从来
//    不授权，它只封顶）。
// 2. **契约不替换风险矩阵**：本层只问「这件事在不在计划说好的范围里」；「这件事本身
//    几档、要不要问」仍归 `RiskMatrix`/`RiskGate`（红线：判据与档位一个字不动）。
//    两个结论**取严**：矩阵说要问 ⇒ 照问，契约不能替它免批（也不动 192 的免批口径）；
//    契约说要停 ⇒ 停，矩阵判它是 R0 也不能替计划放行契约外的目标。
// 3. **超了是"暂停问人"，不是"判死"**：C 段三条硬规则只产出一个事实＝「偏离」，
//    怎么处置（扩计划／取消／换做法）由偏离卡交回给人。
package com.example.zhengdao.baopu.plan

import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.PlanRules.PlanItem
import com.example.zhengdao.baopu.RiskClass
import org.json.JSONArray
import org.json.JSONObject

/**
 * 契约里的一步（A 段 `steps[n]`）。
 *
 * **public 的理由**：计划卡要把每一步的风险预估画出来（判据 1），而 public 类型
 * 不能暴露 internal 类型（编译期硬错）——本类是数据形状；判据仍在
 * [PlanContractRules]（internal）。
 *
 * [n] 与 [intent] 都**不是模型给的**：步骤按位置对齐 `update_plan` 的 `items`
 * （n ＝ 下标 ＋ 1，intent ＝ `items[n-1].text`）。这样「计划里的步骤」只有
 * 一个真源，模型没法在契约里偷偷写一条计划中没有的步骤（负例④的一半）。
 */
data class PlanStep(
    val n: Int,
    val intent: String,
    /** 这一步允许动到的目标（相对 [PlanContract.touches] 更窄；两者取并集生效）。 */
    val mayTouch: List<String>,
    /** 这一步的**预估上限**：实际比它高 ⇒ 立刻暂停（C 段②）。 */
    val risk: RiskClass,
)

/**
 * 计划契约（A 段七个字段）。
 *
 * 用户在计划卡上批准这份计划 ⇒ 它同时成为**本次运行的契约**（B 段）；
 * 批准本身不产生任何 R2/R3 授权——授权只走各自动作自己的确认卡。
 */
data class PlanContract(
    /** 这次要干成什么（一句话；卡面第一行）。 */
    val goal: String,
    val steps: List<PlanStep>,
    /** 全局白名单：整轮都可能动到的目标（目录/主机名）。 */
    val touches: List<String>,
    /** 只读区：可以读、绝不可以写的目标（比 [touches] 更严的一层）。 */
    val readonly: List<String>,
    /** 预估要问人几次（D 段第三项对账用它；负例⑥的靶心）。 */
    val expectedAsks: Int,
    /** 出现什么情况就停（人话条件：本层不解释语义，只随卡面与对账一起如实回读）。 */
    val stopIf: List<String>,
) {
    /** 步号 → 预估上限（[steps] 是唯一真源，这里只是查表用的派生视图）。 */
    val risks: Map<Int, RiskClass> = steps.associate { it.n to it.risk }

    /** 契约声明过的最高档（D 段第二项对账的"预估"那一侧）。 */
    val declaredMaxRisk: RiskClass? = steps.maxByOrNull { it.risk.ordinal }?.risk

    /** 全局 ＋ 各步声明的全部目标（去重、保顺序；日志与「加进白名单」候选用）。 */
    val allTouchSpecs: List<String> = (touches + steps.flatMap { it.mayTouch }).distinct()
}

/** 一个目标面：路径或主机名（契约白名单里的每一项、每次动作实际碰到的每一个目标，都是它）。 */
internal enum class TouchFaceKind { PATH, HOST }

/**
 * 一个目标面（值已归一化：路径折叠 `.`/`..`、去尾分隔符；主机名小写去端口）。
 *
 * 为什么只有两类：契约说的是「动到哪儿」，而工具参数里的地点只有这两种形态
 * （文件系统路径 / 网络主机）。没有地点的动作（剪贴板、通知、生图）不受覆盖判定
 * 约束，只受风险上限约束——见 [PlanContractRules.check]。
 */
internal data class TouchFace(val kind: TouchFaceKind, val value: String) {

    companion object {

        /**
         * 一个字符串 ⇒ 目标面；空白 ⇒ null。
         *
         * 分类只看形状，不看语义：带 `://` 的先剥出主机名（网址是契约里最常见的
         * 一种声明：「这次只准动 example.com」），其余含路径分隔符或形如 `x:` 盘符
         * 开头的算路径，再剩下带点的算主机名，最后（`notes.txt` 这种）按路径兜底——
         * **兜成路径**是因为误判成主机名会让它去比域名，那才是谁都对不上。
         */
        fun of(raw: String?): TouchFace? {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return null
            val schemeAt = s.indexOf("://")
            if (schemeAt >= 0) return hostFace(s.substring(schemeAt + 3))
            return if (s.contains('/') || s.contains('\\') || hasDrive(s)) {
                TouchFace(TouchFaceKind.PATH, normalizePath(s))
            } else if (s.contains('.')) {
                hostFace(s)
            } else {
                TouchFace(TouchFaceKind.PATH, normalizePath(s))
            }
        }

        /**
         * 主机名归一：剥掉路径/查询/锚、`user@` 前缀与 `:端口`，小写、去 `www.`。
         *
         * 为什么按主机名而不是整条 URL：白名单说的是"能动到哪家"，而 URL 每次
         * 都不一样（同一篇稿子的两个链接是两个字符串）——按整条比就只能"一次一授权"，
         * 那是把契约当成一次性口令。剥不出主机（`file:///…` 这种）⇒ null：
         * 没有地点的动作不受覆盖判定约束，只受风险上限约束。
         */
        private fun hostFace(raw: String): TouchFace? {
            val host = raw.substringBefore('/').substringBefore('?').substringBefore('#')
                .substringAfterLast('@').substringBefore(':').lowercase().removePrefix("www.")
            return if (host.isEmpty()) null else TouchFace(TouchFaceKind.HOST, host)
        }

        /** 从参数里取主机名目标（只认这几个键——不做"扫遍所有字符串"的散装判断）。 */
        val HOST_ARG_KEYS = setOf("url", "site", "link", "href")

        /**
         * 一次动作的目标面：路径由调用方给（第 ② 段已把参数改写成绝对路径），
         * 主机名从 [HOST_ARG_KEYS] 取。
         */
        fun facesOf(paths: List<String>, args: JSONObject): List<TouchFace> {
            val out = LinkedHashSet<TouchFace>()
            paths.forEach { TouchFace.of(it)?.let { f -> out += f } }
            HOST_ARG_KEYS.forEach { key -> TouchFace.of(args.optString(key))?.let { f -> out += f } }
            return out.toList()
        }

        /**
         * 路径归一（本层私有）：`\`→`/`、折叠 `.`/`..`、去尾分隔符、盘符小写、
         * 绝对性用开头的空段表示（`/a/b` ⇒ ["", "a", "b"]）。
         *
         * 为什么不去 `canonicalize`：那是第 ② 段（路径守卫）的活，本层不许碰盘；
         * 为什么要**自己**写一份而不是复用 `RiskMatrix` 的同名私有函数：红线说
         * 不动那一段判据，把它内部函数改成共享就是动它；而契约要匹配的声明
         * （`资料库/产物/`、`*.example.com`）本来就不是「在不在工作区里」这一个问题。
         */
        fun normalizePath(raw: String): String {
            val s = raw.trim().replace('\\', '/')
            if (s.isEmpty()) return ""
            val drive = if (hasDrive(s)) s.substring(0, 2).lowercase() else ""
            val absolute = drive.isNotEmpty() || s.startsWith("/")
            val body = when {
                drive.isNotEmpty() -> s.substring(2)
                s.startsWith("/") -> s.substring(1)
                else -> s
            }
            val out = ArrayList<String>()
            for (seg in body.split('/')) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> if (out.isNotEmpty() && out.last() != "..") out.removeAt(out.lastIndex)
                    else -> out.add(seg)
                }
            }
            val joined = out.joinToString("/")
            val head = (when {
                drive.isNotEmpty() -> "$drive/$joined"
                absolute -> "/$joined"
                else -> joined
            }).trimEnd('/')
            return head.ifEmpty {
                when {
                    drive.isNotEmpty() -> "$drive/"
                    absolute -> "/"
                    else -> ""
                }
            }
        }

        /** 路径 ⇒ 组件序列（绝对路径首元素是空串，天然把"绝对/相对"分在不同类里）。 */
        fun segments(path: String): List<String> =
            if (path.isEmpty()) emptyList() else path.split('/')

        private fun hasDrive(s: String): Boolean =
            s.length >= 2 && s[1] == ':' && s[0].isLetter()
    }
}

/** 契约对一次动作的结论（C 段三条硬规则只有这五种落点）。 */
internal enum class ContractBreach {
    /** 不违反契约（没有契约时恒此值——契约只封顶，从不放行）。 */
    NONE,

    /** 往「只读区」里写（C 段②的一种：比越界更具体，卡面要说清是只读区）。 */
    WRITE_IN_READONLY,

    /** 动到了白名单外的目标（C 段②）。 */
    OUT_OF_TOUCHES,

    /** 实际风险档高于这一步的预估（C 段②）。 */
    RISK_OVER_ESTIMATE,

    /** 计划里的步骤已全部打完勾，还要继续动手（B 段「动作必须 ⊆ 契约」的步骤维度）。 */
    BEYOND_STEPS,
}

/** [PlanContractRules.check] 的产物（偏离卡要念的那几件事都在这里）。 */
internal data class ContractCheck(
    val breach: ContractBreach = ContractBreach.NONE,
    /** 没被契约覆盖的目标（[ContractBreach.OUT_OF_TOUCHES] / [ContractBreach.WRITE_IN_READONLY] 时非空）。 */
    val uncovered: List<TouchFace> = emptyList(),
    /** 当前步的预估上限（null ＝ 计划已打完勾 ⇒ 没有上限可比）。 */
    val ceiling: RiskClass? = null,
    /** 这一次动作的实际档位（由风险矩阵给，本层只比较不重判）。 */
    val actual: RiskClass? = null,
    /** 当前步号与原文（卡面要说"计划里这一步是干什么的"）。 */
    val stepNo: Int? = null,
    val stepIntent: String = "",
) {
    val violated: Boolean get() = breach != ContractBreach.NONE

    /** 越界目标的展示串（卡面/回灌共用；没有目标面时说"这个动作没有地点，但已超出计划步骤"）。 */
    val faceBrief: String
        get() = uncovered.joinToString("、") { it.value }.ifBlank { "（这次动作没有具体目标）" }
}

/** 勘验对账的一条不符（D 段三项各一条；进 208 记账）。 */
internal enum class ContractFindingCode(val id: String) {
    /** 实际动作不在契约里（越白名单或写进只读区）。 */
    ACT_OUT_OF_CONTRACT("act_out_of_contract"),

    /** 实际风险高于预估。 */
    RISK_OVER_ESTIMATE("risk_over_estimate"),

    /** 实际问人次数明显高于预估。 */
    ASKS_OVER_ESTIMATE("asks_over_estimate"),

    /** 实际问人次数明显低于预估——「说好的 N 次一次没问」也是估算骗人。 */
    ASKS_UNDER_ESTIMATE("asks_under_estimate"),
    ;

    override fun toString(): String = id
}

/** 一条不符（[count] 是同类问题的次数，卡面与 208 都只念一句）。 */
internal data class ContractFinding(
    val code: ContractFindingCode,
    val detail: String,
    val count: Int = 1,
)

/** 一次运行的勘验对账读数（D 段：三项各一个数，任一不符 ⇒ findings 非空）。 */
internal data class ReconReport(
    val totalActions: Int,
    val writeActions: Int,
    val asks: Int,
    val expectedAsks: Int,
    val declaredMaxRisk: RiskClass?,
    val actualMaxRisk: RiskClass?,
    val outOfContract: Int,
    val findings: List<ContractFinding>,
) {
    val isClean: Boolean get() = findings.isEmpty()
}

/**
 * 契约的解析、覆盖判定与对账（全部纯函数，零 Android 依赖，JVM 直测）。
 *
 * 五条判据的落地处（派工单 §4）：
 * 1. 卡上有 steps/touches/每步风险/expectedAsks —— [parse] 缺这些字段就**不成立契约**
 *    （[ContractParse.Error]），没成立就没有"上限"可装（负例①）；
 * 2. 批准后契约内 R1 不弹卡 —— [check] 返回 NONE 时不改任何既有行为；
 * 3. 契约外路径**立刻**停 —— [check] 在动作派发前调（接线见 `GuardPipeline`），
 *    本层只出结论，不知道"跑完之后"；
 * 4. 想加一步只提示不自己加 —— [check] 的 [ContractBreach.BEYOND_STEPS] ＋
 *    [PlanContractRules.newStepNotice]（只有一句话，没有写权限）；
 * 5. 跑完对账出读数 —— [reconcile]。
 */
internal object PlanContractRules {

    /** 目标数上限：白名单太长就不是"契约"而是"随便动"了（超出截断并记 notice）。 */
    const val MAX_TOUCHES = 12

    /** 只读区上限。 */
    const val MAX_READONLY = 8

    /** 单个目标声明的字数上限。 */
    const val MAX_SPEC_CHARS = 120

    /** 「出现什么情况就停」的条数上限（卡面只留一行）。 */
    const val MAX_STOP_IF = 4

    /** 问人次数的容差：超出预估这么多才算"估算骗人"（一次以内的出入是正常波动）。 */
    const val ASK_GAP = 2

    /** `update_plan` 参数里契约那一块的键名。 */
    const val ARG_CONTRACT = "contract"

    // ── A 段：解析 ──────────────────────────────────────────────────────────

    /** [parse] 的结局。 */
    sealed interface ContractParse {
        /** 契约成立：可以进计划卡、批准后可当本次运行契约。 */
        data class Ok(val contract: PlanContract, val notices: List<String>) : ContractParse

        /** 这次没有声明契约 ⇒ **不设上限**（今天的执行路径逐字节不变）。 */
        data class Absent(val reason: String) : ContractParse

        /** 声明了但缺必备字段/写法不对 ⇒ 错误原文回灌，模型下一版自己补（不静默降级）。 */
        data class Error(val message: String) : ContractParse
    }

    /**
     * 从 `update_plan` 的参数里解析契约。
     *
     * @param items **已归一化**的计划条目（调用方给同一份，保证步骤文字只有一个真源）
     *
     * 为什么"缺字段"是 [ContractParse.Error] 而不是 [ContractParse.Absent]：
     * 模型已经写了 `contract` 就说明它想立契约；这时把不完整的契约静默当成没契约，
     * 等于悄悄放弃封顶——用户看到的是"有计划、照样乱跑"（负例①的反面教材）。
     * 反过来，**根本没写** `contract` 才是 Absent：问答式的一轮本来就不需要契约。
     */
    fun parse(args: JSONObject, items: List<PlanItem>): ContractParse {
        val raw = args.opt(ARG_CONTRACT)
        if (raw == JSONObject.NULL || raw == null) return ContractParse.Absent("这次没有声明契约：按既有风险档走，不设执行上限")
        val obj = raw as? JSONObject
            ?: return ContractParse.Error("参数「$ARG_CONTRACT」不是对象：要 {goal, steps, touches, readonly, expected_asks, stop_if}。")

        val notices = mutableListOf<String>()

        val goal = obj.optString("goal").trim()
        if (goal.isEmpty()) return ContractParse.Error("契约缺「goal」：一句话说清这次要干成什么（没有目标就没有上限）。")

        val touches = stringList(obj.opt("touches"))
        if (touches.isEmpty()) {
            return ContractParse.Error(
                "契约缺「touches」：必须列出这次允许动到哪些目标（目录或网址），" +
                    "否则没有任何上限可判（负例：计划卡上没有白名单）。",
            )
        }

        val stepsRaw = obj.optJSONArray("steps")
        if (stepsRaw == null || stepsRaw.length() == 0) {
            return ContractParse.Error("契约缺「steps」：每一步都要给 {may_touch, risk}，一条不落。")
        }
        if (stepsRaw.length() < items.size) {
            return ContractParse.Error(
                "契约的 steps 只有 ${stepsRaw.length()} 条，但计划有 ${items.size} 条：" +
                    "每一步都得给一个 risk（少一步就少一格上限）。",
            )
        }
        if (stepsRaw.length() > items.size) {
            notices += "契约 steps ${stepsRaw.length()} 条 > 计划 ${items.size} 条，多出来的按位置丢掉。"
        }
        val steps = mutableListOf<PlanStep>()
        for (i in 0 until minOf(stepsRaw.length(), items.size)) {
            val stepObj = stepsRaw.optJSONObject(i)
                ?: return ContractParse.Error("契约第 ${i + 1} 步不是对象：要 {may_touch, risk}。")
            val risk = riskOf(stepObj.optString("risk"))
                ?: return ContractParse.Error(
                    "契约第 ${i + 1} 步缺「risk」或写法不对（${stepObj.optString("risk")}）：" +
                        "只能是 R0/R1/R2/R3 之一——这一步的上限就是它，不许留空。",
                )
            steps += PlanStep(
                n = i + 1,
                intent = items[i].text,
                mayTouch = stringList(stepObj.opt("may_touch")).map { cut(it) },
                risk = risk,
            )
        }

        val expectedAsks = expectedAsksOf(obj)
            ?: return ContractParse.Error(
                "契约缺「expected_asks」：预估这次要问人几次（整数，可以是 0）——" +
                    "跑完要靠它对账，缺了就没法验（负例：expectedAsks 骗人）。",
            )

        return ContractParse.Ok(
            contract = PlanContract(
                goal = goal.take(MAX_SPEC_CHARS),
                steps = steps,
                touches = trim(touches, MAX_TOUCHES, "白名单", notices),
                readonly = trim(stringList(obj.opt("readonly")), MAX_READONLY, "只读区", notices),
                expectedAsks = expectedAsks,
                stopIf = trim(stringList(obj.opt("stop_if")), MAX_STOP_IF, "停机条件", notices),
            ),
            notices = notices,
        )
    }

    /** 档位写法（R0–R3，大小写不限）；认不出 ⇒ null（由调用方报人话错误）。 */
    fun riskOf(raw: String): RiskClass? = when (raw.trim().uppercase()) {
        "R0" -> RiskClass.R0
        "R1" -> RiskClass.R1
        "R2" -> RiskClass.R2
        "R3" -> RiskClass.R3
        else -> null
    }

    /** `expected_asks` 也接受 `expectedAsks` 写法（模型两边都会写）；没有/负数 ⇒ null。 */
    private fun expectedAsksOf(obj: JSONObject): Int? {
        val key = if (obj.has("expected_asks")) "expected_asks" else if (obj.has("expectedAsks")) "expectedAsks" else return null
        val v = obj.opt(key)
        val n = when (v) {
            is Int -> v
            is Long -> v.toInt()
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull()
            else -> null
        }
        return n?.takeIf { it >= 0 }
    }

    /** 字符串数组，也接受单个字符串；空白条目丢掉。 */
    private fun stringList(v: Any?): List<String> = when (v) {
        is JSONArray -> (0 until v.length()).mapNotNull { v.optString(it).trim().ifEmpty { null } }
        is String -> listOf(v.trim()).filter { it.isNotEmpty() }
        else -> emptyList()
    }

    private fun cut(s: String): String = s.take(MAX_SPEC_CHARS)

    private fun trim(list: List<String>, max: Int, what: String, notices: MutableList<String>): List<String> {
        val cleaned = list.map { cut(it) }.distinct()
        if (cleaned.size > max) notices += "$what ${cleaned.size} 项，超过 $max 项上限，已截断到前 $max 项。"
        return cleaned.take(max)
    }

    // ── 落库编解码（payload 的 `contract` 键；缺省时 PlanRules 的字节一个不动）──

    /**
     * 契约 → JSON（键序固定 ⇒ 同一份契约永远同一串字节，J4「写后读回逐字一致」才判得出）。
     *
     * `steps` 里**不存** n 与 intent：位置就是步号，步骤文字仍以 `items` 为唯一真源。
     */
    fun encode(contract: PlanContract): JSONObject = JSONObject()
        .put("goal", contract.goal)
        .put(
            "steps",
            JSONArray().also { arr ->
                contract.steps.forEach { step ->
                    arr.put(
                        JSONObject()
                            .put("may_touch", JSONArray(step.mayTouch))
                            .put("risk", step.risk.name),
                    )
                }
            },
        )
        .put("touches", JSONArray(contract.touches))
        .put("readonly", JSONArray(contract.readonly))
        .put("expected_asks", contract.expectedAsks)
        .put("stop_if", JSONArray(contract.stopIf))

    /**
     * JSON → 契约（回读路径：坏数据 ⇒ null，**不抛**、也不在此报错——
     * 与 [com.example.zhengdao.baopu.PlanRules.fromJson] 同口径：宁缺勿崩）。
     */
    fun decode(obj: JSONObject, items: List<PlanItem>): PlanContract? {
        val goal = obj.optString("goal").trim()
        val touches = stringList(obj.opt("touches"))
        if (goal.isEmpty() || touches.isEmpty()) return null
        val stepsRaw = obj.optJSONArray("steps") ?: return null
        if (stepsRaw.length() == 0 || stepsRaw.length() > items.size) return null
        val steps = mutableListOf<PlanStep>()
        for (i in 0 until stepsRaw.length()) {
            val stepObj = stepsRaw.optJSONObject(i) ?: return null
            val risk = riskOf(stepObj.optString("risk")) ?: return null
            steps += PlanStep(i + 1, items[i].text, stringList(stepObj.opt("may_touch")), risk)
        }
        val expectedAsks = expectedAsksOf(obj) ?: return null
        return PlanContract(
            goal = goal,
            steps = steps,
            touches = touches,
            readonly = stringList(obj.opt("readonly")),
            expectedAsks = expectedAsks,
            stopIf = stringList(obj.opt("stop_if")),
        )
    }

    // ── C 段：执行期三条硬规则 ──────────────────────────────────────────────

    /**
     * 当前生效的那一步：标着 doing 的那条 ⇒ 否则第一条没打完的 ⇒ 都没有 ⇒ null。
     *
     * 为什么不是"最后一条 done 的下一条"：模型会重排顺序；doing 是它的明示，
     * 没明示就退到第一条未完成，两种都是**从上往下**的第一个，不会出现"跳过中间
     * 没做完的按后面那一步的上限"这种放松。
     */
    fun activeStep(contract: PlanContract?, items: List<PlanItem>): PlanStep? {
        val c = contract ?: return null
        val doingIdx = items.indexOfFirst { it.status == PlanRules.STATUS_DOING }
        val idx = if (doingIdx >= 0) doingIdx else items.indexOfFirst { it.status != PlanRules.STATUS_DONE }
        if (idx < 0) return null
        return c.steps.getOrNull(idx)
    }

    /**
     * 一次动作对照契约的判定（C 段①②③ 的唯一判据；**动作派发之前**调）。
     *
     * @param contract 本次运行契约；null ⇒ 没契约，恒 [ContractBreach.NONE]
     * @param faces 这次动作实际碰到的目标（[TouchFace.facesOf]）
     * @param actual 风险矩阵已经判出来的档位（本层**不重判**——重判会二次计频，见报告）
     * @param isWrite 会不会改盘（`ToolSpec.readOnly` 的取反）：读不受风险上限约束
     * @param items 当前计划（定位生效步骤用）
     * @param root 工作区根绝对路径：白名单里相对写法的声明按它解析后再比（口径同第 ② 段）
     *
     * 判定顺序（先具体后一般，卡面只念第一条命中的）：
     * 1. 往只读区写 ⇒ [ContractBreach.WRITE_IN_READONLY]；
     * 2. 目标不在白名单 ⇒ [ContractBreach.OUT_OF_TOUCHES]；
     * 3. 写动作：计划已打完勾 ⇒ [ContractBreach.BEYOND_STEPS]；实际档高于这一步预估 ⇒
     *    [ContractBreach.RISK_OVER_ESTIMATE]。
     *
     * 三条都**只在契约成立时**才可能命中；读动作只吃第 2 条（越界），
     * 不吃风险上限——上限说的是"能改成什么"，读不改东西（负例「一跑就弹」的反面）。
     *
     * 第 1 条为什么不看第 2 条的结果：白名单和只读区可以同时写同一个目录
     * （「整个 `资料库/` 能读能写，但 `资料库/原始/` 只读」是最常见的声明形状）。
     * 只读区那一层必须**独立成立**，否则越"允许"的声明反而把红线洗白了。
     */
    fun check(
        contract: PlanContract?,
        faces: List<TouchFace>,
        actual: RiskClass?,
        isWrite: Boolean,
        items: List<PlanItem>,
        root: String = "",
    ): ContractCheck {
        val c = contract ?: return ContractCheck()
        val step = activeStep(c, items)
        val base = ContractCheck(
            ceiling = step?.risk,
            actual = actual,
            stepNo = step?.n,
            stepIntent = step?.intent.orEmpty(),
        )
        if (isWrite) {
            val ro = readonlyFaces(c, root)
            val inReadonly = faces.filter { f -> ro.any { covers(it, f) } }
            if (inReadonly.isNotEmpty()) {
                return base.copy(breach = ContractBreach.WRITE_IN_READONLY, uncovered = inReadonly)
            }
        }
        val uncovered = faces.filter { !covered(it, allowFaces(c, step, root)) }
        if (uncovered.isNotEmpty()) {
            return base.copy(breach = ContractBreach.OUT_OF_TOUCHES, uncovered = uncovered)
        }
        if (!isWrite) return base
        // 写动作才吃步骤维度的两条：计划已打完勾还要改盘 ⇒ 这一步根本不在契约里
        if (step == null) return base.copy(breach = ContractBreach.BEYOND_STEPS)
        if (actual != null && actual.ordinal > step.risk.ordinal) {
            return base.copy(breach = ContractBreach.RISK_OVER_ESTIMATE)
        }
        return base
    }

    /** 这一步的允许集：全局白名单 ＋（这一步的）may_touch。 */
    private fun allowFaces(contract: PlanContract, step: PlanStep?, root: String): List<TouchFace> {
        val specs = contract.touches + (step?.mayTouch ?: contract.steps.flatMap { it.mayTouch })
        return specs.mapNotNull { faceFromSpec(it, root) }
    }

    /** 整份契约的允许集（全局 ＋ 所有步骤；对账用的那一层，比执行期宽一格是刻意的——见 [reconcile]）。 */
    fun allowAllFaces(contract: PlanContract, root: String = ""): List<TouchFace> =
        contract.allTouchSpecs.mapNotNull { faceFromSpec(it, root) }

    /** 只读区（独立于允许集的一层，写进去就算违约）。 */
    fun readonlyFaces(contract: PlanContract, root: String = ""): List<TouchFace> =
        contract.readonly.mapNotNull { faceFromSpec(it, root) }

    /**
     * 声明串 ⇒ 可比对的目标（相对路径按工作区根解析）。
     *
     * 为什么不直接字符串 startsWith：那正是 `RiskMatrix` 反复躲开的坑——
     * 「声明 `资料库/原始`、实际 `资料库/原始2/`」按字符串算前缀就漏过去了。
     * 这里按**组件**比：一条声明盖住它自己**和它子树里的一切**，兄弟目录盖不住。
     */
    private fun faceFromSpec(spec: String, root: String): TouchFace? {
        val f = TouchFace.of(spec) ?: return null
        if (f.kind != TouchFaceKind.PATH) return f
        val abs = f.value.startsWith("/") || (f.value.length >= 2 && f.value[1] == ':')
        if (abs || root.isEmpty()) return f
        return TouchFace(TouchFaceKind.PATH, TouchFace.normalizePath("$root/${f.value}"))
    }

    /** 实际目标是否落在白名单某一项之内。 */
    fun covered(actual: TouchFace, allow: List<TouchFace>): Boolean = allow.any { covers(it, actual) }

    /** 单对判定（主机名：精确或子域；路径：组件前缀）。 */
    fun covers(declared: TouchFace, actual: TouchFace): Boolean = when {
        declared.kind != actual.kind -> false
        // 白名单写了根就是"全盘"——这是声明的字面意思，风险上限那一层还在管
        declared.kind == TouchFaceKind.PATH && declared.value == "/" -> true
        declared.kind == TouchFaceKind.HOST ->
            declared.value == actual.value || actual.value.endsWith(".${declared.value}")
        else -> {
            val d = TouchFace.segments(declared.value)
            val a = TouchFace.segments(actual.value)
            d.isNotEmpty() && d.size <= a.size && d.indices.all { a[it] == d[it] }
        }
    }

    // ── D 段：勘验对账 ──────────────────────────────────────────────────────

    /** 一次真实发生过的动作（对账的输入；由引擎在派发执行之前攒下）。 */
    internal data class ActualAction(
        /** 工具调用的 id：同一次调用被**重复判定**是常态（确认卡批准后走 `executeApproved`、
         *  偏离卡扩完计划重跑一遍管线），对账只该问"这一次调用动了什么"，一次一条。 */
        val id: String,
        val tool: String,
        val faces: List<TouchFace>,
        /**
         * 风险矩阵给的那一档；null = 这一层没有本地档位（外部 MCP 工具、
         * 以及"批准后只跳第 ③ 段"的那条路径——档位在那一次判定里已经判过）。
         * 可空是**如实**：没有读数就别假装有，对账按"这一档不参与最高档比较"处理。
         */
        val risk: RiskClass?,
        val isWrite: Boolean,
        /** 当时对照契约的判定结果（跑时被拦下过的，拦下也是"计划外"这个事实）。 */
        val breach: ContractBreach = ContractBreach.NONE,
    )

    /**
     * 这一次动作在不在整份契约里（对账的独立复核：只吃事实，不吃跑时的结论）。
     *
     * 为什么要有独立复核：负例⑤「不做对账（计划与执行两张皮）」的靶心就是
     * "账只抄一遍当时的判定"——抄来的账永远对得上，那正是两张皮。
     *
     * 这一条只问**动到哪**（越白名单／写进只读区），不问档位：派工单 D 段的三项是
     * 三件事，档位那一侧由 [reconcile] 第二项单独报。在这里顺手比一次档位，
     * 一次"估保守了"会同时报出"动作在契约外"＋"风险超预估"两条不符，
     * 读起来像出了两件事——那是把账报虚。
     */
    fun outsideContract(contract: PlanContract, action: ActualAction, root: String = ""): Boolean {
        val allow = allowAllFaces(contract, root)
        if (action.faces.any { !covered(it, allow) }) return true
        if (!action.isWrite) return false
        return readonlyFaces(contract, root).any { r -> action.faces.any { covers(r, it) } }
    }

    /**
     * 跑完的勘验对账（三项读数，任一不符 ⇒ 一条 finding）。
     *
     * 1. **实际动作 ⊆ 契约**：**按事实重算一遍**（不看跑时被没被拦下），再加上跑时
     *    判过违约的次数——拦下了也是"计划外"这个事实，只是没让它真的发生。
     *    对账为什么比执行期宽一格：执行期按"当前这一步"的 may_touch 收口，
     *    对账问的是"整份计划里有没有这一步"，用 [allowAllFaces]。
     * 2. **实际风险 = 预估**：写动作里最高的那一档 vs 契约声明的最高档（高了才报，
     *    低于预估是好消息，不是不符）；
     * 3. **实际问人次数 vs expectedAsks**：两边都看，差过 [ASK_GAP] 才算——
     *    「写 0 实际问 5 次」与「写 5 次实际一次没问」都是估算没交代清楚。
     */
    fun reconcile(
        contract: PlanContract,
        actions: List<ActualAction>,
        asks: Int,
        root: String = "",
    ): ReconReport {
        val out = actions.filter { it.breach != ContractBreach.NONE || outsideContract(contract, it, root) }
        val actualMax = actions.filter { it.isWrite }.mapNotNull { it.risk }.maxByOrNull { it.ordinal }
        val findings = mutableListOf<ContractFinding>()
        if (out.isNotEmpty()) {
            val first = out.first()
            val cause = if (first.breach != ContractBreach.NONE) first.breach.name else "跑完复核发现"
            findings += ContractFinding(
                code = ContractFindingCode.ACT_OUT_OF_CONTRACT,
                detail = "有 ${out.size} 次动作在契约之外（$cause：${first.tool}）",
                count = out.size,
            )
        }
        val declared = contract.declaredMaxRisk
        if (declared != null && actualMax != null && actualMax.ordinal > declared.ordinal) {
            findings += ContractFinding(
                code = ContractFindingCode.RISK_OVER_ESTIMATE,
                detail = "实际改盘到了 $actualMax，计划里预估最高只有 $declared",
            )
        }
        if (asks > contract.expectedAsks + ASK_GAP) {
            findings += ContractFinding(
                code = ContractFindingCode.ASKS_OVER_ESTIMATE,
                detail = "计划预估问 ${contract.expectedAsks} 次，实际问了 $asks 次",
                count = asks - contract.expectedAsks,
            )
        } else if (asks + ASK_GAP < contract.expectedAsks) {
            findings += ContractFinding(
                code = ContractFindingCode.ASKS_UNDER_ESTIMATE,
                detail = "计划预估问 ${contract.expectedAsks} 次，实际只问了 $asks 次",
                count = contract.expectedAsks - asks,
            )
        }
        return ReconReport(
            totalActions = actions.size,
            writeActions = actions.count { it.isWrite },
            asks = asks,
            expectedAsks = contract.expectedAsks,
            declaredMaxRisk = declared,
            actualMaxRisk = actualMax,
            outOfContract = out.size,
            findings = findings,
        )
    }

    /** 对账读数的一句人话（收尾 notice 与日志共用；null ⇒ 三项都对得上，不多说）。 */
    fun reconBrief(report: ReconReport): String? {
        if (report.isClean) return null
        return "计划与执行对不上账：" + report.findings.joinToString("；") { it.detail }
    }

    /**
     * 「下次同类计划把这条计入估算」（派工单 D 段最后一句）。
     *
     * 落地方式不是改哪张表，而是**把这次的不符回灌给下一次写计划**：
     * 这一句跟在计划提醒后面，模型下一版写契约时按它把 expected_asks/风险档往上调。
     */
    fun estimateHint(report: ReconReport): String? {
        val over = report.findings.firstOrNull { it.code == ContractFindingCode.ASKS_OVER_ESTIMATE }
        val risk = report.findings.firstOrNull { it.code == ContractFindingCode.RISK_OVER_ESTIMATE }
        val parts = buildList {
            if (over != null) {
                add("上次同类计划预估问 ${report.expectedAsks} 次、实际问了 ${report.asks} 次——" +
                    "这次的 expected_asks 请按实际问的次数写，不要写 0")
            }
            if (risk != null) {
                add("上次同类计划把上限低估在 ${report.declaredMaxRisk}、实际动到了 ${report.actualMaxRisk}" +
                    "——这次的 steps[].risk 按真实档位写")
            }
        }
        return if (parts.isEmpty()) null else "【上次的账】" + parts.joinToString("；") + "。"
    }

    /**
     * 「发现契约外必要步骤」的那一句（C 段③：**只报不改**）。
     *
     * 里没有命令式的"加好了"，只有"要不要加"——加步骤必须走 `update_plan` ＋
     * 计划变更确认卡（140 那条线），本层绝不代用户往计划里写字（负例④）。
     */
    fun newStepNotice(intent: String): String =
        "【计划里没有这一步】「$intent」不在当前契约的步骤与白名单里。" +
            "这一步如果确实必要：先在正文里说明为什么，再由你调 update_plan 提交新计划" +
            "（那会走计划变更确认卡，用户同意才算加上了）。" +
            "不要自己把它当成计划的一部分继续跑，也不要划掉计划里原有的步骤来腾位置。"
}
