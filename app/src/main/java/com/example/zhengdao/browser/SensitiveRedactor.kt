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
 * 状态：骨架（未实现）。正则模式为 ③ 初版，实施时需真机样本校准（误报/漏报率）。
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
        // TODO(阶段1)：PHONE / BANK_CARD / PASSWORD_KV / ID_CARD 等模式逐项替换为 MASK
        return input
    }

    /** 脱敏 DOM 快照（input[type=password] 的 value、token 样式内容等）。 */
    fun redactDom(html: String): String {
        if (!enabled || html.isEmpty()) return html
        // TODO(阶段1)：解析 DOM，password 类输入框值置 MASK；attribute 里的 token 置 MASK
        return html
    }

    /** 脱敏 cookie / localStorage 读取结果（键值对形式，§4.5 snapshot 边界）。 */
    fun redactKeyValues(kvs: String): String {
        if (!enabled || kvs.isEmpty()) return kvs
        // TODO(阶段1)：session/token/auth/cookie 类键的值置 MASK
        return kvs
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
    }
}
