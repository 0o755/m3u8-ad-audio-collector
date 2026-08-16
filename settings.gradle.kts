// 新采集器独立构建；本地开发仅通过复合构建替换 Probe 的公开 Maven 坐标。
pluginManagement {
    repositories {
        google()
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

rootProject.name = "m3u8-ad-audio-collector"
include(":app")

val localProbe = file("../m3u8-ad-audio-probe")
if (localProbe.isDirectory && file("${localProbe.path}/probe").isDirectory) {
    includeBuild(localProbe) {
        dependencySubstitution {
            // 默认聚合制品传递 runtime 与官方 adapter，与发布坐标保持同一依赖图。
            substitute(module("io.github.0o755:ad-audio-probe"))
                .using(project(":probe"))
        }
    }
}
