pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BUAA-Schedule"
include(":app")
include(":kyant-backdrop")
// 宏基准 / Baseline Profile 生成模块。已经接进 :app 了：:app 用
//   baselineProfile(project(":benchmark"))
// 消费它，`:app:generateBaselineProfile` 会驱动它去采集 profile（详见 docs/STATUS.md）。
//
// 但它仍然只在需要时手动执行，两个原因：
//   ① 采集要连一台 API 28+ 的设备/模拟器（模块 minSdk 28 是有意的），CI 没有机子，
//      所以不给它加生成 job；
//   ② 它依赖 androidx.benchmark 与真机，CI 的构建/仪器任务都用 `:app:` 前缀显式限定，
//      避免裸任务名被 Gradle 匹配到这里。
include(":benchmark")
