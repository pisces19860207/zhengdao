#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
rootfs-manifest.py —— 证道（zhengdao）Debian rootfs 的「清单 / 差分包 / 索引」工具。

用途
====
App 每次环境更新都重下 ~217MB 的 debian-13.7-base-arm64.tar.zst 太贵。CI 侧用本工具：

  1) manifest  遍历整棵 rootfs 树，写出「每个文件 sha256」的清单（含目录 / 符号链接）；
  2) env       从清单正文重算环境标识 env（16 hex），验证清单自洽；
  3) patch     对比上一版清单与本版清单，只打包「变更成员」，删除项写进 deletes 清单；
  4) index     生成 App 唯一入口 rootfs-index.json（完整包 / 清单 / 补丁的 URL + sha256 + size）。

App 侧拿「补丁 + 本地已有文件」在原地拼出新树（契约 §6），端侧不需要下载清单。

格式契约见 docs/milestones/证道-环境包增量下发协议.md（**权威**，禁止自行改格式）。

实现约束
========
* 只用 Python 标准库；压缩/解压通过 subprocess 调用 zstd 可执行文件（**不 import zstandard**）。
* 清单生成必须确定：与 mtime / 属主无关，同一棵树跑两次逐字节相同、env 相同。
* 失败时不留半成品文件：一律先写临时文件，成功后再 os.replace 原子替换。

退出码
======
  0  成功
  2  用法 / 输入 / 格式错误（含清单里出现非法字符、包与清单不符等）
  1  未预期异常（附带 traceback）
  130 用户中断
"""

import argparse
import hashlib
import io
import json
import os
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
import traceback

# ---------------------------------------------------------------- 常量

#: 清单头第一行（契约 §2）
MANIFEST_HEADER_V1 = "# zhengdao-rootfs-manifest v1"
#: 默认发行版标识
DEFAULT_DISTRO = "debian-13.7"
#: 补丁包内固定第一个成员（契约 §3）
PATCH_INFO_MEMBER = "./.zhengdao-patch-info"
#: 补丁包内固定第一个成员的 basename（App 侧跳过它）
PATCH_INFO_BASENAME = ".zhengdao-patch-info"
#: 补丁包 info 段第一行
PATCH_INFO_V1 = "zhengdao-patch v1"
#: 滚动版 Release 里清单的资产名（契约 §7）
DEFAULT_MANIFEST_ASSET = "rootfs-manifest.txt"
#: zstd 压缩级别（与 rootfs/build-rootfs.sh 的 -19 保持一致）
ZSTD_LEVEL = "19"
#: 读取文件 / 计算 sha256 的分块大小
CHUNK = 1 << 20

#: 正文行里不允许出现的字符：制表符之外会破坏「一行一条 / 制表符分隔字段」的空白。
#: 注意：普通空格（U+0020）在制表符分隔的格式里不会造成错位，故不禁用（见脚本末尾的自主决定说明）。
_FORBIDDEN_PATH_CHARS = ("\t", "\n", "\r", "\v", "\f")

_RELEASE_URL_TMPL = "https://github.com/{repo}/releases/download/latest/{asset}"


class ToolError(Exception):
    """用法 / 输入 / 格式错误 —— 打印清晰信息并以退出码 2 结束。"""


def die(msg):
    raise ToolError(msg)


# ---------------------------------------------------------------- 基础工具


def fsencode(s):
    """把文件系统路径编码成字节串（用于按「path 的字节序」排序 / 写入清单）。"""
    return s.encode("utf-8", "surrogateescape")


def fsdecode(b):
    return b.decode("utf-8", "surrogateescape")


def read_bytes(path):
    try:
        with open(path, "rb") as f:
            return f.read()
    except OSError as e:
        die("读文件失败 %s: %s" % (path, e))


def hash_and_size(path):
    """返回 (sha256 hex, 字节数)。"""
    h = hashlib.sha256()
    n = 0
    try:
        with open(path, "rb") as f:
            while True:
                b = f.read(CHUNK)
                if not b:
                    break
                h.update(b)
                n += len(b)
    except OSError as e:
        die("读文件失败 %s: %s" % (path, e))
    return h.hexdigest(), n


def atomic_write_bytes(dest, data):
    """先写同目录临时文件，再 os.replace 原子替换（失败不留半成品）。"""
    dest = os.path.abspath(dest)
    d = os.path.dirname(dest) or "."
    try:
        os.makedirs(d, exist_ok=True)
    except OSError as e:
        die("创建输出目录失败 %s: %s" % (d, e))
    fd, tmp = tempfile.mkstemp(prefix=".zd-manifest-", suffix=".tmp", dir=d)
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(data)
        os.replace(tmp, dest)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def rm_quiet(path):
    try:
        os.unlink(path)
    except OSError:
        pass


def check_text_field(s, what):
    """校验一个要写进清单行的字符串：不能含会被当成空白分隔的字符。"""
    for ch in _FORBIDDEN_PATH_CHARS:
        if ch in s:
            die(
                "%s 含非法空白字符 %s（U+%04X）：%r —— 契约 §2 要求正文行内不得出现"
                "制表符以外的空白，出现即会错位，故直接报错退出"
                % (what, "\\t" if ch == "\t" else "\\n" if ch == "\n" else repr(ch), ord(ch), s)
            )


def utc_now_iso():
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


# ---------------------------------------------------------------- env 算法（契约 §1）


def env_from_body_lines(body_lines):
    """env = sha256( 正文行以 LF 连接、末尾 LF )[0:16]。"""
    h = hashlib.sha256()
    for ln in body_lines:
        h.update(ln)
        h.update(b"\n")
    return h.hexdigest()[:16]


def manifest_body_lines(data):
    """
    从清单文件内容里取出「正文行」（bytes，不含行尾 LF）。

    规则（契约 §1/§2）：去掉所有以 '#' 开头的行（注释头）。为容忍 CRLF 拷贝，行尾 \\r 会被去掉；
    空行不含信息（本工具写出的清单不会有空行），一并忽略。
    """
    lines = []
    for raw in data.split(b"\n"):
        if raw.endswith(b"\r"):
            raw = raw[:-1]
        if not raw:
            continue
        if raw.startswith(b"#"):
            continue
        lines.append(raw)
    return lines


def env_from_manifest_bytes(data):
    return env_from_body_lines(manifest_body_lines(data))


# ---------------------------------------------------------------- 清单：生成


def scan_tree(root):
    """
    遍历 root，返回 (entries, skipped)。

    entries: [(path, type, mode, size, digest)]，path 以 './' 开头、按**字节序**升序；
             type ∈ {'f','d','l'}；f 的 digest=内容 sha256，l 的 digest=链接目标，d 的 digest='-'。
    skipped: 被跳过的特殊文件（FIFO / socket / 字符设备 / 块设备 —— App 侧同样跳过）。
    """
    if not os.path.isdir(root):
        die("--root 不是目录或不存在: %s" % root)
    root = os.path.abspath(root)

    out = []
    skipped = []
    spacey = 0
    stack = [""]  # 相对路径（不带 './'），'' 表示 root 自身
    while stack:
        rel = stack.pop()
        absdir = os.path.join(root, rel.replace("/", os.sep)) if rel else root
        try:
            with os.scandir(absdir) as it:
                children = list(it)
        except OSError as e:
            die("遍历目录失败 %s: %s" % (absdir, e))
        for de in children:
            name = de.name
            child_rel = name if not rel else rel + "/" + name
            child_abs = os.path.join(absdir, name)
            path = "./" + child_rel
            check_text_field(path, "path")
            if " " in path:
                spacey += 1
            try:
                st = de.stat(follow_symlinks=False)
            except OSError as e:
                die("stat 失败 %s: %s" % (child_abs, e))
            m = st.st_mode
            if stat.S_ISLNK(m):
                try:
                    target = os.readlink(child_abs)
                except OSError as e:
                    die("readlink 失败 %s: %s" % (child_abs, e))
                check_text_field(target, "符号链接目标（%s）" % path)
                # 契约 §2：软链 mode 固定 0777（tar 惯例）、size 0、digest = 链接目标
                out.append((path, "l", 0o777, 0, target))
            elif stat.S_ISDIR(m):
                out.append((path, "d", stat.S_IMODE(m), 0, "-"))
                stack.append(child_rel)
            elif stat.S_ISREG(m):
                digest, size = hash_and_size(child_abs)
                out.append((path, "f", stat.S_IMODE(m), size, digest))
            else:
                # FIFO / socket / 字符设备 / 块设备：清单与补丁都不表达它们（App 侧同样跳过）
                skipped.append(path)
    out.sort(key=lambda e: fsencode(e[0]))
    if spacey:
        print(
            "警告: %d 条 path 含空格（制表符分隔的字段不会因此错位，故仅告警不报错）" % spacey,
            file=sys.stderr,
        )
    return out, skipped


def format_manifest_line(entry):
    path, typ, mode, size, digest = entry
    return "%s\t%04o\t%d\t%s\t%s\n" % (typ, mode, size, digest, path)


def render_manifest(entries, distro):
    body = "".join(format_manifest_line(e) for e in entries)
    # 只按 '\n' 切（不用 splitlines()，它还会把 \v \f \x1c 等当成行界）
    env = env_from_body_lines([fsencode(l) for l in body.split("\n")[:-1]])
    head = "%s\n# env=%s\n# distro=%s\n" % (MANIFEST_HEADER_V1, env, distro)
    return head.encode("utf-8") + body.encode("utf-8", "surrogateescape"), env


# ---------------------------------------------------------------- 清单：解析


class ManifestEntry(object):
    __slots__ = ("path", "typ", "mode", "size", "digest")

    def __init__(self, path, typ, mode, size, digest):
        self.path = path
        self.typ = typ
        self.mode = mode
        self.size = size
        self.digest = digest

    def same_as(self, other):
        """契约 §3 的「是否需要进补丁」判定；返回 True 表示需要。"""
        if self.typ != other.typ:
            return True
        if self.mode != other.mode:
            return True
        if self.typ == "f" and self.digest != other.digest:
            return True
        if self.typ == "l" and self.digest != other.digest:
            return True
        return False


def parse_manifest(path, what="清单"):
    """解析清单文件，返回 (env, OrderedDict-like dict[path] -> ManifestEntry)。"""
    data = read_bytes(path)
    env = env_from_manifest_bytes(data)
    entries = {}
    for lineno, raw in enumerate(manifest_body_lines(data), 1):
        try:
            text = fsdecode(raw)
        except Exception as e:
            die("%s %s 第 %d 行不是合法 UTF-8: %s" % (what, path, lineno, e))
        parts = text.split("\t")
        if len(parts) != 5:
            die(
                "%s %s 第 %d 行字段数=%d（应为 5：type/mode/size/digest/path）: %r"
                % (what, path, lineno, len(parts), text[:160])
            )
        typ, mode_s, size_s, digest, p = parts
        if typ not in ("f", "d", "l"):
            die("%s %s 第 %d 行 type 非法: %r" % (what, path, lineno, typ))
        try:
            mode = int(mode_s, 8)
        except ValueError:
            die("%s %s 第 %d 行 mode 不是八进制: %r" % (what, path, lineno, mode_s))
        try:
            size = int(size_s, 10)
        except ValueError:
            die("%s %s 第 %d 行 size 不是十进制整数: %r" % (what, path, lineno, size_s))
        if size < 0:
            die("%s %s 第 %d 行 size 为负: %r" % (what, path, lineno, size_s))
        if not p.startswith("./"):
            die("%s %s 第 %d 行 path 未以 './' 开头: %r" % (what, path, lineno, p))
        if p in entries:
            die("%s %s 第 %d 行 path 重复: %r" % (what, path, lineno, p))
        entries[p] = ManifestEntry(p, typ, mode, size, digest)
    return env, entries


# ---------------------------------------------------------------- zstd 调用


def run_zstd(zstd, args, allow_fail=False):
    cmd = [zstd] + list(args)
    try:
        p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    except FileNotFoundError:
        die("找不到 zstd 可执行文件: %s（用 --zstd <路径> 指定，或先安装 zstd）" % zstd)
    except OSError as e:
        die("调用 zstd 失败 %s: %s" % (zstd, e))
    if p.returncode != 0 and not allow_fail:
        err = p.stderr.decode("utf-8", "replace").strip()
        die("zstd 返回 %d：%s\n  cmd: %s" % (p.returncode, err, " ".join(cmd)))
    return p


def compress_zstd(zstd, src, dst):
    """zstd -19 -q -f -k -o dst src（-k 保住源文件，绝不 import zstandard）。"""
    run_zstd(zstd, ["-%s" % ZSTD_LEVEL, "-q", "-f", "-k", "-o", dst, src])
    if not os.path.exists(dst):
        die("zstd 未生成输出文件: %s" % dst)


def decompress_zstd(zstd, src, dst):
    run_zstd(zstd, ["-d", "-q", "-f", "-k", "-o", dst, src])
    if not os.path.exists(dst):
        die("zstd 未生成解压文件: %s" % dst)


# ---------------------------------------------------------------- 补丁 info 段


def build_patch_info(base_env, new_env, deletes):
    """
    契约 §3 的 info 段：
        zhengdao-patch v1
        base=<baseEnv>
        new=<newEnv>
        deletes=<n>
        <相对 path 1>      ← 无 ./ 前缀
    """
    lines = [PATCH_INFO_V1, "base=%s" % base_env, "new=%s" % new_env, "deletes=%d" % len(deletes)]
    for p in deletes:
        lines.append(p[2:])
    return ("\n".join(lines) + "\n").encode("utf-8", "surrogateescape")


def parse_patch_info(data):
    text = data.decode("utf-8", "surrogateescape")
    lines = text.split("\n")
    while lines and lines[-1] == "":
        lines.pop()
    if not lines or lines[0] != PATCH_INFO_V1:
        die("补丁 info 段首行不是 %r: %r" % (PATCH_INFO_V1, lines[0] if lines else ""))
    info = {"deletes": []}
    for ln in lines[1:]:
        if ln.startswith("base="):
            info["base"] = ln[5:]
        elif ln.startswith("new="):
            info["new"] = ln[4:]  # "new=" 只有 4 个字符
        elif ln.startswith("deletes="):
            info["deletes_n"] = int(ln[8:])
        else:
            info["deletes"].append(ln)
    for k in ("base", "new", "deletes_n"):
        if k not in info:
            die("补丁 info 段缺少字段 %s=" % k)
    if info["deletes_n"] != len(info["deletes"]):
        die(
            "补丁 info 段 deletes=%d 与实际行数 %d 不一致"
            % (info["deletes_n"], len(info["deletes"]))
        )
    return info


def read_patch_info(zstd, patch_path):
    """流式解压补丁包，只读到第一个成员（info 段）就收工（不必解完整包）。"""
    try:
        p = subprocess.Popen(
            [zstd, "-d", "-q", "-c", patch_path],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
    except FileNotFoundError:
        die("找不到 zstd 可执行文件: %s（用 --zstd <路径> 指定）" % zstd)
    except OSError as e:
        die("调用 zstd 失败 %s: %s" % (zstd, e))
    try:
        try:
            tf = tarfile.open(fileobj=p.stdout, mode="r|")
        except tarfile.TarError as e:
            die("补丁包不是合法 tar: %s（%s）" % (patch_path, e))
        try:
            for m in tf:
                if m.name.rstrip("/") == PATCH_INFO_MEMBER:
                    f = tf.extractfile(m)
                    if f is None:
                        die("补丁包里的 %s 不是普通文件" % PATCH_INFO_MEMBER)
                    return parse_patch_info(f.read())
            die("补丁包里没有第一个成员 %s" % PATCH_INFO_MEMBER)
        finally:
            try:
                tf.close()
            except Exception:
                pass
    finally:
        try:
            p.stdout.close()
        except Exception:
            pass
        try:
            p.kill()
        except Exception:
            pass
        p.wait()


# ---------------------------------------------------------------- 子命令：manifest


def cmd_manifest(args):
    root = os.path.abspath(args.root)
    entries, skipped = scan_tree(root)
    data, env = render_manifest(entries, args.distro)
    atomic_write_bytes(args.out, data)
    n_f = sum(1 for e in entries if e[1] == "f")
    n_d = sum(1 for e in entries if e[1] == "d")
    n_l = sum(1 for e in entries if e[1] == "l")
    total = sum(e[3] for e in entries if e[1] == "f")
    print("root=%s" % root)
    print("manifest=%s" % os.path.abspath(args.out))
    print(
        "files=%d dirs=%d links=%d skipped=%d bytes=%d"
        % (n_f, n_d, n_l, len(skipped), total)
    )
    if skipped:
        print("skipped (FIFO/socket/device): %s" % ", ".join(skipped[:5]))
    # 末行必须是 env=<16hex>
    print("env=%s" % env)
    return 0


# ---------------------------------------------------------------- 子命令：env


def cmd_env(args):
    data = read_bytes(args.manifest)
    env = env_from_manifest_bytes(data)
    print("env=%s" % env)
    return 0


# ---------------------------------------------------------------- 子命令：patch


def _write_patch_member(tf, entry, root):
    """把一个变更成员写进补丁 tar；f 顺带校验磁盘内容与清单一致。"""
    path, typ, mode, size, digest = entry
    ti = tarfile.TarInfo(path)
    ti.mode = mode
    ti.uid = 0
    ti.gid = 0
    ti.uname = ""
    ti.gname = ""
    ti.mtime = 0  # 确定性：补丁内容不依赖时间
    rel = path[2:]
    if typ == "d":
        ti.type = tarfile.DIRTYPE
        ti.size = 0
        tf.addfile(ti)
    elif typ == "l":
        ti.type = tarfile.SYMTYPE
        ti.linkname = digest  # 清单里 l 的 digest 就是链接目标
        ti.size = 0
        tf.addfile(ti)
    else:
        absf = os.path.join(root, rel.replace("/", os.sep))
        if not os.path.isfile(absf) or os.path.islink(absf):
            die("补丁成员 %s 在新树里找不到普通文件: %s" % (path, absf))
        st = os.stat(absf)
        if st.st_size != size:
            die(
                "补丁成员 %s 磁盘大小 %d 与清单 %d 不一致（新树与清单不是同一棵树？）"
                % (path, st.st_size, size)
            )
        ti.type = tarfile.REGTYPE
        ti.size = size
        reader = _HashingReader(open(absf, "rb"))
        try:
            tf.addfile(ti, reader)
        finally:
            reader.close()
        got = reader.h.hexdigest()
        if got != digest:
            die("补丁成员 %s 内容 sha256 与清单不一致：磁盘 %s != 清单 %s" % (path, got, digest))


class _HashingReader(object):
    """边写 tar 边算 sha256 的读取包装（tarfile 只要求 read()）。"""

    def __init__(self, fh):
        self.fh = fh
        self.h = hashlib.sha256()

    def read(self, n=-1):
        b = self.fh.read(n)
        if b:
            self.h.update(b)
        return b

    def close(self):
        try:
            self.fh.close()
        except Exception:
            pass


def verify_patch(zstd, zst_path, expected_entries, base_env, new_env, deletes, tmpdir):
    """把刚写的 tar.zst 解回来，逐项确认成员集合 / 类型 / mode / 目标 / 内容与预期一致。"""
    fd, dec = tempfile.mkstemp(prefix=".zd-verify-", suffix=".tar", dir=tmpdir)
    os.close(fd)
    os.unlink(dec)
    try:
        _verify_patch_inner(zstd, zst_path, dec, expected_entries, base_env, new_env, deletes)
    finally:
        rm_quiet(dec)


def _verify_patch_inner(zstd, zst_path, dec, expected_entries, base_env, new_env, deletes):
    decompress_zstd(zstd, zst_path, dec)
    want = [(PATCH_INFO_MEMBER, "info")] + [
        (e.path, e.typ) for e in expected_entries
    ]
    try:
        tf = tarfile.open(dec, mode="r:")
    except tarfile.TarError as e:
        die("自检失败：补丁包解回来不是合法 tar: %s" % e)
    with tf:
        members = tf.getmembers()
        got = [(m.name.rstrip("/"), m) for m in members]
        if [g[0] for g in got] != [w[0] for w in want]:
            die(
                "自检失败：补丁成员集合/顺序与预期不一致\n  预期: %s\n  实际: %s"
                % ([w[0] for w in want], [g[0] for g in got])
            )
        # 第一个成员必须是 info 段，且内容自洽
        info_m = got[0][1]
        f = tf.extractfile(info_m)
        if f is None:
            die("自检失败：%s 不是普通文件" % PATCH_INFO_MEMBER)
        info = parse_patch_info(f.read())
        if info["base"] != base_env or info["new"] != new_env:
            die(
                "自检失败：info 段 env 不符（base=%s/%s new=%s/%s）"
                % (info["base"], base_env, info["new"], new_env)
            )
        # info 段的删除清单是「相对 path」（无 ./ 前缀），与清单 path 差一个前缀
        want_deletes = [p[2:] if p.startswith("./") else p for p in deletes]
        if info["deletes"] != want_deletes:
            die(
                "自检失败：info 段 deletes 与预期不一致\n  预期 %d 条 %r\n  实际 %d 条 %r"
                % (
                    len(want_deletes),
                    want_deletes,
                    len(info["deletes"]),
                    info["deletes"],
                )
            )
        for idx, e in enumerate(expected_entries, 1):
            m = got[idx][1]
            if e.typ == "d":
                if not m.isdir():
                    die("自检失败：%s 应为目录成员" % m.name)
            elif e.typ == "l":
                if not m.issym():
                    die("自检失败：%s 应为软链成员（SYMTYPE）" % m.name)
                if m.linkname != e.digest:
                    die(
                        "自检失败：%s 软链目标 %r != 清单 %r" % (m.name, m.linkname, e.digest)
                    )
            else:
                if not m.isfile():
                    die("自检失败：%s 应为普通文件成员" % m.name)
                src = tf.extractfile(m)
                h = hashlib.sha256()
                n = 0
                while True:
                    b = src.read(CHUNK)
                    if not b:
                        break
                    h.update(b)
                    n += len(b)
                if n != e.size or h.hexdigest() != e.digest:
                    die(
                        "自检失败：%s 内容与清单不符（%d 字节 sha=%s）"
                        % (m.name, n, h.hexdigest())
                    )
            if stat.S_IMODE(m.mode) != e.mode:
                die(
                    "自检失败：%s mode %04o != 清单 %04o"
                    % (m.name, stat.S_IMODE(m.mode), e.mode)
                )
    rm_quiet(dec)


def cmd_patch(args):
    if not os.path.isdir(args.root):
        die("--root 不是目录或不存在: %s" % args.root)
    root = os.path.abspath(args.root)
    base_env, base = parse_manifest(args.base, "base 清单")
    new_env, new = parse_manifest(args.new, "new 清单")

    added = []
    changed = []
    for p, e in new.items():
        old = base.get(p)
        if old is None:
            added.append(p)
        elif e.same_as(old):
            changed.append(p)
    payload = sorted(added + changed, key=fsencode)
    deletes = sorted([p for p in base if p not in new], key=fsencode)

    out = os.path.abspath(args.out)
    outdir = os.path.dirname(out) or "."
    try:
        os.makedirs(outdir, exist_ok=True)
    except OSError as e:
        die("创建输出目录失败 %s: %s" % (outdir, e))

    fd, tar_path = tempfile.mkstemp(prefix=".zd-patch-", suffix=".tar", dir=outdir)
    os.close(fd)
    fd, zst_path = tempfile.mkstemp(prefix=".zd-patch-", suffix=".tar.zst", dir=outdir)
    os.close(fd)
    os.unlink(zst_path)  # 交给 zstd 自己创建（-f 覆盖）
    try:
        info_bytes = build_patch_info(base_env, new_env, deletes)
        with tarfile.open(tar_path, "w", format=tarfile.GNU_FORMAT) as tf:
            ti = tarfile.TarInfo(PATCH_INFO_MEMBER)
            ti.type = tarfile.REGTYPE
            ti.mode = 0o644
            ti.size = len(info_bytes)
            ti.uid = 0
            ti.gid = 0
            ti.uname = ""
            ti.gname = ""
            ti.mtime = 0
            tf.addfile(ti, io.BytesIO(info_bytes))
            for p in payload:
                e = new[p]
                _write_patch_member(tf, (e.path, e.typ, e.mode, e.size, e.digest), root)

        tar_bytes = os.path.getsize(tar_path)
        compress_zstd(args.zstd, tar_path, zst_path)
        # 自检：解回来核对成员集合 / 内容
        verify_patch(
            args.zstd,
            zst_path,
            [new[p] for p in payload],
            base_env,
            new_env,
            deletes,
            outdir,
        )
        os.replace(zst_path, out)
    finally:
        rm_quiet(tar_path)
        rm_quiet(zst_path)

    print("patch=%s" % out)
    print("payload=%d (added=%d changed=%d)" % (len(payload), len(added), len(changed)))
    # 工作流按这一行取值
    print(
        "base=%s new=%s added=%d changed=%d deleted=%d bytes=%d"
        % (base_env, new_env, len(added), len(changed), len(deletes), tar_bytes)
    )
    return 0


# ---------------------------------------------------------------- 子命令：index


def release_url(repo, asset):
    return _RELEASE_URL_TMPL.format(repo=repo, asset=asset)


def cmd_index(args):
    new_env = env_from_manifest_bytes(read_bytes(args.new_manifest))
    pkg_sha, pkg_size = hash_and_size(args.pkg)
    if args.asset != os.path.basename(args.asset):
        die("--asset 只填资产文件名（不带路径）: %r" % args.asset)

    index = {
        "schema": 2,
        "distro": args.distro,
        "version": args.version,
        "env": new_env,
        "builtAt": args.built_at or utc_now_iso(),
        "url": release_url(args.repo, args.asset),
        "sha256": pkg_sha,
        "size": pkg_size,
        "manifestUrl": release_url(args.repo, args.manifest_asset),
        "patch": None,
    }

    if args.patch:
        patch_sha, patch_size = hash_and_size(args.patch)
        info = read_patch_info(args.zstd, args.patch)
        if info["new"] != new_env:
            die(
                "补丁的 new env=%s 与本版清单 env=%s 不一致 —— 补丁不是给这一版清单做的"
                % (info["new"], new_env)
            )
        index["patch"] = {
            "from": info["base"],
            "url": release_url(args.repo, os.path.basename(args.patch)),
            "sha256": patch_sha,
            "size": patch_size,
        }

    data = (json.dumps(index, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    atomic_write_bytes(args.out, data)

    p = index["patch"]
    print("index=%s" % os.path.abspath(args.out))
    print(
        "env=%s distro=%s version=%s asset=%s size=%d patch=%s"
        % (new_env, args.distro, args.version, args.asset, pkg_size, p["from"] if p else "-")
    )
    return 0


# ---------------------------------------------------------------- CLI


def build_parser():
    ap = argparse.ArgumentParser(
        prog="rootfs-manifest.py",
        description="证道 rootfs 清单 / 差分包 / 索引工具（格式契约见 docs/milestones/证道-环境包增量下发协议.md）",
    )
    sub = ap.add_subparsers(dest="cmd")

    m = sub.add_parser("manifest", help="遍历 rootfs 树生成清单")
    m.add_argument("--root", required=True, help="rootfs 树的根目录")
    m.add_argument("--out", required=True, help="清单输出文件（rootfs-manifest.txt）")
    m.add_argument("--distro", default=DEFAULT_DISTRO, help="发行版标识（默认 %s）" % DEFAULT_DISTRO)
    m.set_defaults(func=cmd_manifest)

    e = sub.add_parser("env", help="从清单重算并打印 env")
    e.add_argument("--manifest", required=True)
    e.set_defaults(func=cmd_env)

    p = sub.add_parser("patch", help="对比两版清单生成差分包")
    p.add_argument("--root", required=True, help="**新树**根目录（用于读取变更文件内容）")
    p.add_argument("--base", required=True, help="上一版清单")
    p.add_argument("--new", required=True, help="本版清单")
    p.add_argument("--out", required=True, help="补丁输出文件（*.tar.zst）")
    p.add_argument("--zstd", default="zstd", help="zstd 可执行文件路径（默认 zstd）")
    p.set_defaults(func=cmd_patch)

    i = sub.add_parser("index", help="生成 rootfs-index.json")
    i.add_argument("--new-manifest", required=True)
    i.add_argument("--pkg", required=True, help="完整包文件（*.tar.zst）")
    i.add_argument("--patch", default=None, help="补丁包文件（可选；不给则 patch=null）")
    i.add_argument("--repo", required=True, help="owner/repo")
    i.add_argument("--distro", required=True)
    i.add_argument("--version", required=True)
    i.add_argument("--asset", required=True, help="完整包资产名")
    i.add_argument("--built-at", default=None, help="ISO8601 时间（默认当前 UTC）")
    i.add_argument("--manifest-asset", default=DEFAULT_MANIFEST_ASSET, help="清单资产名（默认 %s）" % DEFAULT_MANIFEST_ASSET)
    i.add_argument("--zstd", default="zstd", help="zstd 可执行文件路径（读补丁 info 段用）")
    i.add_argument("--out", required=True)
    i.set_defaults(func=cmd_index)
    return ap


def main(argv=None):
    ap = build_parser()
    args = ap.parse_args(argv)
    if not getattr(args, "func", None):
        ap.print_help(sys.stderr)
        return 2
    try:
        return args.func(args)
    except ToolError as e:
        print("错误: %s" % e, file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("已中断", file=sys.stderr)
        return 130
    except Exception as e:  # 未预期
        traceback.print_exc()
        print("错误(未预期) %s: %s" % (type(e).__name__, e), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
