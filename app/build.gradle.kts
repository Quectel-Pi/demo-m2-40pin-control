import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").inputStream().use { load(it) }
}
val signingPropertiesFile = rootProject.file("signing.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.isFile) {
        signingPropertiesFile.inputStream().use { load(it) }
    }
}
val hasQuectelSigningProperties = signingPropertiesFile.isFile
val sdkDirectory = localProperties.getProperty("sdk.dir")
    ?.let { rootProject.file(it) }
    ?: file(System.getenv("ANDROID_HOME") ?: "")
val ndkVersion = "21.4.7075529"
val ndkHostTag = when {
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "windows-x86_64"
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "darwin-x86_64"
    else -> "linux-x86_64"
}
val ndkBin = sdkDirectory.resolve("ndk/$ndkVersion/toolchains/llvm/prebuilt/$ndkHostTag/bin")
val boardIoSource = layout.projectDirectory.file("src/main/cpp/board_io.c")
val boardIoOutput = layout.buildDirectory.file("generated/boardIoAssets/board-io")
boardIoOutput.get().asFile.parentFile.mkdirs()
val generatedAssetsDir = boardIoOutput.get().asFile.parentFile
val hatProjectDir = rootProject.file("demo-40Pin-HAT")
val sensorSdkDir = hatProjectDir.resolve("Environment-Sensor-HAT/QPi_Environment_Sensor_HAT_SDK/c")
val oledSdkDir = hatProjectDir.resolve("2.23inch-OLED-HAT/QPi_2.23inch_OLED_HAT_SDK/c")
val sensorHatSource = layout.projectDirectory.file("src/main/cpp/sensor_hat.c")
val oledHatSource = layout.projectDirectory.file("src/main/cpp/oled_hat.c")
val sensorSdkSources = fileTree(sensorSdkDir) {
    include(
        "lib/*.c", "lib/Config/*.c", "lib/TSL2591/*.c", "lib/SGP40/*.c",
        "lib/BME280/*.c", "lib/LTR390/*.c", "lib/IMU/*.c"
    )
}.files.sortedBy { it.absolutePath }
val oledSdkSources = fileTree(oledSdkDir) {
    include("lib/Config/*.c", "lib/OLED/*.c")
}.files.sortedBy { it.absolutePath }
val sensorIncludeDirs = listOf(
    "lib", "lib/Config", "lib/TSL2591", "lib/SGP40",
    "lib/BME280", "lib/LTR390", "lib/IMU"
).map { sensorSdkDir.resolve(it).absolutePath }
val oledIncludeDirs = listOf("lib/Config", "lib/OLED")
    .map { oledSdkDir.resolve(it).absolutePath }

val compileBoardIo by tasks.registering(Exec::class) {
    inputs.file(boardIoSource)
    outputs.file(boardIoOutput)
    val compiler = ndkBin.resolve("aarch64-linux-android26-clang")
    if (ndkHostTag.startsWith("windows")) {
        commandLine(
            "cmd", "/c", "$compiler.cmd", "-Wall", "-Wextra", "-Werror",
            "-fPIE", "-pie", "-O2", boardIoSource.asFile.absolutePath,
            "-o", boardIoOutput.get().asFile.absolutePath
        )
    } else {
        commandLine(
            compiler.absolutePath, "-Wall", "-Wextra", "-Werror",
            "-fPIE", "-pie", "-O2", boardIoSource.asFile.absolutePath,
            "-o", boardIoOutput.get().asFile.absolutePath
        )
    }
}

val sensorHatOutput = layout.buildDirectory.file("generated/boardIoAssets/sensor-io")
val compileSensorHat by tasks.registering(Exec::class) {
    inputs.files(sensorHatSource, sensorSdkSources)
    outputs.file(sensorHatOutput)
    val compiler = ndkBin.resolve("aarch64-linux-android26-clang")
    val arguments = listOf("-O2", "-fPIE", "-pie") +
        sensorIncludeDirs.flatMap { listOf("-I", it) } +
        listOf(sensorHatSource.asFile.absolutePath) +
        sensorSdkSources.map { it.absolutePath } +
        listOf("-lm", "-o", sensorHatOutput.get().asFile.absolutePath)
    if (ndkHostTag.startsWith("windows")) {
        commandLine("cmd", "/c", "$compiler.cmd", *arguments.toTypedArray())
    } else {
        commandLine(compiler.absolutePath, *arguments.toTypedArray())
    }
}

val oledHatOutput = layout.buildDirectory.file("generated/boardIoAssets/oled-io")
val compileOledHat by tasks.registering(Exec::class) {
    inputs.files(oledHatSource, oledSdkSources)
    outputs.file(oledHatOutput)
    val compiler = ndkBin.resolve("aarch64-linux-android26-clang")
    val arguments = listOf("-O2", "-fPIE", "-pie") +
        oledIncludeDirs.flatMap { listOf("-I", it) } +
        listOf(oledHatSource.asFile.absolutePath) +
        oledSdkSources.map { it.absolutePath } +
        listOf("-lm", "-o", oledHatOutput.get().asFile.absolutePath)
    if (ndkHostTag.startsWith("windows")) {
        commandLine("cmd", "/c", "$compiler.cmd", *arguments.toTypedArray())
    } else {
        commandLine(compiler.absolutePath, *arguments.toTypedArray())
    }
}

val copyHatLicense by tasks.registering(Copy::class) {
    from(hatProjectDir.resolve("LICENSE"))
    into(generatedAssetsDir)
    rename { "demo-40Pin-HAT-LICENSE.txt" }
}

android {
    namespace = "com.quectel.m2controldemo"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.quectel.m2controldemo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    ndkVersion = ndkVersion

    signingConfigs {
        if (hasQuectelSigningProperties) {
            create("quectel") {
                storeFile = rootProject.file(
                    signingProperties.getProperty("storeFile")
                        ?: error("Missing storeFile in signing.properties")
                )
                storePassword = signingProperties.getProperty("storePassword")
                    ?: error("Missing storePassword in signing.properties")
                keyAlias = signingProperties.getProperty("keyAlias")
                    ?: error("Missing keyAlias in signing.properties")
                keyPassword = signingProperties.getProperty("keyPassword")
                    ?: error("Missing keyPassword in signing.properties")
            }
        }
    }

    buildTypes {
        release {
            if (hasQuectelSigningProperties) {
                signingConfig = signingConfigs.getByName("quectel")
            }
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

android.sourceSets.getByName("main").assets.directories.add(
    boardIoOutput.get().asFile.parentFile.absolutePath
)

tasks.named("preBuild").configure {
    dependsOn(compileBoardIo, compileSensorHat, compileOledHat, copyHatLicense)
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}