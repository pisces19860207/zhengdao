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

退出码
    0 = 全部通过 ；1 = 有 BLOCKER ；2 = 用法/环境错误（找不到仓库、没有 .so）

只依赖标准库（ELF 头自己解析）。CI 里由 .github/workflows/ci.yml 调用，失败即红。
"""

import hashlib
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

# 第⑳刀（E-137）：proot 四件套的 4KB 对齐例外 + 完整性基线。
# 这四个 .so 是 Termux 官方发行二进制（原样聚合分发，GPL 合规口径见 PROVENANCE.md），
# 由 proot 以 PROOT_LOADER 方式装载/被宿主 execve，**不走 bionic loadLibrary**；
# 当前官方构建按 4KB 页对齐 ⇒ 在 16KB 页设备上不可用（记录为产品化待办，与交接单
# §七同口径：我们手上没有 16KB 页设备，证不了；换 Termux 16KB 构建前先钉死哈希）。
# 哈希值与 terminal/ProotBinaries.kt、PROVENANCE.md 三处同源一致；任何一处换版本
# 都必须三处同步 + 重新登记。
PROOT_PINNED = {
    "libproot.so": "1545b85b312505db6eb6908ff8b2ded0a77a3bd689c50ae85aa7c1d8445dd717",
    "libproot-loader.so": "cbdef0e652c2b78af25d867e1719fdebbb0915e25aae2dd35b3b5c1835f6b551",
    "libtalloc.so": "742b438c4d09e276985a61d44164c9de207b6d8cc268f3018cb3001961bcb309",
    "libandroid-shmem.so": "84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731",
}

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
    missing_pinned = [name for name in PROOT_PINNED if name not in present_names]
    if missing_pinned:
        for name in missing_pinned:
            blocker(
                f"PROOT_PINNED 四件套缺件：{name} 不在 app/src/main/jniLibs/**/*.so 的扫描结果里 —— "
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
