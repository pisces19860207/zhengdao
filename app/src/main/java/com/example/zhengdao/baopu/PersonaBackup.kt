// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第三十七批 D 段（2026-10-19 用户口径）：「人设写入前自动备份；设置页加一行
// 『人设：恢复到上一版』显示最近备份时间戳；点它 ⇒ 恢复并先备份当前版（可反悔）；
// 没有备份时该行灰掉，但不许消失。」
//
// 为什么单独一个类而不是照抄 `SkillStore.restore`（房型照，但不合体）：
//   1. 技能备份是 `<名>.SKILL.md.bak-<stamp>`（在技能目录里），人设是单文件
//      `人设-本项目.md.bak-<stamp>`（就在工作区/App 私有的「抱朴记忆」里）——
//      落点不同，不能共用一套拼名字的逻辑；
//   2. 技能可以归档、可以删；**人设永远不删**（本文件故意没有 delete）；
//   3. 回滚人设比回滚技能更要紧：人设错了整轮对话都歪，所以恢复本身也必须先备份
//      （`SkillStore.restore` 同样这么做，这里把这条做成不变的判据）。
//
// 命名沿用 `SkillStore.BACKUP_MARK` 的口径（`.bak-<yyyyMMdd-HHmmss>`），
// 时间戳一律走 `TimeFormats.fileStamp()`（门禁 B-补-24：主源码不许裸 SimpleDateFormat）。
package com.example.zhengdao.baopu

import com.example.zhengdao.util.TimeFormats
import java.io.File

/** 备份/恢复的结果。失败不许静默——设置页要能把 `reason` 直接弹给用户看。 */
sealed class PersonaBackupResult {
    /** 成功。`backup` 是这次留下的那份（恢复时＝当前版的备份，可在设置页再滚回来）。 */
    data class Done(val backup: File) : PersonaBackupResult()

    /** 拒绝（没有备份可恢复／写盘抛错）。文件一个字节都没动。 */
    data class Rejected(val reason: String) : PersonaBackupResult()
}

/**
 * 人设的备份与回滚（**纯函数 + 薄 I/O**，JVM 临时目录可直测）。
 *
 * 判据（见 `PersonaBackupTest`）：
 * - 写入前备份：目标文件存在才备份；首次写入（文件还没有）不产生空备份；
 * - 恢复前也备份：恢复本身是一次写盘，先存当前版 ⇒ 可反悔；
 * - 无备份 ⇒ `Rejected`，源文件不动；
 * - 同一秒内写两次不许互相覆盖（`-2`、`-3` 递增）。
 */
internal object PersonaBackup {

    /**
     * 备份名里的标记。与 `SkillStore.BACKUP_MARK` 同口径（`.bak-`），
     * 这样用户在文件管理器里一眼能看出「这是哪次写之前的版本」。
     */
    const val MARK = ".bak-"

    /** `人设-本项目.md` + `20261019-101500` ⇒ `人设-本项目.md.bak-20261019-101500`。 */
    fun backupNameFor(baseName: String, stamp: String): String = "$baseName$MARK$stamp"

    /** 某个文件名是不是 [baseName] 的备份（**必须**带完整 `baseName` 前缀，防别的文件混进来）。 */
    fun isBackupOf(name: String, baseName: String): Boolean = name.startsWith("$baseName$MARK")

    /** 从备份文件名取回时间戳；不是备份 ⇒ null。 */
    fun stampOf(name: String, baseName: String): String? =
        if (isBackupOf(name, baseName)) name.substring("$baseName$MARK".length) else null

    /**
     * 时间戳 → 给人看的样子（`20261019-101500` ⇒ `2026-10-19 10:15:00`）。
     * 认不出来就原样返回（**不抛异常**：设置页宁可显示怪样子也不能崩）。
     */
    fun displayStamp(stamp: String): String {
        val fmt = Regex("""^(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})$""")
        // 先按原样认；认不出再剥掉同秒去重后缀（`-2`）重试。注意**不能**先剥：
        // 正常时间戳本身就带一个 `-`（`20261019-101500`），先剥会把后半截吃掉。
        val m = fmt.find(stamp) ?: fmt.find(stamp.substringBeforeLast('-')) ?: return stamp
        val (y, mo, d, h, mi, s) = m.destructured
        return "$y-$mo-$d $h:$mi:$s"
    }

    /** 一份文件的全部备份，**新 → 旧**（时间戳定长 ⇒ 字典序即时间序）。 */
    fun backupsOf(file: File): List<File> {
        val dir = file.parentFile ?: return emptyList()
        val base = file.name
        return dir.listFiles { f -> f.isFile && isBackupOf(f.name, base) }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }

    /** 最近一份备份；没有 ⇒ null（设置页据此把按钮灰掉，**但不隐藏这一行**）。 */
    fun latestOf(file: File): File? = backupsOf(file).firstOrNull()

    /**
     * 写前备份：把 [file] 当前内容复制成 `file.name + .bak-<stamp>`。
     *
     * - 文件不存在 ⇒ 返回 null（首次写入没什么可备份，也**不许**造一个空备份出来
     *   骗「可回滚」——那会让用户恢复成空文件）；
     * - 同一秒内重复写 ⇒ 名字加 `-2`、`-3`，**绝不覆盖**已存的那份。
     */
    fun backupBefore(file: File, stamp: String): File? {
        if (!file.isFile) return null
        val dir = file.parentFile ?: return null
        val name = freeNameFor(dir, file.name, stamp)
        return runCatching {
            val dest = File(dir, name)
            file.copyTo(dest, overwrite = false)
            dest
        }.getOrNull()
    }

    /**
     * 恢复到上一版（最近一份备份）。
     *
     * **先备份当前版，再覆盖**——顺序不许反：反了就等于「用备份换掉当前版，
     * 换完发现不想要也没得退」（L6 的「恢复本身也先备份」）。
     */
    fun restore(file: File, stamp: String): PersonaBackupResult {
        val latest = latestOf(file) ?: return PersonaBackupResult.Rejected(NO_BACKUP)
        if (!file.isFile) {
            // 文件被外面删了：备份还在，直接放回去（此时没有「当前版」可备份）。
            return runCatching {
                latest.copyTo(file, overwrite = true)
                PersonaBackupResult.Done(latest)
            }.getOrElse { PersonaBackupResult.Rejected("恢复失败：${it.javaClass.simpleName}") }
        }
        return runCatching {
            val cur = backupBefore(file, stamp)
                ?: return PersonaBackupResult.Rejected("恢复失败：备份当前版没成功（没有动原文件）")
            latest.copyTo(file, overwrite = true)
            PersonaBackupResult.Done(cur)
        }.getOrElse { PersonaBackupResult.Rejected("恢复失败：${it.javaClass.simpleName}") }
    }

    /** 没有备份时的固定话术（设置页直接显示，测试也钉这句话）。 */
    const val NO_BACKUP = "人设还没有备份，没有可恢复的版本"

    /** 时间戳默认值：全部在这里取，调用方不自己造格式。 */
    fun nowStamp(): String = TimeFormats.fileStamp()

    /** 找一个没被占用的备份名（同秒去重）。 */
    private fun freeNameFor(dir: File, baseName: String, stamp: String): String {
        val first = backupNameFor(baseName, stamp)
        if (!File(dir, first).exists()) return first
        var i = 2
        while (true) {
            val next = backupNameFor(baseName, "$stamp-$i")
            if (!File(dir, next).exists()) return next
            i++
        }
    }
}
