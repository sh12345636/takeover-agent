/*
 * takeoverd.c — 系统级多指注入 daemon + root 网关（P2）
 *
 * 传输：TCP 127.0.0.1:17353（普通 app 可直连，零隐藏 API、零 su 依赖）
 * 协议：行文本指令，每条回一行应答（O=ok / P=pong / X<len>\n<len字节>=执行结果）
 *   i <w> <h>        设置触摸屏尺寸
 *   d <slot> <x> <y> 按下    m <slot> <x> <y> 移动    u <slot> 抬起
 *   p                探测（回 P）
 *   x <shell cmd>    root 代执行（popen，输出尾行附加 __RC__<code>）→ X<len>\n<len字节>
 *   q                断开
 *
 * 安全边界：仅监听 loopback；root 权限由"启动它的那一次 su"授予，
 *           daemon 常驻后 app 不再需要 su（"root 是门票不是电源"）。
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <linux/uinput.h>
#include <linux/input.h>
#include <time.h>

#define MAX_SLOT   10
#define DEF_W      1080
#define DEF_H      2400
#define PORT       17353
#define MAX_RESP   (64 * 1024)

static int g_ufd = -1;
static int g_w = DEF_W, g_h = DEF_H;
static int g_tracking[MAX_SLOT];
static volatile sig_atomic_t g_run = 1;
static pthread_mutex_t g_uinput_lock = PTHREAD_MUTEX_INITIALIZER;

static void on_term(int sig) { (void)sig; g_run = 0; }

static void mono_timeval(struct timeval *tv) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    tv->tv_sec = ts.tv_sec;
    tv->tv_usec = ts.tv_nsec / 1000;
}

/* uinput 写入加锁：多连接并发时保持事件序列原子 */
static int emit(int type, int code, int val) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    mono_timeval(&ev.time);
    ev.type = (unsigned short)type;
    ev.code = (unsigned short)code;
    ev.value = val;
    pthread_mutex_lock(&g_uinput_lock);
    ssize_t r = write(g_ufd, &ev, sizeof(ev));
    pthread_mutex_unlock(&g_uinput_lock);
    return r == (ssize_t)sizeof(ev) ? 0 : -1;
}
static void sync_report(void) { emit(EV_SYN, SYN_REPORT, 0); }

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
    usetup.id.vendor  = 0x048d;
    usetup.id.product = 0x8870;
    snprintf(usetup.name, UINPUT_MAX_NAME_SIZE, "fts_ts");
    if (ioctl(g_ufd, UI_DEV_SETUP, &usetup) < 0) { perror("UI_DEV_SETUP"); return -1; }

    struct uinput_abs_setup abs;
    memset(&abs, 0, sizeof(abs));
    abs.code = ABS_MT_POSITION_X; abs.absinfo.maximum = g_w - 1;
    ioctl(g_ufd, UI_ABS_SETUP, &abs);
    abs.code = ABS_MT_POSITION_Y; abs.absinfo.maximum = g_h - 1;
    ioctl(g_ufd, UI_ABS_SETUP, &abs);
    abs.code = ABS_MT_SLOT; abs.absinfo.maximum = MAX_SLOT - 1;
    ioctl(g_ufd, UI_ABS_SETUP, &abs);
    abs.code = ABS_MT_TRACKING_ID; abs.absinfo.maximum = 0xffff;
    ioctl(g_ufd, UI_ABS_SETUP, &abs);

    if (ioctl(g_ufd, UI_DEV_CREATE) < 0) { perror("UI_DEV_CREATE"); return -1; }
    usleep(100 * 1000);
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
    emit(EV_ABS, ABS_MT_TRACKING_ID, -1);
    int any = 0;
    for (int i = 0; i < MAX_SLOT; i++) if (g_tracking[i] != -1) any = 1;
    if (!any) emit(EV_KEY, BTN_TOUCH, 0);
    g_tracking[slot] = -1;
    sync_report();
}

/* root 代执行：popen，末尾附加 __RC__<code> 供客户端解析 */
static int exec_cmd(const char *cmd, char *out, size_t outsz, size_t *olen) {
    char wrapped[8192];
    snprintf(wrapped, sizeof(wrapped), "%s; echo __RC__$?", cmd);
    FILE *fp = popen(wrapped, "r");
    if (!fp) { *olen = 0; return -1; }
    size_t total = 0;
    char buf[1024];
    while (fgets(buf, sizeof(buf), fp)) {
        size_t n = strlen(buf);
        if (total + n >= outsz - 1) break;
        memcpy(out + total, buf, n);
        total += n;
    }
    out[total] = '\0';
    *olen = total;
    int st = pclose(fp);
    return WIFEXITED(st) ? 0 : -1;
}

static void handle_line(int cfd, char *line) {
    /* 去尾部 \r */
    size_t len = strlen(line);
    while (len && (line[len-1] == '\r' || line[len-1] == '\n')) line[--len] = 0;
    if (!len) return;

    char cmd = line[0];
    char resp[256];

    if (cmd == 'q') { write(cfd, "O\n", 2); return; }

    if (cmd == 'p') { write(cfd, "P\n", 2); return; }

    if (cmd == 'i') {
        int w = 0, h = 0;
        sscanf(line + 1, "%d %d", &w, &h);
        if (w > 0 && h > 0) { g_w = w; g_h = h; }
        write(cfd, "O\n", 2); return;
    }
    if (cmd == 'd') {
        int s, x, y; if (sscanf(line + 1, "%d %d %d", &s, &x, &y) == 3) do_down(s, x, y);
        write(cfd, "O\n", 2); return;
    }
    if (cmd == 'm') {
        int s, x, y; if (sscanf(line + 1, "%d %d %d", &s, &x, &y) == 3) do_move(s, x, y);
        write(cfd, "O\n", 2); return;
    }
    if (cmd == 'u') {
        int s; if (sscanf(line + 1, "%d", &s) == 1) do_up(s);
        write(cfd, "O\n", 2); return;
    }
    if (cmd == 'x') {
        const char *c = line + 1;
        while (*c == ' ') c++;
        if (!*c) { write(cfd, "E\n", 2); return; }
        char *out = malloc(MAX_RESP);
        if (!out) { write(cfd, "E\n", 2); return; }
        size_t olen = 0;
        exec_cmd(c, out, MAX_RESP, &olen);
        int n = snprintf(resp, sizeof(resp), "X%zu\n", olen);
        write(cfd, resp, n);
        if (olen) write(cfd, out, olen);
        free(out);
        return;
    }
    write(cfd, "E\n", 2);
}

static void *serve_thread(void *arg) {
    int cfd = (int)(intptr_t)arg;
    char acc[4096];
    size_t alen = 0;
    ssize_t n;
    while (g_run && (n = read(cfd, acc + alen, sizeof(acc) - 1 - alen)) > 0) {
        alen += (size_t)n;
        acc[alen] = '\0';
        char *start = acc;
        char *nl;
        while ((nl = strchr(start, '\n')) != NULL) {
            *nl = '\0';
            handle_line(cfd, start);
            start = nl + 1;
        }
        alen = strlen(start);
        if (alen) memmove(acc, start, alen);
        if (alen >= sizeof(acc) - 1) alen = 0;   /* 防御：超长无换行丢弃 */
    }
    close(cfd);
    return NULL;
}

int main(void) {
    signal(SIGTERM, on_term);
    signal(SIGINT,  on_term);
    signal(SIGPIPE, SIG_IGN);

    if (setup_uinput() != 0) return 1;

    int lfd = socket(AF_INET, SOCK_STREAM, 0);
    if (lfd < 0) { perror("socket"); return 1; }
    int one = 1;
    setsockopt(lfd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(PORT);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);   /* 仅 loopback */
    if (bind(lfd, (struct sockaddr *)&addr, sizeof(addr)) < 0) { perror("bind"); return 1; }
    if (listen(lfd, 8) < 0) { perror("listen"); return 1; }

    fprintf(stderr, "takeoverd up: tcp 127.0.0.1:%d screen=%dx%d\n", PORT, g_w, g_h);

    while (g_run) {
        struct sockaddr_in cli;
        socklen_t cl = sizeof(cli);
        int cfd = accept(lfd, (struct sockaddr *)&cli, &cl);
        if (cfd < 0) {
            if (errno == EINTR) continue;
            if (!g_run) break;
            perror("accept"); continue;
        }
        pthread_t th;
        if (pthread_create(&th, NULL, serve_thread, (void *)(intptr_t)cfd) == 0)
            pthread_detach(th);
        else close(cfd);
    }

    ioctl(g_ufd, UI_DEV_DESTROY);
    close(g_ufd);
    return 0;
}
