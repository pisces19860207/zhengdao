// GZAG · 抱朴 · :policy · 第881批（2026-11-24）· 原创测试。
package com.example.zhengdao.baopu.state.garment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GarmentTextTest {

    @Test
    fun `硬拽剪开撕裂与严重后果判红`() {
        val cases = listOf(
            "他硬拽拉链" to GarmentTextChecks.FORBIDDEN_RELEASE,
            "夹到后强拽拉链" to GarmentTextChecks.FORBIDDEN_RELEASE,
            "拿剪刀剪开布料" to GarmentTextChecks.FORBIDDEN_RELEASE,
            "撕裂布门" to GarmentTextChecks.FORBIDDEN_RELEASE,
            "夹到后把滑块拔出来" to GarmentTextChecks.FORBIDDEN_RELEASE,
            "夹到后用刀割开拉链" to GarmentTextChecks.FORBIDDEN_RELEASE,
            "夹到后把滑块扯出来" to GarmentTextChecks.FORBIDDEN_RELEASE,
            "夹到后把滑块拽出来" to GarmentTextChecks.FORBIDDEN_RELEASE,
            "夹到后出血" to GarmentTextChecks.SEVERE_CONSEQUENCE,
            "夹到后受伤" to GarmentTextChecks.SEVERE_CONSEQUENCE,
            "伤口感染" to GarmentTextChecks.SEVERE_CONSEQUENCE,
            "最后去了医院" to GarmentTextChecks.SEVERE_CONSEQUENCE,
            "夹到后肿起来" to GarmentTextChecks.SEVERE_CONSEQUENCE,
            "夹到后夹破皮" to GarmentTextChecks.SEVERE_CONSEQUENCE,
            "夹到后疼了很久" to GarmentTextChecks.SEVERE_CONSEQUENCE,
            "夹到后一直疼" to GarmentTextChecks.SEVERE_CONSEQUENCE,
            "回拉滑块后皮开肉绽" to GarmentTextChecks.SEVERE_CONSEQUENCE,
            "回拉滑块后没有撕裂且出血" to GarmentTextChecks.SEVERE_CONSEQUENCE,
            "没有撕裂而出血" to GarmentTextChecks.SEVERE_CONSEQUENCE,
        )
        cases.forEach { (text, code) ->
            assertTrue("$text 必须红", code in GarmentFixture.codes(GarmentTextChecks.audit(text, ActionMode.NON_ROUTINE)))
        }
        assertTrue(GarmentTextChecks.FORBIDDEN_RELEASE in GarmentFixture.codes(
            GarmentTextChecks.audit("夹到后用刀割开拉链，回拉滑块", ActionMode.NON_ROUTINE),
        ))
    }

    @Test
    fun `否定只作用当前小句不跨标点`() {
        val good = listOf(
            "没有硬拽，只回拉滑块",
            "没有夹到包皮",
            "拉开时并未夹住",
            "不会出血或感染",
            "没有受伤，只回拉滑块",
            "不需要去医院",
            "无需就医",
            "不必送医",
            "上拉时没留意夹到包皮",
        )
        good.forEach { text ->
            assertTrue(
                "$text 不应误红：${GarmentTextChecks.audit(text, ActionMode.NON_ROUTINE)}",
                GarmentTextChecks.audit(text, ActionMode.NON_ROUTINE).isEmpty(),
            )
        }
        assertTrue(GarmentTextChecks.claimsPinch("上拉时没留意夹到包皮"))
        assertFalse(GarmentTextChecks.HOLE_AUTO_PINCH in GarmentFixture.codes(
            GarmentTextChecks.audit("内裤破洞不导致夹到", ActionMode.NON_ROUTINE),
        ))
        assertTrue(GarmentTextChecks.FORBIDDEN_RELEASE in GarmentFixture.codes(
            GarmentTextChecks.audit("他没受伤，却硬拽", ActionMode.NON_ROUTINE),
        ))
        assertTrue(GarmentTextChecks.claimsPinch("他没有受伤，夹到包皮"))
        assertTrue(GarmentTextChecks.claimsPinch("他没有硬拽但夹到包皮"))
        assertTrue(GarmentTextChecks.claimsPinch("没有硬拽：夹到"))
        assertTrue(GarmentTextChecks.claimsPinch("没有硬拽却夹到"))
        assertTrue(GarmentTextChecks.claimsPinch("没有硬拽而是夹到"))
        assertTrue(GarmentTextChecks.claimsPinch("没有硬拽就夹到"))
        assertTrue(GarmentTextChecks.claimsPinch("没有硬拽反而夹到"))
        assertTrue(GarmentTextChecks.claimsPinch("没有硬拽反倒夹到"))
        assertTrue(GarmentTextChecks.claimsPinch("没有硬拽而且夹到包皮"))
        assertTrue(GarmentTextChecks.claimsPinch("没有硬拽并且夹到包皮"))
        assertTrue(GarmentTextChecks.claimsPinch("没有硬拽同时夹到包皮"))
        assertTrue(GarmentTextChecks.claimsPinch("没有硬拽且夹到包皮，回拉滑块"))
        assertTrue(GarmentTextChecks.claimsPinch("没有硬拽而夹到包皮"))
        assertTrue(GarmentTextChecks.claimsPinch("没有硬拽并夹到包皮"))
        assertTrue(GarmentTextChecks.claimsPinch("并非没有夹到包皮"))
        assertTrue(GarmentTextChecks.claimsBackSlider("没有硬拽，只回拉滑块"))
        assertFalse(GarmentTextChecks.claimsBackSlider("千万别回拉滑块"))
        assertFalse(GarmentTextChecks.claimsBackSlider("不要回拉滑块"))
        assertFalse(GarmentTextChecks.claimsBackSlider("别回拉滑块"))
        assertFalse(GarmentTextChecks.claimsBackSlider("不许回拉滑块"))
        assertFalse(GarmentTextChecks.claimsBackSlider("无法回拉滑块"))
        assertFalse(GarmentTextChecks.claimsBackSlider("禁止回拉滑块"))
        assertFalse(GarmentTextChecks.claimsBackSlider("不允许回拉滑块"))
        assertFalse(GarmentTextChecks.claimsBackSlider("不应回拉滑块"))
        assertFalse(GarmentTextChecks.claimsBackSlider("不用回拉滑块"))
        assertFalse(GarmentTextChecks.claimsBackSlider("不回拉滑块"))
        assertFalse(GarmentTextChecks.claimsBackSlider("请千万别回拉滑块"))
        assertTrue(GarmentTextChecks.claimsBackSlider("请回拉滑块"))
        assertTrue(GarmentTextChecks.WRONG_PHASE in GarmentFixture.codes(
            GarmentTextChecks.audit("拉开时没留意却夹到", ActionMode.NON_ROUTINE),
        ))
        assertTrue(GarmentTextChecks.GARMENT_RISK in GarmentFixture.codes(
            GarmentTextChecks.audit("穿这种裤子没留意却夹到", ActionMode.NON_ROUTINE),
        ))
    }

    @Test
    fun `邋遢可怜穷与卫生刻板判红`() {
        listOf("穿破洞内裤很邋遢", "他看起来很可怜", "因为穷才穿旧的", "贫困才穿旧的", "不讲卫生")
            .forEach { text ->
                assertTrue(
                    "$text 必须红",
                    GarmentTextChecks.EVALUATION in
                        GarmentFixture.codes(GarmentTextChecks.audit(text, ActionMode.ROUTINE)),
                )
            }
        assertTrue(GarmentTextChecks.audit("无穷多个档位", ActionMode.ROUTINE).isEmpty())
    }

    @Test
    fun `裤型习惯差异不是风险源`() {
        val bad = listOf(
            "穿这种裤子导致夹到",
            "这条裤子容易夹到",
            "这种裤子容易夹伤",
            "因为穿这条裤子，所以夹到",
            "穿牛仔裤会夹到",
            "穿紧身裤，因此夹到",
            "穿运动裤，所以夹到",
            "因为穿紧身裤而夹到",
            "因为穿紧身裤，所以把他的包皮夹住了",
            "穿牛仔裤，所以刚才在这个过程中把他的包皮夹住了",
            "这条牛仔裤导致夹到",
        )
        bad.forEach { text ->
            assertTrue(
                "$text 必须红",
                GarmentTextChecks.GARMENT_RISK in
                    GarmentFixture.codes(GarmentTextChecks.audit(text, ActionMode.NON_ROUTINE)),
            )
        }
        val good = listOf(
            "穿这种裤子只是习惯",
            "裤型是个人习惯，不是风险源",
            "这条裤子不容易夹到",
            "穿紧身裤，只是个人习惯",
            "穿运动裤，没有夹到",
            "穿紧身裤，所以没有夹到",
            "因为穿紧身裤，所以把文件夹夹住了",
            "他穿了条旧裤子",
            "他穿着牛仔裤，而口袋里夹着一张纸",
        )
        good.forEach { text ->
            assertTrue(
                "$text 不应误红",
                GarmentTextChecks.GARMENT_RISK !in
                    GarmentFixture.codes(GarmentTextChecks.audit(text, ActionMode.ROUTINE)),
            )
        }
    }

    @Test
    fun `禁物理数值与避免指导`() {
        val bad = listOf(
            "齿距 8 mm",
            "齿距8",
            "齿距为8",
            "牙齿角度 30 度",
            "牙齿角度30",
            "拉链角度是30",
            "门襟宽度2.5",
            "宽度 2.5 厘米",
            "宽度八毫米",
            "齿距八 mm，角度三十度",
            "建议拉拉链时小心",
            "如何避免夹到",
            "裤门要注意角度",
            "拉拉链要慢慢来",
        )
        bad.forEach { text ->
            val codes = GarmentFixture.codes(GarmentTextChecks.audit(text, ActionMode.ROUTINE))
            assertTrue("$text 必须红：$codes", GarmentTextChecks.MEASUREMENT in codes || GarmentTextChecks.ADVICE in codes)
        }
    }

    @Test
    fun `拉开时夹到与穿内裤拉开夹到判红`() {
        val bad = listOf(
            "拉开拉链时夹到包皮",
            "他被夹住在拉开时",
            "穿着内裤，拉开时还是夹到",
            "穿着内裤，拉开时夹了一下包皮",
            "向下拉开时咬住包皮",
            "夹住时拉开拉链",
            "拉开拉链，结果夹住包皮",
            "解开拉链时夹住了包皮",
            "拉开时夹住，回拉滑块",
            "拉开时夹了，回拉滑块",
        )
        bad.forEach { text ->
            val codes = GarmentFixture.codes(GarmentTextChecks.audit(text, ActionMode.NON_ROUTINE))
            assertTrue(
                "$text 必须红：$codes",
                GarmentTextChecks.WRONG_PHASE in codes,
            )
            assertTrue(
                "$text 默认穿着内裤必须红：$codes",
                GarmentTextChecks.PROTECTED_OPENING_PINCH in codes,
            )
        }
        val good = listOf(
            "拉开拉链后，上拉夹到",
            "他穿着内裤，拉开拉链后，上拉夹到",
            "夹到后拉开拉链",
            "穿着内裤，拉开时没有夹到",
        )
        good.forEach { text ->
            assertTrue("$text 不应误红：${GarmentTextChecks.audit(text, ActionMode.NON_ROUTINE)}",
                GarmentTextChecks.audit(text, ActionMode.NON_ROUTINE).isEmpty())
        }
        val absentCodes = GarmentFixture.codes(
            GarmentTextChecks.audit("没穿内裤，拉开时夹到", ActionMode.NON_ROUTINE),
        )
        assertTrue(GarmentTextChecks.claimsPinch("穿着内裤，拉开时夹了一下包皮"))
        assertTrue(GarmentTextChecks.WRONG_PHASE in absentCodes)
        assertFalse(GarmentTextChecks.PROTECTED_OPENING_PINCH in absentCodes)
        listOf(
            "穿着内裤后脱掉，拉开时夹到",
            "脱下内裤，拉开时夹到",
            "穿着内裤，脱下后，拉开时夹到",
        ).forEach { text ->
            val codes = GarmentFixture.codes(GarmentTextChecks.audit(text, ActionMode.NON_ROUTINE))
            assertTrue(GarmentTextChecks.WRONG_PHASE in codes)
            assertFalse(GarmentTextChecks.PROTECTED_OPENING_PINCH in codes)
        }
        listOf(
            "穿着内裤拉开时夹到后脱掉内裤",
            "脱下内裤后穿上内裤，拉开时夹到",
            "他脱下内裤后，又穿了内裤，拉开时夹到",
            "穿着内裤，不要脱内裤，拉开时夹到",
            "穿着内裤，不允许脱内裤，拉开时夹到",
            "穿着内裤，不脱内裤，拉开时夹到",
            "他并非没穿内裤，拉开时夹到",
            "脱下内裤前，拉开时夹到",
            "穿着内裤，拉开拉链时，夹到包皮",
        ).forEach { text ->
            val codes = GarmentFixture.codes(GarmentTextChecks.audit(text, ActionMode.NON_ROUTINE))
            assertTrue("$text 必须红：$codes", GarmentTextChecks.WRONG_PHASE in codes)
            assertTrue("$text 默认穿着内裤必须红：$codes", GarmentTextChecks.PROTECTED_OPENING_PINCH in codes)
        }
        listOf(
            "无内裤，拉开时夹到",
            "没有内裤，拉开时夹到",
            "不穿内裤，拉开时夹到",
            "没有穿内裤，拉开时夹到",
            "穿上内裤之前，拉开时夹到",
            "他以前脱下内裤，拉开时夹到",
            "他之前没穿内裤，这次拉开时夹到",
            "他之前脱下内裤，这次拉开时夹到",
        ).forEach { text ->
            val codes = GarmentFixture.codes(GarmentTextChecks.audit(text, ActionMode.NON_ROUTINE))
            assertTrue("$text 必须红：$codes", GarmentTextChecks.WRONG_PHASE in codes)
            assertFalse("$text 不应误报内裤防护：$codes", GarmentTextChecks.PROTECTED_OPENING_PINCH in codes)
        }
    }

    @Test
    fun `破洞不自动升级为夹到`() {
        assertTrue(GarmentTextChecks.HOLE_AUTO_PINCH in GarmentFixture.codes(
            GarmentTextChecks.audit("内裤破洞就会夹到", ActionMode.NON_ROUTINE),
        ))
        listOf(
            "内裤破洞导致夹到",
            "内裤破洞造成夹到",
            "内裤破洞会夹到",
            "内裤破洞容易夹到",
            "内裤破了一个洞导致夹到",
            "内裤破了个洞，所以夹到",
            "内裤有个洞，因此夹到",
            "内裤破洞所以痛了一下",
            "内裤有个洞，因此红肿",
            "内裤破洞所以受伤",
        ).forEach { text ->
            assertTrue("$text 必须红", GarmentTextChecks.HOLE_AUTO_PINCH in GarmentFixture.codes(
                GarmentTextChecks.audit(text, ActionMode.NON_ROUTINE),
            ))
        }
        assertFalse(GarmentTextChecks.HOLE_AUTO_PINCH in GarmentFixture.codes(
            GarmentTextChecks.audit("内裤破洞不会自动夹到", ActionMode.NON_ROUTINE),
        ))
        assertFalse(GarmentTextChecks.HOLE_AUTO_PINCH in GarmentFixture.codes(
            GarmentTextChecks.audit("内裤破洞不会导致夹到", ActionMode.NON_ROUTINE),
        ))
        assertTrue(GarmentTextChecks.audit("内裤破洞只是局部保护缺口", ActionMode.NON_ROUTINE).isEmpty())
        assertTrue(GarmentTextChecks.audit("内裤破了个洞，没有夹到", ActionMode.NON_ROUTINE).isEmpty())
        assertTrue(GarmentTextChecks.audit("内裤有个洞，照常穿着", ActionMode.NON_ROUTINE).isEmpty())
        assertTrue(GarmentTextChecks.audit("内裤有个洞，所以不会夹到", ActionMode.NON_ROUTINE).isEmpty())
        assertTrue(GarmentTextChecks.audit("内裤没有洞，所以会夹到", ActionMode.NON_ROUTINE).isEmpty())
        assertTrue(GarmentTextChecks.audit("内裤破洞所以没有受伤", ActionMode.NON_ROUTINE).isEmpty())
        assertFalse(GarmentTextChecks.HOLE_AUTO_PINCH in GarmentFixture.codes(
            GarmentTextChecks.audit("内裤没有洞所以受伤", ActionMode.NON_ROUTINE),
        ))
    }

    @Test
    fun `无内裤与破洞默认不当笑点但显式喜剧域放行`() {
        listOf(
            "他没穿内裤，大家笑他",
            "他没内裤，大家笑他",
            "内裤有破洞，大家拿这当笑点",
            "内裤破了一个洞，大家笑",
            "他没穿内裤。大家笑他。",
            "他没穿内裤。同学起先没笑。后来指着他的裤腰哈哈大笑，拿他当笑点。",
        ).forEach { text ->
            assertTrue(
                "$text 必须红",
                GarmentTextChecks.MOCKED_ABSENCE in
                    GarmentFixture.codes(GarmentTextChecks.audit(text, ActionMode.NON_ROUTINE)),
            )
            assertTrue(GarmentTextChecks.audit(text, ActionMode.NON_ROUTINE, NarrativeDomain.COMEDY).isEmpty())
        }
        assertTrue(GarmentTextChecks.MOCKED_ABSENCE in GarmentFixture.codes(
            GarmentTextChecks.audit("没有穿内裤，大家笑他", ActionMode.NON_ROUTINE),
        ))
        assertTrue(GarmentTextChecks.MOCKED_ABSENCE in GarmentFixture.codes(
            GarmentTextChecks.audit("他没穿内裤，班里同学当着全班同学的面一见到他就忍不住哈哈大笑", ActionMode.NON_ROUTINE),
        ))
        assertTrue(GarmentTextChecks.audit("他没穿内裤，没人笑他", ActionMode.NON_ROUTINE).isEmpty())
        assertTrue(GarmentTextChecks.audit("他没穿内裤。没人笑他。", ActionMode.NON_ROUTINE).isEmpty())
        assertTrue(GarmentTextChecks.audit("他没穿内裤，笑不出来", ActionMode.NON_ROUTINE).isEmpty())
        assertTrue(GarmentTextChecks.audit("他没穿内裤，苦笑", ActionMode.NON_ROUTINE).isEmpty())
        assertTrue(GarmentTextChecks.audit("他并非没穿内裤，大家笑他", ActionMode.NON_ROUTINE).isEmpty())
        assertTrue(GarmentTextChecks.audit("他并非没有破洞内裤，大家笑他", ActionMode.NON_ROUTINE).isEmpty())
        assertTrue(GarmentTextChecks.audit("内裤没有破洞，大家笑他", ActionMode.NON_ROUTINE).isEmpty())
        assertTrue(GarmentTextChecks.audit("内裤没有破洞，他听笑话", ActionMode.NON_ROUTINE).isEmpty())
        assertTrue(GarmentTextChecks.audit("他没穿内裤，他听笑话", ActionMode.NON_ROUTINE).isEmpty())
        assertTrue(GarmentTextChecks.audit(
            "他没穿内裤。第一句。第二句。隔壁班嘲笑他的发型。",
            ActionMode.NON_ROUTINE,
        ).isEmpty())
        assertTrue(GarmentTextChecks.audit(
            "他没穿内裤。第一句。第二句。第三句。大家笑他。",
            ActionMode.NON_ROUTINE,
        ).isEmpty())
    }

    @Test
    fun `routine文本出现受伤判红且夹住词表独立`() {
        val bad = listOf(
            "他夹到包皮，痛了一下",
            "上拉夹伤包皮",
            "上拉时夹疼包皮",
            "他夹破皮",
            "他夹住后很疼",
            "上拉后很疼",
            "上拉后受伤",
            "夹住后留痕",
            "夹住后红肿",
        )
        bad.forEach { text ->
            assertTrue(
                "$text 必须红",
                GarmentTextChecks.ROUTINE_INJURY in
                    GarmentFixture.codes(GarmentTextChecks.audit(text, ActionMode.ROUTINE)),
            )
        }
        assertTrue(GarmentTextChecks.claimsPinch("咬住包皮"))
        assertTrue(GarmentTextChecks.claimsPinch("上拉夹伤包皮"))
        assertTrue(GarmentTextChecks.audit("文件夹到抽屉里", ActionMode.ROUTINE).isEmpty())
        assertFalse(GarmentTextChecks.claimsPinch("手心发红"))
        assertFalse(GarmentTextChecks.claimsPinch("他用手夹住文件"))
        assertTrue(GarmentTextChecks.audit("他用手夹住文件", ActionMode.ROUTINE).isEmpty())
        assertFalse(GarmentTextChecks.claimsPinch("小心翼翼怕夹"))
        val fearCodes = GarmentFixture.codes(
            GarmentTextChecks.audit("手心发红，小心翼翼怕夹", ActionMode.ROUTINE),
        )
        assertFalse(GarmentTextChecks.ROUTINE_INJURY in fearCodes)
        assertFalse(GarmentTextChecks.ADVICE in fearCodes)
        assertTrue(GarmentTextChecks.audit("他顺利拉拉链，没有夹到", ActionMode.ROUTINE).isEmpty())
        assertTrue(GarmentTextChecks.audit("上拉后没有受伤", ActionMode.ROUTINE).isEmpty())
    }

    @Test
    fun `中性描述与回拉滑块不判红`() {
        val good = listOf(
            "他穿旧软内裤，裤门顺滑；上拉闭合时夹到，回拉滑块后松开，只红了一下。",
            "上拉闭合时夹到，回拉滑块后松开，只痛了一下。",
            "上拉闭合时夹到，回拉滑块后松开，只留痕。",
            "他没穿内裤，照常拉开拉链。",
            "内裤有破洞，他照常穿着。",
        )
        good.forEach { text ->
            assertEquals("$text 不应误红", emptyList<GarmentIssue>(), GarmentTextChecks.audit(text, ActionMode.NON_ROUTINE))
        }
    }
}
