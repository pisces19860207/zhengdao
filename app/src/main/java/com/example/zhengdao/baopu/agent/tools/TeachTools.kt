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
import com.example.zhengdao.baopu.correct.CorrectEdit
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

    /**
     * 622 A②：把技能导出成单文件动作脚本（产物/玄览脚本/<名>.玄览脚本.json）。
     * 凭证闸＋不可逆闸在导出侧就过（带病脚本不出门）；回执一句话。
     */
    fun exportSkill(name: String): String

    /** 622 A②：把导出脚本装回技能库（schema/凭证/不可逆/入库四道闸，同人重导＝替换）。 */
    fun importSkill(name: String): String

    /**
     * 回放（同步阻塞；name 带 sentence 时按一句话换算参数）。
     * 第三百一十四批：[driftChoice] 是人对漂移问题的回答（ask/proceed/new_version/cancel；
     * null/「ask」＝先问）——接线层 [com.example.zhengdao.baopu.DriftWire] 按它处置。
     */
    fun replay(name: String, sentence: String?, driftChoice: String? = null): String

    /**
     * 624C：把一句纠正落成**单步编辑**（人批卡片即确认闸；备份/留痕/回滚在 TeachScriptHistory）。
     * 回执一句话（成功含新旧版本与 resume 提示；拒绝含原因）。
     */
    fun correctApply(name: String, edit: CorrectEdit): String

    /**
     * 624C：**从第 N 步重跑**（判据③——前 N-1 步不重执行；回执一句话）。
     */
    fun correctResume(name: String, fromStep: Int): String
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

    /** 第三百一十四批：人对漂移问题的回答（漂移闸三选一；没问过就不要给）。 */
    const val ARG_DRIFT = "drift"

    /** drift 参数的合法值（与 [com.example.zhengdao.baopu.DriftChoice] 的 wire 一致）。 */
    val DRIFT_WIRES = setOf("proceed", "new_version", "cancel", "mute")

    /** 624C：纠正编辑的参数名。 */
    const val ARG_STEP = "step"
    const val ARG_OP = "op"
    const val ARG_VALUE = "value"
    const val ARG_LOCATOR_KIND = "locatorKind"
    const val ARG_EXPECT = "expect"
    const val ARG_TO_INDEX = "toIndex"
    const val ARG_REASON = "reason"

    /** 624C：纠正编辑的闭集（与 [com.example.zhengdao.baopu.correct.CorrectEdit.OPS] 一致）。 */
    val CORRECT_OPS = setOf("setValue", "setLocator", "setExpect", "deleteStep", "moveStep")

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
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args ->
        val gw = BaopuTeach.require() ?: return@ToolSpec ToolOutcome.Failed(NO_TEACH)
        runCatching { ToolOutcome.Ok(gw.saveDraft(args.optString(ARG_NAME).trim())) }
            .getOrElse { ToolOutcome.Failed("保存失败：${it.message ?: it.javaClass.simpleName}") }
    }

    // ── ③b/③c 622 A②：动作脚本的导出与导入（可版本化、可带走、可装回） ────────

    fun skillExportSpec(): ToolSpec = ToolSpec(
        name = "teach_skill_export",
        description = "把一个已保存的示教技能导出成单文件动作脚本" +
            "（产物/玄览脚本/<名>.玄览脚本.json；schema zl-teach/2：目标站/版本/步骤/校验点/最终快照）。" +
            "导出前过凭证扫描与不可逆动作扫描——带凭据或含提交/支付/删除类点击的脚本不出门。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_NAME to jsonObjectOf(
                    "type" to "string",
                    "description" to "要导出的技能名（teach_skill_save 保存时的那个名字）",
                ),
            ),
            "required" to jsonArrayOf(ARG_NAME),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args ->
        val gw = BaopuTeach.require() ?: return@ToolSpec ToolOutcome.Failed(NO_TEACH)
        val name = args.optString(ARG_NAME).trim()
        if (name.isEmpty()) return@ToolSpec ToolOutcome.Failed("name 不能为空：导出哪个技能")
        runCatching { ToolOutcome.Ok(gw.exportSkill(name)) }
            .getOrElse { ToolOutcome.Failed("导出失败：${it.message ?: it.javaClass.simpleName}") }
    }

    fun skillImportSpec(): ToolSpec = ToolSpec(
        name = "teach_skill_import",
        description = "把一个导出过的动作脚本装回技能库（从 产物/玄览脚本/ 读，同人重导＝替换）。" +
            "四道闸：schema 严格解析（只认 zl-teach/1、/2）、凭证扫描、不可逆动作扫描" +
            "（提交/支付/删除类点击整份拒绝）、入库验收闸——任何一道不过就一个字节不写。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_NAME to jsonObjectOf(
                    "type" to "string",
                    "description" to "要导入的脚本名（导出时的技能名，不带扩展名）",
                ),
            ),
            "required" to jsonArrayOf(ARG_NAME),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args ->
        val gw = BaopuTeach.require() ?: return@ToolSpec ToolOutcome.Failed(NO_TEACH)
        val name = args.optString(ARG_NAME).trim()
        if (name.isEmpty()) return@ToolSpec ToolOutcome.Failed("name 不能为空：导入哪个脚本")
        runCatching { ToolOutcome.Ok(gw.importSkill(name)) }
            .getOrElse { ToolOutcome.Failed("导入失败：${it.message ?: it.javaClass.simpleName}") }
    }

    // ── ④ 回放 ──────────────────────────────────────────────────────────

    fun replaySpec(): ToolSpec = ToolSpec(
        name = "browser_replay_skill",
        description = "回放一个已保存的示教技能：按示教时的步骤自动定位并执行" +
            "（有指纹校验、授权域闸、信任闸、契约漂移闸；提交/支付类按钮一律拒点；" +
            "失败自动滚入视口重试一次，不成即停）。sentence 给一句话时按它换算技能参数（缺参数会反问）。" +
            "页面与契约对不上时会停下问人（三选一），用户答了就用 drift 带回来再跑一次。" +
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
                ARG_DRIFT to jsonObjectOf(
                    "type" to "string",
                    "enum" to jsonArrayOf("proceed", "new_version", "cancel", "mute"),
                    "description" to "漂移闸的回答：上次回放因「页面与记录对不上」停下问你时，用户答了什么。" +
                        "proceed=照跑（先记一笔）；new_version=这是新版（重新校准后跑）；cancel=算了不跑；" +
                        "mute=以后别问（不再提「重新学一遍」，只报用不了）。没问过就不要给这个参数。",
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
        val drift = args.optString(ARG_DRIFT).trim().ifEmpty { null }
        if (drift != null && drift !in DRIFT_WIRES) {
            return@ToolSpec ToolOutcome.Failed(
                "drift 只认 ${DRIFT_WIRES.joinToString("／")}（拿到的是「$drift」）；没被问到就不要给这个参数",
            )
        }
        runCatching { ToolOutcome.Ok(gw.replay(name, sentence.ifEmpty { null }, drift)) }
            .getOrElse { ToolOutcome.Failed("回放失败：${it.message ?: it.javaClass.simpleName}") }
    }

    // ── ④' 624C：纠正（边看边纠）两件 ──────────────────────────────────────

    fun correctApplySpec(): ToolSpec = ToolSpec(
        name = "teach_correct_apply",
        description = "回放被暂停后，把用户那句纠正落成**一个单步编辑**（改输入值／换定位／加校验点／删步／挪顺序）。" +
            "编辑只会改这一步，其余步不动；工具弹人批确认卡＝「改脚本必须先确认」，批后旧版自动备份到 技能/<名>/修订/、" +
            "留痕 script_corrected，可一键回滚。改完用 teach_correct_resume 从被纠正的那一步重跑——**不重录**。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_NAME to jsonObjectOf("type" to "string", "description" to "要纠正的技能名"),
                ARG_STEP to jsonObjectOf("type" to "number", "description" to "被纠正的步号（暂停报告里给的那个 N）"),
                ARG_OP to jsonObjectOf(
                    "type" to "string",
                    "enum" to jsonArrayOf("setValue", "setLocator", "setExpect", "deleteStep", "moveStep"),
                    "description" to "编辑种类：setValue=改这一步的输入值；setLocator=换这一步的一档定位；" +
                        "setExpect=给这一步加校验点；deleteStep=删掉这一步；moveStep=把这一步挪到别的位置",
                ),
                ARG_VALUE to jsonObjectOf("type" to "string", "description" to "setValue/setLocator 的新值；其他 op 不用给"),
                ARG_LOCATOR_KIND to jsonObjectOf(
                    "type" to "string",
                    "enum" to jsonArrayOf("roleName", "testid", "ariaLabel", "elemId", "inputName", "placeholder", "xpath"),
                    "description" to "setLocator 的定位档；其他 op 不用给",
                ),
                ARG_EXPECT to jsonObjectOf(
                    "type" to "string",
                    "enum" to jsonArrayOf("urlChanged", "rowCountChange", "textAppears", "elementGone"),
                    "description" to "setExpect 的校验点；其他 op 不用给",
                ),
                ARG_TO_INDEX to jsonObjectOf("type" to "number", "description" to "moveStep 的目标位（1 起）；其他 op 不用给"),
                ARG_REASON to jsonObjectOf("type" to "string", "description" to "用户那句纠正的原话（进账留痕，一个字不改）"),
            ),
            "required" to jsonArrayOf(ARG_NAME, ARG_STEP, ARG_OP, ARG_REASON),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args ->
        val gw = BaopuTeach.require() ?: return@ToolSpec ToolOutcome.Failed(NO_TEACH)
        val name = args.optString(ARG_NAME).trim()
        val step = if (args.has(ARG_STEP)) args.optInt(ARG_STEP, -1) else -1
        val op = args.optString(ARG_OP).trim()
        val reason = args.optString(ARG_REASON).trim()
        if (name.isEmpty()) return@ToolSpec ToolOutcome.Failed("name 不能为空：要纠正哪个技能")
        if (step < 1) return@ToolSpec ToolOutcome.Failed("step 要是被纠正的步号（暂停报告里的 N）")
        if (op !in CORRECT_OPS) return@ToolSpec ToolOutcome.Failed("op 只认 ${CORRECT_OPS.joinToString("／")}（拿到「$op」）")
        if (reason.isEmpty()) return@ToolSpec ToolOutcome.Failed("reason 不能为空：用户那句纠正要原话留痕")
        val edit = CorrectEdit(
            name = name, step = step, op = op,
            value = args.optString(ARG_VALUE).trim().ifEmpty { null },
            locatorKind = args.optString(ARG_LOCATOR_KIND).trim().ifEmpty { null },
            expect = args.optString(ARG_EXPECT).trim().ifEmpty { null },
            toIndex = if (args.has(ARG_TO_INDEX)) args.optInt(ARG_TO_INDEX, -1) else null,
            reason = reason,
        )
        runCatching { ToolOutcome.Ok(gw.correctApply(name, edit)) }
            .getOrElse { ToolOutcome.Failed("纠正落盘失败：${it.message ?: it.javaClass.simpleName}") }
    }

    fun correctResumeSpec(): ToolSpec = ToolSpec(
        name = "teach_correct_resume",
        description = "纠正改完后**从第 N 步重跑**：前 N-1 步已成功过，不会重执行（判据③）；" +
            "起点指纹与开场域闸豁免（页面已在中途），每步域闸与安全闸照跑。" +
            "fromStep 给被纠正的那一步的步号。不重录、不重建技能。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_NAME to jsonObjectOf("type" to "string", "description" to "要续跑的技能名"),
                ARG_STEP to jsonObjectOf("type" to "number", "description" to "从第几步开始重跑（被纠正的那一步；1 起）"),
            ),
            "required" to jsonArrayOf(ARG_NAME, ARG_STEP),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.REPLAY_MS,
    ) { args ->
        val gw = BaopuTeach.require() ?: return@ToolSpec ToolOutcome.Failed(NO_TEACH)
        val name = args.optString(ARG_NAME).trim()
        val fromStep = if (args.has(ARG_STEP)) args.optInt(ARG_STEP, -1) else -1
        if (name.isEmpty()) return@ToolSpec ToolOutcome.Failed("name 不能为空：要续跑哪个技能")
        if (fromStep < 1) return@ToolSpec ToolOutcome.Failed("step 要 ≥1（从第几步重跑）")
        runCatching { ToolOutcome.Ok(gw.correctResume(name, fromStep)) }
            .getOrElse { ToolOutcome.Failed("续跑失败：${it.message ?: it.javaClass.simpleName}") }
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

    /** 回放报告的人话（refusals/retried/取证/建议都回灌，不许只说「好了」。） */
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
        // 622 A④：失败给下一步建议（是给人看的路标，不是新计划——回放自己不乱点）
        r.suggestion?.let { append("；建议：").append(it) }
    }
}
