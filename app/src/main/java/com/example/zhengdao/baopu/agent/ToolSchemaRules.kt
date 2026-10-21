// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第一百一十九批 §0 / §1（第九十四批的续）。
// 94 批只锁了四个核心文件工具（read_file / write_file / edit_file / list_dir）的契约，
// 本批把**所有已注册工具**（[BaopuToolCatalog.ENTRIES] 27 条 ＋ 运行时注册 4 条 = 31 个）
// 拉进同一份判据，并回答一个问题：**「这个工具的 schema 到底算不算完整」由谁说了算？**
//
// 三条边界（写完再回头看，一个字都别松）：
// 1. **本文件只管 schema 自身完不完整，不参与参数校验**。参数校验永远只有一条链：
//    [ToolRegistry.validateCall] → [SchemaCheck]（§1「校验统一走 registry.validateCall，
//    不另写解析」）。这里再判一次参数合不合法，就是 §4 打回第四条「另起第二条链」。
// 2. **规则只描述「已经在发的东西」**。判据的门槛必须与 [SchemaCheck] 真正执行的语义对齐：
//    SchemaCheck 只看顶层（type / required / additionalProperties / properties 逐类型 /
//    enum / minimum / maximum），所以这里也只强制顶层——**不要求**声明 SchemaCheck 不执行的
//    嵌套约束（那样会出现「schema 上写着、校验时不管」的第二套真相，正是 94 批要灭的东西）。
//    嵌套约束的现状与处置写进交付报告 §未覆盖项，不在这里硬套。
// 3. **白名单必须带原因，且必须能被门禁查过期**。允许「没有必填参数」「放开发挥未知参数」
//    这两件事都要有人签字（[REQUIRED_EMPTY_OK] / [ADDITIONAL_OPEN_OK]），
//    键集还必须真注册得上——写着一个已删工具的名字就是过期条目
//    （[ToolSchemaGateTest] 会红，形制照 [ToolGroupTable.checkCoverage] 的双向相等）。
package com.example.zhengdao.baopu.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具 JSON Schema 的**完整度判据**（纯函数，零 Android / 零 IO ⇒ JVM 直测）。
 *
 * 谁能用：
 * - [ToolRegistry.register]：每次注册记账一份（[ToolRegistry.schemaIssues]）；
 * - `ToolSchemaGateTest` / `ToolSchemaAllTest`：全量 31 个工具一条一条过，
 *   以及「源码里新写的 ToolSpec( 必须带 inputSchema」的源码门禁。
 *
 * 为什么注册表**不**直接抛异常（派工单 §0 写的是「缺 schema 的工具不允许注册（源码门禁）」，
 * 后半句才是落点）：抱朴的注册表在引擎装配期建好，装配期抛异常 ⇒ 用户拿到的是「抱朴打不开」，
 * 而不是「这个工具用不了」；而这批 31 个工具的 schema 已被门禁测试钉死，
 * 真出错该在 CI/单测里红，不该让用户当第一个发现者。所以这里是**记账 + 门禁**，
 * 不是运行期崩溃。若要改硬抛，只动 [ToolRegistry.register] 一行，理由写进报告。
 */
internal object ToolSchemaRules {

    /**
     * 一条不合规。
     *
     * @param rule 规则代号（门禁报错、交付报告、ERRATA 三处共用；改号要同步报告）
     * @param detail 人话：哪个参数、错在哪、怎么改
     */
    data class Violation(val rule: String, val detail: String) {
        override fun toString(): String = "「$rule」$detail"
    }

    // ── 规则代号（S = Schema） ──────────────────────────────────────────

    /** S1：根本没有 inputSchema（空 JSONObject 也归这条）。 */
    const val S1_NO_SCHEMA = "S1 没有可用的 inputSchema"

    /** S2：顶层 type 不是 object（SchemaCheck 第一行就会拒，属注册表配置错）。 */
    const val S2_TOP_TYPE = "S2 顶层 type 不是 object"

    /** S3：缺 properties 段。 */
    const val S3_NO_PROPS = "S3 缺 properties 段"

    /** S4：缺 required 段（**必须有这一段**，全可选就写空数组并登记原因）。 */
    const val S4_NO_REQUIRED = "S4 缺 required 段"

    /** S5：required 为空，但没在白名单里登记原因（多半是忘了写必填）。 */
    const val S5_REQUIRED_EMPTY = "S5 required 为空且未登记原因"

    /** S6：required 里的键在 properties 里不存在（模型永远填不出来 ⇒ 这个工具死锁）。 */
    const val S6_REQUIRED_UNKNOWN = "S6 required 指向不存在的属性"

    /** S7：additionalProperties 不是 false（幻觉参数被静默接受），且没登记放行原因。 */
    const val S7_ADDITIONAL_OPEN = "S7 additionalProperties 没关且未登记原因"

    /** S8：属性缺 type（SchemaCheck 对该属性等于不校验）。 */
    const val S8_PROP_NO_TYPE = "S8 属性缺 type"

    /** S9：属性缺 description（模型看不见怎么填，等于让它在猜）。 */
    const val S9_PROP_NO_DESC = "S9 属性缺 description"

    /** S10：enum 空数组 / 类型不支持 / 元素与声明的 type 不符。 */
    const val S10_ENUM = "S10 enum 不合规"

    /** S11：minimum > maximum（自相矛盾的区间，谁都填不进去）。 */
    const val S11_RANGE = "S11 minimum 大于 maximum"

    /** S12：工具 description 为空或超长（描述是注入面，长度上限见 [ToolSpec.MAX_DESC]）。 */
    const val S12_DESC = "S12 description 为空或超长"

    /**
     * 「这个工具确实没有必填参数」的**签字表**。
     *
     * 为什么允许空 required：有四个工具的参数天生全可选（清空回收站不需要 path、
     * 整页截图不需要参数……）。写空数组而不写必填，是**如实描述**；但「忘了写必填」
     * 长得一模一样，所以必须有人签字说清理由——签字的地方就是这张表。
     *
     * 新增空 required 的工具 ⇒ 必须在这里加一行，否则 [S5_REQUIRED_EMPTY] 直接红。
     */
    val REQUIRED_EMPTY_OK: Map<String, String> = mapOf(
        "browser_snapshot" to "唯一参数 selector 可选：省略即整页快照（BrowserTools.snapshotSpec）",
        "browser_screenshot" to "唯一参数 full_page 可选：默认当前视口（BrowserTools.screenshotSpec）",
        "card_build" to "spec 默认「母档.json」、out_dir 默认「成品/<name>/」，两个都有默认值（CardTools.buildSpec）",
        "delete_path" to "path 与 empty_trash 二选一：清空整个回收站时不需要 path（DeletePathTool.kt 的注释原文）",
        "capabilities" to "只带一个可选参数 enable：不带就是「把名册念给我听」（CapabilitiesTool）",
        "library_list" to "dir 默认 deliver、pattern 可选（不筛就全列），列清单本身不需要必填（LibraryTools.listSpec）",
        "kb_index" to "path 缺省＝资料库/投递、rebuild 缺省＝增量：两个参数都有默认值，建索引本身不需要必填（KbTools.indexSpec，第一百二十八批）",
        "bash" to "免费通道占位条目：properties 为空，任何参数都不接受（执行器一律拒绝，见 ToolSchemaRules.S7 的放行理由）",
    )

    /**
     * 「这个工具的 additionalProperties 就该放开」的签字表。
     *
     * 目前只有 `bash`：它不是真工具，是免费通道要求的**占位条目**（② 台账：tools 数组里
     * 没有 `bash` 会被服务端 403 FreeTierError）。它必须存在的意义只有一句人话：
     * 「本 App 不提供 shell 执行（不跨 rootfs 是硬边界）」。
     *
     * 为什么不关：关了之后模型传 `{"command":"ls"}` 拿到的是
     * 「未知参数 command（本工具不接受这个参数；可用参数：）」——它只会去猜参数名，
     * 而真正该看见的是那句边界说明。**拒绝要明说原因**这条口径比「未知参数一律拒」更优先。
     */
    val ADDITIONAL_OPEN_OK: Map<String, String> = mapOf(
        "bash" to "免费通道占位条目：接受任意参数只为把「不提供 shell 执行」这句人话回灌给模型；" +
            "收紧成 false 后模型只会收到「未知参数 command」，信息量反而下降",
    )

    /**
     * 逐条判一遍。
     *
     * @param name 工具名（白名单按名字查；空串＝还没有名字，白名单一律不生效）
     * @param schema 该工具的 inputSchema（`null` 只在源码门禁的「忘了写」分支出现）
     * @param description 写进系统提示的工具描述（长度与空值一起判，见 [S12_DESC]）
     * @return 空列表＝合规；否则每条都是人话（门禁直接拼进报错）
     */
    fun audit(name: String, schema: JSONObject?, description: String): List<Violation> {
        val out = mutableListOf<Violation>()
        if (schema == null || schema.length() == 0) {
            out += Violation(S1_NO_SCHEMA, "「$name」没有 inputSchema（或是个空对象）——模型看不见参数就只能瞎猜")
            return out
        }
        // S2 顶层 type
        if (!schema.optString("type").equals("object", ignoreCase = true)) {
            out += Violation(S2_TOP_TYPE, "「$name」的顶层 type 是「${schema.optString("type")}」，必须是 object")
        }
        // S3 properties
        val props = schema.optJSONObject("properties")
        if (props == null) {
            out += Violation(S3_NO_PROPS, "「$name」没有 properties 段（哪怕全可选也要写成空对象）")
        }
        // S4/S5/S6 required
        val required = schema.optJSONArray("required")
        when {
            required == null -> out += Violation(
                S4_NO_REQUIRED,
                "「$name」没有 required 段——全可选就写 required: [] 并在 ToolSchemaRules.REQUIRED_EMPTY_OK 登记原因",
            )
            required.length() == 0 && name !in REQUIRED_EMPTY_OK -> out += Violation(
                S5_REQUIRED_EMPTY,
                "「$name」的 required 是空的，但没在 REQUIRED_EMPTY_OK 里登记原因（是漏写必填，还是真全可选？）",
            )
            required.length() > 0 -> {
                // org.json 的 keys() 是 Iterator（不是 Iterable），走 names() 拿 JSONArray 再成集
                val keys = props?.names()?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }?.toSet()
                    ?: emptySet()
                for (i in 0 until required.length()) {
                    val key = required.optString(i)
                    if (key.isNotEmpty() && key !in keys) {
                        out += Violation(
                            S6_REQUIRED_UNKNOWN,
                            "「$name」的 required 里有「$key」，但 properties 里没有它 —— 模型永远填不出这个必填项",
                        )
                    }
                }
            }
        }
        // S7 additionalProperties
        if (schema.optBoolean("additionalProperties", true) && name !in ADDITIONAL_OPEN_OK) {
            out += Violation(
                S7_ADDITIONAL_OPEN,
                "「$name」没关 additionalProperties —— 模型幻觉出的参数会被静默接受，" +
                    "它会以为参数生效了（94 批负例演练①的靶子）",
            )
        }
        // S8/S9/S10/S11 逐个属性
        props?.let { p ->
            for (key in p.keys()) {
                val def = p.optJSONObject(key)
                if (def == null) {
                    out += Violation(S8_PROP_NO_TYPE, "「$name.$key」的定义不是对象（应当是 {\"type\": …}）")
                    continue
                }
                val type = def.optString("type").trim()
                if (type.isEmpty()) {
                    out += Violation(S8_PROP_NO_TYPE, "「$name.$key」缺 type —— 校验层对它等于不校验")
                }
                if (def.optString("description").trim().isEmpty()) {
                    out += Violation(S9_PROP_NO_DESC, "「$name.$key」缺 description —— 模型看不见怎么填，只能在猜")
                }
                def.optJSONArray("enum")?.let { en -> enumIssue(name, key, type, en)?.let { out += it } }
                val min = def.optDouble("minimum", Double.NaN)
                val max = def.optDouble("maximum", Double.NaN)
                if (!min.isNaN() && !max.isNaN() && min > max) {
                    out += Violation(S11_RANGE, "「$name.$key」minimum($min) 大于 maximum($max) —— 谁都填不进去")
                }
            }
        }
        // S12 工具描述
        when {
            description.isBlank() ->
                out += Violation(S12_DESC, "「$name」的 description 是空的（模型看不见用途就不会调它）")
            description.length > ToolSpec.MAX_DESC ->
                out += Violation(S12_DESC, "「$name」的 description 有 ${description.length} 字，超过上限 ${ToolSpec.MAX_DESC}")
        }
        return out
    }

    /** enum 三件套：非空、类型受支持、元素与声明的 type 一致。 */
    private fun enumIssue(name: String, key: String, type: String, en: JSONArray): Violation? {
        if (en.length() == 0) {
            return Violation(S10_ENUM, "「$name.$key」的 enum 是空数组 —— 没有任何合法取值")
        }
        if (type != "string" && type != "integer") {
            return Violation(S10_ENUM, "「$name.$key」声明了 enum 但 type 是「$type」；只支持 string / integer 的枚举")
        }
        for (i in 0 until en.length()) {
            val v = en.opt(i)
            val ok = when (type) {
                "string" -> v is String
                else -> v is Int || v is Long
            }
            if (!ok) {
                return Violation(
                    S10_ENUM,
                    "「$name.$key」的 enum 第 ${i + 1} 项是 ${v?.javaClass?.simpleName}，与声明的 type「$type」不符",
                )
            }
        }
        return null
    }
}
