// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十六批 §1 学习器：示教事件的**纯判据层**——原始事件进，技能草稿出。
//
// 录制侧（玄览注入 JS）只负责「看见什么记什么」，判据全在这：
// 十级定位链怎么建、expect 怎么自动补、指纹怎么取、密码/拒点动作怎么拒记。
// 产出是**内存草稿**（[TeachSkillDraft]），确认（走 TeachReplayStore → 184 写口）
// 之前一个字节不落盘。
package com.example.zhengdao.baopu

/** 一条示教原始事件（录制侧采到什么就放什么，判据在这层做）。 */
data class TeachRawEvent(
    val action: TeachAction,
    val url: String, // 动作发生时的页面
    val element: TeachElementInfo,
    val value: String?, // type/select 的值（密码字段永远到不了这里——录制层已拒）
    val beforeUrl: String,
    val afterUrl: String,
    val beforeRows: Int,
    val afterRows: Int,
)

/** 目标元素录制时刻的全部语义面（建定位链的原料）。 */
data class TeachElementInfo(
    val tag: String,
    val role: String? = null,
    val name: String? = null, // 可访问名（aria-labelledby → aria-label → 关联 label → 文本）
    val text: String? = null, // 可见文本（截 40）
    val ariaLabel: String? = null,
    val testid: String? = null,
    val elemId: String? = null,
    val inputName: String? = null,
    val placeholder: String? = null,
    val inputType: String? = null,
    val formLabel: String? = null, // 所在表单标签/列名（T8 上下文锚）
    val classes: List<String> = emptyList(),
    val xpath: String? = null,
) {
    fun snapshot(): TeachElementSnapshot = TeachElementSnapshot(
        tag = tag.uppercase(),
        role = role?.takeIf { it.isNotBlank() },
        name = name?.takeIf { it.isNotBlank() },
        text = text?.takeIf { it.isNotBlank() },
        inputType = inputType?.takeIf { it.isNotBlank() },
    )
}

/** 学习产物：技能本体＋拒记警示＋人可读摘要（确认卡三件套）。 */
data class TeachSkillDraft(
    val skill: TeachSkill,
    val warnings: List<String>,
    val summary: String,
)

internal object TeachLearner {

    /** 指纹锚上限（起点页关键表头＋关键按钮，防止整页按钮都进指纹）。 */
    const val FINGERPRINT_MAX = 12

    /** 单值文本上限（与执行端扫描口径一致）。 */
    const val TEXT_MAX = 40

    /**
     * 事件流 → 技能草稿。
     *
     * 判据（派工单 §1）：能出步骤的都带定位链＋expect；每步**至少 2 档**定位（做不到的
     * 步骤保留但在 warnings 里点名，回放时它多半会停下问人）；拒点/拒填的动作**不记**
     * （负例 3 的录制半边）；连续对同一元素多次输入合并成一步（记最后一次值）。
     */
    fun buildDraft(
        events: List<TeachRawEvent>,
        name: String,
        purpose: String,
        startFacts: TeachPageFacts,
        nowMs: Long,
    ): TeachSkillDraft {
        val warnings = mutableListOf<String>()
        val steps = mutableListOf<TeachStep>()
        val clickTags = linkedSetOf<String>()
        val hosts = linkedSetOf<String>()

        events.forEachIndexed { evIdx, ev ->
            val el = ev.element
            val snap = el.snapshot()
            when (ev.action) {
                TeachAction.CLICK -> {
                    val refuse = TeachSafety.refuseClickReason(
                        TeachSafety.ClickSurface(el.text, el.name, el.ariaLabel, el.testid),
                    )
                    if (refuse != null) {
                        warnings += "拒记第 ${evIdx + 1} 个动作：$refuse"
                        return@forEachIndexed
                    }
                    clickTags += snap.tag
                }
                TeachAction.TYPE, TeachAction.SELECT -> {
                    val refuse = TeachSafety.refuseTypeReason(
                        el.inputType, el.inputName, el.elemId, el.placeholder, null,
                    )
                    if (refuse != null) {
                        warnings += "拒记第 ${evIdx + 1} 个动作：$refuse（值不会进技能）"
                        return@forEachIndexed
                    }
                }
                else -> Unit
            }
            hosts += listOfNotNull(TeachSafety.hostOf(ev.url), TeachSafety.hostOf(ev.afterUrl))

            // 连续对同一元素输入 ⇒ 合并（记最后一次值）
            val prev = steps.lastOrNull()
            if (prev != null && (ev.action == TeachAction.TYPE || ev.action == TeachAction.SELECT) &&
                prev.action == ev.action && prev.snapshot == snap
            ) {
                steps[steps.size - 1] = prev.copy(value = ev.value)
                return@forEachIndexed
            }

            val locators = buildLocators(el)
            if (locators.size < 2) {
                warnings += "第 ${steps.size + 1} 步定位链只有 ${locators.size} 档（${el.tag} " +
                    "${el.name ?: el.text ?: "?"}）——回放时这一步容易停下问人"
            }
            steps += TeachStep(
                index = steps.size + 1,
                action = ev.action,
                url = ev.url,
                locators = locators,
                value = ev.value,
                snapshot = snap,
                expect = synthesizeExpect(ev),
            )
        }

        val fingerprint = TeachFingerprint(
            (startFacts.headers.map { TeachFingerprintAnchor("header", it) } +
                startFacts.buttons.map { TeachFingerprintAnchor("button", it) })
                .distinctBy { it.name }
                .take(FINGERPRINT_MAX),
        )
        val skill = TeachSkill(
            name = name,
            purpose = purpose,
            createdAtMs = nowMs,
            startUrl = startFacts.url,
            allowedDomains = hosts.distinct(),
            clickTagWhitelist = clickTags.toList(),
            fingerprint = fingerprint,
            steps = steps,
        )
        return TeachSkillDraft(skill = skill, warnings = warnings, summary = summarize(skill, warnings))
    }

    // ── 十级定位链：从元素语义面建（§0 优先级序）────────────────────────────

    /** id 是否像框架自动生成的（`ember-123`／`radix-:r2:` 这类数字尾巴不稳定）。 */
    private fun stableId(id: String?): Boolean {
        if (id.isNullOrBlank() || id.length > 40) return false
        if (id.endsWith(":r") || id.contains(":r")) return false // React radix 端口
        return !Regex("[-_]\\d+$").containsMatchIn(id) // 数字尾巴（item-12）当不稳定
    }

    /** CSS 类是否稳定：无数字、长度 2..30（构建哈希类 css-1x2y3 一律丢）。 */
    fun stableCssClass(cls: String): Boolean =
        cls.length in 2..30 && cls.none { it.isDigit() } &&
            cls.all { it.isLetter() || it == '-' || it == '_' }

    fun buildLocators(el: TeachElementInfo): List<TeachLocator> {
        val out = mutableListOf<TeachLocator>()
        val role = el.role?.takeIf { it.isNotBlank() }
        val name = el.name?.takeIf { it.isNotBlank() }
        if (role != null && name != null) out += TeachLocator(TeachLocatorKind.ROLE_NAME, "$role|$name")
        el.testid?.takeIf { it.isNotBlank() }?.let { out += TeachLocator(TeachLocatorKind.TESTID, it.take(80)) }
        el.ariaLabel?.takeIf { it.isNotBlank() }?.let { out += TeachLocator(TeachLocatorKind.ARIA_LABEL, it.take(80)) }
        if (stableId(el.elemId)) out += TeachLocator(TeachLocatorKind.ELEM_ID, el.elemId!!.take(80))
        el.inputName?.takeIf { it.isNotBlank() }?.let { out += TeachLocator(TeachLocatorKind.INPUT_NAME, it.take(80)) }
        el.placeholder?.takeIf { it.isNotBlank() }?.let { out += TeachLocator(TeachLocatorKind.PLACEHOLDER, it.take(TEXT_MAX)) }
        el.text?.takeIf { it.isNotBlank() }?.let { out += TeachLocator(TeachLocatorKind.TEXT, it.take(TEXT_MAX)) }
        val secondary = name ?: el.text ?: el.inputName
        val formLabel = el.formLabel?.takeIf { it.isNotBlank() }
        if (formLabel != null && secondary != null) {
            out += TeachLocator(TeachLocatorKind.FORM_CONTEXT, "${formLabel.take(TEXT_MAX)}|${secondary.take(TEXT_MAX)}")
        }
        val stableClasses = el.classes.filter { stableCssClass(it) }
        if (stableClasses.isNotEmpty()) {
            out += TeachLocator(TeachLocatorKind.CSS_CLASS, stableClasses.take(3).joinToString("."))
        }
        el.xpath?.takeIf { it.isNotBlank() }?.let { out += TeachLocator(TeachLocatorKind.XPATH, it.take(200)) }
        return out.sortedBy { it.tier }
    }

    // ── expect 自动补一条（§1：URL 变化／关键文本出现／行数变化）────────────

    private fun synthesizeExpect(ev: TeachRawEvent): List<TeachExpect> {
        val urlChanged = ev.afterUrl.substringBefore('#') != ev.beforeUrl.substringBefore('#')
        return when (ev.action) {
            TeachAction.CLICK, TeachAction.SCROLL -> when {
                urlChanged -> listOf(TeachExpect(TeachExpectKind.URL_CHANGED, null))
                ev.afterRows != ev.beforeRows -> listOf(TeachExpect(TeachExpectKind.ROW_COUNT_CHANGE, null))
                else -> emptyList()
            }
            // 输入类的「关键文本出现」＝输入框里出现了刚打的值（执行端 TextPresent 连 input 值一起扫）
            TeachAction.TYPE, TeachAction.SELECT ->
                ev.value?.takeIf { it.isNotBlank() }?.let { listOf(TeachExpect(TeachExpectKind.TEXT_APPEARS, it)) }
                    ?: emptyList()
            TeachAction.WAIT -> emptyList()
        }
    }

    // ── 人可读摘要（确认卡与 SKILL.md 共用同一份文字）──────────────────────

    fun summarize(skill: TeachSkill, warnings: List<String>): String = buildString {
        appendLine("回放技能：${skill.name}（${skill.steps.size} 步，授权域 ${skill.allowedDomains.joinToString("、")}）")
        skill.steps.forEach { s ->
            appendLine("${s.index}. ${stepLine(s)}")
        }
        appendLine("回放时定位失败只重抽那一步并标「定位漂移」，技能文件不自动改（改要人确认）")
        if (warnings.isNotEmpty()) {
            appendLine("示教警示：")
            warnings.forEach { appendLine("· $it") }
        }
    }

    fun stepLine(s: TeachStep): String {
        val target = s.snapshot.name ?: s.snapshot.text ?: s.snapshot.tag
        val verb = when (s.action) {
            TeachAction.CLICK -> "点"
            TeachAction.TYPE -> "输入「${s.value ?: ""}」到"
            TeachAction.SELECT -> "选「${s.value ?: ""}」"
            TeachAction.SCROLL -> "滚动"
            TeachAction.WAIT -> "等待"
        }
        val expect = s.expect.joinToString("＋") {
            when (it.kind) {
                TeachExpectKind.URL_CHANGED -> "URL 变化"
                TeachExpectKind.TEXT_APPEARS -> "出现「${it.value ?: ""}」"
                TeachExpectKind.ROW_COUNT_CHANGE -> "行数变化"
                TeachExpectKind.ELEMENT_GONE -> "「${it.value ?: ""}」消失"
            }
        }
        return "$verb ${s.snapshot.tag}「$target」${if (expect.isEmpty()) "" else " → $expect"}"
    }
}

/** 起点页快照（指纹与 startUrl 的来源，录制开始时采一次）。 */
data class TeachPageFacts(
    val url: String,
    val headers: List<String>,
    val buttons: List<String>,
)
