plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":umbrix-plugin-api"))

    implementation(compose.desktop.currentOs)
    implementation(compose.components.resources)
    api("org.jetbrains.jewel:jewel-int-ui-standalone-242:0.27.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    testImplementation(kotlin("test"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "umbrix"
            artifactId = "umbrix-plugin-ui-api"
            version = project.version.toString()
        }
    }
}
