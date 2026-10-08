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
use crate::extract::{extract_pipeline_skip, Progress};
use jni::objects::{JByteArray, JClass, JString};
use jni::JNIEnv;
use std::sync::atomic::{AtomicBool, Ordering};

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

/// Kotlin: `CoreNative.nativeSha256File(path) -> String?`
///
/// 对大文件（rootfs 归档 192 MB）做流式摘要，常驻内存只有一个 128 KB 缓冲。
/// 与 `nativeSha256Hex` 同一条契约：**错误返回 null**（Kotlin 回退平台流式实现），
/// 绝不 panic 跨 FFI——文件不存在、无读权限、被并发删除都只算"这条路走不通"。
#[no_mangle]
pub extern "system" fn Java_com_example_zhengdao_rust_CoreNative_nativeSha256File(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jni::sys::jstring {
    let result = (|| -> Result<String, String> {
        let p: String = env
            .get_string(&path)
            .map_err(|e| e.to_string())?
            .to_string_lossy()
            .to_string();
        crate::sha256::sha256_file_hex(std::path::Path::new(&p)).map_err(|e| e.to_string())
    })();

    match result {
        Ok(hex) => match env.new_string(hex) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    }
}

/// Kotlin: `CoreNative.nativeVerifyEd25519(pubKey, sig, msg) -> Boolean`
///
/// Ed25519 验签（RFC 8032 §5.1）——信任根那类纯计算，常驻 native。
/// 契约：**任何异常都返回 false**（参数数量不对、字节数组转换失败都算"验不过"），
/// 绝不 panic 跨 FFI；调用方还会拿平台实现对拍，见 `CoreNative.verifyEd25519`。
#[no_mangle]
pub extern "system" fn Java_com_example_zhengdao_rust_CoreNative_nativeVerifyEd25519(
    env: JNIEnv,
    _class: JClass,
    pub_key: JByteArray,
    sig: JByteArray,
    msg: JByteArray,
) -> jni::sys::jboolean {
    let (Ok(pub_key), Ok(sig), Ok(msg)) = (
        env.convert_byte_array(pub_key),
        env.convert_byte_array(sig),
        env.convert_byte_array(msg),
    ) else {
        return jni::sys::JNI_FALSE;
    };
    if crate::ed25519::verify(&pub_key, &sig, &msg) {
        jni::sys::JNI_TRUE
    } else {
        jni::sys::JNI_FALSE
    }
}

/// Kotlin: `CoreNative.nativeExtract(archivePath, targetDir, expectedSha256) -> String`
/// 永远返回 JSON（Android 的 stderr 不进 logcat，null 协议会让错误无迹可查）：
/// 成功 {"ok":true,"entries":N,"bytes":N,"sha256":"...","skipped":N}；失败 {"ok":false,"error":"..."}
#[no_mangle]
pub extern "system" fn Java_com_example_zhengdao_rust_CoreNative_nativeExtract(
    mut env: JNIEnv,
    _class: JClass,
    archive_path: JString,
    target_dir: JString,
    expected_sha: JString,
) -> jni::sys::jstring {
    let result = extract_impl(&mut env, &archive_path, &target_dir, &expected_sha, &[]);
    to_json(&mut env, result)
}

/// Kotlin: `CoreNative.nativeExtractSkip(archivePath, targetDir, expectedSha256, skipNamesJoined) -> String`
///
/// 与 `nativeExtract` 同一流水线，额外跳过 `skipNamesJoined` 里以 `\n` 连接列出的成员
/// （补丁包顶部的元数据 `.zhengdao-patch-info` 只给 Kotlin 读，不该落进目标树）。
///
/// 两个刻意的取舍（见 docs/ERRATA.md E-050）：
/// - 跳过清单用「`\n` 连接的字符串」而不是 `JObjectArray`：jni crate 的数组 API 版本间签名
///   不稳，而清单永远只有一两个名字；把跨边界的数据形状压到最小。
/// - **新增符号而不是改 `nativeExtract` 的签名**：旧 `.so` + 新 Kotlin 时只有补丁这条路径
///   在 JNI 查找处失败（`RootfsInstaller` 捕获后回退 Java），全量安装入口不受影响。
#[no_mangle]
pub extern "system" fn Java_com_example_zhengdao_rust_CoreNative_nativeExtractSkip(
    mut env: JNIEnv,
    _class: JClass,
    archive_path: JString,
    target_dir: JString,
    expected_sha: JString,
    skip_names: JString,
) -> jni::sys::jstring {
    let skips = read_skip_names(&mut env, &skip_names);
    let result = match skips {
        Ok(list) => extract_impl(&mut env, &archive_path, &target_dir, &expected_sha, &list),
        Err(msg) => Err(msg),
    };
    to_json(&mut env, result)
}

/// 解包 `\n` 连接的跳过清单（null / 空串 = 不跳过任何成员）。
fn read_skip_names(env: &mut JNIEnv, skip_names: &JString) -> Result<Vec<String>, String> {
    if skip_names.is_null() {
        return Ok(Vec::new());
    }
    let joined = env
        .get_string(skip_names)
        .map_err(|e| e.to_string())?
        .to_string_lossy()
        .to_string();
    Ok(joined
        .split('\n')
        .filter(|s| !s.is_empty())
        .map(|s| s.to_string())
        .collect())
}

/// 解压流水线的共用实现：成功回 JSON 摘要，失败回错误串（由 `to_json` 统一封壳）。
fn extract_impl(
    env: &mut JNIEnv,
    archive_path: &JString,
    target_dir: &JString,
    expected_sha: &JString,
    skip_names: &[String],
) -> Result<String, String> {
    let archive: String = env
        .get_string(archive_path)
        .map_err(|e| e.to_string())?
        .to_string_lossy()
        .to_string();
    let target: String = env
        .get_string(target_dir)
        .map_err(|e| e.to_string())?
        .to_string_lossy()
        .to_string();
    let sha: Option<String> = if expected_sha.is_null() {
        None
    } else {
        Some(
            env.get_string(expected_sha)
                .map_err(|e| e.to_string())?
                .to_string_lossy()
                .to_string(),
        )
    };

    let mut last_entries = 0u64;
    let mut callback_env = unsafe { env.unsafe_clone() };
    let report = extract_pipeline_skip(
        std::path::Path::new(&archive),
        std::path::Path::new(&target),
        sha.as_deref(),
        skip_names,
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
        "{{\"ok\":true,\"entries\":{},\"bytes\":{},\"sha256\":\"{}\",\"skipped\":{}}}",
        report.entries, report.bytes_written, report.archive_sha256, report.skipped
    ))
}

/// 统一把结果封成 JSON 字符串交给 Kotlin（失败也绝不 panic 跨 FFI）。
fn to_json(env: &mut JNIEnv, result: Result<String, String>) -> jni::sys::jstring {
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

/// 进度回调可用性闩锁：一旦查找/调用失败就永久关闭。
///
/// 回调只是旁路信息（"尽力而为"），反复跨 JNI 查找没有意义；更关键的是失败路径
/// 必须清掉 pending exception（见 report_progress 的 E-022 注释）。
static PROGRESS_DISABLED: AtomicBool = AtomicBool::new(false);

fn report_progress(env: &mut JNIEnv, entries: u64, name: &str) {
    // Kotlin 侧静态方法 CoreNative.onProgress(entries, name)；找不到/失败静默。
    //
    // ⚠️ E-022（2026-10-08 真机事故）：JNI 调用留下的 pending exception 必须清掉。
    // release 包里 R8 把 `CoreNative.onProgress` 改了名，查找失败后异常一直挂在当前线程上，
    // 下一次 JNI 调用就命中 ART 的 `AssertNoPendingException` → SIGABRT，整个进程闪退
    // （现象：手机上一装环境就退回主页，用户点了三次都装不上）。修法是两条：
    //   1) `app/proguard-rules.pro` 显式 keep 这个被 native 按名字查的成员（R8 看不见 JNI 调用点）；
    //   2) 这里兜底——旁路回调失败一律吞掉并 `exception_clear()`，绝不让它升级成进程崩溃。
    if PROGRESS_DISABLED.load(Ordering::Relaxed) {
        return;
    }
    let result = (|| -> jni::errors::Result<()> {
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
    if result.is_err() {
        PROGRESS_DISABLED.store(true, Ordering::Relaxed);
        let _ = env.exception_clear();
    }
}
