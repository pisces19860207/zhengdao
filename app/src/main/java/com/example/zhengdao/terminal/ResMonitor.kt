// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

import com.example.zhengdao.R
import com.example.zhengdao.rootfs.RunLog
import java.io.File

/**
 * 资源监控（v1.2 阶段 3.2）：量抱朴 serve + PRoot guest 的内存与 CPU，超阈值告警。
 *
 * ### 为什么只量自己 UID 的进程
 * Android 的 `/proc` 对**其他应用**的进程不可读（Android 10+ 限制了 /proc 可见性），
 * 但**自己 UID** 的进程完整可读。抱朴的 opencode serve 是 App 自己 fork 的（宿主
 * bionic，见故障排查手册坑 #0），PRoot guest 及其子进程（bash / tmux / node…）也都在
 * 同一 UID 下 ⇒ 按 UID 聚合就是"证道占了多少"，既不漏也不越界。
 *
 * ### 为什么 CPU 要采两次
 * `/proc/<pid>/stat` 的 utime/stime 是**累计**滴答数，单次读数算不出 CPU%。
 * 必须前后采两次、用差值除以窗口。窗口由调用方给定（默认 700 ms）——
 * 这是"体检要秒级完成"与"窗口太短则噪声大"之间的折中。
 *
 * ### ⚠️ 为什么要把 App 自身进程排除掉（真机实测所得，2026-10-07）
 * 采样是**按需**触发的——用户展开体检卡时量一次。而这时 `EnvHealth.inspect()` 自己
 * 正在做密集工作（扫 rootfs 文件、DNS 探测）+ Compose 重排，全都算在本 UID 头上。
 * 实测：本 UID 只有 App 一个进程、`top` 显示 **0.0%**，体检卡却稳定报 **80–90%**。
 * 也就是说，量到的是"体检自己"，不是"抱朴/终端吃了多少" ⇒ 阈值形同虚设（真出问题
 * 的 30% 淹在体检噪声的 80% 里）。
 * ⇒ 聚合时**剔除 `myPid()`**，只量抱朴 serve 与 PRoot guest 及其子进程。
 * 副作用正好是对的：只有 App 在跑时无进程可采 → 走"未运行"结论，不告警。
 *
 * ### 不做常驻轮询
 * 常驻采样线程本身就要吃 CPU，用来"监控 CPU 占用"很讽刺。改为**按需采样**：
 * 用户展开体检卡时才量一次。真要看趋势，看 RunLog 里超阈值的告警记录。
 */
object ResMonitor {

    /** 一次采样结果。 */
    data class Sample(
        /** 聚合 RSS（MB）。 */
        val rssMb: Int,
        /** 聚合 CPU 占用（%，多核可超 100，与 `top` 口径一致）。 */
        val cpuPct: Float,
        /** 参与统计的进程数。 */
        val procs: Int,
    )

    /** 体检卡口径的内存告警阈值；取值依据与"为什么与通知栏那个阈值不同"见 [RssThresholds]。
     *  真机基线（2026-10-07）抱朴 serve 空闲 RSS 375–468 MB、CPU 5.6–7.4%；
     *  阈值须明显高于基线，否则一开抱朴就告警 = 狼来了。 */
    const val RSS_WARN_MB = RssThresholds.HEALTH_CARD_MB
    const val CPU_WARN_PCT = 25f

    /** 体检结论：ok=false 表示故障，warn=true 表示超阈值但无一键修复。 */
    data class Verdict(val ok: Boolean, val warn: Boolean, val detail: String)

    /**
     * 采样。IO 线程调用。
     *
     * @param windowMs 两次读数的间隔；越长越准，但也越慢。
     * @return 聚合结果；无进程可采（抱朴与终端都没开）时返回 null。
     */
    fun measure(windowMs: Long = 700): Sample? {
        // 硬约束（审计 B4）：本函数 sleep + 全量扫 /proc，在主线程调用会掉帧甚至 ANR。
        BackgroundOnly.check("ResMonitor.measure")
        val uid = android.os.Process.myUid()
        // 剔除 App 自身：否则量到的是体检过程本身（见类注释，实测 0.0% → 报 90%）。
        val pids = excludeSelf(pidsOfUid(uid), android.os.Process.myPid())
        if (pids.isEmpty()) return null
        // P1-5（第五十三批）：两侧都收成 pid → 累计滴答 的映射，再按交集 pid 求差。
        // 原先两个 List 用 `take(min)` 按**下标**对齐：窗口内进程退出/新增就错配，
        // 而末尾 `coerceAtLeast(0)` 又把错配的负值压成 0% —— 既量不准，也不告警。
        val t0 = pids.mapNotNull { pid -> ticksOf(pid)?.let { pid to it } }.toMap()
        val rss = pids.mapNotNull { rssKbOf(it) }.sum()
        if (t0.isEmpty()) return null
        runCatching { Thread.sleep(windowMs) }
        val t1 = pids.mapNotNull { pid -> ticksOf(pid)?.let { pid to it } }.toMap()
        if (t1.isEmpty()) return null
        val delta = cpuDelta(t0, t1)
        // 滴答单位是 1/100 秒：窗口内可用滴答 = windowMs/10
        val cpu = delta.toFloat() * 1000f / windowMs.toFloat()
        return Sample(
            rssMb = (rss / 1024).toInt(),
            cpuPct = cpu,
            procs = pids.size,
        )
    }

    /**
     * 纯函数（可单测）：按 **pid 交集** 算累计滴答差。
     *
     * 只对两侧都存在的 pid 求差，逐 pid `coerceAtLeast(0)` 后再求和：
     * - 窗口内退出的进程：t1 里没有 ⇒ 没有可比基线，跳过；
     * - 窗口内新增的进程：t0 里没有 ⇒ 跳过（它的累计值不是这段窗口的 CPU）；
     * - pid 复用 / 计数回绕：该 pid 的差为负 ⇒ 记 0，不拖累其它 pid。
     */
    internal fun cpuDelta(t0: Map<Int, Long>, t1: Map<Int, Long>): Long {
        var sum = 0L
        for ((pid, t1Ticks) in t1) {
            val t0Ticks = t0[pid] ?: continue
            sum += (t1Ticks - t0Ticks).coerceAtLeast(0L)
        }
        return sum
    }

    /**
     * 纯函数（可单测）：把采样值翻译成体检结论。
     *
     * **无进程可采不算故障**——抱朴和终端都没开时占用本来就是 0，报红会违反
     * 体检卡「红 = 修得了」的不变量（资源占用没有一键修复）。
     * 超阈值走 **warn**（黄色 ⚠），不是红叉。
     *
     * [res] 传 `ctx.resources` 即随界面语言（detail 进 EnvHealth 体检表）；传 null（单测）回退中文。
     */
    internal fun verdict(s: Sample?, res: android.content.res.Resources? = null): Verdict {
        fun s2(id: Int, vararg args: Any): String =
            res?.getString(id, *args) ?: resFallback(id, *args)
        return when {
            s == null -> Verdict(true, false, s2(R.string.resmon_idle))
            s.rssMb >= RSS_WARN_MB -> Verdict(
                true, true,
                resPlural(
                    res, R.plurals.resmon_rss_over, s.procs,
                    "⚠ 内存 %1\$d MB 超阈值 %2\$d MB（%3\$d 个进程）——关掉不用的会话，或重启抱朴",
                    s.rssMb, RSS_WARN_MB, s.procs,
                ),
            )
            s.cpuPct >= CPU_WARN_PCT -> Verdict(
                true, true,
                s2(R.string.resmon_cpu_over, "%.1f".format(s.cpuPct), CPU_WARN_PCT.toInt()),
            )
            else -> Verdict(
                true, false,
                resPlural(
                    res, R.plurals.resmon_normal, s.procs,
                    "RSS %1\$d MB · CPU %2\$s%% · %3\$d 个进程",
                    s.rssMb, "%.1f".format(s.cpuPct), s.procs,
                ),
            )
        }
    }

    /**
     * plurals 版兜底（134 批 lint PluralsCandidate）：有资源时按 quantity 取形态，
     * 无资源的 JVM 单测回退 [fallback] 中文原文；两者都用同一套 `%1$d/%1$s` 占位替换。
     */
    private fun resPlural(
        res: android.content.res.Resources?,
        id: Int,
        quantity: Int,
        fallback: String,
        vararg args: Any,
    ): String {
        var out = res?.getQuantityString(id, quantity) ?: fallback
        args.forEachIndexed { i, a ->
            out = out.replace("%${i + 1}\$d", a.toString()).replace("%${i + 1}\$s", a.toString())
        }
        return out
    }

    /** JVM 单测的中文兜底（无 Android 运行时取不到资源；与 resmon_* 资源条目一一对应）。 */
    private fun resFallback(id: Int, vararg args: Any): String {
        val zh = mapOf(
            R.string.resmon_idle to "抱朴与终端均未运行，无进程可采样",
            R.string.resmon_cpu_over to "⚠ CPU %1\$s%% 超阈值 %2\$d%%——检查插件与会话规模",
        )
        var out = zh[id] ?: ""
        args.forEachIndexed { i, a ->
            out = out.replace("%${i + 1}\$d", a.toString()).replace("%${i + 1}\$s", a.toString())
        }
        return out
    }

    /**
     * 可测核心：从 UID 进程表里剔除 App 自身 pid。
     *
     * 不剔除的话，采样窗口正好覆盖 `EnvHealth.inspect()` 自己的密集工作，
     * 量出来的是"体检的开销"而非"抱朴/终端的占用"（真机：top 0.0% 但报 80–90%）。
     */
    internal fun excludeSelf(pids: List<Int>, selfPid: Int): List<Int> =
        pids.filter { it != selfPid }

    /** 采样 + 告警落日志（超阈值时写一条 RunLog，便于事后查趋势）。[res] 供 verdict 随界面语言。 */
    fun sampleWithLog(windowMs: Long = 700, res: android.content.res.Resources? = null): Verdict {
        val s = runCatching { measure(windowMs) }.getOrNull()
        val v = verdict(s, res)
        if (v.warn) RunLog.log("资源告警: ${v.detail}")
        return v
    }

    // ── /proc 读取 ──

    /** 列出本 UID 的所有进程 pid。读不到 status 的（其他应用/内核线程）跳过。 */
    private fun pidsOfUid(uid: Int): List<Int> {
        val proc = File("/proc")
        val dirs = proc.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } }
            ?: return emptyList()
        val out = ArrayList<Int>(8)
        for (d in dirs) {
            val pid = d.name.toIntOrNull() ?: continue
            val status = runCatching { File(d, "status").readText() }.getOrNull() ?: continue
            // Uid: <real> <eff> <saved> <fs>
            val line = status.lineSequence().firstOrNull { it.startsWith("Uid:") } ?: continue
            val real = line.substringAfter("Uid:").trim().split(Regex("\\s+")).firstOrNull()
                ?.toIntOrNull() ?: continue
            if (real == uid) out.add(pid)
        }
        return out
    }

    /** utime + stime（累计滴答）。comm 可能含空格 → 按最后一个 ')' 切分后再取字段。 */
    private fun ticksOf(pid: Int): Long? {
        val text = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return null
        val after = text.substringAfterLast(')', "")
        val f = after.trim().split(Regex("\\s+"))
        if (f.size < 13) return null
        // after 的字段（1-based）：1=state … 12=utime 13=stime
        val utime = f[11].toLongOrNull() ?: return null
        val stime = f[12].toLongOrNull() ?: return null
        return utime + stime
    }

    /** VmRSS（kB）。 */
    private fun rssKbOf(pid: Int): Long {
        val text = runCatching { File("/proc/$pid/status").readText() }.getOrNull() ?: return 0L
        val line = text.lineSequence().firstOrNull { it.startsWith("VmRSS:") } ?: return 0L
        return line.substringAfter("VmRSS:").trim().split(Regex("\\s+")).firstOrNull()
            ?.toLongOrNull() ?: 0L
    }
}
