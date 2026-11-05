// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百五十九批 · 提示词**导出口**（让「注入生效没有」可验）。
//
// 为什么要这一件：436-A 交付时自述「本仓没有导出提示词的运行时设施」，它的核心判据
// （注入是否生效）至今只能靠 JVM 离线渲染去猜；此后任何 prompt 层改动都没有验收手段。
// 本文件只补这个「可验性」——**只读复制**，一个字节都不改提示词内容与拼装顺序。
//
// 四条边界（派工单 §一边界）：
// 1. **只读复制**：`RunEngine` 在 system 段拼好之后把它递进来，这里只做「洗 → 截 → 写」；
//    返回 Boolean，没有任何回写通道（也不可能改 String）。
// 2. **聊天正文不导出**：函数的入参里**根本没有** user／history——正文在结构上进不来，
//    不是靠"我记得别写"这种口头约束。
// 3. **一次一份**：目录里固定两个文件（`last-system.txt` / `last-system.meta.txt`），
//    每轮覆盖写，不追加、不按时间堆文件（防爆盘）。
// 4. **开关缺省 = 构建类型**：debug 默认开、release 默认关；SP `zhengdao-inspect/prompt_export`
//    一旦被显式写过，就以 SP 为准（debug 下也能关掉，release 下也能开）。
//
// 落盘位置用 `cacheDir/prompt-export/`：debug 包可以 `adb exec-out run-as <包名> cat …` 取；
// cacheDir 由系统在空间紧张时清理，属于「诊断产物」该待的地方（不占 filesDir 的备份配额）。
package com.example.zhengdao.baopu.promptexport

import android.content.Context
import com.example.zhengdao.BuildConfig
import com.example.zhengdao.baopu.CriteriaPitPack
import com.example.zhengdao.baopu.TokenEstimator
import com.example.zhengdao.core.TimeFormats
import java.io.File

/** 洗过一次的文本 ＋ 命中次数（命中次数进元信息，让「导出的不是原文」这件事可见）。 */
internal data class Scrubbed(val text: String, val hits: Int)

/** 截断结果（[truncated] 为真时 [body] 末尾带着「已截断」标记）。 */
internal data class Clipped(val body: String, val truncated: Boolean)

/** 注入包的回执（436-A 的运行时包：版本号与条数，判据②要能在元信息里看到）。 */
internal data class PackFacts(
    val version: Int,
    val criteriaCount: Int,
    val pitCount: Int,
    val criteriaVersion: String,
)

/** 元信息的输入（纯数据，没有时钟也没有文件——时间由调用方给）。 */
internal data class ExportFacts(
    val atMs: Long,
    val modelId: String,
    val channelId: String,
    val protocol: String,
    val chars: Int,
    val outChars: Int,
    val truncated: Boolean,
    val tokens: Int,
    val scrubHits: Int,
    val pack: PackFacts,
)

/**
 * 提示词导出口（只读复制 system 段）。
 *
 * 全部判据都是纯函数（[scrub]／[clip]／[metaOf]／[enabled]），只有 [capture]／[exportLast]
 * 两处碰 Context 与磁盘——JVM 单测因此能直接验判据，不必起 Android 环境。
 */
internal object PromptExport {

    /** 落盘目录名（`cacheDir/prompt-export/`）。 */
    const val DIR_NAME = "prompt-export"

    /** system 段正文（每次请求覆盖）。 */
    const val SYSTEM_FILE = "last-system.txt"

    /** 元信息（时间／模型／token 估算／注入包版本与条数／脱敏命中）。 */
    const val META_FILE = "last-system.meta.txt"

    /** 开关的 SP 文件与键（项目惯例：`zhengdao-*` 一个用途一个文件）。 */
    const val SWITCH_FILE = "zhengdao-inspect"
    const val SWITCH_KEY = "prompt_export"

    /**
     * 正文上限（字符）。真实首轮 system 约 1.7 万字符（436-A 实测 16,984 B），
     * 3.2 万字符足够放下两倍还有余；超了截断并标「已截断」——导出是诊断件，
     * 不是备份，不许把磁盘写爆。
     */
    const val MAX_CHARS = 32_768

    /** 疑似密钥的四条洗法（顺序有意义：先洗 `sk-` 与 `Bearer`，再洗长 hex，最后洗 base64）。 */
    private val RULES: List<Pair<Regex, String>> = listOf(
        Regex("""sk-[A-Za-z0-9_\-]{6,}""") to "***",
        Regex("""(?i)bearer\s+[A-Za-z0-9._\-+/=]{8,}""") to "Bearer ***",
        Regex("""[0-9a-fA-F]{32,}""") to "***",
        Regex("""[A-Za-z0-9+/]{40,}={0,2}""") to "***",
    )

    /** 上次捕获到的（**已洗、已截**）正文与元信息——「导出上一次」的触发拿的就是这一份。 */
    @Volatile
    private var lastBody: String? = null

    @Volatile
    private var lastMeta: String? = null

    // ── 纯判据（零 Context、零磁盘、零时钟）────────────────────────────

    /**
     * 开关缺省值：只认构建类型（判据⑤「release 默认不导出」就是这一行）。
     *
     * 单独抽成函数是为了让单测能直接钉「devMode=false ⇒ 关」，不必造 Android 环境。
     */
    fun defaultOn(devMode: Boolean): Boolean = devMode

    /**
     * 现该不该导出：SP 显式写过就以 SP 为准，没写过才落到 [defaultOn]。
     *
     * 为什么不是 `devMode || prefOn`（84 批自检闸那种并联）：派工单要的是「debug 默认**开**、
     * release 默认**关**」——并联会让 release 永远关、debug 永远开，两头都拨不动。
     */
    fun enabled(devMode: Boolean, pref: Boolean?): Boolean = pref ?: defaultOn(devMode)

    /** 洗疑似密钥：只替换、不删除——命中处一律变 `***`，让人一眼看出这里被洗过。 */
    fun scrub(raw: String): Scrubbed {
        var out = raw
        var hits = 0
        for ((re, replacement) in RULES) {
            val found = re.findAll(out).count()
            if (found > 0) {
                hits += found
                out = re.replace(out, Regex.escapeReplacement(replacement))
            }
        }
        return Scrubbed(out, hits)
    }

    /** 截断到 [MAX_CHARS]；不切断代理对（surrogate pair），超了在末尾标「已截断」。 */
    fun clip(raw: String): Clipped {
        if (raw.length <= MAX_CHARS) return Clipped(raw, false)
        var end = MAX_CHARS
        if (Character.isHighSurrogate(raw[end - 1])) end -= 1
        val mark = "\n\n[已截断：原文 ${raw.length} 字符，只保留前 $end 字符]"
        return Clipped(raw.substring(0, end) + mark, true)
    }

    /** 元信息正文（KV 行；`adb pull` 下来直接看得懂，也能 grep 模型 id 与注入包版本）。 */
    fun metaOf(f: ExportFacts): String = buildString {
        appendLine("# 459 · 一次真实请求的 system 段（只读复制，不改提示词内容与拼装顺序）")
        appendLine("时间: ${TimeFormats.isoSeconds(f.atMs)}")
        appendLine("模型: ${f.modelId}")
        appendLine("通道: ${f.channelId}")
        appendLine("协议: ${f.protocol}")
        val state = if (f.truncated) "已截断" else "未截断"
        appendLine("system: ${f.chars} 字符（脱敏后）· 导出 ${f.outChars} 字符 · $state")
        appendLine("token 估算: ${f.tokens}（TokenEstimator 定版口径：中文按字、ASCII÷4，×1.15）")
        appendLine("注入包: 版本 v${f.pack.version} · ${countLine(f.pack)}")
        append("脱敏: ${f.scrubHits} 处")
    }

    /** 判据②那半句：判据条数与坑账条数（取不到时如实写「未知」，不写 0 冒充）。 */
    private fun countLine(p: PackFacts): String {
        val criteria = if (p.criteriaCount < 0) "未知" else "${p.criteriaCount} 条"
        val pits = if (p.pitCount < 0) "未知" else "${p.pitCount} 条"
        return "判据 $criteria · 坑账 $pits（判据表序：${p.criteriaVersion}）"
    }

    // ── 文件面（JVM 可测：只收 File，不收 Context）───────────────────

    /** 落盘目录（`cacheDir/prompt-export/`）。 */
    fun dirOf(ctx: Context): File = File(ctx.applicationContext.cacheDir, DIR_NAME)

    /** 现读开关；没写过（`contains` 为假）返回 null ⇒ 走 [defaultOn]。读不到也返回 null。 */
    fun prefOf(ctx: Context): Boolean? = runCatching {
        val sp = ctx.applicationContext.getSharedPreferences(SWITCH_FILE, Context.MODE_PRIVATE)
        if (sp.contains(SWITCH_KEY)) sp.getBoolean(SWITCH_KEY, false) else null
    }.getOrNull()

    /** 当前该不该导（生产口径：devMode 取 `BuildConfig.DEBUG`）。 */
    fun enabledNow(ctx: Context, devMode: Boolean = BuildConfig.DEBUG): Boolean =
        enabled(devMode, prefOf(ctx))

    /**
     * 写两份文件（覆盖）。返回 true 表示两份都写成了。
     *
     * 只收 [File] 是为了 JVM 单测能直接给临时目录——Context 只在 [capture] 那一层出现。
     */
    fun writeTo(dir: File, body: String, meta: String): Boolean = runCatching {
        if (!dir.isDirectory && !dir.mkdirs()) return false
        File(dir, SYSTEM_FILE).writeText(body, Charsets.UTF_8)
        File(dir, META_FILE).writeText(meta, Charsets.UTF_8)
        true
    }.getOrDefault(false)

    /**
     * 捕获一次真实请求的 system 段（`RunEngine` 发请求前调；改不了请求，只抄一份）。
     *
     * @param systemText 已拼好的 system 段原文（调用方一个字都不许改）
     * @return true ＝ 写了盘；false ＝ 开关关着或写盘失败（失败会留日志，由调用方记）
     */
    fun capture(
        ctx: Context,
        systemText: String,
        modelId: String,
        channelId: String,
        protocol: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = captureTo(
        dir = dirOf(ctx),
        systemText = systemText,
        modelId = modelId,
        channelId = channelId,
        protocol = protocol,
        on = enabledNow(ctx),
        nowMs = nowMs,
    )

    /**
     * [capture] 的文件面（JVM 可测）：[on] 为假 ⇒ 一个字节都不写，也不留「上一次」缓存。
     *
     * 把开关判定收成一个布尔入参，是为了让「关着就不导」这条判据有测试能咬住——
     * 只有在 false 分支真的早退，判据⑤才不是一句口号。
     */
    @Synchronized
    fun captureTo(
        dir: File,
        systemText: String,
        modelId: String,
        channelId: String,
        protocol: String,
        on: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!on) return false
        val (body, meta) = prepare(systemText, modelId, channelId, protocol, nowMs)
        lastBody = body
        lastMeta = meta
        return writeTo(dir, body, meta)
    }

    /** 洗 → 截 → 算元信息（[capture] 与「导出上一次」共用同一份口径）。 */
    private fun prepare(
        systemText: String,
        modelId: String,
        channelId: String,
        protocol: String,
        nowMs: Long,
    ): Pair<String, String> {
        val scrubbed = scrub(systemText)
        val clipped = clip(scrubbed.text)
        val facts = ExportFacts(
            atMs = nowMs,
            modelId = modelId,
            channelId = channelId,
            protocol = protocol,
            chars = scrubbed.text.length,
            outChars = clipped.body.length,
            truncated = clipped.truncated,
            tokens = TokenEstimator.estimate(clipped.body),
            scrubHits = scrubbed.hits,
            pack = packFacts(),
        )
        return clipped.body to metaOf(facts)
    }

    /** 436-A 的运行时包回执：版本 ＋ 判据条数 ＋ 坑账条数（读盘失败如实写「未知」）。 */
    private fun packFacts(): PackFacts {
        val snap = runCatching { CriteriaPitPack.snapshot() }.getOrNull()
        return PackFacts(
            version = CriteriaPitPack.VERSION,
            criteriaCount = snap?.criteria?.size ?: -1,
            pitCount = snap?.pitTotal ?: -1,
            criteriaVersion = snap?.criteriaVersion ?: "取不到（读盘失败）",
        )
    }

    /**
     * 「导出上一次」的触发：把最近一次捕获的那份**再落一次盘**。
     *
     * 语义照实说：本进程还没跑过任何一轮请求时返回 null（没有「上一次」可导）——
     * 不许拿磁盘上的旧文件冒充新捕获。
     */
    fun exportLast(ctx: Context): String? = exportLastTo(dirOf(ctx))

    /** [exportLast] 的文件面（JVM 可测）：没有缓存 ⇒ null。 */
    @Synchronized
    fun exportLastTo(dir: File): String? {
        val body = lastBody ?: return null
        val meta = lastMeta ?: return null
        return if (writeTo(dir, body, meta)) File(dir, SYSTEM_FILE).absolutePath else null
    }

    /** 测试专用：清掉「上一次」缓存（谁用谁还）。 */
    @Synchronized
    fun resetForTest() {
        lastBody = null
        lastMeta = null
    }
}
