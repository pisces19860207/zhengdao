// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十批 · 斥候的**判据层**（纯函数：不碰 Android、不碰文件、不读时钟，JVM 直测）。
//
// 这一层只回答四个问题：
//   ① 今天的读数 ＋ 昨天的账 ⇒ 这一项该记什么态（granted/revoked/denied/unavailable）；
//   ② 这个态**允不允许主动提**（revoked 只提一次；denied 1–2 次不提、3 次后永不提）；
//   ③ 用户主动问起时**怎么说话**（中性、不翻旧账、不说"你拒了 3 次"）；
//   ④ 这次启动**该不该探**（首启/手动/权限变更/版本变化）。
//
// 边界（派工单硬约束，逐条落在这里）：
// - 不把"不可用"说成"可以做"：读数失败 [ProbeSignal.Failed] 只会落到 UNAVAILABLE；
// - 不弹窗：本文件没有任何 UI 类型；
// - 失败也落账：[ProbeSignal.Failed] 带原因，调用方照写账本（重装不丢）。
package com.example.zhengdao.baopu.scout

internal object ScoutRules {

    /**
     * 四态推导（纯函数）。
     *
     * 顺序本身就是语义：**"曾给过"要问历史，其他当场就能定**。
     * - 现在能用 ⇒ GRANTED（驳回次数清零：重新给了就不再算"驳回过的账"）；
     * - 现在不能用 ＋ 上次是 GRANTED ⇒ **REVOKED**（这是四态扩出来的那一条）；
     * - 现在不能用 ＋ 有历史 ⇒ 沿用历史次数（DENIED）；
     * - 现在不能用 ＋ 没历史 ⇒ DENIED，次数 0（**还没问过**，不是"拒绝过"）；
     * - 不支持 / 探针失败 ⇒ UNAVAILABLE（必须带原因）。
     *
     * 为什么"没历史"不直接算 unavailable：能装能跑的手机，权限项大多数是"没开"而不是"没有"；
     * 把它报成"本机不支持"就是**把不可用说成不可办**，用户按清单去开权限会发现清单在撒谎。
     */
    fun resolve(
        capability: Capability,
        signal: ProbeSignal,
        prior: ScoutRecord?,
        atMs: Long,
        device: String,
    ): ScoutRecord = when (signal) {
        is ProbeSignal.Granted -> ScoutRecord(
            capability = capability,
            state = ScoutState.GRANTED,
            denials = 0,
            lastAtMs = atMs,
            lastIntent = prior?.lastIntent.orEmpty(),
            note = signal.note,
            device = device,
        )

        is ProbeSignal.NotGranted -> when {
            prior?.state == ScoutState.GRANTED -> ScoutRecord(
                capability = capability,
                state = ScoutState.REVOKED,
                denials = 0,
                lastAtMs = atMs,
                lastIntent = prior.lastIntent,
                note = signal.reason,
                // 每次"刚被收回"都重新拥有一次"提一次"的额度（判据⑤）。
                revokedMentioned = false,
                device = device,
            )

            // 已经是"被收回"就**停在 revoked**（不让它掉回 denied）：掉回去等于把"上次开过"
            // 这件事实抹掉，用户会看到"要用时会问你要不要开"——那是还没问过的措辞。
            // `revokedMentioned` 原样带过来，所以"只提一次"挡得住第二次探测（判据⑤）。
            prior?.state == ScoutState.REVOKED -> prior.copy(
                lastAtMs = atMs,
                note = signal.reason,
                device = device,
            )

            else -> ScoutRecord(
                capability = capability,
                state = ScoutState.DENIED,
                denials = prior?.denials ?: 0,
                lastAtMs = atMs,
                lastIntent = prior?.lastIntent.orEmpty(),
                note = signal.reason,
                device = device,
            )
        }

        is ProbeSignal.Unsupported -> ScoutRecord(
            capability = capability,
            state = ScoutState.UNAVAILABLE,
            lastAtMs = atMs,
            lastIntent = prior?.lastIntent.orEmpty(),
            reason = signal.reason,
            device = device,
        )

        is ProbeSignal.Failed -> ScoutRecord(
            capability = capability,
            state = ScoutState.UNAVAILABLE,
            lastAtMs = atMs,
            lastIntent = prior?.lastIntent.orEmpty(),
            // 探针失败**不是**"本机不支持"，原因里要能看出是探测本身没成（287 追加）。
            reason = "探测失败：${signal.reason}",
            device = device,
        )
    }

    /**
     * 两条快照是不是**同一件事实**（`lastAtMs` 不参与：时间只因"事实变了"才该动）。
     *
     * 为什么要有：每次回前台都重探一遍，若按 `record != prior` 判"变没变"，
     * 时间戳每次都不同 ⇒ 11 项全部重写 ⇒ 账本三趟就成一堵墙，"第几次"再也翻不出来。
     */
    fun sameFact(prior: ScoutRecord?, fresh: ScoutRecord): Boolean =
        prior != null &&
            prior.capability == fresh.capability &&
            prior.state == fresh.state &&
            prior.denials == fresh.denials &&
            prior.reason == fresh.reason &&
            prior.note == fresh.note &&
            prior.revokedMentioned == fresh.revokedMentioned &&
            prior.lastIntent == fresh.lastIntent &&
            prior.device == fresh.device

    /**
     * 用户驳回了一次：[ScoutRecord.denials] 加一并记下"当时想做的事"。
     *
     * 为什么次数要落在这里、而不是让 UI 自己数：次数是**账本事实**（重装不丢），
     * 不是会话内状态；UI 每次开都从 0 数就会变成"永远第 1 次"，那正是"反复弹"的来源。
     */
    fun withDenial(record: ScoutRecord, intent: String, atMs: Long): ScoutRecord = record.copy(
        state = ScoutState.DENIED,
        denials = record.denials + 1,
        lastAtMs = atMs,
        lastIntent = intent.trim(),
        revokedMentioned = false,
    )

    /** 用户在系统设置里重新给了（或卡上改判为允许）：次数清零，态改 granted。 */
    fun withGrant(record: ScoutRecord, atMs: Long): ScoutRecord = record.copy(
        state = ScoutState.GRANTED,
        denials = 0,
        lastAtMs = atMs,
        note = "",
        revokedMentioned = false,
    )

    /** 记一次"已提过 revoked"（判据⑤：提过就不再主动提）。只对 REVOKED 生效。 */
    fun withMention(record: ScoutRecord, atMs: Long): ScoutRecord =
        if (record.state == ScoutState.REVOKED) {
            record.copy(revokedMentioned = true, lastAtMs = atMs)
        } else {
            record
        }

    // ── ② 能不能主动提 ────────────────────────────────────────────────────────

    /**
     * **主动**提（弹卡/开口问）的判据——派工单「denied 1–2 次不主动提、3 次后不再主动提」。
     *
     * - revoked ⇒ 只提一次（`revokedMentioned` 一旦为真就再也不主动提）；
     * - denied ⇒ **只有次数 0（还没问过）可以主动问一次**；问过但没开（1 次起）不主动提；
     * - unavailable ⇒ 从不主动提（只说原因，落账不再问）；
     * - granted ⇒ 没什么可提的。
     */
    fun proactiveAskAllowed(record: ScoutRecord): Boolean = when (record.state) {
        ScoutState.REVOKED -> !record.revokedMentioned
        ScoutState.DENIED -> record.denials == 0
        ScoutState.GRANTED, ScoutState.UNAVAILABLE -> false
    }

    /**
     * 用户**主动问起**时永远照答（判据④的后半句）。
     *
     * 这条恒真看着像废话，但它是"3 次后不再主动提 ≠ 永久拒绝"的类型化：
     * 主动提是配额，照答是义务，两件事不许写成一个布尔。
     */
    @Suppress("UNUSED_PARAMETER")
    fun answerWhenAsked(record: ScoutRecord): Boolean = true

    // ── ③ 措辞（不许翻旧账）─────────────────────────────────────────────────

    /**
     * 用户问起、或当前请求真的依赖这项能力时的那一句。
     *
     * **措辞纪律**（派工单「措辞不许翻旧账」）：
     * - 不说"你拒了 N 次"（次数是账，不是话术；`ScoutLedger` 里查得到，嘴里不念）；
     * - 不说"又"、不说"上次不是说了吗"；
     * - 不可用就说原因，**不许拐成"你可以试试"**（那就是把不可用说成可以做）。
     */
    fun wordingFor(record: ScoutRecord): String = when (record.state) {
        ScoutState.GRANTED -> "「${record.capability.cn}」现在可以用。"

        // revoked 提过一次之后判据层不再主动问（[proactiveAskAllowed]），那句"要重新开吗"就作废了：
        // 同一件事只问一次是规矩，措辞不许再许第二次。
        ScoutState.REVOKED ->
            if (record.revokedMentioned) {
                "「${record.capability.cn}」上次开过、现在没了。要用的话可以在系统设置里打开。"
            } else {
                "「${record.capability.cn}」上次开过、现在没了，要重新开吗？"
            }

        ScoutState.DENIED ->
            if (record.denials >= 3) {
                "这件事需要「${record.capability.cn}」权限，现在没开。要用的话可以在系统设置里打开。"
            } else {
                "这件事需要「${record.capability.cn}」权限，现在没开，要开吗？"
            }

        // 说"现在做不了"而不是"这台手机上做不了"：后者对"没插耳机"这种**当下状态**是假话
        // （手机明明支持耳机），对"没有 root"又少说了"现在"——一个中性词两头都不冤枉。
        ScoutState.UNAVAILABLE ->
            "「${record.capability.cn}」现在做不了：${record.reason}"
    }

    /**
     * 驳回前的**后果说明**（派工单「驳回前说清后果，不许盲点」）。
     *
     * 确认卡上要能回答"拒了会怎样"——这里给出那句话的稳定形态；
     * 真正的卡面是 UI 批次的事，本批只把话术与它依赖的字段准备好。
     */
    fun consequenceOfDenying(capability: Capability, blockedTask: String): String {
        val task = blockedTask.trim().ifEmpty { "这件事" }
        return "拒绝后「$task」将不可用（需要「${capability.cn}」权限）。"
    }
}

/** 何时探（判据：首启/手动/权限变更/版本变化）。纯函数，设备指纹由调用方给。 */
internal object ScoutFreshness {

    /**
     * 这次启动该不该探。
     *
     * - `FIRST_RUN`：没有上次指纹（装好第一次打开、或换了工作区读不到账）；
     * - `MANUAL` / `PERMISSION_CHANGED`：无条件探（都是"用户刚动过手"）；
     * - `APP_VERSION`：versionCode 变了才探（不用版本名：同一版重装不该重复探）；
     * - `OS_VERSION`：SDK 或 release 变了（系统升级/降级）。
     */
    fun shouldProbe(trigger: ScoutTrigger, last: ScoutDevice?, now: ScoutDevice): Boolean =
        when (trigger) {
            ScoutTrigger.FIRST_RUN -> last == null
            ScoutTrigger.MANUAL, ScoutTrigger.PERMISSION_CHANGED -> true
            ScoutTrigger.APP_VERSION -> last == null || last.versionCode != now.versionCode
            ScoutTrigger.OS_VERSION -> last == null || last.sdkInt != now.sdkInt || last.release != now.release
        }

    /**
     * 这次启动**自动**触发的那几件事（日志与账本都记这个集合，别让"为什么又探了"只能靠猜）。
     *
     * 没有指纹＝**首启**，这时"版本变了"是无意义的话（没有上一个可比）⇒ 只报 `first_run`。
     * 有指纹时才报版本类触发（两个都可能同时中：装了新包又升了系统）。
     */
    fun autoTriggers(last: ScoutDevice?, now: ScoutDevice): List<ScoutTrigger> =
        if (last == null) {
            listOf(ScoutTrigger.FIRST_RUN)
        } else {
            listOf(ScoutTrigger.APP_VERSION, ScoutTrigger.OS_VERSION).filter { shouldProbe(it, last, now) }
        }

    /** 首启时间预算（判据①：≤2 秒）。超了由调用方记日志——**超时不是失败**，账照落。 */
    fun withinBudget(elapsedMs: Long): Boolean = elapsedMs <= ScoutLimits.MAX_PROBE_MS
}
