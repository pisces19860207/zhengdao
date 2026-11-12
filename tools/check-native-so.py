#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-native-so.py —— 入库 .so 的门禁：16KB 页对齐 + JNI 入口符号。

为什么要它
    native-core/src/main/jniLibs/arm64-v8a/libzhengdao_core.so 是**人工构建入库**的
    （ci.yml / build.yml 里没有任何步骤构建它，见 docs/ERRATA.md E-045 教训 3）。
    而 16KB 页对齐是"静默降级"型故障：漏了 -Wl,-z,max-page-size=16384 不会崩溃，
    而是 16KB 页设备上 loadLibrary 失败 → CoreNative.isRustAvailable()=false
    → 整条解压路径悄悄退回 Java，谁也不会红。
    同理，JNI 入口符号被 strip / 被改名（proguard 少 keep、R8 改 native 名），
    也是运行时才炸。这两种"人工工序 + 静默失败"的组合，正适合做成门禁。

检查项
    1. jniLibs/<abi>/*.so 的每个 PT_LOAD 段 p_align >= 0x4000（16KB 页设备可加载）
       —— 例外：PROOT_PINNED 里的 proot 四件套（第⑳刀，E-137）。它们是**被 execve
       的独立 ELF 程序**、不是被 loadLibrary 的库，且当前 Termux 官方发行二进制按
       4KB 页构建（换 16KB 对齐构建属产品化待办，见 docs/ERRATA.md E-137）。作为
       交换，四件套每个文件在这里做 **sha256 完整性基线**（值与
       terminal/ProotBinaries.kt / PROVENANCE.md 三处同源一致）——换版本/被篡改
       立刻红。清单之外新增 .so 一律仍按 16KB 硬判（fail-closed）。
    2. libzhengdao_core.so 必须导出 CoreNative.kt 里每个 `external fun` 的 JNI 符号
       （符号名现读 CoreNative.kt 推导，将来加 native 方法会自动纳入门禁）
    3. 仅供信息：.symtab 是否存在及大小（影响入库体积，不影响 APK —— 见 E-045）
    4. **PROOT_PINNED 的四件套必须都在**（顺序 38，第㉗刀）：上面 1 里的 16KB 判据与 sha256
       完整性基线都是 **glob 驱动**的 —— 只在文件**存在**时才跑那一段 ⇒ 删掉/改名四件套里
       任一个，两条判据双双失效而 CI 不红（静默漂移窗口）。这里补一条「四个名字必须都出现在
       扫描结果里」，缺失即 BLOCKER。（`libzhengdao_core.so` 的缺席不在这里补：它已被
       `tools/check-so-provenance.py` 的基线 diff 拦住 —— 基线里记着它那条 sha256，文件一没
       就报 `[so] 删除` 并返回 1。）

用法
    python tools/check-native-so.py [仓库根]     # 默认取本脚本上一级目录
    python tools/check-native-so.py --artifact 'app/build/outputs/apk/debug/*.apk'
                                                 # 611 段B：判**包内** lib/<abi>/*.so 的 16KB 对齐
    python tools/check-native-so.py --selftest    # 合成 APK 自证 --artifact 的判据（4KB 必须红）

退出码
    0 = 全部通过 ；1 = 有 BLOCKER ；2 = 用法/环境错误（找不到仓库、没有 .so）

只依赖标准库（ELF 头自己解析）。CI 里由 .github/workflows/ci.yml 调用，失败即红。

611 段B（第六项检查）：**打包产物**里的 .so 也要过同一条判据
    为什么本脚本原先扫不到：它按 `jniLibs/<abi>/*.so` 扫源码目录，而**依赖自带的 .so**
    （如 libandroidx.graphics.path.so，来自 androidx.graphics:graphics-path）和 merge/strip
    之后的那份只在 `<module>/build/**` 里出现，源码树里根本没有——它们照样进 APK。
    `--artifact` 直接解 zip 读 `lib/<abi>/*.so`，用**同一个 Elf 解析器**判 p_align >= 0x4000；
    红时点名「包内路径 + 来源依赖/模块」（来源取 AGP 的 native-libs-blame 报告，见 blame_map）。
    三种"读不出"一律算失败：不是 zip / 打不开 / 包内条目不是 ELF。
"""

import contextlib
import glob
import hashlib
import io
import re
import struct
import sys
import tempfile
import zipfile
from pathlib import Path

try:  # Windows 控制台默认 GBK：个别字符编不出来时降级为替换，而不是让门禁自己崩
    sys.stdout.reconfigure(errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

PAGE_16K = 0x4000
PT_LOAD = 1
SHT_DYNSYM = 11

CORE_KT_REL = "native-core/src/main/java/com/example/zhengdao/rust/CoreNative.kt"
JNI_CLASS = "Java_com_example_zhengdao_rust_CoreNative_"
CORE_SO = "libzhengdao_core.so"
# .so 的扫描面：:native-core（Rust 核）与 :app
SO_DIRS = (
    "native-core/src/main/jniLibs",   # libzhengdao_core.so（Rust 核，主/终端共用）
    # 2026-10-28：terminal-core 已搬去 zhengdao-term，主仓不再扫它的 jniLibs。
    "app/src/main/jniLibs",
)

# 第⑳刀（E-137）：proot 四件套的 4KB 对齐例外 + 完整性基线。
# 这四个 .so 是 Termux 官方发行二进制（原样聚合分发，GPL 合规口径见 PROVENANCE.md），
# 由 proot 以 PROOT_LOADER 方式装载/被宿主 execve，**不走 bionic loadLibrary**；
# 当前官方构建按 4KB 页对齐 ⇒ 在 16KB 页设备上不可用（记录为产品化待办，与交接单
# §七同口径：我们手上没有 16KB 页设备，证不了；换 Termux 16KB 构建前先钉死哈希）。
# 哈希值与 terminal/ProotBinaries.kt、PROVENANCE.md 三处同源一致；任何一处换版本
# 都必须三处同步 + 重新登记。
PROOT_PINNED = {}  # 250：proo t 四件套已随终端搬去 zhengdao-term，主仓不再要求在场

blockers = []
infos = []


def blocker(msg):
    blockers.append(msg)


def info(msg):
    infos.append(msg)


class Elf:
    """最小 ELF 解析：程序头（PT_LOAD 对齐）+ 动态符号表。"""

    def __init__(self, path):
        self.path = path
        self._load(path.read_bytes(), str(path))

    @classmethod
    def from_bytes(cls, data, label):
        """同一套解析器吃「包内条目」的字节（611 段B：APK/AAR 里的 .so 没有文件路径）。

        为什么不让 artifact 模式先解压到临时目录再走路径版：那会**多一份落盘与清理**，
        且解压后的文件路径不再等于「包内路径」—— 报错要点名的正是包内路径。
        """
        obj = cls.__new__(cls)
        obj.path = label
        obj._load(data, label)
        return obj

    def _load(self, data, label):
        if len(data) < 16:
            raise ValueError(f"{label}: 只有 {len(data)} 字节，连 ELF 头都不够")
        if data[:4] != b"\x7fELF":
            raise ValueError(f"{label}: 不是 ELF 文件（头 4 字节 {data[:4]!r}）")
        self.cls = data[4]  # 1=32 位, 2=64 位
        if data[5] != 1:
            raise ValueError("只支持小端 ELF")
        self.data = data
        if self.cls == 2:
            (self.phoff,) = struct.unpack_from("<Q", data, 0x20)
            self.phentsize, self.phnum = struct.unpack_from("<HH", data, 0x36)
            (self.shoff,) = struct.unpack_from("<Q", data, 0x28)
            self.shentsize, self.shnum = struct.unpack_from("<HH", data, 0x3A)
        else:
            (self.phoff,) = struct.unpack_from("<I", data, 0x1C)
            self.phentsize, self.phnum = struct.unpack_from("<HH", data, 0x2A)
            (self.shoff,) = struct.unpack_from("<I", data, 0x20)
            self.shentsize, self.shnum = struct.unpack_from("<HH", data, 0x2E)

    def loads(self):
        """返回 [(p_align, filesz, flags)]。"""
        out = []
        for i in range(self.phnum):
            off = self.phoff + i * self.phentsize
            if self.cls == 2:
                p_type, p_flags = struct.unpack_from("<II", self.data, off)
                (p_filesz,) = struct.unpack_from("<Q", self.data, off + 32)
                (p_align,) = struct.unpack_from("<Q", self.data, off + 48)
            else:
                p_type = struct.unpack_from("<I", self.data, off)[0]
                (p_offset, p_vaddr, p_paddr, p_filesz) = struct.unpack_from(
                    "<IIII", self.data, off + 4
                )
                (p_align,) = struct.unpack_from("<I", self.data, off + 28)
                p_flags = 0
            if p_type == PT_LOAD:
                out.append((p_align, p_filesz, p_flags))
        return out

    def loads_full(self):
        """返回 [(p_align, p_offset, p_vaddr, p_filesz, p_flags)]（顺序 58 的同余判据要用）。

        与 loads() 分开而不是改它：loads() 的返回值顺序被既有 16KB 判据（`for align,
        filesz, _flags in loads`）依赖，改签名会牵动那条判据——顺序 58 的要求是
        「既有判据一字不动」，所以这里新增一个方法，两条判据各取所需。
        """
        out = []
        for i in range(self.phnum):
            off = self.phoff + i * self.phentsize
            if self.cls == 2:
                p_type, p_flags = struct.unpack_from("<II", self.data, off)
                (p_offset, p_vaddr) = struct.unpack_from("<QQ", self.data, off + 8)
                (p_filesz,) = struct.unpack_from("<Q", self.data, off + 32)
                (p_align,) = struct.unpack_from("<Q", self.data, off + 48)
            else:
                (p_type, p_offset, p_vaddr, _p_paddr, p_filesz) = struct.unpack_from(
                    "<IIIII", self.data, off
                )
                (p_align,) = struct.unpack_from("<I", self.data, off + 28)
                p_flags = 0
            if p_type == PT_LOAD:
                out.append((p_align, p_offset, p_vaddr, p_filesz, p_flags))
        return out

    def sections(self):
        """返回 [(name_off, sh_type, sh_offset, sh_size, sh_link, sh_entsize)]。"""
        out = []
        for i in range(self.shnum):
            off = self.shoff + i * self.shentsize
            if self.cls == 2:
                sh_name, sh_type = struct.unpack_from("<II", self.data, off)
                (sh_offset,) = struct.unpack_from("<Q", self.data, off + 24)
                (sh_size,) = struct.unpack_from("<Q", self.data, off + 32)
                sh_link, _sh_info = struct.unpack_from("<II", self.data, off + 40)
                (sh_entsize,) = struct.unpack_from("<Q", self.data, off + 56)
            else:
                sh_name, sh_type = struct.unpack_from("<II", self.data, off)
                (sh_offset,) = struct.unpack_from("<I", self.data, off + 16)
                (sh_size,) = struct.unpack_from("<I", self.data, off + 20)
                sh_link, _sh_info = struct.unpack_from("<II", self.data, off + 24)
                (sh_entsize,) = struct.unpack_from("<I", self.data, off + 36)
            out.append((sh_name, sh_type, sh_offset, sh_size, sh_link, sh_entsize))
        return out

    def dynamic_symbols(self):
        """动态符号名集合（只取 SHT_DYNSYM）。"""
        secs = self.sections()
        names = set()
        strtab = None
        for sh_name, sh_type, sh_offset, sh_size, sh_link, sh_entsize in secs:
            if sh_type == SHT_DYNSYM:
                strtab = secs[sh_link]
                ent = sh_entsize or (24 if self.cls == 2 else 16)
                count = sh_size // ent
                for i in range(count):
                    off = sh_offset + i * ent
                    (st_name,) = struct.unpack_from("<I", self.data, off)
                    if st_name == 0:
                        continue
                    base = strtab[2] + st_name
                    end = self.data.index(b"\x00", base)
                    names.add(self.data[base:end].decode("utf-8", "replace"))
        return names

    def has_symtab(self):
        for _n, sh_type, _o, sh_size, _l, _e in self.sections():
            if sh_type == 2:  # SHT_SYMTAB
                return sh_size
        return 0


def native_methods(repo: Path):
    """从 CoreNative.kt 读所有 `external fun`，返回期望的 JNI 符号名。"""
    kt = repo / CORE_KT_REL
    if not kt.is_file():
        return None, kt
    text = kt.read_text(encoding="utf-8", errors="replace")
    funs = re.findall(r"external\s+fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(", text)
    return funs, kt


# ────────────────────── 611 段B：打包产物模式（--artifact） ──────────────────────
#
# 为什么长在这里、而不是另起一个 tools/check-so-align.py：
#   16KB 页对齐是**同一类病**。611 段A 先写了独立脚本，实测下来它唯一比本脚本多的一面是
#   「打包产物 lib/<abi>/*.so」—— 依赖自带的 .so（如 libandroidx.graphics.path.so）和
#   merge/strip 之后那份，本脚本按目录扫 jniLibs 时看不到。同一判据挂在两处，迟早只修一处
#   （tools/INDEX.md 的口径：不要再写第二份等价实现）。所以并进来，只留一份 ELF 解析。
#
# 三种"读不出"都必须红，不许静默跳过：不是 zip / zip 打不开 / 包内条目不是 ELF。

ABI_RE = re.compile(r"^lib/([^/]+)/(.+\.so)$")


def parse_argv(argv):
    """极简解析：位置参数=仓库根；--artifact 可重复；--selftest。"""
    repo, artifacts, selftest = None, [], False
    i = 1
    while i < len(argv):
        a = argv[i]
        if a == "--selftest":
            selftest = True
        elif a == "--artifact":
            i += 1
            if i >= len(argv):
                raise ValueError("--artifact 后面要跟 APK/AAR 路径（可用 * 通配）")
            artifacts.append(argv[i])
        elif a.startswith("--artifact="):
            artifacts.append(a.split("=", 1)[1])
        elif a.startswith("-"):
            raise ValueError(f"未知参数：{a}")
        elif repo is None:
            repo = a
        else:
            raise ValueError(f"多余的位置参数：{a}")
        i += 1
    return repo, artifacts, selftest


def _src_score(src):
    """来源信息的"信息量"打分：同一 key 被多份 blame 报告登记时，取信息最多的那条。

    实测（611）：`:app` 的 blame 写 `project ':native-core'`，而 `:native-core` 自己的
    blame 只写一个 `Project`（没有模块名）—— 按文件名 sorted 顺序读，"Project" 会覆盖掉
    有用值。所以不能"后读的赢"，要按信息量取。
    """
    s = src.strip()
    if ":" in s and not s.lower().startswith("project"):
        return 3  # 依赖坐标：androidx.graphics:graphics-path:1.0.1
    if s.lower().startswith("project"):
        return 2  # project ':native-core'
    if s.lower() == "project" or not s:
        return 1  # 只有「Project」，等于没写模块
    return 2


def blame_map(repo: Path):
    """包内 .so → 来源（AGP 自己写的 blame 报告）。

    为什么用它而不是去猜：AGP 的 mergeNativeLibs 会生成
    `<module>/build/intermediates/merged_native_libs_blame/<variant>/merge<V>NativeLibs/
     native-libs-blame-<variant>-report.txt`，内容就是 `lib/<abi>/<name>.so-><来源>`，
    来源形如 `project ':native-core'` 或 `androidx.graphics:graphics-path:1.0.1`。
    这正是"点名到来源依赖/模块"要的东西，而且是产物里现成的、可复核的。
    """
    out = {}
    try:
        reports = sorted(repo.glob("*/build/intermediates/merged_native_libs_blame/**/*.txt"))
    except OSError:
        return out
    for rep in reports:  # 多个 variant / 多个模块都读：同名条目取信息量最大的那条（见 _src_score）
        try:
            for line in rep.read_text(encoding="utf-8", errors="replace").splitlines():
                if "->" not in line:
                    continue
                key, src = line.split("->", 1)
                key, src = key.strip(), src.strip()
                if not key.startswith("lib/"):
                    continue
                if key not in out or _src_score(src) > _src_score(out[key]):
                    out[key] = src
        except OSError:
            continue
    return out


def artifact_entries(path: Path):
    """返回 [(包内路径, 字节)]；不是 zip / 打不开 ⇒ 抛 ValueError（调用方记「读不出」）。"""
    try:
        with zipfile.ZipFile(path) as z:
            return [
                (info.filename, z.read(info))
                for info in z.infolist()
                if ABI_RE.match(info.filename) and not info.is_dir()
            ]
    except zipfile.BadZipFile as exc:
        raise ValueError(f"不是 zip（APK/AAR）：{exc}") from exc
    except OSError as exc:
        raise ValueError(f"打不开：{exc}") from exc


def artifact_mode(repo: Path, patterns):
    """扫 APK/AAR 包内 lib/<abi>/*.so 的 16KB 对齐；返回退出码。"""
    blame = blame_map(repo)
    targets = []
    for pat in patterns:
        hits = sorted(glob.glob(pat))
        if not hits:
            print(f"::error title=入库 .so 门禁失败::--artifact 没匹配到任何文件：{pat}")
            print(f"[失败] 打包产物不存在（通配符 {pat}）—— assembleDebug 真的跑过吗？")
            return 1
        targets.extend(Path(h) for h in hits)

    bad = []
    checked = 0
    for art in targets:
        try:
            entries = artifact_entries(art)
        except ValueError as exc:
            bad.append((str(art), "-", "-", f"读不出（{exc}）"))
            continue
        if not entries:
            print(f"::warning title=入库 .so 门禁::{art} 内没有 lib/<abi>/*.so")
            info(f"{art}：包内没有 lib/<abi>/*.so（未判对齐；这不算通过，只是这个包没带 so）")
            continue
        info(f"{art}：包内 {len(entries)} 个 lib/<abi>/*.so")
        for name, data in entries:
            checked += 1
            src = blame.get(name, "未知（无 blame 报告或未登记）")
            try:
                elf = Elf.from_bytes(data, name)
                loads = elf.loads()
            except (ValueError, struct.error) as exc:
                bad.append((art.name, name, src, f"读不出（{exc}）"))
                continue
            if not loads:
                bad.append((art.name, name, src, "读不出（没有 PT_LOAD 段）"))
                continue
            aligns = [(a, fz, fl) for a, fz, fl in loads]
            seg_txt = ",".join(f"0x{a:x}" for a, _f, _l in aligns)
            worst = min(a for a, _f, _l in aligns)
            if worst < PAGE_16K:
                bad.append(
                    (art.name, name, src, f"p_align={seg_txt}（最小 0x{worst:x}={worst}）< 0x4000")
                )
            else:
                info(f"  {name} ← {src}：{len(aligns)} 个 PT_LOAD，p_align = {seg_txt}")

    for line in infos:
        print(f"  · {line}")
    if bad:
        print("")
        for art_name, entry, src, why in bad:
            print(
                f"::error title=入库 .so 门禁失败::{art_name} 内 {entry}"
                f"（来源 {src}）—— {why}：16KB 页设备上 loadLibrary 会失败并静默退回 Java"
            )
        print(f"\n[失败] {len(bad)} 项（包内 .so 共查了 {checked} 个）")
        return 1
    print(f"\n[通过] 包内 {checked} 个 .so 的每个 PT_LOAD 段 p_align 均 >= 0x4000")
    return 0


def _synth_elf(ei_class, align, *, no_phdr=False):
    """自证用：造一个最小 ELF（只有程序头，够解析 p_align）。"""
    ehsize, phentsize = (64, 56) if ei_class == 2 else (52, 32)
    body = bytearray(ehsize + phentsize)
    body[0:4] = b"\x7fELF"
    body[4], body[5], body[6] = ei_class, 1, 1
    if ei_class == 2:
        struct.pack_into("<HHI", body, 0x10, 3, 183, 1)
        struct.pack_into("<Q", body, 0x20, 0 if no_phdr else ehsize)
        struct.pack_into("<HHHHHH", body, 0x34, ehsize, phentsize, 0 if no_phdr else 1, 0, 0, 0)
        struct.pack_into("<II", body, ehsize, 1, 5)
        struct.pack_into("<QQQQ", body, ehsize + 8, 0, 0, 0x100, 0x100)
        struct.pack_into("<Q", body, ehsize + 48, align)
    else:
        struct.pack_into("<HHI", body, 0x10, 3, 40, 1)
        struct.pack_into("<I", body, 0x1C, 0 if no_phdr else ehsize)
        struct.pack_into("<HHHHHH", body, 0x28, ehsize, phentsize, 0 if no_phdr else 1, 0, 0, 0)
        struct.pack_into("<IIIIII", body, ehsize, 1, 0, 0, 0, 0x100, 0x100)
        struct.pack_into("<II", body, ehsize + 24, 5, align)
    return bytes(body)


def _make_apk(path: Path, entries):
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as z:
        for name, data in entries:
            z.writestr(name, data)


def selftest():
    """--selftest：只自证 611 新加的「包内条目」这条判据（仓库侧既有判据由真跑覆盖）。"""
    stats = {"pass": 0, "fail": 0}
    fails = []

    def check(name, ok, detail=""):
        if ok:
            stats["pass"] += 1
            print(f"  ✓ {name}")
        else:
            stats["fail"] += 1
            fails.append(name)
            print(f"  ✗ {name}  {detail}")

    def run(repo, *patterns):
        buf = io.StringIO()
        blockers.clear()  # 每一轮都从空账开始，免得上一条的 info 串到下一条断言里
        infos.clear()
        with contextlib.redirect_stdout(buf):
            code = artifact_mode(Path(repo), list(patterns))
        return code, buf.getvalue()

    with tempfile.TemporaryDirectory(prefix="so-gate-selftest-") as td:
        tmp = Path(td)
        (tmp / "app").mkdir()

        print("[1/7] 正例：包内 16KB（64 位 / 32 位）⇒ 绿")
        ok_apk = tmp / "app/build/outputs/apk/debug/ok.apk"
        _make_apk(
            ok_apk,
            [
                ("lib/arm64-v8a/libok.so", _synth_elf(2, 0x4000)),
                ("lib/armeabi-v7a/libok32.so", _synth_elf(1, 0x4000)),
            ],
        )
        code, out = run(tmp, str(ok_apk))
        check("16KB 判绿", code == 0, f"code={code}\n{out}")
        check("包内 2 个 so 都数到", "包内 2 个 lib/<abi>/*.so" in out, out)

        print("[2/7] 负例：包内塞 4KB（64 位）⇒ 红并点名包内路径")
        bad_apk = tmp / "app/build/outputs/apk/debug/bad.apk"
        _make_apk(
            bad_apk,
            [
                ("lib/arm64-v8a/libok.so", _synth_elf(2, 0x4000)),
                ("lib/arm64-v8a/lib4k.so", _synth_elf(2, 0x1000)),
            ],
        )
        code, out = run(tmp, str(bad_apk))
        check("4KB 退出码 1", code == 1, f"code={code}")
        check("点名包内路径", "lib/arm64-v8a/lib4k.so" in out, out)
        check("给出实测 p_align", "0x1000" in out and "4096" in out, out)

        print("[3/7] 负例：包内 4KB（32 位）⇒ 红（防只按 64 位布局读）")
        bad32 = tmp / "app/build/outputs/apk/debug/bad32.apk"
        _make_apk(bad32, [("lib/armeabi-v7a/lib4k32.so", _synth_elf(1, 0x1000))])
        code, out = run(tmp, str(bad32))
        check("32 位 4KB 退出码 1", code == 1, f"code={code}\n{out}")
        check("32 位 4KB 点名路径", "lib/armeabi-v7a/lib4k32.so" in out, out)

        print("[4/7] 负例：不是 zip / 条目不是 ELF ⇒ 读不出（不许静默跳过）")
        txt_as_apk = tmp / "app/build/outputs/apk/debug/fake.apk"
        txt_as_apk.write_bytes(b"this is not a zip\n")
        code, out = run(tmp, str(txt_as_apk))
        check("非 zip 退出码 1", code == 1, f"code={code}")
        check("非 zip 报读不出", "读不出" in out and "不是 zip" in out, out)

        weird = tmp / "app/build/outputs/apk/debug/weird.apk"
        _make_apk(weird, [("lib/arm64-v8a/libjunk.so", b"not an elf file at all\n")])
        code, out = run(tmp, str(weird))
        check("条目非 ELF 退出码 1", code == 1, f"code={code}")
        check("条目非 ELF 点名", "lib/arm64-v8a/libjunk.so" in out and "不是 ELF" in out, out)

        print("[5/7] 负例：通配符一个都没匹配到 ⇒ 红（不是静默通过）")
        code, out = run(tmp, str(tmp / "app/build/outputs/apk/debug/nope-*.apk"))
        check("空通配退出码 1", code == 1, f"code={code}")
        check("空通配说清原因", "没匹配到任何文件" in out, out)

        print("[6/7] 边界：包内没有 .so ⇒ 只告警、不冒充通过")
        empty_apk = tmp / "app/build/outputs/apk/debug/empty.apk"
        _make_apk(empty_apk, [("classes.dex", b"dex\n")])
        code, out = run(tmp, str(empty_apk))
        check("空包不判红", code == 0, f"code={code}")
        check("空包打 ::warning", "::warning" in out, out)

        print("[7/7] 来源优先级：有用的 blame 不许被只有「Project」的那条覆盖")
        blame_repo = tmp / "blame"
        app_blame = (
            blame_repo
            / "app/build/intermediates/merged_native_libs_blame/debug/mergeDebugNativeLibs"
            / "native-libs-blame-debug-report.txt"
        )
        nc_blame = (
            blame_repo
            / "native-core/build/intermediates/merged_native_libs_blame/debug/mergeDebugNativeLibs"
            / "native-libs-blame-debug-report.txt"
        )
        app_blame.parent.mkdir(parents=True, exist_ok=True)
        nc_blame.parent.mkdir(parents=True, exist_ok=True)
        app_blame.write_text("lib/arm64-v8a/libcore.so->project ':native-core'\n", encoding="utf-8")
        nc_blame.write_text("lib/arm64-v8a/libcore.so->Project\n", encoding="utf-8")
        blame_apk = blame_repo / "app/build/outputs/apk/debug/blame.apk"
        _make_apk(blame_apk, [("lib/arm64-v8a/libcore.so", _synth_elf(2, 0x4000))])
        code, out = run(blame_repo, str(blame_apk))
        check("来源取信息量最大的那条", "project ':native-core'" in out, out)
        check("来源里不出现无模块名的 Project", "← Project：" not in out, out)

    print("")
    print(f"[自证] pass={stats['pass']}  fail={stats['fail']}")
    if fails:
        for f in fails:
            print(f"  - {f}")
        return 1
    print("[自证通过] 包内 4KB 判红并点名 / 16KB 判绿 / 读不出点名 / 空通配不当绿 / 空包只告警")
    return 0


def main(argv):
    try:
        repo_arg, artifacts, want_selftest = parse_argv(argv)
    except ValueError as exc:
        print(f"用法: python tools/check-native-so.py [仓库根] [--artifact <apk|aar|通配>]... [--selftest]")
        print(f"[错误] {exc}")
        return 2

    if want_selftest:
        return selftest()

    repo = Path(repo_arg).resolve() if repo_arg else Path(__file__).resolve().parents[1]
    if not (repo / "app").is_dir():
        print(f"[错误] 找不到仓库根：{repo}（app/ 不存在）")
        return 2

    if artifacts:
        return artifact_mode(repo, artifacts)

    # 第二百四十一批：jniLibs 随终端内核搬进 :terminal-core（proot 四件套 + zhengdao_core
    # + zstd-jni + 自编 libtermux.so）。两处都扫：搬家前后都成立，不写死单一模块。
    libs = sorted(p for d in SO_DIRS for p in (repo / d).glob("*/*.so"))
    if not libs:
        print(f"[错误] {' / '.join(SO_DIRS)} 下没有任何 .so —— 门禁失去意义，视为失败")
        return 1

    # ── 顺序 38（第㉗刀）：PROOT_PINNED 四件套**必须在场** ──
    #
    # 为什么必须单独判：下面那个 `for so in libs` 循环是 **glob 驱动**的 —— 16KB 判据与
    # sha256 完整性基线都只在「文件存在」时才执行。所以删掉（或改名、挪出 jniLibs）四件套
    # 里任一个，那一段根本不跑：既不会因为"缺件"红，也不会因为"哈希对不上"红 —— CI 全绿，
    # 而 App 在真机上 execve/mmap proot 时才炸（或静默退回 Java 路径）。
    #
    # 为什么只补这四个、不补 `libzhengdao_core.so`：**它的缺席已被同源门禁拦住** ——
    # `tools/check-so-provenance.py` 的基线里记着它那条 sha256，文件一没了 diff 就报
    # `[so] 删除: …libzhengdao_core.so` 并返回 1（第㉗刀实测：EXIT=1）。四件套则**没有任何
    # 门禁管**（同源门禁只 glob CORE_SO）—— 这正是本条的缺口。
    #
    # 只判「名字出现在扫描结果里」，不改也不代替 1 里的 16KB 判据与 sha256 基线（那两条一行未动）。
    present_names = {so.name for so in libs}
    if not PROOT_PINNED:
        info("主仓已无 proot 四件套（250 随终端搬走），跳过在场判据")
    missing_pinned = [name for name in PROOT_PINNED if name not in present_names]
    if missing_pinned:
        for name in missing_pinned:
            blocker(
                f"PROOT_PINNED 四件套缺件：{name} 不在 {' / '.join(SO_DIRS)}/**/*.so 的扫描结果里 —— "
                "16KB 判据与 sha256 完整性基线都是 glob 驱动的（文件不在就不跑）⇒ 缺件会静默绕过"
                "两条判据（顺序 38）。修法：把该 .so 放回 jniLibs/<abi>/（工序见 rust/README.md "
                "与 PROVENANCE.md），或同步三处登记（ProotBinaries.kt / PROVENANCE.md / 本脚本）"
            )
    else:
        info(f"PROOT_PINNED 四件套齐备（{', '.join(sorted(PROOT_PINNED))}）")

    funs, kt = native_methods(repo)
    if funs is None:
        blocker(f"找不到 {CORE_KT_REL}（JNI 符号清单的真相来源）")
        expected = set()
    else:
        expected = {JNI_CLASS + f for f in funs}
        info(f"CoreNative.kt 声明 {len(funs)} 个 external fun：{', '.join(funs)}")

    for so in libs:
        rel = so.relative_to(repo).as_posix()
        try:
            elf = Elf(so)
        except Exception as exc:  # noqa: BLE001 - 门禁脚本，任何解析失败都要报出来
            blocker(f"{rel}: 无法解析 ELF（{exc}）")
            continue

        loads = elf.loads()
        if not loads:
            blocker(f"{rel}: 没有 PT_LOAD 段？")

        is_proot_pinned = so.name in PROOT_PINNED
        for align, filesz, _flags in loads:
            if align < PAGE_16K:
                if is_proot_pinned:
                    # E-137 例外（登记在案）：proot 四件套按 4KB 页构建，是被 execve/mmap
                    # 的独立程序而非 loadLibrary 的库；16KB 页设备风险见 docs/ERRATA.md
                    # E-137。交换条件 = 下面的 sha256 完整性基线（换版本必红）。
                    info(
                        f"{rel}: PT_LOAD p_align=0x{align:x}（4KB）—— PROOT_PINNED 登记例外"
                        "（exec 型程序，非 loadLibrary；16KB 页设备风险见 E-137）"
                    )
                    continue
                blocker(
                    f"{rel}: 有 PT_LOAD 段 p_align=0x{align:x}（{align}）< 0x4000 —— "
                    "16KB 页设备上 loadLibrary 会失败并静默退回 Java"
                )
        aligs = ",".join(f"0x{a:x}" for a, _s, _f in loads)
        info(f"{rel}: {len(loads)} 个 PT_LOAD，p_align = {aligs}；体积 {so.stat().st_size} B")

        # ── 顺序 58（第㉗②-A）：PT_LOAD 的 16KB **偏移同余**判据 ──
        # 为什么单独判：上面那条只判「p_align >= 0x4000」，它说的是「段声明了自己
        # 按 16KB 对齐」，**不保证**内核按 16KB 页装载时段的文件偏移与虚拟地址落在
        # 同一页内边界。同余 (p_offset ≡ p_vaddr mod 16KB) 被破坏的 .so 在 4KB 页
        # 设备上照样能跑、在 16KB 页设备上 loadLibrary 失败 —— 又是一次「静默降级」
        # （与 E-128 同类：门禁全绿、真机废）。本块是**叠加**判据，上面那条既有判据
        # 一行未动；PROOT_PINNED 四件套是被 execve 的独立程序（E-137 登记例外），
        # 不参与本判据。
        if is_proot_pinned:
            info(
                f"{rel}: 跳过 PT_LOAD 同余判据（PROOT_PINNED 登记例外，exec 型程序；"
                "见 E-137）"
            )
        else:
            segs = elf.loads_full()
            if not segs:
                blocker(f"{rel}: loads_full() 读不到任何 PT_LOAD 段（解析异常）")
            seg_bad = 0
            for idx, (seg_align, seg_off, seg_vaddr, _fsz, _fl) in enumerate(segs):
                if seg_align % PAGE_16K != 0:
                    seg_bad += 1
                    blocker(
                        f"{rel}: PT_LOAD[{idx}] p_align=0x{seg_align:x}（{seg_align}）不是 "
                        "0x4000 的整数倍 —— 16KB 页设备按 16KB 装载时该段无法对齐"
                    )
                if (seg_off - seg_vaddr) % PAGE_16K != 0:
                    seg_bad += 1
                    blocker(
                        f"{rel}: PT_LOAD[{idx}] 偏移同余被破坏 —— p_offset=0x{seg_off:x}"
                        f"（{seg_off}）、p_vaddr=0x{seg_vaddr:x}（{seg_vaddr}），"
                        f"(p_offset - p_vaddr) mod 0x4000 = "
                        f"{(seg_off - seg_vaddr) % PAGE_16K} ≠ 0 —— 16KB 页设备上 "
                        "loadLibrary 会失败并静默退回 Java"
                    )
            if seg_bad == 0:
                info(
                    f"{rel}: PT_LOAD 同余判据通过（{len(segs)} 段：每段 p_align 是 0x4000 "
                    "的整数倍，且 p_offset ≡ p_vaddr (mod 0x4000)）"
                )

        if is_proot_pinned:
            # 完整性基线（第⑳刀）：四个 proot .so 的 sha256 必须等于登记值——
            # 换 Termux 版本/镜像投毒/手滑拷错文件，这里立刻红。
            h = hashlib.sha256()
            with open(so, "rb") as f:
                for chunk in iter(lambda: f.read(256 * 1024), b""):
                    h.update(chunk)
            actual = h.hexdigest()
            if actual != PROOT_PINNED[so.name]:
                blocker(
                    f"{rel}: sha256 不匹配登记值 —— 实际 {actual}，"
                    f"应为 {PROOT_PINNED[so.name]}（换版本需同步 ProotBinaries.kt / "
                    "PROVENANCE.md / 本脚本三处）"
                )
            else:
                info(f"{rel}: sha256 与登记值一致（E-137 完整性基线）")

        symtab = elf.has_symtab()
        if symtab:
            info(f"{rel}: 带 .symtab（{symtab} B）—— 只影响入库体积，不影响 APK（E-045）")

        if so.name == CORE_SO and expected:
            syms = elf.dynamic_symbols()
            missing = sorted(expected - syms)
            if missing:
                blocker(
                    f"{rel}: 缺少 JNI 入口符号：{', '.join(missing)}"
                    "（strip 过头或方法改名 -> 运行时 UnsatisfiedLinkError）"
                )
            else:
                info(f"{rel}: {len(expected)} 个 JNI 入口符号全部存在")

    for line in infos:
        print(f"  · {line}")
    if blockers:
        print("")
        for line in blockers:
            print(f"::error title=入库 .so 门禁失败::{line}")
        print(f"\n[失败] {len(blockers)} 项 —— 见上（本机重建命令：cd rust && cargo build --release "
              f"--target aarch64-linux-android -p zhengdao_core，再 llvm-strip --strip-unneeded 后拷进 jniLibs）")
        return 1
    print("\n[通过] 入库 .so 的 16KB 对齐与 JNI 入口符号均正常")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
