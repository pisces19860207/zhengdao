// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：765 派工单①③④（G-716-4）——716 真机轮实测：`java.io.File.listFiles` 的 JNI
// 路径（list0 → NewStringUTF）在 CheckJNI 严格校验下遇到非法 UTF-8 名直接 SIGABRT
// **整进程**，Kotlin 层的 runCatching 接不住（那是 native abort，不是异常）。
// 容错的根基因此是**换列举原语**：`Files.newDirectoryStream` 的字节解码是替换式的
// （解不了的字节以 U+FFFD 过境，不抛、不中止），坏名从此以普通字符串进来，
// 由 [ScanNameRules.badNameReasonOf] 先判后用。
package com.example.zhengdao.core.scan

import com.example.zhengdao.core.RunLog
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * 单条目容错列举（765 ①④）。三条铁律：
 *
 * 1. **try/catch 在单条目粒度**：一条炸只丢一条（记一行账），整批照走；
 *    目录级打不开也记一行并经 [Listing.dirError] 交回调用方
 *    （调用方沿用各自既有的「目录读不了」语义，本件不改任何调用方的结果语义）。
 * 2. **跳过必记账，不静默**（765 ④）：每条跳过恰好走一次 [skipSink]（默认＝
 *    RunLog 一行 `[scan-skip] …`），同时留在 [Listing.skips] 里——
 *    「跳过了没记账」在结构上不成立（sink 写失败也只是丢一行日志，
 *    返回值里的行还在，调用方兜底）。
 * 3. **正常条目零改动**（765 ⑤）：顺序＝readdir 顺序（与 listFiles 同源），
 *    返回的就是 `File`，调用方既有代码一字不用改；属性读用 `NOFOLLOW`
 *    （软链条目照原样交回，调用方的软链纪律原样生效）。
 *
 * IO 全部注入（[Io]）：与 494「Windows chmod 不作数」同一口径——权限/IO 失败
 * 在 JVM 上稳定直测，不赌真文件系统的脾气。
 */
object ScanTolerance {

    /** 一次列举的 IO 缝（生产＝真 NIO；测试注入假流/假属性读，五种原因码全可造）。 */
    data class Io(
        val open: (File) -> DirectoryStream<Path> = { Files.newDirectoryStream(it.toPath()) },
        val attrs: (Path) -> BasicFileAttributes = {
            Files.readAttributes(it, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        },
    )

    /** 一条跳过账行（765 ③：原因码＋转义路径；不含原始字节——名字已解码并转义）。 */
    data class NameSkip(
        val dirPath: String,
        val name: String,
        val reason: String,
        val detail: String,
    ) {
        /** 记账行：一条跳过恰好一行；`[scan-skip] <原因码> <转义目录>/<转义名>（<细节>）`。 */
        fun ledgerLine(): String =
            "[scan-skip] $reason ${ScanNameRules.escapeName(dirPath)}/" +
                "${ScanNameRules.escapeName(name)}（$detail）"
    }

    /** 一次列举的结果：正常条目（属性已验证可读）＋跳过账＋目录级错误（null＝目录正常）。 */
    data class Listing(
        val entries: List<File>,
        val skips: List<NameSkip>,
        val dirError: String?,
    )

    /**
     * 列一个目录：坏名/属性失败/权限条目**跳过并记账**，正常条目原样交回。
     * 目录级打不开 ⇒ [Listing.dirError] 非 null（entries 为空），照样记一行。
     *
     * @param skipSink 每条跳过恰好调用一次；默认写 RunLog 一行（生产记账口）。
     *   JVM 测试注入收集器即可断言「跳过数 == 记账行数」（765 ④）。
     */
    fun listTolerant(
        dir: File,
        skipSink: (NameSkip) -> Unit = { RunLog.log(it.ledgerLine()) },
        io: Io = Io(),
    ): Listing {
        val skips = mutableListOf<NameSkip>()
        val stream = openStream(dir, skips, skipSink, io)
            ?: return Listing(emptyList(), skips, skips.lastOrNull()?.reason)
        val entries = mutableListOf<File>()
        stream.use { ds ->
            val cursor = ds.iterator()
            while (true) {
                // 迭代中途的 readdir 错误以 DirectoryIteratorException（unchecked）冒出——
                // 同样按「记账＋目录级交回」处理：丢的是异常，不是进程（765 ①④）。
                val entry = try {
                    if (!cursor.hasNext()) break
                    cursor.next()
                } catch (die: DirectoryIteratorException) {
                    val cause = die.cause
                    return dirFailure(
                        dir, entries, skips, skipSink,
                        if (cause is AccessDeniedException) ScanNameRules.REASON_DENIED else ScanNameRules.REASON_ATTR_FAIL,
                        cause ?: die,
                    )
                }
                val file = classifyEntry(dir, entry, skips, skipSink, io)
                if (file != null) entries += file
            }
        }
        return Listing(entries, skips, dirError = null)
    }

    /** 开目录流（目录级失败＝记一行＋空结果；流的生命周期交给 [use]）。 */
    private fun openStream(
        dir: File,
        skips: MutableList<NameSkip>,
        skipSink: (NameSkip) -> Unit,
        io: Io,
    ): DirectoryStream<Path>? = try {
        io.open(dir)
    } catch (missing: NoSuchFileException) {
        // 目录不存在＝「没得列」，与 java.io 的 null 同语义：空表、不记行、不报错
        // （globalDir 这类懒创建目录每次扫描都会走到，记行就是纯噪音）。
        null
    } catch (denied: AccessDeniedException) {
        recordDirSkip(dir, skips, skipSink, ScanNameRules.REASON_DENIED, denied)
        null
    } catch (ioFail: Exception) {
        recordDirSkip(dir, skips, skipSink, ScanNameRules.REASON_ATTR_FAIL, ioFail)
        null
    }

    /** 单条目判定（**try/catch 只圈这一条**，765 ①④）：名字先判，再单文件属性读。返回 null＝该条已跳过并记账。 */
    private fun classifyEntry(
        dir: File,
        entry: Path,
        skips: MutableList<NameSkip>,
        skipSink: (NameSkip) -> Unit,
        io: Io,
    ): File? {
        val name = entry.fileName.toString()
        val reason = ScanNameRules.badNameReasonOf(name)
        if (reason != null) {
            recordSkip(dir, skips, skipSink, name, reason, badNameDetailOf(reason, name))
            return null
        }
        val file = entry.toFile()
        return try {
            io.attrs(entry)
            file
        } catch (denied: AccessDeniedException) {
            recordSkip(dir, skips, skipSink, name, ScanNameRules.REASON_DENIED, "属性读失败：${denied.javaClass.simpleName}")
            null
        } catch (ioFail: Exception) {
            recordSkip(dir, skips, skipSink, name, ScanNameRules.REASON_ATTR_FAIL, "属性读失败：${ioFail.javaClass.simpleName}")
            null
        }
    }

    /** 坏名行的人读细节（按原因码给，不给原始字节）。 */
    private fun badNameDetailOf(reason: String, name: String): String = when (reason) {
        ScanNameRules.REASON_DECODE_FAIL -> "名字含替换符（字节不可解码）"
        ScanNameRules.REASON_TOO_LONG -> "名字超 ${ScanNameRules.MAX_NAME_BYTES} 字节（${name.toByteArray(Charsets.UTF_8).size} B）"
        ScanNameRules.REASON_RESERVED -> "Windows 保留名/保留形态（尾空格或点）"
        else -> "名字不合规"
    }

    /** 记一行（跳过必过这里——「跳过了没记账」的结构保证；sink 抛异常只丢日志，行留在返回值）。 */
    private fun recordSkip(
        dir: File,
        skips: MutableList<NameSkip>,
        skipSink: (NameSkip) -> Unit,
        name: String,
        reason: String,
        detail: String,
    ) {
        val skip = NameSkip(dirPath = dir.absolutePath, name = name, reason = reason, detail = detail)
        skips += skip
        runCatching { skipSink(skip) }
    }

    /** 目录级失败也记一行（名字记目录自己）＋目录级错误形状交回（openStream/迭代中断共用）。 */
    private fun recordDirSkip(
        dir: File,
        skips: MutableList<NameSkip>,
        skipSink: (NameSkip) -> Unit,
        reason: String,
        error: Exception,
    ) {
        val skip = NameSkip(
            dirPath = dir.parent ?: dir.absolutePath,
            name = dir.name,
            reason = reason,
            detail = "目录打不开：${error.javaClass.simpleName}",
        )
        skips += skip
        runCatching { skipSink(skip) }
    }

    /** 迭代中断时收尾：已有正常条目＋已有跳过账一起交回（都不丢）。 */
    private fun dirFailure(
        dir: File,
        entries: MutableList<File>,
        skips: MutableList<NameSkip>,
        skipSink: (NameSkip) -> Unit,
        reason: String,
        error: Exception,
    ): Listing {
        recordDirSkip(dir, skips, skipSink, reason, error)
        return Listing(entries, skips, dirError = reason)
    }

    /** 目录级错误的读数形状（只在记过目录级行之后才被调用）。 */
    private fun dirErrorOf(skips: List<NameSkip>): String = skips.lastOrNull()?.reason ?: "unknown"
}
