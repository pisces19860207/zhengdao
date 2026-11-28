// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九百四十三批 · 黑名单 manifest 的测试侧读端。
// 单测没有 Android Context，读资产走路径候选（工作目录可能是 app/ 也可能是仓根），
// 解析与校验全部复用生产侧 BlacklistManifest（唯一解析权），这里只做三件事：
//   ① 找到文件（支持 BLACKLIST_MANIFEST 环境变量指向替身文件，做删行／改行变异）；
//   ② 校验并 fail closed——拒收即抛，绝不拿半份表继续跑；
//   ③ 把 935 行转成 Rumor 喂给 ShapeRumorCatalog（装配侧的职责，生产里归装配点）。
//
// 本文件不许出现条数字面量（总数锁只能从 manifest 读）——BlacklistManifestGateTest 机检。
package com.example.zhengdao

import com.example.zhengdao.baopu.policy.BlacklistManifest
import com.example.zhengdao.baopu.state.bodyparts.Rumor
import com.example.zhengdao.baopu.state.bodyparts.RumorClass
import com.example.zhengdao.baopu.state.bodyparts.RumorTarget
import com.example.zhengdao.baopu.state.bodyparts.ShapeRumorCatalog
import java.io.File

internal object BlacklistManifestFeed {

    /** 环境变量名：指向替身 manifest 做变异（对应老 DEFILTER_DOC 的替身文档机制）。 */
    const val OVERRIDE_ENV = "BLACKLIST_MANIFEST"

    /** 找 manifest：环境变量优先，否则按工作目录候选。找不到直接抛，不猜。 */
    fun file(): File {
        val override = System.getenv(OVERRIDE_ENV)
        val candidates = if (override.isNullOrBlank()) {
            listOf(
                File("src/main/assets/policy/blacklist-manifest.json"), // 单测工作目录 = app/
                File("app/src/main/assets/policy/blacklist-manifest.json"), // 工作目录 = 仓根
            )
        } else {
            listOf(File(override))
        }
        return candidates.firstOrNull { it.isFile }
            ?: error("blacklist-manifest 没找到（试过：${candidates.joinToString { it.absolutePath }}）")
    }

    /** 原文（可复算测试对替身文本做内存扰动）。 */
    fun rawText(): String = file().readText()

    /**
     * 校验过的快照：解析层拒收（结构坏／计数不自洽／expect 非 RED）⇒ 这里直接抛。
     * 拒收 = 所有依赖 manifest 的测试一起红——fail closed，不是跳过。
     */
    fun snapshot(): BlacklistManifest.Snapshot {
        val snap = BlacklistManifest.validate(rawText())
        check(snap.ok) { "blacklist-manifest 拒收：${snap.refusal}" }
        return snap
    }

    /** 某类总数（totals 小节）。 */
    fun total(kind: String): Int = snapshot().total(kind)

    /** 某类小计（subtotals 小节）。 */
    fun subtotals(kind: String): Map<String, Int> = snapshot().subtotal(kind)

    /** 934 行（DeFilterGateTest 的唯一表）。 */
    fun deFilterRows(): List<BlacklistManifest.DeFilterRow> = snapshot().deFilter

    /**
     * 把 manifest 的 935 行转成 Rumor 喂给读端（幂等）。
     * 类 tag 与目标名在这里对到闭集枚举：manifest 写了枚举外的值 ⇒ valueOf 抛 ⇒ 红。
     */
    fun installShapeMyth() {
        val snap = snapshot()
        val rows = snap.shapeMyth.map { row ->
            Rumor(
                id = row.id,
                klass = RumorClass.entries.first { it.tag == row.klass },
                template = row.template,
                targets = row.targets.map { RumorTarget.valueOf(it) }.toSet(),
            )
        }
        ShapeRumorCatalog.install(rows)
    }
}
