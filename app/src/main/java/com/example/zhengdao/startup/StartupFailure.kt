// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.startup

/**
 * 一次「抱朴装配失败」的现场（纯数据、无 Context、无 Android 依赖 ⇒ JVM 单测直接造）。
 *
 * ## 为什么单独一间
 *
 * 装配失败要同时喂三个消费方：① 错误页的标题（只要类型名）② 诊断文件正文（要栈＋版本＋
 * 机型）③ 运行日志的一行（只要类型与消息）。三处共用同一份现场，就不会出现「页面说
 * IllegalStateException、文件里写 NullPointerException」这种对不上的账。
 *
 * @param type 异常类型的**短名**（页面标题用它；匿名类没有短名时退回全名）
 * @param typeFull 全名（诊断文件里用；排障时要能一眼看出是哪个包的类）
 * @param message 原始消息（可能含敏感串 ⇒ 落盘前一定过脱敏，见 [StartupDiagnostics.body]）
 * @param stack 栈帧逐行，首行是 `类型: 消息`，**已截断**（见 [MAX_STACK_LINES]）
 * @param atMillis 失败时刻（毫秒；诊断文件名与"上次成功启动"都按它算）
 */
internal data class StartupFailure(
    val type: String,
    val typeFull: String,
    val message: String?,
    val stack: List<String>,
    val atMillis: Long,
) {
    companion object {

        /**
         * 栈最多留多少行。
         *
         * 为什么要有上限：`stackTraceToString()` 会把整条 cause 链都展开，装配失败的现场
         * （Kotlin 默认参数 + 闭包 + 反射）一次能出 100+ 行；诊断文件是给人看的，不是 dump
         * 二进制。60 行足够看清前几层真实调用点，再多就是框架内部的循环帧。
         */
        const val MAX_STACK_LINES = 60

        /** 从异常拍出现场；[atMillis] 由调用方给（时钟统一经注入的 `now()`，方便单测钉住）。 */
        fun of(t: Throwable, atMillis: Long): StartupFailure = StartupFailure(
            type = t.javaClass.simpleName.ifBlank { t.javaClass.name },
            typeFull = t.javaClass.name,
            message = t.message,
            stack = t.stackTraceToString().lineSequence().take(MAX_STACK_LINES).toList(),
            atMillis = atMillis,
        )
    }
}

/**
 * 诊断文件需要的"这台手机/这一版"的静态事实（同样纯数据）。
 *
 * 字段是**白名单**：这里没有的，诊断文件里就不会有。会话正文、账本内容、密钥、工作区
 * 文件清单都不在这张表上 —— 判据「不含密钥/正文」靠的就是"根本没这个入参"，而不是
 * 靠事后正则去猜哪些像隐私。
 */
internal data class StartupFacts(
    val versionName: String,
    val gitSha: String,
    val buildTime: String,
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    /** 上一次装配成功的时刻；从没成功过 ⇒ null（首次安装就坏的情况）。 */
    val lastOkAtMillis: Long?,
    val lastOkVersion: String?,
    val nowMillis: Long,
)

/**
 * 启动装配的状态机取值（闭集四格）。
 *
 * 为什么是闭集：`Idle / Ready / Failed / SafeMode` 之外的第五种状态不存在 —— 尤其**没有**
 * "失败但当作成功继续"这一格。629 的第一条判据是「失败不许静默降级」，闭集是把这条
 * 判据变成类型：想降级得先编一个新状态出来，编不出来就写不出那段代码。
 */
internal sealed interface StartupState {

    /** 还没装过（Application 之前；正常到不了界面）。 */
    data object Idle : StartupState

    /** 装配成功。 */
    data object Ready : StartupState

    /** 装配失败 —— **只能进错误页**，不许进主界面。 */
    data class Failed(val failure: StartupFailure) : StartupState

    /** 用户选了"以安全模式继续"：抱朴不开，只留设置与日志。 */
    data class SafeMode(val failure: StartupFailure?) : StartupState
}

/** 界面该画哪一屏（纯函数，判据①"错误页出现／不进主界面"在这一层可测）。 */
internal enum class StartupRoute { Main, Failure, SafeMode }

/** 状态 ⇒ 该画的屏。除 [StartupState.Failed] 与 [StartupState.SafeMode] 外都进主界面。 */
internal fun routeOf(state: StartupState): StartupRoute = when (state) {
    StartupState.Idle, StartupState.Ready -> StartupRoute.Main
    is StartupState.Failed -> StartupRoute.Failure
    is StartupState.SafeMode -> StartupRoute.SafeMode
}

/**
 * 启动账行（运行日志里那两行，判据⑤的落点）。
 *
 * **成功行与失败行互斥且都只由 [com.example.zhengdao.startup.StartupGate] 的装配函数写**：
 * 成功行在 `try` 里、写在引擎真的拿到之后；失败行走 `catch`。所以「失败路径写完成行」
 * 在这份代码里不是"小心一点"的事，而是结构上写不出来 —— 单测 `StartupLedgerLineTest`
 * 盯的就是失败行**不含**"完成/成功"这几个字。
 */
internal object StartupLedgerLine {

    /** 装配成功的账行。 */
    fun ok(): String = "启动：抱朴装配完成"

    /** 装配失败的账行（不许出现"完成/成功"——失败就是失败）。 */
    fun failed(failure: StartupFailure): String =
        "启动：抱朴装配失败（${failure.type}）——主界面未打开，已进错误页"
}
