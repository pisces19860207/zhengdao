#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""rebuild-and-compare.py —— CI「重编比对」lane（第㊶刀；㉗② 方案 A′，观察期）

为什么需要它
    tools/check-native-so.py   只查 .so 自身结构（16KB 页对齐 + JNI 入口符号）。
    tools/check-so-provenance.py 只查「.so 与 rust/core/src 的同源基线一致」——而那份基线是
    **基线驱动**的：谁把 tools/so-provenance.json 的哈希刷成当前值，它就变绿，产物却根本没重编。
    （第㉗②-A 用 check-baseline-refresh.py 堵了这条捷径，但那也只要求「基线动了必须连带动 .so
    或 rust/core/src」，仍**不要求「能从当前源码编出同样的字节」**。）
    本脚本补的就是最后一环：**从当前源码重新编一遍，与归一化基线逐字节比对**。

第㉗② 可行性取证（2026-10-15 本机 Windows 实测；报告 证道设计报告/证道-第27刀②-重编逐字节比对可行性-2026-10-15.{md,html}）
    - 四次**全新 target** 构建产出的 .so 逐字节相同，且 == 入库件（962,376 B / sha256 ffad1eba…8088a）。
    - 但入库件烘进了 **27 处本机绝对路径**（C:\\Users\\guoli\\.cargo\\registry\\src\\index.crates.io-…\\<crate>\\src\\…），
      所以 CI（ubuntu，/home/runner/.cargo/…）**照现状不可能逐字节相同**。
    - 加 `--remap-path-prefix` 归一后路径消失（实测 0 处命中），产物变成
      **961,960 B / sha256 3e5f400813d06794c49b8590d09be7578a34095a590394ee90a2c0fd48bf5559**（本机实测）
      —— 这个值就是本脚本比对的「归一化基线」。
    - **未证实**：ubuntu 与 Windows 是否产出相同字节。这正是本 lane 观察期要回答的问题。

观察期纪律（用户 2026-10-15 拍板案 A′）
    .github/workflows/ci.yml 里这一步 **continue-on-error: true**：只报数、不卡 CI。
    读数走 `::notice` / `::warning` annotation 抛出——本仓库 Actions 日志未登录读不到，而
    check-runs annotations 接口**匿名可读**（同类处置见 ci.yml 的 Rust 单测段，ERRATA E-028）。
    拿 2–3 次读数后再决定「转硬门禁」还是「退案 B（只做本机发布前脚本）」。

本机与 CI 的差异（必须显式注入，否则 CI 编不动）
    rust/.cargo/config.toml 里的 `linker` 与 `[env] CC_/AR_` 全是 **Windows 绝对路径**
    （`C:/Users/guoli/AppData/Local/Android/Sdk/ndk/…/windows-x86_64/bin/aarch64-linux-android35-clang.cmd`），
    在 ubuntu 上根本不存在。所以本脚本按**本机实际 NDK 目录**重新拼出并注入：
      CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER —— 环境变量**优先于** config 的 `linker`
      CC_aarch64_linux_android / AR_aarch64_linux_android —— cargo 的 `[env]` 默认 `force = false`
      ⇒ 进程里已有的环境变量不会被 config 覆盖
    另外 `RUSTFLAGS` 一旦设置就会**覆盖** config 的 `[target.…] rustflags` ⇒ 两个 16KB 页对齐 flag
    必须在这里显式再给一遍（漏了不会报错，只会静默丢掉 16KB 对齐，真机上 loadLibrary 失败）。

用法
    python3 tools/rebuild-and-compare.py [仓库根]              # 重编并比对（CI / 本机）
    python3 tools/rebuild-and-compare.py --offline [仓库根]     # 离线构建（本机常用）
    python3 tools/rebuild-and-compare.py --update [仓库根]      # 刷新归一化基线（慎用，须连同提交）
    python3 tools/rebuild-and-compare.py --keep [仓库根]        # 保留临时 target 目录（排障）

退出码
    0 = 一致（或 --update 成功） ；1 = 不一致 ；2 = 环境/用法错（**fail-closed**：探测不到的输入一律 2，绝不静默跳过）

只依赖标准库。**绝不写 app/src/main/jniLibs/**（入库 .so 由人工工序维护，见 rust/README.md）。
"""

import argparse
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

TARGET_TRIPLE = "aarch64-linux-android"
LIB_FILE = "libzhengdao_core.so"
BASELINE_REL = os.path.join("tools", "so-repro-baseline.json")
PAGE_SIZE_FLAGS = [
    "-C",
    "link-arg=-Wl,-z,max-page-size=16384",
    "-C",
    "link-arg=-Wl,-z,common-page-size=16384",
]
REMAP_CARGO_HOME = "/cargo"
REMAP_REPO_ROOT = "/src"

EXIT_OK = 0
EXIT_MISMATCH = 1
EXIT_ENV = 2


# ────────────────────────────── 输出 ──────────────────────────────

def annotate(level, title, message):
    """GitHub annotation（日志未登录读不到，annotations 接口匿名可读 ⇒ 观察期读数靠它）。"""
    text = " ".join(str(message).replace("\r", " ").replace("\n", " | ").split())
    if os.environ.get("GITHUB_ACTIONS") == "true":
        print("::%s title=%s::%s" % (level, title, text))
    print("[%s] %s: %s" % (level.upper(), title, text))


def fail_env(what, detail=""):
    annotate("error", "重编比对·环境问题", "%s%s" % (what, ("：" + detail) if detail else ""))
    sys.exit(EXIT_ENV)


def preserve_log(log_path):
    """失败路径也要留下完整构建日志（否则临时目录一删，排障就只剩 tail 的 40 行）。"""
    dest = Path(tempfile.gettempdir()) / "zd-repro-build.log"
    try:
        shutil.copy2(log_path, dest)
        print("[info] 完整构建日志：%s" % dest)
    except OSError as exc:
        print("[warn] 日志副本失败：%r" % (exc,))


def tail_of(path, n=40):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            lines = f.read().splitlines()
    except OSError:
        return ""
    return "\n".join(lines[-n:])


# ────────────────────────────── 探测（全部 fail-closed） ──────────────────────────────

def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def sha256_text_crlf_to_lf(path):
    """源码类输入按『git 归一后的内容』哈希（本机 CRLF、CI LF ⇒ 同一提交必须同值）。"""
    with open(path, "rb") as f:
        data = f.read()
    return hashlib.sha256(data.replace(b"\r\n", b"\n")).hexdigest()


def read_ndk_version(repo):
    """NDK 版本单一真源 = app/build.gradle.kts 的 ndkVersion（§B 57 第③条）。"""
    path = repo / "app" / "build.gradle.kts"
    if not path.is_file():
        fail_env("找不到 app/build.gradle.kts", str(path))
    text = path.read_text(encoding="utf-8", errors="replace")
    m = re.search(r'^\s*ndkVersion\s*=\s*"([^"]+)"', text, re.M)
    if not m:
        fail_env("app/build.gradle.kts 里找不到 ndkVersion = \"…\"（§B 57 第③条的单一真源）")
    return m.group(1)


def find_sdk_root():
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        val = os.environ.get(key)
        if val and os.path.isdir(val):
            return Path(val)
    fail_env("ANDROID_HOME / ANDROID_SDK_ROOT 都没设或不存在", "CI 上由 setup-android 预装提供")


def _rank_ndk_tool(path, prefer_api):
    """NDK 的 bin/ 里同一个工具常有多种包装器：Linux/mac 用的无扩展名脚本、Windows 的 .cmd / .exe。
    Windows 上 exec 无扩展名的 shell 脚本会直接失败（实测 os error 193「不是有效的 Win32 应用程序」）
    ⇒ 必须按平台挑包装器；再优先带 API 级别（config.toml 用的是 aarch64-linux-android35-clang.cmd）。"""
    base = os.path.basename(path).lower()
    is_windows = os.name == "nt"
    plat_ok = base.endswith(".cmd") or base.endswith(".exe") if is_windows \
        else not base.endswith((".cmd", ".exe"))
    api_ok = (prefer_api in base) if prefer_api else True
    # C 驱动优先于 C++ 驱动：zstd 的 cover.c 用 qsort_r，按 C++ 编会报
    # 「use of undeclared identifier 'qsort_r'」（实测），config.toml 用的也是 clang 而非 clang++。
    cxx = 1 if "++" in base else 0
    return (0 if plat_ok else 1, cxx, 0 if api_ok else 1, base)


def find_ndk_tool(ndk_dir, pattern, prefer_api=None, what=None):
    hits = glob.glob(str(ndk_dir / "toolchains" / "llvm" / "prebuilt" / "*" / "bin" / pattern))
    if not hits:
        fail_env("NDK 里找不到 %s" % (what or pattern), str(ndk_dir))
    hits.sort(key=lambda h: _rank_ndk_tool(h, prefer_api))
    chosen = Path(hits[0])
    if len(hits) > 1:
        print("[info] %s 命中 %d 个，按平台包装器取 %s" % (pattern, len(hits), chosen.name))
    if os.name == "nt" and not (chosen.suffix.lower() in (".cmd", ".exe")):
        fail_env("在 Windows 上选中了非可执行包装器 %s" % chosen, "按平台挑 .cmd/.exe")
    return chosen


def cargo_home():
    val = os.environ.get("CARGO_HOME")
    if val and os.path.isdir(val):
        return Path(val)
    default = Path.home() / ".cargo"
    if not default.is_dir():
        fail_env("找不到 cargo 目录（$CARGO_HOME 与 ~/.cargo 都不存在）")
    return default


def rustc_fingerprint():
    try:
        out = subprocess.run(["rustc", "-vV"], capture_output=True, text=True, timeout=120)
    except (OSError, subprocess.SubprocessError) as exc:
        fail_env("跑不了 rustc -vV", repr(exc))
    if out.returncode != 0:
        fail_env("rustc -vV 退出码 %d" % out.returncode, out.stderr.strip())
    text = out.stdout
    ver = re.search(r"^release:\s*(\S+)", text, re.M)
    commit = re.search(r"^commit-hash:\s*([0-9a-f]{40})", text, re.M)
    host = re.search(r"^host:\s*(\S+)", text, re.M)
    if not (ver and commit and host):
        fail_env("rustc -vV 输出读不出 release / commit-hash / host", text.strip())
    return {
        "version": ver.group(1),
        "commit_hash": commit.group(1),
        "host": host.group(1),
        "vV": text.strip(),
    }


def cargo_version():
    try:
        out = subprocess.run(["cargo", "-V"], capture_output=True, text=True, timeout=120)
    except (OSError, subprocess.SubprocessError) as exc:
        fail_env("跑不了 cargo -V", repr(exc))
    return out.stdout.strip()


def ensure_target_installed():
    """aarch64-linux-android 目标必须已装（rust-toolchain.toml 的 targets 会装它；这里兜底）。"""
    try:
        listed = subprocess.run(["rustup", "target", "list", "--installed"],
                                capture_output=True, text=True, timeout=180)
    except (OSError, subprocess.SubprocessError) as exc:
        fail_env("跑不了 rustup target list（rustup 不在 PATH？）", repr(exc))
    if listed.returncode != 0:
        fail_env("rustup target list 退出码 %d" % listed.returncode, listed.stderr.strip())
    if TARGET_TRIPLE in listed.stdout:
        return
    print("[info] %s 未安装，执行 rustup target add" % TARGET_TRIPLE)
    added = subprocess.run(["rustup", "target", "add", TARGET_TRIPLE],
                           capture_output=True, text=True, timeout=900)
    if added.returncode != 0:
        fail_env("rustup target add %s 失败" % TARGET_TRIPLE, added.stderr.strip())


# ────────────────────────────── 构建 + strip ──────────────────────────────

def build_and_strip(repo, workdir, offline, keep, no_remap=False):
    """返回 (归一化后 .so 的 (size, sha256), 未 strip 的 (size, sha256), 环境信息 dict)。"""
    ndk_version = read_ndk_version(repo)
    sdk = find_sdk_root()
    ndk_dir = sdk / "ndk" / ndk_version
    if not ndk_dir.is_dir():
        fail_env("NDK %s 不在 SDK 里（CI 上由 ci.yml 的 sdkmanager 装）" % ndk_version, str(ndk_dir))
    clang = find_ndk_tool(ndk_dir, "aarch64-linux-android*-clang*",
                          prefer_api="aarch64-linux-android35-clang", what="aarch64-linux-android<API>-clang")
    llvm_ar = find_ndk_tool(ndk_dir, "llvm-ar*", what="llvm-ar")
    llvm_strip = find_ndk_tool(ndk_dir, "llvm-strip*", what="llvm-strip")

    ch = cargo_home()
    rustc = rustc_fingerprint()
    ensure_target_installed()

    target_dir = Path(tempfile.mkdtemp(prefix="zd-repro-", dir=str(workdir)))
    log_path = target_dir / "build.log"

    rustflags = list(PAGE_SIZE_FLAGS)
    if no_remap:
        print("[warn] --no-remap：本次**不**做路径归一（只给页对齐 flag）——仅供负例自证，产物不可作为基线")
    else:
        rustflags += [
            "--remap-path-prefix=%s=%s" % (ch, REMAP_CARGO_HOME),
            "--remap-path-prefix=%s=%s" % (repo, REMAP_REPO_ROOT),
        ]
    env = os.environ.copy()
    env["RUSTFLAGS"] = " ".join(rustflags)
    env["CARGO_TARGET_%s_LINKER" % TARGET_TRIPLE.upper().replace("-", "_")] = str(clang)
    env["CC_%s" % TARGET_TRIPLE.replace("-", "_")] = str(clang)
    env["AR_%s" % TARGET_TRIPLE.replace("-", "_")] = str(llvm_ar)

    cmd = ["cargo", "build", "--release", "--target", TARGET_TRIPLE, "--target-dir", str(target_dir)]
    if offline:
        cmd.append("--offline")

    print("[info] rustc      = %s (%s)" % (rustc["version"], rustc["commit_hash"]))
    print("[info] host       = %s" % rustc["host"])
    print("[info] cargo      = %s" % cargo_version())
    print("[info] NDK        = %s" % ndk_dir)
    print("[info] linker     = %s" % clang)
    print("[info] llvm-strip = %s" % llvm_strip)
    print("[info] RUSTFLAGS  = %s" % env["RUSTFLAGS"])
    print("[info] target-dir = %s" % target_dir)
    print("[info] 构建：%s" % " ".join(cmd))

    with open(log_path, "wb") as lf:
        proc = subprocess.run(cmd, cwd=str(repo / "rust"), env=env, stdout=lf, stderr=subprocess.STDOUT)
    if proc.returncode != 0:
        print(tail_of(log_path))
        preserve_log(log_path)
        fail_env("cargo build 失败（退出码 %d）" % proc.returncode, "完整日志 %s" % log_path)

    built = target_dir / TARGET_TRIPLE / "release" / LIB_FILE
    if not built.is_file():
        fail_env("构建产物不存在", str(built))

    raw_size = built.stat().st_size
    raw_sha = sha256_file(built)

    stripped = target_dir / ("stripped-" + LIB_FILE)
    shutil.copy2(built, stripped)
    strip_proc = subprocess.run([str(llvm_strip), "--strip-unneeded", str(stripped)],
                                capture_output=True, text=True)
    if strip_proc.returncode != 0:
        fail_env("llvm-strip 失败（退出码 %d）" % strip_proc.returncode, strip_proc.stderr.strip())

    norm = {"size": stripped.stat().st_size, "sha256": sha256_file(stripped)}
    raw = {"size": raw_size, "sha256": raw_sha}

    inputs = {
        "target_triple": TARGET_TRIPLE,
        # rustc 只记 version + commit_hash（**host 三元组是宿主特定的，不进比对**：
        # 本机是 x86_64-pc-windows-gnu、CI 是 x86_64-unknown-linux-gnu，比它必红）。
        "rustc": {"version": rustc["version"], "commit_hash": rustc["commit_hash"]},
        "ndk_version": ndk_version,
        "page_size_link_flags": PAGE_SIZE_FLAGS,
        "remap": {"cargo_home": REMAP_CARGO_HOME, "repo_root": REMAP_REPO_ROOT},
        "cargo_lock_sha256": sha256_text_crlf_to_lf(repo / "rust" / "Cargo.lock"),
    }
    host = rustc["host"]

    if not keep:
        # 保留 log 供排障：把日志挪到临时目录外
        keep_log = Path(tempfile.gettempdir()) / "zd-repro-build.log"
        try:
            shutil.copy2(log_path, keep_log)
            print("[info] 构建日志副本：%s" % keep_log)
        except OSError:
            pass
        shutil.rmtree(target_dir, ignore_errors=True)
    else:
        print("[info] --keep：临时目录保留在 %s" % target_dir)

    return norm, raw, inputs, host


# ────────────────────────────── 基线比对 ──────────────────────────────

def load_baseline(path):
    if not path.is_file():
        fail_env("归一化基线不存在（先跑一次 --update 生成并提交）", str(path))
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def check_inputs(baseline, inputs):
    """构建输入锁必须逐项对得上；缺键/不一致一律 fail-closed。"""
    bad = []
    bi = baseline.get("build_inputs")
    if not isinstance(bi, dict):
        fail_env("基线里没有 build_inputs 段（旧格式？）", str(BASELINE_REL))
    for key in ("target_triple", "ndk_version", "cargo_lock_sha256"):
        if key not in bi:
            bad.append("基线缺 build_inputs.%s" % key)
        elif bi[key] != inputs[key]:
            bad.append("%s：基线 %r ≠ 当前 %r" % (key, bi[key], inputs[key]))
    if bi.get("page_size_link_flags") != inputs["page_size_link_flags"]:
        bad.append("page_size_link_flags：基线 %r ≠ 当前 %r"
                   % (bi.get("page_size_link_flags"), inputs["page_size_link_flags"]))
    if bi.get("remap") != inputs["remap"]:
        bad.append("remap：基线 %r ≠ 当前 %r" % (bi.get("remap"), inputs["remap"]))
    for key in ("version", "commit_hash"):
        if bi.get("rustc", {}).get(key) != inputs["rustc"][key]:
            bad.append("rustc.%s：基线 %r ≠ 当前 %r"
                       % (key, bi.get("rustc", {}).get(key), inputs["rustc"][key]))
    if bad:
        for b in bad:
            print("  ✗ %s" % b)
        fail_env("构建输入锁不一致（%d 项）" % len(bad),
                 "换编译器/NDK/依赖/归一化前缀都会走到这里；确认后跑 --update 并连同产物一起提交")
    print("[info] 构建输入锁：全部一致 ✔")


def write_baseline(path, norm, raw, inputs, host):
    data = {
        "version": 1,
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "generator": "tools/rebuild-and-compare.py --update（第㊶刀 · 观察期）",
        "generated_on_host": host,
        "hash_mode": "so=raw-bytes-after-llvm-strip --strip-unneeded（remap 归一后）",
        "normalized": norm,
        "raw": raw,
        "build_inputs": inputs,
        "note": ("归一化 = cargo home → /cargo、仓库根 → /src；未归一化的产物含 27 处本机绝对路径，"
                 "跨主机不可比（见 证道设计报告/证道-第27刀②-重编逐字节比对可行性-2026-10-15.md）。"
                 "观察期：ci.yml 里这一步 continue-on-error，只报数不卡 CI。"),
    }
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=2, sort_keys=False)
        f.write("\n")


# ────────────────────────────── 主流程 ──────────────────────────────

def main():
    ap = argparse.ArgumentParser(
        description="从当前源码重编 .so，与归一化基线逐字节比对（第㊶刀 · 观察期）")
    ap.add_argument("repo", nargs="?", default=None, help="仓库根（缺省＝本脚本上一级）")
    ap.add_argument("--offline", action="store_true", help="cargo build --offline（本机常用）")
    ap.add_argument("--update", action="store_true", help="刷新归一化基线（慎用）")
    ap.add_argument("--keep", action="store_true", help="保留临时 target 目录（排障）")
    ap.add_argument("--no-remap", action="store_true",
                    help="负例自证用：故意不做路径归一（产物必然与基线不同 ⇒ 应判红）")
    args = ap.parse_args()

    repo = Path(args.repo).resolve() if args.repo else Path(__file__).resolve().parent.parent
    if not (repo / "rust" / "Cargo.toml").is_file():
        fail_env("这不像证道仓库根（缺 rust/Cargo.toml）", str(repo))

    baseline_path = repo / BASELINE_REL
    workdir = Path(tempfile.mkdtemp(prefix="zd-repro-work-"))
    try:
        norm, raw, inputs, host = build_and_strip(repo, workdir, args.offline, args.keep, args.no_remap)
        print("[info] 归一化产物：%d B / sha256 %s" % (norm["size"], norm["sha256"]))
        print("[info] 未归一化  ：%d B / sha256 %s" % (raw["size"], raw["sha256"]))

        if args.update:
            write_baseline(baseline_path, norm, raw, inputs, host)
            annotate("notice", "重编比对·基线已刷新",
                     "%s ← %d B / %s" % (BASELINE_REL, norm["size"], norm["sha256"]))
            return EXIT_OK

        baseline = load_baseline(baseline_path)
        check_inputs(baseline, inputs)

        expect = baseline.get("normalized") or {}
        exp_size, exp_sha = expect.get("size"), expect.get("sha256")
        if not isinstance(exp_size, int) or not isinstance(exp_sha, str):
            fail_env("基线 normalized 段缺 size/sha256", str(baseline_path))

        same_size = norm["size"] == exp_size
        same_hash = norm["sha256"] == exp_sha

        if same_size and same_hash:
            annotate("notice", "重编比对（观察期）",
                     "与归一化基线逐字节一致 ✔ rustc=%s(%s) 产物=%d B/%s"
                     % (inputs["rustc"]["version"], host,
                        norm["size"], norm["sha256"]))
            return EXIT_OK

        annotate("warning", "重编比对（观察期）·不一致",
                 "归一化产物 %d B/%s ≠ 基线 %d B/%s；rustc=%s(%s) host=%s"
                 % (norm["size"], norm["sha256"], exp_size, exp_sha,
                    inputs["rustc"]["version"], inputs["rustc"]["commit_hash"], host))
        print("[diff] size 相差 %+d B；sha256 %s"
              % (norm["size"] - exp_size, "一致" if same_hash else "不一致"))
        return EXIT_MISMATCH
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
