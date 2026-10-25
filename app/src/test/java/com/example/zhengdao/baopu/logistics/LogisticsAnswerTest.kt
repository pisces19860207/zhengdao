// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · 答话纪律（派工单 §3.5 固定三行）＋判据 20 句的两段（有表 10 / 无表 10）。
//
// 这一段测的是**给用户看的那段话**，不是查询条件（条件归 LogisticsQueryTest）：
// - 行数：`随机抽 10 次提问，回答全部落在三行内` 是原话判据，所以十句一起过 `lineCount ≤ 3`；
// - 禁词：可能／大概／应该／估计／看起来 这类推断词一个不许出现（数据里没有就说没有）；
// - 每答必带数据时间（负例⑤）：**无表那一句也不例外**？不——无表时没有数据时间可带，
//   那句改钉「不许出现任何数字」（编不出时间也不许编数），这是同一枚硬币的两面；
// - 降级（2026-10-25 裁定 4）：字段不支持就少报一维并明说，不许凑一个数出来；
// - **§1.8（2026-10-25 冻结）之后默认档＝ORDER（按配货单去重）**，所以三行逐行钉的那条走默认档，
//   点名格跟着口径变成配货单号；PLATE／TRIP 两条可切换档各自单独钉（数与脚注必须同时变）。
// - §1.8 的隐私分层：司机姓名与手机号进库、面板与台账原样显示，但**三行答话一个都不带**。
//   例外是 2026-10-25 的新增口径：问句本身在问「谁」（WHO 意图）时，面板答话**要**带姓名与号码
//   ——工具那一面拿不到这一档（who 一类的 ask 在工具参数检查处就被拒），所以出本机的路上没有它。
//
// 「未解锁」那两句不在这里，在 LogisticsToolTest（未解锁是工具面的事，答案层不知道解锁这回事）。
package com.example.zhengdao.baopu.logistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogisticsAnswerTest {

    /**
     * 三单两方向：同一台车两单（其中一单待发）、一单没车牌、件数写的是「一批」。
     *
     * 那两单挂在**两张配货单**上（PH101／PH102）而车牌相同——这是「冀A12345 这一天跑了两趟」的形状。
     * 只有这样才能同时看见三种口径的差：按车牌 1 台、按配货单 2 张、按车牌＋方向 1 趟。
     * （§1.8.1 冻结默认档＝按配货单去重；配货单与车牌谁也不替谁。）
     */
    private fun store(): FakeLogisticsStore = FakeLogisticsStore().apply {
        put(
            storedRow("10231", orderNo = "PH101", direction = "北京朝阳", unload = "三间房", goods = "钢材", pieces = 12, plate = "冀A12345", status = LogisticsStatus.LOADED, lineNo = 2),
            storedRow("10232", orderNo = "PH102", direction = "北京朝阳", unload = "四惠", goods = "家具", pieces = 8, plate = "冀A12345", status = LogisticsStatus.PENDING, lineNo = 3),
            storedRow("10233", orderNo = "PH103", direction = "上海青浦", unload = "华新", goods = "钢材", pieces = null, plate = "", status = LogisticsStatus.UNKNOWN, lineNo = 4),
        )
    }

    /**
     * 出话默认档＝**ORDER**。
     *
     * §1.8（2026-10-25 冻结）之后生产路径就是这一档（工具与面板都传 ORDER），
     * 所以三行纪律要钉在用户实际看到的那一档上；PLATE／TRIP 作为可切换档单独钉。
     */
    private fun ask(
        text: String,
        basis: LogisticsBasis = LogisticsBasis.ORDER,
        s: LogisticsStore = store(),
        day: String = TODAY_KEY,
        now: Long = NOW,
    ): String = LogisticsAsk.answer(s, text, basis, day, now, TZ)

    private fun lines(text: String): List<String> = text.lines().filter { it.isNotBlank() }

    // ── 三行的形状 ────────────────────────────────────────────────────────────

    @Test
    fun `三行逐行钉住：汇总、明细、数据时间加脚注`() {
        val text = ask("北京朝阳装没装")
        val got = lines(text)
        assertEquals("三行，不多不少：$text", 3, got.size)
        // 默认档＝按配货单去重：北京朝阳这两单挂在 PH101／PH102 两张单子上 ⇒ 2 车
        // （同一台冀A12345 拉的两趟——换到车牌档就是 1 台，见下面「切换口径」那条）
        assertEquals("北京朝阳方向：2 车／20 件", got[0])
        assertEquals("已装 1 车（PH101），缺 1 车 8 件（PH102）", got[1])
        // 脚注合并进第 3 行（裁定 3：不许为脚注多开一行）
        assertEquals("数据：09:30 导出的配货单；车＝按配货单去重（张）；之后的变化不在这份表里", got[2])
    }

    @Test
    fun `有表十句全部落在三行内、不含推断词、第3行是数据时间`() {
        val ten = listOf(
            "北京朝阳装没装",
            "北京装没装",
            "上海青浦几件",
            "今天还有哪些没发",
            "10231 发了没",
            "冀A12345 装没装",
            "钢材今天装的是什么货",
            "三间房卸了几件",
            "广州白云装没装",
            "还有几单没走",
        )
        val s = store()
        ten.forEach { text ->
            val answer = ask(text, s = s)
            val n = LogisticsAnswer.lineCount(answer)
            assertTrue("「$text」回了 $n 行：$answer", n in 1..3)
        }
    }

    @Test
    fun `十句问法一个禁词都不许出现`() {
        val ten = listOf(
            "北京朝阳装没装", "北京装没装", "上海青浦几件", "今天还有哪些没发", "10231 发了没",
            "冀A12345 装没装", "钢材今天装的是什么货", "三间房卸了几件", "广州白云装没装", "全部几车",
        )
        ten.forEach { text ->
            val answer = ask(text)
            assertEquals("「$text」答出了禁词：$answer", emptyList<String>(), LogisticsAnswer.containsForbidden(answer))
            assertTrue("「$text」最后一行要带数据时间：$answer", lines(answer).last().startsWith("数据："))
        }
    }

    @Test
    fun `无表十句只有一行而且一个数字都没有`() {
        val ten = listOf(
            "北京朝阳装没装", "今天还有哪些没发", "10231 发了没", "几件", "什么货",
            "冀A12345 去哪了", "上海青浦装了几件", "三间房卸了几件", "全部几车", "表是几点导的",
        )
        val empty = FakeLogisticsStore()
        ten.forEach { text ->
            val answer = ask(text, s = empty)
            assertEquals("「$text」应当只有一行：$answer", 1, LogisticsAnswer.lineCount(answer))
            assertEquals("「$text」无表时不许有数字：$answer", emptyList<String>(), LogisticsAnswer.digitsOf(answer))
            assertEquals("「$text」里的禁词：$answer", emptyList<String>(), LogisticsAnswer.containsForbidden(answer))
            assertTrue("要去路（放表的地方）：$answer", answer.contains("inbox"))
        }
    }

    // ── 每答必带口径与数据时间 ────────────────────────────────────────────────

    @Test
    fun `切换口径时答案里的数与脚注同时变`() {
        val plate = ask("北京朝阳装没装", LogisticsBasis.PLATE)
        val order = ask("北京朝阳装没装", LogisticsBasis.ORDER)
        val trip = ask("北京朝阳装没装", LogisticsBasis.TRIP)
        // 同一台冀A12345 拉了两张配货单 ⇒ 按车牌 1 台、按配货单 2 张、按车牌＋方向 1 趟。
        // 三个数两两可比、脚注各说各的定义——判据要的就是「切档时数与话一起变」这一对可观测差。
        assertTrue("车牌档：$plate", plate.lines().first().contains("1 车／20 件"))
        assertTrue("配货单档：$order", order.lines().first().contains("2 车"))
        assertTrue("配货单档的脚注要说按配货单去重：$order", order.contains("车＝按配货单去重（张）"))
        assertTrue("车牌档的脚注要说按车牌去重：$plate", plate.contains("车＝按车牌去重（台）"))
        assertTrue("车次档的脚注要说趟：$trip", trip.contains("车＝按车牌＋方向去重（趟）"))
        // 「车＝按单号数」这一档从来没有过：运单号是**单子**的身份，数出来的是单数不是车数。
        assertFalse("脚注不许把单数说成车数：$order", order.contains("按单号"))
        // 三档都只 3 行（脚注不许把行数撑爆）
        listOf(plate, order, trip).forEach { assertEquals(3, LogisticsAnswer.lineCount(it)) }
    }

    @Test
    fun `点名的东西跟着口径变`() {
        val order = ask("北京朝阳装没装", LogisticsBasis.ORDER)
        // 「2 车」后面跟的是两个配货单号，不是四个运单号——点名的列必须与数车的列是同一列
        assertTrue("默认档点配货单号：$order", order.contains("已装 1 车（PH101）"))
        assertFalse("数的是车，点的不许是单号：$order", order.contains("已装 1 车（10231）"))
        val plate = ask("北京朝阳装没装", LogisticsBasis.PLATE)
        assertTrue("车牌档点车牌：$plate", plate.contains("已装 1 车（冀A12345）"))
    }

    @Test
    fun `取不到数据时间时第三行仍然出现`() {
        val noTime = FakeLogisticsStore().apply {
            put(storedRow("10231", orderNo = "PH101", direction = "北京朝阳", pieces = 12, plate = "冀A12345", status = LogisticsStatus.LOADED, exportedAt = 0L, exportedSource = ExportedAtSource.NONE.name))
        }
        val answer = ask("北京朝阳装没装", s = noTime)
        val last = lines(answer).last()
        assertTrue("取不到也要说取不到：$answer", last.startsWith("数据：") && last.contains("取不到"))
        assertFalse("不许拿墙上时钟顶：$answer", last.contains("09:30"))
    }

    @Test
    fun `只有日期时不许写成零点`() {
        val dayOnly = FakeLogisticsStore().apply {
            put(storedRow("10231", orderNo = "PH101", direction = "北京朝阳", pieces = 12, plate = "冀A12345", status = LogisticsStatus.LOADED, exportedClock = false))
        }
        val answer = ask("北京朝阳装没装", s = dayOnly)
        assertFalse("不许把「只知道哪天」写成 00:00：$answer", answer.contains("00:00"))
        assertTrue(lines(answer).last().contains("10-25"))
    }

    @Test
    fun `补导昨天的表时数据时间要说不不是今天`() {
        val yesterday = FakeLogisticsStore().apply {
            put(storedRow("10231", orderNo = "PH101", day = "20261024", direction = "北京朝阳", pieces = 12, plate = "冀A12345", status = LogisticsStatus.LOADED, exportedAt = millisOf(24)))
        }
        val answer = ask("北京朝阳装没装", s = yesterday, day = "20261024")
        assertTrue("跨天要明说：$answer", answer.contains("（不是今天）"))
    }

    // ── 降级（裁定 4：不支持就降级，不许凑）──────────────────────────────────

    @Test
    fun `缺件数列时报缺件数列`() {
        val s = FakeLogisticsStore().apply {
            put(
                storedRow("10231", orderNo = "PH101", direction = "北京朝阳", pieces = null, plate = "冀A12345", status = LogisticsStatus.LOADED, missing = "件数"),
                storedRow("10232", orderNo = "PH102", direction = "北京朝阳", pieces = null, plate = "冀B67890", status = LogisticsStatus.PENDING, missing = "件数"),
            )
        }
        val answer = ask("北京朝阳几件", s = s)
        assertTrue("要说缺件数列：$answer", answer.contains("（缺件数列）"))
        assertFalse("不许出现 0 件：$answer", answer.contains("0 件"))
    }

    @Test
    fun `件数列在但一格都没读出不谎报缺列`() {
        // 「一批」这种写法：列明明在，只是这格不是数字。说成「缺件数列」会让人去 T9 补列
        val answer = ask("上海青浦几件")
        assertTrue("要说这维没数：$answer", answer.contains("（件数没一个有数）"))
        assertFalse("不许谎报缺列：$answer", answer.contains("缺件数列"))
    }

    @Test
    fun `缺车牌列时两行都不报车数`() {
        val s = FakeLogisticsStore().apply {
            put(
                storedRow("10231", orderNo = "PH101", direction = "北京朝阳", pieces = 12, status = LogisticsStatus.LOADED, missing = "车牌", lineNo = 2),
                storedRow("10232", orderNo = "PH102", direction = "北京朝阳", pieces = 8, status = LogisticsStatus.PENDING, missing = "车牌", lineNo = 3),
            )
        }
        val answer = ask("北京朝阳装没装", LogisticsBasis.PLATE, s = s)
        assertTrue("汇总行要说车数不报：$answer", answer.contains("缺车牌列，车数不报"))
        // 明细行也得跟着降级：第 1 行说不报、第 2 行却写「已装 0 车」，那是换个地方凑数
        assertFalse("明细行不许出现车数：$answer", answer.contains(" 车"))
        assertTrue("改按单点名：$answer", answer.contains("已装 1 单（10231）"))
    }

    @Test
    fun `缺配货单号列时默认档降级而不是改口报车牌`() {
        // 默认档靠的是 `配货单号` 那一列。缺它时**不许**悄悄换成车牌去数——
        // 那是同一句话两次跑出两个数；要换也得让她在面板上切档，脚注跟着换。
        val s = FakeLogisticsStore().apply {
            put(
                storedRow("10231", direction = "北京朝阳", pieces = 12, plate = "冀A12345", status = LogisticsStatus.LOADED, missing = "配货单号", lineNo = 2),
                storedRow("10232", direction = "北京朝阳", pieces = 8, plate = "冀A12345", status = LogisticsStatus.PENDING, missing = "配货单号", lineNo = 3),
            )
        }
        val answer = ask("北京朝阳装没装", s = s)
        assertTrue("缺的是配货单号那一列：$answer", answer.contains("缺配货单号列，车数不报"))
        assertFalse("不许换成按车牌数：$answer", answer.contains(" 车"))
        assertTrue("改按单点名：$answer", answer.contains("已装 1 单（10231）"))
        // 脚注仍然说清这一档的定义（她看见「车＝按配货单去重」才知道刚才那句为什么没车数）
        assertTrue("脚注要说默认档的定义：$answer", answer.contains("车＝按配货单去重（张）"))
        assertEquals(3, LogisticsAnswer.lineCount(answer))
    }

    @Test
    fun `没有状态列时明细行答无法判断而不是都没装`() {
        val s = FakeLogisticsStore().apply {
            put(
                storedRow("10231", orderNo = "PH101", direction = "北京朝阳", pieces = 12, plate = "冀A12345", status = LogisticsStatus.UNKNOWN, missing = "状态", lineNo = 2),
                storedRow("10232", orderNo = "PH102", direction = "北京朝阳", pieces = 8, plate = "冀B67890", status = LogisticsStatus.UNKNOWN, missing = "状态", lineNo = 3),
            )
        }
        val answer = ask("北京朝阳装没装", s = s)
        assertTrue("第三行不许按都还没装算：$answer", answer.contains("表里没有状态列，无法判断装没装"))
        assertFalse("不许报待发件数：$answer", answer.contains("缺 2 车"))
    }

    @Test
    fun `缺货名列时什么货那一问直说没有这一维`() {
        val s = FakeLogisticsStore().apply {
            put(storedRow("10231", direction = "北京朝阳", pieces = 12, plate = "冀A12345", status = LogisticsStatus.LOADED, missing = "货名"))
        }
        val answer = ask("今天装的是什么货", s = s)
        assertTrue("不许编货名：$answer", answer.contains("表里没有货名列"))
    }

    @Test
    fun `什么货按货名分布报，没填的点出几单`() {
        val answer = ask("今天装的是什么货")
        val detail = lines(answer).first { it.contains("货名：") }
        assertTrue("钢材 12 件＋家具 8 件：$detail", detail.contains("钢材 12 件") && detail.contains("家具 8 件"))
    }

    // ── 明细折叠与状态各档 ────────────────────────────────────────────────────

    @Test
    fun `明细超过三条折成另有N条展开看`() {
        val many = FakeLogisticsStore().apply {
            (1..5).forEach { i ->
                put(storedRow("2000$i", orderNo = "PH20$i", direction = "北京朝阳", pieces = i, plate = "冀A1234$i", status = LogisticsStatus.PENDING, lineNo = i + 1))
            }
        }
        val answer = ask("北京朝阳装没装", s = many)
        assertTrue("要折叠：$answer", answer.contains("另有 2 条，展开看"))
        assertEquals("折叠完仍然三行：$answer", 3, LogisticsAnswer.lineCount(answer))
        // 条数说全：折的是名单，不是数量
        assertTrue(answer.contains("缺 5 车 15 件"))
    }

    @Test
    fun `缺件与状态没填的单子各点一句不塞进任何一边`() {
        val s = FakeLogisticsStore().apply {
            put(
                storedRow("10231", orderNo = "PH101", direction = "北京朝阳", pieces = 12, plate = "冀A12345", status = LogisticsStatus.LOADED, lineNo = 2),
                storedRow("10232", orderNo = "PH102", direction = "北京朝阳", pieces = 8, plate = "冀B67890", status = LogisticsStatus.SHORT, lineNo = 3),
                storedRow("10233", orderNo = "PH103", direction = "北京朝阳", pieces = 5, plate = "冀C11111", status = LogisticsStatus.UNKNOWN, lineNo = 4),
            )
        }
        val answer = ask("北京朝阳装没装", s = s)
        val detail = lines(answer)[1]
        assertTrue("缺件要单列：$detail", detail.contains("另 1 单标了缺件"))
        assertTrue("状态没填要单列：$detail", detail.contains("另 1 单状态未填，没法判断"))
        // 既不进已装也不进待发
        assertFalse("缺件的那单不许算待发：$detail", detail.contains("缺 1 车 8 件"))
    }

    @Test
    fun `一件没读出来时说明没算进来`() {
        val answer = ask("今天还有哪些没发")
        assertTrue("件数没算进来的要交代：$answer", answer.contains("1 单件数未填，没算进来"))
        assertEquals(3, LogisticsAnswer.lineCount(answer))
        // 车牌没填的那单在**车牌档**进不了车数，脚注同样要交代（切档才看得到的那一维）
        val byPlate = ask("今天还有哪些没发", LogisticsBasis.PLATE)
        assertTrue("车牌档要说车牌未填：$byPlate", byPlate.contains("1 单车牌未填，没算进车数"))
        // 默认档（配货单）的同一条脚注跟着口径换列名：三单里有一单没配货单号 ⇒ 报 1 单而不是 0 单
        val noOrder = FakeLogisticsStore().apply {
            put(
                storedRow("10231", orderNo = "PH101", direction = "北京朝阳", pieces = 12, plate = "冀A12345", status = LogisticsStatus.LOADED, lineNo = 2),
                storedRow("10234", direction = "北京朝阳", pieces = 3, plate = "冀D22222", status = LogisticsStatus.PENDING, lineNo = 3),
            )
        }
        val byOrder = ask("北京朝阳装没装", LogisticsBasis.ORDER, s = noOrder)
        assertTrue("默认档要说配货单号未填：$byOrder", byOrder.contains("1 单配货单号未填，没算进车数"))
        assertFalse("不许替那一单发明一张配货单：$byOrder", byOrder.contains("2 车"))
    }

    // ── 拒答与反问 ────────────────────────────────────────────────────────────

    @Test
    fun `表里没有这个词时两行：结论加数据时间`() {
        val answer = ask("广州白云装没装")
        assertEquals("两行：$answer", 2, LogisticsAnswer.lineCount(answer))
        assertTrue(answer.contains("这份表里没有「广州白云」的记录"))
        assertEquals(0, LogisticsAnswer.containsForbidden(answer).size)
    }

    @Test
    fun `歧义只反问一行并且把选项念出来`() {
        val s = FakeLogisticsStore().apply {
            put(
                storedRow("10231", direction = "北京朝阳", pieces = 12, plate = "冀A12345", status = LogisticsStatus.LOADED, lineNo = 2),
                storedRow("10232", direction = "北京丰台", pieces = 8, plate = "冀B67890", status = LogisticsStatus.PENDING, lineNo = 3),
            )
        }
        val answer = ask("北京装没装", s = s)
        assertEquals("反问不许多行：$answer", 2, LogisticsAnswer.lineCount(answer))
        val head = lines(answer).first()
        assertTrue("要反问：$head", head.endsWith("问的是哪个？"))
        assertTrue("选项要念全：$head", head.contains("北京朝阳") && head.contains("北京丰台"))
        assertEquals(0, LogisticsAnswer.containsForbidden(answer).size)
    }

    @Test
    fun `禁词表覆盖裁定点名的五个推断词`() {
        listOf("可能", "大概", "应该", "估计", "看起来").forEach {
            assertTrue("「$it」必须在禁词表里", it in LogisticsAnswer.FORBIDDEN)
        }
    }

    @Test
    fun `答案里不许有建议句式`() {
        val s = store()
        listOf("北京朝阳装没装", "今天还有哪些没发", "广州白云装没装", "上海青浦几件").forEach { text ->
            val answer = ask(text, s = s)
            listOf("建议您", "建议你", "最好", "别忘了").forEach {
                assertFalse("「$it」是建议，不许出现在「$text」的回答里：$answer", answer.contains(it))
            }
        }
    }

    @Test
    fun `一行里不出现两件事：汇总只报数，明细才点名`() {
        val got = lines(ask("北京朝阳装没装"))
        // 单号/车牌/配货单号只许出现在第 2 行（第 1 行是「几个」，第 2 行才是「哪些」）
        assertFalse("汇总行不许点名：${got[0]}", got[0].contains("冀") || got[0].contains("1023") || got[0].contains("PH"))
        assertTrue("明细行必须点名（默认档点的是配货单号）：${got[1]}", got[1].contains("PH101"))
        val plateLine = lines(ask("北京朝阳装没装", LogisticsBasis.PLATE))[1]
        assertTrue("车牌档点车牌：$plateLine", plateLine.contains("冀A12345"))
    }

    @Test
    fun `答案里不出现真实来源文件之外的路径`() {
        val answer = ask("北京朝阳装没装")
        assertFalse("答案不带绝对路径：$answer", answer.contains("C:\\") || answer.contains("/storage/"))
    }

    @Test
    fun `答案里不出现司机姓名与手机号`() {
        // §1.8（2026-10-25 冻结）：库里存、面板与台账**原样显示**这四列（配货单号、车牌、司机姓名、司机手机号），
        // 但「不进日志、不进审计、不进模型上下文」这一条同样成立。三行答案是唯一会被转发、
        // 会被念给旁人听的那一段 ⇒ 这一维只在面板看得见，一句话都不带。
        val s = FakeLogisticsStore().apply {
            put(
                storedRow(
                    "10231", orderNo = "PH101", direction = "北京朝阳", unload = "三间房", goods = "钢材",
                    pieces = 12, weight = "3.5", volume = "4.2", driver = "王师傅", phone = "13800000001",
                    plate = "冀A12345", status = LogisticsStatus.LOADED, lineNo = 2,
                ),
            )
        }
        listOf("北京朝阳装没装", "今天还有哪些没发", "10231 发了没", "今天装的是什么货").forEach { text ->
            val answer = ask(text, s = s)
            listOf("王师傅", "13800000001").forEach {
                assertFalse("「$text」的答话里不许带「$it」：$answer", answer.contains(it))
            }
        }
        // 车牌与配货单号是点名单元，本来就要出现（§1.8 原样显示，不打码）
        val loaded = ask("北京朝阳装没装", s = s)
        assertTrue("配货单号要看得见：$loaded", loaded.contains("PH101"))
        assertTrue("车牌要看得见：${ask("北京朝阳装没装", LogisticsBasis.PLATE, s = s)}",
            ask("北京朝阳装没装", LogisticsBasis.PLATE, s = s).contains("冀A12345"))
    }

    // ── 「这车谁的／老张电话」（2026-10-25 新增口径；答话只在**面板**带姓名与号码）──

    /** 两司三单：王师傅两单（号码一空一真），张师傅一单没号。 */
    private fun crew(): FakeLogisticsStore = FakeLogisticsStore().apply {
        put(
            storedRow("10231", direction = "北京朝阳", driver = "王师傅", phone = "138 0013 8000", lineNo = 2),
            storedRow("10232", direction = "北京朝阳", driver = "王师傅", phone = "13800008000", lineNo = 3),
            storedRow("10233", direction = "上海青浦", driver = "张师傅", phone = "", lineNo = 4),
        )
    }

    @Test
    fun `这车谁的答司机与号码且仍然三行`() {
        val answer = ask("这车谁的", s = crew())
        assertEquals("仍落三行内：$answer", 3, LogisticsAnswer.lineCount(answer))
        assertEquals("禁词照扫：$answer", emptyList<String>(), LogisticsAnswer.containsForbidden(answer))
        assertTrue("姓名原样：$answer", answer.contains("王师傅"))
        assertTrue("号码原样（带分隔符照写）：$answer", answer.contains("138 0013 8000"))
        assertTrue("没号的写未填不许写 0：$answer", answer.contains("张师傅 未填"))
        assertTrue("号码不许从单号车牌那边凑：$answer", !answer.contains("1023"))
    }

    @Test
    fun `老张电话只答他那几单`() {
        val answer = ask("老张电话", s = crew())
        assertTrue("要答到张师傅：$answer", answer.contains("张师傅"))
        assertFalse("王师傅不该被捎带：$answer", answer.contains("王师傅"))
    }

    @Test
    fun `手机号整列缺席时只报名字并点名缺列`() {
        val s = FakeLogisticsStore().apply {
            put(
                storedRow("10231", orderNo = "PH101", direction = "北京朝阳", driver = "王师傅", missing = "手机号", lineNo = 2),
                storedRow("10232", orderNo = "PH102", direction = "上海青浦", driver = "张师傅", missing = "手机号", lineNo = 3),
            )
        }
        val answer = ask("这车谁的", s = s)
        assertTrue("要点名缺的是手机号列：$answer", answer.contains("表里没有手机号列"))
        assertTrue(answer.contains("王师傅"))
        assertFalse("没这列时连「未填」都不许写（那是格子空，不是列没有）：$answer", answer.contains("未填"))
    }

    @Test
    fun `司机列整列缺席时直说答不了`() {
        val s = FakeLogisticsStore().apply {
            put(storedRow("10231", direction = "北京朝阳", plate = "冀A12345", missing = "司机", lineNo = 2))
        }
        val answer = ask("这车谁的", s = s)
        assertTrue("$answer", answer.contains("表里没有司机列"))
        assertFalse("不许拿车牌凑人：$answer", answer.contains("冀A12345"))
    }

    @Test
    fun `司机一格都没填时答没填而不是编人名`() {
        val s = FakeLogisticsStore().apply {
            put(storedRow("10231", direction = "北京朝阳", lineNo = 2))
        }
        assertEquals("司机一栏没填", lines(ask("这车谁的", s = s))[1])
    }

    @Test
    fun `司机超过三人折叠但仍三行`() {
        val s = FakeLogisticsStore().apply {
            put(
                *(1..5).map { i ->
                    storedRow("1023$i", direction = "北京朝阳", driver = "司机$i", phone = "1380000000$i", lineNo = i + 1)
                }.toTypedArray(),
            )
        }
        val answer = ask("这车谁的", s = s)
        assertEquals(3, LogisticsAnswer.lineCount(answer))
        assertTrue("要写折叠：$answer", answer.contains("另有 2 人"))
        assertFalse("折叠后不许全列：$answer", answer.contains("司机4"))
    }

    @Test
    fun `货主是谁是两行拒答并点名缺列`() {
        val answer = ask("货主是谁", s = crew())
        assertEquals("拒答两行：$answer", 2, LogisticsAnswer.lineCount(answer))
        assertTrue("$answer", answer.contains("这份表里没有「货主」这一列"))
        assertFalse("不许把司机名单捎带出去：$answer", answer.contains("王师傅"))
    }
}
