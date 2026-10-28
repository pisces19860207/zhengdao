// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第二百一十九批 §1「工具→风险档全覆盖」＋ §2「参数级/组合升档（表驱动）」
// ＋ §3「门禁：注册了却查不到档 ⇒ 单测红；未分类 ⇒ R2＋必问」。
//
// 这是**判定层**，不是执行层：本文件不弹卡、不写盘、不联网、不读时钟、不记日志。
// 所有事实由调用方预先算好放进 [RiskCtx]；本层只做纯函数查表与叠加。
// 执行侧（确认卡、软删、转正、审计）仍走既有通道，173/208 接线是**别的批**。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.ToolCall
import org.json.JSONArray
import org.json.JSONObject

/**
 * 四档风险（173 口径）：
 * - [R0] 读：只读用户文件或只改 App 自己的会话状态；
 * - [R1] 写证道内：在工作区里产出/修改用户可见的东西；
 * - [R2] 改外面的：动工作区外的盘、动网页状态、删改用户文件、批量操作；
 * - [R3] 花钱外发账号：烧额度、对外发送、账号/金额语义。
 */
enum class RiskClass { R0, R1, R2, R3 }

/** 矩阵自己的结论（与 173 的模式映射分开：R2 是否弹卡由模式层决定）。 */
enum class RiskDecision {
    /** 本层没有「必须问/必须拒」的理由；风险档照旧交给模式层。 */
    PROCEED,

    /** 无论什么模式都必须问（未分类、批量、R3、工具表点名 alwaysAsk）。 */
    MUST_ASK,

    /** 硬拒：原件区、密码框、内网/白名单外 URL。 */
    REJECT,

    /** 终端不参与分级（bash）：矩阵不接管，原样交回终端那条路。 */
    EXEMPT,
}

/**
 * **可解释字段的稳定枚举**（派工单 §1.9，2026-10-26 补）：208 的记账与抽检按它分组，
 * 不按人话文案。前十个是派工单点名的，后面几个是本层实现里真实存在的其余判据
 * （清空回收站/语义升档/基档即 R3/表点名必问/无判据）。
 *
 * 为什么枚举与 [id] 并存：枚举给 Kotlin/审计做类型判断，[id] 是**已经写进账的机器键**
 * （`unclassified_tool` 等）——改它等于改历史记录的读法，所以一个字不动。
 */
enum class RiskCode(val id: String) {
    NETWORK_ESCALATED("network_escalated"),
    /** 没有任何判据命中（正常放行）。 */
    NONE("none"),
    PATH_ORIGINAL_RAW("raw_zone"),
    PATH_OUTSIDE_WORKSPACE("outside_workspace"),
    OVERWRITE_EXISTING("overwrite_existing"),
    BATCH_OVER_20("batch_targets"),
    DELETE_OVER_10("batch_delete"),
    SUBMIT_LIKE_BUTTON("dangerous_click"),
    PASSWORD_FIELD("sensitive_field"),
    PRIVATE_URL("internal_url"),

    /** 域名不在白名单（派工单与内网并列的另一种 URL 拒；记账分开，抽检才看得出是哪条）。 */
    URL_NOT_WHITELISTED("url_not_whitelisted"),
    FREQ_OVER_LIMIT("rate_limit"),
    UNCLASSIFIED_TOOL("unclassified_tool"),

    /** 清空回收站：删除数量未知（派工单没点名，但属于「删>10」的极端形态）。 */
    EMPTY_TRASH("empty_trash"),

    /** 含外发/账号/金额语义 ⇒ R3（派工单 §1.8 最后一行）。 */
    SENSITIVE_SEMANTICS("sensitive_semantics"),

    /** 基档本身是 R3（generate_image）。 */
    BASE_R3("base_high_risk"),

    /** 工具表点名必须问（schedule_task）。 */
    ALWAYS_ASK("always_ask"),
    ;

    override fun toString(): String = id
}

/**
 * 一条判定理由（给 173 弹卡文案 / 208 记账）。
 *
 * [code] 是**稳定的类型键**（[RiskCode]），[detail] 是人话；`id` 是历史机器键，
 * 与 `code.id` 同源。含敏感命中时只写命中词/规则名，**不回显原文**
 * （剪贴板全文、手机号不进日志）。
 */
data class RiskReason(val code: RiskCode, val detail: String) {
    val id: String get() = code.id
}

/** [RiskMatrix.classify] 的输出。 */
data class RiskVerdict(
    /** 最终风险档；[RiskDecision.EXEMPT] 时为 null（终端不在四档内）。 */
    val risk: RiskClass?,
    val decision: RiskDecision,
    val reasons: List<RiskReason> = emptyList(),
    /** 未分类工具（fail-closed 的记账键；208 用 [auditTag]）。 */
    val unclassified: Boolean = false,
    /** 判定时用的矩阵版本（表里带；改表必须 +1 ⇒ 旧记录仍能定位"当时是哪版"）。 */
    val matrixVersion: Int = RiskTable.DEFAULT_VERSION,
) {
    val mustAsk: Boolean get() = decision == RiskDecision.MUST_ASK
    val rejected: Boolean get() = decision == RiskDecision.REJECT
    val exempt: Boolean get() = decision == RiskDecision.EXEMPT

    /** 208 的审计标签：只有 fail-closed 这一个键，别的判定不冒记账行。 */
    val auditTag: String? get() = if (unclassified) "unclassified_tool" else null

    /** 本次命中的全部判据（按记录顺序，供抽检逐条对账）。 */
    val reasonCodes: List<RiskCode> get() = reasons.map { it.code }

    /**
     * **主判据**（208 一条账只落这一个键）：硬拒 > R3 类 > 删除超限 > 批量 > 必问 > 其余。
     *
     * 为什么不是「取第一条」：`delete_path` 一次删 30 个会同时命中「删>10」与「目标>20」，
     * 记账要点名更具体的那个；读的时候也不该让顺序变成 API。
     */
    val reasonCode: RiskCode
        get() = PRIORITY.firstOrNull { it in reasonCodes }
            ?: reasons.firstOrNull()?.code
            ?: RiskCode.NONE

    /** 硬拒（原件区/密码框/私有 URL）：208 拿它区分「拒了」与「只是问了」。 */
    val hardDeny: Boolean get() = decision == RiskDecision.REJECT

    private companion object {
        val PRIORITY: List<RiskCode> = listOf(
            RiskCode.PATH_ORIGINAL_RAW,
            RiskCode.PASSWORD_FIELD,
            RiskCode.PRIVATE_URL,
            RiskCode.URL_NOT_WHITELISTED,
            RiskCode.SUBMIT_LIKE_BUTTON,
            RiskCode.SENSITIVE_SEMANTICS,
            RiskCode.BASE_R3,
            RiskCode.EMPTY_TRASH,
            RiskCode.DELETE_OVER_10,
            RiskCode.BATCH_OVER_20,
            RiskCode.ALWAYS_ASK,
            RiskCode.UNCLASSIFIED_TOOL,
            RiskCode.PATH_OUTSIDE_WORKSPACE,
            RiskCode.OVERWRITE_EXISTING,
            RiskCode.FREQ_OVER_LIMIT,
        )
    }
}

/**
 * 判定所需的**事实快照**（全部由调用方算好，零 IO 进来）。
 *
 * @param nowMs 当前时刻（调用方给；本层不读时钟）
 * @param recentCalls 同名工具最近的调用时刻（毫秒）；只用于频率升档
 * @param workspaceRoots 工作区根（`证道/` 的绝对路径；相对路径天然算工作区内）
 * @param existingPaths 已存在的目标路径（覆盖判定用；调用方查盘后传入）
 * @param allowedUrlDomains URL 域名白名单；null = 尚未配置白名单（只拒内网/本机名）
 */
data class RiskCtx(
    val nowMs: Long = 0L,
    val recentCalls: Map<String, List<Long>> = emptyMap(),
    val workspaceRoots: List<String> = emptyList(),
    val existingPaths: Set<String> = emptySet(),
    val allowedUrlDomains: Set<String>? = null,
    val networkUsed: Boolean = false,
)

/**
 * 单个工具的**一行表**：底档 ＋ 这个工具哪些参数是写目标/网页目标/URL 参数。
 *
 * 表驱动的要求落在这里：新工具加一行就有档；改一行的档，`classify` 立刻跟着变。
 * 参数级规则只对表里声明过的键生效，不做「猜所有字段」的散装判断。
 */
internal data class ToolRule(
    val base: RiskClass,
    val networkEscalates: Boolean = false,
    /** 会写盘的目标参数名（`move_path` 的源与目标都算：源被移走也是改盘）。 */
    val writePathKeys: Set<String> = emptySet(),
    /** 显式目标缺席时按这个键判（`image_ops` 不传 out 就落在源图同目录）。 */
    val writeFallbackKey: String? = null,
    /** 这些布尔参数为 true 时写到了工作区外（`browser_screenshot.album`）。 */
    val externalTargetFlags: Set<String> = emptySet(),
    /** 删除类（批量阈值更严：>10 就必问）。 */
    val deleteLike: Boolean = false,
    /** 点击类（目标文本命中「提交/结算/支付…」升 R3）。 */
    val clickLike: Boolean = false,
    /** URL 参数名（内网/白名单外 ⇒ 硬拒）。 */
    val urlKeys: Set<String> = emptySet(),
    /** 表单字段参数名（密码/验证码框 ⇒ 硬拒）。 */
    val fieldKeys: Set<String> = emptySet(),
    /** 同名覆盖要升档（只标「新建产物会撞名」的工具；原地改写的工具不算）。 */
    val collisionSensitive: Boolean = false,
    /** 覆盖判定需要这个布尔参数为 true 才真的会覆盖（`write_file.overwrite`）。 */
    val collisionNeedsFlag: String? = null,
    /** 参数文本扫外发/账号/金额语义（只标对外通道；本地写文件不扫，避免代码里的 password 误伤）。 */
    val scanSensitiveText: Boolean = false,
    /** 无论什么模式都必须问（218 的定时任务：会产生「无人值守的自己」）。 */
    val alwaysAsk: Boolean = false,
    /** 这个布尔参数为 true 时数量未知且必然是大批量（`delete_path.empty_trash`）。 */
    val unknownBatchFlag: String? = null,
    /**
     * **嵌套高危文本键**（第二百二十四批）：这些参数的值是对象/数组时，按里面的
     * 「键名 / 字符串值」判 [RiskTable.dangerousClickWords]。
     *
     * 为什么需要它：`form_fill` 的入参是一张 `{字段名: 值}` 表，`clickTextKeys`
     * （`target`/`selector`/`text`）一个都取不到 ⇒ 语义升档会静默失效。默认空集，
     * 既有行行为**逐字不变**。
     */
    val clickNestedKeys: Set<String> = emptySet(),
    /**
     * 本行**专用**的高危词（不加进全局 [RiskTable.dangerousClickWords]）。
     *
     * 为什么不开在全局表上：全局词表一动，所有点击类工具的判定面跟着变（那等于改
     * 一整列行为）；224 只被授权加 `form_fill` 一行 ⇒ 词挂在行上，影响面就锁在这一行。
     * 派工单原话要求「提交/登录/支付/发布」四类语义 ⇒ 表里缺的两个（登录、支付类近义）
     * 在这里补。
     */
    val extraClickWords: Set<String> = emptySet(),
)

/**
 * 整张矩阵（工具表 ＋ 阈值 ＋ 关键词）。
 *
 * 阈值与关键词都是表的数据，**不许写死在判定函数或调用点**——单测对同一张表改一行
 * 就能验出行为变化（交付报告里的「改表一行 ⇒ 行为变」对照）。
 */
internal data class RiskTable(
    val tools: Map<String, ToolRule>,
    /** 终端不参与分级（173 口径）：列在这里的名字不查档、不叠规则。 */
    val exempt: Set<String> = emptySet(),
    /**
     * 矩阵版本（派工单 §1.9）：**改表必须 +1**。
     *
     * 208 的抽检与回归样本靠它定位「当时用的是哪一版」；记录里存的是整数，
     * 改表后旧记录照样读得出（不需要回填，也不会因为加了一行就解析失败）。
     */
    val matrixVersion: Int = DEFAULT_VERSION,
    /** 原件区的路径组件序列（命中即硬拒；只判写目标，读原件区仍按既有红线放行）。 */
    val originalZoneSegments: List<List<String>> = listOf(
        listOf("资料库", "原始"),
        listOf("原始"),
        listOf("library", "原始"),
    ),
    /** 一次调用里声明多个目标的数组参数名。 */
    val batchListKeys: Set<String> = setOf("paths", "targets", "files"),
    /** 一次调用超过这么多个目标 ⇒ 必问。 */
    val maxTargetsPerCall: Int = 20,
    /** 一次删除超过这么多个目标 ⇒ 必问。 */
    val maxDeleteTargetsPerCall: Int = 10,
    /** 网页目标文本命中这些词 ⇒ R3。 */
    val dangerousClickWords: Set<String> = setOf(
        "提交", "创建", "删除", "结算", "支付", "发布", "确认",
        "submit", "create", "delete", "pay", "publish", "confirm",
    ),
    /** 点击类工具里要扫的文本参数名。 */
    val clickTextKeys: Set<String> = setOf("target", "selector", "text"),
    /** 表单字段命中这些标记 ⇒ 硬拒（密码/验证码绝不代填）。 */
    val sensitiveFieldMarkers: Set<String> = setOf(
        "password", "passwd", "type=password", "密码", "验证码", "captcha", "otp", "one-time-code",
    ),
    /** 外发/账号/金额语义词（只扫 [ToolRule.scanSensitiveText] 的工具）。 */
    val sensitiveTextKeywords: Set<String> = setOf(
        "send", "upload", "password", "金额", "账号", "账户", "转账", "汇款",
        "支付", "结算", "密码", "验证码", "身份证", "手机号", "发送", "上传", "外发",
    ),
    /** 手机号样式（11 位、前后不接数字）。 */
    val sensitiveTextPatterns: List<String> = listOf("(?<!\\d)1[3-9]\\d{9}(?!\\d)"),
    /** 频率窗口与阈值：同一工具一个窗口内超过阈值 ⇒ +1 档。 */
    val rateWindowMs: Long = 60_000L,
    val maxCallsPerWindow: Int = 20,
    /** 内网/本机主机名后缀。 */
    val internalHostSuffixes: Set<String> = setOf(".localhost", ".local", ".internal", ".lan"),
) {
    companion object {
        /**
         * 当前矩阵版本（**改一行表就 +1**；与 `DEFAULT` 同处，改表的人不会看不见）。
         *
         * v2（第二百二十四批）：加 `form_fill` 一行（R2 ＋ 语义升 R3 ＋ 密码键硬拒）。
         * v3（第二百二十八批 B 片）：浏览器手眼断层九件入表（snapshot 几何/导航三件/动作三件/标签三件）。
         * v4（第二百二十八批 C 片）：示教/回放五件入表（录制 R0、入库 R1、回放 R2＋必问、信任授权 R1＋必问）。
         * v5（第二百三十二批）：table_query 入表（本地 R0、联网升 R2 必问）。
         * v6（第二百六十二批）：browser_evidence 入表（R0 只读证据快照）。
         */
        const val DEFAULT_VERSION: Int = 6

        /**
         * 唯一一张默认表（68 行 = 注册工具 68 个全有账；bash 豁免行留作后备——248 COMPAT 退役后它不在注册表里）。
         *
         * 68 = `BaopuToolCatalog.ENTRIES` 64 条 ＋ 运行时注册 4 条（write_skill /
         * append_persona / read / bash）；218 的六件、224 的 form_fill 与 228 批 B/C 两片
         * 的十四件都已接线在册（各自的单测直接读到这里的档）。改表必须同时动
         * [DEFAULT_VERSION]。
         */
        val DEFAULT: RiskTable = RiskTable(
            tools = mapOf(
                // ── R0 读 ─────────────────────────────────────────────────────
                "read_file" to ToolRule(RiskClass.R0),
                "list_dir" to ToolRule(RiskClass.R0),
                "search_in_files" to ToolRule(RiskClass.R0),
                // 文件管理组的两件只读：按名字找、看详情（FILES 组默认不可见）
                "find_files" to ToolRule(RiskClass.R0),
                "file_info" to ToolRule(RiskClass.R0),
                "book_outline" to ToolRule(RiskClass.R0),
                // 浏览器只读面：读快照/滚动/等待不改变页面状态；打开网址是读，URL 规则另判。
                // 173 派工单只点名了 snapshot，另外三件按「读页面」口径补齐（报告 §1 逐条说明）。
                "browser_open" to ToolRule(RiskClass.R0, urlKeys = setOf("url")),
                "browser_snapshot" to ToolRule(RiskClass.R0),
                // 262 批：证据快照只读（DOM/AX 文本，不碰页面、不落像素），与 snapshot 同档
                "browser_evidence" to ToolRule(RiskClass.R0),
                "browser_scroll" to ToolRule(RiskClass.R0),
                "browser_wait" to ToolRule(RiskClass.R0),
                // 228 批 B 片：导航三件只动历史栈，不产生新请求目标（URL 规则管不到——
                // 回退/前进的地址是历史里已经放行过的）；scroll_into_view 与 scroll 同档；
                // tabs_list 只读；tabs_switch 只改「哪颗是活跃」，不改页面内容。
                "browser_back" to ToolRule(RiskClass.R0),
                "browser_forward" to ToolRule(RiskClass.R0),
                "browser_reload" to ToolRule(RiskClass.R0),
                "browser_scroll_into_view" to ToolRule(RiskClass.R0),
                "browser_tabs_list" to ToolRule(RiskClass.R0),
                "browser_tabs_switch" to ToolRule(RiskClass.R0),
                "update_plan" to ToolRule(RiskClass.R0),
                "ask_user" to ToolRule(RiskClass.R0),
                "capabilities" to ToolRule(RiskClass.R0),
                "preview_local_file" to ToolRule(RiskClass.R0),
                "library_list" to ToolRule(RiskClass.R0),
                "kb_search" to ToolRule(RiskClass.R0),
                // kb_index 只重建 App 私有索引缓存，不碰用户文件（派工单「只读部分」口径）
                "kb_index" to ToolRule(RiskClass.R0),
                // 免费通道的 read 是 read_file 的别名

                // ── R1 写证道内 ───────────────────────────────────────────────
                "write_file" to ToolRule(
                    RiskClass.R1,
                    writePathKeys = setOf("path"),
                    collisionSensitive = true,
                    collisionNeedsFlag = "overwrite",
                ),
                "edit_file" to ToolRule(RiskClass.R1, writePathKeys = setOf("path")),
                "image_ops" to ToolRule(
                    RiskClass.R1,
                    writePathKeys = setOf("out"),
                    writeFallbackKey = "path",
                ),
                "card_build" to ToolRule(RiskClass.R1, writePathKeys = setOf("out_dir")),
                "card_inspect" to ToolRule(RiskClass.R1),
                "render_artifact" to ToolRule(RiskClass.R1),
                "draw_chart" to ToolRule(RiskClass.R1),
                "draw_table" to ToolRule(RiskClass.R1),
                "write_skill" to ToolRule(RiskClass.R1),
                "append_persona" to ToolRule(RiskClass.R1),
                // 截图会落一张图；album=true 时写手机相册（工作区外）⇒ 升 R2
                "browser_screenshot" to ToolRule(
                    RiskClass.R1,
                    externalTargetFlags = setOf("album"),
                ),

                // ── R2 改外面的 ───────────────────────────────────────────────
                "delete_path" to ToolRule(
                    RiskClass.R2,
                    writePathKeys = setOf("path"),
                    deleteLike = true,
                    unknownBatchFlag = "empty_trash",
                ),
                "move_path" to ToolRule(RiskClass.R2, writePathKeys = setOf("from", "to")),
                "convert_encoding" to ToolRule(RiskClass.R2, writePathKeys = setOf("path")),
                "browser_click" to ToolRule(RiskClass.R2, clickLike = true),
                "browser_type" to ToolRule(
                    RiskClass.R2,
                    fieldKeys = setOf("target"),
                    scanSensitiveText = true,
                ),
                "browser_tap" to ToolRule(RiskClass.R2, clickLike = true),
                "browser_swipe" to ToolRule(RiskClass.R2),
                "browser_long_press" to ToolRule(RiskClass.R2, clickLike = true),
                "browser_key" to ToolRule(RiskClass.R2),
                // 228 批 B 片：select 与 type 同口径（target 是页面目标；value 是要提交的内容
                // ⇒ 扫语义）；hover 会触发页面上挂着监听的动作 ⇒ 不按只读算；关标签不可逆
                // （关掉的页面状态没了）⇒ R2。
                "browser_select" to ToolRule(
                    RiskClass.R2,
                    fieldKeys = setOf("target"),
                    scanSensitiveText = true,
                ),
                "browser_hover" to ToolRule(RiskClass.R2),
                "browser_tabs_close" to ToolRule(RiskClass.R2),
                // 资料库整理：promote/rename 改工作区内的资料库，但派工单把它定在 R2。
                // 它的 path 是「相对 投递/产物」的口径（GuardPipeline P0-4），不做越界路径判定。
                "library_organize" to ToolRule(RiskClass.R2),
                "novel_create" to ToolRule(RiskClass.R2),
                "novel_project" to ToolRule(RiskClass.R2),
                "novel_continue" to ToolRule(RiskClass.R2),
                "run_subtasks" to ToolRule(RiskClass.R2),
                "card_export" to ToolRule(RiskClass.R2, writePathKeys = setOf("out")),

                // ── R3 花钱外发账号 ───────────────────────────────────────────
                "generate_image" to ToolRule(RiskClass.R3),

                // ── 195 物流查询：本机当日表只读，不联网不调模型 ──────────────
                "logistics_query" to ToolRule(RiskClass.R0),
                // ── 218 预置六件（工具还没合入，档先钉死） ─────────────────────
                "sheet_read" to ToolRule(RiskClass.R0),
                // ── 232 通用表格问答：只读用户的表（唯一落盘是 App 自己的登记簿）──────
                "table_query" to ToolRule(RiskClass.R0, networkEscalates = true),
                "search_web" to ToolRule(RiskClass.R0, urlKeys = setOf("site")),
                "schedule_task" to ToolRule(
                    RiskClass.R2,
                    scanSensitiveText = true,
                    alwaysAsk = true,
                ),
                "clipboard_write" to ToolRule(RiskClass.R1, scanSensitiveText = true),
                "notify" to ToolRule(RiskClass.R1, scanSensitiveText = true),
                // 218：拨号只打开系统界面、不呼出 ⇒ R1；号码是它的本职工作，不按账号语义升档
                "phone_dial" to ToolRule(RiskClass.R1),
                // 224 表单能力接线：form_fill 只填不提交。基档 R2（改外部页面状态），三件事在这一行说清：
                // ① fieldKeys=fields ⇒ 键名像密码/验证码的字段硬拒（⑥ 段，对象取键名）；
                // ② clickNestedKeys=fields ⇒ 键名像提交/登录/支付/发布就升 R3 必问（⑤ 段）；
                // ③ extraClickWords ⇒ 全局词表缺的「登录/登陆」只挂本行，不动别人的判定面。
                "form_fill" to ToolRule(
                    RiskClass.R2,
                    clickLike = true,
                    clickNestedKeys = setOf("fields"),
                    extraClickWords = setOf("登录", "登陆"),
                    fieldKeys = setOf("fields"),
                ),
                // 228 批 C 片：示教/回放五件。record_start/stop 只观察不代做（R0；信任名单
                // 默认空＝连录制都开不了，闸在工具层）；skill_save 写技能库（R1，走 184 写口
                // ＋凭证扫描，录到密码拒存）；replay_skill 自动执行页面动作（R2＋alwaysAsk：
                // 每次回放一张卡，提交类点击由 TeachSafety 硬拒）；site_trust 是授权类
                // （R1＋alwaysAsk：授权必问、撤销也过卡——fail-safe 方向）。
                "teach_record_start" to ToolRule(RiskClass.R0),
                "teach_record_stop" to ToolRule(RiskClass.R0),
                "teach_skill_save" to ToolRule(RiskClass.R1),
                "browser_replay_skill" to ToolRule(RiskClass.R2, alwaysAsk = true),
                "teach_site_trust" to ToolRule(RiskClass.R1, alwaysAsk = true),
            ),
            // 173 口径：终端不参与分级——bash 不查档、不叠规则，原样交回终端那条路。
            // 注意它必须**显式豁免**：若只返回 null，fail-closed 会把它当未分类判 R2＋必问，
            // 那等于「把终端纳入了四档」，正是负例 7 的反面。
            exempt = setOf("bash"),
        )
    }
}

/**
 * 风险矩阵本体（纯函数查表）。
 *
 * 三条硬口径：
 * 1. **全覆盖**：[uncovered] 对注册表名册求差集，缺一档就是门禁红；
 * 2. **fail-closed**：查不到档且未豁免 ⇒ R2＋必问，并打 `unclassified_tool` 标签；
 * 3. **表驱动**：档位、阈值、关键词全在 [RiskTable]；判定函数里不出现这些数字。
 */
internal object RiskMatrix {

    /** 工具的底档；null = 未分类**或**已豁免（用 [isExempt] 区分）。 */
    fun baseOf(tool: String, table: RiskTable = RiskTable.DEFAULT): RiskClass? =
        table.tools[tool]?.base

    /** 终端等「不归本表」的名字。 */
    fun isExempt(tool: String, table: RiskTable = RiskTable.DEFAULT): Boolean =
        tool in table.exempt

    /** 门禁用：这些注册名里谁缺档（不含显式豁免）。空 = 全覆盖。 */
    fun uncovered(names: Collection<String>, table: RiskTable = RiskTable.DEFAULT): List<String> =
        names.filter { it !in table.tools && it !in table.exempt }.distinct().sorted()

    /**
     * 参数级/组合升档后的最终判定。
     *
     * 叠加顺序：硬拒（原件区/密码框/URL）→ 越界/覆盖/批量/点击/语义/频率；
     * **升档取最高、硬拒优先**。全程只读入参，不产生任何副作用。
     */
    fun classify(
        call: ToolCall,
        ctx: RiskCtx = RiskCtx(),
        table: RiskTable = RiskTable.DEFAULT,
    ): RiskVerdict {
        if (isExempt(call.name, table)) {
            return RiskVerdict(
                risk = null,
                decision = RiskDecision.EXEMPT,
                matrixVersion = table.matrixVersion,
            )
        }
        val rule = table.tools[call.name]
            ?: return RiskVerdict(
                risk = RiskClass.R2,
                decision = RiskDecision.MUST_ASK,
                reasons = listOf(
                    RiskReason(
                        RiskCode.UNCLASSIFIED_TOOL,
                        "工具「${call.name}」没有风险档：按 R2 处理且必须问（fail-closed）",
                    ),
                ),
                unclassified = true,
                matrixVersion = table.matrixVersion,
            )

        val args = call.arguments
        val reasons = mutableListOf<RiskReason>()
        var risk = rule.base
        var reject = false
        var mustAsk = false

        // 243：本地只读工具一旦走联网 ⇒ R2 必问（事实由调用方注入）
        if (rule.networkEscalates && ctx.networkUsed) {
            risk = higherOf(risk, RiskClass.R2)
            mustAsk = true
            reasons += RiskReason(RiskCode.NETWORK_ESCALATED, "t上联网了：从只读升到 R2，必须问")
        }

        val targets = writeTargets(rule, args)
        val roots = ctx.workspaceRoots.map(::normalizePath)
        val existing = ctx.existingPaths.map(::normalizePath).toSet()

        // ① 原件区：不是升档，是硬拒（只判写目标；读原件区仍由既有只读红线放行）
        for (raw in targets) {
            val path = normalizePath(raw)
            if (isOriginalZone(path, roots, table)) {
                reject = true
                reasons += RiskReason(
                    RiskCode.PATH_ORIGINAL_RAW,
                    "写目标落在原件区：$path（原件区只读，一个字节都不许碰）",
                )
            }
        }

        // ② 写工作区外 / album 写相册 ⇒ R2
        for (raw in targets) {
            val path = normalizePath(raw)
            if (isOutsideWorkspace(path, roots)) {
                risk = higherOf(risk, RiskClass.R2)
                reasons += RiskReason(RiskCode.PATH_OUTSIDE_WORKSPACE, "写目标在工作区外：$path")
            }
        }
        for (flag in rule.externalTargetFlags) {
            if (args.optBoolean(flag, false)) {
                risk = higherOf(risk, RiskClass.R2)
                reasons += RiskReason(RiskCode.PATH_OUTSIDE_WORKSPACE, "参数 $flag=true 写到了工作区外")
            }
        }

        // ③ 同名覆盖 ⇒ 至少 R2
        if (rule.collisionSensitive) {
            val willWrite = rule.collisionNeedsFlag?.let { args.optBoolean(it, false) } ?: true
            if (willWrite) {
                for (raw in targets) {
                    val path = normalizePath(raw)
                    if (path in existing) {
                        risk = higherOf(risk, RiskClass.R2)
                        reasons += RiskReason(RiskCode.OVERWRITE_EXISTING, "覆盖已有文件：$path")
                    }
                }
            }
        }

        // ④ 批量：>20 个目标、删除 >10 个、清空回收站 ⇒ 必问
        val batchScope = rule.deleteLike || targets.isNotEmpty() || rule.externalTargetFlags.isNotEmpty()
        if (batchScope) {
            var count = targets.size
            for (key in table.batchListKeys) {
                count += args.optJSONArray(key)?.length() ?: 0
            }
            val unknownBatch = rule.unknownBatchFlag?.let { args.optBoolean(it, false) } == true
            if (unknownBatch) {
                count = Int.MAX_VALUE
                reasons += RiskReason(RiskCode.EMPTY_TRASH, "清空回收站：删除数量未知，必须问")
            }
            if (rule.deleteLike && count > table.maxDeleteTargetsPerCall) {
                mustAsk = true
                reasons += RiskReason(
                    RiskCode.DELETE_OVER_10,
                    "本次删除 $count 个目标（上限 ${table.maxDeleteTargetsPerCall}）——必须问",
                )
            }
            if (count > table.maxTargetsPerCall) {
                mustAsk = true
                reasons += RiskReason(
                    RiskCode.BATCH_OVER_20,
                    "本次涉及 $count 个目标（上限 ${table.maxTargetsPerCall}）——必须问",
                )
            }
        }

        // ⑤ 网页目标文本命中提交/结算/支付… ⇒ R3
        if (rule.clickLike) {
            val words = table.dangerousClickWords + rule.extraClickWords
            // 平铺参数（browser_click/tap 的 target/selector/text）＋ 本行声明的嵌套面
            val texts = table.clickTextKeys.map { args.optString(it) }.filter { it.isNotEmpty() } +
                rule.clickNestedKeys.flatMap { nestedFaces(args.opt(it)) }
            val hit = words.firstOrNull { w -> texts.any { it.contains(w, ignoreCase = true) } }
            if (hit != null) {
                risk = higherOf(risk, RiskClass.R3)
                reasons += RiskReason(RiskCode.SUBMIT_LIKE_BUTTON, "网页目标命中高危词「$hit」")
            }
        }

        // ⑥ 密码框/验证码框 ⇒ 硬拒
        if (rule.fieldKeys.isNotEmpty()) {
            val markers = table.sensitiveFieldMarkers
            var hit: String? = null
            for (key in rule.fieldKeys) {
                // 第二百二十四批：`fields` 是对象时，**键名就是要填的字段名**
                // （`{"密码":"…"}` 与 `{"password":"…"}` 都必须在这里被拒）
                val faces = when (val raw = args.opt(key)) {
                    is JSONObject -> raw.keys().asSequence().toList()
                    else -> listOf(args.optString(key)).filter { it.isNotEmpty() }
                }
                hit = faces.firstNotNullOfOrNull { face ->
                    markers.firstOrNull { face.contains(it, ignoreCase = true) }
                }
                if (hit != null) break
            }
            if (hit != null) {
                reject = true
                reasons += RiskReason(RiskCode.PASSWORD_FIELD, "目标字段是密码/验证码框（命中「$hit」）")
            }
        }

        // ⑦ URL：内网/本机 ⇒ 硬拒；配置了白名单时白名单外也拒
        if (rule.urlKeys.isNotEmpty()) {
            for (key in rule.urlKeys) {
                val raw = args.optString(key).trim()
                if (raw.isEmpty()) continue
                val host = hostOf(raw) ?: continue
                when {
                    isInternalHost(host, table) -> {
                        reject = true
                        reasons += RiskReason(RiskCode.PRIVATE_URL, "目标是内网/本机地址：$host")
                    }

                    ctx.allowedUrlDomains != null -> {
                        val ok = ctx.allowedUrlDomains.any { domainMatches(host, it) }
                        if (!ok) {
                            reject = true
                            reasons += RiskReason(RiskCode.URL_NOT_WHITELISTED, "域名不在白名单：$host")
                        }
                    }
                }
            }
        }

        // ⑧ 外发/账号/金额语义 ⇒ R3（只扫对外通道；不回显原文）
        if (rule.scanSensitiveText) {
            val texts = stringLeaves(args)
            val word = table.sensitiveTextKeywords.firstOrNull { kw ->
                texts.any { it.contains(kw, ignoreCase = true) }
            }
            val patterned = table.sensitiveTextPatterns.any { pattern ->
                val regex = Regex(pattern)
                texts.any { regex.containsMatchIn(it) }
            }
            if (word != null || patterned) {
                risk = higherOf(risk, RiskClass.R3)
                val what = word?.let { "命中「$it」" } ?: "命中手机号样式"
                reasons += RiskReason(RiskCode.SENSITIVE_SEMANTICS, "参数含外发/账号/金额语义（$what）")
            }
        }

        // ⑨ 频率：同一工具一个窗口内超阈 ⇒ +1 档
        val windowStart = ctx.nowMs - table.rateWindowMs
        val recent = ctx.recentCalls[call.name].orEmpty().count { it in windowStart..ctx.nowMs }
        if (recent + 1 > table.maxCallsPerWindow) {
            risk = risk.next()
            reasons += RiskReason(
                RiskCode.FREQ_OVER_LIMIT,
                "同一工具 ${table.rateWindowMs / 1000} 秒内第 ${recent + 1} 次（上限 ${table.maxCallsPerWindow}）",
            )
        }

        if (rule.base == RiskClass.R3) {
            reasons += RiskReason(RiskCode.BASE_R3, "R3：花钱/外发/账号类工具")
        }
        if (rule.alwaysAsk) {
            reasons += RiskReason(RiskCode.ALWAYS_ASK, "工具表点名必须问（会产生无人值守的动作）")
        }

        val decision = when {
            reject -> RiskDecision.REJECT
            mustAsk || rule.alwaysAsk || risk == RiskClass.R3 -> RiskDecision.MUST_ASK
            else -> RiskDecision.PROCEED
        }
        return RiskVerdict(
            risk = risk,
            decision = decision,
            reasons = reasons,
            matrixVersion = table.matrixVersion,
        )
    }

    // ── 纯工具函数（全部无 IO） ─────────────────────────────────────────────

    /** 该工具这次会写到的路径原文（按表声明；`image_ops` 的 out 缺省时落回 path）。 */
    private fun writeTargets(rule: ToolRule, args: JSONObject): List<String> {
        val explicit = rule.writePathKeys
            .filter { args.has(it) }
            .map { args.optString(it).trim() }
            .filter { it.isNotEmpty() }
        if (explicit.isNotEmpty()) return explicit
        val fallback = rule.writeFallbackKey?.let { args.optString(it).trim() }.orEmpty()
        return if (fallback.isNotEmpty()) listOf(fallback) else emptyList()
    }

    /** 归一化：`\`→`/`、折叠 `.`/`..`、保留盘符与绝对性（纯字符串，不碰盘）。 */
    private fun normalizePath(raw: String): String {
        var s = raw.trim().replace('\\', '/')
        if (s.isEmpty()) return ""
        val drive = if (s.length >= 2 && s[1] == ':') s.substring(0, 2).lowercase() else ""
        val absolute = drive.isNotEmpty() || s.startsWith("/")
        var body = when {
            drive.isNotEmpty() -> s.substring(2)
            s.startsWith("/") -> s.substring(1)
            else -> s
        }
        val out = ArrayList<String>()
        for (seg in body.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty() && out.last() != "..") out.removeAt(out.lastIndex) else if (!absolute) out.add("..")
                else -> out.add(seg)
            }
        }
        val joined = out.joinToString("/")
        return when {
            drive.isNotEmpty() -> if (joined.isEmpty()) "$drive/" else "$drive/$joined"
            absolute -> "/$joined"
            else -> joined
        }
    }

    /** 相对路径 = 工作区内；绝对路径必须落在某个工作区根下。 */
    private fun isOutsideWorkspace(normalized: String, roots: List<String>): Boolean {
        if (normalized.isEmpty()) return false
        val drive = normalized.length >= 2 && normalized[1] == ':'
        val absolute = drive || normalized.startsWith("/")
        if (!absolute) return false
        if (roots.isEmpty()) return true
        val p = normalized.lowercase()
        return roots.none { root ->
            val r = root.lowercase()
            p == r || p.startsWith("$r/")
        }
    }

    /** 原件区：`资料库/原始/`、相对根 `原始/`、私有 `…/library/原始/`。 */
    private fun isOriginalZone(normalized: String, roots: List<String>, table: RiskTable): Boolean {
        if (normalized.isEmpty()) return false
        val absSegments = normalized.trim('/').split('/').filter { it.isNotEmpty() }
        val relSegments = relativeToRoots(normalized, roots)
        // 相对根就是 `原始/…`
        if (relSegments.firstOrNull() == "原始") return true
        // 其余按「组件序列出现」判：原始2/、产物/原始/ 这类同前缀/同名兄弟都不会误中
        return table.originalZoneSegments
            .filter { it.size >= 2 }
            .any { seq -> containsSequence(absSegments, seq) || containsSequence(relSegments, seq) }
    }

    /** 绝对路径去掉命中的工作区根后剩下的组件；相对路径原样拆。 */
    private fun relativeToRoots(normalized: String, roots: List<String>): List<String> {
        val p = normalized.lowercase()
        val hit = roots.firstOrNull { root ->
            val r = root.lowercase()
            p == r || p.startsWith("$r/")
        }
        val rel = if (hit != null) normalized.substring(hit.length).trim('/') else normalized.trim('/')
        return rel.split('/').filter { it.isNotEmpty() }
    }

    private fun containsSequence(segments: List<String>, seq: List<String>): Boolean {
        if (seq.size > segments.size) return false
        for (i in 0..(segments.size - seq.size)) {
            var ok = true
            for (j in seq.indices) {
                if (segments[i + j] != seq[j]) {
                    ok = false
                    break
                }
            }
            if (ok) return true
        }
        return false
    }

    /** 从 URL 里手工取 host（不引 java.net，判定层保持零网络依赖）。 */
    private fun hostOf(raw: String): String? {
        var s = raw.trim()
        val schemeAt = s.indexOf("://")
        if (schemeAt >= 0) s = s.substring(schemeAt + 3)
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@')
        if (s.startsWith("[")) {
            s = s.substringAfter('[').substringBefore(']')
        } else {
            s = s.substringBefore(':')
        }
        return s.trim().lowercase().ifBlank { null }
    }

    /** 内网/本机：localhost、私有 IPv4、链路本地、IPv6 环回、单标签主机名。 */
    private fun isInternalHost(host: String, table: RiskTable): Boolean {
        if (host == "localhost" || host == "::1" || host == "0.0.0.0") return true
        if (table.internalHostSuffixes.any { host.endsWith(it) }) return true
        if (host.contains(':') && (host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd"))) return true
        val ipv4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(host)
        if (ipv4 != null) {
            val a = ipv4.groupValues[1].toInt()
            val b = ipv4.groupValues[2].toInt()
            return a == 0 || a == 10 || a == 127 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254)
        }
        // 没有点的单标签主机名 = 内网短名（public 域名至少有一个点）
        return !host.contains('.') && !host.contains(':')
    }

    private fun domainMatches(host: String, allowed: String): Boolean {
        val d = allowed.trim().lowercase().removePrefix(".")
        if (d.isEmpty()) return false
        return host == d || host.endsWith(".$d")
    }

    /**
     * 嵌套参数里的「语义面」（第二百二十四批）：对象取**成员名**（`{"提交":"x"}` 的键
     * 就是准备填的字段名），数组逐个递归，字符串原样。**不取对象成员的值**——那会把
     * 「正文里写了『提交』两个字」误判成提交动作（假必问会训练用户无脑点同意）。
     */
    private fun nestedFaces(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is String -> listOf(value)
        is JSONObject -> value.keys().asSequence().toList()
        is JSONArray -> (0 until value.length()).flatMap { nestedFaces(value.opt(it)) }
        else -> emptyList()
    }

    /** arguments 里所有字符串叶子（数组/对象递归；只读）。 */
    private fun stringLeaves(args: JSONObject): List<String> {        val out = mutableListOf<String>()
        fun walk(value: Any?) {
            when (value) {
                is String -> out += value
                is JSONObject -> value.keys().forEach { walk(value.opt(it)) }
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i))
            }
        }
        args.keys().forEach { walk(args.opt(it)) }
        return out
    }

    private fun RiskClass.next(): RiskClass = RiskClass.entries.getOrElse(ordinal + 1) { this }

    private fun higherOf(a: RiskClass, b: RiskClass): RiskClass = if (a.ordinal >= b.ordinal) a else b
}
