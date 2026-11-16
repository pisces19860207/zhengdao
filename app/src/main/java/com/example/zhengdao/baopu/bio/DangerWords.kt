// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百七十五批 · 杏仁核/丘脑共用的可配词表（派工单①："词表走 649 的 facts，可配"）。
//
// 649 的 facts 机械核读（2026-11-16，`_zd-private/packs/男性/index.json`）：
// 包 policy 的 kinds 里有 "fact"，但 counts.by_kind 实测 **fact=0 片**——
// 649 现在并没有可读的 facts 词表。所以词表落成**可配缝**，两头都通：
//   · 默认表内置（app 域危险词，与 SkillRefine 的危险指令模式同一语域）；
//   · [DangerWords.fromFacts] 是 649 facts 的接缝——facts 有货时把词行喂进来即生效。
// 双真相防线：改默认词表必须同批改 AmygdalaTest 的分档用例（词表动了分档就动）。
package com.example.zhengdao.baopu.bio

/**
 * 词表（危险词＋否定词＋新异标记）。**不可变**——读数确定性的一半在这里：
 * 同一个词表实例喂同一文本，分档必然一致（判据④的另一半在器官无状态）。
 * 匹配口径：逐字 [String.contains]，不切词、不折形——粗标就该便宜。
 */
data class DangerWords(
    val danger: Set<String>,
    val negation: Set<String>,
    val noveltyMarks: Set<String>,
) {
    /** 危险词命中数。去重按词计：同一个词出现十次仍算 1（分档表吃的是词数）。 */
    fun dangerHits(text: String): Int = danger.count { text.contains(it) }

    /** 否定词是否在场。布尔就够：分档表里否定词只占一档，不数次数。 */
    fun hasNegation(text: String): Boolean = negation.any { text.contains(it) }

    /** 新异标记命中数（疑问/请求类词）。 */
    fun noveltyHits(text: String): Int = noveltyMarks.count { text.contains(it) }

    companion object {
        /**
         * 默认词表（app 域）。危险词＝动手就伤数据/钱包/边界的词；
         * 否定词＝立边界的词（"不要/禁止"这类，出现即给威胁+1——边界表述本身是突显）；
         * 新异标记＝疑问/请求（新请求比日常闲聊新异）。
         */
        val DEFAULT: DangerWords = DangerWords(
            danger = setOf(
                "删除", "删掉", "清空", "格式化", "卸载", "抹掉", "重置", "回滚",
                "覆写", "覆盖", "支付", "转账", "扣款", "密码", "凭据", "密钥",
                "令牌", "外发", "上传", "共享", "越权", "刷机", "提权", "注入",
            ),
            negation = setOf(
                "不要", "别", "禁止", "不许", "不准", "勿", "取消", "撤销", "拒绝", "驳回",
            ),
            noveltyMarks = setOf("？", "?", "吗", "呢", "请问", "请", "帮忙", "帮我"),
        )

        /**
         * 649 facts 接缝：facts 的词行进来，去空去重成危险词；**行集为空 ⇒ 原样回默认表**
         * （不空转、不退化成空表——空表会让粗标永远读零，等于器官瞎了）。
         * 只换危险词，否定/新异两表仍取 base——649 facts 的口径是"危险词表"。
         */
        fun fromFacts(factLines: List<String>, base: DangerWords = DEFAULT): DangerWords {
            val words = factLines.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            return if (words.isEmpty()) base else base.copy(danger = words)
        }
    }
}
