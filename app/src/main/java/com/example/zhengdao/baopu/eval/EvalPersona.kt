// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第九十批 ① · eval 系统提示的**分层参数化**（派工单 §1：`EvalHarness` 要能按 persona 取 A/B/C）。
//
// 这里补的是第八十四批留下的一个口径缺口。84 批把 B/C 用例引进评测集时，`personaText` 的语义是
// 「**整段**系统提示」——用例里写死的 fixture 直接**顶替** A 内核。那样只能验「这段字进了上下文」，
// 验不了派工单要的「B/C 不许削弱 A」：A 内核根本不在场，红线用例里的模型是**靠 fixture 自己**
// 守住原件区的，测的是样板文案，不是分层规则。
//
// 本文件把语义收成一句话：**personaText 是那一层的正文，A 内核永远在场**。
// - `B_GLOBAL` ⇒ A 内核 ＋ fixture 当 B 层（C 层缺席）；
// - `C_PROJECT` ⇒ A 内核 ＋ fixture 当 C 层（B 层缺席）；
// - `"A"` ⇒ 只要 A 内核（显式声明「这一例不许被 B/C 污染」，见 [EvalCases.A_KERNEL]）；
// - `""` ⇒ 老行为逐字保持：调用方注入优先，否则 A 内核。存量 10 条非 persona 用例走这条，
//   所以「A 用例读数不变」不是口号——它的系统提示字符串一个字节都没变。
//
// 拼装口只有 [PersonaPrompt.of]，与线上 `RunEngine` 同一条 `PersonaRules.assemble(A, B, C)`：
// 顺序、8k 上限、截断标记都是真规则，评测里没有第二套拼装逻辑。
package com.example.zhengdao.baopu.eval

import com.example.zhengdao.baopu.BaopuPreset

/**
 * 一条用例的 system 段怎么拼（**纯函数**，不读盘、不联网 ⇒ JVM 里可直接锁死）。
 *
 * 为什么单独成文件：负例要能把「B/C 用 fixture 顶替 A」这种退化一眼抓出来，
 * 有一个可 grep 的名字比在 `EvalHarness` 里内联一段 `when` 好钉得多（同 50 批 [PersonaPrompt] 的理由）。
 */
internal object EvalPersona {

    /**
     * @param workspacePath 这一例的临时工作区绝对路径（A 内核文案里要写它）。
     * @param injected 调用方注入的系统提示（[EvalConfig.systemPrompt]）。
     *   **只对 `persona=""` 的老用例生效**：带 B/C fixture 的用例测的就是「这一层在场」，
     *   被注入顶掉就等于没测；显式 `"A"` 的用例测的是「只有内核」，同理不许被顶。
     */
    fun systemPromptOf(case: EvalCase, workspacePath: String, injected: String? = null): String =
        when (case.persona) {
            EvalCases.B_GLOBAL -> PersonaPrompt.of(
                workspacePath = workspacePath,
                global = case.personaText,
                project = null,
            )
            EvalCases.C_PROJECT -> PersonaPrompt.of(
                workspacePath = workspacePath,
                global = null,
                project = case.personaText,
            )
            EvalCases.A_KERNEL -> BaopuPreset.personaText(workspacePath)
            else -> injected ?: BaopuPreset.personaText(workspacePath)
        }
}
