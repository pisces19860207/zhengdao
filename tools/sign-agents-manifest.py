#!/usr/bin/env python3
# 独立开发声明：本脚本为本项目从零编写。
# 用途：对 rootfs/agents.json 做 ed25519 签名（M3 manifest 验签链）。
# 私钥位置（仓库外）：C:/Users/guoli/.zhengdao-keys/agents-manifest.ed25519.key
# 用法：python tools/sign-agents-manifest.py   （在仓库根目录执行）
# 签名算法：Ed25519（RFC 8032），对 agents.json 原始字节签名，输出 base64 到 agents.json.sig
import base64
import io
import os
import sys

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import load_pem_private_key, Encoding, PrivateFormat, NoEncryption

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MANIFEST = os.path.join(REPO, "rootfs", "agents.json")
SIG = MANIFEST + ".sig"
KEY = r"C:/Users/guoli/.zhengdao-keys/agents-manifest.ed25519.key"


def main() -> int:
    if not os.path.isfile(KEY):
        print(f"未找到私钥：{KEY}", file=sys.stderr)
        print("签名必须在持有私钥的机器上进行；密钥由首次生成脚本保管在仓库外。", file=sys.stderr)
        return 1
    raw = io.open(MANIFEST, "rb").read()
    pem = open(KEY, "rb").read()
    key = load_pem_private_key(pem, password=None)
    assert isinstance(key, Ed25519PrivateKey), "密钥类型不符（应为 Ed25519）"
    sig = key.sign(raw)
    io.open(SIG, "wb").write(base64.b64encode(sig))
    print(f"已签名 {os.path.relpath(MANIFEST, REPO)} -> {os.path.relpath(SIG, REPO)}（{len(sig)} 字节）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
