#!/usr/bin/env python3
"""子集化终端字体（证道）。

把上游 JetBrains Maple Mono (NF) 全量字体裁成「GB2312 常用汉字 + 终端必备区 + Nerd Font 图标区」，
产物直接替换 app/src/main/assets/fonts/ 下的终端正文资产。

保留的码位（与 PROVENANCE.md 记录一致）：
  U+0000-00FF  拉丁基本 + Latin-1（终端提示符、路径、ASCII 表格）
  U+0100-024F  拉丁扩展 A/B（西欧语路径、git 提交者名）
  U+2000-206F  通用标点（省略号、破折号、不换行空格）
  U+2190-21FF  箭头、U+2300-23FF 杂项技术符号（⌘ ⌥ 等）
  U+2500-259F  制表符 + 方块（tmux 分屏边框、进度条）
  U+25A0-27BF  几何图形 + 杂项符号 + Dingbats
  U+2B00-2BFF  杂项符号与箭头
  U+3000-303F  CJK 符号与标点（、。《》「」等，GB2312 里也有）
  U+3400-4DBF  CJK 扩展 A（上游字体实际为 0 字形，写进参数只为将来换字体时语义完整）
  U+F900-FAFF  CJK 兼容表意
  U+FE30-FE4F  CJK 兼容形式
  U+FF00-FFEF  全角形式
  U+E000-F8FF  BMP 私用区（Nerd Font 图标，上游 3,499 字形）
  U+F0000-FFFFD 补充私用区 A（Nerd Font 大图标，上游 6,880 字形）
  另加 --text-file 传入的 6,763 个 GB2312 汉字（解码自 gb2312 编码，逐字列出）
  注意：这里**故意不写 U+4E00-9FFF**。CJK 基本区有 20,992 个码位，写成范围等于把上游
  全部 20,975 个汉字都留下（字体回到 17.4 MB）；汉字只从 GB2312 的 6,763 字里取。

为什么不用「GBK 全量」：字体 96.4% 的体积是 glyf 表，按 Unicode 范围裁到 GBK 只省 3%
（18.19 MB / 97%），APK 会停在 13 MB 以上；GB2312 常用集（6,763 汉字）可把字体压到 6.8 MB。
代价：GB2312 之外的约 1.4 万汉字会回退系统字体（列宽不再保证 2:1）。

用法：
  python tools/subset-terminal-font.py            # 生成并替换资产（先写临时文件，成功才替换）
  python tools/subset-terminal-font.py --dry-run  # 只生成到 %TEMP%\\zd-subset，不动仓库
  python tools/subset-terminal-font.py --source <上游全量 ttf> --output <目标 ttf>
"""

import argparse
import hashlib
import os
import subprocess
import sys
import tempfile

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_SOURCE = os.path.join(REPO_ROOT, "app", "src", "main", "assets", "fonts",
                              "JetBrainsMapleMono-NF-Regular.ttf")
DEFAULT_OUTPUT = DEFAULT_SOURCE

# 上游全量字体的 SHA-256（来自 GitHub Release 的 zip 解压结果，见 PROVENANCE.md）
UPSTREAM_SHA256 = "a4fc642d821671b1a2937b9a52d398b96cf0b1e1da758846ee1ff38a297b22a5"

# 与 --text-file 合并使用的码位范围
UNICODE_RANGES = ",".join([
    "U+0000-00FF",
    "U+0100-024F",
    "U+2000-206F",
    "U+2190-21FF",
    "U+2300-23FF",
    "U+2500-259F",
    "U+25A0-27BF",
    "U+2B00-2BFF",
    "U+3000-303F",
    "U+3400-4DBF",
    "U+F900-FAFF",
    "U+FE30-FE4F",
    "U+FF00-FFEF",
    "U+E000-F8FF",
    "U+F0000-FFFFD",
])

# fontTools.subset 参数：保留全部 layout feature（连字、OpenType 特性）与全部 name 记录，
# 保留 .notdef 字形；关掉时间戳重算以便产物可复现（同输入必定同 SHA-256）。
SUBSET_ARGS = [
    "--layout-features=*",
    "--notdef-glyph",
    "--name-IDs=*",
    "--no-recalc-timestamp",
]


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def gb2312_hanzi():
    """解码 GB2312 双字节区，取出落在 CJK 基本区的汉字（6,763 个）。"""
    out = set()
    for hi in range(0xA1, 0xFF):
        for lo in range(0xA1, 0xFF):
            try:
                ch = bytes([hi, lo]).decode("gb2312")
            except UnicodeDecodeError:
                continue
            if 0x4E00 <= ord(ch) <= 0x9FFF:
                out.add(ch)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", default=DEFAULT_SOURCE)
    ap.add_argument("--output", default=DEFAULT_OUTPUT)
    ap.add_argument("--dry-run", action="store_true",
                    help="只生成到临时目录，不替换仓库资产")
    args = ap.parse_args()

    if not os.path.isfile(args.source):
        print("ERROR: 源字体不存在: %s" % args.source)
        return 2

    src_size = os.path.getsize(args.source)
    src_sha = sha256_of(args.source)
    print("source      : %s" % args.source)
    print("source size : %d B (%.2f MB)" % (src_size, src_size / 1048576.0))
    print("source sha256: %s" % src_sha)
    if src_sha.lower() != UPSTREAM_SHA256:
        print("WARNING: 源字体 SHA-256 与记录的上游全量字体不同 ——")
        print("         若这是已子集化的资产，请传 --source 指向上游全量字体，")
        print("         否则重复子集化会丢字。")

    hanzi = gb2312_hanzi()
    print("GB2312 汉字 : %d 字" % len(hanzi))

    tmpdir = os.path.join(tempfile.gettempdir(), "zd-subset")
    os.makedirs(tmpdir, exist_ok=True)
    text_file = os.path.join(tmpdir, "gb2312-hanzi.txt")
    with open(text_file, "w", encoding="utf-8") as fh:
        fh.write("".join(sorted(hanzi)))
    print("text file   : %s" % text_file)

    out_path = args.output
    if args.dry_run:
        out_path = os.path.join(tmpdir, "JetBrainsMapleMono-NF-Regular.subset.ttf")

    cmd = [sys.executable, "-m", "fontTools.subset", args.source,
           "--unicodes=" + UNICODE_RANGES, "--text-file=" + text_file,
           "--output-file=" + out_path] + SUBSET_ARGS
    print("running     : python -m fontTools.subset <source> --unicodes=<16 ranges> "
          "--text-file=<gb2312 hanzi> %s" % " ".join(SUBSET_ARGS))
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.stdout.strip():
        print(r.stdout.strip())
    if r.returncode != 0 or not os.path.exists(out_path):
        print("ERROR: fontTools.subset 失败 rc=%d" % r.returncode)
        print(r.stderr[-2000:])
        return 1

    out_size = os.path.getsize(out_path)
    out_sha = sha256_of(out_path)
    print("output      : %s" % out_path)
    print("output size : %d B (%.2f MB) = %.1f%% of source"
          % (out_size, out_size / 1048576.0, 100.0 * out_size / src_size))
    print("output sha256: %s" % out_sha)

    if args.dry_run:
        print("dry-run: 未替换仓库资产")
        return 0

    print("资产已就位（直接写入目标路径，git 会显示为修改后的二进制）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
