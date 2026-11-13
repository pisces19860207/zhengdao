// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.voice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 判据⑤：产物/缓存/日志里没有音频文件（源码断言 ＋ 文件清单）。
 * 源码判据一律读**剥掉注释**的真代码：注释里解释「不上传、不租约」必然出现反例词，
 * 不剥就会自咬（PermissionEntryGateTest 记着同一个坑）。
 */
class VoicePrivacyGateTest {

    private fun repoFile(rel: String): File = File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun sourceRoot(): File = listOf(File("src/main/java"), File("app/src/main/java"))
        .firstOrNull { it.isDirectory }
        ?: throw AssertionError("找不到主源码树（门禁不许空转）")

    /** 语音包全部 .kt ＋ ui/baopu 下本单新增的 Voice* 与生产装配。 */
    private fun sources(): Map<String, String> {
        val base = File(sourceRoot(), "com/example/zhengdao")
        val core = File(base, "baopu/voice").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }.toList()
        val ui = File(base, "ui/baopu").listFiles().orEmpty().filter {
            it.isFile && it.name.endsWith(".kt") &&
                (it.name.startsWith("Voice") || it.name == "AndroidSpeechEngine.kt")
        }
        val files = core + ui
        assertTrue("一个语音源码都没读到（门禁会空转）", files.isNotEmpty())
        return files.associate { it.name to strip(it.readText()) }
    }

    /** 剥掉行注释与块注释（KDoc 也算）。 */
    private fun strip(src: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
            } else if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                i += 2
                while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                i = (i + 2).coerceAtMost(src.length)
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** 音频落盘 / 上传 / 租约 / 自动发送的形状；命中一个即红。 */
    private fun offenders(sources: Map<String, String>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        sources.forEach { (name, src) ->
            FORBIDDEN.forEach { tok -> if (src.contains(tok)) out += name to tok }
        }
        return out
    }

    @Test
    fun `判据5_语音源码里没有落盘上传租约与自动发送`() {
        val bad = offenders(sources())
        assertEquals("语音源码里出现禁止形状：$bad", 0, bad.size)
    }

    @Test
    fun `判据5_负例_塞进落盘上传租约或发送立刻红`() {
        val fake = mapOf(
            "BadAudio.kt" to "val f = File(dir, \"a.wav\")",
            "BadUpload.kt" to "client.upload(bytes)",
            "BadLease.kt" to "val l = DeviceLease.acquire()",
            "BadSend.kt" to "onSend(text, attachments)",
            "BadNetwork.kt" to "val u = \"https://example.com/asr\"",
        )
        assertEquals("每种塞入的形状都要被抓到", fake.size, offenders(fake).size)
        assertTrue("干净源码不该被误伤", offenders(mapOf("Clean.kt" to "val x = 1")).isEmpty())
    }

    @Test
    fun `启动前先查系统ASR_且请求离线识别_音频只进内存缓冲`() {
        val engine = sources()["AndroidSpeechEngine.kt"]
            ?: throw AssertionError("生产装配文件没读到")
        assertTrue("启动前必须 SpeechRecognizer.isRecognitionAvailable", engine.contains("SpeechRecognizer.isRecognitionAvailable("))
        assertTrue("必须请求离线识别（音频不离开设备）", engine.contains("RecognizerIntent.EXTRA_PREFER_OFFLINE"))
        assertTrue("系统回调的 PCM 只写内存", engine.contains("audio.write(buffer)"))
        assertTrue("会话结束缓冲清零", engine.contains("audio.clear()"))
        assertTrue("onBufferReceived 是音频唯一入口", engine.contains("onBufferReceived("))
    }

    @Test
    fun `日志不拼进音频或文本_失败提示只许是元数据`() {
        val all = sources().values.joinToString("\n")
        val calls = Regex("RunLog\\.log\\([^)]*\\)").findAll(all).map { it.value }.toList()
        assertTrue("至少要有一条写账失败的提示（否则日志断言是空转）", calls.isNotEmpty())
        for (call in calls) {
            assertFalse("日志里不许拼进音频/识别文本：$call", logLeaks(call))
        }
        // 判别器自证：塞一段音频/文本进日志必须被抓到，免得这条恒绿
        assertTrue(logLeaks("RunLog.log(\"pcm chunk saved\")"))
        assertTrue(logLeaks("RunLog.log(\"recognized text here\")"))
        assertFalse(logLeaks("RunLog.log(\"语音输入：账没落成（已如实记日志）\")"))
    }

    /** 日志里只许有「时长/失败」这类元数据，不许有音频、缓冲或识别文本。 */
    private fun logLeaks(call: String): Boolean = LOG_BANNED.any { call.contains(it) }

    @Test
    fun `判据5_产物缓存与源码目录的音频文件清单为空`() {
        val found = mutableListOf<String>()
        for (rel in SCAN_ROOTS) {
            val dir = existingDir(rel) ?: continue
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                if (AUDIO_EXT.any { f.name.endsWith(it, ignoreCase = true) }) found += f.name
            }
        }
        assertEquals("音频文件清单（应为空）：$found", 0, found.size)
    }

    private fun existingDir(rel: String): File? {
        val a = File(rel)
        if (a.isDirectory) return a
        return File(rel.removePrefix("app/")).takeIf { it.isDirectory }
    }

    companion object {
        val FORBIDDEN = listOf(
            ".wav", ".pcm", ".mp3", ".m4a", ".aac", ".ogg", ".amr", ".3gp",
            "MediaRecorder", "FileOutputStream", "writeBytes(", "cacheDir", "externalCacheDir",
            "OkHttp", "HttpURLConnection", "http://", "https://", "upload", "Lease", "租约",
            "onSend(", "controller.send", "requestPermissions(",
        )
        val AUDIO_EXT = listOf(".wav", ".pcm", ".mp3", ".m4a", ".aac", ".ogg", ".amr", ".3gp")
        val LOG_BANNED = listOf(
            "audio", "buffer", "pcm", "chunk", "recognized", "partial", "bytes", ".wav", ".mp3",
        )
        val SCAN_ROOTS = listOf(
            "app/build",
            "app/src/main/assets",
            "app/src/main/res/raw",
            "app/src/main/java/com/example/zhengdao/baopu/voice",
            "app/src/main/java/com/example/zhengdao/ui/baopu",
        )
    }
}
