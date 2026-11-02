#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-test-platform-gate.py —— 测试平台无关闸（362 批，2026-11-02）。

目的
----
CI 跑在 Linux，本机是 Windows。测试里只要出现「平台形状」的路径写法，就会出现
**本机绿、CI 红**。今天两趟红都是这么来的（ci #1747，修于 b2ffe874）：

    val copy = "${ws.absolutePath}\\.shadow\\op-296"     // Kotlin 源码里就是这么写的
    File(copy, "notes/a.md").writeText(...)
    ⇒ Linux 上得到一个带反斜杠的畸形文件名 ⇒ FileNotFoundException

四条规则
--------
    T1 反斜杠拼路径   字符串的真实值里有反斜杠
    T2 盘符字面量     真实值里有 `X:` + 分隔符（`C:\\`、`D:/`）
    T3 os.name 分支   读系统属性 os.name（分支＝把差异藏进断言，而不是修掉写法）
    T4 写死临时目录   真实值以 `/tmp`、`/var/tmp`、`/home/`、`%TEMP%` 这类绝对路径开头
    MARK 豁免缺理由   写了 `platform-gate: allow` 却没写理由 ⇒ 底层那条也不豁免

口径（务实边界，写在明处）
--------------------------
* **只扫测试源码**：`<模块>/src/<sourceSet>/` 里 sourceSet 名含 "test"
  （test／testDebug／androidTest…）的 `.kt`／`.java`；生产代码不在本闸。
* **T1／T2／T4 要「碰磁盘」上下文**：反斜杠、盘符、写死路径只有真的被拿去开文件才有
  平台差异。所以下面三种才算：
      ① 字面量自己就是 `File(...)`／`createTempDirectory(...)` 这类磁盘调用的参数
        —— 只看**最内层**调用：`File(p).writeText("{\\\"a\\\":1}")` 里那串是内容不是路径；
      ② 字面量里提到 `absolutePath`／`.path`／`tempDir`／`separator` 等真实路径来源；
      ③ 一跳：`val NAME = "…\\…"` 且同文件里出现 `File(NAME`。
  ⇒ 喂给纯函数的假路径（`assertEquals(albumOf(x), "Pictures\\Screenshots\\a.png")`）
    不算违规——它本来就该是 Windows 形状，测的是字符串逻辑，不落盘。
  ⇒ `resolve` 这种名字不算磁盘调用：本仓库 `BaopuWorkspace.resolve` 是纯字符串解析，
    `Path.resolve` 的漏判由 ②③ 两条兜住。要更严另立批。
* **正则上下文不算 T1**：Kotlin 源码里 `\\d`（正则）与 `C:\\x`（路径）字节完全一样，
  只能看这个字面量是谁的参数。落在 `Regex(...)`／`Pattern.compile(...)` 里的判成正则。
  判据＝字面量起始位置的**外层调用名**（跨行也带），不是「本行出现过 Regex 就放行」。
* **注释不算**：注释里写「别用 C:\\ 拼路径」是本闸要鼓励的，不许自咬。
* **豁免标记的位置**：`platform-gate: allow 理由：……` 写在命中行**同行或紧邻上一行**都算
  （上一行那种是给长行留的出口）；隔两行不算——豁免要贴着它放行那条，不能一次罩住半页。
* T3 不看磁盘：`System.getProperty("os.name")` 本身就是平台分支；断言消息里出现
  字符串 `os.name` 不算（它的外层调用不是取属性的那个）。
* 已知放过：给 os.name 换别名再分支，本闸看不见——它钉的是读取点；
  一跳以上的数据流（假路径经三层函数才落到 File）也看不见。要收更严另立批。

用法
----
    python3 tools/check-test-platform-gate.py             # 判闸（CI 接线见 ci.yml）
    python3 tools/check-test-platform-gate.py --selftest   # 四条负例，证明不是恒绿
    python3 tools/check-test-platform-gate.py --dump        # 打外层调用名，调口径用

退出码：0 = 测试源码里没有平台形状；1 = 有违规；2 = 环境错误（一个测试源文件都没扫到）。
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import sys
import tempfile

try:  # 本机控制台默认 GBK：中文与 ✔/✘ 会被吃成问号，照 check-inflight.py 钉死 UTF-8
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MARKER = "platform-gate: allow"
TITLE = "测试平台无关闸（362）"
TEST_EXT = (".kt", ".java")
SKIP_DIRS = {"build", ".gradle", "tmp", ".git", "node_modules"}

# 反斜杠在这里是正则转义，不是路径分隔符
REGEX_CALLS = {"Regex", "Pattern.compile", "compile", "matches", "matchEntire", "toMatchRegex"}
# 读系统属性/环境变量的调用名 —— 它的参数里出现 os.name 才算 T3
PROP_CALLS = {"System.getProperty", "getProperty", "getenv", "systemProperty"}
# 真会碰磁盘的调用 —— 路径写法只有走到这里才有平台差异。
# 只看**最内层**那个调用：`File(p).writeText("{\\\"a\\\":1}")` 里字面量是**内容**不是路径，
# 外层是 File 也不能算（祖先链一律不算，这是踩过的口径）。
FS_CALLS = {
    "File", "Path", "Paths.get", "Files.get", "createTempFile", "createTempDirectory",
    "mkdir", "mkdirs", "readText", "readBytes", "listFiles", "walkTopDown",
    "FileInputStream", "FileOutputStream",
}
# 不算 FS 的：`resolve`（本仓库 BaopuWorkspace.resolve 是纯字符串解析，Path.resolve 的
# 漏判由「提到 absolutePath」与「那一跳」两条兜住）、`delete/writeText/copyTo`（参数是内容或接收者）。
FS_MENTION_RE = re.compile(r"absolutePath|canonicalPath|\.path\b|tempDir|createTempDir|separator")

CALL_NAME_RE = re.compile(r"([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)?)\s*$")
BIND_RE = re.compile(r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*=")
DRIVE_RE = re.compile(r"(?<![A-Za-z0-9:/\\])[A-Za-z]:[\\/]")
TMP_RE = re.compile(r"^(?:/tmp(?:[/.]|$)|/var/tmp|/private/tmp|/home/|%(?:TEMP|TMPDIR|TMP)%[\\/])")
OSNAME_RE = re.compile(r"os\.name")

RULE_HELP = {
    "T1": '反斜杠拼路径（Linux 上得到一个畸形文件名）。改 `File(parent, "a/b")` 或 `resolve()`。',
    "T2": "盘符字面量（只有 Windows 认）。用运行时取到的目录拼。",
    "T3": "os.name 分支（把平台差异藏进断言，而不是把路径写法改对）。",
    "T4": '写死临时目录。改用 `System.getProperty("java.io.tmpdir")` 或 JUnit 的 `@TempDir`。',
    "MARK": "豁免要写理由：`// platform-gate: allow 理由：……`",
}
ORDER = ("T1", "T2", "T3", "T4", "MARK")


def decode(body: str) -> str:
    r"""解出字符串字面量的真实值：源码里的 `\\` 还原成一个 `\`。"""
    out = []
    i = 0
    while i < len(body):
        if body[i] == "\\" and i + 1 < len(body):
            out.append(body[i + 1])
            i += 2
            continue
        out.append(body[i])
        i += 1
    return "".join(out)


def tokenize(line: str, st: dict) -> tuple[list[tuple[str, str, tuple[str, ...]]], str]:
    """一行切成 (token 串, 去掉字符串后的代码壳)。token kind: code | str。

    st 带跨行状态：block（在块注释里）、triple（在三引号串里）、stack（调用名栈）。
    注释切掉，字符串原文留着（真实值由 decode 再算）；壳只留给「变量名有没有被拿去
    碰磁盘」那一跳用，规则本身不看壳。
    """
    toks: list[tuple[str, str, tuple[str, ...]]] = []
    code: list[str] = []
    shell: list[str] = []
    stack = st["stack"]
    k = 0
    n = len(line)

    def emit(kind: str, value: str) -> None:
        toks.append((kind, value, tuple(stack)))
        shell.append('""' if kind == "str" else value)

    while k < n:
        if st["triple"]:
            e = line.find('"""', k)
            if e < 0:
                emit("str", line[k:])
                break
            emit("str", line[k:e])
            st["triple"] = False
            k = e + 3
            continue
        if st["block"]:
            e = line.find("*/", k)
            if e < 0:
                break
            st["block"] = False
            k = e + 2
            continue
        if line.startswith("/*", k):
            st["block"] = True
            k += 2
            continue
        if line.startswith("//", k):
            break
        if line.startswith('"""', k):
            e = line.find('"""', k + 3)
            if e < 0:
                emit("str", line[k + 3:])
                st["triple"] = True
                break
            emit("str", line[k + 3:e])
            k = e + 6
            continue
        if line[k] == '"':
            e = k + 1
            while e < n:
                if line[e] == "\\":
                    e += 2
                    continue
                if line[e] == '"':
                    break
                e += 1
            emit("str", line[k + 1:min(e, n)])
            k = e + 1
            continue
        if line[k] == "(":
            m = CALL_NAME_RE.search("".join(code).rstrip())
            stack.append(m.group(1) if m else "")
            code.append("(")
            shell.append("(")
            k += 1
            continue
        if line[k] == ")":
            if stack:
                stack.pop()
            code.append(")")
            shell.append(")")
            k += 1
            continue
        code.append(line[k])
        shell.append(line[k])
        k += 1
    return toks, "".join(shell)


def in_calls(ctx: tuple[str, ...], names: set[str]) -> bool:
    """字面量的外层调用栈里有没有这个名字（整名或叶子名都算，跨行也带着栈）。"""
    return any(n and (n in names or n.split(".")[-1] in names) for n in ctx)


def reason_of(raw_line: str) -> str | None:
    """这一行的豁免理由；写了标记没写理由 ⇒ 空串；没有标记 ⇒ None。"""
    idx = raw_line.find(MARKER)
    if idx < 0:
        return None
    rest = raw_line[idx + len(MARKER):].strip()
    return rest if re.search(r"[\u4e00-\u9fffA-Za-z0-9]", rest) else ""


def hit(name: str, ln: int, rule: str, value: str, ctx: tuple[str, ...], fs: bool) -> dict:
    return {
        "file": name, "line": ln, "rule": rule, "fs": fs,
        "value": value.strip()[:70],
        "ctx": ".".join(c for c in ctx if c)[-36:],
    }


def candidates(name: str, ln: int, real: str, ctx: tuple[str, ...]) -> list[dict]:
    """一个字符串字面量的真实值 ⇒ 候选违规（带 fs 标记，还没判要不要报）。"""
    out: list[dict] = []
    probe = "" if in_calls(ctx, REGEX_CALLS) else real  # 正则里的反斜杠是转义，不当路径看
    fs = bool(FS_MENTION_RE.search(probe)) or in_calls(ctx[-1:], FS_CALLS)
    for rule in ("T1", "T2", "T4"):
        bad = (rule == "T1" and "\\" in probe) or (rule == "T2" and DRIVE_RE.search(probe)) \
            or (rule == "T4" and TMP_RE.match(probe))
        if bad:
            out.append(hit(name, ln, rule, probe, ctx, fs))
    if OSNAME_RE.search(real) and in_calls(ctx, PROP_CALLS):
        out.append(hit(name, ln, "T3", real, ctx, True))
    return out


def hop_bound(code_text: str, line: str) -> bool:
    """这一行是不是 `val NAME = "…"` 且 NAME 在同文件里被拿去碰磁盘（口径③那一跳）。"""
    m = BIND_RE.search(line)
    if not m:
        return False
    api = "|".join(re.escape(n) for n in sorted(FS_CALLS))
    return bool(re.search(r"\b(?:%s)\(\s*(?:[A-Za-z_]\w*\s*,\s*)?%s\b" % (api, m.group(1)), code_text))


def scan_text(name: str, text: str) -> list[dict]:
    """一个测试源文件 ⇒ 违规清单（已过豁免）。"""
    lines = text.splitlines()
    marked: dict[int, str] = {}
    bad_marker: dict[int, str] = {}
    for ln, raw in enumerate(lines, 1):
        reason = reason_of(raw)
        if reason is None:
            continue
        (marked if reason else bad_marker)[ln] = reason

    out: list[dict] = []
    shell: list[str] = []
    st = {"block": False, "triple": False, "stack": []}
    for ln, raw in enumerate(lines, 1):
        toks, line_shell = tokenize(raw, st)
        shell.append(line_shell)
        for kind, value, ctx in toks:
            if kind == "str":
                out += candidates(name, ln, decode(value), ctx)
    code_text = "\n".join(shell)
    for h in out:
        if not h["fs"] and h["rule"] != "T3" and hop_bound(code_text, lines[h["line"] - 1]):
            h["fs"] = True
    kept = [h for h in out if h["fs"] and not (h["line"] in marked or h["line"] - 1 in marked)]
    for ln in bad_marker:
        kept.append(hit(name, ln, "MARK", MARKER, (), True))
    return kept


def test_source_files(base: str) -> list[str]:
    """`<base>/<模块>/src/<名含 test 的 sourceSet>/` 下的 .kt／.java（相对 base）。"""
    found: list[str] = []
    if not os.path.isdir(base):
        return found
    for mod in sorted(os.listdir(base)):
        src = os.path.join(base, mod, "src")
        if not os.path.isdir(src):
            continue
        for sset in sorted(os.listdir(src)):
            if "test" not in sset.lower() or sset.lower() in SKIP_DIRS:
                continue
            root = os.path.join(src, sset)
            for dp, dirs, files in os.walk(root):
                dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
                for fn in sorted(files):
                    if fn.endswith(TEST_EXT):
                        rel = os.path.relpath(os.path.join(dp, fn), base)
                        found.append(rel.replace("\\", "/"))
    return found


def read(path: str) -> str:
    with open(path, encoding="utf-8", errors="replace") as fh:
        return fh.read()


def collect(base: str) -> list[dict]:
    hits: list[dict] = []
    for rel in test_source_files(base):
        hits += scan_text(rel, read(os.path.join(base, *rel.split("/"))))
    return sorted(hits, key=lambda h: (h["file"], h["line"], h["rule"]))


def judge(hits: list[dict], scanned: int) -> int:
    for h in hits:
        print(
            "::error title=%s::%s:%d 命中 %s ｜ 值=%r%s"
            % (TITLE, h["file"], h["line"], h["rule"], h["value"],
               "（外层调用 %s）" % h["ctx"] if h["ctx"] else "")
        )
        print("    ↳ %s" % RULE_HELP[h["rule"]])
    by_rule = {r: sum(1 for h in hits if h["rule"] == r) for r in ORDER}
    print(
        "[测试平台无关闸] 扫 %d 个测试源文件 ｜ %s ｜ 违规 %d 处 / %d 行"
        % (scanned, " ／ ".join("%s=%d" % (r, by_rule[r]) for r in ORDER),
           len(hits), len({(h["file"], h["line"]) for h in hits}))
    )
    if hits:
        print("    确实要留平台形状的，写 `// platform-gate: allow 理由：……`（无理由本闸照样红）。")
    return 1 if hits else 0


def selftest() -> int:
    """临时目录里造一个假测试树：四条各一个负例＋四个控制项，不动仓库。"""
    cases = [
        ("T1 反斜杠拼路径（今天的原形）", "Backslash.kt",
         'class A {\n    val copy = "${ws.absolutePath}\\\\.shadow\\\\op-296"\n'
         '    init { File(copy, "notes/a.md").writeText("x") }\n}\n', ["T1"]),
        ("T1 拼接式也抓（靠那一跳）", "Concat.kt",
         'class A {\n    val p = "sub\\\\dir\\\\a.txt"\n'
         '    init { File(p).mkdirs() }\n}\n', ["T1"]),
        ("T2 盘符字面量", "Drive.kt",
         'class A { init { File("C:\\\\Windows\\\\a.txt").readText() } }\n', ["T1", "T2"]),
        ("T3 os.name 分支", "OsName.kt",
         'class A { val posix = !System.getProperty("os.name").startsWith("Windows") }\n', ["T3"]),
        ("T4 写死临时目录", "HardTmp.kt",
         'class A { init { File("/tmp/ws").writeText("x") } }\n', ["T4"]),
        ("控制·正则上下文不算 T1", "RegexOk.kt",
         'class A { val re = Regex("\\\\breturn\\\\b") }\n', []),
        ("控制·注释不算", "CommentOk.kt",
         '// 提醒：别写 C:\\temp 这种路径\n/* 块注释里的 /tmp/ 与 os.name 都不该红 */\nclass A\n', []),
        ("控制·假路径不落盘不算", "FakeData.kt",
         'class A { val rel = listOf("Pictures\\\\Screenshots\\\\a.png", "C:\\\\x\\\\y\\\\a.txt") }\n', []),
        ("控制·豁免带理由＋无理由各一行", "Allow.kt",
         'class A {\n    val p = "a\\\\b"  // platform-gate: allow 只进字符串断言，不碰磁盘\n'
         '    init { File(p).mkdirs() }\n    val q = "c\\\\d"  // platform-gate: allow\n'
         '    init { File(q).mkdirs() }\n}\n', ["MARK", "T1"]),
        ("控制·豁免贴上一行算、隔两行不算", "AllowAbove.kt",
         'class A {\n    // platform-gate: allow 理由：贴上一行也算豁免\n'
         '    val p = "a\\\\b"\n    init { File(p).mkdirs() }\n'
         '    val q = "c\\\\d"\n    init { File(q).mkdirs() }\n}\n', ["T1"]),
        ("控制·内容不是路径", "ContentOk.kt",
         'class A { init { File("d").writeText("{\\\\\"a\\\\\":1}") } }\n', []),
    ]
    tmp = tempfile.mkdtemp(prefix="tpg-")
    ok = True
    try:
        src = os.path.join(tmp, "app", "src", "test", "java", "p")
        os.makedirs(src)
        for _d, fn, body, _w in cases:
            open(os.path.join(src, fn), "w", encoding="utf-8").write(body)
        hits = collect(tmp)
        for desc, fn, _body, want in cases:
            got = sorted({h["rule"] for h in hits if h["file"].endswith(fn)})
            good = got == want if want else not got
            print("  [%s] %s ⇒ %s（got=%s）"
                  % ("✔" if good else "✘", desc, "红" if want else "绿", ",".join(got) or "无"))
            ok = ok and good
        files = test_source_files(tmp)
        good = len(files) == len(cases)
        print("  [%s] 只扫测试源根（files=%d/%d）" % ("✔" if good else "✘", len(files), len(cases)))
        ok = ok and good
        print("  结果：%s" % ("全部通过 ✔" if ok else "有失败 ✘"))
        return 0 if ok else 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main() -> int:
    ap = argparse.ArgumentParser(description="测试平台无关闸（T1 反斜杠／T2 盘符／T3 os.name／T4 写死临时目录）")
    ap.add_argument("--root", default=ROOT, help="仓库根（默认脚本所在仓库）")
    ap.add_argument("--selftest", action="store_true", help="四条负例自测，证明本闸不是恒绿")
    ap.add_argument("--dump", action="store_true", help="把每条命中的外层调用名打出来（调口径用）")
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    if not os.path.isdir(args.root):
        print("::error title=%s::环境错误：找不到 %s" % (TITLE, args.root))
        return 2
    files = test_source_files(args.root)
    if not files:
        print("::error title=%s::一个测试源文件都没扫到（防「没扫到当没红」）" % TITLE)
        return 2
    hits = collect(args.root)
    if args.dump:
        for h in sorted(hits, key=lambda x: (x["rule"], x["ctx"])):
            print("%s ctx=%-38r %s:%d %r" % (h["rule"], h["ctx"], h["file"], h["line"], h["value"]))
        print("dump: %d 处 / %d 文件" % (len(hits), len({h["file"] for h in hits})))
        return 0
    return judge(hits, len(files))


if __name__ == "__main__":
    raise SystemExit(main())
