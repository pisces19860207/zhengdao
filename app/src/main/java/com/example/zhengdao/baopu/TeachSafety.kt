// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十六批 §3：安全比功能重要。五条全部做成**代码层面**的判据（不靠提示词、
// 不靠页面 JS 自觉），学习器与执行器共用同一份名单——录制时拒记、回放时拒动、
// 落盘前拒写，三层同源。
package com.example.zhengdao.baopu

/**
 * 示教/回放的安全闸。全部纯函数（JVM 直测，负例 3/4/6/7 的靶子都在这）。
 *
 * 三层共用：
 * 1. **录制层**（TeachLearner）：命中拒点的动作不记进步骤，草稿里留警示；
 * 2. **执行层**（TeachExecutor）：解析出的候选命中拒点 ⇒ 连已确认的技能也拒动（负例 3）；
 * 3. **落盘层**（TeachReplayStore）：技能 JSON 里扫出明文凭证 ⇒ 一个字节不写（负例 7）。
 */
internal object TeachSafety {

    // ── ① 动作白名单：拒点名单（派工单 §3.1 原文）────────────────────────────
    //
    // 文本命中「提交|确认|删除|作废|发货|派车|结算|支付|充值」⇒ 一律拒绝点击并说明原因。
    // 这正是「不许它乱动手」的落点：宁可误拒（页面同时有「确认收货」这类合法按钮时技能
    // 会停下来让人看），不可漏放。判定输入是候选元素的**全部语义面**：可见文本、可访问名、
    // aria-label、data-testid 值——换一个面写危险词照样拦。
    //
    // 622 起：词表与判定**上收**到端口侧 [com.example.zhengdao.bridge.PortIrreversible]
    // （执行端口的三档闸同一份词表），这里只留委托——名单只有一份，谁都没资格抄第二份。

    /** 拒点词表（派工单定稿九词；唯一实现在 [com.example.zhengdao.bridge.PortIrreversible]）。 */
    val CLICK_REFUSAL_TEXT: Regex
        get() = com.example.zhengdao.bridge.PortIrreversible.CLICK_REFUSAL_TEXT

    /** 英文镜像词表（唯一实现在端口侧；622 上收后这里是转发）。 */
    val CLICK_REFUSAL_TEXT_EN: Regex
        get() = com.example.zhengdao.bridge.PortIrreversible.CLICK_REFUSAL_TEXT_EN

    /** 候选元素的语义面（判定拒点用；四个面全空 = 无名元素，交给定位层而非这里）。 */
    data class ClickSurface(
        val text: String?,
        val accessibleName: String?,
        val ariaLabel: String?,
        val testid: String?,
    )

    /**
     * 拒点判定：命中拒点词 ⇒ 返回拒点原因（执行器原样进报告与审计）；null = 放行。
     * 622 起委托端口侧唯一实现（文案改口为「回抱朴走闸」——判据不变，说话的语境统一）。
     */
    fun refuseClickReason(surface: ClickSurface): String? =
        com.example.zhengdao.bridge.PortIrreversible.reasonIn(
            surface.text,
            surface.accessibleName,
            surface.ariaLabel,
            surface.testid,
        )

    // ── ② 输入白名单：密码/验证码拒填（§3.2）─────────────────────────────────

    /** 密码/凭证字段判别：inputType 或 name/id/placeholder/autocomplete 命中即拒。 */
    private val CREDENTIAL_FIELD: Regex =
        Regex("(?i)password|passwd|pwd|secret|token|credential|captcha|otp|one-time|" +
            "密码|口令|验证码|校验码|授权码")

    /**
     * 拒填判定：目标输入框疑似密码/验证码字段 ⇒ 返回原因；null = 放行。
     * （type=password 是硬判据；属性名疑似是软判据——宁可误拒。）
     */
    fun refuseTypeReason(
        inputType: String?,
        inputName: String?,
        inputId: String?,
        placeholder: String?,
        autocomplete: String?,
    ): String? {
        if (inputType?.lowercase() == "password") {
            return "拒填：目标是密码框（type=password）——回放不填任何密码"
        }
        val faces = listOfNotNull(inputName, inputId, placeholder, autocomplete)
            .filter { it.isNotBlank() }
        for (face in faces) {
            val hit = CREDENTIAL_FIELD.find(face) ?: continue
            return "拒填：字段疑似凭证输入（「${hit.value}」命中于 ${face.take(24)}）——密码/验证码一律不填"
        }
        return null
    }

    // ── ③ 域名白名单（§3.3）───────────────────────────────────────────────────
    //
    // 622 起：host 解析与授权域判定**上收**到 [com.example.zhengdao.bridge.BridgePolicy]
    // （端口的域闸同一份判据），这里只留委托——名单判据只有一份。

    /**
     * 域判据：url 的 host 必须落在授权域内（点前缀匹配防 `a.com.evil.io` 蹭过；
     * 空授权域全拒）。622 起委托 BridgePolicy.hostAllowedIn。
     */
    fun domainAllowed(url: String, allowedDomains: List<String>): Boolean =
        com.example.zhengdao.bridge.BridgePolicy.hostAllowedIn(url, allowedDomains)

    /** 取 url 的 host（622 起委托 BridgePolicy.hostOf——唯一实现）。 */
    fun hostOf(url: String): String? = com.example.zhengdao.bridge.BridgePolicy.hostOf(url)

    // ── ④ 凭证扫描：技能里不许有明文密码/凭证（§3.4 + 负例 7）────────────────

    /** 值字段里明显是凭证样子的字面量（只收有明确结构的，不做「字母+数字=口令」这类启发——查货单号就是字母+数字，误拒写盘比漏拒更伤）。 */
    private val CREDENTIAL_LITERAL: List<Regex> = listOf(
        Regex("^eyJ[A-Za-z0-9_\\-.]{10,}$"), // JWT 三段头
        Regex("^AKIA[0-9A-Z]{16}$"), // AWS AccessKeyId
        Regex("^Bearer\\s+[A-Za-z0-9_\\-.]{8,}$", RegexOption.IGNORE_CASE),
        Regex("^[A-Za-z0-9+/]{28,}={0,2}$"), // Base64 块（≥28 字符才是密钥量级）
    )

    /**
     * 值层面的凭证形状判定（CardValidate 批：表单卡提交预检用，名单一个字不新增）。
     *
     * 为什么 [refuseTypeReason] 拦过还要拦值：那条认的是**输入框的面**（type/name/id/
     * placeholder/autocomplete）。一串 JWT 填进一个叫「备注」的框它一个字都看不见——
     * 明文凭证照样上页面。这里补的是**值这一面**，判据仍是 [CREDENTIAL_LITERAL] 那四条
     * 有明确结构的字面量，不做「字母+数字=口令」的启发（查货单号就是字母+数字）。
     *
     * 返回的话只带脱敏后的样子（[mask]），报告与回灌给模型的正文里不许出现完整凭证。
     */
    fun credentialShapeReason(value: String): String? {
        if (value.isBlank()) return null
        CREDENTIAL_LITERAL.firstOrNull { it.containsMatchIn(value) } ?: return null
        return "值疑似明文凭证（${mask(value)}）"
    }

    /** 值字段里明显是凭证键名的（用户手改技能时塞进去的）。 */
    private val CREDENTIAL_KEY: Regex =
        Regex("(?i)^\"?(password|passwd|pwd|secret|token|api_?key|apikey|authorization|cookie|session_?id)\"?$")

    /**
     * 扫一份序列化好的技能 JSON：发现明文凭证 ⇒ 返回发现清单（非空 = 落盘层拒写）。
     *
     * 只看三类东西，不整文正则（避免把 URL/host 误判成密钥）：
     * 1. snapshot.inputType == password 且步骤 value 非空（录制层本不该记，这是兜底）；
     * 2. 键名像凭证且值非空（手改技能塞进去的）；
     * 3. 步骤/顶层的 value 值长得像密钥/JWT/Base64 块/强口令。
     */
    fun credentialFindings(jsonText: String): List<String> {
        val findings = mutableListOf<String>()
        val o = runCatching { org.json.JSONObject(jsonText) }.getOrNull() ?: return emptyList()
        val steps = o.optJSONArray("steps") ?: return emptyList()
        for (i in 0 until steps.length()) {
            val s = steps.optJSONObject(i) ?: continue
            val value = s.optString("value").takeIf { it.isNotEmpty() } ?: continue
            val snap = s.optJSONObject("snapshot")
            if (snap != null && snap.optString("inputType").lowercase() == "password") {
                findings += "第 ${s.optInt("i", i + 1)} 步：密码框带了输入值（${mask(value)}）"
                continue
            }
            CREDENTIAL_LITERAL.firstOrNull { it.containsMatchIn(value) }?.let {
                findings += "第 ${s.optInt("i", i + 1)} 步：value 疑似明文凭证（${mask(value)}）"
            }
        }
        // 顶层与步骤里的未知键值对（用户手改技能塞 password: xxx 这类）
        scanUnknownPairs(o, "", findings)
        for (i in 0 until steps.length()) {
            steps.optJSONObject(i)?.let { scanUnknownPairs(it, "第 ${i + 1} 步 ", findings) }
        }
        return findings
    }

    /** 遍历对象的键值：键名像凭证且字符串值非空 ⇒ 记一条。 */
    private fun scanUnknownPairs(o: org.json.JSONObject, prefix: String, out: MutableList<String>) {
        for (key in o.keys()) {
            val v = o.opt(key) ?: continue
            if (v is String && v.isNotBlank() && CREDENTIAL_KEY.containsMatchIn(key)) {
                out += "${prefix}键「$key」带着明文值（${mask(v)}）"
            }
        }
    }

    /** 脱敏：只留首尾各 2 字，中间换 *（报告与审计里不许出现完整凭证）。 */
    fun mask(secret: String): String = when {
        secret.length <= 4 -> "*".repeat(secret.length)
        else -> secret.take(2) + "*".repeat(secret.length - 4) + secret.takeLast(2)
    }
}
