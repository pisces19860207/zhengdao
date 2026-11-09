// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十一批（2026-11-09）· **开发核对包**：这台手机上的 APK 是仓库里的哪一版、
// 开了哪些脚手架、哪些候选可删——一处可查、可导出、可长期积累。
//
// 三条硬口径（判据都钉在它们上面）：
//   1. **只读采集＋导出**：不联网、不上传、不写数据库；除了往 `<工作区>/产物/` 写两份
//      点号文件，不碰任何账本。文件点号开头 ⇒ `ReflectionRunner.isCountedProduct`
//      不计入「产物 N 件」（512 口径，判据⑤）。
//   2. **白名单防泄漏（判据④）**：readings 只抄 527 出口 JSON 里 `day/ratios/cards/gray`
//      四个键——`goal`（用户目标原文）、`openLoops`（承诺原文）、`conflicts`、`identity`
//      一律不进包；build/switches 全是构建事实与布尔。整条链路上没有任何「用户正文」的取数口。
//   3. **不许编（559）**：消融登记表 `harness-audit-registry.md` 尚未建立 ⇒ ablation /
//      deadCandidates 两节如实写「未建立」，可删候选计 0。559 落地后由那一批把登记表
//      打进 assets（同 licenses/eval 的装配路子）并把 [registryEstablished] 翻真——
//      两节自动换真数，本文件的其它口径不用动。
package com.example.zhengdao.baopu

import android.content.Context
import com.example.zhengdao.BuildConfig
import com.example.zhengdao.baopu.agent.GovernanceStateTool
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.util.SigningCheck
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONObject

internal object BuildAuditPack {

    /** 五节名（判据③"字段齐全"的钉子；测试按这五个键逐一断言）。 */
    val SECTIONS = listOf("build", "switches", "ablation", "deadCandidates", "readings")

    /** 导出文件名前缀：点号开头 ⇒ 不计产物数（判据⑤；512 口径）。 */
    const val FILE_STEM_PREFIX = ".zd-audit-"

    /** 559 的消融登记表（仓库侧文件；App 里没有它的副本 ⇒ 未建立）。 */
    const val REGISTRY_NAME = "harness-audit-registry.md"

    /** readings 只抄 527 出口的这几段——白名单就是隐私边界（判据④）。 */
    private val READING_KEYS = listOf("day", "ratios", "cards", "gray")

    /** 一条脚手架开关：key（SP 键）＋人话名＋默认值＋现值（默认与现值都写，派工单口径）。 */
    data class SwitchLine(val key: String, val label: String, val default: Boolean, val current: Boolean)

    /** 组好的核对包：两份正文 ＋ 页内那一行摘要。 */
    data class Pack(val json: String, val md: String, val summary: String, val mdLines: Int)

    /**
     * 559 登记表是否已建立。**当前=false（不许编）**：登记表在仓库侧还没建（559 待派），
     * App 里也没有它的副本。559 落地后由那一批翻转这里并接 assets 读数。
     */
    internal fun registryEstablished(): Boolean = false

    /** 可删候选条数：登记表未建立 ⇒ 0（如实；同上，559 落地后翻真）。 */
    internal fun deadCandidateCount(): Int = 0

    /** 页内摘要一行（派工单定式）：`构建 a1b2c3d · 开关 3 关 / 12 开 · 可删候选 2`。 */
    fun summaryLine(gitSha: String, switches: List<SwitchLine>, deadCount: Int): String {
        val off = switches.count { !it.current }
        return "构建 $gitSha · 开关 $off 关 / ${switches.size} 开 · 可删候选 $deadCount"
    }

    /** 文件名时间戳：`yyyyMMdd-HHmm`（派工单口径；同一分钟内重复导出不覆盖 ⇒ 加序号）。 */
    fun stampFor(nowMs: Long): String =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(nowMs))

    /**
     * 落盘用的文件基名（不含扩展名）：目标 `.json` 或 `.md` 已存在 ⇒ 整体换下一个序号
     * （`.zd-audit-20261109-1030.json` → `.zd-audit-20261109-1030-2.json`）。
     * 判据⑤：**重复导出不覆盖旧文件**——json 与 md 必须同号成对，序号也成对。
     */
    fun nextBase(productsDir: File, stamp: String): String {
        var n = 1
        while (true) {
            val tail = if (n == 1) "" else "-$n"
            val base = FILE_STEM_PREFIX + stamp + tail
            if (!File(productsDir, "$base.json").exists() && !File(productsDir, "$base.md").exists()) {
                return base
            }
            n++
        }
    }

    /**
     * readings 节：**只抄白名单四键**（判据④的实现本体）。读不到出口 ⇒ null，
     * 由 [buildJson] 落成 `{"unknown": "527 出口读不到"}`——不冒充 0（527 同款口径）。
     */
    fun readingsSection(governanceJson: String?): JSONObject? {
        // 527 出口 JSON 的解析与 GovernanceCenterRules.parse 同一口径（坏串给 null，不抛）；
        // 不引 ui 层的 Rules——baopu 不反向依赖 ui（分层），这里只做"白名单四键"的搬运。
        if (governanceJson == null) return null
        val root = runCatching { JSONObject(governanceJson) }.getOrNull() ?: return null
        return JSONObject().put(
            "day",
            root.optString("day"),
        ).apply {
            READING_KEYS.filter { it != "day" }.forEach { key ->
                put(key, root.optJSONObject(key) ?: JSONObject())
            }
        }
    }

    /** switches 节的两条（560 的两件：SP 可关、默认开；默认与现值都写）。 */
    fun collectSwitches(context: Context): List<SwitchLine> = listOf(
        SwitchLine(
            "zhengdao-scaffold/history-prefilter",
            "历史预过滤",
            default = true,
            current = ScaffoldSwitches.historyPrefilterEnabled(context),
        ),
        SwitchLine(
            "zhengdao-scaffold/compression-prompt",
            "压卡摘要",
            default = true,
            current = ScaffoldSwitches.compressionPromptEnabled(context),
        ),
    )

    /** build 节：版本＋短号＋构建时刻＋ABI＋签名指纹＋**apkSha256**（判据③/派工单追加③）。 */
    fun buildSection(
        signerSha256: String?,
        apkSha256: String?,
    ): JSONObject = JSONObject()
        .put("versionName", BuildConfig.VERSION_NAME)
        .put("versionCode", BuildConfig.VERSION_CODE)
        .put("gitSha", BuildConfig.GIT_SHA)
        .put(
            "shaState",
            when {
                BuildConfig.GIT_SHA == "local" -> "local"
                BuildConfig.GIT_SHA.endsWith("-dirty") -> "dirty"
                else -> "clean"
            },
        )
        .put("buildTime", BuildConfig.BUILD_TIME)
        .put("abi", BuildConfig.ABI)
        .put("signerSha256", signerSha256 ?: "unknown")
        .put("apkSha256", apkSha256 ?: "unknown")

    /** 采集＋组包（IO 上跑）：两份正文在 [collect] 里一次成型，导出只是写盘。 */
    fun collect(
        context: Context,
        wsRoot: File,
        nowMs: Long,
        governanceJson: String?,
    ): Pack {
        val switches = collectSwitches(context)
        val apkSha = runCatching { sha256Hex(File(context.applicationInfo.sourceDir)) }.getOrNull()
        val signer = runCatching { SigningCheck.signerSha256(context) }.getOrNull()
        val json = buildJson(switches, signer, apkSha, readingsSection(governanceJson))
        val md = buildMd(json, switches)
        return Pack(
            json = json,
            md = md,
            summary = summaryLine(BuildConfig.GIT_SHA, switches, deadCandidateCount()),
            mdLines = md.count { it == '\n' } + 1,
        )
    }

    /** 五节 JSON（判据②可被 json.tool 解析、判据③五节齐全）。internal＝JVM 单测直测。 */
    internal fun buildJson(
        switches: List<SwitchLine>,
        signerSha256: String?,
        apkSha256: String?,
        readings: JSONObject?,
    ): String = JSONObject()
        .put("v", 1)
        .put(
            "exportedAt",
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(java.time.OffsetDateTime.now()),
        )
        .put("build", buildSection(signerSha256, apkSha256))
        .put(
            "switches",
            org.json.JSONArray().apply {
                switches.forEach {
                    put(
                        JSONObject()
                            .put("key", it.key)
                            .put("label", it.label)
                            .put("default", it.default)
                            .put("current", it.current),
                    )
                }
            },
        )
        .put("ablation", ablationSection())
        .put("deadCandidates", deadCandidatesSection())
        .put("readings", readings ?: JSONObject().put("unknown", "527 出口读不到"))
        .toString()

    /** ablation 节：登记表未建立 ⇒ 如实一句「未建立」，一个数都不编（判据③/559）。 */
    private fun ablationSection(): JSONObject =
        if (registryEstablished()) {
            // 559 落地后由那一批实现：运行计数 / usage.txt 命中 / 变异结论 / 结论。
            JSONObject().put("status", "TODO-559")
        } else {
            JSONObject()
                .put("status", "未建立")
                .put("registry", REGISTRY_NAME)
                .put(
                    "note",
                    "559 消融登记表尚未建立（待派）。建成后本节给：运行计数 / usage.txt 命中 / " +
                        "变异结论 / 结论；现在不编任何数。",
                )
        }

    /** deadCandidates 节：三源一致条目（见 559）。登记表未建立 ⇒ 空表＋说明，不编条目。 */
    private fun deadCandidatesSection(): JSONObject =
        if (registryEstablished()) {
            JSONObject().put("source", REGISTRY_NAME).put("entries", org.json.JSONArray())
        } else {
            JSONObject()
                .put("source", REGISTRY_NAME + "（未建立，见 559）")
                .put("entries", org.json.JSONArray())
        }

    /** 人可读的 .md（判据②：≤200 行）。段落固定，灰度行封顶 ⇒ 行数有确定上界。 */
    internal fun buildMd(json: String, switches: List<SwitchLine>): String {
        val root = JSONObject(json)
        val build = root.getJSONObject("build")
        val out = StringBuilder()
        out.append("# 开发核对包（561）\n\n")
        out.append("- 一行摘要：").append(
            summaryLine(
                build.getString("gitSha"),
                switches,
                deadCandidateCount(),
            ),
        ).append('\n')
        out.append("- 导出时刻：").append(root.getString("exportedAt")).append('\n')
        out.append("- 隐私口径：白名单采集，不含用户正文/账本自由文本/模型输出（561 判据④）\n\n")
        out.append("## build\n\n")
        listOf("versionName", "versionCode", "gitSha", "shaState", "buildTime", "abi", "signerSha256", "apkSha256")
            .forEach { key -> out.append("- ").append(key).append("：").append(build.optString(key)).append('\n') }
        out.append("\n## switches（脚手架开关：默认 → 现值）\n\n")
        switches.forEach {
            out.append("- ").append(it.key).append("（").append(it.label).append("）：")
                .append(boolOf(it.default)).append(" → ").append(boolOf(it.current)).append('\n')
        }
        out.append("\n## ablation（559 消融登记）\n\n")
        val ablation = root.getJSONObject("ablation")
        out.append("- 状态：").append(ablation.getString("status"))
            .append("（").append(ablation.optString("registry")).append("）\n")
        out.append("- ").append(ablation.optString("note")).append('\n')
        out.append("\n## deadCandidates（可删候选：三源一致条目，见 559）\n\n")
        val dead = root.getJSONObject("deadCandidates")
        out.append("- 来源：").append(dead.getString("source")).append('\n')
        out.append("- 条数：").append(dead.getJSONArray("entries").length()).append('\n')
        out.append("\n## readings（四率 · 弹卡三数 · 灰度；原文抄 527 出口，未另算）\n\n")
        appendReadings(out, root)
        return out.toString()
    }

    /** readings 的 md 段：day ＋ 三小节逐行；灰度行封顶 [MAX_GRAY_LINES] ⇒ 全文行数有上界。 */
    private fun appendReadings(out: StringBuilder, root: JSONObject) {
        val readings = root.getJSONObject("readings")
        if (readings.has("unknown")) {
            out.append("- ").append(readings.getString("unknown")).append('\n')
            return
        }
        out.append("- day：").append(readings.optString("day")).append('\n')
        READING_KEYS.filter { it != "day" }.forEach { section ->
            val obj = readings.optJSONObject(section) ?: return@forEach
            out.append("\n### ").append(section).append("\n\n")
            var shown = 0
            obj.keys().forEachRemaining { key ->
                if (section == "gray" && shown >= MAX_GRAY_LINES) return@forEachRemaining
                out.append("- ").append(key).append("：")
                    .append(lineValue(obj.opt(key))).append('\n')
                shown++
            }
            if (section == "gray" && obj.length() > MAX_GRAY_LINES) {
                out.append("- …共 ").append(obj.length()).append(" 项（md 只列前 ")
                    .append(MAX_GRAY_LINES).append(" 行，全量在同名 .json）\n")
            }
        }
    }

    /** 值统一转一行可读字：对象/数组压成紧凑 JSON，其余 toString（md 里不折行）。 */
    private fun lineValue(value: Any?): String = when (value) {
        null -> "—"
        is JSONObject -> value.toString()
        is org.json.JSONArray -> value.toString()
        else -> value.toString()
    }

    private fun boolOf(v: Boolean): String = if (v) "开" else "关"

    /** 导出两件（IO 上跑）：同名同号成对写 `.json` ＋ `.md`；绝不覆盖已存在的旧文件。 */
    fun exportFiles(wsRoot: File, pack: Pack, nowMs: Long): Pair<File, File> {
        val dir = MemoryLayout.productsDir(wsRoot)
        dir.mkdirs()
        val base = nextBase(dir, stampFor(nowMs))
        val jsonFile = File(dir, "$base.json")
        val mdFile = File(dir, "$base.md")
        check(!jsonFile.exists() && !mdFile.exists()) { "核对包落点已存在（不许覆盖）：$base" }
        jsonFile.writeText(pack.json, Charsets.UTF_8)
        mdFile.writeText(pack.md, Charsets.UTF_8)
        return jsonFile to mdFile
    }

    /** 527 出口这把（读不到给 null——由 readingsSection 落 unknown，不冒充 0）。 */
    fun governanceJson(wsRoot: File, nowMs: Long): String? = runCatching {
        val outcome = GovernanceStateTool.run(wsRoot, nowMs)
        (outcome as? ToolOutcome.Ok)?.text
    }.getOrNull()

    /** 文件 SHA-256（小写十六进制）：核对包拿它算**装机那份 APK** 的 apkSha256。 */
    fun sha256Hex(file: File): String? {
        if (!file.isFile) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 灰度行在 md 里的封顶（当前 13 项远用不满；封顶是为了让 ≤200 行成为确定性质）。 */
    internal const val MAX_GRAY_LINES = 60
}
