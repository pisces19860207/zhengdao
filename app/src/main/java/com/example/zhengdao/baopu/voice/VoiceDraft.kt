// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.voice

/**
 * 第六百三十三批 · 输入框草稿状态机（纯 JVM，不认 Android、不认 Context）。
 *
 * 四条口径：
 * 1. partial 只更新「未定稿段」，逐段写回输入框（输入框原有内容保留在 [prefix] 里）；
 * 2. 取消 ⇒ [cancel] 返回**开始前内容**，整段语音一个字都不留；
 * 3. 人工编辑过的段 ⇒ 冻结，后续 partial 一律不覆盖（[onUserEdit] 把用户改后的
 *    文本整段接管为语音区，并置 frozen）；
 * 4. 结束只返回文本，**不发送**——发送是输入框旁那颗按钮的独立动作。
 *
 * Compose 的 OutlinedTextField 只在用户交互时回调 onValueChange；程序写回 value 不回调，
 * 因此「程序写回」与「人工编辑」在本状态机里天然分得开，不需要额外标志位。
 */
internal class VoiceDraft(private val baseline: String) {

    /** 开始前内容（末尾补一个空格，避免识别结果粘在原有文字后面）。 */
    private val prefix: String = if (baseline.isEmpty()) {
        ""
    } else if (baseline.last().isWhitespace()) {
        baseline
    } else {
        "$baseline "
    }

    /** 已定稿 / 被人工接管的语音区。 */
    private var voiceArea: String = ""

    /** 当前未定稿段（partial）。 */
    private var partialText: String = ""

    /** 人工编辑后置位：后续 partial、final 都不许再改输入框。 */
    private var frozenByEdit = false

    private var settled = false

    /** 当前应显示在输入框里的文本。 */
    fun render(): String = prefix + voiceArea + partialText

    fun isFrozen(): Boolean = frozenByEdit

    /** 逐词进框：冻结或已收口 ⇒ 不动。 */
    fun onPartial(text: String) {
        if (settled || frozenByEdit) return
        partialText = text
    }

    /** 一段定稿；冻结段保持人工文本原样。 */
    fun onFinal(text: String) {
        if (settled) return
        if (frozenByEdit) return finishEarly()
        voiceArea += text
        partialText = ""
        settled = true
    }

    /**
     * 用户改了输入框：与 [render] 不同即为人工编辑。
     * 只接管 prefix 之后的语音区；连原有内容都改了也整体冻结，绝不覆盖用户改的字。
     */
    fun onUserEdit(newText: String) {
        if (settled) return
        if (newText == render()) return
        if (newText.startsWith(prefix)) {
            voiceArea = newText.removePrefix(prefix)
            partialText = ""
        }
        frozenByEdit = true
    }

    /** 取消：整段丢弃，输入框回到开始前内容。 */
    fun cancel(): String {
        settled = true
        return baseline
    }

    /** 转完：只交回文本等人确认，绝不发送。 */
    fun finish(): String {
        settled = true
        return render()
    }

    private fun finishEarly() {
        settled = true
    }
}
