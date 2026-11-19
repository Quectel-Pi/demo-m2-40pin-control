#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <glob.h>

#include "DEV_Config.h"
#include "ssd1305.h"

#define OLED_VISIBLE_WIDTH 128
#define OLED_FONT_SIZE 12
#define OLED_FONT_CHAR_WIDTH (OLED_FONT_SIZE / 2)
#define OLED_MAX_TEXT_LENGTH (OLED_VISIBLE_WIDTH / OLED_FONT_CHAR_WIDTH)

/* Android bionic omits glob; the fixed M2 SPI node makes the SDK probe unreachable. */
int glob(const char *pattern, int flags, int (*errfunc)(const char *, int),
         glob_t *result)
{
    (void)pattern;
    (void)flags;
    (void)errfunc;
    (void)result;
    return -1;
}

void globfree(glob_t *result)
{
    (void)result;
}

static void print_usage(const char *program)
{
    fprintf(stderr,
            "Usage: %s demo | show TEXT | clear\n"
            "  show TEXT: 1-%d printable ASCII characters\n",
            program, OLED_MAX_TEXT_LENGTH);
}

static int build_text(int argc, char **argv, char *text)
{
    size_t length = 0;
    int arg;

    for (arg = 2; arg < argc; ++arg) {
        const unsigned char *ch = (const unsigned char *)argv[arg];

        if (arg > 2) {
            if (length == OLED_MAX_TEXT_LENGTH)
                return -1;
            text[length++] = ' ';
        }
        while (*ch != '\0') {
            if (*ch < 0x20 || *ch > 0x7e)
                return -1;
            if (length == OLED_MAX_TEXT_LENGTH)
                return -1;
            text[length++] = (char)*ch++;
        }
    }

    if (length == 0)
        return -1;

    text[length] = '\0';
    return 0;
}

int main(int argc, char **argv)
{
    enum { COMMAND_DEMO, COMMAND_SHOW, COMMAND_CLEAR } command;
    char text[OLED_MAX_TEXT_LENGTH + 1];

    if (argc == 2 && strcmp(argv[1], "demo") == 0) {
        command = COMMAND_DEMO;
    } else if (argc == 2 && strcmp(argv[1], "clear") == 0) {
        command = COMMAND_CLEAR;
    } else if (argc >= 3 && strcmp(argv[1], "show") == 0) {
        command = COMMAND_SHOW;
        if (build_text(argc, argv, text) != 0) {
            fprintf(stderr, "show requires 1-%d printable ASCII characters\n",
                    OLED_MAX_TEXT_LENGTH);
            return 2;
        }
    } else {
        print_usage(argv[0]);
        return 2;
    }

    if (setenv("OLED_SPI_DEV", "/dev/spidev1.0", 1) != 0 ||
        setenv("OLED_DC_GPIO", "134", 1) != 0 ||
        setenv("OLED_RST_GPIO", "76", 1) != 0) {
        perror("setting M2 OLED defaults");
        return 1;
    }

    if (DEV_ModuleInit() != 0) {
        fprintf(stderr, "OLED hardware initialization failed\n");
        DEV_ModuleExit();
        return 1;
    }

    SSD1305_begin();
    SSD1305_clear();
    if (command == COMMAND_DEMO) {
        SSD1305_string(0, 0, "Welcome to use", OLED_FONT_SIZE, 1);
        SSD1305_string(0, OLED_FONT_SIZE, "128x32 Pixels", OLED_FONT_SIZE, 1);
    } else if (command == COMMAND_SHOW) {
        SSD1305_string(0, 0, text, OLED_FONT_SIZE, 1);
    }
    SSD1305_display();
    DEV_ModuleExit();

    return 0;
}
