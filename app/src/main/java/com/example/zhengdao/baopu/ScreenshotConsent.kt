// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第一百七十七批施工图 §3（授权闸）＋用户 2026-10-24 13:2x 第三条：
// 「不逐张弹卡可以，但必须有一次会话授权/首次确认＋审计，不能后台偷截。」
// 零 Android、零 IO、纯函数，JVM 直测。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.AskUserOption
import com.example.zhengdao.baopu.agent.AskUserRequest

/**
 * 「抱朴要截玄览当前页发给模型核对」的**授权卡**（判据与文案本体）。
 *
 * ## 它挡的是什么
 * S1 让截图能进模型以后，「截不截」就成了一次真实的数据外发决策：页面里可能有
 * 账号、余额、聊天记录。用户明确要求过的是**一次会话授权 + 审计**，不是逐张弹卡；
 * 所以本闸分两层：
 * 1. **首次确认（会话级）**：同一会话第一次截图，弹一次卡；选「本次会话允许」后
 *    同会话不再问（进程内记住，**重启失效**——如实写进报告）；
 * 2. **敏感页每次问**：`SensitivePage.isSensitive` 命中（登录/支付/验证码类）⇒
 *    即使本会话已授权，**每次**都要再点一次。
 *
 * ## 三条纪律（与 VisionConsent 同規）
 * 1. **默认不截**：超时/没人回答/认不出的答案一律 [Decision.DENY]——把超时算成同意，
 *    就是「后台偷截」的另一种写法；
 * 2. **文案只写事实**：只截当前页、不截其他 App、不上传、留 `证道/截屏/`、不进相册；
 *    不承诺「安全」（截图确实会发给模型，用户要知情）；
 * 3. **不猜页面内容**：卡片里只放 URL 主机名与标题（截断），不把页面正文塞进卡。
 */
internal object ScreenshotConsent {

    /** 本次会话允许（同会话不再问；敏感页仍会问）。 */
    const val OPT_SESSION: String = "shot-allow-session"

    /** 仅这一次（下次还要问）。 */
    const val OPT_ONCE: String = "shot-allow-once"

    /** 拒绝（这次不截、不落盘、不执行）。 */
    const val OPT_DENY: String = "shot-deny"

    /** 用户点完之后怎么走。 */
    enum class Decision {
        /** 本次会话都允许。 */
        ALLOW_SESSION,

        /** 只允许这一次。 */
        ALLOW_ONCE,

        /** 不截。 */
        DENY,
    }

    /**
     * 组装那张卡（**纯函数**）。
     *
     * @param host 当前页主机名（可为空——拿不到状态时卡上不写地址）
     * @param title 当前页标题（可为空）
     * @param sensitive 是否命中敏感页判据（决定措辞与推荐项）
     */
    fun request(host: String?, title: String?, sensitive: Boolean): AskUserRequest {
        val where = buildString {
            if (!host.isNullOrBlank()) append(host.trim().take(80))
            if (!title.isNullOrBlank()) {
                if (isNotEmpty()) append(" · ")
                append("「").append(title.trim().take(40)).append("」")
            }
        }
        val question = if (sensitive) {
            "这个页面像敏感页（${SensitivePage.reasonOf(host, title)}）：确定要截图给模型看？"
        } else {
            "抱朴想截图当前玄览页面给模型核对" + if (where.isNotEmpty()) "（$where）" else ""
        }
        val scope = "只截玄览当前这一页，不截其他 App、不上传；文件留在 证道/截屏/，不进相册"
        return AskUserRequest(
            question = question,
            options = listOf(
                AskUserOption(
                    id = OPT_SESSION,
                    label = "本次会话允许",
                    detail = "$scope。同一会话里后面再截图不再问" +
                        if (sensitive) "（敏感页除外，仍然每次问）" else "",
                    recommended = !sensitive,
                ),
                AskUserOption(
                    id = OPT_ONCE,
                    label = "仅这一次",
                    detail = "$scope。下次截图还会再问",
                ),
                AskUserOption(
                    id = OPT_DENY,
                    label = "不截",
                    detail = "这次不截图、不落盘；我会改用 browser_snapshot 读页面文字",
                    recommended = sensitive,
                ),
            ),
            subject = "截图授权",
            detailText = scope,
        )
    }

    /**
     * 用户点了什么 ⇒ 怎么走。
     *
     * **null（超时/没人回答）＝ 拒绝**；认不出的 id 同样拒绝——不替用户做同意。
     */
    fun decide(pickedId: String?): Decision = when (pickedId) {
        OPT_SESSION -> Decision.ALLOW_SESSION
        OPT_ONCE -> Decision.ALLOW_ONCE
        else -> Decision.DENY
    }

    /** 拒绝后回灌给模型的那句人话（要能让模型换路，而不是干等）。 */
    fun deniedText(): String =
        "用户没有同意这次截图（或者没应答）⇒ 本次不截图、不落盘。" +
            "改用 browser_snapshot 读页面文字；真要看图，等用户明确说「截一张」再试。"

    /**
     * 审计行（进 RunLog；**url/title 已由调用方脱敏**）。
     *
     * 一次截图一条，无论成败——「不能后台偷截」的可核查性就落在这行上。
     */
    fun auditLine(
        source: String,
        url: String,
        title: String,
        file: String?,
        bytes: Int?,
    ): String = buildString {
        append("抱朴截图审计：授权=").append(source)
        append(" url=").append(url.take(120))
        append(" title=").append(title.take(60))
        if (file != null) append(" file=").append(file)
        if (bytes != null) append(" bytes=").append(bytes)
    }
}
