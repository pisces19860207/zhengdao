// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百六十七批 · 租约的**账**（`产物/.leases.jsonl`，append-only，可按 lease id 回放）。
//
// 三条纪律：
//   1. **账是留痕不是闸门**：写失败只 log，不改判定、不抛、不拦动作（208 同一口径）。
//   2. **只有一本**：授予/使用/结果/撤销/降权/越权/建议 七种 kind 都落这一本，
//      不往 `打扰治理.jsonl` 里塞租约的 kind（499 那本的 kind/who 是闭集，混进去等于改它的口径）。
//   3. **落点由装配层装**（房型同 `DecisionLedger.installedRoot`）：管线零 Android，
//      本层只认一个 `() -> File?`；JVM 单测装临时目录，生产装工作区根。
//
// 为什么在 `产物/`（派工单 D7 定的）而不是 App 私有目录：她能在文件管理器里直接看见
// 「什么时候批了什么」。代价是这本账**可被用户删改**——不影响判定，因为判定只读内存
// （[LeaseStore] 启动时不读盘），这本账纯粹是给人看的证据。
package com.example.zhengdao.baopu.authz

import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONObject

/** 账行的 kind 词表（稳定机器键：改了等于让老账读不出）。 */
internal object LeaseKinds {
    const val GRANT = "grant"
    const val USE = "use"
    const val OUTCOME = "outcome"
    const val REVOKE = "revoke"
    const val DOWNGRADE = "downgrade"
    const val VIOLATION = "violation"
    const val ADVICE = "advice"
}

internal object LeaseLedger {

    /** 目录名与文件名：点号开头（`.jsonl` 也不在产物扩展名白名单里 ⇒ 不计产物数）。 */
    const val DIR_NAME = "产物"
    const val FILE_NAME = ".leases.jsonl"
    const val VERSION = 1

    /** 落点（装配层装一次；null ⇒ 只进内存，纯单测里就是这样）。 */
    @Volatile
    private var provider: (() -> File?)? = null

    /** 最近一次由使用侧带上来的工作区根（见 [rememberRoot]）。 */
    @Volatile
    private var remembered: File? = null

    fun installRoot(p: (() -> File?)?) {
        provider = p
    }

    /**
     * 记下当前工作区根（管线每次判卡时喂一次；只存 [File]，**不存 Context**——
     * `Context` 进 object 静态字段就是 `StaticFieldLeak`，仓规不许）。
     *
     * 为什么不在装配层装一个往回调的 lambda：那要捕获 `appCtx`，同一条 lint 红线。
     * 改由使用侧每次带上来，账永远落在"当前工作区/产物"，也不会跟着换工作区变旧。
     */
    fun rememberRoot(root: File?) {
        if (root != null) remembered = root
    }

    fun root(): File? = provider?.invoke() ?: remembered

    /** 账文件（`产物/.leases.jsonl`）。 */
    fun fileIn(root: File): File = File(File(root, DIR_NAME), FILE_NAME)

    /** 落一行；返回有没有真落上。 */
    @Synchronized
    fun append(row: JSONObject): Boolean {
        val root = root() ?: return false
        return runCatching {
            val f = fileIn(root)
            f.parentFile?.mkdirs()
            f.appendText(row.put("v", VERSION).toString() + "\n", Charsets.UTF_8)
            true
        }.getOrElse {
            RunLog.log("抱朴租约：账没落上——${it.javaClass.simpleName}")
            false
        }
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
        }.getOrDefault(emptyList())
    }

    /** 按 lease id 回放（授予→使用→结果→撤销，`at` 升序；**这就是"可按 lease id 回放"**）。 */
    fun rowsFor(id: String): List<JSONObject> =
        replay().filter { it.optString("id") == id }.sortedBy { it.optLong("at", 0L) }

    /** 按 opId 找这一笔动作的账行（`use` 与 `outcome` 用它对上）。 */
    fun rowsForOp(op: String): List<JSONObject> =
        replay().filter { it.optString("op") == op }.sortedBy { it.optLong("at", 0L) }

    /** 清空落点（**测试专用**：与 `DecisionLedger.resetForTest` 同一房型）。 */
    @Synchronized
    fun resetForTest() {
        provider = null
        remembered = null
    }
}
