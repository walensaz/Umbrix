pluginManagement {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://packages.jetbrains.team/maven/p/kpm/public/")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://packages.jetbrains.team/maven/p/kpm/public/")
    }
}

rootProject.name = "umbrix"

include("umbrix-vfs")
include("umbrix-simd")
include("umbrix-index")
include("umbrix-core")
include("umbrix-mcp")
include("umbrix-ui")
include("umbrix-plugin-api")
include("umbrix-plugin-ui-api")
include("umbrix-app")

val jvmPluginDir = file("plugins/umbrix-plugin-jvm")
if (jvmPluginDir.resolve("build.gradle.kts").exists()) {
    include("umbrix-plugin-jvm")
    project(":umbrix-plugin-jvm").projectDir = jvmPluginDir
}
