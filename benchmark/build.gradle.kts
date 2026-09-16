import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
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