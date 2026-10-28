// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 259 批 C 段 #14：ed25519 的常量构造**不许 panic**。
//
// 缺陷原文（`_zd-private/docs/派工单-2026-10-28-第二百五十九批-审计缺陷批次A.md` #14，
// 对账 `全仓隐患审计-2026-10-28.md` A-P2 族）：`rust/core/src/ed25519.rs` 里
// `BigUint::parse_bytes(...).expect("…常量字面量非法")` 三处（L / 基点 X / 基点 Y）。
// 改前表现：一旦有人把某条字面量改坏（多位、少位、混进非十进制字符），`ctx()` 直接在
// **JNI 调用栈上 panic**——本模块自己写的契约是「任何参数非法或验签不过都返回 `false`——
// **绝不 panic 跨 FFI**（规范 #1）」，而 panic 跨 FFI 是「整个进程没了」，比"验签不过"重得多。
//
// 为什么这条用**源码门禁**而不是跑 `cargo test`：本机没有 MSVC 链接器（`cargo test` 停在
// getrandom/zstd-safe/crc32fast 这些第三方 build script 的 link 阶段，与本改动无关，基线同样跑不了），
// 所以 Rust 那半条只能由本门禁在 Android 门禁里钉住；`.rs` 里同时补了 `#[cfg(test)]` 用例，
// 给有 MSVC 工具链的机器（以及 CI）跑。
package com.example.zhengdao.rust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Ed25519NoPanicGateTest {

    /** 仓库根（单测 cwd = `app/`，抄 R8KeepRuleGuardTest 的口径）。 */
    private fun repoFile(rel: String): File = File("../$rel").let { if (it.exists()) it else File(rel) }

    private val ed25519: String by lazy {
        val f = repoFile("rust/core/src/ed25519.rs")
        assertTrue("找不到 rust/core/src/ed25519.rs（cwd=${File("").absolutePath}）", f.isFile)
        f.readText()
    }

    /** 生产区（`#[cfg(test)]` 之前）——测试区里的 `.expect` 是抄测试向量用的，不算缺陷。 */
    private val prod: String get() = ed25519.substringBefore("#[cfg(test)]")

    @Test
    fun `常量构造返回 option 不 panic`() {
        assertTrue("build_ctx 必须存在且返回 Option<Ctx>", prod.contains("fn build_ctx() -> Option<Ctx>"))
        assertTrue("ctx() 必须返回 Option（改前是 &'static Ctx，坏常量只能 panic）",
            prod.contains("fn ctx() -> Option<&'static Ctx>"))
        assertTrue("字面量解析要有可判的失败口（dec）", prod.contains("fn dec(bytes: &[u8]) -> Option<BigUint>"))
        assertTrue("缓存换成 OnceLock<Option<Ctx>>", prod.contains("static CTX: OnceLock<Option<Ctx>> = OnceLock::new();"))
    }

    @Test
    fun `生产区一条 expect 都不许留`() {
        // 改前这里有 3 处 .expect("…常量字面量非法")。不许「加一行日志再 expect」蒙过去：
        // panic 跨 FFI 才是缺陷本体，日志改不了它。
        assertFalse("生产区不许出现 .expect(", prod.contains(".expect("))
        assertFalse("生产区不许出现 unwrap(", prod.contains(".unwrap("))
        assertFalse("生产区不许出现 panic!(", prod.contains("panic!("))
        assertFalse("不许留着 expect 的文案（说明还有 expect）", prod.contains("常量字面量非法"))
    }

    @Test
    fun `点运算把失败交给调用方`() {
        assertTrue("pt_add 失败要交出去", prod.contains("fn pt_add(a: &Point, b: &Point) -> Option<Point>"))
        assertTrue("pt_mul 失败要交出去", prod.contains("fn pt_mul(scalar: &BigUint, point: &Point) -> Option<Point>"))
        // pt_equal 的对外契约仍是 bool：ctx 没了只能算「不相等」，不许改成 Option 逼调用方 unwrap
        assertTrue("pt_equal 仍返回 bool", prod.contains("fn pt_equal(a: &Point, b: &Point) -> bool"))
        assertTrue("pt_equal 对 None 判 false", prod.contains("None => false"))
        assertTrue("verify 的签名一字未改（跨 FFI 的口子）",
            prod.contains("pub fn verify(pub_key: &[u8], sig: &[u8], msg: &[u8]) -> bool"))
    }

    @Test
    fun `失败一律落到返回 false 的分支不是删掉分支`() {
        // §3 负例：不许把错误分支删了凑通过。每条 Option 消费点都得有 `None => return false`。
        val n = Regex("None => return false").findAll(prod).toList().size
        assertTrue("None => return false 至少 6 处，实测 $n 处", n >= 6)
        assertTrue("构造失败也要走同一条出口", prod.contains("let c = match ctx() {"))
    }

    @Test
    fun `信任根的三条常量数字一个字节没动`() {
        // 这条门禁防的是「为了不 panic 顺手把常量改了」——那会把验签从「炸」变成「悄悄全通过/全拒」。
        val l = "27742317777372353535851937790883648493"
        val bx = "15112221349535400772501151409588531511454012693041857206046113283949847762202"
        val by = "46316835694926478169428394003475163141307993866256225615783033603165251855960"
        listOf(l, bx, by).forEach { digits ->
            assertTrue("常量数字串缺失：…${digits.take(12)}…", prod.contains("b\"$digits\""))
            assertEquals("每条字面量只许出现一次", 1, prod.split("b\"$digits\"").size - 1)
        }
        assertTrue("契约那句 KDoc 不许被删", prod.contains("绝不 panic 跨 FFI"))
    }

    @Test
    fun `rs 里配套的单测在位`() {
        // 有 MSVC 的机器 / CI 上跑的那条：坏字面量 ⇒ None，且 verify 不 panic。
        assertTrue(ed25519.contains("fn 常量构造走option_坏字面量返回none()"))
        assertTrue(ed25519.contains("super::build_ctx().is_some()"))
        assertTrue(ed25519.contains("super::dec(b\"2774231777737235353585193779088364849x\").is_none()"))
        assertTrue(ed25519.contains("std::panic::catch_unwind"))
    }
}
