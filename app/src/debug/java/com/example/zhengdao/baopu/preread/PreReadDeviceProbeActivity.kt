// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 436-B 真机验收件（**只进 debug 源集，release 合并清单里没有这个 activity**）。
//
// 为什么要有它（用户 2026-11-06 定的取证四件，缺一不算）：
//   ① 触发证据：真机上跑"要求回执的任务"，故意不交回执就调写工具 ⇒ 被拦住 ＋ 那句可操作提示；
//   ② 放行证据：交了回执再调同一工具 ⇒ 放行，且**动作真的执行了**（盘上文件可验）；
//   ③ 账本原文：`run-as` 把 `files/抱朴记忆/预读回执.jsonl` 拉出来贴原件（不是转述）；
//   ④ 可回看：从账定位任务 → 打印回执三行 → 与必读清单逐条对照。
// 这四件里 ①②④ 是本探针跑出来的，③ 是盘上那份文件本身（探针只负责让它存在）。
//
// ## 为什么必须走真 GuardPipeline
// 判据写的是"在 GuardPipeline 执行前拦"。探针要是自己调 `PreReadWiring.beforeToolCall` 再
// 自己判，验的就只是板子（JVM 已经绿了），盘上有没有真写出去这一步没被证明。
// 所以这里用的是生产同一入口：
//   · 未批准的调用 ⇒ [GuardPipeline.execute]（模型那一侧走的路）；
//   · 用户点过批准 ⇒ [GuardPipeline.executeApproved]（ConfirmFlow 批准后走的那一条）。
// 两条都探，是因为有一条要证的硬话：**批准不能代替回执**——她点了"允许写"，
// 没读单子照样拦（这是裁定里"一律"两个字的含义）。
//
// ## 边界
// - 不进产品路径：只装配预读这一个闸，不碰 W3、不碰授权板、不弹任何卡。
// - 唯一入口是本 Activity（不挂 LAUNCHER），只能显式 `am start`；运行期再查一次 FLAG_DEBUGGABLE。
// - 落盘只落在 App 自己的外部私有目录 `Android/data/…/files/preread-probe/`，
//   不碰她的证道工作区，也不碰任何真实文件。
// - 跑完自动 finish；读数同时进三处：logcat（带时间戳）、屏上（截图用）、结果文件（pull 用）。
package com.example.zhengdao.baopu.preread

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.MemoryLayout
import com.example.zhengdao.baopu.agent.DeviationWiring
import com.example.zhengdao.baopu.agent.GuardPipeline
import com.example.zhengdao.baopu.agent.ReadFileTool
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.agent.ToolRegistry
import com.example.zhengdao.baopu.agent.ToolRunner
import com.example.zhengdao.baopu.agent.WriteFileTool
import com.example.zhengdao.baopu.agent.jsonObjectOf
import java.io.File

/**
 * 436-B 预读回执闸真机探针：五相（A 只读放行／B 拦住／C 批准后仍拦／D 交回执放行／E 回看）。
 *
 * 每相一条 `ROW|相|做什么|判定|细节` 进 logcat，屏上同步列出，全部跑完落
 * `preread-probe/probe_result.txt`（pull 得到，不靠转述）。
 */
class PreReadDeviceProbeActivity : Activity() {

    private val rows = mutableListOf<String>()
    private var outDir: File? = null
    private var view: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val started = System.currentTimeMillis()
        // 取证要**每个判据各一张屏、各一段 logcat**（裁定：产物按判据编号命名）。五相挤在一次启动里
        // 只有一张屏，①的"被拦"和②的"放行"会在同一张图上——那不算两条证据。所以加这个断点：
        //   `--es stop_after blocked` ⇒ 跑到 C 相（拦住了）就收，屏上只有"没交回执"那一半；
        //   不带参数 ⇒ 全相跑完（D 放行、E 回看）。两次启动各拍一张，时间戳天然分开。
        val stopAfter = intent.getStringExtra("stop_after").orEmpty()
        Log.i(TAG, "ROW|GATE|debug 构建|start|batch=436-B|stop_after=${stopAfter.ifEmpty { "all" }}")
        if (!isDebugBuild()) {
            record("GATE", "debug 构建检查", "FAIL", "FLAG_DEBUGGABLE=false，拒绝运行")
            renderAndFinish()
            return
        }
        outDir = File(getExternalFilesDir(null), PROBE_DIR).apply { mkdirs() }
        // 账本落点在**生产**里是由 BaopuRuntime 挂的（`DecisionLedger.install(…/抱朴记忆)`）。
        // 探针不启动整个抱朴运行时，但不挂这一下账本只走内存 ⇒ 判据③「盘上那本账」就没有对象。
        // 用生产同一个 helper 挂（不自己拼路径），落点与线上逐字相同。
        DecisionLedger.install(MemoryLayout.globalMemoryDir(filesDir))
        record("SET", "账本落点", "OK", "${DecisionLedger.installedRoot()?.absolutePath}")
        render("预读回执闸真机探针（436-B）\n跑动中…")
        val report = runCatching { probeAll(stopAfter) }
            .fold(
                onSuccess = { it },
                onFailure = {
                    record("ALL", "探针自身抛异常", "FAIL", "${it.javaClass.simpleName}: ${it.message}")
                    "探针异常：${it.javaClass.simpleName} ${it.message}"
                },
            )
        record("SUM", "总结", if (report.startsWith("PASS")) "PASS" else "FAIL", report)
        writeResult(started, stopAfter, report)
        renderAndFinish()
    }

    // ── 五相 ────────────────────────────────────────────────────────────────

    /** @return 一行总结（`PASS…` 或 `FAIL…`），给 SUM 相用。 */
    private fun probeAll(stopAfter: String): String {
        val root = outDir ?: return "FAIL 没有输出目录"
        val task = "probe-task"
        val target = File(root, "被写的文件.txt")
        target.delete()

        // 信箱：轮 id ＋ 任务 id（生产侧这两个由 450 回填；这里手动装，走同一个读法）
        DeviationWiring.installRun("probe-run-1", task)
        val gaps = PreReadWiring.attachOrder(
            ReadFirstOrder(
                taskKey = task,
                items = listOf(
                    ReadFirstItem("GuardPipeline.kt:441", ReadFirstKind.FILE_LINE, "闸的落点：② 之后 ③ 之前"),
                    ReadFirstItem("判据①（未交回执调写工具⇒报错拦住）", ReadFirstKind.CRITERION),
                    ReadFirstItem("E-173（长门禁后先 fetch 再 commit）", ReadFirstKind.PITFALL),
                ),
                requireReceipt = true,
                title = "真机取证：预读回执闸",
            ),
        )
        if (gaps.isNotEmpty()) return "FAIL 任务单没收下：${gaps.joinToString("、") { it.cn }}"
        record("SET", "附必读清单", "OK", "3 条（文件:行／判据／坑账）｜要回执=显式｜账本落点=${DecisionLedger.installedRoot()?.name}")

        val pipeline = pipeline(root)

        // A：只读工具在未交回执时照走（判据⑤）
        val readme = File(root, "先读这个.txt").apply { writeText("预读正文") }
        val a = pipeline.execute(ToolCall("ca", "read_file", jsonObjectOf("path" to readme.absolutePath))) { _, _ -> null }
        record("A", "read_file（未交回执）", verdict(a is GuardPipeline.Result.Done), a.summary)

        // B：写工具未交回执 ⇒ 拦住，且盘上没有那个文件（"不执行"的实证）
        val b = pipeline.writeCall(target)
        val bBlocked = b is GuardPipeline.Result.Failed && b.error.contains(BLOCK_HEAD) && !target.exists()
        record("B", "write_file（未交回执）", verdict(bBlocked), "结果=${b.summary}｜盘上存在=${target.exists()}")

        // C：她点了批准也不能代替回执（裁定"一律"两字）
        val c = pipeline.writeApproved(target)
        val cBlocked = c is GuardPipeline.Result.Failed && c.error.contains(BLOCK_HEAD) && !target.exists()
        record("C", "批准后仍走写（未交回执）", verdict(cBlocked), "结果=${c.summary}｜盘上存在=${target.exists()}")

        // 断点：只跑到"拦得住"这一半就收（判据①一张自己的屏；此刻回执确实还没交过）。
        if (stopAfter == "blocked") {
            val half = rows.count { it.contains("|FAIL|") }
            return if (half == 0) "PASS 拦得住（A 放行读／B 未交回执拦写法／C 批准也不放）——D/E 留给下一次启动"
            else "FAIL blocked 段有 $half 行没过"
        }

        // D：交回执 ⇒ 放行 ⇒ 动作真的执行（盘上真有了这个文件、内容可验）
        val submitGaps = PreReadWiring.submitReceipt(
            PreReadReceipt(
                taskKey = task,
                readLines = listOf("GuardPipeline.kt:441", "判据①", "E-173"),
                understanding = "闸落在路径守卫之后、判定之前：被拦的调用不进 RiskGate，也不碰 W3；只拦写不拦读。",
                plannedFiles = listOf("被写的文件.txt"),
            ),
        )
        if (submitGaps.isNotEmpty()) return "FAIL 回执没收下：${submitGaps.joinToString("、") { it.cn }}"
        val d = pipeline.writeApproved(target)
        val wrote = target.isFile && target.readText().contains(PROBE_TEXT)
        record("D", "交完回执再写同一笔", verdict(d is GuardPipeline.Result.Done && wrote), "结果=${d.summary}｜盘上内容对=${wrote}")

        // E：可回看（判据④）＋ 账在盘上（判据③的原件由 run-as 拉，这里只证明它存在）
        val screen = PreReadWiring.review(listOf("被写的文件.txt"))
        val perItem = listOf("GuardPipeline.kt:441", "判据①", "E-173").count { t -> screen.any { it.contains(t) } }
        record("E", "回看一屏（清单↔回执逐条）", verdict(perItem == 3), "清单 3 条里回执对到 $perItem 条｜行数=${screen.size}")
        // 判据④要的是**这一屏的原文**（"它当时怎么理解的"），不是"3/3"这个结论 ⇒ 逐行落读数，
        // logcat／屏上／结果文件三处都能看到同样这 11 行。
        screen.forEachIndexed { i, line -> record("E", "回看·${i + 1}", "INFO", line) }
        val ledger = File(DecisionLedger.installedRoot() ?: File("/dev/null"), PreReadLedger.FILE_NAME)
        record("E", "账本文件在盘上", verdict(ledger.isFile), "${ledger.absolutePath}｜${ledger.length()} 字节（原件用 run-as 拉）")

        val fails = rows.count { it.contains("|FAIL|") }
        return if (fails == 0) "PASS 五相全过（A 放行读／B 拦写法／C 批准不放／D 交了放行且落盘／E 可回看）"
        else "FAIL 有 $fails 相没过，看上面各行"
    }

    /** 生产同一个未批准入口（模型调工具走的就是这条）。 */
    private fun pipeline(root: File): GuardPipeline {
        val registry = ToolRegistry().apply {
            register(ReadFileTool.spec())
            register(WriteFileTool.spec { root })
        }
        return GuardPipeline(registry, { root }, { null }, ToolRunner(ToolRunner.toolExecutor))
    }

    private fun GuardPipeline.writeCall(target: File): GuardPipeline.Result =
        execute(ToolCall("cw", "write_file", jsonObjectOf("path" to target.absolutePath, "content" to PROBE_TEXT))) { _, _ -> null }

    private fun GuardPipeline.writeApproved(target: File): GuardPipeline.Result =
        executeApproved(ToolCall("ca2", "write_file", jsonObjectOf("path" to target.absolutePath, "content" to PROBE_TEXT))) { _, _ -> null }

    // ── 读数出口 ────────────────────────────────────────────────────────────

    private fun record(phase: String, what: String, mark: String, detail: String) {
        val line = "ROW|$phase|$what|$mark|$detail"
        rows += line
        Log.i(TAG, line)
    }

    private fun verdict(ok: Boolean) = if (ok) "PASS" else "FAIL"

    private val GuardPipeline.Result.summary: String
        get() = when (this) {
            is GuardPipeline.Result.Done -> "Done"
            is GuardPipeline.Result.Failed -> "Failed｜${error.take(120)}"
            is GuardPipeline.Result.Rejected -> "Rejected｜${reason.take(120)}"
            is GuardPipeline.Result.NeedsConfirm -> "NeedsConfirm"
        }

    private fun render(text: String) {
        val tv = view ?: TextView(this).also {
            it.setPadding(24, 24, 24, 24)
            it.typeface = android.graphics.Typeface.MONOSPACE
            view = it
        }
        tv.text = text
        if (tv.parent == null) setContentView(ScrollView(this).apply { addView(tv) })
    }

    private fun renderAndFinish() {
        render(rows.joinToString("\n"))
        // **不能立刻 finish**：读数要拍成截图（判据①「截图＋logcat」）。立刻 finish 的话，
        // 宿主的 screencap 拍到的已经是桌面了——绿是绿的，但那张图不算证据。
        // 停 90 秒再自己退：够截一张、够看，且第二次启动时 noHistory 会把上一张收掉。
        android.os.Handler(mainLooper).postDelayed({ finish() }, HOLD_MS)
    }

    /** 结果文件（`adb pull` 得到，取证不靠转述）。两份：`probe_result.txt` 是最新一屏，
     *  `probe_result_<段>.txt` 按启动段留底，两次启动的读数不会互相覆盖。 */
    private fun writeResult(started: Long, stopAfter: String, report: String) {
        val dir = outDir ?: return
        val body = buildString {
            appendLine("436-B 预读回执闸 · 真机探针")
            appendLine("开始时刻=$started（logcat -v time 对得上）｜设备=${android.os.Build.MODEL} ${android.os.Build.VERSION.RELEASE}")
            appendLine("包=$packageName｜段=${stopAfter.ifEmpty { "all" }}")
            appendLine()
            rows.forEach(::appendLine)
            appendLine()
            appendLine("结论：$report")
        }
        runCatching {
            File(dir, "probe_result.txt").writeText(body)
            File(dir, "probe_result_${stopAfter.ifEmpty { "all" }}.txt").writeText(body)
        }.onFailure { Log.i(TAG, "ROW|OUT|结果文件|FAIL|${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun isDebugBuild(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    companion object {
        /** logcat 过滤用的 tag（`adb logcat -s PrereadProbe:V` 一条链看得完）。 */
        private const val TAG = "PrereadProbe"
        private const val PROBE_DIR = "preread-probe"
        private const val PROBE_TEXT = "预读闸真机证据（436-B）"
        /** 读数屏留多久（截图窗口）；到点自己 finish，不留一个常驻页面。 */
        private const val HOLD_MS = 90_000L
    }
}
