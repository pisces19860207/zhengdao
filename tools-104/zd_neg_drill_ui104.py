# 第一百零四批负例演练（mutate → 聚焦测试红 → restore → 逐字节复原）
# 靶子：派工单 §2 负例表里能量化到纯判据层的那三条。
#
# 崩溃安全（2026-10-21 实亏）：Windows 下 Python 不能直接 exec ./gradlew（WinError 193），
# 首版崩在变异之后、复原之前，把「不抢占」分支留在了删除态（靠逐字段核对才捞回来）。
# 现在的形状：
#   ① 每个变异都包 try/finally，无论测试怎么崩都先复原；
#   ② 复原用**内存快照逐字节写回**（DecisionCardRules.kt 是本批新增、git 未跟踪，
#      git checkout 救不了未跟踪文件——首版就是这么翻车的）；
#   ③ 跑测试走 cmd /c gradlew.bat。
import hashlib, io, subprocess, sys, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)

FILES = [
    "app/src/main/java/com/example/zhengdao/ui/baopu/DecisionCardRules.kt",
]

def sha(b):
    return hashlib.sha256(b).hexdigest()

# 内存快照（变异前取；bytes 级）
SNAP = {p: io.open(p, "rb").read() for p in FILES}

def restore():
    for p, b in SNAP.items():
        if io.open(p, "rb").read() != b:
            io.open(p, "wb").write(b)
        assert sha(io.open(p, "rb").read()) == sha(b), f"复原校验失败：{p}"

def mutate(old, new):
    for p in FILES:
        s = io.open(p, encoding="utf-8").read()
        n = s.count(old)
        assert n == 1, f"锚点命中 {n} 次（要恰好 1）：{old[:40]}"
        io.open(p, "w", encoding="utf-8", newline="").write(s.replace(old, new))

def run_tests():
    r = subprocess.run(
        ["cmd", "/c", "gradlew.bat", ":app:testDebugUnitTest", "--console=plain",
         "--tests", "com.example.zhengdao.ui.baopu.DecisionCardRulesTest",
         "--tests", "com.example.zhengdao.ui.baopu.DecisionCardSpecsTest"],
        capture_output=True, text=True, timeout=900,
    )
    return r.returncode, (r.stdout + r.stderr)

CASES = [
    ("N-单卡槽不抢占",
     "    if (current != null && pending.any { it == current }) return current\n    return pending.minByOrNull { it.kind.ordinal }",
     "    return pending.minByOrNull { it.kind.ordinal }"),
    ("N-按钮否左确认右",
     "    return buttons.sortedBy { order[it.role] ?: 99 }",
     "    return buttons"),
    ("N-工具约束不给采纳",
     "    ProposalKind.SKILL_BODY, ProposalKind.PERSONA_APPEND -> true\n    ProposalKind.TOOL_RULE -> false",
     "    ProposalKind.SKILL_BODY, ProposalKind.PERSONA_APPEND, ProposalKind.TOOL_RULE -> true"),
]

results = []
for name, old, new in CASES:
    try:
        mutate(old, new)
        code, out = run_tests()
        results.append((name, code != 0, code))
    finally:
        restore()  # 无论成败都复原

print("=== 负例演练结果 ===")
ok = True
for name, red, code in results:
    print(f"{'✓' if red else '✗'} {name}：退出码 {code}（期望非 0）")
    ok = ok and red
dirty = subprocess.run(["git", "status", "--short"], capture_output=True, text=True).stdout.strip()
resid = [l for l in dirty.splitlines() if "DecisionCardRules.kt" in l]
print("DecisionCardRules.kt 状态：", resid if resid else "（无改动残留，干净）")
sys.exit(0 if ok and not resid else 1)
