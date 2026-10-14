// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。

//! JSON 拼装的最小工具层（P2-18 / issue #17）。
//!
//! 为什么单独一个模块：Rust 侧过 JNI 的 JSON 是**手拼**的（没有 serde_json，
//! 见 core/Cargo.toml 的依赖清单）。错误信息里带引号 / 反斜杠 / 换行是常态
//! （路径、shell 报错原文），原样拼进 JSON 会让 Kotlin 侧 `JSONObject(json)`
//! 抛解析异常——"Rust 层明明报了错，Kotlin 侧看到的是一次 JSON 崩"，
//! 真正的错误原因反而丢了。
//!
//! 分层理由（同 lib.rs 的纪律）：纯逻辑、不碰 JNI ⇒ **PC 上 `cargo test` 直接验**；
//! JNI 薄层（jni_bridge）只负责调用。

/// JSON 字符串值转义：只动必须动的字符，其余（含中文）原样。
pub fn json_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out
}

/// 失败分支的 JSON：`{"ok":false,"error":"<转义后的消息>"}`。
pub fn error_json(msg: &str) -> String {
    format!("{{\"ok\":false,\"error\":\"{}\"}}", json_escape(msg))
}

#[cfg(test)]
mod tests {
    use super::{error_json, json_escape};

    #[test]
    fn p2_18_引号与反斜杠被转义() {
        assert_eq!(json_escape("a\"b"), "a\\\"b");
        assert_eq!(json_escape("a\\b"), "a\\\\b");
        // 精确输出：内层引号必须以 \" 形态出现（Kotlin 侧 JSONObject 才解析得动）
        assert_eq!(
            error_json("a\"b"),
            "{\"ok\":false,\"error\":\"a\\\"b\"}"
        );
        assert_eq!(
            error_json("C:\\root\\x 拒绝"),
            "{\"ok\":false,\"error\":\"C:\\\\root\\\\x 拒绝\"}"
        );
    }

    #[test]
    fn p2_18_控制字符转义_中文原样() {
        assert_eq!(json_escape("a\nb"), "a\\nb");
        assert_eq!(json_escape("a\rb"), "a\\rb");
        assert_eq!(json_escape("a\tb"), "a\\tb");
        assert_eq!(json_escape("\u{0001}"), "\\u0001");
        // 中文不必 \u 转义（UTF-8 直出就是合法 JSON），转了反而难读
        assert_eq!(json_escape("解压失败：权限不够"), "解压失败：权限不够");
    }

    #[test]
    fn p2_18_空消息也是合法_json() {
        assert_eq!(error_json(""), "{\"ok\":false,\"error\":\"\"}");
    }

    #[test]
    fn p2_18_消息里带花括号括号不影响结构() {
        // 真实场景：tar/zstd 的报错里全是 {} 与路径
        let j = error_json("failed to open \"/root/.hermes/{a}\": noent");
        assert!(j.starts_with("{\"ok\":false"));
        assert!(j.contains("\\\""));
    }
}
