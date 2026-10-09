package com.example.zhengdao.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * hermes 安装脚本 gateway 补丁的**回归闸门**（ERRATA E-057，2026-10-09 真机）。
 *
 * 背景：脚本最后一步 `stage_gateway()` 要在系统里装 systemd 服务，proot 里没有 init ⇒
 * `hermes gateway install` 非零退出 ⇒ `fail "gateway installation failed"` ⇒ 整个脚本 exit 1
 * ⇒ 丹房显示「安装失败（退出码 1）」，**尽管 hermes 本体已装好、配置已完成**（真机截图：
 * `✓ Install complete!` 后面就这一条红叉）。补丁把那一句的失败尾巴换成只告警。
 *
 * 这里钉三件事：补丁真的只动那一处；幂等；锚点不在（上游改版）时**原样放过**而不是乱改。
 */
class HermesInstallScriptTest {

    /** 上游 44,739 B 版 `stage_gateway()` 的逐字节选（含别处同款 `|| fail`，用于验证只改一处）。 */
    private val upstream = """
        stage_venv() {
            [ -x "${'$'}{INSTALL_DIR}/.hermes/bin/hermes" ] || fail "venv missing"
        }

        stage_gateway() {
            if [ "${'$'}NON_INTERACTIVE" = true ]; then return 0; fi
            if ! has_terminal; then
                log "gateway setup skipped (no terminal); run 'hermes gateway install' after install"
                return 0
            fi
            # Setup installs the service when it handles the gateway; ask only if it did not.
            "${'$'}{INSTALL_DIR}/.hermes/bin/hermes" gateway install --if-missing </dev/tty || fail "gateway installation failed"
        }

        stage_complete() {
            git -C "${'$'}INSTALL_DIR" fetch origin || fail "git fetch failed"
        }
    """.trimIndent() + "\n"

    private fun patchOf(text: String) = HermesInstallScript.patch(text)

    @Test
    fun `gateway 那一句被换成只告警`() {
        val out = patchOf(upstream)
        assertFalse(
            "补丁后不该再有那句 exit 1 的 fail：$out",
            out.contains(HermesInstallScript.GATEWAY_FAIL_TAIL),
        )
        assertTrue(
            "补丁后应当是 log_warn（set -e 下整体退出 0）",
            out.contains(HermesInstallScript.GATEWAY_WARN_TAIL),
        )
        // 语义：那一行必须以 `|| log_warn` 收尾，而不是 `|| fail`
        val line = out.lineSequence().first { it.contains("gateway install --if-missing") }
        assertTrue("gateway 那一行：$line", line.trimEnd().endsWith(HermesInstallScript.GATEWAY_WARN_TAIL))
    }

    @Test
    fun `别处的 fail 一个都不动`() {
        val out = patchOf(upstream)
        assertTrue(out.contains("""|| fail "venv missing""""))
        assertTrue(out.contains("""|| fail "git fetch failed""""))
        assertEquals(
            "除 gateway 那一处外，正文必须逐字不变",
            upstream.replace(HermesInstallScript.GATEWAY_FAIL_TAIL, ""),
            out.replace(HermesInstallScript.GATEWAY_WARN_TAIL, ""),
        )
    }

    @Test
    fun `重复打补丁是幂等的`() {
        val once = patchOf(upstream)
        assertEquals(once, patchOf(once))
    }

    @Test
    fun `找不到锚点就原样放过`() {
        val rewritten = upstream.replace("gateway", "gate-way") // 模拟上游改版
        assertEquals(rewritten, patchOf(rewritten))
        assertTrue(patchOf("").isEmpty())
    }

    @Test
    fun `落盘只在需要时写，并如实报告四种结局`() {
        val dir = Files.createTempDirectory("zd-hermes-script").toFile()
        try {
            val script = File(dir, "hermes-install.sh")
            assertEquals(
                "文件不在时要说 NO_FILE，而不是抛异常",
                HermesInstallScript.Outcome.NO_FILE,
                HermesInstallScript.ensurePatched(script),
            )

            script.writeText(upstream)
            val stamp = script.lastModified()
            assertEquals(HermesInstallScript.Outcome.PATCHED, HermesInstallScript.ensurePatched(script))
            val patched = script.readText()
            assertFalse(patched.contains(HermesInstallScript.GATEWAY_FAIL_TAIL))

            assertEquals(
                "第二次必须是 ALREADY（幂等：内容不变就不写）",
                HermesInstallScript.Outcome.ALREADY,
                HermesInstallScript.ensurePatched(script),
            )
            assertEquals(patched, script.readText())

            script.writeText(upstream.replace("gateway", "gate-way"))
            assertEquals(
                "锚点不在 ⇒ ANCHOR_MISSING，内容一个字都不改",
                HermesInstallScript.Outcome.ANCHOR_MISSING,
                HermesInstallScript.ensurePatched(script),
            )
            assertEquals(upstream.replace("gateway", "gate-way"), script.readText())
            assertTrue("落盘后 mtime 不该倒退", script.lastModified() >= stamp)
        } finally {
            dir.deleteRecursively()
        }
    }
}
