package com.example.zhengdao.terminal

import com.example.zhengdao.rootfs.GithubMirror
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端 git 加速/加固（2026-10-11）：`.gitconfig` 合并规则与 `/usr/local/bin/git` 包装器正文。
 *
 * 这些断言锁的都是"会静默出错"的点：幂等（不能每次开终端都重写）、旧镜像段清理、**push 不走镜像**、
 * 用户自己的配置不被吃掉、包装器里不出现 `sslVerify=false` 这类"拿安全换速度"的写法。
 */
class GitAccelTest {

    private fun tempDirs(): Pair<File, File> {
        val root = Files.createTempDirectory("gitaccel-root").toFile()
        val home = Files.createTempDirectory("gitaccel-home").toFile()
        return root to home
    }

    // ── .gitconfig 合并 ────────────────────────────────────────────────────────

    @Test
    fun `空配置得到托管段，且只有加固键、没有 insteadOf`() {
        val merged = GitAccel.mergeConfig(null)
        assertNotNull(merged)
        val text = merged!!
        assertTrue(text.startsWith(GitAccel.BEGIN))
        assertTrue(text.trimEnd().endsWith(GitAccel.END))
        assertTrue(text.contains("lowSpeedLimit = 1000"))
        assertTrue(text.contains("version = HTTP/1.1"))
        assertTrue(text.contains("fsckObjects = true"))
        // **核心加固点**：镜像不再写进 gitconfig ⇒ push 不可能被改道。
        // 注意只判"生效的键"（非注释行）——托管段的说明文字里会出现 insteadOf 这个词。
        val activeInsteadOf = text.lines().any {
            !it.trimStart().startsWith("#") && it.contains("insteadOf")
        }
        assertFalse("gitconfig 里不允许出现生效的 insteadOf", activeInsteadOf)
    }

    @Test
    fun `合并是幂等的：同样内容第二次返回 null`() {
        val once = GitAccel.mergeConfig(null)!!
        assertNull(GitAccel.mergeConfig(once))
    }

    @Test
    fun `清掉旧版写的 gh-proxy 镜像段`() {
        val legacy = "[url \"https://gh-proxy.com/https://github.com/\"]\n" +
            "\tinsteadOf = https://github.com/\n"
        val merged = GitAccel.mergeConfig(legacy)!!
        assertFalse(merged.contains("gh-proxy.com/https://github.com/"))
        assertTrue(merged.contains(GitAccel.BEGIN))
    }

    @Test
    fun `清掉旧镜像段但保留用户自己的配置与镜像`() {
        val existing = buildString {
            append("[user]\n\tname = 老张\n")
            append("[url \"https://gh-proxy.com/https://github.com/\"]\n\tinsteadOf = https://github.com/\n")
            // 用户自己的镜像（非已知源）必须原样保留
            append("[url \"https://my-own-mirror.example/\"]\n\tinsteadOf = https://github.com/\n")
            append("[core]\n\teditor = vim\n")
        }
        val merged = GitAccel.mergeConfig(existing)!!
        assertTrue(merged.contains("name = 老张"))
        assertTrue(merged.contains("editor = vim"))
        assertTrue(merged.contains("https://my-own-mirror.example/"))
        assertFalse(merged.contains("gh-proxy.com/https://github.com/"))
        assertEquals(1, Regex(Regex.escape(GitAccel.BEGIN)).findAll(merged).count())
    }

    @Test
    fun `重复合并不会堆积托管段`() {
        val once = GitAccel.mergeConfig("[user]\n\tname = a\n")!!
        val twice = GitAccel.mergeConfig(once)
        assertNull(twice)
    }

    // ── 包装器正文 ─────────────────────────────────────────────────────────────

    @Test
    fun `包装器带自识别标记，且占位符已全部还原成美元号`() {
        val sh = GitAccel.wrapperScript()
        assertTrue(sh.startsWith("#!/bin/sh"))
        assertTrue(sh.contains(GitAccel.WRAPPER_MARK))
        assertFalse("正文里不应残留占位符 §", sh.contains('§'))
    }

    @Test
    fun `只有取数类子命令走镜像，push 等写操作直接 exec 真实 git`() {
        val sh = GitAccel.wrapperScript()
        // 白名单里必须有这些取数命令
        for (cmd in listOf("clone", "fetch", "pull", "ls-remote", "submodule", "archive")) {
            assertTrue("白名单缺少 $cmd", sh.contains(cmd))
        }
        // push / tag / commit 不在白名单 —— 它们落到 `*) exec "$REAL" "$@"`
        assertFalse(sh.contains("clone|fetch|pull|push"))
        assertTrue(sh.contains("""*) exec "${'$'}REAL" "${'$'}@""""))
    }

    @Test
    fun `镜像注入是每次调用内的环境变量，不落盘`() {
        val sh = GitAccel.wrapperScript()
        assertTrue(sh.contains("GIT_CONFIG_COUNT=1"))
        assertTrue(sh.contains("""GIT_CONFIG_KEY_0="url.${'$'}{src}https://github.com/.insteadOf""""))
        // 绝不能出现"关掉 TLS 校验"那种换速度的写法
        assertFalse(sh.contains("sslVerify"))
        assertFalse(sh.contains("GIT_SSL_NO_VERIFY"))
    }

    @Test
    fun `包装器里的镜像链与 GithubMirror 同源`() {
        val sh = GitAccel.wrapperScript()
        for (m in GithubMirror.GIT_CHAIN) {
            assertTrue("包装器缺少镜像 $m", sh.contains(m))
        }
        // 下载最快但不支持 git 协议的源不许出现在 git 链里
        assertFalse(sh.contains("ghfast.top"))
    }

    @Test
    fun `提供直连与绕过开关`() {
        val sh = GitAccel.wrapperScript()
        assertTrue(sh.contains("ZZ_GIT_DIRECT"))
        assertTrue(sh.contains("ZZ_NO_GIT_WRAPPER"))
    }

    @Test
    fun `clone 失败且目标目录非空时停止换源（不覆盖已有内容）`() {
        val sh = GitAccel.wrapperScript()
        assertTrue(sh.contains("""[ "${'$'}CMD" = clone ]"""))
        assertTrue(sh.contains("""ls -A "${'$'}d""""))
        assertTrue(sh.contains("不再换源"))
    }

    @Test
    fun `子命令取第一个非选项参数，选项的值不会把子命令认错`() {
        // `git clone --branch main URL` 的 CMD 必须是 clone（全局选项按 git 规矩排在子命令前）
        val sh = GitAccel.wrapperScript()
        assertTrue(sh.contains("""if [ -z "${'$'}CMD" ]; then CMD="${'$'}arg"; else EXTRA="${'$'}EXTRA ${'$'}arg"; fi"""))
    }

    // ── 落盘 ───────────────────────────────────────────────────────────────────

    @Test
    fun `apply 写出 gitconfig 与可执行包装器，第二次调用不再重复写 gitconfig`() {
        val (root, home) = tempDirs()
        val first = GitAccel.apply(root, home)
        assertTrue(first.any { it.contains(".gitconfig") })
        assertTrue(first.any { it.contains("包装器") })

        val gitconfig = File(home, ".gitconfig")
        assertTrue(gitconfig.isFile)
        assertTrue(gitconfig.readText().contains(GitAccel.BEGIN))

        val wrapper = File(root, GitAccel.WRAPPER_REL_PATH)
        assertTrue(wrapper.isFile)
        assertEquals(GitAccel.wrapperScript(), wrapper.readText())

        val second = GitAccel.apply(root, home)
        assertFalse(
            "第二次不应再动 .gitconfig（幂等）",
            second.any { it.startsWith("git 配置") || it.startsWith("git 加固配置") },
        )
    }

    @Test
    fun `已有用户自己的 git 可执行文件时拒绝覆盖`() {
        val (root, home) = tempDirs()
        val wrapper = File(root, GitAccel.WRAPPER_REL_PATH)
        wrapper.parentFile!!.mkdirs()
        wrapper.writeText("#!/bin/sh\necho 我是用户自己装的\n")
        val actions = GitAccel.apply(root, home)
        assertTrue(actions.any { it.contains("拒绝覆盖") })
        assertTrue(wrapper.readText().contains("我是用户自己装的"))
    }
}
