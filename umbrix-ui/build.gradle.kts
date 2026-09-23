plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    `java-library`
}

dependencies {
    api(project(":umbrix-core"))
    api(project(":umbrix-index"))

    implementation(compose.desktop.currentOs)
    runtimeOnly(compose.desktop.macos_arm64)
    runtimeOnly(compose.desktop.macos_x64)
    runtimeOnly(compose.desktop.linux_x64)
    implementation(compose.components.resources)
    implementation("org.jetbrains.jewel:jewel-int-ui-standalone-242:0.27.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    api("org.slf4j:slf4j-api:2.0.16")

    testImplementation(kotlin("test"))
}
