#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-baseline-refresh.py —— 「刷基线」收窄门禁（第㉗②-A，§B 57 加分项）。

为什么需要它
    tools/check-so-provenance.py 的green是**基线驱动**的：它的合法刷新路径是
    「按 rust/README.md 重编 → llvm-strip → 入库 .so → 过 check-native-so.py →
    --write」。但只要有人**单改** tools/so-provenance.json（比如把 .so 的 sha256
    改成当前值），同源门禁立刻变绿，而真机上跑的还是旧二进制 —— 与 E-128 完全同类：
    门禁全绿、产物没重编。同源门禁自己看不出这一步，因为「基线」正是它的判据。

它判什么
    在给定基线提交与当前工作树之间：
      - 若 tools/so-provenance.json **没动** ⇒ 绿（无需收窄）。
      - 若它动了，则**必须**同时至少有一处跟着动：
          · app/src/main/jniLibs/**/*.so          （真重编/重新入库）
          · rust/core/src/** 下的任何文件          （真改了源码，再重编）
        两者都没有 ⇒ 红：这是「只刷基线、不重编」。

它不判什么
    **不判**「重编的 .so 是不是真的对应这份源码」——那是 check-so-provenance.py 与
    check-native-so.py 的职责；本脚本只把「单独刷基线」这条捷径堵上。

用法
    python tools/check-baseline-refresh.py --base <git rev> [仓库根]
    python tools/check-baseline-refresh.py [仓库根]        # 基线默认自动解析

    基线解析顺序：--base > origin/main > HEAD~1。
    解析不到（浅克隆、无 git、孤儿提交等）⇒ 打印「跳过」并返回 0（本门禁是**尽力而为**的
    收窄，不能在无法比较时把 CI 卡死；要它真正生效，checkout 需 fetch-depth >= 2）。

退出码
    0 = 绿（含「跳过」） ；1 = 红（单独刷了基线） ；2 = 用法/环境错误
"""

import json
import subprocess
import sys
from pathlib import Path

try:  # Windows 控制台默认 GBK：个别字符编不出来时降级为替换，而不是让门禁自己崩
    sys.stdout.reconfigure(errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

BASELINE_JSON = "tools/so-provenance.json"
SO_GLOB = "app/src/main/jniLibs/**/*.so"
SRC_DIR = "rust/core/src"


def git(repo: Path, *args: str) -> tuple[int, str]:
    try:
        proc = subprocess.run(
            ["git", "-C", str(repo), *args],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
    except FileNotFoundError:
        return 127, "git 不可用"
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def resolve_base(repo: Path, requested: str | None) -> tuple[str | None, str]:
    candidates = [requested] if requested else []
    candidates += ["origin/main", "HEAD~1"]
    notes = []
    for cand in candidates:
        if not cand:
            continue
        code, _out = git(repo, "rev-parse", "--verify", "--quiet", f"{cand}^{{commit}}")
        if code == 0:
            return cand, f"基线 = {cand}"
        notes.append(f"{cand}（解析不到）")
    return None, "解析不到任何可用基线：" + "、".join(notes)


def main(argv: list[str]) -> int:
    args = list(argv[1:])
    base_req = None
    if "--base" in args:
        i = args.index("--base")
        if i + 1 >= len(args):
            print(__doc__)
            return 2
        base_req = args[i + 1]
        del args[i : i + 2]
    if len(args) > 1:
        print(__doc__)
        return 2
    repo = Path(args[0]).resolve() if args else Path(__file__).resolve().parent.parent
    if not (repo / SRC_DIR).is_dir():
        print(f"[环境错误] 不像仓库根: {repo}")
        return 2

    code, out = git(repo, "rev-parse", "--is-inside-work-tree")
    if code != 0 or "true" not in out:
        print("[跳过] 当前目录不是 git 工作树 —— 本门禁需要 git 才能比较（第㉗②-A 加分项）")
        return 0

    base, note = resolve_base(repo, base_req)
    if base is None:
        print(f"[跳过] {note}")
        print("       本门禁是收窄用的尽力而为判据；要让它生效，checkout 需 fetch-depth >= 2")
        print("       （或在本地显式 `--base <rev>`）。")
        return 0

    code, out = git(repo, "diff", "--name-only", base, "--")
    if code != 0:
        print(f"[跳过] `git diff {base}` 失败：{out.strip()[:200]}")
        return 0
    changed = [line.strip() for line in out.splitlines() if line.strip()]

    print("=== 刷基线收窄门禁 ===")
    print(f"  {note}；相对基线共 {len(changed)} 个文件变化")

    if BASELINE_JSON not in changed:
        print(f"[绿] {BASELINE_JSON} 未改动 —— 无需收窄")
        return 0

    # 关键：不能只看「文件动没动」。第㉗②-A 这次就要往基线里**新增 build_inputs 段**
    # 而产物一字未改（合法：不是刷哈希，是补一类新输入）。所以真正要拦的是
    # 「承载哈希的那三个字段被改了」——so / source_files / source_tree_fingerprint。
    code, old_text = git(repo, "show", f"{base}:{BASELINE_JSON}")
    if code != 0:
        print(f"[绿] 基线在 {base} 里不存在（本次首次建立）—— 无旧哈希可比，放行")
        print("      后续提交由 tools/check-so-provenance.py 的同源判据接管")
        return 0
    try:
        old_bi = json.loads(old_text)
    except Exception as exc:  # noqa: BLE001 - 门禁脚本，解析失败要说清而不是崩
        print(f"[跳过] {base} 里的基线解析失败（{exc}）—— 无法比较")
        return 0
    try:
        new_bi = json.loads((repo / BASELINE_JSON).read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001
        print(f"[红] 当前基线无法解析：{exc}")
        return 1

    hash_fields = ("so", "source_files", "source_tree_fingerprint")
    drifted = [k for k in hash_fields if old_bi.get(k) != new_bi.get(k)]
    if not drifted:
        print(f"[绿] 基线的哈希字段（{'、'.join(hash_fields)}）与 {base} 完全一致 —— "
              "本次只是新增/调整非哈希内容（例如 build_inputs），不构成「刷基线」")
        return 0

    so_changed = [
        p for p in changed if p.startswith("app/src/main/jniLibs/") and p.endswith(".so")
    ]
    src_changed = [p for p in changed if p.startswith(f"{SRC_DIR}/")]
    if so_changed or src_changed:
        print(f"[绿] 基线哈希字段变动（{'、'.join(drifted)}）伴随产物/源码改动："
              f".so {len(so_changed)} 个、{SRC_DIR}/ 下 {len(src_changed)} 个")
        for p in (so_changed + src_changed)[:10]:
            print(f"      · {p}")
        if len(so_changed) + len(src_changed) > 10:
            print(f"      · …（其余 {len(so_changed) + len(src_changed) - 10} 个略）")
        return 0

    print("[红] 基线的哈希字段被改了，却没动任何产物或源码 —— 这是「刷基线让它变绿」：")
    print(f"      变动字段：{'、'.join(drifted)}")
    print(f"      · {SO_GLOB} 下没有任何 .so 变化")
    print(f"      · {SRC_DIR}/ 下没有任何源码变化")
    print("")
    print("合规路径：按 rust/README.md 重编 → llvm-strip → 入库 .so → 过 "
          "check-native-so.py → `python tools/check-so-provenance.py --write` 刷新基线，"
          "并把新 .so 与新 JSON 放进同一个提交。")
    print("（若确实只是修正基线的**错误值**而产物没变，请在同一条提交信息里写明理由，")
    print("  并在评审里说明——本门禁的取舍是「宁可让人解释，也不让刷基线悄悄过」。）")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
