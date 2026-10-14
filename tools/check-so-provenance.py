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


def sha256_file(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(256 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


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
            src_map[p.relative_to(repo).as_posix()] = sha256_file(p)

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
        if old:
            for line in diff_maps(old.get("so", {}), cur_so, "so"):
                print(line)
            for line in diff_maps(old.get("source_files", {}), cur_src, "src"):
                print(line)
            print(f"  [指纹] 旧 {old.get('source_tree_fingerprint', '(无)')}")
            print(f"  [指纹] 新 {cur_fp}")
        else:
            print("  （旧基线不存在，首次建立）")
        baseline = {
            "version": 1,
            "comment": "libzhengdao_core.so 与 rust/core/src 的同源基线；只允许在确认重编后用 --write 刷新",
            "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
            "so": cur_so,
            "source_files": cur_src,
            "source_tree_fingerprint": cur_fp,
        }
        baseline_path.write_text(
            json.dumps(baseline, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        print(f"[完成] 已写入 {BASELINE_JSON}")
        print(f"  .so sha256:")
        for k, v in sorted(cur_so.items()):
            print(f"    {k} = {v}")
        print(f"  源码树指纹 = {cur_fp}（{len(cur_src)} 个文件）")
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
