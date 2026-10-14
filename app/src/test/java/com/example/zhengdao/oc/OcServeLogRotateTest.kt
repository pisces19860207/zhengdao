// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.oc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 第⑭刀 #4：serve.log 轮转（[OcManager.rotateServeLog]）单测。
 *
 * 背景：serve.log 里含 serve 打进 stdout 的明文口令，此前只 append、永不轮转。
 * 现在启动新 serve 之前超限轮转：`serve.log → serve.log.1`，旧的 `.1` 删除
 * （保留「当前 + 上一份」两份）。
 */
class OcServeLogRotateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(file: File, sizeBytes: Int) {
        file.writeText("x".repeat(sizeBytes))
    }

    @Test
    fun `未超限 —— 不轮转`() {
        val log = tmp.newFile("serve.log")
        write(log, 100)
        assertFalse(OcManager.rotateServeLog(log, maxBytes = 1000))
        assertTrue(log.isFile)
        assertFalse(File(tmp.root, "serve.log.1").exists())
    }

    @Test
    fun `文件不存在 —— 返回 false 不抛`() {
        assertFalse(OcManager.rotateServeLog(File(tmp.root, "absent.log"), maxBytes = 10))
    }

    @Test
    fun `超限 —— 轮转为 serve点log点1，原位清空`() {
        val log = tmp.newFile("serve.log")
        write(log, 1001)
        assertTrue(OcManager.rotateServeLog(log, maxBytes = 1000))
        assertFalse(log.exists())
        assertEquals(1001L, File(tmp.root, "serve.log.1").length())
    }

    @Test
    fun `连续两轮 —— 旧 serve点log点1 被顶掉，只留两份`() {
        val log = tmp.newFile("serve.log")
        write(log, 1001)
        assertTrue(OcManager.rotateServeLog(log, maxBytes = 1000))
        // 新一场 serve 又写满并再触发轮转
        write(log, 1002)
        assertTrue(OcManager.rotateServeLog(log, maxBytes = 1000))
        assertEquals(1002L, File(tmp.root, "serve.log.1").length())
        assertFalse(log.exists())
        // .2 永远不存在（只保留两份：当前 + .1）
        assertFalse(File(tmp.root, "serve.log.2").exists())
    }
}
