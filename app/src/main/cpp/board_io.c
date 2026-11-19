#define _DEFAULT_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <poll.h>
#include <stdint.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#include <linux/gpio.h>
#include <linux/i2c-dev.h>
#include <linux/i2c.h>
#include <linux/spi/spidev.h>
#include <termios.h>

#ifndef GPIO_V2_GET_LINE_IOCTL
#define GPIO_V2_LINES_MAX 64
#define GPIO_V2_LINE_NUM_ATTRS_MAX 10
#define GPIO_V2_LINE_FLAG_INPUT (1ULL << 2)
#define GPIO_V2_LINE_FLAG_OUTPUT (1ULL << 3)
#define GPIO_V2_LINE_ATTR_ID_OUTPUT_VALUES 1

struct gpio_v2_line_values {
    __u64 bits;
    __u64 mask;
};

struct gpio_v2_line_attribute {
    __u32 id;
    __u32 padding;
    union {
        __u64 flags;
        struct gpio_v2_line_values values;
        __u32 debounce_period_us;
    } attr;
};

struct gpio_v2_line_config_attribute {
    struct gpio_v2_line_attribute attr;
    __u64 mask;
};

struct gpio_v2_line_config {
    __u64 flags;
    __u32 num_attrs;
    __u32 padding[5];
    struct gpio_v2_line_config_attribute attrs[GPIO_V2_LINE_NUM_ATTRS_MAX];
};

struct gpio_v2_line_request {
    __u32 offsets[GPIO_V2_LINES_MAX];
    char consumer[32];
    struct gpio_v2_line_config config;
    __u32 num_lines;
    __u32 event_buffer_size;
    __u32 padding[5];
    __s32 fd;
};

#define GPIO_V2_GET_LINE_IOCTL \
    _IOWR(0xB4, 0x07, struct gpio_v2_line_request)
#define GPIO_V2_LINE_GET_VALUES_IOCTL \
    _IOWR(0xB4, 0x0E, struct gpio_v2_line_values)
#define GPIO_V2_LINE_SET_VALUES_IOCTL \
    _IOWR(0xB4, 0x0F, struct gpio_v2_line_values)
#endif

#define MAX_PAYLOAD_BYTES 4096U
#define MAX_TIMEOUT_MS 60000U

static volatile sig_atomic_t gps_stop_requested;

static void gps_handle_signal(int signal_number)
{
    (void)signal_number;
    gps_stop_requested = 1;
}

static void report_errno(const char *operation)
{
    fprintf(stderr, "%s: %s\n", operation, strerror(errno));
}

static int parse_uint(const char *text, uint64_t maximum, uint64_t *value)
{
    char *end = NULL;
    unsigned long long parsed;
    int base = 10;

    if (text == NULL || text[0] == '\0' || text[0] == '-' ||
        text[0] == '+') {
        return -1;
    }
    if (text[0] == '0' && (text[1] == 'x' || text[1] == 'X')) {
        if (text[2] == '\0') {
            return -1;
        }
        base = 16;
    }
    errno = 0;
    parsed = strtoull(text, &end, base);
    if (errno != 0 || end == text || *end != '\0' || parsed > maximum) {
        return -1;
    }
    *value = (uint64_t)parsed;
    return 0;
}

static int decode_hex(const char *text, uint8_t *buffer, size_t capacity,
                      size_t *length)
{
    size_t chars;
    size_t i;

    if (text == NULL) {
        return -1;
    }
    chars = strlen(text);
    if ((chars & 1U) != 0 || chars / 2U > capacity) {
        return -1;
    }
    for (i = 0; i < chars; i += 2) {
        unsigned int high;
        unsigned int low;
        char a = text[i];
        char b = text[i + 1];

        if (a >= '0' && a <= '9') {
            high = (unsigned int)(a - '0');
        } else if (a >= 'a' && a <= 'f') {
            high = (unsigned int)(a - 'a' + 10);
        } else if (a >= 'A' && a <= 'F') {
            high = (unsigned int)(a - 'A' + 10);
        } else {
            return -1;
        }
        if (b >= '0' && b <= '9') {
            low = (unsigned int)(b - '0');
        } else if (b >= 'a' && b <= 'f') {
            low = (unsigned int)(b - 'a' + 10);
        } else if (b >= 'A' && b <= 'F') {
            low = (unsigned int)(b - 'A' + 10);
        } else {
            return -1;
        }
        buffer[i / 2U] = (uint8_t)((high << 4) | low);
    }
    *length = chars / 2U;
    return 0;
}

static int print_rx(const uint8_t *data, size_t length)
{
    size_t i;

    if (fputs("RX=", stdout) == EOF) {
        return -1;
    }
    for (i = 0; i < length; ++i) {
        if (printf("%02x", data[i]) < 0) {
            return -1;
        }
    }
    if (putchar('\n') == EOF || fflush(stdout) == EOF) {
        return -1;
    }
    return 0;
}

static int command_i2c(int argc, char **argv, int write_mode)
{
    uint64_t bus;
    uint64_t address;
    uint64_t reg;
    uint64_t value = 0;
    char path[64];
    int fd = -1;
    int result = 1;

    if (argc != (write_mode ? 6 : 5) ||
        parse_uint(argv[2], 255, &bus) != 0 ||
        parse_uint(argv[3], 0x77, &address) != 0 || address < 0x03 ||
        parse_uint(argv[4], 0xff, &reg) != 0 ||
        (write_mode && parse_uint(argv[5], 0xff, &value) != 0)) {
        fprintf(stderr, "usage: i2c-%s BUS ADDR REG%s\n",
                write_mode ? "write" : "read",
                write_mode ? " VALUE" : "");
        return 2;
    }
    if (snprintf(path, sizeof(path), "/dev/i2c-%" PRIu64, bus) >=
        (int)sizeof(path)) {
        fprintf(stderr, "i2c device path is too long\n");
        return 2;
    }
    fd = open(path, O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        report_errno(path);
        goto done;
    }
    if (write_mode) {
        uint8_t bytes[2] = {(uint8_t)reg, (uint8_t)value};
        struct i2c_msg message;
        struct i2c_rdwr_ioctl_data transfer;

        memset(&message, 0, sizeof(message));
        message.addr = (uint16_t)address;
        message.flags = 0;
        message.len = sizeof(bytes);
        message.buf = bytes;
        transfer.msgs = &message;
        transfer.nmsgs = 1;
        if (ioctl(fd, I2C_RDWR, &transfer) != 1) {
            if (errno == 0) {
                errno = EIO;
            }
            report_errno("I2C_RDWR write");
            goto done;
        }
        if (puts("OK") == EOF || fflush(stdout) == EOF) {
            report_errno("stdout");
            goto done;
        }
    } else {
        uint8_t reg_byte = (uint8_t)reg;
        uint8_t read_byte = 0;
        struct i2c_msg messages[2];
        struct i2c_rdwr_ioctl_data transfer;

        memset(messages, 0, sizeof(messages));
        messages[0].addr = (uint16_t)address;
        messages[0].len = 1;
        messages[0].buf = &reg_byte;
        messages[1].addr = (uint16_t)address;
        messages[1].flags = I2C_M_RD;
        messages[1].len = 1;
        messages[1].buf = &read_byte;
        transfer.msgs = messages;
        transfer.nmsgs = 2;
        if (ioctl(fd, I2C_RDWR, &transfer) != 2) {
            if (errno == 0) {
                errno = EIO;
            }
            report_errno("I2C_RDWR read");
            goto done;
        }
        if (printf("VALUE=0x%02x\n", read_byte) < 0 ||
            fflush(stdout) == EOF) {
            report_errno("stdout");
            goto done;
        }
    }
    result = 0;

done:
    if (fd >= 0 && close(fd) != 0 && result == 0) {
        report_errno("close");
        result = 1;
    }
    return result;
}

static int request_gpio_v2(int chip_fd, uint32_t line, int output,
                           int initial_value)
{
    struct gpio_v2_line_request request;

    memset(&request, 0, sizeof(request));
    request.offsets[0] = line;
    request.num_lines = 1;
    (void)snprintf(request.consumer, sizeof(request.consumer), "board_io");
    request.config.flags = output ? GPIO_V2_LINE_FLAG_OUTPUT
                                  : GPIO_V2_LINE_FLAG_INPUT;
    if (output) {
        request.config.num_attrs = 1;
        request.config.attrs[0].attr.id =
            GPIO_V2_LINE_ATTR_ID_OUTPUT_VALUES;
        request.config.attrs[0].attr.attr.values.bits =
            initial_value ? 1U : 0U;
        request.config.attrs[0].attr.attr.values.mask = 1U;
        request.config.attrs[0].mask = 1U;
    }
    if (ioctl(chip_fd, GPIO_V2_GET_LINE_IOCTL, &request) < 0) {
        return -1;
    }
    return request.fd;
}

static int request_gpio_v1(int chip_fd, uint32_t line, int output,
                           int initial_value)
{
    struct gpiohandle_request request;

    memset(&request, 0, sizeof(request));
    request.lineoffsets[0] = line;
    request.lines = 1;
    request.flags = output ? GPIOHANDLE_REQUEST_OUTPUT
                           : GPIOHANDLE_REQUEST_INPUT;
    request.default_values[0] = initial_value ? 1 : 0;
    (void)snprintf(request.consumer_label, sizeof(request.consumer_label),
                   "board_io");
    if (ioctl(chip_fd, GPIO_GET_LINEHANDLE_IOCTL, &request) < 0) {
        return -1;
    }
    return request.fd;
}

static int get_gpio_value(int line_fd, int *value)
{
    struct gpio_v2_line_values values;

    memset(&values, 0, sizeof(values));
    values.mask = 1U;
    if (ioctl(line_fd, GPIO_V2_LINE_GET_VALUES_IOCTL, &values) == 0) {
        *value = (values.bits & 1U) != 0;
        return 0;
    }
    return -1;
}

static int get_gpio_value_v1(int line_fd, int *value)
{
    struct gpiohandle_data data;

    memset(&data, 0, sizeof(data));
    if (ioctl(line_fd, GPIOHANDLE_GET_LINE_VALUES_IOCTL, &data) < 0) {
        return -1;
    }
    *value = data.values[0] != 0;
    return 0;
}

static int command_gpio(int argc, char **argv, int output)
{
    uint64_t line_arg;
    uint64_t initial_arg = 0;
    uint32_t line;
    int initial_value = 0;
    int chip_fd = -1;
    int line_fd = -1;
    int result = 1;
    int value = 0;

    if (argc != (output ? 5 : 4) || argv[2][0] == '\0' ||
        parse_uint(argv[3], UINT32_MAX, &line_arg) != 0 ||
        (output && parse_uint(argv[4], 1, &initial_arg) != 0)) {
        fprintf(stderr, "usage: gpio-%s CHIP LINE%s\n",
                output ? "set" : "get", output ? " VALUE" : "");
        return 2;
    }
    line = (uint32_t)line_arg;
    initial_value = (int)initial_arg;
    chip_fd = open(argv[2], O_RDONLY | O_CLOEXEC);
    if (chip_fd < 0) {
        report_errno(argv[2]);
        goto done;
    }
    line_fd = request_gpio_v2(chip_fd, line, output, initial_value);
    if (line_fd < 0) {
        line_fd = request_gpio_v1(chip_fd, line, output, initial_value);
    }
    if (line_fd < 0) {
        report_errno("request GPIO line (v2 and v1)");
        goto done;
    }
    if (output) {
        if (fputs("READY\n", stdout) == EOF || fflush(stdout) == EOF) {
            report_errno("stdout");
            goto done;
        }
        for (;;) {
            char buffer[256];
            ssize_t count = read(STDIN_FILENO, buffer, sizeof(buffer));
            if (count == 0) {
                break;
            }
            if (count < 0 && errno == EINTR) {
                continue;
            }
            if (count < 0) {
                report_errno("stdin");
                goto done;
            }
        }
    } else {
        if (get_gpio_value(line_fd, &value) != 0 &&
            get_gpio_value_v1(line_fd, &value) != 0) {
            report_errno("read GPIO value (v2 and v1)");
            goto done;
        }
        if (printf("VALUE=%d\n", value) < 0 || fflush(stdout) == EOF) {
            report_errno("stdout");
            goto done;
        }
    }
    result = 0;

done:
    if (line_fd >= 0 && close(line_fd) != 0 && result == 0) {
        report_errno("close GPIO line");
        result = 1;
    }
    if (chip_fd >= 0 && close(chip_fd) != 0 && result == 0) {
        report_errno("close GPIO chip");
        result = 1;
    }
    return result;
}

static int baud_constant(uint64_t baud, speed_t *speed)
{
    switch (baud) {
    case 50: *speed = B50; return 0;
    case 75: *speed = B75; return 0;
    case 110: *speed = B110; return 0;
    case 134: *speed = B134; return 0;
    case 150: *speed = B150; return 0;
    case 200: *speed = B200; return 0;
    case 300: *speed = B300; return 0;
    case 600: *speed = B600; return 0;
    case 1200: *speed = B1200; return 0;
    case 1800: *speed = B1800; return 0;
    case 2400: *speed = B2400; return 0;
    case 4800: *speed = B4800; return 0;
    case 9600: *speed = B9600; return 0;
    case 19200: *speed = B19200; return 0;
    case 38400: *speed = B38400; return 0;
    case 57600: *speed = B57600; return 0;
    case 115200: *speed = B115200; return 0;
    case 230400: *speed = B230400; return 0;
#ifdef B460800
    case 460800: *speed = B460800; return 0;
#endif
#ifdef B500000
    case 500000: *speed = B500000; return 0;
#endif
#ifdef B576000
    case 576000: *speed = B576000; return 0;
#endif
#ifdef B921600
    case 921600: *speed = B921600; return 0;
#endif
#ifdef B1000000
    case 1000000: *speed = B1000000; return 0;
#endif
#ifdef B1500000
    case 1500000: *speed = B1500000; return 0;
#endif
#ifdef B2000000
    case 2000000: *speed = B2000000; return 0;
#endif
    default: return -1;
    }
}

static int command_gps_stream(int argc, char **argv)
{
    uint64_t baud_arg;
    speed_t speed;
    char line[1024];
    size_t line_length = 0;
    int collecting = 0;
    int overflow = 0;
    int fd = -1;
    int result = 1;
    int restore_needed = 0;
    int have_original = 0;
    struct termios original;
    struct termios configured;
    struct sigaction action;
    struct sigaction old_int;
    struct sigaction old_term;
    int have_old_int = 0;
    int have_old_term = 0;

    if (argc != 4 || argv[2][0] == '\0' ||
        parse_uint(argv[3], UINT32_MAX, &baud_arg) != 0 ||
        baud_constant(baud_arg, &speed) != 0) {
        fprintf(stderr, "usage: gps-stream DEVICE BAUD\n");
        return 2;
    }

    fd = open(argv[2], O_RDWR | O_NOCTTY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        report_errno(argv[2]);
        goto done;
    }
    if (tcgetattr(fd, &original) != 0) {
        report_errno("tcgetattr");
        goto done;
    }
    have_original = 1;
    configured = original;
    cfmakeraw(&configured);
    configured.c_cflag |= CLOCAL | CREAD;
    configured.c_cflag &= ~(CSTOPB | PARENB | CSIZE);
    configured.c_cflag |= CS8;
    configured.c_cc[VMIN] = 0;
    configured.c_cc[VTIME] = 0;
    if (cfsetispeed(&configured, speed) != 0 ||
        cfsetospeed(&configured, speed) != 0) {
        report_errno("set GPS UART speed");
        goto done;
    }
    restore_needed = 1;
    if (tcsetattr(fd, TCSANOW, &configured) != 0) {
        report_errno("tcsetattr");
        goto done;
    }

    memset(&action, 0, sizeof(action));
    action.sa_handler = gps_handle_signal;
    sigemptyset(&action.sa_mask);
    gps_stop_requested = 0;
    if (sigaction(SIGINT, &action, &old_int) != 0) {
        report_errno("sigaction SIGINT");
        goto done;
    }
    have_old_int = 1;
    if (sigaction(SIGTERM, &action, &old_term) != 0) {
        report_errno("sigaction SIGTERM");
        goto done;
    }
    have_old_term = 1;
    if (printf("GPS_READY\n") < 0 || fflush(stdout) != 0) {
        report_errno("GPS stream output");
        goto done;
    }

    while (!gps_stop_requested) {
        struct pollfd descriptor;
        int polled;

        descriptor.fd = fd;
        descriptor.events = POLLIN;
        descriptor.revents = 0;
        polled = poll(&descriptor, 1, 1000);
        if (polled < 0 && errno == EINTR) {
            continue;
        }
        if (polled < 0) {
            report_errno("GPS UART poll");
            goto done;
        }
        if (polled == 0) {
            continue;
        }
        if ((descriptor.revents & (POLLERR | POLLNVAL)) != 0) {
            errno = EIO;
            report_errno("GPS UART poll");
            goto done;
        }
        if ((descriptor.revents & POLLHUP) != 0) {
            errno = EIO;
            report_errno("GPS UART hangup");
            goto done;
        }
        if ((descriptor.revents & POLLIN) != 0) {
            unsigned char input[256];
            ssize_t count = read(fd, input, sizeof(input));
            ssize_t index;

            if (count < 0 && (errno == EINTR || errno == EAGAIN ||
                              errno == EWOULDBLOCK)) {
                continue;
            }
            if (count < 0) {
                report_errno("GPS UART read");
                goto done;
            }
            for (index = 0; index < count; ++index) {
                unsigned char byte = input[index];

                if (byte == '$') {
                    collecting = 1;
                    overflow = 0;
                    line_length = 0;
                }
                if (!collecting) {
                    continue;
                }
                if (byte == '\n') {
                    if (!overflow && line_length > 0) {
                        line[line_length] = '\0';
                        if (printf("%s\n", line) < 0 || fflush(stdout) != 0) {
                            report_errno("GPS stream output");
                            goto done;
                        }
                    }
                    collecting = 0;
                    line_length = 0;
                    continue;
                }
                if (byte == '\r') {
                    continue;
                }
                if (line_length + 1 >= sizeof(line)) {
                    overflow = 1;
                    continue;
                }
                line[line_length++] = (char)byte;
            }
        }
    }
    result = 0;

done:
    if (have_old_term && sigaction(SIGTERM, &old_term, NULL) != 0) {
        report_errno("restore SIGTERM handler");
        result = 1;
    }
    if (have_old_int && sigaction(SIGINT, &old_int, NULL) != 0) {
        report_errno("restore SIGINT handler");
        result = 1;
    }
    if (restore_needed && have_original &&
        tcsetattr(fd, TCSANOW, &original) != 0) {
        report_errno("restore GPS UART");
        result = 1;
    }
    if (fd >= 0 && close(fd) != 0 && result == 0) {
        report_errno("close GPS UART");
        result = 1;
    }
    return result;
}

static int64_t monotonic_milliseconds(void)
{
    struct timespec now;

    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) {
        return -1;
    }
    return (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

static int poll_until(int fd, short events, int64_t deadline)
{
    for (;;) {
        int64_t now = monotonic_milliseconds();
        int timeout;
        struct pollfd descriptor;
        int polled;

        if (now < 0) {
            return -1;
        }
        if (now >= deadline) {
            return 0;
        }
        timeout = (deadline - now > INT_MAX) ? INT_MAX : (int)(deadline - now);
        descriptor.fd = fd;
        descriptor.events = events;
        descriptor.revents = 0;
        polled = poll(&descriptor, 1, timeout);
        if (polled < 0 && errno == EINTR) {
            continue;
        }
        if (polled <= 0) {
            return polled;
        }
        if ((descriptor.revents & (POLLERR | POLLNVAL)) != 0) {
            errno = EIO;
            return -1;
        }
        if ((descriptor.revents & events) != 0 ||
            (descriptor.revents & POLLHUP) != 0) {
            return 1;
        }
    }
}

static int write_all_timed(int fd, const uint8_t *data, size_t length,
                           int64_t deadline)
{
    size_t sent = 0;

    while (sent < length) {
        ssize_t count = write(fd, data + sent, length - sent);
        if (count > 0) {
            sent += (size_t)count;
            continue;
        }
        if (count < 0 && errno == EINTR) {
            continue;
        }
        if (count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            int ready = poll_until(fd, POLLOUT, deadline);
            if (ready > 0) {
                continue;
            }
            if (ready == 0) {
                errno = ETIMEDOUT;
            }
        } else if (count == 0) {
            errno = EIO;
        }
        return -1;
    }
    return 0;
}

static int command_uart(int argc, char **argv)
{
    uint64_t baud_arg;
    uint64_t timeout_arg;
    speed_t speed;
    uint8_t tx[MAX_PAYLOAD_BYTES];
    uint8_t rx[MAX_PAYLOAD_BYTES];
    size_t tx_length = 0;
    size_t rx_length = 0;
    int receive_only;
    int fd = -1;
    int result = 1;
    int have_original = 0;
    int restore_needed = 0;
    struct termios original;
    struct termios configured;
    int64_t deadline;

    if (argc != 6 || argv[2][0] == '\0' ||
        parse_uint(argv[3], UINT32_MAX, &baud_arg) != 0 ||
        baud_constant(baud_arg, &speed) != 0 ||
        parse_uint(argv[4], MAX_TIMEOUT_MS, &timeout_arg) != 0 ||
        (strcmp(argv[5], "-") != 0 &&
         decode_hex(argv[5], tx, sizeof(tx), &tx_length) != 0)) {
        fprintf(stderr,
                "usage: uart DEVICE BAUD TIMEOUT_MS HEX_PAYLOAD|- "
                "(timeout <= %u ms, payload <= %u bytes)\n",
                MAX_TIMEOUT_MS, MAX_PAYLOAD_BYTES);
        return 2;
    }
    receive_only = strcmp(argv[5], "-") == 0;
    fd = open(argv[2], O_RDWR | O_NOCTTY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        report_errno(argv[2]);
        goto done;
    }
    if (tcgetattr(fd, &original) != 0) {
        report_errno("tcgetattr");
        goto done;
    }
    have_original = 1;
    configured = original;
    cfmakeraw(&configured);
    configured.c_cflag |= CLOCAL | CREAD;
    configured.c_cflag &= ~(CSTOPB | PARENB | CSIZE);
    configured.c_cflag |= CS8;
    configured.c_cc[VMIN] = 0;
    configured.c_cc[VTIME] = 0;
    if (cfsetispeed(&configured, speed) != 0 ||
        cfsetospeed(&configured, speed) != 0) {
        report_errno("set UART speed");
        goto done;
    }
    restore_needed = 1;
    if (tcsetattr(fd, TCSANOW, &configured) != 0) {
        report_errno("tcsetattr");
        goto done;
    }
    deadline = monotonic_milliseconds();
    if (deadline < 0) {
        report_errno("clock_gettime");
        goto done;
    }
    deadline += (int64_t)timeout_arg;
    if (!receive_only && write_all_timed(fd, tx, tx_length, deadline) != 0) {
        report_errno("UART write");
        goto done;
    }
    while (rx_length < sizeof(rx)) {
        int ready = poll_until(fd, POLLIN, deadline);
        if (ready < 0) {
            report_errno("UART poll");
            goto done;
        }
        if (ready == 0) {
            break;
        }
        {
            ssize_t count = read(fd, rx + rx_length, sizeof(rx) - rx_length);
            if (count > 0) {
                rx_length += (size_t)count;
                continue;
            }
            if (count < 0 && errno == EINTR) {
                continue;
            }
            if (count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
                continue;
            }
            if (count == 0) {
                continue;
            }
            report_errno("UART read");
            goto done;
        }
    }
    result = 0;

done:
    if (restore_needed && have_original && tcsetattr(fd, TCSANOW, &original) != 0) {
        report_errno("restore termios");
        result = 1;
    }
    if (fd >= 0 && close(fd) != 0 && result == 0) {
        report_errno("close UART");
        result = 1;
    }
    if (result == 0 && print_rx(rx, rx_length) != 0) {
        report_errno("stdout");
        result = 1;
    }
    return result;
}

static int command_spi(int argc, char **argv)
{
    uint64_t speed_arg;
    uint64_t mode_arg;
    uint8_t tx[MAX_PAYLOAD_BYTES];
    uint8_t rx[MAX_PAYLOAD_BYTES];
    size_t length = 0;
    uint8_t mode;
    uint8_t bits = 8;
    uint8_t original_mode = 0;
    uint8_t original_bits = 0;
    uint32_t speed;
    uint32_t original_speed = 0;
    int fd = -1;
    int result = 1;
    int restore_needed = 0;
    struct spi_ioc_transfer transfer;

    if (argc != 6 || argv[2][0] == '\0' ||
        parse_uint(argv[3], UINT32_MAX, &speed_arg) != 0 || speed_arg == 0 ||
        parse_uint(argv[4], 3, &mode_arg) != 0 ||
        decode_hex(argv[5], tx, sizeof(tx), &length) != 0 || length == 0) {
        fprintf(stderr,
                "usage: spi DEVICE SPEED_HZ MODE HEX_PAYLOAD "
                "(payload 1..%u bytes)\n",
                MAX_PAYLOAD_BYTES);
        return 2;
    }
    speed = (uint32_t)speed_arg;
    mode = (uint8_t)mode_arg;
    fd = open(argv[2], O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        report_errno(argv[2]);
        goto done;
    }
    if (ioctl(fd, SPI_IOC_RD_MODE, &original_mode) < 0 ||
        ioctl(fd, SPI_IOC_RD_BITS_PER_WORD, &original_bits) < 0 ||
        ioctl(fd, SPI_IOC_RD_MAX_SPEED_HZ, &original_speed) < 0) {
        report_errno("read current SPI configuration");
        goto done;
    }
    restore_needed = 1;
    if (ioctl(fd, SPI_IOC_WR_MODE, &mode) < 0 ||
        ioctl(fd, SPI_IOC_WR_BITS_PER_WORD, &bits) < 0 ||
        ioctl(fd, SPI_IOC_WR_MAX_SPEED_HZ, &speed) < 0) {
        report_errno("configure SPI");
        goto done;
    }
    memset(&transfer, 0, sizeof(transfer));
    transfer.tx_buf = (uintptr_t)tx;
    transfer.rx_buf = (uintptr_t)rx;
    transfer.len = (uint32_t)length;
    transfer.speed_hz = speed;
    transfer.bits_per_word = bits;
    if (ioctl(fd, SPI_IOC_MESSAGE(1), &transfer) != (int)length) {
        if (errno == 0) {
            errno = EIO;
        }
        report_errno("SPI transfer");
        goto done;
    }
    result = 0;

done:
    if (restore_needed) {
        int restore_error = 0;
        if (ioctl(fd, SPI_IOC_WR_MODE, &original_mode) < 0) {
            report_errno("restore SPI configuration");
            restore_error = 1;
        }
        if (ioctl(fd, SPI_IOC_WR_BITS_PER_WORD, &original_bits) < 0) {
            report_errno("restore SPI bits per word");
            restore_error = 1;
        }
        if (ioctl(fd, SPI_IOC_WR_MAX_SPEED_HZ, &original_speed) < 0) {
            report_errno("restore SPI speed");
            restore_error = 1;
        }
        if (restore_error) {
            result = 1;
        }
    }
    if (fd >= 0 && close(fd) != 0 && result == 0) {
        report_errno("close SPI");
        result = 1;
    }
    if (result == 0 && print_rx(rx, length) != 0) {
        report_errno("stdout");
        result = 1;
    }
    return result;
}

static void print_usage(void)
{
    fprintf(stderr,
            "usage:\n"
            "  board_io i2c-read BUS ADDR REG\n"
            "  board_io i2c-write BUS ADDR REG VALUE\n"
            "  board_io gpio-get CHIP LINE\n"
            "  board_io gpio-set CHIP LINE VALUE\n"
            "  board_io uart DEVICE BAUD TIMEOUT_MS HEX_PAYLOAD|-\n"
            "  board_io gps-stream DEVICE BAUD\n"
            "  board_io spi DEVICE SPEED_HZ MODE HEX_PAYLOAD\n");
}

int main(int argc, char **argv)
{
    if (argc < 2) {
        print_usage();
        return 2;
    }
    if (strcmp(argv[1], "i2c-read") == 0) {
        return command_i2c(argc, argv, 0);
    }
    if (strcmp(argv[1], "i2c-write") == 0) {
        return command_i2c(argc, argv, 1);
    }
    if (strcmp(argv[1], "gpio-get") == 0) {
        return command_gpio(argc, argv, 0);
    }
    if (strcmp(argv[1], "gpio-set") == 0) {
        return command_gpio(argc, argv, 1);
    }
    if (strcmp(argv[1], "uart") == 0) {
        return command_uart(argc, argv);
    }
    if (strcmp(argv[1], "gps-stream") == 0) {
        return command_gps_stream(argc, argv);
    }
    if (strcmp(argv[1], "spi") == 0) {
        return command_spi(argc, argv);
    }
    fprintf(stderr, "unknown command: %s\n", argv[1]);
    print_usage();
    return 2;
}
