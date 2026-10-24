// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十六批 §1/§4 落盘层单测：确认才落盘（不确认一个字节不写）、
// 负例 7（技能里出现明文凭证 ⇒ 写盘前直接拒绝）、审计追加、哈希稳定。
// 房型照 SkillStoreTest：真实临时目录＋真实 PersonaFiles，把 184 写口整条链走全。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TeachReplayStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val projectRoot: File by lazy { tmp.newFolder("ws") }
    private val globalRoot: File by lazy { tmp.newFolder("app") }

    private fun files(): PersonaFiles = PersonaFiles(projectRoot = projectRoot, globalRoot = globalRoot)

    private fun draft(): TeachSkillDraft {
        val events = TeachLearnerTestAccess.fiveEvents()
        return TeachLearner.buildDraft(
            events, "查货", "查当日报文",
            TeachPageFacts(
                "https://search.example.com/start",
                listOf("关键词"), listOf("搜索", "提交"),
            ),
            nowMs = 1_728_000_000_000L,
        )
    }

    @Test
    fun `判据_不确认不落盘_草稿只在内存`() {
        val d = draft()
        val skillDir = File(projectRoot, "技能")
        assertFalse("buildDraft 不许写盘", skillDir.exists() && skillDir.listFiles()?.isNotEmpty() == true)
        // 放弃也一个字节不写
        assertNull(d.skill.steps.firstOrNull { it.value == null && it.action == TeachAction.WAIT })
        assertTrue(d.skill.steps.isNotEmpty())
        assertFalse(skillDir.isDirectory)
    }

    @Test
    fun `确认后_回放json与SKILL正文同目录_索引行进C层人设`() {
        val r = TeachReplayStore.saveConfirmed(draft(), projectRoot, globalRoot, files(), "20261024-120000")
        assertNotNull("应保存成功：${r.reason}", r.written)

        val sidecar = TeachReplayStore.sidecarFile(projectRoot, "查货")
        assertTrue(sidecar.isFile)
        val skillMd = TeachReplayStore.skillMdFile(projectRoot, "查货")
        assertTrue(skillMd.isFile)
        val body = skillMd.readText(Charsets.UTF_8)
        assertTrue(body.contains("<!-- status=draft")) // 草稿头（184 试用期口径）
        assertTrue(body.contains("# 回放技能：查货"))
        assertTrue(body.contains("定位链"))
        assertTrue(body.contains("指纹"))
        assertTrue(body.contains("授权域：search.example.com"))
        // 索引与正文是一件事（184 写口铁律）
        val cLayer = files().read(PersonaLayer.C_PROJECT).orEmpty()
        assertTrue(cLayer.contains("【技能】查货："))
        assertTrue(cLayer.contains("草稿·未验证"))
    }

    @Test
    fun `load读回与原技能一致_序列化字节一致`() {
        val d = draft()
        TeachReplayStore.saveConfirmed(d, projectRoot, globalRoot, files(), "20261024-120001")
        val back = TeachReplayStore.load(projectRoot, "查货")
        assertNotNull(back)
        assertEquals(d.skill, back)
        assertEquals(d.skill.toJsonText(), back!!.toJsonText())
        assertNull(TeachReplayStore.load(projectRoot, "不存在的技能"))
    }

    @Test
    fun `负例7_技能里带密码值_写盘前直接拒绝_盘上两文件都没有`() {
        val poisoned = draft().let { dr ->
            TeachSkillDraft(
                skill = dr.skill.copy(
                    steps = listOf(
                        TeachStep(
                            index = 1, action = TeachAction.TYPE,
                            url = dr.skill.startUrl,
                            locators = listOf(TeachLocator(TeachLocatorKind.ELEM_ID, "login-pwd")),
                            value = "hunter2secret",
                            snapshot = TeachElementSnapshot("INPUT", "textbox", null, null, "password"),
                            expect = emptyList(),
                        ),
                    ),
                ),
                warnings = dr.warnings,
                summary = dr.summary,
            )
        }
        val r = TeachReplayStore.saveConfirmed(poisoned, projectRoot, globalRoot, files(), "20261024-120002")
        assertNull("必须拒写", r.written)
        assertTrue(r.reason!!.contains("明文凭证"))
        assertFalse(TeachReplayStore.sidecarFile(projectRoot, "查货").exists())
        assertFalse(TeachReplayStore.skillMdFile(projectRoot, "查货").exists())
    }

    @Test
    fun `审计追加_每执行一行`() {
        val skill = draft().skill
        val report = TeachRunReport(
            skillName = skill.name, ok = true, stepsDone = 5, failedStep = null, stopReason = null,
            durationMs = 1234, drifts = listOf(TeachDrift(2, "T2 testid=x", "T1 roleName=button|查询")),
            refusals = listOf("第 3 步 拒点：候选文案含「提交」"), ask = null, evidenceDir = null,
        )
        assertTrue(TeachReplayStore.appendAudit(projectRoot, report.auditLine(1_728_000_001_000L)))
        assertTrue(TeachReplayStore.appendAudit(projectRoot, report.auditLine(1_728_000_002_000L)))
        val lines = File(File(projectRoot, "技能"), TeachReplayStore.AUDIT_FILE)
            .readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        val o = org.json.JSONObject(lines[0])
        assertEquals("查货", o.getString("skill"))
        assertTrue(o.getBoolean("ok"))
        assertEquals(5, o.getInt("steps"))
        assertTrue(o.getJSONArray("drifts").getJSONObject(0).getString("old").contains("T2"))
        assertTrue(o.getJSONArray("refusals").getString(0).contains("提交"))
    }

    @Test
    fun `sha256_同技能同哈希_改一步就变`() {
        val d = draft()
        assertEquals(TeachReplayStore.sha256Of(d.skill), TeachReplayStore.sha256Of(d.skill.copy()))
        assertFalse(
            TeachReplayStore.sha256Of(d.skill) ==
                TeachReplayStore.sha256Of(d.skill.copy(steps = d.skill.steps.drop(1))),
        )
    }

    @Test
    fun `名字安全化_坏名字拒_好名字进目录`() {
        val bad = draft().let { TeachSkillDraft(it.skill.copy(name = "../越过边界"), it.warnings, it.summary) }
        val r = TeachReplayStore.saveConfirmed(bad, projectRoot, globalRoot, files(), "20261024-120003")
        // sanitizeName 剥掉非法字符后剩「越过边界」——仍是一个合法目录名（不拒，但落点是安全的）
        assertNotNull(r.written)
        assertTrue(r.written!!.absolutePath.contains("技能"))
        assertFalse(r.written.absolutePath.contains(".."))
    }
}
