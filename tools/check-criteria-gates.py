#!/usr/bin/env python3
# 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
"""第三百一十批 · 判据进化门禁（牙齿层）。

用户 2026-10-31 的分工：**规则归 policy，牙齿归门禁；宰相不参与准入。**
policy/ 里的 `ImmutableCriteria`／`CriteriaEvolution`／`AnchorSet`／`CriteriaEpoch`
只放判定；这里用**源级断言＋账本比对**把「清单被改写、被绕过、判据输出出现标量分、
锚定集被静默改、纪元账没有三态」变成会红的门禁。

四把尺：
1. 不可进化清单：恰好三条，id／法典依据／止损白名单逐字固定；清单不许配置化；
   `agent/` 在线路径不许出现这四个判定符号（宰相不参与准入）。
2. 锚定集只增不删：与上一位（git HEAD 的私有一版）逐行比；删行、或改正文却不补签 ⇒ 红。
3. 纪元账：一纪元一行、五列、三态闭集、计数自洽、不许重复。
4. 禁标量分：判据产物层零浮点／零分数样字段／零小数字面量；全仓判据结论类型同理。

退出码：0＝绿（含「私有文件 CI 不可得」这类**显式非红**状态，照 309 先例），1＝红。
`--selftest`：把每个负例做成内存夹具，断言它真的会红（门禁自己也要有人验）。
"""
import json
import re
import subprocess
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

ROOT = Path(__file__).resolve().parent.parent
POLICY = ROOT / "app" / "src" / "main" / "java" / "com" / "example" / "zhengdao" / "baopu" / "policy"
BAOPU_MAIN = ROOT / "app" / "src" / "main" / "java" / "com" / "example" / "zhengdao" / "baopu"
PRIVATE = ROOT.parent / "_zd-private"

SURFACE = ["ImmutableCriteria.kt", "CriteriaEvolution.kt", "AnchorSet.kt", "CriteriaEpoch.kt"]
FLOAT_EXEMPT = {"Policy.kt", "PolicyTable.kt"}
IMMUTABLE_IDS = {
    "DELIVERABLE_ON_DISK": ("deliverable-on-disk", "L19"),
    "DESTRUCTIVE_CONFIRM": ("destructive-confirm", "L16"),
    "EMERGENCY_APPROVAL": ("emergency-approval", "L21"),
}
STOP_LOSS_EXPECTED = {"stop_machine", "quarantine", "rollback", "freeze_writes"}
EPOCH_STATES = {"APPLIED", "ROLLED_BACK", "NO_CHANGE"}
CRITERIA_SYMBOLS = ("ImmutableCriteria", "CriteriaEvolution", "AnchorSet", "CriteriaEpoch")

problems = []
explicit = []


def err(msg):
    problems.append(msg)


def note(msg):
    explicit.append(msg)


# ── 去注释（与 GateIo.stripComments 同口径：注释替空格，行号不变）────────────
def strip_comments(src):
    out = []
    i, n = 0, len(src)
    in_str = in_char = in_raw = False
    while i < n:
        c = src[i]
        if in_raw:
            if src.startswith('"""', i):
                in_raw = False
                out.append('"""')
                i += 3
            else:
                out.append(c)
                i += 1
        elif in_str:
            if c == "\\" and i + 1 < n:
                out.append(src[i:i + 2])
                i += 2
            else:
                if c == '"':
                    in_str = False
                out.append(c)
                i += 1
        elif in_char:
            if c == "\\" and i + 1 < n:
                out.append(src[i:i + 2])
                i += 2
            else:
                if c == "'":
                    in_char = False
                out.append(c)
                i += 1
        elif src.startswith('"""', i):
            in_raw = True
            out.append('"""')
            i += 3
        elif c == '"':
            in_str = True
            out.append(c)
            i += 1
        elif c == "'":
            in_char = True
            out.append(c)
            i += 1
        elif src.startswith("//", i):
            while i < n and src[i] != "\n":
                out.append(" ")
                i += 1
        elif src.startswith("/*", i):
            out.append("  ")
            i += 2
            while i < n and not src.startswith("*/", i):
                out.append("\n" if src[i] == "\n" else " ")
                i += 1
            if i < n:
                out.append("  ")
                i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


# ── 尺一：不可进化清单 ────────────────────────────────────────────────────
def immutable_problems(code):
    found = []
    for const, (wire, law) in IMMUTABLE_IDS.items():
        pat = re.compile(r'const val %s = "([^"]+)"' % const)
        hits = pat.findall(code)
        if hits != [wire]:
            found.append("%s 必须恰好一处且值为 %s，实测 %s" % (const, wire, hits))
        law_pat = re.compile(r"id = %s,.*?lawRef = \"(L[0-9]+)\"" % const, re.S)
        laws = law_pat.findall(code)
        if laws != [law]:
            found.append("%s 的法典依据必须是 %s，实测 %s" % (const, law, laws))
    if code.count("val ALL: List<ImmutableCriterion> = listOf(") != 1:
        found.append("清单入口 val ALL 必须恰好一处")
    # 白名单逐字对齐（止损四件是闭集）
    wl = re.search(r"val STOP_LOSS_WHITELIST: Set<String> = setOf\((.*?)\)", code, re.S)
    wires = set(re.findall(r'"([a-z_]+)"', wl.group(1))) if wl else set()
    if wires != STOP_LOSS_EXPECTED:
        found.append("止损白名单必须逐字等于 %s，实测 %s" % (sorted(STOP_LOSS_EXPECTED), sorted(wires)))
    # 清单不许配置化：挖掉「开洞词表 → ALL」这一段（词表本身含禁词，是给人看的）
    body = code
    start = body.find("val RELAXATION_MARKERS")
    end = body.find("val ALL:")
    if start != -1 and end != -1 and end > start:
        body = body[:start] + body[end:]
    for token in ("var ALL", "mutableListOf", "SharedPreferences", "JSONObject", "fun set", "override"):
        if token in body:
            found.append("清单被做成可改写/可注入（出现 %s）" % token)
    if "java.io" in body or "android." in body or "java.net" in body:
        found.append("清单文件不许 IO／Android／网络")
    return found


CHANCELLOR_PATHS = ("agent", "MediatorWiring.kt", "MediatorJudge.kt")


def leak_in_code(code, rel):
    hits = []
    for sym in CRITERIA_SYMBOLS:
        if sym in code:
            hits.append("%s 出现 %s：宰相的在线路径只查表，不参与判据准入" % (rel, sym))
    return hits


def runtime_leak_problems():
    """宰相的在线路径只查表：agent/ 与宰辅两文件里不许出现准入符号。

    三个目标文件本身也要在——钉子指向一个改名后的空气，等于这条禁令没人守。
    """
    found = []
    targets = []
    for name in CHANCELLOR_PATHS:
        path = BAOPU_MAIN / name
        if not path.exists():
            found.append("宰相路径不见了：%s（准入禁令悬空，先改这行再改代码）" % name)
        elif path.is_dir():
            targets.extend(path.rglob("*.kt"))
        else:
            targets.append(path)
    for path in targets:
        code = strip_comments(path.read_text(encoding="utf-8"))
        found.extend(leak_in_code(code, path.relative_to(ROOT).as_posix()))
    return found


# ── 尺四：禁标量分 ────────────────────────────────────────────────────────
FLOAT_DECL = re.compile(r'(?m)^\s*(?:internal |private )?(?:val|var)\s+\w+\s*:\s*(?:Double|Float)\b')
FLOAT_RETURN = re.compile(r'(?m)^\s*(?:internal |private )?(?:override )?fun\s+\w+[^=\n]*:\s*(?:Double|Float)\b')
# 分数样字段：只抓**数值型**的。`confidence: Confidence`（枚举）是判定名不是分数，别误杀
# （口径与 NoScalarScoreGateTest 里的同名正则逐字对齐）。
SCORE_FIELD = re.compile(
    r'(?m)^\s*(?:internal |private )?(?:val|var)\s+\w*(?:score|rating|confidence)\w*\s*:\s*(?:Double|Float|Int|Long)\b',
    re.I)
DECIMAL = re.compile(r"(?<![\w.])\d+\.\d+")
VERDICT_CLASS = re.compile(r'(?m)^\s*(?:internal )?(?:data )?class\s+\w*(?:Criteria|Admission|Criterion|CheckItem|Verdict)\w*')


def scalar_problems_in_code(code):
    found = []
    floats = FLOAT_DECL.findall(code) + FLOAT_RETURN.findall(code)
    if floats:
        found.append("出现浮点声明 %s" % floats)
    named = SCORE_FIELD.findall(code)
    if named:
        found.append("出现分数样字段 %s" % named)
    decimals = DECIMAL.findall(code)
    if decimals:
        found.append("出现小数字面量 %s（判据不产数）" % decimals)
    for token in ("评分", "置信"):
        if token in code:
            found.append("出现「%s」字样（判据产物只给通过／不通过＋原因）" % token)
    return found


def scalar_surface_problems():
    found = []
    for name in SURFACE:
        path = POLICY / name
        if not path.exists():
            found.append("判据产物文件缺失：%s" % name)
            continue
        rel = path.relative_to(ROOT).as_posix()
        for p in scalar_problems_in_code(strip_comments(path.read_text(encoding="utf-8"))):
            found.append("%s：%s" % (rel, p))
    return found


def scalar_repo_problems():
    found = []
    for path in BAOPU_MAIN.rglob("*.kt"):
        code = strip_comments(path.read_text(encoding="utf-8"))
        if not VERDICT_CLASS.search(code):
            continue
        if FLOAT_DECL.search(code) or FLOAT_RETURN.search(code):
            found.append("%s 的判据结论类型携带浮点" % path.relative_to(ROOT).as_posix())
    # 阈值参数是显式豁免：按「有没有 Double/Float 字样」数，推断声明也算（PolicyTable 的
    # strictDouble 是函数不是 val，但它确实是浮点阈值的那一处）。
    owners = set()
    for path in POLICY.rglob("*.kt"):
        code = strip_comments(path.read_text(encoding="utf-8"))
        if re.search(r"\b(?:Double|Float)\b", code):
            owners.add(path.name)
    if owners != FLOAT_EXEMPT:
        found.append("policy 包带浮点的文件必须是 %s（阈值参数是显式豁免），实测 %s"
                     % (sorted(FLOAT_EXEMPT), sorted(owners)))
    return found


# ── 尺二：锚定集只增不删 ──────────────────────────────────────────────────
def anchor_file():
    for cand in (ROOT / "docs" / "anchor-set.jsonl", PRIVATE / "docs" / "anchor-set.jsonl"):
        if cand.exists():
            return cand
    return None


def parse_rows(text):
    rows = []
    for idx, line in enumerate(text.splitlines(), 1):
        line = line.strip()
        if not line:
            continue
        rows.append(json.loads(line))
    return rows


def pre_signature(row):
    return (row.get("scene"), row.get("oldVerdict"), row.get("humanVerdict"),
            row.get("evidence"), row.get("screenedBy"))


def signature(row):
    return (str(row.get("signedBy", "")).strip(), str(row.get("signedAt", "")).strip())


def anchor_violations(prev_rows, next_rows):
    found = []
    prev_by = {r["id"]: r for r in prev_rows}
    next_by = {r["id"]: r for r in next_rows}
    removed = sorted(set(prev_by) - set(next_by))
    if removed:
        found.append("锚定集被删行（只增不删）：%s" % ",".join(removed))
    changed = []
    for rid in sorted(set(prev_by) & set(next_by)):
        before, after = prev_by[rid], next_by[rid]
        # 补签＝签名字段真的变了；正文改了却把旧签名原样留着 ⇒ 静默改，红。
        if pre_signature(before) != pre_signature(after) and signature(before) == signature(after):
            changed.append(rid)
    if changed:
        found.append("锚定集旧行被改且没有新签字（改锚＝改标准＝立法）：%s" % ",".join(changed))
    return found


def anchor_schema_problems(rows):
    found = []
    if not 30 <= len(rows) <= 50:
        found.append("锚定样本条数应在 30–50，实测 %d" % len(rows))
    ids = [r.get("id") for r in rows]
    if len(set(ids)) != len(ids):
        found.append("锚定集 id 重复")
    for r in rows:
        rid = r.get("id", "?")
        for key in ("scene", "oldVerdict", "humanVerdict", "evidence"):
            if not str(r.get(key, "")).strip():
                found.append("%s 缺 %s" % (rid, key))
        hv = str(r.get("humanVerdict", "")).strip()
        if not (hv.startswith("不通过") or hv.startswith("通过")):
            found.append("%s 的人工结论必须以「通过／不通过」打头：%s" % (rid, hv))
        if not str(r.get("screenedBy", "")).strip():
            found.append("%s 没有初筛人（初筛人是可追责任的落点）" % rid)
    signed = [r for r in rows if str(r.get("signedBy", "")).strip()]
    if signed and len(signed) != len(rows):
        found.append("签字要么整批要么没有，实测 %d/%d" % (len(signed), len(rows)))
    # 一边倒的锚定集等于没锚：全「通过」拦不住凡事都放行的判据，
    # 全「不通过」拦不住凡事都拦的判据——两侧必须都有。
    ok = sum(1 for r in rows if str(r.get("humanVerdict", "")).strip().startswith("通过"))
    ng = sum(1 for r in rows if str(r.get("humanVerdict", "")).strip().startswith("不通过"))
    if not (ok and ng):
        found.append("锚定集一边倒（通过 %d／不通过 %d）：新判据只要照抄这一侧就能 100%% 通过" % (ok, ng))
    return found


def git_baseline(path):
    """上一版（HEAD 里的同一文件）；拿不到就返回 None（显式非红）。"""
    try:
        top = subprocess.run(["git", "-C", str(path.parent), "rev-parse", "--show-toplevel"],
                             capture_output=True, text=True, encoding="utf-8", errors="replace")
        if top.returncode != 0:
            return None
        root = Path(top.stdout.strip())
        rel = path.resolve().relative_to(root.resolve()).as_posix()
        show = subprocess.run(["git", "-C", str(root), "show", "HEAD:%s" % rel],
                              capture_output=True, text=True, encoding="utf-8", errors="replace")
        return show.stdout if show.returncode == 0 else None
    except Exception:
        return None


def check_anchors():
    path = anchor_file()
    if path is None:
        note("锚定集不在本机（私有文件 CI 不可得是既有口径）——显式非红，append-only 待本机核")
        return
    rows = parse_rows(path.read_text(encoding="utf-8"))
    for p in anchor_schema_problems(rows):
        err("锚定集：" + p)
    baseline = git_baseline(path)
    if baseline is None:
        note("锚定集的上一版不在 git HEAD（新文件尚未提交）——显式非红")
    else:
        for p in anchor_violations(parse_rows(baseline), rows):
            err("锚定集：" + p)


# ── 尺三：纪元账 ──────────────────────────────────────────────────────────
def epoch_file():
    for cand in (ROOT / "docs" / "纪元账.jsonl", PRIVATE / "docs" / "纪元账.jsonl"):
        if cand.exists():
            return cand
    return None


def epoch_problems(text):
    found = []
    seen = set()
    for idx, line in enumerate(text.splitlines(), 1):
        line = line.strip()
        if not line:
            continue
        cols = line.split("|")
        if len(cols) != 5:
            found.append("第 %d 行不是五列：%s" % (idx, line))
            continue
        epoch, cands, adopted, refused, state = cols
        try:
            epoch_n, cands_n, adopted_n, refused_n = int(epoch), int(cands), int(adopted), int(refused)
        except ValueError:
            found.append("第 %d 行计数不是整数：%s" % (idx, line))
            continue
        if state not in EPOCH_STATES:
            found.append("第 %d 行换表三态不在闭集 %s：%s" % (idx, sorted(EPOCH_STATES), state))
        if adopted_n + refused_n > cands_n:
            found.append("第 %d 行通过＋否决超过候选数（说明有东西在闸外被放行）：%s" % (idx, line))
        if epoch_n in seen:
            found.append("纪元 %d 出现了不止一行（每纪元只能一行）" % epoch_n)
        seen.add(epoch_n)
    if not seen:
        found.append("纪元账是空的（每纪元必须落一行三态）")
    return found


def check_epochs():
    path = epoch_file()
    if path is None:
        note("纪元账不在本机（私有没有在 CI 里）——显式非红")
        return
    for p in epoch_problems(path.read_text(encoding="utf-8")):
        err("纪元账：" + p)


# ── selftest：五负例必须真的红 ────────────────────────────────────────────
def selftest():
    cases = []

    good_immutable = (
        'const val DELIVERABLE_ON_DISK = "deliverable-on-disk"\n'
        'const val DESTRUCTIVE_CONFIRM = "destructive-confirm"\n'
        'const val EMERGENCY_APPROVAL = "emergency-approval"\n'
        'val STOP_LOSS_WHITELIST: Set<String> = setOf("stop_machine","quarantine","rollback","freeze_writes")\n'
        'val RELAXATION_MARKERS: Set<String> = setOf("开关")\n'
        'val ALL: List<ImmutableCriterion> = listOf(\n'
        '    ImmutableCriterion(id = DELIVERABLE_ON_DISK, lawRef = "L19"),\n'
        '    ImmutableCriterion(id = DESTRUCTIVE_CONFIRM, lawRef = "L16"),\n'
        '    ImmutableCriterion(id = EMERGENCY_APPROVAL, lawRef = "L21"),\n'
        ')\n'
    )
    cases.append(("正例：清单照原样", immutable_problems(good_immutable) == []))
    cases.append(("负例：清单被做成可配置", immutable_problems(good_immutable.replace("val ALL:", "var ALL:")) != []))
    cases.append(("负例：白名单被加一件",
                  immutable_problems(good_immutable.replace('"freeze_writes")', '"freeze_writes","ignore")')) != []))
    cases.append(("负例：法典依据被改",
                  immutable_problems(good_immutable.replace('lawRef = "L19"', 'lawRef = "L99"')) != []))

    cases.append(("正例：检查条目", scalar_problems_in_code('data class CheckItem(val name: String, val passed: Boolean)') == []))
    cases.append(("负例：判据输出分数",
                  scalar_problems_in_code('data class CheckItem(\n    val name: String,\n    val score: Double,\n)') != []))
    cases.append(("负例：小数字面量", scalar_problems_in_code('fun f(): Boolean = 0.5 > 0') != []))

    prev = [{"id": "A1", "scene": "s", "oldVerdict": "o", "humanVerdict": "不通过：x", "evidence": "e", "screenedBy": "蓝总管", "signedBy": ""}]
    cases.append(("正例：只增", anchor_violations(prev, prev + [dict(prev[0], id="A2")]) == []))
    cases.append(("负例：删行", anchor_violations(prev, []) != []))
    tampered = [dict(prev[0], humanVerdict="通过：x")]
    cases.append(("负例：静默改正文不补签", anchor_violations(prev, tampered) != []))
    resigned = [dict(prev[0], humanVerdict="通过：x", signedBy="老婆", signedAt="2026-10-31")]
    cases.append(("正例：改正文并补签", anchor_violations(prev, resigned) == []))
    cases.append(("负例：锚定集一边倒", any("一边倒" in p for p in anchor_schema_problems(prev))))
    mixed = prev + [dict(prev[0], id="A2", humanVerdict="通过：x")]
    cases.append(("正例：两侧都有", not any("一边倒" in p for p in anchor_schema_problems(mixed))))
    cases.append(("负例：准入被塞进宰相路径", leak_in_code("val a = CriteriaEvolution.admit(c, s)", "agent/RunEngine.kt") != []))
    cases.append(("正例：宰相只查表", leak_in_code("val t = PolicyRuntime.current().table()", "agent/RunEngine.kt") == []))

    good_ledger = "2964|3|1|2|APPLIED\n2965|1|0|1|NO_CHANGE\n"
    cases.append(("正例：五列三态", epoch_problems(good_ledger) == []))
    cases.append(("负例：第四态", epoch_problems("2964|0|0|0|MAYBE\n") != []))
    cases.append(("负例：闸外放行", epoch_problems("2964|1|1|1|APPLIED\n") != []))
    cases.append(("负例：同一纪元两行", epoch_problems("2964|0|0|0|APPLIED\n2964|0|0|0|APPLIED\n") != []))

    bad = [name for name, ok in cases if not ok]
    print("criteria selftest: %d/%d" % (len(cases) - len(bad), len(cases)))
    for name in bad:
        print("  [FAIL] %s" % name)
    return 0 if not bad else 1


def main():
    for name in SURFACE:
        if not (POLICY / name).exists():
            err("缺少判据产物文件：%s" % name)
    immutable_src = (POLICY / "ImmutableCriteria.kt")
    if immutable_src.exists():
        for p in immutable_problems(strip_comments(immutable_src.read_text(encoding="utf-8"))):
            err("不可进化清单：" + p)
    for p in runtime_leak_problems():
        err(p)
    for p in scalar_surface_problems():
        err(p)
    for p in scalar_repo_problems():
        err(p)
    check_anchors()
    check_epochs()

    for msg in explicit:
        print("[explicit] %s" % msg)
    for msg in problems:
        print("::error::%s" % msg)
    if problems:
        print("criteria gates: RED（%d 项）" % len(problems))
        return 1
    print("criteria gates: OK")
    return 0


if __name__ == "__main__":
    sys.exit(selftest() if "--selftest" in sys.argv else main())
