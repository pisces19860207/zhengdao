// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百六十三批 · 偏差账（Deviation Ledger）：五类事实**只记不判**。
//
// 来历（派工单-2026-11-02-第三百六十三批）：四时闭环表里「进行时」缺的第一件——
// 超步数/超时/打扰超额/绕过确认/挂起超时这些偏差现在**不记**，验收时只能看结果，
// 看不到"它偷偷多跑了几步"。
//
// 四条硬规矩（派工单 §二，逐条有测试，见 DeviationLedgerTest）：
//   1. **静默**：本文件零 UI、零卡、零回调、零意见（Opinion）——记完就完；
//      落账失败也只留一行日志，不抛、不连累主流程（判据②）；
//   2. **只追加**：一行一次，不覆盖、不改旧（"当时记了什么"本身是证据）；
//   3. **带出处**：runId 必填（构造期拦空），任务 id 有则必带——验收能追到"哪一单"；
//   4. **不判决**：不写"危险/越权"，只写事实。判决词闭集在 [DeviationLedger.JUDGMENT_MARKERS]，
//      本文件**自己产出**的每一行（wire／类名／事实句／收工读数）被测试钉死零判决词；
//      调用方塞进来的事实原样存（记账不改笔迹——它说了什么本身就是证据），
//      检测侧想夹判决私货走五个工厂：键值闭集，判决词从形状上进不来。
//      判决归右相/中心模型，账本只供数。
//
// 边界（派工单 §四）：不接"自动干预"（超预算自动停是 354 之后的事）；不进 208 主账，
// 单独一本 [FILE_NAME]——挂载区与体检账同源（BaopuRuntime 冷启动 install，`抱朴记忆/`），
// 不在用户工作区，不污染业务账。引擎收工读数只认 [DeviationLedger.installedRoot]，
// 不回头碰 DecisionLedger（引擎那侧的 C5 红线：反思切片里不许出现 208 的写口）。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONObject

/** 五类偏差（`wire` 是账行的稳定键；`cn` 只进给人看的读数——两列都是事实，不带判词）。 */
internal enum class DeviationKind(val wire: String, val cn: String) {

    /** 步数 > 契约上限。 */
    OVER_STEPS("over_steps", "超步数"),

    /** 单步或整体超过预算。 */
    TIMEOUT("timeout", "超时"),

    /** 弹卡次数 > 授权次数。 */
    INTERRUPT_OVER("interrupt_over", "打扰超额"),

    /** 走了"免打扰"却做了不可逆动作。 */
    BYPASS_CONFIRM("bypass_confirm", "绕过确认"),

    /** 等用户超 N 小时。 */
    SUSPEND_TIMEOUT("suspend_timeout", "挂起超时"),
    ;

    companion object {

        /** 线值 ⇒ 类；认不出 ⇒ null（自造类不渲染，也不静默当某一类）。 */
        fun ofWire(wire: String): DeviationKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 一条偏差账行（JSONL 一行一条）。
 *
 * @param id 证据 id（工厂给的 `dev:<runId>#<atMs>`；**非唯一键**——账行的单位是行，检索以 [runId] 为准）
 * @param atMs 记账时刻（毫秒）
 * @param runId 出处一：哪一轮（必填，构造期拦空——硬规矩 3）
 * @param taskId 出处二：哪一单（有任务号的轮必填；没有就留空串——出处至少要有 runId）
 * @param kind 五类之一
 * @param facts 事实键值（闭集键见五个工厂；值是量出来的数与摘录，账本原样存不改笔迹）
 */
internal data class DeviationEntry(
    val id: String,
    val atMs: Long,
    val runId: String,
    val taskId: String,
    val kind: DeviationKind,
    val facts: Map<String, String>,
) {

    init {
        require(runId.isNotBlank()) { "偏差账必须带出处：runId 不许空（硬规矩 3）" }
    }

    /** 给人看的一句事实（键的中文说法按 [DeviationLedger.FACT_LABELS]；认不出的键照抄键名）。 */
    fun describe(): String = kind.cn + "：" + facts.entries.joinToString("；") { (k, v) ->
        "${DeviationLedger.FACT_LABELS[k] ?: k} $v"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("v", DeviationLedger.VERSION)
        .put("id", id)
        .put("at", atMs)
        .put("runId", runId)
        .put("taskId", taskId)
        .put("kind", kind.wire)
        .put("facts", JSONObject().also { o -> facts.forEach { (k, v) -> o.put(k, v) } })

    companion object {

        /** 事实键（闭集；五个工厂是唯一产出口，改键名先改这里再改工厂）。 */
        const val F_STEPS = "steps"
        const val F_LIMIT = "limit"
        const val F_AT_STEP = "at_step"
        const val F_ELAPSED_MS = "elapsed_ms"
        const val F_LIMIT_MS = "limit_ms"
        const val F_TOOL = "tool"
        const val F_COUNT = "count"
        const val F_TRIGGER = "trigger"
        const val F_ACTION = "action"
        const val F_AUTHORITY = "authority"
        const val F_REVERSIBLE = "reversible"
        const val F_SUSPENDED_AT = "suspended_at"
        const val F_QUESTION = "question"
        const val F_PENDING = "pending"

        /** 行的 id 拼法（与 TraceEdge.evidenceId 同风格；同刻两条同 run ⇒ id 同、行仍是两行）。 */
        fun idOf(runId: String, atMs: Long): String = "dev:$runId#$atMs"

        // ── 五个工厂（派工单 §一那张表，一行一类；键值全闭集，判决词从形状上进不来） ──

        /** 超步数：实到步数 / 上限 / 在哪一步越过。 */
        fun overSteps(
            runId: String,
            taskId: String,
            atMs: Long,
            steps: Int,
            limit: Int,
            atStep: Int,
        ): DeviationEntry = DeviationEntry(
            id = idOf(runId, atMs), atMs = atMs, runId = runId, taskId = taskId,
            kind = DeviationKind.OVER_STEPS,
            facts = mapOf(F_STEPS to "$steps", F_LIMIT to "$limit", F_AT_STEP to "$atStep"),
        )

        /** 超时：实到毫秒 / 上限 / 卡在哪个工具（没卡到具体工具留空串，不猜）。 */
        fun timeout(
            runId: String,
            taskId: String,
            atMs: Long,
            elapsedMs: Long,
            limitMs: Long,
            tool: String = "",
        ): DeviationEntry {
            val facts = buildMap {
                put(F_ELAPSED_MS, "$elapsedMs")
                put(F_LIMIT_MS, "$limitMs")
                if (tool.isNotBlank()) put(F_TOOL, tool)
            }
            return DeviationEntry(
                id = idOf(runId, atMs), atMs = atMs, runId = runId, taskId = taskId,
                kind = DeviationKind.TIMEOUT, facts = facts,
            )
        }

        /** 打扰超额：第几次超 / 触发条件。 */
        fun interruptOver(
            runId: String,
            taskId: String,
            atMs: Long,
            count: Int,
            trigger: String,
        ): DeviationEntry = DeviationEntry(
            id = idOf(runId, atMs), atMs = atMs, runId = runId, taskId = taskId,
            kind = DeviationKind.INTERRUPT_OVER,
            facts = mapOf(F_COUNT to "$count", F_TRIGGER to trigger),
        )

        /** 绕过确认：动作 / 授权依据 / 是否可回退（三个都是量出来的事实位）。 */
        fun bypassConfirm(
            runId: String,
            taskId: String,
            atMs: Long,
            action: String,
            authority: String,
            reversible: Boolean,
        ): DeviationEntry = DeviationEntry(
            id = idOf(runId, atMs), atMs = atMs, runId = runId, taskId = taskId,
            kind = DeviationKind.BYPASS_CONFIRM,
            facts = mapOf(
                F_ACTION to action, F_AUTHORITY to authority, F_REVERSIBLE to if (reversible) "是" else "否",
            ),
        )

        /** 挂起超时：挂起时刻 / 问题摘要 / 仍挂起。 */
        fun suspendTimeout(
            runId: String,
            taskId: String,
            atMs: Long,
            suspendedAtMs: Long,
            question: String,
            stillPending: Boolean,
        ): DeviationEntry = DeviationEntry(
            id = idOf(runId, atMs), atMs = atMs, runId = runId, taskId = taskId,
            kind = DeviationKind.SUSPEND_TIMEOUT,
            facts = mapOf(
                F_SUSPENDED_AT to "$suspendedAtMs", F_QUESTION to question,
                F_PENDING to if (stillPending) "是" else "否",
            ),
        )
    }
}

/** 偏差账的读写（**只追加**；写失败不抛只留日志；读侧带上界；整本零判决词）。 */
internal object DeviationLedger {

    const val FILE_NAME = "偏差账.jsonl"

    const val VERSION = 1

    /** 一次最多读几条（一条一行，200 条＝五类各几十次的余量；再多也没人看）。 */
    const val READ_LIMIT = 200

    /** 事实键的中文说法（describe 用；与 [DeviationEntry] 的 F_ 键一一对应）。 */
    val FACT_LABELS: Map<String, String> = mapOf(
        DeviationEntry.F_STEPS to "实到步数",
        DeviationEntry.F_LIMIT to "上限",
        DeviationEntry.F_AT_STEP to "越过的那一步",
        DeviationEntry.F_ELAPSED_MS to "实到毫秒",
        DeviationEntry.F_LIMIT_MS to "上限毫秒",
        DeviationEntry.F_TOOL to "卡在工具",
        DeviationEntry.F_COUNT to "第几次超",
        DeviationEntry.F_TRIGGER to "触发条件",
        DeviationEntry.F_ACTION to "动作",
        DeviationEntry.F_AUTHORITY to "授权依据",
        DeviationEntry.F_REVERSIBLE to "可回退",
        DeviationEntry.F_SUSPENDED_AT to "挂起时刻",
        DeviationEntry.F_QUESTION to "问题摘要",
        DeviationEntry.F_PENDING to "仍挂起",
    )

    /**
     * 判决词黑名单（硬规矩 4 的闭集；给测试与自检用，账本自己不产也不改笔迹）。
     * 收词口径：给"人/动作"定性的话，而不是量出来的数——要加词先过派工单那句
     * "不写危险/越权"，能换成数字与摘录的都不算判决词。
     */
    val JUDGMENT_MARKERS: List<String> = listOf("危险", "越权", "违规", "恶意", "滥用", "失控")

    fun fileIn(root: File): File = File(root, FILE_NAME)

    // ── 挂载口（只存 File 不存 Context——StaticFieldLeak 的教训；BaopuRuntime 冷启动装一次） ──

    @Volatile private var mounted: File? = null

    /** 生产装配（与决策记录同区：`<App私有>/抱朴记忆/`；重复 install 以最后一次为准）。 */
    fun install(globalRoot: File) {
        mounted = globalRoot
    }

    /** 已挂载的落盘区；没装（JVM 存量构造点）＝ null，读数口照此惰性跳过。 */
    fun installedRoot(): File? = mounted

    /** 追加一条；返回有没有落上（落不上只记一行日志，不抛——硬规矩 1＋判据②）。 */
    fun write(root: File, entry: DeviationEntry): Boolean = runCatching {
        val f = fileIn(root)
        f.parentFile?.mkdirs()
        f.appendText(entry.toJson().toString() + "\n", Charsets.UTF_8)
        true
    }.onFailure {
        RunLog.log("偏差账：没落上（${entry.id}）——${it.javaClass.simpleName}: ${it.message}")
    }.getOrDefault(false)

    /** 读账（新→旧，最多 [limit] 条；坏行认不出 ⇒ 丢掉，不当清白也不当有账）。 */
    fun read(root: File, limit: Int = READ_LIMIT): List<DeviationEntry> = runCatching {
        val f = fileIn(root)
        if (!f.isFile) return emptyList()
        f.readLines(Charsets.UTF_8)
            .mapNotNull(::parse)
            .takeLast(limit.coerceAtLeast(1))
            .reversed()
    }.getOrDefault(emptyList())

    /** 某一轮的偏差条数（收工读数的分母；整本扫——一条一行，账小）。 */
    fun countForRun(root: File, runId: String): Int {
        if (runId.isBlank()) return 0
        return runCatching {
            val f = fileIn(root)
            if (!f.isFile) return 0
            f.readLines(Charsets.UTF_8).count { line -> parse(line)?.runId == runId }
        }.getOrDefault(0)
    }

    /** 收工报告那一行（判据④；没账＝0 条照说，不许吞读数）。 */
    fun reportLine(root: File, runId: String): String = "偏差 ${countForRun(root, runId)} 条"

    /** 认不出的行 ⇒ null（坏行不许冒充账行；kind 不认识也一样丢）。 */
    private fun parse(raw: String): DeviationEntry? = runCatching {
        val o = JSONObject(raw)
        val kind = DeviationKind.ofWire(o.optString("kind")) ?: return null
        DeviationEntry(
            id = o.optString("id"),
            atMs = o.optLong("at"),
            runId = o.optString("runId"),
            taskId = o.optString("taskId"),
            kind = kind,
            facts = o.optJSONObject("facts")?.let { f ->
                buildMap { f.keys().forEach { k -> put(k, f.optString(k)) } }
            }.orEmpty(),
        )
    }.getOrNull()
}
