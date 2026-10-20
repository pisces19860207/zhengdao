// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.rootfs

import java.util.concurrent.atomic.AtomicLong

/**
 * 解包阶段的「在动」读数（第六十五批 · 必做 #2）。
 *
 * 用户等安装时看不到任何长阶段的进展——原来的文案是「正在解压（没有详细进度，约 30 秒
 * 到几分钟）」，一整段静默。Rust 核心**本来就在回调** `CoreNative.onProgress`（每 200
 * 条目一次，JNI 静态方法），只是此前只 `Log.i` 了一下；Java 回退的逐条目回调
 * （[RootfsInstaller.extractArchiveJava] 的 onEntry）同样现成。本对象把这两路计数收成
 * 一份，节流后交给 [onTick]（由 `InstallFlow` 接到 Compose 状态行 + 通知栏）。
 *
 * 口径（派工单：阶段名 + 已解条目数 + 已用秒；**拿不到字节偏移就绝不编百分比**）：
 * - 阶段名固定为「解包系统层」；
 * - 条目数取 Rust 回调的累计值 / Java 回退的本地计数；
 * - 已用秒从 [start] 起算，向上取整到秒。
 *
 * 线程模型：Rust 回调来自解包线程；[onTick] 也在该线程执行（实现方负责切主线程或
 * 只更新 snapshot state）。节流到**每秒最多一次**，避免 1.8 万条目把 Compose 和通知栏
 * 刷爆。计时/回调任何异常都吞掉——进度不能反过来影响安装。
 */
object InstallPulse {

    /** 一次进度快照（纯数据，单测直接构造）。 */
    data class Snapshot(
        val entries: Long,
        val elapsedMs: Long,
        val currentName: String? = null,
    )

    /** 节流间隔：进度行是给人看的，1 Hz 足够。 */
    const val EMIT_INTERVAL_MS = 1_000L

    private const val EMIT_INTERVAL_NANOS = EMIT_INTERVAL_MS * 1_000_000L

    /** 由 `InstallFlow.start` 接上、`finish/fail` 摘下；null = 没人看，只计数不回调。 */
    @Volatile
    var onTick: ((Snapshot) -> Unit)? = null

    @Volatile
    private var running = false

    @Volatile
    private var startedAtNanos = 0L

    @Volatile
    private var lastEmitNanos = 0L

    /** 用显式标志而不是拿 `lastEmitNanos == 0` 判"从未发过"：假时钟也从 0 开始时会被误判。 */
    @Volatile
    private var hasEmitted = false

    @Volatile
    private var currentName: String? = null

    private val entries = AtomicLong(0L)

    /** 可注入时钟（JVM 单测用）。 */
    internal var nanoTime: () -> Long = System::nanoTime

    /** 解包开始：清零并开始计时。 */
    fun start() {
        entries.set(0L)
        currentName = null
        startedAtNanos = nanoTime()
        lastEmitNanos = 0L
        hasEmitted = false
        running = true
    }

    /**
     * 换一条解包通道时清零（Rust 失败 → Java 回退）：两边的条目计数不是同一口径，
     * 直接把 Rust 试错时的累计值继续往上加会显示一个虚高的数字。
     */
    fun reset() {
        entries.set(0L)
        currentName = null
        startedAtNanos = nanoTime()
        lastEmitNanos = 0L
        hasEmitted = false
    }

    /** 解包结束：停止接收回调（不清空最后读数，留给调用方换阶段文案）。 */
    fun stop() {
        running = false
    }

    /** Rust 回调入口（`CoreNative.onProgress` 转进来）；entries 是累计值的字符串。 */
    fun fromRust(entriesText: String, name: String) {
        if (!running) return
        entriesText.trim().toLongOrNull()?.let { entries.set(it) }
        if (name.isNotEmpty()) currentName = name
        emitIfDue()
    }

    /** Java 回退的逐条目回调。 */
    fun noteEntry(name: String) {
        if (!running) return
        entries.incrementAndGet()
        currentName = name
        emitIfDue()
    }

    private fun emitIfDue() {
        val now = nanoTime()
        if (hasEmitted && now - lastEmitNanos < EMIT_INTERVAL_NANOS) return
        hasEmitted = true
        lastEmitNanos = now
        val snap = Snapshot(
            entries = entries.get(),
            elapsedMs = ((now - startedAtNanos) / 1_000_000L).coerceAtLeast(0L),
            currentName = currentName,
        )
        runCatching { onTick?.invoke(snap) }
    }

    /**
     * 进度行（纯函数，单测直接断言）：
     * `正在解包系统层 · 已解 12345 条 · 已用 2 秒 · extract.rs`
     *
     * 文件名只取最后一段、最多 24 字符：它是"确实在动"的旁证，但不该把状态行挤爆。
     */
    fun line(snap: Snapshot): String {
        val seconds = snap.elapsedMs / 1_000L
        val shortName = snap.currentName
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() && it != "." }
            ?.take(24)
        return buildString {
            append("正在解包系统层 · 已解 ${snap.entries} 条 · 已用 $seconds 秒")
            if (shortName != null) append(" · $shortName")
        }
    }
}
