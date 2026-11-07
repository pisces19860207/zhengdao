// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 第五百一十批 · 判据⑤：模型给自己的草稿票"通过" ⇒ 被拦并记账。
// 另含四类产物的机械自检测试（派工单 §六/§七/§九）。
package com.example.zhengdao.baopu.wish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WishSelfCheckTest {

    // ── 判据⑤：模型自评被拦 ──

    @Test
    fun 判据5_模型给自己发票被拦() {
        val advice = ModelAdvice("我觉得这个草稿挺好的，通过", claimsPass = true)
        val verdict = WishSelfCheck.guardModelPass(advice)
        assertTrue(verdict is SelfCheckVerdict.ModelPassBlocked)
        assertEquals("我觉得这个草稿挺好的，通过", (verdict as SelfCheckVerdict.ModelPassBlocked).modelClaim)
    }

    @Test
    fun 模型只参谋不发票则放行() {
        val advice = ModelAdvice("建议检查边界条件", claimsPass = false)
        assertNull(WishSelfCheck.guardModelPass(advice))
    }

    @Test
    fun 无模型意见也放行() {
        assertNull(WishSelfCheck.guardModelPass(null))
    }

    // ── 技能自检（§七①） ──

    @Test
    fun 技能_全通() {
        val r = SkillTrialReading(allStepsOk = true, realUntouched = true, sampleMatch = true)
        assertTrue(WishSelfCheck.checkSkill(r) is SelfCheckVerdict.Pass)
    }

    @Test
    fun 技能_步骤失败() {
        val r = SkillTrialReading(allStepsOk = false, realUntouched = true, sampleMatch = true)
        assertTrue(WishSelfCheck.checkSkill(r) is SelfCheckVerdict.Fail)
    }

    @Test
    fun 技能_真文件被改() {
        val r = SkillTrialReading(allStepsOk = true, realUntouched = false, sampleMatch = true)
        val v = WishSelfCheck.checkSkill(r)
        assertTrue(v is SelfCheckVerdict.Fail)
        assertTrue((v as SelfCheckVerdict.Fail).reason.contains("hash"))
    }

    @Test
    fun 技能_样例不一致() {
        val r = SkillTrialReading(allStepsOk = true, realUntouched = true, sampleMatch = false)
        assertTrue(WishSelfCheck.checkSkill(r) is SelfCheckVerdict.Fail)
    }

    // ── 卡片自检（§七②） ──

    @Test
    fun 卡片_全通() {
        val r = CardCheckReading(formatRecognized = true, formValid = true, deterministic = true)
        assertTrue(WishSelfCheck.checkCard(r) is SelfCheckVerdict.Pass)
    }

    @Test
    fun 卡片_认脸失败() {
        val r = CardCheckReading(formatRecognized = false, formValid = true, deterministic = true)
        assertTrue(WishSelfCheck.checkCard(r) is SelfCheckVerdict.Fail)
    }

    @Test
    fun 卡片_两次build不一致() {
        val r = CardCheckReading(formatRecognized = true, formValid = true, deterministic = false)
        val v = WishSelfCheck.checkCard(r)
        assertTrue(v is SelfCheckVerdict.Fail)
        assertTrue((v as SelfCheckVerdict.Fail).reason.contains("一致"))
    }

    // ── 内容自检（§七③） ──

    @Test
    fun 内容_全通() {
        val r = ContentCheckReading(citationCount = 2, wordCount = 300, minWords = 100, maxWords = 500, duplicateCount = 0)
        assertTrue(WishSelfCheck.checkContent(r) is SelfCheckVerdict.Pass)
    }

    @Test
    fun 内容_依据为空拒() {
        val r = ContentCheckReading(citationCount = 0, wordCount = 300, minWords = 100, maxWords = 500, duplicateCount = 0)
        val v = WishSelfCheck.checkContent(r)
        assertTrue(v is SelfCheckVerdict.Fail)
        assertTrue((v as SelfCheckVerdict.Fail).reason.contains("依据为空"))
    }

    @Test
    fun 内容_台账重复拒() {
        val r = ContentCheckReading(citationCount = 1, wordCount = 300, minWords = 100, maxWords = 500, duplicateCount = 2)
        assertTrue(WishSelfCheck.checkContent(r) is SelfCheckVerdict.Fail)
    }

    @Test
    fun 内容_字数越界拒() {
        val r = ContentCheckReading(citationCount = 1, wordCount = 50, minWords = 100, maxWords = 500, duplicateCount = 0)
        assertTrue(WishSelfCheck.checkContent(r) is SelfCheckVerdict.Fail)
    }

    // ── 小说自检（§九） ──

    @Test
    fun 小说_全通() {
        val r = NovelCheckReading(hasOutline = true, hasChapters = true, hasBody = true, newCharacterNames = emptyList(), oldChapterChanged = false, chapterWordsInRange = true, dirConventionsOk = true)
        assertTrue(WishSelfCheck.checkNovel(r) is SelfCheckVerdict.Pass)
    }

    @Test
    fun 小说_新造人物名拒() {
        val r = NovelCheckReading(hasOutline = true, hasChapters = true, hasBody = true, newCharacterNames = listOf("张三丰"), oldChapterChanged = false, chapterWordsInRange = true, dirConventionsOk = true)
        val v = WishSelfCheck.checkNovel(r)
        assertTrue(v is SelfCheckVerdict.Fail)
        assertTrue((v as SelfCheckVerdict.Fail).reason.contains("张三丰"))
    }

    @Test
    fun 小说_旧章被改拒() {
        val r = NovelCheckReading(hasOutline = true, hasChapters = true, hasBody = true, newCharacterNames = emptyList(), oldChapterChanged = true, chapterWordsInRange = true, dirConventionsOk = true)
        assertTrue(WishSelfCheck.checkNovel(r) is SelfCheckVerdict.Fail)
    }

    @Test
    fun 小说_三级不齐拒() {
        val r = NovelCheckReading(hasOutline = true, hasChapters = false, hasBody = true, newCharacterNames = emptyList(), oldChapterChanged = false, chapterWordsInRange = true, dirConventionsOk = true)
        assertTrue(WishSelfCheck.checkNovel(r) is SelfCheckVerdict.Fail)
    }

    @Test
    fun 小说_目录不合既有规范拒() {
        // §九⑤：book.json／正文目录约定由既有 novel_project 判，读数不合规 ⇒ 拒（不自动进正文）。
        val r = NovelCheckReading(hasOutline = true, hasChapters = true, hasBody = true, newCharacterNames = emptyList(), oldChapterChanged = false, chapterWordsInRange = true, dirConventionsOk = false)
        val v = WishSelfCheck.checkNovel(r)
        assertTrue(v is SelfCheckVerdict.Fail)
        assertTrue((v as SelfCheckVerdict.Fail).reason.contains("目录"))
    }

    // ── 路由 ──

    @Test
    fun 按产物路由到对应判定() {
        val skill = SkillTrialReading(true, true, true)
        assertTrue(WishSelfCheck.check(WishProduct.SKILL, skill = skill) is SelfCheckVerdict.Pass)
        assertTrue(WishSelfCheck.check(WishProduct.CARD) is SelfCheckVerdict.Fail)
    }
}