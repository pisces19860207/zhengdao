// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百九十批 · 种子技能版本清单（2026-11-06 派工单）：
// 207 批首启装的三条种子在「同名不覆盖」契约下永远停在装机那一刻的版本 ——
// 465 批把「微头条流程」从六步改成七步之后，老手机拿不到新版。
// 本文件只放**清单元数据**：每条种子的 version，以及旧版正文基线（判「未被人改过」的尺子）。
// v2 正文不内置，运行时从 APK assets 读（唯一来源，避免清单与资产漂移）。
//
// 三条纪律：
//   1. 指纹只覆盖「人能编辑的正文」：机器头 <!-- status=… uses=… --> 由 SkillStore 按使用/转正重算，
//      把头算进 hash 会让「用过一次」的种子被误判成「被人改过」，升级通道当场堵死；
//   2. 只有真正换过内容的种子才挂 previousBody；没有旧版基线的条目 version=1，永不迁移；
//   3. 本文件不写盘、不联网、不改既有契约。
package com.example.zhengdao.baopu

import java.security.MessageDigest

internal object SeedSkillCatalog {

    /** 审计行/旁挂标记的来源标（与 207 批 `source=seed` 同值）。 */
    const val SOURCE_SEED = "seed"

    /**
     * v1「微头条流程」六步原文：与 207 批首装进手机的正文逐字节一致（取自 `ee1f16e37^` 的资产）。
     * 705 字节、纯 LF、末尾换行照留 —— 原文一个字都不许改，改了就等于换了一把尺子。
     */
    const val V1_WEITOUTIAO: String = """# 写完这批微头条，按台账查重再动笔

适用：用户说「写完这批微头条」时按固定流程走。

1. 先读 产物/台账.md，把这批要写的题材与台账里已有的对照，写过的不再列。
2. 出一张题材与写法建议卡：每条给题材方向、写法要点、推荐理由；推荐的两三条在卡上标出来。
3. 用户从卡上选定几条后开写，没选定的一律不动。
4. 写完一篇落一篇：文件放 产物/ 目录，文件名带日期与序号。
5. 每落一篇，台账末尾加一行：日期、题材、文件名，状态列记 ☐（未发）。
6. 收尾报一句：这批落了几篇、还缺哪几条、台账新增了几行。
"""

    /** 一条种子的版本账。previousBody 为 null ⇒ 没有可迁移的旧版（永不升级）。 */
    data class Entry(val name: String, val version: Int, val previousBody: String?) {
        val fromVersion: Int get() = version - 1
    }

    /** 版本清单：名字必须与 SeedSkills.NAMES 同集合（由升级测试对账钉住）。 */
    val ENTRIES: List<Entry> = listOf(
        Entry("微头条流程", 2, V1_WEITOUTIAO),
        Entry("T9取数", 1, null),
        Entry("产物清理", 1, null),
    )

    fun entryOf(name: String): Entry? = ENTRIES.firstOrNull { it.name == name }

    /** 有旧版基线、本轮需要扫描的条目（没换过内容的不扫，省一次读盘）。 */
    fun upgradable(): List<Entry> = ENTRIES.filter { it.previousBody != null }

    /** 正文指纹（全量 sha256；空串＝算不出来，调用方按「判不了」处理，绝不冒充当命中）。 */
    fun sha256(text: String): String = runCatching {
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }.getOrDefault("")

    /**
     * 与盘上文件同一把尺子：剥掉首行机器头，对**其余原始字节**算指纹。
     * 特意不用 `SkillStore.contentWithoutHeader`（它经 `lines()` 会把 CRLF 归一成 LF）——
     * 判据⑤要求「改一个字节就判改过」，换行符也算一个字节。
     */
    fun bodySha(body: String): String = sha256(stripMachineHeader(body))

    /** 只剥首行机器头，其余一字不动（含行尾字节）。 */
    fun stripMachineHeader(body: String): String {
        val nl = body.indexOf('\n')
        if (nl < 0) return body
        val head = body.substring(0, nl).trim()
        return if (head.startsWith("<!--") && head.endsWith("-->")) body.substring(nl + 1) else body
    }

    /** 给人看的一句提示（测试与 RunLog 共用；UI 另有中英 string，见启动窗口接线）。 */
    fun hint(from: Int, to: Int): String = "内置有新版 v$from→v$to，看差异吗"
}
