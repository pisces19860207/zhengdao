#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
第一百一十八批 · 性能基线测量脚本（纯标准库，只读源码，不改任何东西）。

用途：把「主线程同步盘 IO」「每帧/每调用重复编译的正则」「冷启动链上的阻塞点」
三件事从源码里**数出来**，作为性能基线报告的结构化读数。
它不测时间（时间读数来自 :macrobenchmark 真机与 JVM bench，见报告 §2/§4）。

用法（在仓库根或 app/ 目录均可）：
    python tools/perf-scan.py                 # 三段全出
    python tools/perf-scan.py --section a     # 只出正则段
    python tools/perf-scan.py --format json   # 机器可读（报告里贴数字时用）

口径写在报告 `证道设计报告/性能基线-2026-10-21.md` §3。
"""

import argparse
import json
import os
import re
import sys

# 剥注释用（与 ui/SettingsStorageMainThreadIoGateTest 的 stripComments 同一口径：
# 字符串字面量原样保留，注释里的 Thread( / Regex( 不算数）。
def strip_comments(src: str) -> str:
    """剥注释，但**保持行数与列位置**（注释体用空格填充，换行原样保留）。

    这样报告里的 file:line 与磁盘上的真实行号一致 —— 证据要能一眼点开。
    字符串字面量原样保留（注释里提一句 Thread( / Regex( 不算数）。
    """
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith('"""', i):
            end = src.find('"""', i + 3)
            end = n if end < 0 else end + 3
            out.append(src[i:end])
            i = end
        elif c in '"\'':
            q = c
            out.append(c)
            i += 1
            while i < n:
                d = src[i]
                out.append(d)
                i += 1
                if d == '\\' and i < n:
                    out.append(src[i])
                    i += 1
                    continue
                if d == q:
                    break
        elif c == '/' and src.startswith('//', i):
            while i < n and src[i] != '\n':
                out.append(' ')
                i += 1
        elif c == '/' and src.startswith('/*', i):
            while i < n and not src.startswith('*/', i):
                out.append('\n' if src[i] == '\n' else ' ')
                i += 1
            out.append('  ')
            i = min(i + 2, n)
        else:
            out.append(c)
            i += 1
    return ''.join(out)


# ── 词汇表 ────────────────────────────────────────────────────────────────
# 主线程会真的等盘的调用（同步 IO / 进程 / binder 一类）。窄口径：宁可漏报不误报。
BLOCKING = [
    r'\.rawQuery\s*\(', r'\bdb\.read\s*[({]', r'\bdb\.write\s*[({]',
    r'\.getSharedPreferences\s*\(', r'\.edit\s*\(\s*\)\s*\.\s*commit\s*\(',
    r'File\s*\([^)]*\)\s*\.(readText|readBytes|writeText|appendText|copyTo|delete|mkdirs|listFiles)\b',
    r'\.(readText|readBytes|writeText|appendText|forEachLine|useLines|listFiles|walkTopDown)\s*\(',
    r'\.(isFile|isDirectory|exists|canRead|length)\s*\(',
    r'BitmapFactory\.\w+', r'Bitmap\.createBitmap',
    r'Runtime\.getRuntime\(\)\.exec', r'ProcessBuilder',
    r'\.join\s*\(\s*\)', r'runBlocking\s*[({]', r'\.get\s*\(\s*\)\s*$',
    r'ContextCompat\.checkSelfPermission', r'WebViewDatabase',
    r'SystemInfoProvider\.\w+', r'dirSizeMb\s*\(',
]
BLOCKING_RE = [(p, re.compile(p)) for p in BLOCKING]

# 冷启动链上的文件（Application → MainActivity → 首屏组合）
COLD_START_FILES = [
    'app/src/main/java/com/example/zhengdao/ZhengdaoApp.kt',
    'app/src/main/java/com/example/zhengdao/MainActivity.kt',
    'app/src/main/java/com/example/zhengdao/ui/AppState.kt',
    'app/src/main/java/com/example/zhengdao/ui/HomeScreen.kt',
    'app/src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt',
    'app/src/main/java/com/example/zhengdao/rootfs/RunLog.kt',
]

COMPOSABLE_RE = re.compile(r'^\s*(?:@\w+[\w().,\s]*)*fun (\w+)\s*\(')
ANNO_COMPOSABLE = re.compile(r'@Composable')
# 内联编译正则：Regex(" 或 Regex("""，且同一行不是 val/const 绑定（那是预编译常量）
INLINE_REGEX = re.compile(r'(?<![\w.])Regex\s*\(\s*(?:""")?["\']')
VAL_BINDING = re.compile(r'^\s*(?:private\s+|internal\s+|public\s+)?(?:val|var|const)\s+\w+')
FUN_RE = re.compile(r'^\s*(?:@\w+[\w().,\s]*)*(?:private\s+|internal\s+|public\s+)?'
                    r'(?:suspend\s+)?(?:inline\s+)?(?:override\s+)?fun\s+(?:<[^>]+>\s+)?([\w.`]+)\s*\(')
OBJ_RE = re.compile(r'^\s*(?:private\s+|internal\s+|public\s+)?(?:object|class)\s+(\w+)')


def kt_files(root='app/src/main/java'):
    for dirpath, _dirs, files in os.walk(root):
        for f in sorted(files):
            if f.endswith('.kt'):
                yield os.path.join(dirpath, f).replace('\\', '/')


def enclosing(lines, idx):
    """返回 (最近的函数名, 最近的 @Composable 标记行距离, 是否在 object/class 顶层作用域)。"""
    name, in_composable, depth = None, False, 0
    for j in range(idx, -1, -1):
        s = lines[j]
        if name is None:
            m = FUN_RE.match(s) or COMPOSABLE_RE.match(s)
            if m:
                name = m.group(1)
        if '@Composable' in s and j < idx:
            # 组合函数：注解在 fun 上方；一旦跨过 fun 头且后面没有更深的函数，认为在组合体内
            pass
        if name is not None and j < idx - 400:
            break
    # @Composable 判定：往上找最近的 fun 头，其前 3 行内是否有 @Composable
    for j in range(idx, -1, -1):
        if FUN_RE.match(lines[j]) or COMPOSABLE_RE.match(lines[j]):
            window = ''.join(lines[max(0, j - 6):j + 1])
            in_composable = '@Composable' in window
            break
    return name, in_composable


def section_a():
    """A 段：内联（每次调用都编译）的正则清单。"""
    rows = []
    for path in kt_files():
        raw = open(path, encoding='utf-8', errors='replace').read().replace('\r\n', '\n')
        src = strip_comments(raw)
        lines = src.split('\n')
        for i, line in enumerate(lines):
            if not INLINE_REGEX.search(line):
                continue
            if VAL_BINDING.match(line):
                continue  # 预编译常量，不算
            name, in_comp = enclosing(lines, i)
            rows.append({
                'file': path, 'line': i + 1, 'fn': name or '?',
                'in_composable': in_comp,
                'pattern': line.strip()[:120],
            })
    return rows


def section_b():
    """B 段：@Composable 体内的同步阻塞调用（组合期 = 主线程）。"""
    rows = []
    for path in kt_files():
        if '/ui/' not in path:
            continue
        raw = open(path, encoding='utf-8', errors='replace').read().replace('\r\n', '\n')
        src = strip_comments(raw)
        lines = src.split('\n')
        for i, line in enumerate(lines):
            _name, in_comp = None, False
            # 便宜的先筛：无阻塞词汇就跳过
            hit = None
            for pat, rx in BLOCKING_RE:
                if rx.search(line):
                    hit = pat
                    break
            if not hit:
                continue
            m = FUN_RE.match(line) or COMPOSABLE_RE.match(line)
            name, in_comp = enclosing(lines, i)
            if not in_comp:
                continue
            rows.append({
                'file': path, 'line': i + 1, 'fn': name or '?', 'vocab': hit,
                'code': line.strip()[:140],
            })
    return rows


def section_c():
    """C 段：冷启动链上的同步阻塞调用（按文件计）。"""
    rows = []
    for path in COLD_START_FILES:
        if not os.path.isfile(path):
            continue
        raw = open(path, encoding='utf-8', errors='replace').read().replace('\r\n', '\n')
        src = strip_comments(raw)
        lines = src.split('\n')
        for i, line in enumerate(lines):
            for pat, rx in BLOCKING_RE:
                if rx.search(line):
                    name, in_comp = enclosing(lines, i)
                    rows.append({
                        'file': path, 'line': i + 1, 'fn': name or '?',
                        'in_composable': in_comp, 'vocab': pat,
                        'code': line.strip()[:140],
                    })
                    break
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--section', default='abc', help='要跑的段（a/b/c 任意组合）')
    ap.add_argument('--format', default='text', choices=['text', 'json'])
    ap.add_argument('--root', default='.', help='仓库根（自动往上找 app/src/main/java）')
    args = ap.parse_args()

    # 允许从 app/ 目录里跑
    if not os.path.isdir('app/src/main/java') and os.path.isdir(os.path.join(args.root, 'app/src/main/java')):
        os.chdir(args.root)

    out = {}
    if 'a' in args.section:
        out['a_inline_regex'] = section_a()
    if 'b' in args.section:
        out['b_composable_blocking'] = section_b()
    if 'c' in args.section:
        out['c_cold_start_blocking'] = section_c()

    if args.format == 'json':
        try:
            sys.stdout.reconfigure(encoding='utf-8')
        except Exception:
            pass
        print(json.dumps(out, ensure_ascii=False, indent=2))
        return 0

    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass
    if 'a_inline_regex' in out:
        rows = out['a_inline_regex']
        print('=== A 段：内联编译的正则（每次调用都 Pattern.compile）===')
        print('合计 %d 处' % len(rows))
        per = {}
        for r in rows:
            per[r['file']] = per.get(r['file'], 0) + 1
        for f, n in sorted(per.items(), key=lambda kv: -kv[1]):
            print('  %3d  %s' % (n, f))
        print('  -- 明细 --')
        for r in rows:
            print('  %s:%d  fn=%s  %s' % (r['file'], r['line'], r['fn'], r['pattern']))
    if 'b_composable_blocking' in out:
        rows = out['b_composable_blocking']
        print('\n=== B 段：@Composable 体内的同步阻塞调用（组合期在主线程）===')
        print('合计 %d 处' % len(rows))
        for r in rows:
            print('  %s:%d  fn=%s  [%s]  %s' % (r['file'], r['line'], r['fn'], r['vocab'], r['code']))
    if 'c_cold_start_blocking' in out:
        rows = out['c_cold_start_blocking']
        print('\n=== C 段：冷启动链上的同步阻塞调用 ===')
        print('合计 %d 处（其中在 @Composable 体内 %d 处）' % (
            len(rows), sum(1 for r in rows if r['in_composable'])))
        per = {}
        for r in rows:
            per[r['file']] = per.get(r['file'], 0) + 1
        for f, n in sorted(per.items(), key=lambda kv: -kv[1]):
            print('  %3d  %s' % (n, f))
        for r in rows:
            print('  %s:%d  fn=%s  comp=%d  [%s]  %s' % (
                r['file'], r['line'], r['fn'], 1 if r['in_composable'] else 0, r['vocab'], r['code']))
    return 0


if __name__ == '__main__':
    sys.exit(main())
