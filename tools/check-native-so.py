#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-native-so.py —— 入库 .so 的门禁：16KB 页对齐 + JNI 入口符号。

为什么要它
    app/src/main/jniLibs/arm64-v8a/libzhengdao_core.so 是**人工构建入库**的
    （ci.yml / build.yml 里没有任何步骤构建它，见 docs/ERRATA.md E-045 教训 3）。
    而 16KB 页对齐是"静默降级"型故障：漏了 -Wl,-z,max-page-size=16384 不会崩溃，
    而是 16KB 页设备上 loadLibrary 失败 → CoreNative.isRustAvailable()=false
    → 整条解压路径悄悄退回 Java，谁也不会红。
    同理，JNI 入口符号被 strip / 被改名（proguard 少 keep、R8 改 native 名），
    也是运行时才炸。这两种"人工工序 + 静默失败"的组合，正适合做成门禁。

检查项
    1. jniLibs/<abi>/*.so 的每个 PT_LOAD 段 p_align >= 0x4000（16KB 页设备可加载）
    2. libzhengdao_core.so 必须导出 CoreNative.kt 里每个 `external fun` 的 JNI 符号
       （符号名现读 CoreNative.kt 推导，将来加 native 方法会自动纳入门禁）
    3. 仅供信息：.symtab 是否存在及大小（影响入库体积，不影响 APK —— 见 E-045）

用法
    python tools/check-native-so.py [仓库根]     # 默认取本脚本上一级目录

退出码
    0 = 全部通过 ；1 = 有 BLOCKER ；2 = 用法/环境错误（找不到仓库、没有 .so）

只依赖标准库（ELF 头自己解析）。CI 里由 .github/workflows/ci.yml 调用，失败即红。
"""

import re
import struct
import sys
from pathlib import Path

try:  # Windows 控制台默认 GBK：个别字符编不出来时降级为替换，而不是让门禁自己崩
    sys.stdout.reconfigure(errors="replace")
except Exception:  # noqa: BLE001 - reconfigure 是尽力而为
    pass

PAGE_16K = 0x4000
PT_LOAD = 1
SHT_DYNSYM = 11

CORE_KT_REL = "app/src/main/java/com/example/zhengdao/rust/CoreNative.kt"
JNI_CLASS = "Java_com_example_zhengdao_rust_CoreNative_"
CORE_SO = "libzhengdao_core.so"

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
        data = path.read_bytes()
        if data[:4] != b"\x7fELF":
            raise ValueError("不是 ELF 文件")
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


def main(argv):
    if len(argv) > 2:
        print("用法: python tools/check-native-so.py [仓库根]")
        return 2
    repo = Path(argv[1]).resolve() if len(argv) == 2 else Path(__file__).resolve().parents[1]
    if not (repo / "app").is_dir():
        print(f"[错误] 找不到仓库根：{repo}（app/ 不存在）")
        return 2

    libs = sorted((repo / "app/src/main/jniLibs").glob("*/*.so"))
    if not libs:
        print("[错误] app/src/main/jniLibs 下没有任何 .so —— 门禁失去意义，视为失败")
        return 1

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
        for align, filesz, _flags in loads:
            if align < PAGE_16K:
                blocker(
                    f"{rel}: 有 PT_LOAD 段 p_align=0x{align:x}（{align}）< 0x4000 —— "
                    "16KB 页设备上 loadLibrary 会失败并静默退回 Java"
                )
        aligs = ",".join(f"0x{a:x}" for a, _s, _f in loads)
        info(f"{rel}: {len(loads)} 个 PT_LOAD，p_align = {aligs}；体积 {so.stat().st_size} B")

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
