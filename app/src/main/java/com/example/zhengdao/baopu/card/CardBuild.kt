// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.baopu.card

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * `card_build`（第四十五批，2026-10-19）：母档 JSON → 三套格式的卡/世界书/五件套骨架。
 *
 * 边界（与派工单一致）：
 * - **不覆盖**：目标任一文件已存在 ⇒ 整次拒绝（先全查再写）；
 * - 单字关键词**拒收**（`_or_` 单字＝积分刺客）；
 * - 不做二次改写：母档文本原样落盘；
 * - 生成后自检由调用方（CardTools）用 [CardCheck] 做。
 *
 * 599a（2026-11-11 拍板「不分家」）：web 档改走 [WebPlatformBook]——
 * 外壳默认裸数组，母档 `whole_package`（布尔）切整包；条数/字数预检可配
 * （母档 `entry_limit`／`value_char_limit`，默认族内最严 1000 条/20000 字）；
 * 整包可选字段（pre_text 等）有则写、无则不写；**没有平台名分支**。
 */
internal object CardBuild {

    const val FORMAT_WEB = "web"
    const val FORMAT_TAVO = "tavo"
    const val FORMAT_ST = "st"

    data class Spec(
        val format: String,
        val name: String,
        val persona: String,
        val world: List<String>,
        val keywords: List<String>,
        val style: String,
        val html: String,
        val css: String,
        val wholePackage: Boolean = false,
        val entryLimit: Int? = null,
        val valueCharLimit: Int? = null,
        val packageFields: JSONObject? = null,
    )

    data class Built(val outDir: File, val files: List<File>)

    class BuildError(message: String) : Exception(message)

    fun parseSpec(raw: String): Spec {
        val o = runCatching { JSONObject(raw) }.getOrElse {
            throw BuildError("母档不是合法 JSON")
        }
        val format = o.optString("format").trim().lowercase()
        if (format !in setOf(FORMAT_WEB, FORMAT_TAVO, FORMAT_ST)) {
            throw BuildError("format 只认 web／tavo／st（给的是『$format』）")
        }
        val name = o.optString("name").trim()
        if (name.isEmpty()) throw BuildError("缺 name（作品名）")
        val keywords = strings(o.opt("keywords"))
        if (keywords.isEmpty()) throw BuildError("缺 keywords（世界书触发词）")
        val one = keywords.firstOrNull { it.codePointCount(0, it.length) == 1 }
        if (one != null) {
            throw BuildError("关键词里有单字「$one」：_or_ 模式下单字＝积分刺客，请换成两字以上的词")
        }
        val world = strings(o.opt("world"))
        return Spec(
            format = format,
            name = name,
            persona = o.optString("persona"),
            world = world,
            keywords = keywords,
            style = o.optString("style"),
            html = o.optString("html"),
            css = o.optString("css"),
            wholePackage = boolSwitch(o, "whole_package"),
            entryLimit = positiveInt(o, "entry_limit"),
            valueCharLimit = positiveInt(o, "value_char_limit"),
            packageFields = WebPlatformBook.packageFieldsOf(o),
        )
    }

    /** 布尔开关（母档可选键）：缺省 false；键在但不是布尔 ⇒ 拒收（不猜意图）。 */
    private fun boolSwitch(o: JSONObject, key: String): Boolean {
        if (!o.has(key) || CardJson.isJsonNull(o, key)) return false
        return o.opt(key) as? Boolean
            ?: throw BuildError("$key 必须是布尔（true／false），给的是 ${o.opt(key)}")
    }

    /** 正整数上限（母档可选键）：缺省 null＝用 profile 默认；键在但不是正整数 ⇒ 拒收。 */
    private fun positiveInt(o: JSONObject, key: String): Int? {
        if (!o.has(key) || CardJson.isJsonNull(o, key)) return null
        val v = CardJson.numberAsInt(o, key)
        return v?.takeIf { it > 0 }
            ?: throw BuildError("$key 必须是正整数，给的是 ${o.opt(key)}")
    }

    /** `outDirRel` 为空时用 `成品/<name>/`（相对工作区根）。 */
    fun build(spec: Spec, root: File, outDirRel: String? = null): Built {
        val rel = outDirRel?.trim().takeUnless { it.isNullOrEmpty() } ?: "成品/${spec.name}"
        val outDir = File(root, rel)
        if (!outDir.canonicalPath.startsWith(root.canonicalPath)) {
            throw BuildError("输出目录越界：$rel")
        }
        val plan = LinkedHashMap<String, String>()
        when (spec.format) {
            FORMAT_WEB -> planWeb(spec, plan)
            FORMAT_TAVO -> {
                plan["card.json"] = tavoCardJson(spec)
                plan["lorebook.json"] = tavoLorebook(spec)
            }
            FORMAT_ST -> {
                plan["card.json"] = cardJson(spec)
            }
        }
        // 先全查再写（不产生半套产物）
        val targets = plan.keys.map { File(outDir, it) }
        targets.firstOrNull { it.exists() }?.let {
            throw BuildError("目标已存在，未覆盖：${it.name}（换 out_dir 或先清理）")
        }
        if (!outDir.exists() && !outDir.mkdirs()) throw BuildError("建不了输出目录：$rel")
        val written = ArrayList<File>()
        for ((name, text) in plan) {
            val f = File(outDir, name)
            f.writeText(text, Charsets.UTF_8)
            written += f
        }
        if (spec.format == FORMAT_ST) {
            val png = File(outDir, "card.png")
            when (val r = CardPng.export(cardJson(spec), CardPng.whiteOnePixelPng(), png)) {
                is PngWrite.Ok -> written += png
                is PngWrite.Failed -> throw BuildError("写 PNG 失败：${r.message}")
            }
        }
        return Built(outDir, written)
    }

    // ── web ──────────────────────────────────────────────────────────────

    /**
     * web 档的产物计划（599a）。
     *
     * 外壳默认裸数组、开关切整包、预检可配——差异全在 [WebPlatformBook] 的数据里；
     * 预检失败收成 [BuildError]（card_build 的失败口径只有一种）。
     */
    private fun planWeb(spec: Spec, plan: LinkedHashMap<String, String>) {
        val profile = WebPlatformProfile().withLimits(spec.entryLimit, spec.valueCharLimit)
        plan["world_book.json"] = try {
            WebPlatformBook.export(webEntries(spec), spec.wholePackage, spec.packageFields, profile)
        } catch (e: WebPlatformBook.PrecheckError) {
            throw BuildError(e.message ?: "网络平台族上限预检未通过")
        }
        plan["提示词.txt"] = listOf(spec.style, spec.persona).filter { it.isNotBlank() }.joinToString("\n\n")
        plan["前置词.txt"] = ""
        plan["后置词.txt"] = ""
        plan["内置CSS.txt"] = spec.css
        plan["详细介绍.html"] = spec.html.ifBlank { "<div class=\"gc\">（配置器骨架：待填）</div>" }
    }

    /**
     * web 档条目合成（599a 起交 [WebPlatformBook] 出盘）。
     *
     * 基础十字段与第四十五批一致；`value_type`／`value_configs` 是**可选字段**
     * （体检口径：平台合法省略）⇒ 母档没给就不写（拍板：有则写、无则不写），
     * 不再像旧版那样恒写 `value_type=0`／`value_configs=[]`。
     */
    fun webEntries(spec: Spec): List<JSONObject> {
        val key = "_or_" + spec.keywords.joinToString("@wb@")
        return spec.world.mapIndexed { i, v ->
            JSONObject()
                .put("key", key)
                .put("value", v)
                .put("group", "")
                .put("key_region", 3)
                .put("value_region", 1)
                .put("sort", i)
                .put("depth", 10)
                .put("probability", 100)
                .put("match_type", 2)
                .put("enable", true)
        }
    }

    // ── tavo / st 共用 CCv3 卡 ───────────────────────────────────────────

    fun cardJson(spec: Spec): String {
        val data = JSONObject()
            .put("name", spec.name)
            .put("description", spec.persona)
            .put("first_mes", "")
            .put("personality", "")
            .put("scenario", "")
            .put("mes_example", "")
            .put("creator", "card_build")
            .put("character_version", "0.0.1")
            .put("tags", JSONArray())
            .put("extensions", JSONObject())
        return JSONObject()
            .put("spec", "chara_card_v3")
            .put("spec_version", "3.0")
            .put("data", data)
            .toString(2) + "\n"
    }

    /** Tavo 卡：四件套包一层，`card_inspect` 才能认成 TAVO_CHAR。 */
    fun tavoCardJson(spec: Spec): String {
        val data = JSONObject()
            .put("name", spec.name)
            .put("description", spec.persona)
            .put("first_mes", "")
            .put("personality", "")
            .put("scenario", "")
            .put("mes_example", "")
            .put("creator", "card_build")
            .put("character_version", "0.0.1")
            .put("tags", JSONArray())
            .put("extensions", JSONObject())
        return JSONObject()
            .put("kind", "character")
            .put("id", 0)
            .put("name", spec.name)
            .put("updatedAt", "1970-01-01T00:00:00")
            .put("data", data)
            .toString(2) + "\n"
    }

    fun tavoLorebook(spec: Spec): String {
        val entries = JSONArray()
        if (spec.world.isEmpty()) {
            entries.put(
                JSONObject()
                    .put("keywords", JSONArray(spec.keywords))
                    .put("content", spec.persona)
                    .put("enabled", true)
                    .put("name", spec.name)
                    .put("scanDepth", 4)
                    .put("probability", 100)
                    .put("useRegex", false)
                    .put("sticky", 0)
                    .put("cooldown", 0)
                    .put("delay", 0),
            )
        } else {
            spec.world.forEachIndexed { i, v ->
                entries.put(
                    JSONObject()
                        .put("keywords", JSONArray(spec.keywords))
                        .put("content", v)
                        .put("enabled", true)
                        .put("name", "${spec.name}-$i")
                        .put("scanDepth", 4)
                        .put("probability", 100)
                        .put("useRegex", false)
                        .put("sticky", 0)
                        .put("cooldown", 0)
                        .put("delay", 0),
                )
            }
        }
        return JSONObject()
            .put("entries", entries)
            .put("name", spec.name)
            .toString(2) + "\n"
    }

    private fun strings(v: Any?): List<String> {
        if (v !is JSONArray) return emptyList()
        val out = ArrayList<String>(v.length())
        for (i in 0 until v.length()) {
            (v.opt(i) as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        }
        return out
    }
}
