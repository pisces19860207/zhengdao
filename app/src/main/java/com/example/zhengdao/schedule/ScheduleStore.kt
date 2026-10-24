// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.schedule

import android.content.Context
import com.example.zhengdao.rootfs.RunLog
import java.io.File
import java.io.FileOutputStream

/** 读表结果（区分「本来就空」与「坏了回退空」——后者必须在界面上说出来，不能看着像用户自己没建）。 */
enum class LoadStatus { EMPTY_MISSING, OK, CORRUPT_RECOVERED }

/**
 * 定时任务的落盘（第一百六十七批 §0.5 约束 1、§5 ④）。
 *
 * 位置：`files/schedule_tasks.json` —— App 私有目录，不碰共享存储，也不进 `baopu/BaopuDb.kt`
 * （172-L4 正在那个文件上做 V19 迁移，本批不许抬库版本；并表是另一单）。
 *
 * 原子写口径抄 [com.example.zhengdao.baopu.GlobalMemory.save]：先写 `.tmp`、`fd.sync()` 落到盘上、
 * 再 `rename` 顶掉正式文件；rename 不成退回直写并**把失败说出来**（不静默当成功）。
 *
 * 坏了怎么办：整份改名 `.bad` 留下证据（下一次写会重新生成干净文件），内存里回退**空表**并记日志。
 * 为什么不救半条：一条缺字段的任务被补齐后照样会到点发消息，用户看不见它、却收得到它——
 * 比整表空掉更危险。
 *
 * 构造只吃 [File]（不吃 Context）：这样读写、损坏回退、rename 失败退回这些路径
 * 都能在 JVM 上用真临时目录跑出来，而不是对着 mock 自证。
 */
class ScheduleStore(private val file: File) {

    /** 读表：不存在 ⇒ 空表；解析不过 ⇒ 搬去 `.bad` ＋ 空表 ＋ 日志。 */
    fun load(): LoadResult = synchronized(LOCK) {
        if (!file.isFile) return@synchronized LoadResult(emptyList(), LoadStatus.EMPTY_MISSING, "")
        val text = runCatching { file.readText(Charsets.UTF_8) }
            .onFailure { log("读失败 ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault("")
        val parsed = ScheduleTask.listFromJson(text)
        if (parsed != null) return@synchronized LoadResult(parsed, LoadStatus.OK, "")
        val detail = quarantine()
        log("文件读不出可信内容（$detail），本次按空表处理；原件已留作 ${BAD_SUFFIX}")
        return@synchronized LoadResult(emptyList(), LoadStatus.CORRUPT_RECOVERED, detail)
    }

    /**
     * 整表写盘。
     * @return true = 已落盘；false = 没落成（调用方要说出来，不许以为保存成功）
     */
    fun save(tasks: List<ScheduleTask>): Boolean = synchronized(LOCK) {
        val text = ScheduleTask.listToJson(tasks)
        file.parentFile?.mkdirs()
        runCatching {
            val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                // rename 之前先落到盘上：否则崩在这里，正式文件会被一个空的 tmp 顶掉。
                runCatching { out.fd.sync() }
            }
            if (!tmp.renameTo(file)) {
                // 退回直写（跨文件系统的 rename 会这样失败），并把 tmp 清掉不留在盘上。
                runCatching { file.writeText(text, Charsets.UTF_8) }
                runCatching { tmp.delete() }
                log("原子 rename 没成，退回直写：${file.absolutePath}")
            }
            file.isFile
        }.onFailure {
            log("写失败 ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(false)
    }

    /** 只改其中一条（找不到 id ⇒ 不动，返回 false 给调用方看）。 */
    fun update(task: ScheduleTask): Boolean {
        val current = load().tasks
        val idx = current.indexOfFirst { it.id == task.id }
        if (idx < 0) return false
        return save(current.toMutableList().also { it[idx] = task })
    }

    /** 把坏件挪成 `.bad`（挪不动就留在原地，只报告，不再重写它——证据比整洁重要）。 */
    private fun quarantine(): String {
        val bad = File(file.parentFile, file.name + BAD_SUFFIX)
        val ok = runCatching { file.renameTo(bad) }.getOrDefault(false)
        return if (ok) "原件已挪走" else "原件挪走失败，仍在原地"
    }

    companion object {
        const val FILE_NAME = "schedule_tasks.json"
        const val VERSION = 1
        const val KEY_VERSION = "version"
        const val KEY_TASKS = "tasks"
        private const val TMP_SUFFIX = ".tmp"
        private const val BAD_SUFFIX = ".bad"

        /** 同进程内读写串行化（UI 保存与后台触发可能同时动这份文件）。 */
        private val LOCK = ScheduleStore::class.java

        fun of(context: Context): ScheduleStore =
            ScheduleStore(File(context.applicationContext.filesDir, FILE_NAME))

        /** 损坏原件被挪去的那个名字（界面上要把它说给用户，别处不许自己拼 `".bad"`）。 */
        fun corruptFileName(): String = FILE_NAME + BAD_SUFFIX

        private fun log(line: String) = RunLog.log("定时任务：$line")
    }
}

/** load() 的返回体。 */
data class LoadResult(val tasks: List<ScheduleTask>, val status: LoadStatus, val detail: String)
