// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百五十三批 · 八环读数的**账本**：只追加＋按天切分＋10MB 滚动（派工单 753 ①④；判据④⑤）。
//
// 房型照 678 ReplayLedger／676 回响账：落点 `<工作区>/产物/`（DIR_PRODUCTS 真源，用户可见产物区）。
// 两条硬规矩（判据逐字落码）：
//   ① 只追加：本 object 没有重写口——现行文件只走 appendText；滚动只做**改名**
//     （renameTo 字节不动，判据④「改写历史行 ⇒ 拒」在结构上成立）；旧文件永不删（判据⑤）。
//   ② 收行前先 validate：判废的行一行都不落（判据②③的账面保证）；「拒」要说清，不静默。
//
// 文件名册：现行＝`环读数.jsonl`（716 真机轮就抓它）；封卷＝`环读数-<天>.jsonl`（同天多卷
// 加序号 -2/-3…）；首行读不出天键的坏文件＝`环读数-残卷.jsonl`（封存不删、不覆盖）。
package com.example.zhengdao.baopu.loops

import com.example.zhengdao.baopu.kernel.BaopuKernel
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** 八环读数账（自身产出；只追加、按天切分、超 10MB 滚动，旧文件不删）。 */
internal object LoopReadingsLedger {

    /** 现行文件（派工单 753 ①：落 `产物/环读数.jsonl`）。 */
    const val FILE_NAME = "环读数.jsonl"

    /** 滚动线：现行文件到 10MB 就封卷（判据⑤「超过 10MB 换新文件」；达线即封，宁早勿超）。 */
    const val ROLL_BYTES = 10L * 1024 * 1024

    private const val ROLLED_STEM = "环读数-"

    /** 首行读不出天键的现行文件的封卷名（不猜天、不覆盖、不删——残卷给验收翻）。 */
    private const val CORRUPT_DAY = "残卷"

    /** 封卷序号上限（同天同卷名的防撞尝试次数；过了就让 renameTo 自己失败并如实报）。 */
    private const val MAX_SUFFIX = 10_000

    fun fileOf(workspace: File): File = File(File(workspace, BaopuKernel.Data.DIR_PRODUCTS), FILE_NAME)

    /** 现行＋已封卷的全部环读数文件（按名排序；旧文件不删，716 按天回看）。 */
    fun filesOf(workspace: File): List<File> {
        val dir = File(workspace, BaopuKernel.Data.DIR_PRODUCTS)
        return dir.listFiles { _, name ->
            name == FILE_NAME || (name.startsWith(ROLLED_STEM) && name.endsWith(".jsonl"))
        }?.sortedBy(File::getName) ?: emptyList()
    }

    /** 收一行（先判后收；判废/失败都带原因返回，不静默）。 */
    fun append(
        workspace: File,
        row: LoopReadingRow,
        dayKey: (Long) -> String = ::dayKeyOf,
        rollBytes: Long = ROLL_BYTES,
    ): AppendOutcome = synchronized(this) { appendLocked(workspace, row, dayKey, rollBytes) }

    /** 读回一个文件的全部合法行（坏行跳过；逐行点名另有 [audit]）。 */
    fun read(file: File): List<LoopReadingRow> = runCatching {
        if (!file.isFile) emptyList() else file.readLines(Charsets.UTF_8).mapNotNull(::parseLine)
    }.getOrDefault(emptyList())

    /**
     * 账面审计（判据①②③④的文件侧断言口）：一行一句问题，返空表＝整卷干净。
     * 缺 ref／unwired 缺 needTicket／九环名／白名单外键／手改过的历史行——都会在这里被点名。
     */
    fun audit(file: File): List<String> {
        if (!file.isFile) return emptyList()
        return runCatching {
            file.readLines(Charsets.UTF_8).withIndex().flatMap { (no, line) -> auditLine(no + 1, line) }
        }.getOrDefault(emptyList())
    }

    private fun auditLine(no: Int, line: String): List<String> {
        if (line.isBlank()) return emptyList()
        val row = parseLine(line) ?: return listOf("第${no}行 解析失败（坏 JSON／九环名／缺 ts，都算坏行）")
        return validate(row).map { "第${no}行 $it" }
    }

    private fun appendLocked(
        workspace: File,
        row: LoopReadingRow,
        dayKey: (Long) -> String,
        rollBytes: Long,
    ): AppendOutcome {
        val problems = validate(row)
        if (problems.isNotEmpty()) return AppendOutcome.Rejected(problems)
        val dir = File(workspace, BaopuKernel.Data.DIR_PRODUCTS)
        if (!dir.isDirectory && !dir.mkdirs()) return AppendOutcome.Failed("产物目录建不了：$dir")
        val active = File(dir, FILE_NAME)
        val target = rollTarget(dir, active, row, dayKey, rollBytes)
        var sealed: String? = null
        if (target != null) {
            if (!active.renameTo(target)) {
                return AppendOutcome.Failed("封卷改名失败：${active.name} → ${target.name}（行没落上，不静默）")
            }
            sealed = target.name
        }
        return runCatching {
            active.appendText(encode(row) + "\n", Charsets.UTF_8)
            AppendOutcome.Appended(file = active, rolledFrom = sealed)
        }.getOrElse { AppendOutcome.Failed("追加失败：${it.message}") }
    }

    /** 滚动决定：返 null＝不滚；返目标＝要封卷到该处（改名在 appendLocked 做，字节不动）。 */
    private fun rollTarget(
        dir: File,
        active: File,
        row: LoopReadingRow,
        dayKey: (Long) -> String,
        rollBytes: Long,
    ): File? {
        if (!active.isFile || active.length() == 0L) return null
        val activeDay = activeDayKey(active, dayKey)
        val today = dayKey(row.ts)
        return when {
            activeDay == null -> sealTo(dir, CORRUPT_DAY)
            activeDay != today -> sealTo(dir, activeDay)
            active.length() >= rollBytes -> sealTo(dir, activeDay)
            else -> null
        }
    }

    /** 封卷去处：`环读数-<天>.jsonl`（残卷另名）；撞了找下一个空位（永不覆盖）。 */
    private fun sealTo(dir: File, day: String): File = File(dir, nextName(dir, day))

    /** 滚动目标名：`环读数-<天>.jsonl`；撞了加序号 -2/-3…，取第一个不存在的（判据⑤旧文件不删）。 */
    private fun nextName(dir: File, day: String): String {
        val base = "$ROLLED_STEM$day"
        var candidate = "$base.jsonl"
        var seq = 2
        while (seq <= MAX_SUFFIX && File(dir, candidate).exists()) {
            candidate = "$base-$seq.jsonl"
            seq += 1
        }
        return candidate
    }

    /** 现行文件的天键＝首行 ts 的天；首行读不出（坏 JSON／缺 ts）⇒ null（按残卷处理）。 */
    private fun activeDayKey(active: File, dayKey: (Long) -> String): String? {
        val first = runCatching {
            active.useLines(Charsets.UTF_8) { lines -> lines.firstOrNull() }
        }.getOrNull() ?: return null
        return parseLine(first)?.let { dayKey(it.ts) }
    }
}

/** 收行结果：收上（含是否封了卷）／判废（问题清单）／失败（I/O，行没落上）。 */
internal sealed class AppendOutcome {
    data class Appended(val file: File, val rolledFrom: String?) : AppendOutcome()
    data class Rejected(val problems: List<String>) : AppendOutcome()
    data class Failed(val why: String) : AppendOutcome()
}

/** 默认天键：本机时区的 ISO 日（yyyy-MM-dd）。测试注入自己的天键，不碰真钟。 */
internal fun dayKeyOf(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString()
