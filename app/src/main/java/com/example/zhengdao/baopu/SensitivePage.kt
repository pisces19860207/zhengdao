// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第一百七十七批施工图 §3（授权闸）＋用户 2026-10-24 13:2x 第三条：
// 「不逐张弹卡可以，但必须有一次会话授权/首次确认＋审计，不能后台偷截；
//   用户没要求时不要主动截敏感页。」零 Android、零 IO、纯函数，JVM 直测。
package com.example.zhengdao.baopu

/**
 * 敏感页判据：登录 / 支付 / 验证码 / 银行 / 钱包一类页面。
 *
 * ## 为什么需要它
 * 「截图发给模型」的风险与页面内容直接相关：一个新闻页截图无害，一个登录页截图
 * 可能带账号、余额、验证码。派工单 §1.5 要求「登录/验证码/支付停下列卡」；
 * 本批把它落成**纯判据**：命中 ⇒ 即使本会话已授权截图，**每次都要再问一次**。
 *
 * ## 判据怎么定（宁可多问一次，不许漏）
 * - 英文键按**整词**比对（把 URL/标题按非字母数字切片，再看是否等于某个键）：
 *   `login.example.com` ✓；`author.com` ✗（不等于 `auth`，避免把 author 当认证页）；
 * - 中文键按**包含**（中文没有空格分词）：`「账户登录」`、`「微信支付」` 都命中；
 * - 只看 URL 与标题——**不看页面正文**：正文判定要读 DOM，反而会把整页内容过一遍
 *   才决定要不要截图，等于先读了再说，与「少碰敏感页」的初衷相背。
 *
 * ## 局限（写进报告，不当已验）
 * 域名/标题不含上述词的登录页（如自定义短链、纯图标页）判不出来 ⇒ 兜底是
 * 「会话首次确认」那张卡本身，且模型侧提示词还写着「用户没要求就别截敏感页」。
 */
internal object SensitivePage {

    /** 英文整词键（小写）。只放**能明确指向认证/支付**的词。 */
    private val ASCII_KEYS: Set<String> = setOf(
        "login", "signin", "logon", "logout",
        "password", "passwd", "passcode",
        "pay", "payment", "checkout", "billing",
        "bank", "banking", "wallet", "card", "creditcard",
        "captcha", "verify", "verification", "otp", "2fa", "mfa",
        "auth", "oauth", "sso",
        "transfer", "withdraw",
    )

    /** 中文键（包含匹配；中文无分词）。 */
    private val CJK_KEYS: List<String> = listOf(
        "登录", "登陆", "密码", "验证码", "支付", "付款", "收银台",
        "银行", "钱包", "转账", "提现", "身份证", "实名",
    )

    /**
     * 这个页面像不像敏感页。
     *
     * @param url 当前页 URL（可空——拿不到状态时只按标题判）
     * @param title 当前页标题（可空）
     */
    fun isSensitive(url: String?, title: String?): Boolean {
        val hay = (url ?: "") + " " + (title ?: "")
        if (hay.isBlank()) return false
        val lower = hay.lowercase()
        if (CJK_KEYS.any { lower.contains(it) }) return true
        // 英文：按非字母数字切片，整词比对（含 `login` 这类连字符写法先被切成两段）
        val tokens = lower.split(Regex("[^a-z0-9]+"))
        return tokens.any { it in ASCII_KEYS }
    }

    /**
     * 命中时给用户看的那半句理由（进卡片的 detail，不进日志原文——日志里 URL 要脱敏）。
     */
    fun reasonOf(url: String?, title: String?): String {
        val lower = ((url ?: "") + " " + (title ?: "")).lowercase()
        val cjk = CJK_KEYS.firstOrNull { lower.contains(it) }
        if (cjk != null) return "页面里出现了「$cjk」"
        val tokens = lower.split(Regex("[^a-z0-9]+"))
        val ascii = tokens.firstOrNull { it in ASCII_KEYS }
        return if (ascii != null) "页面地址/标题里有「$ascii」" else "页面看着像敏感页"
    }
}
