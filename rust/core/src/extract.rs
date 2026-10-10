// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的公开资料：tar(5)/USTAR 格式、zstd 帧格式（RFC 8878）、jli crate 官方文档。
//
//! extract 模块（v2.0 R2）：「归档 → zstd 解压 → tar 落盘 + SHA256 流式校验」流水线。
//!
//! 架构文档裁定（证道-v2.0架构设计.md §2）：数据常驻 native、JNI 边界只跨一次——
//! Java 传入归档路径 + 目标目录 + SHA256，native 完成全部工作，Java 只收结果与进度。
//!
//! 语义与 Kotlin 版 RootfsInstaller 对齐（对拍基准）：
//! - 支持 zstd（主）与 gzip（兜底）两种压缩壳的 tar
//! - 目录 / 符号链接 / 硬链接（前向引用二阶段补齐）/ 普通文件
//! - FIFO/设备节点跳过（proot -b /dev 方案下不需要）
//! - **可落盘 0 条目 = 错误**（`EmptyArchive`，与 Kotlin 版 `extracted == 0` 对拍；BUG-1/E-083）
//! - 路径穿越防护（成员名 + 软链 linkname 都必须落在目标目录内；加固-1/加固-3，E-085）
//! - 完成后写标记文件（由调用方指定内容；Kotlin 侧写 distro 信息）

use sha2::{Digest, Sha256};
use std::fs;
use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};

/// 流水线结果。
#[derive(Debug, PartialEq)]
pub struct ExtractReport {
    pub entries: u64,
    pub bytes_written: u64,
    pub archive_sha256: String,
    /// 按 `skip_names` 主动跳过的成员数（补丁包的元数据成员；见 `extract_pipeline_skip`）
    pub skipped: u64,
    pub duration_hint_ms: u64, // 由 JNI 层计时填充；纯逻辑层为 0
}

/// 错误统一枚举（JNI 层转 null + 错误消息；绝不 panic 跨 FFI——规范 #1）。
#[derive(Debug)]
pub enum ExtractError {
    Io(std::io::Error),
    BadArchive(String),
    ShaMismatch { expected: String, actual: String },
    /// 归档里没有任何**可落盘**的成员（空 tar，或成员全在 `skip_names` 里）。
    ///
    /// BUG-1（2026-10-10）：这条以前不存在——0 条目会被当成安装成功（`Ok`），于是上层
    /// `RootfsMarker.write` + `swapLocked` 把环境标成装好，实际是个空环境还报成功。
    /// Java 路径一直有这条兜底（`RootfsInstaller.extractArchiveJava` 的 `extracted == 0`），
    /// 这里补齐"两条路径一致"的对拍。Kotlin 侧靠 `EMPTY_ARCHIVE_MARK` 认这条消息。
    EmptyArchive { skipped: u64 },
}

impl From<std::io::Error> for ExtractError {
    fn from(e: std::io::Error) -> Self {
        ExtractError::Io(e)
    }
}

impl std::fmt::Display for ExtractError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ExtractError::Io(e) => write!(f, "IO: {e}"),
            ExtractError::BadArchive(m) => write!(f, "归档异常: {m}"),
            ExtractError::EmptyArchive { skipped } => {
                write!(f, "归档不含任何条目: 可落盘 0 条（跳过 {skipped} 条）")
            }
            ExtractError::ShaMismatch { expected, actual } => {
                write!(f, "SHA256 不匹配: 期望 {expected} 实际 {actual}")
            }
        }
    }
}

/// 进度事件（JNI 层每 N 个条目回调一次 Java；纯逻辑层经闭包收集）。
pub struct Progress {
    pub entries: u64,
    pub current_name: String,
}

/// 主流水线：归档路径进（数据常驻 native），解压+校验+落盘，Java 只收报告。
///
/// * `archive`    —— .tar / .tar.zst / .tar.gz 归档路径（按魔数识别：zstd 主、gzip 兜底、其余按纯 tar 处理）
/// * `target_dir` —— 解压目标目录（须已存在或可创建；原子 rename 由调用方负责）
/// * `expected_sha256` —— 归档整体 SHA256（hex 小写）；None = 跳过校验
/// * `on_progress` —— 进度回调（JNI 层转为限频 Java 回调）
pub fn extract_pipeline(
    archive: &Path,
    target_dir: &Path,
    expected_sha256: Option<&str>,
    on_progress: &mut dyn FnMut(Progress),
) -> Result<ExtractReport, ExtractError> {
    extract_pipeline_skip(archive, target_dir, expected_sha256, &[], on_progress)
}

/// 与 [`extract_pipeline`] 相同，额外支持**跳过指定成员**（名字用 tar 里的相对路径，
/// `./` 前缀已去掉，与 `Progress::current_name` 同一口径）。
///
/// 为什么需要它：补丁包（`rootfs-patch-*.tar.zst`）的第一个成员是元数据
/// `.zhengdao-patch-info`，它只给 Kotlin 侧读基线/删除清单，**不能落进目标树**。
/// 在 Rust 支持"跳过"之前，这条路径只能走 Java 版（`RootfsInstaller.openTar` 的
/// `skipNames`）—— 于是"全量走 Rust、补丁走 Java"长期分叉（见 E-050）。
pub fn extract_pipeline_skip(
    archive: &Path,
    target_dir: &Path,
    expected_sha256: Option<&str>,
    skip_names: &[String],
    on_progress: &mut dyn FnMut(Progress),
) -> Result<ExtractReport, ExtractError> {
    // 0) 目标目录必须先存在——路径穿越判定的 canonicalize 依赖它，
    //    也与 Kotlin 版 RootfsInstaller（tmpDir 预建）语义对齐
    fs::create_dir_all(target_dir).map_err(ExtractError::Io)?;

    // 1) 流式读归档 + 同步算 SHA256（一遍完成，不再整读第二遍）
    let mut file = fs::File::open(archive).map_err(ExtractError::Io)?;
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 256 * 1024];
    loop {
        let n = file.read(&mut buf).map_err(ExtractError::Io)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    let archive_sha = hex::encode(hasher.finalize());
    if let Some(expected) = expected_sha256 {
        let expected = expected.trim().to_lowercase();
        if archive_sha != expected {
            return Err(ExtractError::ShaMismatch {
                expected,
                actual: archive_sha,
            });
        }
    }

    // 2) 从头解压（与 Kotlin 版对拍：zstd 主、gzip 兜底，按魔数识别）
    let mut file = fs::File::open(archive).map_err(ExtractError::Io)?;
    let mut magic = [0u8; 4];
    file.read_exact(&mut magic).map_err(ExtractError::Io)?;
    file.seek(SeekFrom::Start(0)).map_err(ExtractError::Io)?;
    let mut decompressed: Box<dyn Read> = match magic {
        [0x28, 0xB5, 0x2F, 0xFD, ..] => Box::new(zstd::stream::read::Decoder::new(file)?),
        [0x1F, 0x8B, ..] => Box::new(flate2::read::GzDecoder::new(file)),
        // 其余一律按**纯 tar** 处理（与 Kotlin 版 `RootfsInstaller.openTar` 对齐）：
        // 测试/合成补丁包就是未压缩 tar（androidTest 用 commons-compress 直写），
        // 垃圾输入会由 tar 解析器报错，不必在这里先猜一遍。
        _ => Box::new(file),
    };

    // 3) tar 遍历落盘
    let mut tar = tar::Archive::new(&mut decompressed);
    tar.set_preserve_permissions(true);
    let mut entries = 0u64;
    let mut skipped = 0u64;
    let mut bytes_written = 0u64;
    let mut pending_hardlinks: Vec<(String, String)> = Vec::new(); // (link_path, target_in_archive)

    for entry in tar.entries().map_err(|e| ExtractError::BadArchive(format!("tar: {e}")))? {
        let mut entry = entry.map_err(ExtractError::Io)?;
        let raw_path = entry
            .path()
            .map_err(ExtractError::Io)?
            .to_string_lossy()
            .to_string();
        let name = raw_path.trim_start_matches("./").to_string();
        if name.is_empty() || name == "." {
            continue;
        }
        // 显式跳过（补丁包的元数据成员）：不落盘、不计入 entries。
        // 归档 sha 是上面那次**独立前置扫描**算出来的，跳过成员不影响它。
        if skip_names.iter().any(|s| s == &name) {
            skipped += 1;
            continue;
        }
        check_path_inside(target_dir, Path::new(&name))?;
        let target = target_dir.join(&name);

        entries += 1;
        on_progress(Progress {
            entries,
            current_name: name.clone(),
        });

        let entry_type = entry.header().entry_type();
        match entry_type {
            tar::EntryType::Directory => {
                fs::create_dir_all(&target).map_err(ExtractError::Io)?;
                set_mode(&target, entry.header().mode().unwrap_or(0o755));
            }
            tar::EntryType::Symlink => {
                if let Some(parent) = target.parent() {
                    fs::create_dir_all(parent).map_err(ExtractError::Io)?;
                }
                let _ = fs::remove_file(&target);
                #[cfg(unix)]
                {
                    // linkname 按 tar 规范是**相对链接所在目录**的路径（也可能是绝对路径，
                    // 按 guest 根解释）。落盘前先判它没跑出解压根 —— 加固-1（E-085）：
                    // 解出来的软链会被同一份包的后续成员或 guest 内程序跟随，越界链接
                    // 等于一条写出环境目录的通道。与 Kotlin 版 `PathGuard.linkStaysInside` 对拍。
                    let link = entry.link_name().map_err(ExtractError::Io)?.unwrap_or_default();
                    if !link_stays_inside(target_dir, &target, &link) {
                        // 越界：不建链接，空文件占位（与"建不出来"同一条退路，环境仍可用）
                        fs::write(&target, b"").map_err(ExtractError::Io)?;
                    } else if std::os::unix::fs::symlink(&link, &target).is_err() {
                        // 个别 symlink 建不出来不致命：空文件占位（与 Kotlin 版语义对齐）
                        fs::write(&target, b"").map_err(ExtractError::Io)?;
                    }
                }
                #[cfg(not(unix))]
                {
                    // host 侧（Windows）不做符号链接：空文件占位，语义与 Kotlin 版回退一致
                    fs::write(&target, b"").map_err(ExtractError::Io)?;
                }
            }
            tar::EntryType::Link => {
                // 硬链接 → 与 Kotlin 版对齐：复制内容落地；前向引用登记二阶段
                let link_target = entry
                    .link_name()
                    .map_err(ExtractError::Io)?
                    .unwrap_or_default()
                    .to_string_lossy()
                    .trim_start_matches("./")
                    .to_string();
                let src = target_dir.join(&link_target);
                if let Some(parent) = target.parent() {
                    fs::create_dir_all(parent).map_err(ExtractError::Io)?;
                }
                if src.is_file() {
                    bytes_written += fs::copy(&src, &target).map_err(ExtractError::Io)?;
                } else {
                    pending_hardlinks.push((name.clone(), link_target));
                }
            }
            tar::EntryType::Fifo | tar::EntryType::Char | tar::EntryType::Block => {
                // 设备节点与 FIFO：proot -b /dev 下不需要，跳过（与 Kotlin 版对齐）
            }
            _ => {
                // 普通文件
                if let Some(parent) = target.parent() {
                    fs::create_dir_all(parent).map_err(ExtractError::Io)?;
                }
                let mut out = fs::File::create(&target).map_err(ExtractError::Io)?;
                let n = std::io::copy(&mut entry, &mut out).map_err(ExtractError::Io)?;
                bytes_written += n;
                set_mode(&target, entry.header().mode().unwrap_or(0o644));
            }
        }
    }

    // 4) 二阶段：补齐前向硬链接
    for (link_path, src_name) in &pending_hardlinks {
        let src = target_dir.join(src_name);
        let dst = target_dir.join(link_path);
        if src.is_file() {
            fs::copy(&src, &dst).map_err(ExtractError::Io)?;
        }
    }

    // 5) 空归档兜底（BUG-1，2026-10-10）：与 Kotlin 版 `extractArchiveJava` 的
    //    「一条都没解出来」对齐（`if (extracted == 0) throw InstallFailed(...)`）。
    //    为什么必须挡在这里：0 条目原本会被当成安装成功，上层接着写
    //    `.zhengdao-rootfs-ok` 并换树 —— 用户得到一个"装好了"的空环境。
    //    为什么用 `entries`（不含 skipped）：Java 侧的计数器同样不计被跳过的成员，
    //    所以"补丁包只含元数据成员"这种形状两条路径都会拒（语义一致，好对拍）。
    if entries == 0 {
        return Err(ExtractError::EmptyArchive { skipped });
    }

    Ok(ExtractReport {
        entries,
        bytes_written,
        archive_sha256: archive_sha,
        skipped,
        duration_hint_ms: 0,
    })
}

/// 词法归一（**不碰文件系统**）：`.` 丢掉、`..` 弹一层。用于软链目标的边界判定。
///
/// 为什么不用 canonicalize：软链目标通常还不存在（同一份包里的后续成员），而 canonicalize
/// 一个不存在的路径会把它原样返回 ⇒ 判不出 `..` 逃逸。真实存在的软链被后续成员跟随时，
/// 威胁由 [`check_path_inside`]（canonicalize 版）兜住。
fn normalize_lexical(p: &Path) -> PathBuf {
    use std::path::Component;
    let mut out = PathBuf::new();
    for c in p.components() {
        match c {
            Component::CurDir => {}
            Component::ParentDir => {
                out.pop();
            }
            other => out.push(other.as_os_str()),
        }
    }
    out
}

/// 软链 linkname 是否落在解压根内（**加固-1**，2026-10-10 / E-085）。
///
/// tar 规范里 linkname 有两种合法写法：相对**链接所在目录**（`libfoo.so.1`，Debian 里的绝大多数），
/// 或绝对路径（`/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1`，基础镜像里也真实存在）。绝对路径按
/// **guest 根**（= 解压根）解释、相对路径按链接所在目录解释；两者归一后都必须仍在根内 ——
/// 否则解出来的软链会把后续成员或 guest 内程序引到树外。
///
/// 与 Kotlin 版 `PathGuard.linkStaysInside` 逐条对拍（同一条判据、同一组用例）。
#[cfg_attr(not(unix), allow(dead_code))]
pub(crate) fn link_stays_inside(root: &Path, link_path: &Path, link: &Path) -> bool {
    use std::path::Component;
    if link.as_os_str().is_empty() {
        return false;
    }
    let root_norm = normalize_lexical(root);
    let base = if link.is_absolute() {
        root_norm.clone()
    } else {
        match link_path.parent() {
            Some(p) => normalize_lexical(p),
            None => return false,
        }
    };
    let mut joined = base;
    for c in link.components() {
        match c {
            // 绝对路径的根：按 guest 根解释 ⇒ 直接落在解压根上，不做替换
            Component::RootDir => {}
            Component::ParentDir => {
                joined.pop();
            }
            Component::CurDir => {}
            other => joined.push(other.as_os_str()),
        }
    }
    joined.starts_with(&root_norm)
}

/// 防路径穿越：目标（上溯到已存在的最近祖先做 canonicalize）必须落在目标目录内。
/// Windows 下 canonicalize 会返回 \?\ 前缀的 verbatim 路径，比较前统一剥掉。
fn check_path_inside(root: &Path, name: &Path) -> Result<(), ExtractError> {
    let target = root.join(name);
    let strip_verbatim = |p: &Path| -> std::path::PathBuf {
        PathBuf::from(p.to_string_lossy().replace(r"\?\", ""))
    };
    let root_norm = strip_verbatim(&root.canonicalize().unwrap_or_else(|_| root.to_path_buf()));

    let mut cur = target.clone();
    loop {
        if let Ok(c) = cur.canonicalize() {
            if strip_verbatim(&c).starts_with(&root_norm) {
                return Ok(());
            }
            return Err(ExtractError::BadArchive(format!(
                "压缩包含越界路径: {}",
                target.display()
            )));
        }
        // 尚不存在 → 上溯最近已存在的祖先继续判定
        match cur.parent() {
            Some(parent) if parent != cur => cur = parent.to_path_buf(),
            _ => {
                return Err(ExtractError::BadArchive(format!(
                    "无法定位解压目标: {}",
                    target.display()
                )))
            }
        }
    }
}

#[cfg(unix)]
fn set_mode(path: &Path, mode: u32) {
    use std::os::unix::fs::PermissionsExt;
    let _ = fs::set_permissions(path, fs::Permissions::from_mode(mode & 0o7777));
}

#[cfg(not(unix))]
fn set_mode(_path: &Path, _mode: u32) {}
