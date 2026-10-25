// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零七批 · 种子技能包 v1（2026-10-25 派工单）：装置齐了、技能库是空的——
// 新装机没有任何内置技能，学习器没东西可学、「一句话触发」没有对象。
// 本批放三条**种子技能**（纯流程 md），首启拷进全局技能目录。
//
// 五条边界（全部写成判据，见 `SeedSkillsTest`）：
//   1. **种子是 md 不是代码**：assets 里只有 SKILL.md，运行时只读不执行；
//   2. **走系统路径安装**：落盘只经 `SkillStore.plan/apply`（备份→正文→索引，174 验过），
//      落点是全局技能目录（App 私有 `抱朴记忆/技能/`），**不塞工作区**；
//   3. **首装即草稿**：`status=draft`＋索引行带「草稿·未验证」标，转正走 §1.5 的两条路，
//      安装本身**不转正**；审计补一行 `action=seed`（`source=seed`）；
//   4. **删了不再装回**：用户删过某条种子 ⇒ 记进 `抱朴记忆/种子拒绝.json`，之后每次启动跳过它；
//   5. **绝不覆盖、失败出声**：同名技能已在（不管是不是种子改出来的）⇒ 跳过并留日志；
//      装失败 ⇒ 每条都有人话原因进 RunLog，不许静默。
package com.example.zhengdao.baopu

import com.example.zhengdao.rootfs.RunLog
import org.json.JSONArray
import java.io.File

/** 一条种子安装到一半的结论（只有 [SeedResult.Installed] 是装上了；其余都是「没装＋为什么」）。 */
internal sealed interface SeedResult {
    data class Installed(val name: String) : SeedResult
    data class Skipped(val name: String, val why: String) : SeedResult
    data class Failed(val name: String, val why: String) : SeedResult
}

/** 一次安装跑完的汇总（调用方拿它说一句便签＋写日志）。 */
internal data class InstallReport(val results: List<SeedResult>) {
    val installed: List<String> get() = results.filterIsInstance<SeedResult.Installed>().map { it.name }
    val failed: List<SeedResult.Failed> get() = results.filterIsInstance<SeedResult.Failed>()
}

internal object SeedSkills {

    /** assets 里的目录（APK 打包路径，不带 `app/src/main/assets/` 前缀）。 */
    const val ASSET_DIR = "baopu/skills"

    /** 三条种子（v1 就三条，宁少不滥；名字＝目录名，必须能原样过 [SkillStore.sanitizeName]）。 */
    val NAMES: List<String> = listOf("微头条流程", "T9取数", "产物清理")

    /**
     * 「删过不再装」名单的落点：`<App私有>/抱朴记忆/种子拒绝.json`（跟 App 走）。
     * 放技能目录**外面**：不污染技能区、不进 204 对账的扫描视野。
     */
    const val DECLINED_FILE = "种子拒绝.json"

    /** 某条种子在 APK 里的路径。 */
    fun assetPathOf(name: String): String = "$ASSET_DIR/$name/${SkillRules.SKILL_FILE}"

    fun declinedFile(globalRoot: File): File = File(globalRoot, DECLINED_FILE)

    /** 读拒绝名单（文件不在／读不出／格式坏 ⇒ 空集：坏名单不拦安装）。 */
    fun readDeclined(globalRoot: File): Set<String> = runCatching {
        val f = declinedFile(globalRoot)
        if (!f.isFile) return@runCatching emptySet<String>()
        val arr = JSONArray(f.readText(Charsets.UTF_8))
        buildSet { for (i in 0 until arr.length()) add(arr.optString(i)) }
    }.getOrDefault(emptySet())

    /**
     * 往拒绝名单加一个名字（合并去重后整体重写）。失败 ⇒ false 由调用方 log——
     * 名单写不进不会拦住本次删除，但下次安装会再试一次扫 `.deleted-*`（兜底在同一道闸里）。
     */
    fun markDeclined(globalRoot: File, name: String): Boolean = runCatching {
        val all = readDeclined(globalRoot) + name
        val f = declinedFile(globalRoot)
        f.parentFile?.mkdirs()
        f.writeText(JSONArray(all.sorted()).toString(), Charsets.UTF_8)
        true
    }.getOrDefault(false)

    /** 这个技能目录里有没有「删过」的痕迹（归档件 `SKILL.md.deleted-*` 还躺在原地）。 */
    private fun hasDeletedTrace(nameDir: File): Boolean =
        nameDir.listFiles { f -> f.isFile && f.name.startsWith("${SkillRules.SKILL_FILE}.deleted-") }
            ?.isNotEmpty() == true

    /**
     * 首启安装（**幂等**：已装／已拒／被改过 ⇒ 各自跳过，不重复装、不覆盖）。
     *
     * @param readAsset assets 读取口（路径 → 正文；读不出 ⇒ 该条失败，不静默）
     * @param globalRoot `<App私有>/抱朴记忆`（种子只落它下面的 `技能/`）
     * @param projectRoot 当前工作区（只喂给 plan 的索引两层合计判据；种子一个都不落这）
     */
    fun install(
        readAsset: (String) -> String?,
        globalRoot: File,
        projectRoot: File,
        personaFiles: PersonaFiles,
        backupStamp: String,
        nowMs: Long = System.currentTimeMillis(),
    ): InstallReport {
        val declined = readDeclined(globalRoot)
        val skillDir = SkillRules.globalSkillDir(globalRoot)
        val results = NAMES.map { name ->
            installOne(name, readAsset, globalRoot, projectRoot, personaFiles, backupStamp, nowMs, declined, skillDir)
        }
        // 每一条都留日志（判据：装失败不许静默——成了/跳过/失败各为什么，RunLog 里全有）。
        results.forEach { r ->
            val line = when (r) {
                is SeedResult.Installed -> "种子技能安装：${r.name}（草稿，不转正）"
                is SeedResult.Skipped -> "种子技能跳过：${r.name}（${r.why}）"
                is SeedResult.Failed -> "种子技能装失败：${r.name}（${r.why}）"
            }
            RunLog.log(line)
        }
        return InstallReport(results)
    }

    private fun installOne(
        name: String,
        readAsset: (String) -> String?,
        globalRoot: File,
        projectRoot: File,
        personaFiles: PersonaFiles,
        backupStamp: String,
        nowMs: Long,
        declined: Set<String>,
        skillDir: File,
    ): SeedResult {
        // ① 用户删过 ⇒ 永不再装（哪怕盘上痕迹已被清掉）。
        if (name in declined) return SeedResult.Skipped(name, "用户删过，已在拒绝名单")
        // ② 读不出正文 ⇒ 失败出声（APK 少文件/名字对不上，都算装失败，不许静默）。
        val body = readAsset(assetPathOf(name))
            ?: return SeedResult.Failed(name, "assets 里读不到 ${assetPathOf(name)}")
        // ③ 内容闸（与 192 免批判据同一张表）：种子自己也不许带代码块/外链/权限类词、超 2KB。
        val findings = SkillWritePolicy.forbiddenFindings(body)
        if (findings.isNotEmpty()) {
            return SeedResult.Failed(name, "正文不干净：${findings.joinToString("、")}")
        }
        val bytes = SkillWritePolicy.utf8Bytes(body)
        if (bytes == null || bytes > SkillWritePolicy.BODY_MAX_BYTES) {
            return SeedResult.Failed(name, "正文超 ${SkillWritePolicy.BODY_MAX_BYTES} 字节或编码不合法")
        }
        val nameDir = File(skillDir, name)
        val target = File(nameDir, SkillRules.SKILL_FILE)
        // ④ 同名已在 ⇒ 绝不覆盖（用户改过的那份一个字节不动，只在日志里说清跳过的原因）。
        if (target.isFile) {
            val sameSeed = runCatching { target.readText(Charsets.UTF_8) }.getOrDefault("").contains(body.trim())
            return SeedResult.Skipped(
                name,
                if (sameSeed) "已装过，不重复装" else "已有同名技能（可能被用户改过），不覆盖",
            )
        }
        // ⑤ 正文没了但归档件还在 ⇒ 用户删过它：记进拒绝名单，从这次起不再装。
        if (hasDeletedTrace(nameDir)) {
            val marked = markDeclined(globalRoot, name)
            if (!marked) RunLog.log("种子拒绝名单写入失败：$name（下次安装会再扫一次归档痕迹）")
            return SeedResult.Skipped(name, if (marked) "用户删过，已记入拒绝名单" else "用户删过；名单写入失败（见日志）")
        }
        // ⑥ 系统路径安装：SkillStore.plan/apply（备份→正文→索引），草稿头由 apply 那条路加。
        val proposal = SkillProposal(
            scope = SkillScope.GLOBAL,
            name = name,
            body = body,
            reason = "种子技能首装",
            draft = true,
        )
        val planned = SkillStore.plan(proposal, projectRoot, globalRoot, backupStamp)
        planned.blocked?.let { return SeedResult.Failed(name, it) }
        return when (
            val r = SkillStore.apply(
                planned,
                personaFiles,
                personaFiles.read(PersonaLayer.B_GLOBAL).orEmpty(),
            )
        ) {
            is SkillWriteResult.Written -> {
                val audited = SkillAudit.record(
                    skillFile = r.file,
                    scope = "global",
                    name = name,
                    action = "seed",
                    reason = "首启种子安装（草稿，不转正）",
                    beforeSha = "-",
                    indexUpdated = r.indexUpdated,
                    nowMs = nowMs,
                    source = "seed",
                )
                if (!audited) RunLog.log("种子技能审计行写入失败：$name")
                SeedResult.Installed(name)
            }
            is SkillWriteResult.Rejected -> SeedResult.Failed(name, r.reason)
        }
    }
}
