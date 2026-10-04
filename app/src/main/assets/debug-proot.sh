#!/system/bin/sh
D=/data/user/0/com.example.zhengdao/files
cd "$D"
export PROOT_TMP_DIR=$D/proot-tmp HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=xterm-256color LANG=C.UTF-8 UV_LINK_MODE=copy UV_PYTHON=/usr/bin/python3
{
echo "T1: proot --version（无追踪）"
files/proot/proot --version >/dev/null 2>&1; echo "T1 exit=$?"
echo "T2: 最小参数 /bin/true"
files/proot/proot -r $D/rootfs -0 /bin/true 2>&1 | tail -2; echo "T2 exit=$?"
echo "T3: +binds /bin/true"
files/proot/proot -r $D/rootfs -0 -b /dev -b /proc -b /sys /bin/true 2>&1 | tail -2; echo "T3 exit=$?"
echo "T4: App 完整参数 /bin/true"
files/proot/proot --kill-on-exit --link2symlink -r $D/rootfs -0 -w /root -b /dev -b /proc -b /sys -b $D/home:/root /bin/true 2>&1 | tail -2; echo "T4 exit=$?"
echo "T5: 完整参数 bash -c"
files/proot/proot --kill-on-exit --link2symlink -r $D/rootfs -0 -w /root -b /dev -b /proc -b /sys -b $D/home:/root /bin/bash -c "echo T5_BODY" 2>&1 | tail -2; echo "T5 exit=$?"
echo "T6: 完整参数 bash -l -c"
files/proot/proot --kill-on-exit --link2symlink -r $D/rootfs -0 -w /root -b /dev -b /proc -b /sys -b $D/home:/root /bin/bash -l -c "echo T6_BODY" 2>&1 | tail -2; echo "T6 exit=$?"
echo "T7: 经 sh 中转 bash -c"
/system/bin/sh -c "files/proot/proot --kill-on-exit --link2symlink -r $D/rootfs -0 -w /root -b /dev -b /proc -b /sys -b $D/home:/root /bin/bash -c 'echo T7_BODY'" 2>&1 | tail -2; echo "T7 exit=$?"
echo "ALL_DONE"
} > $D/debug-result.txt 2>&1
