// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十二批 · 归因**增量游标**：记「上次判到哪一行」，把 [ReflectionAttributor] 从
// 「每次整本重判」改成「只判游标之后的新行」。
//
// 为什么单独一件（不改判据）：判据（[ReflectionMisjudgeJudge] / [AttributionGate]）一个字不动，
// 本文件只回答一个 IO 问题——「这本档案上次判到哪里了」，以及这个答案坏了怎么办。
//
// 落点＝`<工作区>/产物/进化档案.游标.json`（**与档案同屋**），理由三条：
//   1. 游标描述的是**这一本档案**，档案搬走/复制走，游标跟着走才不会错配；
//   2. 多工作区天然隔离：每个工作区一棵 `产物/`，A 区的游标永远读不到 B 区的档案；
//      放进全局 `抱朴记忆/`（判决经验账那棵）就会多区共用一份 ⇒ 必错；
//   3. 进化档案只读的纪律不变：游标是**另一个文件**，档案一个字节不碰。
//
// 双保险＝行号＋该行内容 hash：
//   · 行号会因档案追加/重写而漂，单看行号不可靠；
//   · hash 覆盖「档案被截短/被改写/被整本替换」——对不上就等于游标作废，
//     作废一律**退化为全本重建一次**（宁重判一遍，不漏判一行），并记一行日志。
//
// 并发（诚实写法）：同进程多线程由 [AttributionCursorFile.lock] 串行；跨进程没有文件锁——
// 最坏结果是两边各写一次游标（后写覆盖先写）⇒ 下次要么正常增量、要么退化为一次全本重判。
// 两条都不会漏判：落账口还有第二道去重（[AttributionWiring]），本文件只负责"少判"。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.MemoryLayout
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** 一行档案的指纹：trim 后内容的 SHA-256 前 8 字节（16 个十六进制字符）。 */
internal fun archiveLineHash(raw: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.trim().toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(16)
    for (i in 0 until 8) {
        val v = digest[i].toInt() and 0xFF
        sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
    return sb.toString()
}

private const val HEX = "0123456789abcdef"

/**
 * 归因游标（落盘的三件：版本在文件里、行号、该行指纹）。
 *
 * @param lastLineNo 上次判到的**物理行号**（1 起；指向最后一条解析出来的档案行）
 * @param lastLineHash 该行内容的指纹（行号漂移/档案改写都靠它对不上来自证作废）
 */
internal data class AttributionCursor(
    val lastLineNo: Int,
    val lastLineHash: String,
)

/**
 * 读游标的结果。
 *
 * @param cursor null ⇒ 调用方**退化全本重建一次**
 * @param note 游标不可用的一句话（缺失/损坏/作废三选一；可用时为空串）——调用方照这句记一行日志
 */
internal data class CursorLoad(val cursor: AttributionCursor?, val note: String)

/** 游标的落盘口（文件形状：一行 JSON；坏一行只影响本文件，绝不带崩归因）。 */
internal object AttributionCursorFile {

    /** 与进化档案同屋（`<工作区>/产物/`），文件名跟着档案叫。 */
    const val FILE_NAME = "进化档案.游标.json"

    /** 文件形状版本（改形状才 +1；对不上按损坏处理）。 */
    const val VERSION = 1

    const val NOTE_ABSENT = "游标缺失（首次运行或刚清过）"
    const val NOTE_CORRUPT = "游标损坏（读不出或版本对不上）"
    const val NOTE_STALE = "游标作废（行号越界或该行内容已变）"

    private val lock = Any()

    fun fileIn(wsRoot: File): File = File(MemoryLayout.productsDir(wsRoot), FILE_NAME)

    /**
     * 读并校验：缺失、坏 JSON、版本不符、行号越界、**该行指纹不符** ⇒ cursor=null＋一句原因。
     *
     * @param lines 档案全文按行切好的样子（与 `rowsWithLineNo` 同一个切法，行号才对得上）
     */
    fun load(wsRoot: File, lines: List<String>): CursorLoad {
        val f = fileIn(wsRoot)
        if (!f.isFile) return CursorLoad(null, NOTE_ABSENT)
        val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return CursorLoad(null, NOTE_CORRUPT)
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return CursorLoad(null, NOTE_CORRUPT)
        if (obj.optInt("v", -1) != VERSION) return CursorLoad(null, NOTE_CORRUPT)
        val lineNo = obj.optInt("line", 0)
        val hash = obj.optString("hash")
        if (lineNo <= 0 || hash.isEmpty()) return CursorLoad(null, NOTE_CORRUPT)
        val raw = lines.getOrNull(lineNo - 1) ?: return CursorLoad(null, NOTE_STALE)
        if (archiveLineHash(raw) != hash) return CursorLoad(null, NOTE_STALE)
        return CursorLoad(AttributionCursor(lineNo, hash), "")
    }

    /**
     * 落游标（先写临时件再改名：半截文件不会冒充好游标）。
     *
     * @return false＝没落上（调用方记一行；下次自动退化为全本重建，不漏判）
     */
    fun save(wsRoot: File, cursor: AttributionCursor): Boolean = synchronized(lock) {
        val f = fileIn(wsRoot)
        val dir = f.parentFile
        val body = JSONObject()
            .put("v", VERSION)
            .put("line", cursor.lastLineNo)
            .put("hash", cursor.lastLineHash)
            .toString() + "\n"
        var ok = false
        runCatching {
            dir?.mkdirs()
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(body, Charsets.UTF_8)
            // Windows 的 renameTo 不覆盖同名目标：先试直改，失败再删旧名重试一次
            ok = tmp.renameTo(f) || (f.delete() && tmp.renameTo(f))
        }
        ok
    }
}
