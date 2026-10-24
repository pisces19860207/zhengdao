// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第一百六十五批（人设包 v1，2026-10-23）§1 存储、§2 能力、§3 判据。
//
// 文件是真相：<工作区>/抱朴记忆/人设/<名字>/persona.md（人可读、可导入/导出/分享），
// pack.json 只是可选说明。App 私有目录不存包，卸载重装后重新扫描工作区即可恢复。
// 本类只吃 java.io.File，不碰 Context、不建线程 ⇒ 临时目录 JVM 直测。
package com.example.zhengdao.baopu

import java.io.File
import java.io.InputStream

/**
 * 人设包的工作区存储。
 *
 * 安全口径（判据 §3）：所有读写只落在 [root] 之下；名字先过
 * [PersonaPackRules.normalizeName]，路径再按 canonical 比较一次；删除**只删**
 * persona.md 与 pack.json 两个自有文件，绝不递归删目录（日记/产物/记忆不归它管）。
 */
internal class PersonaPackStore(private val workspace: File) {

    fun root(): File = File(File(workspace, PersonaPackRules.DIR_ROOT), PersonaPackRules.DIR_PACKS)

    /** 列出所有可读目录（坏说明文件照列，只是带一句原因）。 */
    fun list(): List<PersonaPackSummary> {
        val base = runCatching { root().canonicalFile }.getOrNull() ?: return emptyList()
        val dirs = base.listFiles { f -> f.isDirectory && inside(base, f) } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val manifestText = readTextLimited(File(dir, PersonaPackRules.FILE_MANIFEST), PersonaPackRules.MAX_MANIFEST_BYTES)
            val manifest = PersonaPackRules.parseManifest(manifestText)
            val persona = File(dir, PersonaPackRules.FILE_PERSONA)
            val problem = when {
                !persona.isFile -> "persona.md"
                persona.length() > PersonaPackRules.MAX_BYTES -> "too_large"
                else -> manifest.problem
            }
            PersonaPackSummary(
                dirName = dir.name,
                name = manifest.name ?: dir.name,
                description = manifest.description,
                bytes = if (persona.isFile) persona.length() else 0L,
                manifestProblem = problem,
            )
        }.sortedBy { it.name.lowercase() }
    }

    /** 读一套；所有失败都返回可读分类，不抛。 */
    fun load(dirName: String): PersonaPackResult {
        val dir = packDir(dirName) ?: return PersonaPackResult.Rejected(PersonaPackError.NAME_INVALID)
        if (!dir.isDirectory) return PersonaPackResult.Rejected(PersonaPackError.NOT_FOUND)
        val file = File(dir, PersonaPackRules.FILE_PERSONA)
        if (!file.isFile) return PersonaPackResult.Rejected(PersonaPackError.NOT_FOUND)
        if (file.length() > PersonaPackRules.MAX_BYTES) {
            return PersonaPackResult.Rejected(PersonaPackError.TOO_LARGE, ">${PersonaPackRules.MAX_BYTES}B")
        }
        val bytes = runCatching { file.readBytes() }.getOrNull()
            ?: return PersonaPackResult.Rejected(PersonaPackError.IO, PersonaPackRules.FILE_PERSONA)
        return decodePayload(dir, bytes)
    }

    /** 新建：名字不合法 / 正文不合法 ⇒ 拒绝；重名自动加 `-2`、`-3`（绝不覆盖已有包）。 */
    fun create(name: String, text: String): PersonaPackResult {
        val safe = PersonaPackRules.normalizeName(name)
            ?: return PersonaPackResult.Rejected(PersonaPackError.NAME_INVALID)
        val problem = PersonaPackRules.textProblem(text)
        if (problem != null) return PersonaPackResult.Rejected(problem)
        val base = ensureRoot() ?: return PersonaPackResult.Rejected(PersonaPackError.IO, "mkdirs")
        val dir = uniqueDir(base, safe)
        return write(dir, normalizeText(text))
    }

    /**
     * 导入：流式读取，最多读上限＋1 字节即可判超限（不把大文件整个吞进内存）。
     * 显示名只用来起目录名；起不出来时用 [fallbackName]（由界面按语言给）。
     */
    fun importStream(displayName: String, fallbackName: String, input: InputStream): PersonaPackResult {
        val bytes = runCatching { readLimited(input, PersonaPackRules.MAX_BYTES) }.getOrNull()
            ?: return PersonaPackResult.Rejected(PersonaPackError.IO, "read")
        if (bytes.size > PersonaPackRules.MAX_BYTES) {
            return PersonaPackResult.Rejected(PersonaPackError.TOO_LARGE, ">${PersonaPackRules.MAX_BYTES}B")
        }
        val name = PersonaPackRules.nameFromFileName(displayName)
            ?: PersonaPackRules.normalizeName(fallbackName)
            ?: "pack"
        return create(name, PersonaPackRules.decodeUtf8(bytes) ?: return PersonaPackResult.Rejected(PersonaPackError.BAD_UTF8))
    }

    /** 改正文：只认盘上已有的包；名字锁死（改名＝另建一份，不在这里做）。 */
    fun update(dirName: String, text: String): PersonaPackResult {
        val dir = packDir(dirName) ?: return PersonaPackResult.Rejected(PersonaPackError.NAME_INVALID)
        if (!File(dir, PersonaPackRules.FILE_PERSONA).isFile) {
            return PersonaPackResult.Rejected(PersonaPackError.NOT_FOUND)
        }
        val problem = PersonaPackRules.textProblem(text)
        if (problem != null) return PersonaPackResult.Rejected(problem)
        return write(dir, normalizeText(text))
    }

    /** 删一套：只删 persona.md 与 pack.json；目录里若还有别的文件，目录留着（不做递归删除）。 */
    fun delete(dirName: String): Boolean {
        val dir = packDir(dirName) ?: return false
        if (!dir.isDirectory) return false
        var removedAny = false
        listOf(PersonaPackRules.FILE_PERSONA, PersonaPackRules.FILE_MANIFEST).forEach { name ->
            val f = File(dir, name)
            if (f.isFile && runCatching { f.delete() }.getOrDefault(false)) removedAny = true
        }
        runCatching { dir.delete() }
        return removedAny
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private fun decodePayload(dir: File, bytes: ByteArray): PersonaPackResult {
        val text = PersonaPackRules.decodeUtf8(bytes)
            ?: return PersonaPackResult.Rejected(PersonaPackError.BAD_UTF8)
        val problem = PersonaPackRules.textProblem(text)
        if (problem != null) return PersonaPackResult.Rejected(problem)
        val manifest = PersonaPackRules.parseManifest(
            readTextLimited(File(dir, PersonaPackRules.FILE_MANIFEST), PersonaPackRules.MAX_MANIFEST_BYTES),
        )
        return PersonaPackResult.Ok(
            PersonaPackPayload(dirName = dir.name, name = manifest.name ?: dir.name, text = text),
        )
    }

    private fun write(dir: File, text: String): PersonaPackResult {
        val ok = runCatching {
            dir.mkdirs()
            val target = File(dir, PersonaPackRules.FILE_PERSONA)
            val tmp = File(dir, "${PersonaPackRules.FILE_PERSONA}.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (target.exists() && !target.delete()) error("delete old persona.md")
            if (!tmp.renameTo(target)) error("rename persona.md")
            val payload = PersonaPackPayload(dir.name, dir.name, text)
            val manifest = PersonaPackRules.parseManifest(
                readTextLimited(File(dir, PersonaPackRules.FILE_MANIFEST), PersonaPackRules.MAX_MANIFEST_BYTES),
            )
            val display = manifest.name ?: dir.name
            File(dir, PersonaPackRules.FILE_MANIFEST).writeText(
                PersonaPackRules.manifestJson(
                    PersonaPackPayload(dir.name, display, text),
                    manifest.description,
                    System.currentTimeMillis(),
                ),
                Charsets.UTF_8,
            )
            PersonaPackResult.Ok(PersonaPackPayload(dir.name, display, text))
        }.getOrElse { e ->
            PersonaPackResult.Rejected(PersonaPackError.IO, e.message ?: e.javaClass.simpleName)
        }
        return ok
    }

    private fun packDir(dirName: String): File? {
        val safe = PersonaPackRules.normalizeName(dirName) ?: return null
        val base = runCatching { root().canonicalFile }.getOrNull() ?: return null
        val dir = File(base, safe)
        return if (inside(base, dir)) dir else null
    }

    private fun inside(base: File, child: File): Boolean = runCatching {
        val b = base.canonicalFile
        val c = child.canonicalFile
        c.path == b.path || c.path.startsWith(b.path + File.separator)
    }.getOrDefault(false)

    private fun ensureRoot(): File? {
        val base = runCatching { root().canonicalFile }.getOrNull() ?: return null
        return if (base.isDirectory || base.mkdirs()) base else null
    }

    private fun uniqueDir(base: File, name: String): File {
        var candidate = File(base, name)
        var i = 2
        while (candidate.exists()) {
            candidate = File(base, "$name-$i")
            i++
        }
        return candidate
    }

    private fun normalizeText(text: String): String =
        text.replace("\r\n", "\n").trim() + "\n"

    /** 读上限内的文本；超限/读不了 ⇒ null（说明文件坏了按「没有」处理）。 */
    private fun readTextLimited(file: File, limit: Int): String? {
        if (!file.isFile || file.length() > limit) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return PersonaPackRules.decodeUtf8(bytes)
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            total += n
            if (total > limit) {
                out.write(buf, 0, n)
                return out.toByteArray()
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
