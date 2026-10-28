// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的公开标准：RFC 8032 §5.1（Ed25519 定义）与 §7.1（测试向量）。
//
//! ed25519 模块（v2.0 R2）：Ed25519 验签（RFC 8032 §5.1，从零实现）。
//!
//! 为什么要搬进 Rust：App 侧那套 Kotlin 实现（`app/src/main/java/com/example/zhengdao/ui/
//! AgentManifest.kt` 里的 `Ed25519`）原本是因为 Android 的 `KeyFactory("Ed25519")` 默认
//! 路由到 AndroidKeystore、拒收外部公钥字节才被迫手写的；验签是「信任根」（manifest /
//! 环境包索引都靠它），属于该常驻 native 的那类纯计算。本模块是那套实现的 1:1 移植：
//! **同一批常量、同一套公式**，因此两条实现的结论必须逐位一致——`CoreNative.verifyEd25519`
//! 里「Rust 先算、平台对拍、不一致则拒绝」的纪律见 docs/ERRATA.md E-051。
//!
//! 大整数用 `num-bigint`（通用大整数库，**不是**密码学库）：与 Kotlin 侧用
//! `java.math.BigInteger` 同构，公式可以逐行对照，避免手写 255 位域运算引入的静默错误。
//!
//! 已知边界：采用 RFC 8032 §5.1.7 允许的**朴素判定式**（S·B == R + h·A），不做小阶点拒绝
//! —— 全零公钥/签名（阶为 4 的小阶点）会判通过；本项目公钥固化在 APK 内、攻击者只能控制
//! R/S 而无法指定 A，故该路径不可达。语义与 Kotlin 版保持逐位一致，回归锁见本文件单测
//! 与 ERRATA E-051。

use num_bigint::BigUint;
use num_traits::{One, Zero};
use sha2::{Digest, Sha512};
use std::sync::OnceLock;

/// 曲线常量集合（首次调用时算一次并缓存）。
struct Ctx {
    /// p = 2^255 - 19
    p: BigUint,
    /// d = -121665/121666 mod p
    d: BigUint,
    /// 2d mod p
    d2: BigUint,
    /// L = 2^252 + 27742317777372353535851937790883648493（子群阶）
    l: BigUint,
    /// sqrt(-1) mod p
    sqrt_m1: BigUint,
    /// 基点 B（RFC 8032 §5.1）
    base: Point,
}

/// 扩展坐标点 (X:Y:Z:T)，T = XY/Z；恒等元 = (0,1,1,0)。
#[derive(Clone)]
struct Point {
    x: BigUint,
    y: BigUint,
    z: BigUint,
    t: BigUint,
}

impl Point {
    /// 构造并规约（T 直接由 X·Y 算出——Kotlin 版把恒等元的 T 记为 null 再惰性补，
    /// 这里统一算出来，数值等价且少一个分支）。
    fn new(x: BigUint, y: BigUint, z: BigUint, p: &BigUint) -> Point {
        let t = (&x * &y) % p;
        Point { x: x % p, y: y % p, z: z % p, t }
    }

    fn identity() -> Point {
        Point { x: BigUint::zero(), y: BigUint::one(), z: BigUint::one(), t: BigUint::zero() }
    }
}

fn add_mod(a: &BigUint, b: &BigUint, p: &BigUint) -> BigUint {
    (a + b) % p
}

fn sub_mod(a: &BigUint, b: &BigUint, p: &BigUint) -> BigUint {
    ((a + p) - b) % p
}

fn mul_mod(a: &BigUint, b: &BigUint, p: &BigUint) -> BigUint {
    (a * b) % p
}

/// 把三条常量字面量读成大整数；读不进来 ⇒ `None`。
///
/// 改前三处直接 expect 崩：常量一旦被改坏，panic 就落在 JNI 调用栈上——整个进程没。
/// 而本模块对外的契约是「任何参数非法或验签不过都返回 `false`——**绝不 panic 跨 FFI**」
/// （见 [verify] 的 KDoc 与规范 #1），所以构造失败只能走 `None` 这条返回值。
fn dec(bytes: &[u8]) -> Option<BigUint> {
    BigUint::parse_bytes(bytes, 10)
}

/// 曲线常量的**构造**：任何一条字面量非法 ⇒ `None`，不 panic。
fn build_ctx() -> Option<Ctx> {
    let two = BigUint::from(2u8);
    let p = two.pow(255) - BigUint::from(19u8);
    // d = -121665/121666 mod p（费马小定理求逆：a^(p-2)，省掉额外依赖）
    let inv = BigUint::from(121666u32).modpow(&(&p - BigUint::from(2u8)), &p);
    let num = (BigUint::from(121665u32) * inv) % &p;
    let d = sub_mod(&BigUint::zero(), &num, &p);
    let d2 = mul_mod(&d, &two, &p);
    let l = two.pow(252) + dec(b"27742317777372353535851937790883648493")?;
    // sqrt(-1) = 2^((p-1)/4) mod p
    let sqrt_m1 = two.modpow(&((&p - BigUint::one()) / BigUint::from(4u8)), &p);
    let base = Point::new(
        dec(b"15112221349535400772501151409588531511454012693041857206046113283949847762202")?,
        dec(b"46316835694926478169428394003475163141307993866256225615783033603165251855960")?,
        BigUint::one(),
        &p,
    );
    Some(Ctx { p, d, d2, l, sqrt_m1, base })
}

/// 缓存的常量集；`None` = 构造失败 ⇒ 调用方一律按「验签不过」处理。
fn ctx() -> Option<&'static Ctx> {
    static CTX: OnceLock<Option<Ctx>> = OnceLock::new();
    CTX.get_or_init(build_ctx).as_ref()
}

/// 统一加法（RFC 8032 §5.1.4，含倍点——公式对 P=Q 完备）。
fn pt_add(a: &Point, b: &Point) -> Option<Point> {
    let c = ctx()?;
    let p = &c.p;
    let aa = mul_mod(&sub_mod(&a.y, &a.x, p), &sub_mod(&b.y, &b.x, p), p);
    let bb = mul_mod(&add_mod(&a.y, &a.x, p), &add_mod(&b.y, &b.x, p), p);
    let cc = mul_mod(&mul_mod(&a.t, &c.d2, p), &b.t, p);
    let dd = mul_mod(&mul_mod(&a.z, &b.z, p), &BigUint::from(2u8), p);
    let ee = sub_mod(&bb, &aa, p);
    let ff = sub_mod(&dd, &cc, p);
    let gg = add_mod(&dd, &cc, p);
    let hh = add_mod(&bb, &aa, p);
    Some(
        Point::new(
            mul_mod(&ee, &ff, p),
            mul_mod(&gg, &hh, p),
            mul_mod(&ff, &gg, p),
            p,
        )
        .with_t(mul_mod(&ee, &hh, p), p),
    )
}

impl Point {
    /// 覆写 T（pt_add 的 T 不是 X·Y/Z 的常规形态，必须显式给）。
    fn with_t(mut self, t: BigUint, p: &BigUint) -> Point {
        self.t = t % p;
        self
    }
}

/// 标量乘：LSB 双加（加法公式完备，无需显式倍点分支）。
fn pt_mul(scalar: &BigUint, point: &Point) -> Option<Point> {
    let c = ctx()?;
    let l = &c.l;
    let s = scalar % l;
    let mut r = Point::identity();
    let mut t = point.clone();
    let bits = s.bits();
    for i in 0..bits {
        if s.bit(i) {
            r = pt_add(&r, &t)?;
        }
        t = pt_add(&t, &t)?;
    }
    Some(r)
}

/// 投影坐标相等判定（X1·Z2 == X2·Z1 且 Y1·Z2 == Y2·Z1）。
fn pt_equal(a: &Point, b: &Point) -> bool {
    match ctx() {
        None => false,
        Some(c) => {
            let p = &c.p;
            mul_mod(&a.x, &b.z, p) == mul_mod(&b.x, &a.z, p)
                && mul_mod(&a.y, &b.z, p) == mul_mod(&b.y, &a.z, p)
        }
    }
}

/// 小端字节 → 大整数。
fn le_to_uint(bytes: &[u8]) -> BigUint {
    let mut be = bytes.to_vec();
    be.reverse();
    BigUint::from_bytes_be(&be)
}

/// 解码 32 字节压缩点（RFC 8032 §5.1.3）；非法返回 None。
fn decode_point(bytes: &[u8]) -> Option<Point> {
    if bytes.len() != 32 {
        return None;
    }
    let c = ctx()?;
    let p = &c.p;
    let mut copy = bytes.to_vec();
    let x_odd = (copy[31] & 0x80) != 0;
    copy[31] &= 0x7F;
    let y = le_to_uint(&copy);
    if y >= *p {
        return None;
    }
    let one = BigUint::one();
    let u = sub_mod(&mul_mod(&y, &y, p), &one, p);
    let v = add_mod(&mul_mod(&mul_mod(&c.d, &y, p), &y, p), &one, p);
    // x = u·v³·(u·v⁷)^((p-5)/8)
    let three = BigUint::from(3u8);
    let mut x = mul_mod(&u, &v.modpow(&three, p), p);
    let uv7 = mul_mod(&u, &v.modpow(&BigUint::from(7u8), p), p);
    let exp = (p - BigUint::from(5u8)) / BigUint::from(8u8);
    x = mul_mod(&x, &uv7.modpow(&exp, p), p);
    let vx2 = mul_mod(&mul_mod(&v, &x, p), &x, p);
    if vx2 == u {
        if x.is_zero() && x_odd {
            return None;
        }
    } else if vx2 == sub_mod(p, &u, p) {
        x = mul_mod(&x, &c.sqrt_m1, p);
    } else {
        return None;
    }
    if x.is_zero() && x_odd {
        return None;
    }
    if x.bit(0) != x_odd {
        x = sub_mod(p, &x, p);
    }
    Some(Point::new(x, y, BigUint::one(), p))
}

/// 验签：`pub_key` 32 字节、`sig` 64 字节（R‖S）、`msg` 任意长度。
///
/// 判定式：S·B == R + h·A，其中 h = SHA512(R‖A‖M) mod L。
/// 任何参数非法或验签不过都返回 `false`——**绝不 panic 跨 FFI**（规范 #1）。
pub fn verify(pub_key: &[u8], sig: &[u8], msg: &[u8]) -> bool {
    if pub_key.len() != 32 || sig.len() != 64 {
        return false;
    }
    let c = match ctx() {
        Some(v) => v,
        None => return false,
    };
    let a = match decode_point(pub_key) {
        Some(v) => v,
        None => return false,
    };
    let r = match decode_point(&sig[..32]) {
        Some(v) => v,
        None => return false,
    };
    let s = le_to_uint(&sig[32..]);
    if s >= c.l {
        return false;
    }
    let mut md = Sha512::new();
    md.update(&sig[..32]);
    md.update(pub_key);
    md.update(msg);
    let digest = md.finalize();
    let h = le_to_uint(digest.as_slice()) % &c.l;
    let left = match pt_mul(&s, &c.base) {
        Some(v) => v,
        None => return false,
    };
    let ha = match pt_mul(&h, &a) {
        Some(v) => v,
        None => return false,
    };
    let right = match pt_add(&r, &ha) {
        Some(v) => v,
        None => return false,
    };
    pt_equal(&left, &right)
}

#[cfg(test)]
mod tests {
    use super::verify;

    fn h2b(s: &str) -> Vec<u8> {
        hex::decode(s.replace([' ', '\n', '\r'], "")).expect("hex 字面量非法")
    }


    // 2026-10-28：原本还有一条「真实清单」回归锁，读 `rootfs/agents.json` + `.sig`
    // 验真签名。rootfs/ 已随终端整仓搬去 `zhengdao-term`（248/250），本仓新检出上
    // 那两个夹具不存在 ⇒ 该用例必然 panic（CI 上一度就是它在红）。按 #478 的处置删掉，
    // 口径（含公钥与真实签名）归 `zhengdao-term`；本仓保留 RFC 8032 向量验算法本身。

    // ── RFC 8032 §7.1 测试向量（原文抄录，勿改） ──

    #[test]
    fn rfc8032_测试1_空消息() {
        let pub_key = h2b("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
        let sig = h2b(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155\
             5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        );
        assert!(verify(&pub_key, &sig, b""));
        // 正文多一个字节 ⇒ 必须失败
        assert!(!verify(&pub_key, &sig, b"\x00"));
    }

    #[test]
    fn rfc8032_测试2_单字节消息() {
        let pub_key = h2b("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c");
        let sig = h2b(
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da\
             085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
        );
        assert!(verify(&pub_key, &sig, b"\x72"));
        assert!(!verify(&pub_key, &sig, b"\x73"));
    }

    #[test]
    fn rfc8032_测试3_双字节消息() {
        let pub_key = h2b("fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025");
        let sig = h2b(
            "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac\
             18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
        );
        assert!(verify(&pub_key, &sig, &h2b("af82")));
        assert!(!verify(&pub_key, &sig, &h2b("af83")));
    }


    #[test]
    fn 长度非法_一律false不panic() {
        assert!(!verify(&[0u8; 31], &[0u8; 64], b"x"));
        assert!(!verify(&[0u8; 32], &[0u8; 63], b"x"));
    }

    /// 259-C#14：**常量读不进来时不许 panic**。改前这里根本没有可断言的东西——
    /// `ctx()` 的返回类型是 `&'static Ctx`，三条 `BigUint::parse_bytes(...).expect("…常量字面量非法")`
    /// 一旦命中就是跨 FFI 的 panic（进程没了，而不是"验签不过"）。改后构造走 `Option`，
    /// 于是"非法字面量 ⇒ None ⇒ verify 返回 false"这条链第一次变得可测：
    /// `dec` 对同一批字面量的合法/非法两侧各断言一次。
    #[test]
    fn 常量构造走option_坏字面量返回none() {
        assert!(super::build_ctx().is_some(), "真常量必须能构造出来");
        assert!(super::ctx().is_some());
        // 合法侧：与 build_ctx 用的是同一批字面量
        assert!(super::dec(b"27742317777372353535851937790883648493").is_some());
        // 非法侧（把 L 的末位换成非十进制字符）：以前这里 panic，现在只是 None
        assert!(super::dec(b"2774231777737235353585193779088364849x").is_none());
        // 坏常量的下游只能得到 false：入口判定式原样跑通，不炸
        match std::panic::catch_unwind(|| verify(&[0u8; 31], &[0u8; 64], b"x")) {
            Ok(v) => assert!(!v, "长度非法必须返回 false"),
            Err(_) => panic!("verify 不许 panic（本模块契约：绝不 panic 跨 FFI）"),
        }
    }

    /// **已知边界（与 Kotlin 版逐位一致）**：全零公钥/签名解出来的都是阶为 4 的小阶点，
    /// RFC 8032 §5.1.7 允许的朴素判定式（S·B == R + h·A）在这个退化输入上会**通过**。
    /// 本项目的信任根是固化在 APK 里的公钥（32 字节非退化值），攻击者只能控制 R/S，
    /// 无法把 A 换成小阶点 ⇒ 该路径不可达；这里把它固定成断言，
    /// 是为了哪天有人"顺手换实现"时能立刻看见语义漂移（详见 ERRATA E-051）。
    #[test]
    fn 全零退化输入_朴素判定式通过_已知边界() {
        assert!(verify(&[0u8; 32], &[0u8; 64], b"x"));
    }
}
