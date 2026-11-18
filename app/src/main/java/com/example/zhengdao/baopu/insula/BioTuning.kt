// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十一批 · 岛叶消费层的**纯判据**：内感受——把自身状态读出来，据此调
// **自己的行为参数**。可调白名单只有四个（派工单 §二②，闭集一个不多）：
//   并发度 · 批量大小 · 重试间隔 · 是否延后重活。
//
// 四条禁止（§二③，由源码门禁 InsulaSourceGateTest 钉死，不是靠自觉）：
//   不改用户设置 · 不改授权档 · 不改判据阈值 · 不改工具白名单。
// 本件里根本没有那些东西的引用——白名单闭集决定了调不出别的。
//
// 调节只收紧不放宽（机械规则，每条带依据）：体征越紧 ⇒ 参数越保守；
// 放宽走**人**的一键回默认（TuningLedger.resetAll）——机器不猜测"缓过来了"，
// 防抖动（两轮一紧一松来回扇）比省那点资源重要。
//
// 读数纪律（§二①）：只消费既有采集——电量/充电/发烫走 `AutoMaintenance`（479 唯一读数源），
// 内存走 `KeepaliveWatcher` 同一口径（堆＋系统可用），存储走 `StatFs`（WishIOWiring 既定尺），
// 延迟走 `run_trace` 的 durationMs，预算余量走 `AskDailyBudget`。本件不摸任何系统服务。
//
// 纪律：零 Android、零 IO、零时钟——体征与"现在"全由调用方注入，JVM 直测（低电量夹具）。
package com.example.zhengdao.baopu.insula

/** 可调参数闭集（白名单只有这四个；第 N 个不存在）。 */
internal enum class TunableParam(val cn: String, val wire: String) {
    CONCURRENCY("并发度", "concurrency"),
    BATCH_SIZE("批量大小", "batchSize"),
    RETRY_BACKOFF("重试间隔", "retryBackoffMs"),
    DEFER_HEAVY("延后重活", "deferHeavy"),
    ;

    companion object {
        fun ofWire(raw: String): TunableParam? = entries.firstOrNull { it.wire == raw }
    }
}

/** 四个参数的现值（默认值见 [DEFAULT]；调没调过看账，这里只存现值）。 */
internal data class BioTuning(
    val concurrency: Int,
    val batchSize: Int,
    val retryBackoffMs: Long,
    val deferHeavy: Boolean,
) {
    companion object {
        /** 出厂默认（收敛档）：顺序够用、20 条一趟、半小时冷却、重活不延后。 */
        val DEFAULT = BioTuning(
            concurrency = DEFAULT_CONCURRENCY,
            batchSize = DEFAULT_BATCH_SIZE,
            retryBackoffMs = DEFAULT_RETRY_BACKOFF_MS,
            deferHeavy = false,
        )

        const val DEFAULT_CONCURRENCY = 2
        const val DEFAULT_BATCH_SIZE = 20
        const val DEFAULT_RETRY_BACKOFF_MS = 30L * 60_000L

        /** 各参数的可调下界（收紧撞到下界就停在原地，不为零——器官瘫了等于没生）。 */
        const val MIN_CONCURRENCY = 1
        const val MIN_BATCH_SIZE = 10
        const val MAX_RETRY_BACKOFF_MS = 2L * 3_600_000L
    }
}

/** 一把体征读数（全部来自既有采集的装配侧；null ＝ 那格没读数，规则按"不知道"走）。 */
internal data class BioVitals(
    /** 电量百分比（0–100；-1 ＝ 读不出，[AutoMaintenance.batteryPct] 口径）。 */
    val batteryPct: Int,
    val charging: Boolean,
    /** 设备发烫（热档到限流那档；AutoMaintenance.hot 口径）。 */
    val hot: Boolean,
    /** 堆内存压力 0..1（used/max；读不出 ⇒ null）。 */
    val heapPressure: Double?,
    /** 存储余量占比 0..1（free/total；读不出 ⇒ null）。 */
    val storageFreeRatio: Double?,
    /** 最近 ACT 边界的 p95 耗时 ms（run_trace；没数据 ⇒ null）。 */
    val actP95Ms: Long?,
    /** 当日问卡预算余量（AskDailyBudget.LIMIT − usedToday；<0 按 0）。 */
    val budgetLeft: Int,
) {
    companion object {
        /** 低电门槛：与机会式触发的 [Opportunistic.MIN_BATTERY_PCT] 同一把尺（20%），不另立口径。 */
        const val LOW_BATTERY_PCT = 20

        /** 存储告急线：余量 ≤5% 当"满"。 */
        const val STORAGE_CRITICAL_RATIO = 0.05

        /** 内存告急线：堆压力 ≥0.9（接近 OOM 才动，别为一趟扫描瞎敏感）。 */
        const val HEAP_CRITICAL_RATIO = 0.9

        /** 延迟告警线：p95 超过它 ⇒ 重试冷却加倍（模型与工具的体感慢）。 */
        const val ACT_P95_SLOW_MS = 20_000L
    }
}

/** 一次调节动作（落账行的形状：调了什么、旧值→新值、依据哪条读数）。 */
internal data class TuningMove(
    val param: TunableParam,
    val from: String,
    val to: String,
    val basis: String,
)

/**
 * 调节判定（纯函数；机械规则，每条动作自带依据）。只收紧不放宽；不产生动作
 * ＝ 空表（不是失败——体征都健康时最常见的就是空表）。
 *
 * 规则四条（与白名单一一对应，谁也不多）：①低电 ⇒ 并发度−1＋延后；②发烫/预算尽 ⇒ 延后；
 * ③存储/内存告急 ⇒ 批量减到下界；④p95 慢 ⇒ 重试冷却 ×2。
 */
internal fun insulaAdjust(current: BioTuning, vitals: BioVitals): List<TuningMove> {
    val all = lowPowerMoves(current, vitals) + listOfNotNull(
        deferMoveIfStressed(current, vitals),
        batchMoveIfCrowded(current, vitals),
        backoffMoveIfSlow(current, vitals),
    )
    // 同参数只保留第一条（低电与发烫同时命中时，延后重活只落一张动作，不重复记账）。
    return all.distinctBy { it.param }
}

/** 低电（未充电且低于线）⇒ 并发度−1（到下界为止）＋ 延后重活（四参数里的两格）。 */
private fun lowPowerMoves(current: BioTuning, vitals: BioVitals): List<TuningMove> {
    val low = !vitals.charging && vitals.batteryPct in 0 until BioVitals.LOW_BATTERY_PCT
    if (!low) return emptyList()
    val why = basisOf("电量 ${vitals.batteryPct}%（未充电）")
    val moves = mutableListOf<TuningMove>()
    if (current.concurrency > BioTuning.MIN_CONCURRENCY) {
        moves += TuningMove(
            TunableParam.CONCURRENCY,
            "${current.concurrency}",
            "${BioTuning.MIN_CONCURRENCY}",
            why,
        )
    }
    if (!current.deferHeavy) {
        moves += TuningMove(TunableParam.DEFER_HEAVY, "false", "true", why)
    }
    return moves
}

/** 发烫或当日预算耗尽 ⇒ 延后重活（重活等凉/等明天；已是延后档就不重复出动作）。 */
private fun deferMoveIfStressed(current: BioTuning, vitals: BioVitals): TuningMove? {
    if (current.deferHeavy) return null
    val why = when {
        vitals.hot -> "设备发烫（热档到限流档）"
        vitals.budgetLeft <= 0 -> "当日预算余量 ${vitals.budgetLeft}"
        else -> return null
    }
    return TuningMove(TunableParam.DEFER_HEAVY, "false", "true", basisOf(why))
}

/** 存储告急（余量≤5%）或堆压到线（≥0.9）⇒ 批量大小减到下界（已在下界就停在原地）。 */
private fun batchMoveIfCrowded(current: BioTuning, vitals: BioVitals): TuningMove? {
    if (current.batchSize <= BioTuning.MIN_BATCH_SIZE) return null
    val heapCrowded = vitals.heapPressure != null && vitals.heapPressure >= BioVitals.HEAP_CRITICAL_RATIO
    val storageCrowded = vitals.storageFreeRatio != null &&
        vitals.storageFreeRatio <= BioVitals.STORAGE_CRITICAL_RATIO
    if (!heapCrowded && !storageCrowded) return null
    val why = if (heapCrowded) "堆内存压力 ${percent(vitals.heapPressure!!)}" else "存储余量 ${percent(vitals.storageFreeRatio!!)}"
    return TuningMove(
        TunableParam.BATCH_SIZE,
        "${current.batchSize}",
        "${BioTuning.MIN_BATCH_SIZE}",
        basisOf(why),
    )
}

/** p95 边界耗时超线 ⇒ 重试冷却 ×2（封顶 2h；到顶就停在原地）。 */
private fun backoffMoveIfSlow(current: BioTuning, vitals: BioVitals): TuningMove? {
    if (vitals.actP95Ms == null || vitals.actP95Ms <= BioVitals.ACT_P95_SLOW_MS) return null
    if (current.retryBackoffMs >= BioTuning.MAX_RETRY_BACKOFF_MS) return null
    val doubled = (current.retryBackoffMs * 2).coerceAtMost(BioTuning.MAX_RETRY_BACKOFF_MS)
    return TuningMove(
        TunableParam.RETRY_BACKOFF,
        "${current.retryBackoffMs}ms",
        "${doubled}ms",
        basisOf("p95 边界耗时 ${vitals.actP95Ms}ms 超线"),
    )
}

/** 把现值套上动作（纯函数；应用后的新值——落账由 IO 层做）。 */
internal fun applyMoves(current: BioTuning, moves: List<TuningMove>): BioTuning {
    var t = current
    moves.forEach { m ->
        t = when (m.param) {
            TunableParam.CONCURRENCY -> t.copy(concurrency = m.to.toIntOrNull() ?: t.concurrency)
            TunableParam.BATCH_SIZE -> t.copy(batchSize = m.to.toIntOrNull() ?: t.batchSize)
            TunableParam.RETRY_BACKOFF -> t.copy(
                retryBackoffMs = m.to.removeSuffix("ms").toLongOrNull() ?: t.retryBackoffMs,
            )
            TunableParam.DEFER_HEAVY -> t.copy(deferHeavy = m.to == "true")
        }
    }
    return t
}

/** 依据行：落账"依据哪条读数"那一格（哪格读数说话，一句话）。 */
internal fun basisOf(why: String): String = "读数：$why"

private fun percent(ratio: Double): String = "${(ratio * 100).toInt()}%"
