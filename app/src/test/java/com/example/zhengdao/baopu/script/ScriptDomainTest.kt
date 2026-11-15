// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十一批 · 剧本域的纯判据（不碰工具、不碰工作区；只验格式与规则）。
//
// 钉的是派工单里能用纯函数说的那几条：Fountain 读回、一场一次的形状闸、
// 反性格冲突的配对、原文坐标系的章/行对账、中文档的排版记号。
package com.example.zhengdao.baopu.script

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptDomainTest {

    /** 取 Parsed 的值（坏 ⇒ 断言失败并带上人话）；避免 `as Parsed.Ok` 裸转型。 */
    private fun <T> valueOf(parsed: Parsed<T>): T = when (parsed) {
        is Parsed.Ok -> parsed.value
        is Parsed.Bad -> throw AssertionError(parsed.reason)
    }

    private fun scene(
        no: Int,
        location: String = "咖啡馆",
        time: String = "夜",
        interior: Boolean = true,
        beats: List<Beat>,
        characters: List<String> = listOf("沈砚", "林夏"),
        conflicts: List<Conflict> = emptyList(),
        source: SourceRef? = null,
    ): Scene = Scene(
        id = ScriptFormat.idOf(no),
        no = no,
        location = location,
        interior = interior,
        time = time,
        characters = characters,
        beats = beats,
        conflicts = conflicts,
        source = source,
        tier = ScriptTier.REWORK,
        rewrite = false,
        atMs = 0L,
    )

    // ── ① Fountain：写出去能按规范读回来 ──────────────────────────────────

    @Test
    fun `Fountain_三种拍都读得回来`() {
        val scenes = listOf(
            scene(
                1,
                beats = listOf(
                    Beat(BeatKind.ACTION, "沈砚坐在窗边，杯子已经凉了。", null, null, 2),
                    Beat(BeatKind.DIALOGUE, "你来晚了。", "林夏", "低声", 3),
                    Beat(BeatKind.TRANSITION, "切至：巷口", null, null, null),
                ),
            ),
            scene(
                2,
                location = "巷口",
                time = "日",
                interior = false,
                beats = listOf(Beat(BeatKind.ACTION, "雨还没停。", null, null, null)),
            ),
        )
        val text = FountainOut.write("雨夜", scenes, "2026-11-15")
        assertTrue("标题页要有 Title", text.startsWith("Title: 雨夜"))
        assertTrue("场景头按 INT./EXT. 写", text.contains("INT. 咖啡馆 - 夜"))
        assertTrue("外景写 EXT.", text.contains("EXT. 巷口 - 日"))

        val back = FountainIn.parse(text)
        assertTrue("读回失败：$back", back is Parsed.Ok)
        val parsed = valueOf(back)
        assertEquals(2, parsed.size)
        assertEquals("INT. 咖啡馆 - 夜", parsed[0].heading)
        assertEquals(3, parsed[0].beats.size)
        assertEquals(BeatKind.DIALOGUE, parsed[0].beats[1].kind)
        assertEquals("林夏", parsed[0].beats[1].who)
        assertEquals("低声", parsed[0].beats[1].paren)
        assertEquals(BeatKind.TRANSITION, parsed[0].beats[2].kind)
        assertEquals("切至：巷口", parsed[0].beats[2].text)
        assertNull("写出去与读回来必须逐拍一致", FountainSelfCheck.compare(scenes, parsed))
    }

    @Test
    fun `Fountain_动作行首是记号时加感叹号转义`() {
        val beat = Beat(BeatKind.ACTION, "@沈砚转身就走", null, null, null)
        val out = FountainOut.action("@沈砚转身就走")
        assertTrue("行首 @ 会被读成人物名，必须转义：$out", out.startsWith("!"))
        val text = FountainOut.write("片", listOf(scene(1, beats = listOf(beat))), "2026-11-15")
        val parsed = valueOf(FountainIn.parse(text))
        assertEquals("转义后仍是动作", BeatKind.ACTION, parsed[0].beats[0].kind)
        assertEquals("@沈砚转身就走", parsed[0].beats[0].text)
    }

    // ── ② 一场一次：形状闸 ───────────────────────────────────────────────

    @Test
    fun `一次五场_报出五场并拒`() {
        val args = JSONObject().put("project", "片").put(
            "scenes",
            JSONArray().also { a -> repeat(5) { a.put(JSONObject().put("summary", "第${it + 1}场")) } },
        )
        val reason = ScriptWriteRules.batchProblem(args)
        assertNotNull("scenes 数组必须被拒", reason)
        assertTrue("要报出给了几场：$reason", reason!!.contains("5 场"))
    }

    @Test
    fun `只给一个 scene 对象_形状合法`() {
        assertNull(ScriptWriteRules.batchProblem(JSONObject().put("scene", JSONObject().put("id", "S01"))))
    }

    // ── ③ 反性格冲突：禁区命中必须列冲突 ─────────────────────────────────

    @Test
    fun `反性格冲突漏报_会被点名`() {
        val bibles = listOf(
            CharBible("沈砚", listOf("寡言"), listOf(Forbid("滔滔不绝", "第一章 L2"))),
        )
        val beats = listOf(
            Beat(BeatKind.DIALOGUE, "他滔滔不绝地讲了十分钟。", "沈砚", null, null),
        )
        val missing = ScriptWriteRules.missingConflicts(beats, listOf("沈砚"), bibles, emptyList())
        assertEquals(1, missing.size)
        assertEquals("沈砚", missing[0].who)
        assertEquals("滔滔不绝", missing[0].phrase)
        assertEquals("第 1 拍", missing[0].where)
    }

    @Test
    fun `列了对应冲突项_就不算漏报`() {
        val bibles = listOf(
            CharBible("沈砚", listOf("寡言"), listOf(Forbid("滔滔不绝", "第一章 L2"))),
        )
        val beats = listOf(
            Beat(BeatKind.DIALOGUE, "他滔滔不绝地讲了十分钟。", "沈砚", null, null),
        )
        val conflicts = listOf(Conflict("沈砚", "第一章 L2", "滔滔不绝地讲了十分钟", "为了让心结说开"))
        assertTrue(ScriptWriteRules.missingConflicts(beats, listOf("沈砚"), bibles, conflicts).isEmpty())
    }

    @Test
    fun `没有禁区的人物_不产生假冲突`() {
        val bibles = listOf(CharBible("林夏", listOf("爽利"), emptyList()))
        val beats = listOf(Beat(BeatKind.ACTION, "林夏把伞一丢。", null, null, null))
        assertTrue(ScriptWriteRules.missingConflicts(beats, listOf("林夏"), bibles, emptyList()).isEmpty())
    }

    // ── ④ 原文坐标系：章＋行 ─────────────────────────────────────────────

    private val novel = listOf(
        "第一章 夜访",
        "沈砚坐在窗边，一句话没说。",
        "林夏推门进来，把伞靠在桌边。",
        "",
        "第二章 雨后",
        "沈砚撑着伞站在巷口，仍旧不说话。",
        "林夏追出来，喊他的名字。",
        "",
    ).joinToString("\n")

    @Test
    fun `章块按标题切_行号从1数`() {
        val src = valueOf(NovelSource.ofText(File("书.txt"), novel))
        assertEquals(2, src.chapters.size)
        assertEquals(1, src.chapters[0].startLine)
        assertEquals(4, src.chapters[0].endLine)
        assertEquals(5, src.chapters[1].startLine)
        assertEquals(8, src.chapters[1].endLine)
        assertNull(src.check(SourceRef(1, 2, 4)))
        assertNull(src.check(SourceRef(2, 6, 7)))
    }

    @Test
    fun `来源跨章_越界_倒挂_三种都拒`() {
        val src = valueOf(NovelSource.ofText(File("书.txt"), novel))
        val cross = src.check(SourceRef(1, 2, 6))
        assertNotNull("跨章要拒", cross)
        assertTrue("要指出跨章：$cross", cross!!.contains("跨章"))
        val outside = src.check(SourceRef(2, 6, 99))
        assertNotNull("越界要拒", outside)
        assertTrue("要指出越界：$outside", outside!!.contains("越界"))
        val inverted = src.check(SourceRef(1, 4, 2))
        assertNotNull("倒挂要拒", inverted)
    }

    @Test
    fun `抽一段原文_带行号原样`() {
        val src = valueOf(NovelSource.ofText(File("书.txt"), novel))
        val lines = src.excerpt(2, 3)
        assertEquals(2, lines.size)
        assertEquals("L2｜沈砚坐在窗边，一句话没说。", lines[0])
        assertEquals("L3｜林夏推门进来，把伞靠在桌边。", lines[1])
    }

    // ── ⑤ 大纲：场 id 与形状 ─────────────────────────────────────────────

    @Test
    fun `大纲场次_id缺省补S01_空或重复都拒`() {
        val ok = ScriptOutlineRules.parseScenes(
            JSONArray().put(JSONObject().put("summary", "第一场")).put(JSONObject().put("summary", "第二场")),
        )
        val scenes = valueOf(ok)
        assertEquals(listOf("S01", "S02"), scenes.map { it.id })

        assertTrue(ScriptOutlineRules.parseScenes(JSONArray()) is Parsed.Bad)
        val dup = ScriptOutlineRules.parseScenes(
            JSONArray().put(JSONObject().put("id", "S01").put("summary", "甲"))
                .put(JSONObject().put("id", "S01").put("summary", "乙")),
        )
        assertTrue("重复 id 要拒", dup is Parsed.Bad)
    }

    @Test
    fun `改编没选档位_大纲对账直接拒且不给默认值`() {
        val project = ScriptProject("片", ScriptMode.ADAPT, tier = null, sourceRel = "书.txt", createdMs = 0L)
        val reason = ScriptOutlineRules.check(
            project,
            listOf(OutlineScene("S01", "开场", SourceRef(1, 2, 4))),
            valueOf(NovelSource.ofText(File("书.txt"), novel)),
        )
        assertNotNull(reason)
        assertTrue("必须说清三档而不是默认一档：$reason", reason!!.contains("大胆再创作"))
        assertNull("档位不认识就是 null（不兜底）", ScriptTier.ofKey(""))
    }

    @Test
    fun `工程位置只许剧本下_越界写法全拒`() {
        val ws = File("ws")
        // 这三种是模型最可能写出来的逃逸形状：`..`、绝对路径、`剧本/../x`
        assertTrue(ScriptStore.dirOf(ws, "../逃逸") is Parsed.Bad)
        assertTrue(ScriptStore.dirOf(ws, "/绝对/路径") is Parsed.Bad)
        assertTrue(ScriptStore.dirOf(ws, "剧本/../逃逸") is Parsed.Bad)
        assertEquals(
            File(ws, "剧本/雨夜").path,
            valueOf(ScriptStore.dirOf(ws, "剧本/雨夜")).path,
        )
        assertEquals(File(ws, "剧本/雨夜").path, valueOf(ScriptStore.dirOf(ws, "雨夜")).path)
    }

    @Test
    fun `原创工程标了原文来源_拒`() {
        val project = ScriptProject("片", ScriptMode.ORIGINAL, tier = null, sourceRel = null, createdMs = 0L)
        val reason = ScriptOutlineRules.check(
            project,
            listOf(OutlineScene("S01", "开场", SourceRef(1, 2, 4))),
            null,
        )
        assertNotNull(reason)
        assertTrue("要点名 mode=original：$reason", reason!!.contains("original"))
    }

    // ── ⑥ 中文档：排版记号 ───────────────────────────────────────────────

    @Test
    fun `中文档_场头人物对白转场四个记号都在`() {
        val text = ChineseScriptFormat.render(
            "雨夜",
            listOf(
                scene(
                    1,
                    beats = listOf(
                        Beat(BeatKind.ACTION, "沈砚坐在窗边。", null, null, null),
                        Beat(BeatKind.DIALOGUE, "你来晚了。", "林夏", "低声", null),
                        Beat(BeatKind.TRANSITION, "巷口", null, null, null),
                    ),
                    source = SourceRef(1, 2, 4),
                ),
            ),
            ScriptMode.ADAPT,
            ScriptTier.REWORK,
        )
        assertTrue(text.startsWith("《雨夜》"))
        assertTrue(text.contains("改编 · rework（适度重构）"))
        assertTrue(text.contains("第1场　内景 咖啡馆 夜"))
        assertTrue(text.contains("人物：沈砚、林夏"))
        assertTrue(text.contains("林夏（低声）：你来晚了。"))
        assertTrue(text.contains("切至：巷口"))
    }

    @Test
    fun `溯源表_把场和章行连起来`() {
        val table = ChineseScriptFormat.traceTable(
            "雨夜",
            "书.txt",
            listOf(scene(1, beats = listOf(Beat(BeatKind.ACTION, "x", null, null, null)), source = SourceRef(1, 2, 4))),
            listOf(OutlineScene("S01", "开场", SourceRef(1, 2, 4)), OutlineScene("S02", "结尾", SourceRef(2, 6, 7))),
            listOf("S02"),
        )
        assertTrue(table.contains("| S01 | 内景 咖啡馆 夜 | 1 | L2–L4 | 1 | — |"))
        assertTrue("没写的场要明写", table.contains("S02"))
    }
}
