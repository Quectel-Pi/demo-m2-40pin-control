# 40Pin-test —— Waveshare HAT × Quectel Pi 上板测试包

> 本目录是从 `demo-40Pin-HAT` 裁剪出来的测试包，只保留上板测试需要的脚本、README 和预编译二进制。
> **不包含 `packages/`、`install_deps.sh` 和 C 源码**，不需要编译，也不需要安装离线依赖，拷到板子上即可运行。

## 1. 包含内容

| 目录 | 内容 | 覆盖板 |
|---|---|---|
| `2.23inch-OLED-HAT/` | 4 个一键脚本、`QPi_2.23inch_OLED_HAT_SDK/`（prebuilt + python）、4 份 README | L1 / M1 / H1 / M2 |
| `Environment-Sensor-HAT/` | 4 个一键脚本、`QPi_Environment_Sensor_HAT_SDK/`（prebuilt + python）、4 份 README | L1 / M1 / H1 / M2 |
| `40Pin-GPIO/` | `<板>_pin_control.sh`：GPIO 置高 / 置低 / 读取 | L1 / M1 / H1 / M2 |
| `40Pin-PWM/` | `<板>_pwm-test.sh`：PWM 输出 + GPIO 边沿捕获 | L1 / M1 / M2（H1 暂无） |
| `40Pin-UART/` | `<板>_uart-loopback.sh`：Pin8 ↔ Pin10 回环 | L1 / M1 / H1 / M2 |
| `40Pin-CAN/` | `M2_can-loopback.sh` + `README_M2.md`：单板 / 双板 CAN 测试 | M2 |
| `_common.sh` | HAT 脚本共用函数库，**必须保留在根目录** | — |

## 2. 板上环境要求

所有测试都要以 root 运行；Android 需先 `adb root` 或 `su`。

| 测试 | 板上需要的组件 | 缺失时 |
|---|---|---|
| HAT C 例程 | 无（直接运行 `prebuilt/main_aarch64_static`） | — |
| 2.23inch OLED Python | `python3`（只用标准库） | 跳过 Python，C 例程照常 |
| Environment Sensor Python | `python3` + `smbus2` | 跳过 Python，C 例程照常 |
| 40Pin-GPIO / PWM | libgpiod（M2 脚本兼容 v1.6+ 与 v2；其他板型需 v2） | 脚本报 command not found |
| 40Pin-UART | `stty`、`dd`、`cmp`、`od`、`timeout` | 脚本报缺少命令 |
| 40Pin-CAN | `ip` + can-utils（`candump` / `cansend`）；Android 另需 `/data/local/tmp/cantool` | 无法收发或看不到帧 |

> 本测试包不含 `packages/`，也不含 libgpiod / smbus2。板上缺这些依赖时，请改用完整版
> `demo-40Pin-HAT` 在 Debian 上执行 `install_deps.sh`，或提前在镜像里预装。

## 3. 部署到开发板

Linux（放到 `/root` 或 `/home/<用户>` 等持久可写目录，不要放 `/tmp`，重启会丢）：

```bash
scp -r 40Pin-test root@<板子IP>:/root/
ssh root@<板子IP>
cd /root/40Pin-test
chmod +x 2.23inch-OLED-HAT/*.sh Environment-Sensor-HAT/*.sh \
         40Pin-GPIO/*.sh 40Pin-PWM/*.sh 40Pin-UART/*.sh 40Pin-CAN/*.sh
```

Android（`/sdcard` 默认 `noexec`，不能放在那里执行）：

```bash
adb root
adb push 40Pin-test /data/local/tmp/40Pin-test
adb shell
su
cd /data/local/tmp/40Pin-test
```

注意事项：

- **必须推送整个目录**，不能只推单个脚本：HAT 脚本要 `source` 上一层的 `_common.sh`。
- Android 自带 mksh、没有 bash，统一用 `sh <脚本>` 执行。
- 从 Windows 拷过来的脚本可能丢可执行位，先执行上面的 `chmod +x`。

## 4. HAT 测试

### 4.1 2.23inch OLED HAT

```bash
cd 2.23inch-OLED-HAT
./L1_2.23inch-OLED-HAT.sh          # 换成 M1 / M2 / H1 对应脚本
```

Android：

```bash
sh 2.23inch-OLED-HAT/L1_2.23inch-OLED-HAT.sh
```

说明：

- 无需编译：本包没有 C 源码，脚本会自动使用 `prebuilt/main_aarch64_static`。
- 可加 `--no-build`，与不加效果相同（本来就不编译）。
- Python 例程只用标准库，有 `python3` 即可。
- 接线：M2 可直扣 40Pin；L1 / M1 / H1 需用杜邦线；HAT 只吃 Pin2/Pin4 的 5V。

### 4.2 Environment Sensor HAT

```bash
cd Environment-Sensor-HAT
./L1_Environment-Sensor-HAT.sh     # 换成 M1 / M2 / H1 对应脚本
```

说明：

- 无需编译，使用 `prebuilt/main_aarch64_static`。
- Python 例程需要 `smbus2`；没有时脚本会跳过 Python，C 例程仍可运行。
- 所有开发板都可以直扣；I2C 使用 Pin3 / Pin5。

## 5. 40Pin 基础测试

### 5.1 GPIO

```bash
cd 40Pin-GPIO
./L1_pin_control.sh pin18 up       # 换成 M1 / M2 / H1 对应脚本
./L1_pin_control.sh pin18 get
./L1_pin_control.sh all down
```

- 需要 libgpiod 的 `gpioset` / `gpioget`；M2 脚本自动兼容 v1.6+ 与 v2，其他板型需要 v2。
- 已被 I2C / UART / SPI / PWM 占用的引脚会报 `Device or resource busy`。

### 5.2 PWM

```bash
cd 40Pin-PWM
./L1_pwm-test.sh                   # 默认捕获 Pin31
./L1_pwm-test.sh 5                 # 捕获 Pin29
./M2_pwm-test.sh 92                # M2：92 = Pin31，93 = Pin29
```

接线：Pin33 接捕获引脚（默认 Pin31）。判定标准：900–1100 Hz、40–60% 占空比。

### 5.3 UART 回环

```bash
cd 40Pin-UART
sh L1_uart-loopback.sh             # 换成 M1 / M2 / H1 对应脚本
```

接线：短接 Pin8（TXD）与 Pin10（RXD）。7 档波特率全部 PASS 才算通过。

### 5.4 CAN 单板回环（M2）

```bash
cd 40Pin-CAN
./M2_can-loopback.sh               # 控制器回环
./M2_can-loopback.sh silent        # 静默回环，不依赖对端 ACK
```

接线（SN65HVD230，3.3V）：

| 收发器 P1 | M2 40Pin |
|---|---|
| P1-1 CAN_TX | Pin16 |
| P1-2 CAN_RX | Pin18 |
| P1-3 3.3V | Pin17（只能接 3.3V） |
| P1-4 GND | Pin9 |

双板测试、波特率配置和排错见 [40Pin-CAN/README_M2.md](40Pin-CAN/README_M2.md)。
Android 镜像没有 can-utils，需要先把静态 `cantool` 放到 `/data/local/tmp/`。

## 6. 目录结构

```text
40Pin-test/
├── README.md                          本文件
├── _common.sh                         HAT 脚本共用函数库（必须保留）
├── 2.23inch-OLED-HAT/
│   ├── L1_2.23inch-OLED-HAT.sh
│   ├── M1_2.23inch-OLED-HAT.sh
│   ├── H1_2.23inch-OLED-HAT.sh
│   ├── M2_2.23inch-OLED-HAT.sh
│   ├── README_L1.md / README_M1.md / README_H1.md / README_M2.md
│   └── QPi_2.23inch_OLED_HAT_SDK/
│       ├── prebuilt/main_aarch64_static
│       └── python/                    例程与 drive 库
├── Environment-Sensor-HAT/
│   ├── L1_Environment-Sensor-HAT.sh
│   ├── M1_Environment-Sensor-HAT.sh
│   ├── H1_Environment-Sensor-HAT.sh
│   ├── M2_Environment-Sensor-HAT.sh
│   ├── README_L1.md / README_M1.md / README_H1.md / README_M2.md
│   └── QPi_Environment_Sensor_HAT_SDK/
│       ├── prebuilt/main_aarch64_static
│       └── python/                    例程与各传感器驱动
├── 40Pin-GPIO/
│   └── <板>_pin_control.sh            L1 / M1 / H1 / M2
├── 40Pin-PWM/
│   └── <板>_pwm-test.sh               L1 / M1 / M2（H1 暂无）
├── 40Pin-UART/
│   └── <板>_uart-loopback.sh          L1 / M1 / H1 / M2
└── 40Pin-CAN/
    ├── M2_can-loopback.sh
    └── README_M2.md
```

## 7. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| `_common.sh: No such file or directory` | 只推了单个脚本，目录层级丢了 | 推送整个 `40Pin-test`，保持 HAT 目录与 `_common.sh` 的相对位置 |
| Android 上 `./xxx.sh: not found` 或语法错误 | 脚本是 bash 语法，Android 只有 mksh | 用 `sh xxx.sh` 执行 |
| `Permission denied` | 脚本没有可执行位 | `chmod +x <脚本>`，或用 `sh <脚本>` |
| `gpioset/gpiomon: not found` | 板上没有 libgpiod | 预装 libgpiod（M2 可用 v1.6+，其他板型需 v2），或改用完整版在 Debian 上安装依赖 |
| Environment Sensor 提示跳过 Python | 没有 `smbus2` | 装 `python3-smbus2`；C 例程不受影响 |
| CAN `ip link set can0 up` 报 `Invalid argument` | 只配了 `bitrate`，没配 `dbitrate` | 用 `M2_can-loopback.sh`，脚本已按 CAN FD 配置 `bitrate` + `dbitrate` + `fd on` |

> 接线或更换外设前请先断开开发板电源，接好后再上电，避免带电插拔损坏外设或主板。
