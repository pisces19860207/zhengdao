// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第一百二十四批 · 多模态输入 v1》＋ 2026-10-21 用户口径（覆盖原单 §2）：
// 「`Provider.vision=false` 视为**未验证/不支持自动路由**：用户发图时不要静默丢图、不要自动
// 上传、不要自动 fallback；弹现有确认卡：『当前通道未验证看图，可能失败/收费』
// ［切到已验证通道］［仍要发送（风险自负）］［取消］；选『仍发送』才发，失败给可读原因。」
//
// 本文件是那张卡的**判据与文案本体**：零 Android、零 IO、纯函数，JVM 直测。
package com.example.zhengdao.baopu

// 这两个是 **agent 包里的 internal 选项卡类型**（`AskUserTool.kt` 的 `AskUserOption` /
// `AskUserRequest`，带 `detail` 与 `kind`），不是本包里那个 UI 数据卡
// [com.example.zhengdao.baopu.AskUserOption]（`AskUserCard.kt`，只有 id/label/recommended）。
// 同名不同物：explicit import 优先于同包声明，这里靠 import 选到 agent 那一份。
import com.example.zhengdao.baopu.agent.AskUserOption
import com.example.zhengdao.baopu.agent.AskUserRequest

/**
 * 未验证通道发图前的**用户同意闸**（第一百二十四批）。
 *
 * ## 它补的是哪一道缺口
 * 第二十四批把图送进模型时，判据是「通道不能看图 ⇒ 一张都不发，并明说省了几张」
 * （[VisionRules.select]）。这句话在**已知不支持**时是对的；但 `Provider.vision` 的
 * `false` 其实混着两种事：「有依据地不支持」和「没验证过」。默认 `false` 且
 * **不许猜 true**（`Provider` 的 KDoc 原话）⇒ 今天线上几乎所有通道都落在后者。
 * 于是真实行为变成：用户挑了图、看见缩略图、按了发送，**图被静默吃掉**，
 * 模型只收到那句「已省略 N 张图片」——这正是派工单点名的「静默丢图」。
 *
 * 本闸把「省略」换成「问人」：三种结局都由**用户**点出来，机器一律不替他选。
 *
 * ## 三条纪律
 * 1. **默认不发**：没人回答（超时/取消）＝ [Decision.CANCEL]，绝不默认「仍发送」；
 * 2. **没有候选就不出现那个选项**（[VisionRouting.verifiedCandidates] 空表 ⇒ 卡上只有两项），
 *    宁缺不硬塞——选项里写一个切不过去的通道，等于骗用户点；
 * 3. **文案里永不含 Key**：只取 `id` / `model` / `label` 三个字段。[Provider] 是 data
 *    class，`toString()` 会把 `apiKey` 原文打出来 ⇒ 本文件任何字符串都不许内插一个
 *    Provider 对象（与 `ModelRouter` 同一纪律，负例用例塞哨兵 Key 对拍）。
 *
 * ## 为什么复用 [AskKind.QUESTION]
 * 不新增枚举值 ⇒ UI 壳层（`ui/baopu` 那一层，第一百零四/一百零五批在飞）一个字都不用改，
 * 卡照样渲染在输入栏上方、照样由 `answerChoice(id)` 回收答案。派工单红线「不做 UI 视觉重设计」。
 */
internal object VisionConsent {

    /** 选项 id：切到已验证通道（后面必须跟目标通道 id，见 [switchTargetId]）。 */
    const val OPT_SWITCH_PREFIX: String = "vision-switch:"

    /** 选项 id：不动通道，原样把图发出去（风险由用户认）。 */
    const val OPT_SEND: String = "vision-send-anyway"

    /** 选项 id：本轮取消。 */
    const val OPT_CANCEL: String = "vision-cancel"

    /** 用户点完之后这件事怎么走（[DECISION_TIMEOUT] 之外只有这三条路）。 */
    enum class Decision {

        /** 切到候选通道（切完这一轮**继续跑**，图跟着新通道发）。 */
        SWITCH,

        /** 不切通道，本轮授权把图原样上送。 */
        SEND,

        /** 取消：不读盘、不上送、不落库。 */
        CANCEL,
    }

    /**
     * 这一轮要不要问（**唯一触发判据**）。
     *
     * 「本轮有图片附件」且「当前通道没验证过看图」才问；其余场合一个字节都不改既有路径。
     * 能力真相只看 `Provider.vision`（V5：这里不许自己看模型名，也不许查
     * [ModelCapabilityTable]——那张表里没有 vision 列，第一百二十四批口径明令不加）。
     */
    fun needed(imageCount: Int, visionSupported: Boolean): Boolean =
        imageCount > 0 && !visionSupported

    /** 切到某个通道的选项 id（把目标通道 id 编进 id，答案回来时才认得出切去哪）。 */
    fun switchTargetId(channelId: String): String = OPT_SWITCH_PREFIX + channelId

    /** 这个选项 id 是不是「切换」类（前缀判定，不做正则）。 */
    fun isSwitchOption(pickedId: String): Boolean = pickedId.startsWith(OPT_SWITCH_PREFIX)

    /**
     * 组装那张卡（**纯函数**：文案在这里定版，引擎侧只负责弹与收）。
     *
     * @param verified 已验证能看图的候选通道（[VisionRouting.verifiedCandidates] 的产出；
     *   序即建议序——候选名单原本的顺序，不额外排序：今天全表成本/延迟无依据，排不出真序）
     * @param imageCount 本轮图片张数（**附件张数**，不是读盘后的张数——闸在读盘之前）
     */
    fun request(verified: List<Provider>, imageCount: Int): AskUserRequest {
        val head = verified.firstOrNull()
        val options = buildList {
            if (head != null) {
                add(
                    AskUserOption(
                        id = switchTargetId(head.id),
                        label = "切到已验证通道",
                        detail = "切到 ${head.label}（${head.id}/${head.model}）再发这 $imageCount 张图；" +
                            "到下一个安全点生效，不打断别的会话" +
                            if (verified.size > 1) "（还有 ${verified.size - 1} 条候选，本轮先用第一条）" else "",
                        recommended = true,
                    ),
                )
            }
            add(
                AskUserOption(
                    id = OPT_SEND,
                    label = "仍要发送（风险自负）",
                    detail = "不动通道，$imageCount 张图原样上送：可能报错、也可能按失败计费；" +
                        "失败会给你原因，绝不悄悄丢图",
                ),
            )
            add(
                AskUserOption(
                    id = OPT_CANCEL,
                    label = "取消",
                    detail = "这一轮不发送：图片不读盘、不上送，这条消息也不落库" +
                        if (head == null) "（当前没有已验证看图的候选通道，先填好 Key 或换通道再发）" else "",
                    recommended = head == null,
                ),
            )
        }
        return AskUserRequest(
            question = "当前通道未验证看图，可能失败/收费（本轮 $imageCount 张图）",
            options = options,
        )
    }

    /**
     * 用户点了什么 ⇒ 这件事怎么走。
     *
     * **null（超时/没人回答）＝ 取消**：默认值必须落在「不发」这一侧——
     * 派工单红线「不自动上传」，把超时算成同意就是自动上传。
     * 认不出的 id（界面被改版、旧卡残留）同样落 [Decision.CANCEL]，不猜。
     */
    fun decide(pickedId: String?): Decision = when {
        pickedId == null -> Decision.CANCEL
        pickedId == OPT_SEND -> Decision.SEND
        pickedId == OPT_CANCEL -> Decision.CANCEL
        isSwitchOption(pickedId) -> Decision.SWITCH
        else -> Decision.CANCEL
    }

    /**
     * 切换类答案对应的候选通道（从 [verified] 里按 id 找回）。
     *
     * 找回来说明这轮的答案确实是一张本闸发出的卡；找不回（候选被改过、脏 id）
     * ⇒ null，调用方按取消处理，**不许**拿着一个没验证过的通道硬切。
     */
    fun switchTarget(verified: List<Provider>, pickedId: String?): Provider? {
        val id = pickedId?.takeIf { isSwitchOption(it) }?.removePrefix(OPT_SWITCH_PREFIX) ?: return null
        return verified.firstOrNull { it.id == id }
    }

    /** 「仍要发送」之后的那句留痕（进 run_log，也进本轮输入的说明；不含 Key）。 */
    fun authorizedNote(provider: Provider, imageCount: Int): String =
        "（当前通道 ${provider.id}/${provider.model} 未验证看图，本轮按用户选择原样上送 $imageCount 张）"

    /**
     * 「仍要发送」这一轮**失败**时补的可读原因（派工单：「选『仍发送』才发，失败给可读原因」）。
     *
     * 三个条件缺一个就返回空串 ⇒ 既有失败文案一个字节都不变：
     * ① 本轮确实是用户授权过的（不是默认路径）；② 通道仍未验证看图（切成功了就不该怪图）；
     * ③ 确实有图进了请求体（一张没上送，失败就与图无关，别把账算到图上）。
     *
     * 它**不猜错误码**：`errText` / `humanizeNetFailure` 那句原样保留在前面，这里只补
     * 「下一步该怎么办」——用户能自己做的那两步（换已验证通道／改用文字）。
     */
    fun failureHint(provider: Provider, visionAuthorized: Boolean, imagesSent: Int): String =
        if (!visionAuthorized || provider.vision || imagesSent <= 0) {
            ""
        } else {
            "\n这一轮是按你选的「仍要发送」把 $imagesSent 张图原样上送的" +
                "（${provider.id}/${provider.model} 没验证过看图）：这类失败最常见的原因是" +
                "这条通道不认图片块。换一条已验证看图的通道再发一次，或把图里的内容用文字说一遍。"
        }
}
