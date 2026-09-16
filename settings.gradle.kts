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
// 宏基准 / Baseline Profile 生成模块：只在需要时手动执行，例如
//   ./gradlew :benchmark:connectedCheck
// 它依赖 androidx.benchmark 与真机，因此 CI 的构建/仪器任务都用 :app: 前缀显式限定，
// 避免裸任务名被匹配到这里。
include(":benchmark")
