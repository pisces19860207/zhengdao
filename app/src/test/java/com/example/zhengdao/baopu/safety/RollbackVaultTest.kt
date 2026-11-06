// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 504 判据④的 JVM 钉子：删 5 个文件 ⇒ 回滚 ⇒ 5 个都回来且 hash 一致；
 * 回滚后自校验落账（outcome 里的数字）；**回滚自身可回滚**。
 * 顺带钉：覆盖写恢复、新建删回、>10MB 的如实"不可字节回滚"。
 */
class RollbackVaultTest {

    private fun tmp(name: String): File = Files.createTempDirectory("safety-vault-$name").toFile()

    private fun vault(dir: File) = RollbackVault.also { it.install(dir) }

    @Test
    fun `删5个文件_回滚_5个都回来且hash一致`() {
        val dir = tmp("del5")
        val ws = File(dir, "ws").apply { mkdirs() }
        val files = (1..5).map { n ->
            val f = File(ws, "note-$n.txt")
            f.writeText("第 $n 号文件的内容——原始字节\n")
            f
        }
        val originals = files.associateWith { RollbackRules.sha256Of(it) }
        vault(dir)

        // 动作前入库（write 类动作前的原件都在保险箱）
        val stashed = RollbackVault.stash("op-del", "delete_path", files.map { it.absolutePath }, "s1", "r1", 1000L)
        assertEquals(5, stashed.size)
        assertTrue(stashed.all { it.kind == StashKind.BACKUP })

        // 动作本身：全删了
        files.forEach { it.delete() }
        assertTrue(files.none { it.exists() })

        // 一键回滚（按单笔）：5 个都回来、hash 一致
        val o = RollbackVault.rollback(RollbackScope.ByOp("op-del"), 2000L)
        assertEquals(5, o.restored)
        assertEquals(5, o.hashOk)
        assertEquals(0, o.hashMismatch)
        assertEquals(0, o.failed)
        files.forEach { assertTrue(it.exists()) }
        files.forEach { assertEquals(originals[it], RollbackRules.sha256Of(it)) }
        // 自校验的账就在收成里（落账由装配层把 outcome.summaryLine 写进起居注）
        assertTrue(o.summaryLine.contains("hash 对 5"))
    }

    @Test
    fun `回滚自身可回滚_再回滚一次回到删完的样子`() {
        val dir = tmp("undo")
        val ws = File(dir, "ws").apply { mkdirs() }
        val f = File(ws, "undo.txt").apply { writeText("undo-me\n") }
        vault(dir)
        RollbackVault.stash("op-u", "delete_path", listOf(f.absolutePath), "s1", "r1", 1000L)
        f.delete()
        val first = RollbackVault.rollback(RollbackScope.ByOp("op-u"), 2000L)
        assertTrue(f.exists()) // 回滚＝文件回来了
        val before = f.readText()

        // 对"回滚单"再做一次回滚＝撤销回滚：文件回到删完的状态
        val second = RollbackVault.rollback(RollbackScope.ByOp(first.rollbackOpId), 3000L)
        assertFalse("撤销回滚后文件应回到不存在", f.exists())
        assertEquals(1, second.deleted)
    }

    @Test
    fun `覆盖写_回滚_恢复原字节_新建_回滚_删回`() {
        val dir = tmp("over")
        val ws = File(dir, "ws").apply { mkdirs() }
        val over = File(ws, "over.txt").apply { writeText("原稿\n") }
        val created = File(ws, "new.txt")
        vault(dir)
        RollbackVault.stash("op-w", "write_file", listOf(over.absolutePath, created.absolutePath), "s1", "r1", 1000L)
        // write_file 覆盖 + 新建
        over.writeText("改得面目全非\n")
        created.writeText("无中生有\n")

        val o = RollbackVault.rollback(RollbackScope.BySession("s1"), 2000L)
        assertEquals(1, o.restored)
        assertEquals(1, o.hashOk)
        assertEquals("原稿\n", over.readText()) // 覆盖写被还原
        assertFalse(created.exists()) // 新建被删回
    }

    @Test
    fun `大文件只记hash_回滚如实说不可字节还原`() {
        val dir = tmp("big")
        val ws = File(dir, "ws").apply { mkdirs() }
        val big = File(ws, "big.bin")
        val payload = ByteArray(RollbackRules.BIG_FILE_BYTES.toInt() + 1) { (it % 251).toByte() }
        big.writeBytes(payload)
        val sha = RollbackRules.sha256Of(big)
        vault(dir)
        val stashed = RollbackVault.stash("op-b", "write_file", listOf(big.absolutePath), "s1", "r1", 1000L)
        assertEquals(StashKind.HASH_ONLY, stashed.single().kind)
        assertEquals(sha, stashed.single().sha256)
        big.writeBytes(ByteArray(16)) // 覆盖写坏了

        val o = RollbackVault.rollback(RollbackScope.ByOp("op-b"), 2000L)
        assertEquals(0, o.restored)
        assertEquals(1, o.failed)
        assertTrue(o.lines.single().contains("不可字节回滚"))
    }

    @Test
    fun `会话与任务两粒度各自收各自的`() {
        val dir = tmp("scope")
        val ws = File(dir, "ws").apply { mkdirs() }
        val a = File(ws, "a.txt").apply { writeText("A") }
        val b = File(ws, "b.txt").apply { writeText("B") }
        vault(dir)
        RollbackVault.stash("op-a", "delete_path", listOf(a.absolutePath), "sess-1", "task-1", 1000L)
        RollbackVault.stash("op-b", "delete_path", listOf(b.absolutePath), "sess-2", "task-1", 1001L)
        a.delete()
        b.delete()

        // 会话粒度：sess-1 那笔回来，sess-2 的不动（粒度互不越界）
        val bySession = RollbackVault.rollback(RollbackScope.BySession("sess-1"), 3000L)
        assertEquals(1, bySession.restored)
        assertTrue(a.exists())
        assertFalse("sess-2 的还在删着（会话粒度不越界）", b.exists())

        // 任务粒度：把 task-1 里剩下的那笔（sess-2）也收回来
        val byTask = RollbackVault.rollback(RollbackScope.ByTask("task-1"), 4000L)
        assertEquals(1, byTask.restored)
        assertTrue(b.exists())

        // 都回滚过了 ⇒ 再回滚是空转（restored=0，不重复记账）
        val again = RollbackVault.rollback(RollbackScope.BySession("sess-1"), 5000L)
        assertEquals(0, again.restored)
    }
}
