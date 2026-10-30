// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十批 · 斥候（Capability Scout）的词表。
//
// 用户 2026-10-30：「APP 一装好打开后，斥候提前探一遍手机哪些技能可以直接使用、哪些需要权限、
// 哪些有限制…交给四器，四器再给宰相，用户发第一条信息就回灌给模型」。
//
// 三条边界（写在词表这一层，因为它们是**类型**的事，不是自觉）：
//   ① **只探能力，不读内容**：这里的每一项都只回答"这台手机能不能做"，没有一项需要读用户数据；
//   ② **四态是闭集**：`granted／revoked／denied／unavailable`——不给"大概能用"这种第五态；
//   ③ **"不可用"必须带原因**：[ScoutRecord.reason] 为空是构造错误（见 `require`），
//      因为"不可用"不带原因就等于"不知道"，而"不知道"不许冒充成"可以做"。
package com.example.zhengdao.baopu.scout

/**
 * 探测项闭集（派工单第 1 条点名的那一串 ＋ 耳机）。
 *
 * 顺序就是清单里的稳定顺序：**改顺序等于改账本的人读形态**，所以顺序写死在这里，
 * 别在渲染层再排一遍（两处排序必打架）。
 */
internal enum class Capability(val wire: String, val cn: String) {
    STORAGE("storage", "存储"),
    CAMERA("camera", "相机"),
    NOTIFICATIONS("notifications", "通知"),
    ACCESSIBILITY("accessibility", "无障碍"),
    LOCATION("location", "位置"),
    CLIPBOARD("clipboard", "剪贴板"),
    NETWORK("network", "网络"),
    BATTERY_OPTIMIZATION("battery_optimization", "后台保活"),
    HEADPHONES("headphones", "耳机"),
    ROOT("root", "root"),
    VIRTUALIZATION("virtualization", "虚拟化"),
    ;

    companion object {
        /** 认不出就 null（账本里的自造值不渲染，也不静默当别的项——照 208/影子账的解析纪律）。 */
        fun ofWire(wire: String): Capability? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 四态闭集（派工单「扩展为四态（不是三态）」）。
 *
 * - [GRANTED] 已给：直接用，不提；
 * - [REVOKED] **曾给过、后来被收回**（系统回收/用户撤销）⇒ 值得提**一次**；
 * - [DENIED] 用户明确驳回（[ScoutRecord.denials] 记次数；0 ＝ 还没问过）⇒ 1–2 次不主动提，
 *   3 次后不再主动提（但用户主动问照答）；
 * - [UNAVAILABLE] 本机没有/系统不支持/探测失败 ⇒ 一句话说明原因，落账不再问。
 *
 * `DENIED.denials == 0` 的含义是"还没问过"（装好就没开过），措辞与"问过但没开"分开：
 * 前者可以主动问一次，后者按次数走。这是"盲点驳回"要防的另一半——**没问过不等于拒绝过**。
 */
internal enum class ScoutState(val wire: String) {
    GRANTED("granted"),
    REVOKED("revoked"),
    DENIED("denied"),
    UNAVAILABLE("unavailable"),
    ;

    companion object {
        fun ofWire(wire: String): ScoutState? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 探针的**原始读数**（Android 层只产这个，判据层只认这个）。
 *
 * 把"读数"与"结论"分开，是为了让四态推导能 JVM 直测：真机怎么读是 Android 层的事，
 * 读出来之后怎么解释（尤其 granted→revoked 这种要历史才判得出的）是纯函数。
 */
internal sealed class ProbeSignal {
    /** 现在就能用；[note] 用来写"能用但有限制"（例如剪贴板只在前台可读）。 */
    data class Granted(val note: String = "") : ProbeSignal()

    /** 现在不能用（权限没给、开关没开、设备状态不满足）；[reason] 是人话原因。 */
    data class NotGranted(val reason: String = "") : ProbeSignal()

    /** 本机没有 / 系统不支持；[reason] 必填（"不可用"不带原因就是不知道）。 */
    data class Unsupported(val reason: String) : ProbeSignal()

    /** 探针自己失败了（抛异常）。保守归到"不可用"，绝不悄悄当成"可以做"。 */
    data class Failed(val reason: String) : ProbeSignal()
}

/**
 * 一项能力的当前结论（账本一行就是它的快照）。
 *
 * **为什么账本行存快照而不是增量**：账本是追加式 JSONL，读侧按能力取"最后一行"就是当前值；
 * 存绝对值（次数、是否提过）就不会因为"只读最后 2000 行"而把次数丢掉。
 */
internal data class ScoutRecord(
    val capability: Capability,
    val state: ScoutState,
    /** 驳回次数（只有 [ScoutState.DENIED] 有意义；0 ＝ 还没问过）。 */
    val denials: Int = 0,
    /** 最近一次判定/驳回的时间（epoch ms）。 */
    val lastAtMs: Long = 0L,
    /** 当时用户想做的事（驳回记忆的第 4 列；探测时原样保留上次的值）。 */
    val lastIntent: String = "",
    /** 不可用的原因（[ScoutState.UNAVAILABLE] 必填）。 */
    val reason: String = "",
    /** 能用但有限制时的那句限制（例如「只在前台可用」）。 */
    val note: String = "",
    /** `revoked` 是否已经"提过一次"（提过就不再主动提，见 290 判据⑤）。 */
    val revokedMentioned: Boolean = false,
    /** 探测时的机型/系统版本（287 追加：失败结论要带机型与系统版本）。 */
    val device: String = "",
) {
    init {
        require(denials >= 0) { "denials must not be negative" }
        require(state != ScoutState.UNAVAILABLE || reason.isNotBlank()) {
            "unavailable must carry a reason"
        }
    }

    /** 这项能力现在**用不了**（三种否定态）——清单与意见都靠它分流。 */
    val blocked: Boolean get() = state != ScoutState.GRANTED

    /** 账本里的稳定引用（证据行用它，别在别处拼字符串）。 */
    fun ref(): String = "scout:${capability.wire}:${state.wire}:$lastAtMs"
}

/** 一台设备的指纹（判断"App 版本变／系统升级"用；不含任何用户数据）。 */
internal data class ScoutDevice(
    val model: String,
    val release: String,
    val sdkInt: Int,
    val versionName: String,
    val versionCode: Long,
) {
    /** 账本与人话用的短标识（`PGT-AN10/16`）。 */
    fun tag(): String = "$model/$release"
}

/** 何时探（派工单第 2 条：首启、再探一遍、权限变更、App 或系统版本变化）。 */
internal enum class ScoutTrigger(val wire: String) {
    FIRST_RUN("first_run"),
    MANUAL("manual"),
    PERMISSION_CHANGED("permission_changed"),
    APP_VERSION("app_version"),
    OS_VERSION("os_version"),
}

/** 斥候的常量（上限写在判据层，渲染层不再截一次——两处口径必打架）。 */
internal object ScoutLimits {
    /** 一页能力清单的**总行数**上限（含标题；派工单判据②）。 */
    const val MAX_SHEET_LINES = 10

    /** 首启探测的时间预算（判据①）。超了要能在日志里看见，不许悄悄拖着。 */
    const val MAX_PROBE_MS = 2_000L

    /** 工作区账的读侧上界（照勘验层 READ_LIMIT 的口径；读不全就按最后看到的算，不扫全库）。 */
    const val READ_LIMIT = 2_000

    /** 账本文件名（落在 `<工作区>/记忆/`，随工作区走、重装不丢）。 */
    const val LEDGER_FILE = "斥候账.jsonl"

    /** 一页能力清单的落点（同一棵 `记忆/`）。 */
    const val SHEET_FILE = "能力清单.md"

    /** 未办成单的落点。 */
    const val UNFINISHED_FILE = "未办成单.jsonl"
}
