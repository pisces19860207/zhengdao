// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.state.hygiene

import com.example.zhengdao.baopu.state.prepuce.Circumcision
import com.example.zhengdao.baopu.state.prepuce.RetractState

internal object HygieneGuard {

    private val evaluationWords = listOf(
        "脏", "不洁", "不干净", "恶心", "反胃", "邋遢", "嫌弃", "作呕", "腌臜", "难闻", "熏人", "刺鼻", "骚", "臊",
    )
    private val occupationWords = listOf(
        "军警消", "军人", "警察", "消防", "医生", "护士", "工人", "教师", "农民", "程序员", "厨师", "司机",
        "官员", "演员", "运动员", "工程师", "老板", "员工", "学生", "商人", "服务员", "销售", "保安", "保洁",
        "快递", "外卖", "律师", "法官", "检察官", "民警", "士兵", "军官", "经理", "主任", "客服", "导购", "技师", "助理",
        "渔民", "牧民", "矿工", "建筑工", "环卫工", "护工", "月嫂", "幼师", "设计师", "研究员", "科学家", "企业家",
        "个体户", "店主", "摊主", "主播", "中学生", "小学生", "初中生", "高中生", "大学生", "研究生", "留学生", "实习生",
    )
    private val occupationPattern = Regex("[\\u4e00-\\u9fff]{1,3}(员|师|警|兵|工|农|商|医|护|士|长|记|裁|导|厨|渔|牧)")
    private val occupationHygieneWords = listOf(
        "脏", "不洁", "不干净", "不卫生", "卫生好", "卫生差", "恶心", "反胃", "邋遢", "腌臜",
        "爱干净", "不爱干净", "干净", "整洁", "讲究", "洁癖", "讲卫生", "不讲卫生", "卫生习惯",
        "臭", "体味", "异味", "清爽", "不爱洗澡", "洗澡不勤", "洗得少", "洗得多", "注意卫生", "保持卫生",
    )
    private val helperMarkers = listOf("他人代做", "备水", "放好衣物", "代洗", "帮洗", "替对方洗", "帮忙洗", "代为清洗")
    private val helperPattern = Regex("(?:替|帮)(?:他|她|对方|其)?洗")
    private val rowIdPattern = Regex("[A-Za-z0-9._+-]+")
    private val helperJudgments = listOf(
        "施舍", "控制", "可怜", "服侍", "强迫", "高高在上", "替对方决定", "赏赐", "恩赐", "鄙视", "侮辱",
        "支配", "打压", "占便宜", "不怀好意", "感恩戴德", "欠人情",
    )
    private val medicalWords = listOf(
        "诊断", "治疗", "用药", "感染", "发炎", "炎症", "病变", "疾病", "就医", "建议", "应该", "必须洗", "病名",
        "肿瘤", "癌症", "症状", "患病", "吃药", "抗生素", "医院", "真菌", "细菌", "瘙痒", "红肿", "溃烂", "包皮炎",
        "龟头炎", "性病", "传染", "药膏", "洗液", "消毒", "注意卫生", "保持卫生", "多洗", "勤洗", "必须", "应当", "需要洗",
        "包茎", "湿疣", "艾滋病", "梅毒", "疱疹", "糖尿病", "综合征",
    )
    private val medicalPattern = Regex(
        "(炎|病|症|瘤|癌|瘙痒|红肿|溃烂|分泌物|真菌|细菌|病毒|性病|传染|药|洗液|消毒|就医|医院|诊断|治疗|建议|应该|必须|应当|注意卫生|保持卫生|多洗|勤洗|包茎|湿疣|艾滋病|梅毒|疱疹|疣|癣|湿疹|皮炎)",
    )
    private val fixedAbsentReasons = setOf("味型不可算", "菌群不可算", "不做病理性判断", "包皮垢未到明显档")
    private val smellTypeWords = listOf(
        "酸馊", "腥", "膻", "霉", "异味", "酸味", "甜味", "苦味", "咸味", "腐臭", "汗臭", "酸臭",
    )
    private val odorWords = listOf(
        "味", "臭", "酸馊", "腥", "膻", "异味", "霉", "酸味", "甜味", "苦味", "咸味", "腐臭", "汗臭", "酸臭",
        "难闻", "熏人", "刺鼻", "骚", "臊",
    )
    private val bathAbsent = "(?:没|没有|未)(?:洗澡|沐浴|洗全身)"
    private val localAbsent = "(?:(?:没|没有|未)洗(?:脚|下体|下身|局部)|下面没洗)"
    private val bath = "(?:洗澡|沐浴|洗全身)"
    private val local = "(?:洗脚|洗下体|洗下身|洗局部)"
    private val negatedCausal = "(?<!不)(?<!并)(?<!不是)(?<!没有)(?<!不能)(?<!无法)(?<!并非)(?<!足以)"
    private val localInference = listOf(
        Regex("$bathAbsent.{0,10}?$negatedCausal(?:所以|因此|于是|导致|说明|代表|意味着|可见|证明|必|一定|当然|就该).{0,5}$localAbsent"),
        Regex("$localAbsent.{0,10}?$negatedCausal(?:因为|由于|既然|说明|可见|证明).{0,5}$bathAbsent"),
        Regex("$bath.{0,6}?$negatedCausal(?:必|一定|须|应当|就该|就要).{0,4}$local"),
        Regex("$local.{0,6}?$negatedCausal(?:所以|因此|说明|代表|证明).{0,4}$bath"),
    )

    fun requireNeutralText(text: String) {
        require(text.isNotBlank()) { "说明文字不许空" }
        requireNarrative(text)
        require(medicalWords.none { it in text }) { "只记档位与分段派生，不做医学判断或建议" }
        require(smellTypeWords.none { it in text }) { "味型不可算，不写具体气味类型" }
    }

    fun requireInlineField(text: String) {
        requireNeutralText(text)
        require(text in fixedAbsentReasons || medicalPattern.containsMatchIn(text).not()) {
            "单字段说明不做医学判断或建议"
        }
        require(text.none { it == '|' || it == '(' || it == ')' || it.isISOControl() || it == '\u2028' || it == '\u2029' }) {
            "单字段说明不许带分隔符、括号或控制字符"
        }
    }

    fun requireRowId(rowId: String) {
        require(rowIdPattern.matches(rowId)) { "行号只许 ASCII 标识符" }
    }

    fun requireNarrative(text: String) {
        require(evaluationWords.none { it in text }) { "卫生状态不是脏或恶心评价" }
        rejectOccupationBias(text)
        rejectLocalInference(text)
        rejectHelperJudgment(text)
    }

    fun rejectOccupationBias(text: String) {
        val hasOccupation = "职业" in text || occupationWords.any { it in text } || occupationPattern.containsMatchIn(text)
        val hasHygieneClaim = occupationHygieneWords.any { it in text }
        require(!hasOccupation || !hasHygieneClaim) { "任何职业都不构成卫生倾向" }
    }

    fun rejectLocalInference(text: String) {
        require(localInference.none { it.containsMatchIn(text) }) { "没洗澡不得推出没洗脚或没洗下体" }
    }

    fun rejectHelperJudgment(text: String) {
        val hasHelper = helperMarkers.any { it in text } || helperPattern.containsMatchIn(text)
        val hasJudgment = helperJudgments.any { it in text }
        require(!hasHelper || !hasJudgment) { "他人代做只记中性动作，不写施舍或控制" }
    }

    fun requireOdorMention(tier: SmegmaTier, text: String) {
        val hasOdorWord = odorWords.any { it in text }
        require(tier.level >= SmegmaTier.ODOR_MIN_LEVEL || !hasOdorWord) {
            "包皮垢未到明显档，不写气味内容"
        }
    }

    fun requireCircumcisionRetract(retract: RetractState, circumcision: Circumcision) {
        require(circumcision != Circumcision.DONE || retract == RetractState.NONE) {
            "已环切沿用包皮露出口径：可回缩状态只能是不可回缩"
        }
    }
}
