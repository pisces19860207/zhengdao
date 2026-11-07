// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十批 · 许愿流水线 §一②：规格生成＋候选验收。
// 纯函数，无 IO。用户不会写验收 ⇒ 按产物类型给 2–3 条候选判据让他挑。
package com.example.zhengdao.baopu.wish

/** 规格生成器（纯函数；判据①：不写验收的许愿也能出候选判据）。 */
internal object WishSpecBuilder {

    /** 从一句话许愿生成规格（含 2–3 条候选验收），等用户确认。 */
    fun build(wishText: String, product: WishProduct): WishSpec {
        val candidates = candidatesFor(product)
        return WishSpec(
            goal = wishText.trim(),
            input = inputOf(product),
            output = outputOf(product),
            candidates = candidates,
            risk = riskOf(product),
        )
    }

    /** 用户挑选候选判据后确认规格（不许让用户自己写判据）。 */
    fun confirm(spec: WishSpec, chosenIds: List<String>): WishSpec {
        val valid = chosenIds.filter { id -> spec.candidates.any { it.id == id } }
        return spec.copy(chosen = valid)
    }

    /** 按产物类型给候选验收（每类 2–3 条，机械可判）。 */
    fun candidatesFor(product: WishProduct): List<CandidateCriterion> = when (product) {
        WishProduct.SKILL -> listOf(
            CandidateCriterion("S1", "跑一次成功", "隔离试跑全部步骤 ok=true"),
            CandidateCriterion("S2", "样例一致", "试跑输入输出与规格样例逐字一致"),
            CandidateCriterion("S3", "真文件不变", "试跑前后真文件 SHA-256 不变"),
        )
        WishProduct.CARD -> listOf(
            CandidateCriterion("C1", "卡能认脸", "CardFormat.recognize 返回已知格式"),
            CandidateCriterion("C2", "必填齐类型对", "CardValidate.planOf 返回 enabled=true"),
            CandidateCriterion("C3", "两次 build 一致", "同 spec 两次 build 产物字节相同"),
        )
        WishProduct.CONTENT -> listOf(
            CandidateCriterion("T1", "依据非空", "OutputCitationGate.check 返回 Pass"),
            CandidateCriterion("T2", "字数结构合规", "字数在区间内且结构段齐全"),
            CandidateCriterion("T3", "台账无重复", "台账查重命中数=0"),
        )
        WishProduct.NOVEL -> listOf(
            CandidateCriterion("N1", "三级结构齐", "大纲→章节→正文三级齐全"),
            CandidateCriterion("N2", "人物名一致", "正文人名均在设定表内，无新造"),
            CandidateCriterion("N3", "续写只追加", "旧章字节不变，新章只追加"),
        )
    }

    private fun inputOf(product: WishProduct): String = when (product) {
        WishProduct.SKILL -> "工作区文件（只读）＋用户给的样例输入"
        WishProduct.CARD -> "母档 JSON（format/name/keywords/persona）"
        WishProduct.CONTENT -> "人设/知识库/搜索来源（465 闭集）"
        WishProduct.NOVEL -> "题材/篇幅/视角/大纲粒度＋设定表"
    }

    private fun outputOf(product: WishProduct): String = when (product) {
        WishProduct.SKILL -> "技能草稿（status=draft，不进生产）"
        WishProduct.CARD -> "卡片草稿（不落成品目录）"
        WishProduct.CONTENT -> "内容草稿卡（不进台账）"
        WishProduct.NOVEL -> "草稿章（不进正文目录）"
    }

    private fun riskOf(product: WishProduct): String = when (product) {
        WishProduct.SKILL -> "草稿可能含危险操作（生成期拦）；试跑写操作只落副本"
        WishProduct.CARD -> "母档格式非法或关键词单字（CardBuild.parseSpec 拦）"
        WishProduct.CONTENT -> "依据为空或台账重复（465 闸＋查重拦）"
        WishProduct.NOVEL -> "新造人物名或覆盖旧章（自检拦＋470 回滚）"
    }
}