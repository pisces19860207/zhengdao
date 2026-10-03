/*
 * 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
 * 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
 *
 * 依据的标准接口（POSIX / Android NDK 官方文档）：
 *   forkpty(3)           —— 打开伪终端主从对，并在子进程中将其设为控制终端
 *   execve(2)            —— 在子进程中加载目标程序
 *   ioctl(2) TIOCSWINSZ  —— 设置/修改终端窗口尺寸
 *   kill(2) / waitpid(2) —— 向子进程发信号并回收
 */
#include <jni.h>
#include <errno.h>
#include <stdio.h>
#include <pty.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static void throw_runtime(JNIEnv *env, const char *msg) {
    jclass cls = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (cls != NULL) {
        (*env)->ThrowNew(env, cls, msg);
    }
}

/* 把 Java 字符串复制成本地内存副本（释放 Java 引用后副本仍然有效） */
static char *jstr_dup(JNIEnv *env, jstring s) {
    if (s == NULL) return NULL;
    const char *p = (*env)->GetStringUTFChars(env, s, NULL);
    if (p == NULL) return NULL;
    char *copy = strdup(p);
    (*env)->ReleaseStringUTFChars(env, s, p);
    return copy;
}

/*
 * 创建伪终端并在子进程中执行程序。
 * 返回值打包：高 32 位 = 子进程 pid，低 32 位 = 主端 fd。
 * 失败返回 -1，并向 Java 层抛出 RuntimeException。
 */
JNIEXPORT jlong JNICALL
Java_com_example_zhengdao_terminal_Pty_nativeCreate(
        JNIEnv *env, jobject thiz,
        jstring j_cmd, jobjectArray j_args, jobjectArray j_env,
        jint cols, jint rows) {

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);

    /* fork 之前把所有 Java 字符串复制成本地副本：
       子进程里不能再调用 JNI，exec 前也不能做不安全的内存分配。 */
    char *cmd = jstr_dup(env, j_cmd);
    jsize argc = (*env)->GetArrayLength(env, j_args);
    jsize envc = (*env)->GetArrayLength(env, j_env);
    char **argv = (char **) calloc((size_t) argc + 2, sizeof(char *));
    char **envp = (char **) calloc((size_t) envc + 1, sizeof(char *));

    if (cmd == NULL || argv == NULL || envp == NULL) {
        free(cmd);
        free(argv);
        free(envp);
        throw_runtime(env, "nativeCreate: out of memory");
        return -1;
    }

    argv[0] = cmd; /* 惯例：argv[0] 是程序路径本身 */
    for (jsize i = 0; i < argc; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, j_args, (jsize) i);
        argv[i + 1] = jstr_dup(env, s);
        if (s != NULL) (*env)->DeleteLocalRef(env, s);
    }
    argv[argc + 1] = NULL;

    for (jsize i = 0; i < envc; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, j_env, (jsize) i);
        envp[i] = jstr_dup(env, s);
        if (s != NULL) (*env)->DeleteLocalRef(env, s);
    }
    envp[envc] = NULL;

    int master = -1;
    pid_t pid = forkpty(&master, NULL, NULL, &ws);
    if (pid < 0) {
        int e = errno;
        /* argv[0] 与 cmd 是同一块内存：从 1 开始释放，cmd 单独释放一次 */
        for (jsize i = 1; i <= argc; i++) free(argv[i]);
        free(argv);
        for (jsize i = 0; i < envc; i++) free(envp[i]);
        free(envp);
        free(cmd);
        char msg[128];
        snprintf(msg, sizeof(msg), "forkpty failed: %s", strerror(e));
        throw_runtime(env, msg);
        return -1;
    }

    if (pid == 0) {
        /* 子进程：execve 成功不返回；失败统一 _exit(127)。
           此处只使用 fork 前准备好的内存，不做任何分配。 */
        execve(cmd, argv, envp);
        _exit(127);
    }

    /* 父进程：释放全部副本（argv[0] 与 cmd 同一块内存，只 free 一次） */
    for (jsize i = 1; i <= argc; i++) free(argv[i]);
    free(argv);
    for (jsize i = 0; i < envc; i++) free(envp[i]);
    free(envp);
    free(cmd);

    return ((jlong) pid << 32) | (jlong) (master & 0x7fffffff);
}

/* 写入数据到伪终端主端。返回写入字节数，-1 = 失败（含对端已关闭）。 */
JNIEXPORT jint JNICALL
Java_com_example_zhengdao_terminal_Pty_nativeWrite(
        JNIEnv *env, jobject thiz, jint fd, jbyteArray j_data, jint len) {
    if (fd < 0 || len <= 0) return 0;
    jbyte *p = (*env)->GetByteArrayElements(env, j_data, NULL);
    if (p == NULL) return -1;
    jint off = 0;
    while (off < len) {
        ssize_t n = write(fd, p + off, (size_t) (len - off));
        if (n < 0) {
            if (errno == EINTR) continue;
            (*env)->ReleaseByteArrayElements(env, j_data, p, JNI_ABORT);
            return -1;
        }
        off += (jint) n;
    }
    (*env)->ReleaseByteArrayElements(env, j_data, p, JNI_ABORT);
    return off;
}

/*
 * 从伪终端主端读数据（阻塞式，由 Java 读取线程循环调用）。
 * 返回读取字节数；0 = 对端关闭（子进程退出时 Linux 返回 EIO，映射为 0）；-1 = 其他错误。
 */
JNIEXPORT jint JNICALL
Java_com_example_zhengdao_terminal_Pty_nativeRead(
        JNIEnv *env, jobject thiz, jint fd, jbyteArray j_buf) {
    if (fd < 0) return -1;
    jsize cap = (*env)->GetArrayLength(env, j_buf);
    jbyte *p = (*env)->GetByteArrayElements(env, j_buf, NULL);
    if (p == NULL) return -1;
    ssize_t n;
    do {
        n = read(fd, p, (size_t) cap);
    } while (n < 0 && errno == EINTR);
    (*env)->ReleaseByteArrayElements(env, j_buf, p, 0);
    if (n < 0) {
        if (errno == EIO) return 0;
        return -1;
    }
    return (jint) n;
}

/* 修改终端窗口尺寸，并向子进程转发 SIGWINCH（全屏程序如 vim 依赖它重绘） */
JNIEXPORT void JNICALL
Java_com_example_zhengdao_terminal_Pty_nativeResize(
        JNIEnv *env, jobject thiz, jint fd, jint pid, jint cols, jint rows) {
    if (fd < 0) return;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ioctl(fd, TIOCSWINSZ, &ws);
    if (pid > 0) kill(pid, SIGWINCH);
}

JNIEXPORT void JNICALL
Java_com_example_zhengdao_terminal_Pty_nativeClose(
        JNIEnv *env, jobject thiz, jint fd) {
    if (fd >= 0) close(fd);
}

/*
 * 结束会话：forkpty 内部会 setsid，子进程即进程组长，
 * 因此 kill(-pid) 可覆盖它派生的全部子进程；waitpid 回收避免僵尸进程。
 */
JNIEXPORT void JNICALL
Java_com_example_zhengdao_terminal_Pty_nativeKill(
        JNIEnv *env, jobject thiz, jint pid) {
    if (pid <= 0) return;
    kill(-pid, SIGKILL);
    kill(pid, SIGKILL);
    waitpid(pid, NULL, 0);
}
