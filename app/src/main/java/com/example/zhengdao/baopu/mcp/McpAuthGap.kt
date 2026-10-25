// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百一十五批 · MCP 传输校正：**鉴权差距清单（只列不实现）**。
//
// 背景：2026-07-28 修订删掉协议级会话的同时，把鉴权从「贴静态 token」升级为 OAuth
// 标准流程。本批只做传输校正（版本协商 + 去会话依赖），鉴权实现归 216；这个文件是
// 两批之间的「差距账」——现状（配置里的静态 headers）⇄ OAuth 要求，逐项给结论。
//
// 为什么不只写在报告里：报告是给人看的，会漂；清单落成代码 + 门禁（McpAuthGapTest）
// 后，少一项、空一项都会红。216 开工时按这张单接，不用回头翻邮件。
// 本文件**没有任何网络/存储代码**，纯数据。
package com.example.zhengdao.baopu.mcp

/** 一项差距的结论（派工单 §2.5 的三选一）。 */
internal enum class McpAuthGapStatus {
    /** 现状已经满足（或口径现成、OAuth 实现直接复用）。 */
    已满足,

    /** 现状没有，OAuth 流程必须要。 */
    缺,

    /** 现状沾边但不等于满足，或要先拍板才能做 ⇒ 另立单。 */
    需另立单,
}

/** 一条差距：一个 OAuth 能力点，现状 vs 要求，加结论。 */
internal data class McpAuthGapItem(
    /** 能力点名（报告表格的行名）。 */
    val topic: String,
    /** 现状（215 前的实现：配置里手填静态 headers）。 */
    val current: String,
    /** OAuth（2026-07-28 Authorization 章）的要求。 */
    val required: String,
    val status: McpAuthGapStatus,
    /** 补充：手机端现实约束 / 216 的落点建议。 */
    val note: String = "",
)

internal object McpAuthGap {

    /**
     * 差距清单（顺序即报告表格顺序）。
     *
     * 覆盖派工单点名六项（授权服务器发现 / 客户端注册 / resource 参数 / token 刷新 /
     * scope challenge / 手机端无浏览器回调）+ 两项本批审计顺带发现的（token 使用方式、
     * token 存放与泄漏面）。
     */
    val ITEMS: List<McpAuthGapItem> = listOf(
        McpAuthGapItem(
            topic = "授权服务器发现",
            current = "无。连接失败只按 HTTP 码回「认证失败（401/403），请检查请求头/服务端权限」，" +
                "不知道授权服务器在哪、也不知道它要什么流程",
            required = "401/403 的响应带 WWW-Authenticate（resource_metadata 参数），客户端按它取 " +
                "Protected Resource Metadata（RFC 9728）发现授权服务器",
            status = McpAuthGapStatus.缺,
            note = "216：McpClient 的 AUTH 分支解析 WWW-Authenticate，取到 metadata URL 后报给 UI",
        ),
        McpAuthGapItem(
            topic = "客户端注册",
            current = "无。没有 client_id 这个概念——用户手填的 token 就是全部凭据",
            required = "向授权服务器注册客户端（RFC 7591 动态注册，或预置 client_id），" +
                "拿到 client_id 才能走后续授权码流程",
            status = McpAuthGapStatus.缺,
            note = "216：优先动态注册；本地只存非机密信息（client_id），不存 client_secret（手机端装不住机密）",
        ),
        McpAuthGapItem(
            topic = "resource 参数",
            current = "无。请求只带用户手填的头，没有任何「这个 token 是给哪个资源用的」标记",
            required = "取 token 时带 resource 参数（RFC 8707），值是 MCP server 的规范化 URI——" +
                "防止 token 被拿去访问别的资源",
            status = McpAuthGapStatus.缺,
            note = "216：resource = McpUrlRules.redact 后的 server URL（canonical URI 形态）",
        ),
        McpAuthGapItem(
            topic = "access token 的获取与使用",
            current = "「带 token」这件事是现成的：config.headers 每个请求原样带。" +
                "但 token 来源是用户手填，过期了只能用户自己发现、自己换",
            required = "token 来自授权码流程（授权码 + PKCE 换 access_token），按 scope 颁发",
            status = McpAuthGapStatus.需另立单,
            note = "216 的主菜；「每个请求带头」的现成机制直接复用，不用重写传输",
        ),
        McpAuthGapItem(
            topic = "refresh token 刷新",
            current = "无。token 过期后连接失败，人话只到「认证失败」，不会尝试刷新",
            required = "access_token 过期前/401 后用 refresh_token 换新的（带 resource 参数），" +
                "失败（refresh_token 也死了）才让用户重新授权",
            status = McpAuthGapStatus.缺,
            note = "216：刷新要在 401 重试一次之前做；refresh_token 的存放见下条",
        ),
        McpAuthGapItem(
            topic = "scope challenge 与 step-up 授权",
            current = "无。403 与 401 同一句「认证失败」，分不清「没登录」和「权限不够」",
            required = "403 insufficient_scope + WWW-Authenticate 里带缺的 scope ⇒ 用 " +
                "incremental scope 重新走一遍授权（step-up），而不是让用户重新登录",
            status = McpAuthGapStatus.缺,
            note = "216：error 文案要能说出「缺哪个 scope」，模型/用户才知道去批什么",
        ),
        McpAuthGapItem(
            topic = "手机端无浏览器回调",
            current = "无回调机制（本来就没有授权流程）",
            required = "授权码流程要 redirect_uri；手机上常见三条路：环回 127.0.0.1 随机端口、" +
                "自定义 scheme、claimed https URI（Android App Links）",
            status = McpAuthGapStatus.需另立单,
            note = "216 开工前先拍板：建议环回端口 + Custom Tab——与 McpUrlRules 的环回白名单同口径，" +
                "不引入 scheme 劫持面；真机无头环境下由用户点一次浏览器",
        ),
        McpAuthGapItem(
            topic = "token 存放与泄漏面",
            current = "口径现成：请求头只在内存、不落盘（McpServerStore 白名单字段不含头）、" +
                "不进日志/错误原文、不跟重定向（followRedirects=false）",
            required = "OAuth token 的存放与泄漏面和静态头**同级或更严**（refresh_token 更要命）",
            status = McpAuthGapStatus.已满足,
            note = "216 直接复用这三条口径；token 若要落盘须先过「密钥不落 SP」的设计评审",
        ),
    )

    /** 派工单点名必须覆盖的六项（缺一项 McpAuthGapTest 就红）。 */
    val REQUIRED_TOPICS: List<String> = listOf(
        "授权服务器发现",
        "客户端注册",
        "resource 参数",
        "refresh token 刷新",
        "scope challenge 与 step-up 授权",
        "手机端无浏览器回调",
    )
}
