// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九十批 ② · 生产 eval 入口（派工单 §2：**默认关**、关掉零额外耗时/零网络、跑只记录不阻塞）。
//
// 它与 84 批的 `SelfCheckGate` 是两个东西，别混：
// - `SelfCheckGate` 管「**模型提案落盘后**要不要自检」——它挂了会**回滚**用户刚写的东西，
//   所以允许「调试构建开箱即跑」（devMode 并联）；
// - 本闸门管「**线上要不要周期性烧额度跑一版评测**」——它花的是用户的钱、烧的是网络，
//   而且产出的只是数据。所以这里**没有 devMode 那条并联**：调试构建、CI、真机一律默认关，
//   只有用户自己在设置里拨过开关才跑。少了这条差别，就会出现「跑个单测顺手烧掉一次额度」。
//
// 三条硬边界（都有用例钉着，见 ProdEvalTest）：
// 1. **关掉时一个口子都不碰**：不读 assets、不建目录、不发请求、不写文件——不是「很快就返回」，
//    是可数的零次调用；
// 2. **失败不阻塞对话**：入口整条路径包在 try/catch 里，任何异常只变成一个 NotRun 记录，
//    调用方拿到的永远是「读数或原因」，不会有异常冒到聊天路径上；
// 3. **只产出数据**：不回滚、不改技能、不改人设、不晋升——决策口（`EvolutionPolicy` / 99 批的
//    证据桥）读的是这份文件里的 `passRate` 与 `cases[].id`；**没有这份文件就是 DEFER，不是通过**。
package com.example.zhengdao.baopu.eval

import android.content.Context
import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.Provider
import com.example.zhengdao.rootfs.RunLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 生产 eval 的闸门（**纯判定**，Context 只用来取 SharedPreferences）。
 *
 * 与 [SelfCheckGate] 共用同一个 prefs 文件（`zhengdao_eval`），但**键分开**：
 * 自检与生产跑批是两件不同的授权，不能一个开关管两件事。
 */
internal object ProdEvalGate {

    /** 用户拨动的那个开关（设置页「生产评测」；写口在 [ProdEvalSettings]）。 */
    const val PREF_KEY = "eval_prod_on"

    /** 与 [SelfCheckGate] 共用的 prefs 文件（键分开，见类 KDoc）。 */
    const val PREFS_NAME = "zhengdao_eval"

    /** 默认值。**false**——派工单 §2：默认关。 */
    const val PREF_DEFAULT = false

    /** 纯判定：只有用户拨过才开（**没有 devMode 并联**，理由见文件头）。 */
    fun enabled(prefOn: Boolean): Boolean = prefOn

    /** 现读用户开关；读不到按**关**处理（磁盘坏了一道开关不该变成默认开）。 */
    fun prefOn(ctx: Context): Boolean = runCatching {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREF_KEY, PREF_DEFAULT)
    }.getOrDefault(PREF_DEFAULT)

    /** 当前该不该跑。 */
    fun enabledNow(ctx: Context): Boolean = enabled(prefOn(ctx))

    // 写口不在这个对象里（第一百六十八批：设置页开关落在 [ProdEvalSettings]，本对象仍旧只读）——
    // 照 84 批 SelfCheckGate 的先例，闸门与写口分开，源码门禁才门得住。拨动永远只能是
    // 「显式」：不许有 devMode、adb、环境变量之类悄悄打开的旁路。
}

/**
 * 一次生产跑批的结果。**没有「假装通过」这一档**：跑不了就是 [NotRun]，关掉就是 [Disabled]。
 */
internal sealed interface ProdEvalOutcome {
    /** 开关关着：什么都没发生（也没有耗时）。 */
    object Disabled : ProdEvalOutcome
    /** 该跑但跑不了：原因必须说清，不许静默。 */
    data class NotRun(val reason: String) : ProdEvalOutcome
    /** 真跑了：读数在这里，产物也已落盘（[artifactWritten]=false 时读数只进了日志）。 */
    data class Recorded(
        val total: Int,
        val passed: Int,
        val passRate: Double,
        val avgSteps: Double,
        val elapsedMs: Long,
        val tokensIn: Int,
        val tokensOut: Int,
        val costYuanEstimate: String,
        val determinismLabel: String,
        val personaSkipped: Int,
        val artifactWritten: Boolean,
        val artifactPath: String,
    ) : ProdEvalOutcome
}

/**
 * 生产 eval 的执行壳（Context 相关的全在 [of]，判据与拼装沿用 84/50 批那一条口）。
 *
 * 依赖全部注入成函数，是为了把「关掉时零调用」这条**量出来**而不是说出来：
 * 见 `ProdEvalTest` 里的计数器假件。
 */
internal class ProdEval(
    private val gateOn: () -> Boolean,
    private val casesJson: () -> String?,
    private val providerOf: () -> Provider,
    private val systemPromptOf: () -> String,
    private val artifactPath: () -> String,
    private val writeArtifact: (String) -> Boolean,
    private val runCases: (List<EvalCase>, EvalConfig) -> EvalRun,
    private val log: (String) -> Unit,
) {

    /**
     * 跑一次。**只在开关开着时才做任何事**，且任何失败只影响读数、不影响调用方。
     *
     * @param scene 触发场景（进日志与产物，用来对账「这一次是谁起的」）。
     */
    fun execute(scene: String): ProdEvalOutcome {
        val on = try {
            gateOn()
        } catch (t: Throwable) {
            // 兜底：连「读开关」这一步崩了也不许把异常抛回对话路径。
            return notRun(scene, "跑批入口异常：${t.javaClass.simpleName}: ${t.message}")
        }
        // 关掉 ⇒ 零额外耗时：这一条路径上连一行日志都不写（写日志要打开文件、拿时间戳）。
        // 「返回得快」不叫零耗时，「什么都没碰」才叫——所以它能被计数器证伪（见 ProdEvalTest）。
        if (!on) return ProdEvalOutcome.Disabled
        // 跑批本身每一段都可能崩（读 prefs、取 key、拼人设、落盘）——崩了只变读数，不抛回调用方。
        val out = try {
            run(scene)
        } catch (t: Throwable) {
            ProdEvalOutcome.NotRun("跑批入口异常：${t.javaClass.simpleName}: ${t.message}")
        }
        // 失败不许静默（84 批那条口径）：没跑成必须说一句为什么，但**只说**，不动用户的文件。
        return if (out is ProdEvalOutcome.NotRun) notRun(scene, out.reason) else out
    }

    /** 没跑成 ⇒ 留一行账再返回读数：既不许静默，也不许把异常冒回调用方。 */
    private fun notRun(scene: String, reason: String): ProdEvalOutcome {
        log("[prod-eval] $scene 未跑：$reason（只记录，不阻塞对话）")
        return ProdEvalOutcome.NotRun(reason)
    }

    private fun run(scene: String): ProdEvalOutcome {
        val provider = providerOf()
        RuntimeEval.unusableReason(provider)?.let { return ProdEvalOutcome.NotRun(it) }
        val json = casesJson()
            ?: return ProdEvalOutcome.NotRun("用例读不到（APK 里没有 ${EvalAssets.CASES_PATH}）")
        val cases = try {
            EvalCases.parse(json)
        } catch (t: Throwable) {
            // 坏用例宁可明说「没跑」，也不许少跑几条还报数（parse 是当场抛的，见 EvalCases）
            return ProdEvalOutcome.NotRun("用例解析失败：${t.message}")
        }
        if (cases.isEmpty()) return ProdEvalOutcome.NotRun("用例是空的（0 条）：跑它没有意义")
        // 生产口径只跑水位池：B/C fixture 用例测的是「通路」，不是「这台机器的人设有没有把事变坏」。
        // 跳过必须留名（安静少跑几条比红更糟），名单与条数一起进产物。
        val pool = cases.filter { !it.usesLayerFixture }
        val skipped = cases.filter { it.usesLayerFixture }.map { it.id }
        if (pool.isEmpty()) return ProdEvalOutcome.NotRun("水位池是空的（全是要跳过的 fixture 用例）")

        val cfg = RuntimeEval.configOf(provider, systemPromptOf())
        val run = try {
            runCases(pool, cfg)
        } catch (t: Throwable) {
            return ProdEvalOutcome.NotRun("跑批崩了（不阻塞对话，只记录）：${t.javaClass.simpleName}: ${t.message}")
        }
        val determinism = EvalDeterminism()
        val doc = artifactOf(scene, System.currentTimeMillis(), provider, cfg, run, skipped, determinism)
        val path = artifactPath()
        val written = runCatching { writeArtifact(doc.toString(2) + "\n") }.getOrDefault(false)
        val rate = if (run.total > 0) run.passed.toDouble() / run.total else 0.0
        val avg = run.results.map { it.steps }.average().takeIf { !it.isNaN() } ?: 0.0
        log(
            "[prod-eval] $scene：${run.passed}/${run.total} 通过（${"%.0f".format(rate * 100)}%），" +
                "平均 ${"%.1f".format(avg)} 步，token 入 ${run.tokensIn} 出 ${run.tokensOut}，" +
                "估算 ¥${EvalCost.yuan(run.tokensIn, run.tokensOut)}，跳过 fixture ${skipped.size} 条；" +
                "${determinism.label()}；产物 $path${if (written) "" else "（写失败：读数只进了日志）"}",
        )
        if (!written) log("[prod-eval] 产物写不进 $path（磁盘/权限）——决策口按「没有证据」处理，不许当通过")
        return ProdEvalOutcome.Recorded(
            total = run.total,
            passed = run.passed,
            passRate = rate,
            avgSteps = avg,
            elapsedMs = run.elapsedMs,
            tokensIn = run.tokensIn,
            tokensOut = run.tokensOut,
            costYuanEstimate = EvalCost.yuan(run.tokensIn, run.tokensOut),
            determinismLabel = determinism.label(),
            personaSkipped = skipped.size,
            artifactWritten = written,
            artifactPath = path,
        )
    }

    companion object {

        /**
         * 产物结构（**纯函数**，抽出来才能不建 Context 就锁形状）。
         *
         * 字段名与 99 批的证据桥对齐：`passRate`（0–1）＋ `cases[].id` 是**判据字段**，
         * 缺一个或越界，消费方必须判「没有证据 ⇒ DEFER」，不许当成通过（这就是 §2 要的「只产出数据」）。
         * 其余是记账字段：口径（通道/模型/确定性档/token/花费估算）与谁被跳过。
         *
         * ⚠️ **绝不含 apiKey**：provider 只取 id/baseUrl/model 三个可公开字段。
         */
        internal fun artifactOf(
            scene: String,
            ranAt: Long,
            provider: Provider,
            cfg: EvalConfig,
            run: EvalRun,
            personaSkipped: List<String>,
            determinism: EvalDeterminism,
        ): JSONObject {
            val arr = JSONArray()
            for (r in run.results) {
                arr.put(
                    JSONObject()
                        .put("id", r.id)
                        .put("ok", r.ok)
                        .put("steps", r.steps)
                        .put("failures", JSONArray(r.failures))
                        .put("toolCalls", JSONArray(r.toolCalls))
                        .put("tokensIn", r.tokensIn)
                        .put("tokensOut", r.tokensOut),
                )
            }
            val skip = JSONArray()
            for (id in personaSkipped) skip.put(JSONObject().put("id", id).put("reason", PROD_EVAL_SKIP_FIXTURE))
            return JSONObject()
                .put("schema", PROD_EVAL_SCHEMA)
                .put("scene", scene)
                .put("ranAt", ranAt)
                .put("provider", provider.id)
                .put("model", cfg.model)
                .put("baseUrl", cfg.baseUrl)
                .put("determinism", determinism.toJson())
                .put("passRate", if (run.total > 0) run.passed.toDouble() / run.total else 0.0)
                .put("avgSteps", run.results.map { it.steps }.average().takeIf { !it.isNaN() } ?: 0.0)
                .put("elapsedMs", run.elapsedMs)
                .put("tokensIn", run.tokensIn)
                .put("tokensOut", run.tokensOut)
                .put("costYuanEstimate", EvalCost.yuan(run.tokensIn, run.tokensOut))
                .put("cases", arr)
                .put("skipped", skip)
                .put(
                    "personas",
                    JSONObject()
                        .put("fixtureSkipped", personaSkipped.size)
                        .put("note", "B/C fixture 用例不在生产口径；这两层的水位仍未实测"),
                )
                .put("blocking", false)
                .put("rollbackPerformed", false)
        }

        const val PROD_EVAL_SCHEMA = "zhengdao-prod-eval/1"

        /** 产物目录与文件名：落在 App 私有 filesDir 下的 eval 目录里，与仓库里的 eval 目录无关。 */
        const val ARTIFACT_DIR = "eval"
        const val ARTIFACT_NAME = "prod-result.json"
        const val PROD_EVAL_SKIP_FIXTURE = "B/C fixture 用例（生产口径只跑水位池）"

        /** 产物落点：App 私有目录，**不进 assets、不进仓库**（构建期那份归 99 批的 staged 文件）。 */
        fun artifactFile(ctx: Context): File = File(File(ctx.filesDir, ARTIFACT_DIR), ARTIFACT_NAME)

        /**
         * 装配（唯一需要 Context 的地方）。
         *
         * 系统提示走 [PersonaPrompt.ofDisk]：**现读**盘上人设，与自检同一条口——
         * 缓存它就等于测一份旧人设。跑批本身由调用方放到 IO 线程上（本工厂不做调度）。
         */
        fun of(ctx: Context, personaFiles: PersonaFiles, workspace: File): ProdEval {
            val file = artifactFile(ctx)
            return ProdEval(
                gateOn = { ProdEvalGate.enabledNow(ctx) },
                casesJson = { EvalAssets.casesJson(ctx) },
                providerOf = { RuntimeEval.providerOf(ctx) },
                systemPromptOf = { PersonaPrompt.ofDisk(personaFiles, workspace) },
                artifactPath = { file.absolutePath },
                writeArtifact = { text ->
                    runCatching {
                        file.parentFile?.mkdirs()
                        file.writeText(text, Charsets.UTF_8)
                        true
                    }.getOrDefault(false)
                },
                runCases = { cases, cfg -> RuntimeEval.run(cases, cfg, tag = RuntimeEval.PROD_EVAL_TAG) },
                log = { RunLog.log(it) },
            )
        }
    }
}
