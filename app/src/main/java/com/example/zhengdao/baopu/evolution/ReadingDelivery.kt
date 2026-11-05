// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百六十一批 · 进化读数**送达通道**：380 的收件箱（`进化读数.jsonl`）原先只能人手送
// （debug 经 run-as；release 连推送口都没有——体检单上那一格写的就是这件事）。本文件把
// "人手送"换成"CI 送"：CI 把读数写进专用分支 `ci/readings` 的 `readings/进化读数.jsonl`
// （**不动 main**），App 每轮收工匿名 GET 那一个固定 raw URL，新行落私有目录收件箱，
// 之后照旧归 [ProofReading.pick] 选行——**判定零改写，本文件只做搬运工**。
//
// 硬约束（派工单 ③，逐条落法）：
//   · **只 GET 一个 URL**：全仓只此一处字面量 [READINGS_URL]；任何取数必经 [fetchAllowed]，
//     非白名单（≠ 常量全字匹配）当场拒——重定向也不跟（`instanceFollowRedirects=false`），
//     跟了就等于 GET 了第二个 URL；
//   · **不传设备信息**：请求头一个不加，User-Agent 覆盖成固定常量（平台默认的 Dalvik UA
//     才带着系统版本这类设备信息）；method 恒 GET，不 POST；
//   · **读数带 ref**：行 schema 归 [ProofRow]（ref＝CI run id），本文件不发明第二个 schema；
//
// 失败语义（派工单 ④，逐条落法）：
//   · 格式不符（`ProofRow.parse` 建不出来）⇒ 弃用：不落收件箱、一行汇总进 RunLog；
//     时间窗不符不在本文件判——那是 [ProofReading.pick] 的既有闸（本文件零判法，不重造第二套）；
//   · 断网／404／超限 ⇒ **落一行诊断、不阻断**：异常就地接住，绝不往调用方抛（收工触发
//     的 runCatching 只是兜底，不该指望它）；
//   · **同轮只取一次**：轮入口只有 [onRoundStart] 一个（onRunSettled 每轮收工调一次），
//     单次调用至多提交一次取数；另加在途互斥（上一次没回来不叠加）；
//   · **退避**：失败按 5→10→20→40→60 分钟指数退避（持久化到 [STATE_FILE]，进程重启不
//     清零）；成功后一小时内不再取——CI 分支一天才动几回，别把收工轮变成对 GitHub 的轮询。
//
// release 可用：本文件零 Android（`java.net`＋`java.io`＋`org.json`），没有 debug 闸、
// 没有 run-as 依赖——release 与 debug 走同一条路。落盘走 [DecisionLedger.installedRoot]，
// 与提案/尝试/读数各账同一棵账根。
//
// 零判法声明：好/坏行只看 376/310 的构造器建不建得出来（[ProofRow.parse]）；过没过只看
// 链自己的闸。本文件不数"哪条读数该信"。
package com.example.zhengdao.baopu.evolution

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.core.RunLog
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 读数送达（461 的取数件；选行与判定仍归 380 的 [ProofReading]，本件零判法）。 */
internal object ReadingDelivery {

    /**
     * 唯一白名单 URL：`ci/readings` 分支上的读数文件（匿名 raw，公开仓）。
     * 路径里的中文按 RFC 3986 百分号编码写死（`进化读数.jsonl`），URI 不用现拼。
     */
    internal const val READINGS_URL: String =
        "https://raw.githubusercontent.com/pisces19860207/zhengdao/ci/readings/" +
            "readings/%E8%BF%9B%E5%8C%96%E8%AF%BB%E6%95%B0.jsonl"

    /** 退避/节流状态文件（账根下；两三行小文件，整体重写，不是追加账）。 */
    internal const val STATE_FILE = "进化读数-送达.state"

    /** 固定 UA：一串无设备信息的常量（平台默认 Dalvik UA 才带系统版本——那才叫设备信息）。 */
    private const val USER_AGENT = "zhengdao-readings/1"

    /** 远端体上限：CI 侧每轮裁到最近 200 行，512 KiB 绰绰有余；超限按失败处理不解析。 */
    private const val MAX_BODY_BYTES = 512L * 1024L

    /** 连接/读取超时：收工轮不等网，10 秒封顶（超时＝失败＝退避，不阻断本轮）。 */
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    /** 成功后的节流窗：一小时。CI 分支的更新频率是"天"级，一小时一次足够新鲜。 */
    private const val SUCCESS_INTERVAL_MS = 60L * 60_000L

    /** 失败退避的底数与上限（分钟）：第 n 次连败 ⇒ min(5·2^(n-1), 60) 分钟。 */
    private const val BACKOFF_BASE_MINUTES = 5L
    private const val BACKOFF_MAX_MINUTES = 60L

    /** 送达状态：连败次数（成功清零）＋下次允许取数的时刻＋上次成功时刻。 */
    internal data class State(val fails: Int, val nextTryMs: Long, val lastOkMs: Long) {
        fun persist(root: File) {
            File(root, STATE_FILE).writeText(
                "FAILS|$fails\nNEXT_TRY|$nextTryMs\nLAST_OK|$lastOkMs\n",
                Charsets.UTF_8,
            )
        }

        companion object {
            val EMPTY = State(fails = 0, nextTryMs = 0L, lastOkMs = 0L)

            /** 坏行当空状态：状态文件不是账，坏了就当没退避过（最坏后果是多取一次）。 */
            fun load(root: File): State = runCatching {
                val f = File(root, STATE_FILE)
                if (!f.isFile) return EMPTY
                var fails = 0
                var nextTry = 0L
                var lastOk = 0L
                f.readLines(Charsets.UTF_8).forEach { line ->
                    val cols = line.split('|')
                    when {
                        line.startsWith("FAILS|") -> fails = cols.getOrNull(1)?.toIntOrNull() ?: 0
                        line.startsWith("NEXT_TRY|") -> nextTry = cols.getOrNull(1)?.toLongOrNull() ?: 0L
                        line.startsWith("LAST_OK|") -> lastOk = cols.getOrNull(1)?.toLongOrNull() ?: 0L
                    }
                }
                State(fails, nextTry, lastOk)
            }.getOrDefault(EMPTY)
        }
    }

    /** 一次送达的收尾读数（判据断言面：取到什么、弃了什么、落了什么，逐个数得出来）。 */
    internal data class Outcome(
        val attempted: Boolean,
        val ok: Boolean,
        val remoteLines: Int,
        val badLines: Int,
        val knownRefs: Int,
        val appended: Int,
        val reason: String,
    ) {
        companion object {
            fun skipped(why: String) = Outcome(false, true, 0, 0, 0, 0, why)
            fun failed(why: String) = Outcome(true, false, 0, 0, 0, 0, why)
        }
    }

    /** 取数传输（可注入：测试喂假传输，生产走 [httpGet]）。只许白名单 URL 进来。 */
    @Volatile
    internal var transport: (String) -> String = ::httpGet

    private val inflight = AtomicBoolean(false)

    /** 单线程后台执行器：网络绝不占用调用方线程（收工触发在主线程上）。 */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "reading-delivery").apply { isDaemon = true }
    }

    /** 白名单闸：全字匹配那一个常量。多一个字符、换一个 host、http 降级都算非白名单。 */
    internal fun isAllowedUrl(url: String): Boolean = url == READINGS_URL

    /**
     * 轮入口（每轮收工由 [EvolutionWiring.onRunSettled] 调一次）：到点才取、在途不叠、
     * **任何异常不出本函数**——送达是收工的搭头，不许挡住本轮的链。
     */
    fun onRoundStart(nowMs: Long) {
        runCatching {
            val root = DecisionLedger.installedRoot() ?: return
            if (!due(root, nowMs)) return
            if (!inflight.compareAndSet(false, true)) return
            executor.execute {
                try {
                    attemptOnce(nowMs)
                } finally {
                    inflight.set(false)
                }
            }
        }
        // 无 catch 体：attemptOnce 自己接住一切；这里 runCatching 只是让"决定取数"这步
        // 也保证不往收工路径抛（例如状态文件被外力改坏）。
    }

    /** 到点判定：退避期内不取；成功后一小时内不取；账根没挂由调用方先挡。 */
    internal fun due(root: File, nowMs: Long): Boolean {
        val s = State.load(root)
        if (nowMs < s.nextTryMs) return false
        if (s.lastOkMs > 0L && nowMs - s.lastOkMs < SUCCESS_INTERVAL_MS) return false
        return true
    }

    /**
     * 同步取一次（生产走 [onRoundStart] 的后台线程；测试直调本函数保证确定性）。
     * 失败只落一行诊断＋退避，**绝不抛**。
     */
    internal fun attemptOnce(nowMs: Long): Outcome {
        val root = DecisionLedger.installedRoot() ?: return Outcome.skipped("账根没挂")
        val body = try {
            fetchAllowed(READINGS_URL)
        } catch (e: IOException) {
            return fail(root, nowMs, "取数失败（${e.javaClass.simpleName}）：${e.message}")
        } catch (e: SecurityException) {
            return fail(root, nowMs, "取数被拒（SecurityException）：${e.message}")
        } catch (e: RuntimeException) {
            return fail(root, nowMs, "取数失败（${e.javaClass.simpleName}）：${e.message}")
        }
        val parsed = classifyRemote(body, root)
        if (parsed.allBad) {
            // 整个远端没有一行建得出来：弃用，不算失败（通道是好的，是内容不行）。
            RunLog.log("抱朴读数送达：远端 ${parsed.remote} 行全部格式不符，弃用不落收件箱（时间窗归 ProofReading.pick 判）")
            return Outcome(true, true, parsed.remote, parsed.bad, 0, 0, "全部弃用")
        }
        if (parsed.fresh.isNotEmpty()) {
            runCatching {
                File(root, ProofReading.READINGS_FILE).appendText(parsed.fresh.joinToString("", postfix = "\n"), Charsets.UTF_8)
            }.onFailure {
                return fail(root, nowMs, "收件箱写不上（${it.javaClass.simpleName}）：${it.message}")
            }
        }
        // 成功：连败清零（写失败只喊一声——退避丢了最坏是多取一次，不是账丢失）。
        State(0, 0L, nowMs).persist(root)
        RunLog.log(
            "抱朴读数送达：远端 ${parsed.remote} 行——格式不符弃用 ${parsed.bad}、已知 ref 跳过 ${parsed.knownSkip}、" +
                "新落收件箱 ${parsed.fresh.size}（选行归 ProofReading.pick）",
        )
        return Outcome(true, true, parsed.remote, parsed.bad, parsed.knownSkip, parsed.fresh.size, "送达")
    }

    /** 远端逐行分类的收尾（弃用判据只认 [ProofRow.parse]：建不出来＝弃，零自判法）。 */
    private class RemoteParse(val remote: Int, val bad: Int, val fresh: List<String>, val knownSkip: Int) {
        /** 整个远端没一行建得出来（且远端确实非空）。 */
        val allBad: Boolean get() = remote > 0 && fresh.isEmpty() && bad == remote
    }

    /** 逐行分类：格式坏行弃掉、已知 ref 跳过、其余当新行（同体内重复 ref 也只落一次）。 */
    private fun classifyRemote(body: String, root: File): RemoteParse {
        val known = HashSet<String>().apply {
            addAll(existingRefs(root))
            addAll(ProofReading.usedRefs(root))
            addAll(ProofReading.suspectedRefs(root))
        }
        var bad = 0
        var remote = 0
        val fresh = ArrayList<String>()
        val seen = HashSet<String>()
        for (raw in body.split('\n')) {
            val line = raw.trimEnd('\r').trim()
            if (line.isEmpty()) continue
            remote++
            val row = ProofRow.parse(line)
            when {
                row == null -> bad++
                row.ref in seen || row.ref in known -> seen.add(row.ref)
                else -> {
                    fresh.add(line)
                    seen.add(row.ref)
                }
            }
        }
        return RemoteParse(remote, bad, fresh, seen.size - fresh.size)
    }

    /** 失败收尾：退避写入＋**只一行**诊断（判据④：断网不刷屏、不阻断）。 */
    private fun fail(root: File, nowMs: Long, why: String): Outcome {
        val s = State.load(root)
        val fails = (s.fails + 1).coerceAtLeast(1)
        val backoffMin = (BACKOFF_BASE_MINUTES shl (fails - 1).coerceAtMost(4)).coerceAtMost(BACKOFF_MAX_MINUTES)
        val nextTry = nowMs + backoffMin * 60_000L
        runCatching { State(fails, nextTry, s.lastOkMs).persist(root) }
            .onFailure { RunLog.log("抱朴读数送达：退避状态没写上（${it.javaClass.simpleName}）——重启后可能提前重试") }
        RunLog.log("抱朴读数送达：$why——本轮不阻断，退避 ${backoffMin} 分钟（第 $fails 连败）")
        return Outcome.failed(why)
    }

    /** 收件箱里已有的 ref（送达去重用；行残缺拿不到 ref 的当"新行"由 parse 挡掉）。 */
    private fun existingRefs(root: File): Set<String> {
        val f = File(root, ProofReading.READINGS_FILE)
        if (!f.isFile) return emptySet()
        return runCatching {
            f.readLines(Charsets.UTF_8).mapNotNull { raw -> ProofRow.parse(raw)?.ref }.toSet()
        }.getOrDefault(emptySet())
    }

    /** 唯一取数口：非白名单 URL 在这里就被拒（判据④"非白名单拒"的钉点；internal 供测试直调）。 */
    internal fun fetchAllowed(url: String): String {
        require(isAllowedUrl(url)) { "非白名单 URL 拒绝：只许 GET 固定读数地址，不许：$url" }
        return transport(url)
    }

    /** 真传输：GET、零附加头（UA 覆盖成固定常量）、不跟重定向、状态码与体积双闸。 */
    internal fun httpGet(url: String): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code（404＝CI 分支还没建；3xx＝重定向也当失败，不跟）")
            val input = conn.inputStream
            val buffer = ByteArray(8192)
            var total = 0L
            val out = StringBuilder()
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > MAX_BODY_BYTES) throw IOException("远端读数超过 ${MAX_BODY_BYTES} 字节上限，拒收")
                out.append(String(buffer, 0, n, Charsets.UTF_8))
            }
            return out.toString()
        } finally {
            conn.disconnect()
        }
    }

    /** 测试还原（生产不调）：内存态清零、传输还原；状态文件由用例自管。 */
    internal fun resetForTest() {
        transport = ::httpGet
        inflight.set(false)
    }
}
