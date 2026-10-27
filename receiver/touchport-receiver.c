// touchport-receiver: turns TouchPort phone packets into a virtual keyboard and mouse (Linux uinput).
//
// Transports, all on port 47800:
//   UDP 0.0.0.0     Wi-Fi and USB tethering. Also answers discovery broadcasts.
//   TCP 127.0.0.1   USB with ADB ("adb reverse tcp:47800 tcp:47800", done automatically).
//
// Every packet carries the full state (all held keys + cumulative mouse counters), so a lost
// or late UDP packet never leaves a key stuck and never loses mouse motion.

#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/uinput.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <signal.h>
#include <spawn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#define PORT 47800
#define PKT_SIZE 32
#define REPLY_SIZE 64
#define PROTO_VERSION 1
#define RELEASE_TIMEOUT_MS 500   // no packet for this long: release everything
#define ADB_RETRY_MS 3000

enum { T_STATE = 1, T_DISCOVER = 2, T_DISCOVER_REPLY = 3, T_BYE = 4, T_PING = 5, T_PONG = 6 };

// Bit index -> Linux key code. Must match Keys.kt in the Android app.
static const int KEYMAP[64] = {
    [0] = KEY_W,         [1] = KEY_A,          [2] = KEY_S,          [3] = KEY_D,
    [4] = KEY_SPACE,     [5] = KEY_LEFTSHIFT,  [6] = KEY_LEFTCTRL,   [7] = KEY_C,
    [8] = KEY_E,         [9] = KEY_F,          [10] = KEY_Q,         [11] = KEY_R,
    [12] = KEY_V,        [13] = KEY_G,         [14] = KEY_H,         [15] = KEY_T,
    [16] = KEY_B,        [17] = KEY_Z,         [18] = KEY_TAB,       [19] = KEY_ESC,
    [20] = KEY_1,        [21] = KEY_2,         [22] = KEY_3,         [23] = KEY_4,
    [24] = KEY_5,        [25] = KEY_J,         [26] = KEY_K,         [27] = KEY_P,
    [28] = KEY_M,        [29] = KEY_X,         [30] = KEY_ENTER,     [31] = KEY_LEFTALT,
    [32] = BTN_LEFT,     [33] = BTN_RIGHT,     [34] = BTN_MIDDLE,    [35] = BTN_SIDE,
    [36] = BTN_EXTRA,    [37] = KEY_N,         [38] = KEY_Y,         [39] = KEY_U,
    [40] = KEY_I,        [41] = KEY_O,         [42] = KEY_L,         [43] = KEY_6,
    [44] = KEY_7,        [45] = KEY_8,         [46] = KEY_9,         [47] = KEY_0,
    [48] = KEY_CAPSLOCK, [49] = KEY_BACKSPACE, [50] = KEY_GRAVE,     [51] = KEY_UP,
    [52] = KEY_DOWN,     [53] = KEY_LEFT,      [54] = KEY_RIGHT,     [55] = KEY_F1,
    [56] = KEY_F2,       [57] = KEY_F3,        [58] = KEY_F4,        [59] = KEY_F5,
    [60] = KEY_F6,       [61] = KEY_F7,        [62] = KEY_F8,        [63] = KEY_F9,
};

static int kbd_fd = -1, mouse_fd = -1;
static volatile sig_atomic_t running = 1;

// Current session state.
static uint32_t session;          // 0 = nobody connected
static uint32_t last_seq;
static uint64_t held;             // keys we pressed on the virtual devices
static int32_t last_mx, last_my, last_wheel;
static int64_t last_rx_ms;
static char peer_desc[64];

static int64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static uint32_t rd32(const uint8_t *p) { return p[0] | p[1] << 8 | p[2] << 16 | (uint32_t)p[3] << 24; }
static uint64_t rd64(const uint8_t *p) { return rd32(p) | (uint64_t)rd32(p + 4) << 32; }

static void emit(int fd, int type, int code, int value) {
    struct input_event ev = {0};
    ev.type = type;
    ev.code = code;
    ev.value = value;
    if (write(fd, &ev, sizeof ev) < 0 && errno != EAGAIN) perror("uinput write");
}

static int is_mouse_code(int code) { return code >= BTN_MISC && code < KEY_OK; }

static int make_device(const char *name, int mouse) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        perror("open /dev/uinput");
        return -1;
    }
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    for (int i = 0; i < 64; i++)
        if (KEYMAP[i] && is_mouse_code(KEYMAP[i]) == mouse) ioctl(fd, UI_SET_KEYBIT, KEYMAP[i]);
    if (mouse) {
        ioctl(fd, UI_SET_EVBIT, EV_REL);
        ioctl(fd, UI_SET_RELBIT, REL_X);
        ioctl(fd, UI_SET_RELBIT, REL_Y);
        ioctl(fd, UI_SET_RELBIT, REL_WHEEL);
    }
    struct uinput_setup us = {0};
    us.id.bustype = BUS_USB;
    us.id.vendor = 0x1209;   // pid.codes test vendor
    us.id.product = mouse ? 0x7402 : 0x7401;
    snprintf(us.name, sizeof us.name, "%s", name);
    if (ioctl(fd, UI_DEV_SETUP, &us) < 0 || ioctl(fd, UI_DEV_CREATE) < 0) {
        perror("uinput create");
        close(fd);
        return -1;
    }
    return fd;
}

static void set_keys(uint64_t want) {
    uint64_t diff = want ^ held;
    int kbd_dirty = 0, mouse_dirty = 0;
    for (int i = 0; diff && i < 64; i++) {
        if (!(diff >> i & 1)) continue;
        diff &= ~(1ULL << i);
        int code = KEYMAP[i];
        if (!code) continue;
        int down = want >> i & 1;
        if (is_mouse_code(code)) {
            emit(mouse_fd, EV_KEY, code, down);
            mouse_dirty = 1;
        } else {
            emit(kbd_fd, EV_KEY, code, down);
            kbd_dirty = 1;
        }
    }
    held = want;
    if (kbd_dirty) emit(kbd_fd, EV_SYN, SYN_REPORT, 0);
    if (mouse_dirty) emit(mouse_fd, EV_SYN, SYN_REPORT, 0);
}

static void release_all(void) {
    if (held) set_keys(0);
}

static void end_session(const char *why) {
    release_all();
    if (session) printf("disconnected (%s): %s\n", why, peer_desc);
    session = 0;
    fflush(stdout);
}

static void apply_state(const uint8_t *p, const char *from) {
    uint32_t seq = rd32(p + 4);
    uint64_t buttons = rd64(p + 8);
    int32_t mx = (int32_t)rd32(p + 16), my = (int32_t)rd32(p + 20), wheel = (int32_t)rd32(p + 24);
    uint32_t sess = rd32(p + 28);

    if (sess != session) {
        // A new phone session (or the phone switched between Wi-Fi and USB): take it over.
        release_all();
        session = sess;
        last_mx = mx;
        last_my = my;
        last_wheel = wheel;
        snprintf(peer_desc, sizeof peer_desc, "%s", from);
        printf("connected: %s\n", from);
        fflush(stdout);
    } else if ((int32_t)(seq - last_seq) <= 0) {
        return;  // old or duplicate UDP packet
    }
    last_seq = seq;
    last_rx_ms = now_ms();

    int32_t dx = (int32_t)((uint32_t)mx - (uint32_t)last_mx);
    int32_t dy = (int32_t)((uint32_t)my - (uint32_t)last_my);
    int32_t dw = (int32_t)((uint32_t)wheel - (uint32_t)last_wheel);
    last_mx = mx;
    last_my = my;
    last_wheel = wheel;
    if (dx || dy || dw) {
        if (dx) emit(mouse_fd, EV_REL, REL_X, dx);
        if (dy) emit(mouse_fd, EV_REL, REL_Y, dy);
        if (dw) emit(mouse_fd, EV_REL, REL_WHEEL, dw);
        emit(mouse_fd, EV_SYN, SYN_REPORT, 0);
    }
    set_keys(buttons);
}

static int valid(const uint8_t *p) { return p[0] == 'T' && p[1] == 'P' && p[2] == PROTO_VERSION; }

// Handles one packet. Writes a reply into out and returns its size, or 0 for no reply.
static int handle(const uint8_t *p, const char *from, uint8_t *out) {
    switch (p[3]) {
    case T_STATE:
        apply_state(p, from);
        return 0;
    case T_PING:
        memcpy(out, p, PKT_SIZE);
        out[3] = T_PONG;
        return PKT_SIZE;
    case T_BYE:
        if (rd32(p + 28) == session) end_session("phone closed the app");
        return 0;
    case T_DISCOVER:
        memset(out, 0, REPLY_SIZE);
        memcpy(out, p, 8);
        out[3] = T_DISCOVER_REPLY;
        gethostname((char *)out + 32, 31);
        return REPLY_SIZE;
    }
    return 0;
}

static void adb_reverse(void) {
    static char *argv[] = {"adb", "reverse", "tcp:47800", "tcp:47800", NULL};
    posix_spawn_file_actions_t fa;
    posix_spawn_file_actions_init(&fa);
    posix_spawn_file_actions_addopen(&fa, 1, "/dev/null", O_WRONLY, 0);
    posix_spawn_file_actions_addopen(&fa, 2, "/dev/null", O_WRONLY, 0);
    pid_t pid;
    extern char **environ;
    posix_spawnp(&pid, "adb", &fa, NULL, argv, environ);  // fails quietly when adb is missing
    posix_spawn_file_actions_destroy(&fa);
}

static void on_signal(int sig) {
    (void)sig;
    running = 0;
}

int main(int argc, char **argv) {
    int use_adb = 1;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--no-adb")) use_adb = 0;
        else {
            fprintf(stderr, "usage: %s [--no-adb]\n", argv[0]);
            return 2;
        }
    }

    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    signal(SIGPIPE, SIG_IGN);
    signal(SIGCHLD, SIG_IGN);  // adb children reap themselves

    kbd_fd = make_device("TouchPort Keyboard", 0);
    mouse_fd = make_device("TouchPort Mouse", 1);
    if (kbd_fd < 0 || mouse_fd < 0) return 1;

    int one = 1;
    int udp = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    struct sockaddr_in any = {.sin_family = AF_INET, .sin_port = htons(PORT), .sin_addr.s_addr = htonl(INADDR_ANY)};
    setsockopt(udp, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    int tos = 0xb8;  // DSCP EF: ask the network stack and Wi-Fi to treat replies as low latency
    setsockopt(udp, IPPROTO_IP, IP_TOS, &tos, sizeof tos);
    if (bind(udp, (struct sockaddr *)&any, sizeof any) < 0) {
        perror("bind udp");
        return 1;
    }

    int tcp = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sockaddr_in lo = {.sin_family = AF_INET, .sin_port = htons(PORT), .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    setsockopt(tcp, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    if (bind(tcp, (struct sockaddr *)&lo, sizeof lo) < 0 || listen(tcp, 2) < 0) {
        perror("bind tcp");
        return 1;
    }
    int client = -1;
    uint8_t cbuf[PKT_SIZE];
    size_t cfill = 0;

    printf("TouchPort receiver ready on port %d (UDP: Wi-Fi/USB tethering, TCP: ADB)\n", PORT);
    fflush(stdout);

    int64_t next_adb = 0;
    while (running) {
        struct pollfd pf[3] = {{udp, POLLIN, 0}, {tcp, POLLIN, 0}, {client, POLLIN, 0}};
        int n = poll(pf, client >= 0 ? 3 : 2, 100);
        int64_t now = now_ms();

        if (session && held && now - last_rx_ms > RELEASE_TIMEOUT_MS) {
            printf("no packets for %d ms: released all keys\n", RELEASE_TIMEOUT_MS);
            fflush(stdout);
            release_all();
        }
        if (use_adb && client < 0 && now >= next_adb) {
            adb_reverse();
            next_adb = now + ADB_RETRY_MS;
        }
        if (n <= 0) continue;

        uint8_t buf[256], out[REPLY_SIZE];
        if (pf[0].revents & POLLIN) {
            struct sockaddr_in src;
            socklen_t sl = sizeof src;
            ssize_t len;
            while ((len = recvfrom(udp, buf, sizeof buf, MSG_DONTWAIT, (struct sockaddr *)&src, &sl)) > 0) {
                if (len >= PKT_SIZE && valid(buf)) {
                    char from[64];
                    snprintf(from, sizeof from, "UDP %s", inet_ntoa(src.sin_addr));
                    int r = handle(buf, from, out);
                    if (r) sendto(udp, out, r, 0, (struct sockaddr *)&src, sl);
                }
                sl = sizeof src;
            }
        }
        if (pf[1].revents & POLLIN) {
            int c = accept4(tcp, NULL, NULL, SOCK_CLOEXEC);
            if (c >= 0) {
                if (client >= 0) close(client);  // newest ADB connection wins
                client = c;
                cfill = 0;
                setsockopt(client, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
            }
        }
        if (client >= 0 && (pf[2].revents & (POLLIN | POLLHUP | POLLERR))) {
            ssize_t len = read(client, cbuf + cfill, PKT_SIZE - cfill);
            if (len <= 0) {
                close(client);
                client = -1;
                end_session("USB cable / ADB closed");
            } else if ((cfill += len) == PKT_SIZE) {
                cfill = 0;
                if (valid(cbuf)) {
                    int r = handle(cbuf, "USB (ADB)", out);
                    if (r == PKT_SIZE && write(client, out, r) < 0) perror("tcp write");
                }
            }
        }
    }

    release_all();
    ioctl(kbd_fd, UI_DEV_DESTROY);
    ioctl(mouse_fd, UI_DEV_DESTROY);
    printf("\nreceiver stopped\n");
    return 0;
}
