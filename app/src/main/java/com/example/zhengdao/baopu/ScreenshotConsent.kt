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
 * 2. **文案只写事实**：只截当前页、不截其他 App、不上传、存进系统截屏夹
 *    `Pictures/Screenshots/`（268 起与用户自己截的图同目录、相册可见）；
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
        val scope = "只截玄览当前这一页，不截其他 App、不上传；文件存进系统截屏夹 Pictures/Screenshots/（相册里能看到，与你自己截的图同目录）"
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

/**
 * 「抱朴要切到玄览截一张（约 2 秒，随后切回）」的**切屏征询卡**（262 批第二步）。
 *
 * 与 [ScreenshotConsent] 的分工（两层独立、都过才截）：那张管**数据外发**
 * （截了给模型看——隐私授权），这张管**界面打扰**（要把用户屏幕切到玄览再切回来）。
 * 用户拒截图 ⇒ 不用问这张；用户同意截图但不想被打扰 ⇒ 截不了、如实跳过。
 *
 * 纪律：**超时/没人答/认不出 ⇒ 跳过**（不抢屏——「超时算同意切屏」与「超时算同意
 * 截图」是同一种错）；文案只写事实（切哪个 tab、约多久、切回来），不承诺「没感觉」。
 */
internal object ScreenshotSwitchConsent {

    /** 同意（切过去截、截完切回）。 */
    const val OPT_AGREE: String = "switch-agree"

    /** 跳过（这次不切屏、不截；不算失败）。 */
    const val OPT_SKIP: String = "switch-skip"

    enum class Decision {
        /** 切屏执行（引擎负责切进切出与恢复）。 */
        AGREE,

        /** 不切屏；本次截图如实记 SKIPPED_NO_VISUAL。 */
        SKIP,
    }

    /** 组装那张卡（**纯函数**；host/title 可空——拿不到就不写地址）。 */
    fun request(host: String?, title: String?): AskUserRequest {
        val where = buildString {
            if (!host.isNullOrBlank()) append(host.trim().take(80))
            if (!title.isNullOrBlank()) {
                if (isNotEmpty()) append(" · ")
                append("「").append(title.trim().take(40)).append("」")
            }
        }
        return AskUserRequest(
            question = "抱朴需要一张玄览的截图作视觉证据" + if (where.isNotEmpty()) "（$where）" else "",
            options = listOf(
                AskUserOption(
                    id = OPT_AGREE,
                    label = "同意（约 2 秒）",
                    detail = "我切到玄览截一张（约 2 秒，页面没开过时再花约 2 秒载入，随后切回你现在的页面）；滚动位置不动",
                    recommended = true,
                ),
                AskUserOption(
                    id = OPT_SKIP,
                    label = "跳过",
                    detail = "这次不切屏、不截图（记 SKIPPED_NO_VISUAL，不算失败）；要看内容我改用文字证据",
                ),
            ),
            subject = "切屏征询",
            detailText = "只切 App 内页签到玄览，不动其他 App、不动滚动位置；截完切回",
        )
    }

    /**
     * 用户点了什么 ⇒ 怎么走。
     *
     * **null（超时/没人回答）＝ 跳过**；认不出的 id 同样跳过——不替用户切屏。
     */
    fun decide(pickedId: String?): Decision = when (pickedId) {
        OPT_AGREE -> Decision.AGREE
        else -> Decision.SKIP
    }

    /** 跳过后回灌给模型的那句（要能让模型换路，而不是干等或当失败重试）。 */
    fun skippedText(): String =
        "SKIPPED_NO_VISUAL：用户没同意切屏（或没应答）⇒ 本次没截图。这**不算失败**，别重试截图。" +
            "要看页面内容改用 browser_snapshot 或 browser_evidence（都不依赖页面在前台）。"
}

/**
 * 截图的**三级链路**（262 批 §6，2026-10-28 追加；依据 263 真机实测）。
 *
 * 263 的发现：截图成败取决于「**载入时**是否在前台」，不是「截图时」——已在前台
 * 渲染过的页，切后台再画不失真；后台载入的页 readyState 也会到 complete，但渲染器
 * 从没产出过像素，直接画必然白图。所以按「页面渲染过没有 × App 在不在前台」分流：
 *
 * - [PAINT_OFFSCREEN]：渲染过 ⇒ 直接画（免征询、免切屏）；
 * - [LOAD_THEN_PAINT]：没渲染过、App 在前台 ⇒ 先前台载入再画（1dp 离屏挂窗，
 *   不抢焦点，用户无感；**不出卡**）；
 * - [ASK_TO_FOREGROUND]：没渲染过、App 还在后台 ⇒ 才出征询卡（切前台 2 秒载入
 *   再画；跳过记 SKIPPED_NO_VISUAL）。
 */
internal enum class ShotTier {
    /** 已渲染过：直接离屏画。 */
    PAINT_OFFSCREEN,

    /** 没渲染过、App 在前台：先前台载入再画（不打扰）。 */
    LOAD_THEN_PAINT,

    /** 没渲染过、App 在后台：出征询卡（唯一的打扰档）。 */
    ASK_TO_FOREGROUND,
}

/**
 * 三级的**判定**（纯函数，零 Android）。
 *
 * 判定顺序就是 §6 的优先级：能直接画就不载入，能无感载入就不问人。「浏览器在不在
 * 屏上」**不进**判定——玄览在屏但页面未载入时走 [ShotTier.LOAD_THEN_PAINT] 就地
 * 载入即可，载入过程用户看得见，无妨。
 *
 * ⚠️ 没渲染过就画＝白图，但那是**我们没载入**，不是页面有问题——错误账记在链路上
 * （日志写「未载入」），永远不许写「页面有问题」。
 */
internal object ShotTierRule {

    /** [decide] 的判定顺序（§6 三条，从上往下第一条命中即走）。 */
    fun decide(pageRenderable: Boolean, appForeground: Boolean): ShotTier = when {
        pageRenderable -> ShotTier.PAINT_OFFSCREEN
        appForeground -> ShotTier.LOAD_THEN_PAINT
        else -> ShotTier.ASK_TO_FOREGROUND
    }

    /**
     * 载入等待的判据（[ScreenSwitch.loadForShot] 的轮询用）：readyState 到
     * `complete` 才算载入完成。`interactive`/`loading`/拿不到都算没好——
     * 半成品就画，与「没载入就画」是同一类错。
     */
    fun isReadyState(readyState: String?): Boolean = readyState == "complete"
}

/**
 * 切屏台（262 批第二步的宿主能力口）。
 *
 * 为什么是接口：引擎（baopu.agent）不许 import 宿主 UI 类；真身由
 * `BaopuBrowserWiring.install` 装配（「玄览的接线只许有一个地方」），没装配
 * （JVM 测试/极端环境）⇒ 引擎按「玄览已在屏上」放行——只少打扰，不少功能。
 *
 * 「用户手动切走就不抢」的判定落在 [restoreAfterShot]：恢复那一刻用户已不在
 * 玄览 ⇒ 说明他自己选了别的页，不动。切进切出只切 App 内页签，**不动滚动位置**
 * （WebView 归宿主管，切页签本就不碰它）。
 */
internal interface ScreenSwitch {

    /** 玄览此刻是否在屏上（征询卡的触发判据：在屏上就不问、直接截）。 */
    fun browserOnScreen(): Boolean

    /**
     * 程序化切到玄览（征询卡 [ScreenshotSwitchConsent.OPT_AGREE] 之后调）。
     *
     * @return 切之前的原 App 页签（用于截完恢复）；拿不到（未上报/已在玄览）＝ null
     */
    fun openForShot(): Int?

    /**
     * 截完切回 [origin]。用户此刻已手动切走（不在玄览）⇒ **不动**并返回 false——
     * 他切去哪儿是他此刻的选择，程序化恢复反而是抢屏。
     */
    fun restoreAfterShot(origin: Int?): Boolean

    // ── 262 批 §6 三级链路的四个新口 ────────────────────────────────────────
    //
    // §6（2026-10-28 追加）按「载入状态 × 前台状态」把截图分流成三级，判定所需的
    // 两个事实（页面渲染过没有 / App 在不在前台）与两个补救动作（前台载入 / 摘下
    // 离屏挂载）都只能宿主回答与执行——引擎只认接口。

    /**
     * 截图目标页（玄览当前页）此刻**渲染过没有**（263 实测口径：成败取决于「载入时
     * 是否在前台」，不是「截图时」）。
     *
     * 判据在宿主（[com.example.zhengdao.ui.browser.BrowserTabState.isRenderable]）：
     * 载入过 **且** 载入完成时 App 在前台 **且** 没被回收。只「载入过」不够——后台
     * 载入的页 readyState 也会到 complete，直接画必然白图。
     */
    fun pageRenderable(): Boolean

    /** App 是否在前台（任一 Activity 处于 started；[com.example.zhengdao.AppForeground] 同一口径）。 */
    fun appInForeground(): Boolean

    /**
     * 前台载入（§6 ②/③ 的补救动作）：把目标页拉起来并等到真的载入完成。
     *
     * 页面没挂载（玄览离屏/未载入页签）⇒ 挂 1dp 离屏容器（**不抢焦点、用户无感**）；
     * 页面本来就挂在真实窗口（玄览在屏）⇒ 就地载入，用户看得见——那本来就是要给
     * 他看的页。载入路径复用既有出口（恢复标签走 loadRestoredTab、回收标签走
     * reloadRecycled、普通页 loadUrl）。
     *
     * @return 是否真的载入就绪（readyState 到 complete 且引擎提交了目标地址）；
     *   超时如实回 false——调用方照样执行截图，拍不出是工具自己的诚实错误。
     */
    fun loadForShot(): Boolean

    /**
     * 摘下离屏挂载（与 [loadForShot] 配对；载入时就地挂在真实窗口的页签没有东西
     * 可摘 ⇒ no-op）。只在 WebView 还被**我们自己的 1dp 容器**持有时才摘——用户
     * 若在载入期间切到玄览，组合已把它挂进界面，那一刻再摘等于当面拆页面。
     */
    fun detachForShot()
}

/**
 * 切屏台的装配点（进程级；`BaopuBrowserWiring.install` 写入，引擎读）。
 *
 * null（没装配/测试环境）⇒ 引擎按「玄览已在屏上」放行——只少打扰，不少功能。
 */
internal object ScreenSwitchDesk {
    @Volatile
    var impl: ScreenSwitch? = null
}
