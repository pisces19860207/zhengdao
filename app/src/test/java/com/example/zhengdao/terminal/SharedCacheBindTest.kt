package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公共区包缓存挂载清单的**回归闸门**（ERRATA E-056，2026-10-09）。
 *
 * 背景：2.0.0 把 uv 的缓存也挂进了 `Download/证道/cache/uv`，而公共区在 guest 里是
 * **FUSE**（`/dev/fuse … fuse, noexec`）。真机实测 FUSE 上：
 *  - `ln -s` ⇒ `Permission denied`（uv 要在 `wheels-v6/pypi/<pkg>/<wheel>` 建软链指向
 *    `archive-v0/<hash>`）⇒ `failed to symlink file … (os error 13)`；
 *  - `flock` ⇒ `Function not implemented`（uv 要锁 `sdists-v9/editable/<hash>/.lock`）
 *    ⇒ `failed to lock … (os error 38)`。
 *
 * 两条都会让 `uv sync` 退出 1，而 hermes 的依赖安装正是 `uv sync` ⇒ 用户点了三次安装
 * 全是「失败（退出码 1）」。所以「uv 不许进这张表」值得用测试钉住 —— 这不是风格偏好，
 * 是一条踩过的文件系统约束。
 */
class SharedCacheBindTest {

    @Test
    fun `挂载清单就是 npm 与 pip 两条`() {
        assertEquals(
            listOf("npm" to ".npm", "pip" to ".cache/pip"),
            ProotLauncher.sharedCacheBindings(),
        )
    }

    @Test
    fun `uv 绝不在挂载清单里`() {
        val hits = ProotLauncher.sharedCacheBindings().filter { (kind, rel) ->
            kind.contains("uv", ignoreCase = true) || rel.contains("uv", ignoreCase = true)
        }
        assertTrue(
            "uv 的缓存一旦被挂到共享存储（FUSE），软链与 flock 都会失败：$hits",
            hits.isEmpty(),
        )
    }

    @Test
    fun `每条落点都是 home 内的相对路径且 kind 合法`() {
        for ((kind, rel) in ProotLauncher.sharedCacheBindings()) {
            assertTrue("kind 必须能安全拼进用户可见目录名: $kind", Store.isLegalKind(kind))
            assertFalse("rel 必须是相对路径（会被拼到 /root/ 之后）: $rel", rel.startsWith("/"))
            assertFalse("rel 不许出现 ..: $rel", rel.split('/').contains(".."))
            assertFalse("rel 不许为空: $kind", rel.isBlank())
        }
    }
}
