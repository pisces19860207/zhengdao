// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.bridge

/**
 * 不可逆动作的词表判据（622 批：从示教层的 TeachSafety **上收**到端口，一处判）。
 *
 * ## 为什么搬到这里（"三档闸"的第一半）
 *
 * 旧格局：拒点九词住在 `baopu/TeachSafety.kt`，只有示教（录制拒记 / 回放拒动 / 落盘拒写）
 * 三层用它——抱朴工具路径的点击不经过它（它靠工具层的 CONFIRM 卡兜底）。622 要把
 * 浏览器层收口成**一个执行端口**（`BrowserPort`），三档闸在端口内判：
 * 外部不可逆（提交/发布/支付/删除…）必须回抱朴走闸。于是这份词表必须住在 bridge
 * （端口的家），示教层反过来引用它——**名单只有一份**，谁都没资格再抄一份
 * （TeachSafety 的 KDoc 原话：名单一个字不许增删——增删要过派工单变更）。
 *
 * ## 判定输入
 *
 * 候选元素的**全部语义面**（可见文本、可访问名、aria-label、data-testid）——换一个面
 * 写危险词照样拦。语义面由调用方带进来（示教/回放路径有元素快照；抱朴工具路径暂无
 * 元素语义，靠工具层 CONFIRM 兜底——见 BrowserPort 的 KDoc「三档闸」段）。
 */
internal object PortIrreversible {

    /** 拒点词表（派工单定稿九词，一个字不许增删——增删要过派工单变更）。 */
    val CLICK_REFUSAL_TEXT: Regex = Regex("提交|确认|删除|作废|发货|派车|结算|支付|充值")

    /**
     * 英文镜像词表（TeachSafety 同款）：同一九个概念的英文写法，词边界匹配。
     * 误伤方向安全（误拦 ⇒ 停下问人，不误点）。
     */
    val CLICK_REFUSAL_TEXT_EN: Regex =
        Regex("(?i)\\b(submit|confirm|delete|remove|void|ship|dispatch|settle|pay|payment|checkout|recharge|topup|top_up)\\b")

    /**
     * 不可逆判定：语义面命中拒点词 ⇒ 返回原因（调用方原样进回执/账本）；null = 放行。
     *
     * 端口与示教层共用：端口拿它把 CLICK/ACT 升档成「外部不可逆」（必须回抱朴走闸），
     * 示教层（TeachSafety.refuseClickReason）拿它做录制拒记/回放拒动——同一个函数，
     * 不会有两份词表。
     */
    fun reasonIn(text: String?, accessibleName: String?, ariaLabel: String?, testid: String?): String? =
        reasonIn(listOfNotNull(text, accessibleName, ariaLabel, testid))

    /** 语义面列表版（端口 [BrowserPort.PortCall.subject] 直接喂；顺序无关，全部面都扫）。 */
    fun reasonIn(faces: List<String>): String? {
        for (face in faces.filter { it.isNotBlank() }) {
            CLICK_REFUSAL_TEXT.find(face)?.let {
                return "拒点：候选文案含「${it.value}」（${face.take(24)}）——提交/删除/结算类按钮一律回抱朴走闸"
            }
            CLICK_REFUSAL_TEXT_EN.find(face)?.let {
                return "拒点：候选文案含英文危险词「${it.value}」（${face.take(24)}）——提交/删除/结算类按钮一律回抱朴走闸"
            }
        }
        return null
    }
}
