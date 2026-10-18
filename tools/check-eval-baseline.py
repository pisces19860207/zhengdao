#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-eval-baseline.py —— 评测基线不许放松（第二十三批 B 段 · 派工单 §2.2；判据 E2/E3/E4/E6/E7）。

为什么需要它
    进化门禁（app/src/test/java/com/example/zhengdao/baopu/eval/EvalGateTest.kt）自建成起
    一次也没拦过人：不进 CI、跳过不留痕。本脚本补缺口之二——**提交之间不许顺手放松评测判据**：
      - eval/cases.json：用例被删 / 总数变少 ⇒ 红；
      - eval/baseline.json：阈值被改松 ⇒ 红（passRate 调低、avgSteps 调高、cases 调小、
        passing 名单变短；整份基线被删＝最严重的放松，同样红）。
    只加用例、阈值收紧、recordedAt/note 这类元数据变化 ⇒ 绿。

    与 tools/check-baseline-refresh.py 的**刻意差异**：那个脚本解析不到基线时「跳过」并
    返回 0（尽力而为、不卡死 CI）；本脚本**宁可红也不静默放行**——比较不了就等于确认不了
    「未放松」，而「确认不了还放行」正是这个门禁要堵的行为（判据 E7：--base 不存在 ⇒
    不崩、说清楚、不许静默 0）。

它不判什么
    **不判**「模型是不是真的还跑得过这 10 条」——那是带 ZHENGDAO_EVAL_KEY 的 EvalGateTest
    的职责；本脚本只把「改提交时顺手放松判据」这条捷径堵上。

用法
    python3 tools/check-eval-baseline.py --base <git rev> [仓库根]

退出码
    0 = 绿（未放松） ；1 = 红（删用例/总数变少/阈值放松/基线被删） ；2 = 用法或环境错误
    （--base 缺失或解析不到、不是 git 工作树、git 不可用、历史里的文件读不出/解析失败——
    一律明说，不静默 0）
"""

import argparse
import json
import subprocess
import sys
from pathlib import Path

try:  # Windows 控制台默认 GBK：个别字符编不出来时降级为替换，而不是让门禁自己崩
    sys.stdout.reconfigure(errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

CASES_JSON = "eval/cases.json"
BASELINE_JSON = "eval/baseline.json"


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


def has_blob(repo: Path, rev: str, path: str) -> bool:
    code, _ = git(repo, "cat-file", "-e", f"{rev}:{path}")
    return code == 0


def parse_cases(text: str, where: str) -> tuple[list[str], str | None]:
    """解析 cases.json（评测集是**单个文件**，不是目录）。

    用 json 模块解析，**不许按行 grep 统计**——本仓踩过：按行统计把「某条缺字段」
    误判成「格式错乱」。顶层必须是数组；逐条取 id，缺 id 的条数不出来也算问题
    （点不出名就锁不死它，等于放它悄悄消失）。
    """
    try:
        data = json.loads(text)
    except Exception as exc:  # noqa: BLE001 - 门禁脚本，解析失败要说清而不是崩
        return [], f"{where} 不是合法 JSON：{exc}"
    if not isinstance(data, list):
        return [], f"{where} 顶层不是数组"
    ids: list[str] = []
    bad = 0
    for entry in data:
        if isinstance(entry, dict) and isinstance(entry.get("id"), str) and entry["id"]:
            ids.append(entry["id"])
        else:
            bad += 1
    if bad:
        return ids, f"{where} 有 {bad} 条用例缺 id 字段（共 {len(data)} 条）"
    return ids, None


def parse_baseline(text: str, where: str) -> tuple[dict | None, str | None]:
    try:
        data = json.loads(text)
    except Exception as exc:  # noqa: BLE001
        return None, f"{where} 不是合法 JSON：{exc}"
    if not isinstance(data, dict):
        return None, f"{where} 顶层不是对象"
    return data, None


def fmt(v) -> str:
    return "—" if v is None else str(v)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="评测基线不许放松：eval/cases.json 不许删用例，eval/baseline.json 不许放松阈值",
    )
    parser.add_argument("--base", required=True, help="基线 commit（如 HEAD~1 或 PR 的 base.sha）")
    parser.add_argument("repo", nargs="?", help="仓库根（默认：本脚本上级目录）")
    args = parser.parse_args(argv[1:])

    repo = Path(args.repo).resolve() if args.repo else Path(__file__).resolve().parent.parent
    if not (repo / CASES_JSON).is_file():
        print(f"[环境错误] 不像仓库根：{repo}（找不到 {CASES_JSON}）")
        return 2

    code, out = git(repo, "rev-parse", "--is-inside-work-tree")
    if code != 0 or "true" not in out:
        print("[环境错误] 当前目录不是 git 工作树 —— 无法与基线提交比较，本门禁不静默放行")
        return 2
    code, _out = git(repo, "rev-parse", "--verify", "--quiet", f"{args.base}^{{commit}}")
    if code != 0:
        print(f"[环境错误] --base 解析不到：{args.base!r}")
        print("      比较不了就等于确认不了「未放松」——本门禁不静默放行（判据 E7）")
        return 2
    base = args.base

    print("=== 评测基线不许放松门禁 ===")
    print(f"  基线 = {base}")

    # ── 用例集：旧 id 集合 vs 新 id 集合 ──────────────────────────────────
    if not has_blob(repo, base, CASES_JSON):
        print(f"[绿] {CASES_JSON} 在 {base} 里不存在（本次首次建立评测集）—— 无旧集可比，放行")
        return 0
    code, old_text = git(repo, "show", f"{base}:{CASES_JSON}")
    if code != 0:
        print(f"[环境错误] 读不到 {base}:{CASES_JSON}：{old_text.strip()[:200]} —— 无法比较，不静默放行")
        return 2
    old_ids, old_err = parse_cases(old_text, f"{base} 里的 {CASES_JSON}")
    if old_err:
        print(f"[环境错误] {old_err} —— 历史里的评测集坏到无法比较，不静默放行")
        return 2
    try:
        new_text = (repo / CASES_JSON).read_text(encoding="utf-8")
    except Exception as exc:  # noqa: BLE001
        print(f"[红] 工作区 {CASES_JSON} 读不出来：{exc}")
        return 1
    new_ids, new_err = parse_cases(new_text, f"工作区 {CASES_JSON}")
    if new_err:
        print(f"[红] {new_err} —— 评测集坏到点不出名，按未通过处理")
        return 1

    problems: list[str] = []
    notes: list[str] = []

    old_set, new_set = set(old_ids), set(new_ids)
    deleted = [i for i in old_ids if i not in new_set]
    if deleted:
        problems.append(f"用例被删 {len(deleted)} 条：{'、'.join(deleted)}")
    added = [i for i in new_ids if i not in old_set]
    if added:
        notes.append(f"新增用例 {len(added)} 条：{'、'.join(added)}")
    if len(new_ids) < len(old_ids):
        problems.append(f"用例总数变少：{len(old_ids)} → {len(new_ids)}")

    # ── 基线水位：四个字段逐字段比（变松的方向各自相反，不靠人记） ──────────
    old_rate: object = None
    new_rate: object = None
    if not has_blob(repo, base, BASELINE_JSON):
        notes.append(f"{BASELINE_JSON} 在 {base} 里不存在（本次首次建立基线）—— 无旧水位可比")
    else:
        code, old_bl_text = git(repo, "show", f"{base}:{BASELINE_JSON}")
        if code != 0:
            print(f"[环境错误] 读不到 {base}:{BASELINE_JSON}：{old_bl_text.strip()[:200]} —— 无法比较，不静默放行")
            return 2
        old_bl, old_bl_err = parse_baseline(old_bl_text, f"{base} 里的 {BASELINE_JSON}")
        if old_bl_err:
            print(f"[环境错误] {old_bl_err} —— 历史里的基线无法比较，不静默放行")
            return 2
        old_rate = old_bl.get("passRate")
        if not (repo / BASELINE_JSON).is_file():
            problems.append(f"{BASELINE_JSON} 被整个删掉 —— 下次带 key 跑直接重记水位，这是最严重的放松")
        else:
            try:
                new_bl_text = (repo / BASELINE_JSON).read_text(encoding="utf-8")
            except Exception as exc:  # noqa: BLE001
                print(f"[红] 工作区 {BASELINE_JSON} 读不出来：{exc}")
                return 1
            new_bl, new_bl_err = parse_baseline(new_bl_text, f"工作区 {BASELINE_JSON}")
            if new_bl_err:
                # 解析失败 ⇒ EvalGateTest.readBaseline 的 runCatching 会当「没有基线」
                # ⇒ 下次带 key 跑直接重记 ⇒ 水位自动涨＝放松
                problems.append(f"工作区 {new_bl_err} —— 门禁会当没有基线、下次直接重记，按放松处理")
                new_bl = None
            if new_bl is not None:
                new_rate = new_bl.get("passRate")
                for key in ("passRate", "avgSteps", "cases"):
                    if key not in new_bl:
                        if key in old_bl:
                            problems.append(f"基线字段 {key} 被删（旧值 {fmt(old_bl.get(key))}）")
                        continue
                    if key not in old_bl:
                        notes.append(f"基线新增字段 {key} = {fmt(new_bl.get(key))}")
                        continue
                    o, n = old_bl[key], new_bl[key]
                    if not isinstance(o, (int, float)) or not isinstance(n, (int, float)):
                        if o != n:
                            problems.append(f"基线字段 {key} 的值/类型变了：{o!r} → {n!r}")
                        continue
                    if key == "passRate" and n < o:
                        problems.append(f"passRate 被调低：{o} → {n}")
                    elif key == "avgSteps" and n > o:
                        problems.append(f"avgSteps 被调高：{o} → {n}")
                    elif key == "cases" and n < o:
                        problems.append(f"cases 被调小：{o} → {n}")
                    elif n != o:
                        notes.append(f"{key} 收紧/变动：{o} → {n}")
                op, np_ = old_bl.get("passing"), new_bl.get("passing")
                if isinstance(op, list):
                    if not isinstance(np_, list):
                        problems.append("基线字段 passing 被删或坏了")
                    elif len(np_) < len(op):
                        gone = [str(x) for x in op if x not in np_]
                        problems.append(
                            f"passing 名单变短：{len(op)} → {len(np_)}"
                            + (f"（少了：{'、'.join(gone)}）" if gone else "")
                        )
                    elif set(op) != set(np_):
                        notes.append(
                            f"passing 名单等长但成员有换（旧 {len(op)} / 新 {len(np_)}）"
                            "——按派工单字面口径不判红，请评审留心"
                        )
                elif isinstance(np_, list) and op is None:
                    notes.append(f"基线新增 passing 名单（{len(np_)} 条）")

    if problems:
        for p in problems:
            print(f"  ✗ {p}")
        print(f"❌ 评测基线被放松：{len(problems)} 处（详见上）—— 提升水位请走「重跑写基线」流程，不许顺手放宽")
        return 1
    for n in notes:
        print(f"  · {n}")
    print(f"✅ 评测基线未放松：{len(old_ids)} → {len(new_ids)} 例，passRate {fmt(old_rate)} → {fmt(new_rate)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
