// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百五十三批 · **任务态 vs 闲聊态**的边界探针（判据出处：238 派工单 §3.9.0，
// 2026-10-27 用户定案「闲扯淡这类不算」）。
//
// 法典原文（逐字判据，不许改口径）：
//   任务态 = 本轮至少命中一条：①有工具调用或执行意图（"帮我改/查/装"）
//            ②有**事实主张**（数字、结论、文件内容、谁对谁错）③在收尾一个已开工的活
//   闲聊态 = 纯情绪、玩笑、吐槽、爱称、感叹，**无事实主张也无执行意图**
//   混合   = 闲聊里夹一句事实主张 ⇒ 只对**那一句**进事实态
//
// 为什么这条边界必须先钉（派工单 253 L3 的硬要求）：三条禁令只管任务态；
// **态判错，禁令全废**——把闲聊误判成任务态，禁令就会"满屏误报"（派工单负例⑥）。
// 本探针是**词表级**的保守实现：任务态只在命中①②③之一时才成立，命中不了就归闲聊态
// （宁可漏判进不了禁令，不可把玩笑当办事——误伤的方向一律不判）。
package com.example.zhengdao.baopu.constitution

/** 一段话落在哪个态。 */
internal enum class TaskMode { TASK, CHAT }

internal object TaskModeBoundary {

    // ── ① 执行意图 / 工具调用形态（"帮我改/查/装"）───────────────────────────
    private val INTENT = listOf(
        "帮我", "替我", "给我查", "给我改", "给我删", "改一下", "改掉", "换掉", "查一下", "查查",
        "搜一下", "搜个", "写一个", "写一份", "写个", "删掉", "删除", "卸载", "安装", "装一下",
        "跑一下", "跑起来", "执行", "生成", "导出", "导入", "上传", "下载", "打开", "关掉",
        "修复", "修一下", "部署", "提交", "保存", "重命名", "替换", "压缩", "解压",
        "照这个改", "按这个改", "按这个做", "照着做", "试试看能不能", "重新",
    )

    // ── ② 事实主张：数字 / 路径 / 引用 / 对错结论 / 执行结果 ──────────────────
    // 「第N步/行/条/章」用正则，不用裸「第」——「你是第一个让我这么生气的小笨蛋」不是事实主张。
    private val CLAIM = listOf(
        "就是错的", "是对的", "没错", "不对", "错了", "正确的", "应该是", "其实是", "我查过",
        "数据显示", "事实证明", "结论是", "结果是", "失败", "成功", "报错", "超时", "找不到",
        "打不开", "崩溃", "日志", "合计", "总共", "一共", "版本", "文件里", "表里", "官网",
        "文档说",
    )
    private val CLAIM_RE = listOf(
        Regex("""第\s*\d+\s*(?:步|行|条|章|页|个|次|版)"""),
        Regex("""\d"""), // 出现数字即算事实主张（闲聊里报数字的罕见；报了就该按事实态对待）
        Regex("""[\w\-]+\.(?:kt|java|md|txt|json|xml|csv|png|jpg)"""),
    )

    // ── ③ 收尾已开工的活 ────────────────────────────────────────────────────
    private val WRAPUP = listOf("收尾", "收工", "搞定了", "完成了", "完工", "交差", "交付")

    // ── 闲聊的形态词（只用于测试钉边界；探针判闲聊靠的是"没命中任务态"，不靠命中闲聊词）──
    private val PURE_EMOTION = listOf(
        "哈哈", "笑死", "开心", "难过", "气死", "无语", "绝了", "太逗了", "好烦", "麻了",
        "爱你", "想你", "晚安", "早安", "晚上好", "在吗", "无聊",
    )

    /** 一段话落在哪个态：命中①②③任一 ⇒ 任务态；否则闲聊态（238 §3.9.0 的保守方向）。 */
    fun classify(text: String): TaskMode {
        val s = text.trim()
        if (s.isEmpty()) return TaskMode.CHAT
        if (INTENT.any { s.contains(it) }) return TaskMode.TASK
        if (WRAPUP.any { s.contains(it) }) return TaskMode.TASK
        // 自责客套（「我错了」「我的错」）不是对世界的事实主张——闲聊里的道歉不许因此进任务态。
        val claimScope = s.replace("我错了", "　").replace("我的错", "　")
        if (CLAIM.any { claimScope.contains(it) }) return TaskMode.TASK
        if (CLAIM_RE.any { it.containsMatchIn(claimScope) }) return TaskMode.TASK
        return TaskMode.CHAT
    }

    /** 按中文句界切开（句末标点归前句；空白丢弃）。混合态的"那一句"就是这里的一句。 */
    fun splitSentences(text: String): List<String> =
        text.split('。', '！', '？', '?', '!', '\n', '；', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** 逐句判态（混合：只对带事实主张的那句进事实态）。 */
    fun classifySentences(text: String): List<Pair<String, TaskMode>> =
        splitSentences(text).map { it to classify(it) }

    /** 这段话是不是「闲聊里夹了一句事实主张」（至少一句 TASK，且整体有闲聊语气词）。 */
    fun isMixed(text: String): Boolean =
        classifySentences(text).any { it.second == TaskMode.TASK } &&
            PURE_EMOTION.any { text.contains(it) }

    /** 测试钉边界用的闲聊词表（只暴露给断言，不参与判定）。 */
    fun emotionLexicon(): List<String> = PURE_EMOTION
}
