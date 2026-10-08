#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
rootfs-delta-selftest.py —— 证道 rootfs「清单 + 差分包」端到端自测（本地跑，CI 可选）。

它做的事（对应契约 §1–§3、§6）：
  [1/6] 造一棵小 base 树（含 1MB 随机文件、嵌套目录、软链、0755 可执行文件）并生成 base 清单；
  [2/6] 在 base 副本上造 new 树：新增 2 个文件、改 2 个文件内容、改 1 个 mode、
        换 1 个软链目标、删 1 个文件 + 1 个目录；生成 new 清单；
  [3/6] 用 tools/rootfs-manifest.py patch 生成补丁包，打印补丁字节数，
        并独立核对补丁 tar 的成员集合（不依赖工具自带的自检）；
  [4/6] 用脚本内实现的「参考 Applier」（模拟契约 §6：复制 base 树 → 解补丁 → 按 deletes 递归删
        → 不落地 .zhengdao-patch-info）把补丁应用到 base 树的副本上；
  [5/6] 对应用后的树重新生成清单，断言与 new 清单正文逐字节相同，且 env 相同；
  [6/6] 附加断言：清单生成确定（跑两次逐字节相同）、改 mtime 后 env 不变、
        清单里没有 .zhengdao-patch-info、正文行格式/排序合规。

本机（无符号链接权限）会自动跳过软链相关的端到端用例，并改用「合成清单」覆盖软链进补丁的代码路径；
最终结果的 SKIP 行会显式列出跳过了什么，不会假装通过。

格式契约见 docs/milestones/证道-环境包增量下发协议.md。

用法：
  python tools/rootfs-delta-selftest.py [--zstd <zstd 路径>] [--workdir <目录>] [--keep]
退出码：0 = PASS（可能带 SKIP）；1 = FAIL。
"""

import argparse
import hashlib
import json
import os
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(HERE, "rootfs-manifest.py")
PATCH_INFO_BASENAME = ".zhengdao-patch-info"

FAILURES = []
SKIPS = []
STATS = {"pass": 0, "fail": 0}


# ---------------------------------------------------------------- 日志 / 断言


def log(msg=""):
    sys.stdout.write(msg + "\n")
    sys.stdout.flush()


def step(idx, title):
    log("")
    log("[%d/6] %s" % (idx, title))


def note(msg):
    log("      .. %s" % msg)


def ok(msg):
    STATS["pass"] += 1
    log("      OK   %s" % msg)


def skip(msg):
    SKIPS.append(msg)
    log("      SKIP %s" % msg)


def check(cond, msg, detail=None):
    if cond:
        ok(msg)
        return True
    STATS["fail"] += 1
    FAILURES.append(msg)
    log("      FAIL %s" % msg)
    if detail:
        for line in str(detail).splitlines():
            log("           | %s" % line)
    return False


# ---------------------------------------------------------------- 环境 / 子进程


def probe_symlink_support(dirpath):
    """返回 (是否支持创建符号链接, 失败原因)。"""
    tgt = os.path.join(dirpath, "sym-probe-target")
    link = os.path.join(dirpath, "sym-probe-link")
    with open(tgt, "wb") as f:
        f.write(b"x")
    err = ""
    supported = False
    try:
        os.symlink("sym-probe-target", link)
        supported = os.path.islink(link) and os.readlink(link) == "sym-probe-target"
    except (OSError, NotImplementedError) as e:
        err = "%s: %s" % (type(e).__name__, e)
    finally:
        force_remove(link)
        force_remove(tgt)
    return supported, err


def run_tool(args, expect_ok=True):
    """调用 tools/rootfs-manifest.py；返回 (rc, stdout, stderr)。"""
    cmd = [sys.executable, TOOL] + [str(a) for a in args]
    env = dict(os.environ)
    env["PYTHONIOENCODING"] = "utf-8"  # Windows 管道下默认是 cp936，中文会炸
    env["PYTHONUTF8"] = "1"
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
    out = p.stdout.decode("utf-8", "replace")
    err = p.stderr.decode("utf-8", "replace")
    if expect_ok and p.returncode != 0:
        check(False, "命令应当成功却失败 rc=%d" % p.returncode, " ".join(cmd) + "\n" + err.strip())
    return p.returncode, out, err


def last_line(text, prefix):
    """取 stdout 里最后一条以 prefix 开头的行（用于解析 env= / base= 行）。"""
    hit = None
    for line in text.splitlines():
        if line.startswith(prefix):
            hit = line
    return hit


# ---------------------------------------------------------------- 文件系统工具


def wb(root, rel, data, mode=None):
    p = os.path.join(root, *rel.split("/"))
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "wb") as f:
        f.write(data)
    if mode is not None:
        os.chmod(p, mode)
    return p


def force_remove(path):
    if not os.path.lexists(path):
        return
    if os.path.islink(path) or os.path.isfile(path):
        try:
            os.remove(path)
        except PermissionError:
            os.chmod(path, 0o666)
            os.remove(path)
    elif os.path.isdir(path):
        rmtree(path)


def _onexc(func, path, exc):
    try:
        os.chmod(path, 0o777 if os.path.isdir(path) else 0o666)
    except OSError:
        pass
    try:
        func(path)
    except OSError:
        pass


def _onerror(func, path, exc_info):
    _onexc(func, path, exc_info)


def rmtree(path):
    if not os.path.exists(path) and not os.path.islink(path):
        return
    if sys.version_info >= (3, 12):
        shutil.rmtree(path, onexc=_onexc)
    else:
        shutil.rmtree(path, onerror=_onerror)


def _link_or_copy(src, dst):
    """契约 §6「同内容文件优先硬链接，失败退复制」。"""
    try:
        os.link(src, dst)
    except OSError:
        shutil.copy2(src, dst)


def copy_tree(src, dst):
    shutil.copytree(src, dst, symlinks=True, copy_function=_link_or_copy)


def read_bytes(path):
    with open(path, "rb") as f:
        return f.read()


def write_bytes(path, data):
    with open(path, "wb") as f:
        f.write(data)


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


# ---------------------------------------------------------------- 造树


def build_base_tree(root, can_symlink):
    """契约 §6 的「当前 rootfs」：文件 / 嵌套目录 / 软链 / 可执行文件。"""
    os.makedirs(root, exist_ok=True)
    plan = []

    wb(root, "etc/config.ini", b"config v1\n")
    plan.append("etc/config.ini")
    wb(root, "etc/obsolete.conf", b"obsolete\n")  # → 新树删除（文件）
    plan.append("etc/obsolete.conf")

    wb(root, "usr/bin/hello", b"hello v1\n")  # → 新树改内容
    plan.append("usr/bin/hello")
    wb(root, "usr/bin/tool", b"tool v1\n", 0o755)  # → 新树改 mode
    plan.append("usr/bin/tool")

    wb(root, "usr/local/bin/exe", b"#!/bin/sh\necho hi\n", 0o755)  # 0755 可执行文件（不变）
    plan.append("usr/local/bin/exe")

    wb(root, "usr/lib/blob.bin", os.urandom(1 << 20))  # 1MB 随机文件（不变 → 不该进补丁）
    plan.append("usr/lib/blob.bin")
    wb(root, "usr/lib/unchanged.txt", b"same\n")  # 不变 → 不该进补丁
    plan.append("usr/lib/unchanged.txt")
    os.makedirs(os.path.join(root, "usr", "lib", "empty"), exist_ok=True)  # 空目录（不变）
    plan.append("usr/lib/empty/")

    wb(root, "usr/share/doc/readme.txt", b"readme v1\n")  # → 新树改内容
    plan.append("usr/share/doc/readme.txt")
    wb(root, "usr/share/legacy/a.txt", b"legacy a\n")  # → 新树整目录删除
    wb(root, "usr/share/legacy/b.txt", b"legacy b\n")
    plan.append("usr/share/legacy/ (含 2 个文件)")

    if can_symlink:
        link = os.path.join(root, "usr", "bin", "link")
        os.symlink("hello", link)  # → 新树换目标
        plan.append("usr/bin/link -> hello")
    return plan


def build_new_tree(base_root, new_root, can_symlink):
    """在 base 的副本上做 delta 变更（契约 §6 的「新树」）。"""
    # 这里必须「真拷贝」而不是硬链接复制：下一步要原地改文件内容和权限，
    # 硬链接会让 base 树跟着被改（base 树被污染正是 [4/6] 要抓的 bug，
    # 那个断言检查的是 Applier 的 copy_tree 硬链接复制有没有写坏源树）。
    shutil.copytree(base_root, new_root, symlinks=True)
    os.chmod(new_root, 0o755)
    plan = []

    wb(new_root, "usr/bin/new1", b"new1\n")  # 新增
    wb(new_root, "etc/new2.conf", b"new2\n")  # 新增
    plan.append("新增 usr/bin/new1, etc/new2.conf")

    wb(new_root, "usr/bin/hello", b"hello v2\n")  # 改内容
    wb(new_root, "usr/share/doc/readme.txt", b"readme v2\n")  # 改内容
    plan.append("改内容 usr/bin/hello, usr/share/doc/readme.txt")

    os.chmod(os.path.join(new_root, "usr", "bin", "tool"), 0o444)  # 改 mode
    plan.append("改 mode usr/bin/tool -> 0444")

    if can_symlink:
        link = os.path.join(new_root, "usr", "bin", "link")
        os.remove(link)
        os.symlink("tool", link)  # 换软链目标
        plan.append("换软链目标 usr/bin/link -> tool")

    force_remove(os.path.join(new_root, "etc", "obsolete.conf"))  # 删文件
    rmtree(os.path.join(new_root, "usr", "share", "legacy"))  # 删目录（含内容）
    plan.append("删除 etc/obsolete.conf, usr/share/legacy/ (递归)")
    return plan


# ---------------------------------------------------------------- 参考 Applier（契约 §6）


def read_patch(patch_zst, zstd):
    """解压补丁包并读出 (info 段, tar 文件路径)。调用方负责删除 tar。"""
    fd, tar_path = tempfile.mkstemp(prefix="zd-applier-", suffix=".tar")
    os.close(fd)
    os.unlink(tar_path)
    cmd = [zstd, "-d", "-q", "-f", "-k", "-o", tar_path, patch_zst]
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if p.returncode != 0:
        raise RuntimeError("zstd 解压失败 rc=%d: %s" % (p.returncode, p.stderr.decode("utf-8", "replace")))
    info = None
    with tarfile.open(tar_path, "r:") as tf:
        names = [m.name for m in tf.getmembers()]
        first = tf.getmembers()[0]
        if first.name.rstrip("/") == "./" + PATCH_INFO_BASENAME:
            info = parse_patch_info(tf.extractfile(first).read())
    return info, tar_path, names


def parse_patch_info(data):
    text = data.decode("utf-8", "surrogateescape")
    lines = [l for l in text.split("\n")]
    while lines and lines[-1] == "":
        lines.pop()
    out = {"version": lines[0], "deletes": []}
    for ln in lines[1:]:
        if ln.startswith("base="):
            out["base"] = ln[5:]
        elif ln.startswith("new="):
            out["new"] = ln[4:]  # "new=" 只有 4 个字符
        elif ln.startswith("deletes="):
            out["deletes_n"] = int(ln[8:])
        else:
            out["deletes"].append(ln)
    return out


def apply_patch_reference(base_root, work_root, patch_zst, zstd, tar_path):
    """
    参考 Applier = 契约 §6 的端侧算法（与 App 的 RootfsInstaller 语义一致）：
      1. 把 base 树整棵搬到 work_root（硬链接优先，失败退复制；软链重建软链）
      2. 解补丁到 work_root（跳过 .zhengdao-patch-info；不恢复 mtime；目录 mkdirs+chmod；
         软链 symlink；普通文件先写后 chmod）
      3. 按 deletes 递归删（深的先删，仍然限制在 work_root 内）
      4. 断言补丁自带的 .zhengdao-patch-info 没有落地
    """
    copy_tree(base_root, work_root)
    root_abs = os.path.abspath(work_root)
    extracted = []
    with tarfile.open(tar_path, "r:") as tf:
        for m in tf.getmembers():
            name = m.name
            while name.startswith("./"):
                name = name[2:]
            if name in ("", ".", PATCH_INFO_BASENAME):
                continue  # App 侧同样跳过补丁自带的 info 段
            dest = os.path.join(root_abs, *name.split("/"))
            if os.path.abspath(dest) != root_abs and not os.path.abspath(dest).startswith(root_abs + os.sep):
                raise RuntimeError("补丁成员越界: %s" % m.name)
            if m.isdir():
                os.makedirs(dest, exist_ok=True)
                os.chmod(dest, m.mode)
            elif m.issym():
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                if os.path.lexists(dest):
                    force_remove(dest)
                os.symlink(m.linkname, dest)
            elif m.isfile():
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                # 先删再写：work_root 的文件可能硬链接到 base 树，原地截断会污染 base 树
                if os.path.lexists(dest):
                    force_remove(dest)
                with open(dest, "wb") as f:
                    src = tf.extractfile(m)
                    shutil.copyfileobj(src, f)
                os.chmod(dest, m.mode)
            else:
                continue  # FIFO/设备等：App 侧同样跳过
            extracted.append(name)
    info = None
    # 删除清单来自补丁 info 段
    with tarfile.open(tar_path, "r:") as tf:
        first = tf.getmembers()[0]
        info = parse_patch_info(tf.extractfile(first).read())
    for rel in sorted(info["deletes"], key=lambda p: (-p.count("/"), p)):
        dest = os.path.join(root_abs, *rel.split("/"))
        if os.path.abspath(dest) == root_abs or not os.path.abspath(dest).startswith(root_abs + os.sep):
            raise RuntimeError("删除项越界: %s" % rel)
        force_remove(dest)
    return info, extracted


# ---------------------------------------------------------------- 清单比较


def manifest_parts(data):
    """返回 (头行列表, 正文行列表)。"""
    head, body = [], []
    for raw in data.split(b"\n"):
        if not raw:
            continue
        (head if raw.startswith(b"#") else body).append(raw)
    return head, body


def env_of(manifest_path):
    rc, out, err = run_tool(["env", "--manifest", manifest_path])
    line = last_line(out, "env=")
    if rc != 0 or not line:
        return None
    return line.split("=", 1)[1].strip()


def first_diff(a_lines, b_lines, label):
    if len(a_lines) != len(b_lines):
        return "%s 行数不同: %d != %d" % (label, len(a_lines), len(b_lines))
    for i, (x, y) in enumerate(zip(a_lines, b_lines)):
        if x != y:
            return "%s 首个不同在第 %d 行:\n  期望 %r\n  实际 %r" % (
                label,
                i + 1,
                y.decode("utf-8", "replace"),
                x.decode("utf-8", "replace"),
            )
    return None


# ---------------------------------------------------------------- 主流程


def main():
    ap = argparse.ArgumentParser(description="rootfs 增量（清单+补丁）端到端自测")
    ap.add_argument("--zstd", default=None, help="zstd 可执行文件路径（默认找 PATH，再找本机已知路径）")
    ap.add_argument("--workdir", default=None, help="工作目录（默认系统临时目录）")
    ap.add_argument("--keep", action="store_true", help="保留工作目录便于排查")
    args = ap.parse_args()

    t_start = time.time()
    log("=== rootfs 增量下发自测（清单 / 补丁 / 参考 Applier） ===")
    log("python     = %s" % sys.executable)
    log("tool       = %s" % TOOL)
    log("python ver = %s" % sys.version.replace("\n", " "))

    if not os.path.isfile(TOOL):
        log("找不到 %s" % TOOL)
        return 1

    zstd = args.zstd
    if not zstd:
        zstd = shutil.which("zstd") or ""
    if not zstd:
        known = r"C:\Users\guoli\AppData\Local\Temp\zd-pack\zstd-cli\zstd-v1.5.7-win64\zstd.exe"
        if os.path.isfile(known):
            zstd = known
    if not zstd or not os.path.isfile(zstd):
        log("找不到 zstd，请用 --zstd <路径> 指定（本机已知路径不存在）")
        return 1
    p = subprocess.run([zstd, "--version"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    log("zstd       = %s" % zstd)
    log("zstd ver   = %s" % p.stdout.decode("utf-8", "replace").strip())

    work = args.workdir
    if work:
        os.makedirs(work, exist_ok=True)
    else:
        work = tempfile.mkdtemp(prefix="zd-selftest-")
    work = os.path.abspath(work)
    log("workdir    = %s" % work)

    base_root = os.path.join(work, "base")
    new_root = os.path.join(work, "new")
    applied_root = os.path.join(work, "applied")
    base_manifest = os.path.join(work, "base-manifest.txt")
    new_manifest = os.path.join(work, "new-manifest.txt")
    patch_path = os.path.join(work, "rootfs-patch.tar.zst")

    can_symlink, sym_err = probe_symlink_support(work)
    log("symlink    = %s%s" % ("支持" if can_symlink else "不支持", "" if can_symlink else "  (%s)" % sym_err))

    try:
        # ---------------- [1/6]
        step(1, "造 base 树并生成 base 清单")
        plan = build_base_tree(base_root, can_symlink)
        note("base 树内容: %s" % "; ".join(plan))
        rc, out, err = run_tool(["manifest", "--root", base_root, "--out", base_manifest])
        if rc != 0:
            check(False, "manifest 生成 base 清单失败", err)
            return 1
        base_env = last_line(out, "env=")
        check(bool(base_env), "base 清单末行打印 %s" % base_env)
        note(last_line(out, "files=") or out.strip().replace("\n", " | "))
        check(os.path.isfile(base_manifest), "base 清单文件已生成: %s" % base_manifest)

        # ---------------- [2/6]
        step(2, "在 base 副本上造 new 树并生成 new 清单")
        plan = build_new_tree(base_root, new_root, can_symlink)
        note("new 树变更: %s" % "; ".join(plan))
        rc, out, err = run_tool(["manifest", "--root", new_root, "--out", new_manifest])
        if rc != 0:
            check(False, "manifest 生成 new 清单失败", err)
            return 1
        new_env = last_line(out, "env=")
        check(bool(new_env), "new 清单末行打印 %s" % new_env)
        check(base_env != new_env, "base/new env 不同（%s != %s）" % (base_env, new_env))

        # ---------------- [3/6]
        step(3, "生成补丁包")
        rc, out, err = run_tool(
            ["patch", "--root", new_root, "--base", base_manifest, "--new", new_manifest,
             "--out", patch_path, "--zstd", zstd]
        )
        if rc != 0:
            check(False, "patch 生成失败", err)
            return 1
        summary = last_line(out, "base=")
        note(summary or out.strip().replace("\n", " | "))
        patch_size = os.path.getsize(patch_path) if os.path.isfile(patch_path) else 0
        log("      补丁字节数（压缩后 .tar.zst）= %d" % patch_size)
        check(patch_size > 0, "补丁包已生成且非空")
        fields = dict(kv.split("=", 1) for kv in (summary or "").split() if "=" in kv)
        exp_added, exp_changed, exp_deleted = 2, (4 if can_symlink else 3), 4
        check(fields.get("added") == str(exp_added), "added 计数 = %s（期望 %d）" % (fields.get("added"), exp_added))
        check(fields.get("changed") == str(exp_changed), "changed 计数 = %s（期望 %d）" % (fields.get("changed"), exp_changed))
        check(fields.get("deleted") == str(exp_deleted), "deleted 计数 = %s（期望 %d）" % (fields.get("deleted"), exp_deleted))
        if "bytes" in fields:
            log("      未压缩 tar 字节数 = %s" % fields["bytes"])

        # 独立核对补丁成员集合（不依赖工具自带自检）
        info, patch_tar, names = read_patch(patch_path, zstd)
        try:
            norm = [n.rstrip("/") for n in names]
            payload = norm[1:]
            expected_payload = [
                "./etc/new2.conf",
                "./usr/bin/hello",
                "./usr/bin/new1",
                "./usr/share/doc/readme.txt",
            ]
            if can_symlink:
                expected_payload.append("./usr/bin/link")
            else:
                expected_payload.append("./usr/bin/tool")  # mode 变了（Windows 上 0666 -> 0444）
            expected_payload.sort(key=lambda s: s.encode("utf-8"))
            if can_symlink:
                # tool 在 POSIX 上 mode 也变了，所以两边都在
                expected_payload = sorted(
                    payload_expected_full(can_symlink), key=lambda s: s.encode("utf-8")
                )
            check(norm[0] == "./" + PATCH_INFO_BASENAME, "补丁第一个成员是 %s" % norm[0])
            check(payload == expected_payload,
                  "补丁载荷成员集合与预期一致（%d 个）" % len(payload),
                  "期望: %s\n实际: %s" % (expected_payload, payload))
            check(info is not None and info.get("base") == base_env.split("=")[-1],
                  "补丁 info base=%s 等于 base env" % (info or {}).get("base"))
            check(info is not None and info.get("new") == new_env.split("=")[-1],
                  "补丁 info new=%s 等于 new env" % (info or {}).get("new"))
            check(info is not None and info.get("deletes_n") == len(info["deletes"]) == 4,
                  "补丁 info deletes 段 = %d 条" % (len(info["deletes"]) if info else -1))
            with tarfile.open(patch_tar, "r:") as tf:
                m0 = tf.getmembers()[0]
                check(m0.isfile(), "info 段是普通文件成员")
                if can_symlink:
                    lm = [m for m in tf.getmembers() if m.name.rstrip("/") == "./usr/bin/link"]
                    check(bool(lm) and lm[0].issym() and lm[0].linkname == "tool",
                          "补丁里 ./usr/bin/link 是 SYMTYPE 且目标=tool")
        finally:
            force_remove(patch_tar)

        # ---------------- [4/6]
        step(4, "用参考 Applier 把补丁应用到 base 树的副本（模拟契约 §6）")
        info, patch_tar, names = read_patch(patch_path, zstd)
        try:
            applied_info, extracted = apply_patch_reference(base_root, applied_root, patch_path, zstd, patch_tar)
        finally:
            force_remove(patch_tar)
        note("Applier 落地 %d 个成员，删除 %d 项" % (len(extracted), len(applied_info["deletes"])))
        check(True, "参考 Applier 应用完成（复制 base -> 解补丁 -> 递归删）")
        check(not os.path.exists(os.path.join(applied_root, PATCH_INFO_BASENAME)),
              "补丁自带的 .zhengdao-patch-info 没有落地到应用后的树")
        check(not os.path.exists(os.path.join(applied_root, "etc", "obsolete.conf")),
              "删除项 etc/obsolete.conf 已从应用后的树里消失")
        check(not os.path.exists(os.path.join(applied_root, "usr", "share", "legacy")),
              "删除项 usr/share/legacy/ 已递归删除")
        check(os.path.isfile(os.path.join(applied_root, "usr", "bin", "new1")),
              "新增文件 usr/bin/new1 已落地")
        # base 树未被硬链接写坏（Applier 先删再写）
        base_hello = os.path.join(base_root, "usr", "bin", "hello")
        check(read_bytes(base_hello) == b"hello v1\n",
              "base 树未被污染（usr/bin/hello 仍是 v1 —— 硬链接 + 原地截断会写坏源树）",
              "实际内容 %r" % read_bytes(base_hello))

        # ---------------- [5/6]
        step(5, "重新生成应用后树的清单，与 new 清单逐字节比对")
        applied_manifest = os.path.join(work, "applied-manifest.txt")
        rc, out, err = run_tool(["manifest", "--root", applied_root, "--out", applied_manifest])
        if rc != 0:
            check(False, "manifest 生成应用后清单失败", err)
            return 1
        applied_env_line = last_line(out, "env=")
        a_head, a_body = manifest_parts(read_bytes(applied_manifest))
        n_head, n_body = manifest_parts(read_bytes(new_manifest))
        diff = first_diff(a_body, n_body, "清单正文")
        check(diff is None, "应用后清单正文与 new 清单逐字节相同（%d 行）" % len(n_body), diff)
        diff_h = first_diff([l for l in a_head if not l.startswith(b"# env=")],
                            [l for l in n_head if not l.startswith(b"# env=")], "清单头")
        check(diff_h is None, "清单头（除 env 行外）相同", diff_h)
        applied_env = env_of(applied_manifest)
        new_env_val = env_of(new_manifest)
        check(applied_env == new_env_val and applied_env is not None,
              "应用后 env == new env（%s == %s）" % (applied_env, new_env_val))
        check(applied_env_line == new_env, "manifest 末行 env 与 new 清单一致")

        # ---------------- [6/6]
        step(6, "附加断言：确定性 / mtime 无关 / 无 patch-info / 格式合规")
        m2 = os.path.join(work, "base-manifest-2.txt")
        m3 = os.path.join(work, "base-manifest-3.txt")
        run_tool(["manifest", "--root", base_root, "--out", m2])
        b1 = read_bytes(base_manifest)
        b2 = read_bytes(m2)
        check(b1 == b2, "同一棵树连续两次生成清单逐字节相同（%d 字节）" % len(b1))
        check(sha256_bytes(b1) == sha256_bytes(b2), "两份清单 sha256 相同 = %s" % sha256_bytes(b2)[:16])

        now = time.time()
        touched = 0
        for dirpath, dirnames, filenames in os.walk(base_root):
            for n in list(filenames) + list(dirnames):
                p = os.path.join(dirpath, n)
                try:
                    os.utime(p, (now - 86400 * 365, now - 86400 * 365))
                    touched += 1
                except OSError:
                    pass
        run_tool(["manifest", "--root", base_root, "--out", m3])
        b3 = read_bytes(m3)
        check(b3 == b1, "改掉 %d 个文件/目录的 mtime 后清单仍逐字节相同（env 因此不变）" % touched)

        bodies = [l for l in manifest_parts(b3)[1]]
        check(not any(b".zhengdao-patch-info" in l for l in bodies),
              "清单里没有 .zhengdao-patch-info")
        bad_fields = [l for l in bodies if len(l.split(b"\t")) != 5]
        check(not bad_fields, "正文每行都是 5 个制表符字段（%d 行）" % len(bodies),
              "反例: %s" % bad_fields[:3])
        paths = [l.split(b"\t")[4] for l in bodies]
        check(paths == sorted(paths), "正文按 path 字节序升序")
        check(all(p.startswith(b"./") for p in paths), "所有 path 以 ./ 开头")
        check(all(not p.endswith(b"/") for p in paths), "所有 path 不带尾随 /")
        types = set(l.split(b"\t")[0] for l in bodies)
        check(types <= {b"f", b"d", b"l"}, "type 只有 f/d/l，实际 %s" % sorted(t.decode() for t in types))
        if can_symlink:
            lmodes = set(l.split(b"\t")[1] for l in bodies if l.startswith(b"l\t"))
            check(lmodes <= {b"0777"}, "软链 mode 全为 0777，实际 %s" % sorted(m.decode() for m in lmodes))
        else:
            skip("本机无符号链接权限：无法端到端覆盖「软链进补丁 / 换目标」的真实用例")
            synth_symlink_check(work, zstd, base_manifest)
        edge_case_checks(work, zstd)
    finally:
        if args.keep:
            log("")
            log("保留工作目录: %s" % work)
        else:
            try:
                rmtree(work)
            except OSError as e:
                log("清理工作目录失败（可忽略）: %s" % e)

    # ---------------- 汇总
    dur = time.time() - t_start
    log("")
    log("=== 自测结果 ===")
    log("断言通过 %d，失败 %d，跳过 %d，耗时 %.2fs" % (STATS["pass"], STATS["fail"], len(SKIPS), dur))
    for s in SKIPS:
        log("  SKIP: %s" % s)
    if FAILURES:
        log("失败项:")
        for f in FAILURES:
            log("  - %s" % f)
        log("RESULT: FAIL")
        return 1
    log("RESULT: PASS" + (" (含 %d 项 SKIP)" % len(SKIPS) if SKIPS else ""))
    return 0


def edge_case_checks(work, zstd):
    """
    附加边界用例（真实 Debian rootfs 一定会碰到，故值得覆盖）：
      a. 超长 path（>100 字符 → tar 必须走 GNU 长名条目）
      b. UTF-8 文件名
      c. path 含制表符 → manifest 必须报错退出，且不留半成品
    """
    d = os.path.join(work, "edge")
    long_dir = "d" * 60
    long_name = "f" * 70 + ".txt"
    rel = "usr/share/doc/%s/%s" % (long_dir, long_name)
    utf8_rel = "usr/share/doc/\u4e2d\u6587\u8bf4\u660e.txt"
    base = os.path.join(d, "base")
    new = os.path.join(d, "new")
    wb(base, rel, b"long v1\n")
    wb(base, utf8_rel, b"utf8 v1\n")
    shutil.copytree(base, new, symlinks=True)
    wb(new, rel, b"long v2\n")  # 内容变 → 必须进补丁
    wb(new, utf8_rel, b"utf8 v2\n")  # 内容变 → 必须进补丁

    bm = os.path.join(d, "base.txt")
    nm = os.path.join(d, "new.txt")
    rc, out, err = run_tool(["manifest", "--root", base, "--out", bm])
    if rc != 0:
        check(False, "边界用例：生成 base 清单失败", err)
        return
    lines = manifest_parts(read_bytes(bm))[1]
    long_path = ("./" + rel).encode("utf-8")
    utf8_path = ("./" + utf8_rel).encode("utf-8")
    check(any(l.split(b"\t")[4] == long_path for l in lines),
          "超长 path（%d 字符）能进清单" % len(long_path))
    check(any(l.split(b"\t")[4] == utf8_path for l in lines), "UTF-8 文件名能进清单")

    rc, out, err = run_tool(["manifest", "--root", new, "--out", nm])
    if rc != 0:
        check(False, "边界用例：生成 new 清单失败", err)
        return
    patch = os.path.join(d, "patch.tar.zst")
    rc, out, err = run_tool(["patch", "--root", new, "--base", bm, "--new", nm,
                             "--out", patch, "--zstd", zstd])
    if rc == 0:
        info, tar_path, names = read_patch(patch, zstd)
        try:
            norm = [n.rstrip("/") for n in names]
            check(("./" + rel) in norm, "补丁含超长路径成员（tar 走 GNU 长名条目）")
            check(("./" + utf8_rel) in norm, "补丁含 UTF-8 文件名成员")
            check(len(norm) == 3, "补丁成员数 = 3（info + 2 个变更文件），实际 %d" % len(norm))
        finally:
            force_remove(tar_path)
        force_remove(patch)

    # c. 含制表符的 path
    tab_dir = os.path.join(d, "tab")
    os.makedirs(tab_dir, exist_ok=True)
    made = False
    try:
        write_bytes(os.path.join(tab_dir, "bad\tname.txt"), b"x")
        made = True
    except OSError as e:
        skip("本机不允许创建含制表符的文件名（%s），跳过「path 含制表符必须报错」用例" % type(e).__name__)
        tool_validator_checks()
    if made:
        out_m = os.path.join(d, "tab-manifest.txt")
        rc, out, err = run_tool(["manifest", "--root", tab_dir, "--out", out_m], expect_ok=False)
        check(rc != 0, "path 含制表符时 manifest 非零退出（rc=%d）" % rc)
        check("非法空白" in (err + out), "报错信息说明了非法空白字符", (err + out).strip())
        check(not os.path.exists(out_m), "报错后没有留下半成品清单文件")

    # d. 出错时一律不留半成品文件（用不存在的 root 触发）
    out_m2 = os.path.join(d, "never-written.txt")
    rc, out, err = run_tool(["manifest", "--root", os.path.join(d, "no-such-tree"), "--out", out_m2],
                            expect_ok=False)
    check(rc != 0, "root 不存在时 manifest 非零退出（rc=%d）" % rc)
    check(not os.path.exists(out_m2), "root 不存在时没有留下半成品清单文件")


def tool_validator_checks():
    """本机不能建含制表符/换行的文件名时，直接对 scan_tree 用的校验函数做单元级断言。"""
    import importlib.util

    spec = importlib.util.spec_from_file_location("zd_manifest_under_test", TOOL)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    for bad, label in (("usr/bin/bad\tname", "制表符"), ("usr/bin/bad\nname", "换行")):
        try:
            mod.check_text_field(bad, "path")
            died, msg = False, ""
        except mod.ToolError as e:
            died, msg = True, str(e)
        except Exception as e:  # 非预期异常也算「报错了」，但信息会打印出来
            died, msg = True, "%s: %s" % (type(e).__name__, e)
        check(died and "非法空白" in msg, "path 含%s时 scan_tree 的校验直接报错（函数级）" % label, msg)


def payload_expected_full(can_symlink):
    """补丁载荷的完整期望（按 path 排序前）。"""
    exp = ["./etc/new2.conf", "./usr/bin/hello", "./usr/bin/new1", "./usr/share/doc/readme.txt"]
    exp.append("./usr/bin/tool")  # mode 变了（两种情况都变）
    if can_symlink:
        exp.append("./usr/bin/link")  # 链接目标变了
    return exp


def synth_symlink_check(work, zstd, base_manifest):
    """本机不能建软链时：用合成清单覆盖「软链进补丁」的代码路径。"""
    log("      .. 改用合成清单覆盖软链代码路径")
    d = os.path.join(work, "synth")
    os.makedirs(d, exist_ok=True)
    root = os.path.join(d, "root")
    os.makedirs(root, exist_ok=True)
    src_dir = os.path.join(root, "usr", "bin")
    os.makedirs(src_dir, exist_ok=True)
    write_bytes(os.path.join(src_dir, "real"), b"payload\n")

    def mk_manifest(path, env_tag, target):
        body = (
            "f\t0644\t8\t%s\t./usr/bin/real\n"
            "l\t0777\t0\t%s\t./usr/bin/link\n" % (sha256_bytes(b"payload\n"), target)
        )
        head = "# zhengdao-rootfs-manifest v1\n# env=%s\n# distro=debian-13.7\n" % env_tag
        write_bytes(path, (head + body).encode("utf-8"))

    base_m = os.path.join(d, "base.txt")
    new_m = os.path.join(d, "new.txt")
    mk_manifest(base_m, "0" * 16, "real")
    mk_manifest(new_m, "1" * 16, "real2")
    out = os.path.join(d, "patch.tar.zst")
    rc, so, se = run_tool(["patch", "--root", root, "--base", base_m, "--new", new_m,
                           "--out", out, "--zstd", zstd])
    if rc != 0:
        check(False, "合成清单的补丁生成失败", se)
        return
    check(True, "合成清单：补丁生成成功（l 成员即使本机不能建软链也能打包）")
    try:
        info, tar_path, names = read_patch(out, zstd)
        try:
            with tarfile.open(tar_path, "r:") as tf:
                lm = [m for m in tf.getmembers() if m.name.rstrip("/") == "./usr/bin/link"]
                check(bool(lm) and lm[0].issym() and lm[0].linkname == "real2",
                      "合成清单：补丁里 ./usr/bin/link 是 SYMTYPE、目标=real2")
                check([m.name.rstrip("/") for m in tf.getmembers()] ==
                      ["./" + PATCH_INFO_BASENAME, "./usr/bin/link"],
                      "合成清单：只有变更的软链成员进补丁（未变的 ./usr/bin/real 不进）")
        finally:
            force_remove(tar_path)
    finally:
        force_remove(out)


if __name__ == "__main__":
    sys.exit(main())
