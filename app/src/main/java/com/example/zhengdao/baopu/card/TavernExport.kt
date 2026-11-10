// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十五批（2026-11-11）：酒馆格式兼容 · 落盘出口与撤销。
//
// 边界（派工单第三节逐条兑现）：
//  · 只做格式兼容：不实现插入深度算法、不做 token 预算裁剪；
//  · 导出产物进 `产物/`（目录常量不抄第二份，直接引 ZhengdaoDirs）；
//  · 导出**可撤销**：每次导出写一份回执，撤销只删「本次生成且内容未变」的文件；
//  · **不自动上传**：本文件零网络 API，源码门禁钉死。
//
// 落盘前先跑 TavernFields 的 schema 自检；不过就整次拒绝，不产生半套产物
// （与 CardBuild「先全查再写」同款）。
package com.example.zhengdao.baopu.card

import com.example.zhengdao.core.ZhengdaoDirs
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal data class TavernExportResult(
    val outDir: File,
    val files: List<File>,
    val receiptId: String,
    val losses: TavernLossReport,
    val schemaViolations: List<TavernFields.Violation>,
)

internal data class TavernUndoResult(
    val deleted: Int,
    val skipped: Int,
    val failed: Int,
)

internal class TavernExportError(message: String) : Exception(message)

internal object TavernExport {

    const val REL_DIR = "酒馆兼容"
    const val RECEIPT_DIR_REL = "_酒馆导出回执"
    const val FILE_CARD = "card.json"
    const val FILE_WORLD = "world.json"
    const val FILE_LOSSES = "_会丢字段.md"

    /** 目录真源：`产物/`（直接引 ZhengdaoDirs 的 const，编译期内联，不抄第二份）。 */
    const val PRODUCTS: String = ZhengdaoDirs.PRODUCTS_DIR

    /**
     * 导出两份 JSON 到 `<root>/产物/酒馆兼容/<name>/`。
     *
     * @param stamp 回执标识（调用方给；测试传固定值保证可复现）。
     */
    fun export(root: File, name: String, bundle: TavernBundleDto, stamp: String): TavernExportResult {
        val encoded = TavernCodec.encode(bundle)
        // 生产侧自检：不是「测试里才验」，导出当场就按酒馆 schema 验一遍。
        val violations = TavernFields.validateCard(encoded.cardJson) +
            TavernFields.validateWorld(encoded.worldJson)
        if (violations.isNotEmpty()) {
            throw TavernExportError("导出未落盘：schema 自检不通过（${violations.first().line}）")
        }

        val outDir = File(File(root, PRODUCTS), "$REL_DIR/${safeName(name)}")
        if (!outDir.canonicalPath.startsWith(File(root, PRODUCTS).canonicalPath)) {
            throw TavernExportError("输出目录越界：${outDir.path}")
        }
        val plan = LinkedHashMap<String, String>()
        plan[FILE_CARD] = encoded.cardJson
        plan[FILE_WORLD] = encoded.worldJson
        if (!encoded.losses.isEmpty) plan[FILE_LOSSES] = encoded.losses.markdown()

        // 先全查再写：任一目标已存在整次拒绝，绝不覆盖用户文件。
        val targets = plan.keys.map { File(outDir, it) }
        targets.firstOrNull { it.exists() }?.let {
            throw TavernExportError("目标已存在，未覆盖：${it.name}（换 name 或先撤销上次导出）")
        }
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw TavernExportError("建不了输出目录：${outDir.path}")
        }
        val written = ArrayList<File>()
        for ((n, text) in plan) {
            val f = File(outDir, n)
            val bytes = CardJson.encodeUtf8Strict(text)
                ?: throw TavernExportError("$n 含非法 UTF-8 字符，未写出")
            f.writeBytes(bytes)
            written += f
        }
        val receiptId = writeReceipt(root, stamp, written)
        return TavernExportResult(outDir, written, receiptId, encoded.losses, violations)
    }

    /**
     * 撤销一次导出：只删回执里登记、**且 sha256 仍与导出时一致**的文件。
     * 用户后来改过的文件一律跳过（不覆盖、不误删），空目录不递归删。
     */
    fun undo(root: File, receiptId: String): TavernUndoResult {
        val receipt = File(File(File(root, PRODUCTS), RECEIPT_DIR_REL), "$receiptId.json")
        val text = runCatching { receipt.readText(Charsets.UTF_8) }.getOrNull()
            ?: return TavernUndoResult(0, 0, 1)
        val arr = runCatching { JSONObject(text).optJSONArray("files") }.getOrNull()
            ?: return TavernUndoResult(0, 0, 1)
        var deleted = 0
        var skipped = 0
        var failed = 0
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val f = File(row.optString("path"))
            val expected = row.optString("sha256")
            when {
                !f.isFile -> skipped++
                sha256(f) != expected -> skipped++ // 内容变了 ⇒ 用户动过，绝不删
                runCatching { f.delete() }.getOrDefault(false) -> deleted++
                else -> failed++
            }
        }
        if (deleted > 0) runCatching { receipt.delete() }
        return TavernUndoResult(deleted, skipped, failed)
    }

    /** 回执也是程序生成 JSON，不手写。 */
    private fun writeReceipt(root: File, stamp: String, files: List<File>): String {
        val id = "tavern-$stamp"
        val dir = File(File(root, PRODUCTS), RECEIPT_DIR_REL)
        dir.mkdirs()
        val rows = JSONArray()
        for (f in files) {
            rows.put(
                JSONObject()
                    .put("path", f.absolutePath)
                    .put("sha256", sha256(f)),
            )
        }
        File(dir, "$id.json").writeText(
            JSONObject().put("id", id).put("files", rows).toString(2) + "\n",
            Charsets.UTF_8,
        )
        return id
    }

    private fun safeName(name: String): String {
        val cleaned = name.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }.joinToString("")
        return cleaned.take(48).ifEmpty { "未命名" }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hex = md.digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        return hex
    }
}
