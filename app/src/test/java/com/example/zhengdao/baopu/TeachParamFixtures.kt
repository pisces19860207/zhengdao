// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百批 · 测试夹具（唯一一份）：查货页的示教事件、由真学习器产出的技能、
// 以及回放用的内存假 DOM 页面。参数化单测与执行单测共用，避免两份夹具悄悄漂开。
package com.example.zhengdao.baopu

internal object TeachParamFixtures {

    const val START = "https://logi.example.com/query"

    fun resultUrl(dir: String) = "https://logi.example.com/result?direction=$dir"

    // ── 示教事件（5 个动作：选方向 / 填车牌 / 填日期 / 查询 / 翻页）────────────────

    fun events(): List<TeachRawEvent> {
        val dirSel = TeachElementInfo(
            tag = "select", role = "combobox", name = "方向", text = "",
            inputName = "dir", formLabel = "方向",
            options = listOf(
                TeachOption("北京", "北京"),
                TeachOption("天津", "天津"),
                TeachOption("唐山", "唐山"),
            ),
        )
        val plateIn = TeachElementInfo(
            tag = "input", role = "textbox", name = "车牌号", text = null,
            elemId = "plate-input", inputName = "plate", placeholder = "车牌号",
            inputType = "text", formLabel = "车牌",
        )
        val dayIn = TeachElementInfo(
            tag = "input", role = "textbox", name = "日期", text = null,
            elemId = "day-input", inputName = "day", placeholder = "日期",
            inputType = "text", formLabel = "日期",
        )
        val btnQuery = TeachElementInfo(
            tag = "button", role = "button", name = "查询", text = "查询", testid = "go-btn",
        )
        val btnNext = TeachElementInfo(tag = "button", role = "button", name = "下一页", text = "下一页")
        val res = resultUrl("北京")
        return listOf(
            TeachRawEvent(TeachAction.SELECT, START, dirSel, "北京", START, START, 0, 0),
            TeachRawEvent(TeachAction.TYPE, START, plateIn, "冀A12345", START, START, 0, 0),
            TeachRawEvent(TeachAction.TYPE, START, dayIn, "2026-10-24", START, START, 0, 0),
            TeachRawEvent(TeachAction.CLICK, START, btnQuery, null, START, res, 0, 20),
            // 结果页上的下一步：URL 里的 ?direction=北京 从这里被识别成候选落点
            TeachRawEvent(TeachAction.CLICK, res, btnNext, null, res, res, 20, 35),
        )
    }

    /** 走真学习器产出技能（不手搓，判据链完整）。 */
    fun skill(): TeachSkill = TeachLearner.buildDraft(
        events(), "查货-按方向", "按方向查当日运单",
        TeachPageFacts(START, listOf("方向"), listOf("查询")),
        nowMs = 1_728_000_000_000L,
    ).skill

    fun candidate(sk: TeachSkill, example: String): TeachParamCandidate =
        TeachParams.collect(sk).first { it.example == example }

    fun pick(c: TeachParamCandidate, name: String = c.name, default: String? = null) =
        TeachParamPick(
            name = name, label = c.label, example = c.example, source = c.source,
            shape = c.shape, slots = c.slots, default = default, options = c.options,
        )

    /** 把三个值全参数化（方向/车牌/日期），返回可落盘的技能。 */
    fun parameterized(
        examples: List<String> = listOf("北京", "冀A12345", "2026-10-24"),
        defaults: Map<String, String?> = emptyMap(),
    ): TeachSkill {
        val sk = skill()
        val picks = examples.map { ex ->
            val c = candidate(sk, ex)
            pick(c, default = defaults[ex])
        }
        val r = TeachParams.parameterize(sk, picks)
        check(r.ok) { "夹具参数化失败：${r.errors}" }
        return r.skill!!
    }

    /** 回放页面（结果页 URL 随方向变——参数化后要跑不同方向就靠它）。 */
    fun pages(dir: String): Map<String, FakePage> {
        val res = resultUrl(dir)
        val startPage = FakePage(
            START,
            listOf(
                FakeEl(tag = "th", text = "方向"),
                FakeEl(
                    tag = "select", id = "dir-select", nameAttr = "dir", inputType = null,
                    formLabel = "方向", classes = listOf("filter-select"),
                ),
                FakeEl(
                    tag = "input", id = "plate-input", nameAttr = "plate", inputType = "text",
                    placeholder = "车牌号", formLabel = "车牌", classes = listOf("filter-box"),
                ),
                FakeEl(
                    tag = "input", id = "day-input", nameAttr = "day", inputType = "text",
                    placeholder = "日期", formLabel = "日期", classes = listOf("filter-box"),
                ),
                FakeEl(
                    tag = "button", text = "查询", testid = "go-btn", id = "btn-go",
                    classes = listOf("btn", "primary"),
                    onClick = { it.navigate(res) },
                ),
            ),
        )
        val resultPage = FakePage(
            res,
            listOf(
                FakeEl(tag = "th", text = "运单号"),
                FakeEl(
                    tag = "button", text = "下一页", classes = listOf("pager-next"),
                    onClick = { dom ->
                        dom.replacePage(res) { els ->
                            els.filter { it.upperTag != "TR" } + (1..35).map { FakeEl(tag = "tr", text = "次$it") }
                        }
                    },
                ),
            ) + (1..20).map { FakeEl(tag = "tr", text = "行$it") },
        )
        return mapOf(START to startPage, res to resultPage)
    }
}
