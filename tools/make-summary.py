# -*- coding: utf-8 -*-
"""证道项目工具：把第一方代码汇总成 TXT（供其他 AI 评审）。

用法：python tools/make-summary.py
输出：C:\\Users\\guoli\\证道-完整代码汇总.txt

2026-10-28 重写（隐患审计 A-P1-4 / B-3）：原版 FILES 是**手写清单**，终端搬走后 11 条路径失效
（`rootfs/*`、`terminal/*`、`res/layout/activity_main.xml`、`cpp/*` 等），一跑就崩；
同时又漏掉新模块（`core/`、`native-core/`、`baopu/plan/`）。
现在改为**按目录递归采集**：不再维护路径清单，新文件自动纳入；只按扩展名与目录黑名单过滤。
"""
import io
import os

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = r"C:\Users\guoli\证道-完整代码汇总.txt"

# 采集根（相对仓库根）。终端已搬去 zhengdao-term，这里不再含 rootfs/、terminal-core/。
ROOTS = [
    "app/src/main",
    "app/src/test",
    "app/src/androidTest",
    "core/src/main",
    "native-core/src/main",
    "gradle",
    "tools",
    ".github/workflows",
]
# 单文件（不在上面的目录里，但要收）
FILES = [
    "PROVENANCE.md",
    "THIRD-PARTY-LICENSES.md",
    "README.md",
    "AGENTS.md",
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle.properties",
    "app/build.gradle.kts",
    "core/build.gradle.kts",
    "native-core/build.gradle.kts",
]
EXTS = {".kt", ".kts", ".java", ".xml", ".py", ".yml", ".yaml", ".toml", ".properties", ".pro", ".sh", ".md"}
SKIP_DIRS = {"build", ".gradle", "node_modules", ".idea", "assets", "jniLibs",
             "mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi"}
MAX_BYTES = 2 * 1024 * 1024   # 单文件上限：超大产物混入时跳过


def read(path):
    with io.open(path, "r", encoding="utf-8-sig", errors="replace") as f:
        return f.read()


def walk_files():
    """递归采集；返回按路径排序的列表（稳定输出，便于 diff）。"""
    seen = set()
    out = []
    for root in ROOTS:
        base = os.path.join(PROJ, root)
        if not os.path.isdir(base):
            continue
        for cur, dirs, files in os.walk(base):
            dirs[:] = sorted(d for d in dirs if d not in SKIP_DIRS)
            for name in sorted(files):
                if os.path.splitext(name)[1].lower() not in EXTS:
                    continue
                full = os.path.join(cur, name)
                rel = os.path.relpath(full, PROJ).replace("\\", "/")
                if rel in seen:
                    continue
                try:
                    if os.path.getsize(full) > MAX_BYTES:
                        continue
                except OSError:
                    continue
                seen.add(rel)
                out.append(rel)
    for rel in FILES:
        if rel in seen:
            continue
        if os.path.isfile(os.path.join(PROJ, rel)):
            seen.add(rel)
            out.append(rel)
    return sorted(out)


def main():
    files = walk_files()
    parts = []
    missing = []
    for rel in files:
        path = os.path.join(PROJ, rel)
        if not os.path.isfile(path):
            missing.append(rel)
            continue
        parts.append("\n\n" + "=" * 78 + "\n== " + rel + "\n" + "=" * 78 + "\n")
        parts.append(read(path))
    parts.append("\n\n" + "=" * 78 + "\n== 采集统计\n" + "=" * 78 + "\n")
    parts.append("文件数：%d\n" % len(files))
    if missing:
        parts.append("清单中不存在（已跳过，不视为错误）：%s\n" % "、".join(missing))
    with io.open(OUT, "w", encoding="utf-8") as f:
        f.write("".join(parts))
    print("已写出：%s（%d 个文件）" % (OUT, len(files)))
    if missing:
        print("跳过不存在的单文件：%s" % "、".join(missing))


if __name__ == "__main__":
    main()
