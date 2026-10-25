// 独立开发声明：本文件为本项目原创。
//
// 第一百九十批 · 题材与写法卡的**纯判据**（建议卡／推荐卡／确认卡共用）。
// 图纸：`_zd-private/docs/派工单-2026-10-24-第一百九十批-题材与写法卡.md`。
//
// 这里零 Android / 零 IO / 零时钟：候选池、查重、换批、写法档位、状态机全部是
// 纯函数与纯数据，JVM 直测；卡片与控制器只做装配（读台账在控制器、落盘在
// `BaopuLongformStore`——179 已合入的出口，本批不另造第二套路径）。
//
// 候选数：派工单原文 7 条；用户 2026-10-25 开工中拍板「4 条也行」⇒ 常量
// [TopicSuggest.CANDIDATES]＝4（随时可调回，卡面与测试都跟着常量走）。
package com.example.zhengdao.baopu

/**
 * 一个题材候选的种子（池子里的一条）。
 *
 * @param id 稳定标识（换批去重按它对账）
 * @param title 一句话题目
 * @param scene 核心场景关键词（2–6 字）——「撞核心场景」判据的锚点
 * @param track 赛道（卡面备注用；落盘仍统一走 179 的默认赛道，不在这里分目录）
 * @param hook 「为什么现在写」的角度素材（与已发内容无关的那半句理由）
 */
internal data class TopicSeed(
    val id: String,
    val title: String,
    val scene: String,
    val track: String,
    val hook: String,
)

/** 一条已发内容的指纹（去重对账用；来源写清，报告要「撞了哪条」）。 */
internal data class TopicPublishedRef(
    /** 出处的人话描述（如 `产物/20261023_微头条_1_xxx.md` 或 `台账 第 12 行`）。 */
    val source: String,
    /** 文件名/台账行里的标题 slug（去空格连字符后的紧凑形态）。 */
    val slug: String,
    /** 原始标题（有就给，没有＝slug 本身）。 */
    val title: String,
)

/** 一次撞车：哪条种子、撞了哪条规则、撞上哪条已发内容（交付报告的证据本体）。 */
internal data class TopicClash(
    val seedId: String,
    /** `scene`＝撞核心场景/题材；`skeleton`＝标题结构雷同。 */
    val rule: String,
    val refSource: String,
)

/** 出卡的一条候选：种子 ＋ 拼好的「为什么现在写」。 */
internal data class TopicCandidate(val seed: TopicSeed, val reason: String)

/** 一批候选与它的查重账（批内给卡面，clash 给交付报告）。 */
internal data class TopicSuggestBatch(
    val candidates: List<TopicCandidate>,
    val clashes: List<TopicClash>,
    /** 池子绕了一整圈仍凑不满（全撞/全被换过）⇒ true，卡面要说明只剩这些。 */
    val exhausted: Boolean,
)

/** 一套写法档位（推荐卡的选项；默认档＝上次成功落盘那套，记在本地偏好）。 */
internal data class TopicStyle(
    val id: String,
    val name: String,
    /** 口吻：第一人称/旁观/对话体。 */
    val voice: String,
    val lenMin: Int,
    val lenMax: Int,
    /** 结构：故事钩子/清单体/反转结尾。 */
    val structure: String,
)

/** 一篇的落盘结果（顺序产出，失败可知哪篇没落）。 */
internal data class TopicArticleResult(
    val title: String,
    val ok: Boolean,
    /** 给人看的一句话：成功＝路径；失败＝原因。 */
    val message: String,
    val path: String? = null,
)

/** 三卡流的状态（纯数据；迁移只走 [TopicFlowRules.reduce]，控制器不自改字段）。 */
internal data class TopicFlowState(
    val phase: TopicFlowPhase,
    /** 第几批候选（首批 1；换一批 ＋1）。 */
    val batchNo: Int,
    /** 本流程里已展示过的种子 id（「换一批与上批零重复」的对账集合，只增不减）。 */
    val shownSeedIds: Set<String>,
    val candidates: List<TopicCandidate>,
    /** 最近一批查重撞掉的种子（证据随状态走，报告可取）。 */
    val clashes: List<TopicClash>,
    /** 本批是否没凑满（池子绕完仍不够 ⇒ 卡面说明「只剩这些」）。 */
    val batchExhausted: Boolean,
    /** 选中的种子 id（默认全选＝候选全体）。 */
    val selected: Set<String>,
    val styleId: String,
    /** 换批轮转的池内起点（由调用方按已发条数给初值，批与批之间顺延）。 */
    val poolOffset: Int,
    val writing: TopicWritingState? = null,
)

internal enum class TopicFlowPhase { SUGGEST, STYLE, CONFIRM, WRITING, DONE }

/** 批量产出进行中的账（队列、进度、逐篇结果）。 */
internal data class TopicWritingState(
    val queue: List<TopicCandidate>,
    /** 已写完的篇数（0 基；＝ queue.size 表示全部发完）。 */
    val done: Int,
    val results: List<TopicArticleResult>,
)

/** 纯判据本体。 */
internal object TopicSuggest {

    /** 一批出几条候选（用户 2026-10-25：「4 条也行」，派工单原值 7）。 */
    const val CANDIDATES = 4

    /**
     * 触发词（派工单 §0.1）：「微头条发完了」「下一篇写什么」「给我几个题材」。
     * 刻意窄：普通聊天里单出现「题材」两个字不许误触发。
     */
    private val TOPIC_REQUEST = Regex(
        "微头条.{0,8}发完|发完.{0,6}下一篇|下一篇.{0,10}(写什么|写啥|题材|哪个)|几个题材|一批题材|换个题材|再来一批",
    )

    fun isTopicRequest(text: String): Boolean =
        TOPIC_REQUEST.containsMatchIn(text.replace(Regex("\\s+"), ""))

    // ── 候选池 ────────────────────────────────────────────────────────────
    //
    // 32 条种子、六条赛道。够「换一批」连换几轮：查重 + 本流程已展示双重排除后
    // 仍能凑满一批。池子顺序固定、不用随机数——同一状态永远同一批，
    // 「换一批与上批零重复」的判据才立得住（零重复由 shownSeedIds 排除保证，
    // 轮转 offset 只负责让不同天出的批不一样）。

    val POOL: List<TopicSeed> = listOf(
        TopicSeed("t01", "中年失业第三个月，我把简历删了", "失业", "职场", "裁员潮里最真实的不是焦虑，是松了口气"),
        TopicSeed("t02", "同学聚会散场后，我把群设了免打扰", "同学聚会", "人际", "成年人的断舍离从社交软件开始"),
        TopicSeed("t03", "给爸妈换了智能马桶盖，他们嘴上嫌弃身体诚实", "爸妈", "家庭", "孝心的代差冲突永远有共鸣"),
        TopicSeed("t04", "凌晨三点的急诊室，我看见了成年人的崩溃", "急诊", "健康", "健康话题自带切身感，夜间急诊是浓缩剧场"),
        TopicSeed("t05", "我把孩子的补习班退了，换成周末爬山", "补习班", "亲子", "教育焦虑的反向操作最容易引战也最容易共鸣"),
        TopicSeed("t06", "楼下便利店老板娘记住了我的口味", "便利店", "生活观察", "城市孤独症的解药是陌生人的小善意"),
        TopicSeed("t07", "工资到账那天我做的第一件事，是转给房东", "房租", "职场", "大城市打工人的月度仪式感"),
        TopicSeed("t08", "体检报告出来后，我把奶茶戒了半个月", "体检", "健康", "年轻人怕死经济学，从一份报告说起"),
        TopicSeed("t09", "结婚十年，我们开始分房睡", "分房睡", "婚姻", "婚姻里最诚实的话题反而没人明说"),
        TopicSeed("t10", "小区门口的修鞋摊，涨了两块钱", "修鞋摊", "生活观察", "物价话题落在最小的事上最有实感"),
        TopicSeed("t11", "被裁的同事朋友圈开始卖保险了", "卖保险", "职场", "中年转行的真实样本比任何分析都有力"),
        TopicSeed("t12", "我妈学会了网购，家里多了三台破壁机", "老年网购", "家庭", "银发触网的家庭喜剧天天在上演"),
        TopicSeed("t13", "地铁上有人外放短视频，全车厢的表情", "地铁外放", "公共礼仪", "公共空间的默契是最容易站队的话题"),
        TopicSeed("t14", "孩子问我我们家是不是很穷", "穷", "亲子", "一句童言戳破大人的体面"),
        TopicSeed("t15", "把车卖了改坐地铁，一个月省出两顿火锅", "卖车", "消费", "消费降级的真实账本"),
        TopicSeed("t16", "住院陪护七天，我看懂了护工这个职业", "住院陪护", "健康", "老龄化社会的现场感话题"),
        TopicSeed("t17", "相亲市场上，35 岁是道坎", "相亲", "婚恋", "婚恋年龄线永远有讨论度"),
        TopicSeed("t18", "公司取消大小周，同事反而更累了", "大小周", "职场", "职场政策的悖论，亲历者视角最可信"),
        TopicSeed("t19", "菜市场教会我的砍价哲学", "菜市场", "生活观察", "烟火气场景自带流量"),
        TopicSeed("t20", "我爸的老年机换成智能手机后，家族群沦陷了", "家族群", "家庭", "代际数字生活的荒诞日常"),
        TopicSeed("t21", "凌晨四点的早市，摆摊的年轻人", "早市摆摊", "职场", "副业刚需的具象化场景"),
        TopicSeed("t22", "给孩子开家长会，老师一句话让我沉默了", "家长会", "亲子", "教育现场的一句话顶十篇道理"),
        TopicSeed("t23", "我算了一笔账：养车一年到底花多少", "养车账", "消费", "算账体永远有人对号入座"),
        TopicSeed("t24", "离婚冷静期最后一天，我们又去吃了顿火锅", "离婚冷静期", "婚姻", "情感话题的反转叙事钩子"),
        TopicSeed("t25", "健身房年卡只用过三次的我，又续费了", "健身房", "消费", "自律经济的自嘲共鸣"),
        TopicSeed("t26", "加班到十点，出租车司机给我上了一课", "出租车", "职场", "深夜通勤路上的偶遇是天然故事"),
        TopicSeed("t27", "爸妈开始热衷买保健品，我斗智斗勇一个月", "保健品", "家庭", "老年消费的家庭攻防战"),
        TopicSeed("t28", "孩子开学第一天，我在校门口站了很久", "开学", "亲子", "父母视角的时令情感"),
        TopicSeed("t29", "我把朋友圈关了半年，生活有什么变化", "朋友圈", "人际", "社交戒断实验的切身体验"),
        TopicSeed("t30", "回县城参加婚礼，同学都开上了奥迪", "县城婚礼", "人际", "县城叙事与大城市对照的反差感"),
        TopicSeed("t31", "食堂阿姨手抖的那一下，决定了我的下午", "食堂", "职场", "打工人的日常微小确幸"),
        TopicSeed("t32", "陪我妈看她跳广场舞，才知道她有多认真", "广场舞", "家庭", "老年生活的认真劲儿最动人"),
    )

    /** 写法档位（推荐卡的 2–3 套；默认＝上次成功那套，由控制器从本地偏好取）。 */
    val STYLES: List<TopicStyle> = listOf(
        TopicStyle("s1", "老友唠嗑", "第一人称", 500, 700, "故事钩子开篇"),
        TopicStyle("s2", "旁观点评", "旁观视角", 250, 350, "清单体"),
        TopicStyle("s3", "对话反转", "对话体", 900, 1200, "反转结尾"),
    )

    fun styleOf(id: String): TopicStyle = STYLES.firstOrNull { it.id == id } ?: STYLES.first()

    /** 默认写法：记得住上次成功那套就用它，否则第一套。 */
    fun defaultStyle(lastSuccessfulId: String?): TopicStyle =
        styleOf(lastSuccessfulId ?: STYLES.first().id)

    // ── 已发内容指纹（输入＝台账文本 + 产物目录文件名清单，读盘在控制器） ──────

    /**
     * 产物文件名 → 标题 slug。两种命名都认：
     * 179 v1 `微头题-YYYYMMDD-HHmm-slug.ext` 与拍板版 `YYYYMMDD_赛道_序号_slug.ext`。
     */
    fun slugFromFileName(name: String): String? {
        val stem = name.substringBeforeLast('.')
        if (stem.isEmpty()) return null
        val legacy = Regex("^微头条-\\d{8}-\\d{4}-(.+)$").find(stem)?.groupValues?.get(1)
        if (legacy != null) return legacy
        val parts = stem.split('_')
        // 拍板版四段：YYYYMMDD_赛道_序号_slug（slug 段里可能还有 - 连接的多词）
        if (parts.size >= 4 && parts[0].length == 8 && parts[0].all { it.isDigit() }) {
            return parts.drop(3).joinToString("-")
        }
        return null
    }

    /** 台账行 `- ☐/✔ 文件名（N 字）` → 文件名 → slug（与 [slugFromFileName] 同一解析）。 */
    fun publishedFromLedger(ledgerText: String?): List<TopicPublishedRef> {
        if (ledgerText.isNullOrBlank()) return emptyList()
        return ledgerText.lineSequence().mapIndexedNotNull { i, raw ->
            val m = Regex("^-\\s*[☐✔]\\s+(.+?)（\\d+\\s*字）\\s*$").find(raw.trim())
                ?: return@mapIndexedNotNull null
            val name = m.groupValues[1].trim()
            val slug = slugFromFileName(name) ?: name
            TopicPublishedRef(source = "台账 第 ${i + 1} 行（$name）", slug = slug, title = slug)
        }.toList()
    }

    /** 产物目录清单 → 已发指纹（source 直接写文件名，报告好对账）。 */
    fun publishedFromFiles(fileNames: List<String>): List<TopicPublishedRef> =
        fileNames.mapNotNull { name ->
            slugFromFileName(name)?.let { TopicPublishedRef(source = "产物/$name", slug = it, title = it) }
        }

    // ── 查重（派工单 §0.1：撞题材／标题结构雷同／核心场景重复 ⇒ 不许出） ──────

    /**
     * 标题骨架：数字折成 `#`、汉字/字母折成 `字`，其余（标点、空格）全丢。
     * 「45岁被裁后我明白了三件事」与「30岁离婚后我悟透了两个道理」骨架同为
     * `#字字字字字字字字字` ⇒ 结构雷同；只比形状不比词，判据可解释、可测。
     */
    fun skeleton(title: String): String = buildString {
        title.forEach { c ->
            when {
                c.isDigit() -> append('#')
                c.isLetter() -> append('字')
                // 空白与标点不算结构（slug 生成时本就剔了非法字符）
            }
        }
    }

    /** 一条种子撞不撞已发内容；撞 ⇒ 带证据的 [TopicClash]（先撞先记）。 */
    fun clashOf(seed: TopicSeed, published: List<TopicPublishedRef>): TopicClash? {
        published.forEach { ref ->
            // ① 撞核心场景/题材：种子的场景词出现在已发 slug 里，
            //    或已发 slug（≥4 字，够长才算数）整段出现在种子题目里。
            if (seed.scene.isNotEmpty() && ref.slug.contains(seed.scene)) {
                return TopicClash(seed.id, "scene", ref.source)
            }
            if (ref.slug.length >= 4 && seed.title.contains(ref.slug)) {
                return TopicClash(seed.id, "topic", ref.source)
            }
            // ② 标题结构雷同：骨架逐字符相同（slug 已是压缩形态，两侧同规则折叠后可直比）。
            if (ref.slug.length >= 6 && skeleton(ref.title) == skeleton(seed.title)) {
                return TopicClash(seed.id, "skeleton", ref.source)
            }
        }
        return null
    }

    /**
     * 出一批候选（确定性轮转）：从 [offset] 起绕池子走一圈，跳过 [excludeIds]
     * （本流程展示过的——「换一批零重复」的本体）与撞车种子；撞了的记进
     * clashes（证据），凑满 [count] 条或绕完一圈为止。
     */
    fun nextBatch(
        published: List<TopicPublishedRef>,
        excludeIds: Set<String>,
        offset: Int,
        count: Int = CANDIDATES,
    ): TopicSuggestBatch {
        val picked = mutableListOf<TopicCandidate>()
        val clashes = mutableListOf<TopicClash>()
        var scanned = 0
        var i = ((offset % POOL.size) + POOL.size) % POOL.size
        while (scanned < POOL.size && picked.size < count) {
            val seed = POOL[i]
            if (seed.id !in excludeIds) {
                val clash = clashOf(seed, published)
                if (clash != null) {
                    clashes += clash
                } else {
                    picked += TopicCandidate(seed, reasonOf(seed, published))
                }
            }
            scanned++
            i = (i + 1) % POOL.size
        }
        return TopicSuggestBatch(picked, clashes, exhausted = picked.size < count)
    }

    /**
     * 「为什么现在写」＝角度素材 ＋ 对账句。对账句必须是真的：查重刚做过才敢说
     * 「未撞」；台账有货时点名最近一篇，用户看得出这批接在哪儿。
     */
    internal fun reasonOf(seed: TopicSeed, published: List<TopicPublishedRef>): String {
        val base = seed.hook
        val recent = published.lastOrNull()
        return if (published.isEmpty()) {
            "$base（台账还是空的，这是头一批）"
        } else if (recent != null) {
            "$base（已对台账 ${published.size} 条查重未撞；接得上《${recent.slug}》之后的更新节奏）"
        } else {
            "$base（已对台账 ${published.size} 条查重未撞）"
        }
    }

    // ── 写作指令与汇总（确认卡与批量产出共用同一拼法） ──────────────────────

    /**
     * 发给引擎的一轮指令：题目、写法、长度、结构一次说清，并要求**只给正文**。
     * 题目互不相同 ⇒ 指令文本互不相同（收尾对账按指令原文锚定哪篇写完了）。
     */
    fun writeInstruction(title: String, style: TopicStyle): String =
        "写一篇微头条，题目：《$title》。写法：口吻=${style.voice}；长度=${style.lenMin}–${style.lenMax} 字；" +
            "结构=${style.structure}。只输出 markdown 正文（第一行是一级标题），不要解释、不要提问。"

    /** 确认卡的逐篇行（题目 + 写法摘要）。 */
    fun confirmLine(candidate: TopicCandidate, style: TopicStyle): String =
        "《${candidate.seed.title}》——${style.voice}·${style.lenMin}–${style.lenMax} 字·${style.structure}"

    /** 预计字数（整批口径：篇数 × 区间）。 */
    fun estimate(n: Int, style: TopicStyle): String = "$n 篇 × ${style.lenMin}–${style.lenMax} 字"
}

/**
 * 三卡流的状态机（**唯一迁移入口**；控制器把 UI 回调与引擎事件折成 [FlowEvent] 喂进来）。
 *
 * 两条负例靶心（派工单 §2，测试钉着）：
 * - **确认卡不许被文字绕过**：[FlowEvent.UserText] 无论说什么，都不可能把状态推进到
 *   WRITING/DONE——开写闸只有 CONFIRM 相位下的 [FlowEvent.ConfirmWrite]；
 * - **打字不等于选择**：UserText 不改 selected/styleId（触发词重开建议卡由控制器
 *   走 [start]/[TopicFlowRules.reduce] 的 [FlowEvent.BatchSwapped]，不经 UserText）。
 */
internal object TopicFlowRules {

    /** 控制器能喂进来的全部事件（UI 按钮、用户文字、引擎收尾、换批数据）。 */
    sealed interface FlowEvent {
        /** 用户发了一条普通文字（触发与否都进来；本事件对状态**零副作用**）。 */
        data class UserText(val text: String) : FlowEvent

        data class Select(val seedId: String) : FlowEvent

        /** 换一批/触发词重开：控制器现算好新批（读台账在它那边）从这里塞进来。 */
        data class BatchSwapped(val batch: TopicSuggestBatch, val poolOffset: Int) : FlowEvent

        /** 建议卡「就写这些」（进推荐卡）。 */
        data object ToStyle : FlowEvent

        data class PickStyle(val styleId: String) : FlowEvent

        /** 「按推荐的来」（直接进确认卡）。 */
        data object UseRecommended : FlowEvent

        /** 「改一档」（就地切下一套，随后进确认卡）。 */
        data object ShiftStyle : FlowEvent

        /** 确认卡「改题材」（回建议卡，选中态保留）。 */
        data object BackToSuggest : FlowEvent

        /** 确认卡「改写法」（回推荐卡）。 */
        data object BackToStyle : FlowEvent

        /** 「就按这些写」——唯一能开写闸的事件，且只在 CONFIRM 相位有效。 */
        data object ConfirmWrite : FlowEvent

        /** 一篇写完（成败都算一篇；[TopicWritingState.done] 前移由这里做）。 */
        data class ArticleDone(val result: TopicArticleResult) : FlowEvent

        /** 批量产出中用户点「停下这批」（已出的结果保留）。 */
        data object StopWrite : FlowEvent

        data object Dismiss : FlowEvent
    }

    /** 首批（触发词 ⇒ 控制器读台账算批，从这里进状态机）。 */
    fun start(batch: TopicSuggestBatch, styleId: String, poolOffset: Int): TopicFlowState =
        TopicFlowState(
            phase = TopicFlowPhase.SUGGEST,
            batchNo = 1,
            shownSeedIds = batch.candidates.map { it.seed.id }.toSet(),
            candidates = batch.candidates,
            clashes = batch.clashes,
            batchExhausted = batch.exhausted,
            selected = batch.candidates.map { it.seed.id }.toSet(),
            styleId = styleId,
            poolOffset = poolOffset,
        )

    /**
     * 唯一迁移函数。**被忽略的事件返回原状态**（不回 null）——回 null 会被控制器
     * 当成「销卡」，点错相位上的按钮不该把整条流吹掉；null 只留给 [FlowEvent.Dismiss]。
     */
    fun reduce(state: TopicFlowState?, ev: FlowEvent): TopicFlowState? = when (ev) {
        // 文字对三卡零副作用：卡不挡输入（164），打字只算聊天/备注——不选、不批、不开写。
        is FlowEvent.UserText -> state

        is FlowEvent.Select -> if (state != null && state.phase == TopicFlowPhase.SUGGEST) {
            state.copy(selected = if (ev.seedId in state.selected) state.selected - ev.seedId else state.selected + ev.seedId)
        } else {
            state
        }

        // 换一批＝同一流程的第 N 批：shownSeedIds 只增不减（与**所有**上批零重复，
        // 比派工单「与上批零重复」更严），选中态重置为全选。
        is FlowEvent.BatchSwapped -> if (state != null) {
            state.copy(
                batchNo = state.batchNo + 1,
                shownSeedIds = state.shownSeedIds + ev.batch.candidates.map { it.seed.id }.toSet(),
                candidates = ev.batch.candidates,
                clashes = ev.batch.clashes,
                batchExhausted = ev.batch.exhausted,
                selected = ev.batch.candidates.map { it.seed.id }.toSet(),
                poolOffset = ev.poolOffset,
                phase = TopicFlowPhase.SUGGEST,
            )
        } else {
            start(ev.batch, TopicSuggest.STYLES.first().id, ev.poolOffset)
        }

        FlowEvent.ToStyle -> if (state != null && state.phase == TopicFlowPhase.SUGGEST && state.selected.isNotEmpty()) {
            state.copy(phase = TopicFlowPhase.STYLE)
        } else {
            state
        }

        is FlowEvent.PickStyle -> if (state != null && state.phase == TopicFlowPhase.STYLE) {
            state.copy(styleId = ev.styleId)
        } else {
            state
        }

        FlowEvent.UseRecommended -> if (state != null && state.phase == TopicFlowPhase.STYLE) {
            state.copy(phase = TopicFlowPhase.CONFIRM)
        } else {
            state
        }

        FlowEvent.ShiftStyle -> if (state != null && state.phase == TopicFlowPhase.STYLE) {
            // 「改一档」＝就地切到下一套（末尾绕回第一套），随后进确认卡（派工单 §0.2）
            val next = TopicSuggest.STYLES.map { it.id }
                .dropWhile { it != state.styleId }.drop(1).firstOrNull() ?: TopicSuggest.STYLES.first().id
            state.copy(styleId = next, phase = TopicFlowPhase.CONFIRM)
        } else {
            state
        }

        FlowEvent.BackToSuggest -> if (state != null &&
            (state.phase == TopicFlowPhase.CONFIRM || state.phase == TopicFlowPhase.STYLE)
        ) {
            state.copy(phase = TopicFlowPhase.SUGGEST)
        } else {
            state
        }

        FlowEvent.BackToStyle -> if (state != null && state.phase == TopicFlowPhase.CONFIRM) {
            state.copy(phase = TopicFlowPhase.STYLE)
        } else {
            state
        }

        // 唯一开写闸：CONFIRM 相位 + 用户点了按钮。文字到不了这里（见 UserText 分支）。
        FlowEvent.ConfirmWrite -> if (state != null && state.phase == TopicFlowPhase.CONFIRM) {
            val queue = state.candidates.filter { it.seed.id in state.selected }
            if (queue.isEmpty()) state else state.copy(
                phase = TopicFlowPhase.WRITING,
                writing = TopicWritingState(queue = queue, done = 0, results = emptyList()),
            )
        } else {
            state
        }

        is FlowEvent.ArticleDone -> if (state != null && state.phase == TopicFlowPhase.WRITING && state.writing != null) {
            val w = state.writing
            val results = w.results + ev.result
            val done = w.done + 1
            if (done >= w.queue.size) {
                state.copy(phase = TopicFlowPhase.DONE, writing = w.copy(done = done, results = results))
            } else {
                state.copy(writing = w.copy(done = done, results = results))
            }
        } else {
            state
        }

        FlowEvent.StopWrite -> if (state != null && state.phase == TopicFlowPhase.WRITING) {
            state.copy(phase = TopicFlowPhase.DONE)
        } else {
            state
        }

        FlowEvent.Dismiss -> null
    }
}
