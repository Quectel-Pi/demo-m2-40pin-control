#include <fcntl.h>
#include <locale.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "DEV_Config.h"
#include "BME280.h"
#include "TSL2591.h"
#include "LTR390.h"
#include "SGP40.h"
#include "IMU.h"

extern void TSL2591_Set_Gain(UBYTE Gain);

static int suppress_sdk_stdout(void)
{
    int saved_stdout;
    int null_fd;

    fflush(stdout);
    saved_stdout = dup(STDOUT_FILENO);
    if (saved_stdout < 0)
        return -1;

    null_fd = open("/dev/null", O_WRONLY);
    if (null_fd < 0) {
        close(saved_stdout);
        return -1;
    }

    if (dup2(null_fd, STDOUT_FILENO) < 0) {
        close(null_fd);
        close(saved_stdout);
        return -1;
    }
    close(null_fd);
    return saved_stdout;
}

static int restore_stdout(int saved_stdout)
{
    int result;

    fflush(stdout);
    result = dup2(saved_stdout, STDOUT_FILENO);
    close(saved_stdout);
    clearerr(stdout);
    return result;
}

static int fail(const char *sensor, const char *stage)
{
    fprintf(stderr, "sensor_hat: %s %s failed\n", sensor, stage);
    return 1;
}

int main(void)
{
    int saved_stdout = -1;
    int result = 1;
    int value;
    int imu_available = 0;
    int voc_available = 1;
    unsigned long io_errors;
    const char *imu_status = "not_detected";
    const char *voc_status = "ok";
    UWORD lux;
    IMU_ST_ANGLES_DATA angles;
    IMU_ST_SENSOR_DATA gyro;
    IMU_ST_SENSOR_DATA accel;
    IMU_ST_SENSOR_DATA magn;

    setlocale(LC_NUMERIC, "C");
    saved_stdout = suppress_sdk_stdout();
    if (saved_stdout < 0) {
        fail("stdout", "setup");
        goto cleanup;
    }

    if (DEV_ModuleInit() != 0) {
        fail("Environment Sensor HAT", "module initialization");
        goto cleanup;
    }

    DEV_I2C_Set_SlaveAddress(BME280_ADDR);
    value = I2C_Read_Byte(0xD0);
    if (value != 0x60) {
        fail("BME280", "initialization");
        goto cleanup;
    }
    BME280_Init();

    DEV_I2C_Set_SlaveAddress(TSL2591_ADDRESS);
    value = I2C_Read_Byte(COMMAND_BIT | ID_REGISTER);
    if (value != 0x50 || TSL2591_Init() != 0) {
        fail("TSL2591", "initialization");
        goto cleanup;
    }
    TSL2591_Set_Gain(LOW_AGAIN);

    DEV_I2C_Set_SlaveAddress(LTR390_ADDR);
    value = I2C_Read_Byte(LTR390_PART_ID);
    if (value != 0xB2) {
        fail("LTR390", "initialization");
        goto cleanup;
    }
    LTR390_init();

    // The optional feature query and self-test are omitted; validate the
    // sensor with its actual measurement instead.
    SGP40_init();

    DEV_I2C_Set_SlaveAddress(I2C_ADD_ICM20948);
    if (ICM20948_Check()) {
        io_errors = I2C_Get_Error_Count();
        imuInit();
        if (I2C_Get_Error_Count() == io_errors) {
            imu_available = 1;
            imu_status = "ok";
        } else {
            imu_status = "initialization_failed";
        }
    }

    BME280_value();
    if (!isfinite(pres_raw[0]) || !isfinite(pres_raw[1]) ||
        !isfinite(pres_raw[2]) || pres_raw[0] <= 0.0 ||
        pres_raw[0] > 2000.0 || pres_raw[1] < -100.0 ||
        pres_raw[1] > 150.0 || pres_raw[2] < 0.0 ||
        pres_raw[2] > 100.0) {
        fprintf(stderr, "sensor_hat: BME280 read failed (pressure=%.2f, temperature=%.2f, humidity=%.2f)\n",
                pres_raw[0], pres_raw[1], pres_raw[2]);
        goto cleanup;
    }

    lux = TSL2591_Read_Lux();
    DEV_I2C_Set_SlaveAddress(TSL2591_ADDRESS);
    if (lux == UINT16_MAX ||
        I2C_Read_Byte(COMMAND_BIT | ID_REGISTER) != 0x50) {
        fail("TSL2591", "read");
        goto cleanup;
    }

    DEV_Delay_ms(2100);
    DEV_I2C_Set_SlaveAddress(LTR390_ADDR);
    value = I2C_Read_Byte(LTR390_MAIN_STATUS);
    if (value < 0 || (value & 0x08) == 0) {
        fail("LTR390", "read");
        goto cleanup;
    }
    UVS_value();
    if (uv > 0xFFFFF) {
        fail("LTR390", "read");
        goto cleanup;
    }

    if (voc_available) {
        io_errors = I2C_Get_Error_Count();
        SGP40_value();
        if (gas == UINT16_MAX || I2C_Get_Error_Count() != io_errors) {
            voc_available = 0;
            voc_status = "read_failed";
            gas = 0;
        }
    }

    if (imu_available) {
        io_errors = I2C_Get_Error_Count();
        imuDataGet(&angles, &gyro, &accel, &magn);
        if (I2C_Get_Error_Count() != io_errors) {
            imu_available = 0;
            imu_status = "read_failed";
        } else if (accel.s16X == 0 && accel.s16Y == 0 && accel.s16Z == 0) {
            imu_available = 0;
            imu_status = "read_failed";
        } else if (!isfinite(angles.fRoll) || !isfinite(angles.fPitch) ||
                   !isfinite(angles.fYaw) ||
                   (magn.s16X == 0 && magn.s16Y == 0 && magn.s16Z == 0)) {
            imu_status = "mag_unavailable";
        }
    }

    result = 0;

cleanup:
    DEV_ModuleExit();
    if (saved_stdout >= 0 && restore_stdout(saved_stdout) < 0) {
        fail("stdout", "restore");
        result = 1;
    }

    if (result == 0) {
        printf("pressure=%.2f\n", pres_raw[0]);
        printf("temperature=%.2f\n", pres_raw[1]);
        printf("humidity=%.2f\n", pres_raw[2]);
        printf("lux=%u\n", (unsigned int)lux);
        printf("uv=%u\n", (unsigned int)uv);
        printf("voc_status=%s\n", voc_status);
        if (voc_available)
            printf("voc=%u\n", (unsigned int)gas);
        printf("imu_status=%s\n", imu_status);
        if (imu_available) {
            printf("accel_x=%d\n", (int)accel.s16X);
            printf("accel_y=%d\n", (int)accel.s16Y);
            printf("accel_z=%d\n", (int)accel.s16Z);
            printf("gyro_x=%d\n", (int)gyro.s16X);
            printf("gyro_y=%d\n", (int)gyro.s16Y);
            printf("gyro_z=%d\n", (int)gyro.s16Z);
            if (strcmp(imu_status, "ok") == 0) {
                printf("roll=%.2f\n", angles.fRoll);
                printf("pitch=%.2f\n", angles.fPitch);
                printf("yaw=%.2f\n", angles.fYaw);
                printf("mag_x=%d\n", (int)magn.s16X);
                printf("mag_y=%d\n", (int)magn.s16Y);
                printf("mag_z=%d\n", (int)magn.s16Z);
            }
        }
        if (ferror(stdout)) {
            fprintf(stderr, "sensor_hat: output failed\n");
            result = 1;
        }
    }
    return result;
}
