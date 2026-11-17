#!/usr/bin/env python3
"""670 跨语言对拍：用 Python 参考实现跑共享场景脚本，产出期望读数夹具。

用法（仓库根目录下）：
    python tools/character-track-parity.py \
        --reference ../证道设计报告/附件-角色轨状态机/character_track.py

输入：app/src/test/resources/track/parity-scenarios.json（两国语言共读的场景脚本）
输出：app/src/test/resources/track/parity-expected.json（Kotlin TrackParityTest 逐条比对）

op 语义：
  feed       喂信号（sig ∈ -1/0/+1）
  tick       轮末推进判定（anchor=true 表示本轮命中锚点）
  attacker   进攻方推进一步
  touch      请求接触层（layer 给定）
  touch_max  请求接触层 min(3, SALIENCE_LAYER_MAX[当前显性度])（参考实现场景写法）
  inertia    写惯性（template 里 {layer} 用当前接触层渲染）
  场景脚本的 inertia 模板用公开仓写法（御裁①：不写部位/距离，同一概念一种写法），
  两国语言共读同一份 scenarios.json，故对拍不受措辞影响。

每条 op 记录：返回值 msg、操作后状态 state、snapshot()。参考实现的 check()
不变量在每步后执行；任何断言失败即非零退出（不产假夹具）。
"""
import argparse
import hashlib
import importlib.util
import io
import json
import sys
from contextlib import redirect_stdout
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCENARIOS = ROOT / "app/src/test/resources/track/parity-scenarios.json"
EXPECTED = ROOT / "app/src/test/resources/track/parity-expected.json"


def load_reference(path: Path):
    spec = importlib.util.spec_from_file_location("character_track_ref", path)
    mod = importlib.util.module_from_spec(spec)
    with redirect_stdout(io.StringIO()):
        spec.loader.exec_module(mod)
    return mod


def state_of(st):
    return {
        "stance": st.stance,
        "salience": st.salience,
        "d_stage": st.d_stage,
        "a_stage": st.a_stage,
        "layer": st.layer,
        "turns": st.turns,
        "anchors_hit": sorted(st.anchors_hit),
        "window": list(st.window),
        "inertia": st.inertia,
    }


def run_op(mod, st, op):
    kind = op["op"]
    if kind == "feed":
        return st.feed(op["sig"])
    if kind == "tick":
        return st.tick(anchor_hit=op.get("anchor", False))
    if kind == "attacker":
        return st.attacker_step()
    if kind == "touch":
        return st.touch(op["layer"])
    if kind == "touch_max":
        return st.touch(min(3, mod.SALIENCE_LAYER_MAX[st.salience]))
    if kind == "inertia":
        st.inertia = op["template"].format(layer=st.layer)
        return st.inertia
    raise ValueError("未知 op: %r" % kind)


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser()
    ap.add_argument("--reference", required=True, help="character_track.py 参考实现路径")
    args = ap.parse_args()
    ref_path = Path(args.reference).resolve()
    raw = ref_path.read_bytes()
    mod = load_reference(ref_path)
    doc = json.loads(SCENARIOS.read_text(encoding="utf-8"))
    out = {
        "reference": ref_path.relative_to(ROOT.parent).as_posix(),
        "reference_sha256": hashlib.sha256(raw).hexdigest(),
        "reference_bytes": len(raw),
        "scenarios": [],
    }
    total = 0
    for sc in doc["scenarios"]:
        st = mod.PairState(stance=sc["stance"])
        steps = []
        for op in sc["ops"]:
            msg = run_op(mod, st, op)
            mod.check(st)
            steps.append({"op": op, "msg": msg, "state": state_of(st), "snapshot": st.snapshot()})
            total += 1
        out["scenarios"].append({"id": sc["id"], "steps": steps})
    EXPECTED.write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print("参考实现 sha256=%s（%d B）" % (out["reference_sha256"], out["reference_bytes"]))
    print("场景 %d 个 / 步 %d 条 ⇒ %s" % (len(out["scenarios"]), total, EXPECTED))


if __name__ == "__main__":
    sys.exit(main())