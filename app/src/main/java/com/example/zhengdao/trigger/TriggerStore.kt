// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.trigger

import com.example.zhengdao.core.RunLog
import java.io.File
import java.io.FileOutputStream

/**
 * 规则表的落盘（`files/trigger_rules.json`）——房型整份照 167 的 `ScheduleStore`
 * （先 tmp、fd.sync、再 rename 顶掉；坏了整份挪 `.bad` 留证据、内存回退空表）。
 *
 * 为什么不进 `BaopuDb`：167 建定时任务时（v1）给过同一答案——并表要抬库版本，
 * 是另一单的事；本批同样不碰库。构造只吃 [File]（吃 Context 的工厂在 [TriggerRunner]）：
 * 读写、损坏回退这些路径 JVM 上用真临时目录跑。
 */
class TriggerStore(private val file: File) {

    /** 整表读。 */
    fun load(): List<TriggerRule> = synchronized(LOCK) {
        if (!file.isFile) return@synchronized emptyList()
        val text = runCatching { file.readText(Charsets.UTF_8) }
            .onFailure { log("读失败 ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault("")
        val parsed = TriggerRule.listFromJson(text)
        if (parsed != null) return@synchronized parsed
        quarantine()
        log("规则文件读不出可信内容，本次按空表处理；原件已留作 ${BAD_SUFFIX}")
        emptyList()
    }

    /** 整表写；false = 没落成（调用方要说出来）。 */
    fun save(rules: List<TriggerRule>): Boolean = synchronized(LOCK) {
        val text = TriggerRule.listToJson(rules)
        file.parentFile?.mkdirs()
        runCatching {
            val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                runCatching { out.fd.sync() }
            }
            if (!tmp.renameTo(file)) {
                runCatching { file.writeText(text, Charsets.UTF_8) }
                runCatching { tmp.delete() }
                log("原子 rename 没成，退回直写：${file.absolutePath}")
            }
            file.isFile
        }.onFailure {
            log("写失败 ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(false)
    }

    /** 只改其中一条（找不到 id ⇒ false）。 */
    fun update(rule: TriggerRule): Boolean {
        val current = load()
        val idx = current.indexOfFirst { it.id == rule.id }
        if (idx < 0) return false
        return save(current.toMutableList().also { it[idx] = rule })
    }

    private fun quarantine(): String {
        val bad = File(file.parentFile, file.name + BAD_SUFFIX)
        val ok = runCatching { file.renameTo(bad) }.getOrDefault(false)
        return if (ok) "原件已挪走" else "原件挪走失败，仍在原地"
    }

    companion object {
        const val FILE_NAME = "trigger_rules.json"
        const val VERSION = 1
        private const val TMP_SUFFIX = ".tmp"
        private const val BAD_SUFFIX = ".bad"

        private val LOCK = TriggerStore::class.java
    }

    private fun log(line: String) = RunLog.log("事件触发：$line")
}
