package com.example.zhengdao.baopu.card

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 外部名册（W7，2026-10-19）。卡里 `extensions.world` / `regex_scripts` / `tavo.theme` /
 * 内嵌 `character_book` 要「和外部对一下」，但 App 里没有资产库；名册就是那份外部清单。
 *
 * 约定（零新参数）：
 * - 文件名固定 `名册.json`；查找顺序：卡文件同目录 → 同目录下 `.抱朴/名册.json` → 向上最多 3 层；
 * - 找不到 ⇒ `null`，体检沿用 F12–F16 的「未判」口径（不报错、不假装通过）；
 * - 文件坏了 ⇒ 也当 `null`（名册是**辅助**，坏名册不该让体检整份失败）。
 *
 * JSON 形状（字段都可省，省了就当"不知道"）：
 * ```json
 * {
 *   "worldbooks": ["永生·修真世界模拟器's Lorebook"],
 *   "assets": ["永生·修真世界模拟器"],
 *   "regexes": [{"name": "记忆块显示", "findRegex": "...", "replaceString": "..."}],
 *   "currentTheme": "渊海"
 * }
 * ```
 */
data class CardRoster(
    val worldbooks: Set<String>,
    val assets: Set<String>,
    /** 规范化后的正则键：`findRegex\u0000replaceString`；名字单独存一份便于报错。 */
    val regexKeys: Set<String>,
    val regexNames: Set<String>,
    val currentThemeName: String?,
) {
    fun hasWorldbook(name: String): Boolean = name in worldbooks
    fun hasAsset(name: String): Boolean = name in assets
    fun hasRegex(findRegex: String?, replaceString: String?): Boolean =
        regexKey(findRegex, replaceString) in regexKeys

    companion object {
        private const val DIR = ".抱朴"
        private const val FILE = "名册.json"
        private const val MAX_UP = 3

        /** 与卡文件同目录（或向上 3 层）找名册；找不到或读坏都返回 null。 */
        fun findNear(cardFile: File): CardRoster? {
            var dir: File? = cardFile.absoluteFile.parentFile
            var up = 0
            while (dir != null && up <= MAX_UP) {
                val direct = File(dir, FILE)
                if (direct.isFile) return load(direct)
                val nested = File(File(dir, DIR), FILE)
                if (nested.isFile) return load(nested)
                dir = dir.parentFile
                up++
            }
            return null
        }

        fun load(file: File): CardRoster? =
            runCatching { parse(file.readText(Charsets.UTF_8)) }.getOrNull()

        fun parse(json: String): CardRoster? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val worlds = strings(o.opt("worldbooks"))
            val assets = strings(o.opt("assets"))
            val keys = LinkedHashSet<String>()
            val names = LinkedHashSet<String>()
            (o.opt("regexes") as? JSONArray)?.let { a ->
                for (i in 0 until a.length()) {
                    val e = a.optJSONObject(i) ?: continue
                    e.optString("name").takeIf { it.isNotEmpty() }?.let { names += it }
                    keys += regexKey(e.optString("findRegex"), e.optString("replaceString"))
                }
            }
            val theme = o.opt("currentTheme")
            val themeName = (theme as? String)?.takeIf { it.isNotEmpty() }
                ?: (theme as? JSONObject)?.optString("name")?.takeIf { it.isNotEmpty() }
            return CardRoster(worlds, assets, keys, names, themeName)
        }

        fun regexKey(findRegex: String?, replaceString: String?): String =
            (findRegex ?: "").trim() + "\u0000" + (replaceString ?: "")

        private fun strings(v: Any?): Set<String> {
            val out = LinkedHashSet<String>()
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) {
                    (v.opt(i) as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
                }
                is String -> v.trim().takeIf { it.isNotEmpty() }?.let { out += it }
            }
            return out
        }
    }
}
