// JNI 薄层（仅 Android 目标编译）。收编前是两份独立实现
// （sha256poc 的 nativeSha256Hex、extract 的 nativeExtract + report_progress），
// R1 合并进同一个 .so、同一个 Kotlin 类：
//   符号名前缀 Java_com_example_zhengdao_rust_Sha256Native_* / _ExtractNative_*
//   → 统一为 Java_com_example_zhengdao_rust_CoreNative_*；
//   进度回调的查找类同步改为 com/example/zhengdao/rust/CoreNative。
//
// 两条约定（R2 已固化，收编后逐字不变）：
// - **错误不跨 FFI panic**：哈希失败返回 null（Kotlin 回退 MessageDigest）；
//   解压失败返回 JSON {"ok":false,"error":"..."}——Android 的 stderr 不进 logcat，
//   用 null 协议会让错误无迹可查。
// - **数据常驻 native，边界只跨一次**：解压整条流水线在 native 内完成，
//   Java 只收 JSON 摘要 + 限频进度事件。
use crate::extract::{extract_pipeline, Progress};
use jni::objects::{JByteArray, JClass, JString};
use jni::JNIEnv;

/// 进度回调限频：每 +200 条目通知 Java 一次（避免 JNI 边界成为热点）。
const PROGRESS_STEP: u64 = 200;

/// Kotlin: `CoreNative.nativeSha256Hex(ByteArray) -> String?`
/// 错误时返回 null（调用方回退 MessageDigest），绝不 panic 跨 FFI。
#[no_mangle]
pub extern "system" fn Java_com_example_zhengdao_rust_CoreNative_nativeSha256Hex(
    env: JNIEnv,
    _class: JClass,
    data: JByteArray,
) -> jni::sys::jstring {
    let bytes = match env.convert_byte_array(data) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    match env.new_string(crate::sha256::sha256_hex(&bytes)) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Kotlin: `CoreNative.nativeExtract(archivePath, targetDir, expectedSha256) -> String`
/// 永远返回 JSON（Android 的 stderr 不进 logcat，null 协议会让错误无迹可查）：
/// 成功 {"ok":true,"entries":N,"bytes":N,"sha256":"..."}；失败 {"ok":false,"error":"..."}
#[no_mangle]
pub extern "system" fn Java_com_example_zhengdao_rust_CoreNative_nativeExtract(
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
    // Kotlin 侧静态方法 CoreNative.onProgress(entries, name)；找不到/失败静默
    let _ = (|| -> jni::errors::Result<()> {
        let cls = env.find_class("com/example/zhengdao/rust/CoreNative")?;
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
