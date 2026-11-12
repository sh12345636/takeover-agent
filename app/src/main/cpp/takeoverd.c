/*
 * takeoverd.c — 系统级多指注入 daemon（uinput 通道，后端 A/B 共用）
 *
 * 设计要点：
 *  1. root 窗口内由 RootProvider 以 `setsid` 拉起，随后脱离 su 会话常驻；
 *  2. 启动时 open("/dev/uinput") 并 UI_DEV_CREATE —— 权限只在 open 时校验一次，
 *     之后临时 root 失效/会话撤销不影响写入（"root 是门票不是电源"）；
 *  3. Unix domain socket 收指令（文本协议，便于调试与跨语言接入）：
 *        i <w> <h>                 初始化：设置触摸屏尺寸
 *        d <slot> <x> <y>          按下（slot 0..9）
 *        m <slot> <x> <y>          移动
 *        u <slot>                  抬起
 *        q                          退出
 *     每条指令后立即 SYN_REPORT；组合手势由 Gateway 侧按序下发。
 *  4. 事件时间戳用 CLOCK_MONOTONIC（与 SystemClock.uptimeMillis 同源）——
 *     填错时钟域会被 Android InputReader 判为异常事件丢弃。
 *
 * 兼容性：设备名伪装成真实触摸屏（fts_ts），规避 uinput 设备名检测；
 *         无 ABS_MT_TRACKING_ID 支持的内核自动回退单点。
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/ioctl.h>
#include <linux/uinput.h>
#include <linux/input.h>
#include <time.h>

#define MAX_SLOT   10
#define DEF_W      1080
#define DEF_H      2400
#define SOCK_PATH "/data/local/tmp/takeover/ud.sock"

static int g_ufd = -1;
static int g_w = DEF_W, g_h = DEF_H;
static int g_tracking[MAX_SLOT];          /* slot -> tracking id, -1 空闲 */
static volatile sig_atomic_t g_run = 1;

static void on_term(int sig) { (void)sig; g_run = 0; }

/* CLOCK_MONOTONIC → struct timeval（uinput 事件时间戳） */
static void mono_timeval(struct timeval *tv) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    tv->tv_sec  = ts.tv_sec;
    tv->tv_usec = ts.tv_nsec / 1000;
}

static void emit(int type, int code, int val) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    mono_timeval(&ev.time);
    ev.type  = (unsigned short)type;
    ev.code  = (unsigned short)code;
    ev.value = val;
    if (write(g_ufd, &ev, sizeof(ev)) != (ssize_t)sizeof(ev))
        perror("uinput write");
}

static void sync_report(void) { emit(EV_SYN, SYN_REPORT, 0); }

/* 创建伪装触摸屏设备；返回 0 成功 */
static int setup_uinput(void) {
    g_ufd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (g_ufd < 0) { perror("open /dev/uinput"); return -1; }

    ioctl(g_ufd, UI_SET_EVBIT,  EV_KEY);
    ioctl(g_ufd, UI_SET_EVBIT,  EV_ABS);
    ioctl(g_ufd, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(g_ufd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(g_ufd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    ioctl(g_ufd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    ioctl(g_ufd, UI_SET_ABSBIT, ABS_MT_SLOT);

    struct uinput_setup usetup;
    memset(&usetup, 0, sizeof(usetup));
    usetup.id.bustype = BUS_USB;
    usetup.id.vendor  = 0x048d;            /* 伪装成真实触控 IC 厂商 */
    usetup.id.product = 0x8870;
    snprintf(usetup.name, UINPUT_MAX_NAME_SIZE, "fts_ts");  /* 设备名伪装 */
    if (ioctl(g_ufd, UI_DEV_SETUP, &usetup) < 0) { perror("UI_DEV_SETUP"); return -1; }

    /* 触摸范围 = 屏幕尺寸（尺寸由 Gateway 经 i 指令下发，这里先设默认） */
    struct uinput_abs_setup abs;
    memset(&abs, 0, sizeof(abs));
    abs.code = ABS_MT_POSITION_X; abs.absinfo.maximum = g_w - 1; abs.absinfo.fuzz = 0;
    ioctl(g_ufd, UI_ABS_SETUP, &abs);
    abs.code = ABS_MT_POSITION_Y; abs.absinfo.maximum = g_h - 1;
    ioctl(g_ufd, UI_ABS_SETUP, &abs);
    abs.code = ABS_MT_SLOT; abs.absinfo.maximum = MAX_SLOT - 1;
    ioctl(g_ufd, UI_ABS_SETUP, &abs);
    abs.code = ABS_MT_TRACKING_ID; abs.absinfo.maximum = 0xffff;
    ioctl(g_ufd, UI_ABS_SETUP, &abs);

    if (ioctl(g_ufd, UI_DEV_CREATE) < 0) { perror("UI_DEV_CREATE"); return -1; }
    usleep(100 * 1000);                    /* 等 InputReader 枚举完成 */
    for (int i = 0; i < MAX_SLOT; i++) g_tracking[i] = -1;
    return 0;
}

static void do_down(int slot, int x, int y) {
    if (slot < 0 || slot >= MAX_SLOT) return;
    if (g_tracking[slot] == -1) {
        static int next_id = 100;
        g_tracking[slot] = next_id++;
        emit(EV_ABS, ABS_MT_SLOT, slot);
        emit(EV_ABS, ABS_MT_TRACKING_ID, g_tracking[slot]);
        emit(EV_KEY, BTN_TOUCH, 1);
    }
    emit(EV_ABS, ABS_MT_POSITION_X, x);
    emit(EV_ABS, ABS_MT_POSITION_Y, y);
    sync_report();
}

static void do_move(int slot, int x, int y) {
    if (slot < 0 || slot >= MAX_SLOT || g_tracking[slot] == -1) return;
    emit(EV_ABS, ABS_MT_SLOT, slot);
    emit(EV_ABS, ABS_MT_POSITION_X, x);
    emit(EV_ABS, ABS_MT_POSITION_Y, y);
    sync_report();
}

static void do_up(int slot) {
    if (slot < 0 || slot >= MAX_SLOT || g_tracking[slot] == -1) return;
    emit(EV_ABS, ABS_MT_SLOT, slot);
    emit(EV_ABS, ABS_MT_TRACKING_ID, -1);   /* -1 = 抬起该触点 */
    /* 所有 slot 都空才松 BTN_TOUCH */
    int any = 0;
    for (int i = 0; i < MAX_SLOT; i++) if (g_tracking[i] != -1) any = 1;
    if (!any) emit(EV_KEY, BTN_TOUCH, 0);
    g_tracking[slot] = -1;
    sync_report();
}

/* 指令循环：一行一条，空格分隔 */
static void serve(int cfd) {
    char buf[256];
    ssize_t n;
    while (g_run && (n = read(cfd, buf, sizeof(buf) - 1)) > 0) {
        buf[n] = '\0';
        char *save = NULL;
        char *cmd = strtok_r(buf, " \t\n", &save);
        if (!cmd) continue;
        if (strcmp(cmd, "i") == 0) {
            char *a1 = strtok_r(NULL, " \t\n", &save);
            char *a2 = strtok_r(NULL, " \t\n", &save);
            if (a1 && a2) { g_w = atoi(a1); g_h = atoi(a2); }
        } else if (strcmp(cmd, "d") == 0) {
            char *s = strtok_r(NULL, " \t\n", &save);
            char *x = strtok_r(NULL, " \t\n", &save);
            char *y = strtok_r(NULL, " \t\n", &save);
            if (s && x && y) do_down(atoi(s), atoi(x), atoi(y));
        } else if (strcmp(cmd, "m") == 0) {
            char *s = strtok_r(NULL, " \t\n", &save);
            char *x = strtok_r(NULL, " \t\n", &save);
            char *y = strtok_r(NULL, " \t\n", &save);
            if (s && x && y) do_move(atoi(s), atoi(x), atoi(y));
        } else if (strcmp(cmd, "u") == 0) {
            char *s = strtok_r(NULL, " \t\n", &save);
            if (s) do_up(atoi(s));
        } else if (strcmp(cmd, "q") == 0) {
            break;
        }
    }
    close(cfd);
}

int main(void) {
    signal(SIGTERM, on_term);
    signal(SIGINT,  on_term);
    signal(SIGPIPE, SIG_IGN);

    if (setup_uinput() != 0) return 1;      /* 失败 = capability uinput:false，走降级链 */

    unlink(SOCK_PATH);
    int lfd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (lfd < 0) { perror("socket"); return 1; }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", SOCK_PATH);
    if (bind(lfd, (struct sockaddr *)&addr, sizeof(addr)) < 0) { perror("bind"); return 1; }
    chmod(SOCK_PATH, 0660);
    if (listen(lfd, 4) < 0) { perror("listen"); return 1; }

    fprintf(stderr, "takeoverd up: sock=%s screen=%dx%d\n", SOCK_PATH, g_w, g_h);

    while (g_run) {
        int cfd = accept(lfd, NULL, NULL);
        if (cfd < 0) {
            if (errno == EINTR) continue;
            perror("accept"); break;
        }
        serve(cfd);                          /* 单连接串行：保证多指时序不错乱 */
    }

    ioctl(g_ufd, UI_DEV_DESTROY);
    close(g_ufd);
    unlink(SOCK_PATH);
    return 0;
}
