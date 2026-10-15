#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-so-provenance.py —— 入库 .so 与 Rust 源码的「同源」门禁（第⑬刀，E-128）。

为什么需要它
    现有 tools/check-native-so.py 只查「这个 .so 本身结构好不好」：
    16KB 页对齐 + JNI 入口符号。它**查不出「这个 .so 是不是这份源码编的」**。
    E-128 实录：入库 .so 停在 2026-10-12（提交 2b8507e），而 Rust 源码改到
    2026-10-14（提交 7eb394d）——二进制落后源码两天，真机跑的是旧逻辑，
    所有门禁全绿，没有任何一步会红。
    用户硬规矩：绝对不允许改了 Rust 源码却不重编 .so 就提交；改完必须过
    同源门禁才算数。

它记什么、比什么
    基线记录在 tools/so-provenance.json（本脚本 --write 生成/刷新）：
      1. app/src/main/jniLibs/**/libzhengdao_core.so 的 sha256（按相对路径）
      2. rust/core/src/** 的树指纹：
         - 逐文件 sha256（按相对路径排序，用于红时指出**哪个文件变了**）
         - 汇总指纹 = 对 "相对路径\\n" + "该文件sha256\\n" 逐行喂 SHA256 的最终值
         （不含 target/ —— src 下本来也没有；构建产物不参与指纹）

    绿 = 当前 .so 与当前源码树**都**与基线一致；
    红 = 任一不一致，逐条打印哪个文件变了 / 加了 / 删了。

构建输入锁（第㉗②-A，§B 57）
    同源基线只管「.so 与源码同不同步」；它管不了**「同一个源码、换一把工具链编出来
    的 .so 也是合法入库」**这件事。所以 baseline 里另有 build_inputs 段，记的是
    「这份 .so 是被什么编出来的」：
      - rustc.version / release_date / commit_hash、clang、linker：**取自入库 .so
        自身内嵌的编译器指纹**（`rustc version x.y.z (shorthash date)` 与
        `/rustc/<40hex>/` 路径、`Android (…) clang version …`、`Linker: LLD …`），
        产物自证 —— 比读配置更硬：换一把 rustc/NDK 重编，即使源码一字未改，这里也红。
      - ndk.version：读 app/build.gradle.kts 的 `ndkVersion`（**单一真源**；
        .github/workflows/ci.yml 也从同一行读，见 §B 57 第③条）。
      - cargo_lock_sha256：rust/Cargo.lock 按 CRLF→LF 归一后的 sha256（依赖树变了 = 输入变了）。
      - page_size_link_flags：rust/.cargo/config.toml 里的 `-Wl,-z,…=<数>` 链接参数
        （16KB 页对齐的**输入**；产物侧的对齐由 check-native-so.py 判）。
      - target_triple：jniLibs 的 ABI 目录名 + ELF e_machine 推出的目标三元组。
    取不到的键**绝不编**：写 null（并在报告里说明），校验时该键探测不到即红（fail-closed）。

行尾归一（重要）
    rust/core/src/** 的源码按「git 归一后的内容」哈希：读入后先把 CRLF 换成 LF，再算 sha256。
    本机 core.autocrlf=true（Windows 检出 = CRLF），CI 是 Linux（LF）；若对工作树原始字节算
    哈希，同一提交会「本机红、CI 绿」（第⑬刀实测：tests.rs 基线记的是作者工作树的 LF
    25289B，合并后 Windows 检出 25850B/CRLF ⇒ 门禁红）。门禁判的是「源码内容变没变」，
    不是「你盘上是什么行尾」。入库 .so 是二进制，按原始字节哈希，不归一。

用法
    python tools/check-so-provenance.py [仓库根]     # 校验（CI 用）
    python tools/check-so-provenance.py --write [仓库根]  # 仅用于「确认重编后」刷新基线
                                                          # （总是打印旧值与新值）

退出码
    0 = 绿 ；1 = 红（同源破坏或无基线） ；2 = 用法/环境错误
    注意：红 ≠ 一定是忘了重编——也可能是改了源码还没入库新 .so。
    恢复绿的唯一合法路径：按 rust/README.md 工序重编 + llvm-strip + 入库 +
    过 check-native-so.py，然后 --write 刷新本基线（提交里带上新 JSON）。

只依赖标准库。CI 里由 .github/workflows/ci.yml 在 check-native-so.py 旁调用，失败即红。
"""

import hashlib
import json
import re
import sys
import time
from pathlib import Path

try:  # Windows 控制台默认 GBK：个别字符编不出来时降级为替换，而不是让门禁自己崩
    sys.stdout.reconfigure(errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

SO_GLOB = "app/src/main/jniLibs/**/libzhengdao_core.so"
SRC_DIR = "rust/core/src"
BASELINE_JSON = "tools/so-provenance.json"

# ── 构建输入锁（第㉗②-A）用到的路径与判据 ──
CARGO_LOCK = "rust/Cargo.lock"
CARGO_CONFIG = "rust/.cargo/config.toml"
GRADLE_FILE = "app/build.gradle.kts"
PAGE_16K = 0x4000

# ABI 目录名 → Rust target triple。新增 ABI 必须同时在这里登记，否则 target_triple 写 null。
ABI_TRIPLE = {"arm64-v8a": "aarch64-linux-android"}
# ELF e_machine → 人类可读架构名，用来交叉核对上面的 ABI 目录名没写错。
ELF_MACHINE_NAME = {183: "aarch64", 62: "x86_64", 40: "arm", 3: "x86"}

# 产物内嵌的编译器指纹（都是实测存在的形式；取不到就是探测失败，不许编）
RUSTC_VERSION_RE = re.compile(
    rb"rustc version ([0-9][0-9A-Za-z.\-]*) \(([0-9a-f]{7,40}) ([0-9]{4}-[0-9]{2}-[0-9]{2})\)"
)
RUSTC_PATH_RE = re.compile(rb"/rustc/([0-9a-f]{40})/")
_CLANG_STR_RE = re.compile(r"^Android \(\d+, [^)]*\) clang version \d[\d.]*")
_LINKER_STR_RE = re.compile(r"^Linker: LLD \d[\d.]*")
# gradle 里的 NDK 版本（单一真源）；ci.yml 用同一行取版本装 NDK
NDK_GRADLE_RE = re.compile(r'ndkVersion\s*=\s*"([^"]+)"')
# .cargo/config.toml 里的页大小链接参数（形状：-Wl,-z,max-page-size=16384）
LINK_FLAG_RE = re.compile(r"-Wl,-z,[A-Za-z0-9_.\-]+=\d+")

_MISSING = object()


def sha256_file(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(256 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def sha256_source(p: Path) -> str:
    """源码文件按「git 归一后的内容」哈希：CRLF → LF 之后再算 sha256。

    为什么必须归一：core.autocrlf=true 的 Windows 检出是 CRLF、CI（Linux）是 LF。
    对工作树原始字节算哈希 ⇒ 同一个提交「本机红、CI 绿」，门禁变成在量行尾而不是量内容
    （第⑬刀实测：tests.rs 基线 25289B/LF，合并后检出 25850B/CRLF）。只归一 CRLF，
    不动单独出现的 CR（那不是行尾，是内容）。.so 走 sha256_file，不归一。
    """
    return hashlib.sha256(p.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def _strings_from_bytes(data: bytes, min_len: int = 8) -> list[str]:
    """抽出可打印 ASCII 串（≥ min_len）——用于从产物里读编译器指纹。"""
    out: list[str] = []
    cur = bytearray()
    for b in data:
        if 32 <= b < 127:
            cur.append(b)
        else:
            if len(cur) >= min_len:
                out.append(cur.decode("ascii"))
            cur = bytearray()
    if len(cur) >= min_len:
        out.append(cur.decode("ascii"))
    return out


def detect_build_inputs(repo: Path, so_map: dict[str, str]) -> dict:
    """探测「这份 .so 是被什么编出来的」。探测不到的键留 None —— 绝不编。

    证据来源（全部离线、全部可复现）：
      - 产物内嵌：`rustc version x.y.z (shorthash date)`、`/rustc/<40hex>/`、
        `Android (…) clang version …`、`Linker: LLD …`
      - 仓库静态：app/build.gradle.kts 的 ndkVersion、rust/Cargo.lock 内容、
        rust/.cargo/config.toml 的 -Wl,-z,… 链接参数
    """
    inputs: dict = {
        "comment": (
            "第㉗②-A 构建输入锁：这些输入决定入库 .so 里的机器码。rustc.* / clang / linker "
            "全部取自 .so 自身内嵌的编译器指纹（产物自证：换一把 rustc/NDK 编出来的 .so，"
            "即使源码一字未改，这里也会红）；ndk.version 取自 app/build.gradle.kts（单一真源，"
            "ci.yml 从同一行读）；cargo_lock_sha256 按 CRLF→LF 归一；page_size_link_flags "
            "是 16KB 对齐的输入。探测不到的键写 null，校验时按 fail-closed 处理。"
        ),
        "target_triple": None,
        "rustc": {"version": None, "release_date": None, "commit_hash": None},
        "ndk": {"version": None},
        "cargo_lock_sha256": None,
        "page_size_link_flags": [],
        "artifact_fingerprints": {},
    }

    # ① target triple：ABI 目录名（唯一）+ ELF e_machine 交叉核对
    abi_dirs = sorted({Path(rel).parent.name for rel in so_map})
    triples = {ABI_TRIPLE[a] for a in abi_dirs if a in ABI_TRIPLE}
    if len(triples) == 1 and all(a in ABI_TRIPLE for a in abi_dirs):
        inputs["target_triple"] = triples.pop()

    # ② 产物内嵌指纹（每个 .so 各记一条）
    for rel in sorted(so_map):
        data = (repo / rel).read_bytes()
        m_ver = RUSTC_VERSION_RE.search(data)
        m_path = RUSTC_PATH_RE.search(data)
        if m_ver:
            inputs["rustc"]["version"] = m_ver.group(1).decode("ascii")
            inputs["rustc"]["release_date"] = m_ver.group(3).decode("ascii")
            short = m_ver.group(2).decode("ascii")
            full = m_path.group(1).decode("ascii") if m_path else None
            # 两条独立证据（短哈希 vs /rustc/<40hex>/ 路径）应互相印证；不一致就只记短哈希
            inputs["rustc"]["commit_hash"] = full if (full and full.startswith(short)) else short
        elif m_path:
            inputs["rustc"]["commit_hash"] = m_path.group(1).decode("ascii")

        strings = _strings_from_bytes(data)
        clangs = sorted(
            {s for s in strings if _CLANG_STR_RE.match(s)},
            key=lambda s: ("+pgo" in s, len(s)),  # 优先取不带 +pgo/+bolt 的那条（更短更稳）
        )
        linkers = sorted({s for s in strings if _LINKER_STR_RE.match(s)})
        machine = None
        if data[:4] == b"\x7fELF" and len(data) >= 20:
            machine = int.from_bytes(data[18:20], "little")
        inputs["artifact_fingerprints"][rel] = {
            "elf_machine": ELF_MACHINE_NAME.get(machine, f"0x{machine:x}" if machine else None),
            "clang": clangs[0] if clangs else None,
            "linker": linkers[0] if linkers else None,
        }

    # ③ NDK 版本：单一真源 = gradle
    gradle = repo / GRADLE_FILE
    if gradle.is_file():
        m_ndk = NDK_GRADLE_RE.search(gradle.read_text(encoding="utf-8", errors="replace"))
        if m_ndk:
            inputs["ndk"]["version"] = m_ndk.group(1)

    # ④ Cargo.lock（依赖树 = 输入）
    lock = repo / CARGO_LOCK
    if lock.is_file():
        inputs["cargo_lock_sha256"] = sha256_source(lock)

    # ⑤ 16KB 页对齐的链接参数（输入侧）
    cfg = repo / CARGO_CONFIG
    if cfg.is_file():
        inputs["page_size_link_flags"] = sorted(
            set(LINK_FLAG_RE.findall(cfg.read_text(encoding="utf-8", errors="replace")))
        )

    return inputs


def _walk_none_keys(d: dict, path: str = "") -> list[str]:
    """收集值为 None 的叶子键（点号路径）——探测失败 = fail-closed。"""
    out: list[str] = []
    for k, v in d.items():
        if k == "comment":
            continue
        p = f"{path}.{k}" if path else k
        if isinstance(v, dict):
            out += _walk_none_keys(v, p)
        elif v is None:
            out.append(p)
    return out


def _get_path(d: dict, dotted: str):
    cur = d
    for part in dotted.split("."):
        if not isinstance(cur, dict) or part not in cur:
            return None
        cur = cur[part]
    return cur


def _set_path(d: dict, dotted: str, value) -> None:
    parts = dotted.split(".")
    cur = d
    for part in parts[:-1]:
        cur = cur.setdefault(part, {})
    cur[parts[-1]] = value


def diff_build_inputs(old: dict, new: dict, path: str = "") -> list[str]:
    """递归比对 build_inputs，逐条打印「哪个输入变了、旧值 → 新值」。"""
    lines: list[str] = []
    for k in sorted(set(old) | set(new)):
        if k == "comment":
            continue
        p = f"{path}.{k}" if path else k
        o = old.get(k, _MISSING)
        n = new.get(k, _MISSING)
        if isinstance(o, dict) and isinstance(n, dict):
            lines += diff_build_inputs(o, n, p)
            continue
        if o is _MISSING:
            lines.append(f"  [输入] {p} 新增：{json.dumps(n, ensure_ascii=False)}")
        elif n is _MISSING:
            lines.append(f"  [输入] {p} 删除：旧 {json.dumps(o, ensure_ascii=False)}")
        elif o != n:
            lines.append(
                f"  [输入] {p} 变更：旧 {json.dumps(o, ensure_ascii=False)} "
                f"→ 新 {json.dumps(n, ensure_ascii=False)}"
            )
    return lines


def collect_state(repo: Path) -> tuple[dict[str, str], dict[str, str], str]:
    """返回 (当前 .so 哈希表, 当前源码逐文件哈希表, 当前树汇总指纹)。"""
    so_map: dict[str, str] = {}
    for p in sorted(repo.glob(SO_GLOB)):
        so_map[p.relative_to(repo).as_posix()] = sha256_file(p)

    src_map: dict[str, str] = {}
    src_root = repo / SRC_DIR
    if not src_root.is_dir():
        raise SystemExit(f"[环境错误] 找不到源码目录: {SRC_DIR}")
    for p in sorted(src_root.rglob("*")):
        if p.is_file() and "target" not in p.parts:
            src_map[p.relative_to(repo).as_posix()] = sha256_source(p)

    h = hashlib.sha256()
    for rel, digest in sorted(src_map.items()):
        h.update(f"{rel}\n{digest}\n".encode("utf-8"))
    return so_map, src_map, h.hexdigest()


def diff_maps(old: dict, new: dict, label: str) -> list[str]:
    lines = []
    for k in sorted(set(old) | set(new)):
        if k not in old:
            lines.append(f"  [{label}] 新增: {k}")
        elif k not in new:
            lines.append(f"  [{label}] 删除: {k}")
        elif old[k] != new[k]:
            lines.append(f"  [{label}] 变更: {k}\n      旧 {old[k]}\n      新 {new[k]}")
    return lines


def main() -> int:
    args = [a for a in sys.argv[1:]]
    write = "--write" in args
    rest = [a for a in args if a != "--write"]
    if len(rest) > 1:
        print(__doc__)
        return 2
    repo = Path(rest[0]).resolve() if rest else Path(__file__).resolve().parent.parent
    if not (repo / SRC_DIR).is_dir():
        print(f"[环境错误] 不像仓库根: {repo}")
        return 2

    baseline_path = repo / BASELINE_JSON
    cur_so, cur_src, cur_fp = collect_state(repo)

    if write:
        old = None
        if baseline_path.exists():
            try:
                old = json.loads(baseline_path.read_text(encoding="utf-8"))
            except Exception as e:
                print(f"[警告] 旧基线解析失败（按无基线处理）: {e}")
        print("=== 刷新同源基线（--write）===")
        cur_bi = detect_build_inputs(repo, cur_so)
        prev_bi = (old or {}).get("build_inputs") or {}
        if old:
            for line in diff_maps(old.get("so", {}), cur_so, "so"):
                print(line)
            for line in diff_maps(old.get("source_files", {}), cur_src, "src"):
                print(line)
            print(f"  [指纹] 旧 {old.get('source_tree_fingerprint', '(无)')}")
            print(f"  [指纹] 新 {cur_fp}")
            if prev_bi:
                for line in diff_build_inputs(prev_bi, cur_bi):
                    print(line)
            else:
                print("  [输入] 旧基线没有 build_inputs 段 —— 本次首次建立")
        else:
            print("  （旧基线不存在，首次建立）")

        # 探测不到的输入键：绝不编。旧基线有值就沿用（并打印），否则留 null。
        for key in _walk_none_keys(cur_bi):
            prev_val = _get_path(prev_bi, key) if prev_bi else None
            if prev_val is None:
                print(f"  [输入] {key} 本次探测不到，旧基线也没有 ⇒ 写 null（不编）")
            else:
                print(f"  [输入] {key} 本次探测不到 ⇒ 沿用旧值 {prev_val!r}")
                _set_path(cur_bi, key, prev_val)

        baseline = {
            "version": 1,
            "comment": "libzhengdao_core.so 与 rust/core/src 的同源基线；只允许在确认重编后用 --write 刷新",
            "hash_mode": "so=raw-bytes; src=text-crlf-to-lf",
            "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
            "so": cur_so,
            "source_files": cur_src,
            "source_tree_fingerprint": cur_fp,
            "build_inputs": cur_bi,
        }
        baseline_path.write_text(
            json.dumps(baseline, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        print(f"[完成] 已写入 {BASELINE_JSON}")
        print(f"  .so sha256:")
        for k, v in sorted(cur_so.items()):
            print(f"    {k} = {v}")
        print(f"  源码树指纹 = {cur_fp}（{len(cur_src)} 个文件）")
        print(
            "  构建输入 = rustc {} ({} {}) / ndk {} / target {} / Cargo.lock {} / 链接参数 {}"
            .format(
                cur_bi["rustc"].get("version"),
                cur_bi["rustc"].get("commit_hash"),
                cur_bi["rustc"].get("release_date"),
                cur_bi["ndk"].get("version"),
                cur_bi["target_triple"],
                cur_bi["cargo_lock_sha256"],
                ", ".join(cur_bi["page_size_link_flags"]) or "(空)",
            )
        )
        for rel, fp in sorted(cur_bi["artifact_fingerprints"].items()):
            print(
                f"    {rel}: {fp.get('elf_machine')} / {fp.get('clang')} / {fp.get('linker')}"
            )
        return 0

    if not baseline_path.exists():
        print(f"[红] 没有基线 {BASELINE_JSON} —— 请先按 rust/README.md 工序重编入库，"
              f"再 `python tools/check-so-provenance.py --write` 建立基线。")
        return 1

    old = json.loads(baseline_path.read_text(encoding="utf-8"))
    old_so = old.get("so", {})
    old_src = old.get("source_files", {})
    old_fp = old.get("source_tree_fingerprint", "")

    problems: list[str] = []
    so_changed = old_so != cur_so
    src_changed = old_src != cur_src

    problems += diff_maps(old_so, cur_so, "so")
    problems += diff_maps(old_src, cur_src, "src")

    # ── 构建输入锁（第㉗②-A）：这份 .so 是被什么编出来的 ──
    old_bi = old.get("build_inputs")
    cur_bi = detect_build_inputs(repo, cur_so)
    cur_bi_summary = (
        f"rustc {cur_bi['rustc'].get('version')} ({cur_bi['rustc'].get('commit_hash')}), "
        f"ndk {cur_bi['ndk'].get('version')}, target {cur_bi['target_triple']}"
    )
    if old_bi is None:
        problems.append(
            "  [输入] 基线里没有 build_inputs 段（第㉗②-A 构建输入锁）—— 确认重编后跑 "
            "`python tools/check-so-provenance.py --write` 补齐（本门禁从此刻起覆盖构建输入）"
        )
    else:
        problems += diff_build_inputs(old_bi, cur_bi)
        for key in _walk_none_keys(cur_bi):
            old_val = _get_path(old_bi, key)
            if old_val is not None:
                problems.append(
                    f"  [输入] {key} 本次探测不到（产物/配置里读不出这个指纹）—— fail-closed 判红；"
                    f"旧基线值 {old_val!r}"
                )
    if not so_changed and not src_changed:
        pass
    elif so_changed and src_changed:
        pass  # 两边都变了：最常见（改源码+重编），仍需 --write 刷新
    elif src_changed and not so_changed:
        problems.append("  [同源] Rust 源码变了但 .so 没重编 —— 正是本门禁要拦的事（E-128）")
    elif so_changed and not src_changed:
        problems.append("  [同源] .so 变了但源码没变 —— 请确认是按工序重编（编译参数/工具链变化也会改哈希）")

    print(f"=== 入库 .so 同源门禁 ===")
    print(f"  基线指纹: {old_fp}")
    print(f"  当前指纹: {cur_fp}")
    print(f"  构建输入: {cur_bi_summary}")
    if problems:
        print("[红] 入库 .so 与 Rust 源码不同源（或基线过期）：")
        for line in problems:
            print(line)
        print("恢复绿的唯一合法路径：重编 + llvm-strip + 入库 + 过 check-native-so.py，"
              "再 `python tools/check-so-provenance.py --write` 刷新基线。")
        return 1

    print(f"[绿] {len(cur_so)} 个 .so 与 {len(cur_src)} 个源码文件均与基线一致（同源）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
