// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百四十三批（2026-11-04，派工单《模型名映射》）：**模型显示名的唯一真相**。
//
// 三条先钉死的规则：
//   1. 显示名 = 系列 + 版本 + 档位，不带构建号；值逐字取 `assets/models/model-labels.json`；
//   2. 表里没有 ⇒ 原样显示 ID，**绝不猜、绝不美化**（131 批的 prettify 不再参与显示名）；
//   3. 表只管「显示什么」，**不改请求**：resolve 原样带回 requestId，Provider.model 一个字不动。
//
// 为什么解析层零 Android：`assets/` 在 JVM 单测里读不到（没有 AssetManager），本仓也没有
// Robolectric。范式照 PersonaFactory / PolicyAssets —— 读盘留给 ModelLabelAssets，
// 判据全留在这里；测试用 [sourceFile] 读**同一份**源码树文件，不另存副本（两份就会漂）。
package com.example.zhengdao.baopu

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val STATUS_LEGACY = "legacy"

/** 表里的一行；七字段必填，`aliasOf` 只对别名条目存在。 */
internal data class ModelLabel(
    val modelId: String,
    val displayName: String,
    val vendor: String,
    val tier: String,
    val source: String,
    val asOf: String,
    val status: String,
    val aliasOf: String? = null,
) {
    val legacy: Boolean get() = status == STATUS_LEGACY
}

/** 解析结果：`requestId` 永远是调用方给的原 ID（请求链路读它，不读显示名）。 */
internal data class ResolvedLabel(
    val requestId: String,
    val displayName: String,
    val vendor: String,
    val tier: String,
    val badgeLetter: String,
    val legacy: Boolean,
    val inTable: Boolean,
    val aliasOf: String?,
)

internal object ModelLabels {

    const val ASSET_PATH = "models/model-labels.json"
    const val LEGACY = STATUS_LEGACY
    const val LEGACY_MARK = " · 旧版"

    private const val DATE_SUFFIX_RE = "-\\d{8}$"
    private const val CODING_SUFFIX_RE = "-coding$"
    private val DATE_SUFFIX = Regex(DATE_SUFFIX_RE)
    private val CODING_SUFFIX = Regex(CODING_SUFFIX_RE)

    /** 徽标一律自绘字母（D/O/A/G/Z/Q/K/豆），不用官方 logo。 */
    private val VENDOR_LETTERS = mapOf(
        "deepseek" to "D",
        "openai" to "O",
        "anthropic" to "A",
        "google" to "G",
        "gemini" to "G",
        "zhipu" to "Z",
        "智谱" to "Z",
        "alibaba" to "Q",
        "qwen" to "Q",
        "通义" to "Q",
        "moonshot" to "K",
        "kimi" to "K",
        "bytedance" to "豆",
        "doubao" to "豆",
        "豆包" to "豆",
    )

    @Volatile
    private var installed: List<ModelLabel>? = null

    /** JVM 上从源码树读同一份 assets；Android 上读不到 ⇒ 空表 ⇒ 一律显 ID（安全降级）。 */
    private val bundled: List<ModelLabel> by lazy {
        runCatching { parse(sourceFile().readText()) }.getOrDefault(emptyList())
    }

    fun install(labels: List<ModelLabel>) {
        installed = labels
    }

    fun current(): List<ModelLabel> = installed ?: bundled

    /** 严格解析：缺任一必填字段、status 越界都直接抛，绝不静默补一个猜出来的名字。 */
    fun parse(json: String): List<ModelLabel> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { one(arr.getJSONObject(it)) }
    }

    private fun one(o: JSONObject): ModelLabel {
        fun field(key: String): String = o.optString(key).trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("model-labels.json 条目缺字段：$key")
        val status = field("status")
        require(status == "active" || status == LEGACY) { "status 只能是 active/legacy：$status" }
        return ModelLabel(
            modelId = field("modelId"),
            displayName = field("displayName"),
            vendor = field("vendor"),
            tier = field("tier"),
            source = field("source"),
            asOf = field("asOf"),
            status = status,
            aliasOf = o.optString("aliasOf").trim().takeIf { it.isNotEmpty() },
        )
    }

    /** 源码树里的那一份（测试专用）；单测工作目录有模块根／仓库根两种形态。 */
    fun sourceFile(repoRoot: File = File(".")): File {
        val rel = "app/src/main/assets/$ASSET_PATH"
        return listOf(File(repoRoot, rel), File(repoRoot, "src/main/assets/$ASSET_PATH"))
            .firstOrNull { it.isFile } ?: File(repoRoot, rel)
    }

    /** 测试入口：读同一份文件、走同一个严格解析，不另立读法。 */
    fun loadFromSource(): List<ModelLabel> = parse(sourceFile().readText())

    /**
     * 去部署变体后缀（-coding / 8 位日期）。**只在查表时用**：表内精确命中优先，
     * 去后缀后仍必须精确命中表里某条才采用其显示名，否则原样回吐 ID（不猜）。
     */
    fun stripVariants(id: String): String {
        var out = id.trim()
        var guard = 0
        while (guard < 4) {
            guard++
            val next = CODING_SUFFIX.replace(DATE_SUFFIX.replace(out, ""), "")
            if (next == out) break
            out = next
        }
        return out
    }

    /** 查表顺序：精确 → 去后缀精确 → 表外原样；别名解析到真实 ID 的那条显示名。 */
    fun resolve(rawId: String): ResolvedLabel {
        val id = rawId.trim()
        val labels = current()
        val hit = find(labels, id) ?: find(labels, stripVariants(id))
        if (hit == null) {
            return ResolvedLabel(id, id, "", "", "", false, false, null)
        }
        val target = hit.aliasOf?.let { find(labels, it) } ?: hit
        return ResolvedLabel(
            requestId = id,
            displayName = target.displayName,
            vendor = target.vendor,
            tier = target.tier,
            badgeLetter = badgeOf(target.vendor),
            legacy = hit.legacy,
            inTable = true,
            aliasOf = hit.aliasOf,
        )
    }

    private fun find(labels: List<ModelLabel>, id: String): ModelLabel? =
        labels.firstOrNull { it.modelId.equals(id, ignoreCase = true) }

    /** 顶栏/抽屉共用的显示名：表外原样 ID；legacy 加「旧版」但**不隐藏**。 */
    fun displayName(id: String): String {
        val r = resolve(id)
        val text = if (r.inTable) r.displayName else r.requestId
        return if (r.legacy) text + LEGACY_MARK else text
    }

    fun badgeOf(vendor: String): String {
        val key = vendor.trim().lowercase()
        if (key.isEmpty()) return ""
        return VENDOR_LETTERS[key] ?: key.first().uppercaseChar().toString()
    }
}
