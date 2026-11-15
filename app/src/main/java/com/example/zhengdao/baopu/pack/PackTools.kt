// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 649 批一 · 工具 `pack_search`（内容创作资料包检索）。
//
// 它是**隐藏能力**：默认关着的时候这个工具根本不在 tools 数组里（注册表由一个组挂载位
// 控制，见 `ToolRegistry.setPackMount`），模型看不见；即使幻觉调用，走到执行器第一行
// 也会被「没开」挡回，且**一个资料包字节都不读**（判据「关着零 IO」）。
//
// 只读：只读包目录里的 index.json 与命中那几片的 .md；不写包、不碰工作区、不联网。
package com.example.zhengdao.baopu.pack

import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolPermission
import com.example.zhengdao.baopu.agent.ToolSpec
import com.example.zhengdao.baopu.agent.ToolTimeout
import com.example.zhengdao.baopu.agent.jsonArrayOf
import com.example.zhengdao.baopu.agent.jsonObjectOf
import java.io.File

internal object PackTools {

    const val NAME = "pack_search"
    private const val ARG_QUERY = "q"
    private const val ARG_K = "k"

    /** 包根（装配层注入：App 私有目录下的 `packs/男性`）。没装配 ⇒ 检索不了，明说。 */
    @Volatile
    var rootProvider: (() -> File?)? = null

    /** 开关（装配层注入：设置项「内容创作资料包」）。关着 ⇒ 执行器第一行就退。 */
    @Volatile
    var gateOn: () -> Boolean = { false }

    /** 注入记账（装配层注入；测试给假件）。 */
    @Volatile
    var ledger: PackLedger = PackLedger.NONE

    /** 包壳按根缓存：同一个根只建一次（**建壳不读盘**，读盘发生在 search）。 */
    @Volatile
    private var cached: PackStore? = null
    @Volatile
    private var cachedRoot: String? = null

    fun spec(): ToolSpec = ToolSpec(
        name = NAME,
        description = "从本机「内容创作资料包」里按主题取片（只读）：返回最相关的片段，" +
            "每片带**片 id** 与**来源文件名**，单次最多 ${PackStore.MAX_K} 片、" +
            "合计不超过 ${PackStore.MAX_BYTES} 字节。没命中就明说没命中，不要编造。" +
            "这个工具只在用户打开「内容创作资料包」、且这轮话题命中主题标签时才出现。" +
            "要搜工作区文件用 search_in_files，要搜知识库索引用 kb_search。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_QUERY to jsonObjectOf(
                    "type" to "string",
                    "description" to "要取的主题词（如「勃起温度」「沦陷链阶段」「体位阻力」）",
                ),
                ARG_K to jsonObjectOf(
                    "type" to "integer",
                    "minimum" to 1,
                    "maximum" to PackStore.MAX_K,
                    "description" to "片数上限（默认 ${PackStore.DEFAULT_K}，最多 ${PackStore.MAX_K}）",
                ),
            ),
            "required" to jsonArrayOf(ARG_QUERY),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        readOnly = true,
        parallelSafe = true, // 只读包文件，无共享可变状态
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> search(args) }

    fun search(args: org.json.JSONObject): ToolOutcome {
        // ① 开关：关着 ⇒ 立刻退，且不碰任何包文件（下面一行都没执行到）
        if (!runCatching { gateOn() }.getOrDefault(false)) {
            return ToolOutcome.Rejected(
                "内容创作资料包没开（抱朴设置 → 内容创作资料包）。本轮不读任何资料包文件，也不改写成别的工具。",
            )
        }
        val query = args.optString(ARG_QUERY).trim()
        if (query.isEmpty()) return ToolOutcome.Failed("缺少必填参数「$ARG_QUERY」（要取的主题词）")

        val root = runCatching { rootProvider?.invoke() }.getOrNull()
            ?: return ToolOutcome.Failed("资料包没装到这台机器上（App 内部状态：包目录未装配），这轮不检索")
        val store = storeFor(root)
        val idx = store.index()
            ?: return ToolOutcome.Failed("资料包索引读不出（index.json 缺失或损坏），这轮不检索")

        val k = args.optInt(ARG_K, PackStore.DEFAULT_K).coerceIn(1, PackStore.MAX_K)
        val hits = store.search(query, k)
        if (hits.slices.isEmpty()) {
            return ToolOutcome.Ok(
                "（没命中）资料包里 ${idx.size} 片，没有和「$query」对得上的主题。" +
                    "换个更具体的词（例如某个部位/阶段名），或确认这个主题本来就不在包里。",
            )
        }
        // ② 记账：每次注入都记一笔（片 id + 字节），失败不阻塞
        runCatching { ledger.record(query, hits.ids, hits.bytes) }

        val sb = StringBuilder()
        sb.append("资料包检索「").append(query).append("」：命中 ").append(hits.slices.size)
            .append(" 片（合计 ").append(hits.bytes).append(" 字节，上限 ")
            .append(PackStore.MAX_BYTES).append("）：\n")
        for ((meta, body) in hits.slices) {
            sb.append("\n[").append(meta.id).append("]（来源：").append(meta.from)
                .append(" · ").append(meta.kind).append(" · ").append(meta.coverage).append("）\n")
            sb.append(body).append('\n')
        }
        sb.append("\n（引用请带上片 id 与来源文件名；" +
            "`coverage=edge` 的片是非代表性情形，只在本轮明确需要时才用；" +
            "上面没有的内容不要编造来源）")
        return ToolOutcome.Ok(sb.toString().trimEnd())
    }

    /**
     * 本轮话题有没有命中 L1 主题（挂载判定的第二半）。
     *
     * **开关关着 ⇒ 直接 false，不建壳、不读 index.json**——「关着零 IO」的落点就是这里：
     * 第一行 short-circuit，[storeFor] 都不会被调用。
     */
    fun l1Hit(text: String): Boolean {
        if (text.isBlank()) return false
        if (!runCatching { gateOn() }.getOrDefault(false)) return false
        val root = runCatching { rootProvider?.invoke() }.getOrNull() ?: return false
        return runCatching { storeFor(root).l1Hits(text).isNotEmpty() }.getOrDefault(false)
    }

    private fun storeFor(root: File): PackStore {        val key = root.absolutePath
        cached?.let { if (cachedRoot == key) return it }
        val s = PackStore(root)
        cached = s
        cachedRoot = key
        return s
    }

    /** 测试用：丢掉包壳缓存（换假包根时）。 */
    fun resetCacheForTest() {
        cached = null
        cachedRoot = null
    }
}
