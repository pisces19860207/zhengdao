// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的公开资料：`std::fs` / `std::fs::symlink_metadata` 官方文档（POSIX `lstat` 语义）。
//
//! 目录占用统计纯逻辑层（v2.0 R3 新增，见 `docs/证道-Rust化余地审计-2026-10-09.md` 候选 A）。
//!
//! 只有一个函数：[`dir_size_bytes`] —— 递归求一个目录树的**普通文件字节数总和**。
//! 本模块**不含任何 JNI 代码**——JNI 薄层统一挂在 `crate::jni_bridge`，
//! 对应 Kotlin `com.example.zhengdao.rust.CoreNative.nativeDirSizeBytes`。
//!
//! ## 为什么值得下沉（且形态是对的）
//!
//! 设置页 / 首页要显示 `rootfs`(约 1.6 GB) + `home`(可达数 GB) 的占用，
//! 旧实现是 Java `Files.walkFileTree` 全量重算 —— 每次进页面都要再走一遍
//! 数万条 `stat`。这里下沉的价值不是"算得更快"，而是**边界只跨一次**：
//! 输入是一个路径字符串、输出是一个 u64，数据全程常驻 native
//! （与 sha256 那种"逐函数搬大块数据"的亏本形态相反，符合 `ERRATA E-012 §6` 的收益模型）。
//!
//! ## 语义必须与 Java 版逐条对齐（不然 Rust/Java 两条路径会给出不同数字）
//!
//! 对齐的是 `ui/SystemInfoProvider.dirSizeMb` 的既有行为：
//! 1. **跳过符号链接子树**（`rootfs` 里有指向整个共享存储的软链，跟随会把用户的
//!    照片视频全算进来 —— 这是真机上实测过的坑）；
//! 2. **只累加普通文件**（目录本身不计、设备/FIFO 不计）；
//! 3. **无权限的条目静默跳过、继续**（bind 挂载点常见 0000 模式）；
//! 4. 结果单位是**字节**（除以 1048576 是调用方的事 —— 保持纯函数不引入单位约定）。

use std::fs;
use std::path::Path;

/// 递归统计目录树下**普通文件**的字节数总和。
///
/// ## 语义必须与 Java 版 `SystemInfoProvider.dirSizeMb` **逐字节一致**
///
/// 这是回退路径的对拍基准（Rust 不可用时调用方跑 Java 版），所以两条路径对
/// **同一个输入**必须给出**同一个数字**：
/// - 入口是**目录** ⇒ 递归累加其下所有普通文件（见下）；
/// - 入口是**普通文件** ⇒ 返回该文件大小。
///   ⚠️ 这条看着反直觉，但是 Java 版的实际行为：`Files.walkFileTree(文件路径)`
///   会把它自己当成一个 entry 调一次 `visitFile`，于是 `attrs.isRegularFile()`
///   成立、大小被计入（2026-10-09 实测：3 MiB 的文件 → 3145728 字节，不是 0）。
///   为了两路一致，这里也照做——**不要让"看起来更合理"的 0 破坏对拍**。
///   （真实调用点只传目录，此分支是为一致性兜底，不是热点。）
/// - 入口不存在 / 是软链根 ⇒ `Ok(0)`（Java 版 `catch { 0L }` 的等价物）。
///
/// 目录内的规则：
/// - 符号链接**不跟随、也不统计**（用 `symlink_metadata` 取 `lstat` 语义：
///   拿到的是链接自身的元数据，不解析到目标，因此不会走进被链接的整棵子树）；
/// - 只累加 `is_file()` 的条目；
/// - 单个条目读元数据失败（无权限 / 被并发删除）**静默跳过**，不影响整棵树的结果。
///
/// 为避免深目录树把递归栈打爆，这里用**显式栈**迭代（Java 版是 `walkFileTree` 的回调，
/// 同样不依赖 JVM 调用栈深度）。
pub fn dir_size_bytes(path: &Path) -> u64 {
    // lstat：不解析软链。根若是软链 ⇒ 判 0（Java 版遇软链根走 visitFileFailed/跳过，等价 0）。
    let root_meta = match fs::symlink_metadata(path) {
        Ok(m) => m,
        Err(_) => return 0, // 不存在 / 无权限
    };
    // 与 Java 版对齐：入口是普通文件 ⇒ 返回文件大小（不是 0）。
    if root_meta.file_type().is_file() {
        return root_meta.len();
    }
    if !root_meta.is_dir() {
        return 0; // 软链根 / 设备 / FIFO 等
    }

    let mut total: u64 = 0;
    let mut stack: Vec<std::path::PathBuf> = vec![path.to_path_buf()];

    while let Some(dir) = stack.pop() {
        // 读目录失败（无权限）⇒ 跳过这一层，继续处理栈里其他目录。
        let entries = match fs::read_dir(&dir) {
            Ok(it) => it,
            Err(_) => continue,
        };
        for entry in entries {
            // 单个条目出错（并发删除 / 权限）⇒ 跳过这一条。
            let entry = match entry {
                Ok(e) => e,
                Err(_) => continue,
            };
            // lstat：不解析软链 —— 软链既不统计自身、也不进入其目标。
            let meta = match fs::symlink_metadata(entry.path()) {
                Ok(m) => m,
                Err(_) => continue,
            };
            let ft = meta.file_type();
            if ft.is_symlink() {
                continue; // 与 Java 版"跳过软链子树"完全一致
            }
            if ft.is_dir() {
                stack.push(entry.path());
            } else if ft.is_file() {
                total = total.saturating_add(meta.len());
            }
            // 其余（设备 / FIFO / socket）不计 —— 与 Java 版 `isRegularFile` 判定一致
        }
    }

    total
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    /// 造一个临时目录：`zd-dirsize-<pid>-<tag>`，先删后建，返回路径。
    fn scratch(tag: &str) -> std::path::PathBuf {
        let p = std::env::temp_dir().join(format!("zd-dirsize-{}-{}", std::process::id(), tag));
        let _ = fs::remove_dir_all(&p);
        fs::create_dir_all(&p).unwrap();
        p
    }

    #[test]
    fn 空目录为0() {
        let p = scratch("empty");
        assert_eq!(dir_size_bytes(&p), 0);
        let _ = fs::remove_dir_all(&p);
    }

    #[test]
    fn 统计单层文件字节数() {
        let p = scratch("flat");
        fs::write(p.join("a.bin"), vec![0u8; 100]).unwrap();
        fs::write(p.join("b.bin"), vec![0u8; 250]).unwrap();
        assert_eq!(dir_size_bytes(&p), 350);
        let _ = fs::remove_dir_all(&p);
    }

    #[test]
    fn 递归统计子目录() {
        let p = scratch("nested");
        fs::write(p.join("top.bin"), vec![0u8; 10]).unwrap();
        let sub = p.join("sub").join("deeper");
        fs::create_dir_all(&sub).unwrap();
        fs::write(sub.join("deep.bin"), vec![0u8; 5000]).unwrap();
        assert_eq!(dir_size_bytes(&p), 5010);
        let _ = fs::remove_dir_all(&p);
    }

    #[test]
    fn 不存在的路径返回0() {
        let missing = std::env::temp_dir().join("zd-dirsize-必然不存在-404");
        assert_eq!(dir_size_bytes(&missing), 0);
    }

    #[test]
    fn 普通文件入口返回文件大小与java版一致() {
        // ⚠️ 反直觉但必须如此：Java 的 walkFileTree(文件) 会把它自己计入 → 返回文件大小。
        // 两路对拍要求这里也一样（2026-10-09 实测 Java: 3 MiB 文件 → 3145728）。
        let p = scratch("is_file");
        let f = p.join("x.bin");
        fs::write(&f, vec![0u8; 42]).unwrap();
        assert_eq!(dir_size_bytes(&f), 42);
        let _ = fs::remove_dir_all(&p);
    }

    /// 软链**不跟随**：指向目录的软链不计算其目标内容，也不递归进去。
    /// 这是对齐 Java 版"跳过软链子树"的关键用例（rootfs 里有指向共享存储的软链）。
    #[cfg(unix)]
    #[test]
    fn 软链不跟随() {
        let outside = scratch("outside");
        fs::write(outside.join("big.bin"), vec![0u8; 9999]).unwrap();

        let p = scratch("withlink");
        fs::write(p.join("keep.bin"), vec![0u8; 7]).unwrap();
        std::os::unix::fs::symlink(&outside, p.join("link_to_outside")).unwrap();

        // 只应统计 keep.bin（7 字节），不把 outside 的 9999 字节算进来。
        assert_eq!(dir_size_bytes(&p), 7);

        let _ = fs::remove_dir_all(&p);
        let _ = fs::remove_dir_all(&outside);
    }

    /// 软链指向**普通文件**也不跟随：那 9999 字节不算。
    #[cfg(unix)]
    #[test]
    fn 软链指向文件不计入() {
        let outside = scratch("outside2");
        let target = outside.join("big.bin");
        fs::write(&target, vec![0u8; 9999]).unwrap();

        let p = scratch("withfilelink");
        fs::write(p.join("real.bin"), vec![0u8; 3]).unwrap();
        std::os::unix::fs::symlink(&target, p.join("link_to_file")).unwrap();

        assert_eq!(dir_size_bytes(&p), 3);

        let _ = fs::remove_dir_all(&p);
        let _ = fs::remove_dir_all(&outside);
    }
}
