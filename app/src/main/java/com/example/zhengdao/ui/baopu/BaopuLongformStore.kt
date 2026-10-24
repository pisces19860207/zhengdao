// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.ui.baopu

import android.content.Context
import com.example.zhengdao.baopu.BaopuRuntime
import java.io.File
import java.util.Calendar

/**
 * 第一百七十九批 · 长文产物的**落库出口**（20:4x 拍板版）。
 *
 * 落点（179 §0.2/§0.3 ＋ **175 S1 收口**）：
 * - **知识库根**默认＝**工作区根本身**（`BaopuRuntime.workspaceRoot`）；产物平铺在
 *   `<工作区>/产物/`（**不加 `文本/` 第三层**，也**不再套 `资料库/`**）。
 *   ⚠️ 口径修正，别改回去：179 上一版在这里多写了 `资料库/` 一层，而 157 已把 `资料库/`
 *   定为**旧布局**（`BaopuKernel.kt:81` 注释「新代码不许引用」）、`MemoryLayout.migrate`
 *   每进一次抱朴就把它**搬空删掉一次**。两边各自测试都绿，没有组合用例 ⇒ 175 设计实测到
 *   「存一篇长文 ⇒ `资料库/` 被 mkdirs 重建 ⇒ 非空 ⇒ 永远删不掉」，本行就是那条收口。
 * - **可选外置根**（Obsidian 等）：`<工作区>/产物/外置根.txt` 一行绝对路径 ⇒ VaultRoot 换指向，
 *   同一套代码；外置根下已有 `已发台账_勿删.md` ⇒ **复用它，绝不新建第二份**；
 * - 产物命名：`YYYYMMDD_<赛道>_<序号>_<标题slug>.<ext>`，序号＝同赛道当日文件数+1，重名加 `_2`，**不覆盖**；
 * - **台账 append-only ＋ 多写者协议**：写前重读最新 → 临时文件＋`rename` 原子替换＋`.bak` →
 *   写后校验老内容逐字未变、只多末尾几行 → 冲突重读重试一次，再冲突就停手报一句；
 * - **红线**：原件区一个字节不碰（现为 App 私有 `原始/`，旧形态 `资料库/原始/` 同样不碰；本类没有
 *   任何指向原始/ 的路径；slug 已剔除路径分隔符，
 *   文件名不可能越出 `产物/`）；**删除永远用户手动**——本类没有 delete。
 * 失败给可读原因，不静默（`SaveOut.message`）。
 */
internal object BaopuLongformStore {

    /** 默认赛道（赛道可自定义；微头条是本批的出发点）。 */
    const val DEFAULT_TRACK = "微头条"

    data class SaveOut(val ok: Boolean, val message: String, val path: String?)

    /** 知识库根（生产入口）：默认＝**工作区根**；判据本体在 [vaultRootUnder]（纯函数，JVM 直测）。 */
    fun vaultRoot(context: Context): File =
        vaultRootUnder(BaopuRuntime.workspaceRoot(context.applicationContext))

    /**
     * 落点判据（**纯函数**：不碰 Context、不建线程 ⇒ 175 S1 的组合门禁能在 JVM 里跑）：
     * - 默认＝工作区根**本身**（157 四目录里的 `产物/` 就是它的直接子目录，中间不夹 `资料库/`）；
     * - `<工作区>/产物/外置根.txt` 里有一行绝对路径 ⇒ 换指外置根（Obsidian 等），同一套代码。
     *
     * 标记文件放 `产物/` 而不是工作区根：根里不许再多一个裸文件（175 设计 §12.1 实测根目录
     * 已经躺着 `人设-本项目.md` 和它的 `.bak`，别再往里添）。
     */
    internal fun vaultRootUnder(workspace: File): File {
        val marker = File(productDirOf(workspace), BaopuLongformPolicy.ROOT_MARKER)
        return runCatching {
            val line = if (marker.isFile) marker.readText().trim() else ""
            if (line.isNotEmpty()) File(line) else workspace
        }.getOrDefault(workspace)
    }

    /** 产物目录（**唯一出口**：`<根>/产物/`，平铺、不加第三层）。 */
    internal fun productDirOf(root: File): File = File(root, BaopuLongformPolicy.PRODUCT_DIR)

    /**
     * 保存正文（md/txt）并**追加台账一段**。
     * 两种结局都有可读 message：文件成功台账失败 ⇒ 照常 ok，但 message 里说明台账未记。
     */
    fun save(
        context: Context,
        markdown: String,
        ext: String,
        track: String = DEFAULT_TRACK,
        now: Calendar = Calendar.getInstance(),
    ): SaveOut {
        if (markdown.isBlank()) return SaveOut(false, "正文为空，没有可保存的内容", null)
        if (ext !in setOf("md", "txt")) return SaveOut(false, "不支持的格式 .$ext（只支持 md/txt）", null)
        val y = now.get(Calendar.YEAR); val m = now.get(Calendar.MONTH) + 1
        val d = now.get(Calendar.DAY_OF_MONTH)
        val h = now.get(Calendar.HOUR_OF_DAY); val min = now.get(Calendar.MINUTE)

        val dir = productDirOf(vaultRoot(context))
        val dirMsg = runCatching { if (dir.isDirectory || dir.mkdirs()) null else "建不了目录 ${dir.absolutePath}" }
            .getOrElse { "建目录失败：${it.message}" }
        if (dirMsg != null) return SaveOut(false, "落盘失败：$dirMsg", null)

        val existing = dir.list()?.toSet() ?: emptySet()
        val seq = BaopuLongformPolicy.nextSeq(existing, y, m, d, track)
        val base = BaopuLongformPolicy.trackFileName(y, m, d, track, seq, BaopuLongformPolicy.cardTitle(markdown), ext)
        val name = BaopuLongformPolicy.nextFileName(existing, base)
        val path = runCatching { File(dir, name).writeText(markdown, Charsets.UTF_8) }
            .getOrElse { return SaveOut(false, "落盘失败：${it.message}", null) }

        val ledgerMsg = appendLedger(root = vaultRoot(context), productDir = dir, now = now, files = listOf(name to BaopuLongformPolicy.wordCount(markdown)))
        val tail = if (ledgerMsg == null) "" else "；$ledgerMsg"
        return SaveOut(true, "已保存：${File(dir, name).absolutePath}$tail", File(dir, name).absolutePath)
    }

    /**
     * 台账追加（**多写者 append 协议**，派工单 §0.3）。
     *
     * @return null＝台账已记；非 null＝给用户看的说明（文件已保存、台账没记成的原因）。
     */
    private fun appendLedger(root: File, productDir: File, now: Calendar, files: List<Pair<String, Int>>): String? {
        val y = now.get(Calendar.YEAR); val m = now.get(Calendar.MONTH) + 1
        val d = now.get(Calendar.DAY_OF_MONTH)
        val h = now.get(Calendar.HOUR_OF_DAY); val min = now.get(Calendar.MINUTE)
        val section = BaopuLongformPolicy.ledgerSection(y, m, d, h, min, files)
        val ledger = BaopuLongformPolicy.pickLedger(root, productDir)

        for (attempt in 0..1) {
            // ① 写前重读最新（不许用缓存）
            val oldText = if (ledger.isFile) { runCatching { ledger.readText() }.getOrElse { return "台账读取失败：${it.message}" } } else {
                BaopuLongformPolicy.ledgerHeader().joinToString("\n")
            }
            // ② 合并（老内容逐字保留，段接末尾）
            val merged = BaopuLongformPolicy.mergeAppend(oldText, section).joinToString("\n")
            if (!BaopuLongformPolicy.verifyAppend(oldText, merged)) {
                return "台账合并校验未通过（不该发生，已停手保护原文件）"
            }
            // ③ 临时文件 + rename 原子替换 + .bak
            val tmp = File(ledger.parentFile, ledger.name + ".tmp")
            val bak = File(ledger.parentFile, ledger.name + ".bak")
            runCatching {
                tmp.writeText(merged, Charsets.UTF_8)
                if (ledger.isFile) ledger.copyTo(bak, overwrite = true)
                if (!tmp.renameTo(ledger)) error("rename 失败")
            }.getOrElse { return "台账写入失败：${it.message}" }
            // ④ 写后校验：重读落盘结果，老内容必须逐字未变
            val after = runCatching { ledger.readText() }.getOrElse { return "台账校验失败：${it.message}" }
            if (BaopuLongformPolicy.verifyAppend(oldText, after)) return null
            if (attempt == 0) {
                // 期间别人改过 ⇒ 重读最新再试一次（②③④ 整套重来）
                continue
            }
            // ⑤ 再冲突 ⇒ 停手报一句（产品文件已经落好，不回滚、不覆盖别人的改动）
            return "台账正被其他工具同时写入，本轮台账未记（文件已保存，稍后再说一声「记台账」即可补记）"
        }
        return "台账追加未完成（重试上限）"
    }
}
