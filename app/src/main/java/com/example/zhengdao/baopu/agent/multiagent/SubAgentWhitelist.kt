// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百三十一批 · 子智能体 v2 ②「工具白名单（收窄兑现）」的**判据层**：
// 每子任务一张名单（`SubTask.tools` 读 + `SubTask.writes` 父显式授予的写），
// 名单外的调用一律拒；`run_subtasks` 永远不在名单里（禁递归）。
//
// 为什么判定放在这里而不是装配层：装配层的 lambda 测不到（要 Context＋注册表），
// 而「谁的调用该被拒」是纯判据——放这里，JVM 单测喂两条数据就能钉住。
//
// 第六百四十八批 · 这份清单**不只管子代理**：外部委派（`delegate_peer_task` 那条闸序）
// 出发前也逐项比对它（工具/能力/端点三类，见下面 [forbiddenAtEndpoint]/[forbiddenOnCard]）。
// 572 演练取证的翻车点是「清单在、可委派路径根本没查它」——清单写成一份、判定函数写成一份，
// 两条路径各自解释，迟早漂。所以本批把扫禁区的入口也开在这个对象里：委派侧只调下面两个函数，
// 一个都不自己比。派工单 648 一③「两者用同一份清单与同一判定函数」钉的就是这件事。
package com.example.zhengdao.baopu.agent.multiagent

import com.example.zhengdao.baopu.peer.EnvoyTool
import java.net.URI

internal object SubAgentWhitelist {

    /**
     * 永远不许「换个执行体再跑一遍」的工具（v2 禁递归 ＋ 648 的委派禁区）。
     *
     * 597 加 `portrait_appearance`：它的 describe 是**全仓唯一**把图片字节发出去的动作、
     * merge 是改卡——两条都必须**人在场**（CONFIRM 卡/467 租约在主管线）；而子 Agent 的
     * 执行器是直调（不经过弹卡），一旦进名单就是无人在场外发。`portrait_look` 只读，可留。
     * 610 加 `novel_readthrough_run`：一次调用会连烧几十上百次模型请求＋写盘——同样必须
     * 人在场（只读预检的 `novel_readthrough` 可留）。
     * 648 加 `delegate_peer_task`（549 §七.4 那条遗留）：把活派给外部 agent 要人在场批
     * 授权卡；子 Agent 的执行器不弹卡，一旦让它委派就是**无人批准的外发**——而且这台
     * 对端有没有过准入，子 Agent 根本不会问。
     */
    val FORBIDDEN: Set<String> = setOf(
        SubTaskTool.NAME,
        EnvoyTool.NAME,
        "portrait_appearance",
        "novel_readthrough_run",
    )

    /** 工具名上限（防一条 grant_writes 塞长文；正常工具名远短于此）。 */
    const val MAX_NAME_CHARS = 60

    /**
     * 生效的**写类**授权 ＝ 父显式给的 `grant_writes` ∩ 父会话可用 ∩ 真写类。
     *
     * 为什么不收进 [SubAgentRules.effectiveWhitelist]：读名单的口径是「只能收窄」，
     * 写名单的口径是「不显式给就一件都没有」——两条口径混在一个函数里，
     * 「模型顺手在 tools 里写了 write_file」就会变成默许。分开放，混不进来。
     *
     * @param isWrite 真写类判定（生产由注册表 `ToolSpec.readOnly` 反推；未知工具按写处理）
     */
    fun effectiveWrites(
        granted: List<String>?,
        available: Set<String>,
        isWrite: (String) -> Boolean,
    ): List<String> {
        if (granted.isNullOrEmpty()) return emptyList()
        val out = mutableListOf<String>()
        granted.forEach { raw ->
            val name = raw.trim().take(MAX_NAME_CHARS)
            if (name.isEmpty() || name in FORBIDDEN) return@forEach
            if (name !in available) return@forEach
            if (!isWrite(name)) return@forEach // 读类走 tools 那张表，不在写名单里重复
            if (name !in out) out += name
        }
        return out
    }

    /** 这条子任务总共能用哪些工具（读＋写；顺序稳定：先读后写）。 */
    fun allowed(task: SubTask): Set<String> = (task.tools + task.writes).toSet()

    /** 一次调用的放行判定。 */
    sealed interface Decision {
        /** 放行。 */
        object Allow : Decision

        /** 拒（带回灌给人话理由；调用点同时落一条 REJECT 账）。 */
        data class Reject(val reason: String) : Decision
    }

    /**
     * 该不该放行这次调用。
     *
     * 禁递归排在最前：即使某天有人把 `run_subtasks` 塞进可用集或写名单，
     * 这条判定也不放行——「子任务不能再拆」是 v2 的硬口径，不靠名单维护。
     *
     * 名单**空**（读名单与写名单都没解析出东西）时不在这里拦：那是 v1 的
     * 「用户把读工具全关了 / 纯凭 input 作答」形状，执行层仍会按空名单拒
     * （[com.example.zhengdao.baopu.BaopuRuntime] 那一层，v1 既有口径）。本层
     * 只负责「有名单时名单说了算」——把两种拦截混在一起，会计出两笔账。
     */
    fun decide(toolName: String, task: SubTask): Decision = when {
        toolName in FORBIDDEN -> Decision.Reject(
            "$toolName 在子任务里不可用（v2 禁递归）：子任务不能再拆，自己把这件事做完。" +
                "确实要拆，由父任务拿到结论后另行决定。",
        )

        allowed(task).isEmpty() -> Decision.Allow

        toolName !in allowed(task) -> Decision.Reject(
            "$toolName 不在这条子任务的白名单里（可用：${allowed(task).joinToString("、")}）",
        )

        else -> Decision.Allow
    }

    // ── 648 §一：同一份清单给委派路径用（三类逐项比对，判定仍只 [decide] 一处）──────

    /** 禁区扫描查的三类（派工单 648 一①「工具/能力/端点三类都查」）。 */
    enum class ForbiddenClass(val wire: String, val cn: String) {
        /** 对端自报的技能（卡上 `skills[].id`／`skills[].name`）——禁区工具换层皮挂到 A2A 上。 */
        TOOL("tool", "工具"),

        /** 卡上声明的能力键（streaming/pushNotifications/…那一层）。 */
        CAPABILITY("capability", "能力"),

        /** 端点本身（host ＋ 路径分段）——禁区名藏在地址里。 */
        ENDPOINT("endpoint", "端点"),
    }

    /**
     * 一次禁区扫描的命中：哪一类、哪一项。
     *
     * [text] 就是派工单 648 一② 要的那句账（「委派被挡：命中禁区 <项>」）——账行与回给
     * 调用方的人话共用这一格，不会出现「账上一句、嘴里一句」。
     */
    data class ForbiddenHit(val cls: ForbiddenClass, val item: String) {
        val text: String get() = "委派被挡：命中禁区${cls.cn}「$item」"
    }

    /**
     * **端点类**扫描：委派出发前问一句（位置是判据——这一步在拉卡之前，命中连卡节的字节都不发）。
     *
     * 整条 URL 不比，拆成 host＋路径分段逐段比：禁区项是**工具名**，不是地址；
     * 拿整串去比只会得到一个永远不命中的判定（假闸）。
     */
    fun forbiddenAtEndpoint(endpoint: String): ForbiddenHit? =
        scan(ForbiddenClass.ENDPOINT, endpointParts(endpoint))

    /** **工具类＋能力类**扫描：卡到手之后、准入考试之前（命中 ⇒ 考卷字节也不发出去）。 */
    fun forbiddenOnCard(skillIds: List<String>, skillNames: List<String>, capabilities: List<String>): ForbiddenHit? =
        scan(ForbiddenClass.TOOL, skillIds + skillNames) ?: scan(ForbiddenClass.CAPABILITY, capabilities)

    /**
     * 一条候选清单里第一个命中禁区的项。
     *
     * **判定只有 [decide] 一处**：喂的是空名单子任务，所以名单那一支不说话
     * （`allowed(task)` 空 ⇒ [decide] 短路成 Allow），Reject 唯一的可能就是命中 [FORBIDDEN]。
     * 本层因此没有第二份 `in FORBIDDEN`——清单加减只动 [FORBIDDEN] 那一格。
     */
    private fun scan(cls: ForbiddenClass, items: List<String>): ForbiddenHit? =
        items.firstNotNullOfOrNull { raw ->
            val name = raw.trim().take(MAX_NAME_CHARS)
            when {
                name.isEmpty() -> null
                decide(name, NO_ALLOWANCE) is Decision.Reject -> ForbiddenHit(cls, name)
                else -> null
            }
        }

    /** 端点拆段：host ＋ 路径分段（认不出来的地址原样作一段，不因此放行任何一类）。 */
    private fun endpointParts(endpoint: String): List<String> = runCatching {
        val uri = URI(endpoint.trim())
        listOf(uri.host.orEmpty()) + uri.path.orEmpty().split('/')
    }.getOrDefault(listOf(endpoint)).filter { it.isNotBlank() }

    /** 空名单的那条子任务形状：只让禁区那一支说话（见 [scan]）。 */
    private val NO_ALLOWANCE = SubTask(id = "禁区扫描", parentId = "", seq = 0, goal = "")
}
