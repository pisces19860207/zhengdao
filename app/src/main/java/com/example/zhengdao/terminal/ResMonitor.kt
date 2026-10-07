// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

import com.example.zhengdao.rootfs.RunLog
import java.io.File

/**
 * 资源监控（v1.2 阶段 3.2）：量太极 serve + PRoot guest 的内存与 CPU，超阈值告警。
 *
 * ### 为什么只量自己 UID 的进程
 * Android 的 `/proc` 对**其他应用**的进程不可读（Android 10+ 限制了 /proc 可见性），
 * 但**自己 UID** 的进程完整可读。太极的 opencode serve 是 App 自己 fork 的（宿主
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
 * 也就是说，量到的是"体检自己"，不是"太极/终端吃了多少" ⇒ 阈值形同虚设（真出问题
 * 的 30% 淹在体检噪声的 80% 里）。
 * ⇒ 聚合时**剔除 `myPid()`**，只量太极 serve 与 PRoot guest 及其子进程。
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

    /** 告警阈值。取值依据：真机基线（2026-10-07）太极 serve 空闲 RSS 375–468 MB、
     *  CPU 5.6–7.4%；阈值须明显高于基线，否则一开太极就告警 = 狼来了。 */
    const val RSS_WARN_MB = 800
    const val CPU_WARN_PCT = 25f

    /** 体检结论：ok=false 表示故障，warn=true 表示超阈值但无一键修复。 */
    data class Verdict(val ok: Boolean, val warn: Boolean, val detail: String)

    /**
     * 采样。IO 线程调用。
     *
     * @param windowMs 两次读数的间隔；越长越准，但也越慢。
     * @return 聚合结果；无进程可采（太极与终端都没开）时返回 null。
     */
    fun measure(windowMs: Long = 700): Sample? {
        val uid = android.os.Process.myUid()
        // 剔除 App 自身：否则量到的是体检过程本身（见类注释，实测 0.0% → 报 90%）。
        val pids = excludeSelf(pidsOfUid(uid), android.os.Process.myPid())
        if (pids.isEmpty()) return null
        val t0 = pids.mapNotNull { ticksOf(it) }
        val rss = pids.mapNotNull { rssKbOf(it) }.sum()
        if (t0.isEmpty()) return null
        runCatching { Thread.sleep(windowMs) }
        val t1 = pids.mapNotNull { ticksOf(it) }
        if (t1.isEmpty()) return null
        // 进程可能在窗口内退出，导致两次数量不同 → 取较小值对齐
        val n = minOf(t0.size, t1.size)
        val delta = (t1.take(n).sum() - t0.take(n).sum()).coerceAtLeast(0)
        // 滴答单位是 1/100 秒：窗口内可用滴答 = windowMs/10
        val cpu = delta.toFloat() * 1000f / windowMs.toFloat()
        return Sample(
            rssMb = (rss / 1024).toInt(),
            cpuPct = cpu,
            procs = pids.size,
        )
    }

    /**
     * 纯函数（可单测）：把采样值翻译成体检结论。
     *
     * **无进程可采不算故障**——太极和终端都没开时占用本来就是 0，报红会违反
     * 体检卡「红 = 修得了」的不变量（资源占用没有一键修复）。
     * 超阈值走 **warn**（黄色 ⚠），不是红叉。
     */
    internal fun verdict(s: Sample?): Verdict = when {
        s == null -> Verdict(true, false, "太极与终端均未运行，无进程可采样")
        s.rssMb >= RSS_WARN_MB -> Verdict(
            true, true,
            "⚠ 内存 ${s.rssMb} MB 超阈值 ${RSS_WARN_MB} MB（${s.procs} 个进程）——关掉不用的会话，或重启太极",
        )
        s.cpuPct >= CPU_WARN_PCT -> Verdict(
            true, true,
            "⚠ CPU ${"%.1f".format(s.cpuPct)}% 超阈值 ${CPU_WARN_PCT.toInt()}%——检查插件与会话规模",
        )
        else -> Verdict(
            true, false,
            "RSS ${s.rssMb} MB · CPU ${"%.1f".format(s.cpuPct)}% · ${s.procs} 个进程",
        )
    }

    /**
     * 可测核心：从 UID 进程表里剔除 App 自身 pid。
     *
     * 不剔除的话，采样窗口正好覆盖 `EnvHealth.inspect()` 自己的密集工作，
     * 量出来的是"体检的开销"而非"太极/终端的占用"（真机：top 0.0% 但报 80–90%）。
     */
    internal fun excludeSelf(pids: List<Int>, selfPid: Int): List<Int> =
        pids.filter { it != selfPid }

    /** 采样 + 告警落日志（超阈值时写一条 RunLog，便于事后查趋势）。 */
    fun sampleWithLog(windowMs: Long = 700): Verdict {
        val s = runCatching { measure(windowMs) }.getOrNull()
        val v = verdict(s)
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
