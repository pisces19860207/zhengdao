// JNI 薄层（仅 Android 目标编译）：架构文档 §2.2 裁定的嵌入形态——
// Java 传入路径与 SHA256，native 完成整条流水线（数据常驻 native，边界只跨一次），
// 结果返回 ExtractReport 的 JSON 摘要；错误返回 null + 错误消息入 RustLog。
//
// 进度上报：entries 每 +200 触发一次 Java 静态回调
// Sha256Native 不适用——新类 ExtractNative，由 Kotlin 侧对称实现。
use crate::{extract_pipeline, Progress};
use jni::objects::{JClass, JObject, JString};
use jni::JNIEnv;

const PROGRESS_STEP: u64 = 200;

/// Kotlin: ExtractNative.extract(archivePath, targetDir, expectedSha256) -> String
/// 永远返回 JSON（Android 的 stderr 不进 logcat，null 协议会让错误无迹可查）：
/// 成功 {"ok":true,"entries":N,"bytes":N,"sha256":"..."}；失败 {"ok":false,"error":"..."}
#[no_mangle]
pub extern "system" fn Java_com_example_zhengdao_rust_ExtractNative_extract(
    mut env: JNIEnv,
    _class: JClass,
    archive_path: JString,
    target_dir: JString,
    expected_sha: JString,
) -> jni::sys::jstring {
    let result = (|| -> Result<String, String> {
        let archive: String = env
            .get_string(&archive_path)
            .map_err(|e| e.to_string())?
            .to_string_lossy()
            .to_string();
        let target: String = env
            .get_string(&target_dir)
            .map_err(|e| e.to_string())?
            .to_string_lossy()
            .to_string();
        let sha: Option<String> = if expected_sha.is_null() {
            None
        } else {
            Some(
                env.get_string(&expected_sha)
                    .map_err(|e| e.to_string())?
                    .to_string_lossy()
                    .to_string(),
            )
        };

        let mut last_entries = 0u64;
        let mut callback_env = unsafe { env.unsafe_clone() };
        let report = extract_pipeline(
            std::path::Path::new(&archive),
            std::path::Path::new(&target),
            sha.as_deref(),
            &mut |p: Progress| {
                // 限频回调：每 PROGRESS_STEP 个条目通知 Java 一次
                if p.entries >= last_entries + PROGRESS_STEP {
                    last_entries = p.entries;
                    report_progress(&mut callback_env, p.entries, &p.current_name);
                }
            },
        )
        .map_err(|e| e.to_string())?;

        Ok(format!(
            "{{\"ok\":true,\"entries\":{},\"bytes\":{},\"sha256\":\"{}\"}}",
            report.entries, report.bytes_written, report.archive_sha256
        ))
    })();

    let json = match result {
        Ok(json) => json,
        Err(msg) => {
            format!("{{\"ok\":false,\"error\":\"{}\"}}", msg)
        }
    };
    match env.new_string(json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn report_progress(env: &mut JNIEnv, entries: u64, name: &str) {
    // Kotlin 侧静态方法 ExtractNative.onProgress(entries, name)；找不到/失败静默
    let _ = (|| -> jni::errors::Result<()> {
        let cls = env.find_class("com/example/zhengdao/rust/ExtractNative")?;
        let jentries = env.new_string(format!("{entries}"))?;
        let jname = env.new_string(name)?;
        use jni::objects::JValue;
        env.call_static_method(
            cls,
            "onProgress",
            "(Ljava/lang/String;Ljava/lang/String;)V",
            &[JValue::Object(&jentries), JValue::Object(&jname)],
        )?;
        Ok(())
    })();
}
