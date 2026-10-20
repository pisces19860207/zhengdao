// 第五十批 · 用例进包的一致性门禁。
//
// 派工单 §2.7：`eval/cases.json` + `eval/baseline.json` 走**构建期复制**进
// `assets/eval/`，仓库 `eval/` 是唯一真源；并要一条「防漂移」断言。
//
// 这里钉三件事：
//   ① 仓库里**不许**有手存的第二份（`app/src/main/assets/eval/` 不该存在）——
//      两份放在两个地方，早晚漂移，那时随包那份就成了假真源；
//   ② 用例与基线本身不许互相漂移（条数、id 对得上）；
//   ③ 构建脚本里那条复制任务还在（谁删了它，APK 里就没有用例 ⇒ 运行时自检永远「未跑」）。
package com.example.zhengdao.baopu.eval

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EvalAssetsTest {

    private fun repoRoot(): File {
        var d: File? = File(".").absoluteFile
        repeat(6) {
            if (d != null && File(d, "eval/cases.json").isFile) return d
            d = d?.parentFile
        }
        return File("..").absoluteFile
    }

    @Test
    fun `真源唯一_assets里不许手存第二份`() {
        val handMade = File("src/main/assets/eval")
        assertFalse(
            "assets/eval 只该由构建期生成（build/generated/…）；手工存一份就等于有了第二真源",
            handMade.exists(),
        )
    }

    @Test
    fun `用例与基线不漂移_条数与id对得上`() {
        val root = repoRoot()
        val cases = EvalCases.load(File(root, "eval/cases.json"))
        assertTrue("评测集不该是空的", cases.isNotEmpty())
        val ids = cases.map { it.id }.toSet()

        val baseline = JSONObject(File(root, "eval/baseline.json").readText(Charsets.UTF_8))
        val passing = baseline.optJSONArray("passing")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }.orEmpty()
        assertTrue("基线里一条都没记", passing.isNotEmpty())

        val unknown = passing.filterNot { it in ids }
        assertTrue("基线记了用例集里不存在的 id：$unknown", unknown.isEmpty())
        assertTrue(
            "每条用例都该有判据（没判据的用例验不了任何东西）",
            cases.all { it.asserts.isNotEmpty() },
        )
    }

    @Test
    fun `构建期复制任务还在_且只复制这两份`() {
        val kts = File("build.gradle.kts").readText(Charsets.UTF_8)
        assertTrue("构建脚本里必须有 stageEvalAssets 任务", kts.contains("StageEvalAssetsTask"))
        assertTrue(
            "复制源必须是仓库根 eval/（唯一真源）",
            kts.contains("""evalDir.set(rootProject.layout.projectDirectory.dir("eval"))"""),
        )
        assertTrue("用例文件必须进包", kts.contains("\"cases.json\""))
        assertTrue("基线文件必须进包", kts.contains("\"baseline.json\""))
        assertTrue(
            "生成的 assets 目录必须挂到每个变体上",
            kts.contains("stageEvalAssets"),
        )
    }

    @Test
    fun `随包路径口径_assets_eval_两个文件名`() {
        assertEquals("eval/cases.json", EvalAssets.CASES_PATH)
        assertEquals("eval/baseline.json", EvalAssets.BASELINE_PATH)
    }
}
