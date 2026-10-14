// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.oc

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第⑭刀 #1 门禁：**坏 JSON 帧不影响后续帧**。
 *
 * 背景：`handle` 里的 JSON 解析（如 reduceMessageUpdated 的 `JSONObject(raw)`）原先没有
 * try/catch，一条坏帧的异常冲出 collect ⇒ SSE 协程死亡（进程级 scope 且无
 * CoroutineExceptionHandler 时 = FATAL，SSE 从此永久停）。
 * 现在 collect 每帧经 [OcRepository.handleFrameSafely] 兜底：坏帧跳过、留痕，好帧照常生效。
 */
class OcRepositoryFrameTest {

    private fun newRepo(): OcRepository {
        val client = OcClient({ null })
        return OcRepository(client, SseClient(client.sseClient))
    }

    @Test
    fun `坏 JSON 帧不抛、不进状态，后续好帧照常生效`() {
        val repo = newRepo()

        // 坏帧：不得抛出（抛出 = 原缺陷复现）
        repo.handleFrameSafely(SseClient.Event.MessageUpdated("{not-json"))
        repo.handleFrameSafely(SseClient.Event.MessageUpdated(""))
        repo.handleFrameSafely(SseClient.Event.PartUpdated("]] broken", null))
        assertTrue(repo.state.value.messages.isEmpty())

        // 后续好帧照常归约进 state —— 「单帧失败不带走整条流」
        repo.handleFrameSafely(
            SseClient.Event.MessageUpdated("""{"info":{"id":"m1","type":"user","text":"hi"}}""")
        )
        val msgs = repo.state.value.messages
        assertEquals(1, msgs.size)
        assertEquals("m1", msgs.single().id)
    }

    @Test
    fun `坏帧之后同一消息的好帧覆盖生效（delta 流式场景）`() {
        val repo = newRepo()
        // 流式过程中夹一条坏帧
        repo.handleFrameSafely(
            SseClient.Event.PartUpdated("""{"part":{"id":"p1","type":"text","text":"你"}}""", null)
        )
        repo.handleFrameSafely(SseClient.Event.PartUpdated("<<<corrupt>>>", null))
        // 坏帧后的合法帧继续追加
        repo.handleFrameSafely(
            SseClient.Event.PartUpdated("""{"part":{"id":"p1","type":"text","delta":"好"}}""", null)
        )
        val part = repo.state.value.parts["p1"]
        assertTrue(part is OcPart.Text)
        assertEquals("你好", (part as OcPart.Text).text)
    }
}
