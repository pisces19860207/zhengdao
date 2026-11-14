// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百二十七批 · 准入的**接线口**（四步链与门见 [AdmissionFlow]／[AdmissionGate]，384-A；
// 画像见 com.example.zhengdao.baopu.policy.CapabilityProfile，365）。
//
// 来历：384-A 交付时如实记账——「AdmissionFlow.run 生产零调用方，门造好了没人敲门」；
// 418 复核同口径。本批把门接上：**外部执行体（MCP 服务器）第一次调工具时**，未登记才走
// 准入四步；App 自身内部调用（非 MCP 组）视为已认证，不进准入（派工单 427 判据②）。
//
// 执行体身份从哪来（不新建"执行体注册处"——登记处就是 384-A 造好的画像快照＋准入账）：
//   * MCP 工具的 wire 名形如 `mcp_<serverSlug>_<toolSlug>`（McpSchema.wireName，超长走
//     哈希后缀）——**最后一个下划线**之前就是 serverSlug（slug 内部的下划线都保留），
//     所以 `substringBeforeLast('_')` 能稳定还原 server 侧；前面统一加 `mcp-` 作 agentId。
//   * 现行版本指纹 ＝ 当前注册表里全部 MCP 工具名排序相连——server 的工具集一变
//     （连了新 server／断了／换了表），指纹就对不上 ⇒ needsReprobe 说真话。
//
// 三条纪律（照 DeviationWiring 的房型）：
//   1. **静默**：没挂根目录（[install] 没调）⇒ 一个字节不写、不抛、不打日志——
//      登记永远不连累主流程；
//   2. **只登记不拦人**：准入结论（含 reject_incompetent）只落盘留痕＋一行日志，
//      **不改管线的任何判定**（派工单判据③「只加调用不改判定」）；
//   3. **未登记才走**：快照已在（本进程或上一进程登记的）⇒ 不重跑四步，只读画像
//      （canAccept／needsReprobe——派工单判据③的读画像就在这里），对不上才说话。
//      每次现读快照（几 KB 的小文件，MCP 调用本身是网络往返）——不养内存缓存，
//      就没有"缓存说登记了、盘上没有"的第二种状态。
//
// 第五百四十六批（L3 出题接线）：上面第 3 条的"未登记才走"原先喂的是 `emptyMap()`——九道题面
// 零引用，判卷器只能按「未考＝没过」判，真本事与假本事同一个档。现在改成先经 [IntakeExam] 真出
// 一次题（5 探针＋4 品行），把量出来的**真 traces** 喂四步链。三点不变：
//   * [onExternalToolCall] 的签名与管线调用点一个字没动（546 判据⑤）；
//   * 出题只在首调发生，[IntakeExam] 自带 (题目版本, 执行体版本) 缓存，再调不重考；
//   * 没装出题通道（[installChannel] 没调）⇒ 空卷 ⇒ 出题结论 UNVERIFIABLE、traces 仍为空 ⇒
//     门照旧给 reject_incompetent。**空卷两头都不 PASS**；真通道归 548（MCP＋A2A 接入）。
package com.example.zhengdao.baopu.intake

import java.io.File

internal object AdmissionWiring {

    /** 生产装配（与偏差账同区：`<App私有>/抱朴记忆/`；重复 install 以最后一次为准）。 */
    fun install(globalRoot: File) {
        root = globalRoot
    }

    /**
     * 装出题通道（L3 的缝；546）。**生产真通道归 548**：MCP 现在只有 `callTool`，没有"派一件活
     * 给外部执行体"的任务原语，所以这里不假装已经通了——没装就是空卷 UNVERIFIABLE，如实记账。
     * 传 null 即摘通道（与 [resetForTest] 同效，但保留根目录）。
     */
    fun installChannel(examChannel: ExamChannel?) {
        channel = examChannel
    }

    /** 测试摘挂载（进程级状态不清就是随执行顺序变红——`DeviationWiring.resetForTest` 同款）。 */
    fun resetForTest() {
        root = null
        channel = null
        routeSink = null
    }

    /**
     * 外部执行体的工具调用经管线时都过这里（管线在 MCP 组的调用上接的一行）。
     *
     * @param wireName 这次调用的工具 wire 名（`mcp_<server>_<tool>`）
     * @param readOnly 这次调用的工具在注册表上的只读标记（false＝按写处理）
     * @param currentNames 注册表当前的 MCP 工具名全集（版本指纹的原料；调用方现取）
     * @param log 日志口（生产走管线注入的 RunLog 通道；没挂根目录时一个字都不会来）
     */
    fun onExternalToolCall(
        wireName: String,
        readOnly: Boolean,
        currentNames: List<String>,
        log: (String) -> Unit,
    ) {
        val dir = root ?: return
        runCatching { admit(dir, wireName, readOnly, currentNames, log) }
            .onFailure { log("抱朴准入：登记失败（不影响本次调用）${it.javaClass.simpleName}") }
    }

    // ── 内部：登记与读画像（判定全在 384-A 的门里，本文件不判）─────────────────

    @Volatile private var root: File? = null

    @Volatile private var channel: ExamChannel? = null

    /**
     * 第五百六十八批 C 域②：新 peer 准入的路由候选出口（装配层注入 → `RouteEvo.onPeerAdmitted`）。
     *
     * 与准入判定分开：546 的四步链/画像/登记一字未动，这一路只在「登记完」这个既有动作边上
     * 出一次路由偏好候选（选哪个 peer 是路由输入，不碰五流状态机）。null = 没人听（候选不产，准入照旧）。
     */
    @Volatile
    var routeSink: ((agentId: String, atMs: Long) -> Unit)? = null

    private fun admit(
        dir: File,
        wireName: String,
        readOnly: Boolean,
        currentNames: List<String>,
        log: (String) -> Unit,
    ) {
        val agentId = agentIdOf(wireName)
        val version = versionOf(currentNames)
        val kind = if (readOnly) TaskKind.READ_ONLY else TaskKind.WRITES
        val known = AdmissionFlow.load(dir, agentId)
        if (known != null) {
            // 已登记：不重跑四步，只读画像（判据③——canAccept／needsReprobe 在这里被真读）。
            val canAccept = known.profile.canAccept(kind, version)
            val reprobe = known.profile.needsReprobe(version)
            // 画像与现状对得上就闭嘴（MCP 每次调用都来，别刷屏）；对不上才说一行。
            if (!canAccept || reprobe) {
                log(
                    "抱朴准入：外部执行体「$agentId」画像与现状对不上" +
                        "（canAccept($kind)=$canAccept · needsReprobe=$reprobe）——${known.line()}",
                )
            }
            return
        }
        // 未登记：先真出一次题（546 的 L3），再把量出来的真卷喂四步成链。
        // 没装通道 ⇒ 空卷 ⇒ IntakeExam 记 UNVERIFIABLE、traces 为空 ⇒ 门照 345 口径给 reject_incompetent。
        val exam = IntakeExam.take(dir, agentId, version, channel)
        val record = AdmissionFlow.run(
            AdmissionFlow.Request(
                agentId = agentId,
                version = version,
                probedAtMs = System.currentTimeMillis(),
                selfReport = SelfReport(agentId = agentId, version = version, claimedCapabilities = emptyList()),
                probeTraces = exam.probeTraces,
                conductTraces = exam.conductTraces,
            ),
        )
        AdmissionFlow.save(dir, record)
        log(
            "抱朴准入：外部执行体「$agentId」已登记 ${record.wire()}" +
                "（出题：${exam.conclusion.cn}·${exam.asked} 道）" +
                "｜canAccept($kind)=${record.profile.canAccept(kind, version)}" +
                "｜needsReprobe=${record.profile.needsReprobe(version)}",
        )
        // 第五百六十八批 C 域②（生产调用点，全仓只此一处）：新 peer 登记完 ⇒ 出一次路由偏好候选。
        // 546 的准入判定一字未动；候选产不产、落不落账由 RouteEvo 按档位判，这里只转手。
        routeSink?.invoke(agentId, System.currentTimeMillis())
    }

    /** `mcp_<serverSlug>_<toolSlug>` ⇒ `mcp-<serverSlug>`（最后一个下划线是 server 与 tool 的界）。 */
    internal fun agentIdOf(wireName: String): String {
        val body = wireName.removePrefix(WIRE_PREFIX)
        val server = body.substringBeforeLast('_').ifBlank { "server" }
        return "mcp-$server"
    }

    /** 版本指纹：当前 MCP 工具名全集排序相连（集合一变指纹就变——needsReprobe 说真话的根据）。 */
    internal fun versionOf(currentNames: List<String>): String =
        currentNames.filter { it.startsWith(WIRE_PREFIX) }.sorted().joinToString(",")

    private const val WIRE_PREFIX = "mcp_"
}
