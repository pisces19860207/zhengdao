// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十八批 ② · `exam_peer_agent`（准入考试的**独立生产入口**——派工单 G-572-4）。
//
// 572 演练取证的事实：九道题的考试**只挂在"首次委派"的闸序里**（[EnvoyAdmission.ensure] 那一格），
// 而 548 的探针只拉卡不考。于是想摸清一台对端的底，只有两条路：等模型哪天派活给它，
// 或者根本没办法——用户手里没有「给这台出个题」这件事。本批补的就是这个口。
//
// 房型照 549 的 [EnvoyTool]（614 已把它接进生产注册表）：
//   · spec 只从注册表经 GuardPipeline 到达——停机闸/授权闸/租约闸在管线里跑完才轮到执行体；
//   · 必问是硬线：这件工具一样 R2＋`alwaysAsk`（放权档免不掉它），因为它**要往外发字节**
//     （九道题＝九次 message/send），没人批就一个字节不发；
//   · 落点（sink）由引擎装配时注入，没接线就明确拒绝，不静默成功。
//
// 三件刻意不做的事：
//   · **不派活**：本工具一件任务都不派（`EnvoyDeliver.exam` 走道半截闸序就收口）；
//   · **不新造账本**：结论落既有的 `准入画像-*.json` ＋ `准入账.jsonl` ＋ `准入出题账.jsonl`，
//     外发那三行落既有的租约账（类目仍是 `peer/delegate`，账面工具名换成 `peer.exam`）；
//   · **不重造考试**：出题体只有 [EnvoyAdmission.retest] 那一条路，而 retest 与首次委派
//     的 ensure 合用同一个 private `examine`（判据③的源码断言钉的就是这一格）。
//
// 两把尺（648 三、只记录不修）：考完用户会看到「出题结论 fail」而「准入档位 accept_read_only」——
// 前者量**能不能写**（探针里有写类题，纯文本对端必不过），后者量**收编到哪一档**。
// 回执里把这句话原样带给用户，不解释成矛盾。
package com.example.zhengdao.baopu.peer

import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolPermission
import com.example.zhengdao.baopu.agent.ToolSpec
import com.example.zhengdao.baopu.agent.jsonArrayOf
import com.example.zhengdao.baopu.agent.jsonObjectOf
import org.json.JSONObject

/** exam_peer_agent 的落点（引擎装配时注入；JVM 夹具注入接 [EnvoyDeliver.exam] 的那一件）。 */
internal fun interface EnvoyExamSink {
    fun submit(args: JSONObject): ToolOutcome
}

/** exam_peer_agent（第六百四十八批 ② · 准入考试独立入口）。 */
internal object EnvoyExamTool {

    const val NAME = "exam_peer_agent"

    /** 挂账用的工具名（同一本租约账、同一类目，只换 `tool` 那一格；见 [EnvoyDeliver.EXAM_LEDGER_TOOL]）。 */
    const val LEDGER_TOOL: String = EnvoyDeliver.EXAM_LEDGER_TOOL

    /** 进程级落点（与 [EnvoyTool].sink 同一房型）。没接线 ⇒ 明确拒绝，不静默丢弃。 */
    @Volatile
    var sink: EnvoyExamSink? = null

    /** 写进系统提示的工具描述（≤ [ToolSpec.MAX_DESC] 字符；工具描述是注入面）。
     *
     * 故意**不提** FORBIDDEN：那是本机自己的禁区，说给模型只会引来一句「那能不能绕过」。 */
    const val DESCRIPTION: String =
        "给一台外部 A2A agent 单独出一场准入考（九道题：五枚探针＋四道品行），也可以拿来补考——" +
            "不必先派活。考完就地更新它的准入画像与档位：通过＝可用（读类写类都接）；" +
            "算错或纪律差＝降权只读（写类活不派给它，读类活照派）；明知红线还踩＝不用。" +
            "考试本身也是外发：每次都要用户在授权卡上批准，批了才发得出考卷字节。" +
            "只看结论不派活时用这件工具，别为了考试先派一件没用的活。"

    fun spec(): ToolSpec = ToolSpec(
        name = NAME,
        description = DESCRIPTION,
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                "endpoint" to jsonObjectOf(
                    "type" to "string",
                    "description" to "要考的这台 A2A 服务地址（http(s)://host[:port]，与 delegate_peer_task 的 endpoint 同一格）",
                ),
            ),
            "required" to jsonArrayOf("endpoint"),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        timeoutMs = TOOL_TIMEOUT_MS,
        // 组盖章：与 delegate_peer_task 同组（渐进披露：默认不可见，解锁「多 Agent 协作」才露面）。
        group = ToolGroup.AGENT,
        riskTier = "R2",
        // 只写准入账/画像（不改用户盘），但**不许并行**：它要外发并阻塞整场考试。
        readOnly = true,
        parallelSafe = false,
    ) { args ->
        sink?.submit(args) ?: ToolOutcome.Rejected(
            "exam_peer_agent 没接线（引擎未装配）：本次调用不生效。" +
                "把任务当作一个整体自己完成，或告诉用户稍后再试。",
        )
    }

    /** 出题的墙钟上限：准入考试总限 60s ＋ 九道题各自轮询的收口余量（比委派短，因为不派活）。 */
    const val TOOL_TIMEOUT_MS = 180_000L
}
