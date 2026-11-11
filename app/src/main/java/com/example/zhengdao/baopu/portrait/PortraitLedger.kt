// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百九十七批（2026-11-12）：appearance 账本——**草稿**（判据③）＋**落账**（判据⑤）。
//
// 一个文件两件事：`<工作区>/产物/.appearance.jsonl`（点号开头 ⇒ 不计入「产物 N 件」，
// 与 `.conflicts.jsonl`/`.leases.jsonl` 同一口径）。三类行（`op` 区分）：
// - `look`／`describe`：**落账**（判据⑤）——每张图读到什么、用的哪个模型、是否外发；
// - `draft`：**外貌草稿**（判据③）——只是草稿，人批（CONFIRM 卡）之后 merge 才进卡；
// - `merge`：进卡留痕——哪份草稿、进了哪张卡（草稿不删行，账只追加）。
//
// 纪律：
// 1. **只追加**（append-only）：错了不改写历史，后行覆盖语义（merge 行引用 draft id）；
// 2. **坏行跳过**：账是给人看的证据，一行坏了不让整本读不出来；
// 3. 行里**没有图片字节、没有 dataUrl**（判据⑦：图片不写进产物）——只有路径与颜色文本；
// 4. 零 Android；JSON 用 org.json（与 card 包同一件）。
package com.example.zhengdao.baopu.portrait

import com.example.zhengdao.baopu.MemoryLayout
import java.io.File
import org.json.JSONObject

/** 一条外貌草稿（判据③：人批后才进卡；这里只是"账上的一行"，不是卡）。 */
data class PortraitDraft(
    val id: String,
    val ts: Long,
    val character: String,
    val image: String,
    val appearance: String,
    /** 本地特征摘要（发/瞳/服饰/宽高比/取景），给人批时看来历用。 */
    val features: String,
    /** 来源：`本地`（纯本地特征）或视觉模型 id（走 CONFIRM 外发那次）。 */
    val model: String,
    val sentOut: Boolean,
)

/** 一条落账（判据⑤：每张图读到什么、用的哪个模型、是否外发）。 */
data class PortraitAccount(
    val ts: Long,
    val op: String,
    val image: String,
    val model: String,
    val sentOut: Boolean,
    val ok: Boolean,
    val note: String,
)

internal object PortraitLedger {

    /** 账本文件名（点号开头：不进「产物 N 件」计数）。 */
    const val FILE_NAME = ".appearance.jsonl"

    const val OP_LOOK = "look"
    const val OP_DESCRIBE = "describe"
    const val OP_DRAFT = "draft"
    const val OP_MERGE = "merge"

    fun file(workspace: File): File = File(MemoryLayout.productsDir(workspace), FILE_NAME)

    // ── 写（只追加）────────────────────────────────────────────────────────

    fun appendAccount(workspace: File, a: PortraitAccount) {
        append(
            workspace,
            JSONObject()
                .put("ts", a.ts)
                .put("op", a.op)
                .put("image", a.image)
                .put("model", a.model)
                .put("sentOut", a.sentOut)
                .put("ok", a.ok)
                .put("note", a.note),
        )
    }

    /** 追加一条草稿并回填 id（id 由 ts＋现有 draft 行数拼成；同毫秒不撞：尾号递增）。 */
    fun appendDraft(workspace: File, d: PortraitDraft): PortraitDraft {
        val seq = drafts(workspace).size + 1
        val withId = d.copy(id = "pad-${d.ts}-$seq")
        append(
            workspace,
            JSONObject()
                .put("ts", withId.ts)
                .put("op", OP_DRAFT)
                .put("id", withId.id)
                .put("character", withId.character)
                .put("image", withId.image)
                .put("appearance", withId.appearance)
                .put("features", withId.features)
                .put("model", withId.model)
                .put("sentOut", withId.sentOut),
        )
        return withId
    }

    /** 进卡留痕（判据③的"人批后"那一步；人批本体是 CONFIRM 卡，这里只记账）。 */
    fun appendMerged(workspace: File, draftId: String, cardPath: String, ts: Long) {
        append(
            workspace,
            JSONObject()
                .put("ts", ts)
                .put("op", OP_MERGE)
                .put("id", draftId)
                .put("card", cardPath),
        )
    }

    private fun append(workspace: File, row: JSONObject) {
        val f = file(workspace)
        f.parentFile?.mkdirs()
        f.appendText(row.toString().plus("\n"), Charsets.UTF_8)
    }

    // ── 读（坏行跳过）──────────────────────────────────────────────────────

    fun rows(workspace: File): List<JSONObject> {
        val f = file(workspace)
        if (!f.isFile) return emptyList()
        return f.readLines(Charsets.UTF_8).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            runCatching { JSONObject(line) }.getOrNull()
        }
    }

    fun drafts(workspace: File): List<PortraitDraft> = rows(workspace)
        .filter { it.optString("op") == OP_DRAFT }
        .map {
            PortraitDraft(
                id = it.optString("id"),
                ts = it.optLong("ts"),
                character = it.optString("character"),
                image = it.optString("image"),
                appearance = it.optString("appearance"),
                features = it.optString("features"),
                model = it.optString("model"),
                sentOut = it.optBoolean("sentOut"),
            )
        }

    fun draftById(workspace: File, id: String): PortraitDraft? =
        drafts(workspace).firstOrNull { it.id == id }

    /** 已进过卡的草稿 id（判据③：一份草稿只进一次卡；重复 merge 拒）。 */
    fun mergedDraftIds(workspace: File): Set<String> = rows(workspace)
        .filter { it.optString("op") == OP_MERGE }
        .map { it.optString("id") }
        .toSet()

    fun accounts(workspace: File): List<PortraitAccount> = rows(workspace)
        .filter { it.optString("op") == OP_LOOK || it.optString("op") == OP_DESCRIBE }
        .map {
            PortraitAccount(
                ts = it.optLong("ts"),
                op = it.optString("op"),
                image = it.optString("image"),
                model = it.optString("model"),
                sentOut = it.optBoolean("sentOut"),
                ok = it.optBoolean("ok"),
                note = it.optString("note"),
            )
        }
}
