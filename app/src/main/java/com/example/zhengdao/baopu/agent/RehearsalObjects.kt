// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百五十八批 · 推演盘（军师）的**判定层**：跑法、配额、启动闸、报告形制。
//
// 为什么判定层单独成文件（与 `inspect/ShadowRehearsal.kt` 同一个分法）：
// 派单 §四判据⑥要「报告数字全部来自实测」——这句话只有在**判据不含 IO** 的前提下才可测；
// 把算术留在无盘文件里，六条判据才能走 JVM 直测，不必造一台手机来验一个除法。
//
// ## 本文件持有的四条口径（都在派单里，逐条对上）
// 1. **配额** `min(可用空间×5%, 100MB)`（§二②）：见 [RehearsalRules.quotaBytes]；
// 2. **超 8MB 只做元数据预演**（§一表第二行）：阈值 [RehearsalRules.METADATA_ONLY_BYTES]，
//    复制与不复制的分界在 `RehearsalSandbox`，本文件只管那个数；
// 3. **电量<20%／过热／无网 ⇒ 不启动**（§二③）：见 [RehearsalRules.blockOf]——
//    派单写死「发起前检查，不是中途停」，所以这一条只在开跑前问一次；
// 4. **报告 ≤8 行**（§一③桌面版形制，手机版沿用）：见 [RehearsalReport.lines]。
//
// ## 两个「不编数」的先例（本文件照抄它们的口径，不另立）
// · `RunTraceRules.UNKNOWN_DURATION_MS`：没测到就交 -1，不写 0 假装知道；
// · [RehearsalReport.riskiestStep]：卡点只认**实测跑挂的那一步**，没有就写「没有卡点」，
//   不拿先验猜一个步号充数。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.RiskClass
import java.io.File

/** 推演跑法：[FILE] 建副本真跑；[PLAN_ONLY] 只算计划层（不建副本、不真跑写侧）。 */
internal enum class RehearsalMode(val cn: String) {
    FILE("文件预演"),
    PLAN_ONLY("纯计划层预演"),
}

/** 一步在推演里**实际**是怎么跑的（报告里"只读真跑 N 步"这一格按它数）。 */
internal enum class StepKind(val cn: String) {
    READ_REAL("只读真跑"),
    WRITE_COPY("副本写"),
    METADATA("元数据预演"),
    PLAN("计划层"),
    UNSUPPORTED("不在推演覆盖"),
    NOT_RUN("没跑"),
}

/** 不启动的理由（派单 §二③）。取不到读数一律按**拦**算——不推演只是少一份报告，写坏盘是事故。 */
internal enum class StartBlock(val cn: String) {
    OK("可启动"),
    LOW_BATTERY("电量不足"),
    OVERHEAT("设备过热"),
    NO_NETWORK("无网络"),

    /**
     * 派单 §二③ 没点名的第四条，来自【隔离】④：同一任务的军师与影子**串行**，
     * 道被占时这一次推演**不叠上去跑**（并发跑＝两份副本混在同一棵树上）。
     */
    BUSY("同任务在跑"),
}

/**
 * 现场读数（探针取自装配层，判据留在这里）。
 *
 * 三格全部由**官方 API** 取：电量 `BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)`、
 * 热状态 `PowerManager.getCurrentThermalStatus()`、网络 `ConnectivityManager`。
 * 没有走电池温度（`EXTRA_TEMPERATURE` 只能从 sticky 广播里拿，那个调用在 targetSdk 34+
 * 会撞 `UnspecifiedRegisterReceiverFlag` 那条硬墙 lint）——热状态这一路在本 App 的
 * minSdk 36 上恒可用，不需要第二条腿。
 *
 * @param batteryPercent 0–100；取不到交 -1（按 [StartBlock.LOW_BATTERY] 算）
 * @param thermalStatus  `PowerManager.getCurrentThermalStatus()` 的原值；取不到交 -1
 * @param hasNetwork     取不到按 false（拦）
 */
internal data class EnvReading(
    val batteryPercent: Int,
    val thermalStatus: Int,
    val hasNetwork: Boolean,
)

/** 元数据预演取的那三格（>8MB 的对象只读这些＋首尾抽样，不复制）。 */
internal data class MetaReading(
    val path: String,
    val exists: Boolean,
    val bytes: Long,
    val writable: Boolean,
    val headBytes: Int,
    val tailBytes: Int,
) {
    /** 一行短形，进[StepResult.note]（抽样字节是实测出来的，不是"约"）。 */
    fun brief(): String = if (!exists) "$path（不存在，将新建）" else {
        val kb = bytes / 1024L
        "$path（${kb}KB · ${if (writable) "可写" else "不可写"} · 抽样首$headBytes+尾$tailBytes B）"
    }
}

/** 判据算术（无盘、无 Android 依赖）。 */
internal object RehearsalRules {

    /** 配额上限（派单 §二②：`min(可用×5%, 100MB)`）。 */
    const val QUOTA_CAP_BYTES: Long = 100L * 1024L * 1024L

    /** 配额比例（5%）。用「除以 20」而不是「乘 5 再除 100」：可用空间是 Long，先乘会溢出。 */
    const val QUOTA_DIVISOR: Long = 20L

    /** 单对象超过这个大小就不复制（派单 §一表：默认 8MB）。 */
    const val METADATA_ONLY_BYTES: Long = 8L * 1024L * 1024L

    /** 启动闸的电量下限（派单 §二③：20%）。 */
    const val MIN_BATTERY_PERCENT: Int = 20

    /**
     * 热状态分界：`THERMAL_STATUS_SEVERE`。
     * 值是**实测**自本机 `Android\Sdk\platforms\android-36\android.jar`
     * （`javap -constants android.os.PowerManager`：NONE=0 LIGHT=1 MODERATE=2 SEVERE=3
     * CRITICAL=4 EMERGENCY=5 SHUTDOWN=6），不是凭记忆写的数。
     */
    const val THERMAL_STATUS_SEVERE: Int = 3

    /** 元数据预演的抽样长度（首尾各读这么多字节）。 */
    const val SAMPLE_BYTES: Int = 4 * 1024

    /** 报告里路径清单最多列几条（多了折成「…另 N 条」，行宽不许顶爆卡片）。 */
    const val MAX_PATHS_IN_REPORT: Int = 6

    /**
     * 副本配额：`min(可用空间 × 5%, 100MB)`；可用空间读数缺失（≤0）⇒ 0，即直接降级。
     *
     * 为什么 0 也照样出报告而不是报错：派单 §二②写的是「不足 ⇒ 降级为纯计划层预演并明说」，
     * 降级是**正常出口**，不是失败。
     */
    fun quotaBytes(availableBytes: Long): Long =
        if (availableBytes <= 0L) 0L else minOf(availableBytes / QUOTA_DIVISOR, QUOTA_CAP_BYTES)

    /** 配额够不够再放 [needBytes] 进去（等于也算够：边界不该多一次无谓降级）。 */
    fun fits(usedBytes: Long, quotaBytes: Long, needBytes: Long): Boolean =
        usedBytes + needBytes <= quotaBytes

    /**
     * 启动闸（派单 §二③：发起前检查一次，不是中途停）。
     *
     * 三条并列按**电量→热→网**的固定顺序取第一个拦的：报告只说得出一个理由，
     * 顺序不定就会出现同读数两次报告不同理由——不可复现。
     *
     * 读数取不到（-1）一律算**拦**：热状态缺格也按过热收——少一份报告不亏，
     * 在没读数的前提下照跑才是事故。
     */
    fun blockOf(env: EnvReading): StartBlock = when {
        env.batteryPercent < MIN_BATTERY_PERCENT -> StartBlock.LOW_BATTERY
        env.thermalStatus < 0 || env.thermalStatus >= THERMAL_STATUS_SEVERE -> StartBlock.OVERHEAT
        !env.hasNetwork -> StartBlock.NO_NETWORK
        else -> StartBlock.OK
    }

    /** 拦截理由的一行白话（数字是探针取的实测值，取不到直说取不到）。 */
    fun blockNote(env: EnvReading, block: StartBlock): String = when (block) {
        StartBlock.OK -> ""
        StartBlock.LOW_BATTERY -> if (env.batteryPercent < 0) {
            "电量读数取不到 ⇒ 按不启动算（宁可少一份报告，不在低电上赌）"
        } else {
            "电量 ${env.batteryPercent}% < $MIN_BATTERY_PERCENT%"
        }
        StartBlock.OVERHEAT -> if (env.thermalStatus < 0) {
            "热状态读数取不到 ⇒ 按过热算（同一句保守话只说一次：拿不准就不跑）"
        } else {
            "热状态 $THERMAL_STATUS_SEVERE(SEVERE) 及以上：当前 ${env.thermalStatus}"
        }
        StartBlock.NO_NETWORK -> "网络不通（探针取不到也算不通）"
        // 道被占的理由由串行道自己交回（[RehearsalLane.waitNote]），这里不另写一份文案。
        StartBlock.BUSY -> ""
    }

    /** 相对写法归一：与 `FileShadowSandbox` 同口径（反斜杠折成正斜杠、去掉 `./`、小写）。 */
    fun relKey(p: String): String =
        p.replace('\\', '/').trim().removePrefix("./").trimEnd('/').lowercase()
}

/** 推演输入：一次任务的一串待跑动作（`ToolCall` 是全仓原语，不另立第二步模型）。 */
internal data class RehearsalPlan(
    /** 会话内那次跑的 id（`RunTraceRules.newRunId` 那种写法）；副本目录名按它加 `rh-` 前缀。 */
    val runId: String,
    /** 同任务串行的键（军师与影子共用一根道，见 `RehearsalIsolation`）。 */
    val taskKey: String,
    val workspaceRoot: File,
    val steps: List<RehearsalStep>,
)

/** 计划里的一步。 */
internal data class RehearsalStep(
    val n: Int,
    val call: ToolCall,
    /** 这一步声明要碰的对象（相对工作区写法）；读侧＝要真读的目标，写侧＝要复制进副本的目标。 */
    val targets: List<String>,
)

/** 一步的实测结果。 */
internal data class StepResult(
    val n: Int,
    val tool: String,
    val kind: StepKind,
    /** 风险档取自生产表 `RiskMatrix`（不是推演自己给的估计）。 */
    val risk: RiskClass?,
    val ok: Boolean,
    val note: String,
    /** 实测耗时；没测到交 [RunTraceRules.UNKNOWN_DURATION_MS]，不写 0。 */
    val ms: Long,
    val paths: List<String>,
)

/**
 * 预演报告（派单 §一③：会改几个文件／耗时／第几步最可能卡／成本／路径清单）。
 *
 * 每一格的来源都是实测：步数＝真跑过＋判过的步数，文件数＝副本树 diff 出来的路径条数，
 * 耗时＝开跑到收尾的系统钟差，清理量＝删之前逐棵树称出来的字节。
 */
internal data class RehearsalReport(
    val runId: String,
    val mode: RehearsalMode,
    val block: StartBlock,
    val blockNote: String,
    val steps: List<StepResult>,
    /** 实测影响面（见 [filesToChange] 的口径说明）。 */
    val measuredPaths: List<String>,
    val cancelled: Boolean,
    val degradeNote: String,
    val realUntouched: Boolean,
    val copyBytesPeak: Long,
    val cleanedBytes: Long,
    val elapsedMs: Long,
) {

    /** 只读真跑的步数。 */
    val readSteps: Int get() = steps.count { it.kind == StepKind.READ_REAL }

    /** 在副本里真写过的步数。 */
    val writeSteps: Int get() = steps.count { it.kind == StepKind.WRITE_COPY }

    /** 只做元数据预演的步数（>8MB，派单判据③）。 */
    val metaSteps: Int get() = steps.count { it.kind == StepKind.METADATA }

    /**
     * 会改几个文件＝影响面清单去重后的长度（派单 §一③ 那一格）。
     *
     * 清单的来源是**副本树跑前跑后的 diff** ＋「超阈值只做元数据」那几条声明对象——
     * 后者必须补进去：>8MB 的文件压根没复制进副本，只看副本 diff 就把"真实跑会改它"漏掉了
     * （判据③与判据⑥在这儿交汇）。
     *
     * 口径只有这一份：正文与 [listedPaths] 同取 [measuredPaths]，不会出现"行里一个数、清单另一个数"。
     */
    val filesToChange: Int get() = listedPaths.size

    /** 影响面清单（归一成相对写法再去重），报告最后一行点它的头几条。 */
    val listedPaths: List<String> get() = measuredPaths.map { RehearsalRules.relKey(it) }.distinct()

    /** 推演覆盖不到的步（报告里直说，不混进"跑通了"）。 */
    val unsupportedSteps: Int get() = steps.count { it.kind == StepKind.UNSUPPORTED }

    /**
     * 第 K 步最可能卡：**实测**跑挂的第一步。
     *
     * 没有跑挂就交 null（报告写「没有卡点」）。派单硬规矩 3「数字必须来自实测，不许估」——
     * 先验挑一步出来当"最可能卡"就是估，所以这里只做"真挂了的"这一件事。
     */
    val riskiestStep: StepResult? get() = steps.firstOrNull { !it.ok && it.kind != StepKind.NOT_RUN }

    /** 是不是压根没启动（三条启动闸任一拦下）。 */
    val blocked: Boolean get() = block != StartBlock.OK

    /** 降级成了纯计划层（配额不足或压根没配额）。 */
    val degraded: Boolean get() = mode == RehearsalMode.PLAN_ONLY && !blocked

    // 两格小数按 `Locale.ROOT` 定死：报告里"3.5 秒 / 12.00MB"是要对着实测核的数，
    // 跟系统区域走会在用逗号做小数点的地区写成"3,5"（写法沿用本仓 ArchiveTools 等四处先例）。
    private fun seconds(ms: Long): String = if (ms < 0L) "未测到" else String.format(java.util.Locale.ROOT, "%.1f", ms / 1000.0)

    private fun mb(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.2fMB", bytes / (1024.0 * 1024.0))

    /**
     * 报告正文，**最多 8 行**（派单 §一③的形制；判据测试逐条数行数）。
     *
     * 取消那一行只在真取消时出现；没启动时第 1 行直接换成拦截理由（后面不再铺开）。
     */
    fun lines(): List<String> {
        if (blocked) {
            return listOf(
                "推演未启动（$runId）：${block.cn}——$blockNote",
                "计划 ${steps.size} 步一步没跑：以上按启动闸拦下，不占用配额、不建副本",
            )
        }
        val head = StringBuilder("推演 $runId：${mode.cn}")
        if (degraded) head.append("——未做文件预演（$degradeNote）")
        val tally = "会改 $filesToChange 个文件 · 只读真跑 $readSteps 步 · 副本写 $writeSteps 步" +
            metaTail()
        val risk = riskiestStep
        val notRun = steps.count { it.kind == StepKind.NOT_RUN }
        val riskLine = if (risk == null) {
            "没有卡点（${steps.size - notRun} 步跑通${uncoveredTail(notRun)}）"
        } else {
            "第 ${risk.n} 步最可能卡：${risk.tool}——${risk.note}"
        }
        val out = mutableListOf(
            head.toString(),
            tally,
            "实测耗时 ${seconds(elapsedMs)} 秒（${elapsedMs}ms，未含等确认的时间）",
            riskLine,
            "成本：副本峰值 ${mb(copyBytesPeak)} · 真盘零写入 ${if (realUntouched) "已核对" else "核对不过"}",
            "清理了 ${mb(cleanedBytes)}（副本目录已删除）",
            pathLine(),
        )
        if (cancelled) out.add("已取消：中途收到取消，副本已清空")
        return out
    }

    /** >8MB 那一格只在有的时候说，没有就不占一行（八行是上限，不是要凑满）。 */
    private fun metaTail(): String = if (metaSteps == 0) "" else " · 元数据预演 $metaSteps 步（未复制）"

    /**
     * 「没验成」那一半要说清是**覆盖不到**还是**没跑到**：前者是推演的边界，后者是取消／拦停的现场，
     * 对用户是两回事，混成一句"全部跑通"就是把没跑的都说成跑过的。
     */
    private fun uncoveredTail(notRun: Int): String {
        val parts = mutableListOf<String>()
        if (unsupportedSteps > 0) parts.add("另有 $unsupportedSteps 步不在覆盖内")
        if (notRun > 0) parts.add("另 $notRun 步没跑到")
        return if (parts.isEmpty()) "" else "，" + parts.joinToString("、")
    }

    private fun pathLine(): String {
        val shown = listedPaths.take(RehearsalRules.MAX_PATHS_IN_REPORT).joinToString("、")
        if (listedPaths.isEmpty()) return "涉及路径：无"
        val rest = listedPaths.size - shown.split("、").size
        // 降级那一趟没有副本可 diff，清单只能来自计划声明——这一格来路必须写在脸上。
        val from = if (degraded) "（清单来自计划声明）" else ""
        return "涉及路径：$shown" + (if (rest > 0) "（另 $rest 条）" else "") + from
    }
}
