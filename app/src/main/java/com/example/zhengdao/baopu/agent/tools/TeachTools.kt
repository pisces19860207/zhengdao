// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百二十八批 C 片 · 示教/回放的工具面：把 196/200/226 已建好的「教一遍 → 存技能 →
// 回放」执行链接进抱朴的工具注册表（派工单 C 段）。
//
// 一句话：**工具层只管把话说清、把闸拦好，页面动作全部复用现成内核**——
// 录制/草稿/保存/回放的判据一字不改（TeachRecorder / TeachLearner / TeachReplayStore /
// TeachExecutor），本文件不出现第二套定位器或第二套安全闸。
//
// 五件工具（派工单 C 段点名三件＋信任管理两件）：
//   · teach_record_start / teach_record_stop —— 开始与结束示教（信任闸＋CONFIRM 知情卡）；
//   · teach_skill_save —— 草稿确认入库（走 184 写口＋凭证扫描，录到密码就拒存）；
//   · browser_replay_skill —— 回放（授权域闸＋信任闸＋指纹＋拒点提交）；
//   · teach_site_trust —— 信任名单的 add/remove/list（默认空＝全拒；加/撤留痕决策中心）。
//
// 端口（[TeachGateway]）与 [BrowserGateway] 同一条纪律：工具层不抓 WebView 实例，
// 真身在 `BaopuBrowserWiring` 运行时注入，JVM 单测用假网关就能验完全部语义。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.TeachRunReport
import com.example.zhengdao.baopu.TeachSiteTrust
import org.json.JSONObject

/** 示教/回放五件工具的端口（真身见 `BaopuBrowserWiring`；单测传假实现）。 */
internal interface TeachGateway {
    /** 玄览当前页 URL；没有打开的页 ⇒ null（信任闸按「没页面」拒）。 */
    fun currentUrl(): String?

    /** 信任名单现读（默认空 ⇒ 全拒）。 */
    fun trustedHosts(): List<String>

    /** 加/撤信任；null＝成功，非 null＝一句人话错误（名单文件没写成/名单里没有它）。 */
    fun trustAdd(raw: String): String?
    fun trustRemove(raw: String): String?

    /** 开始录制；失败抛异常（由执行体的 runCatching 翻成 Failed）。 */
    fun startRecording(): String

    /** 结束录制 → 草稿（不落盘）；回执按 TeachStopRules 的人话给。 */
    fun stopRecording(): String

    /** 草稿确认入库（name 空串＝用自动起的名字）；回执一句话。 */
    fun saveDraft(name: String): String

    /** 技能的授权域（回放前置闸用）；没有这个技能 ⇒ null。 */
    fun skillAllowedDomains(name: String): List<String>?

    /** 回放（同步阻塞；name 带 sentence 时按一句话换算参数）。 */
    fun replay(name: String, sentence: String?): String
}

/** 端口的运行时持有者（与 [BaopuBrowser] 同规：每次调用现取，不抓引用）。 */
internal object BaopuTeach {
    @Volatile
    var gateway: TeachGateway? = null

    fun require(): TeachGateway? = gateway
}

/** 示教/回放五件工具（表在 [BaopuToolCatalog]，开关在设置页）。 */
internal object TeachTools {

    const val ARG_NAME = "name"
    const val ARG_SENTENCE = "sentence"
    const val ARG_ACTION = "action"
    const val ARG_HOST = "host"

    /** 拿不到网关时的统一话术（与浏览器件同一把声音）。 */
    val NO_TEACH = "玄览还没打开：先在底部切到「玄览」并打开一个页面，再教我或让我回放。"

    // ── ① 开始示教 ──────────────────────────────────────────────────────

    fun recordStartSpec(): ToolSpec = ToolSpec(
        name = "teach_record_start",
        description = "开始录制示教：我在玄览当前页记录你点过/填过的操作，做成可回放的技能草稿。" +
            "只记录、不代做；只允许在信任过的站点上开始（新站点先 teach_site_trust add）。" +
            "录完用 teach_record_stop 结束。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(),
            "required" to jsonArrayOf(),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.BROWSER_MS,
    ) { args ->
        val gw = BaopuTeach.require() ?: return@ToolSpec ToolOutcome.Failed(NO_TEACH)
        val url = gw.currentUrl()
            ?: return@ToolSpec ToolOutcome.Failed("玄览还没有打开的页面（先打开目标站点再开始示教）")
        TeachSiteTrust.gateError(url, gw.trustedHosts(), "开始示教")
            ?.let { return@ToolSpec ToolOutcome.Failed(it) }
        runCatching { ToolOutcome.Ok(gw.startRecording()) }
            .getOrElse { ToolOutcome.Failed("开始示教失败：${it.message ?: it.javaClass.simpleName}") }
    }

    // ── ② 结束示教 ──────────────────────────────────────────────────────

    fun recordStopSpec(): ToolSpec = ToolSpec(
        name = "teach_record_stop",
        description = "结束正在进行的示教录制，生成技能草稿（还没有保存，不会写盘）。" +
            "之后用 teach_skill_save 确认保存；不存就放着，我不会自己写。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(),
            "required" to jsonArrayOf(),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        timeoutMs = ToolTimeout.BROWSER_MS,
    ) { args ->
        val gw = BaopuTeach.require() ?: return@ToolSpec ToolOutcome.Failed(NO_TEACH)
        runCatching { ToolOutcome.Ok(gw.stopRecording()) }
            .getOrElse { ToolOutcome.Failed("结束示教失败：${it.message ?: it.javaClass.simpleName}") }
    }

    // ── ③ 草稿入库 ──────────────────────────────────────────────────────

    fun skillSaveSpec(): ToolSpec = ToolSpec(
        name = "teach_skill_save",
        description = "把示教草稿保存成可回放的技能（写进技能库）。保存前会过凭证扫描：" +
            "录到密码/验证码这类字段就拒存。name 省略＝用示教时自动起的名字。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_NAME to jsonObjectOf(
                    "type" to "string",
                    "description" to "技能名；省略即用自动起的名字（如 m.example.com-流程）",
                ),
            ),
            "required" to jsonArrayOf(),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.BROWSER_MS,
    ) { args ->
        val gw = BaopuTeach.require() ?: return@ToolSpec ToolOutcome.Failed(NO_TEACH)
        runCatching { ToolOutcome.Ok(gw.saveDraft(args.optString(ARG_NAME).trim())) }
            .getOrElse { ToolOutcome.Failed("保存失败：${it.message ?: it.javaClass.simpleName}") }
    }

    // ── ④ 回放 ──────────────────────────────────────────────────────────

    fun replaySpec(): ToolSpec = ToolSpec(
        name = "browser_replay_skill",
        description = "回放一个已保存的示教技能：按示教时的步骤自动定位并执行" +
            "（有指纹校验、授权域闸、信任闸；提交/支付类按钮一律拒点；失败自动滚入视口重试一次，" +
            "不成即停）。sentence 给一句话时按它换算技能参数（缺参数会反问）。" +
            "只在信任过的站点上跑。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_NAME to jsonObjectOf(
                    "type" to "string",
                    "description" to "要回放的技能名（teach_skill_save 保存时的那个名字）",
                ),
                ARG_SENTENCE to jsonObjectOf(
                    "type" to "string",
                    "description" to "一句话参数（如「查昨天发的那单」）；技能没参数就不用给",
                ),
            ),
            "required" to jsonArrayOf(ARG_NAME),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.REPLAY_MS,
    ) { args ->
        val gw = BaopuTeach.require() ?: return@ToolSpec ToolOutcome.Failed(NO_TEACH)
        val name = args.optString(ARG_NAME).trim()
        if (name.isEmpty()) return@ToolSpec ToolOutcome.Failed("name 不能为空：要回放哪个技能")
        val url = gw.currentUrl()
            ?: return@ToolSpec ToolOutcome.Failed("玄览还没有打开的页面（先打开技能的起点页再回放）")
        val allowed = gw.skillAllowedDomains(name)
            ?: return@ToolSpec ToolOutcome.Failed("没有这个回放技能：$name（先在玄览里教一遍）")
        TeachSiteTrust.domainsError(allowed, gw.trustedHosts())
            ?.let { return@ToolSpec ToolOutcome.Failed(it) }
        TeachSiteTrust.gateError(url, gw.trustedHosts(), "回放")
            ?.let { return@ToolSpec ToolOutcome.Failed(it) }
        val sentence = args.optString(ARG_SENTENCE).trim()
        runCatching { ToolOutcome.Ok(gw.replay(name, sentence.ifEmpty { null })) }
            .getOrElse { ToolOutcome.Failed("回放失败：${it.message ?: it.javaClass.simpleName}") }
    }

    // ── ⑤ 信任名单 ──────────────────────────────────────────────────────

    fun siteTrustSpec(): ToolSpec = ToolSpec(
        name = "teach_site_trust",
        description = "查看/添加/撤销「回放信任站点」名单：action=list 看名单；" +
            "action=add 加白一个域名（示教与回放才能在该域自动执行；默认名单是空的）；" +
            "action=remove 撤销信任。加与撤都写决策记录，可在决策中心回滚。" +
            "localhost、IP、内网地址不收。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_ACTION to jsonObjectOf(
                    "type" to "string",
                    "enum" to jsonArrayOf("list", "add", "remove"),
                    "description" to "list=看名单；add=信任；remove=撤销",
                ),
                ARG_HOST to jsonObjectOf(
                    "type" to "string",
                    "description" to "域名（如 m.example.com）；add/remove 必填，list 不用",
                ),
            ),
            "required" to jsonArrayOf(ARG_ACTION),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args ->
        val gw = BaopuTeach.require() ?: return@ToolSpec ToolOutcome.Failed(NO_TEACH)
        when (val action = args.optString(ARG_ACTION).trim()) {
            "list" -> ToolOutcome.Ok(listReceipt(gw.trustedHosts()))
            "add" -> {
                val host = TeachSiteTrust.normalize(args.optString(ARG_HOST))
                    ?: return@ToolSpec ToolOutcome.Failed(
                        "「${args.optString(ARG_HOST)}」不像可以信任的域名（要形如 example.com；" +
                            "localhost／IP／内网地址不收）",
                    )
                val err = gw.trustAdd(host)
                if (err != null) return@ToolSpec ToolOutcome.Failed(err)
                ToolOutcome.Ok(
                    "已信任 $host：示教与回放可在该域自动执行" +
                        "（撤销：teach_site_trust remove $host；决策记录里留了痕）",
                )
            }
            "remove" -> {
                val host = args.optString(ARG_HOST).trim().lowercase()
                if (host.isEmpty()) {
                    return@ToolSpec ToolOutcome.Failed("remove 要带 host：撤掉哪个域名")
                }
                val err = gw.trustRemove(host)
                if (err != null) return@ToolSpec ToolOutcome.Failed(err)
                ToolOutcome.Ok("已撤销对 $host 的信任：示教与回放不再在该域自动执行（决策记录里留了痕）")
            }
            else -> ToolOutcome.Failed("action 必须是 add／remove／list 之一（拿到的是「$action」）")
        }
    }

    /** 信任名单的回执（空名单要说清后果，不假装「已是最新」）。 */
    fun listReceipt(trusted: List<String>): String =
        if (trusted.isEmpty()) {
            "信任名单是空的：示教与回放默认全拒（teach_site_trust add <域名> 显式加白）"
        } else {
            "信任名单（${trusted.size}）：" + trusted.joinToString("、") +
                "（撤销：teach_site_trust remove <域名>）"
        }

    /** 回放报告的人话（refusals/retried/取证都回灌，不许只说「好了」。） */
    fun reportLine(r: TeachRunReport): String = buildString {
        append(if (r.ok) "回放完成：" else "回放停下：")
        append(r.stepsDone).append(" 步")
        r.failedStep?.let { append("，停在第 ").append(it).append(" 步") }
        r.stopReason?.let { append("，原因：").append(it) }
        if (r.refusals.isNotEmpty()) {
            append("；被安全闸拦下：").append(r.refusals.joinToString("；"))
        }
        if (r.retried.isNotEmpty()) {
            append("；第 ").append(r.retried.joinToString("、")).append(" 步滚入视口重试后成功")
        }
        r.evidenceDir?.let { append("；取证：").append(it) }
    }
}
