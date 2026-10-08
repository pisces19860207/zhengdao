#!/usr/bin/env python3
# 独立开发声明：本脚本为本项目从零编写。
#
# 用途：对**环境包索引** `rootfs-index.json` 做 Ed25519 签名，产出同名 `.sig`（base64）。
# 背景：`rootfs/agents.json` 自 v1.x 起就有 Ed25519 签名（App 侧先验签后解析），
#       但环境包这条链——`rootfs-index.json` → 完整包 sha256 / 差分包 sha256——**一个签名都没有**。
#       sha256 与被校验的包放在同一个 Release 里，**能改包的人也能顺手改哈希**，
#       App 端因此无法察觉"发布端被篡改"。本脚本补的就是这一层。
#
# 为什么是**纯标准库**实现 Ed25519（不用 cryptography）：
#   ① CI（ubuntu-latest）不保证预装 cryptography，而本脚本必须在 CI 里跑；
#   ② `tools/rootfs-manifest.py` 等既有工具本来就是纯标准库（压缩走 subprocess 调 zstd），
#      不引入第三方依赖是本仓库的既定风格；
#   ③ App 侧的验签同样是**从零实现**（ui/AgentManifest.kt，理由：AndroidKeyStore 拒收外部公钥），
#      两边都自己实现，行为可对照。
#
# 用法：
#   # 1) 生成密钥对（私钥写仓库外，公钥打印出来嵌进 App）
#   python tools/sign-rootfs-index.py --genkey
#
#   # 2) 签索引（本地；私钥在仓库外）
#   python tools/sign-rootfs-index.py [索引路径]
#
#   # 3) CI 里签（私钥来自 GitHub Secret，不落盘）
#   ROOTFS_INDEX_SIGNING_KEY_PEM="$(cat key.pem)" python tools/sign-rootfs-index.py rootfs-out/rootfs-index.json
#
# ⚠️ Secret 名字只有一个事实来源：`.github/workflows/build.yml` 的
#   `secrets.ROOTFS_INDEX_SIGNING_KEY_PEM`。本脚本 2026-10-08 首版把名字写成
#   `ZHENGDAO_INDEX_SIGNING_KEY_PEM`（与 agents.json 那条链的命名习惯串了），
#   照着脚本提示去建 secret 的话工作流读不到、签名步骤会硬失败 —— 已统一。
#   （旧名仍被读取以兼容本地既有环境变量，但请以 ROOTFS_ 为准。）
#
# 签名口径（与 agents.json.sig 保持一致）：
#   对索引文件的**原始字节**签名，输出 base64（88 字符，含换行）。App 侧必须
#   **按原始字节验签**，不能先 trim 再验（裁掉末尾换行即恒败 —— 2026-10-04 实测踩过）。
import argparse
import base64
import hashlib
import os
import sys

# ── Ed25519（RFC 8032）常量 ────────────────────────────────────────────────
P = 2 ** 255 - 19
L = 2 ** 252 + 27742317777372353535851937790883648493
D = (-121665 * pow(121666, P - 2, P)) % P
# 基点 B 的 x / y（RFC 8032 §5.1）
BX = 15112221349535400772501151409588531511454012693041857206046113283949847762202
BY = 46316835694926478169428394003475163141307993866256225615783033603165251855960


def _add(p1, p2):
    """曲线加法（仿射坐标，模 p）。"""
    x1, y1 = p1
    x2, y2 = p2
    t = (D * x1 * x2 * y1 * y2) % P
    x3 = (x1 * y2 + x2 * y1) * pow(1 + t, P - 2, P) % P
    y3 = (y1 * y2 + x1 * x2) * pow(1 - t, P - 2, P) % P
    return x3, y3


def _mul(s, point):
    """标量乘（双标量用的朴素实现；单次签名只需几十次，足够快）。"""
    q = (0, 1)  # 单位元
    while s > 0:
        if s & 1:
            q = _add(q, point)
        point = _add(point, point)
        s >>= 1
    return q


def _encode_point(point):
    x, y = point
    raw = bytearray(y.to_bytes(32, "little"))
    if x & 1:
        raw[31] |= 0x80
    return bytes(raw)


def _seed_to_public(seed32: bytes) -> bytes:
    h = hashlib.sha512(seed32).digest()
    a = bytearray(h[:32])
    a[0] &= 248
    a[31] &= 127
    a[31] |= 64
    return _encode_point(_mul(int.from_bytes(a, "little"), (BX, BY)))


def sign(seed32: bytes, message: bytes) -> bytes:
    """RFC 8032 §5.1.6 的 Ed25519 签名（纯标准库）。"""
    if len(seed32) != 32:
        raise ValueError("种子必须 32 字节")
    h = hashlib.sha512(seed32).digest()
    a = bytearray(h[:32])
    a[0] &= 248
    a[31] &= 127
    a[31] |= 64
    a_int = int.from_bytes(a, "little")
    prefix = h[32:]
    pub = _encode_point(_mul(a_int, (BX, BY)))
    r = int.from_bytes(hashlib.sha512(prefix + message).digest(), "little") % L
    rr = _encode_point(_mul(r, (BX, BY)))
    k = int.from_bytes(hashlib.sha512(rr + pub + message).digest(), "little") % L
    s = (r + k * a_int) % L
    return rr + s.to_bytes(32, "little")


def _pem_to_seed(pem: str) -> bytes:
    """从 PKCS#8 PEM 私钥里取出 32 字节种子（RFC 8410 的 Ed25519 私钥只有 seed 一个字段）。"""
    body = "".join(
        line.strip() for line in pem.strip().splitlines()
        if line.strip() and not line.startswith("-----")
    )
    der = base64.b64decode(body)
    # PKCS#8: SEQUENCE { INTEGER 0, SEQUENCE{OID}, OCTET STRING { OCTET STRING seed } }
    # 这里不引入 ASN.1 解析器，只做定位：最后一个 32 字节子结构就是 seed。
    if len(der) < 32:
        raise ValueError("PEM 内容过短，不是 Ed25519 私钥")
    seed = der[-32:]
    # 结构自检：Ed25519 PKCS#8 固定尾巴是 04 20 <32 字节 seed>
    if der[-34:-32] != b"\x04\x20":
        raise ValueError("PEM 不是 Ed25519 PKCS#8 私钥（缺 04 20 标记）")
    return seed


def _seed_to_pem(seed: bytes) -> str:
    """把 32 字节种子包成 PKCS#8 PEM（Ed25519 的 PKCS#8 结构是固定的 48 字节 DER）。

    结构（RFC 8410 §7）：
        SEQUENCE(46) { INTEGER 0, SEQUENCE{ OID 1.3.101.112 }, OCTET STRING(34){ OCTET STRING(32) } }
    纯标准库手拼 DER —— 为了让「生成密钥」这一步也不依赖 cryptography。
    """
    if len(seed) != 32:
        raise ValueError("种子必须 32 字节")
    der = bytes([
        0x30, 0x2E,                      # SEQUENCE, 46 bytes
        0x02, 0x01, 0x00,                 # INTEGER 0（版本）
        0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70,  # SEQUENCE { OID 1.3.101.112 = Ed25519 }
        0x04, 0x22, 0x04, 0x20,           # OCTET STRING(34) { OCTET STRING(32) }
    ]) + seed
    b64 = base64.b64encode(der).decode()
    lines = [b64[i:i + 64] for i in range(0, len(b64), 64)]
    return "-----BEGIN PRIVATE KEY-----\n" + "\n".join(lines) + "\n-----END PRIVATE KEY-----\n"


def main() -> int:
    ap = argparse.ArgumentParser(description="签名环境包索引 rootfs-index.json（Ed25519）")
    ap.add_argument("index", nargs="?", help="索引文件路径（默认 rootfs-out/rootfs-index.json）")
    ap.add_argument("--key", help="私钥 PEM 路径（默认读环境变量 ROOTFS_INDEX_SIGNING_KEY_PEM）")
    ap.add_argument(
        "--genkey",
        action="store_true",
        help="生成新密钥对：私钥写到仓库外 ~/.zhengdao-keys/，公钥打印（供嵌入 App）",
    )
    ap.add_argument(
        "--key-out",
        default=os.path.expanduser("~/.zhengdao-keys/rootfs-index-signing.ed25519.key"),
        help="--genkey 的私钥输出路径",
    )
    args = ap.parse_args()

    repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    if args.genkey:
        # 私钥格式与 tools/sign-agents-manifest.py 一致：PKCS#8 PEM（这里纯标准库手拼 DER）
        seed = os.urandom(32)
        pem = _seed_to_pem(seed)
        os.makedirs(os.path.dirname(args.key_out), exist_ok=True)
        with open(args.key_out, "w", encoding="ascii") as f:
            f.write(pem)
        pub_b64 = base64.b64encode(_seed_to_public(seed)).decode()
        print(f"私钥已写入（仓库外，勿提交）：{args.key_out}")
        print(f"公钥 raw(32B) base64：{pub_b64}")
        print("把公钥填进 app/.../rootfs/RootfsIndex.kt 的 INDEX_SIGNING_PUBKEY_B64，")
        print("并把私钥 PEM 配成 GitHub Secret：ROOTFS_INDEX_SIGNING_KEY_PEM")
        return 0

    index_path = args.index or os.path.join(repo, "rootfs-out", "rootfs-index.json")
    if not os.path.isfile(index_path):
        print(f"[错误] 找不到索引：{index_path}", file=sys.stderr)
        return 1

    if args.key:
        # 读不到就给一句人话，不要把 FileNotFoundError 的堆栈甩给用的人
        try:
            with open(args.key, "rb") as f:
                pem = f.read().decode()
        except OSError as e:
            print(f"[错误] 读不到私钥文件 {args.key}：{e}", file=sys.stderr)
            return 1
    else:
        # 优先级：显式 --key > 环境变量（CI 用） > 仓库外默认路径（本地省事）
        # 环境变量只认 ROOTFS_INDEX_SIGNING_KEY_PEM（与工作流里的 secret 同名）；
        # 旧名 ZHENGDAO_INDEX_SIGNING_KEY_PEM 仅作兼容兜底。
        pem = os.environ.get("ROOTFS_INDEX_SIGNING_KEY_PEM", "")
        if not pem.strip():
            pem = os.environ.get("ZHENGDAO_INDEX_SIGNING_KEY_PEM", "")
        if not pem.strip():
            default_key = os.path.expanduser(
                "~/.zhengdao-keys/rootfs-index-signing.ed25519.key")
            if os.path.isfile(default_key):
                with open(default_key, encoding="ascii") as f:
                    pem = f.read()
    if not pem.strip():
        print(
            "[错误] 没有私钥：给 --key <文件>、设环境变量 ROOTFS_INDEX_SIGNING_KEY_PEM，\n"
            "        或放到 ~/.zhengdao-keys/rootfs-index-signing.ed25519.key（--genkey 会生成）。\n"
            "        CI 里配 GitHub Secret：ROOTFS_INDEX_SIGNING_KEY_PEM（见 .github/workflows/build.yml\n"
            "        的「签名环境包索引」那一步）。",
            file=sys.stderr,
        )
        return 1

    with open(index_path, "rb") as f:
        raw = f.read()
    sig = sign(_pem_to_seed(pem), raw)
    out = index_path + ".sig"
    with open(out, "wb") as f:
        f.write(base64.b64encode(sig))
    print(f"已签名 {os.path.basename(index_path)} -> {os.path.basename(out)}"
          f"（Ed25519，{len(sig)} 字节签名，base64 {len(base64.b64encode(sig))} 字符）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
