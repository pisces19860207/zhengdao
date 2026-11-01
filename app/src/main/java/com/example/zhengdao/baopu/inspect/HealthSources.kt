// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十一批 · 御医体检的**取数层**：把"每个部位有没有人调、有没有产出"从盘上读出来。
//
// 两层，界线不许糊：
// 1. [OrganCensus]：**源码事实**（调用点数、落点、证据行）。这一层不可能在运行时测——
//    手机上没有源码，而"有没有生产调用点"是编译期就定了的。所以它是写死的普查表，
//    由 `HealthCheckTest` 每次跑单测时**重扫源码核对**：接线改了而普查没改 ⇒ 红。
// 2. [HealthSources]：**运行时事实**（轨迹、账本行、产出条数）。只读：没有 `execSQL`、
//    没有追加口、没有建目录（`InspectReadOnlyGateTest` 从源码层钉）；每条 SQL 都带 `LIMIT`。
//
// 一处实话（与 239／281 同一口径）：取不到数就回 null，**绝不拿 0 顶替**。
// 体检单里"未取样"与"零产出"是两件事，把前者写成后者就是本批要纠的弊。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.BaopuDb
import java.io.File

/**
 * 部位普查表（**源码事实**，第一版 15 条，覆盖派工单 291 §覆盖部位清单）。
 *
 * 每条的数字口径（`HealthCheckTest` 按同一口径重扫）：
 * **剥掉注释后，`entry` 在 `app/src/main/**/*.kt` 里出现、且不在 `declaredIn` 那个文件里的次数。**
 * 为什么排除定义文件：自己文件里的声明与自调不算"生产调用点"，否则任何死件都能自称接线了。
 * `declaredIn` 留空 ⇒ **不排除任何文件**，数字按全仓出现次数记（只有"名字被别的门禁独占、
 * 体检表不许再写那个路径"的行才这么办，见反思器那条的注释）。
 *
 * 改接线的同时必须改这里（证据行一起改）；不改就红——"账实相符"这一条先用在体检自己身上。
 */
internal object OrganCensus {

    /** 生产调用点最多只数到这么多就够判（起居注这种高频口不必精确到个位）。 */
    const val CALL_SITES_FLOOR_ONLY = 50

    val PROBES: List<OrganProbe> = listOf(
        OrganProbe(
            name = "分类器",
            entry = "RiskMatrix.classify",
            declaredIn = "app/src/main/java/com/example/zhengdao/baopu/RiskMatrix.kt",
            callSites = 3,
            sink = "决策记录.jsonl（208 账行）",
            evidence = listOf(
                "RiskGate.kt:69 RiskMatrix.classify(call, ctx, table)",
                "RiskGate.kt:82 RiskMatrix.classify(call)",
            ),
        ),
        OrganProbe(
            name = "勘验",
            entry = "Inspector.report",
            declaredIn = "app/src/main/java/com/example/zhengdao/baopu/inspect/Inspector.kt",
            callSites = 2,
            sink = "巡按记录.jsonl",
            evidence = listOf(
                "inspect/PatrolRunner.kt:143 Inspector.report(findings, nowMs, rate)",
                "inspect/PatrolRunner.kt:175 Inspector.report(",
            ),
        ),
        OrganProbe(
            name = "计簿",
            entry = "DecisionLedger.record",
            declaredIn = "app/src/main/java/com/example/zhengdao/baopu/DecisionLedger.kt",
            // 第二百九十三批：宰相接线层搬进 `:policy`，它那处 `DecisionLedger.record(` 出了
            // 本口径的扫描根（普查只数 `app/src/main`，见上面的口径注释）⇒ 22 改 21，不是有人删了接线。
            callSites = 21,
            sink = "决策记录.jsonl",
            evidence = listOf(
                "agent/GuardPipeline.kt:634 入账点（DecisionLedger.record）",
                "AutonomySelfEdit.kt:304 入账点（同上）",
                // 第二百九十八批判据③：草稿被法典判据否掉时补一笔 208（供修正阈值）。
                "agent/LearnWiring.kt:502 入账点（同上，草稿被否）",
            ),
        ),
        OrganProbe(
            name = "影子",
            entry = "ShadowRehearsal.compare",
            declaredIn = "app/src/main/java/com/example/zhengdao/baopu/inspect/ShadowRehearsal.kt",
            // 第二百九十六批 §2/§3 之前这里是 `callSites = 0`（239 交付的就是"只有判据、没有跑的人"）。
            callSites = 1,
            sink = "影子执行.jsonl",
            evidence = listOf(
                "inspect/ShadowAdapter.kt:229 judge() 里 compare() 的唯一调用点（296 §2 接上）",
                "authz/TaskGrantGate.kt:231 shadowSignalFor() 向适配器要第⑤步的读数（296 §3）",
            ),
        ),
        OrganProbe(
            name = "宰相",
            entry = "MediatorWiring.onUserTurn",
            // 第二百九十三批：接线层搬进 `:policy`（白名单匣）。路径照实写，哪怕它已经不在
            // 本口径的扫描根里——普查的口径是"调用点数只数 app/src/main"，定义文件在哪不影响数。
            declaredIn = "policy/src/main/java/com/example/zhengdao/baopu/MediatorWiring.kt",
            callSites = 1,
            sink = "调解指令（进本轮上下文的那一句）",
            evidence = listOf(
                "ui/baopu/BaopuChatController.kt:2736 MediatorWiring.onUserTurn(",
                "agent/RunEngine.kt:2475 读回调解指令（258 的读回口）",
            ),
        ),
        OrganProbe(
            name = "学习器",
            entry = "LearnWiring.onOpinion",
            declaredIn = "app/src/main/java/com/example/zhengdao/baopu/agent/LearnWiring.kt",
            callSites = 0,
            sink = "判决经验账（进化档案-承诺.md）",
            evidence = listOf(
                "agent/LearnWiring.kt:280 onOpinion 定义，全仓零调用",
                "交付-283 §122「生产接线是下一批的一行」",
            ),
        ),
        OrganProbe(
            name = "反思器",
            entry = "onSettled",
            // 这一行**不写定义文件的路径**：265 的门禁钉着"全仓只许两个文件出现那个名字"，
            // 体检表再写一次就成了第三个 ⇒ 干脆不排除，数字按"全仓出现次数"记（含声明那一处）。
            declaredIn = "",
            callSites = 2,
            sink = "进化档案-承诺.md",
            evidence = listOf(
                "agent/RunEngine.kt:2861 收工后调一次（reflectOnSettled 的最后一句，265 R1）",
            ),
        ),
        OrganProbe(
            name = "斥候",
            entry = "(无)",
            declaredIn = "(无)",
            callSites = 0,
            present = false,
            sink = null,
            byDesign = "290 在飞（qoder 丙），本仓无 baopu/scout 目录",
            evidence = listOf(
                "在飞.md:29 290 占 baopu/scout/，基线 91562ca0",
                "grep -r 'SCOUT\\|斥候' app/src/main ⇒ 0 命中",
            ),
        ),
        OrganProbe(
            name = "起居注",
            entry = "RunLog.log",
            declaredIn = "core/src/main/java/com/example/zhengdao/core/RunLog.kt",
            callSites = 266,
            sink = "run_log（DB 表）＋日志文件",
            evidence = listOf(
                "MainActivity.kt:115 RunLog.log(\"完成通知：请求打开会话 #\$it\")",
                "core/RunLog.kt 定义；app/src/main 里 266 处调用",
            ),
        ),
        OrganProbe(
            name = "不良人",
            entry = "NocturnalPatrol.gate",
            declaredIn = "app/src/main/java/com/example/zhengdao/baopu/inspect/NocturnalPatrol.kt",
            callSites = 0,
            sink = "暗卫记录.jsonl",
            byDesign = "夜巡自动档按 §10③ 裁定关着（PatrolScheduler.AUTO_ENABLED = false）",
            evidence = listOf(
                "inspect/PatrolScheduler.kt:28 AUTO_ENABLED = false",
                "inspect/NocturnalPatrol.kt:148 gate() 定义，全仓零调用",
            ),
        ),
        OrganProbe(
            name = "金吾卫",
            entry = "GoldenGuard.sweep",
            declaredIn = "app/src/main/java/com/example/zhengdao/baopu/inspect/GoldenGuard.kt",
            callSites = 0,
            sink = "金吾卫报告.jsonl",
            evidence = listOf(
                "inspect/GoldenGuard.kt:114 sweep() 定义，全仓零调用",
                "inspect/DecisionCenterScreen.kt:434 只接了 PatrolRunner.run(manual = true)，没接 sweep",
            ),
        ),
        OrganProbe(
            name = "中书舍人",
            entry = "RoyalDrafts.draft",
            declaredIn = "app/src/main/java/com/example/zhengdao/ui/baopu/RoyalDrafts.kt",
            callSites = 2,
            sink = "御案卡面（RoyalQueueRules.prepare）",
            evidence = listOf(
                "ui/baopu/RoyalQueueRules.kt:49 RoyalDrafts.draft(ruling, checks, s)",
                "ui/baopu/RoyalQueueRules.kt:70 RoyalDrafts.draft(ruling, checks, s)",
            ),
        ),
        OrganProbe(
            name = "调度·夜巡",
            entry = "PatrolScheduler.autoGate",
            declaredIn = "app/src/main/java/com/example/zhengdao/baopu/inspect/PatrolScheduler.kt",
            callSites = 0,
            sink = "巡按记录.jsonl",
            byDesign = "自动夜跑按 §10③ 默认关（AUTO_ENABLED = false）；手动档接通",
            evidence = listOf(
                "inspect/PatrolScheduler.kt:62 autoGate() 定义，全仓零调用",
                "ui/baopu/DecisionCenterScreen.kt:427 只用了 manualGate()",
            ),
        ),
        OrganProbe(
            name = "读数层",
            entry = "MetricsSources.snapshot",
            declaredIn = "app/src/main/java/com/example/zhengdao/baopu/inspect/MetricsSources.kt",
            // 第三百四十八批：281 交付时这里是 0（"算了没人看"）。348 把取数口接进体检编排，
            // 每次体检读一趟、翻成一行四率印在单子上 ⇒ 生产调用点 1 处（门禁按同一口径重扫）。
            callSites = 1,
            sink = "Metrics 四率（内存算，无落盘）",
            evidence = listOf(
                "inspect/MetricsWiring.kt:read() 是唯一生产调用点（348 接上；体检每次读一趟）",
                "inspect/HealthRun.kt:run() 把读数翻成一行印进体检单（只读，不触发动作）",
            ),
        ),
    )

    /** 按名字取一条（体检单只认这 15 个名字）。 */
    fun of(name: String): OrganProbe? = PROBES.firstOrNull { it.name == name }
}

/** 轨迹里一行原始读数（只有这四个字段出库；正文类字段一概不取）。 */
internal data class TraceRaw(
    val tool: String,
    val status: String,
    val durationMs: Long,
    val atMs: Long,
)

/** 轨迹里一个工具的一组读数（`run_trace` 聚合出来的，不含正文）。 */
internal data class TraceStat(
    val tool: String,
    val calls: Int,
    val ok: Int,
    val rejected: Int,
    val skipped: Int,
    val p50Ms: Long,
    val p90Ms: Long,
    val lastAtMs: Long,
)

/** 体检的取数口（供装配层调用；静态那半由 [OrganCensus] 给）。 */
internal object HealthSources {

    /** 一次最多读多少行轨迹（读数不是全库导出）。 */
    const val ROWS = 2_000

    /** 产出文件最多数多少行（数到上限就够判"有产出"）。 */
    const val OUTPUT_LINES = 5_000

    /** 轨迹按工具聚合（只取窗口内、只取有工具名的行）。 */
    fun traceStats(db: BaopuDb, fromMs: Long, limit: Int = ROWS): List<TraceStat> = runCatching {
        val rows = db.read { d ->
            val out = ArrayList<TraceRaw>()
            d.rawQuery(
                "SELECT tool, status, duration_ms, started_at FROM run_trace " +
                    "WHERE started_at >= ? AND tool <> '' ORDER BY id DESC LIMIT ?",
                arrayOf(fromMs.toString(), limit.toString()),
            ).use { c ->
                while (c.moveToNext()) out += TraceRaw(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3))
            }
            out
        }
        aggregate(rows)
    }.getOrDefault(emptyList())

    /** 纯函数：把轨迹四列聚合成每个工具一行（无 IO，可直测）。 */
    fun aggregate(rows: List<TraceRaw>): List<TraceStat> =
        rows.groupBy { it.tool }.map { (tool, list) ->
            val durations = list.map { it.durationMs }.sorted()
            TraceStat(
                tool = tool,
                calls = list.size,
                ok = list.count { it.status == "OK" },
                rejected = list.count { it.status == "REJECTED" },
                skipped = list.count { it.status == "SKIPPED" },
                p50Ms = percentile(durations, 50),
                p90Ms = percentile(durations, 90),
                lastAtMs = list.maxOfOrNull { it.atMs } ?: 0L,
            )
        }.sortedBy { it.tool }

    /** 百分位（最近秩法；空表回 0）。 */
    fun percentile(sorted: List<Long>, p: Int): Long {
        if (sorted.isEmpty()) return 0L
        val n = sorted.size
        val idx = ((p * n + 99) / 100 - 1).coerceIn(0, n - 1)
        return sorted[idx]
    }

    /**
     * 数一份 JSONL 账本的行数（**产出数**就是这么来的）。
     *
     * 三态：文件不在 ⇒ 0（真的没产出）；读不动 ⇒ null（测不出来）；否则行数（数到上限为止）。
     */
    fun outputLines(root: File, name: String, cap: Int = OUTPUT_LINES): Int? {
        val file = File(root, name)
        if (!file.isFile) return 0
        return runCatching {
            file.bufferedReader(Charsets.UTF_8).use { r ->
                var n = 0
                while (n < cap && r.readLine() != null) n++
                n
            }
        }.getOrNull()
    }

    /**
     * 把普查表 + 轨迹读数拼成体检的输入（缺哪路就那一路为空，绝不编数）。
     *
     * @param measured 轨迹表里**有没有任何一行**。有 ⇒ 表里没出现的工具就是"窗内 0 次调用"
     *   （这是"理解卡"的唯一来源）；没有 ⇒ 全部记未取样（没数据源时不许把"看不见"说成"没人用"）。
     */
    fun probes(
        stats: List<TraceStat>,
        visible: Set<String>,
        groupOf: Map<String, String>,
        allTools: Collection<String>,
        measured: Boolean = stats.isNotEmpty(),
    ): List<ToolProbe> {
        val byTool = stats.associateBy { it.tool }
        return allTools.sorted().map { name ->
            val s = byTool[name]
            ToolProbe(
                name = name,
                group = groupOf[name] ?: "?",
                visibleByDefault = name in visible,
                calls = if (measured) (s?.calls ?: 0) else null,
                ok = s?.ok ?: 0,
                rejected = s?.rejected ?: 0,
                p50Ms = s?.p50Ms ?: 0L,
                p90Ms = s?.p90Ms ?: 0L,
                skipReason = if ((s?.skipped ?: 0) > 0) "SKIPPED ${s!!.skipped} 次" else null,
            )
        }
    }
}
