// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百三十二批（2026-11-13）· 轻办公两条写出的**落盘口**（唯一一份）。
//
// 三条口径（派工单 §一③ ＋ 判据③⑥）：
//   1. **只进 `产物/`**：调用方给不了路径参数，落点由 `MemoryLayout.productsDir` 一处决定；
//   2. **同名不覆盖**：`<名>-<时间>.<ext>` 已存在就 `-2`、`-3`……（连写两次同名字 ⇒ 两份）；
//   3. **落账一行**：写成功后记 `FileOpLog`（工具/种类/路径/用途），用途里带**大小与来源**；
//      账写不进库也不许把文件判失败（账是留痕不是闸门，287 口径）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.FileOpKind
import com.example.zhengdao.baopu.FileOpLog
import com.example.zhengdao.baopu.FileOpRules
import com.example.zhengdao.baopu.FilePaths
import com.example.zhengdao.baopu.FilePolicy
import com.example.zhengdao.baopu.MemoryLayout
import com.example.zhengdao.baopu.office.Step
import com.example.zhengdao.core.RunLog
import java.io.File

internal object OfficeSink {

    /** 文件名基最长多少字（再长就不像文件名，像把整段话塞进来了）。 */
    const val MAX_BASE_CHARS = 40

    /** 文件名里一律替换掉的字符（Android/Windows 双端非法或危险：分隔符与控制字符）。 */
    private val ILLEGAL = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '\u0000')

    /**
     * 文件名基：去掉路径分隔符与非法字符，裁到 [MAX_BASE_CHARS]；
     * 清洗后为空就用 [fallback]（不拿空名去拼 `-20261113.docx` 这种没有主语的怪名字）。
     */
    fun baseOf(raw: String, fallback: String): String {
        val cleaned = raw.trim().map { ch ->
            when {
                ch in ILLEGAL -> '_'
                ch.isISOControl() -> '_'
                else -> ch
            }
        }.joinToString("").trim(' ', '.').take(MAX_BASE_CHARS).trim()
        return cleaned.ifEmpty { fallback }
    }

    /**
     * 目标文件：`<产物>/<基>-<时间><序号>.<ext>`，**先到先得、绝不覆盖**。
     *
     * 序号形状与 `BuildAuditPack.nextBase` 一致（`-2`、`-3`），用户在产物目录里一眼看得出
     * 「这是同一分钟里第二次导出的」。
     */
    fun target(productsDir: File, base: String, ext: String, stamp: String): File {
        var n = 1
        while (true) {
            val tail = if (n == 1) "" else "-$n"
            val candidate = File(productsDir, "$base-$stamp$tail.$ext")
            if (!candidate.exists()) return candidate
            n++
        }
    }

    /**
     * 落盘（原子写：先 `.tmp` 再改名——半截 docx 比没有更坏）。
     *
     * @return 成功给出落盘文件；失败给一句人话（盘上不留半截文件）
     */
    fun write(workspaceRoot: File, base: String, ext: String, bytes: ByteArray, stamp: String): Step<File> {
        val dir = MemoryLayout.productsDir(workspaceRoot)
        if (!dir.isDirectory && !dir.mkdirs()) {
            return Step.Bad("产物目录建不了：${dir.name}（权限或 IO 错误）")
        }
        val target = target(dir, base, ext, stamp)
        val tmp = File(dir, target.name + ".tmp")
        val ok = runCatching {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }.isSuccess
        if (!ok) {
            runCatching { tmp.delete() }
            return Step.Bad("写盘失败（IO 错误）：${target.name}；产物目录状态未保证")
        }
        return Step.Ok(target)
    }

    /**
     * 记一行账（判据⑥：**路径 / 大小 / 来源**）。
     *
     * 为什么大小与来源塞进 `purpose` 而不是新开列：`file_op` 表的六列是 287 定的
     * （时间/会话/工具/种类/路径/用途），为两件工具改表结构会让「30 天可回放」的老账对不上；
     * 用途本来就是一句人话，把「多大、谁给的」写进去，回放时看得懂也不破坏结构。
     */
    fun record(tool: String, workspaceRoot: File, file: File, kindText: String): String {
        val purpose = "$kindText（${PathBatch.sizeText(file.length())}｜来源：模型草稿）"
        val entry = FileOpLog.record(tool, FileOpKind.WRITE, FilePolicy.normalize(file), purpose)
        val line = FileOpRules.lineOf(entry)
        RunLog.log(line)
        return FilePaths.display(workspaceRoot, file)
    }
}
