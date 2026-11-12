// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单 599 C 段（2026-11-11）：读 zip ⇒ card.json(V3)＋assets/；
//   写母档 ⇒ .charx（card.json＋assets/，data.assets[] 列清单）；损坏 zip 明确报错不吞；不联网。
// 外部事实（2026-11-12 核，CCv3 SPEC_V3.md，github.com/kwaroran/character-card-spec-v3）：
//   CHARX＝zip，根 `card.json`，资产放 `assets/{type}/…`；内嵌 URI 规范原文拼作 `embeded://path`；
//   Asset＝{type, uri, name, ext}，ext 小写不带点；规范枚举 icon/background/user_icon/emotion，
//   expression 是派工单实测列入的扩展类型 ⇒ type 照收不猜（规范允许自定义 type）。
// 复用：ArchiveRules（269 批）的 checkName 安全线／shouldStore 不压口径／MAX_TOTAL_BYTES 总量闸，
//   同一把尺不另造。ZipPacker/ZipExtractor 是 File 进 File 出的落盘件，本件是字节进字节出（零落盘），
//   故只复用规则、不复用壳。
// 边界（599cd）：只新增文件；CardFormat 对 zip 的 NOT_IN_SCOPE 行为不动（F18 照旧）。
package com.example.zhengdao.baopu.card

import com.example.zhengdao.baopu.archive.ArchiveRules
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * .charx 里的一件资产。
 *
 * [uri] 是清单原文（内嵌件为 `embeded://assets/{type}/{name}.{ext}`）；
 * [bytes]＝null 表示**未内嵌**（http/data 等远端 URI）——不联网取，只保留清单登记。
 */
internal data class CharxAsset(
    val type: String,
    val uri: String,
    val name: String,
    val ext: String,
    val bytes: ByteArray?,
)

/** 读出的 .charx：[card] 是 V3 全文（含 data.assets 清单），[assets] 与清单一一对应。 */
internal data class CharxCard(val card: JSONObject, val assets: List<CharxAsset>)

/**
 * `.charx` 读写（纯字节 API，零落盘、零网络、零 Android 依赖）。
 *
 * 写：V3 卡 JSON ＋ 资产字节 ⇒ zip；`data.assets[]` 清单以传入 assets 为准重写，
 * uri 由 type/name/ext 生成（清单与文件落点同一真源，不两处各拼一份）。
 * 读：zip ⇒ V3 卡 ＋ 按清单取回的资产字节；清单登记了但包里缺文件 ⇒ 报错不吞。
 */
internal object Charx {

    /** 读/写失败的唯一口径：话术说清卡在哪一步，调用方不用猜异常类型。 */
    class CharxError(message: String) : Exception(message)

    private const val CARD_ENTRY = "card.json"
    private const val ASSETS_DIR = "assets/"

    /** 规范原文拼写（embeded 不是 embedded），照抄不「修正」。 */
    private const val EMBEDED_SCHEME = "embeded://"
    private const val SPEC_V3 = "chara_card_v3"

    // ── 读 ─────────────────────────────────────────────────────────────

    fun read(bytes: ByteArray): CharxCard {
        val entries = readEntries(bytes)
        val cardBytes = entries[CARD_ENTRY]
            ?: throw CharxError("zip 里找不到 card.json：不是 .charx，或包已损坏/不完整。")
        val text = CardJson.decodeUtf8Strict(cardBytes)
            ?: throw CharxError("card.json 不是合法 UTF-8 文本。")
        val card = CardJson.parseObject(text)
            ?: throw CharxError("card.json 不是合法 JSON 对象。")
        val data = requireV3(card)
        return CharxCard(card, readAssets(data, entries))
    }

    /** 逐条目读进内存：名字过安全线、重名整包拒、总量过 2GB 闸；zip 结构损坏 ⇒ 明确报错。 */
    private fun readEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(bytes), Charsets.UTF_8).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.isDirectory) continue
                    val verdict = ArchiveRules.checkName(e.name)
                    if (verdict != ArchiveRules.NameVerdict.OK) {
                        throw CharxError(ArchiveRules.rejectReason(e.name, verdict))
                    }
                    if (out.containsKey(e.name)) {
                        throw CharxError("zip 里有重名条目：${e.name}（不猜用哪一份，整包拒读）。")
                    }
                    val data = zin.readBytes()
                    total += data.size
                    if (total > ArchiveRules.MAX_TOTAL_BYTES) {
                        throw CharxError("解压总量超上限 ${ArchiveRules.MAX_TOTAL_HUMAN}，整包拒读。")
                    }
                    out[e.name] = data
                }
            }
        } catch (e: IOException) {
            throw CharxError("zip 损坏或不是 zip（${e.javaClass.simpleName}: ${e.message ?: "无细节"}），读不下去。")
        }
        return out
    }

    /** V3 形状闸：spec 必须是 chara_card_v3 且 data 是对象（派工单 C 段只认 V3）。 */
    private fun requireV3(card: JSONObject): JSONObject {
        val spec = CardJson.stringOf(card, "spec")
        if (spec != SPEC_V3) {
            throw CharxError("card.json 的 spec 是「${spec ?: "缺"}」，本段只认 $SPEC_V3（V3）。")
        }
        return CardJson.objectOf(card, "data")
            ?: throw CharxError("card.json 缺 data 对象（V3 形状不完整）。")
    }

    /** 按 data.assets[] 清单取资产；清单缺键/缺文件都报错，不静默跳过。 */
    private fun readAssets(data: JSONObject, entries: Map<String, ByteArray>): List<CharxAsset> {
        val manifest = data.opt("assets") ?: return emptyList()
        if (manifest !is JSONArray) throw CharxError("data.assets 不是数组，清单读不出。")
        val out = ArrayList<CharxAsset>(manifest.length())
        for (i in 0 until manifest.length()) {
            val o = manifest.optJSONObject(i)
                ?: throw CharxError("data.assets[$i] 不是对象，清单读不出。")
            val type = CardJson.stringOf(o, "type")
            val uri = CardJson.stringOf(o, "uri")
            val name = CardJson.stringOf(o, "name")
            val ext = CardJson.stringOf(o, "ext")
            if (type == null || uri == null || name == null || ext == null) {
                throw CharxError("data.assets[$i] 缺 type/uri/name/ext 之一，清单读不出。")
            }
            out += CharxAsset(type, uri, name, ext, embeddedBytes(uri, entries))
        }
        return out
    }

    /** 内嵌 URI ⇒ 包内字节；远端/data URI ⇒ null（不联网）；登记了却缺文件 ⇒ 报错。 */
    private fun embeddedBytes(uri: String, entries: Map<String, ByteArray>): ByteArray? {
        val path = uri.removePrefix(EMBEDED_SCHEME)
        if (!path.startsWith(ASSETS_DIR)) return null
        return entries[path]
            ?: throw CharxError("清单登记的资产在 zip 里找不到：$uri（包不完整，不吞）。")
    }

    // ── 写 ─────────────────────────────────────────────────────────────

    /**
     * 母档（V3 卡 JSON 文本）＋资产 ⇒ .charx 字节。
     * `data.assets[]` 以传入 assets 为准重写；uri 由 type/name/ext 生成，传入的 uri 字段不参与。
     */
    fun write(cardJson: String, assets: List<CharxAsset>): ByteArray {
        val card = CardJson.parseObject(cardJson)
            ?: throw CharxError("母档卡 JSON 不是合法 JSON 对象。")
        val data = requireV3(card)
        data.put("assets", manifestOf(assets))
        val cardBytes = CardJson.encodeUtf8Strict(card.toString(2) + "\n")
            ?: throw CharxError("card.json 含无法严格 UTF-8 编码的字符（孤立代理项）。")
        return zipBytes(cardBytes, assets)
    }

    private fun manifestOf(assets: List<CharxAsset>): JSONArray {
        val arr = JSONArray()
        val seen = HashSet<String>()
        for (a in assets) {
            requirePart(a.type, "type")
            requirePart(a.name, "name")
            requirePart(a.ext, "ext")
            val uri = uriOf(a)
            if (!seen.add(uri)) {
                throw CharxError("资产清单重名：$uri（两件同 type/name/ext，不猜留哪件）。")
            }
            arr.put(JSONObject().put("type", a.type).put("uri", uri).put("name", a.name).put("ext", a.ext))
        }
        return arr
    }

    /** type/name/ext 是落点路径的原料：空名/路径分隔符/.. 一律拒（不给出歧义 uri 的机会）。 */
    private fun requirePart(v: String, what: String) {
        if (v.isBlank()) throw CharxError("资产 $what 不能为空。")
        if (v.contains('/') || v.contains('\\') || v.contains("..")) {
            throw CharxError("资产 $what 不许含路径分隔符或 ..（给的是「$v」）。")
        }
    }

    private fun uriOf(a: CharxAsset): String = "$EMBEDED_SCHEME$ASSETS_DIR${a.type}/${a.name}.${a.ext}"

    private fun zipBytes(cardBytes: ByteArray, assets: List<CharxAsset>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos, Charsets.UTF_8).use { zos ->
            zos.putEntry(CARD_ENTRY, cardBytes)
            for (a in assets) {
                val bytes = a.bytes
                    ?: throw CharxError("资产没有字节内容，无法打包（远端 URI 不联网取）：${a.type}/${a.name}.${a.ext}")
                zos.putEntry(uriOf(a).removePrefix(EMBEDED_SCHEME), bytes)
            }
        }
        return bos.toByteArray()
    }

    /** 已压缩格式（jpg/jpeg/mp4/zip）走 STORED 不压——口径与 ZipPacker 同一份规则。 */
    private fun ZipOutputStream.putEntry(name: String, bytes: ByteArray) {
        val e = ZipEntry(name)
        if (ArchiveRules.shouldStore(name)) {
            e.method = ZipEntry.STORED
            e.size = bytes.size.toLong()
            e.compressedSize = bytes.size.toLong()
            e.crc = CRC32().apply { update(bytes) }.value
        } else {
            e.method = ZipEntry.DEFLATED
        }
        putNextEntry(e)
        write(bytes)
        closeEntry()
    }
}