// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

import android.content.Context
import com.example.zhengdao.rootfs.RunLog
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hermes 依赖环境（ERRATA E-025，2026-10-08 真机事故驱动）。
 *
 * **事故**：装好 Hermes 后恢复"搬家包"，再敲 `hermes` 只剩三行错，关掉 App 重进也一样：
 *
 * ```
 * hermes: source-update completion failed: [Errno 2] No such file or directory:
 *         '/root/.hermes/hermes-agent/uv.lock'; running with the previous dependencies …
 * hermes: dependency repair failed: venv: recorded dependency lock is missing; refusing to drop plugins …
 * hermes: dependency environment is missing or outside this install:
 *         /root/.hermes/installs/<旧机 hash>/environments/<旧机代>/venv; run `hermes pm repair`
 * ```
 *
 * **两条根因**（都不是 App 的锅，但 App 侧一直缺这一层检查）：
 * 1. 搬家包**有意不带** `installs/<hash>/environments/` 整个目录（345M），却带了旧机的
 *    `installs/<hash>/facts.json` —— 那条记录指向旧机上的依赖代，本机不存在；
 * 2. 一次被中断的自我更新删掉了受 git 管理的 `hermes-agent/uv.lock`（+`flake.lock`）却没重建，
 *    于是每次启动都先报 source-update completion failed。**且 `hermes update` 救不了**：
 *    非 `pm` 子命令在 bootstrap 阶段就退出（`hermes_bootstrap.py:582-620`）。
 *
 * **本对象的职责**：宿主侧**只读**判定 + 能纯文件修的立刻修好；修不了的（要重建 Python 环境、
 * 要 `git checkout`）把脚本落盘、由用户在终端里跑一眼能看懂的过程（[script] / [REPAIR_CMD]）。
 *
 * 判定只有文件读写、不 spawn 进程（与 [EnvHealth] 的其它项同源），所以能进单测、秒级完成。
 * 宿主侧 `filesDir/home` 与 guest 的 `/root` 是同一个 bind（见 [ProotLauncher]），
 * 因此在宿主改 `facts.json` 与在 guest 内改是同一件事——这正是能"一键修正记录"的前提。
 */
object HermesEnv {

    /** guest 侧脚本落点（宿主侧 = `Workspace.hostDir/.zhengdao/scripts/`，与 AgentInstaller 同约定）。 */
    const val SCRIPT_REL = ".zhengdao/scripts/hermes-env-repair.sh"

    /** 终端里可直接执行的修复命令（进 guest 跑同一份脚本，输出全程可见）。 */
    const val REPAIR_CMD = "bash /workspace/.zhengdao/scripts/hermes-env-repair.sh"

    /** `filesDir/home` 即 guest 的 `/root`。 */
    fun hermesHome(ctx: Context): File = File(ctx.filesDir, "home/.hermes")

    /**
     * 体检结论。
     *
     * @param installed `installs/` 是否存在（没装 Hermes 就不是问题）
     * @param ok 依赖环境可用（含源码锁在、无陈旧标记）
     * @param detail 给用户看的一句话
     * @param recordedEnv facts.json 里记录的依赖环境路径（诊断用）
     * @param canRepairOnHost 宿主侧纯文件就能修好（有完整代可指过去 / 只需清标记）
     * @param sourceLockMissing `hermes-agent/uv.lock` 不在（每次启动会报警告）
     * @param staleLocks 更新中断留下的标记文件个数
     */
    data class State(
        val installed: Boolean,
        val ok: Boolean,
        val detail: String,
        val recordedEnv: String?,
        val canRepairOnHost: Boolean,
        val sourceLockMissing: Boolean,
        val staleLocks: Int,
    )

    fun inspect(ctx: Context): State = inspect(hermesHome(ctx))

    /**
     * 宿主侧只读判定（纯文件，可单测）。`root` = `.hermes` 目录。
     */
    fun inspect(root: File): State {
        val installs = File(root, "installs")
        if (!installs.isDirectory) {
            return State(false, true, "未安装 Hermes，跳过", null, false, false, 0)
        }
        val stale = staleLockFiles(root).size
        val sourceLockMissing = File(root, "hermes-agent").isDirectory &&
            !File(root, "hermes-agent/uv.lock").isFile
        val complete = completeGenerations(installs)

        var recordedEnv: String? = null
        var anyRecord = false
        var brokenRecord = false
        installDirs(installs).forEach { dir ->
            val facts = File(dir, "facts.json")
            if (!facts.isFile) return@forEach
            val venv = readVenv(facts) ?: return@forEach
            val env = venv.first ?: return@forEach
            anyRecord = true
            if (recordedEnv == null) recordedEnv = env
            if (!venvUsable(env, venv.second)) brokenRecord = true
        }

        val hostFixable = when {
            // 有完整代可指过去 → 改写记录即可；一代都没有 → 只能进 guest 重建
            brokenRecord -> complete.isNotEmpty()
            // 压根没有记录可改（facts.json 不在或没写 venv）→ 也得进 guest 重建
            !anyRecord -> false
            // git checkout 只能在 guest 里做
            sourceLockMissing -> false
            // 删陈旧标记是纯文件操作
            stale > 0 -> true
            else -> false
        }

        val ok = anyRecord && !brokenRecord && !sourceLockMissing && stale == 0
        val detail = when {
            brokenRecord && complete.isNotEmpty() -> "依赖环境记录指向不存在的目录，可一键修正（盘上有可用的代）"
            brokenRecord -> "依赖环境已丢失，需在终端重建（点「修复」会在终端里跑，过程可见）"
            !anyRecord -> "尚未登记依赖环境（安装可能没跑完），需在终端重建"
            sourceLockMissing -> "源码锁 uv.lock 缺失（每次启动会报警告），可在终端一键补回"
            stale > 0 -> "有 $stale 个更新中断留下的标记文件，可一键清理"
            else -> "依赖环境记录有效"
        }
        return State(true, ok, detail, recordedEnv, hostFixable, sourceLockMissing, stale)
    }

    /**
     * 宿主侧纯文件修复（返回是否真的改了东西）：
     *
     * 1. `facts.json` 记的依赖代不存在 → 指针改成盘上真实存在的完整代；一代都没有就把这条
     *    失效记录**备份后删掉**，让 guest 里的 `hermes pm repair` 能重新登记
     *    （记录在时 `pm repair` 会以 "recorded dependency lock is missing" 拒绝重建）；
     * 2. 删掉更新中断留下的标记文件（`.recovery.lock` / `.repair-incomplete` /
     *    `.hermes-update-in-progress.lock`）。
     *
     * 备份一律落在原目录（`facts.json.bak-证道<时间戳>`），不删用户数据。
     * ⚠️ 别在 `hermes pm repair` 正跑的时候点：本函数会删更新/修复的进行中标记。
     */
    fun repairOnHost(root: File): Boolean {
        var changed = false
        val installs = File(root, "installs")
        val complete = completeGenerations(installs)
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

        installDirs(installs).forEach { dir ->
            val facts = File(dir, "facts.json")
            if (!facts.isFile) return@forEach
            val venv = readVenv(facts) ?: return@forEach
            val env = venv.first ?: return@forEach
            if (venvUsable(env, venv.second)) return@forEach

            runCatching { facts.copyTo(File(dir, "facts.json.bak-证道$ts"), overwrite = true) }
            val target = complete.maxByOrNull { it.lastModified() }
            if (target != null) {
                val moved = runCatching {
                    val json = JSONObject(facts.readText())
                    val packages = json.optJSONObject("packages") ?: JSONObject().also { json.put("packages", it) }
                    val venvObj = packages.optJSONObject("venv") ?: JSONObject().also { packages.put("venv", it) }
                    venvObj.put("environment", File(target, "venv").absolutePath)
                    File(target, "workspace/uv.lock").takeIf { it.isFile }?.let {
                        venvObj.put("resolved_lock", it.absolutePath)
                    }
                    facts.writeText(json.toString(2))
                    true
                }.getOrDefault(false)
                if (moved) {
                    RunLog.log("HermesEnv: 依赖环境记录已改指存在的代 ${target.name}")
                    changed = true
                }
            } else {
                // 一代都没有：留着这条记录会让 pm repair 直接拒绝重建
                val dropped = runCatching { facts.delete() }.getOrDefault(false)
                if (dropped) {
                    RunLog.log("HermesEnv: 已删失效的依赖环境记录（备份 facts.json.bak-证道$ts），等 guest 重建")
                    changed = true
                }
            }
        }

        staleLockFiles(root).forEach { f ->
            if (runCatching { f.delete() }.getOrDefault(false)) {
                RunLog.log("HermesEnv: 清掉陈旧标记 ${f.name}")
                changed = true
            }
        }
        return changed
    }

    /** 把修复脚本写到工作区（guest 内 `/workspace/.zhengdao/scripts/`），返回是否成功。 */
    fun writeRepairScript(ctx: Context): Boolean = runCatching {
        val dir = File(Workspace.hostDir(ctx), ".zhengdao/scripts")
        if (!dir.isDirectory && !dir.mkdirs()) return false
        File(dir, "hermes-env-repair.sh").writeText(readRepairScript(ctx))
        true
    }.getOrElse {
        RunLog.log("HermesEnv: 修复脚本落盘失败：${it.message}")
        false
    }

    /** 脚本正文（`res/raw/hermes_env_repair.sh`，纯 shell 便于直接审阅）。 */
    fun readRepairScript(ctx: Context): String =
        ctx.resources.openRawResource(com.example.zhengdao.R.raw.hermes_env_repair)
            .bufferedReader().use { it.readText() }

    // ── 内部：解析与扫描 ────────────────────────────────────────────────

    private fun installDirs(installs: File): List<File> =
        installs.listFiles { f: File -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()

    /** 读 `facts.json` 里的 `packages.venv`（environment, resolved_lock）；读不动/没有则 null。 */
    internal fun readVenv(facts: File): Pair<String?, String?>? = runCatching {
        val json = JSONObject(facts.readText())
        val venv = json.optJSONObject("packages")?.optJSONObject("venv") ?: return null
        val env = venv.optString("environment").takeIf { it.isNotEmpty() }
        val lock = venv.optString("resolved_lock").takeIf { it.isNotEmpty() }
        env to lock
    }.getOrNull()

    /** 记录可用 = 环境目录在且含 `pyvenv.cfg`；记了锁则锁也得在（与 pm/environments.py 同条件）。 */
    internal fun venvUsable(env: String?, lock: String?): Boolean {
        if (env == null) return false
        if (!File(env, "pyvenv.cfg").isFile) return false
        return lock == null || File(lock).isFile
    }

    /**
     * 盘上**完整**的依赖代：`installs/<hash>/environments/<代>/venv/pyvenv.cfg`
     * 与 `.../workspace/uv.lock` 都在。只有完整的代才敢把记录指过去
     * （`pm/packages.py` 的 repair 路径要求记录里的锁文件真实存在）。
     */
    internal fun completeGenerations(installs: File): List<File> =
        installDirs(installs).flatMap { dir ->
            File(dir, "environments").listFiles { f: File -> f.isDirectory }?.toList() ?: emptyList()
        }.filter { gen ->
            File(gen, "venv/pyvenv.cfg").isFile && File(gen, "workspace/uv.lock").isFile
        }

    /** 更新/修复中断留下的标记文件（存在才返回）。 */
    internal fun staleLockFiles(root: File): List<File> {
        val names = listOf(".recovery.lock", ".repair-incomplete")
        val out = mutableListOf<File>()
        installDirs(File(root, "installs")).forEach { dir ->
            names.forEach { n -> File(dir, n).takeIf { it.isFile }?.let { out.add(it) } }
        }
        names.forEach { n -> File(root, n).takeIf { it.isFile }?.let { out.add(it) } }
        File(root, ".hermes-update-in-progress.lock").takeIf { it.isFile }?.let { out.add(it) }
        return out
    }
}
