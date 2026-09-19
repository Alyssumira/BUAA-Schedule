import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    // 同一个 wrapper 插件，在 com.android.test 模块上落到 producer 那一半：
    // 负责把本模块的 instrumented test 推到设备上跑、把采到的 profile 拉回
    // :app 的 app/src/main/baselineProfiles/。不应用它的话，:app 侧的
    // baselineProfile(project(":benchmark")) 会因为拿不到约定属性而配置失败。
    alias(libs.plugins.baselineprofile)
}

@Suppress("UnstableApiUsage")
android {
    namespace = "com.buaa.schedule.benchmark.test"
    compileSdk = 36

    defaultConfig {
        // 故意高于 :app 的 minSdk 26：宏基准与 Baseline Profile 生成要求 API 28+
        // （ProfileInstaller / 编译过滤在 26-27 上不可用）。这是有意的差异，不是笔误。
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

// producer 侧的配置（:app 那侧只有 consumer 配置，两边不是同一个扩展）
baselineProfile {
    // 只认**已连接**的设备/模拟器：本仓库不引入 Gradle Managed Device。
    // 一是 gmd 镜像要在这台机器上另下一份 system image 才能离线复现，
    // 二是真正的验收对照（HomeStartupBenchmark 的 None vs Partial）本来就要跑在
    // 编排者手里那台固定的机上，换设备测出来的启动时间没有可比性。
    // CI 也不跑这条：生成要连设备，而 android.yml 里没有可用机子（见 docs/STATUS.md）。
    useConnectedDevices = true
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 走版本目录，版本号与其余模块统一管理（此前这里是硬编码字符串，
    // 升级时容易漏掉本模块导致版本漂移）
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.junit)
    implementation(libs.androidx.uiautomator)
}