package com.example.zhengdao.browser

/**
 * 敏感信息脱敏器 —— §4.3 / §4.4 的强制脱敏红线（硬红线区第⑦项）。
 *
 * 设计依据：《证道-浏览器Tab技术选型与架构草案-2026-10-13》v1.1 §4.3（脱敏段）、
 * §4.4「隐私脱敏(强制、不可绕过)」行。
 * 用户定调（2026-10-13，②）："快照里的密码/卡号/手机号等等个人隐私强制脱敏，
 * 不准 agent 和 AI 什么的碰。"
 *
 * 铁律：
 * 1. 覆盖**所有离开浏览器、流向 agent/AI 上下文的数据**：快照文本/DOM、execJS 返回值、
 *    cookie/localStorage 读取结果（§4.4）。
 * 2. 在**桥边界**强制执行，与 agent 权限高低无关（§4.3）。
 * 3. agent/AI **无任何手段关闭或绕过**：不能改脱敏规则、不能借 execJS 读原始值、
 *    不能借其他命令偷读。
 * 4. 仅人类所有者可在 App「浏览器桥」设置里手动、知情风险后关闭（用户自主权，非 agent 能力）。
 *
 * 状态：第㊵刀起 URL/键值对出口齐备——[redactUrl]（status/navigate 的 url）、
 * [redactPairs]（execJS 的 cookie/localStorage 返回）均为真实调用点；正文 email
 * 模式仍缺（有意，见 [redactUrl] KDoc），其余模式 ③ 初版待真机样本校准。
 */
class SensitiveRedactor(

    /**
     * 人类所有者开关（默认 true = 强制脱敏）。
     * 只有 App 设置项能改它；桥与 agent 侧无任何写入口（红线⑦，§4.3）。
     */
    private var enabled: Boolean = true,
) {
    /** 脱敏纯文本（快照文本 / console / execJS 返回值）。检测到的 PII 一律替换为 [MASK]。 */
    fun redactText(input: String): String {
        if (!enabled || input.isEmpty()) return input
        // 顺序：先密码键值（整段，避免值里的数字被后续号码规则部分匹配），再号码。
        // ID_CARD 必须早于 BANK_CARD：否则身份证的 17 位数字部分被卡号规则先吃、漏个 X（"***X"）。
        // 全部命中一律 MASK——宁可误报（长数字串被当卡号）不漏报（隐私优先，用户定调 ②）。
        var out = PASSWORD_KV.replace(input) { m -> m.groupValues[1] + "=" + MASK }
        out = PHONE.replace(out, MASK)
        out = ID_CARD.replace(out, MASK)
        out = BANK_CARD.replace(out, MASK)
        return out
    }

    /**
     * password 类输入框的 value 属性（DOM 快照用）。
     * TODO(阶段3)：接桥时换完整 DOM 解析（jsoup 或 WebMessagePort 侧结构化处理），
     * 正则只能覆盖规整写法；本版 + redactText 兜底已可挡常见形态。
     */
    private val PASSWORD_INPUT = Regex(
        """(?i)(<input\b[^>]*\btype\s*=\s*["']?password["']?[^>]*?\bvalue\s*=\s*["'])([^"']*)(["'])"""
    )

    /** 脱敏 DOM 快照：password input 值置 MASK，其余文本再过 [redactText] 兜底。 */
    fun redactDom(html: String): String {
        if (!enabled || html.isEmpty()) return html
        val maskedInputs = PASSWORD_INPUT.replace(html) { m ->
            m.groupValues[1] + MASK + m.groupValues[3]
        }
        return redactText(maskedInputs)
    }

    /**
     * 脱敏键值对串（cookie / localStorage 形态：`k=v; k2=v2`）——第㊵刀新增，
     * 承接原 `redactKeyValues` 的 [SENSITIVE_KEY] 模式。原函数全仓零生产调用点
     * （死代码造成"cookie 已脱敏"的虚假安全感，第㊵刀删除之），其能力改由两个
     * **真实出口**承担：本函数（execJS 的 cookie/localStorage 返回值）与 [redactUrl]
     * （URL query 的敏感键名）。命中键名一律值打码、键名保留。
     */
    fun redactPairs(text: String): String {
        if (!enabled || text.isEmpty()) return text
        return SENSITIVE_KEY.replace(text) { m -> m.groupValues[1] + "=" + MASK }
    }

    /**
     * URL 脱敏（第㊵刀新增）：**凡把 tab url 写进桥响应体的命令都必须过这里**
     * （status / navigate 回执——真机实证过：URL query 里的 PII 曾被原样带回）。
     *
     * 两段式：
     * ① query 的**敏感键名**（[SENSITIVE_QUERY_KEY]：session/token/auth/cookie/jwt/
     *    secret/api-key/password/sign/email/tel/phone/mobile/id/idcard/card/bankcard
     *    ＋ `[\w-]*` 后缀如 sessionid / csrf_token / api-key / user_token）值打码；
     * ② 剩余部分整体过 [redactText]——兜底 query/路径里明文的号码类 PII。
     *
     * 已知边界（有意为之，报告留痕）：**正文/路径里的 email 不加全局 EMAIL 模式**——
     * email 正则误报面大（任何 `a@b.c` 都中，含文档示例与错误信息），需真机样本
     * 校准率；URL query 的 `email=` 键已由①覆盖，正文 email 模式单独立项。
     */
    fun redactUrl(url: String): String {
        if (!enabled || url.isEmpty()) return url
        val q = url.indexOf('?')
        if (q < 0) return redactText(url)
        val head = url.substring(0, q + 1)
        val query = url.substring(q + 1)
        val masked = SENSITIVE_QUERY_KEY.replace(query) { m -> m.groupValues[1] + "=" + MASK }
        return redactText(head + masked)
    }

    companion object {
        /** 脱敏替换符（§4.3：一律脱敏为 ***）。 */
        const val MASK = "***"

        // PII 模式（③ 初版，实施时真机样本校准）：
        /** 中国大陆手机号（11 位，1[3-9] 开头）。 */
        val PHONE = Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""")

        /** 银行卡号（16-19 位；初版不校验 Luhn，TODO(阶段1) 降低误报）。 */
        val BANK_CARD = Regex("""(?<!\d)\d{16,19}(?!\d)""")

        /** 密码类键值（password/passwd/pwd = 值）。 */
        val PASSWORD_KV = Regex("""(?i)(password|passwd|pwd)["']?\s*[:=]\s*["']?[^\s"',;]+""")

        /** 身份证号（18 位，末位可 X）。 */
        val ID_CARD = Regex("""(?<![0-9Xx])\d{17}[\dXx](?![0-9Xx])""")

        /**
         * 敏感键的值置 MASK（cookie/localStorage/session）：命中则值置 MASK，完整键名保留。
         * 键名后允许 [\w-]* 后缀（sessionid / csrf_token / api-key 都算）。
         * 已知误报（可接受，隐私优先）：author= 也会被当敏感键打码。
         */
        val SENSITIVE_KEY = Regex(
            """(?i)((?:session|token|auth|cookie|jwt|secret|api[_-]?key|passwd|password)[\w-]*)["']?\s*[:=]\s*["']?[^\s"',;}]+"""
        )

        /**
         * URL query 的敏感键名（第㊵刀 [redactUrl] 用）——比 [SENSITIVE_KEY] 宽：
         * 加 sign/signature（签名参数）、email/tel/phone/mobile（联系信息）、
         * id/idcard/card/bankcard（证件卡号）；值取到 `&` 或串尾（query 标准形态）。
         * 已知误报（可接受，隐私优先）：`?id=42` 这类普通文章 id 也会打码。
         */
        val SENSITIVE_QUERY_KEY = Regex(
            """(?i)((?:session|token|auth|cookie|jwt|secret|api[_-]?key|password|passwd|pwd|sign|signature|email|tel|phone|mobile|idcard|id|card|bankcard)[\w-]*)=([^&]*)"""
        )
    }
}
