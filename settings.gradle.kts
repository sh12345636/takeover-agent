pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://api.xposed.info/")                      // de.robv.android.xposed:api:82 官方源
        maven("https://maven.aliyun.com/repository/public")    // jcenter 镜像兜底
    }
}
rootProject.name = "TakeoverAgent"
include(":app")
