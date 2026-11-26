// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百一十九批 · 总检：三层齐＋表层由状态推＋十维齐＋口头禅真出现＋崩相必指定
// ＋底层带触发＋禁心理诊断＋归属硬规矩＋台词互换测试。
// 红=判据破；提示=写了但永不出现/永不崩/只列不给触发。纯函数，无 IO。
package com.example.zhengdao.baopu.state.persona

/** 总检查器。所有判定都是字符串/枚举的确定性运算，同输入必得同 [CheckReport]。 */
object PersonaChecks {

    /** 底层条文数下限/上限：3–5 条。 */
    const val CORE_MIN = 3
    const val CORE_MAX = 5

    /** 口头禅上限字数。 */
    const val CATCHPHRASE_MAX = 6

    /** 归属硬规矩的稳定归因措辞与临时归因措辞。 */
    private val STABLE_WORDS = listOf("他就是这种人", "她就是这种人", "本性", "天性", "一贯", "向来")
    private val MOMENT_WORDS = listOf("今天这样", "这会儿", "此刻", "刚刚", "一时")

    /** 一个人完整总检：把八类判据的结果汇总。 */
    fun check(p: Persona): CheckReport {
        val items = ArrayList<CheckItem>()
        items += coreChecks(p.core)
        items += traitChecks(p.trait)
        items += surfaceChecks(p.surface)
        items += voiceChecks(p.voice)
        items += languageChecks(p)
        return CheckReport(items)
    }

    /** ①底层：条数 3–5、每条带触发、rank 连续无并列、需求不重复。 */
    private fun coreChecks(core: CoreLayer): List<CheckItem> {
        val out = ArrayList<CheckItem>()
        val n = core.drives.size
        if (n !in CORE_MIN..CORE_MAX) {
            out += err("CORE_COUNT", "底层条文须 ${CORE_MIN}–${CORE_MAX} 条，实得 $n")
        }
        if (core.drives.any { !it.hasTrigger }) {
            out += warn("CORE_NO_TRIGGER", "只列需求不给触发条件 ⇒ 提示：写'他就是这种人'没有依据")
        }
        val ranks = core.drives.map { it.rank }
        if (ranks != ranks.distinct() || ranks.sorted() != (1..n).toList()) {
            out += err("CORE_RANK", "价值排序须 1..$n 连续且无并列：$ranks")
        }
        val needs = core.drives.map { it.need }
        if (needs != needs.distinct()) out += err("CORE_NEED_DUP", "核心需求不得重复：$needs")
        return out
    }

    /** ②稳定特质：819 七轴 + 930 五因子；此处保留稳定码并给出可核对快照。 */
    private fun traitChecks(trait: TraitLayer): List<CheckItem> {
        val old = listOf(
            trait.sociability.label, trait.directness.label, trait.stance.label,
            trait.orientation.label, trait.selfControl.label, trait.aggressiveness.label, trait.humor.label,
        ).joinToString("/")
        val five = trait.bigFiveLevels().joinToString("/") { (axis, value) -> "$axis=${value.tier}(${value.source})" }
        return listOf(CheckItem(Severity.WARNING, "TRAIT_AXES", "819 七轴 → 930 十二轴（新增五因子）：$old/$five"))
    }

    /** ③表层：五轴齐、档位在 0–3、每轴 source 非空；source 空即"只有笼统性格、推不出状态" ⇒ 红。 */
    private fun surfaceChecks(surface: SurfaceState): List<CheckItem> {
        val out = ArrayList<CheckItem>()
        val axes = surface.levels.map { it.axis }
        if (axes != SurfaceAxis.entries.toList()) {
            out += err("SURFACE_AXES", "表层须五轴齐且各一次：$axes")
        }
        if (surface.levels.any { it.source.isBlank() }) {
            out += err("SURFACE_NO_SOURCE", "表层必须写明由哪条 670 状态推出；只有'性格开朗'⇒红")
        }
        out += PersonaLanguage.surfaceTags(surface.tags)
        return out
    }

    /** 语声十维：口头禅超长红、写了但台词里永不出现提示、崩相空提示。 */
    private fun voiceChecks(voice: PersonaVoice): List<CheckItem> {
        val out = ArrayList<CheckItem>()
        val v = voice.dimensions
        if (v.catchphraseTooLong) {
            out += err("CATCHPHRASE_LONG", "口头禅须 ≤${CATCHPHRASE_MAX} 字：${v.catchphrase}")
        }
        val ph = v.catchphrase
        if (!ph.isNullOrBlank() && voice.lines.none { it.features.catchphrase == ph }) {
            out += warn("CATCHPHRASE_NEVER", "口头禅'$ph'写了却永不出现 ⇒ 提示")
        }
        if (!v.hasBreakdown) {
            out += warn("BREAKDOWN_NONE", "崩相未指定（永不崩）⇒ 提示")
        }
        return out
    }

    /** 三态语言闸：叙述文本扫 clinical/stigma/决定论/越界标签；台词按 DIALOGUE 判定。 */
    private fun languageChecks(p: Persona): List<CheckItem> {
        val narrative = mutableListOf<String>()
        p.core.drives.forEach {
            narrative += it.coreFear
            narrative += it.trigger
        }
        val v = p.voice.dimensions
        narrative += listOfNotNull(v.address, v.catchphrase, v.modalParticle, v.pauseEllipsis, v.breakdown)
        val out = narrative.flatMap {
            PersonaLanguage.clinical(it) +
                PersonaLanguage.stigma(it) +
                PersonaLanguage.determinism(it) +
                PersonaLanguage.offSurfaceTags(listOf(it))
        }.toMutableList()
        p.voice.lines.forEach { line ->
            out += PersonaLanguage.clinical(line.text, ClinicalPosition.DIALOGUE)
            out += PersonaLanguage.stigma(line.text, ClinicalPosition.DIALOGUE)
            out += PersonaLanguage.determinism(line.text)
            out += PersonaLanguage.offSurfaceTags(listOf(line.text))
        }
        return out
    }

    /**
     * 归属硬规矩：写"他就是这种人"须指到②/①；写"他今天这样"只能指③。
     * 稳定措辞指到 SURFACE ⇒ 红；临时措辞指到 CORE/TRAIT ⇒ 红。
     */
    fun attribute(text: String, layer: PersonaLayer): CheckItem? {
        val stable = STABLE_WORDS.any { it in text }
        val moment = MOMENT_WORDS.any { it in text }
        return when {
            stable && layer == PersonaLayer.SURFACE -> err(
                "ATTR_STABLE_TO_SURFACE", "写'他就是这种人'须指到②稳定特质/①底层，不能指③表层",
            )
            moment && layer != PersonaLayer.SURFACE -> err(
                "ATTR_MOMENT_TO_CORE", "写'他今天这样'只能指③表层，不能指①/②",
            )
            else -> null
        }
    }

    /** 两人同场台词互换测试：读不出差异（任一方向 <2 处不合）⇒ 红。 */
    fun swapCheck(a: PersonaVoice, b: PersonaVoice): CheckReport {
        val items = ArrayList<CheckItem>()
        val (ab, ba) = VoiceSwap.swapTest(a, b)
        items += if (ab.passed) {
            CheckItem(Severity.WARNING, "SWAP_AB", ab.line())
        } else {
            err("SWAP_AB", "A→B 读不出差异（${ab.distinctCount}<${VoiceSwap.MIN_MISMATCH}）：两人一个嘴")
        }
        items += if (ba.passed) {
            CheckItem(Severity.WARNING, "SWAP_BA", ba.line())
        } else {
            err("SWAP_BA", "B→A 读不出差异（${ba.distinctCount}<${VoiceSwap.MIN_MISMATCH}）：两人一个嘴")
        }
        return CheckReport(items)
    }

    private fun err(code: String, message: String): CheckItem = CheckItem(Severity.ERROR, code, message)

    private fun warn(code: String, message: String): CheckItem = CheckItem(Severity.WARNING, code, message)
}
