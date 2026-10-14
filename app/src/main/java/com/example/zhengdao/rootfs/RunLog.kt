// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.rootfs

import android.content.Context
import com.example.zhengdao.BuildConfig
import com.example.zhengdao.terminal.Store
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志（**2026-10-14 N-4 A 案：落点改回私有区**）。
 *
 * 位置：**`filesDir/logs/`**（应用私有目录），四类文件：
 * - `zhengdao-log.txt`：**本轮运行**的日志（App 每次启动时，上一轮的会被**归档**，见下）；
 * - `zhengdao-log.<yyyyMMdd-HHmmss>.txt`：**历次运行的存档**（每次启动归档一份，保留最近
 *   `KEEP_ARCHIVES` 份、总量不超过 `ARCHIVE_TOTAL_MAX`，超了从最旧的删）；
 * - `zhengdao-log.prev.txt`：**旧版**"只留一代错误日志"的产物（仍会被读取，不再新写）；
 * - `errors.log`：跨轮次的**错误汇总**（只追加含错误标记的行，用户不用在几千行里翻）。
 *
 * ## 位置沿革（两次搬迁，别搞混）
 * - **2026-10-08**：按用户要求「运行日志放到 download 证道 文件夹里」从私有 cache 搬进
 *   **公共区** `Download/证道/logs/`（`Store.logsDir`）——当时要的是"打不开、只能截图报障"。
 * - **2026-10-14（N-4 A 案，用户拍板）**：日志内容含**私有绝对路径与异常原文**，放在公共区
 *   ⇒ 任何拿到存储权限的程序都能读，不用 USB 调试。改回**私有 `filesDir/logs/`**；
 *   对外出口改为设置页「导出日志」（SAF，用户按下 = 一次知情动作）。
 *   - 旧公共区里的历史日志**不删、不改写、不迁移覆盖**（是用户数据；`migrateLegacy` 只做
 *     **复制**一份进私有区，源文件保持原样）；
 *   - `KeepaliveArchive` 的 logcat-boot 落点仍在公共区（`keepalive/` 目录归 ⑪批 B-1，不归本类）。
 *
 * ## 脱敏（N-4 A 案）
 * 落盘前每一行过 [redact]：`/data/user/0/<包名>`、`/data/data/<包名>`、filesDir 绝对路径
 * ⇒ `<私有目录>`；`Authorization: Basic …` / `server password…` / `passwd=…` / `token=…`
 * ⇒ `****`。**保留** `/storage/emulated/0/Download/证道/…`（用户可见路径，不属隐私且排障要它）。
 *
 * ## logcat 镜像（#5 的历史设计，N-4 收窄）
 * 进程被杀时文件里最后一行往往是"正常跑着"，而 logcat 环形缓冲里还留着死前那几十行 ⇒
 * 原设计把每一行镜像进 logcat（tag `zhengdao`）。release 包里这就是把日志内容送给任何
 * 能读 logcat 的人，**N-4 起只在 `BuildConfig.DEBUG` 下镜像**；「拿不到 context 时进 logcat」
 * 与「落盘失败进 logcat」两条保留（失败可见优先，且都不落盘、不构成外送面）。
 *
 * 保留规则（**2026-10-08 用户拍板后改过语义**）：
 * 用户原话（2026-10-08）：「那些日志都是方便给你们这些 agent 看查哪里有问题的，所以要留着」。
 * ⇒ 旧行为"启动时上一轮**没出错就直接删掉**"与这句话直接冲突（干净的那一轮往往正是
 *   要对照的"正常长什么样"），已改成 **每次启动都把上一轮整份归档**，永不因为"没错误"而丢弃；
 *   只有体积/份数的上限会让你丢日志，且是从**最旧的一份**开始。
 * - ⚠️ **同一进程只归档一次**：`init()` 在三处被调用（ZhengdaoApp / MainActivity /
 *   TerminalActivity），旧实现每次 init 都会"善后一遍"，于是第二个 init 会把**本轮
 *   刚开始写的日志**当成上一轮的、发现它还没错误就删掉——正是"日志动不动就没了"的
 *   隐藏原因。现在用一个进程内标志兜住。
 * - 错误标记：行内含「错误 / 失败 / 异常 / FATAL / Exception / error」。
 */
object RunLog {

    /** 单个日志文件的上限；超出丢最早的一半（保留后半段 + 一行"已截断"提示）。 */
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val ERRORS_MAX_BYTES = 1024L * 1024
    private const val NAME = "zhengdao-log.txt"
    private const val PREV_NAME = "zhengdao-log.prev.txt"
    private const val ERRORS_NAME = "errors.log"
    private const val ARCHIVE_PREFIX = "zhengdao-log."
    private const val ARCHIVE_SUFFIX = ".txt"

    /** 历史日志保留上限：份数与总量，先到先限（都从最旧的开始删）。 */
    private const val KEEP_ARCHIVES = 20
    private const val ARCHIVE_TOTAL_MAX = 20L * 1024 * 1024
    private val ERROR_MARKERS = listOf("错误", "失败", "异常", "FATAL", "Exception", "error")

    private var appContext: Context? = null

    /**
     * 已解析的日志目录（N-4 A 案起恒为私有 `filesDir/logs`——私有目录永远可用，
     * 解析一次即可；旧的"公共区不可用就每次重试"缓存语义随公共区落点一起取消）。
     */
    @Volatile
    private var resolvedDir: File? = null

    /** 上一轮日志的善后只做一次（见类注释：多次 init 会把本轮日志误删）。 */
    @Volatile
    private var settled = false

    /**
     * 写文件专用锁（P2-13）。
     *
     * 为什么不用类监视器（`synchronized(RunLog)`）：那是公开的，任何持 RunLog 引用的代码
     * 都能拿它做同步，锁意图会被外部代码意外耦合。专用锁对象把临界区收窄到本文件。
     */
    private val lock = Any()

    fun init(context: Context) {
        appContext = context.applicationContext
        val ctx = appContext ?: return
        val d = dir(ctx)
        migrateLegacy(ctx, d)
        if (!settled) {
            settled = true
            archivePrevious(d)
        }
    }

    /**
     * 日志目录：**私有 `filesDir/logs`**（N-4 A 案；原公共区 `Download/证道/logs` 落点取消）。
     *
     * 私有目录不需要任何权限、随 App 卸载一起消失，任何外部程序读不到 ⇒ 日志里的
     * 私有路径/异常原文不再构成外送面。对外出口见设置页「导出日志」（SAF 显式导出）。
     *
     * 判据抽成纯函数 [dirFor]：JVM 单测直接断言"不在 /storage/emulated/0 下、在 filesDir 下"，
     * 不靠 grep 文本（真门禁，见 `RunLogPrivacyGateTest`）。
     */
    private fun dir(context: Context): File {
        resolvedDir?.let { if (it.isDirectory) return it }
        val d = dirFor(context.filesDir).apply { mkdirs() }
        resolvedDir = d
        return d
    }

    /** 私有日志目录判据（纯函数，单测直接调）：恒为 `filesDir/logs`。 */
    internal fun dirFor(filesDir: File): File = File(filesDir, "logs")

    /**
     * 迁移旧位置（N-4 A 案）：
     * ① `files/zhengdao-log.txt`（旧版"只留一代"的产物）→ `logs/zhengdao-log.prev.txt`；
     * ② **公共区老日志复制一份进私有区**（`Store.logsDir` 下的四类文件）——源文件
     *    **不删、不改写**（是用户数据，且可能正被用户/Agent 看着）；复制失败不影响主流程。
     */
    fun migrateLegacy(context: Context, d: File = dir(context)) {
        try {
            val target = File(d, PREV_NAME)
            Store.adoptFile(target, File(context.filesDir, NAME))
        } catch (t: Throwable) {
            // P2-7：老用户的旧日志搬失败 = 那批记录事实上丢了，不能静默。
            // 走 logcat 直连（migrateLegacy 可能在 init 早期被调，log() 未必就绪）。
            android.util.Log.w("zhengdao/RunLog", "旧位置日志迁移失败（忽略）：${t.message}")
        }
        copyLegacyFromPublic(context, d)
    }

    /**
     * 公共区老日志 → 私有区**复制**（N-4 A 案；一次性，幂等）。
     *
     * 只复制不删除：源在 `Download/证道/logs/`，是用户 2026-10-08 明确要求"留在那"的数据；
     * 新日志从此只进私有区，公共区自然不再增长。目标已存在（非空）就跳过——私有区这份
     * 更新，不拿公共区的旧内容覆盖。
     */
    private fun copyLegacyFromPublic(context: Context, d: File) {
        try {
            val pub = runCatching { Store.logsDir(context) }.getOrNull() ?: return
            if (!pub.isDirectory) return
            val names = mutableListOf(NAME)
            pub.listFiles { f -> f.isFile && isArchiveName(f.name) }?.forEach { names += it.name }
            names += ERRORS_NAME
            names += PREV_NAME
            for (name in names) {
                val src = File(pub, name)
                if (!src.isFile) continue
                val dst = File(d, name)
                if (dst.isFile && dst.length() > 0L) continue
                runCatching { src.copyTo(dst, overwrite = false) }
            }
        } catch (t: Throwable) {
            // 复制失败 = 老日志在私有区看不到而已（源还在公共区），不致命
            android.util.Log.w("zhengdao/RunLog", "公共区老日志复制进私有区失败（忽略）：${t.message}")
        }
    }

    /**
     * 上一轮日志的善后：**整份归档**成 `zhengdao-log.<时间戳>.txt`（用户 2026-10-08：
     * 「日志都是方便给你们这些 agent 看查哪里有问题的，所以要留着」）。
     *
     * 与旧实现的区别：旧版"没错误标记就 `delete()`"，等于每轮把正常运行的记录擦掉——
     * 而排查问题时"正常那轮长什么样"恰恰是最有用的对照。现在只有 [pruneArchivesIn]
     * 的体积/份数上限会让你丢日志，且从**最旧**的一份开始。
     */
    private fun archivePrevious(d: File) {
        try {
            val current = File(d, NAME)
            if (!current.isFile) return
            if (current.length() == 0L) {
                // 空文件没有任何信息量（比如刚启动就被杀），别占一个历史位
                current.delete()
                return
            }
            val at = current.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(at))
            // B-2（审计第 2 期）：归档名原先只精确到秒，且紧接着 `if (dest.exists()) dest.delete()`
            // 会在同一秒内重启时**无条件删掉上一次的归档** —— 既丢日志，又绕过了 [pruneArchivesIn]
            // 的"从最旧删、最新那份永不删"策略。改成撞名就加唯一后缀（两份都留着），
            // 是否丢弃一律交给 [pruneArchivesIn] 按份数/总量统一裁决。
            val taken = d.listFiles()?.mapTo(HashSet()) { it.name } ?: HashSet()
            val dest = File(d, archiveNameFor(stamp, taken))
            if (!current.renameTo(dest)) {
                // 跨文件系统 / 被占用时退回复制（宁可多一份，也别把日志丢了）
                current.copyTo(dest, overwrite = true)
                current.delete()
            }
            pruneArchivesIn(d)
        } catch (t: Throwable) {
            // P2-7：归档失败会直接丢掉上一轮的日志（"死前那几十行"正是它负责保住的），
            // 必须留痕。走 logcat 直连（同 adoptPrivate：不调 log() 防自递归）。
            android.util.Log.w("zhengdao/RunLog", "上轮日志归档失败（忽略）：${t.message}")
        }
    }

    /** 历史日志（新 → 旧）。 */
    fun archives(): List<File> {
        val ctx = appContext ?: return emptyList()
        return listArchives(dir(ctx))
    }

    /** 最新一份历史日志（"上次运行"）。 */
    fun latestArchive(): File? = archives().firstOrNull()

    private fun listArchives(d: File): List<File> =
        d.listFiles { f -> f.isFile && isArchiveName(f.name) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** 归档文件名判定（单测直接调）。 */
    internal fun isArchiveName(name: String): Boolean =
        name.startsWith(ARCHIVE_PREFIX) && name.endsWith(ARCHIVE_SUFFIX) &&
            name != NAME && name != PREV_NAME && name != ERRORS_NAME &&
            name.length > ARCHIVE_PREFIX.length + ARCHIVE_SUFFIX.length

    /**
     * 归档文件名：`zhengdao-log.<stamp>.txt`；同秒撞名时退化为 `…-1` / `…-2` …（B-2）。
     *
     * 纯函数（单测直接调）：只在 [taken]（目录里已有的文件名）里查重，不碰文件系统。
     * 生成的名字仍满足 [isArchiveName] ⇒ 照样进 [pruneArchivesIn] 的保留策略，
     * 不会变成"没人管的孤儿文件"。
     */
    internal fun archiveNameFor(stamp: String, taken: Set<String>): String {
        val base = "$ARCHIVE_PREFIX$stamp$ARCHIVE_SUFFIX"
        if (base !in taken) return base
        var n = 1
        while (true) {
            val cand = "$ARCHIVE_PREFIX$stamp-$n$ARCHIVE_SUFFIX"
            if (cand !in taken) return cand
            n++
        }
    }

    /**
     * 保留策略（纯文件操作，单测直接调）：最多 [keep] 份、总量不超过 [maxBytes]，
     * 超出的**从最旧的删**，返回删掉的份数。
     *
     * 两份约束的先后：先按份数砍掉 `drop(keep)` 那段（更旧的），再在剩下的里按总量
     * **继续从最旧的**删——于是无论哪条先触发，"留给人看的永远是最近几轮"。
     * 唯一的例外：最新那一份永远不删（哪怕它自己就超过 [maxBytes]），
     * 否则会退化成"一份都不剩"。
     */
    internal fun pruneArchivesIn(
        d: File,
        keep: Int = KEEP_ARCHIVES,
        maxBytes: Long = ARCHIVE_TOTAL_MAX,
    ): Int {
        val all = listArchives(d) // 新 → 旧
        if (all.isEmpty()) return 0
        val doomed = LinkedHashSet<File>()
        doomed += all.drop(keep.coerceAtLeast(1))
        var bytes = all.take(keep.coerceAtLeast(1)).sumOf { it.length() }
        for (f in all.take(keep.coerceAtLeast(1)).reversed()) { // 剩余里从最旧开始
            if (bytes <= maxBytes) break
            if (f == all.first()) break // 最新那份留着
            doomed += f
            bytes -= f.length()
        }
        var deleted = 0
        for (f in doomed) if (f.delete()) deleted++
        return deleted
    }

    /** 设置页的手动清理：只留最近 [keep] 份（用户主动点，不从后台偷偷删）。 */
    fun pruneArchivesKeep(context: Context, keep: Int): Int =
        runCatching { pruneArchivesIn(dir(context), keep = keep.coerceAtLeast(1)) }.getOrDefault(0)

    private fun hasError(f: File): Boolean = runCatching {
        f.useLines { lines -> lines.any { line -> ERROR_MARKERS.any { line.contains(it, true) } } }
    }.getOrDefault(false)

    /**
     * 上一次会话是否留下了错误（设置页提示「上次运行检测到错误」用）。
     * 读**最新一份归档**；老用户可能只有旧版留下的 `.prev.txt`，那条也认。
     */
    fun lastRunHadErrors(context: Context): Boolean {
        val d = runCatching { dir(context) }.getOrNull() ?: return false
        val latest = listArchives(d).firstOrNull()
        if (latest != null) return latest.length() > 0L && hasError(latest)
        val legacy = File(d, PREV_NAME)
        return legacy.isFile && legacy.length() > 0L && hasError(legacy)
    }

    fun log(line: String) {
        val ctx = appContext
        if (ctx == null) {
            // ⚠️ 绝不静默丢弃：拿不到 context 时至少进 logcat。
            //    「失败可见」的前提是日志存在——丢进黑洞**比不记日志更糟**，
            //    因为它会让人误判成"这段分支没执行 / 异常没触发"，
            //    本次就因此把 SSE 断连归因错了两轮。
            android.util.Log.w("zhengdao/RunLog", "[未落盘] $line")
            return
        }
        // N-4 A 案：落盘前统一脱敏——私有绝对路径与口令不进任何持久化出口
        // （logcat 镜像那行也走脱敏后的文本：KeepaliveArchive 会把它抓成 logcat-boot-*.txt）。
        val safe = redact(line, ctx.filesDir.absolutePath)
        try {
            val d = dir(ctx)
            val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            appendRotated(File(d, NAME), "[$ts] $safe\n")
            // #5（2026-10-09）：**debug 构建才镜像进 logcat**（N-4 A 案收窄）。
            // 为什么原来是每一行都镜像：进程被杀时文件里最后一行往往是"正常跑着"，而 logcat
            // 环形缓冲里还留着死前那几十行；下一轮启动时 KeepaliveArchive.onStartup 会把这段
            // 抓成 logcat-boot-*.txt 存下来。为什么收窄：release 包里这就是把每一行业务行
            // （含私有路径/异常原文）送给任何能读 logcat 的程序 ⇒ 只在 BuildConfig.DEBUG 下做。
            if (BuildConfig.DEBUG) {
                android.util.Log.i("zhengdao", safe)
            }
            // 错误汇总：跨轮次保留，用户在设置页点开就能看到"最近出过什么事"
            if (ERROR_MARKERS.any { safe.contains(it, ignoreCase = true) }) {
                appendRotated(File(d, ERRORS_NAME), "[$ts] $safe\n", ERRORS_MAX_BYTES)
            }
        } catch (e: Throwable) {
            // 落盘失败同样不能静默——否则又是一个黑洞
            android.util.Log.w("zhengdao/RunLog", "[落盘失败] $safe | ${e.message}")
        }
    }

    /**
     * 脱敏（N-4 A 案，纯函数 ⇒ JVM 单测直接调，不需要 Android）。
     *
     * 规则（与 `docs/N-4-日志落点改造方案-A案-2026-10-14.md` §2.2 逐条对应）：
     * 1. `/data/user/0/<包名>/…` 与 `/data/data/<包名>/…` ⇒ `<私有目录>`；
     * 2. [filesDirPath]（`context.filesDir.absolutePath`，传入时）⇒ `<私有目录>`
     *    （精确路径优先，防第 1 条的字符类没圈到的形态）；
     * 3. `Authorization: Basic <值>` / `server password…` / `passwd=…` / `token=…` ⇒ 值替 `****`；
     * 4. **不动** `/storage/emulated/0/Download/证道/…`（用户可见路径，不属隐私且排障要它）；
     * 5. 不碰时间戳/错误标记词（本函数只替换上述形态，其余逐字保留）。
     *
     * ⚠️ 与 ⑯刀的边界：**不过滤 Exception / java.* 堆栈**——那归 ⑯刀，别撞车。
     */
    internal fun redact(line: String, filesDirPath: String? = null): String {
        var s = line
        if (!filesDirPath.isNullOrEmpty()) s = s.replace(filesDirPath, "<私有目录>")
        // 私有绝对路径两类形态（包名段一整段替掉，含后面的全部子路径）
        s = s.replace(Regex("/data/user/0/[^\\s:;,'\")]+"), "<私有目录>")
        s = s.replace(Regex("/data/data/[^\\s:;,'\")]+"), "<私有目录>")
        // 口令类（值一律 ****；分隔符宽松匹配 = : 空格）
        s = s.replace(Regex("(Authorization:\\s*Basic\\s+)\\S+", RegexOption.IGNORE_CASE), "$1****")
        s = s.replace(Regex("(server password[=:\\s]+)\\S+", RegexOption.IGNORE_CASE), "$1****")
        s = s.replace(Regex("(passwd[=:\\s]+)\\S+", RegexOption.IGNORE_CASE), "$1****")
        s = s.replace(Regex("(token[=:\\s]+)\\S+", RegexOption.IGNORE_CASE), "$1****")
        return s
    }

    /**
     * 追加 + 超限轮转（保留后半段）。
     *
     * ⚠️ 必须持 [lock]：三个步骤（量长度 → 轮换重写 → 追加）不是一个原子操作。
     *    RunLog 的调用方跨多个线程（保活线程 / proot 输出线程 / UI 线程 / 下载线程），
     *    无锁时两类事故都会真实发生：① 两个线程同时发现超限 ⇒ 各读一份、各写一份，
     *    后写的把前一份截断结果整个覆盖（日志整段丢失）；② 线程 A 正在追加时线程 B
     *    轮换重写 ⇒ A 那行被 B 的 writeText 抹掉。
     *
     * 判定用字节数（`length()` 是 stat，便宜）；只有真的超限才读整份文件——
     * 与改动前的 I/O 模式一致（不把读文件放进每行日志的热路径）。
     */
    private fun appendRotated(f: File, text: String, max: Long = MAX_BYTES) {
        synchronized(lock) {
            if (f.length() > max) {
                val current = runCatching { f.readText() }.getOrNull().orEmpty()
                f.writeText(rotateContent(current, max))
            }
            f.appendText(text)
        }
    }

    /**
     * 轮换后的内容（纯函数，供 JVM 单测钉住）：截断标记 + 原内容后半段。
     *
     * 取后半段按**字符数**（`max / 2`）：日志以中文为主，UTF-8 一字 3 字节，
     * 触发判定用的却是字节数 ⇒ 轮换后文件实际约占 1.5×max 字节。这是改动前就有的
     * 近似（上限是 MB 量级、日志又是低频，这点误差不值得上字节数组）。
     */
    internal fun rotateContent(current: String, max: Long): String =
        "…（旧日志已截断）…\n" + current.takeLast((max / 2).toInt())

    fun file(): File? = appContext?.let { File(dir(it), NAME) }?.takeIf { it.isFile }

    fun prevFile(): File? = appContext?.let { File(dir(it), PREV_NAME) }?.takeIf { it.isFile }

    /** 日志目录的真实路径（设置页展示用：告诉用户"东西在哪"，而不是让他去猜）。 */
    fun dirPath(context: Context): String = dir(context).absolutePath

    /**
     * 导出用：全部日志文件（**新 → 旧**，带人类可读标签）。
     *
     * N-4 A 案的对外出口配套：私有目录用户打不开，设置页「导出日志」经 SAF 让用户选目标，
     * 内容过 [redact] 后写出（见 `SettingsHelpActivity`）。
     */
    fun exportEntries(context: Context): List<Pair<String, File>> {
        val d = dir(context)
        val out = ArrayList<Pair<String, File>>()
        File(d, NAME).takeIf { it.isFile }?.let { out += "本次运行" to it }
        listArchives(d).forEach { out += "历史归档 ${it.name}" to it }
        File(d, ERRORS_NAME).takeIf { it.isFile }?.let { out += "错误汇总" to it }
        File(d, PREV_NAME).takeIf { it.isFile }?.let { out += "旧版遗留" to it }
        return out
    }
}
