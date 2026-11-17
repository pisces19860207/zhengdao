// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百八十四批 · **故障注入实现（只在 debug 源集；release 不编译本文件）**。
//
// 为什么要这个：失败路径（`run_log.err` → `ToolFailureAttribution` → 约束卡）只有
// 真出事才走得到，环境好的时候没法验。这里让**指定工具按规则失败**，而且失败是
// **真的**：注入点在 [com.example.zhengdao.baopu.agent.GuardPipeline] 第 ④ 段之前，
// 冒充的是一个普通的 `ToolOutcome.Failed`——⑤ 的围栏、引擎的 `persistToolExchange`、
// `run_log.err`、收工归因、约束卡全部原路走，没有一行是手写的假账。
//
// 三条边界（判据）：
//   · **只在 debug**：本文件在 `app/src/debug/java`，release 编译期就不存在；
//     main 里只剩一根针（`ToolFaultSeam`），正式包里没有规则、没有文件名、没有入口。
//   · **关掉＝原样**：配置文件不存在 ⇒ 缝里是 null ⇒ 工具调用一个字节都不变。
//   · **失败码必须是真码**：`code` 只许取 [ToolErrCodes.ALL] 里已有的码（写错的行
//     直接跳过并报错），这样归因层认得出、约束卡才会弹——不许编一个"注入专用"的假码。
package com.example.zhengdao.debug

import android.content.Context
import android.util.Log
import com.example.zhengdao.baopu.agent.ToolErrCodes
import com.example.zhengdao.baopu.agent.ToolFaultHook
import com.example.zhengdao.baopu.agent.ToolFaultSeam
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.core.RunLog
import org.json.JSONObject
import java.io.File

/**
 * 一条注入规则（纯值；解析与命中判定都可 JVM 直测）。
 *
 * @param tool 工具名；`*` ＝任意工具
 * @param nth 0 ＝每次命中都注入；N ＝第 N 次命中时才注入（1-based）
 * @param limit 最多注入几次；0 ＝不限（配合 nth=0 就是"前 N 次都失败"）
 * @param code 失败码（[ToolErrCodes] 里的 `code` 字面量，如 `protocol`）
 * @param argsPattern 可选：参数 JSON 文本要能匹配这个正则才注入（参数模式）
 */
internal data class FaultRule(
    val tool: String,
    val nth: Int,
    val limit: Int,
    val code: String,
    val argsPattern: Regex? = null,
) {
    fun matches(toolName: String, argsJson: String): Boolean {
        if (tool != WILDCARD && tool != toolName) return false
        return argsPattern == null || argsPattern.containsMatchIn(argsJson)
    }

    companion object {
        const val WILDCARD = "*"
    }
}

/**
 * 配置文件解析（**纯函数**，JVM 直测）。
 *
 * 格式（一行一条，`#` 开头是注释，空行忽略）：
 *
 * ```
 * # tool | nth | limit | code | argsRegex(可空)
 * read_file | 0 | 3 | protocol |
 * write_file | 2 | 1 | timeout | "path":"资料库/
 * ```
 *
 * 为什么用竖线不用 JSON：这是**给 adb 手敲/脚本 echo 的东西**，一行一条最不容易写坏；
 * 而且坏行能精确报到第几行，JSON 写坏一个括号整份就废了。
 *
 * 坏行（列数不对／数字不合法／码不在 [ToolErrCodes] 里／正则写坏）一律**跳过并报错**，
 * 绝不"猜一个最像的"——注入器猜错会让验收读到一条根本不是它想要的失败。
 */
internal object FaultRuleParser {

    const val SEPARATOR = "|"

    private const val COLUMNS = 5

    fun parse(text: String, onBad: (line: Int, why: String) -> Unit = { _, _ -> }): List<FaultRule> {
        val rules = ArrayList<FaultRule>()
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val cols = line.split(SEPARATOR).map { it.trim() }
            if (cols.size != COLUMNS) {
                onBad(index + 1, "要 $COLUMNS 列（tool|nth|limit|code|argsRegex），实际 ${cols.size} 列")
                return@forEachIndexed
            }
            val nth = cols[1].toIntOrNull()
            val limit = cols[2].toIntOrNull()
            if (nth == null || nth < 0) {
                onBad(index + 1, "nth 不是非负整数：${cols[1]}")
                return@forEachIndexed
            }
            if (limit == null || limit < 0) {
                onBad(index + 1, "limit 不是非负整数：${cols[2]}")
                return@forEachIndexed
            }
            val code = cols[3].lowercase()
            if (ToolErrCodes.byCode(code) == null) {
                onBad(index + 1, "码「${cols[3]}」不在 ToolErrCodes 里（可用：" +
                    ToolErrCodes.ALL.joinToString("/") { it.code } + "）")
                return@forEachIndexed
            }
            val pattern = cols[4].takeIf { it.isNotEmpty() }?.let { src ->
                runCatching { Regex(src) }.getOrElse {
                    onBad(index + 1, "argsRegex 写坏了：${it.javaClass.simpleName}")
                    return@forEachIndexed
                }
            }
            rules += FaultRule(
                tool = cols[0].ifEmpty { FaultRule.WILDCARD },
                nth = nth,
                limit = limit,
                code = code,
                argsPattern = pattern,
            )
        }
        return rules
    }
}

/**
 * 规则执行器：数命中次数、按 nth/limit 决定这一次要不要失败。
 *
 * 计数是**进程内**的（一个注入器一份），所以 `nth=0, limit=3` 的语义是
 * 「从装上开始，前 3 次命中都失败」——三连失败脚本要的正是这个。
 */
internal class FaultInjector(
    private val rules: List<FaultRule>,
    private val onFire: (rule: FaultRule, hit: Int) -> Unit = { _, _ -> },
) : ToolFaultHook {

    private val hits = IntArray(rules.size)
    private val fired = IntArray(rules.size)

    override fun intercept(tool: String, args: JSONObject): ToolOutcome? {
        val argsJson = args.toString()
        for (i in rules.indices) {
            val rule = rules[i]
            if (!rule.matches(tool, argsJson)) continue
            hits[i]++
            if (rule.nth != 0 && hits[i] != rule.nth) continue
            if (rule.limit != 0 && fired[i] >= rule.limit) continue
            fired[i]++
            onFire(rule, hits[i])
            return ToolOutcome.Failed(errorLineOf(rule.code))
        }
        return null
    }

    /** 失败原文＝码的固定短句（[ToolErrCodes] 唯一一份；归因层认的就是这一句）。 */
    private fun errorLineOf(code: String): String =
        ToolErrCodes.byCode(code)?.line ?: error("码「$code」在解析阶段就该被挡掉")
}

/**
 * 进程级安装器（**唯一入口在 debug 清单的 `<provider>`**，见 `FaultInjectInstaller`）。
 *
 * 为什么不挂在 `ZhengdaoApp`：那是 main 源集，一旦引用本类 release 就编不过；
 * 而 debug 清单里的 provider 只在开发包存在，正式包里连这个类都没有——这就是
 * 「release 编译期剔除」的结构保证（不是 `if (BuildConfig.DEBUG)` 的运行时挡板）。
 *
 * 配置改了不用重启：每次工具调用前只看一眼 mtime，变了就重读（顺带把计数清零，
 * 一次新场景从 0 数起）。文件被删 ⇒ 注入器置空 ⇒ 行为立刻回到"和现在完全一致"。
 */
internal object FaultInjection {

    const val TAG = "FaultInject"

    /** 配置落点（相对 `filesDir`；与 `StartupGate` 的 `baopu/inject-assembly-fail` 同一个目录口径）。 */
    const val DIR = "baopu"
    const val FILE_NAME = "fault-inject.txt"

    fun configFile(context: Context): File = File(File(context.filesDir, DIR), FILE_NAME)

    /** 进程启动时装上（provider.onCreate 调；幂等）。 */
    fun install(context: Context) {
        val file = configFile(context)
        ToolFaultSeam.hook = ConfigWatcher(file)
        Log.i(TAG, "installed; config=${file.absolutePath} exists=${file.isFile}")
        RunLog.log("[fault-inject] 注入器已装（仅 debug）：${file.absolutePath}")
    }

    /** 只读一眼当前规则（探针/脚本取证用；不改计数）。 */
    fun describe(context: Context): List<String> {
        val file = configFile(context)
        if (!file.isFile) return emptyList()
        return FaultRuleParser.parse(file.readText()).map { r ->
            "tool=${r.tool} nth=${r.nth} limit=${r.limit} code=${r.code}" +
                (r.argsPattern?.let { " args=/${it.pattern}/" } ?: "")
        }
    }

    /** 带 mtime 热重载的包装：配置不在 ⇒ 直接放行（＝注入关闭）。 */
    private class ConfigWatcher(private val file: File) : ToolFaultHook {

        @Volatile
        private var stamp: Long = Long.MIN_VALUE

        @Volatile
        private var inner: FaultInjector? = null

        override fun intercept(tool: String, args: JSONObject): ToolOutcome? {
            refreshIfChanged()
            return inner?.intercept(tool, args)
        }

        private fun refreshIfChanged() {
            val mtime = if (file.isFile) file.lastModified() else 0L
            if (mtime == stamp) return
            synchronized(this) {
                if (mtime == stamp) return
                stamp = mtime
                inner = if (mtime == 0L) {
                    Log.i(TAG, "config 不存在 ⇒ 注入关闭")
                    null
                } else {
                    load()
                }
            }
        }

        private fun load(): FaultInjector? {
            val text = runCatching { file.readText() }.getOrElse {
                Log.w(TAG, "config 读失败（${it.javaClass.simpleName}）⇒ 本次不注入")
                return null
            }
            val bad = ArrayList<String>()
            val rules = FaultRuleParser.parse(text) { line, why -> bad += "第 $line 行：$why" }
            bad.forEach { Log.w(TAG, "坏规则已跳过 —— $it") }
            Log.i(TAG, "config 载入：${rules.size} 条规则（坏 ${bad.size} 行）")
            RunLog.log("[fault-inject] 载入 ${rules.size} 条规则（坏 ${bad.size} 行）")
            if (rules.isEmpty()) return null
            return FaultInjector(rules) { rule, hit ->
                Log.i(TAG, "ROW|INJECT|tool=${rule.tool}|hit=$hit|code=${rule.code}")
                RunLog.log("[fault-inject] 注入失败：tool=${rule.tool} hit=$hit code=${rule.code}")
            }
        }
    }
}
