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
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.policy.SelfDecideLog
import java.io.File

/**
 * 部位普查表（**源码事实**；条目数不是本表的一部分，见下）。
 *
 * 每条的数字口径（`HealthCheckTest` 按同一口径重扫）：
 * **剥掉注释后，`entry` 在 `app/src/main/**/*.kt` 里出现、且不在 `declaredIn` 那个文件里的次数。**
 * 为什么排除定义文件：自己文件里的声明与自调不算"生产调用点"，否则任何死件都能自称接线了。
 * `declaredIn` 留空 ⇒ **不排除任何文件**，数字按全仓出现次数记（只有"名字被别的门禁独占、
 * 体检表不许再写那个路径"的行才这么办，见反思器那条的注释）。
 *
 * 改接线的同时必须改这里（证据行一起改）；不改就红——"账实相符"这一条先用在体检自己身上。
 *
 * 第三百二十六批：表名从 `PROBES` 改成 **`HEALTH_PARTS`**（"清单"这件事要说在名字上），
 * 并把原来钉在闸里的 `assertEquals(14, ...)` 拆掉——**条目数不是判据**。
 * 加第 15 个部位的正确做法就是往这张表里加一项：闸只问 [evidenceGaps] 那一件事
 * （非空 ＋ 每项有出处），不问有几项。数量唯一有意义的场合是"这张表空没空"
 * ——空表会被读成"全都没问题"，那是最坏的一种谎。
 */
internal object OrganCensus {

    /** 生产调用点最多只数到这么多就够判（起居注这种高频口不必精确到个位）。 */
    const val CALL_SITES_FLOOR_ONLY = 50

    /** 读数层的部位名（HealthRun.organOutputs 按它把读数产出接进部位档；改字面量＝两处一起改）。 */
    const val READING_ORGAN = "读数层"

    /** 部位清单（唯一的清单；体检单就是照这张表一行一行问下来的）。 */
    val HEALTH_PARTS: List<OrganProbe> = listOf(
        OrganProbe(
            name = "分类器",
            entry = "RiskMatrix.classify",
            declaredIn = "app/src/main/java/com/example/zhengdao/baopu/RiskMatrix.kt",
            // 第三百五十八批：军师（推演盘）判风险档时调的是矩阵本体，多出第 4 处 ⇒ 3 改 4。
            // 它只取档位：不走 `RiskGate` 的时间窗、不写 208 账（派单【隔离】③「军师不写任何账」）。
            // 第四百一十七批：改约落地后用新参数重算风险档，判定层旧/新参数各调矩阵一次
            // ⇒ 4 改 6。这两处**只调矩阵本体**，不另写第二套判定（派工单判据④）。
            callSites = 6,
            sink = "决策记录.jsonl（208 账行）",
            evidence = listOf(
                "RiskGate.kt:69 RiskMatrix.classify(call, ctx, table)",
                "RiskGate.kt:82 RiskMatrix.classify(call)",
                "agent/RehearsalRunner.kt:281 军师取档位（358：读生产表、不落账）",
                "plan/AmendRisk.kt:84 RiskMatrix.classify(probe.before, ctx, table)（417：改约前旧参数）",
                "plan/AmendRisk.kt:85 RiskMatrix.classify(probe.after, ctx, table)（417：改约后新参数）",
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
            // 409 裁定：影子降为**按需**触发（不可逆动作前预演／右相判 UNVERIFIABLE 时复核），
            // 其余时候不跑——所以「窗内没跑」不是零产出，体检按运行记录判（两维度分开）。
            byDesign = "按需触发（409 裁定：不可逆前／UNVERIFIABLE 复核才跑，不按周期）",
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
            // 508：收工链多了归因这一句（AttributionWiring.onSettled），全仓 2→4（两处定义＋两处调用）。
            callSites = 4,
            sink = "进化档案-承诺.md",
            evidence = listOf(
                "agent/RunEngine.kt:3590 收工后调一次（reflectOnSettled 的最后一句，265 R1）",
                "agent/AttributionWiring.kt:33 onSettled 定义（508 归因装配壳）",
                "agent/RunEngine.kt:3598 收工归因同列调一次（508 接线）",
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
            // 第四百三十二批：自动档接上（2026-11-04 用户拍板 A）——0 改 1，运行器（NocturnalAuto）
            // 每周抽一轮、运行记录落暗卫记录.jsonl，体检「抽检读数」块有得上读。
            callSites = 1,
            sink = "暗卫记录.jsonl",
            byDesign = "432 拍板 A：每周自动抽检 1 次（充电+WiFi+非值守；可关·只做读开关）",
            evidence = listOf(
                "inspect/NocturnalAuto.kt:111 NocturnalPatrol.gate( 生产调用点（432 接上；配额与充电闸）",
                "inspect/NocturnalPatrol.kt:154 gate() 定义（一键停/四前提/双闸配额，一字未动）",
            ),
        ),
        OrganProbe(
            name = "金吾卫",
            entry = "GoldenGuard.sweep",
            declaredIn = "app/src/main/java/com/example/zhengdao/baopu/inspect/GoldenGuard.kt",
            // 第四百零九批：决策中心接上「金吾卫巡检」那颗按钮（279 §2 的"用户点名全量巡"），
            // 0 改 1——从此金吾卫能真跑、运行记录（金吾卫报告.jsonl）能真落，体检才有得上读。
            callSites = 1,
            sink = "金吾卫报告.jsonl",
            evidence = listOf(
                "ui/baopu/DecisionCenterScreen.kt 巡检栏「金吾卫巡检」按钮调 GoldenGuard.sweep（409 接上）",
                "inspect/GoldenGuard.kt:114 sweep() 定义（279 §2：用户点名全量巡，运行记录一行一份）",
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
            entry = "Opportunistic.decide",
            declaredIn = "app/src/main/java/com/example/zhengdao/baopu/inspect/Opportunistic.kt",
            // 第四百三十二批：周位（每周 1 次自动抽检）接上；第五百一十一批把**触发**换成机会式，
            // 判据搬到 `Opportunistic`（手动／负载／7 天下限＋机会条件＋冷却），两件装配各一处调用：
            // 暗卫 `NocturnalAuto`、巡按 `PatrolAuto`。§10③ 的"每晚低峰自动夜跑"（autoGate）仍按裁定关着。
            callSites = 2,
            sink = "暗卫记录.jsonl／巡按记录.jsonl（自动档一行一轮/一趟）",
            byDesign = "自动夜跑按 §10③ 默认关（AUTO_ENABLED = false）；511 机会式触发开着；手动档接通",
            evidence = listOf(
                "inspect/NocturnalAuto.kt:runDue Opportunistic.decide( 生产调用点（511 接上）",
                "inspect/PatrolAuto.kt:runDue Opportunistic.decide( 生产调用点（511 接上）",
                "ui/baopu/DecisionCenterScreen.kt:427 只用了 manualGate()（手动档）",
            ),
        ),
        OrganProbe(
            name = READING_ORGAN,
            entry = "MetricsSources.snapshot",
            declaredIn = "app/src/main/java/com/example/zhengdao/baopu/inspect/MetricsSources.kt",
            // 第三百四十八批：281 交付时这里是 0（"算了没人看"）。348 把取数口接进体检编排，
            // 每次体检读一趟、翻成一行四率印在单子上 ⇒ 生产调用点 1 处（门禁按同一口径重扫）。
            // 第四百零三批：产出计数接进部位档（HealthRun.readingOutputs）——四率在内存里、
            // 不落盘，SINK_FILES 测不出它 ⇒ 以前这里被判"未取样"、态线空串，如今按读数真判。
            callSites = 1,
            sink = "Metrics 四率（内存算，无落盘）",
            evidence = listOf(
                "inspect/MetricsWiring.kt:read() 是唯一生产调用点（348 接上；体检每次读一趟）",
                "inspect/HealthRun.kt:run() 把读数翻成一行印进体检单（只读，不触发动作）",
                "inspect/HealthRun.kt:organOutputs() 把产出计数接进部位档（403；态线不再空串）",
            ),
        ),
    )

    /** 按名字取一条（体检单只认这张表里的名字）。 */
    fun of(name: String): OrganProbe? = HEALTH_PARTS.firstOrNull { it.name == name }

    /**
     * 表里"没有出处"的项（**闸唯一认的那条判据**；返回空 ＝ 这张表立得住）。
     *
     * 为什么只认这一条、不认数量：部位数是**数据**，不是判据。把它写成判据的那一天起，
     * "加第 15 个部位"就变成"先改闸"，而闸一改就看不出是新增还是掩盖（315 自报的第②条
     * 就是这个形状：`assertEquals(14, PROBES.size)`）。这里的每一条都能被单项数据违反：
     *
     * - 表空 ⇒ 一条（空单读起来像"全都没问题"，比少一行更坏）；
     * - 没名字 / 重名 ⇒ 体检单按名字取数与去重，无名或重名会互相顶掉；
     * - `evidence` 空或全是空白行 ⇒ 这一项的结论没有出处；
     * - `present` 却没写 `entry` ⇒ 说它接线了，但没说接的是哪个口；
     * - 记成"不存在"（`present = false`）却没说凭什么（`byDesign` 空）⇒ 缺席没有出处。
     *
     * @param parts 默认查真表；测试给一张坏表来证明这条判据真会红
     */
    fun evidenceGaps(parts: List<OrganProbe> = HEALTH_PARTS): List<String> {
        if (parts.isEmpty()) return listOf("部位表是空的：体检单会变成一张空单，读起来像「全都没问题」")
        val gaps = ArrayList<String>()
        val seen = HashSet<String>()
        parts.forEachIndexed { i, p ->
            val tag = if (p.name.isBlank()) "第 ${i + 1} 项（无名）" else p.name
            if (p.name.isBlank()) {
                gaps += "$tag：没有名字（体检单按名字取数）"
            } else if (!seen.add(p.name)) {
                gaps += "$tag：重名（同名两项会互相顶掉，后一项说了算）"
            }
            if (p.evidence.none { it.isNotBlank() }) {
                gaps += "$tag：没有出处（evidence 一条都没有）"
            } else if (p.evidence.any { it.isBlank() }) {
                gaps += "$tag：出处里有空白行（占位符不是出处）"
            }
            if (p.present && p.entry.isBlank()) {
                gaps += "$tag：说它接线了，却没写 entry（接的是哪个口）"
            }
            if (!p.present && p.byDesign.isNullOrBlank()) {
                gaps += "$tag：记成「不存在」，却没写 byDesign（凭什么说没有）"
            }
        }
        return gaps
    }
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

    // ── 331：自决账的三读数（只读；分母全是外部事实）────────────────────────

    /** 自决账文件名（331）：一行一张自决单／一条必须问／一条推翻。 */
    const val SELF_LOG = "自决单.jsonl"

    /**
     * 读自决账原文（只读，带上限）。
     *
     * 三态：文件不在 ⇒ 空表（真的一笔没记，不是"看不见"）；读不动 ⇒ null（测不出来）；
     * 否则原文行。**不解析、不猜**——解析归 [SelfDecideLog.parse]，本层只搬字节。
     */
    fun selfLines(root: File, cap: Int = OUTPUT_LINES): List<String>? {
        val file = File(root, SELF_LOG)
        if (!file.isFile) return emptyList()
        return runCatching {
            file.bufferedReader(Charsets.UTF_8).use { r ->
                val out = ArrayList<String>()
                while (out.size < cap) {
                    out += r.readLine() ?: break
                }
                out
            }
        }.getOrNull()
    }

    /**
     * 三读数（自决率／误自决率／打扰比）＋ 跨账缺口，一行一条交给体检单。
     *
     * 分母全是**外部事实**：自决账的行数、208 决策账的行数。
     * **不取意见条数**（`Opinion` 是判据自己的产物，拿它当分母等于自己量自己）。
     *
     * @param ledgerTotal 208 决策账行数；null ＝ 现数（[outputLines] 只读、带上限）
     */
    fun selfReadings(root: File, ledgerTotal: Int? = null): List<String> {
        val lines = selfLines(root)
            ?: return listOf("自决读数：自决账读不动（未取样），三率都不印")
        val log = SelfDecideLog.parse(lines)
        val total = ledgerTotal ?: outputLines(root, DecisionLedger.FILE_NAME)
        val gaps = ArrayList<String>()
        if (lines.size > log.size) {
            gaps += "自决账有 ${lines.size - log.size} 行读不懂（按坏行丢弃，不进三率）"
        }
        return log.readings(total).lines() + gaps
    }
}
