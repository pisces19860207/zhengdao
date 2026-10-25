// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零一批 · 测试夹具：一张「风月制卡」形状的表（5 类控件全占）
//   char_name  单行      标签「角色名」
//   persona    多行富文本 标签「人设」（三行）
//   style      下拉      标签「风格」（古风 / 江湖）
//   gender     单选      标签「性别」（男 / 女）
//   worldbook  固定 2 段 每段 title+content（name 形如 worldbook[0][title]）
//
// 段数写死 2 段就是派工单那句「只支持固定条数」的具象：夹具里没有「加一条」的能力。
package com.example.zhengdao.baopu

internal object FormFixtures {

    const val START_URL = "https://demo.example/card/new"

    /** 页面元素（可变：变异测试要原地改 store/ignoreFill）。 */
    fun elements(): MutableList<FakeFormEl> = mutableListOf(
        FakeFormEl(
            tag = "INPUT", nameAttr = "char_name", label = "角色名", type = "text",
            id = "f-char-name", placeholder = "起个名字",
        ),
        FakeFormEl(
            tag = "TEXTAREA", nameAttr = "persona", label = "人设", id = "f-persona",
        ),
        FakeFormEl(
            tag = "SELECT", nameAttr = "style", label = "风格", id = "f-style",
            options = listOf(TeachOption("1", "古风"), TeachOption("2", "江湖")),
        ),
        FakeFormEl(
            tag = "INPUT", nameAttr = "gender", label = "性别", type = "radio", id = "g-m",
            options = listOf(TeachOption("m", "男"), TeachOption("f", "女")),
            value = "m",
        ),
        FakeFormEl(
            tag = "INPUT", nameAttr = "gender", label = "性别", type = "radio", id = "g-f",
            options = listOf(TeachOption("m", "男"), TeachOption("f", "女")),
            value = "f",
        ),
        FakeFormEl(tag = "INPUT", nameAttr = "worldbook[0][title]", label = "第 1 条标题", type = "text"),
        FakeFormEl(tag = "TEXTAREA", nameAttr = "worldbook[0][content]", label = "第 1 条内容"),
        FakeFormEl(tag = "INPUT", nameAttr = "worldbook[1][title]", label = "第 2 条标题", type = "text"),
        FakeFormEl(tag = "TEXTAREA", nameAttr = "worldbook[1][content]", label = "第 2 条内容"),
    )

    /** 页脚那两个诱饵按钮：他们必须永远不出现在字段表里（负例 1）。 */
    fun decoys(): List<FakeFormEl> = listOf(
        FakeFormEl(tag = "BUTTON", label = "创建", id = "btn-create", testid = "create-card"),
        FakeFormEl(tag = "INPUT", type = "submit", label = "发布", id = "btn-publish", value = "发布"),
        FakeFormEl(tag = "BUTTON", label = "保存草稿", id = "btn-save"),
    )

    /** 把一组假元素扫成候选（判据测试要喂 buildFields 的是候选，不是元素）。 */
    fun candidates(els: List<FakeFormEl>): List<FormCandidate> =
        FakeFormDom(els.toMutableList()).scan().orEmpty()

    fun dom(withDecoys: Boolean = true): FakeFormDom {
        val els = elements()
        if (withDecoys) els += decoys()
        return FakeFormDom(els)
    }

    /** 扫一遍 → 模板（判据走生产端同一条路：buildFields/templateFrom）。 */
    fun template(dom: FakeFormDom = dom(), name: String = "风月制卡"): FormTemplate {
        val scan = TeachFormRules.buildFields(dom.scan().orEmpty())
        return TeachFormRules.templateFrom(
            scan = scan,
            name = name,
            startUrl = START_URL,
            allowedDomains = listOf("demo.example"),
            nowMs = 1_700_000_000_000L,
        )
    }

    /** 一张完整的卡（8 个字段全给）。 */
    fun card(name: String = "小明"): FormCard = FormCard(
        name = name,
        values = mapOf(
            "char_name" to "小明",
            "persona" to "十六岁，住在河边。\n怕水，但会撑船。\n嘴上不认。",
            "style" to "古风",
            "gender" to "女",
            "worldbook1_title" to "河湾",
            "worldbook1_content" to "一条大河\n两座石桥",
            "worldbook2_title" to "老周",
            "worldbook2_content" to "摆渡人\n欠他三块钱",
        ),
    )

    /** 只填了第一段的坏卡（负例 4 的输入）。 */
    fun cardFirstSegmentOnly(name: String = "只填一段"): FormCard = FormCard(
        name = name,
        values = mapOf(
            "char_name" to "小明",
            "persona" to "一行",
            "style" to "古风",
            "gender" to "男",
            "worldbook1_title" to "河湾",
            "worldbook1_content" to "一条大河",
        ),
    )
}
