// PC 侧验证：合成包（zstd+gzip 双壳）端到端解压 + 边界用例。
// 真机对拍由 androidTest 对同一真实 rootfs 归档跑 Java/Rust 双版本完成。

use crate::extract::{extract_pipeline, extract_pipeline_skip, link_stays_inside, ExtractError};
use std::fs;
use std::path::Path;

/// 合成一个 .tar.zst：目录 + 普通文件 + symlink + 硬链接（后置源，覆盖二阶段补齐）
fn make_archive(dir: &Path, name: &str, gz: bool) -> (PathBuf, String) {
    let tar_path = dir.join(format!("{name}.tar"));
    let mut builder = tar::Builder::new(fs::File::create(&tar_path).unwrap());

    let base = "data";
    fs::create_dir_all(dir.join("src")).unwrap();

    // 目录条目 ./data/
    let mut hdr_dir = tar::Header::new_gnu();
    hdr_dir.set_size(0);
    hdr_dir.set_entry_type(tar::EntryType::Directory);
    hdr_dir.set_mode(0o755);
    hdr_dir.set_cksum();
    builder.append_data(&mut hdr_dir, format!("{base}/"), io::empty()).unwrap();

    // 普通文件 ./data/hello.txt
    let content = b"hello zhengdao extract\n".repeat(100);
    let mut hdr = tar::Header::new_gnu();
    hdr.set_size(content.len() as u64);
    hdr.set_entry_type(tar::EntryType::Regular);
    hdr.set_mode(0o644);
    hdr.set_cksum();
    builder.append_data(&mut hdr, format!("{base}/hello.txt"), &content[..]).unwrap();

    // 大文件（跨多块读）
    let big = vec![0xA5u8; 300_000];
    let mut hdr_big = tar::Header::new_gnu();
    hdr_big.set_size(big.len() as u64);
    hdr_big.set_mode(0o644);
    hdr_big.set_cksum();
    builder.append_data(&mut hdr_big, format!("{base}/big.bin"), &big[..]).unwrap();

    // symlink ./data/link → hello.txt
    // ⚠️ linkname 必须是**相对链接所在目录**的路径（tar 规范如此；GNU tar 也照磁盘原样记录），
    //    所以这里是 "hello.txt"，不是根相对的 "data/hello.txt"。
    //    写成根相对时，Windows host 走"空文件回退"分支、对称链接的断言被 cfg 掉，看不出来；
    //    只有 unix 会现形：symlink 落在 out/data/link、内容 "data/hello.txt"
    //    → 解析到 out/data/data/hello.txt，读穿失败。
    //    （这正是本文件接进 CI 后第一个被抓到的坑——见 docs/ERRATA.md E-028。）
    let mut hdr_link = tar::Header::new_gnu();
    hdr_link.set_size(0);
    hdr_link.set_entry_type(tar::EntryType::Symlink);
    hdr_link.set_link_name("hello.txt").unwrap();
    hdr_link.set_mode(0o777);
    hdr_link.set_cksum();
    builder.append_data(&mut hdr_link, format!("{base}/link"), io::empty()).unwrap();

    // 硬链接 ./data/hardlink → big.bin（后置源已存在，直接复制路径）
    let mut hdr_hard = tar::Header::new_gnu();
    hdr_hard.set_size(big.len() as u64);
    hdr_hard.set_entry_type(tar::EntryType::Link);
    hdr_hard.set_link_name(format!("{base}/big.bin")).unwrap();
    hdr_hard.set_mode(0o644);
    hdr_hard.set_cksum();
    builder.append_data(&mut hdr_hard, format!("{base}/hardlink"), &big[..]).unwrap();

    builder.finish().unwrap();
    drop(builder);

    // 压缩壳
    let (arch_path, sha);
    if gz {
        arch_path = dir.join(format!("{name}.tar.gz"));
        let enc = flate2::write::GzEncoder::new(fs::File::create(&arch_path).unwrap(), flate2::Compression::default());
        let mut enc = enc;
        io::copy(&mut fs::File::open(&tar_path).unwrap(), &mut enc).unwrap();
        enc.finish().unwrap();
    } else {
        arch_path = dir.join(format!("{name}.tar.zst"));
        let enc = zstd::stream::write::Encoder::new(fs::File::create(&arch_path).unwrap(), 3).unwrap();
        let mut enc = enc.auto_finish();
        io::copy(&mut fs::File::open(&tar_path).unwrap(), &mut enc).unwrap();
    }
    // SHA256
    use sha2::{Digest, Sha256};
    let mut h = Sha256::new();
    h.update(&fs::read(&arch_path).unwrap());
    sha = hex::encode(h.finalize());
    let _ = tar_path;
    (arch_path, sha)
}

use std::io;
use std::path::PathBuf;

#[test]
fn 端到端_zstd包解压_文件树完整() {
    let dir = std::env::temp_dir().join(format!("zext_{}", std::process::id()));
    fs::create_dir_all(&dir).unwrap();
    let (arch, sha) = make_archive(&dir, "test_zstd", false);
    let out = dir.join("out");

    let mut progress_seen = 0u64;
    let report = extract_pipeline(
        &arch, &out, Some(&sha),
        &mut |p| { progress_seen = p.entries; },
    ).unwrap();

    assert_eq!(report.entries, 5, "条目数异常: {}", report.entries);
    assert!(report.bytes_written >= 600_000);
    assert!(progress_seen > 0);
    assert!(out.join("data/hello.txt").is_file());
    assert!(out.join("data/big.bin").is_file());
    assert!(out.join("data/link").exists());       // symlink 落位
    assert!(out.join("data/hardlink").is_file());  // 硬链接复制落地
    #[cfg(unix)]
    {
        // unix：真 symlink —— 先断言 linkname 原样落盘，再断言 read 穿透到同目录的 hello.txt。
        // 这一段**只在 Linux 上真正跑过**（Windows 走下面的空文件回退、整块被 cfg 掉），
        // 所以它是"把 cargo test 接进 CI"的直接收益：linkname 一旦被改成根相对，
        // 这里立刻会以 out/data/data/hello.txt 不存在而失败。
        assert_eq!(
            fs::read_link(out.join("data/link")).unwrap(),
            Path::new("hello.txt")
        );
        assert_eq!(
            fs::read(out.join("data/link")).unwrap(),
            fs::read(out.join("data/hello.txt")).unwrap()
        );
    }
    // windows host：symlink 走空文件回退（提取器语义），存在即可
    #[cfg(not(unix))]
    {
        assert_eq!(fs::metadata(out.join("data/link")).unwrap().len(), 0);
    }
    assert_eq!(report.archive_sha256, sha);
    fs::remove_dir_all(&dir).ok();
}

#[test]
fn 端到端_gzip包_兜底壳() {
    let dir = std::env::temp_dir().join(format!("zext_gz_{}", std::process::id()));
    fs::create_dir_all(&dir).unwrap();
    let (arch, sha) = make_archive(&dir, "test_gz", true);
    let out = dir.join("out");
    extract_pipeline(&arch, &out, Some(&sha), &mut |_| {}).unwrap();
    assert!(out.join("data/hello.txt").is_file());
    fs::remove_dir_all(&dir).ok();
}

#[test]
fn sha不匹配_报错且不落盘内容() {
    let dir = std::env::temp_dir().join(format!("zext_bad_{}", std::process::id()));
    fs::create_dir_all(&dir).unwrap();
    let (arch, _real_sha) = make_archive(&dir, "test_bad", false);
    let out = dir.join("out");
    let err = extract_pipeline(&arch, &out, Some(&"0".repeat(64)), &mut |_| {}).unwrap_err();
    match err {
        ExtractError::ShaMismatch { actual, .. } => assert_eq!(actual.len(), 64),
        e => panic!("应为 ShaMismatch，实际 {e:?}"),
    }
    // 校验在解压前——失败时目标目录不应有任何内容落盘
    let landed = fs::read_dir(&out).map(|rd| rd.count()).unwrap_or(0);
    assert_eq!(landed, 0, "SHA 失败不应落盘");
    fs::remove_dir_all(&dir).ok();
}

#[test]
fn 路径穿越_拒绝() {
    let dir = std::env::temp_dir().join(format!("zext_trav_{}", std::process::id()));
    fs::create_dir_all(&dir).unwrap();
    // 手工构造 USTAR 头（tar crate 在 append 阶段就拒绝 .. 路径——
    // 必须绕过它才能测到我们管线的穿越防御）
    fn evil_entry() -> Vec<u8> {
        let mut hdr = [0u8; 512];
        let name = b"../evil.txt";
        hdr[..name.len()].copy_from_slice(name);
        hdr[100..108].copy_from_slice(b"0000644\0");        // mode
        hdr[108..116].copy_from_slice(b"0000000\0");    // uid
        hdr[116..124].copy_from_slice(b"0000000\0");    // gid
        hdr[124..136].copy_from_slice(b"00000000004\0");    // size = 4 (octal)
        hdr[136..148].copy_from_slice(b"00000000000\0");    // mtime
        hdr[148..156].copy_from_slice(b"        ");         // chksum 占位（空格）
        hdr[156] = b'0';                                     // typeflag: 普通文件
        hdr[257..263].copy_from_slice(b"ustar\0");
        hdr[263..265].copy_from_slice(b"00");
        let sum: u32 = hdr.iter().map(|&b| b as u32).sum();
        hdr[148..156].copy_from_slice(format!("{:06o}\0 ", sum).as_bytes());
        let mut out = hdr.to_vec();
        out.extend_from_slice(b"evil");
        out.extend(std::iter::repeat(0u8).take(508));       // 补齐 block
        out
    }
    let zst = dir.join("evil.tar.zst");
    let enc = zstd::stream::write::Encoder::new(fs::File::create(&zst).unwrap(), 3).unwrap();
    let mut enc = enc.auto_finish();
    io::copy(&mut evil_entry().as_slice(), &mut enc).unwrap();
    drop(enc);

    let out = dir.join("out");
    // 安全属性本身：解压必须失败，且 ../evil.txt 绝不落到 out 目录之外
    assert!(extract_pipeline(&zst, &out, None, &mut |_| {}).is_err());
    assert!(!dir.join("evil.txt").exists(), "穿越文件落到了目标目录外");
    assert!(!out.join("evil.txt").exists());
    fs::remove_dir_all(&dir).ok();
}

// ── 补丁路径（E-050）：纯 tar 壳 + 跳过成员 ────────────────────────────────

#[test]
fn 纯tar包_无压缩壳_直接解压() {
    let dir = std::env::temp_dir().join(format!("zext_plain_{}", std::process::id()));
    fs::create_dir_all(&dir).unwrap();
    // make_archive 先写 <name>.tar 再套压缩壳 —— 那个 .tar 就是纯 tar，直接拿来用。
    // 补丁包（androidTest 用 commons-compress 直写）正是这个形状：没有 zstd/gzip 魔数。
    let (_arch, _sha) = make_archive(&dir, "test_plain", false);
    let tar_path = dir.join("test_plain.tar");
    assert!(tar_path.is_file(), "合成 tar 不在: {}", tar_path.display());
    use sha2::{Digest, Sha256};
    let mut h = Sha256::new();
    h.update(&fs::read(&tar_path).unwrap());
    let tar_sha = hex::encode(h.finalize());

    let out = dir.join("out");
    let report = extract_pipeline(&tar_path, &out, Some(&tar_sha), &mut |_| {}).unwrap();

    assert_eq!(report.entries, 5, "条目数异常: {}", report.entries);
    assert_eq!(report.skipped, 0);
    assert_eq!(report.archive_sha256, tar_sha, "纯 tar 的 SHA 也要前置校验");
    assert!(out.join("data/hello.txt").is_file());
    assert!(out.join("data/big.bin").is_file());
    fs::remove_dir_all(&dir).ok();
}

#[test]
fn 跳过成员_不落盘且计入skipped() {
    let dir = std::env::temp_dir().join(format!("zext_skip_{}", std::process::id()));
    fs::create_dir_all(&dir).unwrap();
    // 用 zstd 包（生产形状：压缩壳 + SHA 对账 + 跳过清单一起走）
    let (arch, sha) = make_archive(&dir, "test_skip", false);
    let out = dir.join("out");
    let skip = vec!["data/hello.txt".to_string()];

    let report = extract_pipeline_skip(&arch, &out, Some(&sha), &skip, &mut |_| {}).unwrap();

    assert_eq!(report.skipped, 1, "跳过计数");
    assert_eq!(report.entries, 4, "被跳过的成员不该计入 entries");
    assert!(
        !out.join("data/hello.txt").exists(),
        "被跳过的成员不该落盘（补丁元数据就是这么处理的）"
    );
    assert!(out.join("data/big.bin").is_file(), "其余成员照常落盘");
    assert_eq!(report.archive_sha256, sha, "跳过成员不影响归档整体 SHA");
    fs::remove_dir_all(&dir).ok();
}

// ── 空归档兜底（BUG-1）：0 条目 = 错误，不是成功 ──────────────────────────

#[test]
fn 空tar_报错而不是当成装成功() {
    let dir = std::env::temp_dir().join(format!("zext_empty_{}", std::process::id()));
    fs::create_dir_all(&dir).unwrap();
    // 合法但**一条成员都没有**的 tar：1024 个 0 字节 = 两个结束块（USTAR 的空归档）。
    // 这正是"能通过 tar 解析、但什么都没解出来"的形状 —— 以前 Rust 路径会 Ok(entries=0)。
    let tar_path = dir.join("empty.tar");
    fs::write(&tar_path, vec![0u8; 1024]).unwrap();
    use sha2::{Digest, Sha256};
    let mut h = Sha256::new();
    h.update(&fs::read(&tar_path).unwrap());
    let tar_sha = hex::encode(h.finalize());

    let out = dir.join("out");
    let err = extract_pipeline(&tar_path, &out, Some(&tar_sha), &mut |_| {}).unwrap_err();
    match err {
        ExtractError::EmptyArchive { skipped } => assert_eq!(skipped, 0),
        e => panic!("应为 EmptyArchive，实际 {e:?}"),
    }
    // 消息里必须带 Kotlin 侧认的那句话（EMPTY_ARCHIVE_MARK = "归档不含任何条目"）
    let msg = ExtractError::EmptyArchive { skipped: 0 }.to_string();
    assert!(
        msg.contains("归档不含任何条目"),
        "Kotlin 靠这句话分流，不能改: {msg}"
    );
    fs::remove_dir_all(&dir).ok();
}

#[test]
fn 成员全被跳过_同样报空归档() {
    let dir = std::env::temp_dir().join(format!("zext_allskip_{}", std::process::id()));
    fs::create_dir_all(&dir).unwrap();
    let (arch, sha) = make_archive(&dir, "test_allskip", false);
    let out = dir.join("out");
    // make_archive 的 5 个成员全进 skip_names ⇒ 可落盘 0 条
    let skip: Vec<String> = ["data/", "data/hello.txt", "data/big.bin", "data/link", "data/hardlink"]
        .iter()
        .map(|s| s.to_string())
        .collect();

    let err = extract_pipeline_skip(&arch, &out, Some(&sha), &skip, &mut |_| {}).unwrap_err();
    match err {
        ExtractError::EmptyArchive { skipped } => {
            assert_eq!(skipped, 5, "被跳过的成员数应如实报出")
        }
        e => panic!("应为 EmptyArchive，实际 {e:?}"),
    }
    fs::remove_dir_all(&dir).ok();
}

/// 加固-1（E-085）：软链 linkname 的边界判定 —— 纯词法，与 Kotlin 版
/// `PathGuard.linkStaysInside` 同判据、同用例（两版必须一起漂）。
#[test]
fn 软链目标必须落在解压根内() {
    let root = Path::new("/tmp/zd-link-guard/rootfs");
    let link = root.join("usr/lib/aarch64-linux-gnu/libfoo.so");

    // Debian 里绝大多数软链就是这个形状：相对链接所在目录
    assert!(link_stays_inside(root, &link, Path::new("libfoo.so.1.2.3")));
    // 用 .. 走回根内其它目录：合法（基础镜像里常见）
    assert!(link_stays_inside(
        root,
        &link,
        Path::new("../../../lib/libfoo.so.1")
    ));
    // 一路 .. 出去：拒（链接所在目录在根下 3 层，6 个 .. 已经翻到 /）
    assert!(!link_stays_inside(
        root,
        &link,
        Path::new("../../../../../../etc/passwd")
    ));
    // 空 linkname 没有合法语义
    assert!(!link_stays_inside(root, &link, Path::new("")));
}

/// 绝对形式的 linkname 按 **guest 根**（= 解压根）解释。
/// 只跑 unix：Windows 上 `Path::is_absolute()` 对 `/lib/x` 返回 false（没有盘符），
/// 那是宿主差异、不是判据本身；Kotlin 侧同判据用 `startsWith("/")` 判绝对，
/// 与 Android/unix 的语义一致。
#[cfg(unix)]
#[test]
fn 绝对形式的软链按解压根解释() {
    let root = Path::new("/tmp/zd-link-guard/rootfs");
    let link = root.join("lib64/ld-linux-aarch64.so.1");
    assert!(link_stays_inside(
        root,
        &link,
        Path::new("/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1")
    ));
    assert!(!link_stays_inside(root, &link, Path::new("/../../etc/passwd")));
}
