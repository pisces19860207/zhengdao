// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第八百七十批 · 躯干装配：人源与回执只挂缝；场景判定仍由 848 提供。
package com.example.zhengdao.baopu.state.bodyparts

import com.example.zhengdao.baopu.calibration.CalibrationWiring
import java.util.concurrent.ConcurrentLinkedDeque

internal object TorsoRuntime {

    const val TRACE_TAG = "[torso]"
    private const val TRACE_KEEP = 64

    @Volatile
    var personSource: (() -> List<TorsoPerson>)? = null

    @Volatile
    var traceSink: ((String) -> Unit)? = null

    private val receipts = ConcurrentLinkedDeque<String>()

    fun install(persons: (() -> List<TorsoPerson>)? = null, trace: ((String) -> Unit)? = null) {
        personSource = persons
        traceSink = trace
        CalibrationWiring.torsoProvider = { emptyList() }
    }

    fun detach() {
        CalibrationWiring.torsoProvider = null
        personSource = null
        traceSink = null
        receipts.clear()
    }

    fun resetForTest() = detach()

    fun currentPersons(): List<TorsoPerson> =
        runCatching { personSource?.invoke() }.getOrDefault(emptyList()).orEmpty()

    fun record(sceneId: String, finding: TorsoFinding) {
        val line = "$TRACE_TAG 写后 场=$sceneId 可见位=pending:687 ${finding.line()}"
        receipts.addLast(line)
        while (receipts.size > TRACE_KEEP) receipts.pollFirst()
        runCatching { traceSink?.invoke(line) }
    }

    fun receiptLines(): List<String> = receipts.toList()
}
