// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十八批 · 对端探测的**事件账**（`产物/.peer-events.jsonl`，append-only）。
//
// 房型完全照 467 那本租约账（`.leases.jsonl`）：账是留痕不是闸门；只追加、写失败不抛；
// 点号开头不计产物数；落点由装配层装（纯 JVM 单测装临时目录）。
//
// 为什么另立一本而不塞进 `.leases.jsonl` 或 `打扰治理.jsonl`：租约的 kind 是七种闭集、
// 治理账的 kind/who 也是闭集——把探测的失败分类混进去等于改别人的账本口径
// （467 的原话：「不往打扰治理.jsonl 里塞租约的 kind」）。**判据⑤「不改三态/账本结构」
// 就落在这：新事新账，老账一个字不动。**
package com.example.zhengdao.baopu.peer

import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONObject

/** 事件账的 kind 词表（稳定机器键：改了等于让老账读不出；**只增不改名不删**——老账照读）。 */
internal object PeerEventKinds {
    /** 一次探测的收场：result = [PeerFailureCode.wire]，detail = 人话补一句。 */
    const val PROBE = "probe"

    /** 闸的一次裁决：result = refused／ok（ok 只是记录，真正执行在 probe 行）。 */
    const val GATE = "gate"

    /**
     * 第五百四十九批 · 一次**委派的收场**：result = 终/停状态（A2aTaskState.wire），
     * detail = 任务号＋迁移轨迹摘要（五条流的账面证据）。
     */
    const val DELEGATE = "delegate"
}

internal object PeerEventsLedger {

    const val DIR_NAME = "产物"
    const val FILE_NAME = ".peer-events.jsonl"
    const val VERSION = 1

    @Volatile
    private var provider: (() -> File?)? = null

    /** 最近一次使用侧带上来的工作区根（与 467 的租约账同一条纪律：不存 Context）。 */
    @Volatile
    private var remembered: File? = null

    @Volatile
    private var seq = 0

    fun installRoot(p: (() -> File?)?) {
        provider = p
    }

    fun rememberRoot(root: File?) {
        if (root != null) remembered = root
    }

    fun root(): File? = provider?.invoke() ?: remembered

    fun fileIn(root: File): File = File(File(root, DIR_NAME), FILE_NAME)

    /**
     * 落一行（kind/result/endpoint 由调用方给；id 与时间这里盖）。
     * 没装配落点 ⇒ 只丢一行日志，不抛（账是留痕不是闸门）。
     */
    @Synchronized
    fun append(kind: String, result: String, endpoint: String = "", detail: String = "", atMs: Long = System.currentTimeMillis()): JSONObject {
        seq += 1
        val row = JSONObject()
            .put("v", VERSION)
            .put("id", "pe-$atMs-$seq")
            .put("at", atMs)
            .put("kind", kind)
            .put("result", result)
            .put("endpoint", endpoint)
            .put("detail", detail)
        val root = root()
        if (root == null) {
            RunLog.log("抱朴对端：事件账没落点，只留日志——$kind/$result")
            return row
        }
        runCatching {
            val f = fileIn(root)
            f.parentFile?.mkdirs()
            f.appendText(row.toString() + "\n", Charsets.UTF_8)
        }.onFailure {
            RunLog.log("抱朴对端：事件账没落上（${row.optString("id")}）——${it.javaClass.simpleName}")
        }
        return row
    }

    /** 回放（新→旧；读不出 ⇒ 空表，不抛）。 */
    @Synchronized
    fun replay(): List<JSONObject> {
        val root = root() ?: return emptyList()
        val f = fileIn(root)
        if (!f.isFile) return emptyList()
        return runCatching {
            f.readLines(Charsets.UTF_8)
                .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
                .filter { it.optString("id").isNotEmpty() }
                .asReversed()
        }.getOrDefault(emptyList())
    }

    /** 清空落点（**测试专用**：与 467 租约账的 resetForTest 同一房型）。 */
    @Synchronized
    fun resetForTest() {
        provider = null
        remembered = null
        seq = 0
    }
}
